package pipeline

import convolution.autoGridConvolve
import convolution.columnsConvolve
import convolution.defaultThreads
import convolution.pixelwiseConvolve
import convolution.rowsConvolve
import convolution.serialConvolve
import images.Bitmap
import images.readImage
import images.writeImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.io.File

/**
 * Streaming dataset pipeline (Task 3): read -> convolve -> write, fully overlapped.
 *
 * Parallelism model — one axis, the OS thread count:
 * - [PipelineConfig.numWorkers] convolution workers are pinned to a *dedicated*
 *   fixed thread-pool dispatcher of exactly `numWorkers` OS threads. Each worker
 *   owns one image at a time and, in the default `SERIAL` mode, convolves it on
 *   that single thread — zero nested coroutines. Cross-image parallelism already
 *   saturates the machine, so spawning extra per-image coroutines inside workers
 *   would only add scheduling overhead (and oversubscribe the pools).
 * - Nested tile-parallel modes (ROWS/COLUMNS/GRID) are exposed for images too
 *   large/unbalanced to benefit from cross-image parallelism; they use the
 *   convolution pools, deliberately *separate* from the worker pool, and are
 *   only sensible with `numWorkers == 1`.
 * - Readers/writers run on [Dispatchers.IO]: blocking file work must never
 *   occupy a convolution thread, and vice versa — that separation is what
 *   keeps the pipeline from being I/O-bound.
 *
 * I/O-bound avoidance (Task 3 requirements):
 * - One sequential reader keeps ahead of the workers whenever per-image I/O is
 *   cheaper than per-image convolution (true for non-trivial kernels). When the
 *   kernel is so light that reading becomes the bottleneck, [PipelineConfig.numReaders]
 *   / [PipelineConfig.numWriters] scale the I/O stages independently.
 * - Backpressure: [PipelineConfig.bufferSize] channel slots plus a shared
 *   in-flight semaphore cap resident images, so a fast reader can never fill
 *   memory when convolution or writing lags behind.
 * - Load balancing happens for free: workers pull completed images individually,
 *   so slow (I/O) or fast (image-size) variation never idles a convolution thread.
 */
enum class Mode { SERIAL, ROWS, COLUMNS, GRID, PIXELWISE }

data class Image(
    val bitmap: Bitmap,
    val name: String,
)

/**
 * Pipeline configuration.
 *
 * @param numReaders  parallel file readers (I/O bound, [Dispatchers.IO])
 * @param numWorkers  convolution workers == dedicated OS threads (CPU bound)
 * @param numWriters  parallel file writers (I/O bound, [Dispatchers.IO])
 * @param bufferSize  capacity of each inter-stage channel (backpressure / memory limit)
 * @param convThreads OS threads per image for the nested tile-parallel modes
 *                    (ignored in [Mode.SERIAL])
 */
data class PipelineConfig(
    val numReaders: Int = 1,
    val numWorkers: Int = defaultThreads,
    val numWriters: Int = 1,
    val bufferSize: Int = 8,
    val convThreads: Int = defaultThreads,
)

fun processSingleFile(
    file: File,
    outDir: File,
    kernel: Bitmap,
    mode: Mode = Mode.SERIAL,
    threads: Int = defaultThreads,
) {
    if (!outDir.exists()) outDir.mkdirs()
    val image = readImage(file.absolutePath)
    val result = runBlocking { convolveWithMode(image, kernel, mode, threads) }
    writeImage(result, File(outDir, "convolved_" + file.name).path)
}

fun processDataset(
    inDirectory: File,
    outDirectory: File,
    kernel: Bitmap,
    mode: Mode,
    bufferSize: Int,
) = processDataset(inDirectory, outDirectory, kernel, mode, PipelineConfig(bufferSize = bufferSize))

fun processDataset(
    inDirectory: File,
    outDirectory: File,
    kernel: Bitmap,
    mode: Mode,
    config: PipelineConfig,
) = runBlocking {
    if (!outDirectory.exists()) outDirectory.mkdirs()

    // Dedicated dispatcher for convolution: exactly `numWorkers` OS threads,
    // isolated from Dispatchers.Default and from the convolution tile pools.
    val workerDispatcher = newFixedThreadPoolContext(config.numWorkers.coerceAtLeast(1), "pipeline-workers")
    try {
        // Each in-flight image occupies one slot in each of the two channels;
        // the semaphore caps residency so readers cannot exhaust memory when
        // convolution or writing is the slow stage.
        val inFlight = Semaphore(config.bufferSize * 2)

        val readChannel = readDataset(config, inDirectory, inFlight)
        val writeChannel = processImages(config, workerDispatcher, readChannel, kernel, mode)
        writeDataset(config, outDirectory, writeChannel, inFlight)
    } finally {
        workerDispatcher.close()
    }
}

/**
 * Reads image files from [directory] into a buffered channel.
 * [inFlight] is acquired before each read and released by the writer after the
 * image is persisted, bounding memory to ~2 x bufferSize images.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun CoroutineScope.readDataset(
    config: PipelineConfig,
    directory: File,
    inFlight: Semaphore,
): ReceiveChannel<Image> =
    produce(capacity = config.bufferSize) {
        val files =
            directory
                .listFiles { f -> f.isFile }
                ?.sortedBy { it.name }
                ?: return@produce

        // coroutineScope waits for all readers before the channel closes.
        coroutineScope {
            (0 until config.numReaders).forEach { readerId ->
                launch(Dispatchers.IO) {
                    val slice = files.filterIndexed { idx, _ -> idx % config.numReaders == readerId }
                    for (file in slice) {
                        inFlight.acquire()
                        val bitmap = readImage(file.absolutePath)
                        send(Image(bitmap, file.name))
                    }
                }
            }
        }
    }

/**
 * `numWorkers` coroutines on a dedicated pool of `numWorkers` OS threads pull
 * decoded images, convolve them and forward results. In [Mode.SERIAL] (the
 * optimal default for datasets) no further coroutines are ever created.
 */
private fun CoroutineScope.processImages(
    config: PipelineConfig,
    workerDispatcher: CoroutineDispatcher,
    images: ReceiveChannel<Image>,
    kernel: Bitmap,
    mode: Mode,
): ReceiveChannel<Image> =
    produce(capacity = config.bufferSize) {
        coroutineScope {
            repeat(config.numWorkers) {
                launch(workerDispatcher) {
                    for (image in images) {
                        val result = convolveWithMode(image.bitmap, kernel, mode, config.convThreads)
                        send(Image(result, "convolved_" + image.name))
                    }
                }
            }
        }
    }

/**
 * Writes processed images in parallel on [Dispatchers.IO] and releases the
 * in-flight permits, letting the readers prefetch again.
 */
private suspend fun writeDataset(
    config: PipelineConfig,
    directory: File,
    images: ReceiveChannel<Image>,
    inFlight: Semaphore,
) = coroutineScope {
    repeat(config.numWriters) {
        launch(Dispatchers.IO) {
            for (image in images) {
                writeImage(image.bitmap, File(directory, image.name).path)
                inFlight.release()
            }
        }
    }
}

suspend fun convolveWithMode(
    image: Bitmap,
    kernel: Bitmap,
    mode: Mode,
    threads: Int = defaultThreads,
): Bitmap =
    when (mode) {
        Mode.SERIAL -> serialConvolve(image, kernel)
        Mode.PIXELWISE -> pixelwiseConvolve(image, kernel)
        Mode.COLUMNS -> columnsConvolve(image, kernel, threads)
        Mode.ROWS -> rowsConvolve(image, kernel, threads)
        Mode.GRID -> autoGridConvolve(image, kernel, threads)
    }

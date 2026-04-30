package pipeline

import convolution.allProcessorsConvolve
import convolution.columnsConvolve
import convolution.gridConvolve
import convolution.pixelwiseConvolve
import convolution.rowsConvolve
import convolution.serialConvolve
import images.Bitmap
import images.readImage
import images.writeImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.io.File

enum class Mode { SERIAL, PIXELWISE, COLUMNS, ROWS, ALLPROCESSORS, GRID }

data class Image(
    val bitmap: Bitmap,
    val name: String,
)

/**
 * Pipeline configuration.
 *
 * @param numReaders  number of parallel file readers (I/O bound)
 * @param numWorkers  number of parallel convolution workers (CPU bound)
 * @param numWriters  number of parallel file writers (I/O bound)
 * @param bufferSize  capacity of each inter-stage channel (backpressure / memory limit)
 */
data class PipelineConfig(
    val numReaders: Int = 1,
    val numWorkers: Int = Runtime.getRuntime().availableProcessors(),
    val numWriters: Int = 1,
    val bufferSize: Int = 8,
)

fun processSingleFile(
    file: File,
    outDir: File,
    kernel: Bitmap,
    mode: Mode,
) {
    if (!outDir.exists()) outDir.mkdirs()
    val image = readImage(file.absolutePath)
    val result = runBlocking { convolveWithMode(image, kernel, mode) }
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

    val inFlight = Semaphore(config.bufferSize * 2)

    val readChannel = readDataset(config, inDirectory, inFlight)
    val writeChannel = processImages(config, readChannel, kernel, mode)
    writeDataset(config, outDirectory, writeChannel, inFlight)
}

/**
 * Reads all image files from [directory] and sends them into a channel.
 * [inFlight] is acquired before each read; released by the writer after the image is fully written.
 *
 * Uses [config.numReaders] parallel I/O coroutines, each processing its own slice of the file list.
 * The channel is closed automatically when the producer block exits (even on exception).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun CoroutineScope.readDataset(
    config: PipelineConfig,
    directory: File,
    inFlight: Semaphore,
): ReceiveChannel<Image> =
    produce(capacity = config.bufferSize) {
        val files = directory.listFiles { f -> f.isFile }?.toList() ?: return@produce

        // coroutineScope waits for all reader coroutines before the produce block exits,
        // ensuring the channel is only closed after all files are sent.
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
 * Pulls images from [images], applies convolution, and sends results into a new channel.
 *
 * Uses exactly [config.numWorkers] worker coroutines for fan-out, bounding CPU parallelism.
 * Each worker iterates over [images] independently (Kotlin channels are safe for concurrent receive).
 * The channel is closed automatically when all workers finish.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun CoroutineScope.processImages(
    config: PipelineConfig,
    images: ReceiveChannel<Image>,
    kernel: Bitmap,
    mode: Mode,
): ReceiveChannel<Image> =
    produce(capacity = config.bufferSize) {
        coroutineScope {
            (0 until config.numWorkers).forEach {
                launch(Dispatchers.Default) {
                    for (image in images) {
                        val result = convolveWithMode(image.bitmap, kernel, mode)
                        send(Image(result, "convolved_" + image.name))
                    }
                }
            }
        }
    }

/**
 * Pulls processed images from [images] and writes them to [directory].
 *
 * Uses [config.numWriters] parallel I/O coroutines.
 * Releases [inFlight] after each image is written to disk, allowing readers to proceed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun writeDataset(
    config: PipelineConfig,
    directory: File,
    images: ReceiveChannel<Image>,
    inFlight: Semaphore,
) = coroutineScope {
    (0 until config.numWriters).forEach {
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
): Bitmap =
    when (mode) {
        Mode.SERIAL -> serialConvolve(image, kernel)
        Mode.PIXELWISE -> pixelwiseConvolve(image, kernel)
        Mode.COLUMNS -> columnsConvolve(image, kernel)
        Mode.ROWS -> rowsConvolve(image, kernel)
        Mode.ALLPROCESSORS -> allProcessorsConvolve(image, kernel)
        Mode.GRID -> gridConvolve(image, kernel)
    }

import convolution.autoGridConvolve
import convolution.columnsConvolve
import convolution.defaultThreads
import convolution.rowsConvolve
import convolution.serialConvolve
import convolution.shutdownConvolutionPools
import filters.BLUR
import images.Bitmap
import images.readImage
import images.writeImage
import kotlinx.coroutines.runBlocking
import pipeline.Mode
import pipeline.PipelineConfig
import pipeline.processDataset
import java.io.File
import java.nio.file.Paths

// Box blurs 11x11 (121 taps), 15x15 (225 taps), 21x21 (441 taps).
// Small images and 3x3 kernels are deliberately EXCLUDED from the benchmark:
// a run of ~1 ms is dominated by OS noise and clock quantization, which is the
// opposite of the point. Here every measured run carries >= ~50 ms (task 1) or
// >= ~10 ms (task 2, many threads) of real convolution work, so the signal to
// noise ratio of the thread scaling curves stays high.
val MID_KERNEL: Bitmap = Array(11) { DoubleArray(11) { 1.0 / 121 } }
val HEAVY_KERNEL: Bitmap = Array(15) { DoubleArray(15) { 1.0 / 225 } }
val EXTRA_HEAVY_KERNEL: Bitmap = Array(21) { DoubleArray(21) { 1.0 / 441 } }

// Shared image ladder (>= 1 MP, from app/src/test/resources).
val BENCH_IMAGES =
    listOf(
        "man.bmp" to "1024x1024",
        "Rainier.bmp" to "1920x1080",
        "Maltese.bmp" to "2238x2446",
    )

val BENCH_KERNELS =
    listOf(
        Triple("blur_11x11", MID_KERNEL, 121),
        Triple("blur_15x15", HEAVY_KERNEL, 225),
        Triple("blur_21x21", EXTRA_HEAVY_KERNEL, 441),
    )

fun loadBenchImages(resourcesDir: File): List<Pair<String, Bitmap>> =
    BENCH_IMAGES.mapNotNull { (name, _) ->
        val file = File(resourcesDir, name)
        if (!file.exists()) {
            println("Missing $name in $resourcesDir — skipping")
            null
        } else {
            name to readImage(file.absolutePath)
        }
    }

fun dimsOf(
    strategy: String,
    threads: Int,
    image: String,
    pixels: Long,
    kernel: String,
    taps: Int,
): List<Pair<String, String>> =
    listOf(
        "strategy" to strategy,
        "threads" to threads.toString(),
        "image" to image,
        "pixels" to pixels.toString(),
        "kernel" to kernel,
        "taps" to taps.toString(),
    )

fun runSerialBenchmark(
    resourcesDir: File,
    args: BenchArgs,
) {
    println("=== Task 1: Serial Convolution Benchmark ===")
    println("CPU threads available: ${Runtime.getRuntime().availableProcessors()}")
    println("Reps: ${args.reps} per config, interleaved (seed ${args.seed}), raw ns recorded\n")

    val images = loadBenchImages(resourcesDir)
    val jobs =
        images.flatMap { (imageName, image) ->
            val pixels = image.size.toLong() * image[0].size
            BENCH_KERNELS.map { (kernelName, kernel, taps) ->
                BenchJob(
                    dims = dimsOf("serial", 1, imageName, pixels, kernelName, taps),
                    warmup = { serialConvolve(image, kernel) },
                    measure = {
                        val t0 = System.nanoTime()
                        serialConvolve(image, kernel)
                        System.nanoTime() - t0
                    },
                )
            }
        }

    val csv =
        CsvSink(
            File(args.outDir, "benchmark_task1.csv"),
            listOf("strategy", "threads", "image", "pixels", "kernel", "taps"),
        )
    runMatrix(jobs, args.reps, args.seed, csv)
    writeRunMeta(args.outDir, args)
    println("Raw data (ns, long format) -> ${File(args.outDir, "benchmark_task1.csv").absolutePath}")
    println("Draw charts with: ./gradlew charts -Psrc=${args.outDir.invariantSeparatorsPath}")
}

fun runParallelBenchmark(
    resourcesDir: File,
    args: BenchArgs,
) {
    // The experiment: convolution speed as a function of the number of OS
    // threads it may use. Strategies do not differ in coroutine count — every
    // strategy launches exactly `threads` coroutines on a fixed pool of
    // `threads` OS threads; only the tile shape changes.
    //
    // Thread counts for a 14-physical-core chip (Ultra 5 225H, no SMT):
    //   1, 2, 4, 7, 8, 12, 14   -> scaling curve, 14 = all physical cores
    //   28                       -> 2x oversubscription control point
    val cores = defaultThreads
    val threadCounts = listOf(1, 2, 4, 7, 8, 12, 14, 28).filter { it <= 2 * cores }.distinct()
    // 11x11 and 15x15 keep even the t=28 runs above the noise floor; the
    // 21x21 kernel is exercised in Task 1 only (time budget of this matrix).
    val images = loadBenchImages(resourcesDir)
    val kernels = BENCH_KERNELS.take(2)

    val strategies: List<Pair<String, (Bitmap, Bitmap, Int) -> Unit>> =
        listOf(
            "rows" to { img, k, t -> runBlocking { rowsConvolve(img, k, t) } },
            "columns" to { img, k, t -> runBlocking { columnsConvolve(img, k, t) } },
            "autoGrid" to { img, k, t -> runBlocking { autoGridConvolve(img, k, t) } },
        )

    println("=== Task 2: Parallel Convolution — OS Thread Scaling ===")
    println("CPU cores: $cores | thread sweep: $threadCounts | reps: ${args.reps} (interleaved, raw ns)\n")

    val jobs =
        images.flatMap { (imageName, image) ->
            val pixels = image.size.toLong() * image[0].size
            kernels.flatMap { (kernelName, kernel, taps) ->
                buildList {
                    add(
                        BenchJob(
                            dims = dimsOf("serial", 1, imageName, pixels, kernelName, taps),
                            warmup = { serialConvolve(image, kernel) },
                            measure = {
                                val t0 = System.nanoTime()
                                serialConvolve(image, kernel)
                                System.nanoTime() - t0
                            },
                        ),
                    )
                    for ((sName, invoke) in strategies) {
                        for (t in threadCounts) {
                            add(
                                BenchJob(
                                    dims = dimsOf(sName, t, imageName, pixels, kernelName, taps),
                                    warmup = { invoke(image, kernel, t) },
                                    measure = {
                                        val t0 = System.nanoTime()
                                        invoke(image, kernel, t)
                                        System.nanoTime() - t0
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }

    val csv =
        CsvSink(
            File(args.outDir, "benchmark_task2.csv"),
            listOf("strategy", "threads", "image", "pixels", "kernel", "taps"),
        )
    runMatrix(jobs, args.reps, args.seed, csv)
    writeRunMeta(args.outDir, args)
    shutdownConvolutionPools()
    println("Raw data -> ${File(args.outDir, "benchmark_task2.csv").absolutePath}")
}

// ---------------------------------------------------------------------------
// Task 3: streaming pipeline (read -> convolve -> write) over a dataset
// ---------------------------------------------------------------------------

/**
 * Curated dataset of large images (~14 MP total): few files so one full pass
 * is a single long timed unit (>= ~1.3 s of convolution work at 1 worker with
 * the heavy kernel), large files so per-pass OS noise stays in the low %.
 */
private fun prepareDataset(resourcesDir: File): Pair<File, Double> {
    val wanted =
        listOf(
            "airplaneU2.bmp",
            "brickwall.bmp",
            "carpet.bmp",
            "man.bmp",
            "Rainier.bmp",
            "Maltese.bmp",
            "textureA.bmp",
            "textureB.bmp",
        )
    val dir = File(repoRoot(), "build/benchmark_dataset")
    dir.deleteRecursively()
    dir.mkdirs()
    var megapixels = 0.0
    for (name in wanted) {
        val src = File(resourcesDir, name)
        if (!src.exists()) continue
        src.copyTo(File(dir, name), overwrite = true)
        val img = readImage(src.absolutePath)
        megapixels += img.size.toDouble() * img[0].size / 1e6
    }
    return dir to megapixels
}

fun runPipelineBenchmark(
    resourcesDir: File,
    args: BenchArgs,
) {
    val cores = defaultThreads
    val (datasetDir, megapixels) = prepareDataset(resourcesDir)
    val outputDir = File(repoRoot(), "build/benchmark_output").also { it.mkdirs() }
    val files = datasetDir.listFiles { f -> f.isFile }?.toList() ?: emptyList()

    println("=== Task 3: Pipeline Benchmark ===")
    println("Dataset : ${files.size} images, ${"%.1f".format(megapixels)} MP total")
    println("CPU     : $cores OS threads; workers pinned to a dedicated pool of `workers` threads")
    println("Reps    : 1 warmup + ${args.reps} measured per config, interleaved (seed ${args.seed}), raw ns\n")

    val mp = megapixels.toString()

    fun passDims(
        scenario: String,
        label: String,
        kernelName: String,
        mode: Mode,
        cfg: PipelineConfig,
    ) = listOf(
        "scenario" to scenario,
        "label" to label,
        "kernel" to kernelName,
        "mode" to mode.name,
        "readers" to cfg.numReaders.toString(),
        "workers" to cfg.numWorkers.toString(),
        "writers" to cfg.numWriters.toString(),
        "buffer" to cfg.bufferSize.toString(),
        "conv_threads" to cfg.convThreads.toString(),
        "dataset_mp" to mp,
    )

    fun passJob(
        scenario: String,
        label: String,
        kernelName: String,
        kernel: Bitmap,
        mode: Mode,
        cfg: PipelineConfig,
    ) = BenchJob(
        dims = passDims(scenario, label, kernelName, mode, cfg),
        warmup = { processDataset(datasetDir, outputDir, kernel, mode, cfg) },
        measure = {
            val t0 = System.nanoTime()
            processDataset(datasetDir, outputDir, kernel, mode, cfg)
            System.nanoTime() - t0
        },
    )

    val workerCounts = listOf(1, 2, 4, 8, 12, 14).filter { it <= cores }.distinct()
    val jobs = mutableListOf<BenchJob>()

    fun stageDims(
        label: String,
        readers: Int,
        workers: Int,
        writers: Int,
    ) = listOf(
        "scenario" to "stages",
        "label" to label,
        "kernel" to "io",
        "mode" to "n/a",
        "readers" to readers.toString(),
        "workers" to workers.toString(),
        "writers" to writers.toString(),
        "buffer" to "1",
        "conv_threads" to "1",
        "dataset_mp" to mp,
    )

    // -- Stage budget: what each pipeline stage costs on its own --
    jobs.add(
        BenchJob(
            dims = stageDims("read+decode dataset", readers = 1, workers = 1, writers = 0),
            warmup = { files.forEach { readImage(it.absolutePath) } },
            measure = {
                val t0 = System.nanoTime()
                files.forEach { readImage(it.absolutePath) }
                System.nanoTime() - t0
            },
        ),
    )
    val loaded = files.map { readImage(it.absolutePath) to "convolved_" + it.name }
    jobs.add(
        BenchJob(
            dims = stageDims("encode+write dataset", readers = 0, workers = 1, writers = 1),
            warmup = { loaded.forEach { writeImage(it.first, File(outputDir, it.second).path) } },
            measure = {
                val t0 = System.nanoTime()
                loaded.forEach { writeImage(it.first, File(outputDir, it.second).path) }
                System.nanoTime() - t0
            },
        ),
    )

    // -- Scenarios 1/2: worker sweeps for the heavy and light kernels --
    for (w in workerCounts) {
        jobs.add(passJob("workers_heavy", "workers=$w", "heavy_15x15", HEAVY_KERNEL, Mode.SERIAL, PipelineConfig(numWorkers = w)))
        jobs.add(passJob("workers_light", "workers=$w", "light_3x3", BLUR, Mode.SERIAL, PipelineConfig(numWorkers = w)))
    }

    // -- Scenario 3: escaping the I/O bound: scale readers/writers --
    jobs.add(
        passJob(
            "io_scaling",
            "light ${cores}w 2r/2w",
            "light_3x3",
            BLUR,
            Mode.SERIAL,
            PipelineConfig(numReaders = 2, numWorkers = cores, numWriters = 2),
        ),
    )
    jobs.add(
        passJob(
            "io_scaling",
            "light ${cores}w 3r/3w",
            "light_3x3",
            BLUR,
            Mode.SERIAL,
            PipelineConfig(numReaders = 3, numWorkers = cores, numWriters = 3),
        ),
    )
    jobs.add(
        passJob(
            "io_scaling",
            "heavy ${cores}w 2r/2w",
            "heavy_15x15",
            HEAVY_KERNEL,
            Mode.SERIAL,
            PipelineConfig(numReaders = 2, numWorkers = cores, numWriters = 2),
        ),
    )

    // -- Scenario 4: nested per-image parallelism vs cross-image only --
    jobs.add(
        passJob(
            "nested",
            "1w ROWS t=$cores (nested)",
            "heavy_15x15",
            HEAVY_KERNEL,
            Mode.ROWS,
            PipelineConfig(numWorkers = 1, convThreads = cores),
        ),
    )
    jobs.add(
        passJob(
            "nested",
            "4w ROWS t=4 (private pools)",
            "heavy_15x15",
            HEAVY_KERNEL,
            Mode.ROWS,
            PipelineConfig(numWorkers = 4, convThreads = 4),
        ),
    )
    jobs.add(
        passJob(
            "nested",
            "${cores}w ROWS t=$cores (shared pool)",
            "heavy_15x15",
            HEAVY_KERNEL,
            Mode.ROWS,
            PipelineConfig(numWorkers = cores, convThreads = cores),
        ),
    )

    // -- Scenario 5: channel buffer size --
    for (buf in listOf(1, 2, 4, 8, 16, 32)) {
        jobs.add(
            passJob(
                "buffer",
                "heavy ${cores}w buf=$buf",
                "heavy_15x15",
                HEAVY_KERNEL,
                Mode.SERIAL,
                PipelineConfig(numWorkers = cores, bufferSize = buf),
            ),
        )
        jobs.add(
            passJob(
                "buffer",
                "light ${cores}w buf=$buf",
                "light_3x3",
                BLUR,
                Mode.SERIAL,
                PipelineConfig(numWorkers = cores, bufferSize = buf),
            ),
        )
    }

    val csv =
        CsvSink(
            File(args.outDir, "benchmark_task3.csv"),
            listOf("scenario", "label", "kernel", "mode", "readers", "workers", "writers", "buffer", "conv_threads", "dataset_mp"),
        )
    runMatrix(jobs, args.reps, args.seed, csv)
    writeRunMeta(args.outDir, args)
    shutdownConvolutionPools()
    println("Raw data -> ${File(args.outDir, "benchmark_task3.csv").absolutePath}")
}

fun main(args: Array<String>) {
    val workDir = Paths.get("").toAbsolutePath()
    val resourcesDir =
        listOf(
            workDir.resolve("src/test/resources").toFile(),
            workDir.resolve("app/src/test/resources").toFile(),
            workDir.parent?.resolve("app/src/test/resources")?.toFile(),
        ).filterNotNull().firstOrNull { it.exists() }
            ?: error("Resources dir not found under $workDir")

    val docsDir = File(repoRoot(), "docs").also { it.mkdirs() }

    val taskToken = args.firstOrNull()?.takeIf { !it.startsWith("--") } ?: "serial"
    val defaultReps =
        when (taskToken) {
            "parallel" -> 9
            "pipeline" -> 5
            else -> 7
        }
    val parsed = parseBenchArgs(args, defaultReps = defaultReps, fallbackOut = docsDir)

    when (parsed.task) {
        "serial" -> runSerialBenchmark(resourcesDir, parsed)
        "parallel" -> runParallelBenchmark(resourcesDir, parsed)
        "pipeline" -> runPipelineBenchmark(resourcesDir, parsed)
        else -> error("Unknown benchmark task: '${parsed.task}'. Available: serial, parallel, pipeline")
    }
}

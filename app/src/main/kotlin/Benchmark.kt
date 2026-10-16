import convolution.autoGridConvolve
import convolution.columnsConvolve
import convolution.defaultThreads
import convolution.rowsConvolve
import convolution.serialConvolve
import convolution.shutdownConvolutionPools
import images.Bitmap
import images.readImage
import kotlinx.coroutines.runBlocking
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
    val parsed = parseBenchArgs(args, defaultReps = if (taskToken == "parallel") 9 else 7, fallbackOut = docsDir)

    when (parsed.task) {
        "serial" -> runSerialBenchmark(resourcesDir, parsed)
        "parallel" -> runParallelBenchmark(resourcesDir, parsed)
        else -> error("Unknown benchmark task: '${parsed.task}'. Available: serial, parallel")
    }
}

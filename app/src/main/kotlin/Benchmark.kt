import convolution.serialConvolve
import images.Bitmap
import images.readImage
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

    val parsed = parseBenchArgs(args, defaultReps = 7, fallbackOut = docsDir)

    when (parsed.task) {
        "serial" -> runSerialBenchmark(resourcesDir, parsed)
        else -> error("Unknown benchmark task: '${parsed.task}'. Available: serial")
    }
}

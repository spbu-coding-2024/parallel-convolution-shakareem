import convolution.allProcessorsConvolve
import convolution.columnsConvolve
import convolution.gridConvolve
import convolution.pixelwiseConvolve
import convolution.rowsConvolve
import convolution.serialConvolve
import filters.BLUR
import filters.EDGE
import filters.SHARPEN
import images.Bitmap
import images.readImage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Paths
import kotlin.system.measureTimeMillis

// 11x11 box blur — compute-heavy
val HEAVY_KERNEL = Array(11) { DoubleArray(11) { 1.0 / 121 } }

fun benchSerial(
    image: Bitmap,
    kernel: Bitmap,
    repeats: Int,
): List<Long> {
    val times = mutableListOf<Long>()
    repeat(repeats) { times.add(measureTimeMillis { serialConvolve(image, kernel) }) }
    return times
}

fun runSerialBenchmark(
    resourcesDir: File,
    docsDir: File,
) {
    val repeats = 5

    val testImages =
        listOf(
            "cameraman.bmp" to "256x256",
            "lenna.bmp" to "512x512",
            "man.bmp" to "1024x1024",
            "girl.bmp" to "576x720",
        )

    val kernels =
        mapOf(
            "blur_3x3" to BLUR,
            "sharpen_3x3" to SHARPEN,
            "edge_3x3" to EDGE,
            "blur_11x11" to HEAVY_KERNEL,
        )

    data class Row(
        val image: String,
        val kernel: String,
        val avgMs: Long,
        val minMs: Long,
        val maxMs: Long,
        val times: List<Long>,
    )

    val rows = mutableListOf<Row>()

    println("=== Task 1: Serial Convolution Benchmark ===\n")

    for ((imageName, sizeLabel) in testImages) {
        val file = File(resourcesDir, imageName)
        if (!file.exists()) {
            println("Skipping $imageName")
            continue
        }
        val image = readImage(file.absolutePath)
        println("Image: $imageName ($sizeLabel)")

        for ((kernelName, kernel) in kernels) {
            val times = benchSerial(image, kernel, repeats)
            val avg = times.average().toLong()
            val min = times.min()
            val max = times.max()
            println("  %-15s avg=%4dms  min=%4dms  max=%4dms".format(kernelName, avg, min, max))
            rows.add(Row(imageName, kernelName, avg, min, max, times))
        }
        println()
    }

    val csvFile = File(docsDir, "benchmark_task1.csv")
    csvFile.printWriter().use { out ->
        out.println("strategy,image,kernel,avg_ms,min_ms,max_ms,run1_ms,run2_ms,run3_ms,run4_ms,run5_ms")
        for (r in rows) {
            out.println("serial,${r.image},${r.kernel},${r.avgMs},${r.minMs},${r.maxMs},${r.times.joinToString(",")}")
        }
    }
    println("CSV saved to ${csvFile.absolutePath}")
}

fun runParallelBenchmark(
    resourcesDir: File,
    docsDir: File,
) {
    val repeats = 5
    val cores = Runtime.getRuntime().availableProcessors()

    val testImages =
        listOf(
            "cameraman.bmp" to "256x256",
            "lenna.bmp" to "512x512",
            "man.bmp" to "1024x1024",
        )

    val kernels =
        mapOf(
            "blur_3x3" to BLUR,
            "blur_11x11" to HEAVY_KERNEL,
        )

    data class Row(
        val strategy: String,
        val image: String,
        val kernel: String,
        val avgMs: Long,
        val minMs: Long,
        val maxMs: Long,
        val times: List<Long>,
    )

    val rows = mutableListOf<Row>()

    println("=== Task 2: Parallel Convolution Benchmark ===")
    println("CPU cores: $cores\n")

    for ((imageName, sizeLabel) in testImages) {
        val file = File(resourcesDir, imageName)
        if (!file.exists()) {
            println("Skipping $imageName")
            continue
        }
        val image = readImage(file.absolutePath)
        println("Image: $imageName ($sizeLabel)")

        for ((kernelName, kernel) in kernels) {
            println("  Kernel: $kernelName")

            val strategies: List<Pair<String, (Bitmap, Bitmap) -> Unit>> =
                listOf(
                    "serial" to { img, k -> serialConvolve(img, k) },
                    "rows" to { img, k -> runBlocking { rowsConvolve(img, k) } },
                    "columns" to { img, k -> runBlocking { columnsConvolve(img, k) } },
                    "grid_2x2" to { img, k -> runBlocking { gridConvolve(img, k, 2, 2) } },
                    "grid_4x4" to { img, k -> runBlocking { gridConvolve(img, k, 4, 4) } },
                    "allProcessors" to { img, k -> runBlocking { allProcessorsConvolve(img, k) } },
                    "pixelwise" to { img, k ->
                        if (img.size <= 256) {
                            runBlocking { pixelwiseConvolve(img, k) }
                        } else {
                            serialConvolve(img, k)
                        }
                    },
                )

            var serialAvg = 0L
            for ((stratName, block) in strategies) {
                val times = mutableListOf<Long>()
                repeat(repeats) { times.add(measureTimeMillis { block(image, kernel) }) }
                val avg = times.average().toLong()
                val min = times.min()
                val max = times.max()
                if (stratName == "serial") serialAvg = avg
                val speedupStr =
                    if (stratName != "serial" && serialAvg > 0) {
                        "  speedup=${"%.2f".format(serialAvg.toDouble() / avg)}x"
                    } else {
                        ""
                    }
                println("    %-15s avg=%4dms  min=%4dms  max=%4dms$speedupStr".format(stratName, avg, min, max))
                rows.add(Row(stratName, imageName, kernelName, avg, min, max, times))
            }
            println()
        }
    }

    val csvFile = File(docsDir, "benchmark_task2.csv")
    csvFile.printWriter().use { out ->
        out.println("strategy,image,kernel,avg_ms,min_ms,max_ms,run1_ms,run2_ms,run3_ms,run4_ms,run5_ms")
        for (r in rows) {
            out.println("${r.strategy},${r.image},${r.kernel},${r.avgMs},${r.minMs},${r.maxMs},${r.times.joinToString(",")}")
        }
    }
    println("CSV saved to ${csvFile.absolutePath}")
}

fun main(args: Array<String>) {
    val task = args.firstOrNull() ?: "serial"

    val workDir = Paths.get("").toAbsolutePath()
    val resourcesDir =
        listOf(
            workDir.resolve("src/test/resources").toFile(),
            workDir.resolve("app/src/test/resources").toFile(),
            workDir.parent?.resolve("app/src/test/resources")?.toFile(),
        ).filterNotNull().firstOrNull { it.exists() }
            ?: error("Resources dir not found under $workDir")

    val docsDir =
        listOf(
            workDir.resolve("../docs").toFile(),
            workDir.resolve("docs").toFile(),
        ).firstOrNull { it.exists() } ?: workDir.resolve("../docs").toFile().also { it.mkdirs() }

    when (task) {
        "serial" -> runSerialBenchmark(resourcesDir, docsDir)
        "parallel" -> runParallelBenchmark(resourcesDir, docsDir)
        else -> error("Unknown benchmark task: '$task'. Available: serial, parallel")
    }
}

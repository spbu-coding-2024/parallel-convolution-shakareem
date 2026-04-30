import convolution.serialConvolve
import filters.BLUR
import filters.EDGE
import filters.SHARPEN
import images.Bitmap
import images.readImage
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
        else -> error("Unknown benchmark task: '$task'. Available: serial")
    }
}

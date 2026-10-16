import convolution.serialConvolve
import filters.BLUR
import filters.EDGE
import images.Bitmap
import images.readImage
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.CategoryChart
import org.knowm.xchart.CategoryChartBuilder
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.style.Styler
import java.io.File
import java.nio.file.Paths

// Box blurs 11x11 (121 taps) and 15x15 (225 taps). Long runs make OS scheduling
// noise small relative to the useful convolution work; they also keep the whole
// working set (image + kernel) in L2/L3 so we measure arithmetic throughput.
val MID_KERNEL: Bitmap = Array(11) { DoubleArray(11) { 1.0 / 121 } }
val HEAVY_KERNEL: Bitmap = Array(15) { DoubleArray(15) { 1.0 / 225 } }

data class BenchRow(
    val strategy: String,
    val image: String,
    val sizeLabel: String,
    val pixels: Long,
    val kernel: String,
    val taps: Int,
    val avgMs: Double,
    val minMs: Double,
    val maxMs: Double,
    val times: List<Double>,
)

/** Time [block] in milliseconds with nanosecond-resolution clock (sub-ms runs). */
fun timedMs(block: () -> Unit): Double {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000.0
}

fun benchSerial(
    image: Bitmap,
    kernel: Bitmap,
    repeats: Int,
): List<Double> {
    val times = mutableListOf<Double>()
    repeat(repeats) { times.add(timedMs { serialConvolve(image, kernel) }) }
    return times
}

fun runSerialBenchmark(
    resourcesDir: File,
    docsDir: File,
) {
    // One warmup (JIT + page cache) then a fixed number of measured runs.
    // A heavy kernel keeps every measured run >= ~100 ms, so the relative
    // impact of OS noise is small and 5 runs are enough for a stable average.
    val repeats = 5

    val testImages =
        listOf(
            "cameraman.bmp" to "256x256",
            "lenna.bmp" to "512x512",
            "man.bmp" to "1024x1024",
            "Rainier.bmp" to "1920x1080",
        )

    val kernels =
        listOf(
            Triple("blur_3x3", BLUR, 9),
            Triple("edge_3x3", EDGE, 9),
            Triple("blur_11x11", MID_KERNEL, 121),
            Triple("blur_15x15", HEAVY_KERNEL, 225),
        )

    val rows = mutableListOf<BenchRow>()

    println("=== Task 1: Serial Convolution Benchmark ===")
    println("CPU threads available: ${Runtime.getRuntime().availableProcessors()}\n")

    for ((imageName, sizeLabel) in testImages) {
        val file = File(resourcesDir, imageName)
        if (!file.exists()) {
            println("Skipping $imageName")
            continue
        }
        val image = readImage(file.absolutePath)
        val pixels = image.size.toLong() * image[0].size
        println("Image: $imageName ($sizeLabel)")

        for ((kernelName, kernel, taps) in kernels) {
            serialConvolve(image, kernel) // warmup, not measured
            val times = benchSerial(image, kernel, repeats)
            val avg = times.average()
            val min = times.min()
            val max = times.max()
            println("  %-12s avg=%8.2fms  min=%8.2fms  max=%8.2fms".format(kernelName, avg, min, max))
            rows.add(
                BenchRow("serial", imageName, sizeLabel, pixels, kernelName, taps, avg, min, max, times),
            )
        }
        println()
    }

    val csvFile = File(docsDir, "benchmark_task1.csv")
    csvFile.printWriter().use { out ->
        out.println("strategy,image,kernel,avg_ms,min_ms,max_ms,run1_ms,run2_ms,run3_ms,run4_ms,run5_ms")
        for (r in rows) {
            out.println(
                "${r.strategy},${r.image},${r.kernel},${"%.2f".format(r.avgMs)},${"%.2f".format(r.minMs)},${"%.2f".format(r.maxMs)}," +
                    r.times.joinToString(",") { t -> "%.2f".format(t) },
            )
        }
    }
    println("CSV saved to ${csvFile.absolutePath}")

    saveTask1Charts(rows, File(docsDir, "charts").also { it.mkdirs() })
}

private fun saveTask1Charts(
    rows: List<BenchRow>,
    chartsDir: File,
) {
    val images = rows.map { it.image }.distinct()
    val kernels = rows.map { it.kernel }.distinct()

    // 1. Scaling law: average time vs image size (log-log axes, one line per
    //    kernel). Straight lines confirm the O(pixels x taps) complexity.
    lineChart(
        title = "Task 1: serial convolution time vs image size",
        xAxis = "image size, megapixels (log)",
        yAxis = "avg time, ms (log)",
        series =
            kernels.map { k ->
                k to
                    rows.filter { it.kernel == k }.map { r -> r.pixels / 1_000_000.0 to r.avgMs }
            },
        logX = true,
        logY = true,
        file = File(chartsDir, "task1_scaling.png"),
    )

    // 2. Hardware throughput: ns spent per (pixel x kernel tap). Flat lines
    //    mean the serial core runs at constant throughput, i.e. the measured
    //    differences are real work, not scheduling noise.
    lineChart(
        title = "Task 1: throughput per kernel tap",
        xAxis = "kernel taps",
        yAxis = "ns per (pixel x tap)",
        series =
            images.map { img ->
                img to
                    rows.filter { it.image == img }.map { r -> r.taps.toDouble() to r.avgMs * 1_000_000.0 / (r.pixels * r.taps) }
            },
        logX = true,
        logY = false,
        file = File(chartsDir, "task1_ns_per_tap.png"),
    )

    // 3. Relative run-to-run spread (max-min)/avg. It shrinks as the work per
    //    run grows, which justifies the heavy-kernel / large-image benchmark
    //    design over many runs of tiny images.
    columnChart(
        title = "Task 1: relative run-to-run spread (max-min)/avg",
        xAxis = "image",
        yAxis = "relative spread, %",
        categories = images,
        series =
            kernels.map { k ->
                k to
                    images.map { img ->
                        val r = rows.first { it.image == img && it.kernel == k }
                        if (r.avgMs > 0) 100.0 * (r.maxMs - r.minMs) / r.avgMs else 0.0
                    }
            },
        file = File(chartsDir, "task1_run_spread.png"),
    )

    println("Charts saved to ${chartsDir.absolutePath}")
}

private fun lineChart(
    title: String,
    xAxis: String,
    yAxis: String,
    series: List<Pair<String, List<Pair<Double, Double>>>>,
    logX: Boolean,
    logY: Boolean,
    file: File,
) {
    val chart =
        XYChartBuilder()
            .width(1000)
            .height(600)
            .theme(Styler.ChartTheme.XChart)
            .title(title)
            .xAxisTitle(xAxis)
            .yAxisTitle(yAxis)
            .build()
    chart.styler.setLegendPosition(Styler.LegendPosition.OutsideE)
    chart.styler.setMarkerSize(6)
    if (logX) chart.styler.setXAxisLogarithmic(true)
    if (logY) chart.styler.setYAxisLogarithmic(true)
    for ((name, points) in series) {
        chart.addSeries(name, points.map { it.first }, points.map { it.second })
    }
    BitmapEncoder.saveBitmap(chart, file.absolutePath.removeSuffix(".png"), BitmapEncoder.BitmapFormat.PNG)
}

private fun columnChart(
    title: String,
    xAxis: String,
    yAxis: String,
    categories: List<String>,
    series: List<Pair<String, List<Double>>>,
    file: File,
) {
    val chart: CategoryChart =
        CategoryChartBuilder()
            .width(1000)
            .height(600)
            .theme(Styler.ChartTheme.XChart)
            .title(title)
            .xAxisTitle(xAxis)
            .yAxisTitle(yAxis)
            .build()
    chart.styler.setLegendPosition(Styler.LegendPosition.OutsideE)
    series.forEach { (name, values) -> chart.addSeries(name, categories, values) }
    BitmapEncoder.saveBitmap(chart, file.absolutePath.removeSuffix(".png"), BitmapEncoder.BitmapFormat.PNG)
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

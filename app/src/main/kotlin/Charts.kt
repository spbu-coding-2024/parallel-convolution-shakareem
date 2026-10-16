import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.BoxChart
import org.knowm.xchart.BoxChartBuilder
import org.knowm.xchart.XYChart
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.style.Styler
import java.io.File

/**
 * Offline analysis + chart generation for the raw benchmark data.
 *
 * Usage: ./gradlew charts -Psrc=docs/bench/<stamp>
 * (with no -Psrc, uses docs/bench).
 *
 * Reads long-format `benchmark_taskN.csv` files (one row per measurement:
 * dims..., rep, ns) from [src] — either a single run directory or a parent
 * directory containing several run subdirectories, which are then POOLED.
 * Computes robust statistics (median, IQR, bootstrap 95% CI of the median)
 * per configuration, writes `benchmark_stats_taskN.csv` next to the charts,
 * and renders all PNGs into docs/charts/.
 *
 * The tool is deterministic: same inputs + fixed bootstrap seed -> same
 * outputs, so charts can be regenerated over archived raw data later.
 */

data class Config(
    val dims: List<Pair<String, String>>,
    val samples: MutableList<Long> = mutableListOf(),
) {
    val key: String
        get() = dims.joinToString("/") { it.second }

    fun dim(name: String): String = dims.first { it.first == name }.second
}

private fun parseCsv(
    file: File,
    into: LinkedHashMap<String, Config>,
) {
    val lines = file.readLines().filter { it.isNotBlank() }
    require(lines.size > 1) { "empty csv $file" }
    val header = lines[0].split(",")
    val nsIdx = header.indexOf("ns").also { require(it >= 0) { "no ns column in $file" } }
    val repIdx = header.indexOf("rep")
    val dimNames = header.mapIndexed { i, n -> n to i }.filter { it.first !in listOf("ns", "rep") }
    for (line in lines.drop(1)) {
        val cells = line.split(",")
        val dims = dimNames.map { (n, i) -> n to cells[i] }
        val key = dims.joinToString("/") { it.second }
        into.getOrPut(key) { Config(dims) }.samples.add(cells[nsIdx].toLong())
    }
    require(repIdx >= 0)
}

private fun collectConfigs(
    runDirs: List<File>,
    fileName: String,
): LinkedHashMap<String, Config> {
    val configs = LinkedHashMap<String, Config>()
    for (dir in runDirs) {
        val csv = File(dir, fileName)
        if (csv.exists() && csv.length() > 0) parseCsv(csv, configs)
    }
    return configs
}

private fun hasData(f: File) =
    f.isDirectory && f.listFiles { x -> x.name.startsWith("benchmark_task") && x.extension == "csv" }?.isNotEmpty() == true

private fun findRunDirs(src: File): List<File> =
    when {
        hasData(src) -> listOf(src)
        else -> src.listFiles()?.filter { hasData(it) }?.sortedBy { it.name } ?: emptyList()
    }

private fun ciHalfWidthNs(values: List<Long>): Double {
    val (lo, hi) = Stats.bootstrapMedianCi(values)
    val m = Stats.median(values)
    return maxOf(hi - m, m - lo)
}

/** Writes one stats row per config: dims..., n, median_ns, q1, q3, min, max, ci +/-, spread. */
private fun writeStats(
    configs: Collection<Config>,
    out: File,
) {
    if (configs.isEmpty()) return
    out.printWriter().use { w ->
        val first = configs.first()
        w.println(first.dims.joinToString(",") { it.first } + ",n,median_ms,q1_ms,q3_ms,min_ms,max_ms,ci95_half_ms,spread_pct")
        for (c in configs) {
            val ci = ciHalfWidthNs(c.samples)
            val row =
                c.dims.map { it.second } +
                    listOf(
                        c.samples.size.toString(),
                        "%.3f".format(Stats.median(c.samples) / 1e6),
                        "%.3f".format(Stats.quantile(c.samples, 0.25) / 1e6),
                        "%.3f".format(Stats.quantile(c.samples, 0.75) / 1e6),
                        "%.3f".format(c.samples.min() / 1e6),
                        "%.3f".format(c.samples.max() / 1e6),
                        "%.3f".format(ci / 1e6),
                        "%.2f".format(Stats.relSpread(c.samples)),
                    )
            w.println(row.joinToString(","))
        }
    }
    println("Stats -> ${out.absolutePath}")
}

// ---------------------------------------------------------------------------
// Chart helpers
// ---------------------------------------------------------------------------

private fun baseXY(
    title: String,
    x: String,
    y: String,
    logX: Boolean,
    logY: Boolean,
): XYChart {
    val chart =
        XYChartBuilder()
            .width(1100).height(650)
            .theme(Styler.ChartTheme.XChart)
            .title(title)
            .xAxisTitle(x)
            .yAxisTitle(y)
            .build()
    chart.styler.setLegendPosition(Styler.LegendPosition.OutsideE)
    chart.styler.setMarkerSize(6)
    if (logX) chart.styler.setXAxisLogarithmic(true)
    if (logY) chart.styler.setYAxisLogarithmic(true)
    return chart
}

private fun save(
    chart: org.knowm.xchart.internal.chartpart.Chart<*, *>,
    file: File,
) {
    BitmapEncoder.saveBitmap(chart, file.absolutePath.removeSuffix(".png"), BitmapEncoder.BitmapFormat.PNG)
}

private fun boxChart(
    title: String,
    x: String,
    y: String,
): BoxChart =
    BoxChartBuilder()
        .width(1200).height(700)
        .theme(Styler.ChartTheme.XChart)
        .title(title)
        .xAxisTitle(x)
        .yAxisTitle(y)
        .build()
        .also {
            it.styler.setLegendPosition(Styler.LegendPosition.OutsideS)
            it.styler.setXAxisLabelRotation(45)
        }

// ---------------------------------------------------------------------------
// Task 1
// ---------------------------------------------------------------------------

private fun saveTask1Charts(
    configs: Collection<Config>,
    outDir: File,
) {
    if (configs.isEmpty()) return
    val images = configs.map { it.dim("image") }.distinct()
    val kernels = configs.map { it.dim("kernel") }.distinct()

    // 1. Scaling law (log-log), medians with bootstrap 95% CI error bars.
    val chart1 =
        baseXY(
            "Task 1: serial time vs image size (median, 95% CI)",
            "image size, megapixels (log)",
            "time, ms (log)",
            logX = true,
            logY = true,
        )
    for (k in kernels) {
        val rs = configs.filter { it.dim("kernel") == k }.sortedBy { it.dim("pixels").toLong() }
        chart1.addSeries(
            "$k [CI 95%]",
            rs.map { it.dim("pixels").toLong() / 1e6 }.toDoubleArray(),
            rs.map { Stats.median(it.samples) / 1e6 }.toDoubleArray(),
            rs.map { ciHalfWidthNs(it.samples) / 1e6 }.toDoubleArray(),
        )
    }
    save(chart1, File(outDir, "task1_scaling.png"))

    // 2. Throughput per (pixel x tap): flat = hardware-bound, not noise-bound.
    val chart2 =
        baseXY("Task 1: throughput per kernel tap (median, 95% CI)", "kernel taps (log)", "ns per (pixel x tap)", logX = true, logY = false)
    for (img in images) {
        val rs = configs.filter { it.dim("image") == img }.sortedBy { it.dim("taps").toInt() }
        val med = rs.map { Stats.median(it.samples) }
        val div = rs.map { it.dim("pixels").toLong() * it.dim("taps").toInt() }
        chart2.addSeries(
            img,
            rs.map { it.dim("taps").toDouble() }.toDoubleArray(),
            rs.indices.map { med[it] / div[it] }.toDoubleArray(),
            rs.indices.map { ciHalfWidthNs(rs[it].samples) / div[it] }.toDoubleArray(),
        )
    }
    save(chart2, File(outDir, "task1_ns_per_tap.png"))

    // 3. Box plots of per-run samples (% of config median): the honest noise
    //    picture that justifies dropping tiny configurations.
    val chart3 =
        boxChart("Task 1: run-to-run distribution per config (% of config median)", "config: image / kernel", "run time, % of median")
    for (c in configs.sortedBy { it.key }) {
        val m = Stats.median(c.samples)
        chart3.addSeries(c.dim("image") + " " + c.dim("kernel"), c.samples.map { 100.0 * it / m }.map { it as kotlin.Number })
    }
    save(chart3, File(outDir, "task1_run_spread.png"))
    println("Task 1 charts -> ${outDir.absolutePath}")
}

fun main(args: Array<String>) {
    val workDir = File("").absoluteFile.canonicalFile
    val repoRoot =
        when {
            File(workDir, "docs").exists() -> workDir
            File(workDir.parentFile, "docs").exists() -> workDir.parentFile
            else -> workDir
        }

    val src =
        args.firstOrNull { !it.startsWith("--") }?.let { File(it).let { f -> if (f.isAbsolute) f else File(repoRoot, it) } }
            ?: File(repoRoot, "docs/bench")

    val outDir = File(repoRoot, "docs/charts").also { it.mkdirs() }

    println("Charts source: ${src.absolutePath}")
    val runDirs = findRunDirs(src)
    if (runDirs.isEmpty()) error("no benchmark_task*.csv found under ${src.absolutePath} — run ./gradlew benchmark first")
    println("Pooled run directories (${runDirs.size}): ${runDirs.map { it.name }}")

    val t1 = collectConfigs(runDirs, "benchmark_task1.csv")
    if (t1.isNotEmpty()) {
        writeStats(t1.values, File(repoRoot, "docs/benchmark_stats_task1.csv"))
        saveTask1Charts(t1.values, outDir)
    }
}

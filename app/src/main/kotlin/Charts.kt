import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.BoxChartBuilder
import org.knowm.xchart.XYChart
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.style.Styler
import java.io.File
import java.util.Random
import kotlin.math.floor
import kotlin.math.max

// ---------------------------------------------------------------------------
// Data model: samples pooled per configuration, attributed per run dir so
// cross-round repeatability is measurable.
// ---------------------------------------------------------------------------

data class Config(
    val dims: List<Pair<String, String>>,
    val samples: MutableList<Long> = mutableListOf(),
    val byDir: LinkedHashMap<String, MutableList<Long>> = linkedMapOf(),
) {
    val key: String
        get() = dims.joinToString("/") { it.second }

    fun dim(name: String): String = dims.first { it.first == name }.second

    fun dirMedians(): List<Double> = byDir.values.filter { it.isNotEmpty() }.map { Stats.median(it) }
}

private fun parseCsv(
    file: File,
    into: LinkedHashMap<String, Config>,
    dirName: String,
) {
    val lines = file.readLines().filter { it.isNotBlank() }
    require(lines.size > 1) { "empty csv $file" }
    val header = lines[0].split(",")
    val nsIdx = header.indexOf("ns").also { require(it >= 0) { "no ns column in $file" } }
    // A pooled export may carry a `round` column: it is the reproducibility
    // grouping key (like a run directory), not a configuration dimension.
    val roundIdx = header.indexOf("round")
    val dimCols = header.withIndex().filter { it.value !in listOf("ns", "rep", "round") }
    for (line in lines.drop(1)) {
        val cells = line.split(",")
        val dims = dimCols.map { (i, n) -> n to cells[i] }
        val key = dims.joinToString("/") { it.second }
        val cfg = into.getOrPut(key) { Config(dims) }
        val ns = cells[nsIdx].toLong()
        cfg.samples.add(ns)
        val group = if (roundIdx >= 0) "round${cells[roundIdx]}" else dirName
        cfg.byDir.getOrPut(group) { mutableListOf() }.add(ns)
    }
}

private fun collectConfigs(
    runDirs: List<File>,
    fileName: String,
): LinkedHashMap<String, Config> {
    val configs = LinkedHashMap<String, Config>()
    for (dir in runDirs) {
        val csv = File(dir, fileName)
        if (csv.exists() && csv.length() > 0) parseCsv(csv, configs, dir.name)
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

// ---------------------------------------------------------------------------
// Statistics helpers
// ---------------------------------------------------------------------------

private fun ciHalfWidthNs(values: List<Long>): Double {
    val (lo, hi) = Stats.bootstrapMedianCi(values)
    val m = Stats.median(values)
    return max(hi - m, m - lo)
}

/** Bootstrap 95% CI of median(numerator)/median(denominator) — speedup ratios. */
private fun ratioCi(
    numerator: List<Long>,
    denominator: List<Long>,
    seed: Long = 20260416L,
    iterations: Int = 4000,
): Pair<Double, Double> {
    if (numerator.size < 3 || denominator.size < 3) {
        val p = Stats.median(numerator) / Stats.median(denominator)
        return p to p
    }
    val rnd = Random(seed)
    val ratios = DoubleArray(iterations)
    for (i in 0 until iterations) {
        val a = LongArray(numerator.size) { numerator[rnd.nextInt(numerator.size)] }.toList()
        val b = LongArray(denominator.size) { denominator[rnd.nextInt(denominator.size)] }.toList()
        ratios[i] = Stats.median(a) / Stats.median(b)
    }
    val s = ratios.sortedArray()
    return s[floor(0.025 * iterations).toInt()] to s[floor(0.975 * iterations).toInt()]
}

private fun numbers(values: List<Double>): List<Number> = values.map { it as Number }

private fun writeStats(
    configs: Collection<Config>,
    out: File,
) {
    if (configs.isEmpty()) return
    out.printWriter().use { w ->
        val first = configs.first()
        w.println(first.dims.joinToString(",") { it.first } + ",n,median_ms,q1_ms,q3_ms,min_ms,max_ms,ci95_half_ms,spread_pct")
        for (c in configs) {
            val row =
                c.dims.map { it.second } +
                    listOf(
                        c.samples.size.toString(),
                        "%.3f".format(Stats.median(c.samples) / 1e6),
                        "%.3f".format(Stats.quantile(c.samples, 0.25) / 1e6),
                        "%.3f".format(Stats.quantile(c.samples, 0.75) / 1e6),
                        "%.3f".format(c.samples.min() / 1e6),
                        "%.3f".format(c.samples.max() / 1e6),
                        "%.3f".format(ciHalfWidthNs(c.samples) / 1e6),
                        "%.2f".format(Stats.relSpread(c.samples)),
                    )
            w.println(row.joinToString(","))
        }
    }
    println("Stats -> ${out.absolutePath}")
}

/**
 * Cross-round repeatability: per-dir medians normalized to the pooled median.
 * Written as a CSV row per config (max relative deviation across dirs) and
 * drawn as one box per config (only meaningful with >= 2 invocations).
 */
private fun repeatability(
    configs: Collection<Config>,
    taskTag: String,
    docsDir: File,
    chartsDir: File,
    chartOnly: (Config) -> Boolean = { true },
) {
    if (configs.firstOrNull()?.byDir?.size?.let { it < 2 } != false) return
    File(docsDir, "benchmark_repeatability_$taskTag.csv").printWriter().use { w ->
        w.println("config,n_dirs,pooled_median_ms,max_deviation_pct")
        for (c in configs.sortedBy { it.key }) {
            val medians = c.dirMedians()
            if (medians.size < 2) continue
            val pooled = Stats.median(c.samples)
            val maxDev = medians.maxOf { kotlin.math.abs(it - pooled) / pooled * 100.0 }
            w.println("\"${c.key}\",${medians.size},%.3f,%.2f".format(pooled / 1e6, maxDev))
        }
    }
    val chart =
        BoxChartBuilder()
            .width(1200).height(700)
            .theme(Styler.ChartTheme.XChart)
            .title("$taskTag: per-round medians across invocations (% of pooled median)")
            .xAxisTitle("config")
            .yAxisTitle("round median, % of pooled")
            .build()
    chart.styler.setLegendPosition(Styler.LegendPosition.OutsideS)
    chart.styler.setXAxisLabelRotation(45)
    var drawn = 0
    for (c in configs.sortedBy { it.key }) {
        val medians = c.dirMedians()
        if (medians.size < 2 || !chartOnly(c)) continue
        chart.addSeries(c.key.removePrefix("serial/1/"), numbers(medians.map { 100.0 * it / Stats.median(c.samples) }))
        drawn++
    }
    if (drawn > 0) save(chart, File(chartsDir, "${taskTag}_repeatability.png"))
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

// ---------------------------------------------------------------------------
// Task 1
// ---------------------------------------------------------------------------

private fun saveTask1Charts(
    configs: Collection<Config>,
    chartsDir: File,
) {
    if (configs.isEmpty()) return
    val images = configs.map { it.dim("image") }.distinct()
    val kernels = configs.map { it.dim("kernel") }.distinct()

    // 1. Scaling law (log-log): medians + bootstrap 95% CI error bars.
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
    save(chart1, File(chartsDir, "task1_scaling.png"))

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
    save(chart2, File(chartsDir, "task1_ns_per_tap.png"))

    // 3. Box plots of per-run samples (% of config median): honest noise view.
    val chart3 =
        BoxChartBuilder()
            .width(1200).height(700)
            .theme(Styler.ChartTheme.XChart)
            .title("Task 1: run-to-run distribution per config (% of config median)")
            .xAxisTitle("config: image / kernel")
            .yAxisTitle("run time, % of median")
            .build()
    chart3.styler.setLegendPosition(Styler.LegendPosition.OutsideS)
    chart3.styler.setXAxisLabelRotation(45)
    for (c in configs.sortedBy { it.key }) {
        val m = Stats.median(c.samples)
        chart3.addSeries(c.dim("image") + " " + c.dim("kernel"), numbers(c.samples.map { 100.0 * it / m }))
    }
    save(chart3, File(chartsDir, "task1_run_spread.png"))
    println("Task 1 charts -> ${chartsDir.absolutePath}")
}

// ---------------------------------------------------------------------------
// Task 2
// ---------------------------------------------------------------------------

private fun saveTask2Charts(
    configs: Collection<Config>,
    chartsDir: File,
) {
    if (configs.isEmpty()) return
    val cores = Runtime.getRuntime().availableProcessors()

    fun serialFor(
        img: String,
        kernel: String,
    ): Config? = configs.firstOrNull { it.dim("strategy") == "serial" && it.dim("image") == img && it.dim("kernel") == kernel }

    val bigImage = configs.maxByOrNull { it.dim("pixels").toLong() }!!.dim("image")
    val heavyKernel = configs.maxByOrNull { it.dim("taps").toInt() }!!.dim("kernel")
    val focus = "$bigImage / $heavyKernel"
    val strategies = listOf("rows", "columns", "autoGrid")

    // 1. Median time vs OS threads (focus config), CI error bars + serial line.
    val chart1 =
        baseXY(
            "Task 2: time vs OS threads ($focus; median, 95% CI)",
            "OS threads (pool = coroutines = threads)",
            "time, ms (log)",
            logX = false,
            logY = true,
        )
    for (s in strategies) {
        val rs =
            configs.filter {
                it.dim("strategy") == s && it.dim("image") == bigImage && it.dim("kernel") == heavyKernel
            }.sortedBy { it.dim("threads").toInt() }
        chart1.addSeries(
            s,
            rs.map { it.dim("threads").toDouble() }.toDoubleArray(),
            rs.map { Stats.median(it.samples) / 1e6 }.toDoubleArray(),
            rs.map { ciHalfWidthNs(it.samples) / 1e6 }.toDoubleArray(),
        )
    }
    serialFor(bigImage, heavyKernel)?.let { base ->
        val maxT = configs.maxOf { it.dim("threads").toInt() }.toDouble()
        chart1.addSeries(
            "serial",
            doubleArrayOf(1.0, maxT),
            doubleArrayOf(Stats.median(base.samples) / 1e6, Stats.median(base.samples) / 1e6),
        )
    }
    save(chart1, File(chartsDir, "task2_time_vs_threads.png"))

    // 2. Speedup with ratio-bootstrap CI + ideal line.
    val chart2 =
        baseXY("Task 2: speedup vs OS threads ($focus; 95% ratio-CI)", "OS threads", "speedup [x = ideal]", logX = false, logY = false)
    for (s in strategies) {
        val rs =
            configs.filter {
                it.dim("strategy") == s && it.dim("image") == bigImage && it.dim("kernel") == heavyKernel
            }.sortedBy { it.dim("threads").toInt() }
        val speeds = rs.map { Stats.median(serialFor(bigImage, heavyKernel)!!.samples) / Stats.median(it.samples) }
        val errs =
            rs.indices.map {
                val (lo, hi) = ratioCi(serialFor(bigImage, heavyKernel)!!.samples, rs[it].samples)
                max(speeds[it] - lo, hi - speeds[it])
            }
        chart2.addSeries(s, rs.map { it.dim("threads").toDouble() }.toDoubleArray(), speeds.toDoubleArray(), errs.toDoubleArray())
    }
    val maxT = configs.maxOf { it.dim("threads").toInt() }
    chart2.addSeries("ideal", doubleArrayOf(1.0, maxT.toDouble()), doubleArrayOf(1.0, maxT.toDouble()))
    save(chart2, File(chartsDir, "task2_speedup_vs_threads.png"))

    // 3. Parallel efficiency (speedup / threads) for every image/kernel config.
    val chart3 = baseXY("Task 2: parallel efficiency, rows strategy", "OS threads", "efficiency, %", logX = false, logY = false)
    val combos = configs.filter { it.dim("strategy") == "rows" }.map { it.dim("image") to it.dim("kernel") }.distinct()
    for ((img, k) in combos) {
        val rs =
            configs.filter {
                it.dim("strategy") == "rows" && it.dim("image") == img && it.dim("kernel") == k
            }.sortedBy { it.dim("threads").toInt() }
        chart3.addSeries(
            "${img.removeSuffix(".bmp")} $k",
            rs.map { it.dim("threads").toDouble() }.toDoubleArray(),
            rs.map {
                100.0 * Stats.median(serialFor(img, k)!!.samples) / Stats.median(it.samples) / it.dim("threads").toInt()
            }.toDoubleArray(),
        )
    }
    save(chart3, File(chartsDir, "task2_efficiency.png"))

    // 4. Strategy distributions at all physical cores: raw box plots per config.
    val chart4 =
        BoxChartBuilder()
            .width(1200).height(700)
            .theme(Styler.ChartTheme.XChart)
            .title("Task 2: strategy run distributions at $cores threads (ms)")
            .xAxisTitle("image / kernel / strategy")
            .yAxisTitle("time, ms")
            .build()
    chart4.styler.setLegendPosition(Styler.LegendPosition.OutsideS)
    chart4.styler.setXAxisLabelRotation(45)
    for (c in configs.filter { it.dim("threads") == cores.toString() && it.dim("strategy") != "serial" }.sortedBy { it.key }) {
        chart4.addSeries(
            "${c.dim("image").removeSuffix(".bmp")} ${c.dim("kernel")} ${c.dim("strategy")}",
            numbers(c.samples.map { it / 1e6 }),
        )
    }
    save(chart4, File(chartsDir, "task2_strategies_at_all_cores.png"))
    println("Task 2 charts -> ${chartsDir.absolutePath}")
}

// ---------------------------------------------------------------------------
// Task 3
// ---------------------------------------------------------------------------

private fun cfgLabel(c: Config): String = "${c.dim("scenario")}: ${c.dim("label")}"

private fun saveTask3Charts(
    configs: Collection<Config>,
    chartsDir: File,
) {
    if (configs.isEmpty()) return
    val cores = Runtime.getRuntime().availableProcessors()

    // 1. Pass time vs workers for both kernels + ideal line (heavy baseline/w).
    val heavy = configs.filter { it.dim("scenario") == "workers_heavy" }.sortedBy { it.dim("workers").toInt() }
    val light = configs.filter { it.dim("scenario") == "workers_light" }.sortedBy { it.dim("workers").toInt() }
    val chart1 =
        baseXY(
            "Task 3: dataset pass time vs pipeline workers (median, 95% CI)",
            "convolution workers (= dedicated OS threads)",
            "pass, ms (log)",
            logX = false,
            logY = true,
        )
    if (heavy.isNotEmpty()) {
        chart1.addSeries(
            "heavy 15x15 [CI]",
            heavy.map { it.dim("workers").toDouble() }.toDoubleArray(),
            heavy.map { Stats.median(it.samples) / 1e6 }.toDoubleArray(),
            heavy.map { ciHalfWidthNs(it.samples) / 1e6 }.toDoubleArray(),
        )
        val base = Stats.median(heavy.first { it.dim("workers").toInt() == 1 }.samples)
        chart1.addSeries(
            "ideal (heavy/threads)",
            heavy.map { it.dim("workers").toDouble() }.toDoubleArray(),
            heavy.map { base / it.dim("workers").toInt() / 1e6 }.toDoubleArray(),
        )
    }
    if (light.isNotEmpty()) {
        chart1.addSeries(
            "light 3x3 [CI]",
            light.map { it.dim("workers").toDouble() }.toDoubleArray(),
            light.map { Stats.median(it.samples) / 1e6 }.toDoubleArray(),
            light.map { ciHalfWidthNs(it.samples) / 1e6 }.toDoubleArray(),
        )
    }
    save(chart1, File(chartsDir, "task3_scaling_vs_workers.png"))

    // 2. I/O budget: stage costs vs the light-kernel plateau + IO scaling.
    val chart2 =
        BoxChartBuilder()
            .width(1250).height(700)
            .theme(Styler.ChartTheme.XChart)
            .title("Task 3: stage budget and escaping the I/O bound (ms per pass/stage)")
            .xAxisTitle("configuration")
            .yAxisTitle("ms")
            .build()
    chart2.styler.setLegendPosition(Styler.LegendPosition.OutsideS)
    chart2.styler.setXAxisLabelRotation(45)
    for (c in configs.filter {
        it.dim("scenario") in setOf("stages", "io_scaling") || (it.dim("scenario") == "workers_light" && it.dim("workers") == "$cores")
    }) {
        chart2.addSeries(cfgLabel(c), numbers(c.samples.map { it / 1e6 }))
    }
    save(chart2, File(chartsDir, "task3_io_stages.png"))

    // 3. Nested vs cross-image parallelism (heavy): distributions per config.
    val chart3 =
        BoxChartBuilder()
            .width(1250).height(700)
            .theme(Styler.ChartTheme.XChart)
            .title("Task 3: nested per-image parallelism vs cross-image workers (heavy)")
            .xAxisTitle("configuration")
            .yAxisTitle("pass, ms")
            .build()
    chart3.styler.setLegendPosition(Styler.LegendPosition.OutsideS)
    chart3.styler.setXAxisLabelRotation(45)
    for (c in configs.filter {
        it.dim("scenario") == "nested" || (it.dim("scenario") == "workers_heavy" && it.dim("workers") in setOf("1", "$cores"))
    }) {
        chart3.addSeries(cfgLabel(c), numbers(c.samples.map { it / 1e6 }))
    }
    save(chart3, File(chartsDir, "task3_nested_vs_cross.png"))

    // 4. Buffer-size sweep for both kernels.
    val chart4 =
        baseXY(
            "Task 3: channel buffer size effect ($cores workers; median, 95% CI)",
            "buffer size (slots per stage channel)",
            "pass, ms (log)",
            logX = false,
            logY = true,
        )
    for ((k, label) in listOf("heavy_15x15" to "heavy 15x15", "light_3x3" to "light 3x3")) {
        val rs = configs.filter { it.dim("scenario") == "buffer" && it.dim("kernel") == k }.sortedBy { it.dim("buffer").toInt() }
        chart4.addSeries(
            label,
            rs.map { it.dim("buffer").toDouble() }.toDoubleArray(),
            rs.map { Stats.median(it.samples) / 1e6 }.toDoubleArray(),
            rs.map { ciHalfWidthNs(it.samples) / 1e6 }.toDoubleArray(),
        )
    }
    save(chart4, File(chartsDir, "task3_buffer.png"))
    println("Task 3 charts -> ${chartsDir.absolutePath}")
}

fun main(args: Array<String>) {
    val repoRoot = repoRoot()

    val src =
        args.firstOrNull { !it.startsWith("--") }?.let { f0 -> File(f0).let { f -> if (f.isAbsolute) f else File(repoRoot, f0) } }
            ?: File(repoRoot, "docs/bench")

    val chartsDir = File(repoRoot, "docs/charts").also { it.mkdirs() }
    val docsDir = File(repoRoot, "docs")

    println("Charts source: ${src.absolutePath}")
    val runDirs = findRunDirs(src)
    if (runDirs.isEmpty()) error("no benchmark_task*.csv found under ${src.absolutePath} — run the benchmark first")
    println("Pooled run directories (${runDirs.size}): ${runDirs.map { it.name }}")

    val t1 = collectConfigs(runDirs, "benchmark_task1.csv")
    if (t1.isNotEmpty()) {
        writeStats(t1.values, File(docsDir, "benchmark_stats_task1.csv"))
        saveTask1Charts(t1.values, chartsDir)
        repeatability(t1.values, "task1", docsDir, chartsDir)
    }

    val t2 = collectConfigs(runDirs, "benchmark_task2.csv")
    if (t2.isNotEmpty()) {
        writeStats(t2.values, File(docsDir, "benchmark_stats_task2.csv"))
        saveTask2Charts(t2.values, chartsDir)
        val bigImage = t2.values.maxOf { it.dim("pixels").toLong() }
        val heavyTaps = t2.values.maxOf { it.dim("taps").toInt() }
        val cores = Runtime.getRuntime().availableProcessors()
        val focusPixels = bigImage
        val focusTaps = heavyTaps
        val focusThreads = setOf("8", "$cores", "${cores * 2}")
        repeatability(t2.values, "task2", docsDir, chartsDir) { c ->
            val isFocus = c.dim("pixels").toLong() == focusPixels && c.dim("taps").toInt() == focusTaps
            isFocus && (c.dim("strategy") == "serial" || c.dim("threads") in focusThreads)
        }
    }

    val t3 = collectConfigs(runDirs, "benchmark_task3.csv")
    if (t3.isNotEmpty()) {
        writeStats(t3.values, File(docsDir, "benchmark_stats_task3.csv"))
        saveTask3Charts(t3.values, chartsDir)
        val cores = Runtime.getRuntime().availableProcessors()
        repeatability(t3.values, "task3", docsDir, chartsDir) { c ->
            when {
                c.dim("scenario") == "nested" -> true
                c.dim("scenario") == "workers_heavy" -> c.dim("workers") == "$cores"
                c.dim("scenario") == "workers_light" -> c.dim("workers") == "$cores"
                c.dim("scenario") == "io_scaling" -> true
                else -> false
            }
        }
    }
}

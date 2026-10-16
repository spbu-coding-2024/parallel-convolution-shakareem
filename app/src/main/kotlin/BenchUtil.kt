import java.io.File
import java.util.Random
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

//
// Shared benchmarking utilities: interleaved measurement matrix, raw
// nanosecond recording, robust statistics with bootstrap confidence
// intervals, and run-metadata capture.
//
// Measurement model (used by all three benchmark tasks):
//
// - every configuration is a [BenchJob] with dimension tags (image, kernel,
//   threads, ...), one warmup and `reps` timed executions;
// - timed executions are INTERLEAVED: the job order is one seeded shuffle,
//   then rep 1 over all jobs, rep 2 over all jobs, ... so any environmental
//   drift (thermal, background load) hits every configuration equally instead
//   of biasing whichever block happened to run at that time;
// - raw values are written to CSV in *nanoseconds*, one row per measurement
//   (long format). All statistics (median, IQR, bootstrap CI, speedup ratios)
//   are computed by the offline `charts` tool over pooled runs, so the raw
//   data stays re-analyzable and the benchmark process itself stays dumb.
//

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

object Stats {
    fun median(values: List<Long>): Double = quantile(values, 0.5)

    /** Linear-interpolated quantile of the empirical distribution. */
    fun quantile(
        values: List<Long>,
        q: Double,
    ): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        if (s.size == 1) return s[0].toDouble()
        val pos = q * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = min(lo + 1, s.size - 1)
        val frac = pos - lo
        return s[lo] * (1 - frac) + s[hi] * frac
    }

    /**
     * Percentile bootstrap CI of the median. Seeded -> reproducible.
     * Returns (lo, hi) in the same units as the input.
     */
    fun bootstrapMedianCi(
        values: List<Long>,
        seed: Long = 20260416L,
        iterations: Int = 5000,
        level: Double = 0.95,
    ): Pair<Double, Double> {
        if (values.size < 3) return median(values) to median(values)
        val rnd = Random(seed)
        val n = values.size
        val medians = DoubleArray(iterations)
        for (i in 0 until iterations) {
            val sample = LongArray(n) { values[rnd.nextInt(n)] }
            medians[i] = quantile(sample.toList(), 0.5)
        }
        val sorted = medians.sortedArray()
        val a = (1 - level) / 2
        val lo = sorted[floor(a * iterations).toInt().coerceAtMost(iterations - 1)]
        val hi = sorted[floor((1 - a) * iterations).toInt().coerceAtMost(iterations - 1)]
        return lo to hi
    }

    /** Relative spread (max-min)/median, percent — the within-run noise figure. */
    fun relSpread(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val m = median(values)
        return if (m > 0) 100.0 * (values.max() - values.min()) / m else 0.0
    }
}

// ---------------------------------------------------------------------------
// Jobs and the interleaved matrix
// ---------------------------------------------------------------------------

class BenchJob(
    /** Ordered dimension tags, e.g. ("threads","14"), ("kernel","blur_15x15"). */
    val dims: List<Pair<String, String>>,
    val warmup: () -> Unit,
    val measure: () -> Long,
) {
    val samples = mutableListOf<Long>()

    val label: String
        get() = dims.joinToString("/") { it.second }
}

/** Long-format CSV sink: dimension columns, then rep and raw ns. */
class CsvSink(
    file: File,
    dimNames: List<String>,
) {
    private val writer = file.bufferedWriter()

    init {
        writer.write((dimNames + listOf("rep", "ns")).joinToString(","))
        writer.newLine()
    }

    fun row(
        job: BenchJob,
        rep: Int,
        ns: Long,
    ) {
        writer.write(job.dims.joinToString(",") { it.second })
        writer.write(",")
        writer.write(rep.toString())
        writer.write(",")
        writer.write(ns.toString())
        writer.newLine()
    }

    fun close() = writer.close()
}

/**
 * Runs the measurement matrix: one un-timed warmup per job, then `reps`
 * interleaved rounds over a seeded-shuffled job order, appending every raw
 * sample to [csv] immediately (crash-safe), and prints a per-job median
 * summary at the end.
 */
fun runMatrix(
    jobs: List<BenchJob>,
    reps: Int,
    seed: Long,
    csv: CsvSink,
) {
    jobs.forEach { it.warmup() }
    val order = jobs.shuffled(Random(seed))
    for (rep in 1..reps) {
        for (job in order) {
            val ns = job.measure()
            job.samples.add(ns)
            csv.row(job, rep, ns)
        }
        println("  ... round $rep/$reps done")
    }
    csv.close()
    println()
    for (job in jobs) {
        val med = Stats.median(job.samples) / 1e6
        val (lo, hi) = Stats.bootstrapMedianCi(job.samples)
        println(
            "  %-52s median=%9.2fms  CI95=[%8.2f, %8.2f]ms  spread=%.1f%%".format(
                job.label,
                med,
                lo / 1e6,
                hi / 1e6,
                Stats.relSpread(job.samples),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// CLI + metadata
// ---------------------------------------------------------------------------

/** Repo root: the directory holding settings.gradle.kts (JVM cwd is app/ under gradle). */
fun repoRoot(): File {
    val cwd = File("").absoluteFile
    var dir = cwd
    while (true) {
        if (File(dir, "settings.gradle.kts").exists() && File(dir, "app").exists()) return dir
        dir = dir.parentFile ?: return cwd
    }
}

/** Parses `[task] [--out DIR] [--reps N] [--seed N]` style benchmark args. */
class BenchArgs(
    val task: String,
    val outDir: File,
    val reps: Int,
    val seed: Long,
)

fun parseBenchArgs(
    args: Array<String>,
    defaultReps: Int,
    fallbackOut: File,
): BenchArgs {
    var task = args.firstOrNull()?.takeIf { !it.startsWith("--") } ?: "serial"
    var out = fallbackOut
    var reps = defaultReps
    var seed = 20260416L
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--out" -> {
                i++
                val f = File(args[i])
                out = (if (f.isAbsolute) f else File(repoRoot(), args[i])).apply { mkdirs() }
            }

            "--reps" -> {
                i++
                reps = args[i].toInt()
            }

            "--seed" -> {
                i++
                seed = args[i].toLong()
            }
        }
        i++
    }
    out.mkdirs()
    return BenchArgs(task, out, reps, seed)
}

private fun readFirst(path: String): String? =
    try {
        File(path).readLines().firstOrNull()
    } catch (
        @Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception,
    ) {
        null
    }

/** Records the measurement environment next to the raw data (for provenance). */
fun writeRunMeta(
    outDir: File,
    args: BenchArgs,
) {
    val proc =
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.trim() ?: "Cpus_allowed_list: ?"
    val lines =
        listOf(
            "timestamp=${java.time.LocalDateTime.now()}",
            "task=${args.task}",
            "reps=${args.reps}",
            "seed=${args.seed}",
            "jvm=${System.getProperty("java.vm.version")} (${System.getProperty("java.vendor")})",
            "availableProcessors=${Runtime.getRuntime().availableProcessors()}",
            "procAffinity=$proc",
            "loadavg_before=${readFirst("/proc/loadavg") ?: "?"}",
            "cpuGovernor=${readFirst("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") ?: "?"}",
            "cpu0_curMHz=${readFirst("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq") ?: "?"}",
            "cmdline=${args.let { System.getProperty("sun.java.command") ?: "?" }}",
        )
    File(outDir, "env.txt").writeText(lines.joinToString("\n") + "\n")
}

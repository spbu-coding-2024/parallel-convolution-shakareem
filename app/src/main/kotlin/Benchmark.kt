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
import pipeline.Mode
import pipeline.PipelineConfig
import pipeline.processDataset
import java.io.File
import java.nio.file.Paths
import kotlin.system.measureTimeMillis

// 11x11 box blur — compute-heavy
val HEAVY_KERNEL = Array(11) { DoubleArray(11) { 1.0 / 121 } }

// 3x3 blur — I/O-bound scenario
val LIGHT_KERNEL = Array(3) { DoubleArray(3) { 1.0 / 9 } }

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

fun bench(
    label: String,
    repeats: Int,
    block: () -> Unit,
): Long {
    block() // warmup
    val times = mutableListOf<Long>()
    repeat(repeats) { times.add(measureTimeMillis { block() }) }
    val avg = times.average().toLong()
    val min = times.min()
    val max = times.max()
    println("  %-45s avg=%4dms  min=%4dms  max=%4dms".format(label, avg, min, max))
    return avg
}

fun runPipelineBenchmark(
    resourcesDir: File,
    docsDir: File,
) {
    val cores = Runtime.getRuntime().availableProcessors()
    val repeats = 3
    val outputDir = File("build/benchmark_output").also { it.mkdirs() }

    val files = resourcesDir.listFiles { f -> f.isFile }?.toList() ?: emptyList()
    val sampleImg = readImage(files.first().absolutePath)
    val imgKB = sampleImg.size * sampleImg[0].size * 8L / 1024

    println("=== Task 3: Pipeline Benchmark ===")
    println("Dataset  : ${files.size} images")
    println("Sample   : ${sampleImg.size}x${sampleImg[0].size} = $imgKB KB per image")
    println("CPU cores: $cores\n")

    data class Row(
        val kernel: String,
        val label: String,
        val readers: Int,
        val workers: Int,
        val writers: Int,
        val buffer: Int,
        val avgMs: Long,
    )

    val rows = mutableListOf<Row>()

    fun runLight(
        label: String,
        cfg: PipelineConfig,
    ): Long {
        val avg = bench(label, repeats) { processDataset(resourcesDir, outputDir, LIGHT_KERNEL, Mode.SERIAL, cfg) }
        rows.add(Row("light_3x3", label, cfg.numReaders, cfg.numWorkers, cfg.numWriters, cfg.bufferSize, avg))
        return avg
    }

    fun runHeavy(
        label: String,
        cfg: PipelineConfig,
        mode: Mode = Mode.SERIAL,
    ): Long {
        val avg = bench(label, repeats) { processDataset(resourcesDir, outputDir, HEAVY_KERNEL, mode, cfg) }
        rows.add(Row("heavy_11x11", label, cfg.numReaders, cfg.numWorkers, cfg.numWriters, cfg.bufferSize, avg))
        return avg
    }

    println("── Scenario 1: Light kernel (3×3 blur) — I/O bound ──")
    val baseLight = runLight("serial 1r/1w/1worker buf=8", PipelineConfig(1, 1, 1, 8))
    runLight("parallel 1r/1w/4workers buf=8", PipelineConfig(1, 4, 1, 8))
    runLight("parallel 2r/2w/4workers buf=8", PipelineConfig(2, 4, 2, 8))
    runLight("parallel 2r/2w/8workers buf=8", PipelineConfig(2, 8, 2, 8))
    runLight("parallel 1r/1w/1worker buf=1 (tight)", PipelineConfig(1, 1, 1, 1))
    runLight("parallel 1r/1w/1worker buf=32 (loose)", PipelineConfig(1, 1, 1, 32))
    println()

    println("── Scenario 2: Heavy kernel (11×11 blur) — compute bound ──")
    val baseHeavy = runHeavy("serial 1r/1w/1worker buf=8", PipelineConfig(1, 1, 1, 8))
    runHeavy("parallel 1r/1w/4workers buf=8", PipelineConfig(1, 4, 1, 8))
    runHeavy("parallel 1r/1w/8workers buf=8", PipelineConfig(1, 8, 1, 8))
    runHeavy("parallel 1r/1w/12workers buf=8", PipelineConfig(1, cores, 1, 8))
    runHeavy("parallel 2r/2w/8workers buf=8", PipelineConfig(2, 8, 2, 8))
    runHeavy("parallel 2r/2w/12workers buf=16", PipelineConfig(2, cores, 2, 16))
    println()

    println("── Scenario 3: Heavy kernel + parallel convolution per image ──")
    runHeavy("1r/1w/1worker ALLPROCESSORS buf=8", PipelineConfig(1, 1, 1, 8), Mode.ALLPROCESSORS)
    runHeavy("1r/1w/4workers ALLPROCESSORS buf=8", PipelineConfig(1, 4, 1, 8), Mode.ALLPROCESSORS)
    runHeavy("1r/1w/8workers ALLPROCESSORS buf=8", PipelineConfig(1, 8, 1, 8), Mode.ALLPROCESSORS)
    println()

    println("── Scenario 4: Buffer size effect (heavy kernel, 4 workers) ──")
    for (buf in listOf(1, 2, 4, 8, 16, 32)) {
        runHeavy("4workers buf=$buf", PipelineConfig(1, 4, 1, buf))
    }
    println()

    val bestLight = rows.filter { it.kernel == "light_3x3" }.minByOrNull { it.avgMs }
    val bestHeavy = rows.filter { it.kernel == "heavy_11x11" }.minByOrNull { it.avgMs }
    println("── Summary ──")
    println(
        "  Light baseline: ${baseLight}ms  best: ${bestLight?.avgMs}ms  speedup: ${"%.2f".format(
            baseLight.toDouble() / (bestLight?.avgMs ?: 1),
        )}x",
    )
    println(
        "  Heavy baseline: ${baseHeavy}ms  best: ${bestHeavy?.avgMs}ms  speedup: ${"%.2f".format(
            baseHeavy.toDouble() / (bestHeavy?.avgMs ?: 1),
        )}x",
    )
    println()

    println("── Memory pressure (per channel, heavy kernel) ──")
    for (buf in listOf(1, 4, 8, 16, 32)) {
        val mb = imgKB * buf * 2 / 1024
        println("  buffer=$buf: ~$mb MB (2 channels × $buf × ${imgKB}KB)")
    }

    val csvFile = File(docsDir, "benchmark_task3.csv")
    csvFile.printWriter().use { out ->
        out.println("kernel,label,readers,workers,writers,buffer,avg_ms")
        for (r in rows) {
            out.println("${r.kernel},${r.label},${r.readers},${r.workers},${r.writers},${r.buffer},${r.avgMs}")
        }
    }
    println("\nCSV saved to ${csvFile.absolutePath}")
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
        "pipeline" -> runPipelineBenchmark(resourcesDir, docsDir)
        else -> error("Unknown benchmark task: '$task'. Available: serial, parallel, pipeline")
    }
}

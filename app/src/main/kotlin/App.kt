import convolution.defaultThreads
import filters.FILTERS
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import pipeline.Mode
import pipeline.PipelineConfig
import pipeline.processDataset
import pipeline.processSingleFile
import java.io.File

fun main(args: Array<String>) {
    val parser = ArgParser("image-convolver")

    val input by parser
        .option(
            ArgType.String,
            shortName = "i",
            description = "Input file or directory",
        ).required()

    val output by parser
        .option(
            ArgType.String,
            shortName = "o",
            description = "Output directory (default: .)",
        ).default(".")

    val filterName by parser
        .option(
            ArgType.String,
            shortName = "f",
            description = "Filter name: ${FILTERS.keys.joinToString()}",
        ).required()

    val mode by parser
        .option(
            ArgType.Choice<Mode>(),
            shortName = "m",
            description =
                "Convolution strategy: SERIAL, ROWS, COLUMNS, GRID, PIXELWISE " +
                    "(default: ROWS for a single file, SERIAL for a dataset)",
        )

    val threads by parser
        .option(
            ArgType.Int,
            shortName = "t",
            description = "OS threads for tile-parallel convolution of one image (default: all cores)",
        ).default(defaultThreads)

    val workers by parser
        .option(
            ArgType.Int,
            shortName = "w",
            description = "Pipeline convolution workers == dedicated OS threads (dataset mode, default: all cores)",
        ).default(defaultThreads)

    val readers by parser
        .option(
            ArgType.Int,
            description = "Parallel file readers (dataset mode)",
        ).default(1)

    val writers by parser
        .option(
            ArgType.Int,
            description = "Parallel file writers (dataset mode)",
        ).default(1)

    val bufferSize by parser
        .option(
            ArgType.Int,
            shortName = "b",
            description = "Buffer size for pipeline channels (dataset mode)",
        ).default(8)

    parser.parse(args)

    val kernel = FILTERS[filterName] ?: error("Unknown filter: $filterName. Available: ${FILTERS.keys.joinToString()}")

    val inputFile = File(input)
    val outputFile = File(output)

    when {
        inputFile.isFile -> {
            processSingleFile(inputFile, outputFile, kernel, mode ?: Mode.ROWS, threads)
        }

        inputFile.isDirectory -> {
            processDataset(
                inputFile,
                outputFile,
                kernel,
                // Cross-image parallelism (SERIAL convolution per worker) is the
                // optimal default: `workers` images in flight saturate the CPUs
                // without nesting coroutines inside the pipeline workers.
                mode ?: Mode.SERIAL,
                PipelineConfig(
                    numReaders = readers,
                    numWorkers = workers,
                    numWriters = writers,
                    bufferSize = bufferSize,
                    convThreads = threads,
                ),
            )
        }

        else -> error("Input path is not a file or directory: $input")
    }
}

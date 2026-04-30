import filters.FILTERS
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import pipeline.Mode
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
            description = "Output directory",
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
            description = "Mode: SERIAL, PIXELWISE, COLUMNS, ROWS, ALLPROCESSORS, GRID",
        ).default(Mode.ALLPROCESSORS)

    val bufferSize by parser
        .option(
            ArgType.Int,
            shortName = "b",
            description = "Buffer size for pipeline channels (dataset mode only)",
        ).default(8)

    parser.parse(args)

    val kernel = FILTERS[filterName] ?: error("Unknown filter: $filterName. Available: ${FILTERS.keys.joinToString()}")

    val inputFile = File(input)
    val outputFile = File(output)

    if (inputFile.isFile) {
        processSingleFile(inputFile, outputFile, kernel, mode)
    } else if (inputFile.isDirectory) {
        processDataset(inputFile, outputFile, kernel, mode, bufferSize)
    } else {
        error("Input path is not a file or directory: $input")
    }
}

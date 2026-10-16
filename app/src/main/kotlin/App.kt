import convolution.autoGridConvolve
import convolution.columnsConvolve
import convolution.defaultThreads
import convolution.rowsConvolve
import convolution.serialConvolve
import convolution.shutdownConvolutionPools
import filters.FILTERS
import images.readImage
import images.writeImage
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import java.io.File

enum class ConvMode { SERIAL, ROWS, COLUMNS, GRID }

fun main(args: Array<String>) {
    val parser = ArgParser("image-convolver")

    val input by parser
        .option(
            ArgType.String,
            shortName = "i",
            description = "Input image file",
        ).required()

    val output by parser
        .option(
            ArgType.String,
            shortName = "o",
            description = "Output image file",
        ).default("output.bmp")

    val filterName by parser
        .option(
            ArgType.String,
            shortName = "f",
            description = "Filter name: ${FILTERS.keys.joinToString()}",
        ).required()

    val mode by parser
        .option(
            ArgType.Choice<ConvMode>(),
            shortName = "m",
            description = "Partition strategy (Task 2.1)",
        ).default(ConvMode.ROWS)

    val threads by parser
        .option(
            ArgType.Int,
            shortName = "t",
            description = "OS threads the convolution may use (default: all $defaultThreads cores)",
        ).default(defaultThreads)

    parser.parse(args)

    val kernel = FILTERS[filterName] ?: error("Unknown filter: $filterName. Available: ${FILTERS.keys.joinToString()}")

    val inputFile = File(input)
    if (!inputFile.exists()) error("Input file not found: $input")

    println("Reading image: $input")
    val image = readImage(inputFile.absolutePath)
    println("Image size: ${image.size}x${image[0].size}")

    println("Applying filter '$filterName' (mode=$mode, threads=$threads)...")
    val result =
        runBlocking {
            when (mode) {
                ConvMode.SERIAL -> serialConvolve(image, kernel)
                ConvMode.ROWS -> rowsConvolve(image, kernel, threads)
                ConvMode.COLUMNS -> columnsConvolve(image, kernel, threads)
                ConvMode.GRID -> autoGridConvolve(image, kernel, threads)
            }
        }

    val outputFile = File(output)
    outputFile.parentFile?.mkdirs()
    writeImage(result, outputFile.absolutePath)
    println("Result saved to: $output")

    shutdownConvolutionPools()
}

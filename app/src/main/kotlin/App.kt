import convolution.serialConvolve
import filters.FILTERS
import images.readImage
import images.writeImage
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import java.io.File

fun main(args: Array<String>) {
    val parser = ArgParser("image-convolver")

    val input by parser.option(
        ArgType.String,
        shortName = "i",
        description = "Input image file"
    ).required()

    val output by parser.option(
        ArgType.String,
        shortName = "o",
        description = "Output image file"
    ).default("output.bmp")

    val filterName by parser.option(
        ArgType.String,
        shortName = "f",
        description = "Filter name: ${FILTERS.keys.joinToString()}"
    ).required()

    parser.parse(args)

    val kernel = FILTERS[filterName] ?: error("Unknown filter: $filterName. Available: ${FILTERS.keys.joinToString()}")

    val inputFile = File(input)
    if (!inputFile.exists()) error("Input file not found: $input")

    println("Reading image: $input")
    val image = readImage(inputFile.absolutePath)
    println("Image size: ${image.size}x${image[0].size}")

    println("Applying filter '$filterName' (serial)...")
    val result = serialConvolve(image, kernel)

    val outputFile = File(output)
    outputFile.parentFile?.mkdirs()
    writeImage(result, outputFile.absolutePath)
    println("Result saved to: $output")
}

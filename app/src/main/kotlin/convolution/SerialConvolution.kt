package convolution

import images.Bitmap

fun serialConvolve(image: Bitmap, kernel: Bitmap): Bitmap {
    require(kernel.size % 2 == 1 && kernel[0].size % 2 == 1) { "Kernel dimensions must be odd" }

    val imageHeight = image.size
    val imageWidth = image[0].size

    val output = Array(imageHeight) { DoubleArray(imageWidth) }

    for (y in 0 until imageHeight) {
        for (x in 0 until imageWidth) {
            output[y][x] = convolvePixel(image, kernel, y, x)
        }
    }

    return output
}

fun convolvePixel(image: Bitmap, kernel: Bitmap, y: Int, x: Int): Double {
    val imageHeight = image.size
    val imageWidth = image[0].size
    val kernelHeight = kernel.size
    val kernelWidth = kernel[0].size

    var sum = 0.0
    for (ky in 0 until kernelHeight) {
        for (kx in 0 until kernelWidth) {
            val imageY = (y - kernelHeight / 2 + ky + imageHeight) % imageHeight
            val imageX = (x - kernelWidth / 2 + kx + imageWidth) % imageWidth

            // use reflected kernel (classical discrete convolution, not correlation)
            sum += kernel[kernelHeight - 1 - ky][kernelWidth - 1 - kx] * image[imageY][imageX]
        }
    }
    // working with images as math objects, so pixels can be (< 0) or (> 255)
    return sum
}

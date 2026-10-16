package convolution

import images.Bitmap

fun serialConvolve(
    image: Bitmap,
    kernel: Bitmap,
): Bitmap {
    require(kernel.size % 2 == 1 && kernel[0].size % 2 == 1) { "Kernel dimensions must be odd" }

    val imageHeight = image.size
    val imageWidth = image[0].size

    val output = Array(imageHeight) { DoubleArray(imageWidth) }
    convolveRegion(image, flipped(kernel), output, 0, imageHeight, 0, imageWidth)
    return output
}

/**
 * Kernel rotated by 180 degrees: a classical discrete convolution with [kernel]
 * is a correlation with the rotated kernel. Computing the rotation once saves
 * two lookups and two index subtractions per tap of the inner loop.
 *
 * Taps are visited in exactly the same order as in the naive formulation,
 * so floating-point sums are bit-identical.
 */
fun flipped(kernel: Bitmap): Bitmap {
    val kernelHeight = kernel.size
    val kernelWidth = kernel[0].size
    return Array(kernelHeight) { ky ->
        val sourceRow = kernel[kernelHeight - 1 - ky]
        DoubleArray(kernelWidth) { kx -> sourceRow[kernelWidth - 1 - kx] }
    }
}

/**
 * Convolves the tile [yFrom, yTo) x [xFrom, xTo) of [image] with a [flipped]
 * (rotated by 180 degrees, odd-sized) kernel and writes the result into the
 * matching tile of [output]. All indices are clamped to the image by the caller.
 */
fun convolveRegion(
    image: Bitmap,
    flipped: Bitmap,
    output: Bitmap,
    yFrom: Int,
    yTo: Int,
    xFrom: Int,
    xTo: Int,
) {
    val imageHeight = image.size
    val imageWidth = image[0].size
    val radiusY = flipped.size / 2
    val radiusX = flipped[0].size / 2

    // Columns far enough from the left/right edges to be read without wrap-around.
    val fastXFrom = maxOf(xFrom, radiusX)
    val fastXTo = minOf(xTo, imageWidth - radiusX)
    val hasFastColumns = fastXFrom <= fastXTo

    for (y in yFrom until yTo) {
        val outRow = output[y]
        // Rows far enough from the top/bottom edges to be read without wrap-around.
        val fastRow = y >= radiusY && y + radiusY < imageHeight

        if (!fastRow || !hasFastColumns) {
            for (x in xFrom until xTo) {
                outRow[x] = convolvePixelWrapped(image, flipped, y, x, radiusY, radiusX)
            }
            continue
        }

        for (x in xFrom until fastXFrom) {
            outRow[x] = convolvePixelWrapped(image, flipped, y, x, radiusY, radiusX)
        }
        for (x in fastXFrom until fastXTo) {
            outRow[x] = convolvePixelFast(image, flipped, y, x, radiusY, radiusX)
        }
        for (x in fastXTo until xTo) {
            outRow[x] = convolvePixelWrapped(image, flipped, y, x, radiusY, radiusX)
        }
    }
}

/**
 * Hot path: the whole kernel window is inside the image, so no modulo and
 * no bounds special-casing is needed. Row references are hoisted out of the
 * inner loop, each tap costs one kernel load, one image load and one FMA.
 */
private fun convolvePixelFast(
    image: Bitmap,
    flipped: Bitmap,
    y: Int,
    x: Int,
    radiusY: Int,
    radiusX: Int,
): Double {
    var sum = 0.0
    for (ky in flipped.indices) {
        val imageRow = image[y - radiusY + ky]
        val kernelRow = flipped[ky]
        for (kx in kernelRow.indices) {
            sum += kernelRow[kx] * imageRow[x - radiusX + kx]
        }
    }
    // working with images as math objects, so pixels can be (< 0) or (> 255)
    return sum
}

/** Boundary pixels: image access wraps around to the opposite edge. */
private fun convolvePixelWrapped(
    image: Bitmap,
    flipped: Bitmap,
    y: Int,
    x: Int,
    radiusY: Int,
    radiusX: Int,
): Double {
    val imageHeight = image.size
    val imageWidth = image[0].size

    var sum = 0.0
    for (ky in flipped.indices) {
        val imageRow = image[(y - radiusY + ky + imageHeight) % imageHeight]
        val kernelRow = flipped[ky]
        for (kx in kernelRow.indices) {
            sum += kernelRow[kx] * imageRow[(x - radiusX + kx + imageWidth) % imageWidth]
        }
    }
    return sum
}

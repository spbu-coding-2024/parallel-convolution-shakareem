package convolution

import images.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.min

suspend fun pixelwiseConvolve(
    image: Bitmap,
    kernel: Bitmap,
) = parallelConvolve(image, kernel) { output, k ->
    pixelwiseStrategy(image, k, output)
}

suspend fun rowsConvolve(
    image: Bitmap,
    kernel: Bitmap,
) = parallelConvolve(image, kernel) { output, k ->
    rowsStrategy(image, k, output)
}

suspend fun columnsConvolve(
    image: Bitmap,
    kernel: Bitmap,
) = parallelConvolve(image, kernel) { output, k ->
    columnsStrategy(image, k, output)
}

suspend fun gridConvolve(
    image: Bitmap,
    kernel: Bitmap,
    numBlocksY: Int = defaultBlocks(),
    numBlocksX: Int = defaultBlocks(),
) = parallelConvolve(image, kernel) { output, k ->
    gridStrategy(image, k, output, numBlocksY, numBlocksX)
}

suspend fun allProcessorsConvolve(
    image: Bitmap,
    kernel: Bitmap,
) = parallelConvolve(image, kernel) { output, k ->
    allProcessorsStrategy(image, k, output)
}

private fun defaultBlocks(): Int {
    val n = Runtime.getRuntime().availableProcessors()
    return maxOf(1, Math.sqrt(n.toDouble()).toInt())
}

private suspend fun parallelConvolve(
    image: Bitmap,
    kernel: Bitmap,
    dispatchStrategy: suspend (output: Bitmap, kernel: Bitmap) -> Unit,
): Bitmap {
    require(kernel.size % 2 == 1 && kernel[0].size % 2 == 1) { "Kernel dimensions must be odd" }
    val output = Array(image.size) { DoubleArray(image[0].size) }
    coroutineScope {
        dispatchStrategy(output, kernel)
    }
    return output
}

private suspend fun pixelwiseStrategy(
    image: Bitmap,
    kernel: Bitmap,
    output: Bitmap,
) {
    coroutineScope {
        for (y in 0 until image.size) {
            for (x in 0 until image[0].size) {
                launch(Dispatchers.Default) {
                    output[y][x] = convolvePixel(image, kernel, y, x)
                }
            }
        }
    }
}

private suspend fun rowsStrategy(
    image: Bitmap,
    kernel: Bitmap,
    output: Bitmap,
) {
    coroutineScope {
        for (y in 0 until image.size) {
            launch(Dispatchers.Default) {
                for (x in 0 until image[0].size) {
                    output[y][x] = convolvePixel(image, kernel, y, x)
                }
            }
        }
    }
}

private suspend fun columnsStrategy(
    image: Bitmap,
    kernel: Bitmap,
    output: Bitmap,
) {
    coroutineScope {
        for (x in 0 until image[0].size) {
            launch(Dispatchers.Default) {
                for (y in 0 until image.size) {
                    output[y][x] = convolvePixel(image, kernel, y, x)
                }
            }
        }
    }
}

private suspend fun gridStrategy(
    image: Bitmap,
    kernel: Bitmap,
    output: Bitmap,
    numBlocksY: Int,
    numBlocksX: Int,
) {
    val imageHeight = image.size
    val imageWidth = image[0].size
    val blockHeight = (imageHeight + numBlocksY - 1) / numBlocksY
    val blockWidth = (imageWidth + numBlocksX - 1) / numBlocksX

    coroutineScope {
        for (blockY in 0 until numBlocksY) {
            for (blockX in 0 until numBlocksX) {
                val startY = blockY * blockHeight
                val endY = min(startY + blockHeight, imageHeight)
                val startX = blockX * blockWidth
                val endX = min(startX + blockWidth, imageWidth)

                if (startY < imageHeight && startX < imageWidth) {
                    launch(Dispatchers.Default) {
                        for (y in startY until endY) {
                            for (x in startX until endX) {
                                output[y][x] = convolvePixel(image, kernel, y, x)
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun allProcessorsStrategy(
    image: Bitmap,
    kernel: Bitmap,
    output: Bitmap,
) {
    val imageHeight = image.size
    val imageWidth = image[0].size
    val numProcessors = Runtime.getRuntime().availableProcessors()
    val rowsPerChunk = (imageHeight + numProcessors - 1) / numProcessors

    coroutineScope {
        for (chunk in 0 until numProcessors) {
            val startRow = chunk * rowsPerChunk
            val endRow = min(startRow + rowsPerChunk, imageHeight)

            if (startRow < imageHeight) {
                launch(Dispatchers.Default) {
                    for (y in startRow until endRow) {
                        for (x in 0 until imageWidth) {
                            output[y][x] = convolvePixel(image, kernel, y, x)
                        }
                    }
                }
            }
        }
    }
}

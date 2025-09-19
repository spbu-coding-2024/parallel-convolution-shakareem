package convolution

class SerialConvolver : Convolver {
    override fun convolve(image: Array<DoubleArray>, kernel: Array<DoubleArray>): Array<DoubleArray> {
        val imageHeight = image.size
        val imageWidth = image[0].size
        val kernelHeight = kernel.size
        val kernelWidth = kernel[0].size
        require(kernelHeight % 2 == 1 && kernelWidth % 2 == 1) { "Kernel dimensions must be odd" }
        val padHeight = kernelHeight / 2
        val padWidth = kernelWidth / 2

        val output = Array(imageHeight) { DoubleArray(imageWidth) }

        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                var sum = 0.0
                for (ky in 0 until kernelHeight) {
                    for (kx in 0 until kernelWidth) {
                        val imageY = (y - padHeight + ky + imageHeight) % imageHeight
                        val imageX = (x - padWidth + kx + imageWidth) % imageWidth

                        sum += kernel[ky][kx] * image[imageY][imageX]
                    }
                }
                output[y][x] = sum.coerceIn(0.0, 255.0)
            }
        }

        return output
    }
}

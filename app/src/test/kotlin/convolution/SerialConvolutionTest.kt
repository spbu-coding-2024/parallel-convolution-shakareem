package convolution

import filters.BLACK
import filters.ID
import filters.SHIFTLEFT
import filters.SHIFTRIGHT
import images.Bitmap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import util.assertImagesEqual
import util.randomImage
import util.randomKernel
import util.randomOddSize
import java.util.stream.Stream
import kotlin.random.Random

class SerialConvolutionTest {
    private val smallSize = 10
    private val bigSize = 100
    private val sizes = listOf(smallSize, bigSize)

    companion object {
        @JvmStatic
        fun imageSizes(): Stream<Int> = Stream.of(10, 50, 100, 200)
    }

    @Test
    fun `kernel with even dimensions raises an exception`() {
        val image = Array(smallSize) { DoubleArray(smallSize) }
        val evenKernel = arrayOf(doubleArrayOf(1.0, 1.0))
        assertThrows<IllegalArgumentException> {
            serialConvolve(image, evenKernel)
        }
    }

    @Test
    fun `composition of shift left and shift right kernels creates ID`() {
        val comp = serialConvolve(SHIFTLEFT, SHIFTRIGHT)
        assertImagesEqual(comp, ID)
    }

    @Test
    fun `there exist inverse-like shift kernels whose composition is identity`() {
        val rnd = Random(123)
        for (size in sizes) {
            val image = randomImage(size, size, rnd)

            val seq = serialConvolve(serialConvolve(image, SHIFTRIGHT), SHIFTLEFT)
            val direct = serialConvolve(image, arrayOf(doubleArrayOf(1.0)))

            assertImagesEqual(seq, direct)
        }
    }

    @Test
    fun `zero-extend kernel does not change result when centered`() {
        val rnd = Random(7)
        for (size in sizes) {
            repeat(6) {
                val image = randomImage(size, size, rnd)
                val kh = randomOddSize(5, rnd)
                val kw = randomOddSize(5, rnd)
                val k = randomKernel(kh, kw, rnd)

                val extH = if (kh == size) kh else (if (size % 2 == 1) size else size - 1)
                val extW = if (kw == size) kw else (if (size % 2 == 1) size else size - 1)
                val kExtended = Array(extH) { DoubleArray(extW) { 0.0 } }
                val oh = k.size
                val ow = k[0].size
                val offY = extH / 2 - oh / 2
                val offX = extW / 2 - ow / 2
                for (y in 0 until oh) for (x in 0 until ow) kExtended[y + offY][x + offX] = k[y][x]

                val r1 = serialConvolve(image, k)
                val r2 = serialConvolve(image, kExtended)
                assertImagesEqual(r1, r2)
            }
        }
    }

    @Test
    fun `known kernels produce known results`() {
        val rnd = Random(99)
        for (size in sizes) {
            repeat(6) {
                val image = randomImage(size, size, rnd)

                val outZero = serialConvolve(image, BLACK)
                assertImagesEqual(outZero, Array(image.size) { DoubleArray(image[0].size) { 0.0 } })

                val outId = serialConvolve(image, ID)
                assertImagesEqual(outId, image)
            }
        }
    }

    @ParameterizedTest(name = "serial convolution handles image size {0}x{0}")
    @MethodSource("imageSizes")
    fun `serial convolution handles various image sizes`(size: Int) {
        val rnd = Random(size.toLong())
        val image = randomImage(size, size, rnd)
        val kernel = randomKernel(3, 3, rnd)
        val result = serialConvolve(image, kernel)
        assert(result.size == size) { "Output height mismatch" }
        assert(result[0].size == size) { "Output width mismatch" }
    }
}

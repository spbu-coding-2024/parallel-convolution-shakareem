package convolution

import filters.BLACK
import filters.FILTERS
import filters.ID
import filters.SHIFTLEFT
import filters.SHIFTRIGHT
import images.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import util.assertImagesEqual
import util.randomImage
import util.randomKernel
import util.randomOddSize
import java.util.stream.Stream
import kotlin.random.Random

typealias Convolver = (Bitmap, Bitmap) -> Bitmap

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParameterizedConvolutionTest {
    private val smallSize = 10
    private val bigSize = 100
    private val sizes = listOf(smallSize, bigSize)

    companion object {
        @JvmStatic
        fun convolvers(): Stream<Arguments> =
            Stream.of(
                Arguments.of("serial", ::serialConvolve),
                Arguments.of("parallel-pixelwise", { img: Bitmap, k: Bitmap ->
                    runBlocking { pixelwiseConvolve(img, k) }
                }),
                Arguments.of("parallel-rows", { img: Bitmap, k: Bitmap ->
                    runBlocking { rowsConvolve(img, k) }
                }),
                Arguments.of("parallel-columns", { img: Bitmap, k: Bitmap ->
                    runBlocking { columnsConvolve(img, k) }
                }),
                Arguments.of("parallel-grid-2x2", { img: Bitmap, k: Bitmap ->
                    runBlocking { gridConvolve(img, k, numBlocksY = 2, numBlocksX = 2) }
                }),
                Arguments.of("parallel-grid-4x4", { img: Bitmap, k: Bitmap ->
                    runBlocking { gridConvolve(img, k, numBlocksY = 4, numBlocksX = 4) }
                }),
                Arguments.of("parallel-all", { img: Bitmap, k: Bitmap ->
                    runBlocking { allProcessorsConvolve(img, k) }
                }),
            )
    }

    @ParameterizedTest(name = "{0} - kernel with even dimensions raises an exception")
    @MethodSource("convolvers")
    fun `kernel with even dimensions raises an exception`(
        name: String,
        convolve: Convolver,
    ) {
        val image = Array(smallSize) { DoubleArray(smallSize) }
        val evenKernel = arrayOf(doubleArrayOf(1.0, 1.0))
        assertThrows<IllegalArgumentException> {
            convolve(image, evenKernel)
        }
    }

    @ParameterizedTest(name = "{0} - composition of shift left and shift right kernels creates ID")
    @MethodSource("convolvers")
    fun `composition of shift left and shift right kernels creates ID`(
        name: String,
        convolve: Convolver,
    ) {
        val comp = convolve(SHIFTLEFT, SHIFTRIGHT)
        assertImagesEqual(comp, ID)
    }

    @ParameterizedTest(name = "{0} - there exist inverse-like shift kernels whose composition is identity")
    @MethodSource("convolvers")
    fun `there exist inverse-like shift kernels whose composition is identity`(
        name: String,
        convolve: Convolver,
    ) {
        val rnd = Random(123)
        for (size in sizes) {
            val image = randomImage(size, size, rnd)

            val seq = convolve(convolve(image, SHIFTRIGHT), SHIFTLEFT)
            val direct = convolve(image, arrayOf(doubleArrayOf(1.0)))

            assertImagesEqual(seq, direct)
        }
    }

    @ParameterizedTest(name = "{0} - zero-extend kernel does not change result when centered")
    @MethodSource("convolvers")
    fun `zero-extend kernel does not change result when centered`(
        name: String,
        convolve: Convolver,
    ) {
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

                val r1 = convolve(image, k)
                val r2 = convolve(image, kExtended)
                assertImagesEqual(r1, r2)
            }
        }
    }

    @ParameterizedTest(name = "{0} - known kernels produce known results")
    @MethodSource("convolvers")
    fun `known kernels produce known results`(
        name: String,
        convolve: Convolver,
    ) {
        val rnd = Random(99)
        for (size in sizes) {
            repeat(6) {
                val image = randomImage(size, size, rnd)

                val outZero = convolve(image, BLACK)
                assertImagesEqual(outZero, Array(image.size) { DoubleArray(image[0].size) { 0.0 } })

                val outId = convolve(image, ID)
                assertImagesEqual(outId, image)
            }
        }
    }

    @Test
    fun `all parallel strategies produce same result as serial`() {
        val image = randomImage(randomOddSize(100, Random(123)), randomOddSize(100, Random(321)), Random(213))
        for (kernel in FILTERS.values) {
            val serialResult = serialConvolve(image, kernel)
            val pixelwiseResult = runBlocking { pixelwiseConvolve(image, kernel) }
            val rowResult = runBlocking { rowsConvolve(image, kernel) }
            val columnResult = runBlocking { columnsConvolve(image, kernel) }
            val gridResult2x2 = runBlocking { gridConvolve(image, kernel, numBlocksY = 2, numBlocksX = 2) }
            val gridResult4x4 = runBlocking { gridConvolve(image, kernel, numBlocksY = 4, numBlocksX = 4) }
            val allProcessorsResult = runBlocking { allProcessorsConvolve(image, kernel) }

            assertImagesEqual(serialResult, pixelwiseResult)
            assertImagesEqual(serialResult, rowResult)
            assertImagesEqual(serialResult, columnResult)
            assertImagesEqual(serialResult, gridResult2x2)
            assertImagesEqual(serialResult, gridResult4x4)
            assertImagesEqual(serialResult, allProcessorsResult)
        }
    }
}

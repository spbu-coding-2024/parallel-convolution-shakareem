package util

import images.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.random.Random

fun assertImagesEqual(
    img1: Bitmap,
    img2: Bitmap,
    eps: Double = 1e-9
) {
    assertEquals(img1.size, img2.size, "Different image heights")

    for (y in img1.indices) {
        assertEquals(img1[y].size, img2[y].size, "Different row widths at row $y")

        for (x in img1[y].indices) {
            assertEquals(
                img1[y][x],
                img2[y][x],
                eps,
                "Pixels differ at ($y,$x)"
            )
        }
    }
}

fun randomImage(height: Int, width: Int, rnd: Random): Bitmap {
    return Array(height) { DoubleArray(width) { rnd.nextDouble(0.0, 255.0) } }
}

fun randomOddSize(max: Int, rnd: Random): Int {
    val choices = (1..max step 2).toList()
    return choices[rnd.nextInt(choices.size)]
}

fun randomKernel(height: Int, width: Int, rnd: Random): Bitmap {
    val k = Array(height) { DoubleArray(width) { rnd.nextDouble() } }
    var sum = 0.0
    for (row in k) for (v in row) sum += v
    if (sum > 1.0) {
        for (y in k.indices) for (x in k[y].indices) k[y][x] = k[y][x] / sum
    }
    return k
}

package images

import java.awt.image.BufferedImage
import java.awt.image.WritableRaster
import java.io.File
import javax.imageio.ImageIO

typealias Bitmap = Array<DoubleArray>

fun readImage(filePath: String): Bitmap {
    val img = ImageIO.read(File(filePath)) ?: error("Cannot read image $filePath")
    val height = img.height
    val width = img.width
    val bitmap = Array(height) { DoubleArray(width) }

    // Bulk row transfer: one native copy per row instead of a Color object and
    // a colour-space conversion per pixel. The luminance formula and, hence,
    // the produced values are identical to the per-pixel variant.
    val argbRow = IntArray(width)
    for (y in 0 until height) {
        img.getRGB(0, y, width, 1, argbRow, 0, width)
        val outRow = bitmap[y]
        for (x in 0 until width) {
            val argb = argbRow[x]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            outRow[x] = 0.299 * r + 0.587 * g + 0.114 * b
        }
    }
    return bitmap
}

fun writeImage(
    image: Bitmap,
    filePath: String,
) {
    val height = image.size
    val width = image[0].size
    val out = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    val raster = out.raster as WritableRaster

    // setDataElements writes a whole row with a single array copy; setRGB per
    // pixel would make the (I/O-bound) save stage CPU-bound for large images.
    val grayRow = ByteArray(width)
    for (y in 0 until height) {
        val row = image[y]
        for (x in 0 until width) {
            grayRow[x] = row[x].coerceIn(0.0, 255.0).toInt().toByte()
        }
        raster.setDataElements(0, y, width, 1, grayRow)
    }

    ImageIO.write(out, "bmp", File(filePath))
}

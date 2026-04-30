package images

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

typealias Bitmap = Array<DoubleArray>

fun readImage(filePath: String): Bitmap {
    val img = ImageIO.read(File(filePath)) ?: error("Cannot read image $filePath")
    val height = img.height
    val width = img.width
    val bitmap = Array(height) { DoubleArray(width) }

    for (y in 0 until height) {
        for (x in 0 until width) {
            val c = Color(img.getRGB(x, y))
            val gray = 0.299 * c.red + 0.587 * c.green + 0.114 * c.blue
            bitmap[y][x] = gray
        }
    }
    return bitmap
}

fun writeImage(image: Bitmap, filePath: String) {
    val height = image.size
    val width = image[0].size
    val out = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val gray = image[y][x].coerceIn(0.0, 255.0).toInt()
            val rgb = Color(gray, gray, gray).rgb
            out.setRGB(x, y, rgb)
        }
    }

    ImageIO.write(out, "bmp", File(filePath))
}

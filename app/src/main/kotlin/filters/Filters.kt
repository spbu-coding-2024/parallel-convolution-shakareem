package filters

import images.Bitmap

val BLACK: Bitmap = arrayOf(
    doubleArrayOf(0.0, 0.0, 0.0),
    doubleArrayOf(0.0, 0.0, 0.0),
    doubleArrayOf(0.0, 0.0, 0.0)
)

val ID: Bitmap = arrayOf(
    doubleArrayOf(0.0, 0.0, 0.0),
    doubleArrayOf(0.0, 1.0, 0.0),
    doubleArrayOf(0.0, 0.0, 0.0)
)

val SHIFTLEFT: Bitmap = arrayOf(
    doubleArrayOf(0.0, 0.0, 0.0),
    doubleArrayOf(1.0, 0.0, 0.0),
    doubleArrayOf(0.0, 0.0, 0.0)
)

val SHIFTRIGHT: Bitmap = arrayOf(
    doubleArrayOf(0.0, 0.0, 0.0),
    doubleArrayOf(0.0, 0.0, 1.0),
    doubleArrayOf(0.0, 0.0, 0.0)
)

val SHARPEN: Bitmap = arrayOf(
    doubleArrayOf(0.0, -1.0, 0.0),
    doubleArrayOf(-1.0, 5.0, -1.0),
    doubleArrayOf(0.0, -1.0, 0.0)
)

val BLUR: Bitmap = arrayOf(
    doubleArrayOf(1.0 / 9, 1.0 / 9, 1.0 / 9),
    doubleArrayOf(1.0 / 9, 1.0 / 9, 1.0 / 9),
    doubleArrayOf(1.0 / 9, 1.0 / 9, 1.0 / 9)
)

val EDGE: Bitmap = arrayOf(
    doubleArrayOf(-1.0, -1.0, -1.0),
    doubleArrayOf(-1.0, 8.0, -1.0),
    doubleArrayOf(-1.0, -1.0, -1.0)
)

val FILTERS = mapOf(
    "black" to BLACK,
    "id" to ID,
    "left" to SHIFTLEFT,
    "right" to SHIFTRIGHT,
    "sharpen" to SHARPEN,
    "blur" to BLUR,
    "edge" to EDGE
)

package pipeline

import filters.BLUR
import images.Bitmap
import images.readImage
import images.writeImage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import util.assertImagesEqual
import util.randomImage
import java.io.File
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineTest {
    private fun writeBmp(
        bitmap: Bitmap,
        file: File,
    ) = writeImage(bitmap, file.absolutePath)

    private fun readBmp(file: File): Bitmap = readImage(file.absolutePath)

    @Test
    fun `processDataset results match processSingleFile for each file`(
        @TempDir tmpDir: File,
    ) {
        val inDir = File(tmpDir, "in").also { it.mkdirs() }
        val outDirBatch = File(tmpDir, "out_batch")
        val outDirSingle = File(tmpDir, "out_single").also { it.mkdirs() }

        val fileCount = 3
        val images =
            (0 until fileCount).map { i ->
                val img = randomImage(15, 15, Random(i.toLong() + 100))
                writeBmp(img, File(inDir, "img$i.bmp"))
                img
            }

        processDataset(inDir, outDirBatch, BLUR, Mode.SERIAL, PipelineConfig())

        (0 until fileCount).forEach { i ->
            processSingleFile(File(inDir, "img$i.bmp"), outDirSingle, BLUR, Mode.SERIAL)
        }

        (0 until fileCount).forEach { i ->
            val batchResult = readBmp(File(outDirBatch, "convolved_img$i.bmp"))
            val singleResult = readBmp(File(outDirSingle, "convolved_img$i.bmp"))
            assertImagesEqual(batchResult, singleResult)
        }
    }
}

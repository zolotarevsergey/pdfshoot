package uk.mwms1488.pdfshoot

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWriterTest {
    private fun jpeg(width: Int, height: Int, gray: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, if (gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.WHITE; fillRect(0, 0, width, height)
            color = Color.BLACK; drawString("page ${width}x$height", 20, 40)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    /** The file is left in build/ so it can be opened with a real PDF reader as well. */
    @Test
    fun writesPagesAndSetsRotations() {
        val file = File("build/test-pdf/sample.pdf").apply { parentFile.mkdirs(); delete() }
        PdfWriter.write(listOf(jpeg(600, 800), jpeg(800, 600, gray = true), jpeg(300, 300)), file)
        val before = file.length()

        assertEquals(listOf(0, 0, 0), PdfWriter.rotations(file))
        assertTrue(PdfWriter.setRotations(file, mapOf(1 to 90, 2 to 270)))
        assertEquals(listOf(0, 90, 270), PdfWriter.rotations(file))
        assertTrue(PdfWriter.setRotations(file, mapOf(2 to 360, 0 to -90)))
        assertEquals(listOf(270, 90, 0), PdfWriter.rotations(file))
        // Справжній PDF-читач бачить ті самі кути (сам файл — у build/test-pdf).
        val parsed = Regex("/Type /Page /Rotate (\\d+)").findAll(file.readText(Charsets.ISO_8859_1)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(270, 90, 0), parsed)
        assertEquals(null, PdfWriter.rotations(File("build.gradle.kts")))
        assertTrue(!File(file.path + ".tmp").exists())
        assertEquals(before, file.length())
    }
}

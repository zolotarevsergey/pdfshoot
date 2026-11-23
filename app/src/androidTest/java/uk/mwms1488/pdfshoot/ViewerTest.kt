package uk.mwms1488.pdfshoot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Жести перегляду й поворот сторінок — справжніми дотиками (два пальці теж). Знімки екрана після
 * кожного кроку — у /sdcard/Android/data/uk.mwms1488.pdfshoot.debug/files/ui.
 */
@RunWith(AndroidJUnit4::class)
class ViewerTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(context.getExternalFilesDir(null), "ui").apply { mkdirs() }

    private fun pdf(name: String): File {
        val pages = (1..3).map { n ->
            val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
                for (i in 0 until 40) drawText("Сторінка $n, рядок $i: дрібний текст для перевірки чіткості.", 60f, 120f + i * 38, p)
                p.textSize = 160f
                drawText("$n", 560f, 1650f, p)
            }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        }
        return File(out, "$name.pdf").apply { delete(); PdfWriter.write(pages, this) }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400) // чіткий рендер після жесту
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun pinchDoubleTapAndOneFingerZoom() {
        val file = pdf("viewer")
        compose.setContent { ViewerScreen(file, onBack = {}, onShare = {}, onRotate = {}) }
        shot("0-start")
        compose.onRoot().performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(300f, 0f), center + Offset(40f, 0f), center + Offset(300f, 0f), 400)
        }
        shot("1-pinch-out")
        compose.onRoot().performTouchInput {
            pinch(center - Offset(300f, 0f), center - Offset(30f, 0f), center + Offset(300f, 0f), center + Offset(30f, 0f), 400)
        }
        shot("2-pinch-in")
        compose.onRoot().performTouchInput { doubleClick(Offset(width * 0.25f, height * 0.3f)) }
        shot("3-double-tap")
        compose.onRoot().performTouchInput { doubleClick(Offset(width * 0.25f, height * 0.3f)) }
        shot("4-double-tap-back")
        // Подвійний дотик з утриманням: другий дотик тягнемо вниз — збільшення одним пальцем.
        compose.onRoot().performTouchInput {
            val p = Offset(width * 0.5f, height * 0.4f)
            down(p); up()
            advanceEventTime(80)
            down(p)
            repeat(20) { moveBy(Offset(0f, 20f)); advanceEventTime(16) }
            up()
        }
        shot("5-one-finger-zoom")
    }

    @Test
    fun rotateOnePageAndSave() {
        val file = pdf("rotate")
        var saved = false
        compose.setContent { PagesScreen(file, onClose = {}, onSaved = { saved = true }) }
        compose.waitForIdle()
        Thread.sleep(500)
        compose.onAllNodesWithContentDescription("Повернути вправо")[1].performClick()
        compose.onAllNodesWithContentDescription("Повернути вліво")[2].performClick()
        compose.onAllNodesWithContentDescription("Повернути вліво")[2].performClick()
        shot("6-pages")
        compose.onNodeWithText("Зберегти").performClick()
        compose.waitUntil(5000) { saved }
        assertTrue(saved)
        assertEquals(listOf(0, 90, 180), PdfWriter.rotations(file))
    }
}

package uk.mwms1488.pdfshoot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Обробка на справжньому Android (емулятор чи телефон): синтетичне «фото» документа в перспективі,
 * з тінню, на темному й на світлому тлі. Результати лишаються в
 * /sdcard/Android/data/uk.mwms1488.pdfshoot.debug/files/test — їх варто переглянути очима.
 */
@RunWith(AndroidJUnit4::class)
class DocImageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(context.getExternalFilesDir(null), "test").apply { deleteRecursively(); mkdirs() }

    /** Кути аркуша A4, знятого телефоном під кутом: справжня проєкція камери (фокус 3200 px). */
    private val corners: List<Offset> = run {
        val f = 3200.0
        val ax = Math.toRadians(18.0) // нахил «від себе»
        val ay = Math.toRadians(-12.0)
        val az = Math.toRadians(6.0)
        listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            var x = sx * 105.0; var y = sy * 148.5; var z = 0.0 // мм, центр аркуша
            // поворот навколо z, потім x, потім y
            val x1 = x * kotlin.math.cos(az) - y * kotlin.math.sin(az); val y1 = x * kotlin.math.sin(az) + y * kotlin.math.cos(az)
            x = x1; y = y1
            val y2 = y * kotlin.math.cos(ax) - z * kotlin.math.sin(ax); val z2 = y * kotlin.math.sin(ax) + z * kotlin.math.cos(ax)
            y = y2; z = z2
            val x3 = x * kotlin.math.cos(ay) + z * kotlin.math.sin(ay); val z3 = -x * kotlin.math.sin(ay) + z * kotlin.math.cos(ay)
            x = x3; z = z3 + 420.0 // відстань до камери
            Offset((1500 + f * x / z).toFloat(), (2000 + f * y / z).toFloat())
        }
    }

    @Test fun darkBackground() = run("dark", Color.rgb(92, 70, 52))

    @Test fun lightBackground() = run("light", Color.rgb(200, 198, 190))

    /** Той самий кадр, збережений «боком», як його віддає камера телефона в портреті. */
    @Test fun rotatedSource() {
        val photo = photo(Color.rgb(92, 70, 52))
        val sideways = Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, Matrix().apply { postRotate(-90f) }, true)
        val file = File(out, "sideways.jpg").apply { outputStream().use { sideways.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val quad = DocImage.detect(file, 90)
        assertNotNull("документ не знайдено", quad)
        assertClose(quad!!, photo.width, photo.height)
    }

    /**
     * Глибока тінь від руки чи телефона (папір утричі темніший) з розмитим краєм — така лишалась
     * сірою плямою: її приймали за фото. Має стати білим папером; темне фото поруч — лишитись темним.
     */
    @Test fun handShadow() {
        val photo = photo(Color.rgb(92, 70, 52), handShadow = true)
        val file = File(out, "hand-photo.jpg").apply { outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val quad = DocImage.detect(file, 0)
        assertNotNull("документ не знайдено", quad)
        val jpeg = DocImage.process(file, 0, quad!!, 0, Filter.Auto)
        File(out, "hand-Auto.jpg").writeBytes(jpeg)
        val page = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val paper = brightness(page, 1500f / 2100, 1820f / 2970)
        assertTrue("папір у тіні не вибілений: $paper", paper > 225)
        val dark = brightness(page, 0.24f, 0.74f)
        assertTrue("фото вибілене: $dark", dark < 170)
    }

    private fun run(name: String, background: Int) {
        val photo = photo(background)
        val file = File(out, "$name-photo.jpg").apply { outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val started = System.nanoTime()
        val quad = DocImage.detect(file, 0)
        val detectMs = (System.nanoTime() - started) / 1_000_000
        assertNotNull("документ не знайдено ($name)", quad)
        assertClose(quad!!, photo.width, photo.height)
        val size = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        for (filter in Filter.entries) {
            val t = System.nanoTime()
            val jpeg = DocImage.process(file, 0, quad, 0, filter)
            File(out, "$name-${filter.name}.jpg").writeBytes(jpeg)
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, size)
            val aspect = size.outWidth.toFloat() / size.outHeight
            assertTrue("пропорції ${size.outWidth}×${size.outHeight}, а мали б бути A4", abs(aspect - 0.7071f) < 0.01f)
            android.util.Log.i("DocImageTest", "$name ${filter.name}: ${jpeg.size / 1024} KB, ${(System.nanoTime() - t) / 1_000_000} ms")
            if (filter == Filter.Auto) {
                val page = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                // Порожнє поле вгорі ліворуч — у глибокій тіні (центр тіні там): має стати білим.
                val shadowed = brightness(page, 0.045f, 0.035f)
                assertTrue("поле в тіні не вибілене: $shadowed", shadowed > 225)
                // Темне фото на аркуші — не тінь: лишається темним.
                val photo = brightness(page, 0.24f, 0.74f)
                assertTrue("фото вибілене: $photo", photo < 170)
            }
        }
        android.util.Log.i("DocImageTest", "$name detect: $detectMs ms, quad=$quad")
    }

    /** Середня яскравість квадратика 21×21 у точці (частки ширини й висоти). */
    private fun brightness(page: Bitmap, fx: Float, fy: Float): Int {
        val cx = (page.width * fx).toInt(); val cy = (page.height * fy).toInt()
        var sum = 0; var n = 0
        for (y in cy - 10..cy + 10) for (x in cx - 10..cx + 10) {
            val c = page.getPixel(x, y)
            sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3; n++
        }
        return sum / n
    }

    private fun assertClose(quad: Quad, w: Int, h: Int) {
        quad.corners.zip(corners).forEach { (found, real) ->
            val dx = abs(found.x * w - real.x)
            val dy = abs(found.y * h - real.y)
            assertTrue("кут $real знайдено як ${Offset(found.x * w, found.y * h)}", dx < w * 0.02f && dy < h * 0.02f)
        }
    }

    /** 12-Мп «фото»: аркуш з текстом, таблицею, печаткою й фото в перспективі, з тінню й шумом. */
    private fun photo(background: Int, handShadow: Boolean = false): Bitmap {
        val page = Bitmap.createBitmap(2100, 2970, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.rgb(250, 249, 245))
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 30) }
            text.textSize = 70f; text.isFakeBoldText = true
            drawText("АКТ ПРИЙМАННЯ-ПЕРЕДАЧІ № 1488", 260f, 240f, text)
            text.isFakeBoldText = false
            text.textSize = 34f
            var y = 360f
            repeat(14) {
                drawText("Рядок тексту $it: постачальник передав, а покупець прийняв товар належної якості.", 150f, y, text)
                y += 52f
            }
            val grid = Paint().apply { color = Color.rgb(40, 40, 40); strokeWidth = 3f; style = Paint.Style.STROKE }
            for (r in 0..8) drawLine(150f, 1150f + r * 70f, 1950f, 1150f + r * 70f, grid)
            for (c in listOf(150f, 300f, 1100f, 1400f, 1650f, 1950f)) drawLine(c, 1150f, c, 1710f, grid)
            text.textSize = 28f
            for (r in 0 until 8) drawText("${r + 1}   Позиція номер ${r + 1}        шт   ${(r + 3) * 7}   ${(r + 1) * 125},00", 170f, 1195f + r * 70f, text)
            val stamp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 30, 60, 200); style = Paint.Style.STROKE; strokeWidth = 8f }
            drawCircle(1600f, 2350f, 210f, stamp)
            drawCircle(1600f, 2350f, 150f, stamp)
            val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 30, 30); textSize = 44f }
            drawText("ПІДПИСАНО", 1380f, 2700f, red)
            val photo = Paint().apply { shader = LinearGradient(150f, 1900f, 850f, 2500f, Color.rgb(60, 90, 70), Color.rgb(170, 140, 90), Shader.TileMode.CLAMP) }
            drawRect(150f, 1900f, 850f, 2500f, photo)
        }
        val shot = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888)
        Canvas(shot).apply {
            drawColor(background)
            val rnd = Random(7)
            val grain = Paint()
            repeat(4000) {
                grain.color = Color.argb(40, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                drawRect(rnd.nextFloat() * 3000, rnd.nextFloat() * 4000, rnd.nextFloat() * 3000 + 60, rnd.nextFloat() * 4000 + 8, grain)
            }
            val m = Matrix()
            val src = floatArrayOf(0f, 0f, 2100f, 0f, 2100f, 2970f, 0f, 2970f)
            val dst = corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
            m.setPolyToPoly(src, 0, dst, 0, 4)
            drawBitmap(page, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            // Нерівне світло: тінь від руки/телефона зверху ліворуч і загальне затемнення до низу.
            drawRect(0f, 0f, 3000f, 4000f, Paint().apply {
                shader = RadialGradient(700f, 900f, 1500f, Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
            })
            drawRect(0f, 0f, 3000f, 4000f, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, 4000f, Color.argb(0, 0, 0, 0), Color.argb(70, 30, 20, 0), Shader.TileMode.CLAMP)
            })
            if (handShadow) {
                val c = floatArrayOf(1500f, 1880f).also { m.mapPoints(it) }
                val shade = Color.argb(175, 10, 10, 20)
                drawRect(0f, 0f, 3000f, 4000f, Paint().apply {
                    shader = RadialGradient(c[0], c[1], 700f, intArrayOf(shade, shade, Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                })
            }
        }
        return shot
    }
}

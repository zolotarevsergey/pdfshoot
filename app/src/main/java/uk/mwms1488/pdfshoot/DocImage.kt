package uk.mwms1488.pdfshoot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Як покращувати сторінку. */
enum class Filter(val label: String) {
    Auto("Авто"),        // колір, рівне освітлення без тіней, білий папір, різкість
    Gray("Ч/Б"),         // відтінки сірого з сильним контрастом — для тексту
    Original("Оригінал"), // лише обрізка й вирівнювання
}

/** Чотири кути документа в частках кадру (0..1), за годинниковою від лівого верхнього. */
data class Quad(val tl: Offset, val tr: Offset, val br: Offset, val bl: Offset) {
    val corners: List<Offset> get() = listOf(tl, tr, br, bl)

    fun with(index: Int, p: Offset): Quad {
        val c = Offset(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
        return when (index) {
            0 -> copy(tl = c)
            1 -> copy(tr = c)
            2 -> copy(br = c)
            else -> copy(bl = c)
        }
    }

    /** Опуклий чотирикутник: лише такий можна вирівняти. */
    fun isConvex(): Boolean {
        val p = corners
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Поворот на 90° за годинниковою разом із кадром (кути лишаються в тому самому порядку). */
    fun rotated(degrees: Int): Quad {
        fun r(p: Offset): Offset = when (((degrees % 360) + 360) % 360) {
            90 -> Offset(1 - p.y, p.x)
            180 -> Offset(1 - p.x, 1 - p.y)
            270 -> Offset(p.y, 1 - p.x)
            else -> p
        }
        return order(corners.map(::r))
    }

    companion object {
        val Full = Quad(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))

        /** Розставляє чотири точки за годинниковою, першою — найближчу до лівого верхнього кута. */
        fun order(points: List<Offset>): Quad {
            val cx = points.map { it.x }.average().toFloat()
            val cy = points.map { it.y }.average().toFloat()
            val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) } // при осі y донизу — за годинниковою
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val p = List(4) { sorted[(start + it) % 4] }
            return Quad(p[0], p[1], p[2], p[3])
        }
    }
}

/**
 * Уся робота з пікселями — OpenCV (нативний код): пошук країв документа, вирівнювання перспективи
 * на повній роздільності, вирівнювання освітлення й контрасту, різкість, JPEG високої якості.
 */
object DocImage {
    /** Довша сторона готової сторінки: A4 при 300 dpi — 3508 px, із запасом. */
    private const val MAX_SIDE = 4200

    /** Довша сторона кадру, з яким працюємо взагалі (50-Мп знімки з галереї зменшуються до такого). */
    private const val MAX_SOURCE = 6000

    private const val JPEG_QUALITY = 93

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV не завантажився" }
    }

    /** Викликати заздалегідь у фоні, щоб перший знімок не чекав на завантаження бібліотеки. */
    fun warmUp() = Unit

    // ---------- Читання й запис ----------

    /** Знімок як матриця BGR, повернутий рівно. [maxSide] — орієнтовна межа (зменшення кратне 2). */
    fun decode(file: File, rotation: Int, maxSide: Int = MAX_SOURCE): Mat {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longSide = max(bounds.outWidth, bounds.outHeight)
        var reduce = 1
        while (reduce < 8 && longSide / (reduce * 2) >= maxSide) reduce *= 2
        val mode = when (reduce) {
            2 -> Imgcodecs.IMREAD_REDUCED_COLOR_2
            4 -> Imgcodecs.IMREAD_REDUCED_COLOR_4
            8 -> Imgcodecs.IMREAD_REDUCED_COLOR_8
            else -> Imgcodecs.IMREAD_COLOR
        }
        // Поворот беремо з камери (чи EXIF галереї) самі, тож власний поворот декодера вимикаємо.
        val mat = Imgcodecs.imread(file.path, mode or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        require(!mat.empty()) { "Не вдалося прочитати знімок" }
        return rotate(mat, rotation)
    }

    private fun rotate(mat: Mat, degrees: Int): Mat {
        val code = when (((degrees % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return mat
        }
        val out = Mat()
        Core.rotate(mat, out, code)
        mat.release()
        return out
    }

    private fun encode(mat: Mat): ByteArray {
        val params = if (mat.channels() == 1) {
            MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY, Imgcodecs.IMWRITE_JPEG_OPTIMIZE, 1)
        } else {
            // 4:4:4 — колір без зменшення: червоні печатки й кольоровий текст лишаються чіткими.
            MatOfInt(
                Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY,
                Imgcodecs.IMWRITE_JPEG_OPTIMIZE, 1,
                Imgcodecs.IMWRITE_JPEG_SAMPLING_FACTOR, Imgcodecs.IMWRITE_JPEG_SAMPLING_FACTOR_444,
            )
        }
        val buffer = MatOfByte()
        check(Imgcodecs.imencode(".jpg", mat, buffer, params)) { "Не вдалося стиснути сторінку" }
        return buffer.toArray().also { buffer.release() }
    }

    /** Зменшена копія для екрана. */
    fun toBitmap(mat: Mat, maxSide: Int): Bitmap {
        val f = min(1.0, maxSide.toDouble() / max(mat.cols(), mat.rows()))
        val small = Mat()
        if (f < 1.0) Imgproc.resize(mat, small, Size(), f, f, Imgproc.INTER_AREA) else mat.copyTo(small)
        val rgba = Mat()
        Imgproc.cvtColor(small, rgba, if (small.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_BGR2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        small.release(); rgba.release()
        return bitmap
    }

    fun preview(file: File, rotation: Int, maxSide: Int): Bitmap {
        val mat = decode(file, rotation, maxSide)
        return toBitmap(mat, maxSide).also { mat.release() }
    }

    // ---------- Сторінка ----------

    /** Готова сторінка (JPEG): вирівняна за [quad], повернута на [turn], покращена [filter]. */
    fun process(source: File, rotation: Int, quad: Quad, turn: Int, filter: Filter): ByteArray {
        val src = decode(source, rotation)
        var page = warp(src, quad)
        src.release()
        page = rotate(page, turn)
        val out = when (filter) {
            Filter.Original -> page
            Filter.Auto -> enhanceColor(page).also { page.release() }
            Filter.Gray -> enhanceGray(page).also { page.release() }
        }
        return encode(out).also { out.release() }
    }

    /** Кути документа на знімку; null — документ не знайдено. */
    fun detect(file: File, rotation: Int): Quad? {
        val mat = decode(file, rotation, maxSide = 1200)
        return detect(mat).also { mat.release() }
    }

    private fun warp(src: Mat, quad: Quad): Mat {
        val w = src.cols().toDouble()
        val h = src.rows().toDouble()
        val p = quad.corners.map { Point(it.x * w, it.y * h) }
        val edgeW = max(dist(p[0], p[1]), dist(p[3], p[2]))
        val edgeH = max(dist(p[0], p[3]), dist(p[1], p[2]))
        // Справжні пропорції аркуша, а не довжини сторін на знімку: при нахилі вони брешуть.
        val aspect = trueAspect(p, w, h) ?: (edgeW / edgeH)
        // Розмір — щоб жодна сторона не стала дрібнішою, ніж на знімку: роздільність не губиться.
        val height = max(edgeH, edgeW / aspect)
        val width = height * aspect
        val scale = min(1.0, MAX_SIDE / max(width, height))
        val outW = (width * scale).roundToInt().coerceAtLeast(16)
        val outH = (height * scale).roundToInt().coerceAtLeast(16)
        val from = MatOfPoint2f(*p.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        // Кубічна інтерполяція: при вирівнюванні текст лишається чіткішим, ніж з лінійною.
        Imgproc.warpPerspective(src, out, m, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        from.release(); to.release(); m.release()
        return out
    }

    /**
     * Ширина/висота аркуша за чотирма кутами на знімку (Zhang & He, «Whiteboard scanning»): з
     * перспективи відновлюються фокусна відстань і справжні пропорції. Головна точка — центр кадру.
     * Близьке до A4 стає рівно A4. null — геометрія вироджена (знято майже прямо), тоді вистачає сторін.
     */
    private fun trueAspect(p: List<Point>, w: Double, h: Double): Double? {
        val cx = w / 2
        val cy = h / 2
        fun v(pt: Point) = doubleArrayOf(pt.x - cx, pt.y - cy, 1.0)
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
        )
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        // Позначення статті: m1 — лівий верхній, m2 — правий верхній, m3 — лівий нижній, m4 — правий нижній.
        val m1 = v(p[0]); val m2 = v(p[1]); val m4 = v(p[2]); val m3 = v(p[3])
        val c14 = cross(m1, m4)
        val k2 = dot(c14, m3) / dot(cross(m2, m4), m3)
        val k3 = dot(c14, m2) / dot(cross(m3, m4), m2)
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val edgeAspect = (dist(p[0], p[1]) + dist(p[3], p[2])) / (dist(p[0], p[3]) + dist(p[1], p[2]))
        val f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        var aspect = if (f2.isFinite() && f2 > 0 && abs(n2[2] * n3[2]) > 1e-9) {
            sqrt((n2[0] * n2[0] / f2 + n2[1] * n2[1] / f2 + n2[2] * n2[2]) / (n3[0] * n3[0] / f2 + n3[1] * n3[1] / f2 + n3[2] * n3[2]))
        } else {
            return null
        }
        // Фокус, неможливий для телефона (поле зору за межами ~25°…120°), — оцінка ненадійна.
        val f = sqrt(f2)
        val diag = hypot(w, h)
        if (f < diag * 0.3 || f > diag * 4) return null
        if (!aspect.isFinite() || aspect / edgeAspect !in 0.6..1.6) return null
        for (paper in listOf(1 / sqrt(2.0), sqrt(2.0))) {
            if (abs(aspect - paper) / paper < 0.04) aspect = paper
        }
        return aspect
    }

    // ---------- Покращення ----------

    /**
     * Освітлення паперу: знімок без тексту й дрібних деталей (розмита «максимум-карта»). Поділ
     * знімка на неї вирівнює тіні й нерівне світло, а папір робить білим і нейтральним.
     */
    private fun background(img: Mat): Mat {
        val f = 320.0 / max(img.cols(), img.rows())
        val small = Mat()
        Imgproc.resize(img, small, Size(), f, f, Imgproc.INTER_AREA)
        fun paper(kernelSize: Double, sigma: Double): Mat {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kernelSize, kernelSize))
            val m = Mat()
            Imgproc.dilate(small, m, kernel) // світле «з'їдає» темне, вужче за ядро
            Imgproc.GaussianBlur(m, m, Size(0.0, 0.0), sigma)
            m.convertTo(m, CvType.CV_32F)
            kernel.release()
            return m
        }
        // Дрібне ядро стирає лише текст і точно повторює тіні. Гладка поверхня, підігнана тільки
        // під папір, не бачить і великих фото чи заливок.
        val fine = paper(9.0, 6.0)
        val coarse = paperSurface(fine)
        // Де дрібна оцінка набагато темніша за велику — там вміст (фото), а не тінь: беремо велику,
        // і фото лишається фото, а не вибілюється.
        val weight = Mat()
        Core.add(coarse, Scalar.all(1.0), weight)
        Core.divide(fine, weight, weight)
        Core.subtract(weight, Scalar.all(0.62), weight)
        Core.multiply(weight, Scalar.all(1 / 0.15), weight)
        Core.min(weight, Scalar.all(1.0), weight)
        Core.max(weight, Scalar.all(0.0), weight)
        // Біля краю фото дрібна оцінка — суміш паперу й фото, і край висвітлювався б обідком:
        // зону вмісту розширюємо на розмір ядра, перехід розмиваємо.
        val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(17.0, 17.0))
        Imgproc.erode(weight, weight, grow)
        grow.release()
        Imgproc.GaussianBlur(weight, weight, Size(0.0, 0.0), 4.0)
        val bg32 = Mat()
        Core.subtract(fine, coarse, bg32)
        Core.multiply(bg32, weight, bg32)
        Core.add(bg32, coarse, bg32)
        val bgSmall = Mat()
        bg32.convertTo(bgSmall, CvType.CV_8U)
        val bg = Mat()
        Imgproc.resize(bgSmall, bg, img.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        listOf(small, fine, coarse, weight, bg32, bgSmall).forEach { it.release() }
        return bg
    }

    /**
     * Освітлення паперу як гладка поверхня (многочлен 3-го степеня від x, y), підігнана до точок
     * паперу: точки, помітно темніші за поверхню (фото, заливки, текст), відкидаються, і підгонка
     * повторюється. Так велике фото на аркуші не вважається «тінню» і не вибілюється.
     */
    private fun paperSurface(fine: Mat): Mat {
        val cols = fine.cols()
        val rows = fine.rows()
        val ch = fine.channels()
        val data = FloatArray(cols * rows * ch)
        fine.get(0, 0, data)
        val step = 6
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val idx = ArrayList<Int>()
        for (y in step / 2 until rows step step) for (x in step / 2 until cols step step) {
            xs += x * 2.0 / cols - 1; ys += y * 2.0 / rows - 1; idx += (y * cols + x) * ch
        }
        val n = idx.size
        fun gray(i: Int): Double { var s = 0.0; for (c in 0 until ch) s += data[idx[i] + c]; return s / ch }
        val g = DoubleArray(n) { gray(it) }
        var inlier = BooleanArray(n) { true }
        var coef = fit(xs, ys, inlier) { g[it] }
        repeat(4) {
            val next = BooleanArray(n) { g[it] >= 0.9 * poly(coef, xs[it], ys[it]) }
            if (next.count { it } < 30) return@repeat
            inlier = next
            coef = fit(xs, ys, inlier) { g[it] }
        }
        val out = Mat(rows, cols, fine.type())
        val surface = FloatArray(cols * rows * ch)
        for (c in 0 until ch) {
            val k = fit(xs, ys, inlier) { data[idx[it] + c].toDouble() }
            for (y in 0 until rows) {
                val yn = y * 2.0 / rows - 1
                for (x in 0 until cols) surface[(y * cols + x) * ch + c] = poly(k, x * 2.0 / cols - 1, yn).toFloat()
            }
        }
        out.put(0, 0, surface)
        return out
    }

    private fun terms(x: Double, y: Double) = doubleArrayOf(1.0, x, y, x * x, x * y, y * y, x * x * x, x * x * y, x * y * y, y * y * y)

    private fun poly(k: DoubleArray, x: Double, y: Double): Double {
        val t = terms(x, y); var s = 0.0
        for (i in t.indices) s += k[i] * t[i]
        return s
    }

    /** Найменші квадрати: нормальні рівняння 10×10, метод Гаусса з вибором головного елемента. */
    private fun fit(xs: List<Double>, ys: List<Double>, use: BooleanArray, value: (Int) -> Double): DoubleArray {
        val m = 10
        val a = Array(m) { DoubleArray(m + 1) }
        for (i in xs.indices) {
            if (!use[i]) continue
            val t = terms(xs[i], ys[i]); val v = value(i)
            for (r in 0 until m) {
                for (c in 0 until m) a[r][c] += t[r] * t[c]
                a[r][m] += t[r] * v
            }
        }
        for (r in 0 until m) a[r][r] += 1e-6 // на випадок мало точок у куті
        for (col in 0 until m) {
            val pivot = (col until m).maxBy { abs(a[it][col]) }
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (r in 0 until m) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..m) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(m) { a[it][m] / a[it][it] }
    }

    /** Чорна точка [black] стає 0, біла [white] — 255. */
    private fun levels(img: Mat, black: Double, white: Double) {
        val alpha = 255.0 / (white - black)
        img.convertTo(img, -1, alpha, -black * alpha)
    }

    private fun sharpen(img: Mat, amount: Double, sigma: Double) {
        val blurred = Mat()
        Imgproc.GaussianBlur(img, blurred, Size(0.0, 0.0), sigma)
        Core.addWeighted(img, 1 + amount, blurred, -amount, 0.0, img)
        blurred.release()
    }

    private fun enhanceColor(src: Mat): Mat {
        val bg = background(src)
        val out = Mat()
        Core.divide(src, bg, out, 255.0)
        bg.release()
        levels(out, black = 20.0, white = 242.0)
        sharpen(out, amount = 0.6, sigma = 1.1)
        return out
    }

    private fun enhanceGray(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val bg = background(gray)
        val out = Mat()
        Core.divide(gray, bg, out, 255.0)
        gray.release(); bg.release()
        levels(out, black = 55.0, white = 232.0)
        sharpen(out, amount = 0.8, sigma = 1.0)
        return out
    }

    // ---------- Пошук документа ----------

    /** Кути документа на кадрі (BGR або сірий); null — не знайдено. */
    fun detect(image: Mat): Quad? {
        val gray = Mat()
        if (image.channels() == 1) image.copyTo(gray)
        else Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        val f = 480.0 / max(gray.cols(), gray.rows())
        val small = Mat()
        Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
        gray.release()

        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
        // «Закриття» стирає темний текст на світлому папері, а краї аркуша лишає.
        val close = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, close)

        val w = small.cols().toDouble()
        val h = small.rows().toDouble()
        val minArea = w * h * 0.12
        val candidates = ArrayList<List<Point>>()

        // Карта країв, за якою перевіряємо кандидатів: межа справжнього аркуша лежить на краях.
        val support = Mat()
        Imgproc.Canny(small, support, 30.0, 90.0)
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(support, support, k3)

        for ((lo, hi) in listOf(15.0 to 45.0, 30.0 to 90.0, 60.0 to 180.0)) {
            val edges = Mat()
            Imgproc.Canny(small, edges, lo, hi)
            Imgproc.dilate(edges, edges, k3)
            quadsFrom(edges, minArea, candidates)
            edges.release()
        }
        // Світлий аркуш на темнішому тлі: поріг Оцу дає його контур навіть із нечіткими краями.
        val binary = Mat()
        Imgproc.threshold(small, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        quadsFrom(binary, minArea, candidates)
        binary.release()

        var best: List<Point>? = null
        var bestScore = 0.0
        for (c in candidates) {
            if (!reasonable(c, w, h)) continue
            val area = polygonArea(c) / (w * h)
            val edge = edgeSupport(c, support)
            if (edge < 0.55) continue
            val score = edge + 0.35 * area
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        small.release(); support.release(); close.release(); k3.release()
        return best?.let { pts -> inset(Quad.order(pts.map { Offset((it.x / w).toFloat(), (it.y / h).toFloat()) })) }
    }

    /**
     * Знайдений край лежить на межі аркуша й тла з точністю до пікселя зменшеного кадру: трохи
     * всередину, щоб у PDF не потрапила смужка столу.
     */
    private fun inset(q: Quad): Quad {
        val c = q.corners
        val cx = c.map { it.x }.average().toFloat()
        val cy = c.map { it.y }.average().toFloat()
        return Quad.order(c.map { Offset(it.x + (cx - it.x) * 0.012f, it.y + (cy - it.y) * 0.012f) })
    }

    private fun quadsFrom(binary: Mat, minArea: Double, out: MutableList<List<Point>>) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val big = contours.map { it to Imgproc.contourArea(it) }.sortedByDescending { it.second }.take(6)
        for ((contour, _) in big) {
            val hullIdx = MatOfInt()
            Imgproc.convexHull(contour, hullIdx)
            val pts = contour.toArray()
            val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
            hullIdx.release()
            if (Imgproc.contourArea(hull) >= minArea) {
                val perimeter = Imgproc.arcLength(hull, true)
                // Що грубіше спрощення, то менше кутів; беремо перше, де їх рівно чотири.
                for (k in 1..12) {
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(hull, approx, perimeter * 0.008 * k, true)
                    val n = approx.total()
                    if (n == 4L) out += approx.toList()
                    approx.release()
                    if (n <= 4L) break
                }
            }
            hull.release()
        }
        contours.forEach { it.release() }
    }

    /** Опуклий, кути між 40° і 140°, не надто вузький. */
    private fun reasonable(p: List<Point>, w: Double, h: Double): Boolean {
        val q = Quad.order(p.map { Offset((it.x / w).toFloat(), (it.y / h).toFloat()) })
        if (!q.isConvex()) return false
        val c = q.corners.map { Point(it.x * w, it.y * h) }
        for (i in 0 until 4) {
            val a = c[(i + 3) % 4]; val b = c[i]; val d = c[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = d.x - b.x; val v2y = d.y - b.y
            val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y))
            val angle = Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
            if (angle < 40 || angle > 140) return false
        }
        val sides = (0 until 4).map { dist(c[it], c[(it + 1) % 4]) }
        return sides.min() > 0.12 * min(w, h)
    }

    /** Частка периметра чотирикутника, що лежить на краях зображення. */
    private fun edgeSupport(p: List<Point>, edges: Mat): Double {
        val q = Quad.order(p.map { Offset(it.x.toFloat(), it.y.toFloat()) }).corners
        val data = ByteArray((edges.total() * edges.channels()).toInt())
        edges.get(0, 0, data)
        val cols = edges.cols()
        val rows = edges.rows()
        var hit = 0
        var total = 0
        var all = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val steps = max(8, hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toInt())
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                all++
                // Межа кадру: аркуш, що виходить за край, краю там не має — ці точки не рахуємо зовсім.
                if (x <= 2 || y <= 2 || x >= cols - 3 || y >= rows - 3) continue
                total++
                if (data[y * cols + x].toInt() != 0) hit++
            }
        }
        // Чотирикутник, що здебільшого лежить на межі кадру, — це сам кадр, а не документ.
        if (total < all * 0.35) return 0.0
        return hit.toDouble() / total
    }

    private fun polygonArea(p: List<Point>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    private fun dist(a: Point, b: Point) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    /** Кадр аналізу камери (лише яскравість, Y): швидкий пошук для рамки на екрані. */
    fun detectLuma(bytes: ByteArray, width: Int, height: Int): Quad? {
        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, bytes)
        return detect(mat).also { mat.release() }
    }
}

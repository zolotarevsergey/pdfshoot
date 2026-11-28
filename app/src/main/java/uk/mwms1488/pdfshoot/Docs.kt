package uk.mwms1488.pdfshoot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Збережений скан: PDF у внутрішній пам'яті застосунку. */
data class Doc(val file: File, val pages: Int, val modified: Long) {
    val title: String get() = file.nameWithoutExtension
}

object Docs {
    private fun dir(context: Context) = File(context.filesDir, "scans").apply { mkdirs() }

    /** Усі скани, найновіші зверху. Читає диск — викликати не з головного потоку. */
    fun list(context: Context): List<Doc> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val pages = runCatching { PdfDoc(f).use { it.pageCount } }.getOrDefault(0)
                Doc(f, pages, f.lastModified())
            }

    /** Назва, яку пропонуємо для нового скану. */
    fun defaultTitle(): String = "Скан " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())

    /** Збирає PDF із готових сторінок (JPEG). Зайнята назва отримує « (2)», « (3)»… */
    fun save(context: Context, pages: List<File>, title: String): File {
        val jpegs = pages.map { it.readBytes() }
        val target = freeName(dir(context), cleanTitle(title).ifEmpty { defaultTitle() })
        PdfWriter.write(jpegs, target)
        return target
    }

    /** Перейменовує; null — якщо назва порожня або вже зайнята. */
    fun rename(file: File, newTitle: String): File? {
        val clean = cleanTitle(newTitle)
        if (clean.isEmpty()) return null
        val target = File(file.parentFile, "$clean.pdf")
        if (target == file) return file
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData потрібен, щоб вікно «Поширити» показало прев'ю й отримало право читати файл.
        send.clipData = android.content.ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, "Поширити PDF"))
    }

    /** Копія встановленого APK у кеші — з назвою, зрозумілою одержувачу. */
    fun appApk(context: Context): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(dir, "PDF-Shoot-${Updater.installedName(context)}.apk")
        dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        File(context.applicationInfo.sourceDir).copyTo(target, overwrite = true)
        return target
    }

    fun shareApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(apk.name, uri)
        context.startActivity(Intent.createChooser(send, "Поділитися застосунком"))
    }

    fun shareLink(context: Context) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "PDF Shoot — сканер документів у PDF для Android: ${Updater.APK_LINK}")
        context.startActivity(Intent.createChooser(send, "Поділитися посиланням"))
    }

    /**
     * Копія PDF, відкритого з іншої програми, у кеші — під його справжньою назвою. Кожен файл у своїй
     * теці (назви можуть збігатись); старші за добу копії прибираються.
     */
    fun copyExternal(context: Context, uri: Uri): File {
        val root = File(context.cacheDir, "open").apply { mkdirs() }
        val now = System.currentTimeMillis()
        root.listFiles()?.filter { now - it.lastModified() > 24 * 3600_000L }?.forEach { it.deleteRecursively() }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        val title = cleanTitle(name.orEmpty().removeSuffix(".pdf").removeSuffix(".PDF")).ifEmpty { "Документ" }
        val target = File(File(root, now.toString()).apply { mkdirs() }, "$title.pdf")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            target.parentFile?.deleteRecursively()
            throw e
        }
        return target
    }

    /** Чужий PDF — до своїх сканів. Зайнята назва отримує « (2)». */
    fun keep(context: Context, file: File): File =
        file.copyTo(freeName(dir(context), cleanTitle(file.nameWithoutExtension).ifEmpty { defaultTitle() }))

    /** Прибирає знаки, яких не можна мати в назві файлу. */
    private fun cleanTitle(title: String): String =
        title.replace(Regex("""[/\\:*?"<>|\n\r]"""), " ").trim().trim('.').trim()

    private fun freeName(dir: File, base: String): File {
        var candidate = File(dir, "$base.pdf")
        var n = 2
        while (candidate.exists()) candidate = File(dir, "$base (${n++}).pdf")
        return candidate
    }
}

/** Відкритий PDF. Системний PdfRenderer тримає лише одну сторінку за раз, тому все під замком. */
class PdfDoc(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Ширина / висота кожної сторінки — щоб список одразу мав правильні розміри, ще до рендеру. */
    val ratios: List<Float> = List(pageCount) { i ->
        renderer.openPage(i).use { it.width.toFloat() / it.height }
    }

    @Synchronized
    fun render(index: Int, width: Int): Bitmap? {
        if (closed || index !in 0 until pageCount) return null
        renderer.openPage(index).use { page ->
            val height = (width * page.height.toFloat() / page.width).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    /**
     * Частина сторінки [index] — у [bitmap], обмежено [clip]. Сторінка на екрані займає прямокутник
     * [left], [top], [width]×[height] (у пікселях бітмапа): так рендериться лише видиме, але в повному масштабі.
     */
    @Synchronized
    fun renderRegion(index: Int, bitmap: Bitmap, clip: android.graphics.Rect, left: Float, top: Float, width: Float, height: Float) {
        if (closed || index !in 0 until pageCount) return
        renderer.openPage(index).use { page ->
            val matrix = android.graphics.Matrix().apply {
                setScale(width / page.width, height / page.height)
                postTranslate(left, top)
            }
            page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
        runCatching { descriptor.close() }
    }
}

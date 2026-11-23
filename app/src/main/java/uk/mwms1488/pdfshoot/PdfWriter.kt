package uk.mwms1488.pdfshoot

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Пише PDF зі сторінок-JPEG, не перетискаючи їх: байти знімка кладуться у файл як є (DCTDecode).
 * Це швидко й без втрати якості, а файл виходить не більший за самі знімки.
 *
 * Розкладка об'єктів стала: 1 — каталог, 2 — список сторінок, далі по три на сторінку
 * (3+3k — сторінка, 4+3k — знімок, 5+3k — вміст). На неї спираються [rotations] і [setRotations].
 */
object PdfWriter {
    private const val PAGE_WIDTH = 595f // A4 у пунктах; висота — за пропорцією знімка

    fun write(jpegs: List<ByteArray>, target: File) {
        require(jpegs.isNotEmpty()) { "Немає сторінок" }
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            Out(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
                val offsets = LongArray(3 + jpegs.size * 3)
                out.text("%PDF-1.4\n%âãÏÓ\n")

                offsets[1] = out.position
                out.text("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

                offsets[2] = out.position
                val kids = jpegs.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
                out.text("2 0 obj\n<< /Type /Pages /Count ${jpegs.size} /Kids [$kids] >>\nendobj\n")

                jpegs.forEachIndexed { k, jpeg ->
                    val info = jpegInfo(jpeg)
                    val page = 3 + k * 3
                    val h = PAGE_WIDTH * info.height / info.width
                    val size = String.format(Locale.US, "%.2f", h)

                    offsets[page] = out.position
                    // «/Rotate» — одразу на початку й рівно три знаки: поворот потім правиться на місці.
                    out.text(
                        "$page 0 obj\n<< /Type /Page /Rotate 0   /Parent 2 0 R /MediaBox [0 0 595 $size] " +
                            "/Resources << /XObject << /Im0 ${page + 1} 0 R >> >> /Contents ${page + 2} 0 R >>\nendobj\n"
                    )

                    offsets[page + 1] = out.position
                    out.text(
                        "${page + 1} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                            "/ColorSpace /${info.colorSpace} /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
                    )
                    out.write(jpeg)
                    out.text("\nendstream\nendobj\n")

                    offsets[page + 2] = out.position
                    val content = "q 595 0 0 $size 0 0 cm /Im0 Do Q"
                    out.text("${page + 2} 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
                }

                val xref = out.position
                out.text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
                for (i in 1 until offsets.size) out.text(String.format(Locale.US, "%010d 00000 n \n", offsets[i]))
                out.text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            }
            if (!tmp.renameTo(target)) error("Не вдалося зберегти файл")
        } finally {
            tmp.delete()
        }
    }

    /** Кут повороту кожної сторінки (0, 90, 180, 270); null — файл записаний не нами. */
    fun rotations(file: File): List<Int>? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val layout = layout(raf)
            List(layout.pages) { page ->
                val field = ByteArray(3)
                raf.seek(rotateField(raf, layout, page))
                raf.readFully(field)
                latin(field).trim().toInt()
            }
        }
    }.getOrNull()

    /**
     * Ставить сторінкам кути з [rotations] (номер сторінки → кут), правлячи по три байти у файлі —
     * знімки не перетискаються. Пишеться в копію, яка потім замінює файл: обірваний запис не псує документ.
     */
    fun setRotations(file: File, rotations: Map<Int, Int>): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return runCatching {
            file.copyTo(tmp, overwrite = true)
            RandomAccessFile(tmp, "rw").use { raf ->
                val layout = layout(raf)
                for ((page, degrees) in rotations) {
                    require(page in 0 until layout.pages)
                    raf.seek(rotateField(raf, layout, page))
                    raf.write((((degrees % 360) + 360) % 360).toString().padEnd(3).toByteArray(Charsets.ISO_8859_1))
                }
                raf.fd.sync()
            }
            check(tmp.renameTo(file))
            true
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    /** Де в файлі таблиця адрес об'єктів і скільки сторінок. */
    private class Layout(val entries: Long, val pages: Int)

    private fun layout(raf: RandomAccessFile): Layout {
        val tail = ByteArray(minOf(64L, raf.length()).toInt())
        raf.seek(raf.length() - tail.size)
        raf.readFully(tail)
        val xref = latin(tail).substringAfter("startxref", "").trim().lineSequence().first().trim().toLong()
        val head = ByteArray(32)
        raf.seek(xref)
        val headText = latin(head, raf.read(head))
        check(headText.startsWith("xref\n0 "))
        val size = headText.substring(7).substringBefore('\n').trim().toInt()
        check(size >= 6 && (size - 3) % 3 == 0)
        return Layout(entries = xref + headText.indexOf('\n', 5) + 1, pages = (size - 3) / 3)
    }

    /** Позиція трьох знаків кута «/Rotate» у словнику сторінки [page]. */
    private fun rotateField(raf: RandomAccessFile, layout: Layout, page: Int): Long {
        val entry = ByteArray(10)
        raf.seek(layout.entries + 20L * (3 + page * 3))
        raf.readFully(entry)
        val objectAt = latin(entry).toLong()
        val start = ByteArray(96)
        raf.seek(objectAt)
        val text = latin(start, raf.read(start))
        val at = text.indexOf("/Rotate ")
        check(at >= 0 && text.contains("/Type /Page "))
        return objectAt + at + 8
    }

    private fun latin(bytes: ByteArray, length: Int = bytes.size) =
        String(bytes, 0, length.coerceAtLeast(0), Charsets.ISO_8859_1)

    private class JpegInfo(val width: Int, val height: Int, val colorSpace: String)

    /** Розмір і кількість каналів — із заголовка JPEG (маркер SOF), без розпакування знімка. */
    private fun jpegInfo(b: ByteArray): JpegInfo {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        require(b.size > 4 && u8(0) == 0xFF && u8(1) == 0xD8) { "Знімок не JPEG" }
        var i = 2
        while (i + 9 < b.size) {
            if (u8(i) != 0xFF) { i++; continue }
            val marker = u8(i + 1)
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                val colorSpace = when (u8(i + 9)) {
                    1 -> "DeviceGray"
                    3 -> "DeviceRGB"
                    else -> error("Непідтримуваний JPEG")
                }
                return JpegInfo(width = u16(i + 7), height = u16(i + 5), colorSpace = colorSpace)
            }
            i += 2 + u16(i + 2)
        }
        error("Пошкоджений JPEG")
    }

    private class Out(private val stream: OutputStream) : AutoCloseable {
        var position = 0L
            private set

        fun text(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

        fun write(bytes: ByteArray) {
            stream.write(bytes)
            position += bytes.size
        }

        override fun close() = stream.close()
    }
}

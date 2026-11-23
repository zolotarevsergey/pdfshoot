package uk.mwms1488.pdfshoot

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Сторінка скану, поки він не збережений у PDF. */
data class Page(
    val id: Long,
    val source: File,          // знімок як є — з камери чи галереї
    val sourceRotation: Int,   // на скільки його повернути, щоб стояв рівно
    val quad: Quad,            // межі документа на рівно поставленому знімку
    val detected: Quad?,       // що знайшов пошук країв (null — нічого)
    val turn: Int = 0,         // поворот, обраний користувачем
    val filter: Filter = Filter.Auto,
    val result: File? = null,  // готова сторінка (JPEG)
    val busy: Boolean = true,
)

/** Куди дивиться застосунок. */
sealed interface Screen {
    data object List : Screen
    data object Camera : Screen
    data class Review(val index: Int) : Screen
    data class Crop(val id: Long) : Screen
    data class Viewer(val path: String) : Screen
    data class Pages(val path: String) : Screen
}

enum class FlashMode(val label: String) { Off("Вимк."), Auto("Авто"), On("Увімк."), Torch("Ліхтар") }

/** Сторінки поточного скану й навігація. ViewModel — переживає поворот екрана. */
class ScanSession(app: Application) : AndroidViewModel(app) {
    val pages = mutableStateListOf<Page>()
    var screen by mutableStateOf<Screen>(Screen.List)

    private val prefs = app.getSharedPreferences("scan", Context.MODE_PRIVATE)
    var flash by mutableStateOf(runCatching { FlashMode.valueOf(prefs.getString("flash", null)!!) }.getOrDefault(FlashMode.Off))
        private set

    /** Фільтр нових сторінок — останній, який обирав користувач. */
    private var defaultFilter = runCatching { Filter.valueOf(prefs.getString("filter", null)!!) }.getOrDefault(Filter.Auto)

    private val dir = File(app.cacheDir, "session")
    private var nextId = 1L
    private val jobs = HashMap<Long, Job>()

    // Обробка 12-Мп знімка займає сотні мегабайт нативної пам'яті: не більше двох одночасно.
    private val work = Dispatchers.Default.limitedParallelism(2)

    init {
        dir.deleteRecursively() // залишки скану, перерваного закриттям застосунку
        dir.mkdirs()
        viewModelScope.launch(Dispatchers.Default) { DocImage.warmUp() }
    }

    fun nextFlash() {
        flash = FlashMode.entries[(flash.ordinal + 1) % FlashMode.entries.size]
        prefs.edit().putString("flash", flash.name).apply()
    }

    /** Знімок із камери: зберегти, знайти краї, обробити. */
    fun addCaptured(jpeg: ByteArray, rotation: Int) {
        val id = nextId++
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) { File(dir, "src-$id.jpg").apply { writeBytes(jpeg) } }
            add(id, source, rotation)
        }
    }

    fun addFromGallery(uris: List<Uri>) {
        val context = getApplication<Application>()
        for (uri in uris) {
            val id = nextId++
            viewModelScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val source = File(dir, "src-$id.jpg")
                        context.contentResolver.openInputStream(uri)!!.use { input -> source.outputStream().use { input.copyTo(it) } }
                        source to ExifInterface(source).rotationDegrees
                    }.getOrNull()
                }
                if (loaded != null) add(id, loaded.first, loaded.second)
            }
        }
    }

    private suspend fun add(id: Long, source: File, rotation: Int) {
        // Сторінка з'являється одразу (із «зачекайте»), щоб лічильник на кнопці відгукувався миттєво.
        pages += Page(id, source, rotation, Quad.Full, detected = null, filter = defaultFilter)
        val detected = withContext(work) { runCatching { DocImage.detect(source, rotation) }.getOrNull() }
        update(id) { it.copy(quad = detected ?: Quad.Full, detected = detected) }
    }

    fun page(id: Long): Page? = pages.firstOrNull { it.id == id }

    /** Змінити сторінку; якщо змінились межі, поворот чи фільтр — обробити наново. */
    fun update(id: Long, change: (Page) -> Page) {
        val index = pages.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = pages[index]
        val new = change(old)
        val stale = new.result == null || new.quad != old.quad || new.turn != old.turn || new.filter != old.filter
        if (!stale) {
            pages[index] = new
            return
        }
        pages[index] = new.copy(busy = true)
        if (new.filter != old.filter) {
            defaultFilter = new.filter
            prefs.edit().putString("filter", new.filter.name).apply()
        }
        jobs[id]?.cancel()
        jobs[id] = viewModelScope.launch {
            val result = withContext(work) {
                runCatching {
                    val jpeg = DocImage.process(new.source, new.sourceRotation, new.quad, new.turn, new.filter)
                    File(dir, "page-$id-${System.nanoTime()}.jpg").apply { writeBytes(jpeg) }
                }.getOrNull()
            }
            val i = pages.indexOfFirst { it.id == id }
            if (i < 0 || result == null) return@launch
            val current = pages[i]
            // Поки обробляли, користувач міг змінити сторінку ще раз — тоді цей результат застарів.
            if (current.quad != new.quad || current.turn != new.turn || current.filter != new.filter) {
                result.delete()
                return@launch
            }
            current.result?.delete()
            pages[i] = current.copy(result = result, busy = false)
        }
    }

    fun remove(id: Long) {
        jobs.remove(id)?.cancel()
        val page = page(id) ?: return
        pages.remove(page)
        page.source.delete()
        page.result?.delete()
    }

    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pages.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Готові сторінки — у PDF. Сесія очищується. */
    suspend fun save(title: String): File {
        val files = pages.map { requireNotNull(it.result) { "Сторінка ще обробляється" } }
        val file = withContext(Dispatchers.IO) { Docs.save(getApplication(), files, title) }
        clear()
        return file
    }

    val allReady: Boolean get() = pages.isNotEmpty() && pages.all { !it.busy && it.result != null }
}

package uk.mwms1488.pdfshoot

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PdfShootTheme { App() } }
        UpdateJob.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Запит Android «дозволити встановлення» має відкритись із цього вікна, а не з окремого.
        Updater.asker = { startActivity(it) }
        Updater.start(this, visible = true, force = false)
    }

    override fun onPause() {
        Updater.asker = null
        super.onPause()
    }
}

@Composable
private fun PdfShootTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session: ScanSession = viewModel()

    var docs by remember { mutableStateOf<List<Doc>?>(null) } // null — ще читаємо диск
    var version by remember { mutableIntStateOf(0) }           // росте після кожної зміни на диску
    var saving by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) }    // запропонована назва, поки відкрито діалог
    var discarding by remember { mutableStateOf(false) }

    LaunchedEffect(version) {
        docs = withContext(Dispatchers.IO) { Docs.list(context) }
    }

    fun save(title: String) {
        naming = null
        saving = true
        scope.launch {
            val saved = runCatching { session.save(title) }
            saving = false
            saved.onSuccess {
                version++
                session.screen = Screen.Viewer(it.path) // одразу показуємо результат: звідти ж і «Поширити»
            }.onFailure {
                Toast.makeText(context, "Не вдалося зберегти: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** З камери назад: зняте не губимо мовчки. */
    fun closeCamera() {
        if (session.pages.isEmpty()) session.screen = Screen.List else discarding = true
    }

    naming?.let { proposed ->
        RenameDialog(
            initial = proposed,
            heading = "Назва файлу",
            // Закриття діалогу не губить скан: він зберігається під запропонованою назвою.
            dismissLabel = "Типова назва",
            onDismiss = { save(proposed) },
            onConfirm = { save(it) },
        )
    }

    if (discarding) {
        val n = session.pages.size
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text("Скасувати скан?") },
            text = { Text("Знято сторінок: $n. Їх не буде збережено.") },
            confirmButton = {
                TextButton(onClick = {
                    discarding = false
                    session.clear()
                    session.screen = Screen.List
                }) { Text("Скасувати скан") }
            },
            dismissButton = {
                TextButton(onClick = {
                    discarding = false
                    session.screen = Screen.Review(n - 1)
                }) { Text("До сторінок") }
            },
        )
    }

    val screen = session.screen.let {
        val path = (it as? Screen.Viewer)?.path ?: (it as? Screen.Pages)?.path
        if (path != null && !File(path).exists()) Screen.List else it
    }
    // Оновлення закриває застосунок: воно чекає, доки користувач повернеться до списку без скану.
    SideEffect { Updater.hold = screen != Screen.List || saving || naming != null || session.pages.isNotEmpty() }

    AnimatedContent(
        targetState = screen,
        contentKey = { it::class },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { target ->
        when (target) {
            Screen.List -> ListScreen(
                docs = docs,
                saving = saving,
                onScan = { session.screen = Screen.Camera },
                appVersion = remember { Updater.installedName(context) },
                onShareApk = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Docs.appApk(context) } }
                            .onSuccess { Docs.shareApk(context, it) }
                            .onFailure { Toast.makeText(context, "Не вдалося підготувати APK", Toast.LENGTH_SHORT).show() }
                    }
                },
                onShareLink = { Docs.shareLink(context) },
                onCheckUpdate = {
                    Toast.makeText(context, "Перевіряю оновлення…", Toast.LENGTH_SHORT).show()
                    Updater.start(context, visible = true, force = true) { outcome ->
                        val text = when (outcome) {
                            Updater.Outcome.UpToDate -> "У вас найновіша версія"
                            Updater.Outcome.Unreachable -> "Сервер оновлень недоступний"
                            Updater.Outcome.Failed -> "Не вдалося оновити"
                            Updater.Outcome.Disabled -> "Debug-збірка не оновлюється"
                            else -> null // «Оновлюю…» каже сам Updater
                        }
                        if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                },
                onOpen = { session.screen = Screen.Viewer(it.file.path) },
                onShare = { Docs.share(context, it.file) },
                onRename = { doc, title ->
                    val renamed = Docs.rename(doc.file, title)
                    if (renamed == null) Toast.makeText(context, "Така назва вже є або вона порожня", Toast.LENGTH_SHORT).show()
                    else version++
                },
                onDelete = {
                    it.file.delete()
                    version++
                },
            )

            Screen.Camera -> CameraScreen(
                session = session,
                onClose = ::closeCamera,
                onDone = { session.screen = Screen.Review(session.pages.size - 1) },
            )

            is Screen.Review -> ReviewScreen(
                session = session,
                startIndex = target.index,
                saving = saving,
                onBack = { session.screen = Screen.Camera },
                onAdd = { session.screen = Screen.Camera },
                onCrop = { session.screen = Screen.Crop(it) },
                onSave = { naming = Docs.defaultTitle() },
            )

            is Screen.Crop -> {
                val page = session.page(target.id)
                val index = session.pages.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
                if (page == null) {
                    LaunchedEffect(Unit) { session.screen = Screen.Review(0) }
                } else {
                    CropScreen(
                        page = page,
                        onCancel = { session.screen = Screen.Review(index) },
                        onDone = { quad ->
                            session.update(page.id) { it.copy(quad = quad) }
                            session.screen = Screen.Review(index)
                        },
                    )
                }
            }

            is Screen.Viewer -> {
                val file = File(target.path)
                ViewerScreen(
                    file = file,
                    onBack = { session.screen = Screen.List },
                    onShare = { Docs.share(context, file) },
                    onRotate = { session.screen = Screen.Pages(file.path) },
                )
            }

            is Screen.Pages -> PagesScreen(
                file = File(target.path),
                onClose = { session.screen = Screen.Viewer(target.path) },
                onSaved = {
                    version++ // мініатюра в списку перемалюється
                    session.screen = Screen.Viewer(target.path)
                    Toast.makeText(context, "Збережено", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

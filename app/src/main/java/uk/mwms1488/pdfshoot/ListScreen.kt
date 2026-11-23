package uk.mwms1488.pdfshoot

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    docs: List<Doc>?,
    saving: Boolean,
    onScan: () -> Unit,
    appVersion: String,
    onShareApk: () -> Unit,
    onShareLink: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpen: (Doc) -> Unit,
    onShare: (Doc) -> Unit,
    onRename: (Doc, String) -> Unit,
    onDelete: (Doc) -> Unit,
) {
    var renaming by remember { mutableStateOf<Doc?>(null) }
    var deleting by remember { mutableStateOf<Doc?>(null) }
    var appMenu by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Скани") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { appMenu = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Поділитися застосунком (APK)") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                            onClick = { appMenu = false; onShareApk() },
                        )
                        DropdownMenuItem(
                            text = { Text("Поділитися посиланням") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                            onClick = { appMenu = false; onShareLink() },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Перевірити оновлення")
                                    Text(
                                        "Версія $appVersion",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_update), contentDescription = null) },
                            onClick = { appMenu = false; onCheckUpdate() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // На порожньому екрані кнопка вже є посередині — другу не показуємо.
            if (!docs.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                    text = { Text("Сканувати") },
                )
            }
        },
    ) { padding ->
        when {
            docs == null -> Unit // диск читається кілька мілісекунд — не блимаємо індикатором
            docs.isEmpty() -> EmptyState(onScan, Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Знизу запас, щоб кнопка «Сканувати» не закривала останній рядок.
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
            ) {
                items(docs, key = { it.file.path }) { doc ->
                    DocRow(
                        doc = doc,
                        onOpen = { onOpen(doc) },
                        onShare = { onShare(doc) },
                        onRename = { renaming = doc },
                        onDelete = { deleting = doc },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        if (saving) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), Alignment.Center) {
                Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Зберігаю PDF…")
                    }
                }
            }
        }
    }

    renaming?.let { doc ->
        RenameDialog(
            initial = doc.title,
            onDismiss = { renaming = null },
            onConfirm = {
                renaming = null
                onRename(doc, it)
            },
        )
    }

    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Видалити скан?") },
            text = { Text("«${doc.title}» буде видалено назавжди.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(doc)
                }) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun EmptyState(onScan: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_camera),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        Text("Сканів ще немає", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Наведіть камеру на документ — краї, перспектива й контраст виправляться самі. " +
                "Кілька сторінок поспіль зберуться в один PDF.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan) {
            Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Сканувати документ")
        }
    }
}

@Composable
private fun DocRow(
    doc: Doc,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    // Ключ із часом зміни: після повороту сторінки мініатюра перемальовується.
    val thumbnail by produceState<Bitmap?>(null, doc.file.path, doc.modified) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfDoc(doc.file).use { it.render(0, 220) } }.getOrNull()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            thumbnail?.let {
                Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "${pagesLabel(doc.pages)} · ${sizeLabel(doc.file.length())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Дата окремим рядком: на великому шрифті в один рядок вона обрізалась.
            Text(
                dateLabel(doc.modified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onShare) { Icon(painterResource(R.drawable.ic_share), contentDescription = "Поширити") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), contentDescription = "Ще") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Перейменувати") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Видалити") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    heading: String = "Назва",
    dismissLabel: String = "Скасувати",
) {
    // Текст одразу виділено: можна просто почати друкувати нову назву.
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

private fun pagesLabel(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "сторінок"
        n % 10 == 1 -> "сторінка"
        n % 10 in 2..4 -> "сторінки"
        else -> "сторінок"
    }
    return "$n $word"
}

private fun sizeLabel(bytes: Long): String =
    if (bytes >= 1_000_000) String.format(java.util.Locale.US, "%.1f МБ", bytes / 1_000_000.0)
    else "${(bytes / 1000).coerceAtLeast(1)} КБ"

private fun dateLabel(millis: Long): String =
    if (System.currentTimeMillis() - millis < DateUtils.MINUTE_IN_MILLIS) "щойно"
    else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

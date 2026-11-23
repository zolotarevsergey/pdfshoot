package uk.mwms1488.pdfshoot

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Поворот сторінок збереженого PDF: усі сторінки сіткою, кожну можна повернути, і всі зміни
 * записуються у файл разом кнопкою «Зберегти». Знімки не перетискаються — міняється лише кут.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen(file: File, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pdf = remember(file) { runCatching { PdfDoc(file) }.getOrNull() }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }
    // Кути, що вже записані у файлі; null — ще читаємо (або файл не наш).
    val saved by produceState<List<Int>?>(null, file) { value = withContext(Dispatchers.IO) { PdfWriter.rotations(file) } }
    // Поворот, доданий тут, на кожну сторінку: кратний 90, може бути від'ємним (для анімації в правильний бік).
    val turns = remember { mutableStateListOf<Int>() }
    val count = pdf?.pageCount ?: 0
    if (turns.size != count) {
        turns.clear()
        repeat(count) { turns += 0 }
    }
    val changed = turns.any { it % 360 != 0 }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val close = { if (changed) confirmDiscard = true else onClose() }
    BackHandler(onBack = close)

    fun save() {
        val base = saved ?: return
        saving = true
        scope.launch {
            val target = turns.indices.filter { turns[it] % 360 != 0 }.associateWith { base[it] + turns[it] }
            val ok = withContext(Dispatchers.IO) { PdfWriter.setRotations(file, target) }
            saving = false
            if (ok) onSaved() else Toast.makeText(context, "Не вдалося зберегти файл", Toast.LENGTH_LONG).show()
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Скасувати зміни?") },
            text = { Text("Повороти сторінок не буде збережено.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text("Не зберігати") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    save()
                }) { Text("Зберегти") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поворот сторінок") },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Закрити") }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = changed && !saving && saved != null) {
                        Text(if (saving) "Зберігаю…" else "Зберегти")
                    }
                },
            )
        },
    ) { padding ->
        when {
            pdf == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Text("Не вдалося відкрити файл") }
            saved == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            "Торкніться сторінки, щоб повернути її за годинниковою, або скористайтесь кнопками. " +
                                "Зміни запишуться у файл після «Зберегти».",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (count > 1) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { for (i in turns.indices) turns[i] -= 90 }) {
                                    Icon(painterResource(R.drawable.ic_rotate_left), null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Усі вліво")
                                }
                                OutlinedButton(onClick = { for (i in turns.indices) turns[i] += 90 }) {
                                    Icon(painterResource(R.drawable.ic_rotate), null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Усі вправо")
                                }
                            }
                        }
                    }
                }
                items(count, key = { it }) { index ->
                    PageTile(
                        pdf = pdf,
                        index = index,
                        turn = turns[index],
                        onLeft = { turns[index] -= 90 },
                        onRight = { turns[index] += 90 },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageTile(pdf: PdfDoc, index: Int, turn: Int, onLeft: () -> Unit, onRight: () -> Unit) {
    val renderWidth = with(LocalDensity.current) { 220.dp.toPx() }.roundToInt()
    val bitmap by produceState<Bitmap?>(null, pdf, index) {
        value = withContext(Dispatchers.IO) { runCatching { pdf.render(index, renderWidth) }.getOrNull() }
    }
    val angle by animateFloatAsState(turn.toFloat(), label = "turn")
    val changed = turn % 360 != 0
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = if (changed) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onRight)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = pdf.ratios[index]
                // Сторінка вписана в квадрат так, щоб не вилазила за нього в жодному положенні повороту.
                val sideways = ((angle / 90f).roundToInt() % 2) != 0
                val fit = if (sideways) min(1f, 1f / maxOf(ratio, 1f / ratio)) else 1f
                Box(
                    Modifier.fillMaxSize(fraction = 1f).graphicsLayer {
                        rotationZ = angle
                        scaleX = fit
                        scaleY = fit
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.aspectRatio(ratio).background(Color.White)) {
                        bitmap?.let {
                            Image(it.asImageBitmap(), "Сторінка ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onLeft) { Icon(painterResource(R.drawable.ic_rotate_left), contentDescription = "Повернути вліво") }
                Text(
                    "${index + 1}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (changed) accent else MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = onRight) { Icon(painterResource(R.drawable.ic_rotate), contentDescription = "Повернути вправо") }
            }
        }
    }
}

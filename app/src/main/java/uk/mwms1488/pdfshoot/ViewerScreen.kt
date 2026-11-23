package uk.mwms1488.pdfshoot

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(file: File, onBack: () -> Unit, onShare: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) } // росте після повороту — PDF відкривається наново

    val pdf = remember(file, revision) { runCatching { PdfDoc(file) }.getOrNull() }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }

    val listState = rememberLazyListState()
    // «Поточна» сторінка — та, що перетинає середину екрана: її показує лічильник і повертає кнопка.
    val currentPage by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val middle = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size >= middle }?.index
                ?: listState.firstVisibleItemIndex
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Назад") }
                },
                actions = {
                    IconButton(
                        enabled = pdf != null,
                        onClick = {
                            val page = currentPage
                            scope.launch {
                                val done = withContext(Dispatchers.IO) { PdfWriter.rotatePage(file, page) }
                                if (done) {
                                    revision++
                                    onChanged()
                                } else {
                                    Toast.makeText(context, "Не вдалося повернути сторінку", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) { Icon(painterResource(R.drawable.ic_rotate), contentDescription = "Повернути сторінку") }
                    IconButton(onClick = onShare) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = "Поширити")
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clipToBounds(),
        ) {
            if (pdf == null) {
                Text("Не вдалося відкрити файл", Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }

            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            // Рендеримо з запасом, щоб текст лишався чітким при збільшенні, але без зайвої пам'яті.
            val renderWidth = (constraints.maxWidth * 2).coerceIn(600, 2200)

            var scale by remember { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }
            var offsetY by remember { mutableFloatStateOf(0f) }

            fun clamp() {
                val maxX = widthPx * (scale - 1f) / 2f
                val maxY = heightPx * (scale - 1f) / 2f
                offsetX = offsetX.coerceIn(-maxX, maxX)
                offsetY = offsetY.coerceIn(-maxY, maxY)
            }

            val transform = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                offsetX += pan.x
                // Вертикальний рух при збільшенні гортає список; зсув самого полотна потрібен лише
                // на краях, щоб дістатися верху першої й низу останньої сторінки.
                var dy = pan.y
                if (offsetY != 0f && dy != 0f && sign(dy) != sign(offsetY)) {
                    val back = dy.coerceIn(-abs(offsetY), abs(offsetY))
                    offsetY += back
                    dy -= back
                }
                val scrolled = -listState.dispatchRawDelta(-dy / scale) * scale
                offsetY += dy - scrolled
                clamp()
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                // Збільшуємо те місце, куди торкнулись, а не центр екрана.
                                scale = 2.5f
                                offsetX = (widthPx / 2 - tap.x) * (scale - 1)
                                offsetY = (heightPx / 2 - tap.y) * (scale - 1)
                                clamp()
                            }
                        })
                    }
                    // Поки масштаб 1 — палець просто гортає список; жест забираємо лише при збільшенні.
                    .transformable(transform, canPan = { scale > 1f })
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                contentPadding = PaddingValues(top = 8.dp, bottom = padding.calculateBottomPadding() + 56.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pdf.pageCount, key = { it }) { index ->
                    PageImage(pdf, index, renderWidth)
                }
            }

            // Збільшене — чітке: після жесту видиме перемальовується з PDF у точному масштабі, а не
            // розтягується з екранної копії. Під час жесту видно розтягнуту копію (це миттєво).
            var sharp by remember(pdf) { mutableStateOf<Sharp?>(null) }
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            LaunchedEffect(pdf, w, h) {
                snapshotFlow { ViewKey(scale, offsetX, offsetY, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
                    .collectLatest { key ->
                        if (key.scale <= 1.01f) {
                            sharp = null
                            return@collectLatest
                        }
                        delay(120) // жест ще триває — чекаємо, доки рука зупиниться
                        val layout = listState.layoutInfo
                        val items = layout.visibleItemsInfo.map { Triple(it.index, (it.offset - layout.viewportStartOffset).toFloat(), it.size) }
                        val bitmap = withContext(Dispatchers.IO) {
                            runCatching { renderSharp(pdf, items, key, w, h) }.getOrNull()
                        }
                        if (bitmap != null) sharp = Sharp(bitmap, key)
                    }
            }
            Canvas(Modifier.fillMaxSize()) {
                val s = sharp ?: return@Canvas
                val now = ViewKey(scale, offsetX, offsetY, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                if (s.key == now) drawImage(s.bitmap.asImageBitmap())
            }

            if (pdf.pageCount > 1) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        "${currentPage + 1} / ${pdf.pageCount}",
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private data class ViewKey(val scale: Float, val x: Float, val y: Float, val first: Int, val firstOffset: Int)

private class Sharp(val bitmap: Bitmap, val key: ViewKey)

/**
 * Видима частина збільшених сторінок у бітмапі розміром з екран. [items] — сторінки списку:
 * номер, верх у координатах списку, висота. Масштаб — навколо центру, як у graphicsLayer.
 */
private fun renderSharp(pdf: PdfDoc, items: List<Triple<Int, Float, Int>>, key: ViewKey, w: Int, h: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val white = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
    val cx = w / 2f
    val cy = h / 2f
    for ((index, top, size) in items) {
        val l = (0f - cx) * key.scale + cx + key.x
        val r = (w - cx) * key.scale + cx + key.x
        val t = (top - cy) * key.scale + cy + key.y
        val b = (top + size - cy) * key.scale + cy + key.y
        val clip = android.graphics.Rect(
            maxOf(0, kotlin.math.floor(l).toInt()), maxOf(0, kotlin.math.floor(t).toInt()),
            minOf(w, kotlin.math.ceil(r).toInt()), minOf(h, kotlin.math.ceil(b).toInt()),
        )
        if (clip.isEmpty) continue
        canvas.drawRect(clip, white)
        pdf.renderRegion(index, bitmap, clip, l, t, r - l, b - t)
    }
    return bitmap
}

@Composable
private fun PageImage(pdf: PdfDoc, index: Int, renderWidth: Int) {
    val bitmap by produceState<Bitmap?>(null, pdf, index, renderWidth) {
        value = withContext(Dispatchers.IO) { runCatching { pdf.render(index, renderWidth) }.getOrNull() }
    }
    // Місце під сторінку займаємо одразу за її пропорцією: список не стрибає, коли домальовується знімок.
    Box(Modifier.fillMaxWidth().aspectRatio(pdf.ratios[index]).background(Color.White)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Сторінка ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

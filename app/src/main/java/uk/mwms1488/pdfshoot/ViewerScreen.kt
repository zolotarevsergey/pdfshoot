package uk.mwms1488.pdfshoot

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.exp
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
fun ViewerScreen(file: File, onBack: () -> Unit, onShare: () -> Unit, onRotate: () -> Unit) {
    val pdf = remember(file) { runCatching { PdfDoc(file) }.getOrNull() }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }

    val listState = rememberLazyListState()
    // «Поточна» сторінка — та, що перетинає середину екрана: її показує лічильник.
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
                    IconButton(enabled = pdf != null, onClick = onRotate) {
                        Icon(painterResource(R.drawable.ic_rotate), contentDescription = "Повернути сторінки")
                    }
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

            /**
             * Масштаб у [zoom] разів навколо точки екрана [focus] плюс зсув [pan]. Вертикальне, що
             * виходить за межі збільшеного кадру, гортає список: так збільшене читається донизу.
             */
            fun transform(zoom: Float, pan: Offset, focus: Offset) {
                val old = scale
                val new = (old * zoom).coerceIn(1f, MAX_ZOOM)
                val k = new / old
                val cx = widthPx / 2
                val cy = heightPx / 2
                var tx = focus.x - cx - (focus.x - cx - offsetX) * k + pan.x
                var ty = focus.y - cy - (focus.y - cy - offsetY) * k + pan.y
                scale = new
                val maxX = widthPx * (new - 1f) / 2f
                val maxY = heightPx * (new - 1f) / 2f
                tx = tx.coerceIn(-maxX, maxX)
                val over = ty - ty.coerceIn(-maxY, maxY)
                if (over != 0f) ty += listState.dispatchRawDelta(-over / new) * new
                offsetX = tx
                offsetY = ty.coerceIn(-maxY, maxY)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) { zoomGestures(::transform, { scale }) }
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

/**
 * Жести перегляду. Обробляються раніше за прокрутку списку (прохід Initial), тож вона їх не перехоплює:
 * - два пальці — масштаб навколо точки між ними й зсув;
 * - подвійний дотик — збільшити в точці дотику або повернутись до звичайного розміру;
 * - подвійний дотик з утриманням і рухом вниз/вгору — плавно збільшити/зменшити одним пальцем;
 * - один палець при збільшенні — рух збільшеної сторінки; без збільшення — звичайна прокрутка списку.
 */
private suspend fun PointerInputScope.zoomGestures(
    transform: (zoom: Float, pan: Offset, focus: Offset) -> Unit,
    scale: () -> Float,
) {
    var lastTapUp = 0L
    var lastTapAt = Offset.Zero
    val slop = viewConfiguration.touchSlop
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val secondTap = down.uptimeMillis - lastTapUp < doubleTapTimeout && (down.position - lastTapAt).getDistance() < slop * 4
        var multi = false
        var dragZoom = false
        var dragging = false
        var travel = 0f
        var upTime = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                upTime = event.changes.maxOf { it.uptimeMillis }
                break
            }
            if (pressed.size >= 2) {
                multi = true
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                transform(zoom, pan, event.calculateCentroid(useCurrent = true))
                event.changes.forEach { it.consume() }
                continue
            }
            if (multi) { // після щипка лишився один палець — жест скінчився, нічого не робимо
                event.changes.forEach { it.consume() }
                continue
            }
            val change = pressed.first()
            val delta = change.position - change.previousPosition
            travel += delta.getDistance()
            when {
                secondTap -> if (dragZoom || travel > slop) {
                    dragZoom = true
                    // Вниз — більше, вгору — менше; центр — там, де був подвійний дотик.
                    transform(exp(delta.y * 0.006f), Offset.Zero, down.position)
                    change.consume()
                }
                scale() > 1.01f -> if (dragging || travel > slop) {
                    dragging = true
                    transform(1f, delta, change.position)
                    change.consume()
                }
            }
        }
        when {
            secondTap && !dragZoom && !multi -> {
                if (scale() > 1.01f) transform(1f / scale(), Offset.Zero, down.position)
                else transform(2.5f, Offset.Zero, down.position)
                lastTapUp = 0L
            }
            !multi && travel < slop -> {
                lastTapUp = upTime
                lastTapAt = down.position
            }
            else -> lastTapUp = 0L
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

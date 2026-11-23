package uk.mwms1488.pdfshoot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    session: ScanSession,
    startIndex: Int,
    saving: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onCrop: (Long) -> Unit,
    onSave: () -> Unit,
) {
    val pages = session.pages
    // Порожньо — щойно збережено (екран ще зникає в анімації): нікуди не переходимо самі.
    if (pages.isEmpty()) return
    BackHandler(onBack = onBack)

    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, pages.size - 1)) { pages.size }
    val current = pages.getOrNull(pager.currentPage) ?: pages.last()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pages.size == 1) "Сторінка" else "Сторінка ${pager.currentPage + 1} з ${pages.size}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "До камери") }
                },
                actions = {
                    IconButton(onClick = {
                        val last = pages.size == 1
                        session.remove(current.id)
                        if (last) onAdd() // остання сторінка — назад до камери
                    }) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Видалити сторінку")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pager,
                key = { pages.getOrNull(it)?.id ?: it },
                modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                pageSpacing = 16.dp,
            ) { index ->
                pages.getOrNull(index)?.let { PagePreview(it) }
            }

            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Filter.entries.forEachIndexed { i, filter ->
                            SegmentedButton(
                                selected = current.filter == filter,
                                onClick = { session.update(current.id) { it.copy(filter = filter) } },
                                shape = SegmentedButtonDefaults.itemShape(i, Filter.entries.size),
                            ) { Text(filter.label) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Action(R.drawable.ic_crop, "Межі") { onCrop(current.id) }
                        Action(R.drawable.ic_rotate, "Повернути") {
                            session.update(current.id) { it.copy(turn = (it.turn + 90) % 360) }
                        }
                        Action(R.drawable.ic_add, "Ще сторінка", onAdd)
                    }
                    Spacer(Modifier.height(8.dp))
                    val ready = session.allReady
                    Button(onClick = onSave, enabled = ready && !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        when {
                            saving -> Text("Зберігаю…")
                            ready -> Text(if (pages.size == 1) "Зберегти PDF" else "Зберегти PDF · ${pages.size} стор.")
                            else -> {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("Обробляю…")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PagePreview(page: Page) {
    val maxSide = with(LocalDensity.current) { 900.dp.toPx() }.roundToInt()
    val bitmap by produceState<Bitmap?>(null, page.result) {
        val file = page.result ?: return@produceState
        value = withContext(Dispatchers.IO) { decodeScaled(file.path, maxSide) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = "Сторінка",
                modifier = Modifier.shadow(6.dp, RoundedCornerShape(2.dp)).background(Color.White),
                contentScale = ContentScale.Fit,
            )
        }
        if (page.busy) CircularProgressIndicator()
    }
}

/** Знімок, зменшений кратно 2 так, щоб довша сторона була не менша за [maxSide]. */
fun decodeScaled(path: String, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Ручні межі сторінки: кути й сторони перетягуються, під пальцем — лупа. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CropScreen(page: Page, onCancel: () -> Unit, onDone: (Quad) -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onCancel)
    val density = LocalDensity.current
    val maxSide = with(density) { 1400.dp.toPx() }.roundToInt()
    val bitmap by produceState<Bitmap?>(null, page.source) {
        value = withContext(Dispatchers.Default) {
            runCatching { DocImage.preview(page.source, page.sourceRotation, maxSide) }.getOrNull()
        }
    }
    var quad by remember { mutableStateOf(page.quad) }
    var active by remember { mutableIntStateOf(-1) } // 0..3 — кут, 4..7 — сторона після кута (i - 4)
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Скасувати", color = Color.White) }
            Text(
                "Межі сторінки",
                Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(onClick = {
                if (quad.isConvex()) onDone(quad)
                else Toast.makeText(context, "Межі перехрещені — розведіть кути", Toast.LENGTH_SHORT).show()
            }) { Text("Готово", color = accent) }
        }

        // Відступ від країв екрана: там системний жест «Назад», і кут під ним не вхопити.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 36.dp, vertical = 24.dp)) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }
            val image = remember(bmp) { bmp.asImageBitmap() }
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val rect = fitRect(w, h, bmp.width.toFloat() / bmp.height)
            fun toScreen(p: Offset) = Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)
            val grab = with(density) { 44.dp.toPx() }
            val lift = with(density) { 96.dp.toPx() }

            // Над кутами системний жест «Назад» вимкнено: інакше рух кута до краю закривав би екран.
            val exclusion = quad.corners.map(::toScreen).fold(Modifier.fillMaxSize() as Modifier) { m, p ->
                m.systemGestureExclusion { androidx.compose.ui.geometry.Rect(p, grab) }
            }
            Canvas(
                exclusion
                    .magnifier(
                        sourceCenter = { if (active in 0..3) toScreen(quad.corners[active]) else Offset.Unspecified },
                        magnifierCenter = {
                            if (active in 0..3) toScreen(quad.corners[active]) - Offset(0f, lift) else Offset.Unspecified
                        },
                        zoom = 2.5f,
                        size = DpSize(112.dp, 112.dp),
                        cornerRadius = 56.dp,
                    )
                    .pointerInput(rect) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val corners = quad.corners.map(::toScreen)
                                val mids = (0 until 4).map { (corners[it] + corners[(it + 1) % 4]) / 2f }
                                val corner = corners.indices.minBy { (corners[it] - start).getDistance() }
                                val mid = mids.indices.minBy { (mids[it] - start).getDistance() }
                                active = when {
                                    (corners[corner] - start).getDistance() < grab -> corner
                                    (mids[mid] - start).getDistance() < grab -> 4 + mid
                                    else -> -1
                                }
                            },
                            onDrag = { change, delta ->
                                if (active < 0) return@detectDragGestures
                                change.consume()
                                val d = Offset(delta.x / rect.width, delta.y / rect.height)
                                val c = quad.corners
                                quad = if (active < 4) {
                                    quad.with(active, c[active] + d)
                                } else {
                                    val i = active - 4
                                    quad.with(i, c[i] + d).with((i + 1) % 4, c[(i + 1) % 4] + d)
                                }
                            },
                            onDragEnd = { active = -1 },
                            onDragCancel = { active = -1 },
                        )
                    },
            ) {
                drawImage(
                    image,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                )
                val pts = quad.corners.map(::toScreen)
                val shape = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                // Поза межами — затемнено: одразу видно, що піде в PDF.
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(rect)
                    addPath(shape)
                }
                drawPath(outside, Color.Black.copy(alpha = 0.55f))
                drawPath(shape, accent, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
                for (i in 0 until 4) {
                    val mid = (pts[i] + pts[(i + 1) % 4]) / 2f
                    drawCircle(Color.White, 7.dp.toPx(), mid)
                    drawCircle(accent, 5.dp.toPx(), mid)
                }
                pts.forEachIndexed { i, p ->
                    if (i == active) {
                        // Під лупою — тонке перехрестя: кут ставиться точно, а не «десь під пальцем».
                        drawCircle(accent, 14.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                        drawLine(accent, p - Offset(10.dp.toPx(), 0f), p + Offset(10.dp.toPx(), 0f), 1.dp.toPx())
                        drawLine(accent, p - Offset(0f, 10.dp.toPx()), p + Offset(0f, 10.dp.toPx()), 1.dp.toPx())
                    } else {
                        drawCircle(Color.White, 12.dp.toPx(), p)
                        drawCircle(accent, 9.dp.toPx(), p)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = { quad = Quad.Full }) {
                Icon(painterResource(R.drawable.ic_crop_free), null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text("Весь кадр", color = Color.White)
            }
            TextButton(onClick = { page.detected?.let { quad = it } }, enabled = page.detected != null) {
                Icon(painterResource(R.drawable.ic_auto), null, tint = if (page.detected != null) Color.White else Color.Gray)
                Spacer(Modifier.size(8.dp))
                Text("Знайдені краї", color = if (page.detected != null) Color.White else Color.Gray)
            }
        }
    }
}

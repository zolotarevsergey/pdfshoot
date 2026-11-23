package uk.mwms1488.pdfshoot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Що знайшов аналіз кадру: межі документа й пропорції кадру (рівно поставленого). */
private data class Detection(val quad: Quad?, val frameWidth: Int, val frameHeight: Int)

@Composable
fun CameraScreen(session: ScanSession, onClose: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onClose)

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) session.addFromGallery(uris)
    }
    val pickFromGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            granted -> CameraContent(session, onClose, onDone, pickFromGallery)
            denied -> NoPermission(
                onAsk = { permission.launch(Manifest.permission.CAMERA) },
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onGallery = pickFromGallery,
                onClose = onClose,
                pages = session.pages.size,
                onDone = onDone,
            )
        }
    }
}

@Composable
private fun CameraContent(session: ScanSession, onClose: () -> Unit, onDone: () -> Unit, onGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val main = remember { ContextCompat.getMainExecutor(context) }

    var detection by remember { mutableStateOf<Detection?>(null) }
    var misses by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) } // камера ініціалізована: затвор працює лише тоді
    val shutterFlash = remember { Animatable(0f) }

    val controller = remember { LifecycleCameraController(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }

    fun applyFlash() {
        controller.imageCaptureFlashMode = when (session.flash) {
            FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.On -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        controller.enableTorch(session.flash == FlashMode.Torch)
    }

    DisposableEffect(lifecycleOwner) {
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        // Якість важливіша за швидкість затвора: повна обробка камери (шумозаглушення, HDR, де є).
        controller.imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        controller.imageCaptureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(ratio)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
        controller.previewResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
        // Для рамки на екрані досить малого кадру: аналіз встигає за кожним кадром.
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(ratio)
            .setResolutionStrategy(
                ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.setImageAnalysisAnalyzer(analysisExecutor) { proxy ->
            val found = runCatching { analyze(proxy) }.getOrNull()
            proxy.close()
            if (found != null) main.execute {
                val previous = detection?.quad
                detection = when {
                    found.quad != null -> {
                        misses = 0
                        found.copy(quad = previous?.let { blend(it, found.quad) } ?: found.quad)
                    }
                    // Кілька кадрів без документа поспіль — лише тоді ховаємо рамку: вона не блимає.
                    ++misses > 6 -> found
                    else -> found.copy(quad = previous)
                }
            }
        }
        controller.bindToLifecycle(lifecycleOwner)
        controller.initializationFuture.addListener({
            ready = runCatching { controller.initializationFuture.get() }.isSuccess
            if (ready) applyFlash()
            else Toast.makeText(context, "Камера недоступна — можна взяти фото з галереї", Toast.LENGTH_LONG).show()
        }, main)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analysisExecutor.shutdown()
            captureExecutor.shutdown()
        }
    }
    LaunchedEffect(session.flash) { if (ready) runCatching { applyFlash() } }

    fun capture() {
        if (capturing || !ready) return
        capturing = true
        scope.launch {
            shutterFlash.snapTo(0.7f)
            shutterFlash.animateTo(0f, tween(250))
        }
        val callback = object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val jpeg = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                main.execute {
                    session.addCaptured(jpeg, rotation)
                    capturing = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                main.execute {
                    capturing = false
                    Toast.makeText(context, "Не вдалося зняти: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        try {
            controller.takePicture(captureExecutor, callback)
        } catch (e: Exception) {
            capturing = false
            Toast.makeText(context, "Камера ще не готова", Toast.LENGTH_SHORT).show()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // Увесь кадр, який буде знято, без обрізання: що бачите — те й у скані.
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    setBackgroundColor(android.graphics.Color.BLACK)
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val accent = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize()) {
            val d = detection ?: return@Canvas
            val quad = d.quad ?: return@Canvas
            val frame = fitRect(size.width, size.height, d.frameWidth.toFloat() / d.frameHeight)
            val pts = quad.corners.map { Offset(frame.left + it.x * frame.width, frame.top + it.y * frame.height) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, accent.copy(alpha = 0.18f))
            drawPath(path, accent, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
            pts.forEach { drawCircle(Color.White, 6.dp.toPx(), it) }
        }

        if (shutterFlash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = shutterFlash.value)))

        // Верх: закрити, підказка, спалах.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Закрити", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = { session.nextFlash() },
                shape = RoundedCornerShape(50),
                color = if (session.flash == FlashMode.Off) Color.Black.copy(alpha = 0.45f) else Color(0xFFFFD54F),
                contentColor = if (session.flash == FlashMode.Off) Color.White else Color.Black,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(
                            when (session.flash) {
                                FlashMode.Off -> R.drawable.ic_flash_off
                                FlashMode.Auto -> R.drawable.ic_flash_auto
                                FlashMode.On -> R.drawable.ic_flash_on
                                FlashMode.Torch -> R.drawable.ic_torch
                            },
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Спалах: ${session.flash.label}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        val found = detection?.quad != null
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
        ) {
            Text(
                if (found) "Документ знайдено — знімайте" else "Наведіть камеру на документ",
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Низ: галерея, затвор, зняті сторінки.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledButton("Галерея", onGallery) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_gallery), contentDescription = null, tint = Color.White)
                }
            }
            Shutter(enabled = ready && !capturing, busy = capturing || !ready, onClick = ::capture)
            val last = session.pages.lastOrNull()
            if (last == null) {
                Spacer(Modifier.size(width = 64.dp, height = 52.dp))
            } else {
                LabeledButton("Готово", onDone) { LastPage(last, session.pages.size) }
            }
        }
    }
}

/** Кадр аналізу → межі документа на рівно поставленому кадрі. Виконується не в головному потоці. */
private fun analyze(proxy: ImageProxy): Detection {
    val plane = proxy.planes[0]
    val width = proxy.width
    val height = proxy.height
    val buffer = plane.buffer
    val bytes = ByteArray(width * height)
    if (plane.rowStride == width) {
        buffer.get(bytes, 0, bytes.size)
    } else {
        for (row in 0 until height) {
            buffer.position(row * plane.rowStride)
            buffer.get(bytes, row * width, width)
        }
    }
    val rotation = proxy.imageInfo.rotationDegrees
    val quad = DocImage.detectLuma(bytes, width, height)?.rotated(rotation)
    val sideways = rotation == 90 || rotation == 270
    return Detection(quad, if (sideways) height else width, if (sideways) width else height)
}

/** Плавний рух рамки: половина шляху до нового положення за кадр. */
private fun blend(a: Quad, b: Quad): Quad {
    fun m(p: Offset, q: Offset) = Offset(p.x + (q.x - p.x) * 0.5f, p.y + (q.y - p.y) * 0.5f)
    return Quad(m(a.tl, b.tl), m(a.tr, b.tr), m(a.br, b.br), m(a.bl, b.bl))
}

/** Прямокутник із пропорціями [aspect] (ширина/висота), вписаний по центру в [w]×[h]. */
fun fitRect(w: Float, h: Float, aspect: Float): Rect {
    val width = min(w, h * aspect)
    val height = width / aspect
    val left = (w - width) / 2
    val top = (h - height) / 2
    return Rect(left, top, left + width, top + height)
}

@Composable
private fun Shutter(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
    }
}

@Composable
private fun LabeledButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun LastPage(page: Page, count: Int) {
    val thumbnail by produceState<Bitmap?>(null, page.result) {
        val file = page.result ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 160) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    Box {
        Box(
            Modifier.size(width = 46.dp, height = 58.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.25f))
                .border(2.dp, Color.White, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (page.busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(start = 0.dp).size(22.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$count", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NoPermission(
    onAsk: () -> Unit,
    onSettings: () -> Unit,
    onGallery: () -> Unit,
    onClose: () -> Unit,
    pages: Int,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(64.dp), tint = Color.White)
        Spacer(Modifier.height(16.dp))
        Text("Потрібен доступ до камери", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Без нього документ можна лише взяти з галереї. Якщо Android більше не питає — дозвольте камеру в налаштуваннях застосунку.",
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (pages > 0) {
            Button(onClick = onDone) { Text("До сторінок ($pages)") }
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = onAsk) { Text("Дозволити камеру") }
        TextButton(onClick = onSettings) { Text("Відкрити налаштування") }
        TextButton(onClick = onGallery) { Text("Вибрати з галереї") }
        TextButton(onClick = onClose) { Text("Закрити") }
    }
}

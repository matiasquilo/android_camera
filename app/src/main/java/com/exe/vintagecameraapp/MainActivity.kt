package com.exe.vintagecameraapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.tan

private const val TAG = "Vintage"
private const val SAVE_QUALITY = 92      // casi no se nota y comprime más rápido

private val DIGIT_SEGMENTS = mapOf(
    '0' to "abcdef", '1' to "bc",     '2' to "abged", '3' to "abgcd", '4' to "fgbc",
    '5' to "afgcd",  '6' to "afgedc", '7' to "abc",   '8' to "abcdefg", '9' to "abcdfg"
)

// ============================================================
// PRESETS
// ============================================================

enum class VintagePreset(
    val label: String,
    val lutPath: String?
) {
    NORMAL(
        "STD",
        null
    ),
    FUJI_1(
        "FUJI 1",
        "luts/fuji_1.png"
    ),
    FUJI_2(
        "FUJI 2",
        "luts/fuji_2.png"
    ),
    BLACK_AND_WHITE(
        "B&W",
        "luts/bw_classic.png"
    )
}

// ============================================================
// LUT 3D
// ============================================================

class Lut3D(bitmap: Bitmap) {

    companion object {
        private const val SIZE = 512
    }

    // Hald nivel 8: el índice del píxel en la imagen es r + 64*g + 4096*b
    private val table = IntArray(SIZE * SIZE)

    init {
        require(bitmap.width == SIZE && bitmap.height == SIZE) {
            "La LUT debe ser exactamente 512x512."
        }
        bitmap.getPixels(table, 0, SIZE, 0, 0, SIZE, SIZE)
    }

    /** Aplica la LUT a un color ARGB. */
    fun map(c: Int): Int {
        val idx = ((c shr 18) and 0x3F) or
                (((c shr 10) and 0x3F) shl 6) or
                (((c shr 2) and 0x3F) shl 12)
        return (c and 0xFF000000.toInt()) or (table[idx] and 0x00FFFFFF)
    }

    /** Para la foto final: el bitmap tiene que ser mutable. */
    fun applyInPlace(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        val n = 2
        val chunk = (px.size + n - 1) / n
        (0 until n).map { t ->
            thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val end = minOf(px.size, (t + 1) * chunk)
                for (i in t * chunk until end) px[i] = map(px[i])
            }
        }.forEach { it.join() }

        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }
}

// ============================================================
// PREVIEW PROCESSOR
// ============================================================

class PreviewProcessor {
    private var inPx = IntArray(0)
    private var outPx = IntArray(0)

    fun process(image: ImageProxy, lut: Lut3D): Bitmap {
        val src = image.toBitmap()
        val w = src.width
        val h = src.height
        if (inPx.size != w * h) {
            inPx = IntArray(w * h)
            outPx = IntArray(w * h)
        }
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        src.recycle()

        val rot = image.imageInfo.rotationDegrees
        val swap = rot == 90 || rot == 270
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val o = when (rot) {
                    90 -> x * h + (h - 1 - y)
                    180 -> (h - 1 - y) * w + (w - 1 - x)
                    270 -> (w - 1 - x) * h + y
                    else -> row + x
                }
                outPx[o] = lut.map(inPx[row + x])
            }
        }
        return Bitmap.createBitmap(
            outPx, if (swap) h else w, if (swap) w else h, Bitmap.Config.ARGB_8888
        )
    }
}

// ============================================================
// CARGAR LUT DESDE ASSETS
// ============================================================

private fun loadLutFromAssets(
    context: Context,
    path: String
): Lut3D? {
    return try {
        context.assets.open(path).use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream) ?: return null
            Lut3D(bitmap)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error cargando LUT desde $path", e)
        null
    }
}

// ============================================================
// MAIN ACTIVITY
// ============================================================

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private var deviceRotation by mutableIntStateOf(0)
    private lateinit var orientationEventListener: OrientationEventListener

    @ExperimentalZeroShutterLag
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                deviceRotation = when (orientation) {
                    in 45..134 -> 270
                    in 135..224 -> 180
                    in 225..314 -> 90
                    else -> 0
                }
            }
        }

        orientationEventListener.enable()

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {
                hasCameraPermission = it
            }.launch(Manifest.permission.CAMERA)
        }

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Ui.bg
            ) {
                if (hasCameraPermission) {
                    CameraPreviewScreen(
                        modifier = Modifier.fillMaxSize(),
                        deviceRotation = deviceRotation
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener.disable()
    }
}

// ============================================================
// CAMERA SCREEN
// ============================================================

@ExperimentalZeroShutterLag
@Composable
fun CameraPreviewScreen(
    modifier: Modifier = Modifier,
    deviceRotation: Int
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Executors
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val photoProcessingExecutor = remember {
        Executors.newSingleThreadExecutor { r ->
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }
        }
    }
    val previewProcessor = remember { PreviewProcessor() }
    val overlayShown = remember { AtomicBoolean(false) }
    val processingCount = remember { AtomicInteger(0) }
    var capturing by remember { mutableStateOf(false) }

    // Shutter flash animation
    var flashTarget by remember { mutableFloatStateOf(0f) }
    val flashAlpha by animateFloatAsState(
        targetValue = flashTarget,
        animationSpec = tween(90),
        finishedListener = { flashTarget = 0f },
        label = "shutterFlash"
    )

    // Camera state
    var cameraObj by remember { mutableStateOf<Camera?>(null) }
    var exposureIndex by remember { mutableFloatStateOf(0f) }
    var exposureRange by remember { mutableStateOf(-1..1) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusOk by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(status) {
        if (status != null) {
            delay(1400)
            status = null
        }
    }
    val evStep = cameraObj?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 1f

    // Preset state
    var selectedPreset by remember { mutableStateOf(VintagePreset.NORMAL) }
    val selectedPresetReference = remember { AtomicReference(VintagePreset.NORMAL) }
    LaunchedEffect(selectedPreset) {
        selectedPresetReference.set(selectedPreset)
    }

    // Preview bitmap
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // LUT Async loading
    val selectedLut by produceState<Lut3D?>(initialValue = null, selectedPreset) {
        value = null
        value = selectedPreset.lutPath?.let { path ->
            withContext(Dispatchers.IO) { loadLutFromAssets(context, path) }
        }
    }

    val selectedLutReference = remember { AtomicReference<Lut3D?>(null) }
    LaunchedEffect(selectedLut) {
        selectedLutReference.set(selectedLut)
    }

    // Camera Provider
    val cameraProviderState = produceState<ProcessCameraProvider?>(
        initialValue = null
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { value = future.get() },
            mainExecutor
        )
        awaitCancellation()
    }
    val cameraProvider = cameraProviderState.value

    DisposableEffect(Unit) {
        onDispose {
            cameraProviderState.value?.unbindAll()
            analysisExecutor.shutdown()
            captureExecutor.shutdown()
            photoProcessingExecutor.shutdown()
        }
    }

    // Image Capture with ZSL and max 12MP ResolutionSelector
    val captureSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(4000, 3000),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
            )
        )
        .build()

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(captureSelector)
            .setJpegQuality(95)
            .setTargetRotation(android.view.Surface.ROTATION_0)
            .build()
    }

    LaunchedEffect(deviceRotation) {
        imageCapture.targetRotation = when (deviceRotation) {
            90 -> android.view.Surface.ROTATION_90
            180 -> android.view.Surface.ROTATION_180
            270 -> android.view.Surface.ROTATION_270
            else -> android.view.Surface.ROTATION_0
        }
    }

    // Zoom Observer
    DisposableEffect(cameraObj) {
        val observer = Observer<ZoomState> { state ->
            zoomRatio = state.zoomRatio
        }
        cameraObj?.cameraInfo?.zoomState?.observe(lifecycleOwner, observer)
        onDispose {
            cameraObj?.cameraInfo?.zoomState?.removeObserver(observer)
        }
    }

    // UI
    Column(
        modifier = modifier
            .background(Ui.bg)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))

        // Viewfinder
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .weight(1f, fill = false)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Ui.hairline, RoundedCornerShape(24.dp))
        ) {
            if (cameraProvider != null) {
                AndroidView<PreviewView>(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }

                        val scaleGestureDetector = ScaleGestureDetector(
                            ctx,
                            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                override fun onScale(detector: ScaleGestureDetector): Boolean {
                                    val current = cameraObj?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                                    cameraObj?.cameraControl?.setZoomRatio(current * detector.scaleFactor)
                                    return true
                                }
                            }
                        )

                        val gestureDetector = GestureDetector(
                            ctx,
                            object : GestureDetector.SimpleOnGestureListener() {
                                @SuppressLint("ClickableViewAccessibility")
                                override fun onSingleTapUp(e: MotionEvent): Boolean {
                                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                                    focusPoint = Offset(e.x, e.y)
                                    focusOk = null

                                    val future = cameraObj?.cameraControl?.startFocusAndMetering(
                                        FocusMeteringAction.Builder(point).build()
                                    )
                                    future?.addListener({
                                        focusOk = try { future.get().isFocusSuccessful } catch (ex: Exception) { false }
                                    }, ContextCompat.getMainExecutor(ctx))

                                    exposureIndex = 0f
                                    cameraObj?.cameraControl?.setExposureCompensationIndex(0)
                                    return true
                                }
                            }
                        )

                        val preview = Preview.Builder()
                            .build()
                            .also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                        previewView.setOnTouchListener { _, event ->
                            scaleGestureDetector.onTouchEvent(event)
                            if (!scaleGestureDetector.isInProgress) {
                                gestureDetector.onTouchEvent(event)
                            }
                            true
                        }

                        val analysisSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setResolutionSelector(analysisSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()

                        var lastFrameTime = 0L
                        imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                            try {
                                val now = System.nanoTime()
                                val minGap = if (processingCount.get() > 0) 100_000_000L else 33_000_000L
                                if (now - lastFrameTime < minGap) return@setAnalyzer
                                lastFrameTime = now

                                val preset = selectedPresetReference.get()
                                val lut = selectedLutReference.get()

                                if (preset != VintagePreset.NORMAL && lut != null) {
                                    val filtered = previewProcessor.process(image, lut)
                                    overlayShown.set(true)
                                    mainExecutor.execute { previewBitmap = filtered }
                                } else if (overlayShown.getAndSet(false)) {
                                    mainExecutor.execute { previewBitmap = null }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "analyzer", e)
                            } finally {
                                image.close()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()

                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                                imageAnalysis
                            )
                            cameraObj = camera

                            val zsl = camera.cameraInfo.isZslSupported
                            Log.d(TAG, "ZSL soportado: $zsl")

                            val range = camera.cameraInfo.exposureState.exposureCompensationRange
                            exposureRange = range.lower..range.upper

                        } catch (exc: Exception) {
                            Log.e(TAG, "Error iniciando cámara", exc)
                            status = "Error iniciando cámara" to System.nanoTime()
                        }

                        previewView
                    }
                )

                previewBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                CameraGrid(Modifier.fillMaxSize())

                FocusIndicator(focusPoint, focusOk) { focusPoint = null }

                ZoomIndicator(
                    zoom = zoomRatio,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                )

                StatusPill(
                    message = status?.first,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                )

                if (flashAlpha > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = flashAlpha))
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Presets
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            items(VintagePreset.entries) { preset ->
                PresetChip(preset, selectedPreset == preset) { selectedPreset = preset }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Exposure Control
        if (cameraObj != null && exposureRange.last > exposureRange.first) {
            ExposureControl(
                index = exposureIndex,
                range = exposureRange,
                stepEv = evStep,
                onChange = {
                    exposureIndex = it
                    cameraObj?.cameraControl?.setExposureCompensationIndex(it.roundToInt())
                },
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
            )
        }

        Spacer(Modifier.height(14.dp))

        // Shot Button
        ShutterButton(
            enabled = !capturing,
            onClick = {
                capturing = true
                flashTarget = 0.7f

                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        20L,
                        60
                    )
                )
                val tapRealtimeNs = SystemClock.elapsedRealtimeNanos()
                val captureStartTime = System.nanoTime()

                processAndTakePhoto(
                    context = context,
                    imageCapture = imageCapture,
                    preset = selectedPreset,
                    lut = selectedLut,
                    captureExecutor = captureExecutor,
                    photoProcessingExecutor = photoProcessingExecutor,
                    captureStartTime = captureStartTime,
                    tapRealtimeNs = tapRealtimeNs,
                    processingCount = processingCount,
                    onHandedOff = { mainExecutor.execute { capturing = false } },
                    onResult = { resultMsg -> status = resultMsg to System.nanoTime() }
                )
            },
            modifier = Modifier.padding(bottom = 28.dp)
        )
    }
}

// ============================================================
// CAPTURE AND PROCESSING
// ============================================================

private fun processAndTakePhoto(
    context: Context,
    imageCapture: ImageCapture,
    preset: VintagePreset,
    lut: Lut3D?,
    captureExecutor: ExecutorService,
    photoProcessingExecutor: ExecutorService,
    captureStartTime: Long,
    tapRealtimeNs: Long,
    processingCount: AtomicInteger,
    onHandedOff: () -> Unit,
    onResult: (String) -> Unit
) {
    val mainExecutor = ContextCompat.getMainExecutor(context)

    imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {

        override fun onCaptureSuccess(image: ImageProxy) {
            val shutterMs = (System.nanoTime() - captureStartTime) / 1_000_000
            val captureDate = Date()
            val frameTimestampNs = image.imageInfo.timestamp
            val frameOffsetMs = (frameTimestampNs - tapRealtimeNs) / 1_000_000

            val buffer = image.planes.firstOrNull()?.buffer
            if (buffer == null) {
                image.close()
                onHandedOff()
                return
            }
            buffer.rewind()
            val jpegBytes = ByteArray(buffer.remaining())
            buffer.get(jpegBytes)
            val rotationDegrees = image.imageInfo.rotationDegrees
            val queuedAt = System.nanoTime()
            image.close()
            onHandedOff()

            processingCount.incrementAndGet()
            photoProcessingExecutor.execute {
                val waitMs = (System.nanoTime() - queuedAt) / 1_000_000
                var mark = System.nanoTime()
                fun lap(): Long {
                    val now = System.nanoTime()
                    val d = (now - mark) / 1_000_000
                    mark = now
                    return d
                }

                val expInfo = try {
                    val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
                    val exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()
                    val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    val expTxt = if (exp != null && exp > 0 && exp < 1) "1/${Math.round(1.0 / exp)} s" else "${exp ?: "?"} s"
                    "exp $expTxt · ISO ${iso ?: "?"}"
                } catch (e: Exception) { "exp ?" }

                try {
                    val opts = BitmapFactory.Options().apply {
                        inMutable = true
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                    if (bitmap == null) {
                        mainExecutor.execute {
                            onResult("No se pudo procesar la imagen")
                        }
                        return@execute
                    }
                    val megapixels = bitmap.width * bitmap.height / 1_000_000f
                    val decodeMs = lap()

                    if (rotationDegrees != 0) {
                        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                        if (rotated !== bitmap) bitmap.recycle()
                        bitmap = rotated
                    }
                    if (!bitmap.isMutable) {
                        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        bitmap.recycle()
                        bitmap = copy
                    }
                    val rotateMs = lap()

                    // Dibujar la fecha ANTES del filtro LUT para que el filtro la integre naturally
                    drawSegmentDate(bitmap, captureDate)
                    val dateMs = lap()

                    if (preset != VintagePreset.NORMAL && lut != null) lut.applyInPlace(bitmap)
                    val lutMs = lap()

                    val ok = saveBitmapToGallery(context, bitmap, SAVE_QUALITY)
                    val saveMs = lap()
                    bitmap.recycle()

                    val totalMs = (System.nanoTime() - captureStartTime) / 1_000_000
                    Log.d(
                        TAG,
                        "disparo=${shutterMs}ms cola=${waitMs}ms decod=$decodeMs rot=$rotateMs " +
                                "fecha=$dateMs filtro=$lutMs guardar=$saveMs total=${totalMs}ms " +
                                "(${"%.1f".format(megapixels)} MP) frame vs toque ${if (frameOffsetMs >= 0) "+" else ""}$frameOffsetMs ms · $expInfo"
                    )
                    mainExecutor.execute {
                        onResult(if (ok) "Foto guardada" else "No se pudo guardar la foto")
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "sin memoria", e)
                    mainExecutor.execute {
                        onResult("Sin memoria para procesar la foto")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "procesando foto", e)
                    mainExecutor.execute {
                        onResult("Error al procesar la foto")
                    }
                } finally {
                    processingCount.decrementAndGet()
                }
            }
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "captura", exception)
            onHandedOff()
            mainExecutor.execute {
                onResult("Error al tomar la foto")
            }
        }
    })
}

// ============================================================
// DATE STAMP ON BITMAP (PROGRAMMATIC 7-SEGMENT)
// ============================================================

private fun drawSegmentDate(
    bitmap: Bitmap,
    date: Date,
    color: Int = AndroidColor.argb(190, 225, 130, 35),   // ámbar atenuado, menos brillante
    withOutline: Boolean = true                          // halo oscuro muy suave difuminado
) {
    val width = bitmap.width.toFloat()
    val h = width * 0.021f                  // alto de los dígitos
    val w = h * 0.50f                       // ancho de cada dígito
    val t = h * 0.125f                      // grosor de los segmentos
    val k = tan(Math.toRadians(7.0)).toFloat()   // inclinación
    val s = t                               // hueco entre segmentos
    val step = w + t + h * 0.04f            // avance entre dígitos
    val groupGap = h * 0.65f                // espacio acortado entre año, mes y día
    val apostropheGap = h * 0.55f           // del apóstrofo al primer dígito
    val marginX = width * 0.03f
    val marginY = width * 0.06f                  // duplicado desde el fondo hacia arriba

    val year = SimpleDateFormat("yy", Locale.US).format(date)
    val month = SimpleDateFormat("M", Locale.US).format(date)
    val day = SimpleDateFormat("d", Locale.US).format(date)

    // Posición (relativa) de cada dígito
    val chars = ArrayList<Pair<Char, Float>>()
    var cursor = 0f
    listOf(year, month, day).forEachIndexed { i, group ->
        if (i > 0) cursor += groupGap
        group.forEach { ch -> chars.add(ch to cursor); cursor += step }
    }
    val lastX0 = chars.last().second
    val totalWidth = lastX0 + w + t / 2f + h * k / 4f

    val x0First = bitmap.width - marginX - totalWidth
    val y0 = bitmap.height - marginY - h - t / 2f

    val m = h / 2f
    val seg = mapOf(
        'a' to floatArrayOf(s, 0f, w - s, 0f),
        'b' to floatArrayOf(w, s, w, m - s),
        'c' to floatArrayOf(w, m + s, w, h - s),
        'd' to floatArrayOf(s, h, w - s, h),
        'e' to floatArrayOf(0f, m + s, 0f, h - s),
        'f' to floatArrayOf(0f, s, 0f, m - s),
        'g' to floatArrayOf(s, m, w - s, m)
    )

    val canvas = Canvas(bitmap)
    
    // Pincel principal de la fecha con difuminado óptico de LED
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        this.color = color
        strokeWidth = t
        maskFilter = android.graphics.BlurMaskFilter(t * 0.22f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    // Sombreado/halo oscuro reducido y mas sutil
    val outline = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        this.color = AndroidColor.argb(20, 40, 10, 0)
        strokeWidth = t * 1.5f
        maskFilter = android.graphics.BlurMaskFilter(t * 1.0f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    // Línea con inclinación: la parte de arriba se corre a la derecha
    fun line(x0: Float, ax: Float, ay: Float, bx: Float, by: Float) {
        val x1 = x0 + ax + (m - ay) * k
        val x2 = x0 + bx + (m - by) * k
        if (withOutline) canvas.drawLine(x1, y0 + ay, x2, y0 + by, outline)
        canvas.drawLine(x1, y0 + ay, x2, y0 + by, paint)
    }

    // Apóstrofo: segmento corto inclinado, arriba a la izquierda del año
    line(x0First - apostropheGap, 0f, h * 0.07f, 0f, h * 0.36f)

    // Dígitos
    chars.forEach { (ch, relX) ->
        val x0 = x0First + relX
        DIGIT_SEGMENTS[ch]?.forEach { name ->
            val p = seg.getValue(name)
            line(x0, p[0], p[1], p[2], p[3])
        }
    }
}

// ============================================================
// SAVE TO GALLERY
// ============================================================

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, quality: Int): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Vintage_${System.currentTimeMillis()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VintageCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        val written = resolver.openOutputStream(uri)?.let { raw ->
            BufferedOutputStream(raw, 256 * 1024).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
        } ?: false
        if (!written) throw IllegalStateException("compress devolvió false")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "guardando", e)
        resolver.delete(uri, null, null)
        false
    }
}

// ============================================================
// GRID
// ============================================================

@Composable
fun CameraGrid(
    modifier: Modifier
) {
    Canvas(modifier) {
        val color = Color.White.copy(alpha = 0.22f)

        drawLine(
            color,
            Offset(size.width / 3, 0f),
            Offset(size.width / 3, size.height),
            1.dp.toPx()
        )

        drawLine(
            color,
            Offset(2 * size.width / 3, 0f),
            Offset(2 * size.width / 3, size.height),
            1.dp.toPx()
        )

        drawLine(
            color,
            Offset(0f, size.height / 3),
            Offset(size.width, size.height / 3),
            1.dp.toPx()
        )

        drawLine(
            color,
            Offset(0f, 2 * size.height / 3),
            Offset(size.width, 2 * size.height / 3),
            1.dp.toPx()
        )
    }
}

// ============================================================
// UI MINIMALISTA
// ============================================================

private object Ui {
    val bg = Color(0xFF0B0B0D)
    val hairline = Color(0xFF2A2A2F)
    val text = Color(0xFFF2EDE6)
    val muted = Color(0xFF8C877E)
    val amber = Color(0xFFE8892B)
}

/** Círculo de enfoque: aparece, se asienta, y desaparece solo en ~1 segundo. */
@Composable
fun FocusIndicator(point: Offset?, focused: Boolean?, onFinished: () -> Unit) {
    if (point == null) return
    val grow = remember { Animatable(1.5f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(point) {
        fade.snapTo(1f)
        grow.snapTo(1.5f)
        grow.animateTo(1f, tween(200))
        delay(550)
        fade.animateTo(0f, tween(250))
        onFinished()
    }
    // Ámbar si la cámara confirmó el enfoque, blanco mientras tanto
    val ringColor by animateColorAsState(if (focused == true) Ui.amber else Ui.text, label = "focusColor")
    val density = LocalDensity.current
    Box(
        Modifier
            .offset(
                with(density) { point.x.toDp() } - 32.dp,
                with(density) { point.y.toDp() } - 32.dp
            )
            .size(64.dp)
            .graphicsLayer {
                scaleX = grow.value
                scaleY = grow.value
                alpha = fade.value
            }
            .border(1.5.dp, ringColor, CircleShape)
    )
}

@Composable
fun ZoomIndicator(zoom: Float, modifier: Modifier) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = "%.1fx".format(zoom),
            color = Ui.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
fun PresetChip(preset: VintagePreset, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg by animateColorAsState(
        if (isSelected) Ui.amber.copy(alpha = 0.16f) else Color.Transparent, label = "chipBg"
    )
    val fg by animateColorAsState(if (isSelected) Ui.amber else Ui.muted, label = "chipFg")
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (isSelected) Ui.amber else Ui.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = preset.label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp
        )
    }
}

/** Barra de exposición manual. Muestra EV real (índice × paso de la cámara), no el índice crudo. */
@Composable
fun ExposureControl(
    index: Float,
    range: IntRange,
    stepEv: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val i = index.roundToInt()
    val label = if (i == 0) "0.0" else "%+.1f".format(i * stepEv)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("EV", color = Ui.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp)
        Slider(
            value = index,
            onValueChange = onChange,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            colors = SliderDefaults.colors(
                thumbColor = Ui.text,
                activeTrackColor = Ui.amber,
                inactiveTrackColor = Ui.hairline,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
        Text(
            text = label,
            color = Ui.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
    }
}

/** Botón de disparo: anillo + círculo interior que se achica al apretar. */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val inner by animateDpAsState(
        targetValue = if (pressed) 52.dp else if (enabled) 62.dp else 46.dp,
        animationSpec = tween(90),
        label = "shutterInner"
    )
    val tone = if (enabled) Ui.text else Ui.muted
    Box(
        modifier
            .size(80.dp)
            .border(3.dp, tone, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(inner).background(tone, CircleShape))
    }
}

/** Aviso breve sobre el visor ("Foto guardada"). Se muestra con `message != null`. */
@Composable
fun StatusPill(message: String?, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf("") }
    LaunchedEffect(message) { if (message != null) last = message }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 },
        exit = fadeOut(tween(250)),
        modifier = modifier
    ) {
        Row(
            Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).background(Ui.amber, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(message ?: last, color = Ui.text, fontSize = 12.sp, letterSpacing = 0.6.sp)
        }
    }
}

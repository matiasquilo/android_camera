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
import android.os.Build
import android.os.Bundle
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
import android.widget.Toast
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.tan

private const val TAG = "Vintage"
private const val SHOW_TIMINGS = true    // ponelo en false cuando termines de medir
private const val SAVE_QUALITY = 92      // antes 95; casi no se nota y comprime más rápido

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

        val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val chunk = (px.size + n - 1) / n
        (0 until n).map { t ->
            thread {
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
                color = Color.Black
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
    val photoProcessingExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewProcessor = remember { PreviewProcessor() }
    val overlayShown = remember { AtomicBoolean(false) }

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
        modifier = modifier.statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))

        // Viewfinder
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f, fill = false)
                .aspectRatio(3f / 4f)
                .border(
                    BorderStroke(1.dp, Color.DarkGray),
                    RoundedCornerShape(4.dp)
                )
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

                                    cameraObj?.cameraControl?.startFocusAndMetering(
                                        FocusMeteringAction.Builder(point).build()
                                    )

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
                                if (now - lastFrameTime < 33_000_000L) return@setAnalyzer
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
                            Toast.makeText(context, "Error iniciando cámara", Toast.LENGTH_SHORT).show()
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

                FocusIndicator(focusPoint)

                ZoomIndicator(
                    zoom = zoomRatio,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
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

        Spacer(Modifier.height(16.dp))

        // Presets
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            items(VintagePreset.entries) { preset ->
                PresetItem(
                    preset = preset,
                    isSelected = selectedPreset == preset
                ) {
                    selectedPreset = preset
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Exposure Slider
        if (cameraObj != null) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EV",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )

                Slider(
                    value = exposureIndex,
                    onValueChange = {
                        exposureIndex = it
                        cameraObj?.cameraControl?.setExposureCompensationIndex(it.toInt())
                    },
                    valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                    steps = if (exposureRange.last > exposureRange.first) {
                        exposureRange.last - exposureRange.first - 1
                    } else {
                        0
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFD1C4E9),
                        activeTrackColor = Color(0xFF7E57C2)
                    )
                )

                Text(
                    text = exposureIndex.toInt().toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Shot Button
        Button(
            onClick = {
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
                        70L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
                val captureStartTime = System.nanoTime()

                processAndTakePhoto(
                    context = context,
                    imageCapture = imageCapture,
                    preset = selectedPreset,
                    lut = selectedLut,
                    captureExecutor = captureExecutor,
                    photoProcessingExecutor = photoProcessingExecutor,
                    captureStartTime = captureStartTime
                )
            },
            modifier = Modifier
                .padding(bottom = 32.dp)
                .size(86.dp)
                .border(2.dp, Color(0xFFB39DDB), CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF673AB7)
            )
        ) {
            Text(
                text = "SHOT",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ============================================================
// PRESET ITEM
// ============================================================

@Composable
fun PresetItem(
    preset: VintagePreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(width = 64.dp, height = 32.dp)
            .background(
                if (isSelected) Color(0xFF673AB7) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .border(
                1.dp,
                if (isSelected) Color.White else Color.Gray,
                RoundedCornerShape(4.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = preset.label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
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
    captureStartTime: Long
) {
    val mainExecutor = ContextCompat.getMainExecutor(context)

    imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {

        override fun onCaptureSuccess(image: ImageProxy) {
            val shutterMs = (System.nanoTime() - captureStartTime) / 1_000_000
            val captureDate = Date()

            val buffer = image.planes.firstOrNull()?.buffer
            if (buffer == null) { image.close(); return }
            buffer.rewind()
            val jpegBytes = ByteArray(buffer.remaining())
            buffer.get(jpegBytes)
            val rotationDegrees = image.imageInfo.rotationDegrees
            val queuedAt = System.nanoTime()
            image.close()

            photoProcessingExecutor.execute {
                val waitMs = (System.nanoTime() - queuedAt) / 1_000_000
                var mark = System.nanoTime()
                fun lap(): Long {
                    val now = System.nanoTime()
                    val d = (now - mark) / 1_000_000
                    mark = now
                    return d
                }

                try {
                    val opts = BitmapFactory.Options().apply {
                        inMutable = true
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                    if (bitmap == null) {
                        mainExecutor.execute {
                            Toast.makeText(context, "No se pudo procesar la imagen", Toast.LENGTH_SHORT).show()
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

                    // Dibujar la fecha ANTES del filtro LUT para que el filtro la integre naturalmente
                    drawSegmentDate(bitmap, captureDate)
                    val dateMs = lap()

                    if (preset != VintagePreset.NORMAL && lut != null) lut.applyInPlace(bitmap)
                    val lutMs = lap()

                    val ok = saveBitmapToGallery(context, bitmap, SAVE_QUALITY)
                    val saveMs = lap()
                    bitmap.recycle()

                    val totalMs = (System.nanoTime() - captureStartTime) / 1_000_000
                    val msg = buildString {
                        append(if (ok) "¡Captura Vintage!" else "Error guardando la foto")
                        if (SHOW_TIMINGS) {
                            append("\nFoto de %.1f MP".format(megapixels))
                            append("\ndisparo $shutterMs ms · espera cola $waitMs ms")
                            append("\ndecodificar $decodeMs · rotar $rotateMs · filtro $lutMs")
                            append("\nfecha $dateMs · guardar $saveMs")
                            append("\nTOTAL $totalMs ms")
                        }
                    }
                    Log.d(TAG, msg.replace("\n", " | "))
                    mainExecutor.execute {
                        Toast.makeText(
                            context, msg,
                            if (SHOW_TIMINGS) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "sin memoria", e)
                    mainExecutor.execute {
                        Toast.makeText(context, "Sin memoria para procesar la foto", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "procesando foto", e)
                    mainExecutor.execute {
                        Toast.makeText(context, "Error al procesar la foto", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "captura", exception)
            mainExecutor.execute {
                Toast.makeText(context, "Error al tomar la foto", Toast.LENGTH_SHORT).show()
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
    val groupGap = h * 0.65f                // espacio acortado entre año, mes y día (antes 0.95f)
    val apostropheGap = h * 0.55f           // del apóstrofo al primer dígito (antes 0.88f)
    val marginX = width * 0.03f
    val marginY = width * 0.06f                  // duplicado desde el fondo hacia arriba (antes 0.03f)

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
    
    // Pincel principal de la fecha con difuminado óptico de LED un poco más definido
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
// FOCUS INDICATOR
// ============================================================

@Composable
fun FocusIndicator(
    point: Offset?
) {
    if (point == null) return

    val anim = remember { Animatable(0.6f) }

    LaunchedEffect(point) {
        anim.snapTo(0.6f)
        anim.animateTo(1.3f, tween(250))
        anim.animateTo(1.0f, tween(150))
    }

    Box(
        Modifier
            .offset(
                with(LocalDensity.current) { point.x.toDp() } - 30.dp,
                with(LocalDensity.current) { point.y.toDp() } - 30.dp
            )
            .size(60.dp)
            .scale(anim.value)
            .border(
                2.dp,
                Color.White.copy(alpha = 0.8f),
                CircleShape
            )
    )
}

// ============================================================
// ZOOM INDICATOR
// ============================================================

@Composable
fun ZoomIndicator(
    zoom: Float,
    modifier: Modifier
) {
    Box(
        modifier
            .background(
                Color.Black.copy(alpha = 0.5f),
                RoundedCornerShape(4.dp)
            )
            .padding(4.dp)
    ) {
        Text(
            text = "%.1fx".format(zoom),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
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
        val color = Color.White.copy(alpha = 0.3f)

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

package com.exe.vintagecameraapp
import androidx.core.content.res.ResourcesCompat
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference


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
// LUT 3D 64³ EN PNG 512x512
//
// Organización:
//
// 512 x 512
// 8 x 8 bloques
// cada bloque = 64 x 64
//
// R = eje X
// G = eje Y
// B = selección del bloque
// ============================================================

class Lut3D(
    private val bitmap: Bitmap
) {

    companion object {

        private const val LUT_SIZE = 64
        private const val GRID_SIZE = 8
        private const val IMAGE_SIZE = 512
    }

    private val pixels: IntArray

    init {

        require(
            bitmap.width == IMAGE_SIZE &&
                    bitmap.height == IMAGE_SIZE
        ) {
            "La LUT debe ser exactamente 512x512."
        }

        pixels =
            IntArray(
                IMAGE_SIZE * IMAGE_SIZE
            )

        bitmap.getPixels(
            pixels,
            0,
            IMAGE_SIZE,
            0,
            0,
            IMAGE_SIZE,
            IMAGE_SIZE
        )
    }


    private fun lutPixel(
        x: Int,
        y: Int
    ): Int {

        return pixels[
            y * IMAGE_SIZE + x
        ]
    }


    private fun sample(
        r: Int,
        g: Int,
        b: Int
    ): Int {

        // Hald 8:
        // Imagen: 512 x 512
        // LUT real: 64 x 64 x 64
        val rIndex = r.coerceIn(0, 255) * 63 / 255
        val gIndex = g.coerceIn(0, 255) * 63 / 255
        val bIndex = b.coerceIn(0, 255) * 63 / 255

        // Hald 8:
        //
        // X = (R % 64) + (G % 8) * 64
        // Y = B * 8 + (G / 8)
        //
        // Resultado:
        // X: 0..511
        // Y: 0..511

        val x = (rIndex % 64) + (gIndex % 8) * 64
        val y = bIndex * 8 + (gIndex / 8)

        return lutPixel(
            x.coerceIn(0, 511),
            y.coerceIn(0, 511)
        )
    }

    fun apply(
        source: Bitmap
    ): Bitmap {

        val width =
            source.width

        val height =
            source.height


        val input =
            IntArray(
                width * height
            )

        val output =
            IntArray(
                width * height
            )


        source.getPixels(
            input,
            0,
            width,
            0,
            0,
            width,
            height
        )


        for (i in input.indices) {

            val color =
                input[i]


            val r =
                AndroidColor.red(
                    color
                )

            val g =
                AndroidColor.green(
                    color
                )

            val b =
                AndroidColor.blue(
                    color
                )

            val a =
                AndroidColor.alpha(
                    color
                )


            val lutColor =
                sample(
                    r,
                    g,
                    b
                )


            output[i] =
                AndroidColor.argb(
                    a,
                    AndroidColor.red(
                        lutColor
                    ),
                    AndroidColor.green(
                        lutColor
                    ),
                    AndroidColor.blue(
                        lutColor
                    )
                )
        }


        return Bitmap.createBitmap(
            output,
            width,
            height,
            Bitmap.Config.ARGB_8888
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

        context.assets
            .open(path)
            .use { stream ->

                val bitmap =
                    BitmapFactory.decodeStream(
                        stream
                    )
                        ?: return null

                Lut3D(bitmap)
            }

    } catch (e: Exception) {

        e.printStackTrace()

        null
    }
}


// ============================================================
// MAIN ACTIVITY
// ============================================================

class MainActivity : ComponentActivity() {

    private var hasCameraPermission
            by mutableStateOf(false)

    private var deviceRotation
            by mutableIntStateOf(0)

    private lateinit var orientationEventListener:
            OrientationEventListener


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        enableEdgeToEdge()


        orientationEventListener =
            object :
                OrientationEventListener(
                    this
                ) {

                override fun onOrientationChanged(
                    orientation: Int
                ) {

                    deviceRotation =
                        when (orientation) {

                            in 45..134 ->
                                270

                            in 135..224 ->
                                180

                            in 225..314 ->
                                90

                            else ->
                                0
                        }
                }
            }


        orientationEventListener.enable()


        hasCameraPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
                    PackageManager.PERMISSION_GRANTED


        if (!hasCameraPermission) {

            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {

                hasCameraPermission =
                    it

            }.launch(
                Manifest.permission.CAMERA
            )
        }


        setContent {

            Surface(
                modifier =
                    Modifier.fillMaxSize(),

                color =
                    Color.Black
            ) {

                if (hasCameraPermission) {

                    CameraPreviewScreen(
                        modifier =
                            Modifier.fillMaxSize(),

                        deviceRotation =
                            deviceRotation
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

@Composable
fun CameraPreviewScreen(
    modifier: Modifier = Modifier,
    deviceRotation: Int
) {

    val context =
        LocalContext.current

    val lifecycleOwner =
        LocalLifecycleOwner.current

    val scope =
        rememberCoroutineScope()


    // ========================================================
    // CAMERA STATE
    // ========================================================

    var cameraObj by remember {

        mutableStateOf<Camera?>(
            null
        )
    }


    var exposureIndex by remember {

        mutableFloatStateOf(
            0f
        )
    }


    var exposureRange by remember {

        mutableStateOf(
            -1..1
        )
    }


    var zoomRatio by remember {

        mutableFloatStateOf(
            1f
        )
    }


    var focusPoint by remember {

        mutableStateOf<Offset?>(
            null
        )
    }


    // ========================================================
    // PRESET
    // ========================================================

    var selectedPreset by remember {

        mutableStateOf(
            VintagePreset.NORMAL
        )
    }


    val selectedPresetReference =
        remember {

            AtomicReference(
                VintagePreset.NORMAL
            )
        }


    LaunchedEffect(
        selectedPreset
    ) {

        selectedPresetReference.set(
            selectedPreset
        )
    }


    // ========================================================
    // PREVIEW BITMAP
    // ========================================================

    var previewBitmap by remember {

        mutableStateOf<Bitmap?>(
            null
        )
    }


    // ========================================================
    // LUT
    // ========================================================

    val selectedLut =
        remember(selectedPreset) {

            selectedPreset.lutPath?.let { path ->

                loadLutFromAssets(
                    context,
                    path
                )
            }
        }


    val selectedLutReference =
        remember {

            AtomicReference<Lut3D?>(
                null
            )
        }


    LaunchedEffect(
        selectedLut
    ) {

        selectedLutReference.set(
            selectedLut
        )
    }


    // ========================================================
    // CAMERA PROVIDER
    // ========================================================

    val cameraProviderState =
        produceState<ProcessCameraProvider?>(
            initialValue = null
        ) {

            val future =
                ProcessCameraProvider
                    .getInstance(
                        context
                    )


            future.addListener(

                {

                    value =
                        future.get()
                },

                ContextCompat
                    .getMainExecutor(
                        context
                    )
            )


            awaitCancellation()
        }


    val cameraProvider =
        cameraProviderState.value


    // ========================================================
    // EXECUTOR
    // ========================================================

    val cameraExecutor =
        remember {

            Executors.newSingleThreadExecutor()
        }
    val photoProcessingExecutor =
        remember {
            Executors.newSingleThreadExecutor()
        }


    DisposableEffect(Unit) {

        onDispose {

            cameraExecutor.shutdown()

            photoProcessingExecutor.shutdown()
        }
    }


    // ========================================================
    // IMAGE CAPTURE
    //
    // CameraX ImageCapture utiliza JPEG por defecto.
    // ========================================================

    val imageCapture =
        remember {

            ImageCapture.Builder()

                .setCaptureMode(
                    ImageCapture
                        .CAPTURE_MODE_MINIMIZE_LATENCY
                )

                .setTargetAspectRatio(
                    AspectRatio.RATIO_4_3
                )

                .setJpegQuality(
                    95
                )

                .setTargetRotation(
                    android.view.Surface.ROTATION_0
                )

                .build()
        }
    LaunchedEffect(deviceRotation) {

        imageCapture.targetRotation =
            when (deviceRotation) {

                90 ->
                    android.view.Surface.ROTATION_90

                180 ->
                    android.view.Surface.ROTATION_180

                270 ->
                    android.view.Surface.ROTATION_270

                else ->
                    android.view.Surface.ROTATION_0
            }
    }


    // ========================================================
    // ZOOM OBSERVER
    // ========================================================

    DisposableEffect(
        cameraObj
    ) {

        val observer =
            Observer<ZoomState> { state ->

                zoomRatio =
                    state.zoomRatio
            }


        cameraObj
            ?.cameraInfo
            ?.zoomState
            ?.observe(
                lifecycleOwner,
                observer
            )


        onDispose {

            cameraObj
                ?.cameraInfo
                ?.zoomState
                ?.removeObserver(
                    observer
                )
        }
    }


    // ========================================================
    // UI
    // ========================================================

    Column(

        modifier =
            modifier.statusBarsPadding(),

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Spacer(
            Modifier.height(12.dp)
        )


        // ====================================================
        // VIEWFINDER
        // ====================================================

        Box(

            modifier =
                Modifier
                    .padding(
                        horizontal = 16.dp
                    )
                    .weight(
                        1f,
                        fill = false
                    )
                    .aspectRatio(
                        3f / 4f
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Color.DarkGray
                        ),
                        RoundedCornerShape(
                            4.dp
                        )
                    )
        ) {

            if (cameraProvider != null) {

                AndroidView<PreviewView>(

                    modifier =
                        Modifier.fillMaxSize(),

                    factory = { ctx ->

                        val previewView =
                            PreviewView(
                                ctx
                            ).apply {

                                scaleType =
                                    PreviewView
                                        .ScaleType
                                        .FIT_CENTER

                                implementationMode =
                                    PreviewView
                                        .ImplementationMode
                                        .COMPATIBLE
                            }


                        // ====================================
                        // PINCH TO ZOOM
                        // ====================================

                        val scaleGestureDetector =
                            ScaleGestureDetector(

                                ctx,

                                object :
                                    ScaleGestureDetector
                                    .SimpleOnScaleGestureListener() {

                                    override fun onScale(
                                        detector:
                                        ScaleGestureDetector
                                    ): Boolean {

                                        val current =
                                            cameraObj
                                                ?.cameraInfo
                                                ?.zoomState
                                                ?.value
                                                ?.zoomRatio
                                                ?: 1f


                                        cameraObj
                                            ?.cameraControl
                                            ?.setZoomRatio(

                                                current *
                                                        detector.scaleFactor
                                            )


                                        return true
                                    }
                                }
                            )


                        // ====================================
                        // TAP TO FOCUS
                        // ====================================

                        val gestureDetector =
                            GestureDetector(

                                ctx,

                                object :
                                    GestureDetector
                                    .SimpleOnGestureListener() {

                                    @SuppressLint(
                                        "ClickableViewAccessibility"
                                    )
                                    override fun onSingleTapUp(
                                        e: MotionEvent
                                    ): Boolean {

                                        val point =
                                            previewView
                                                .meteringPointFactory
                                                .createPoint(
                                                    e.x,
                                                    e.y
                                                )


                                        focusPoint =
                                            Offset(
                                                e.x,
                                                e.y
                                            )


                                        //cameraObj
                                        //    ?.cameraControl
                                        //    ?.startFocusAndMetering(

                                        //        FocusMeteringAction
                                        //            .Builder(
                                        //                point
                                        //            )
                                        //            .build()
                                        //    )
                                        //

                                        exposureIndex =
                                            0f


                                        cameraObj
                                            ?.cameraControl
                                            ?.setExposureCompensationIndex(
                                                0
                                            )


                                        return true
                                    }
                                }
                            )


                        // ====================================
                        // PREVIEW
                        // ====================================

                        val preview =
                            Preview.Builder()

                                .setTargetAspectRatio(
                                    AspectRatio.RATIO_4_3
                                )

                                .build()

                                .also {

                                    it.setSurfaceProvider(
                                        previewView
                                            .surfaceProvider
                                    )
                                }


                        // ====================================
                        // TOUCH
                        // ====================================

                        previewView.setOnTouchListener {
                                _,
                                event ->

                            scaleGestureDetector
                                .onTouchEvent(
                                    event
                                )


                            if (
                                !scaleGestureDetector
                                    .isInProgress
                            ) {

                                gestureDetector
                                    .onTouchEvent(
                                        event
                                    )
                            }


                            true
                        }


                        // ====================================
                        // IMAGE ANALYSIS
                        //
                        // Esta es la imagen utilizada para
                        // la previsualización del LUT.
                        // ====================================

                        val imageAnalysis =
                            ImageAnalysis.Builder()

                                .setTargetAspectRatio(
                                    AspectRatio.RATIO_4_3
                                )

                                .setBackpressureStrategy(
                                    ImageAnalysis
                                        .STRATEGY_KEEP_ONLY_LATEST
                                )

                                .setOutputImageFormat(
                                    ImageAnalysis
                                        .OUTPUT_IMAGE_FORMAT_RGBA_8888
                                )

                                .build()


                        // ====================================
                        // ANALYZER
                        // ====================================

                        // ====================================
// ANALYZER
// ====================================

                        var lastFrameTime = 0L

                        imageAnalysis.setAnalyzer(

                            cameraExecutor

                        ) { image ->

                            try {

                                val currentPreset =
                                    selectedPresetReference
                                        .get()

                                val currentLut =
                                    selectedLutReference
                                        .get()


                                // ====================================
                                // LIMITAR PREVIEW A ~30 FPS
                                // ====================================

                                val now =
                                    System.nanoTime()

                                if (
                                    now - lastFrameTime <
                                    33_000_000L
                                ) {

                                    return@setAnalyzer
                                }

                                lastFrameTime =
                                    now


                                // ====================================
                                // APLICAR LUT
                                // ====================================

                                if (
                                    currentPreset !=
                                    VintagePreset.NORMAL &&
                                    currentLut != null
                                ) {

                                    val bitmap =
                                        imageProxyToBitmapRGBA(
                                            image
                                        )


                                    if (bitmap != null) {

                                        val filtered =
                                            currentLut.apply(
                                                bitmap
                                            )


                                        scope.launch(
                                            Dispatchers.Main
                                        ) {

                                            previewBitmap =
                                                filtered
                                        }
                                    }

                                } else {

                                    scope.launch(
                                        Dispatchers.Main
                                    ) {

                                        previewBitmap =
                                            null
                                    }
                                }

                            } catch (e: Exception) {

                                e.printStackTrace()

                            } finally {

                                image.close()
                            }
                        }


                        // ====================================
                        // BIND CAMERA
                        // ====================================

                        try {

                            cameraProvider.unbindAll()


                            cameraObj =
                                cameraProvider
                                    .bindToLifecycle(

                                        lifecycleOwner,

                                        CameraSelector
                                            .DEFAULT_BACK_CAMERA,

                                        preview,

                                        imageCapture,

                                        imageAnalysis
                                    )


                            val range =
                                cameraObj!!
                                    .cameraInfo
                                    .exposureState
                                    .exposureCompensationRange


                            exposureRange =
                                range.lower..
                                        range.upper

                        } catch (exc: Exception) {

                            exc.printStackTrace()


                            Toast.makeText(
                                context,
                                "Error iniciando cámara",
                                Toast.LENGTH_SHORT
                            ).show()
                        }


                        previewView
                    }
                )


                // =================================================
                // LUT PREVIEW OVERLAY
                // =================================================

                previewBitmap?.let { bitmap ->

                    Image(

                        bitmap =
                            bitmap.asImageBitmap(),

                        contentDescription =
                            null,

                        modifier =
                            Modifier.fillMaxSize(),

                        contentScale =
                            ContentScale.Fit
                    )
                }


                // =================================================
                // GRID
                // =================================================

                CameraGrid(
                    Modifier.fillMaxSize()
                )


                // =================================================
                // FOCUS INDICATOR
                // =================================================

                FocusIndicator(
                    focusPoint
                )


                // =================================================
                // ZOOM
                // =================================================

                ZoomIndicator(

                    zoom =
                        zoomRatio,

                    modifier =
                        Modifier
                            .align(
                                Alignment.TopEnd
                            )
                            .padding(
                                12.dp
                            )
                )


                // =================================================
                // DATE PREVIEW
                // =================================================


            }
        }


        Spacer(
            Modifier.height(16.dp)
        )


        // ====================================================
        // PRESETS
        // ====================================================

        LazyRow(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp
                    ),

            horizontalArrangement =
                Arrangement.Center
        ) {

            items(
                VintagePreset.entries
            ) { preset ->

                PresetItem(

                    preset =
                        preset,

                    isSelected =
                        selectedPreset ==
                                preset

                ) {

                    selectedPreset =
                        preset
                }
            }
        }


        Spacer(
            Modifier.height(12.dp)
        )


        // ====================================================
        // EXPOSURE
        // ====================================================

        if (cameraObj != null) {

            Row(

                modifier =
                    Modifier
                        .padding(
                            horizontal = 32.dp
                        )
                        .fillMaxWidth(),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(

                    text = "EV",

                    color =
                        Color.White,

                    style =
                        MaterialTheme
                            .typography
                            .labelSmall
                )


                Slider(

                    value =
                        exposureIndex,

                    onValueChange = {

                        exposureIndex =
                            it


                        cameraObj
                            ?.cameraControl
                            ?.setExposureCompensationIndex(
                                it.toInt()
                            )
                    },


                    valueRange =
                        exposureRange.first
                            .toFloat()..
                                exposureRange.last
                                    .toFloat(),


                    steps =
                        if (
                            exposureRange.last >
                            exposureRange.first
                        ) {

                            exposureRange.last -
                                    exposureRange.first -
                                    1

                        } else {

                            0
                        },


                    modifier =
                        Modifier.weight(
                            1f
                        ),


                    colors =
                        SliderDefaults.colors(

                            thumbColor =
                                Color(
                                    0xFFD1C4E9
                                ),

                            activeTrackColor =
                                Color(
                                    0xFF7E57C2
                                )
                        )
                )


                Text(

                    text =
                        exposureIndex
                            .toInt()
                            .toString(),

                    color =
                        Color.White,

                    style =
                        MaterialTheme
                            .typography
                            .labelSmall
                )
            }
        }


        Spacer(
            Modifier.height(16.dp)
        )


        // ====================================================
        // SHOT BUTTON
        // ====================================================

        Button(

            onClick = {


                val vibrator =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                        val vibratorManager =
                            context.getSystemService(
                                Context.VIBRATOR_MANAGER_SERVICE
                            ) as VibratorManager

                        vibratorManager.defaultVibrator

                    } else {

                        @Suppress("DEPRECATION")
                        context.getSystemService(
                            Context.VIBRATOR_SERVICE
                        ) as Vibrator
                    }


                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        70L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
                val captureStartTime =
                    System.nanoTime()


                processAndTakePhoto(

                    context =
                        context,

                    imageCapture =
                        imageCapture,

                    preset =
                        selectedPreset,

                    lut =
                        selectedLut,

                    executor =
                        cameraExecutor,

                    photoProcessingExecutor =
                        photoProcessingExecutor,

                    captureStartTime =
                        captureStartTime
                )
            },


            modifier =
                Modifier
                    .padding(
                        bottom = 32.dp
                    )
                    .size(
                        86.dp
                    )
                    .border(
                        2.dp,
                        Color(
                            0xFFB39DDB
                        ),
                        CircleShape
                    ),


            shape =
                CircleShape,


            colors =
                ButtonDefaults.buttonColors(

                    containerColor =
                        Color(
                            0xFF673AB7
                        )
                )
        ) {

            Text(

                text = "SHOT",

                color =
                    Color.White,

                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}


// ============================================================
// PRESET BUTTON
// ============================================================

@Composable
fun PresetItem(
    preset: VintagePreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {

    Box(

        modifier =
            Modifier
                .padding(
                    horizontal = 8.dp
                )
                .size(
                    width = 64.dp,
                    height = 32.dp
                )
                .background(

                    if (isSelected)
                        Color(
                            0xFF673AB7
                        )
                    else
                        Color.Transparent,

                    RoundedCornerShape(
                        4.dp
                    )
                )
                .border(

                    1.dp,

                    if (isSelected)
                        Color.White
                    else
                        Color.Gray,

                    RoundedCornerShape(
                        4.dp
                    )
                )
                .clickable {
                    onClick()
                },


        contentAlignment =
            Alignment.Center
    ) {

        Text(

            text =
                preset.label,

            color =
                Color.White,

            fontSize =
                11.sp,

            fontWeight =
                FontWeight.Bold
        )
    }
}



// ============================================================
// DATE PREVIEW
// ============================================================

@Composable
fun DateStampOverlay(
    rotation: Int,
    modifier: Modifier = Modifier
) {

    val digitalFont = FontFamily(
        Font(R.font.dseg7_classic_regular)
    )

    Text(

        text =
            SimpleDateFormat(
                "''yy  MM  dd",
                Locale.US
            ).format(
                Date()
            ),

        color =
            Color(
                0xFFFF8F00
            ),

        fontSize =
            14.sp,

        fontWeight =
            FontWeight.Normal,

        fontFamily =
            digitalFont,

        modifier =
            modifier.rotate(
                rotation.toFloat()
            )
    )
}



// ============================================================
// CAPTURE
//
// ImageCapture entrega JPEG.
// Lo convertimos a Bitmap.
// Después aplicamos el LUT.
// Después agregamos fecha.
// Finalmente guardamos.
// ============================================================

private fun processAndTakePhoto(

    context: Context,

    imageCapture: ImageCapture,

    preset: VintagePreset,

    lut: Lut3D?,

    executor: ExecutorService,

    photoProcessingExecutor: ExecutorService,

    captureStartTime: Long

) {

    imageCapture.takePicture(

        executor,

        object :
            ImageCapture
            .OnImageCapturedCallback() {


            override fun onCaptureSuccess(

                image: ImageProxy
            ) {
                val elapsedMs =
                    (System.nanoTime() - captureStartTime) / 1_000_000

                ContextCompat
                    .getMainExecutor(context)
                    .execute {

                        Toast.makeText(
                            context,
                            "Captura: ${elapsedMs} ms",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                // ====================================
                // CAPTURA CONFIRMADA
                // ====================================

                try {

                    val vibrator =

                        if (
                            Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.S
                        ) {

                            val vibratorManager =
                                context.getSystemService(
                                    Context.VIBRATOR_MANAGER_SERVICE
                                ) as VibratorManager

                            vibratorManager.defaultVibrator

                        } else {

                            @Suppress("DEPRECATION")
                            context.getSystemService(
                                Context.VIBRATOR_SERVICE
                            ) as Vibrator
                        }


                    vibrator.vibrate(

                        VibrationEffect.createOneShot(

                            70L,

                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )

                } catch (e: Exception) {

                    e.printStackTrace()
                }


                // ====================================
                // COPIAR JPEG Y ROTACIÓN
                //
                // Esto se hace rápidamente para
                // poder liberar ImageProxy.
                // ====================================

                val buffer =

                    image.planes
                        .firstOrNull()
                        ?.buffer


                if (buffer == null) {

                    image.close()

                    return
                }


                buffer.rewind()


                val jpegBytes =

                    ByteArray(
                        buffer.remaining()
                    )


                buffer.get(
                    jpegBytes
                )


                val rotationDegrees =
                    image.imageInfo.rotationDegrees


                // ====================================
                // LIBERAR IMAGEPROXY INMEDIATAMENTE
                // ====================================

                image.close()


                // ====================================
                // PROCESAMIENTO PESADO
                //
                // Se ejecuta en otro hilo.
                // CameraX queda libre para la
                // siguiente captura.
                // ====================================

                photoProcessingExecutor.execute {

                    try {

                        var bitmap =
                            BitmapFactory.decodeByteArray(

                                jpegBytes,

                                0,

                                jpegBytes.size
                            )


                        if (bitmap == null) {

                            ContextCompat
                                .getMainExecutor(
                                    context
                                )
                                .execute {

                                    Toast.makeText(
                                        context,
                                        "No se pudo procesar la imagen",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                            return@execute
                        }


                        // ====================================
                        // ROTACIÓN
                        //
                        // MISMA LÓGICA QUE ANTES
                        // ====================================

                        if (
                            rotationDegrees != 0
                        ) {

                            val matrix =
                                Matrix()


                            matrix.postRotate(
                                rotationDegrees.toFloat()
                            )


                            bitmap =
                                Bitmap.createBitmap(

                                    bitmap,

                                    0,

                                    0,

                                    bitmap.width,

                                    bitmap.height,

                                    matrix,

                                    true
                                )
                        }


                        // ====================================
                        // LUT
                        // ====================================

                        val filtered =

                            if (
                                preset !=
                                VintagePreset.NORMAL &&
                                lut != null
                            ) {

                                lut.apply(
                                    bitmap
                                )

                            } else {

                                bitmap
                            }


                        // ====================================
                        // FECHA
                        // ====================================

                        val finalBitmap =
                            addDateToBitmap(

                                context,

                                filtered
                            )


                        // ====================================
                        // GUARDAR
                        // ====================================

                        saveBitmapToGallery(

                            context,

                            finalBitmap
                        )


                    } catch (e: Exception) {

                        e.printStackTrace()


                        ContextCompat
                            .getMainExecutor(
                                context
                            )
                            .execute {

                                Toast.makeText(
                                    context,
                                    "Error al procesar la foto",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                }
            }


            override fun onError(
                exception:
                ImageCaptureException
            ) {

                exception.printStackTrace()


                ContextCompat
                    .getMainExecutor(
                        context
                    )
                    .execute {

                        Toast.makeText(
                            context,
                            "Error al tomar la foto",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }
    )
}

// ============================================================
// IMAGE PROXY JPEG -> BITMAP
//
// ImageCapture utiliza JPEG como formato de salida.
// ============================================================

private fun imageProxyToBitmapJPEG(
    image: ImageProxy
): Bitmap? {

    val plane =
        image.planes.firstOrNull()
            ?: return null


    val buffer =
        plane.buffer


    buffer.rewind()


    val bytes =
        ByteArray(
            buffer.remaining()
        )


    buffer.get(
        bytes
    )


    var bitmap =
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size
        )
            ?: return null


    val rotation =
        image.imageInfo.rotationDegrees


    if (rotation != 0) {

        val matrix =
            Matrix()


        matrix.postRotate(
            rotation.toFloat()
        )


        bitmap =
            Bitmap.createBitmap(

                bitmap,

                0,
                0,

                bitmap.width,
                bitmap.height,

                matrix,

                true
            )
    }


    return bitmap
}


// ============================================================
// IMAGE PROXY RGBA -> BITMAP
//
// Utilizado para PREVISUALIZACIÓN.
// ============================================================

private fun imageProxyToBitmapRGBA(
    image: ImageProxy
): Bitmap? {

    val plane =
        image.planes.firstOrNull()
            ?: return null


    val buffer =
        plane.buffer


    buffer.rewind()


    val width =
        image.width

    val height =
        image.height


    val rowStride =
        plane.rowStride

    val pixelStride =
        plane.pixelStride


    val pixels =
        IntArray(
            width * height
        )


    for (y in 0 until height) {

        val rowStart =
            y * rowStride


        buffer.position(
            rowStart
        )


        for (x in 0 until width) {

            val r =
                buffer.get()
                    .toInt()
                    .and(
                        0xFF
                    )


            val g =
                buffer.get()
                    .toInt()
                    .and(
                        0xFF
                    )


            val b =
                buffer.get()
                    .toInt()
                    .and(
                        0xFF
                    )


            val a =
                buffer.get()
                    .toInt()
                    .and(
                        0xFF
                    )


            pixels[
                y * width + x
            ] =
                AndroidColor.argb(
                    a,
                    r,
                    g,
                    b
                )


            val extra =
                pixelStride - 4


            if (extra > 0) {

                buffer.position(
                    buffer.position() +
                            extra
                )
            }
        }
    }


    var bitmap =
        Bitmap.createBitmap(

            pixels,

            width,
            height,

            Bitmap.Config.ARGB_8888
        )


    val rotation =
        image.imageInfo.rotationDegrees


    if (rotation != 0) {

        val matrix =
            Matrix()


        matrix.postRotate(
            rotation.toFloat()
        )


        bitmap =
            Bitmap.createBitmap(

                bitmap,

                0,
                0,

                bitmap.width,
                bitmap.height,

                matrix,

                true
            )
    }


    return bitmap
}


// ============================================================
// DATE SOBRE LA FOTO
// ============================================================



private fun addDateToBitmap(
    context: Context,
    source: Bitmap
): Bitmap {

    val result =
        source.copy(
            Bitmap.Config.ARGB_8888,
            true
        )


    val canvas =
        Canvas(
            result
        )


    val paint =
        Paint().apply {

            color =
                AndroidColor.argb(
                    160,
                    250,
                    110,
                    15
                )


            textSize =
                source.width /
                        40f


            typeface =
                ResourcesCompat.getFont(
                    context,
                    R.font.dseg7_classic_regular
                )


            isAntiAlias =
                true
        }


    // =================================================
    // DATE GROUPS
    // =================================================

    val now =
        Date()

    val year =
        SimpleDateFormat(
            "''yy",
            Locale.US
        ).format(
            now
        )

    val month =
        SimpleDateFormat(
            "MM",
            Locale.US
        ).format(
            now
        )

    val day =
        SimpleDateFormat(
            "dd",
            Locale.US
        ).format(
            now
        )


    // =================================================
    // SEPARATION BETWEEN GROUPS
    // =================================================

    val groupSpacing =
        source.width * 0.015f


    // =================================================
    // MEASURE EACH GROUP
    // =================================================

    val yearWidth =
        paint.measureText(
            year
        )

    val monthWidth =
        paint.measureText(
            month
        )

    val dayWidth =
        paint.measureText(
            day
        )


    // =================================================
    // TOTAL WIDTH
    // =================================================

    val totalWidth =
        yearWidth +
                monthWidth +
                dayWidth +
                groupSpacing * 2


    // =================================================
    // POSITION
    // =================================================

    val xStart =
        source.width -
                totalWidth -
                source.width * 0.025f


    val y =
        source.height -
                source.height * 0.025f


    val yearX =
        xStart

    val monthX =
        yearX +
                yearWidth +
                groupSpacing

    val dayX =
        monthX +
                monthWidth +
                groupSpacing


    // =================================================
    // MAIN DATE
    // =================================================

    canvas.drawText(
        year,
        yearX,
        y,
        paint
    )

    canvas.drawText(
        month,
        monthX,
        y,
        paint
    )

    canvas.drawText(
        day,
        dayX,
        y,
        paint
    )


    return result
}

// ============================================================
// GUARDAR EN GALERÍA
// ============================================================

private fun saveBitmapToGallery(

    context: Context,

    bitmap: Bitmap
) {

    val contentValues =
        ContentValues().apply {

            put(

                MediaStore
                    .MediaColumns
                    .DISPLAY_NAME,

                "Vintage_${System.currentTimeMillis()}.jpg"
            )


            put(

                MediaStore
                    .MediaColumns
                    .MIME_TYPE,

                "image/jpeg"
            )


            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                put(

                    MediaStore
                        .Images
                        .Media
                        .RELATIVE_PATH,

                    "Pictures/VintageCamera"
                )
            }
        }


    val uri =
        context.contentResolver.insert(

            MediaStore
                .Images
                .Media
                .EXTERNAL_CONTENT_URI,

            contentValues
        )


    if (uri == null) {

        Toast.makeText(

            context,

            "No se pudo guardar la foto",

            Toast.LENGTH_SHORT
        ).show()

        return
    }


    try {

        context.contentResolver
            .openOutputStream(uri)
            ?.use { stream ->

                bitmap.compress(

                    Bitmap.CompressFormat.JPEG,

                    95,

                    stream
                )
            }


        ContextCompat
            .getMainExecutor(context)
            .execute {

                Toast.makeText(

                    context,

                    "¡Captura Vintage!",

                    Toast.LENGTH_SHORT
                ).show()
            }

    } catch (e: Exception) {

        e.printStackTrace()


        ContextCompat
            .getMainExecutor(context)
            .execute {

                Toast.makeText(

                    context,

                    "Error guardando la foto",

                    Toast.LENGTH_SHORT
                ).show()
            }
    }
}


// ============================================================
// FOCUS INDICATOR
// ============================================================

@Composable
fun FocusIndicator(
    point: Offset?
) {

    if (point == null) {
        return
    }


    val anim =
        remember {

            Animatable(
                0.6f
            )
        }


    LaunchedEffect(
        point
    ) {

        anim.snapTo(
            0.6f
        )


        anim.animateTo(
            1.3f,
            tween(250)
        )


        anim.animateTo(
            1.0f,
            tween(150)
        )
    }


    Box(

        Modifier
            .offset(

                with(
                    LocalDensity.current
                ) {

                    point.x.toDp()
                } - 30.dp,

                with(
                    LocalDensity.current
                ) {

                    point.y.toDp()
                } - 30.dp
            )

            .size(
                60.dp
            )

            .scale(
                anim.value
            )

            .border(

                2.dp,

                Color.White.copy(
                    alpha = 0.8f
                ),

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

                Color.Black.copy(
                    alpha = 0.5f
                ),

                RoundedCornerShape(
                    4.dp
                )
            )

            .padding(
                4.dp
            )
    ) {

        Text(

            text =
                "%.1fx".format(
                    zoom
                ),

            color =
                Color.White,

            fontSize =
                12.sp,

            fontWeight =
                FontWeight.Bold
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

    Canvas(
        modifier
    ) {

        val color =
            Color.White.copy(
                alpha = 0.3f
            )


        drawLine(

            color,

            Offset(
                size.width / 3,
                0f
            ),

            Offset(
                size.width / 3,
                size.height
            ),

            1.dp.toPx()
        )


        drawLine(

            color,

            Offset(
                2 * size.width / 3,
                0f
            ),

            Offset(
                2 * size.width / 3,
                size.height
            ),

            1.dp.toPx()
        )


        drawLine(

            color,

            Offset(
                0f,
                size.height / 3
            ),

            Offset(
                size.width,
                size.height / 3
            ),

            1.dp.toPx()
        )


        drawLine(

            color,

            Offset(
                0f,
                2 * size.height / 3
            ),

            Offset(
                size.width,
                2 * size.height / 3
            ),

            1.dp.toPx()
        )
    }
}
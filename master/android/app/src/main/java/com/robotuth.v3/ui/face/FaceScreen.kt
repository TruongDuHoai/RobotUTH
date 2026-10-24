package com.robotuth.v3.ui.face

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.doOnLayout
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.common.InputImage
import com.robotuth.v3.data.camera.CameraManager
import com.robotuth.v3.data.mlkit.FaceDetector
import com.robotuth.v3.ui.common.CameraPermissionHandler

/**
 * Stateful Face Screen - kết nối với ViewModel và quản lý camera lifecycle.
 * Đây là entry point cho feature này.
 */
@Composable
fun FaceScreen(
    viewModel: FaceViewModel,
    cameraManager: CameraManager,
    faceDetector: FaceDetector,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraStarted by remember { mutableStateOf(false) }
    var cameraBound by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var isAnalysisRunning by remember { mutableStateOf(false) }
    var previewViewSize by remember { mutableStateOf<android.util.Pair<Int, Int>?>(null) }
    val lastProcessTime = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val lastRecognizeTime = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    LaunchedEffect(Unit) {
        viewModel.startAttendanceSession()
    }

    // Handle camera lifecycle
    LaunchedEffect(lifecycleOwner.lifecycle) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            cameraManager.initialize()
            viewModel.onEvent(FaceUiEvent.OnCameraInitialized)
        }
    }

    // Start camera when permission is granted and camera is ready
    LaunchedEffect(cameraStarted, previewView) {
        if (cameraStarted && previewView != null) {
            cameraBound = false
            // Wait for camera provider to be ready, with 5-second timeout
            val timeoutMs = 5_000L
            val pollMs = 100L
            var elapsed = 0L
            while (!cameraManager.isReady()) {
                if (elapsed >= timeoutMs) {
                    viewModel.onEvent(FaceUiEvent.OnError("Camera initialization timed out"))
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(pollMs)
                elapsed += pollMs
            }
            cameraManager.startCamera(previewView!!.surfaceProvider) { error ->
                cameraBound = false
                viewModel.onEvent(FaceUiEvent.OnError("Camera failed to start: ${error.message}"))
            }
            cameraBound = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraManager.stopCamera()
            cameraManager.stopAnalysis()
            cameraManager.shutdown()
            cameraStarted = false
            cameraBound = false
            isAnalysisRunning = false
            viewModel.onEvent(FaceUiEvent.OnCameraStopped)
        }
    }

    // Manage analysis start/stop atomically when auto-detect toggles.
    // Keyed on isAutoDetectEnabled so this runs exactly once per toggle, not on every recomposition.
    LaunchedEffect(state.isAutoDetectEnabled, cameraBound, previewViewSize) {
        if (state.isAutoDetectEnabled && cameraBound && previewViewSize != null) {
            cameraManager.startAnalysis { imageProxy ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastProcessTime.get() < 150L) { // ~7 FPS for smooth bounding box
                        imageProxy.close()
                        return@startAnalysis
                    }
                    lastProcessTime.set(now)

                    // Check if we should also trigger recognition (1.5s cooldown, not busy)
                    val uiState = viewModel.uiState.value
                    val shouldRecognize = uiState.isAutoDetectEnabled &&
                        !uiState.isProcessing &&
                        uiState.attendanceState != AttendanceState.SUCCESS &&
                        uiState.attendanceState != AttendanceState.RECOGNIZING &&
                        (now - lastRecognizeTime.get() > 1_500L)

                    // Convert to Bitmap BEFORE ML Kit consumes the image (needed for recognition API)
                    val recognitionBitmap = if (shouldRecognize) {
                        lastRecognizeTime.set(now)
                        safeImageProxyToBitmap(imageProxy)
                    } else null

                    @androidx.camera.core.ExperimentalGetImage
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                        viewModel.onEvent(FaceUiEvent.OnNoFaceDetected)
                        return@startAnalysis
                    }

                    val imageWidth = imageProxy.width
                    val imageHeight = imageProxy.height
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

                    faceDetector.detectAsync(inputImage) { result ->
                        try {
                            result.onSuccess { detectionResult ->
                                when (detectionResult.faces.size) {
                                    0 -> viewModel.onEvent(FaceUiEvent.OnNoFaceDetected)
                                    1 -> {
                                        val face = detectionResult.faces.first()
                                        viewModel.onEvent(FaceUiEvent.OnFaceDetected(
                                            bounds = face.boundingBox,
                                            imageWidth = imageWidth,
                                            imageHeight = imageHeight,
                                            rotationDegrees = rotationDegrees
                                        ))
                                        // Trigger recognition if bitmap was captured
                                        if (recognitionBitmap != null) {
                                            viewModel.onEvent(FaceUiEvent.OnAutoDetectFaceFound(recognitionBitmap))
                                        } else if (shouldRecognize) {
                                            viewModel.onEvent(FaceUiEvent.OnError("Không thể đọc ảnh camera"))
                                        }
                                    }
                                    else -> viewModel.onEvent(FaceUiEvent.OnMultipleFaces)
                                }
                            }.onFailure { error ->
                                viewModel.onEvent(FaceUiEvent.OnError("Face detection failed: ${error.message}"))
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }
                } catch (e: Exception) {
                    imageProxy.close()
                    viewModel.onEvent(FaceUiEvent.OnError("Face detection failed: ${e.message}"))
                }
            }
            isAnalysisRunning = true
        } else {
            cameraManager.stopAnalysis()
            isAnalysisRunning = false
        }
    }

    CameraPermissionHandler(
        permission = Manifest.permission.CAMERA,
        onPermissionGranted = {
            cameraStarted = true
            viewModel.onEvent(FaceUiEvent.OnCameraStarted)
        }
    )

    FaceContent(
        state = state,
        onEvent = { event ->
            when (event) {
                is FaceUiEvent.OnDetectClick -> {
                    captureAndProcess(cameraManager) { bitmap -> runDetect(bitmap, viewModel, faceDetector) }
                }
                is FaceUiEvent.OnEnrollClick -> {
                    captureAndProcess(cameraManager) { bitmap -> runEnroll(bitmap, state.enrollName, viewModel, faceDetector) }
                }
                is FaceUiEvent.OnRecognizeClick -> {
                    captureAndProcess(cameraManager) { bitmap -> runRecognize(bitmap, viewModel, faceDetector) }
                }
                is FaceUiEvent.OnSwitchCamera -> { /* button removed, no-op */ }
                else -> viewModel.onEvent(event)
            }
        },
        cameraPreview = { cameraModifier ->
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // Track size when layout completes
                        doOnLayout { view ->
                            val w = view.width
                            val h = view.height
                            if (w > 0 && h > 0) {
                                previewViewSize = android.util.Pair(w, h)
                            }
                        }
                    }.also { previewView = it }
                },
                modifier = cameraModifier,
                update = { pv ->
                    // Update size on each recomposition if changed
                    val w = pv.width
                    val h = pv.height
                    if (w > 0 && h > 0 && (previewViewSize?.first != w || previewViewSize?.second != h)) {
                        previewViewSize = android.util.Pair(w, h)
                    }
                }
            )
        },
        previewViewSize = previewViewSize,
        onBack = onBack,
        modifier = modifier
    )
}

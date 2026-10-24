package com.robotuth.v3.data.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManagerImpl(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : CameraManager {

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var _surfaceProvider: Preview.SurfaceProvider? = null

    private var _currentSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    override val currentSelector: CameraSelector
        get() = _currentSelector

    override val isInitialized: Boolean
        get() = cameraProvider != null

    override fun initialize() {
        if (cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(context))
    }

    override fun isReady(): Boolean {
        return cameraProvider != null
    }

    override fun startCamera(surfaceProvider: Preview.SurfaceProvider, onError: ((Exception) -> Unit)?) {
        _surfaceProvider = surfaceProvider
        val provider = cameraProvider ?: return

        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                _currentSelector,
                preview,
                imageCapture
            )
        } catch (e: IllegalStateException) {
            android.util.Log.e("CameraManager", "Failed to bind camera lifecycle", e)
            onError?.invoke(e)
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("CameraManager", "Invalid camera configuration", e)
            onError?.invoke(e)
        } catch (e: Exception) {
            android.util.Log.e("CameraManager", "Unexpected camera error", e)
            onError?.invoke(e)
        }
    }

    override fun stopCamera() {
        cameraProvider?.unbindAll()
        camera = null
    }

    override fun switchCamera(): CameraSelector {
        _currentSelector = if (_currentSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        _surfaceProvider?.let { startCamera(it) }
        return _currentSelector
    }

    override fun captureImage(onCapture: (Bitmap) -> Unit) {
        val capture = imageCapture ?: return

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    bitmap?.let(onCapture) ?: android.util.Log.w("CameraManager", "Failed to convert image to bitmap")
                }

                override fun onError(exception: ImageCaptureException) {
                    android.util.Log.e("CameraManager", "Image capture failed", exception)
                    // Note: ImageCapture automatically closes the image on error
                }
            }
        )
    }

    override fun startAnalysis(onFrame: (ImageProxy) -> Unit) {
        val provider = cameraProvider ?: return

        // Unbind the previous instance before replacing the reference. Binding a
        // newly-created instance and calling unbind(newInstance) left the old
        // analyzer attached, eventually exceeding the device surface limit.
        imageAnalysis?.let { oldAnalysis ->
            oldAnalysis.clearAnalyzer()
            provider.unbind(oldAnalysis)
        }

        val newAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    onFrame(imageProxy)
                }
            }
        imageAnalysis = newAnalysis

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                _currentSelector,
                preview,
                imageCapture,
                newAnalysis
            )
        } catch (e: Exception) {
            newAnalysis.clearAnalyzer()
            provider.unbind(newAnalysis)
            if (imageAnalysis === newAnalysis) imageAnalysis = null
            android.util.Log.e("CameraManager", "Failed to bind image analysis", e)
        }
    }

    override fun stopAnalysis() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis?.let { cameraProvider?.unbind(it) }
        imageAnalysis = null
    }

    override fun shutdown() {
        stopCamera()
        cameraExecutor.shutdown()
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            image.toBitmap()
        } catch (e: Exception) {
            null
        }
    }
}

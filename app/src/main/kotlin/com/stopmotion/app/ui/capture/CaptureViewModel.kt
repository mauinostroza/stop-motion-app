package com.stopmotion.app.ui.capture

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stopmotion.app.data.ProjectRepository
import com.stopmotion.app.util.BitmapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Holds the live state of the capture screen and owns the CameraX
 * [ImageCapture] use case. Also keeps the last captured bitmap so that
 * onion-skinning can overlay it on the live preview.
 */
class CaptureViewModel : ViewModel() {

    private var repo: ProjectRepository? = null
    private var captureDir: File? = null

    /** Backing image for onion skin overlay (most recent captured frame). */
    private val _onionSkinBitmap = MutableStateFlow<Bitmap?>(null)
    val onionSkinBitmap: StateFlow<Bitmap?> = _onionSkinBitmap.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Back camera by default; can be flipped at runtime. */
    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    private val _gridEnabled = MutableStateFlow(true)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled.asStateFlow()

    private val _onionSkinEnabled = MutableStateFlow(true)
    val onionSkinEnabled: StateFlow<Boolean> = _onionSkinEnabled.asStateFlow()

    /** The CameraX ImageCapture use case — configured by [bindCamera]. */
    var imageCapture: ImageCapture? = null
        private set

    fun init(repo: ProjectRepository) {
        this.repo = repo
        this.captureDir = repo.capturesDir
        _frameCount.value = repo.frameCount()
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
    }

    fun toggleOnionSkin() {
        _onionSkinEnabled.value = !_onionSkinEnabled.value
    }

    fun flipCamera() {
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    }

    /**
     * Binds the camera use cases (preview + image capture) to the given
     * lifecycle owner. Returns the [PreviewView] surface provider that
     * the caller should set on its [PreviewView].
     *
     * Re-binds every time [lensFacing] changes (handled in the Composable).
     */
    suspend fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
    ): Boolean = withContext(Dispatchers.Main) {
        val cameraProvider = awaitCameraProvider(context) ?: return@withContext false

        // Unbind any prior use cases before re-binding.
        cameraProvider.unbindAll()

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(surfaceProvider) }

        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(
                ContextCompat.getSystemService(context, WindowManager::class.java)
                    ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            )
        val imageCapture = imageCaptureBuilder.build()
        this@CaptureViewModel.imageCapture = imageCapture

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(_lensFacing.value)
            .build()

        try {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
            _lastError.value = e.localizedMessage ?: "Camera binding failed"
            false
        }
    }

    /**
     * Snap a single photo, save it to the capture directory, register it
     * with the project repository, and update the onion skin + frame count.
     */
    fun takePhoto(context: Context) {
        val repo = repo ?: return
        val captureDir = captureDir ?: return
        val imageCapture = imageCapture ?: return
        if (_capturing.value) return

        _capturing.value = true
        val file = File(captureDir, "frame_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        val executor: Executor = ContextCompat.getMainExecutor(context)
        imageCapture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    repo.addCapturedFrame(file)
                    _frameCount.value = repo.frameCount()
                    // Load last bitmap for onion skin (downsampled).
                    viewModelScope.launch(Dispatchers.IO) {
                        val bmp = BitmapLoader.loadDownsampled(
                            context = context,
                            filePath = file.absolutePath,
                            uri = null,
                            targetMaxDim = 720,
                        )
                        _onionSkinBitmap.value = bmp
                    }
                    _capturing.value = false
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    _lastError.value = exc.localizedMessage ?: "Capture failed"
                    _capturing.value = false
                }
            },
        )
    }

    private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider? =
        suspendCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CaptureViewModel"
    }
}

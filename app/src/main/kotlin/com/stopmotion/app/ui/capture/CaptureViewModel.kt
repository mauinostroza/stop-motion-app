package com.stopmotion.app.ui.capture

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.os.Build
import android.provider.MediaStore
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
import java.io.IOException
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
    private val shutterSound = MediaActionSound()

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

    private val _autoCaptureEnabled = MutableStateFlow(false)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()

    private val _autoCaptureRunning = MutableStateFlow(false)
    val autoCaptureRunning: StateFlow<Boolean> = _autoCaptureRunning.asStateFlow()

    private val _intervalSeconds = MutableStateFlow(ProjectRepository.DEFAULT_INTERVAL_SECONDS)
    val intervalSeconds: StateFlow<Int> = _intervalSeconds.asStateFlow()

    /** The CameraX ImageCapture use case — configured by [bindCamera]. */
    var imageCapture: ImageCapture? = null
        private set

    private var initialized = false

    fun init(repo: ProjectRepository) {
        this.repo = repo
        this.captureDir = repo.capturesDir
        _frameCount.value = repo.frameCount()
        if (!initialized) {
            _intervalSeconds.value = repo.snapshot().intervalSeconds
            shutterSound.load(MediaActionSound.SHUTTER_CLICK)
            initialized = true
        }
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
    }

    fun toggleOnionSkin() {
        _onionSkinEnabled.value = !_onionSkinEnabled.value
    }

    fun toggleAutoCapture() {
        val next = !_autoCaptureEnabled.value
        _autoCaptureEnabled.value = next
        if (next) {
            _lastError.value = null
        } else {
            _autoCaptureRunning.value = false
            repo?.setIntervalSeconds(_intervalSeconds.value)
        }
    }

    fun toggleAutoCaptureRunning() {
        if (!_autoCaptureEnabled.value) return
        _autoCaptureRunning.value = !_autoCaptureRunning.value
    }

    fun stopAutoCapture() {
        _autoCaptureRunning.value = false
        _autoCaptureEnabled.value = false
    }

    fun setIntervalSeconds(seconds: Int) {
        _intervalSeconds.value = seconds.coerceIn(
            ProjectRepository.MIN_INTERVAL_SECONDS,
            ProjectRepository.MAX_INTERVAL_SECONDS,
        )
    }

    fun consumeError() {
        _lastError.value = null
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
            stopAutoCapture()
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
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        imageCapture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    repo.addCapturedFrame(file)
                    _frameCount.value = repo.frameCount()
                    // Load last bitmap for onion skin (downsampled) and copy to the gallery.
                    viewModelScope.launch(Dispatchers.IO) {
                        val bmp = BitmapLoader.loadDownsampled(
                            context = context,
                            filePath = file.absolutePath,
                            uri = null,
                            targetMaxDim = 720,
                        )
                        _onionSkinBitmap.value = bmp
                        saveToGallery(context, file)
                    }
                    _capturing.value = false
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    _lastError.value = exc.localizedMessage ?: "Capture failed"
                    _capturing.value = false
                    stopAutoCapture()
                }
            },
        )
    }

    /**
     * Copies a captured frame into the system MediaStore ("Pictures/StopMotion")
     * so it also shows up in the device's gallery app. The private copy in
     * [ProjectRepository.capturesDir] remains the source of truth for assembling
     * the stop motion video.
     */
    private fun saveToGallery(context: Context, file: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StopMotion")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val item = resolver.insert(collection, values) ?: return
        try {
            file.inputStream().use { input ->
                resolver.openOutputStream(item)?.use { output -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalize = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(item, finalize, null, null)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save photo to gallery", e)
        }
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

    override fun onCleared() {
        super.onCleared()
        shutterSound.release()
    }

    companion object {
        private const val TAG = "CaptureViewModel"
    }
}

package com.stopmotion.app.ui.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stopmotion.app.data.ExportResolution
import com.stopmotion.app.data.ProjectRepository
import com.stopmotion.app.encoder.BitmapToVideoEncoder
import com.stopmotion.app.util.BitmapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * State and orchestration for the export screen.
 *
 * The export flow is:
 *  1. User picks resolution + FPS from the UI.
 *  2. On [startExport], the ViewModel launches a background coroutine that
 *     loads each frame bitmap, encodes it via [BitmapToVideoEncoder], and
 *     writes the resulting MP4 to [ProjectRepository.exportsDir].
 *  3. On success, exposes the resulting [File] and a shareable [Uri].
 *
 * The encoder receives the current FPS as the per-frame duration so the
 * exported file plays back at the desired speed regardless of capture rate.
 */
class ExportViewModel : ViewModel() {

    private var repo: ProjectRepository? = null

    private val _resolution = MutableStateFlow(ExportResolution.P720)
    val resolution: StateFlow<ExportResolution> = _resolution.asStateFlow()

    private val _fps = MutableStateFlow(12)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _status = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val status: StateFlow<ExportStatus> = _status.asStateFlow()

    private val _outputFile = MutableStateFlow<File?>(null)
    val outputFile: StateFlow<File?> = _outputFile.asStateFlow()

    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    fun init(repo: ProjectRepository) {
        this.repo = repo
        val snap = repo.snapshot()
        _resolution.value = snap.resolution
        _fps.value = snap.frameRateFps
        _frameCount.value = snap.frames.size
    }

    fun setResolution(resolution: ExportResolution) {
        repo?.setResolution(resolution)
        _resolution.value = resolution
    }

    fun setFps(fps: Int) {
        val clamped = fps.coerceIn(1, 30)
        repo?.setFps(clamped)
        _fps.value = clamped
    }

    /**
     * Starts the encoding coroutine. Idempotent: ignores subsequent calls
     * while [ExportStatus.Running] is active.
     */
    fun startExport(context: Context) {
        if (_status.value is ExportStatus.Running) return
        val repo = repo ?: return

        viewModelScope.launch {
            _status.value = ExportStatus.Running
            _progress.value = 0f
            _outputFile.value = null
            _shareUri.value = null

            try {
                val (file, uri) = withContext(Dispatchers.IO) {
                    encodeToMp4(context, repo)
                }
                _outputFile.value = file
                _shareUri.value = uri
                _status.value = ExportStatus.Done(file, uri)
            } catch (t: Throwable) {
                Log.e(TAG, "Export failed", t)
                _status.value = ExportStatus.Error(t.localizedMessage ?: "Unknown error")
            }
        }
    }

    /**
     * Returns an [Intent.ACTION_SEND] configured for sharing the
     * exported MP4. Returns null if no file is available yet.
     */
    fun buildShareIntent(context: Context): Intent? {
        val uri = _shareUri.value ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private suspend fun encodeToMp4(
        context: Context,
        repo: ProjectRepository,
    ): Pair<File, Uri> {
        val snap = repo.snapshot()
        val frames = snap.frames
        require(frames.isNotEmpty()) { "No frames to encode" }

        val resolution = _resolution.value
        val fps = _fps.value
        val outputDir = repo.exportsDir
        val outputFile = File(outputDir, "stopmotion_${System.currentTimeMillis()}.mp4")

        // Build the encoder and feed it frame by frame.
        val encoder = BitmapToVideoEncoder(
            outputPath = outputFile.absolutePath,
            width = resolution.width,
            height = resolution.height,
            bitRate = resolution.bitrate,
            frameRate = fps,
            onProgress = { p -> _progress.value = p },
        )

        var decodedFrames = 0
        try {
            encoder.setTotalFrames(frames.size)
            // Work in microseconds instead of integer milliseconds. This keeps
            // 30 fps at 33,333 us instead of the visibly faster 33 ms.
            val defaultFrameDurationUs = 1_000_000L / fps

            for (frame in frames) {
                // Note: we update progress before encoding so users see the
                // "currently encoding frame N" state immediately.
                val bitmap = BitmapLoader.loadDownsampled(
                    context = context,
                    filePath = frame.filePath,
                    uri = frame.sourceUri,
                    targetMaxDim = maxOf(resolution.width, resolution.height),
                )
                if (bitmap != null) {
                    try {
                        val durationUs = frame.durationMs?.times(1_000L)
                            ?: defaultFrameDurationUs
                        encoder.encodeFrame(bitmap, durationUs)
                        decodedFrames++
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }

            check(decodedFrames > 0) { "No se pudo leer ninguna imagen del proyecto" }
            encoder.finish()
        } catch (t: Throwable) {
            try { encoder.finish() } catch (_: Throwable) {}
            outputFile.delete()
            throw t
        }

        // Build a shareable content URI via FileProvider.
        val uri = repo.videoShareUri(outputFile)
        return outputFile to uri
    }

    companion object {
        private const val TAG = "ExportViewModel"
    }
}

/**
 * One-shot state of the export pipeline.
 */
sealed class ExportStatus {
    /** Idle / not started. */
    object Idle : ExportStatus()

    /** Encoding in progress; check [ExportViewModel.progress] for value. */
    object Running : ExportStatus()

    /** Encoding finished successfully — file is ready for sharing / saving. */
    data class Done(val file: File, val uri: Uri) : ExportStatus()

    /** Encoding failed — [message] describes the cause. */
    data class Error(val message: String) : ExportStatus()
}

package com.stopmotion.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central repository for managing the in-memory [Project] state and
 * the on-disk capture / export directories.
 *
 * The repository is intentionally kept as a single, mutable in-memory store
 * for simplicity. For a real production app you would swap this with a
 * Room/SQLite or DataStore-backed implementation.
 *
 * The repository exposes immutable snapshots via [snapshot] for observation
 * by ViewModels and Compose state holders.
 *
 * @property context Application context used to resolve filesDir.
 */
class ProjectRepository(private val context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Directory where captured photos are stored. Created lazily. */
    val capturesDir: File by lazy {
        File(context.filesDir, "captures").apply { if (!exists()) mkdirs() }
    }

    /** Directory where final exported MP4 files are stored. */
    val exportsDir: File by lazy {
        File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }
    }

    /**
     * The single in-memory project. Subscreens observe it via [snapshot].
     */
    private val _project: Project = loadProject()

    /** Synchronized snapshot accessor. */
    @Synchronized
    fun snapshot(): Project = _project.copy(
        frames = _project.frames.toList().toMutableList(),
    )

    @Synchronized
    fun setFps(fps: Int) {
        require(fps in 1..30) { "FPS must be in 1..30, was $fps" }
        _project.frameRateFps = fps
        saveProject()
    }

    @Synchronized
    fun setResolution(resolution: ExportResolution) {
        _project.resolution = resolution
        saveProject()
    }

    @Synchronized
    fun setIntervalSeconds(seconds: Int) {
        require(seconds in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) { "Interval must be in $MIN_INTERVAL_SECONDS..$MAX_INTERVAL_SECONDS, was $seconds" }
        _project.intervalSeconds = seconds
        saveProject()
    }

    /**
     * Creates a new capture file inside [capturesDir]. The filename is
     * timestamped to ensure chronological order during sorting.
     */
    fun newCaptureFile(): File =
        File(capturesDir, "frame_${System.currentTimeMillis()}.jpg")

    /**
     * Adds a frame captured via CameraX (file-backed).
     */
    @Synchronized
    fun addCapturedFrame(file: File) {
        _project.frames.add(
            Frame(
                id = UUID.randomUUID().toString(),
                sourceUri = null,
                filePath = file.absolutePath,
                order = _project.frames.size,
                capturedAt = System.currentTimeMillis(),
            )
        )
        saveProject()
    }

    /**
     * Adds multiple frames picked from the system Photo Picker.
     * Copies selected images into app-private storage so later exports do not
     * depend on a temporary Photo Picker URI permission.
     */
    @Synchronized
    fun addPickedFrames(uris: List<Uri>) {
        uris.forEach { uri ->
            // Copy into app-private storage so the project remains usable
            // after the Photo Picker's temporary URI grant expires.
            val localFile = copyPickedUri(uri)
            _project.frames.add(Frame(
                id = UUID.randomUUID().toString(),
                sourceUri = if (localFile == null) uri else null,
                filePath = localFile?.absolutePath,
                order = _project.frames.size,
                capturedAt = System.currentTimeMillis(),
            ))
        }
        saveProject()
    }

    @Synchronized
    fun removeFrame(id: String) {
        val removed = _project.frames.filter { it.id == id }
        _project.frames.removeAll { it.id == id }
        removed.mapNotNull { it.filePath }
            .map(::File)
            .filter { it.parentFile == capturesDir }
            .forEach { it.delete() }
        // Re-index order
        _project.frames.forEachIndexed { idx, f -> f.order = idx }
        saveProject()
    }

    @Synchronized
    fun clearAll() {
        _project.frames.clear()
        // Also remove captured files from disk
        capturesDir.listFiles()?.forEach { it.delete() }
        saveProject()
    }

    @Synchronized
    fun moveFrame(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in _project.frames.indices) return
        if (toIndex !in _project.frames.indices) return
        val moved = _project.frames.removeAt(fromIndex)
        _project.frames.add(toIndex, moved)
        _project.frames.forEachIndexed { idx, f -> f.order = idx }
        saveProject()
    }

    @Synchronized
    fun frameCount(): Int = _project.frames.size

    /**
     * Returns a content [Uri] for the given exported video [File] suitable
     * for sharing via intents (uses FileProvider).
     */
    fun videoShareUri(file: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

    private fun copyPickedUri(uri: Uri): File? {
        val extension = when (context.contentResolver.getType(uri)?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
        val target = File(capturesDir, "import_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target
        } catch (_: IOException) {
            target.delete()
            null
        } catch (_: SecurityException) {
            target.delete()
            null
        }
    }

    private fun loadProject(): Project {
        val frames = mutableListOf<Frame>()
        val encoded = preferences.getString(KEY_FRAMES, null)
        if (encoded != null) {
            runCatching {
                val array = JSONArray(encoded)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val path = item.optString("filePath").takeIf { it.isNotBlank() }
                    val uri = item.optString("sourceUri").takeIf { it.isNotBlank() }?.let(Uri::parse)
                    if (path != null && !File(path).exists() && uri == null) continue
                    frames += Frame(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        sourceUri = uri,
                        filePath = path,
                        order = frames.size,
                        capturedAt = item.optLong("capturedAt", System.currentTimeMillis()),
                        durationMs = if (item.has("durationMs")) item.optLong("durationMs") else null,
                    )
                }
            }
        }
        val fps = preferences.getInt(KEY_FPS, 12).coerceIn(1, 30)
        val resolution = preferences.getString(KEY_RESOLUTION, ExportResolution.P720.name)
            ?.let { runCatching { ExportResolution.valueOf(it) }.getOrNull() }
            ?: ExportResolution.P720
        val intervalSeconds = preferences.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
            .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        return Project(
            id = preferences.getString(KEY_PROJECT_ID, UUID.randomUUID().toString())!!,
            frames = frames,
            frameRateFps = fps,
            resolution = resolution,
            createdAt = preferences.getLong(KEY_CREATED_AT, System.currentTimeMillis()),
            intervalSeconds = intervalSeconds,
        )
    }

    private fun saveProject() {
        val array = JSONArray()
        _project.frames.forEach { frame ->
            array.put(JSONObject().apply {
                put("id", frame.id)
                put("sourceUri", frame.sourceUri?.toString())
                put("filePath", frame.filePath)
                put("capturedAt", frame.capturedAt)
                frame.durationMs?.let { put("durationMs", it) }
            })
        }
        preferences.edit()
            .putString(KEY_PROJECT_ID, _project.id)
            .putLong(KEY_CREATED_AT, _project.createdAt)
            .putInt(KEY_FPS, _project.frameRateFps)
            .putString(KEY_RESOLUTION, _project.resolution.name)
            .putString(KEY_FRAMES, array.toString())
            .putInt(KEY_INTERVAL_SECONDS, _project.intervalSeconds)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "stop_motion_project"
        private const val KEY_PROJECT_ID = "project_id"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_FPS = "fps"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_FRAMES = "frames"
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
        const val MIN_INTERVAL_SECONDS = 1
        const val MAX_INTERVAL_SECONDS = 60
        const val DEFAULT_INTERVAL_SECONDS = 3
    }
}

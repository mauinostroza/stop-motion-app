package com.stopmotion.app.ui.frames

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stopmotion.app.data.ExportResolution
import com.stopmotion.app.data.Frame
import com.stopmotion.app.data.ProjectRepository
import com.stopmotion.app.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the list of frames for the current project, plus the
 * currently-selected resolution & FPS. The frames list is exposed as a
 * snapshot copy so consumers can compose freely without mutation issues.
 *
 * Frames can be:
 *  - added from the capture screen (file-backed), or
 *  - added from the Photo Picker (URI-backed).
 *
 * Reordering, deletion and FPS / resolution selection are performed via
 * the repository so state persists across screens.
 */
class FramesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: ProjectRepository = ServiceLocator.provideRepository(app)

    private val _frames = MutableStateFlow<List<Frame>>(emptyList())
    val frames: StateFlow<List<Frame>> = _frames.asStateFlow()

    private val _fps = MutableStateFlow(repo.snapshot().frameRateFps)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _resolution = MutableStateFlow(repo.snapshot().resolution)
    val resolution: StateFlow<ExportResolution> = _resolution.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _frames.value = repo.snapshot().frames
        _fps.value = repo.snapshot().frameRateFps
        _resolution.value = repo.snapshot().resolution
    }

    fun addPickedUris(uris: List<Uri>) {
        // Copying selected images can involve several megabytes. Keep it off
        // the UI thread so the picker returns to a responsive timeline.
        viewModelScope.launch(Dispatchers.IO) {
            repo.addPickedFrames(uris)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun deleteFrame(id: String) {
        repo.removeFrame(id)
        refresh()
    }

    fun clearAll() {
        repo.clearAll()
        refresh()
    }

    fun moveFrame(fromIndex: Int, toIndex: Int) {
        repo.moveFrame(fromIndex, toIndex)
        refresh()
    }

    fun setFps(fps: Int) {
        val clamped = fps.coerceIn(1, 30)
        repo.setFps(clamped)
        _fps.value = clamped
    }

    fun setResolution(resolution: ExportResolution) {
        repo.setResolution(resolution)
        _resolution.value = resolution
    }

    fun frameCount(): Int = repo.frameCount()
}

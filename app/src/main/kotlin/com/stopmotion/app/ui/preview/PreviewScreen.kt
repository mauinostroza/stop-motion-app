package com.stopmotion.app.ui.preview

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stopmotion.app.R
import com.stopmotion.app.data.Frame
import com.stopmotion.app.data.ProjectRepository
import com.stopmotion.app.data.ServiceLocator
import com.stopmotion.app.util.BitmapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreviewViewModel : ViewModel() {

    private var repo: ProjectRepository? = null

    private val _frames = MutableStateFlow<List<Frame>>(emptyList())
    val frames: StateFlow<List<Frame>> = _frames.asStateFlow()

    private val _fps = MutableStateFlow(12)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _bitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val bitmaps: StateFlow<List<Bitmap>> = _bitmaps.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun init(repo: ProjectRepository) {
        this.repo = repo
        refresh()
    }

    fun refresh() {
        val snap = repo?.snapshot() ?: return
        _frames.value = snap.frames
        _fps.value = snap.frameRateFps
    }

    /**
     * Pre-loads all frame bitmaps off the main thread so the preview loop
     * can iterate without per-frame IO. Uses a small target dim (~720px)
     * to keep memory footprint low.
     */
    fun preload(context: android.content.Context) {
        val snap = repo?.snapshot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val bitmaps = snap.frames.mapNotNull { frame ->
                BitmapLoader.loadDownsampled(
                    context = context,
                    filePath = frame.filePath,
                    uri = frame.sourceUri,
                    targetMaxDim = 720,
                )
            }
            _bitmaps.value = bitmaps
            _loaded.value = true
        }
    }

    fun setFps(fps: Int) {
        val clamped = fps.coerceIn(1, 30)
        repo?.setFps(clamped)
        _fps.value = clamped
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onBack: () -> Unit,
    onExport: () -> Unit,
    vm: PreviewViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.init(ServiceLocator.provideRepository(context))
        vm.preload(context)
    }

    val bitmaps by vm.bitmaps.collectAsState()
    val fps by vm.fps.collectAsState()
    val loaded by vm.loaded.collectAsState()

    var playing by remember { mutableStateOf(true) }
    var currentIndex by remember { mutableStateOf(0) }

    // Animation loop: advances currentIndex at the selected FPS
    LaunchedEffect(fps, playing, bitmaps.size) {
        if (bitmaps.isEmpty()) return@LaunchedEffect
        while (playing) {
            val frameDurationMs = (1000f / fps).toLong().coerceAtLeast(16L)
            kotlinx.coroutines.delay(frameDurationMs)
            currentIndex = (currentIndex + 1) % bitmaps.size
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.preview_fps_label, fps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        value = fps.toFloat(),
                        onValueChange = { vm.setFps(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    Text("$fps", style = MaterialTheme.typography.bodyMedium)
                }

                ExtendedFloatingActionButton(
                    onClick = onExport,
                    icon = { Icon(Icons.Default.MovieCreation, null) },
                    text = { Text(stringResource(R.string.frames_export)) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (!loaded || bitmaps.isEmpty()) {
                Text(
                    text = "Cargando…",
                    color = Color.White,
                )
            } else {
                val current = bitmaps.getOrNull(currentIndex.coerceIn(0, bitmaps.lastIndex))
                if (current != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val srcW = current.width.toFloat()
                        val srcH = current.height.toFloat()
                        val dstW = size.width
                        val dstH = size.height
                        val scale = minOf(dstW / srcW, dstH / srcH)
                        val drawW = srcW * scale
                        val drawH = srcH * scale
                        val left = (dstW - drawW) / 2f
                        val top = (dstH - drawH) / 2f
                        drawImage(
                            image = current.asImageBitmap(),
                            dstOffset = IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                        )
                    }
                }
            }

            // Play / pause button (top right floating)
            IconButton(
                onClick = { playing = !playing },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) stringResource(R.string.preview_pause)
                    else stringResource(R.string.preview_play),
                    tint = Color.White,
                )
            }
        }
    }
}

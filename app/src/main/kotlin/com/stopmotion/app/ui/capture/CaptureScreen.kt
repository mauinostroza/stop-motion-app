package com.stopmotion.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stopmotion.app.R
import com.stopmotion.app.data.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Capture screen — combines a CameraX [PreviewView], an onion-skin overlay
 * rendered via Compose [Canvas], and a 3x3 grid overlay toggleable via FAB.
 *
 * The screen assumes the user has granted the CAMERA permission; if not,
 * a permission rationale card is displayed with a button to request it.
 */
@Composable
fun CaptureScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    vm: CaptureViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Initialize the viewmodel with the project repository.
    LaunchedEffect(Unit) {
        vm.init(ServiceLocator.provideRepository(context))
    }

    var permissionGranted by remember {
        mutableStateOf(vm.hasCameraPermission(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Camera preview state
    val onionBitmap by vm.onionSkinBitmap.collectAsState()
    val frameCount by vm.frameCount.collectAsState()
    val capturing by vm.capturing.collectAsState()
    val onionEnabled by vm.onionSkinEnabled.collectAsState()
    val gridEnabled by vm.gridEnabled.collectAsState()
    val lensFacing by vm.lensFacing.collectAsState()
    val autoCaptureEnabled by vm.autoCaptureEnabled.collectAsState()
    val intervalSeconds by vm.intervalSeconds.collectAsState()
    val lastError by vm.lastError.collectAsState()

    val previewView = remember { PreviewView(context) }

    var remainingMs by remember { mutableLongStateOf(0L) }

    // Keep the screen on while the intervalometer is running.
    val activity = context as? android.app.Activity
    DisposableEffect(autoCaptureEnabled) {
        if (autoCaptureEnabled) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            !permissionGranted -> PermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
            else -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        previewView.apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    },
                )

                // Re-bind the camera every time lensFacing changes
                LaunchedEffect(lensFacing) {
                    vm.bindCamera(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        surfaceProvider = previewView.surfaceProvider,
                    )
                }

                // Intervalometer loop: fires takePhoto() every intervalSeconds while
                // autoCaptureEnabled is true. Cancelled automatically if this branch
                // stops being composed (e.g. camera permission lost).
                LaunchedEffect(autoCaptureEnabled, intervalSeconds) {
                    if (!autoCaptureEnabled) {
                        remainingMs = 0L
                        return@LaunchedEffect
                    }
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        val totalMs = intervalSeconds * 1000L
                        while (isActive) {
                            val deadline = android.os.SystemClock.elapsedRealtime() + totalMs
                            while (true) {
                                val left = deadline - android.os.SystemClock.elapsedRealtime()
                                if (left <= 0L) break
                                remainingMs = left
                                delay(minOf(left, 50L))
                            }
                            remainingMs = 0L

                            if (!vm.hasCameraPermission(context) || vm.imageCapture == null) {
                                vm.stopAutoCapture()
                                break
                            }

                            vm.takePhoto(context)
                            vm.capturing.first { !it }
                        }
                    }
                }

                // Onion skin overlay
                if (onionEnabled && onionBitmap != null) {
                    ImageWithAlpha(
                        bitmap = onionBitmap!!,
                        alpha = 0.35f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Grid overlay
                if (gridEnabled) {
                    GridOverlay(modifier = Modifier.fillMaxSize())
                }

                // Top controls (grid toggle, onion toggle, flip camera, auto capture toggle)
                CaptureTopControls(
                    gridEnabled = gridEnabled,
                    onionEnabled = onionEnabled,
                    autoEnabled = autoCaptureEnabled,
                    onToggleGrid = vm::toggleGrid,
                    onToggleOnion = vm::toggleOnionSkin,
                    onFlipCamera = vm::flipCamera,
                    onToggleAuto = vm::toggleAutoCapture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                )

                // Bottom: interval selector + frame count + capture button + Done button
                CaptureBottomBar(
                    frameCount = frameCount,
                    capturing = capturing,
                    autoEnabled = autoCaptureEnabled,
                    intervalSeconds = intervalSeconds,
                    onIntervalChange = vm::setIntervalSeconds,
                    countdownProgress = {
                        if (intervalSeconds > 0) 1f - (remainingMs.toFloat() / (intervalSeconds * 1000f)) else 0f
                    },
                    secondsLeft = kotlin.math.ceil(remainingMs / 1000f).toInt(),
                    onTakePhoto = { vm.takePhoto(context) },
                    onToggleAuto = vm::toggleAutoCapture,
                    onDone = onDone,
                    onCancel = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )

                // Error banner: shown briefly when auto-capture stops due to a failure.
                LaunchedEffect(lastError) {
                    if (lastError != null) {
                        delay(4000)
                        vm.consumeError()
                    }
                }
                if (lastError != null) {
                    Text(
                        text = stringResource(R.string.capture_auto_error, lastError ?: ""),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(
                                color = Color.Black.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    // Re-bind on resume
    DisposableEffect(lifecycleOwner) {
        onDispose { /* cameraX handles lifecycle automatically */ }
    }
}

@Composable
private fun CaptureTopControls(
    gridEnabled: Boolean,
    onionEnabled: Boolean,
    autoEnabled: Boolean,
    onToggleGrid: () -> Unit,
    onToggleOnion: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleAuto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleGrid,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (gridEnabled) Icons.Default.GridOn else Icons.Default.GridOff,
                contentDescription = stringResource(R.string.capture_grid),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        IconButton(
            onClick = onToggleOnion,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (onionEnabled)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (onionEnabled) Icons.Default.Layers else Icons.Default.LayersClear,
                contentDescription = stringResource(R.string.capture_onion_skin),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        IconButton(
            onClick = onFlipCamera,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Cameraswitch,
                contentDescription = stringResource(R.string.capture_flip_camera),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        IconButton(
            onClick = onToggleAuto,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (autoEnabled)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (autoEnabled) Icons.Default.Timer else Icons.Default.TimerOff,
                contentDescription = stringResource(R.string.capture_auto_mode),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun CaptureBottomBar(
    frameCount: Int,
    capturing: Boolean,
    autoEnabled: Boolean,
    intervalSeconds: Int,
    onIntervalChange: (Int) -> Unit,
    countdownProgress: () -> Float,
    secondsLeft: Int,
    onTakePhoto: () -> Unit,
    onToggleAuto: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(visible = autoEnabled) {
            IntervalSelector(
                intervalSeconds = intervalSeconds,
                onIntervalChange = onIntervalChange,
            )
        }

        Text(
            text = stringResource(R.string.capture_frames_count, frameCount),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        AnimatedVisibility(visible = autoEnabled) {
            Text(
                text = stringResource(R.string.capture_next_shot_in, secondsLeft),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cancel
            IconButton(
                onClick = onCancel,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.45f),
                ),
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Cancelar", tint = Color.White)
            }

            // Capture button
            Box(
                modifier = Modifier.size(108.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (autoEnabled && !capturing) {
                    CountdownRing(
                        progress = countdownProgress,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp),
                        )
                    } else if (autoEnabled) {
                        IconButton(onClick = onToggleAuto) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stringResource(R.string.capture_stop_auto),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    } else {
                        IconButton(onClick = onTakePhoto) {
                            Icon(
                                imageVector = Icons.Default.Camera,
                                contentDescription = stringResource(R.string.capture_take_photo),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }
                }
            }

            // Done
            IconButton(
                onClick = onDone,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.capture_done),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun IntervalSelector(
    intervalSeconds: Int,
    onIntervalChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onIntervalChange(intervalSeconds - 1) },
            enabled = intervalSeconds > com.stopmotion.app.data.ProjectRepository.MIN_INTERVAL_SECONDS,
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.capture_interval_decrease), tint = Color.White)
        }
        Text(
            text = stringResource(R.string.capture_interval_label, intervalSeconds),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        IconButton(
            onClick = { onIntervalChange(intervalSeconds + 1) },
            enabled = intervalSeconds < com.stopmotion.app.data.ProjectRepository.MAX_INTERVAL_SECONDS,
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.capture_interval_increase), tint = Color.White)
        }
    }
}

@Composable
private fun CountdownRing(
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val progressColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = modifier) {
        val strokeWidth = 4.dp.toPx()
        val inset = strokeWidth / 2
        drawArc(
            color = Color.White.copy(alpha = 0.25f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
        )
        drawArc(
            color = progressColor,
            startAngle = -90f,
            sweepAngle = 360f * progress(),
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
        )
    }
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val color = Color.White.copy(alpha = 0.45f)
        val stroke = Stroke(width = 1.dp.toPx())
        // Vertical lines (1/3 and 2/3)
        for (i in 1..2) {
            val x = size.width * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke.width)
        }
        // Horizontal lines
        for (i in 1..2) {
            val y = size.height * i / 3f
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke.width)
        }
    }
}

@Composable
private fun ImageWithAlpha(
    bitmap: android.graphics.Bitmap,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        with(drawContext.canvas) {
            drawImage(
                image = bitmap.asImageBitmap(),
                topLeft = Offset.Zero,
                alpha = alpha,
            )
        }
    }
}

@Composable
private fun PermissionRationale(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.capture_permission_rationale),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FloatingActionButton(onClick = onRequest) {
            Text(stringResource(R.string.capture_permission_grant))
        }
    }
}

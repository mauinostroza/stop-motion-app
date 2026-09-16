package com.stopmotion.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stopmotion.app.R
import com.stopmotion.app.data.ServiceLocator
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

    val previewView = remember { PreviewView(context) }

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

                // Top controls (grid toggle, onion toggle, flip camera)
                CaptureTopControls(
                    gridEnabled = gridEnabled,
                    onionEnabled = onionEnabled,
                    onToggleGrid = vm::toggleGrid,
                    onToggleOnion = vm::toggleOnionSkin,
                    onFlipCamera = vm::flipCamera,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                )

                // Bottom: frame count + capture button + Done button
                CaptureBottomBar(
                    frameCount = frameCount,
                    capturing = capturing,
                    onTakePhoto = { vm.takePhoto(context) },
                    onDone = onDone,
                    onCancel = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
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
    onToggleGrid: () -> Unit,
    onToggleOnion: () -> Unit,
    onFlipCamera: () -> Unit,
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
    }
}

@Composable
private fun CaptureBottomBar(
    frameCount: Int,
    capturing: Boolean,
    onTakePhoto: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

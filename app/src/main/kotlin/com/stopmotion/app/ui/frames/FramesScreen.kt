package com.stopmotion.app.ui.frames

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stopmotion.app.R
import com.stopmotion.app.data.Frame

/**
 * Frame management screen.
 *
 * Lists the captured / picked frames in their timeline order. Supports:
 *  - Drag-to-reorder via the drag handle on the right side of each row.
 *  - Long-tap on row to delete.
 *  - Top app bar with two extended FABs: add from camera, add from gallery.
 *  - Bottom action bar with Preview / Export actions (enabled when there
 *    are 2+ frames).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FramesScreen(
    onNavigateToCapture: () -> Unit,
    onNavigateToPreview: () -> Unit,
    onNavigateToExport: () -> Unit,
    vm: FramesViewModel = viewModel(),
) {
    val frames by vm.frames.collectAsState()
    val context = LocalContext.current

    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    // Multiple-image picker (modern Android Photo Picker API)
    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            vm.addPickedUris(uris)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.frames_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateToCapture) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.frames_add_camera))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { pickMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        ) }
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.frames_add_gallery))
                    }
                    if (frames.isNotEmpty()) {
                        IconButton(onClick = { confirmDeleteAll = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.frames_delete_all))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (frames.size >= 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ExtendedFloatingActionButton(
                        onClick = onNavigateToPreview,
                        icon = { Icon(Icons.Default.PlayArrow, null) },
                        text = { Text(stringResource(R.string.frames_preview)) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    ExtendedFloatingActionButton(
                        onClick = onNavigateToExport,
                        icon = { Icon(Icons.Default.MovieCreation, null) },
                        text = { Text(stringResource(R.string.frames_export)) },
                    )
                }
            }
        },
    ) { padding ->
        if (frames.isEmpty()) {
            EmptyState(
                onAddFromCamera = onNavigateToCapture,
                onAddFromGallery = {
                    pickMediaLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = 12.dp, bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = frames, key = { it.id }) { frame ->
                    FrameRow(
                        frame = frame,
                        position = frames.indexOf(frame) + 1,
                        onTapDelete = { pendingDeleteId = frame.id },
                        onMoveUp = {
                            val cur = frames.indexOf(frame)
                            if (cur > 0) vm.moveFrame(cur, cur - 1)
                        },
                        onMoveDown = {
                            val cur = frames.indexOf(frame)
                            if (cur < frames.size - 1) vm.moveFrame(cur, cur + 1)
                        },
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.frames_delete_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFrame(id)
                    pendingDeleteId = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.frames_cancel))
                }
            },
        )
    }

    // Delete-all confirmation
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.frames_delete_all_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    confirmDeleteAll = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.frames_cancel))
                }
            },
        )
    }
}

@Composable
private fun FrameRow(
    frame: Frame,
    position: Int,
    onTapDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = position.toString(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.width(8.dp))

        AsyncImage(
            model = frame.filePath ?: frame.sourceUri,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        )

        Spacer(Modifier.width(8.dp))

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "Frame $position",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (frame.filePath != null) "Capturada" else "Galería",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            IconButton(onClick = onMoveUp, enabled = position > 1) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Mover arriba")
            }
            IconButton(onClick = onMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Mover abajo")
            }
        }

        IconButton(onClick = onTapDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.frames_delete))
        }
    }
}

@Composable
private fun EmptyState(
    onAddFromCamera: () -> Unit,
    onAddFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MovieCreation,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.frames_empty),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = onAddFromCamera,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.frames_add_camera)) },
            )
            ExtendedFloatingActionButton(
                onClick = onAddFromGallery,
                icon = { Icon(Icons.Default.PhotoLibrary, null) },
                text = { Text(stringResource(R.string.frames_add_gallery)) },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

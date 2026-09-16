package com.stopmotion.app.ui.export

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stopmotion.app.R
import com.stopmotion.app.data.ExportResolution
import com.stopmotion.app.data.ServiceLocator

/**
 * Export screen: lets the user pick the output resolution (FilterChip row),
 * tweak FPS via a slider (1–30), and start the encoding.
 *
 * On success, surfaces two actions:
 *  - Save to gallery (MediaStore insert + copy)
 *  - Share via ACTION_SEND
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    vm: ExportViewModel = viewModel(),
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.init(ServiceLocator.provideRepository(context))
    }

    val resolution by vm.resolution.collectAsState()
    val fps by vm.fps.collectAsState()
    val progress by vm.progress.collectAsState()
    val status by vm.status.collectAsState()
    val outputFile by vm.outputFile.collectAsState()
    val frameCount by vm.frameCount.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Frame count summary
            Text(
                text = "Total: $frameCount fotos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            HorizontalDivider()

            // Resolution chips
            Text(
                text = stringResource(R.string.export_resolution),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExportResolution.values().take(3).forEach { res ->
                    FilterChip(
                        selected = resolution == res,
                        onClick = { vm.setResolution(res) },
                        label = { Text(res.label) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExportResolution.values().drop(3).forEach { res ->
                    FilterChip(
                        selected = resolution == res,
                        onClick = { vm.setResolution(res) },
                        label = { Text(res.label) },
                    )
                }
            }

            HorizontalDivider()

            // FPS slider
            Text(
                text = "${stringResource(R.string.export_fps)}: $fps",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = fps.toFloat(),
                onValueChange = { vm.setFps(it.toInt()) },
                valueRange = 1f..30f,
                steps = 28,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("1 fps", style = MaterialTheme.typography.labelSmall)
                Text("15 fps", style = MaterialTheme.typography.labelSmall)
                Text("30 fps", style = MaterialTheme.typography.labelSmall)
            }

            HorizontalDivider()

            // Encode button
            when (val s = status) {
                ExportStatus.Idle -> {
                    Button(
                        onClick = { vm.startExport(context) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = frameCount > 0,
                    ) {
                        Icon(Icons.Default.MovieCreation, contentDescription = null)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.export_start))
                    }
                }
                ExportStatus.Running -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.export_creating),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.export_progress, (progress * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is ExportStatus.Done -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(48.dp),
                        )
                        Text(
                            text = stringResource(R.string.export_success),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )

                        outputFile?.let { file ->
                            Text(
                                text = file.absolutePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    saveToGallery(context, s.uri, "video/mp4")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.export_save_gallery))
                            }
                            Button(
                                onClick = {
                                    val shareIntent = vm.buildShareIntent(context)
                                    if (shareIntent != null) {
                                        context.startActivity(Intent.createChooser(shareIntent, "Compartir video"))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.export_share))
                            }
                        }

                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.back))
                        }
                    }
                }
                is ExportStatus.Error -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.export_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { vm.startExport(context) }) {
                            Text("Reintentar")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Saves a content [uri] of [mimeType] into the system MediaStore for the
 * "Movies/StopMotion" album. This is the canonical way to make a video
 * appear in the user's gallery on API 26+.
 */
private fun saveToGallery(
    context: android.content.Context,
    uri: Uri,
    mimeType: String,
) {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "stopmotion_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, mimeType)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StopMotion")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val item = resolver.insert(collection, values) ?: return

    resolver.openInputStream(uri)?.use { input ->
        resolver.openOutputStream(item)?.use { output ->
            input.copyTo(output)
        }
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val finalize = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        resolver.update(item, finalize, null, null)
    }
}

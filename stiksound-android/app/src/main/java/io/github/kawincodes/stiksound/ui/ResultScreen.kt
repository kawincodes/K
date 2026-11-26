package io.github.kawincodes.stiksound.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kawincodes.stiksound.ExportResult
import io.github.kawincodes.stiksound.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    result: ExportResult,
    saved: Boolean,
    snackbar: SnackbarHostState,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (result is ExportResult.Video) R.string.result_video_title else R.string.result_webp_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when (result) {
                    is ExportResult.Video -> LoopingVideo(result.file.absolutePath)
                    is ExportResult.Webp -> WebpPreview(result.file.absolutePath)
                }
            }

            val sizeText = Formatter.formatShortFileSize(context, result.file.length())
            Text(
                when (result) {
                    is ExportResult.Video -> stringResource(
                        R.string.result_video_info,
                        result.sizePx,
                        result.sizePx,
                        formatSeconds(result.durationMs),
                        sizeText,
                    )
                    is ExportResult.Webp -> stringResource(R.string.result_webp_info, sizeText)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = onSave, enabled = !saved, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (saved) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.saved))
                } else {
                    Text(stringResource(R.string.save_to_gallery))
                }
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text(stringResource(R.string.share))
            }
            Text(
                stringResource(if (result is ExportResult.Video) R.string.result_video_note else R.string.result_webp_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoopingVideo(path: String) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
                setVideoURI(Uri.fromFile(File(path)))
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun WebpPreview(path: String) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
        Checkerboard()
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Checkerboard() {
    val light = Color(0xFFE8EBEE)
    val dark = Color(0xFFC9CED4)
    Canvas(Modifier.fillMaxSize().background(light)) {
        val cell = 16.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) {
            if ((r + c) % 2 == 1) drawRect(dark, Offset(c * cell, r * cell), Size(cell, cell))
        }
    }
}

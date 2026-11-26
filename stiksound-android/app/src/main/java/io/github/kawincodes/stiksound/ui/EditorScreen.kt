package io.github.kawincodes.stiksound.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kawincodes.stiksound.Busy
import io.github.kawincodes.stiksound.EditorState
import io.github.kawincodes.stiksound.EditorViewModel
import io.github.kawincodes.stiksound.Matte
import io.github.kawincodes.stiksound.R
import io.github.kawincodes.stiksound.UiMessage
import io.github.kawincodes.stiksound.core.Limits

private val SWATCHES = listOf(
    0xFF25D366, 0xFF128C7E, 0xFF34B7F1, 0xFF5B5FC7, 0xFFE91E63,
    0xFFFF7043, 0xFFFFC107, 0xFF8D6E63, 0xFF9E9E9E, 0xFFFDE7F3,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    vm: EditorViewModel,
    snackbar: SnackbarHostState,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::loadSticker)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::loadAudio)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = onAbout) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.about))
                    }
                    IconButton(onClick = vm::reset, enabled = state.sticker != null || state.audio != null) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.reset))
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StickerStage(
                    sticker = state.sticker,
                    cropX = state.cropX,
                    cropY = state.cropY,
                    matte = Color(state.matteArgb),
                    loading = state.busy == Busy.STICKER,
                    onCrop = vm::setCrop,
                    onPick = { pickImage.launch("image/*") },
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
            StickerActions(state, onPick = { pickImage.launch("image/*") }, onPaste = { pasteFromClipboard(context, vm) })
            if (state.sticker?.hasAlpha == true) {
                BackgroundCard(state, vm)
            }
            AudioCard(state, vm, onPickAudio = { pickAudio.launch(arrayOf("audio/*", "video/*")) })
            ExportCard(state, vm)
            Text(
                stringResource(R.string.whatsapp_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun pasteFromClipboard(context: Context, vm: EditorViewModel) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip
    val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    val uri = item?.uri
    val text = item?.text?.toString()
    when {
        uri != null -> {
            val type = context.contentResolver.getType(uri) ?: clip.description.getMimeType(0) ?: ""
            when {
                type.startsWith("image/") -> vm.loadSticker(uri)
                type.startsWith("audio/") || type.startsWith("video/") -> vm.loadAudio(uri)
                else -> vm.showMessage(UiMessage(R.string.paste_nothing))
            }
        }
        !text.isNullOrBlank() -> vm.fetchMyInstants(text)
        else -> vm.showMessage(UiMessage(R.string.paste_nothing))
    }
}

@Composable
private fun StickerActions(state: EditorState, onPick: () -> Unit, onPaste: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (state.sticker == null) R.string.pick_image else R.string.change_image))
            }
            OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.paste))
            }
        }
        state.sticker?.let { s ->
            val animatedInfo = if (s.animated) {
                pluralStringResource(R.plurals.sticker_animated_info, s.frames.size, s.frames.size, formatSeconds(s.loopDurationMs))
            } else {
                null
            }
            val info = buildString {
                append(s.format.name)
                append(" · ")
                append("${s.originalWidth}×${s.originalHeight}")
                if (animatedInfo != null) append(" · ").append(animatedInfo)
            }
            Text(
                text = if (s.originalWidth != s.originalHeight) {
                    stringResource(R.string.drag_to_frame, info)
                } else {
                    info
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BackgroundCard(state: EditorState, vm: EditorViewModel) {
    SectionCard(stringResource(R.string.background_title)) {
        Text(
            stringResource(R.string.background_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val options = listOf(Matte.WHITE to R.string.matte_white, Matte.BLACK to R.string.matte_black, Matte.CUSTOM to R.string.matte_color)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (matte, label) ->
                SegmentedButton(
                    selected = state.matte == matte,
                    onClick = { vm.setMatte(matte) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        if (state.matte == Matte.CUSTOM) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SWATCHES.forEach { argb ->
                    val selected = (state.customColor or 0xFF000000.toInt()) == argb
                    val label = io.github.kawincodes.stiksound.core.ImageOps.toHexColor(argb)
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .clickable { vm.setCustomColor(argb) }
                            .semantics { contentDescription = label },
                    )
                }
            }
            HexField(state.customColor, vm::setCustomColorHex)
        }
    }
}

@Composable
private fun HexField(color: Int, onHex: (String) -> Boolean) {
    var text by rememberSaveable(color) { mutableStateOf(io.github.kawincodes.stiksound.core.ImageOps.toHexColor(color)) }
    var error by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(7)
            error = false
        },
        label = { Text(stringResource(R.string.hex_color)) },
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            error = !onHex(text)
            if (!error) focus.clearFocus()
        }),
        modifier = Modifier.width(180.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioCard(state: EditorState, vm: EditorViewModel, onPickAudio: () -> Unit) {
    SectionCard(stringResource(R.string.audio_title)) {
        var url by rememberSaveable { mutableStateOf("") }
        val focus = LocalFocusManager.current
        val downloading = state.busy == Busy.DOWNLOAD
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.myinstants_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                enabled = !downloading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    focus.clearFocus()
                    vm.fetchMyInstants(url)
                }),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    focus.clearFocus()
                    vm.fetchMyInstants(url)
                },
                enabled = url.isNotBlank() && !downloading,
            ) { Text(stringResource(R.string.fetch)) }
        }
        FilledTonalButton(onClick = onPickAudio, modifier = Modifier.fillMaxWidth(), enabled = !downloading) {
            Text(stringResource(R.string.pick_audio))
        }
        if (state.busy == Busy.AUDIO || downloading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val audio = state.audio
        val trim = state.trim
        if (audio != null && trim != null) {
            Text(
                audio.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TrimBar(
                peaks = audio.peaks,
                totalMs = audio.durationMs,
                trim = trim,
                minMs = state.minTrimMs,
                maxMs = state.maxDurationMs,
                playheadMs = state.playheadMs,
                onTrim = vm::setTrim,
                description = stringResource(R.string.trim_description, formatSeconds(trim.startMs), formatSeconds(trim.endMs)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val playLabel = stringResource(if (state.playing) R.string.stop_preview else R.string.play_preview)
                FilledIconButton(
                    onClick = vm::togglePreview,
                    colors = IconButtonDefaults.filledIconButtonColors(),
                    modifier = Modifier.semantics { contentDescription = playLabel },
                ) {
                    if (state.playing) {
                        Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp)))
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.selection_range, formatMs(trim.startMs), formatMs(trim.endMs)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.selection_length, formatSeconds(trim.durationMs), formatSeconds(audio.durationMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if ((state.sticker?.loopDurationMs ?: 0) > trim.durationMs) {
                Text(
                    stringResource(R.string.gif_loop_note, formatSeconds(minOf(state.sticker!!.loopDurationMs, state.maxDurationMs))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.max_duration),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            SingleChoiceSegmentedButtonRow {
                Limits.AUDIO_DURATION_PRESETS.forEachIndexed { i, ms ->
                    SegmentedButton(
                        selected = state.maxDurationMs == ms,
                        onClick = { vm.setMaxDuration(ms) },
                        shape = SegmentedButtonDefaults.itemShape(i, Limits.AUDIO_DURATION_PRESETS.size),
                    ) { Text("${ms / 1000}s") }
                }
            }
        }
    }
}

@Composable
private fun ExportCard(state: EditorState, vm: EditorViewModel) {
    SectionCard(stringResource(R.string.export_title)) {
        val exporting = state.busy == Busy.EXPORT
        if (exporting) {
            val p = state.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.exporting), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = vm::exportVideo,
            enabled = state.videoBlocker == null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.create_video, state.maxDurationMs / 1000), fontWeight = FontWeight.SemiBold)
        }
        state.videoBlocker?.takeIf { !exporting }?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            onClick = vm::exportWebp,
            enabled = state.sticker != null && state.busy == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.create_webp)) }
    }
}

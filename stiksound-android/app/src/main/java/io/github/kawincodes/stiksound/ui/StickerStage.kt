package io.github.kawincodes.stiksound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kawincodes.stiksound.R
import io.github.kawincodes.stiksound.core.computeSquareCrop
import io.github.kawincodes.stiksound.media.StickerSource
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * Square preview of the sticker as it will be exported. Drag to move the 1:1 crop
 * on non-square images; animated GIFs play with their own frame delays.
 */
@Composable
fun StickerStage(
    sticker: StickerSource?,
    cropX: Float,
    cropY: Float,
    matte: Color,
    loading: Boolean,
    onCrop: (Float, Float) -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val outline = MaterialTheme.colorScheme.outline
    val stageDescription = stringResource(R.string.stage_description)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = stageDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (sticker == null) {
            EmptyStage(outline, onPick)
        } else {
            StickerCanvas(sticker, cropX, cropY, matte, onCrop)
        }
        if (loading) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
        if (sticker != null) {
            Box(Modifier.fillMaxSize().border(1.dp, outline.copy(alpha = 0.6f), shape))
        }
    }
}

@Composable
private fun EmptyStage(outline: Color, onPick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onPick)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = outline,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.stage_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.stage_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StickerCanvas(
    sticker: StickerSource,
    cropX: Float,
    cropY: Float,
    matte: Color,
    onCrop: (Float, Float) -> Unit,
) {
    val images = remember(sticker) { sticker.frames.map { it.asImageBitmap() } }
    var frameIndex by remember(sticker) { mutableIntStateOf(0) }
    LaunchedEffect(sticker) {
        if (!sticker.animated) return@LaunchedEffect
        while (true) {
            delay(sticker.delaysMs[frameIndex].toLong().coerceAtLeast(20))
            frameIndex = (frameIndex + 1) % images.size
        }
    }

    val w = sticker.width
    val h = sticker.height
    val side = min(w, h)
    val canMove = w != h
    val currentCrop by rememberUpdatedState(cropX to cropY)
    val currentOnCrop by rememberUpdatedState(onCrop)

    val dragModifier = if (!canMove) Modifier else Modifier.pointerInput(sticker) {
        detectDragGestures { change, drag ->
            change.consume()
            val (cx, cy) = currentCrop
            val srcPerPx = side / size.width.toFloat()
            val nx = if (w > side) cx - drag.x * srcPerPx / (w - side) else cx
            val ny = if (h > side) cy - drag.y * srcPerPx / (h - side) else cy
            currentOnCrop(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
        }
    }

    Canvas(Modifier.fillMaxSize().then(dragModifier)) {
        drawRect(matte)
        val crop = computeSquareCrop(w, h, cropX, cropY)
        drawImage(
            image = images[frameIndex.coerceIn(0, images.lastIndex)],
            srcOffset = IntOffset(crop.srcX, crop.srcY),
            srcSize = IntSize(crop.cropSide, crop.cropSide),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Medium,
        )
        if (canMove) {
            // Small hint bar showing where the crop sits along the long side.
            val track = 4.dp.toPx()
            val margin = 10.dp.toPx()
            val horizontal = w > h
            val fraction = side.toFloat() / (if (horizontal) w else h)
            val pos = if (horizontal) cropX else cropY
            val length = (if (horizontal) size.width else size.height) - margin * 2
            val thumb = length * fraction
            val start = margin + (length - thumb) * pos
            val trackColor = Color.Black.copy(alpha = 0.25f)
            val thumbColor = Color.White.copy(alpha = 0.9f)
            if (horizontal) {
                val y = size.height - margin - track
                drawRoundRect(trackColor, Offset(margin, y), Size(length, track), androidx.compose.ui.geometry.CornerRadius(track))
                drawRoundRect(thumbColor, Offset(start, y), Size(thumb, track), androidx.compose.ui.geometry.CornerRadius(track))
            } else {
                val x = size.width - margin - track
                drawRoundRect(trackColor, Offset(x, margin), Size(track, length), androidx.compose.ui.geometry.CornerRadius(track))
                drawRoundRect(thumbColor, Offset(x, start), Size(track, thumb), androidx.compose.ui.geometry.CornerRadius(track))
            }
        }
    }
}

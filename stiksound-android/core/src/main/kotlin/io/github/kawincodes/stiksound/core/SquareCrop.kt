package io.github.kawincodes.stiksound.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class SquareCropRect(
    val srcX: Int,
    val srcY: Int,
    val cropSide: Int,
    val outputSize: Int,
)

/**
 * Computes a 1:1 square crop anchored at normalized ([cropX], [cropY]) in [0, 1]
 * (0.5, 0.5 = center). The output size depends only on the input: at most 512 px,
 * never upscaled, and rounded down to an even number for video encoders.
 */
fun computeSquareCrop(
    srcWidth: Int,
    srcHeight: Int,
    cropX: Float = 0.5f,
    cropY: Float = 0.5f,
): SquareCropRect {
    val safeW = max(1, srcWidth)
    val safeH = max(1, srcHeight)
    val cropSide = min(safeW, safeH)

    val rawOut = min(Limits.STICKER_DIMENSION_PX, cropSide)
    val outputSize = if (rawOut <= 2) rawOut else rawOut - (rawOut % 2)

    val nx = if (cropX.isFinite()) cropX.coerceIn(0f, 1f) else 0.5f
    val ny = if (cropY.isFinite()) cropY.coerceIn(0f, 1f) else 0.5f

    val srcX = ((safeW - cropSide) * nx).roundToInt()
    val srcY = ((safeH - cropSide) * ny).roundToInt()

    return SquareCropRect(srcX, srcY, cropSide, outputSize)
}

package io.github.kawincodes.stiksound.core

/**
 * Hard limits, ported from StikSound `packages/core/src/domain/limits.ts`.
 * Keeping the same numbers means a file accepted by the web/desktop app is
 * accepted here, and vice versa.
 */
object Limits {
    const val MAX_IMAGE_BYTES: Int = 20 * 1024 * 1024
    const val MAX_AUDIO_BYTES: Int = 25 * 1024 * 1024
    const val MAX_AUDIO_DURATION_MS: Int = 180_000
    const val MIN_AUDIO_DURATION_MS: Int = 100
    const val MAX_IMAGE_PIXELS: Long = 40_000_000
    const val MAX_IMAGE_DIMENSION: Int = 8000
    const val MAX_GIF_FRAMES: Int = 300
    const val MAX_GIF_CANVAS_BYTES: Long = 64L * 1024 * 1024
    const val MAX_GIF_FRAME_PIXELS: Long = 16_777_216
    const val MAX_GIF_DECODED_BYTES: Long = 256L * 1024 * 1024
    const val DEFAULT_VIDEO_MAX_AUDIO_MS: Int = 10_000
    const val MIN_TRIM_DURATION_MS: Int = 100
    const val DURATION_TOLERANCE_MS: Int = 50
    const val STICKER_DIMENSION_PX: Int = 512
    val AUDIO_DURATION_PRESETS: List<Int> = listOf(5_000, 10_000)
    const val MAX_STATIC_WEBP_BYTES: Int = 102_400
    const val MAX_REDIRECTS: Int = 2
    const val FETCH_TIMEOUT_MS: Int = 20_000
    const val MAX_HTML_BYTES: Int = 2 * 1024 * 1024
    const val WAVEFORM_BUCKETS: Int = 1024
    const val OUTPUT_SAMPLE_RATE: Int = 48_000
    const val DEFAULT_MATTE_COLOR: String = "#FFFFFF"

    /** Frame rate used when a still image becomes a video (upstream: 10 fps). */
    const val STATIC_VIDEO_FPS: Int = 10

    /** Frame rate an animated GIF is resampled onto for the encoder (upstream: 24 fps). */
    const val GIF_VIDEO_FPS: Int = 24

    val ALLOWED_MYINSTANTS_HOSTS: Set<String> = setOf("www.myinstants.com", "myinstants.com")
}

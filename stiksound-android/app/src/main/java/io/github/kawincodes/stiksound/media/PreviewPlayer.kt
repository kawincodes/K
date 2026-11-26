package io.github.kawincodes.stiksound.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.kawincodes.stiksound.core.AudioDsp
import io.github.kawincodes.stiksound.core.MonoPcm
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Plays the selected part of the audio and reports the playhead. */
object PreviewPlayer {
    /**
     * Plays [startMs, endMs) of [pcm]; suspends until playback ends or the caller is
     * cancelled. [onPosition] receives the playhead in source milliseconds.
     */
    suspend fun play(pcm: MonoPcm, startMs: Int, endMs: Int, onPosition: (Int) -> Unit) = coroutineScope {
        val rate = pcm.sampleRate
        val from = floor(startMs / 1000.0 * rate).toInt().coerceIn(0, pcm.samples.size)
        val to = floor(endMs / 1000.0 * rate).toInt().coerceIn(from, pcm.samples.size)
        if (to <= from) return@coroutineScope
        val samples = AudioDsp.toPcm16(pcm.samples, from, to)
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, 8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val ticker = launch {
                while (isActive) {
                    val played = track.playbackHeadPosition
                    onPosition(startMs + (played * 1000L / rate).toInt())
                    delay(30)
                }
            }
            withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < samples.size && isActive) {
                    val n = track.write(samples, offset, minOf(4096, samples.size - offset))
                    if (n <= 0) break
                    offset += n
                }
                // Wait for the tail of the buffer to play out.
                while (isActive && track.playbackHeadPosition < samples.size) delay(20)
            }
            ticker.cancel()
        } finally {
            withContext(NonCancellable) {
                runCatching { track.pause(); track.flush(); track.stop() }
                track.release()
            }
        }
    }
}

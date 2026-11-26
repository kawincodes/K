package io.github.kawincodes.stiksound.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.kawincodes.stiksound.core.AudioDsp
import io.github.kawincodes.stiksound.core.MonoPcm
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .also { it.asShortBuffer().put(samples) }
            .array()
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
                while (offset < bytes.size && isActive) {
                    val n = track.write(bytes, offset, minOf(8192, bytes.size - offset))
                    if (n <= 0) break
                    offset += n
                }
                // Wait for the tail of the buffer to play out; give up if the playhead stops moving.
                val framesWritten = offset / 2
                var lastHead = -1
                var stalledTicks = 0
                while (isActive) {
                    val head = track.playbackHeadPosition
                    if (head >= framesWritten) break
                    stalledTicks = if (head == lastHead) stalledTicks + 1 else 0
                    if (stalledTicks > 50) break
                    lastHead = head
                    delay(20)
                }
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

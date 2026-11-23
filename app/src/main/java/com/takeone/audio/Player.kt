package com.takeone.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/** Plays a slice of an in-memory take to whatever output is active (e.g. your headphones). */
class Player {
    @Volatile var isPlaying = false
        private set
    /** Current playhead as a sample index into the take. */
    @Volatile var position = 0
        private set

    private var thread: Thread? = null

    fun play(samples: ShortArray, rate: Int, from: Int, to: Int, onDone: () -> Unit) {
        stop()
        val start = from.coerceIn(0, samples.size)
        val end = to.coerceIn(start, samples.size)
        if (end - start < rate / 20) return
        isPlaying = true
        position = start
        thread = Thread({
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuf, rate / 5 * 2))
                .build()
            try {
                track.play()
                val block = rate / 20
                var i = start
                while (isPlaying && i < end) {
                    val n = min(block, end - i)
                    val w = track.write(samples, i, n)
                    if (w <= 0) break
                    i += w
                    position = (start + track.playbackHeadPosition).coerceAtMost(end)
                }
                // Let the tail drain so the last bit isn't cut off.
                val total = i - start
                while (isPlaying && track.playbackHeadPosition < total) {
                    position = (start + track.playbackHeadPosition).coerceAtMost(end)
                    Thread.sleep(15)
                }
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
                isPlaying = false
                onDone()
            }
        }, "TakeOne-player").also { it.start() }
    }

    fun stop() {
        isPlaying = false
        thread?.join(500)
        thread = null
    }
}

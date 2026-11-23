package com.takeone.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Encodes PCM to AAC-LC using the phone's hardware/software encoder and wraps each frame in
 * an ADTS header, producing a playable .aac file entirely in memory (no temp files).
 */
object AacEncoder {

    fun encode(samples: ShortArray, sampleRate: Int, bitRate: Int = 160_000, onProgress: (Float) -> Unit = {}): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = ByteArrayOutputStream(samples.size / 4 + 1024)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inPos = 0
            var inputDone = false
            var outputDone = false
            var frame = ByteArray(4096)

            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        val n = min(buf.remaining() / 2, samples.size - inPos)
                        val ptsUs = inPos * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, inPos, n)
                            codec.queueInputBuffer(idx, 0, n * 2, ptsUs, 0)
                            inPos += n
                            onProgress(inPos.toFloat() / samples.size)
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        if (frame.size < info.size) frame = ByteArray(info.size)
                        ob.get(frame, 0, info.size)
                        out.write(Adts.header(info.size, sampleRate, 1))
                        out.write(frame, 0, info.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            codec.stop()
        } finally {
            codec.release()
        }
        return out.toByteArray()
    }
}

package com.takeone.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** One encoded AAC frame held in memory. */
class AacFrame(val data: ByteArray, val ptsUs: Long)

/** Encoded audio ready for muxing: the codec's output format (with its config) plus all frames. */
class AacTrack(val format: MediaFormat, val frames: List<AacFrame>)

/**
 * Encodes PCM to AAC-LC with the phone's encoder, entirely in memory (no temp files).
 */
object AacEncoder {

    /** Raw AAC frames + format, for putting inside an MP4. */
    fun encodeTrack(samples: ShortArray, sampleRate: Int, bitRate: Int = 160_000, onProgress: (Float) -> Unit = {}): AacTrack {
        val frames = ArrayList<AacFrame>()
        val format = encodeWith(samples, sampleRate, bitRate, onProgress) { buf, info ->
            val arr = ByteArray(info.size)
            buf.get(arr)
            frames.add(AacFrame(arr, info.presentationTimeUs))
        }
        return AacTrack(format, frames)
    }

    /** A standalone .aac file (ADTS stream). */
    fun encode(samples: ShortArray, sampleRate: Int, bitRate: Int = 160_000, onProgress: (Float) -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream(samples.size / 4 + 1024)
        var frame = ByteArray(4096)
        encodeWith(samples, sampleRate, bitRate, onProgress) { buf, info ->
            if (frame.size < info.size) frame = ByteArray(info.size)
            buf.get(frame, 0, info.size)
            out.write(Adts.header(info.size, sampleRate, 1))
            out.write(frame, 0, info.size)
        }
        return out.toByteArray()
    }

    private fun encodeWith(
        samples: ShortArray,
        sampleRate: Int,
        bitRate: Int,
        onProgress: (Float) -> Unit,
        sink: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ): MediaFormat {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var outFormat: MediaFormat = format
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inPos = 0
            var inputDone = false
            var outputDone = false

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
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (outIdx >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        sink(ob, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            codec.stop()
        } finally {
            codec.release()
        }
        return outFormat
    }
}

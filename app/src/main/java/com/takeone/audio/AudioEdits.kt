package com.takeone.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Growable in-memory PCM store made of fixed-size chunks, so recording never has to
 * reallocate one huge array. Nothing here ever touches the disk.
 */
class ChunkStore(private val chunkSize: Int) {
    private val chunks = ArrayList<ShortArray>()
    private var pos = chunkSize
    var size = 0
        private set

    fun append(src: ShortArray, n: Int) {
        var off = 0
        while (off < n) {
            if (pos == chunkSize) {
                chunks.add(ShortArray(chunkSize)); pos = 0
            }
            val c = min(n - off, chunkSize - pos)
            System.arraycopy(src, off, chunks[chunks.size - 1], pos, c)
            pos += c; off += c; size += c
        }
    }

    /** Copies everything into one array, releasing chunks as it goes to keep peak memory low. */
    fun flatten(): ShortArray {
        val out = ShortArray(size)
        var o = 0
        for (i in chunks.indices) {
            val ch = chunks[i]
            val c = min(ch.size, size - o)
            System.arraycopy(ch, 0, out, o, c)
            o += c
            chunks[i] = EMPTY
        }
        chunks.clear()
        pos = chunkSize
        size = 0
        return out
    }

    private companion object { val EMPTY = ShortArray(0) }
}

/** Pure, non-destructive edit operations. Each returns a new array. */
object AudioEdits {

    fun trim(s: ShortArray, from: Int, to: Int): ShortArray {
        val a = from.coerceIn(0, s.size); val b = to.coerceIn(a, s.size)
        return s.copyOfRange(a, b)
    }

    fun delete(s: ShortArray, from: Int, to: Int): ShortArray {
        val a = from.coerceIn(0, s.size); val b = to.coerceIn(a, s.size)
        val out = ShortArray(s.size - (b - a))
        System.arraycopy(s, 0, out, 0, a)
        System.arraycopy(s, b, out, a, s.size - b)
        return out
    }

    fun gainDb(s: ShortArray, from: Int, to: Int, db: Float): ShortArray =
        scale(s, from, to, 10f.pow(db / 20f))

    /** Raises the selection so its loudest peak sits at -0.3 dBFS. */
    fun normalize(s: ShortArray, from: Int, to: Int): ShortArray {
        val a = from.coerceIn(0, s.size); val b = to.coerceIn(a, s.size)
        var peak = 0
        for (i in a until b) peak = max(peak, abs(s[i].toInt()))
        if (peak == 0) return s.copyOf()
        return scale(s, a, b, 32767f * 0.966f / peak)
    }

    fun fadeIn(s: ShortArray, from: Int, to: Int, maxLen: Int): ShortArray {
        val out = s.copyOf()
        val a = from.coerceIn(0, s.size); val b = to.coerceIn(a, s.size)
        val len = min(maxLen, b - a)
        for (i in 0 until len) out[a + i] = clip(out[a + i] * (i.toFloat() / len))
        return out
    }

    fun fadeOut(s: ShortArray, from: Int, to: Int, maxLen: Int): ShortArray {
        val out = s.copyOf()
        val a = from.coerceIn(0, s.size); val b = to.coerceIn(a, s.size)
        val len = min(maxLen, b - a)
        for (i in 0 until len) {
            val idx = b - len + i
            out[idx] = clip(out[idx] * (1f - (i + 1).toFloat() / len))
        }
        return out
    }

    private fun scale(s: ShortArray, from: Int, to: Int, k: Float): ShortArray {
        val out = s.copyOf()
        val a = from.coerceIn(0, s.size); val b = to.coerceIn(a, s.size)
        for (i in a until b) out[i] = clip(out[i] * k)
        return out
    }

    private fun clip(v: Float): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()

    /** Peak envelope (0..1) in [buckets] slices, used to draw the waveform. */
    fun envelope(s: ShortArray, buckets: Int): FloatArray {
        if (s.isEmpty() || buckets <= 0) return FloatArray(0)
        val n = min(buckets, s.size)
        val out = FloatArray(n)
        for (b in 0 until n) {
            val start = (b.toLong() * s.size / n).toInt()
            val end = ((b + 1).toLong() * s.size / n).toInt()
            var peak = 0
            for (i in start until end) peak = max(peak, abs(s[i].toInt()))
            out[b] = peak / 32768f
        }
        return out
    }
}

/** 16-bit PCM WAV files. */
object WavWriter {
    /** Streams a WAV file to [out] without building it in memory first. */
    fun write(out: java.io.OutputStream, samples: ShortArray, sampleRate: Int, channels: Int = 1) {
        val header = encode(ShortArray(0), sampleRate, channels)
        val dataLen = samples.size * 2
        fun i32(o: Int, v: Int) { header[o] = v.toByte(); header[o + 1] = (v shr 8).toByte(); header[o + 2] = (v shr 16).toByte(); header[o + 3] = (v shr 24).toByte() }
        i32(4, 36 + dataLen); i32(40, dataLen)
        out.write(header)
        val buf = ByteArray(64 * 1024)
        var i = 0
        while (i < samples.size) {
            val n = minOf(buf.size / 2, samples.size - i)
            for (k in 0 until n) {
                val v = samples[i + k].toInt()
                buf[2 * k] = v.toByte(); buf[2 * k + 1] = (v shr 8).toByte()
            }
            out.write(buf, 0, n * 2)
            i += n
        }
    }

    fun encode(samples: ShortArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val dataLen = samples.size * 2
        val out = ByteArray(44 + dataLen)
        fun str(o: Int, s: String) { for (i in s.indices) out[o + i] = s[i].code.toByte() }
        fun i32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        str(0, "RIFF"); i32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels)
        i32(24, sampleRate); i32(28, sampleRate * channels * 2); i16(32, channels * 2); i16(34, 16)
        str(36, "data"); i32(40, dataLen)
        var o = 44
        for (v in samples) { out[o] = v.toInt().toByte(); out[o + 1] = (v.toInt() shr 8).toByte(); o += 2 }
        return out
    }
}

object Adts {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun rateIndex(sampleRate: Int): Int = RATES.indexOf(sampleRate).let { if (it < 0) 4 else it }

    /** 7-byte ADTS header (AAC-LC, no CRC) for one raw AAC frame of [frameLen] bytes. */
    fun header(frameLen: Int, sampleRate: Int, channels: Int): ByteArray {
        val full = frameLen + 7
        val profile = 2 // AAC LC
        val sr = rateIndex(sampleRate)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((profile - 1) shl 6) or (sr shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (full shr 11)).toByte(),
            ((full and 0x7FF) shr 3).toByte(),
            (((full and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}

fun formatTime(samples: Int, rate: Int, tenths: Boolean = true): String {
    if (rate <= 0) return "0:00"
    val totalTenths = (samples.toLong() * 10 / rate).toInt()
    val secs = totalTenths / 10
    val m = secs / 60
    val s = secs % 60
    return if (tenths) "%d:%02d.%d".format(m, s, totalTenths % 10) else "%d:%02d".format(m, s)
}

package com.takeone.video

/**
 * Edit decision list for a recorded video: which pieces of the original recording are kept,
 * in order. Video is never re-encoded; on export the kept pieces are copied as-is.
 *
 * Compressed video can only *start* playing at a keyframe, so every kept piece must begin on one.
 * Trim and cut therefore snap the side of the selection that becomes a new start point.
 * Times are microseconds. "Timeline" time is time in the edited result; "source" time is time in
 * the original recording.
 */
data class Segment(val srcStartUs: Long, val srcEndUs: Long) {
    val lengthUs: Long get() = srcEndUs - srcStartUs
}

/** The result of an edit: the new EDL and the timeline range that was actually used after snapping. */
data class EditResult(val edl: Edl, val startUs: Long, val endUs: Long)

class Edl(val segments: List<Segment>, private val sourceKeyframesUs: LongArray) {

    val durationUs: Long = segments.sumOf { it.lengthUs }

    /** Timeline times where playback may begin: each segment start plus every keyframe inside a segment. */
    val keyframesUs: LongArray by lazy {
        val out = ArrayList<Long>()
        var offset = 0L
        for (seg in segments) {
            out.add(offset)
            for (k in sourceKeyframesUs) {
                if (k > seg.srcStartUs && k < seg.srcEndUs) out.add(offset + (k - seg.srcStartUs))
            }
            offset += seg.lengthUs
        }
        out.distinct().sorted().toLongArray()
    }

    /** Largest keyframe at or before [t]. */
    fun snapBack(t: Long): Long = keyframesUs.lastOrNull { it <= t } ?: 0L

    /** Smallest keyframe at or after [t], or the end of the timeline. */
    fun snapForward(t: Long): Long = keyframesUs.firstOrNull { it >= t } ?: durationUs

    /** Maps a timeline time to (segment index, source time). */
    fun toSource(t: Long): Pair<Int, Long> {
        var offset = 0L
        segments.forEachIndexed { i, seg ->
            if (t < offset + seg.lengthUs || i == segments.lastIndex) {
                return i to (seg.srcStartUs + (t - offset).coerceIn(0, seg.lengthUs))
            }
            offset += seg.lengthUs
        }
        return 0 to 0L
    }

    /** Source pieces covering timeline [a, b). */
    fun slice(a: Long, b: Long): List<Segment> {
        val out = ArrayList<Segment>()
        var offset = 0L
        for (seg in segments) {
            val segStart = offset
            val segEnd = offset + seg.lengthUs
            val s = maxOf(a, segStart)
            val e = minOf(b, segEnd)
            if (e > s) out.add(Segment(seg.srcStartUs + (s - segStart), seg.srcStartUs + (e - segStart)))
            offset = segEnd
        }
        return out
    }

    /** Keep only [a, b). The start snaps back to a keyframe; the end stays exact. */
    fun trim(a: Long, b: Long): EditResult? {
        val start = snapBack(a.coerceIn(0, durationUs))
        val end = b.coerceIn(start, durationUs)
        if (end - start <= 0) return null
        return EditResult(Edl(merge(slice(start, end)), sourceKeyframesUs), start, end)
    }

    /** Remove [a, b). The end snaps forward to a keyframe so what follows can start cleanly. */
    fun cut(a: Long, b: Long): EditResult? {
        val start = a.coerceIn(0, durationUs)
        val end = snapForward(b.coerceIn(start, durationUs))
        if (end <= start) return null
        if (start == 0L && end >= durationUs) return null // would delete everything
        val kept = slice(0, start) + slice(end, durationUs)
        if (kept.isEmpty()) return null
        return EditResult(Edl(merge(kept), sourceKeyframesUs), start, end)
    }

    private fun merge(list: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in list) {
            val last = out.lastOrNull()
            if (last != null && last.srcEndUs == s.srcStartUs) out[out.size - 1] = Segment(last.srcStartUs, s.srcEndUs)
            else out.add(s)
        }
        return out
    }

    companion object {
        fun whole(durationUs: Long, keyframesUs: LongArray) =
            Edl(listOf(Segment(0, durationUs)), keyframesUs)
    }
}

/** Lines microphone audio up with the video: positive offset = audio started after video. */
fun alignAudio(samples: ShortArray, sampleRate: Int, audioMinusVideoNs: Long, videoDurationUs: Long): ShortArray {
    val target = (videoDurationUs * sampleRate / 1_000_000L).toInt()
    val shift = (audioMinusVideoNs * sampleRate / 1_000_000_000L).toInt()
    val out = ShortArray(target)
    // out[i] = samples[i - shift]
    for (i in 0 until target) {
        val j = i - shift
        if (j in samples.indices) out[i] = samples[j]
    }
    return out
}

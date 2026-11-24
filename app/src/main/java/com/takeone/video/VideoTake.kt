package com.takeone.video

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

/** A finished camera recording (video only, in the app's cache), plus what the editor needs to know about it. */
class VideoTake(
    val file: File,
    val durationUs: Long,
    val keyframesUs: LongArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    /** Timestamp of the first frame inside the file; editor times are relative to it. */
    val baseUs: Long,
) {
    /** Grabs single frames for the editor's preview. Not thread-safe: use from one thread. */
    private var retriever: MediaMetadataRetriever? = null

    fun frameAt(sourceUs: Long, exact: Boolean, maxWidth: Int = 720): Bitmap? = try {
        val r = retriever ?: MediaMetadataRetriever().also { mmr ->
            mmr.setDataSource(file.absolutePath)
            retriever = mmr
        }
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
        val (w, h) = if (rotation % 180 == 0) width to height else height to width
        if (Build.VERSION.SDK_INT >= 27 && w > maxWidth) {
            r.getScaledFrameAtTime(sourceUs + baseUs, option, maxWidth, (h.toLong() * maxWidth / w).toInt())
        } else {
            r.getFrameAtTime(sourceUs + baseUs, option)
        }
    } catch (e: Exception) {
        null
    }

    fun release() {
        try { retriever?.release() } catch (_: Exception) {}
        retriever = null
        file.delete()
    }

    companion object {
        /** Reads duration, keyframe positions, size and rotation from a finished recording. */
        fun probe(file: File): VideoTake? {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.absolutePath)
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return null
                val format = ex.getTrackFormat(track)
                ex.selectTrack(track)
                val keys = ArrayList<Long>()
                var last = 0L
                while (true) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keys.add(t)
                    last = maxOf(last, t)
                    if (!ex.advance()) break
                }
                if (keys.isEmpty()) return null
                val first = keys.first()
                val frameUs = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) 1_000_000L / format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1) else 33_333L
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else last + frameUs

                val mmr = MediaMetadataRetriever()
                val rotation = try {
                    mmr.setDataSource(file.absolutePath)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } catch (e: Exception) { 0 } finally { mmr.release() }

                return VideoTake(
                    file = file,
                    durationUs = duration - first,
                    keyframesUs = keys.map { it - first }.toLongArray(),
                    width = format.getInteger(MediaFormat.KEY_WIDTH),
                    height = format.getInteger(MediaFormat.KEY_HEIGHT),
                    rotation = rotation,
                    baseUs = first,
                )
            } catch (e: Exception) {
                android.util.Log.e("TakeOneCamera", "probe failed", e)
                return null
            } finally {
                ex.release()
            }
        }
    }
}

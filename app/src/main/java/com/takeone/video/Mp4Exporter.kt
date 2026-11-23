package com.takeone.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.RequiresApi
import com.takeone.audio.AacTrack
import java.nio.ByteBuffer

/**
 * Builds the final .mp4 in a RAM-only file:
 *  - video: the kept pieces of the camera recording, copied without re-encoding (no quality loss)
 *  - audio: the edited microphone track, freshly encoded to AAC
 * Either track may be absent (audio-only MP4, or video with no sound).
 */
@RequiresApi(30)
object Mp4Exporter {

    fun export(
        video: VideoTake?,
        edl: Edl?,
        audio: AacTrack?,
        onProgress: (Float) -> Unit = {},
    ): MemFd {
        require(video != null || audio != null) { "Nothing to export" }
        val out = MemFd.create("takeone-export")
        val muxer = MediaMuxer(out.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var extractor: MediaExtractor? = null
        var readerFd: android.os.ParcelFileDescriptor? = null
        try {
            var videoTrack = -1
            var audioTrack = -1
            var srcTrack = -1
            var bufSize = 1 shl 20

            if (video != null && edl != null) {
                readerFd = video.file.openReader()
                val ex = MediaExtractor().also { extractor = it }
                ex.setDataSource(readerFd.fileDescriptor)
                srcTrack = (0 until ex.trackCount).first {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                val fmt = ex.getTrackFormat(srcTrack)
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufSize = maxOf(bufSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                ex.selectTrack(srcTrack)
                videoTrack = muxer.addTrack(fmt)
                muxer.setOrientationHint(video.rotation)
            }
            if (audio != null) audioTrack = muxer.addTrack(audio.format)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(bufSize)
            val audioBuffer = ByteBuffer.allocateDirect(8192)
            val info = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            var audioIdx = 0
            val frames = audio?.frames ?: emptyList()
            val totalUs = (edl?.durationUs ?: 0L).coerceAtLeast(1)

            fun writeAudioUntil(limitUs: Long) {
                while (audioIdx < frames.size && frames[audioIdx].ptsUs <= limitUs) {
                    val f = frames[audioIdx++]
                    val b = if (f.data.size <= audioBuffer.capacity()) audioBuffer else ByteBuffer.allocateDirect(f.data.size)
                    b.clear(); b.put(f.data); b.flip()
                    audioInfo.set(0, f.data.size, f.ptsUs, 0)
                    muxer.writeSampleData(audioTrack, b, audioInfo)
                }
            }

            val ex = extractor
            if (ex != null && edl != null && video != null) {
                var timelineOffset = 0L
                for (seg in edl.segments) {
                    val srcStart = seg.srcStartUs + video.baseUs
                    val srcEnd = seg.srcEndUs + video.baseUs
                    ex.seekTo(srcStart, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    // Segments always start on a keyframe, so this lands exactly at the segment start.
                    val landed = ex.sampleTime.coerceAtLeast(0)
                    while (true) {
                        val t = ex.sampleTime
                        if (t < 0 || t >= srcEnd) break
                        buffer.clear()
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0) break
                        val outPts = timelineOffset + (t - landed)
                        val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        // Keep the file interleaved: audio up to this point goes first.
                        if (audioTrack >= 0) writeAudioUntil(outPts)
                        buffer.position(0); buffer.limit(size)
                        info.set(0, size, outPts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(videoTrack, buffer, info)
                        onProgress(outPts.toFloat() / totalUs)
                        if (!ex.advance()) break
                    }
                    timelineOffset += seg.lengthUs
                }
            }
            // Remaining audio (or all of it, for audio-only).
            if (audioTrack >= 0) writeAudioUntil(Long.MAX_VALUE)

            muxer.stop()
            onProgress(1f)
            return out
        } catch (e: Exception) {
            out.close()
            throw e
        } finally {
            try { muxer.release() } catch (_: Exception) {}
            extractor?.release()
            try { readerFd?.close() } catch (_: Exception) {}
        }
    }
}

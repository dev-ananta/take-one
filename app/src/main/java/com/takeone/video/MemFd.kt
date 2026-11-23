package com.takeone.video

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.annotation.RequiresApi
import java.io.File

/**
 * A RAM-only file (Linux memfd). It behaves like a normal seekable file for MediaMuxer,
 * CameraX and MediaExtractor, but it lives in memory, never touches storage, and disappears
 * when it is closed or the app's process ends.
 */
@RequiresApi(30)
class MemFd private constructor(val pfd: ParcelFileDescriptor) : AutoCloseable {

    val size: Long get() = try { Os.fstat(pfd.fileDescriptor).st_size } catch (e: Exception) { 0L }

    /**
     * Opens an independent read handle (its own file position) so several readers —
     * the frame previewer, the exporter, the share provider — never disturb each other.
     */
    fun openReader(): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File("/proc/self/fd/${pfd.fd}"), ParcelFileDescriptor.MODE_READ_ONLY)

    /** Reads up to [len] bytes at [offset] without moving any file position. */
    fun pread(offset: Long, dst: ByteArray, len: Int): Int =
        Os.pread(pfd.fileDescriptor, dst, 0, len, offset)

    override fun close() { try { pfd.close() } catch (_: Exception) {} }

    companion object {
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 30

        fun create(name: String): MemFd {
            val raw = Os.memfd_create(name, 0)
            val pfd = ParcelFileDescriptor.dup(raw)
            Os.close(raw)
            return MemFd(pfd)
        }
    }
}

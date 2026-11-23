package com.takeone

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves an exported recording to other apps *straight from RAM*.
 *
 * Android's share sheet needs a content:// Uri. Instead of writing a temp file, this provider
 * answers reads through [StorageManager.openProxyFileDescriptor], a virtual, seekable file
 * descriptor whose bytes come from the in-memory array. Nothing is ever written to storage,
 * and when the app's process ends the data is gone.
 */
class MemoryAudioProvider : ContentProvider() {

    /** Something shareable held in memory: a byte array or a RAM-only file (memfd). */
    class Blob(
        val size: Long,
        val mime: String,
        val displayName: String,
        val read: (offset: Long, dst: ByteArray, len: Int) -> Int,
    )

    companion object {
        private val blobs = ConcurrentHashMap<String, Blob>()
        private val ioThread by lazy { HandlerThread("TakeOne-share").apply { start() } }

        fun authority(context: Context) = "${context.packageName}.memory"

        /** Publishes bytes and returns a shareable Uri. Only the latest export is kept. */
        fun publish(context: Context, bytes: ByteArray, mime: String, displayName: String): Uri =
            publish(context, Blob(bytes.size.toLong(), mime, displayName) { off, dst, len ->
                val n = minOf(len.toLong(), bytes.size - off).toInt()
                System.arraycopy(bytes, off.toInt(), dst, 0, n); n
            })

        /** Shares a RAM-only file without copying it. The caller keeps it open while sharing. */
        @androidx.annotation.RequiresApi(30)
        fun publish(context: Context, file: com.takeone.video.MemFd, mime: String, displayName: String): Uri =
            publish(context, Blob(file.size, mime, displayName) { off, dst, len -> file.pread(off, dst, len) })

        private fun publish(context: Context, blob: Blob): Uri {
            val displayName = blob.displayName
            blobs.clear()
            val token = java.util.UUID.randomUUID().toString()
            blobs[token] = blob
            return Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(token)
                .appendPath(displayName)
                .build()
        }

        fun clear() = blobs.clear()
    }

    private fun blob(uri: Uri): Blob? = uri.pathSegments.firstOrNull()?.let { blobs[it] }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = blob(uri)?.mime

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val b = blob(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols.toTypedArray(), 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) b.size else b.displayName })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val b = blob(uri) ?: throw java.io.FileNotFoundException("Recording is no longer in memory")
        if (mode.contains('w')) throw SecurityException("Read only")
        return try {
            val sm = context!!.getSystemService(StorageManager::class.java)
            sm.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = b.size
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        if (offset < 0) throw ErrnoException("onRead", OsConstants.EINVAL)
                        if (offset >= b.size) return 0
                        val n = minOf(size.toLong(), b.size - offset).toInt()
                        return b.read(offset, data, n)
                    }
                    override fun onRelease() {}
                },
                Handler(ioThread.looper),
            )
        } catch (e: Exception) {
            // Fallback: stream through a pipe (still memory-only, just not seekable).
            val pipe = ParcelFileDescriptor.createReliablePipe()
            Thread {
                try {
                    FileOutputStream(pipe[1].fileDescriptor).use { out ->
                        val chunk = ByteArray(256 * 1024)
                        var off = 0L
                        while (off < b.size) {
                            val n = b.read(off, chunk, minOf(chunk.size.toLong(), b.size - off).toInt())
                            if (n <= 0) break
                            out.write(chunk, 0, n); off += n
                        }
                    }
                    pipe[1].close()
                } catch (err: Exception) {
                    try { pipe[1].closeWithError(err.message) } catch (_: Exception) {}
                }
            }.start()
            pipe[0]
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

package com.takeone

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Saves finished takes where you can find them:
 *   video (.mp4)  → Movies/TakeOne   (shows in Gallery / Photos)
 *   audio (.wav, .aac, sound-only .mp4) → Music/TakeOne
 * Returns a content:// Uri that can also be shared.
 */
object MediaSaver {
    private const val FOLDER = "TakeOne"

    class Saved(val uri: Uri, val displayName: String, val folder: String)

    fun saveVideo(context: Context, displayName: String, write: (OutputStream) -> Unit): Saved =
        save(context, displayName, "video/mp4", video = true, write)

    fun saveAudio(context: Context, displayName: String, mime: String, write: (OutputStream) -> Unit): Saved =
        save(context, displayName, mime, video = false, write)

    fun copyFrom(file: File): (OutputStream) -> Unit = { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }

    private fun save(context: Context, name: String, mime: String, video: Boolean, write: (OutputStream) -> Unit): Saved {
        val resolver = context.contentResolver
        val topDir = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        val folder = "$topDir/$FOLDER"

        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Couldn't create $name in $folder")
            try {
                resolver.openOutputStream(uri)!!.use { write(it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return Saved(uri, name, folder)
        }

        // Android 8–9: write the file directly (needs storage permission), then register it.
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(topDir), FOLDER).apply { mkdirs() }
        var file = File(dir, name)
        var n = 2
        while (file.exists()) { file = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.')); n++ }
        file.outputStream().use { write(it) }
        @Suppress("DEPRECATION")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return Saved(uri ?: Uri.fromFile(file), file.name, folder)
    }
}

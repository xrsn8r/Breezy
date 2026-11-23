package dev.breezy.browser.downloads

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Where downloads are written: Download/Breezy. On Android 10+ through MediaStore (no storage
 * permission; the item stays "pending" and hidden until complete), on older versions as a plain
 * file in the public Downloads folder.
 */
object Storage {
    private const val FOLDER = "Breezy"

    /** An open, random-access writable file. Close both when done. */
    class Handle(val channel: FileChannel, private val pfd: ParcelFileDescriptor?, private val raf: RandomAccessFile?) {
        fun close() {
            runCatching { channel.close() }
            runCatching { pfd?.close() }
            runCatching { raf?.close() }
        }
    }

    fun create(context: Context, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create the file")
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "$base ($n)$ext").also { n++ }
        file.createNewFile()
        return Uri.fromFile(file)
    }

    fun open(context: Context, uri: Uri): Handle = if (uri.scheme == "file") {
        val raf = RandomAccessFile(uri.path!!, "rw")
        Handle(raf.channel, null, raf)
    } else {
        // "rw" keeps existing bytes (needed to resume); positional writes go through the channel.
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("Couldn't open the file")
        Handle(java.io.FileOutputStream(pfd.fileDescriptor).channel, pfd, null)
    }

    fun truncate(context: Context, uri: Uri) {
        runCatching { open(context, uri).also { it.channel.truncate(0) }.close() }
    }

    /** Make the finished file visible to other apps. */
    fun finish(context: Context, uri: Uri) {
        if (uri.scheme == "file") {
            MediaScannerConnection.scanFile(context, arrayOf(uri.path), null, null)
        } else if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    fun delete(context: Context, uri: Uri) {
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).delete() else context.contentResolver.delete(uri, null, null)
        }
    }

    /** A URI other apps can open (file:// URIs aren't shareable since Android 7). */
    fun shareable(context: Context, uri: Uri): Uri = if (uri.scheme == "file") {
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", File(uri.path!!))
    } else uri
}

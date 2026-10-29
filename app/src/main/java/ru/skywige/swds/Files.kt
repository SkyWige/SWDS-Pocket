package ru.skywige.swds

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Сохранение в «Загрузки/SWDS» и сведения о файлах по content://. */
object Files {
    const val SUBDIR = "SWDS"

    fun mimeOf(name: String, fallback: String = "application/octet-stream"): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fallback
    }

    data class Info(val name: String, val size: Long, val mime: String, val mtime: Long)

    fun info(ctx: Context, uri: Uri): Info {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) { }
        val mime = ctx.contentResolver.getType(uri) ?: mimeOf(name)
        return Info(name, size, mime, 0L)
    }

    /** Записать поток в «Загрузки/SWDS». Возвращает Uri, который можно открыть. */
    fun saveToDownloads(ctx: Context, name: String, mime: String, input: InputStream, size: Long,
                        onProgress: ((Long, Long) -> Unit)? = null): Uri {
        val type = if (mime.isBlank() || mime == "application/octet-stream") mimeOf(name) else mime.substringBefore(';')
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw ApiException("Не удалось создать файл в Загрузках")
            try {
                cr.openOutputStream(uri)?.use { copy(input, it, size, onProgress) } ?: throw ApiException("Нет доступа к Загрузкам")
                cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { cr.delete(uri, null, null) }
                throw e
            }
            return uri
        }
        val legacy = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val dir = if (legacy) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        else File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        dir.mkdirs()
        var f = File(dir, name)
        var i = 2
        while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + " ($i)" + (if ('.' in name) "." + name.substringAfterLast('.') else "")); i++ }
        f.outputStream().use { copy(input, it, size, onProgress) }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    fun copy(input: InputStream, out: OutputStream, size: Long, onProgress: ((Long, Long) -> Unit)?) {
        val buf = ByteArray(128 * 1024)
        var done = 0L
        var last = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (onProgress != null && done - last > 512 * 1024) { onProgress(done, size); last = done }
        }
        onProgress?.invoke(done, size)
    }

    fun viewIntent(uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.substringBefore(';').ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun human(n: Long): String = when {
        n < 0 -> ""
        n < 1024 -> "$n Б"
        n < 1024 * 1024 -> "${n / 1024} КБ"
        n < 1024L * 1024 * 1024 -> String.format("%.1f МБ", n / 1048576.0)
        else -> String.format("%.2f ГБ", n / 1073741824.0)
    }
}

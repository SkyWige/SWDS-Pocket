package ru.skywige.swds

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Музыка и видео медиатеки на телефоне.
 *
 *  • «📥 На телефон» в медиатеке → файл целиком скачивается в память приложения (уведомление с прогрессом);
 *  • сыгранные по сети треки сами сохраняются (кэш с лимитом, старое удаляется первым) — следующий раз без сети и без заиканий;
 *  • плеер медиатеки, запрашивая /modules/media_manager/api/stream/<id>, получает файл с телефона, если он скачан
 *    (с поддержкой перемотки — Range): никакой передачи по Wi-Fi, никаких шумов от недогруженного буфера;
 *  • скачанное видно в офлайн-режиме саб-сервера (вкладка «🎵 На телефоне»).
 */
object MediaOffline {
    private const val NOTE_ID = 4310
    private val dl = Executors.newSingleThreadExecutor()
    private val queued = HashSet<Long>()
    private val STREAM = Regex("/modules/media_manager/api/stream/(\\d+)")

    private fun dir(ctx: Context) = File(ctx.filesDir, "media").apply { mkdirs() }

    fun cachedIds(ctx: Context): String = LocalStore.get(ctx).mediaIds().toString()

    fun save(ctx: Context, json: String, auto: Boolean = false) {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        val app = ctx.applicationContext
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            if (id <= 0 || LocalStore.get(app).mediaPath(id)?.let { File(it).isFile } == true) continue
            val fresh = synchronized(queued) { queued.add(id) }
            if (!fresh) continue
            dl.execute { runCatching { fetch(app, o, auto) }; synchronized(queued) { queued.remove(id) } }
        }
    }

    fun remove(ctx: Context, id: Long) {
        val st = LocalStore.get(ctx)
        st.mediaPath(id)?.let { File(it).delete() }
        st.mediaDelete(id)
    }

    private fun fetch(ctx: Context, o: JSONObject, auto: Boolean) {
        val id = o.optLong("id")
        val ext = o.optString("filename").substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 } ?: "bin"
        val f = File(dir(ctx), "$id.$ext")
        val tmp = File(dir(ctx), "$id.part")
        val title = o.optString("title").ifEmpty { o.optString("filename") }
        val nb = NotificationCompat.Builder(ctx, Notifier.CH_INFO).setSmallIcon(R.drawable.ic_stat).setContentTitle("📥 $title")
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
        try {
            Api.withServer(ctx, "/modules/media_manager/api/stream/$id", "GET", 10000, 60000) { conn ->
                conn.setRequestProperty("Accept", "*/*")
                if (conn.responseCode !in 200..299) throw ApiException("HTTP ${conn.responseCode}", conn.responseCode)
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (!auto && pct != lastPct && pct % 5 == 0) {
                                lastPct = pct
                                Notifier.post(ctx, NOTE_ID, nb.setProgress(100, pct.coerceAtLeast(0), total <= 0).setContentText("$pct%").build())
                            }
                        }
                    }
                }
                conn.disconnect()
            }
            tmp.renameTo(f)
            LocalStore.get(ctx).mediaPut(o, f.absolutePath, f.length())
            if (!auto) Notifier.post(ctx, NOTE_ID, NotificationCompat.Builder(ctx, Notifier.CH_INFO).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("✅ На телефоне: $title").setContentText("Играет без сети").setAutoCancel(true).build())
            trim(ctx)
        } catch (e: Exception) {
            tmp.delete()
            if (!auto) Notifier.post(ctx, NOTE_ID, NotificationCompat.Builder(ctx, Notifier.CH_INFO).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("⚠ Не скачалось: $title").setContentText(e.message ?: "нет связи").setAutoCancel(true).build())
        }
    }

    /** Лимит места: удаляем самое старое (скачанное вручную тоже — но только когда превышен лимит). */
    private fun trim(ctx: Context) {
        val limit = Prefs(ctx).mediaCacheMb.toLong() * 1024 * 1024
        val files = dir(ctx).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (f in files) {
            if (size <= limit) break
            size -= f.length()
            f.nameWithoutExtension.toLongOrNull()?.let { remove(ctx, it) } ?: f.delete()
        }
    }

    /** WebView запросил поток медиатеки: отдать файл с телефона (если есть) или — для музыки — запомнить, чтобы сохранить. */
    fun intercept(ctx: Context, req: WebResourceRequest): WebResourceResponse? {
        val url = req.url?.toString() ?: return null
        val m = STREAM.find(url) ?: return null
        if (url.contains("download=1")) return null
        val id = m.groupValues[1].toLongOrNull() ?: return null
        val path = LocalStore.get(ctx).mediaPath(id)
        val f = path?.let { File(it) }
        if (f == null || !f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        val len = f.length()
        val mime = when (f.extension.lowercase()) {
            "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"; "ogg", "oga", "opus" -> "audio/ogg"; "wav" -> "audio/wav"
            "mp4", "m4v", "mov" -> "video/mp4"; "webm", "mkv" -> "video/webm"; else -> "application/octet-stream"
        }
        val range = req.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", true) }?.value
        var start = 0L
        var end = len - 1
        if (range != null && range.startsWith("bytes=")) {
            val a = range.removePrefix("bytes=").split("-", limit = 2)
            if (a[0].isNotEmpty()) { start = a[0].toLong(); if (a.getOrNull(1).orEmpty().isNotEmpty()) end = a[1].toLong() }
            else if (a.getOrNull(1).orEmpty().isNotEmpty()) start = len - a[1].toLong()
            end = end.coerceAtMost(len - 1)
            val inp: InputStream = FileInputStream(f).apply { skip(start) }
            val body = LimitedStream(inp, end - start + 1)
            return WebResourceResponse(mime, null, 206, "Partial Content", mapOf("Content-Range" to "bytes $start-$end/$len",
                "Content-Length" to (end - start + 1).toString(), "Accept-Ranges" to "bytes", "Access-Control-Allow-Origin" to "*"), body)
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Content-Length" to len.toString(), "Accept-Ranges" to "bytes"), FileInputStream(f))
    }

    private class LimitedStream(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val b = inner.read(); if (b >= 0) left--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
        }
        override fun close() = inner.close()
    }
}

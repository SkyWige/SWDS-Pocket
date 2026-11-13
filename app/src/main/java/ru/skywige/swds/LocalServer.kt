package ru.skywige.swds

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Локальный HTTP-сервер саб-сервера SWDS на телефоне (127.0.0.1, по желанию — для всей домашней сети по токену).
 *
 *   /app/…                 — офлайн-приложение (обновляется с основного сервера, есть встроенная копия)
 *   /modules/<id>/…        — свои офлайн-модули из пакета
 *   /local/api/state       — состояние саб-сервера
 *   /local/api/records/<набор>[/<uid>]  — GET список · POST {uid?, data} · DELETE
 *   /local/api/sync        — POST: синхронизироваться сейчас
 *   /local/api/kv/<модуль>[/<ключ>]     — хранилище своих модулей
 *   /local/api/media       — скачанные медиа; /local/media/<id> — файл (с Range — перемотка)
 */
object LocalServer {
    @Volatile private var socket: ServerSocket? = null
    @Volatile var port = 0
    private val pool = Executors.newCachedThreadPool()

    fun url(ctx: Context): String = "http://127.0.0.1:${if (port > 0) port else Prefs(ctx).subPort}"

    @Synchronized
    fun start(ctx: Context) {
        val p = Prefs(ctx)
        val want = p.subPort
        val lan = p.subLan
        val cur = socket
        if (cur != null && !cur.isClosed && port == want && (cur.inetAddress.isAnyLocalAddress == lan)) return
        stop()
        val app = ctx.applicationContext
        for (tryPort in listOf(want, want + 1, want + 2)) {
            try {
                val s = ServerSocket(tryPort, 50, if (lan) null else InetAddress.getByName("127.0.0.1"))
                socket = s
                port = tryPort
                Thread({ loop(app, s) }, "swds-local").apply { isDaemon = true }.start()
                return
            } catch (_: Exception) { }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun loop(ctx: Context, s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: Exception) { break }
            pool.execute { runCatching { handle(ctx, c) }; runCatching { c.close() } }
        }
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16384) return null
        }
    }

    private fun parse(sock: Socket): Req? {
        val inp = BufferedInputStream(sock.getInputStream())
        val first = readLine(inp) ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len.coerceAtMost(20_000_000))
        var off = 0
        while (off < body.size) { val n = inp.read(body, off, body.size - off); if (n < 0) break; off += n }
        val full = parts[1]
        val qi = full.indexOf('?')
        val path = URLDecoder.decode(if (qi >= 0) full.substring(0, qi) else full, "UTF-8")
        val q = HashMap<String, String>()
        if (qi >= 0) full.substring(qi + 1).split("&").forEach { kv ->
            val e = kv.indexOf('='); if (e > 0) q[URLDecoder.decode(kv.substring(0, e), "UTF-8")] = URLDecoder.decode(kv.substring(e + 1), "UTF-8")
        }
        return Req(parts[0].uppercase(), path, q, headers, body)
    }

    private fun send(out: OutputStream, code: Int, type: String, data: ByteArray, extra: Map<String, String> = emptyMap()) {
        val status = mapOf(200 to "OK", 204 to "No Content", 206 to "Partial Content", 302 to "Found", 400 to "Bad Request",
            401 to "Unauthorized", 404 to "Not Found", 416 to "Range Not Satisfiable", 500 to "Error")[code] ?: "OK"
        val h = StringBuilder("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\n" +
                "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n")
        extra.forEach { (k, v) -> h.append("$k: $v\r\n") }
        h.append("\r\n")
        out.write(h.toString().toByteArray(Charsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun json(out: OutputStream, o: Any, code: Int = 200) = send(out, code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"; "js" -> "application/javascript; charset=utf-8"; "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"; "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"; "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"; "mp4", "m4v" -> "video/mp4"; "webm", "mkv" -> "video/webm"; "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun handle(ctx: Context, sock: Socket) {
        sock.soTimeout = 30000
        val r = parse(sock) ?: return
        val out = sock.getOutputStream()
        val p = Prefs(ctx)
        // из сети (не с самого телефона) — только по токену саб-сервера
        if (!sock.inetAddress.isLoopbackAddress) {
            val tok = r.query["token"] ?: r.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: ""
            if (!p.subLan || tok != p.subToken) { json(out, JSONObject().put("ok", false).put("error", "Нужен токен саб-сервера"), 401); return }
        }
        if (r.method == "OPTIONS") { send(out, 204, "text/plain", ByteArray(0), mapOf("Access-Control-Allow-Methods" to "GET,POST,DELETE",
            "Access-Control-Allow-Headers" to "Content-Type, Authorization")); return }
        val path = r.path
        try {
            when {
                path == "/" || path == "/app" || path == "/app/" -> send(out, 302, "text/plain", ByteArray(0), mapOf("Location" to "/app/index.html"))
                path.startsWith("/app/") -> static(ctx, out, "app/" + path.removePrefix("/app/"))
                path.startsWith("/modules/") -> static(ctx, out, "modules/" + path.removePrefix("/modules/"))
                path.startsWith("/local/media/") -> media(ctx, out, r, path.removePrefix("/local/media/").toLongOrNull() ?: -1)
                path.startsWith("/local/api/") -> api(ctx, out, r, path.removePrefix("/local/api/").trim('/').split("/"))
                else -> json(out, JSONObject().put("ok", false).put("error", "not found"), 404)
            }
        } catch (e: Exception) {
            runCatching { json(out, JSONObject().put("ok", false).put("error", e.message ?: "ошибка"), 500) }
        }
    }

    /** Файлы офлайн-приложения: сначала из пакета сервера, иначе — встроенная копия в APK. */
    private fun static(ctx: Context, out: OutputStream, rel: String) {
        if (rel.contains("..")) { json(out, JSONObject().put("ok", false), 400); return }
        val f = File(ctx.filesDir, "offline/$rel")
        if (f.isFile) { send(out, 200, mime(f.name), f.readBytes()); return }
        if (rel.startsWith("app/")) {
            val bytes = runCatching { ctx.assets.open("offline/" + rel.removePrefix("app/")).use { it.readBytes() } }.getOrNull()
            if (bytes != null) { send(out, 200, mime(rel), bytes); return }
        }
        send(out, 404, "text/plain", "not found".toByteArray())
    }

    private fun media(ctx: Context, out: OutputStream, r: Req, id: Long) {
        val path = LocalStore.get(ctx).mediaPath(id)
        val f = path?.let { File(it) }
        if (f == null || !f.isFile) { send(out, 404, "text/plain", "not found".toByteArray()); return }
        val len = f.length()
        val range = r.headers["range"]
        var start = 0L
        var end = len - 1
        var code = 200
        if (range != null && range.startsWith("bytes=")) {
            val (a, b) = range.removePrefix("bytes=").split("-", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = b.toLong() } else if (b.isNotEmpty()) start = len - b.toLong()
            end = end.coerceAtMost(len - 1)
            if (start > end || start >= len) { send(out, 416, "text/plain", ByteArray(0), mapOf("Content-Range" to "bytes */$len")); return }
            code = 206
        }
        val n = end - start + 1
        val head = StringBuilder("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\nContent-Type: ${mime(f.name)}\r\n" +
                "Content-Length: $n\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
        if (code == 206) head.append("Content-Range: bytes $start-$end/$len\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = n
            while (left > 0) {
                val k = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (k < 0) break
                out.write(buf, 0, k)
                left -= k
            }
        }
        out.flush()
    }

    private fun api(ctx: Context, out: OutputStream, r: Req, seg: List<String>) {
        val st = LocalStore.get(ctx)
        val p = Prefs(ctx)
        val body = runCatching { JSONObject(String(r.body, Charsets.UTF_8).ifBlank { "{}" }) }.getOrDefault(JSONObject())
        when (seg.firstOrNull()) {
            "state" -> json(out, SubSync.state(ctx))
            "sync" -> json(out, SubSync.sync(ctx))
            "records" -> {
                val ds = seg.getOrNull(1) ?: return json(out, JSONObject().put("ok", false).put("error", "набор?"), 400)
                if (ds !in SubSync.DATASETS) return json(out, JSONObject().put("ok", false).put("error", "нет набора $ds"), 404)
                when (r.method) {
                    "GET" -> json(out, JSONObject().put("ok", true).put("records", st.list(ds)))
                    "POST" -> {
                        val data = body.optJSONObject("data") ?: return json(out, JSONObject().put("ok", false).put("error", "data?"), 400)
                        val uid = st.upsertLocal(ds, body.optString("uid").takeIf { it.isNotEmpty() && it != "null" }, data, p.subNodeId)
                        SubKick.soon(ctx)
                        json(out, JSONObject().put("ok", true).put("uid", uid))
                    }
                    "DELETE" -> { seg.getOrNull(2)?.let { st.deleteLocal(ds, it) }; SubKick.soon(ctx); json(out, JSONObject().put("ok", true)) }
                    else -> json(out, JSONObject().put("ok", false), 400)
                }
            }
            "kv" -> {
                val mod = seg.getOrNull(1) ?: return json(out, JSONObject().put("ok", false), 400)
                val key = seg.getOrNull(2)
                when {
                    key == null -> json(out, JSONObject().put("ok", true).put("keys", st.kvKeys(mod)))
                    r.method == "GET" -> json(out, JSONObject().put("ok", true).put("value", st.kvGet(mod, key)?.let { runCatching { JSONObject(it) }.getOrNull() ?: runCatching { JSONArray(it) }.getOrNull() ?: it } ?: JSONObject.NULL))
                    r.method == "DELETE" -> { st.kvSet(mod, key, null); json(out, JSONObject().put("ok", true)) }
                    else -> { st.kvSet(mod, key, body.opt("value")?.toString()); json(out, JSONObject().put("ok", true)) }
                }
            }
            "media" -> json(out, JSONObject().put("ok", true).put("items", st.mediaList()))
            else -> json(out, JSONObject().put("ok", false).put("error", "не найдено"), 404)
        }
    }
}

/** Правка в офлайн-режиме — попробовать отправить через пару секунд (если связь есть). */
object SubKick {
    @Volatile private var pending = false
    fun soon(ctx: Context) {
        if (pending) return
        pending = true
        val app = ctx.applicationContext
        Thread {
            Thread.sleep(4000)
            pending = false
            runCatching { SubSync.sync(app) }
        }.start()
    }
}

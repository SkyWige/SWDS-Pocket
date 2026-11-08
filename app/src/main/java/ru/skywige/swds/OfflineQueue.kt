package ru.skywige.swds

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Офлайн-очередь: команды, тексты и файлы, отправленные без связи с SWDS, ждут здесь
 * и уходят сами, когда сервер снова доступен (SyncWorker «queue», переподключение потока).
 */
object OfflineQueue {
    private val lock = Any()
    /** Одна отправка за раз: её запускают и служба связи, и фоновая синхронизация — иначе команда выполнится дважды. */
    private val flushLock = java.util.concurrent.locks.ReentrantLock()
    private fun file(ctx: Context) = File(ctx.filesDir, "offline_queue.jsonl")
    private fun dir(ctx: Context) = File(ctx.filesDir, "offline_files").also { it.mkdirs() }

    /** Ошибка «нет связи» (а не ответ сервера с ошибкой) — значит, стоит повторить позже. */
    fun isOffline(e: Throwable?): Boolean = e is ApiException && e.status == 0 && !(e.message ?: "").contains("не привязан")

    /** Сервер есть, но сейчас не может принять запись (модуль перезапускается, ошибка 5xx). */
    fun isServerTrouble(e: Throwable?): Boolean = e is ApiException && e.status >= 500

    fun items(ctx: Context): List<JSONObject> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun count(ctx: Context): Int = items(ctx).size

    private fun save(ctx: Context, list: List<JSONObject>) = synchronized(lock) {
        val f = file(ctx)
        if (list.isEmpty()) f.delete() else f.writeText(list.joinToString("\n", postfix = "\n") { it.toString() })
    }

    fun add(ctx: Context, item: JSONObject) {
        synchronized(lock) { file(ctx).appendText(item.put("queued", System.currentTimeMillis()).toString() + "\n") }
        SyncWorker.now(ctx, "queue")
    }

    fun addCommand(ctx: Context, command: String, args: JSONObject, title: String = command) =
        add(ctx, JSONObject().put("type", "cmd").put("command", command).put("args", args).put("title", title))

    fun addText(ctx: Context, text: String, action: String) =
        add(ctx, JSONObject().put("type", "text").put("text", text).put("action", action).put("title", text.take(60)))

    const val QUICK_PATH = "/modules/mobile/api/quick"

    /** Быстрая запись (заметка, трата). В body уже есть client_id: повторная отправка не создаст дубль. */
    fun addQuick(ctx: Context, body: JSONObject, title: String) =
        add(ctx, JSONObject().put("type", "quick").put("body", body).put("title", title))

    /**
     * Команда из офлайн-экрана (задача, заметка, привычка…): у неё свой client_id, сервер не выполнит её дважды,
     * даже если телефон не дождался ответа и отправил повторно.
     */
    fun addExec(ctx: Context, command: String, args: JSONObject, title: String, clientId: String) =
        add(ctx, JSONObject().put("type", "exec").put("command", command).put("args", args).put("title", title)
            .put("client_id", clientId))

    /** Точки местоположения, собранные без связи (уходят пачкой в «Геолокацию»). */
    fun addGeo(ctx: Context, point: JSONObject) {
        synchronized(lock) {
            val f = File(ctx.filesDir, "geo_points.jsonl")
            f.appendText(point.toString() + "\n")
            // не копим бесконечно: последние ~5000 точек
            if (f.length() > 600_000) {
                runCatching { f.writeText(f.readLines().takeLast(5000).joinToString("\n", postfix = "\n")) }
            }
        }
    }

    fun geoCount(ctx: Context): Int = synchronized(lock) {
        val f = File(ctx.filesDir, "geo_points.jsonl")
        if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
    }

    fun flushGeo(ctx: Context): Int {
        val f = File(ctx.filesDir, "geo_points.jsonl")
        val lines = synchronized(lock) { if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList() }
        if (lines.isEmpty()) return 0
        val pts = org.json.JSONArray()
        lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.forEach { pts.put(it) }
        val p = Prefs(ctx)
        Api.post(ctx, "/modules/geolocation/api/client_report", JSONObject().put("device_id", "phone_" + p.deviceId)
            .put("name", p.deviceName).put("icon", "📱").put("points", pts), 15000, 60000)
        synchronized(lock) {
            // за время отправки могли добавиться новые точки — удаляем только отправленные
            val now = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
            val rest = now.drop(lines.size)
            if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n", postfix = "\n"))
        }
        return pts.length()
    }

    /** Файл копируется во внутреннюю папку — исходный content:// может перестать быть доступен. */
    fun addUpload(ctx: Context, uri: Uri, name: String, mime: String, fields: Map<String, String>) {
        val dst = File(dir(ctx), System.currentTimeMillis().toString() + "_" + name.replace("/", "_"))
        ctx.contentResolver.openInputStream(uri)?.use { inp -> dst.outputStream().use { inp.copyTo(it) } }
            ?: throw ApiException("Нет доступа к файлу $name")
        add(ctx, JSONObject().put("type", "upload").put("path", dst.absolutePath).put("name", name).put("mime", mime)
            .put("fields", JSONObject(fields)).put("title", name))
    }

    /** Отправить всё, что можно. Возвращает число отправленных; останавливается на первой ошибке связи. */
    fun flush(ctx: Context): Int {
        flushLock.lock()
        try { return flushLocked(ctx) } finally { flushLock.unlock() }
    }

    /** Убрать из очереди именно этот элемент (а не «первый»: очередь могли очистить или дополнить). */
    private fun remove(ctx: Context, item: JSONObject): Boolean = synchronized(lock) {
        val key = item.toString()
        val list = items(ctx).toMutableList()
        val i = list.indexOfFirst { it.toString() == key }
        if (i >= 0) { list.removeAt(i); save(ctx, list) }
        i >= 0
    }

    private fun flushLocked(ctx: Context): Int {
        var sent = 0
        val failed = ArrayList<String>()
        while (true) {
            val list = items(ctx)
            if (list.isEmpty()) break
            val it = list.first()
            try {
                when (it.optString("type")) {
                    "cmd" -> Api.execute(ctx, it.optString("command"), it.optJSONObject("args") ?: JSONObject())
                    "text" -> Api.post(ctx, "/modules/mobile/api/text", JSONObject().put("text", it.optString("text"))
                        .put("action", it.optString("action")))
                    "quick" -> Api.post(ctx, QUICK_PATH, it.optJSONObject("body") ?: JSONObject())
                    "exec" -> Api.post(ctx, "/modules/mobile/api/offline_exec", JSONObject().put("client_id", it.optString("client_id"))
                        .put("command", it.optString("command")).put("args", it.optJSONObject("args") ?: JSONObject()))
                    "upload" -> {
                        val f = File(it.optString("path"))
                        if (f.exists()) {
                            val fields = HashMap<String, String>()
                            it.optJSONObject("fields")?.let { o -> o.keys().forEach { k -> fields[k] = o.optString(k) } }
                            Api.upload(ctx, "/modules/mobile/api/files/upload", it.optString("name"), it.optString("mime"),
                                f.length(), { f.inputStream() }, fields)
                            f.delete()
                        }
                    }
                }
                sent++
            } catch (e: Exception) {
                if (isOffline(e)) break                                  // связи всё ещё нет — подождём
                // запись человека не теряем из-за временной ошибки сервера
                if (it.optString("type") in setOf("quick", "exec") && isServerTrouble(e)) break
                failed.add(it.optString("title") + ": " + (e.message ?: "ошибка"))
                if (it.optString("type") == "upload") File(it.optString("path")).delete()
            }
            if (!remove(ctx, it)) break                            // очередь изменили снаружи — начнём заново в следующий раз
        }
        if (sent > 0) runCatching { OfflineData.fetch(ctx) }        // снимок для офлайн-экрана — уже с учётом отправленного
        if (sent > 0) Notifier.simple(ctx, 3500, "📶 Связь с SWDS восстановлена", "Отправлено из очереди: $sent",
            Notifier.CH_FILES, timeoutMs = 60_000)
        if (failed.isNotEmpty()) Notifier.simple(ctx, 3501, "⚠ Не выполнено из очереди", failed.joinToString("\n").take(400))
        return sent
    }

    fun clear(ctx: Context) {
        items(ctx).filter { it.optString("type") == "upload" }.forEach { File(it.optString("path")).delete() }
        save(ctx, emptyList())
    }
}

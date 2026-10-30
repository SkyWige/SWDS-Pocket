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
                failed.add(it.optString("title") + ": " + (e.message ?: "ошибка"))
                if (it.optString("type") == "upload") File(it.optString("path")).delete()
            }
            if (!remove(ctx, it)) break                            // очередь изменили снаружи — начнём заново в следующий раз
        }
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

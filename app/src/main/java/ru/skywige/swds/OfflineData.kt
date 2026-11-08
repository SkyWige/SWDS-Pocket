package ru.skywige.swds

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Офлайн-снимок данных SWDS: задачи, заметки, календарь, привычки, браслет и «Сегодня».
 * Обновляется в фоне (раз в 15–30 минут и при открытии приложения), хранится в памяти телефона.
 * Экран «Без связи» (offline.html) показывает его и позволяет править: изменения встают в очередь
 * (OfflineQueue, тип exec) и уходят на сервер, когда связь появится. Пока правка не ушла, она лежит в
 * снимке в «_pending» — и не пропадает, даже если снимок успел обновиться.
 */
object OfflineData {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "offline_bundle.json")

    fun read(ctx: Context): String = synchronized(lock) {
        val f = file(ctx)
        if (f.exists()) runCatching { f.readText() }.getOrDefault("{}") else "{}"
    }

    fun save(ctx: Context, json: String) {
        if (runCatching { JSONObject(json) }.isFailure) return
        synchronized(lock) {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { f.writeText(json); tmp.delete() }
        }
    }

    /** Скачать свежий снимок. Неотправленные правки (_pending) переносятся в новый снимок. */
    fun fetch(ctx: Context): Boolean {
        val r = Api.get(ctx, "/modules/mobile/api/offline_bundle", 30000)
        if (!r.optBoolean("ok", true)) return false
        val queued = OfflineQueue.items(ctx).mapNotNull { it.optString("client_id").takeIf { s -> s.isNotEmpty() } }.toSet()
        val old = runCatching { JSONObject(read(ctx)) }.getOrDefault(JSONObject())
        val pend = old.optJSONArray("_pending") ?: JSONArray()
        val keep = JSONArray()
        for (i in 0 until pend.length()) {
            val op = pend.optJSONObject(i) ?: continue
            if (op.optString("client_id") in queued) keep.put(op)
        }
        r.put("_pending", keep).put("fetched", System.currentTimeMillis())
        save(ctx, r.toString())
        Prefs(ctx).offlineFetched = System.currentTimeMillis()
        return true
    }

    fun maybeFetch(ctx: Context, maxAgeMs: Long = 10 * 60_000L) {
        val p = Prefs(ctx)
        if (!p.offlineEnabled || !p.paired) return
        if (System.currentTimeMillis() - p.offlineFetched < maxAgeMs) return
        fetch(ctx)
    }

    fun summary(ctx: Context): JSONObject {
        val b = runCatching { JSONObject(read(ctx)) }.getOrDefault(JSONObject())
        return JSONObject().put("fetched", b.optLong("fetched")).put("tasks", b.optJSONArray("tasks")?.length() ?: 0)
            .put("notes", b.optJSONArray("notes")?.length() ?: 0).put("size_kb", file(ctx).length() / 1024)
    }
}

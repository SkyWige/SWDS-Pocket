package ru.skywige.swds

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * «Мои места»: журнал посещений прямо на телефоне — работает без сервера и без связи.
 *
 * Каждая отметка положения либо продлевает текущую стоянку (если мы всё ещё в пределах ~150 м от её центра),
 * либо закрывает её и начинает новую. В журнале остаётся одна запись на место: «был здесь с 14:20 до 18:05»,
 * а не сотня одинаковых точек. На сервер уходят только две точки на стоянку — приход и уход
 * (через ту же офлайн-очередь, что и маршрут): «Карта передвижений» SWDS покажет и место, и сколько там пробыли.
 */
object Places {
    /** В пределах стольких метров от центра считаем, что это то же место. */
    const val RADIUS = 150.0
    val INTERVALS = listOf(2, 5, 10, 15, 30, 60, 120)

    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "places.jsonl")
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("swds_places", Context.MODE_PRIVATE)

    /** Стоянка, которая продолжается сейчас: {id, lat, lng, acc, from, until, n}; время — секунды. */
    fun current(ctx: Context): JSONObject? =
        sp(ctx).getString("cur", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun setCurrent(ctx: Context, v: JSONObject?) =
        sp(ctx).edit().apply { if (v == null) remove("cur") else putString("cur", v.toString()) }.apply()

    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, r)
        return r[0].toDouble()
    }

    private fun point(lat: Double, lng: Double, acc: Double, ts: Long) =
        JSONObject().put("lat", lat).put("lng", lng).put("accuracy", acc).put("ts", ts)

    /**
     * Новая отметка положения. upload — ставить ли точки прихода/ухода в очередь на сервер
     * (в режиме подробного маршрута их и так отправляет TrackService).
     * Возвращает true, если началась новая стоянка.
     */
    fun onFix(ctx: Context, lat: Double, lng: Double, accuracy: Float, tsSec: Long, upload: Boolean): Boolean {
        val acc = if (accuracy > 0f) accuracy.toDouble() else 50.0
        val send = ArrayList<JSONObject>()
        var started = false
        synchronized(lock) {
            val cur = current(ctx)
            if (cur != null) {
                if (tsSec <= cur.optLong("until")) return false                  // запоздавшая отметка
                val d = distance(cur.optDouble("lat"), cur.optDouble("lng"), lat, lng)
                // неточная отметка (сеть, в помещении) не должна «переносить» нас в соседний квартал
                val same = d <= maxOf(RADIUS, minOf(acc, 400.0))
                if (same) {
                    if (acc < cur.optDouble("acc", 9999.0)) cur.put("lat", lat).put("lng", lng).put("acc", acc)
                    cur.put("until", tsSec).put("n", cur.optInt("n", 1) + 1)
                    setCurrent(ctx, cur)
                    return false
                }
                close(ctx, cur)
                if (upload && cur.optLong("until") - cur.optLong("from") >= 60)
                    send.add(point(cur.optDouble("lat"), cur.optDouble("lng"), cur.optDouble("acc"), cur.optLong("until")))
            }
            setCurrent(ctx, JSONObject().put("id", tsSec).put("lat", lat).put("lng", lng).put("acc", acc)
                .put("from", tsSec).put("until", tsSec).put("n", 1))
            started = true
            if (upload) send.add(point(lat, lng, acc, tsSec))
        }
        if (send.isNotEmpty()) {
            send.forEach { OfflineQueue.addGeo(ctx, it) }
            if (Prefs(ctx).paired) SyncWorker.now(ctx, "geo")
        }
        return started
    }

    private fun close(ctx: Context, v: JSONObject) {
        val f = file(ctx)
        f.appendText(v.toString() + "\n")
        if (f.length() > 700_000) runCatching { f.writeText(f.readLines().takeLast(4000).joinToString("\n", postfix = "\n")) }
    }

    /** Посещения за период (секунды), новые сверху; текущая стоянка помечена open. */
    fun list(ctx: Context, fromSec: Long, toSec: Long): JSONArray {
        val out = ArrayList<JSONObject>()
        synchronized(lock) {
            val f = file(ctx)
            if (f.exists()) f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val v = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (v.optLong("until") >= fromSec && v.optLong("from") <= toSec) out.add(v)
            }
            current(ctx)?.let { if (it.optLong("until") >= fromSec && it.optLong("from") <= toSec) out.add(it.put("open", true)) }
        }
        out.sortByDescending { it.optLong("from") }
        return JSONArray(out)
    }

    fun count(ctx: Context): Int = synchronized(lock) {
        val f = file(ctx)
        (if (f.exists()) f.readLines().count { it.isNotBlank() } else 0) + (if (current(ctx) != null) 1 else 0)
    }

    /** Сводка для экрана и уведомления. */
    fun summary(ctx: Context): JSONObject {
        val cur = current(ctx)
        return JSONObject().put("count", count(ctx)).put("current", cur ?: JSONObject.NULL)
            .put("pending", OfflineQueue.geoCount(ctx)).put("labels", Prefs(ctx).placeLabels)
    }

    /** Название места по своим меткам («Дом», «Работа») или пусто. */
    fun labelAt(ctx: Context, lat: Double, lng: Double): String {
        val labels = Prefs(ctx).placeLabels
        var best = ""
        var bestD = 250.0
        for (i in 0 until labels.length()) {
            val l = labels.optJSONObject(i) ?: continue
            val d = distance(l.optDouble("lat"), l.optDouble("lng"), lat, lng)
            if (d < bestD) { bestD = d; best = l.optString("name") }
        }
        return best
    }

    /** Назвать место (пустое имя — убрать название). Одно название на круг ~250 м. */
    fun setLabel(ctx: Context, lat: Double, lng: Double, name: String) {
        val p = Prefs(ctx)
        val old = p.placeLabels
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val l = old.optJSONObject(i) ?: continue
            if (distance(l.optDouble("lat"), l.optDouble("lng"), lat, lng) >= 250.0) out.put(l)
        }
        if (name.isNotBlank()) out.put(JSONObject().put("name", name.trim().take(40)).put("lat", lat).put("lng", lng))
        p.placeLabels = out
    }

    /** Удалить одну запись журнала (по id) или весь журнал (id = 0). */
    fun delete(ctx: Context, id: Long) = synchronized(lock) {
        val f = file(ctx)
        if (id == 0L) { f.delete(); setCurrent(ctx, null); return@synchronized }
        if (current(ctx)?.optLong("id") == id) setCurrent(ctx, null)
        if (f.exists()) {
            val rest = f.readLines().filter { it.isNotBlank() && runCatching { JSONObject(it).optLong("id") }.getOrDefault(0L) != id }
            if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n", postfix = "\n"))
        }
    }

    /** Журнал в GeoJSON — чтобы забрать свои данные куда угодно. */
    fun geoJson(ctx: Context): String {
        val all = list(ctx, 0, Long.MAX_VALUE / 2)
        val feats = JSONArray()
        for (i in 0 until all.length()) {
            val v = all.getJSONObject(i)
            feats.put(JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(v.optDouble("lng")).put(v.optDouble("lat"))))
                .put("properties", JSONObject().put("from", v.optLong("from")).put("until", v.optLong("until"))
                    .put("minutes", (v.optLong("until") - v.optLong("from")) / 60)
                    .put("name", labelAt(ctx, v.optDouble("lat"), v.optDouble("lng")))))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", feats).toString()
    }
}

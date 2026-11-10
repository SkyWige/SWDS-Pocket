package ru.skywige.swds

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Игры телефона для модуля «Игры» SWDS: список установленных игр и время в них.
 *
 * Игрой считается приложение, которое само так себя назвало (категория «Игра»), плюс отмеченные вручную.
 * Время берётся из системной статистики использования (нужен «Доступ к истории использования»):
 * приложение на экране = сеанс. На сервер уходят только сеансы игр — остальные приложения SWDS не видит.
 */
object PhoneGames {
    class Game(val pkg: String, val label: String, val size: Long, val auto: Boolean)
    class Session(val pkg: String, val start: Long, val end: Long)          // миллисекунды

    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun isGame(ai: ApplicationInfo): Boolean =
        ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    /** Все приложения с ярлыком: (пакет, название, сведения). */
    private fun launchable(ctx: Context): List<Triple<String, String, ApplicationInfo>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).mapNotNull { ri ->
            val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
            if (ai.packageName == ctx.packageName) null
            else Triple(ai.packageName, runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName), ai)
        }.distinctBy { it.first }
    }

    fun library(ctx: Context): List<Game> {
        val p = Prefs(ctx)
        val extra = p.gamesExtra.toSet()
        val hidden = p.gamesHidden.toSet()
        return launchable(ctx).mapNotNull { (pkg, label, ai) ->
            val auto = isGame(ai)
            if ((auto || pkg in extra) && pkg !in hidden)
                Game(pkg, label, runCatching { File(ai.sourceDir).length() }.getOrDefault(0L), auto)
            else null
        }.sortedBy { it.label.lowercase() }
    }

    /** Приложения, которые можно отметить игрой вручную (и скрытые игры — чтобы вернуть). */
    fun candidates(ctx: Context): JSONArray {
        val p = Prefs(ctx)
        val extra = p.gamesExtra.toSet()
        val hidden = p.gamesHidden.toSet()
        val out = JSONArray()
        launchable(ctx).sortedBy { it.second.lowercase() }.forEach { (pkg, label, ai) ->
            val auto = isGame(ai)
            if (auto && pkg !in hidden) return@forEach
            if (pkg in extra && pkg !in hidden) return@forEach
            out.put(JSONObject().put("pkg", pkg).put("label", label).put("hidden", pkg in hidden))
        }
        return out
    }

    /**
     * Сеансы игр за период по системным событиям «приложение вышло на экран / ушло с экрана».
     * Короткие уходы (ответить на сообщение — до 2 минут) сеанс не рвут. Второй элемент — игра, запущенная прямо сейчас.
     */
    fun sessions(ctx: Context, fromMs: Long, toMs: Long, games: Set<String>): Pair<List<Session>, Session?> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return Pair(emptyList(), null)
        val ev = usm.queryEvents(fromMs, toMs) ?: return Pair(emptyList(), null)
        val raw = ArrayList<Session>()
        var open = ""                       // игра на экране
        var start = 0L
        var pause = 0L                      // когда она ушла с экрана (0 — на экране)
        fun close(at: Long) {
            if (open.isNotEmpty() && at > start) raw.add(Session(open, start, at))
            open = ""; start = 0L; pause = 0L
        }
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg == open) {
                        if (pause > 0 && e.timeStamp - pause > 120_000) { close(pause); open = pkg; start = e.timeStamp }
                        pause = 0L
                    } else {
                        if (open.isNotEmpty()) {
                            // другая программа поверх игры: закрываем, только если игра уже ушла с экрана
                            if (pause > 0) close(pause) else if (pkg in games) close(e.timeStamp)
                        }
                        if (pkg in games && open.isEmpty()) { open = pkg; start = e.timeStamp; pause = 0L }
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (pkg == open) pause = e.timeStamp
                26 /* DEVICE_SHUTDOWN */, 16 /* SCREEN_NON_INTERACTIVE */ -> if (open.isNotEmpty()) {
                    if (pause == 0L) pause = e.timeStamp
                }
            }
        }
        var running: Session? = null
        if (open.isNotEmpty()) {
            if (pause > 0) close(pause) else running = Session(open, start, toMs)
        }
        // склеиваем куски одной игры с короткими паузами, отбрасываем случайные запуски
        val merged = ArrayList<Session>()
        for (s in raw.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && last.pkg == s.pkg && s.start - last.end < 120_000) merged[merged.size - 1] = Session(s.pkg, last.start, maxOf(last.end, s.end))
            else merged.add(s)
        }
        return Pair(merged.filter { it.end - it.start >= 60_000 }, running)
    }

    fun icon(ctx: Context, pkg: String, px: Int): String = runCatching {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, px, px)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Список для экрана «Игры телефона»: считается на телефоне, сервер не нужен. */
    fun state(ctx: Context, icons: Boolean): JSONObject {
        val p = Prefs(ctx)
        val lib = library(ctx)
        val access = hasUsageAccess(ctx)
        val now = System.currentTimeMillis()
        val day = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = HashMap<String, Long>()
        val week = HashMap<String, Long>()
        val last = HashMap<String, Long>()
        var running = ""
        if (access) {
            val (ss, run) = sessions(ctx, now - 7 * 86_400_000L, now, lib.map { it.pkg }.toSet())
            for (s in ss + listOfNotNull(run)) {
                week[s.pkg] = (week[s.pkg] ?: 0L) + (s.end - s.start)
                if (s.end > day) today[s.pkg] = (today[s.pkg] ?: 0L) + (s.end - maxOf(s.start, day))
                last[s.pkg] = maxOf(last[s.pkg] ?: 0L, s.end)
            }
            running = run?.pkg ?: ""
        }
        val arr = JSONArray()
        lib.sortedByDescending { last[it.pkg] ?: 0L }.forEach { g ->
            arr.put(JSONObject().put("pkg", g.pkg).put("label", g.label).put("auto", g.auto).put("size", g.size)
                .put("today", (today[g.pkg] ?: 0L) / 1000).put("week", (week[g.pkg] ?: 0L) / 1000)
                .put("last", (last[g.pkg] ?: 0L) / 1000).put("running", g.pkg == running)
                .put("icon", if (icons) icon(ctx, g.pkg, 96) else ""))
        }
        return JSONObject().put("enabled", p.gamesEnabled).put("usage_access", access).put("games", arr)
            .put("last_result", p.gamesLastResult).put("synced", p.gamesSynced)
    }

    /** Отправить список игр и новые сеансы в модуль «Игры». */
    fun sync(ctx: Context): JSONObject {
        val p = Prefs(ctx)
        if (!p.paired) return JSONObject().put("ok", false).put("error", "Телефон не привязан к SWDS")
        val lib = library(ctx)
        val now = System.currentTimeMillis()
        val body = JSONObject().put("device_id", p.deviceId).put("name", p.deviceName)
            .put("model", Build.MANUFACTURER + " " + Build.MODEL).put("version", BuildConfig.VERSION_NAME)
        val libJson = JSONArray()
        lib.forEach { libJson.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("size", it.size)) }
        body.put("library", libJson)
        var runJson: JSONObject? = null
        var upTo = p.gamesSynced
        if (hasUsageAccess(ctx)) {
            // система хранит события около недели; берём с запасом назад — повторы сервер отбрасывает сам
            val from = maxOf(now - 9 * 86_400_000L, p.gamesSynced - 6 * 3_600_000L)
            val (ss, run) = sessions(ctx, from, now, lib.map { it.pkg }.toSet())
            val arr = JSONArray()
            ss.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("start", it.start / 1000).put("end", it.end / 1000)) }
            body.put("sessions", arr)
            if (run != null) {
                runJson = JSONObject().put("pkg", run.pkg).put("start", run.start / 1000)
                body.put("running", runJson)
            }
            upTo = run?.start ?: now
        }
        return try {
            val r = Api.post(ctx, "/modules/games/api/pocket/sync", body, 15000, 60000)
            val need = r.optJSONArray("need_icons")
            if (need != null && need.length() > 0) {
                val icons = JSONObject()
                for (i in 0 until minOf(need.length(), 30)) {
                    val pkg = need.optString(i)
                    icon(ctx, pkg, 256).takeIf { it.isNotEmpty() }?.let { icons.put(pkg, it) }
                }
                if (icons.length() > 0) runCatching {
                    Api.post(ctx, "/modules/games/api/pocket/sync", JSONObject().put("device_id", p.deviceId).put("name", p.deviceName)
                        .put("icons", icons).apply { if (runJson != null) put("running", runJson) }, 15000, 90000)
                }
            }
            p.gamesSynced = upTo
            val added = r.optInt("sessions_added")
            p.gamesLastResult = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("ru")).format(java.util.Date(now)) +
                " · игр ${lib.size}" + (if (added > 0) ", новых сеансов $added" else "")
            JSONObject().put("ok", true).put("games", lib.size).put("sessions_added", added)
        } catch (e: ApiException) {
            val msg = if (e.status == 404) "На сервере нет модуля «Игры» — обновите SWDS" else (e.message ?: "Ошибка")
            p.gamesLastResult = "⚠ $msg"
            JSONObject().put("ok", false).put("error", msg)
        }
    }
}

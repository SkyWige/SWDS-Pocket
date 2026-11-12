package ru.skywige.swds

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Экранное время телефона → модуль «Куда ушло время» (screen_time.phone_report).
 * Сколько минут каждое приложение было на экране сегодня — из системной статистики
 * («Доступ к истории использования», тот же, что для игр). Отправляются только приложения с ярлыком,
 * минимум минута, не больше 60 штук.
 */
object ScreenTime {
    private fun category(ai: ApplicationInfo): String = if (Build.VERSION.SDK_INT >= 26) when (ai.category) {
        ApplicationInfo.CATEGORY_GAME -> "games"
        ApplicationInfo.CATEGORY_AUDIO -> "music"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_NEWS -> "web"
        ApplicationInfo.CATEGORY_MAPS -> "system"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "work"
        ApplicationInfo.CATEGORY_IMAGE -> "other"
        else -> ""
    } else ""

    fun today(ctx: Context): JSONArray {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return JSONArray()
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val stats = usm.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis()) ?: return JSONArray()
        val pm = ctx.packageManager
        val launch = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { it.activityInfo?.applicationInfo }.associateBy { it.packageName }
        val rows = stats.values.mapNotNull { st ->
            val ai = launch[st.packageName] ?: return@mapNotNull null
            if (st.packageName == ctx.packageName) return@mapNotNull null
            val min = st.totalTimeInForeground / 60000.0
            if (min < 1) return@mapNotNull null
            Triple(st.packageName, ai, min)
        }.sortedByDescending { it.third }.take(60)
        val out = JSONArray()
        for ((pkg, ai, min) in rows) {
            out.put(JSONObject().put("package", pkg).put("minutes", Math.round(min * 10) / 10.0)
                .put("label", runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)).put("category", category(ai)))
        }
        return out
    }

    fun sync(ctx: Context): JSONObject {
        if (!PhoneGames.hasUsageAccess(ctx)) return JSONObject().put("ok", false).put("error", "Нет доступа к истории использования")
        val apps = today(ctx)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
        val r = Api.execute(ctx, "screen_time.phone_report", JSONObject().put("day", day).put("device", Prefs(ctx).deviceName).put("apps", apps))
        var total = 0.0
        for (i in 0 until apps.length()) total += apps.optJSONObject(i)?.optDouble("minutes") ?: 0.0
        return JSONObject().put("ok", r.optString("status") != "error").put("apps", apps.length()).put("minutes", Math.round(total))
    }
}

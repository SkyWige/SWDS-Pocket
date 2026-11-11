package ru.skywige.swds

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет «Здоровье»: кольцо шагов к цели, сон, пульс, калории и дистанция.
 * Рисуется из сводки на телефоне (Health Connect) — работает и без связи с SWDS.
 * Если Health Connect не включён, берёт сводку модуля «Браслет» с сервера (Gadgetbridge и т. п.).
 */
class HealthWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, mgr, id)
        // раз в 30 минут система будит виджет — заодно подтянем свежие данные (не чаще, чем разрешено в Health)
        SyncWorker.now(ctx, "health")
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            Events.toast(ctx, "⟳ Обновляю данные здоровья…")
            SyncWorker.now(ctx, "health", manual = true)
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_REFRESH = "ru.skywige.swds.HEALTH_REFRESH"

        fun renderAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, HealthWidget::class.java)) }.getOrNull() ?: return
            for (id in ids) runCatching { render(ctx, mgr, id) }
        }

        private fun n(v: Long): String = String.format(Locale("ru"), "%,d", v).replace(',', ' ').replace(' ', ' ')

        private fun hm(min: Long): String = if (min >= 60) "${min / 60} ч ${String.format("%02d", min % 60)} мин" else "$min мин"

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val p = Prefs(ctx)
            val d = runCatching { JSONObject(p.healthToday) }.getOrDefault(JSONObject())
            val pal = Theme.widgetColors(ctx, null)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_health)
            WidgetStyle.applyBg(ctx, rv, pal, id, 250, 120)
            for (v in listOf(R.id.h_title, R.id.h_steps, R.id.h_l1, R.id.h_l2, R.id.h_l3)) rv.setTextColor(v, pal.text)
            for (v in listOf(R.id.h_updated, R.id.h_refresh, R.id.h_steps_l, R.id.h_l4)) rv.setTextColor(v, pal.muted)

            val today = java.time.LocalDate.now().toString()
            val fresh = d.optString("date") == today
            val steps = if (fresh) d.optLong("steps", 0) else 0L
            val goal = d.optInt("steps_goal", p.stepsGoal).coerceAtLeast(1000)
            val done = steps >= goal
            val ringColor = if (done) 0xFF10B981.toInt() else pal.accent
            runCatching {
                rv.setImageViewBitmap(R.id.h_ring, WidgetStyle.ring(ctx, (steps.toFloat() / goal).coerceIn(0f, 1f), ringColor,
                    WidgetStyle.alpha(pal.muted, 0x38), 92f, 9f, if (done) 0xFF6EE7B7.toInt() else null))
            }
            rv.setTextViewText(R.id.h_steps, if (d.length() == 0) "—" else if (steps >= 10000) "${steps / 1000}.${(steps % 1000) / 100}к" else n(steps))
            rv.setTextViewText(R.id.h_steps_l, if (done) "цель ✓" else "из ${n(goal.toLong())}")

            val lines = ArrayList<String>()
            if (d.has("sleep_min")) {
                val start = d.optLong("sleep_start"); val end = d.optLong("sleep_end")
                val f = SimpleDateFormat("HH:mm", Locale.getDefault())
                lines.add("😴 " + hm(d.optLong("sleep_min")) + if (start > 0 && end > 0) " · ${f.format(Date(start * 1000))}–${f.format(Date(end * 1000))}" else "")
            }
            val hr = when {
                d.has("hr_rest") -> "❤️ ${d.optLong("hr_rest")} покоя" + (if (d.has("hr_last")) " · сейчас ${d.optLong("hr_last")}" else "")
                d.has("hr_last") -> "❤️ ${d.optLong("hr_last")} уд/мин"
                else -> null
            }
            hr?.let { lines.add(it) }
            val bits = ArrayList<String>()
            if (fresh && d.optLong("active_kcal") > 0) bits.add("🔥 ${n(d.optLong("active_kcal"))} ккал")
            if (fresh && d.optLong("distance_m") > 0) bits.add("📏 ${String.format(Locale("ru"), "%.1f", d.optLong("distance_m") / 1000.0)} км")
            if (bits.isNotEmpty()) lines.add(bits.joinToString(" · "))
            if (lines.isEmpty()) lines.add(if (d.length() == 0) "Нет данных" else "За сегодня пока пусто")
            rv.setTextViewText(R.id.h_l1, lines.getOrElse(0) { "" })
            rv.setTextViewText(R.id.h_l2, lines.getOrElse(1) { "" })
            rv.setTextViewText(R.id.h_l3, lines.getOrElse(2) { "" })
            rv.setTextViewText(R.id.h_l4, when {
                d.length() == 0 && !p.healthEnabled -> "Нажмите, чтобы подключить Health Connect"
                d.optString("insight").isNotEmpty() -> d.optString("insight")
                fresh && d.optInt("exercise_min") > 0 -> "🏋️ тренировки ${d.optInt("exercise_min")} мин"
                else -> ""
            })
            val ts = d.optLong("ts")
            rv.setTextViewText(R.id.h_updated, if (ts > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts)) else "")

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            rv.setOnClickPendingIntent(R.id.h_refresh, PendingIntent.getBroadcast(ctx, 72000 + id,
                Intent(ctx, HealthWidget::class.java).setAction(ACTION_REFRESH), flags))
            val open = if (!p.healthEnabled && d.length() == 0)
                PendingIntent.getActivity(ctx, 72100 + id, Intent(ctx, SettingsActivity::class.java).putExtra("section", "health")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            else Notifier.openIntent(ctx, "/modules/bracelet/window", 72200 + id)
            rv.setOnClickPendingIntent(R.id.h_body, open)
            rv.setOnClickPendingIntent(R.id.h_title, open)
            mgr.updateAppWidget(id, rv)
        }
    }
}

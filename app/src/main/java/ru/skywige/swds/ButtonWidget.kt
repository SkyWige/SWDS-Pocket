package ru.skywige.swds

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Виджет-кнопка 1×1: ассистент, команда SWDS, пульт, отправка файла… (выбирается при добавлении). */
class ButtonWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val p = Prefs(ctx)
        for (id in ids) p.setButton(id, null)
    }

    companion object {
        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ButtonWidget::class.java))

        fun renderAll(ctx: Context) { for (id in ids(ctx)) render(ctx, id) }

        fun render(ctx: Context, id: Int) {
            val conf = Prefs(ctx).buttonFor(id)
            val pal = Theme.widgetColors(ctx, null)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_button)
            rv.setInt(R.id.bg, "setColorFilter", pal.surface)
            rv.setInt(R.id.bg, "setImageAlpha", 240)
            rv.setTextViewText(R.id.icon, conf?.optString("icon")?.ifEmpty { null } ?: "⚡")
            rv.setTextViewText(R.id.label, conf?.optString("title")?.ifEmpty { null } ?: "Настроить")
            rv.setTextColor(R.id.label, pal.text)
            rv.setTextColor(R.id.icon, pal.accent)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (conf == null) {
                PendingIntent.getActivity(ctx, 70000 + id, Intent(ctx, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            } else {
                val act = conf.optJSONObject("action")
                if (act?.optBoolean("assist") == true) {
                    PendingIntent.getActivity(ctx, 70000 + id, Intent(ctx, AssistActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
                } else {
                    PendingIntent.getActivity(ctx, 70000 + id, Intent(ctx, RouterActivity::class.java)
                        .setAction(RouterActivity.A_WIDGET_ROW + ".btn" + id)
                        .putExtra("row_action", act?.toString() ?: "{}")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION), flags)
                }
            }
            rv.setOnClickPendingIntent(R.id.root, pi)
            AppWidgetManager.getInstance(ctx).updateAppWidget(id, rv)
        }
    }
}

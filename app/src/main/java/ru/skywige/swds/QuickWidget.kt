package ru.skywige.swds

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** Виджет быстрой записи: «Заметка» и «Трата» — одно касание, и можно печатать. Работает без связи. */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, mgr, id)
    }

    companion object {
        fun renderAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, QuickWidget::class.java))) render(ctx, mgr, id)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val pal = Theme.widgetColors(ctx, null)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_quick)
            rv.setInt(R.id.bg, "setColorFilter", pal.surface)
            rv.setInt(R.id.bg, "setImageAlpha", 240)
            rv.setTextColor(R.id.q_note_icon, pal.accent)
            rv.setTextColor(R.id.q_note_label, pal.text)
            rv.setTextColor(R.id.q_money_icon, pal.accent)
            rv.setTextColor(R.id.q_money_label, pal.text)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            rv.setOnClickPendingIntent(R.id.q_note, PendingIntent.getActivity(ctx, 71000, QuickActivity.intent(ctx, "note"), flags))
            rv.setOnClickPendingIntent(R.id.q_money, PendingIntent.getActivity(ctx, 71001, QuickActivity.intent(ctx, "expense"), flags))
            mgr.updateAppWidget(id, rv)
        }
    }
}

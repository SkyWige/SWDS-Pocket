package ru.skywige.swds

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет SWDS на домашнем экране. Что показывать, выбирается при добавлении (WidgetConfigActivity):
 * «Сегодня», быстрые действия, уведомления, общая папка или любой виджет SWDS Desktop (в т.ч. свой).
 * Сервер отдаёт готовые строки (rows), их рисует WidgetService.
 */
class SwdsWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, id)
        val pending = goAsync()
        Thread {
            try { for (id in ids) fetch(ctx, id) } finally { pending.finish() }
        }.start()
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val p = Prefs(ctx)
        for (id in ids) p.setWidget(id, null)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (id >= 0) {
                val pending = goAsync()
                Thread { try { fetch(ctx, id) } finally { pending.finish() } }.start()
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_REFRESH = "ru.skywige.swds.WIDGET_REFRESH"

        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, SwdsWidget::class.java))

        fun refreshAll(ctx: Context) {
            Thread { runCatching { updateAllSync(ctx) } }.start()
        }

        /** Обновить только виджеты определённого вида (например, «notifications»). */
        fun refreshKind(ctx: Context, wid: String) {
            val p = Prefs(ctx)
            val list = ids(ctx).filter { p.widgetFor(it)?.optString("wid") == wid }
            if (list.isEmpty()) return
            Thread { list.forEach { runCatching { fetch(ctx, it) } } }.start()
        }

        fun updateAllSync(ctx: Context) {
            for (id in ids(ctx)) runCatching { fetch(ctx, id) }
        }

        fun fetch(ctx: Context, appWidgetId: Int) {
            val p = Prefs(ctx)
            val conf = p.widgetFor(appWidgetId) ?: return
            if (!p.paired) {
                p.setWidgetCache(appWidgetId, JSONObject().put("ok", false).put("error", "Телефон не привязан к SWDS").toString())
                render(ctx, appWidgetId); return
            }
            try {
                val r = Api.get(ctx, "/modules/mobile/api/widget/" + Uri.encode(conf.optString("wid"), ":"), 20000)
                r.put("fetched", System.currentTimeMillis())
                p.setWidgetCache(appWidgetId, r.toString())
            } catch (e: Exception) {
                val old = p.widgetCache(appWidgetId)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                old.put("stale", true).put("stale_error", e.message ?: "нет связи")
                p.setWidgetCache(appWidgetId, old.toString())
            }
            render(ctx, appWidgetId)
        }

        fun render(ctx: Context, appWidgetId: Int) {
            val p = Prefs(ctx)
            val mgr = AppWidgetManager.getInstance(ctx)
            val conf = p.widgetFor(appWidgetId)
            val data = p.widgetCache(appWidgetId)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val pal = Theme.widgetColors(ctx, data?.optJSONObject("colors"))
            val rv = RemoteViews(ctx.packageName, R.layout.widget_root)

            rv.setInt(R.id.bg, "setColorFilter", pal.surface)
            rv.setInt(R.id.bg, "setImageAlpha", 245)
            val title = when {
                data != null && data.has("title") -> (data.optString("icon") + " " + data.optString("title")).trim()
                conf != null -> conf.optString("name", "SWDS")
                else -> "SWDS"
            }
            rv.setTextViewText(R.id.title, title)
            rv.setTextColor(R.id.title, pal.text)
            rv.setTextColor(R.id.updated, pal.muted)
            rv.setTextColor(R.id.refresh, pal.muted)
            rv.setTextColor(R.id.empty, pal.muted)
            val fetched = data?.optLong("fetched", 0) ?: 0
            rv.setTextViewText(R.id.updated, when {
                data?.optBoolean("stale") == true -> "⚠ нет связи"
                fetched > 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fetched))
                else -> ""
            })
            rv.setTextViewText(R.id.empty, when {
                conf == null -> "Нажмите, чтобы настроить"
                data == null -> ctx.getString(R.string.loading)
                data.optBoolean("ok", true).not() -> "⚠ " + data.optString("error")
                else -> ctx.getString(R.string.empty)
            })

            // список строк
            val svc = Intent(ctx, WidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra("nonce", fetched)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            rv.setRemoteAdapter(R.id.list, svc)
            rv.setEmptyView(R.id.list, R.id.empty)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val tpl = Intent(ctx, RouterActivity::class.java).setAction(RouterActivity.A_WIDGET_ROW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            rv.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(ctx, appWidgetId, tpl, flags))

            val refresh = Intent(ctx, SwdsWidget::class.java).setAction(ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            rv.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(ctx, appWidgetId, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val titleIntent = if (conf == null)
                PendingIntent.getActivity(ctx, appWidgetId, Intent(ctx, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else Notifier.openIntent(ctx, data?.optString("open")?.ifEmpty { null } ?: "/", 50000 + appWidgetId)
            rv.setOnClickPendingIntent(R.id.title, titleIntent)
            rv.setOnClickPendingIntent(R.id.empty, titleIntent)

            mgr.updateAppWidget(appWidgetId, rv)
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.list)
        }
    }
}

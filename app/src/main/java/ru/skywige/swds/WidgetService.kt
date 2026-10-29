package ru.skywige.swds

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject

/** Строки виджета: header / text / metric / item / progress / button / divider. */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    class Factory(private val ctx: Context, private val widgetId: Int) : RemoteViewsFactory {
        private var rows = JSONArray()
        private var pal: Palette = Theme.palette(ctx)

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getCount(): Int = rows.length()
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 7
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false

        override fun onDataSetChanged() {
            val data = Prefs(ctx).widgetCache(widgetId)?.let { runCatching { JSONObject(it) }.getOrNull() }
            rows = data?.optJSONArray("rows") ?: JSONArray()
            pal = Theme.widgetColors(ctx, data?.optJSONObject("colors"))
        }

        private fun col(r: JSONObject, def: Int): Int = Theme.color(r.optString("color").ifEmpty { null }, def)

        private fun bar(rv: RemoteViews, id: Int, value: Double, color: Int) {
            rv.setProgressBar(id, 1000, (value.coerceIn(0.0, 1.0) * 1000).toInt(), false)
            if (Build.VERSION.SDK_INT >= 31) {
                rv.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(color))
                rv.setColorStateList(id, "setProgressBackgroundTintList", ColorStateList.valueOf((pal.muted and 0x00FFFFFF) or 0x33000000))
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val r = rows.optJSONObject(position) ?: JSONObject()
            val rv: RemoteViews
            var clickId = R.id.row
            when (r.optString("t")) {
                "header" -> {
                    rv = RemoteViews(ctx.packageName, R.layout.row_header)
                    rv.setTextViewText(R.id.t, r.optString("text"))
                    rv.setTextColor(R.id.t, pal.muted)
                    clickId = R.id.t
                }
                "metric" -> {
                    rv = RemoteViews(ctx.packageName, R.layout.row_metric)
                    rv.setTextViewText(R.id.value, r.optString("value"))
                    rv.setTextColor(R.id.value, col(r, pal.text))
                    rv.setTextViewText(R.id.label, r.optString("label"))
                    rv.setTextColor(R.id.label, pal.muted)
                    rv.setViewVisibility(R.id.label, if (r.optString("label").isEmpty()) View.GONE else View.VISIBLE)
                    if (r.has("progress") && !r.isNull("progress")) {
                        rv.setViewVisibility(R.id.bar, View.VISIBLE)
                        bar(rv, R.id.bar, r.optDouble("progress", 0.0), col(r, pal.accent))
                    } else rv.setViewVisibility(R.id.bar, View.GONE)
                }
                "item" -> {
                    rv = RemoteViews(ctx.packageName, R.layout.row_item)
                    val icon = r.optString("icon")
                    rv.setTextViewText(R.id.icon, icon)
                    rv.setViewVisibility(R.id.icon, if (icon.isEmpty()) View.GONE else View.VISIBLE)
                    rv.setTextColor(R.id.icon, pal.text)
                    rv.setTextViewText(R.id.t, r.optString("text"))
                    rv.setTextColor(R.id.t, col(r, pal.text))
                    val sub = r.optString("sub")
                    rv.setTextViewText(R.id.sub, sub)
                    rv.setTextColor(R.id.sub, pal.muted)
                    rv.setViewVisibility(R.id.sub, if (sub.isEmpty()) View.GONE else View.VISIBLE)
                    rv.setTextViewText(R.id.right, r.optString("right"))
                    rv.setTextColor(R.id.right, pal.muted)
                    if (r.has("action")) rv.setTextColor(R.id.icon, pal.accent)
                }
                "progress" -> {
                    rv = RemoteViews(ctx.packageName, R.layout.row_progress)
                    rv.setTextViewText(R.id.label, r.optString("label"))
                    rv.setTextColor(R.id.label, pal.text)
                    rv.setTextViewText(R.id.right, r.optString("right"))
                    rv.setTextColor(R.id.right, pal.muted)
                    bar(rv, R.id.bar, r.optDouble("progress", 0.0), col(r, pal.accent))
                }
                "button" -> {
                    rv = RemoteViews(ctx.packageName, R.layout.row_button)
                    rv.setTextViewText(R.id.t, r.optString("text"))
                    rv.setTextColor(R.id.t, pal.accent)
                    rv.setInt(R.id.btnbg, "setColorFilter", pal.accent)
                    rv.setInt(R.id.btnbg, "setImageAlpha", if (pal.dark) 60 else 36)
                }
                "divider" -> {
                    rv = RemoteViews(ctx.packageName, R.layout.row_divider)
                    rv.setInt(R.id.line, "setBackgroundColor", (pal.muted and 0x00FFFFFF) or 0x33000000)
                }
                else -> {
                    rv = RemoteViews(ctx.packageName, R.layout.row_text)
                    rv.setTextViewText(R.id.t, r.optString("text"))
                    rv.setTextColor(R.id.t, if (r.optBoolean("muted")) pal.muted else col(r, pal.text))
                    val size = r.optDouble("size", 0.0)
                    if (size > 0) rv.setTextViewTextSize(R.id.t, TypedValue.COMPLEX_UNIT_SP, size.toFloat().coerceIn(9f, 28f))
                    clickId = R.id.t
                }
            }
            val fill = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            r.optJSONObject("action")?.let { fill.putExtra("row_action", it.toString()) }
            fill.putExtra("row_text", r.optString("text"))
            rv.setOnClickFillInIntent(clickId, fill)
            return rv
        }
    }
}

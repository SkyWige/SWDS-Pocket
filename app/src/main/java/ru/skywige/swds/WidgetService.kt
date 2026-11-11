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
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1), intent.getBooleanExtra("tiles", false))

    companion object {
        private val EMOJI_LEAD = Regex("^([\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}][\\x{FE0F}\\x{200D}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]*)\\s*(.*)$")

        /** «📝 Заметка» → ("📝", "Заметка"). */
        fun splitIcon(text: String): Pair<String, String> {
            val m = EMOJI_LEAD.find(text.trim()) ?: return Pair("", text)
            return Pair(m.groupValues[1], m.groupValues[2])
        }

        /** Строки, которые выглядят как набор кнопок, показываем плитками (или если сервер попросил layout = tiles). */
        fun wantsTiles(data: JSONObject?): Boolean {
            if (data == null) return false
            val lay = data.optString("layout")
            if (lay == "tiles") return true
            if (lay == "list") return false
            val rows = data.optJSONArray("rows") ?: return false
            var n = 0
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                when (r.optString("t")) {
                    "header", "divider" -> {}
                    "button" -> n++
                    "item" -> if (r.has("action") && r.optString("sub").isEmpty() && r.optString("right").isEmpty()) n++ else return false
                    else -> return false
                }
            }
            return n >= 3
        }
    }

    class Factory(private val ctx: Context, private val widgetId: Int, private val tiles: Boolean = false) : RemoteViewsFactory {
        private var rows = JSONArray()
        private var pal: Palette = Theme.palette(ctx)

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getCount(): Int = rows.length()
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 8
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false

        override fun onDataSetChanged() {
            val data = Prefs(ctx).widgetCache(widgetId)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val all = data?.optJSONArray("rows") ?: JSONArray()
            pal = Theme.widgetColors(ctx, data?.optJSONObject("colors"))
            if (tiles) {                                     // плитки: без заголовков и разделителей
                val out = JSONArray()
                for (i in 0 until all.length()) {
                    val r = all.optJSONObject(i) ?: continue
                    if (r.optString("t") !in setOf("header", "divider")) out.put(r)
                }
                rows = out
            } else rows = all
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
            if (tiles) {
                rv = RemoteViews(ctx.packageName, R.layout.row_tile)
                var icon = r.optString("icon")
                var text = r.optString("text")
                if (icon.isEmpty()) { val (ic, rest) = splitIcon(text); icon = ic; text = rest }
                rv.setTextViewText(R.id.icon, icon.ifEmpty { "✦" })
                rv.setTextColor(R.id.icon, pal.accent)
                rv.setTextViewText(R.id.t, text)
                rv.setTextColor(R.id.t, col(r, pal.text))
                val tint = Theme.color(r.optString("color").ifEmpty { null }, pal.accent)
                rv.setInt(R.id.rowbg, "setColorFilter", tint)
                rv.setInt(R.id.rowbg, "setImageAlpha", if (pal.dark) 46 else 30)
                val fill = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                r.optJSONObject("action")?.let { fill.putExtra("row_action", it.toString()) }
                fill.putExtra("row_text", r.optString("text"))
                rv.setOnClickFillInIntent(R.id.row, fill)
                return rv
            }
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
                    rv.setViewVisibility(R.id.icon_box, if (icon.isEmpty()) View.GONE else View.VISIBLE)
                    rv.setTextColor(R.id.icon, pal.text)
                    val tint = Theme.color(r.optString("color").ifEmpty { null }, pal.accent)
                    rv.setInt(R.id.icon_bg, "setColorFilter", tint)
                    rv.setInt(R.id.icon_bg, "setImageAlpha", if (pal.dark) 60 else 38)
                    rv.setInt(R.id.rowbg, "setColorFilter", pal.text)
                    rv.setInt(R.id.rowbg, "setImageAlpha", if (pal.dark) 14 else 10)
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
                    rv.setTextColor(R.id.t, if (pal.dark) WidgetStyle.mix(pal.accent, android.graphics.Color.WHITE, 0.35f) else pal.accent)
                    rv.setInt(R.id.btnbg, "setColorFilter", pal.accent)
                    rv.setInt(R.id.btnbg, "setImageAlpha", if (pal.dark) 64 else 38)
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

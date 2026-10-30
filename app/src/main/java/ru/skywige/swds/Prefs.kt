package ru.skywige.swds

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Настройки приложения: сервер, токен, синхронизация папок, виджеты, тема. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("swds", Context.MODE_PRIVATE)

    var servers: List<String>
        get() = jsonList(sp.getString("servers", "[]"))
        set(v) = sp.edit().putString("servers", JSONArray(v.map { it.trimEnd('/') }.distinct()).toString()).apply()

    var activeServer: String
        get() = sp.getString("active_server", "") ?: ""
        set(v) = sp.edit().putString("active_server", v.trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var deviceId: String
        get() = sp.getString("device_id", "") ?: ""
        set(v) = sp.edit().putString("device_id", v).apply()

    var deviceName: String
        get() = sp.getString("device_name", null) ?: (Build.MANUFACTURER + " " + Build.MODEL)
        set(v) = sp.edit().putString("device_name", v).apply()

    /** Постоянный id сервера — по нему автопоиск узнаёт «свой» SWDS, если сменился IP. */
    var serverId: String
        get() = sp.getString("server_id", "") ?: ""
        set(v) = sp.edit().putString("server_id", v).apply()

    var serverName: String
        get() = sp.getString("server_name", "SWDS") ?: "SWDS"
        set(v) = sp.edit().putString("server_name", v).apply()

    var lastQueueId: Long
        get() = sp.getLong("last_queue_id", 0L)
        set(v) = sp.edit().putLong("last_queue_id", v).apply()

    var lastStreamOk: Long
        get() = sp.getLong("last_stream_ok", 0L)
        set(v) = sp.edit().putLong("last_stream_ok", v).apply()

    var persistentPush: Boolean
        get() = sp.getBoolean("persistent_push", true)
        set(v) = sp.edit().putBoolean("persistent_push", v).apply()

    var reportLocation: Boolean
        get() = sp.getBoolean("report_location", false)
        set(v) = sp.edit().putBoolean("report_location", v).apply()

    var reportDevice: Boolean
        get() = sp.getBoolean("report_device", true)
        set(v) = sp.edit().putBoolean("report_device", v).apply()

    var receiveFiles: Boolean
        get() = sp.getBoolean("receive_files", true)
        set(v) = sp.edit().putBoolean("receive_files", v).apply()

    var theme: JSONObject
        get() = runCatching { JSONObject(sp.getString("theme", "{}") ?: "{}") }.getOrDefault(JSONObject())
        set(v) = sp.edit().putString("theme", v.toString()).apply()

    var tileCommand: JSONObject?
        get() = sp.getString("tile_command", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) = sp.edit().putString("tile_command", v?.toString()).apply()

    // ── лента уведомлений телефона ──
    var feedEnabled: Boolean
        get() = sp.getBoolean("feed_enabled", false)
        set(v) = sp.edit().putBoolean("feed_enabled", v).apply()

    /** except — все, кроме списка; only — только из списка */
    var feedMode: String
        get() = sp.getString("feed_mode", "except") ?: "except"
        set(v) = sp.edit().putString("feed_mode", v).apply()

    var feedApps: List<String>
        get() = jsonList(sp.getString("feed_apps", "[]"))
        set(v) = sp.edit().putString("feed_apps", JSONArray(v.distinct()).toString()).apply()

    var feedLastSent: Long
        get() = sp.getLong("feed_last_sent", 0L)
        set(v) = sp.edit().putLong("feed_last_sent", v).apply()

    // ── ассистент ──
    var assistAutoListen: Boolean
        get() = sp.getBoolean("assist_auto_listen", true)
        set(v) = sp.edit().putBoolean("assist_auto_listen", v).apply()

    var assistSpeak: Boolean
        get() = sp.getBoolean("assist_speak", true)
        set(v) = sp.edit().putBoolean("assist_speak", v).apply()

    /** phone — распознавание телефоном; server — запись отправляется на SWDS (Vosk) */
    var assistEngine: String
        get() = sp.getString("assist_engine", "phone") ?: "phone"
        set(v) = sp.edit().putString("assist_engine", v).apply()

    /** phone — голос телефона (TTS); server — голос SWDS */
    var assistVoice: String
        get() = sp.getString("assist_voice", "phone") ?: "phone"
        set(v) = sp.edit().putString("assist_voice", v).apply()

    // ── внешний вид и медиа ──
    /** swds — цвета темы SWDS; wallpaper — цвета обоев (Material You, Android 12+) */
    var widgetColors: String
        get() = sp.getString("widget_colors", "swds") ?: "swds"
        set(v) = sp.edit().putString("widget_colors", v).apply()

    var remoteMedia: Boolean
        get() = sp.getBoolean("remote_media", true)
        set(v) = sp.edit().putBoolean("remote_media", v).apply()

    /** Последняя сводка «Сегодня» — показывается без связи. */
    var todayCache: String
        get() = sp.getString("today_cache", "") ?: ""
        set(v) = sp.edit().putString("today_cache", v).apply()

    fun buttonFor(appWidgetId: Int): JSONObject? =
        sp.getString("button_$appWidgetId", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun setButton(appWidgetId: Int, v: JSONObject?) =
        sp.edit().apply { if (v == null) remove("button_$appWidgetId") else putString("button_$appWidgetId", v.toString()) }.apply()

    /** Последний ответ /hello: быстрые действия, папки, каталог виджетов, модули. */
    var hello: JSONObject
        get() = runCatching { JSONObject(sp.getString("hello", "{}") ?: "{}") }.getOrDefault(JSONObject())
        set(v) = sp.edit().putString("hello", v.toString()).apply()

    fun quickCache(): JSONArray = hello.optJSONArray("quick") ?: JSONArray()

    /** Правила синхронизации папок телефона → SWDS: [{id, uri, name, dest, dest_label, mode}] */
    var syncRules: JSONArray
        get() = runCatching { JSONArray(sp.getString("sync_rules", "[]")) }.getOrDefault(JSONArray())
        set(v) = sp.edit().putString("sync_rules", v.toString()).apply()

    fun syncedState(ruleId: String): JSONObject =
        runCatching { JSONObject(sp.getString("synced_$ruleId", "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun setSyncedState(ruleId: String, v: JSONObject) = sp.edit().putString("synced_$ruleId", v.toString()).apply()

    fun clearSyncedState(ruleId: String) = sp.edit().remove("synced_$ruleId").apply()

    fun widgetFor(appWidgetId: Int): JSONObject? =
        sp.getString("widget_$appWidgetId", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun setWidget(appWidgetId: Int, v: JSONObject?) =
        sp.edit().apply { if (v == null) remove("widget_$appWidgetId") else putString("widget_$appWidgetId", v.toString()) }.apply()

    fun widgetCache(appWidgetId: Int): String? = sp.getString("wcache_$appWidgetId", null)
    fun setWidgetCache(appWidgetId: Int, v: String) = sp.edit().putString("wcache_$appWidgetId", v).apply()

    val paired: Boolean get() = token.isNotEmpty() && servers.isNotEmpty()

    fun unpair() {
        sp.edit().remove("token").remove("device_id").remove("last_queue_id").remove("active_server").apply()
    }

    /** Состояние для страниц настроек (assets). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("servers", JSONArray(servers)); put("active_server", activeServer); put("device_name", deviceName)
        put("device_id", deviceId); put("server_name", serverName); put("paired", paired); put("server_id", serverId)
        put("persistent_push", persistentPush); put("report_location", reportLocation); put("report_device", reportDevice)
        put("receive_files", receiveFiles); put("sync_rules", syncRules); put("tile_command", tileCommand ?: JSONObject.NULL)
        put("theme", theme); put("version", BuildConfig.VERSION_NAME)
        put("feed_enabled", feedEnabled); put("feed_mode", feedMode); put("feed_apps", JSONArray(feedApps))
        put("feed_last_sent", feedLastSent); put("assist_auto_listen", assistAutoListen); put("assist_speak", assistSpeak)
        put("assist_engine", assistEngine); put("assist_voice", assistVoice); put("widget_colors", widgetColors)
        put("remote_media", remoteMedia)
    }

    companion object {
        fun jsonList(s: String?): List<String> = runCatching {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }
}

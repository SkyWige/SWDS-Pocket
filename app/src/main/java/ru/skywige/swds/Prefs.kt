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
        put("device_id", deviceId); put("server_name", serverName); put("paired", paired)
        put("persistent_push", persistentPush); put("report_location", reportLocation); put("report_device", reportDevice)
        put("receive_files", receiveFiles); put("sync_rules", syncRules); put("tile_command", tileCommand ?: JSONObject.NULL)
        put("theme", theme); put("version", BuildConfig.VERSION_NAME)
    }

    companion object {
        fun jsonList(s: String?): List<String> = runCatching {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }
}

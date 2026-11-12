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

    /** «Записывать маршруты» — GPS-трек для «Карты передвижений» SWDS. */
    var trackRoutes: Boolean
        get() = sp.getBoolean("track_routes", false)
        set(v) = sp.edit().putBoolean("track_routes", v).apply()

    /** Как часто брать точку маршрута, секунд. */
    var trackInterval: Int
        get() = sp.getInt("track_interval", 20)
        set(v) = sp.edit().putInt("track_interval", v).apply()

    /** route — подробный трек (точка раз в секунды); places — только места: редкая отметка, стоянки склеиваются. */
    var trackMode: String
        get() = if (sp.getString("track_mode", "route") == "places") "places" else "route"
        set(v) = sp.edit().putString("track_mode", if (v == "places") "places" else "route").apply()

    /** Режим «только места»: как часто отмечаться, минут (2/5/10/15/30/60/120). */
    var placesInterval: Int
        get() = sp.getInt("places_interval", 10)
        set(v) = sp.edit().putInt("places_interval", v).apply()

    /** Свои названия мест: [{name, lat, lng}] — «Дом», «Работа», «Дача». */
    var placeLabels: JSONArray
        get() = runCatching { JSONArray(sp.getString("place_labels", "[]")) }.getOrDefault(JSONArray())
        set(v) = sp.edit().putString("place_labels", v.toString()).apply()

    // ── пароли (канал к модулю «Пароли»: свой ключ, AES-256-GCM) ──
    /** {u: адрес при подключении, i: id клиента, k: ключ} или пусто. */
    var passPair: JSONObject?
        get() = sp.getString("pass_pair", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) = sp.edit().apply { if (v == null) remove("pass_pair") else putString("pass_pair", v.toString()) }.apply()

    /** Мастер-пароль, зашифрованный ключом из Android Keystore (открывается отпечатком): «iv.шифр» или пусто. */
    var passBio: String
        get() = sp.getString("pass_bio", "") ?: ""
        set(v) = sp.edit().putString("pass_bio", v).apply()

    /** Через сколько секунд стирать скопированный пароль из буфера обмена (0 — не стирать). */
    var passClipSec: Int
        get() = sp.getInt("pass_clip_sec", 30)
        set(v) = sp.edit().putInt("pass_clip_sec", v).apply()

    // ── экранное время телефона → «Куда ушло время» ──
    var screenTimeEnabled: Boolean
        get() = sp.getBoolean("screen_time_enabled", false)
        set(v) = sp.edit().putBoolean("screen_time_enabled", v).apply()

    // ── режим камеры (старый телефон как камера для «Камер» SWDS) ──
    val cameraKey: String
        get() = sp.getString("camera_key", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            .also { sp.edit().putString("camera_key", it).apply() }
    var cameraName: String
        get() = sp.getString("camera_name", null) ?: deviceName
        set(v) = sp.edit().putString("camera_name", v).apply()
    var cameraBack: Boolean
        get() = sp.getBoolean("camera_back", true)
        set(v) = sp.edit().putBoolean("camera_back", v).apply()

    // ── игры телефона → модуль «Игры» ──
    var gamesEnabled: Boolean
        get() = sp.getBoolean("games_enabled", false)
        set(v) = sp.edit().putBoolean("games_enabled", v).apply()

    /** Приложения, отмеченные игрой вручную, и игры, скрытые из списка. */
    var gamesExtra: List<String>
        get() = jsonList(sp.getString("games_extra", "[]"))
        set(v) = sp.edit().putString("games_extra", JSONArray(v.distinct()).toString()).apply()

    var gamesHidden: List<String>
        get() = jsonList(sp.getString("games_hidden", "[]"))
        set(v) = sp.edit().putString("games_hidden", JSONArray(v.distinct()).toString()).apply()

    /** До какого момента сеансы игр уже отправлены на сервер, мс. */
    var gamesSynced: Long
        get() = sp.getLong("games_synced", 0L)
        set(v) = sp.edit().putLong("games_synced", v).apply()

    var gamesLastResult: String
        get() = sp.getString("games_last_result", "") ?: ""
        set(v) = sp.edit().putString("games_last_result", v).apply()

    var reportDevice: Boolean
        get() = sp.getBoolean("report_device", true)
        set(v) = sp.edit().putBoolean("report_device", v).apply()

    /** Куда попадают быстрые заметки: inbox (разобрать потом) или notes (сразу в Заметки). */
    var quickDest: String
        get() = sp.getString("quick_dest", "inbox") ?: "inbox"
        set(v) = sp.edit().putString("quick_dest", if (v == "notes") "notes" else "inbox").apply()

    /** Счета и категории Кошелька на случай, когда связи нет. */
    var quickMeta: JSONObject
        get() = runCatching { JSONObject(sp.getString("quick_meta", "{}") ?: "{}") }.getOrDefault(JSONObject())
        set(v) = sp.edit().putString("quick_meta", v.toString()).apply()

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

    // ── библиотека (книги без связи) ──
    var libraryEnabled: Boolean
        get() = sp.getBoolean("library_enabled", true)
        set(v) = sp.edit().putBoolean("library_enabled", v).apply()

    var libraryWifiOnly: Boolean
        get() = sp.getBoolean("library_wifi_only", true)
        set(v) = sp.edit().putBoolean("library_wifi_only", v).apply()

    // ── здоровье (Health Connect) ──
    var healthEnabled: Boolean
        get() = sp.getBoolean("health_enabled", false)
        set(v) = sp.edit().putBoolean("health_enabled", v).apply()

    var healthLastSync: Long
        get() = sp.getLong("health_last_sync", 0L)
        set(v) = sp.edit().putLong("health_last_sync", v).apply()

    var healthLastResult: String
        get() = sp.getString("health_last_result", "") ?: ""
        set(v) = sp.edit().putString("health_last_result", v).apply()

    /** Сводка «сегодня» с телефона: шаги, сон, пульс — для виджета «Здоровье» и экрана без связи. */
    var healthToday: String
        get() = sp.getString("health_today", "{}") ?: "{}"
        set(v) = sp.edit().putString("health_today", v).apply()

    var stepsGoal: Int
        get() = sp.getInt("steps_goal", 10000)
        set(v) = sp.edit().putInt("steps_goal", v).apply()

    /** Когда последний раз скачан офлайн-снимок (задачи, заметки, календарь…). */
    var offlineFetched: Long
        get() = sp.getLong("offline_fetched", 0L)
        set(v) = sp.edit().putLong("offline_fetched", v).apply()

    var offlineEnabled: Boolean
        get() = sp.getBoolean("offline_enabled", true)
        set(v) = sp.edit().putBoolean("offline_enabled", v).apply()

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
        sp.edit().remove("token").remove("device_id").remove("last_queue_id").remove("active_server")
            .remove("pass_pair").remove("pass_bio").remove("games_synced").apply()
    }

    /** Состояние для страниц настроек (assets). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("servers", JSONArray(servers)); put("active_server", activeServer); put("device_name", deviceName)
        put("device_id", deviceId); put("server_name", serverName); put("paired", paired); put("server_id", serverId)
        put("persistent_push", persistentPush); put("report_location", reportLocation); put("report_device", reportDevice)
        put("track_routes", trackRoutes); put("track_interval", trackInterval)
        put("track_mode", trackMode); put("places_interval", placesInterval)
        put("pass_paired", passPair != null); put("pass_bio", passBio.isNotEmpty()); put("pass_clip_sec", passClipSec)
        put("games_enabled", gamesEnabled); put("screen_time_enabled", screenTimeEnabled); put("camera_name", cameraName)
        put("receive_files", receiveFiles); put("sync_rules", syncRules); put("tile_command", tileCommand ?: JSONObject.NULL)
        put("theme", theme); put("version", BuildConfig.VERSION_NAME)
        put("feed_enabled", feedEnabled); put("feed_mode", feedMode); put("feed_apps", JSONArray(feedApps))
        put("feed_last_sent", feedLastSent); put("assist_auto_listen", assistAutoListen); put("assist_speak", assistSpeak)
        put("assist_engine", assistEngine); put("assist_voice", assistVoice); put("widget_colors", widgetColors)
        put("remote_media", remoteMedia); put("quick_dest", quickDest)
        put("health_enabled", healthEnabled); put("offline_enabled", offlineEnabled); put("offline_fetched", offlineFetched)
        put("library_enabled", libraryEnabled); put("library_wifi_only", libraryWifiOnly)
    }

    companion object {
        fun jsonList(s: String?): List<String> = runCatching {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }
}

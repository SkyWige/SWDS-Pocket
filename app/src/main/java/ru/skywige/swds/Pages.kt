package ru.skywige.swds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Экраны приложения на HTML (assets): привязка, настройки, «Отправить в SWDS», выбор виджета.
 * Страница общается с Kotlin через window.Pocket: Pocket.state() и Pocket.call(id, method, argsJson).
 */
abstract class LocalPageActivity : Activity() {
    abstract val page: String
    protected lateinit var web: WebView
    protected val main = Handler(Looper.getMainLooper())
    private val waiting = HashMap<Int, String>()      // requestCode → id обратного вызова

    open fun extraState(): JSONObject = JSONObject()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pal = Theme.palette(this)
        window.statusBarColor = pal.topbar
        window.navigationBarColor = pal.bg
        web = WebView(this)
        web.setBackgroundColor(pal.bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(JsBridge(), "Pocket")
        setContentView(web)
        web.loadUrl("file:///android_asset/$page.html")
    }

    override fun onDestroy() {
        runCatching { web.destroy() }
        super.onDestroy()
    }

    fun reply(id: String, r: JSONObject) = main.post {
        if (!isFinishing) web.evaluateJavascript("window.__pocketCb && window.__pocketCb(${JSONObject.quote(id)}, $r)", null)
    }

    fun emit(event: String, data: JSONObject) = main.post {
        if (!isFinishing) web.evaluateJavascript("window.__pocketEvent && window.__pocketEvent(${JSONObject.quote(event)}, $data)", null)
    }

    fun ok(extra: JSONObject = JSONObject()) = extra.put("ok", true)
    fun err(msg: String?) = JSONObject().put("ok", false).put("error", msg ?: "Ошибка")

    /** Методы, общие для всех страниц. Возвращает null, если метод асинхронный и ответит сам. */
    open fun handle(id: String, method: String, a: JSONObject): JSONObject? {
        return when (method) {
            "close" -> { main.post { finish() }; ok() }
            "open_main" -> {
                main.post {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("path", a.optString("path").ifEmpty { null })
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }
                ok()
            }
            "open_settings" -> { main.post { startActivity(Intent(this, SettingsActivity::class.java)) }; ok() }
            "open_url" -> { main.post { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url")))) } }; ok() }
            "toast" -> { Events.toast(this, a.optString("text")); ok() }
            "hello" -> ok(Bridge.hello(this))
            "widgets" -> ok(JSONObject().put("widgets", Api.get(this, "/modules/mobile/api/widgets").optJSONArray("widgets") ?: JSONArray()))
            "scan" -> {
                main.post {
                    waiting[IntentIntegrator.REQUEST_CODE] = id
                    IntentIntegrator(this).setPrompt("Наведите камеру на QR-код со страницы «Телефон» в SWDS")
                        .setBeepEnabled(false).setOrientationLocked(false)
                        .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).initiateScan()
                }
                null
            }
            "permission" -> {
                val perms = when (a.optString("what")) {
                    "notifications" -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else arrayOf()
                    "location" -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    "background_location" -> if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else arrayOf()
                    "storage" -> if (Build.VERSION.SDK_INT < 29) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else arrayOf()
                    "camera" -> arrayOf(Manifest.permission.CAMERA)
                    "microphone" -> arrayOf(Manifest.permission.RECORD_AUDIO)
                    else -> arrayOf()
                }
                val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) ok(JSONObject().put("granted", true))
                else { main.post { waiting[RC_PERM] = id; requestPermissions(missing.toTypedArray(), RC_PERM) }; null }
            }
            "perms" -> ok(Bridge.perms(this))
            "battery_opt" -> {
                main.post {
                    val pm = getSystemService(PowerManager::class.java)
                    val i = if (!pm.isIgnoringBatteryOptimizations(packageName))
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    runCatching { startActivity(i) }
                }
                ok()
            }
            "notif_settings" -> {
                main.post {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }
                }
                ok()
            }
            "app_settings" -> {
                main.post { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
                ok()
            }
            "pair" -> Bridge.pair(this, a)
            "discover" -> ok(JSONObject().put("servers", org.json.JSONArray(Discovery.scan(this, 2500).map { it.json() })))
            else -> err("Неизвестный метод $method")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = waiting.remove(requestCode) ?: return
        if (requestCode == IntentIntegrator.REQUEST_CODE) {
            val res = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            reply(id, if (res?.contents != null) ok(JSONObject().put("text", res.contents)) else err("Сканирование отменено"))
            return
        }
        onResult(id, requestCode, resultCode, data)
    }

    open fun onResult(id: String, requestCode: Int, resultCode: Int, data: Intent?) {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val id = waiting.remove(requestCode) ?: return
        reply(id, ok(JSONObject().put("granted", grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })))
    }

    protected fun await(requestCode: Int, id: String) { waiting[requestCode] = id }

    inner class JsBridge {
        @JavascriptInterface
        fun state(): String {
            val p = Prefs(this@LocalPageActivity)
            return p.toJson().put("css", Theme.cssVars(this@LocalPageActivity)).put("hello", p.hello)
                .put("perms", Bridge.perms(this@LocalPageActivity)).put("extra", extraState()).toString()
        }

        @JavascriptInterface
        fun call(id: String, method: String, args: String) {
            val a = runCatching { JSONObject(args) }.getOrDefault(JSONObject())
            Thread {
                val r = try { handle(id, method, a) } catch (e: Exception) { err(e.message) }
                if (r != null) reply(id, r)
            }.start()
        }
    }

    companion object {
        const val RC_PERM = 41
        const val RC_TREE = 42
        const val RC_FILES = 43
    }
}

/** Общие действия: привязка, /hello, разрешения. */
object Bridge {
    fun perms(ctx: android.content.Context): JSONObject {
        fun g(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val pm = ctx.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("notifications", Build.VERSION.SDK_INT < 33 || g(Manifest.permission.POST_NOTIFICATIONS))
            .put("location", g(Manifest.permission.ACCESS_FINE_LOCATION) || g(Manifest.permission.ACCESS_COARSE_LOCATION))
            .put("background_location", Build.VERSION.SDK_INT < 29 || g(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            .put("storage", Build.VERSION.SDK_INT >= 29 || g(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            .put("battery", pm.isIgnoringBatteryOptimizations(ctx.packageName))
            .put("sdk", Build.VERSION.SDK_INT)
    }

    /** /hello: тема, быстрые действия, папки, модули → кеш. */
    fun hello(ctx: android.content.Context): JSONObject {
        val p = Prefs(ctx)
        val r = Api.get(ctx, "/modules/mobile/api/hello")
        p.hello = r
        r.optJSONObject("theme")?.let { p.theme = it }
        r.optString("server_name").takeIf { it.isNotEmpty() }?.let { p.serverName = it }
        r.optString("server_id").takeIf { it.isNotEmpty() }?.let { p.serverId = it }
        val srv = r.optJSONArray("servers")
        if (srv != null) {
            val list = p.servers.toMutableList()
            for (i in 0 until srv.length()) srv.optString(i).trimEnd('/').takeIf { it.isNotEmpty() && it !in list }?.let { list.add(it) }
            p.servers = list
        }
        return r
    }

    /** Привязка: {link} (из QR) или {server, code}; плюс name. */
    fun pair(act: Activity, a: JSONObject): JSONObject {
        val p = Prefs(act)
        var code = a.optString("code").filter { it.isDigit() }
        val urls = ArrayList<String>()
        val link = a.optString("link").trim()
        if (link.startsWith("swds://")) {
            val u = Uri.parse(link)
            code = u.getQueryParameter("c")?.filter { it.isDigit() } ?: code
            u.getQueryParameter("u")?.split(",")?.map { it.trim().trimEnd('/') }?.filter { it.isNotEmpty() }?.let { urls.addAll(it) }
        } else if (link.isNotEmpty()) {
            return JSONObject().put("ok", false).put("error", "Это не QR-код SWDS. Откройте в SWDS страницу «Телефон» → «Показать QR-код».")
        }
        a.optString("server").trim().takeIf { it.isNotEmpty() }?.let {
            var s = it.trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            if (!Regex(":\\d+$").containsMatchIn(s.substringAfter("://")) && s.startsWith("http://")) urls.add(0, "$s:7979")
            urls.add(if (urls.isNotEmpty() && urls[0].endsWith(":7979")) 1 else 0, s)
        }
        if (code.length != 6) return JSONObject().put("ok", false).put("error", "Нужен 6-значный код со страницы «Телефон»")
        if (urls.isEmpty()) return JSONObject().put("ok", false).put("error", "Укажите адрес SWDS")
        val name = a.optString("name").trim().ifEmpty { p.deviceName }
        val errors = ArrayList<String>()
        for (u in urls.distinct()) {
            try {
                val r = Api.pair(u, code, name, Build.MANUFACTURER + " " + Build.MODEL)
                if (!r.optBoolean("ok")) { errors.add(r.optString("error")); continue }
                val servers = ArrayList<String>()
                servers.add(u)
                val rs = r.optJSONArray("servers")
                if (rs != null) for (i in 0 until rs.length()) servers.add(rs.optString(i).trimEnd('/'))
                urls.forEach { servers.add(it) }
                p.servers = servers.filter { it.isNotEmpty() }.distinct()
                p.activeServer = u
                p.token = r.optString("token")
                p.deviceId = r.optString("device_id")
                p.deviceName = name
                p.serverName = r.optString("server_name", "SWDS")
                p.serverId = r.optString("server_id")
                p.lastQueueId = 0
                // старые уведомления из очереди сервера новому телефону не показываем
                runCatching { hello(act) }.getOrNull()?.let { p.lastQueueId = it.optLong("last_id", 0) }
                SyncWorker.schedule(act)
                PushService.start(act)
                SyncWorker.now(act, "all")
                return JSONObject().put("ok", true).put("server", u).put("user", r.optString("user"))
            } catch (e: ApiException) {
                errors.add(e.message ?: "ошибка")
                if (e.status == 403) break
            } catch (e: Exception) {
                errors.add("$u: ${e.message}")
            }
        }
        return JSONObject().put("ok", false).put("error", errors.lastOrNull() ?: "Сервер не отвечает")
    }
}

/** Первый запуск: QR или ручной ввод адреса и кода. */
class PairActivity : LocalPageActivity() {
    override val page = "pair"
    override fun extraState(): JSONObject = JSONObject().put("link", intent?.dataString ?: "")
}

/** Настройки приложения. */
class SettingsActivity : LocalPageActivity() {
    override val page = "settings"

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? {
        val p = Prefs(this)
        return when (method) {
            "save" -> {
                if (a.has("device_name")) p.deviceName = a.optString("device_name").trim().ifEmpty { p.deviceName }
                if (a.has("persistent_push")) {
                    p.persistentPush = a.optBoolean("persistent_push")
                    if (p.persistentPush) PushService.start(this) else PushService.stop(this)
                }
                if (a.has("report_location")) p.reportLocation = a.optBoolean("report_location")
                if (a.has("report_device")) p.reportDevice = a.optBoolean("report_device")
                if (a.has("receive_files")) p.receiveFiles = a.optBoolean("receive_files")
                if (a.has("servers")) {
                    val arr = a.optJSONArray("servers") ?: JSONArray()
                    p.servers = (0 until arr.length()).map { arr.optString(it).trim().trimEnd('/') }.filter { it.isNotEmpty() }
                    if (p.activeServer !in p.servers) p.activeServer = p.servers.firstOrNull() ?: ""
                }
                if (a.has("tile")) p.tileCommand = a.optJSONObject("tile")
                if (a.has("feed_enabled")) p.feedEnabled = a.optBoolean("feed_enabled")
                if (a.has("feed_mode")) p.feedMode = a.optString("feed_mode", "except")
                if (a.has("feed_apps")) {
                    val arr = a.optJSONArray("feed_apps") ?: JSONArray()
                    p.feedApps = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
                }
                if (a.has("assist_auto_listen")) p.assistAutoListen = a.optBoolean("assist_auto_listen")
                if (a.has("assist_speak")) p.assistSpeak = a.optBoolean("assist_speak")
                if (a.has("assist_engine")) p.assistEngine = a.optString("assist_engine", "phone")
                if (a.has("assist_voice")) p.assistVoice = a.optString("assist_voice", "phone")
                if (a.has("widget_colors")) {
                    p.widgetColors = a.optString("widget_colors", "swds")
                    SwdsWidget.refreshAll(this); ButtonWidget.renderAll(this)
                }
                if (a.has("remote_media")) { p.remoteMedia = a.optBoolean("remote_media"); main.post { MediaHub.update(applicationContext) } }
                ok(p.toJson())
            }
            "check" -> {
                val t0 = System.currentTimeMillis()
                val r = Bridge.hello(this)
                PushService.start(this, reconnect = true)
                ok(JSONObject().put("server", p.activeServer).put("ms", System.currentTimeMillis() - t0)
                    .put("user", r.optString("user")).put("device", r.optJSONObject("device")))
            }
            "test_notify" -> {
                Notifier.show(this, JSONObject().put("id", 99).put("title", "🧪 Проверка").put("body", "Так выглядят уведомления SWDS")
                    .put("level", "success"))
                ok()
            }
            "report_now" -> ok(Device.send(this))
            "feed_status" -> ok(JSONObject().put("access", FeedListener.hasAccess(this)).put("pending", FeedStore.pendingCount(this))
                .put("last_sent", p.feedLastSent).put("wallpaper", Build.VERSION.SDK_INT >= 31)
                .put("phone_stt", android.speech.SpeechRecognizer.isRecognitionAvailable(this)))
            "feed_access" -> {
                main.post { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
                ok()
            }
            "feed_flush" -> { val all = FeedStore.flush(this); ok(JSONObject().put("all", all).put("pending", FeedStore.pendingCount(this))) }
            "apps" -> ok(JSONObject().put("apps", FeedStore.launcherApps(this)))
            "queue" -> ok(JSONObject().put("items", JSONArray(OfflineQueue.items(this).map { it.optString("title") })))
            "queue_flush" -> ok(JSONObject().put("sent", OfflineQueue.flush(this)).put("left", OfflineQueue.count(this)))
            "queue_clear" -> { OfflineQueue.clear(this); ok() }
            "assist_default" -> {
                main.post {
                    val ok = runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }.isSuccess
                    if (!ok) runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                }
                ok()
            }
            "open_assist" -> { main.post { startActivity(Intent(this, AssistActivity::class.java)) }; ok() }
            "sync_now" -> { SyncWorker.now(this, a.optString("what", "all"), manual = true); ok() }
            "unpair" -> {
                runCatching { Api.post(this, "/modules/mobile/api/report", JSONObject().put("bye", true)) }
                PushService.stop(this)
                SyncWorker.cancelAll(this)
                p.unpair()
                main.post {
                    startActivity(Intent(this, PairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
                ok()
            }
            "pick_folder" -> {
                main.post {
                    await(RC_TREE, id)
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), RC_TREE)
                }
                null
            }
            "rule_add" -> {
                val rules = p.syncRules
                rules.put(SyncWorker.newRule(a.optString("uri"), a.optString("name"), a.optString("dest", "shared"),
                    a.optString("path"), a.optBoolean("only_new", true), a.optString("mode", FolderSync.UPLOAD),
                    a.optBoolean("wifi_only"), a.optBoolean("charging_only")))
                p.syncRules = rules
                SyncWorker.now(this, "folders", manual = true)
                ok(p.toJson())
            }
            "rule_update" -> {
                val rules = p.syncRules
                val out = JSONArray()
                for (i in 0 until rules.length()) {
                    val r = rules.optJSONObject(i) ?: continue
                    if (r.optString("id") == a.optString("id")) {
                        if (a.optBoolean("delete")) {
                            val uri = Uri.parse(r.optString("uri"))
                            // доступ к папке отпускаем, только если она не нужна другому правилу
                            val others = (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }
                                .count { it.optString("uri") == r.optString("uri") }
                            if (others <= 1) runCatching { contentResolver.releasePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                            FolderSync.forgetRule(this, r.optString("id"))
                            continue
                        }
                        if (a.has("enabled")) r.put("enabled", a.optBoolean("enabled"))
                        val target = listOf("dest", "path", "mode").any { a.has(it) && a.optString(it) != r.optString(it) }
                        if (a.has("dest")) r.put("dest", a.optString("dest"))
                        if (a.has("path")) r.put("path", a.optString("path"))
                        if (a.has("mode")) r.put("mode", a.optString("mode"))
                        if (a.has("wifi_only")) r.put("wifi_only", a.optBoolean("wifi_only"))
                        if (a.has("charging_only")) r.put("charging_only", a.optBoolean("charging_only"))
                        if (target) FolderSync.forgetRule(this, r.optString("id"))   // другая папка/режим — сравниваем заново
                    }
                    out.put(r)
                }
                p.syncRules = out
                ok(p.toJson())
            }
            else -> super.handle(id, method, a)
        }
    }

    override fun onResult(id: String, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_TREE) {
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) { reply(id, err("Папка не выбрана")); return }
            // запись нужна для синхронизации в обе стороны; если система её не дала — хотя бы чтение
            val write = runCatching { contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isSuccess
            if (!write) runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name ?: "Папка"
            reply(id, ok(JSONObject().put("uri", uri.toString()).put("name", name).put("writable", write)))
        }
    }
}

/** Выбор содержимого виджета при добавлении на домашний экран. */
class WidgetConfigActivity : LocalPageActivity() {
    override val page = "widget_config"
    private val widgetId by lazy {
        intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) finish()
    }

    private val isButton by lazy {
        runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className }
            .getOrNull() == ButtonWidget::class.java.name
    }

    override fun extraState(): JSONObject = JSONObject().put("widget_id", widgetId)
        .put("kind", if (isButton) "button" else "list")
        .put("current", (if (isButton) Prefs(this).buttonFor(widgetId) else Prefs(this).widgetFor(widgetId)) ?: JSONObject.NULL)

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? = when (method) {
        "button_set" -> {
            Prefs(this).setButton(widgetId, JSONObject().put("icon", a.optString("icon")).put("title", a.optString("title"))
                .put("action", a.optJSONObject("action") ?: JSONObject()))
            ButtonWidget.render(this, widgetId)
            main.post {
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            }
            ok()
        }
        "widget_set" -> {
            Prefs(this).setWidget(widgetId, JSONObject().put("wid", a.optString("wid")).put("name", a.optString("name")))
            SwdsWidget.fetch(this, widgetId)
            main.post {
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            }
            ok()
        }
        else -> super.handle(id, method, a)
    }
}

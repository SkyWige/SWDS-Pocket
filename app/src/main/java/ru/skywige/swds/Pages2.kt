package ru.skywige.swds

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import org.json.JSONObject

/**
 * «Пароли» на телефоне: поиск по хранилищу SWDS, копирование логина/пароля/кода 2FA, генератор,
 * разблокировка отпечатком. Тот же экран открывается из автозаполнения Android — тогда выбранная запись
 * сразу подставляется в приложение или сайт.
 */
class PassActivity : LocalPageActivity() {
    override val page = "passwords"

    private val af: Boolean get() = intent?.getBooleanExtra("af", false) == true
    private val afUrl: String get() = intent?.getStringExtra("af_url") ?: ""

    @Suppress("DEPRECATION")
    private fun afId(key: String): AutofillId? =
        if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(key, AutofillId::class.java) else intent?.getParcelableExtra(key)

    override fun onCreate(savedInstanceState: Bundle?) {
        // пароли не попадают на снимки экрана и в список недавних приложений
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }

    private fun pendingBrief(): Any = Pass.pendingSave?.let {
        JSONObject().put("title", it.optString("title")).put("username", it.optString("username"))
    } ?: JSONObject.NULL

    override fun extraState(): JSONObject = JSONObject().put("af", af).put("af_url", afUrl)
        .put("af_label", intent?.getStringExtra("af_label") ?: "")

    private fun autofillOn(): Boolean = runCatching {
        val am = getSystemService(AutofillManager::class.java)
        am != null && am.isAutofillSupported && am.hasEnabledAutofillServices()
    }.getOrDefault(false)

    private fun secret(uuid: String): JSONObject =
        Pass.rpc(this, "get", JSONObject().put("uuid", uuid).put("url", if (af) afUrl else "SWDS Pocket"))

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? {
        val p = Prefs(this)
        return when (method) {
            "pw_state" -> {
                if (!Pass.paired(this)) Pass.pair(this)
                val h = Pass.rpc(this, "hello", JSONObject().put("agent", Api.UA))
                ok(h.put("bio", p.passBio.isNotEmpty()).put("bio_available", PassBio.available(this))
                    .put("clip_sec", p.passClipSec).put("autofill", autofillOn()).put("pending", pendingBrief()))
            }
            "pw_unlock" -> {
                val pw = a.optString("password")
                Pass.rpc(this, "unlock", JSONObject().put("password", pw))        // неверный пароль — исключение с текстом сервера
                if (a.optBoolean("remember") && PassBio.available(this)) {
                    main.post {
                        PassBio.remember(this, pw) { done, msg -> reply(id, ok(JSONObject().put("bio", done).put("bio_error", msg))) }
                    }
                    null
                } else ok()
            }
            "pw_bio" -> {
                main.post {
                    PassBio.reveal(this) { master, msg ->
                        if (master == null) reply(id, err(msg))
                        else Thread {
                            reply(id, try {
                                Pass.rpc(this, "unlock", JSONObject().put("password", master)); ok()
                            } catch (e: Exception) {
                                // мастер-пароль сменили на сервере — сохранённый больше не подходит
                                if ((e.message ?: "").contains("парол", ignoreCase = true)) PassBio.forget(this)
                                err(e.message)
                            })
                        }.start()
                    }
                }
                null
            }
            "pw_bio_off" -> { PassBio.forget(this); ok() }
            "pw_lock" -> { Pass.rpc(this, "lock"); ok() }
            "pw_list" -> {
                val q = a.optString("q").trim()
                ok(if (q.isEmpty() && af) Pass.rpc(this, "match", JSONObject().put("url", afUrl).put("label", intent?.getStringExtra("af_label") ?: ""))
                else Pass.rpc(this, "search", JSONObject().put("q", q)))
            }
            "pw_copy" -> {
                val s = secret(a.optString("uuid"))
                if (s.optBoolean("locked")) return ok(s)
                val what = a.optString("what", "password")
                val v = s.optString(what)
                if (v.isNotEmpty()) Pass.copy(this, v, secret = what != "username")
                ok(JSONObject().put("empty", v.isEmpty()).put("clip_sec", p.passClipSec))
            }
            "pw_show" -> ok(secret(a.optString("uuid")))
            "pw_totp" -> ok(Pass.rpc(this, "totp", JSONObject().put("uuid", a.optString("uuid"))))
            "pw_fill" -> {
                val s = secret(a.optString("uuid"))
                if (s.optBoolean("locked")) return ok(s)
                val user = afId("af_user")
                val pass = afId("af_pass")
                if (user == null && pass == null) return err("Поля для заполнения не найдены")
                @Suppress("DEPRECATION")
                val ds = Dataset.Builder(PassAutofillService.presentation(this, s.optString("title"), s.optString("username")))
                user?.let { ds.setValue(it, AutofillValue.forText(s.optString("username"))) }
                pass?.let { ds.setValue(it, AutofillValue.forText(s.optString("password"))) }
                // запись выбрана вручную — запоминаем, что она подходит этому приложению или сайту
                if (a.optBoolean("link") && afUrl.isNotEmpty()) runCatching {
                    Pass.rpc(this, "link", JSONObject().put("uuid", a.optString("uuid")).put("url", afUrl))
                }
                val totp = s.optString("totp")
                if (totp.isNotEmpty()) { Pass.copy(this, totp, secret = true); Events.toast(this, "Код 2FA скопирован — вставьте на следующем шаге") }
                val built = ds.build()
                main.post {
                    setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, built))
                    finish()
                }
                ok()
            }
            "pw_generate" -> ok(Pass.rpc(this, "generate", a))
            "pw_save" -> ok(Pass.rpc(this, "save", JSONObject().put("url", a.optString("url")).put("username", a.optString("username"))
                .put("password", a.optString("password")).put("title", a.optString("title"))))
            "pw_save_pending" -> {
                val item = Pass.pendingSave ?: return err("Нечего сохранять")
                ok(JSONObject().put("saved", PassAutofillService.saveLogin(this, item)))
            }
            "pw_drop_pending" -> { Pass.pendingSave = null; ok() }
            "pw_copy_text" -> { Pass.copy(this, a.optString("text"), secret = true); ok(JSONObject().put("clip_sec", p.passClipSec)) }
            "pw_settings" -> {
                if (a.has("clip_sec")) p.passClipSec = a.optInt("clip_sec", 30).coerceIn(0, 600)
                ok()
            }
            "autofill_enable" -> {
                main.post {
                    val ok = runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:$packageName"))) }.isSuccess
                    if (!ok) runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                ok()
            }
            "pw_cancel" -> { main.post { setResult(RESULT_CANCELED); finish() }; ok() }
            else -> super.handle(id, method, a)
        }
    }
}

/** «Мои места»: журнал посещений на телефоне — работает без связи и без сервера. */
class PlacesActivity : LocalPageActivity() {
    override val page = "places"

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? {
        val p = Prefs(this)
        return when (method) {
            "places" -> ok(Places.summary(this)
                .put("visits", Places.list(this, a.optLong("from", 0L), a.optLong("to", Long.MAX_VALUE / 2)))
                .put("on", p.trackRoutes).put("mode", p.trackMode).put("interval", p.placesInterval)
                .put("paired", p.paired).put("perms", Bridge.perms(this)))
            "places_setup" -> {
                if (a.has("interval")) p.placesInterval = a.optInt("interval", 10).let { v -> Places.INTERVALS.minByOrNull { kotlin.math.abs(it - v) } ?: 10 }
                if (a.has("mode")) p.trackMode = a.optString("mode")
                if (a.has("on")) p.trackRoutes = a.optBoolean("on")
                if (p.trackRoutes) { TrackService.start(this); TrackService.restart(this) } else TrackService.stop(this)
                ok()
            }
            "places_now" -> {
                if (!p.trackRoutes) err("Сначала включите запись мест")
                else { TrackService.start(this); TrackService.markNow(this); ok() }
            }
            "place_label" -> { Places.setLabel(this, a.optDouble("lat"), a.optDouble("lng"), a.optString("name")); ok() }
            "place_delete" -> { Places.delete(this, a.optLong("id")); ok() }
            "places_send" -> ok(JSONObject().put("sent", OfflineQueue.flushGeo(this)))
            "places_export" -> {
                val bytes = Places.geoJson(this).toByteArray(Charsets.UTF_8)
                val name = "swds-places-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".geojson"
                Files.saveToDownloads(this, name, "application/geo+json", bytes.inputStream(), bytes.size.toLong())
                ok(JSONObject().put("name", name))
            }
            "open_map" -> {
                val ll = "${a.optDouble("lat")},${a.optDouble("lng")}"
                main.post {
                    if (runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll"))) }.isFailure)
                        Events.toast(this, "Нет приложения с картами")
                }
                ok()
            }
            else -> super.handle(id, method, a)
        }
    }
}

/** «Игры телефона»: что установлено и сколько наиграно — считается на телефоне, в SWDS уходит по расписанию. */
class GamesActivity : LocalPageActivity() {
    override val page = "games"

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? {
        val p = Prefs(this)
        return when (method) {
            "games" -> ok(PhoneGames.state(this, icons = true).put("paired", p.paired))
            "games_enable" -> {
                p.gamesEnabled = a.optBoolean("on")
                if (p.gamesEnabled) SyncWorker.now(this, "games", manual = true)
                ok()
            }
            "usage_access" -> {
                main.post {
                    if (runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.isFailure)
                        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                ok()
            }
            "games_sync" -> {
                val r = PhoneGames.sync(this)
                if (r.optBoolean("ok")) ok(r) else err(r.optString("error"))
            }
            "games_apps" -> ok(JSONObject().put("apps", PhoneGames.candidates(this)))
            "games_mark" -> {
                val pkg = a.optString("pkg")
                if (pkg.isEmpty()) return err("Нет приложения")
                if (a.optBoolean("game")) { p.gamesExtra = p.gamesExtra + pkg; p.gamesHidden = p.gamesHidden - pkg }
                else { p.gamesHidden = p.gamesHidden + pkg; p.gamesExtra = p.gamesExtra - pkg }
                ok()
            }
            "games_launch" -> if (PhoneGames.launch(this, a.optString("pkg"))) ok() else err("Не удалось запустить игру")
            else -> super.handle(id, method, a)
        }
    }
}

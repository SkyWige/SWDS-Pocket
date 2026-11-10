package ru.skywige.swds

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import org.json.JSONObject

/** Что нашлось на экране входа: поля логина и пароля, сайт или приложение. */
class LoginForm {
    var username: AutofillId? = null
    var password: AutofillId? = null
    var usernameText = ""
    var passwordText = ""
    var domain = ""
    var pkg = ""
    private var lastText: AutofillId? = null
    private var lastTextValue = ""

    /** Адрес для поиска записи: сайт (в браузере) или androidapp://пакет. */
    val url: String get() = if (domain.isNotEmpty()) "https://$domain" else "androidapp://$pkg"

    fun walk(n: AssistStructure.ViewNode) {
        n.webDomain?.takeIf { it.isNotEmpty() }?.let { domain = it }
        val id = n.autofillId
        if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.visibility == View.VISIBLE) {
            val hints = n.autofillHints?.map { it.lowercase() } ?: emptyList()
            val cls = n.inputType and InputType.TYPE_MASK_CLASS
            val variation = n.inputType and InputType.TYPE_MASK_VARIATION
            val attrs = n.htmlInfo?.attributes
            val htmlType = attrs?.firstOrNull { it.first == "type" }?.second?.lowercase() ?: ""
            val words = (listOf(n.idEntry, n.hint) + (attrs?.filter { it.first in setOf("name", "id", "autocomplete", "placeholder") }?.map { it.second }
                ?: emptyList())).filterNotNull().joinToString(" ").lowercase()
            val value = n.autofillValue?.takeIf { it.isText }?.textValue?.toString() ?: ""
            val passType = (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                    (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val isPass = hints.any { "password" in it } || passType || htmlType == "password" ||
                    Regex("passw|парол").containsMatchIn(words)
            val isUser = hints.any { it in setOf("username", "emailaddress", "email", "phone", "phonenumber") } ||
                    htmlType in setOf("email", "tel") ||
                    (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) ||
                    Regex("user|login|e-?mail|логин|почт|phone|телефон|account|аккаунт").containsMatchIn(words)
            val isEdit = (n.className ?: "").contains("EditText") || n.htmlInfo?.tag == "input" || hints.isNotEmpty()
            if (isPass && !Regex("otp|code|sms|captcha|код").containsMatchIn(words)) {
                if (password == null) {
                    password = id; passwordText = value
                    // логин не подписан — берём текстовое поле прямо перед паролем
                    if (username == null && lastText != null) { username = lastText; usernameText = lastTextValue }
                }
            } else if (isUser && isEdit && username == null) {
                username = id; usernameText = value
            } else if (isEdit && cls == InputType.TYPE_CLASS_TEXT || htmlType == "text") {
                lastText = id; lastTextValue = value
            }
        }
        for (i in 0 until n.childCount) walk(n.getChildAt(i))
    }

    companion object {
        fun parse(st: AssistStructure): LoginForm {
            val f = LoginForm()
            f.pkg = st.activityComponent?.packageName ?: ""
            for (i in 0 until st.windowNodeCount) f.walk(st.getWindowNodeAt(i).rootViewNode)
            return f
        }
    }
}

/**
 * Автозаполнение Android из «Паролей» SWDS: в любом приложении и браузере под полем входа появляется
 * «🔐 Пароли SWDS». Касание открывает окно выбора (с отпечатком, если хранилище заблокировано),
 * выбранная запись сразу подставляется. После входа с новым паролем Android предложит его сохранить.
 * Сам сервис паролей не хранит и в сеть не ходит, пока человек не коснулся подсказки.
 */
class PassAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val st = request.fillContexts.lastOrNull()?.structure
        val f = st?.let { runCatching { LoginForm.parse(it) }.getOrNull() }
        if (f == null || f.pkg == packageName || (f.password == null && f.username == null) || !Prefs(this).paired) {
            callback.onSuccess(null); return
        }
        try {
            val label = appLabel(this, f.pkg)
            val pres = presentation(this, "🔐 Пароли SWDS", if (f.domain.isNotEmpty()) f.domain else label)
            val intent = Intent(this, PassActivity::class.java)
                .putExtra("af", true).putExtra("af_url", f.url).putExtra("af_label", label)
                .putExtra("af_user", f.username).putExtra("af_pass", f.password)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getActivity(this, (System.nanoTime() and 0xFFFFFF).toInt(), intent, flags)
            @Suppress("DEPRECATION")
            val ds = Dataset.Builder(pres)
            f.username?.let { ds.setValue(it, null) }
            f.password?.let { ds.setValue(it, null) }
            ds.setAuthentication(pi.intentSender)
            val resp = FillResponse.Builder().addDataset(ds.build())
            f.password?.let { pw ->
                val save = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(pw))
                f.username?.let { save.setOptionalIds(arrayOf(it)) }
                resp.setSaveInfo(save.build())
            }
            callback.onSuccess(resp.build())
        } catch (e: Exception) {
            android.util.Log.w("SWDS", "autofill: $e")
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val st = request.fillContexts.lastOrNull()?.structure
        val f = st?.let { runCatching { LoginForm.parse(it) }.getOrNull() }
        callback.onSuccess()
        if (f == null || f.passwordText.isEmpty()) return
        val app = applicationContext
        val label = appLabel(app, f.pkg)
        val item = JSONObject().put("url", f.url).put("username", f.usernameText).put("password", f.passwordText)
            .put("title", if (f.domain.isNotEmpty()) f.domain else label)
        Thread { saveLogin(app, item) }.start()
    }

    companion object {
        fun appLabel(ctx: Context, pkg: String): String = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")

        fun presentation(ctx: Context, title: String, sub: String): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.autofill_item).apply {
                setTextViewText(R.id.af_title, title)
                setTextViewText(R.id.af_sub, sub)
            }

        /** Сохранить вход в хранилище; если оно заблокировано — отложить до разблокировки и позвать человека. */
        fun saveLogin(ctx: Context, item: JSONObject): Boolean {
            val open = PendingIntent.getActivity(ctx, 3610, Intent(ctx, PassActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return try {
                if (!Pass.paired(ctx)) Pass.pair(ctx)
                val chk = Pass.rpc(ctx, "check", JSONObject().put("url", item.optString("url")).put("username", item.optString("username"))
                    .put("password", item.optString("password")))
                if (chk.optBoolean("locked")) {
                    Pass.pendingSave = item
                    Notifier.simple(ctx, 3610, "🔐 Сохранить пароль в SWDS?", item.optString("title") +
                        " — хранилище заблокировано. Коснитесь, чтобы разблокировать и сохранить.", Notifier.CH_INFO, open)
                    return false
                }
                if (chk.optBoolean("known") && chk.optBoolean("same")) return true          // уже сохранён такой же
                val r = Pass.rpc(ctx, "save", JSONObject().put("url", item.optString("url")).put("username", item.optString("username"))
                    .put("password", item.optString("password")).put("title", item.optString("title")).put("uuid", chk.optString("uuid")))
                if (r.optBoolean("locked")) { Pass.pendingSave = item; return false }
                Pass.pendingSave = null
                Notifier.simple(ctx, 3610, if (r.optBoolean("created")) "🔐 Вход сохранён в SWDS" else "🔐 Пароль обновлён в SWDS",
                    item.optString("title") + (item.optString("username").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                    Notifier.CH_INFO, open, timeoutMs = 20_000)
                true
            } catch (e: Exception) {
                Pass.pendingSave = item
                Notifier.simple(ctx, 3610, "🔐 Пароль не сохранён", (e.message ?: "Нет связи с SWDS") +
                    ". Коснитесь, чтобы сохранить позже.", Notifier.CH_INFO, open)
                false
            }
        }
    }
}

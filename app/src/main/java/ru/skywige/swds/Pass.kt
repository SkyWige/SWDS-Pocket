package ru.skywige.swds

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Канал к модулю «Пароли» SWDS — тот же, что у расширения браузера: у телефона свой 256-битный ключ,
 * каждый запрос и ответ шифруются AES-256-GCM с меткой времени и одноразовым nonce. Пароли не идут по сети
 * открытым текстом даже по обычному http в домашней сети. Сама база KeePass на телефон не копируется.
 */
object Pass {
    private const val PATH = "/modules/passwords"
    @Volatile private var offset = 0L                 // разница часов телефона и сервера, мс
    @Volatile private var offsetKnown = false
    /** Вход, который Android предложил сохранить, пока хранилище было заблокировано: {url, username, password, title}. */
    @Volatile var pendingSave: JSONObject? = null

    private fun b64e(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun paired(ctx: Context) = Prefs(ctx).passPair != null

    /** Подключить телефон: сервер (под токеном устройства) выдаёт ключ канала. */
    fun pair(ctx: Context): JSONObject {
        val p = Prefs(ctx)
        val r = try {
            Api.post(ctx, "$PATH/api/clients", JSONObject().put("name", "📱 " + p.deviceName))
        } catch (e: ApiException) {
            if (e.status == 404) throw ApiException("На сервере нет модуля «Пароли» — обновите SWDS и включите модуль", 404)
            throw e
        }
        val s = r.optString("pair")
        if (!s.startsWith("SWDS1.")) throw ApiException(r.optString("error").ifEmpty { "Модуль «Пароли» не выдал ключ" })
        val blob = JSONObject(String(b64d(s.substring(6)), Charsets.UTF_8))
        p.passPair = JSONObject().put("i", blob.getString("i")).put("k", blob.getString("k"))
        return rpc(ctx, "hello", JSONObject().put("agent", Api.UA).put("name", "📱 " + p.deviceName))
    }

    private fun syncClock(ctx: Context) {
        runCatching {
            val t = Api.get(ctx, "$PATH/ext/ping", 6000).optLong("time")
            if (t > 0) { offset = t - System.currentTimeMillis(); offsetKnown = true }
        }
    }

    /** Запрос к хранилищу. Ответ {locked: true} — хранилище заблокировано. */
    fun rpc(ctx: Context, op: String, args: JSONObject = JSONObject(), retried: Boolean = false): JSONObject {
        val pair = Prefs(ctx).passPair ?: throw ApiException("Телефон не подключён к «Паролям»")
        if (!offsetKnown) syncClock(ctx)
        val id = pair.getString("i")
        val key = SecretKeySpec(b64d(pair.getString("k")), "AES")
        val aad = id.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        val msg = JSONObject().put("t", System.currentTimeMillis() + offset).put("op", op).put("args", args)
        val ct = c.doFinal(msg.toString().toByteArray(Charsets.UTF_8))
        val res = try {
            Api.post(ctx, "$PATH/ext/rpc", JSONObject().put("id", id).put("n", b64e(nonce)).put("c", b64e(ct)), 8000, 25000)
        } catch (e: ApiException) {
            if (e.status == 401 && !retried) {
                // часы разошлись — сверяемся; ключ отозван на сервере — подключаемся заново (мы всё ещё привязаны токеном)
                if ((e.message ?: "").contains("Час")) syncClock(ctx)
                else { Prefs(ctx).passPair = null; pair(ctx) }
                return rpc(ctx, op, args, true)
            }
            if (e.status == 404) throw ApiException("На сервере нет модуля «Пароли»", 404)
            throw e
        }
        val box = res.optJSONObject("box") ?: throw ApiException(res.optString("error").ifEmpty { "Пустой ответ «Паролей»" })
        val d = Cipher.getInstance("AES/GCM/NoPadding")
        d.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64d(box.getString("n"))))
        d.updateAAD(aad)
        val data = JSONObject(String(d.doFinal(b64d(box.getString("c"))), Charsets.UTF_8))
        if (!data.optBoolean("ok")) throw ApiException(data.optString("error").ifEmpty { "Ошибка «Паролей»" })
        return data.optJSONObject("data") ?: JSONObject()
    }

    // ── буфер обмена: помечаем как секрет и стираем через N секунд ──
    private val main = Handler(Looper.getMainLooper())
    private var clearToken = 0

    fun copy(ctx: Context, text: String, secret: Boolean) {
        val app = ctx.applicationContext
        main.post {
            val cm = app.getSystemService(ClipboardManager::class.java) ?: return@post
            val clip = ClipData.newPlainText("SWDS", text)
            if (secret) {
                // Android 13+: содержимое не показывается в подсказке буфера и на клавиатуре
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
                }
            }
            runCatching { cm.setPrimaryClip(clip) }
            val sec = Prefs(app).passClipSec
            val token = ++clearToken
            if (secret && sec > 0) main.postDelayed({
                if (token != clearToken) return@postDelayed
                runCatching {
                    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }, sec * 1000L)
        }
    }
}

/**
 * Разблокировка отпечатком: мастер-пароль хранится на телефоне зашифрованным ключом из Android Keystore,
 * который работает только после подтверждения биометрией. Новый отпечаток в системе делает ключ негодным —
 * тогда мастер-пароль нужно ввести заново.
 */
object PassBio {
    private const val KEY = "swds_pass_master"

    fun available(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val bm = ctx.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                @Suppress("DEPRECATION")
                val can = bm?.canAuthenticate() ?: -1
                can == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            } else ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)
        }.getOrDefault(false)
    }

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build())
        return kg.generateKey()
    }

    fun forget(ctx: Context) {
        Prefs(ctx).passBio = ""
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY) }
    }

    private fun prompt(act: Activity, title: String, cipher: Cipher, done: (Cipher) -> Unit, fail: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 28) { fail("Нужен Android 9 или новее"); return }
        val ex = act.mainExecutor
        val bp = android.hardware.biometrics.BiometricPrompt.Builder(act)
            .setTitle(title)
            .setSubtitle("Пароли SWDS")
            .setNegativeButton("Отмена", ex) { _, _ -> fail("Отменено") }
            .build()
        bp.authenticate(android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher), CancellationSignal(), ex,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c != null) done(c) else fail("Система не вернула ключ")
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = fail(errString.toString())
            })
    }

    /** Запомнить мастер-пароль под отпечаток. Вызывать из главного потока. */
    fun remember(act: Activity, master: String, done: (Boolean, String) -> Unit) {
        try {
            forget(act)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            prompt(act, "Подтвердите отпечатком", cipher, { c ->
                runCatching {
                    val ct = c.doFinal(master.toByteArray(Charsets.UTF_8))
                    Prefs(act).passBio = Base64.encodeToString(c.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(ct, Base64.NO_WRAP)
                }.fold({ done(true, "") }, { done(false, it.message ?: "Ошибка шифрования") })
            }, { done(false, it) })
        } catch (e: Exception) {
            done(false, "Отпечаток недоступен: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Достать мастер-пароль по отпечатку. Вызывать из главного потока. */
    fun reveal(act: Activity, done: (String?, String) -> Unit) {
        val saved = Prefs(act).passBio.split(".")
        if (saved.size != 2) { done(null, "Отпечаток не настроен"); return }
        try {
            val k = key(false) ?: run { forget(act); done(null, "Ключ отпечатка удалён — введите мастер-пароль"); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, Base64.decode(saved[0], Base64.NO_WRAP)))
            prompt(act, "Разблокировать пароли", cipher, { c ->
                runCatching { String(c.doFinal(Base64.decode(saved[1], Base64.NO_WRAP)), Charsets.UTF_8) }
                    .fold({ done(it, "") }, { done(null, it.message ?: "Ошибка расшифровки") })
            }, { done(null, it) })
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            forget(act)
            done(null, "В системе изменились отпечатки — введите мастер-пароль и включите отпечаток заново")
        } catch (e: Exception) {
            done(null, "Отпечаток недоступен: " + (e.message ?: e.javaClass.simpleName))
        }
    }
}

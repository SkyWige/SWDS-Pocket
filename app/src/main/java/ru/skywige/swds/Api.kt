package ru.skywige.swds

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ApiException(message: String, val status: Int = 0) : IOException(message)

/**
 * HTTP-клиент SWDS. Перебирает адреса сервера (домашняя сеть → внешний адрес) и запоминает рабочий.
 * Авторизация — токен устройства (выдаётся при привязке), заголовок Authorization: Bearer.
 */
object Api {
    const val UA = "SWDSPocket/" + BuildConfig.VERSION_NAME

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun bases(p: Prefs): List<String> {
        val all = p.servers
        val first = p.activeServer.takeIf { it.isNotEmpty() && it in all }
        return listOfNotNull(first) + all.filter { it != first }
    }

    /** Самоподписанный сертификат SWDS (HTTPS на домашнем сервере) — доверяем только нашим адресам. */
    private fun relaxTls(conn: HttpURLConnection) {
        if (conn !is HttpsURLConnection) return
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    fun open(base: String, path: String, method: String, token: String?, timeoutMs: Int = 15000,
             readTimeoutMs: Int = 30000): HttpURLConnection {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        relaxTls(conn)
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    /** Выполнить запрос с перебором адресов. block получает соединение и возвращает результат. */
    fun <T> withServer(ctx: Context, path: String, method: String, timeoutMs: Int = 12000, readTimeoutMs: Int = 30000,
                       block: (HttpURLConnection) -> T): T {
        try {
            return attempt(ctx, path, method, timeoutMs, readTimeoutMs, block)
        } catch (e: ApiException) {
            // ни один адрес не ответил — может, роутер выдал компьютеру новый IP: ищем SWDS в сети и пробуем ещё раз
            if (e.status == 0 && Prefs(ctx).paired && Discovery.heal(ctx)) return attempt(ctx, path, method, timeoutMs, readTimeoutMs, block)
            throw e
        }
    }

    private fun <T> attempt(ctx: Context, path: String, method: String, timeoutMs: Int, readTimeoutMs: Int,
                            block: (HttpURLConnection) -> T): T {
        val p = Prefs(ctx)
        val list = bases(p)
        if (list.isEmpty()) throw ApiException("Телефон не привязан к SWDS")
        var last: Exception? = null
        for (base in list) {
            try {
                val conn = open(base, path, method, p.token, timeoutMs, readTimeoutMs)
                val r = block(conn)
                if (p.activeServer != base) p.activeServer = base
                return r
            } catch (e: ApiException) {
                if (e.status in 400..599) throw e     // сервер ответил — другой адрес не поможет
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw ApiException("Нет связи с SWDS: " + (last?.message ?: "все адреса недоступны"))
    }

    private fun readAll(s: InputStream?): String {
        if (s == null) return ""
        val out = ByteArrayOutputStream()
        s.use { it.copyTo(out) }
        return out.toString("UTF-8")
    }

    private fun result(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val body = readAll(if (code >= 400) conn.errorStream else conn.inputStream)
        conn.disconnect()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (code == 401 || code == 403) throw ApiException(json?.optString("error")?.takeIf { it.isNotEmpty() }
            ?: "Нет доступа (токен устройства отозван?)", code)
        if (code >= 400) throw ApiException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Ошибка сервера $code", code)
        return json ?: JSONObject().put("ok", true).put("raw", body)
    }

    fun get(ctx: Context, path: String, timeoutMs: Int = 12000): JSONObject =
        withServer(ctx, path, "GET", timeoutMs) { result(it) }

    fun post(ctx: Context, path: String, body: JSONObject? = null, timeoutMs: Int = 12000,
             readTimeoutMs: Int = 60000): JSONObject =
        withServer(ctx, path, "POST", timeoutMs, readTimeoutMs) { conn ->
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            result(conn)
        }

    /** Выполнить команду SWDS (как /api/execute). */
    fun execute(ctx: Context, command: String, args: JSONObject = JSONObject()): JSONObject {
        val r = post(ctx, "/api/execute", JSONObject().put("name", command).put("args", args))
        val res = r.optJSONObject("result") ?: JSONObject()
        if (!r.optBoolean("ok", true) || res.optString("status") == "error")
            throw ApiException(r.optString("error").ifEmpty { res.optString("error", "Ошибка команды") })
        return res
    }

    /**
     * Загрузка файла multipart/form-data (поле file + текстовые поля). Потоковая — без чтения в память.
     * onProgress(отправлено, всего) — всего может быть -1.
     */
    fun upload(ctx: Context, path: String, fileName: String, mime: String, size: Long, input: () -> InputStream,
               fields: Map<String, String> = emptyMap(), fileField: String = "file",
               onProgress: ((Long, Long) -> Unit)? = null): JSONObject =
        withServer(ctx, path, "POST", 15000, 300000) { conn ->
            val boundary = "----swds" + System.currentTimeMillis()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(256 * 1024)
            conn.outputStream.use { out ->
                for ((k, v) in fields) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                    out.write(v.toByteArray(Charsets.UTF_8)); out.write("\r\n".toByteArray())
                }
                val safe = fileName.replace("\"", "'").replace("\r", "").replace("\n", "")
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$safe\"\r\n" +
                        "Content-Type: ${mime.ifEmpty { "application/octet-stream" }}\r\n\r\n").toByteArray(Charsets.UTF_8))
                var sent = 0L
                var lastReport = 0L
                input().use { inp ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        if (onProgress != null && sent - lastReport > 256 * 1024) { onProgress(sent, size); lastReport = sent }
                    }
                }
                onProgress?.invoke(sent, size)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            result(conn)
        }

    /** Скачать файл: block получает поток, имя файла (из Content-Disposition) и тип. */
    fun <T> download(ctx: Context, path: String, block: (InputStream, String, String, Long) -> T): T =
        withServer(ctx, path, "GET", 15000, 300000) { conn ->
            val code = conn.responseCode
            if (code >= 400) { result(conn); throw ApiException("Ошибка $code", code) }
            val cd = conn.getHeaderField("Content-Disposition") ?: ""
            val name = Regex("filename\\*=UTF-8''([^;]+)").find(cd)?.groupValues?.get(1)?.let { Uri.decode(it) }
                ?: Regex("filename=\"?([^\";]+)\"?").find(cd)?.groupValues?.get(1)
                ?: path.substringAfterLast('/').substringBefore('?')
            val r = conn.inputStream.use { block(it, name, conn.contentType ?: "application/octet-stream", conn.contentLengthLong) }
            conn.disconnect()
            r
        }

    /** Привязка: без токена, по коду с экрана SWDS. */
    fun pair(base: String, code: String, name: String, model: String): JSONObject {
        val conn = open(base.trimEnd('/'), "/modules/mobile/api/pair", "POST", null, 10000, 20000)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val body = JSONObject().put("code", code.trim()).put("name", name).put("model", model)
            .put("app_version", BuildConfig.VERSION_NAME)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return result(conn)
    }
}

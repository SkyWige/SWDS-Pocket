package ru.skywige.swds

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Отправить в SWDS» из любого приложения (фото, документы, ссылки, текст) и кнопка «📤 Отправить файл»:
 * выбор, куда положить на компьютере — общая папка, стандартные папки (Документы, Фото…) или «🪄 разложить само».
 */
class ShareActivity : LocalPageActivity() {
    override val page = "share"
    private val uris = ArrayList<Uri>()
    private var text: String = ""
    @Volatile private var uploading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        readIntent(intent)
        super.onCreate(savedInstanceState)
        if (!Prefs(this).paired) {
            startActivity(Intent(this, PairActivity::class.java))
            finish(); return
        }
        if (intent?.action == A_PICK) pick()
    }

    @Suppress("DEPRECATION")
    private fun readIntent(i: Intent?) {
        uris.clear(); text = ""
        when (i?.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
                text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
                    .filter { it.isNotBlank() }.distinct().joinToString("\n")
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                list?.let { uris.addAll(it) }
            }
        }
    }

    private fun items(): JSONArray {
        val a = JSONArray()
        for (u in uris) {
            val f = Files.info(this, u)
            a.put(JSONObject().put("name", f.name).put("size", f.size).put("mime", f.mime).put("human", Files.human(f.size)))
        }
        return a
    }

    override fun extraState(): JSONObject = JSONObject().put("items", items()).put("text", text)

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), RC_FILES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_FILES) {
            if (resultCode != RESULT_OK || data == null) { if (uris.isEmpty() && text.isEmpty()) finish(); return }
            data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
            data.data?.let { if (it !in uris) uris.add(it) }
            emit("items", JSONObject().put("items", items()))
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? = when (method) {
        "pick" -> { main.post { pick() }; ok() }
        "remove" -> { val i = a.optInt("index", -1); if (i in uris.indices) uris.removeAt(i); ok(JSONObject().put("items", items())) }
        "upload" -> upload(a)
        "send_text" -> {
            val r = Api.post(this, "/modules/mobile/api/text", JSONObject().put("text", a.optString("text")).put("action", a.optString("action")))
            if (r.optBoolean("ok")) ok(r) else err(r.optString("error"))
        }
        else -> super.handle(id, method, a)
    }

    override fun onBackPressed() {
        if (uploading) { moveTaskToBack(true); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** {dest: shared|auto|place:key, path} → загрузка по одному с прогрессом. */
    private fun upload(a: JSONObject): JSONObject {
        if (uris.isEmpty()) return err("Нечего отправлять")
        uploading = true
        val dest = a.optString("dest", "shared")
        val path = a.optString("path").trim('/')
        val results = JSONArray()
        var okCount = 0
        try {
            for ((i, u) in uris.withIndex()) {
                val f = Files.info(this, u)
                val fields = mutableMapOf("dest" to dest)
                if (path.isNotEmpty()) fields["path"] = path
                if (a.optString("hint").isNotEmpty()) fields["hint"] = a.optString("hint")
                try {
                    val r = Api.upload(this, "/modules/mobile/api/files/upload", f.name, f.mime, f.size,
                        { contentResolver.openInputStream(u) ?: throw ApiException("Нет доступа к файлу ${f.name}") }, fields) { done, total ->
                        emit("progress", JSONObject().put("index", i).put("done", done).put("total", total))
                        Notifier.progress(this, Notifier.ID_UPLOAD, "⬆ ${f.name} (${i + 1}/${uris.size})",
                            Files.human(done) + " из " + Files.human(total), done, total)
                    }
                    okCount++
                    results.put(JSONObject().put("name", f.name).put("ok", true).put("where", r.optString("where"))
                        .put("note", r.optString("note")))
                    emit("done", JSONObject().put("index", i).put("ok", true).put("where", r.optString("where")))
                } catch (e: Exception) {
                    results.put(JSONObject().put("name", f.name).put("ok", false).put("error", e.message))
                    emit("done", JSONObject().put("index", i).put("ok", false).put("error", e.message))
                }
            }
        } finally {
            uploading = false
            Notifier.cancel(this, Notifier.ID_UPLOAD)
        }
        val msg = if (okCount == uris.size) "Отправлено в SWDS: $okCount" else "Отправлено $okCount из ${uris.size}"
        Notifier.simple(this, Notifier.ID_UPLOAD + 100, "📤 $msg",
            results.optJSONObject(0)?.optString("where")?.takeIf { it.isNotEmpty() } ?: "", Notifier.CH_FILES, timeoutMs = 30000)
        SwdsWidget.refreshKind(this, "files")
        return ok(JSONObject().put("results", results).put("message", msg).put("all_ok", okCount == uris.size))
    }

    companion object {
        const val A_PICK = "ru.skywige.swds.PICK_SEND"
    }
}

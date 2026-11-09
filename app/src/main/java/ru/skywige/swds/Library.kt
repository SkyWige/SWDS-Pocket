package ru.skywige.swds

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Библиотека на телефоне: книги из SWDS («Библиотека» на сервере), скачанные для чтения без связи.
 *
 *  filesDir/library/index.json        — список книг с сервера + что скачано (pack_ver) + место чтения
 *  filesDir/library/<id>/book.json    — пакет книги: главы ch_*.html, assets/, cover.jpg, book.pdf
 *  filesDir/library/<id>/marks.json   — закладки и цитаты этой книги (локальная копия)
 *  filesDir/library/outbox.json       — прогресс и закладки, которые ещё не ушли на сервер
 *
 * Какие книги скачивать, решает сервер (папки и полки с отметкой «📱 На телефон» и книги «Читаю»).
 * Место чтения: побеждает более свежее — телефон без связи не затирает то, что читали на ПК позже.
 */
object Library {
    private val lock = Any()
    fun dir(ctx: Context) = File(ctx.filesDir, "library").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")
    private fun outboxFile(ctx: Context) = File(dir(ctx), "outbox.json")
    fun bookDir(ctx: Context, id: String) = File(dir(ctx), safe(id))
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() }.take(40)

    @Volatile var status: String = ""
    /** Книга, которая скачивается прямо сейчас (для индикатора на обложке). */
    @Volatile var downloading: String = ""
    /** Книга, которую пользователь открыл, а её ещё нет — скачивается первой. */
    @Volatile var wanted: String = ""
    @Volatile var progressDone = 0
    @Volatile var progressTotal = 0

    // ───────────────────────── файлы ─────────────────────────
    private fun readJson(f: File, def: String): JSONObject =
        runCatching { if (f.exists()) JSONObject(f.readText()) else JSONObject(def) }.getOrDefault(JSONObject(def))

    private fun writeJson(f: File, j: JSONObject) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(f)) { f.writeText(j.toString()); tmp.delete() }
    }

    fun index(ctx: Context): JSONObject = synchronized(lock) { readJson(indexFile(ctx), "{\"books\":{}}") }
    private fun saveIndex(ctx: Context, j: JSONObject) = synchronized(lock) { writeJson(indexFile(ctx), j) }

    fun book(ctx: Context, id: String): JSONObject? = index(ctx).optJSONObject("books")?.optJSONObject(id)

    fun isLocal(ctx: Context, id: String): Boolean {
        val b = book(ctx, id) ?: return false
        return b.optString("local_ver").isNotEmpty() && File(bookDir(ctx, id), "book.json").exists()
    }

    fun pack(ctx: Context, id: String): JSONObject? =
        File(bookDir(ctx, id), "book.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    fun chapter(ctx: Context, id: String, n: Int): String {
        val pk = pack(ctx, id) ?: return ""
        val f = pk.optJSONArray("chapters")?.optJSONObject(n)?.optString("file") ?: return ""
        if (f.isEmpty() || f.contains("..") || f.contains("/")) return ""
        val file = File(bookDir(ctx, id), f)
        return if (file.exists()) file.readText() else ""
    }

    fun marks(ctx: Context, id: String): JSONArray =
        readJson(File(bookDir(ctx, id), "marks.json"), "{\"marks\":[]}").optJSONArray("marks") ?: JSONArray()

    private fun saveMarks(ctx: Context, id: String, arr: JSONArray) {
        val d = bookDir(ctx, id).apply { mkdirs() }
        writeJson(File(d, "marks.json"), JSONObject().put("marks", arr))
    }

    /** Список для экрана библиотеки: книги + обложки (file://) + где остановились. */
    fun list(ctx: Context): JSONObject {
        val idx = index(ctx)
        val books = idx.optJSONObject("books") ?: JSONObject()
        val out = JSONArray()
        var bytes = 0L
        for (k in books.keys()) {
            val b = books.getJSONObject(k)
            val d = bookDir(ctx, k)
            val local = b.optString("local_ver").isNotEmpty() && File(d, "book.json").exists()
            val cover = coverFile(d)
            if (local) bytes += dirSize(d)
            out.put(JSONObject(b.toString()).put("id", k).put("local", local).put("downloading", k == downloading)
                .put("cover_file", cover?.let { "file://" + it.absolutePath } ?: ""))
        }
        return JSONObject().put("books", out).put("bytes", bytes).put("synced_at", idx.optLong("synced_at"))
            .put("status", status).put("done", progressDone).put("total", progressTotal).put("wanted", wanted)
            .put("outbox", readJson(outboxFile(ctx), "{}").let { (it.optJSONArray("progress")?.length() ?: 0) + (it.optJSONArray("marks")?.length() ?: 0) })
            .put("shelves", idx.optJSONArray("shelves") ?: JSONArray())
    }

    private val COVER_EXT = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "svg")

    /** Обложка книги: из пакета или скачанная отдельно миниатюра (для ещё не скачанных книг). */
    fun coverFile(d: File): File? = COVER_EXT.map { File(d, "cover.$it") }.firstOrNull { it.exists() && it.length() > 0 }

    /** Обложки книг, которых ещё нет на телефоне: маленькие картинки, чтобы список выглядел как в SWDS. */
    private fun fetchCovers(ctx: Context, list: JSONArray) {
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val url = s.optString("cover")
            if (url.isEmpty()) continue
            val d = bookDir(ctx, s.optString("id"))
            if (coverFile(d) != null) continue
            runCatching {
                d.mkdirs()
                Api.download(ctx, url) { input, _, type, _ ->
                    val ext = when {
                        type.contains("png") -> "png"
                        type.contains("webp") -> "webp"
                        type.contains("gif") -> "gif"
                        type.contains("svg") -> "svg"
                        type.contains("avif") -> "avif"
                        else -> "jpg"
                    }
                    val tmp = File(d, ".cover.tmp")
                    tmp.outputStream().use { input.copyTo(it) }
                    if (tmp.length() > 0) tmp.renameTo(File(d, "cover.$ext")) else tmp.delete()
                }
            }.onFailure { android.util.Log.w("SWDS", "library cover ${s.optString("id")}: $it") }
        }
    }

    private fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ───────────────────────── чтение на телефоне ─────────────────────────
    fun saveProgress(ctx: Context, id: String, a: JSONObject) {
        synchronized(lock) {
            val idx = index(ctx)
            val b = idx.optJSONObject("books")?.optJSONObject(id) ?: return
            val ts = a.optDouble("ts", System.currentTimeMillis() / 1000.0)
            if (ts >= b.optDouble("loc_ts", 0.0)) {
                b.put("progress", a.optDouble("progress", 0.0)).put("locator", a.optString("locator")).put("loc_ts", ts)
                if (b.optString("status") in setOf("", "new") && a.optDouble("progress", 0.0) > 0) b.put("status", "reading")
            }
            b.put("opened_at", ts)
            saveIndex(ctx, idx)
            val ob = readJson(outboxFile(ctx), "{}")
            val arr = ob.optJSONArray("progress") ?: JSONArray()
            // одна запись на книгу, секунды и знаки складываем
            var merged = false
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                if (p.optString("id") == id) {
                    p.put("progress", a.optDouble("progress")).put("locator", a.optString("locator")).put("ts", ts)
                        .put("seconds", p.optInt("seconds") + a.optInt("seconds")).put("chars", p.optInt("chars") + a.optInt("chars"))
                    merged = true
                }
            }
            if (!merged) arr.put(JSONObject().put("id", id).put("progress", a.optDouble("progress")).put("locator", a.optString("locator"))
                .put("ts", ts).put("seconds", a.optInt("seconds")).put("chars", a.optInt("chars")))
            writeJson(outboxFile(ctx), ob.put("progress", arr))
        }
    }

    fun saveMark(ctx: Context, id: String, m: JSONObject) {
        synchronized(lock) {
            m.put("book_id", id)
            if (!m.has("updated")) m.put("updated", System.currentTimeMillis() / 1000.0)
            val arr = marks(ctx, id)
            val out = JSONArray()
            var found = false
            for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                if (x.optString("id") == m.optString("id")) { out.put(m); found = true } else out.put(x)
            }
            if (!found) out.put(m)
            saveMarks(ctx, id, out)
            val ob = readJson(outboxFile(ctx), "{}")
            val q = ob.optJSONArray("marks") ?: JSONArray()
            q.put(m)
            writeJson(outboxFile(ctx), ob.put("marks", q))
        }
    }

    /** Поиск по скачанной книге — без сервера. */
    fun search(ctx: Context, id: String, query: String): JSONArray {
        val out = JSONArray()
        val q = query.trim().lowercase()
        if (q.length < 2) return out
        val pk = pack(ctx, id) ?: return out
        val chs = pk.optJSONArray("chapters") ?: return out
        val tag = Regex("<[^>]+>")
        val ws = Regex("\\s+")
        for (n in 0 until chs.length()) {
            val file = chs.optJSONObject(n)?.optString("file") ?: continue
            if (file.isEmpty()) continue
            val text = ws.replace(android.text.Html.fromHtml(tag.replace(chapter(ctx, id, n), " ").let { it }, 0).toString(), " ")
            val low = text.lowercase()
            var i = low.indexOf(q)
            var k = 0
            while (i >= 0 && k < 30 && out.length() < 200) {
                val s = maxOf(0, i - 70)
                val e = minOf(text.length, i + q.length + 70)
                out.put(JSONObject().put("ch", n).put("snip", text.substring(s, i) + "[[" + text.substring(i, i + q.length) + "]]" + text.substring(i + q.length, e)))
                i = low.indexOf(q, i + q.length)
                k++
            }
        }
        return out
    }

    fun delete(ctx: Context, id: String) {
        bookDir(ctx, id).deleteRecursively()
        synchronized(lock) {
            val idx = index(ctx)
            idx.optJSONObject("books")?.optJSONObject(id)?.remove("local_ver")
            idx.optJSONObject("books")?.optJSONObject(id)?.put("removed", true)
            saveIndex(ctx, idx)
        }
    }

    fun clearAll(ctx: Context) {
        synchronized(lock) {
            dir(ctx).listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() }
            val idx = index(ctx)
            val books = idx.optJSONObject("books") ?: JSONObject()
            for (k in books.keys()) books.getJSONObject(k).remove("local_ver")
            saveIndex(ctx, idx)
        }
    }

    // ───────────────────────── синхронизация ─────────────────────────
    /** Отправить прогресс/закладки и получить свежие с сервера. Без скачивания книг. */
    fun pushPull(ctx: Context) {
        val ob = synchronized(lock) { readJson(outboxFile(ctx), "{}") }
        val ids = JSONArray()
        index(ctx).optJSONObject("books")?.keys()?.forEach { ids.put(it) }
        val body = JSONObject().put("progress", ob.optJSONArray("progress") ?: JSONArray())
            .put("marks", ob.optJSONArray("marks") ?: JSONArray()).put("books", ids)
        val r = Api.post(ctx, "/modules/library/api/sync/push", body, 30000)
        if (!r.optBoolean("ok")) throw ApiException(r.optString("error", "Библиотека недоступна"), 0)
        synchronized(lock) {
            // убираем отправленное (то, что добавилось во время запроса, остаётся)
            val now = readJson(outboxFile(ctx), "{}")
            fun minus(key: String, sent: JSONArray?, same: (JSONObject, JSONObject) -> Boolean): JSONArray {
                val cur = now.optJSONArray(key) ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until cur.length()) {
                    val x = cur.getJSONObject(i)
                    var gone = false
                    if (sent != null) for (j in 0 until sent.length()) if (same(x, sent.getJSONObject(j))) { gone = true; break }
                    if (!gone) out.put(x)
                }
                return out
            }
            writeJson(outboxFile(ctx), JSONObject()
                .put("progress", minus("progress", ob.optJSONArray("progress")) { a, b -> a.optString("id") == b.optString("id") && a.optDouble("ts") == b.optDouble("ts") })
                .put("marks", minus("marks", ob.optJSONArray("marks")) { a, b -> a.optString("id") == b.optString("id") && a.optDouble("updated") == b.optDouble("updated") }))
            val srv = r.optJSONObject("books") ?: JSONObject()
            val idx = index(ctx)
            val books = idx.optJSONObject("books") ?: JSONObject()
            for (k in srv.keys()) {
                val s = srv.getJSONObject(k)
                val b = books.optJSONObject(k) ?: continue
                if (s.optDouble("loc_ts", 0.0) > b.optDouble("loc_ts", 0.0)) {
                    b.put("progress", s.optDouble("progress")).put("locator", s.optString("locator")).put("loc_ts", s.optDouble("loc_ts"))
                }
                if (s.optString("status").isNotEmpty()) b.put("status", s.optString("status"))
                val sm = s.optJSONArray("marks")
                if (sm != null && bookDir(ctx, k).exists()) {
                    // слияние закладок: по id, свежее побеждает
                    val loc = marks(ctx, k)
                    val map = LinkedHashMap<String, JSONObject>()
                    for (i in 0 until loc.length()) loc.getJSONObject(i).let { map[it.optString("id")] = it }
                    for (i in 0 until sm.length()) {
                        val m = sm.getJSONObject(i)
                        val old = map[m.optString("id")]
                        if (old == null || m.optDouble("updated") >= old.optDouble("updated")) map[m.optString("id")] = m
                    }
                    saveMarks(ctx, k, JSONArray(map.values.toList()))
                }
            }
            saveIndex(ctx, idx)
        }
    }

    private fun metered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    /** Полная синхронизация: прогресс ⇄, список книг, скачивание новых и изменившихся, уборка лишнего. */
    fun sync(ctx: Context, manual: Boolean = false): JSONObject {
        val p = Prefs(ctx)
        if (!p.paired) return JSONObject().put("ok", false).put("error", "Телефон не привязан")
        if (!p.libraryEnabled && !manual) return JSONObject().put("ok", true).put("skipped", true)
        status = "Обмен прогрессом…"
        runCatching { pushPull(ctx) }
        status = "Список книг…"
        val man = Api.get(ctx, "/modules/library/api/sync/manifest", 30000)
        if (!man.optBoolean("ok")) { status = ""; return JSONObject().put("ok", false).put("error", man.optString("error", "Модуль «Библиотека» недоступен")) }
        val list = man.optJSONArray("books") ?: JSONArray()
        val keep = HashSet<String>()
        synchronized(lock) {
            val idx = index(ctx)
            val books = idx.optJSONObject("books") ?: JSONObject().also { idx.put("books", it) }
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                val id = s.optString("id")
                keep.add(id)
                val b = books.optJSONObject(id) ?: JSONObject().also { books.put(id, it) }
                for (k in listOf("title", "author", "series", "series_no", "fmt", "kind", "size", "pack_ver", "pack_url", "chars", "pages",
                                 "description", "why", "rating", "favorite", "shelves", "year")) if (s.has(k)) b.put(k, s.get(k))
                if (s.optDouble("loc_ts", 0.0) > b.optDouble("loc_ts", 0.0)) {
                    b.put("progress", s.optDouble("progress")).put("locator", s.optString("locator")).put("loc_ts", s.optDouble("loc_ts"))
                }
                if (s.optString("status").isNotEmpty()) b.put("status", s.optString("status"))
                if (id == wanted) b.remove("removed")             // удалённую с телефона книгу снова качаем, только если её открыли
            }
            // книги, которых больше нет в списке для телефона, — убираем (кроме тех, что читаются прямо сейчас)
            val drop = books.keys().asSequence().filter { it !in keep }.toList()
            for (k in drop) {
                val b = books.getJSONObject(k)
                val recent = System.currentTimeMillis() / 1000.0 - b.optDouble("opened_at", 0.0) < 3 * 86400
                if (recent && b.optString("local_ver").isNotEmpty()) { b.put("why", "kept"); continue }
                bookDir(ctx, k).deleteRecursively()
                books.remove(k)
            }
            idx.put("shelves", man.optJSONArray("shelves") ?: JSONArray()).put("synced_at", System.currentTimeMillis())
            saveIndex(ctx, idx)
        }
        status = "Обложки…"
        fetchCovers(ctx, list)
        if (p.libraryWifiOnly && metered(ctx) && !manual) { status = ""; return JSONObject().put("ok", true).put("waiting", "wifi") }
        // скачивание: сначала «читаю», потом остальное
        val todo = ArrayList<JSONObject>()
        for (i in 0 until list.length()) {
            val s = list.getJSONObject(i)
            val b = book(ctx, s.optString("id")) ?: continue
            if (b.optBoolean("removed") && s.optString("id") != wanted) continue
            if (b.optString("local_ver") != s.optString("pack_ver") || !File(bookDir(ctx, s.optString("id")), "book.json").exists()) todo.add(s)
        }
        todo.sortBy { if (it.optString("id") == wanted) -1 else if (it.optString("status") == "reading") 0 else 1 }
        progressTotal = todo.size
        progressDone = 0
        var got = 0
        var failed = 0
        for (s in todo) {
            val id = s.optString("id")
            status = "Скачиваю: " + s.optString("title").take(40)
            downloading = id
            try {
                download(ctx, id, s.optString("pack_url"))
                synchronized(lock) {
                    val idx = index(ctx)
                    idx.optJSONObject("books")?.optJSONObject(id)?.put("local_ver", s.optString("pack_ver"))?.put("downloaded_at", System.currentTimeMillis())
                        ?.remove("removed")
                    saveIndex(ctx, idx)
                }
                got++
            } catch (e: Exception) {
                android.util.Log.w("SWDS", "library download $id: $e")
                failed++
            }
            progressDone++
        }
        downloading = ""
        if (wanted.isNotEmpty() && isLocal(ctx, wanted)) wanted = ""
        status = ""
        return JSONObject().put("ok", true).put("downloaded", got).put("failed", failed).put("books", list.length())
    }

    private fun download(ctx: Context, id: String, url: String) {
        val tmp = File(dir(ctx), ".dl_" + safe(id))
        tmp.deleteRecursively()
        tmp.mkdirs()
        val root = tmp.canonicalPath + File.separator
        Api.download(ctx, url) { input, _, _, _ ->
            ZipInputStream(input.buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(root)) continue   // защита от «../» в архиве
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        if (!File(tmp, "book.json").exists()) { tmp.deleteRecursively(); throw IllegalStateException("Пустой пакет книги") }
        val dst = bookDir(ctx, id)
        val marks = File(dst, "marks.json").takeIf { it.exists() }?.readText()
        dst.deleteRecursively()
        if (!tmp.renameTo(dst)) { tmp.copyRecursively(dst, true); tmp.deleteRecursively() }
        if (marks != null) File(dst, "marks.json").writeText(marks)
    }

    /** Файл для «Открыть в другом приложении»: PDF или исходник (DjVu, MOBI…). */
    fun originalFile(ctx: Context, id: String): File? {
        val d = bookDir(ctx, id)
        return d.listFiles()?.firstOrNull { it.name == "book.pdf" || it.name.startsWith("original.") }
    }
}

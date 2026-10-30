package ru.skywige.swds

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/**
 * Синхронизация папок телефона и SWDS — как в Syncthing.
 *
 * Для каждой папки помним, как файл выглядел на обеих сторонах после прошлой синхронизации («база»).
 * Сравнивая с базой, понятно, кто что поменял:
 *   изменился только на телефоне → отправить; только на компьютере → скачать;
 *   на обеих → конфликт: побеждает более новый, другой сохраняется как «имя.sync-conflict-…»;
 *   удалён на одной стороне и не менялся на другой → удалить и там (в корзину/версии, не насовсем).
 * Повторная отправка того же файла ничего не дублирует: файл кладётся ПОВЕРХ по тому же пути.
 *
 * Режимы: two_way — в обе стороны; upload — только с телефона (резервная копия, ничего не удаляет);
 * download — только с компьютера.
 */
object FolderSync {
    const val TWO_WAY = "two_way"
    const val UPLOAD = "upload"
    const val DOWNLOAD = "download"
    private const val MTIME_TOL_MS = 2000L
    private const val TRASH = ".swds-trash"
    private val busy = ReentrantLock()

    data class LFile(val rel: String, val size: Long, val mtime: Long, val docId: String)
    data class RFile(val rel: String, val size: Long, val mtime: Double)
    data class Base(val lsize: Long, val lmtime: Long, val rsize: Long, val rmtime: Double)

    class Stats {
        var up = 0; var down = 0; var delLocal = 0; var delRemote = 0; var conflicts = 0
        val errors = ArrayList<String>()
        fun total() = up + down + delLocal + delRemote
        fun text(): String = listOfNotNull(
            if (up > 0) "⬆ $up" else null, if (down > 0) "⬇ $down" else null,
            if (delLocal + delRemote > 0) "🗑 ${delLocal + delRemote}" else null,
            if (conflicts > 0) "⚠ конфликтов: $conflicts" else null).joinToString(" · ")
    }

    // ───────────────────────── база (SQLite) ─────────────────────────
    private class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "folder_sync.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE base (rule TEXT NOT NULL, rel TEXT NOT NULL, lsize INTEGER, lmtime INTEGER, " +
                    "rsize INTEGER, rmtime REAL, PRIMARY KEY (rule, rel))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Volatile private var db: Db? = null
    private fun db(ctx: Context): SQLiteDatabase =
        (db ?: synchronized(this) { db ?: Db(ctx).also { db = it } }).writableDatabase

    private fun loadBase(ctx: Context, rule: String): HashMap<String, Base> {
        val out = HashMap<String, Base>()
        db(ctx).query("base", arrayOf("rel", "lsize", "lmtime", "rsize", "rmtime"), "rule=?", arrayOf(rule), null, null, null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = Base(c.getLong(1), c.getLong(2), c.getLong(3), c.getDouble(4))
        }
        return out
    }

    private fun putBase(ctx: Context, rule: String, rel: String, b: Base) {
        val cv = ContentValues().apply {
            put("rule", rule); put("rel", rel); put("lsize", b.lsize); put("lmtime", b.lmtime)
            put("rsize", b.rsize); put("rmtime", b.rmtime)
        }
        db(ctx).insertWithOnConflict("base", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun dropBase(ctx: Context, rule: String, rel: String) {
        db(ctx).delete("base", "rule=? AND rel=?", arrayOf(rule, rel))
    }

    fun forgetRule(ctx: Context, rule: String) {
        db(ctx).delete("base", "rule=?", arrayOf(rule))
    }

    fun baseCount(ctx: Context, rule: String): Int =
        db(ctx).rawQuery("SELECT COUNT(*) FROM base WHERE rule=?", arrayOf(rule)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Старый формат (synced_<id> в настройках, только отправка) → база. Тогда ничего не уйдёт повторно. */
    private fun migrate(ctx: Context, p: Prefs, ruleId: String) {
        val old = p.syncedState(ruleId)
        if (old.length() == 0) return
        val d = db(ctx)
        d.beginTransaction()
        try {
            for (key in old.keys()) {
                val parts = key.split("|")
                if (parts.size < 3) continue
                val mtime = parts[parts.size - 1].toLongOrNull() ?: continue
                val size = parts[parts.size - 2].toLongOrNull() ?: continue
                val rel = parts.dropLast(2).joinToString("|")
                putBase(ctx, ruleId, rel, Base(size, mtime, size, 0.0))
            }
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
        p.clearSyncedState(ruleId)
    }

    // ───────────────────────── телефон (SAF) ─────────────────────────
    private fun skip(name: String) = name.startsWith(".") || name.endsWith(".swds-tmp") || name.endsWith(".part")

    class Local(val tree: Uri, val files: HashMap<String, LFile>, val dirs: HashMap<String, String>)

    /** Все файлы выбранной папки одним запросом на каталог (DocumentFile делает запрос на каждое поле). */
    private fun scanLocal(ctx: Context, tree: Uri): Local {
        val files = HashMap<String, LFile>()
        val dirs = HashMap<String, String>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        dirs[""] = rootId
        val cr = ctx.contentResolver
        val proj = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        fun walk(docId: String, prefix: String, depth: Int) {
            if (depth > 10 || files.size > 50_000) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val sub = ArrayList<Pair<String, String>>()
            cr.query(children, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (skip(name)) continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) sub.add(id to name)
                    else files[prefix + name] = LFile(prefix + name, if (c.isNull(3)) 0 else c.getLong(3),
                        if (c.isNull(4)) 0 else c.getLong(4), id)
                }
            } ?: throw ApiException("Нет доступа к папке на телефоне — выберите её заново в настройках")
            for ((id, name) in sub) {
                dirs[prefix + name] = id
                walk(id, "$prefix$name/", depth + 1)
            }
        }
        walk(rootId, "", 0)
        return Local(tree, files, dirs)
    }

    private fun docUri(l: Local, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(l.tree, docId)

    private fun stat(ctx: Context, uri: Uri): Pair<Long, Long> {
        ctx.contentResolver.query(uri, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (if (c.isNull(0)) 0L else c.getLong(0)) to (if (c.isNull(1)) 0L else c.getLong(1))
        }
        return 0L to 0L
    }

    /** Папка на телефоне по относительному пути (создаётся при необходимости). */
    private fun ensureDir(ctx: Context, l: Local, relDir: String): String {
        if (relDir.isEmpty()) return l.dirs[""]!!
        l.dirs[relDir]?.let { return it }
        val parent = ensureDir(ctx, l, relDir.substringBeforeLast('/', ""))
        val name = relDir.substringAfterLast('/')
        val uri = DocumentsContract.createDocument(ctx.contentResolver, docUri(l, parent), Document.MIME_TYPE_DIR, name)
            ?: throw ApiException("Не удалось создать папку $relDir на телефоне")
        val id = DocumentsContract.getDocumentId(uri)
        l.dirs[relDir] = id
        return id
    }

    /** Записать содержимое в файл телефона (новый или поверх). Возвращает docId. */
    private fun writeLocal(ctx: Context, l: Local, rel: String, existingId: String?, src: File): String {
        val cr = ctx.contentResolver
        val id = existingId ?: run {
            val parent = ensureDir(ctx, l, rel.substringBeforeLast('/', ""))
            val uri = DocumentsContract.createDocument(cr, docUri(l, parent), "application/octet-stream", rel.substringAfterLast('/'))
                ?: throw ApiException("Не удалось создать файл ${rel.substringAfterLast('/')} на телефоне")
            DocumentsContract.getDocumentId(uri)
        }
        val uri = docUri(l, id)
        try {
            val out = runCatching { cr.openOutputStream(uri, "wt") }.getOrNull() ?: cr.openOutputStream(uri, "w")
            ?: throw ApiException("Нет доступа на запись: $rel")
            out.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
        } catch (e: Exception) {
            if (existingId == null) runCatching { DocumentsContract.deleteDocument(cr, uri) }
            throw e
        }
        return id
    }

    /** Удалённое на компьютере — не насовсем, а в скрытую «.swds-trash» этой папки (30 дней). */
    private fun trashLocal(ctx: Context, l: Local, f: LFile) {
        val cr = ctx.contentResolver
        val uri = docUri(l, f.docId)
        val parentRel = f.rel.substringBeforeLast('/', "")
        val moved = runCatching {
            val trash = ensureDir(ctx, l, TRASH)
            val newUri = DocumentsContract.moveDocument(cr, uri, docUri(l, l.dirs[parentRel] ?: l.dirs[""]!!), docUri(l, trash))
                ?: return@runCatching false
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            runCatching { DocumentsContract.renameDocument(cr, newUri, stamp + "_" + f.rel.substringAfterLast('/')) }
            true
        }.getOrDefault(false)
        if (!moved && !DocumentsContract.deleteDocument(cr, uri)) throw ApiException("Не удалось удалить ${f.rel} на телефоне")
    }

    private fun cleanTrash(ctx: Context, l: Local) {
        val trashId = l.dirs[TRASH] ?: runCatching {
            // скрытые папки при сканировании пропускаются — ищем корзину отдельно
            val c = ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(l.tree, l.dirs[""]!!),
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)
            c?.use { while (it.moveToNext()) if (it.getString(1) == TRASH) return@runCatching it.getString(0) }
            null
        }.getOrNull() ?: return
        l.dirs[TRASH] = trashId
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val limit = System.currentTimeMillis() - 30L * 86400_000
        runCatching {
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(l.tree, trashId),
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val t = runCatching { fmt.parse(name.substringBefore('_'))?.time }.getOrNull() ?: continue
                    if (t < limit) runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, docUri(l, c.getString(0))) }
                }
            }
        }
    }

    // ───────────────────────── отпечатки ─────────────────────────
    /** Как sync.qhash на сервере: sha1(размер + первые 64 КБ + последние 64 КБ). */
    private fun qhash(size: Long, open: () -> InputStream): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(size.toString().toByteArray())
        open().use { inp ->
            val buf = ByteArray(65536)
            fun readFully(n: Int): Int {
                var got = 0
                while (got < n) { val r = inp.read(buf, got, n - got); if (r < 0) break; got += r }
                return got
            }
            md.update(buf, 0, readFully(minOf(65536L, size).toInt()))
            if (size > 65536) {
                val start = maxOf(65536L, size - 65536)
                var toSkip = start - 65536
                while (toSkip > 0) { val s = inp.skip(toSkip); if (s <= 0) break; toSkip -= s }
                md.update(buf, 0, readFully((size - start).toInt()))
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ───────────────────────── сервер ─────────────────────────
    private fun q(s: String) = Api.enc(s)

    private fun remoteIndex(ctx: Context, root: String, dir: String): Pair<Boolean, HashMap<String, RFile>> {
        val r = Api.get(ctx, "/modules/mobile/api/sync/index?root=${q(root)}&path=${q(dir)}", 60_000)
        val out = HashMap<String, RFile>()
        val arr = r.optJSONArray("files")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rel = o.optString("rel")
            if (rel.isNotEmpty()) out[rel] = RFile(rel, o.optLong("size"), o.optDouble("mtime"))
        }
        return r.optBoolean("exists", true) to out
    }

    private fun remotePath(dir: String, rel: String) = if (dir.isEmpty()) rel else "$dir/$rel"

    /** Отправить файл ПОВЕРХ. expect — каким файл должен быть на сервере сейчас (null — «файла нет»). */
    private fun put(ctx: Context, root: String, dir: String, rel: String, l: Local, f: LFile, expect: RFile?, check: Boolean,
                    title: String): RFile {
        val fields = mutableMapOf("root" to root, "path" to remotePath(dir, rel), "mtime" to f.mtime.toString())
        if (check) {
            fields["if_size"] = (expect?.size ?: -1L).toString()
            if (expect != null) fields["if_mtime"] = expect.mtime.toString()
        }
        val name = rel.substringAfterLast('/')
        val r = Api.upload(ctx, "/modules/mobile/api/sync/put", name, Files.mimeOf(name), f.size,
            { ctx.contentResolver.openInputStream(docUri(l, f.docId)) ?: throw ApiException("Нет доступа к $name") }, fields) { done, total ->
            Notifier.progress(ctx, Notifier.ID_UPLOAD, "⬆ $title: $name", Files.human(done) + " из " + Files.human(total), done, total)
        }
        return RFile(rel, r.optLong("size", f.size), r.optDouble("mtime", f.mtime / 1000.0))
    }

    private fun fetch(ctx: Context, root: String, dir: String, rel: String, title: String): File {
        val tmp = File(ctx.cacheDir, "sync_" + System.nanoTime() + ".swds-tmp")
        try {
            Api.download(ctx, "/modules/mobile/api/files/download?root=${q(root)}&path=${q(remotePath(dir, rel))}") { input, _, _, len ->
                tmp.outputStream().use { Files.copy(input, it, len) { d, t ->
                    Notifier.progress(ctx, Notifier.ID_DOWNLOAD, "⬇ $title: ${rel.substringAfterLast('/')}",
                        Files.human(d) + " из " + Files.human(t), d, t)
                } }
            }
        } catch (e: Exception) { tmp.delete(); throw e }
        return tmp
    }

    private fun conflictName(rel: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = rel.substringAfterLast('/')
        val dir = rel.substringBeforeLast('/', "")
        val c = if ('.' in name) name.substringBeforeLast('.') + ".sync-conflict-$stamp." + name.substringAfterLast('.')
                else "$name.sync-conflict-$stamp"
        return if (dir.isEmpty()) c else "$dir/$c"
    }

    // ───────────────────────── синхронизация ─────────────────────────
    private fun onWifiOrCharging(ctx: Context, rule: JSONObject): String? {
        if (rule.optBoolean("wifi_only")) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            if (cm == null || cm.isActiveNetworkMetered) return "ждёт Wi-Fi"
        }
        if (rule.optBoolean("charging_only")) {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            if (bm != null && !bm.isCharging) return "ждёт зарядку"
        }
        return null
    }

    fun remoteRoot(rule: JSONObject): String = rule.optString("dest", "shared").ifEmpty { "shared" }

    fun remoteDir(rule: JSONObject): String {
        val path = rule.optString("path").trim().trim('/')
        return if (remoteRoot(rule) == "shared") path.ifEmpty { "С телефона/" + rule.optString("name") } else path
    }

    /** Все папки. manual — запущено кнопкой (тогда не ждём Wi-Fi/зарядку). */
    fun runAll(ctx: Context, manual: Boolean = false): Boolean {
        if (!busy.tryLock()) return true                  // уже идёт (служба связи + WorkManager) — второй раз не нужен
        var ok = true
        try {
            val p = Prefs(ctx)
            val rules = p.syncRules
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                if (!rule.optBoolean("enabled", true)) continue
                val wait = if (manual) null else onWifiOrCharging(ctx, rule)
                if (wait != null) { rule.put("status", wait); rules.put(i, rule); continue }
                val st = Stats()
                try {
                    if (rule.optString("dest") == "auto") syncSorted(ctx, p, rule, st) else syncRule(ctx, p, rule, st)
                    rule.put("status", if (st.errors.isEmpty()) "ok" else "Ошибок: ${st.errors.size}")
                    rule.put("last_error", st.errors.firstOrNull() ?: "")
                } catch (e: ApiException) {
                    if (e.status == 401) throw e
                    ok = false
                    rule.put("status", "ошибка").put("last_error", e.message ?: "ошибка")
                    if (OfflineQueue.isOffline(e)) break
                } catch (e: Exception) {
                    ok = false
                    rule.put("status", "ошибка").put("last_error", e.message ?: e.toString())
                }
                rule.put("last_sync", System.currentTimeMillis())
                rule.put("last_count", st.total()).put("last_text", if (st.total() > 0 || st.conflicts > 0) st.text() else "без изменений")
                rules.put(i, rule)
                Notifier.cancel(ctx, Notifier.ID_UPLOAD); Notifier.cancel(ctx, Notifier.ID_DOWNLOAD)
                if (st.total() > 0 || st.conflicts > 0)
                    Notifier.simple(ctx, 3100 + i, "🔄 «${rule.optString("name")}»", st.text() +
                        (if (st.errors.isNotEmpty()) "\n⚠ " + st.errors.take(3).joinToString("\n") else ""), Notifier.CH_FILES, timeoutMs = 120_000)
            }
            // правила могли поменять в настройках, пока шла синхронизация — сохраняем только служебные поля
            val fresh = p.syncRules
            for (i in 0 until fresh.length()) {
                val r = fresh.optJSONObject(i) ?: continue
                for (j in 0 until rules.length()) {
                    val s = rules.optJSONObject(j) ?: continue
                    if (s.optString("id") != r.optString("id")) continue
                    for (k in listOf("status", "last_error", "last_sync", "last_count", "last_text")) if (s.has(k)) r.put(k, s.get(k))
                }
                fresh.put(i, r)
            }
            p.syncRules = fresh
        } finally { busy.unlock() }
        return ok
    }

    private fun syncRule(ctx: Context, p: Prefs, rule: JSONObject, st: Stats) {
        val id = rule.optString("id")
        val mode = rule.optString("mode", UPLOAD).ifEmpty { UPLOAD }
        val title = rule.optString("name")
        val root = remoteRoot(rule)
        val dir = remoteDir(rule)
        val since = rule.optLong("since", 0)
        val tree = Uri.parse(rule.optString("uri"))
        migrate(ctx, p, id)
        if (mode != UPLOAD && ctx.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission })
            throw ApiException("Нет права записи в папку на телефоне — удалите её и добавьте заново (раньше доступ давался только на чтение)")

        val local = scanLocal(ctx, tree)
        val (remoteExists, remote) = remoteIndex(ctx, root, dir)
        val base = loadBase(ctx, id)

        // защита от «удалить всё»: папку отключили (SD-карта) или переименовали на компьютере
        val allowDelRemote = mode == TWO_WAY && !(local.files.isEmpty() && base.isNotEmpty())
        val allowDelLocal = mode == TWO_WAY && remoteExists && !(remote.isEmpty() && base.size > 3)
        if (mode == TWO_WAY && base.isNotEmpty() && !remoteExists)
            throw ApiException("Папка «$dir» на компьютере пропала — синхронизация остановлена, чтобы ничего не удалить")

        val same = { l: LFile, r: RFile ->
            l.size == r.size && (l.size == 0L || Math.abs(l.mtime - (r.mtime * 1000).toLong()) <= MTIME_TOL_MS ||
                    runCatching {
                        qhash(l.size) { ctx.contentResolver.openInputStream(docUri(local, l.docId))!! } ==
                            Api.get(ctx, "/modules/mobile/api/sync/hash?root=${q(root)}&path=${q(remotePath(dir, l.rel))}").optString("qhash")
                    }.getOrDefault(false))
        }
        fun lChanged(l: LFile?, b: Base?) = b == null || l == null || l.size != b.lsize || Math.abs(l.mtime - b.lmtime) > MTIME_TOL_MS
        fun rChanged(r: RFile?, b: Base?) = b == null || r == null || r.size != b.rsize ||
                (b.rmtime > 0 && Math.abs(r.mtime - b.rmtime) * 1000 > MTIME_TOL_MS)
        fun record(rel: String, l: LFile, r: RFile) = putBase(ctx, id, rel, Base(l.size, l.mtime, r.size, r.mtime))

        fun upload(l: LFile, expect: RFile?) {
            val r = put(ctx, root, dir, l.rel, local, l, expect, check = mode == TWO_WAY, title = title)
            record(l.rel, l, r); st.up++
        }
        fun download(r: RFile, existing: LFile?) {
            val tmp = fetch(ctx, root, dir, r.rel, title)
            try {
                val docId = writeLocal(ctx, local, r.rel, existing?.docId, tmp)
                val (size, mtime) = stat(ctx, docUri(local, docId))
                val l = LFile(r.rel, size, mtime, docId)
                local.files[r.rel] = l
                record(r.rel, l, r); st.down++
            } finally { tmp.delete() }
        }

        val rels = (local.files.keys + remote.keys + base.keys).toSortedSet()
        for (rel in rels) {
            val l = local.files[rel]; val r = remote[rel]; val b = base[rel]
            try {
                when (mode) {
                    UPLOAD -> when {                                  // резервная копия: только туда, ничего не удаляем
                        l == null -> if (b != null) dropBase(ctx, id, rel)
                        !lChanged(l, b) -> {}
                        b == null && l.mtime < since -> {}
                        b == null && r != null && same(l, r) -> record(rel, l, r)
                        else -> upload(l, r)
                    }
                    DOWNLOAD -> when {                                // только с компьютера
                        r == null -> if (l == null && b != null) dropBase(ctx, id, rel)
                        !rChanged(r, b) -> {}
                        l != null && b == null && same(l, r) -> record(rel, l, r)
                        else -> {
                            if (l != null && b != null && lChanged(l, b)) {       // правили на телефоне — сохраним копией
                                runCatching { DocumentsContract.renameDocument(ctx.contentResolver, docUri(local, l.docId),
                                    conflictName(rel).substringAfterLast('/')) }
                                st.conflicts++
                                download(r, null)
                            } else download(r, l)
                        }
                    }
                    else -> twoWay(rel, l, r, b, ::upload, ::download, same, ::lChanged, ::rChanged, allowDelLocal,
                        allowDelRemote, ctx, id, root, dir, local, st, title)
                }
            } catch (e: ApiException) {
                if (e.status == 401 || OfflineQueue.isOffline(e)) throw e
                if (e.status == 409) continue                         // на сервере успели поменять — разберёмся в следующий раз
                st.errors.add("$rel: ${e.message}")
            } catch (e: Exception) {
                st.errors.add("$rel: ${e.message ?: e}")
            }
        }
        if (mode == TWO_WAY) cleanTrash(ctx, local)
    }

    private fun twoWay(rel: String, l: LFile?, r: RFile?, b: Base?,
                       upload: (LFile, RFile?) -> Unit, download: (RFile, LFile?) -> Unit, same: (LFile, RFile) -> Boolean,
                       lChanged: (LFile?, Base?) -> Boolean, rChanged: (RFile?, Base?) -> Boolean,
                       allowDelLocal: Boolean, allowDelRemote: Boolean,
                       ctx: Context, id: String, root: String, dir: String, local: Local, st: Stats, title: String) {
        val lch = lChanged(l, b); val rch = rChanged(r, b)
        when {
            l == null && r == null -> if (b != null) dropBase(ctx, id, rel)
            l != null && r != null -> when {
                !lch && !rch -> {}
                b == null || (lch && rch) -> {
                    if (same(l, r)) { putBase(ctx, id, rel, Base(l.size, l.mtime, r.size, r.mtime)); return }
                    // конфликт: новее — главный, другой сохраняем рядом как «.sync-conflict-…»
                    st.conflicts++
                    if (l.mtime >= (r.mtime * 1000).toLong()) {
                        val tmp = fetch(ctx, root, dir, rel, title)
                        try { writeLocal(ctx, local, conflictName(rel), null, tmp) } finally { tmp.delete() }
                        upload(l, r)
                    } else {
                        put(ctx, root, dir, conflictName(rel), local, l, null, check = true, title = title)
                        download(r, l)
                    }
                }
                lch -> upload(l, r)
                else -> download(r, l)
            }
            l != null -> when {                                      // на компьютере файла нет
                b == null || lch -> upload(l, null)                  // новый на телефоне (или правили после удаления)
                allowDelLocal -> { trashLocal(ctx, local, l); dropBase(ctx, id, rel); st.delLocal++ }
                else -> {}
            }
            else -> when {                                           // на телефоне файла нет
                b == null || rch -> download(r!!, null)
                allowDelRemote -> {
                    Api.post(ctx, "/modules/mobile/api/sync/delete", JSONObject().put("root", root).put("path", remotePath(dir, rel))
                        .put("if_size", r!!.size).put("if_mtime", r.mtime).put("stop", dir))
                    dropBase(ctx, id, rel); st.delRemote++
                }
                else -> {}
            }
        }
    }

    /** «🪄 Разложить само»: только отправка через сортировщик SWDS; повторно ничего не уходит. */
    private fun syncSorted(ctx: Context, p: Prefs, rule: JSONObject, st: Stats) {
        val id = rule.optString("id")
        migrate(ctx, p, id)
        val local = scanLocal(ctx, Uri.parse(rule.optString("uri")))
        val base = loadBase(ctx, id)
        val since = rule.optLong("since", 0)
        for ((rel, f) in local.files) {
            val b = base[rel]
            if (b != null && b.lsize == f.size && Math.abs(b.lmtime - f.mtime) <= MTIME_TOL_MS) continue
            if (b == null && f.mtime < since) continue
            val name = rel.substringAfterLast('/')
            try {
                Api.upload(ctx, "/modules/mobile/api/files/upload", name, Files.mimeOf(name), f.size,
                    { ctx.contentResolver.openInputStream(docUri(local, f.docId)) ?: throw ApiException("Нет доступа к $name") },
                    mapOf("dest" to "auto", "mtime" to f.mtime.toString())) { done, total ->
                    Notifier.progress(ctx, Notifier.ID_UPLOAD, "⬆ ${rule.optString("name")}: $name",
                        Files.human(done) + " из " + Files.human(total), done, total)
                }
                putBase(ctx, id, rel, Base(f.size, f.mtime, f.size, 0.0)); st.up++
            } catch (e: ApiException) {
                if (e.status == 401 || OfflineQueue.isOffline(e)) throw e
                st.errors.add("$rel: ${e.message}")
            }
        }
    }
}

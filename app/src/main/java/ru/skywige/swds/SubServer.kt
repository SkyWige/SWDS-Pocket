package ru.skywige.swds

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Саб-сервер SWDS на телефоне.
 *
 * Телефон держит у себя актуальные данные основного сервера (задачи, покупки, календарь, заметки, траты — за выбранный
 * период) и работает с ними без сети: офлайн-приложение на локальном сервере (LocalServer) читает и пишет сюда.
 * Каждая правка помечается «не отправлено» (dirty) и хранит «базу» — какой запись была при прошлой синхронизации.
 * При появлении связи: сначала отправляем свои правки (сервер сливает их по полям и по уровням), потом забираем
 * чужие изменения с курсора. Уровень телефона (1–3) и период задаются в настройках; уровень 1 означает,
 * что в споре за одно поле правка телефона важнее правки сервера уровня 2+.
 */
class LocalStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "subserver.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE records (dataset TEXT NOT NULL, uid TEXT NOT NULL, data TEXT, base TEXT, dirty INTEGER DEFAULT 0,
            deleted INTEGER DEFAULT 0, mtime INTEGER DEFAULT 0, PRIMARY KEY(dataset, uid))""")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE kv (module TEXT NOT NULL, k TEXT NOT NULL, v TEXT, PRIMARY KEY(module, k))")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) db.execSQL("""CREATE TABLE IF NOT EXISTS media (id INTEGER PRIMARY KEY, title TEXT, artist TEXT, album TEXT,
            media_type TEXT, filename TEXT, path TEXT, size INTEGER DEFAULT 0, duration REAL DEFAULT 0, cover TEXT, added INTEGER)""")
    }

    // ── записи ──
    fun list(ds: String): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT uid, data, dirty, deleted, mtime FROM records WHERE dataset=? AND deleted=0", arrayOf(ds)).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put("uid", c.getString(0)).put("data", JSONObject(c.getString(1) ?: "{}"))
                .put("dirty", c.getInt(2) == 1).put("mtime", c.getLong(4)))
        }
        return out
    }

    fun get(ds: String, uid: String): JSONObject? =
        readableDatabase.rawQuery("SELECT data, base, dirty, deleted FROM records WHERE dataset=? AND uid=?", arrayOf(ds, uid)).use { c ->
            if (!c.moveToFirst()) null else JSONObject().put("data", c.getString(0)).put("base", c.getString(1))
                .put("dirty", c.getInt(2) == 1).put("deleted", c.getInt(3) == 1)
        }

    /** Правка с телефона: запись становится «не отправлено». */
    fun upsertLocal(ds: String, uid0: String?, data: JSONObject, nodeId: String): String {
        val uid = uid0?.takeIf { it.isNotEmpty() } ?: "$nodeId:$ds:${UUID.randomUUID().toString().take(12)}"
        val cv = ContentValues().apply {
            put("dataset", ds); put("uid", uid); put("data", data.toString()); put("dirty", 1); put("deleted", 0)
            put("mtime", System.currentTimeMillis() / 1000)
        }
        val db = writableDatabase
        if (db.update("records", cv, "dataset=? AND uid=?", arrayOf(ds, uid)) == 0) db.insert("records", null, cv)
        return uid
    }

    fun deleteLocal(ds: String, uid: String) {
        val db = writableDatabase
        val cur = get(ds, uid) ?: return
        if (cur.optString("base").isEmpty() || cur.optString("base") == "null") {
            db.delete("records", "dataset=? AND uid=?", arrayOf(ds, uid))      // сервер о ней не знал — просто стираем
            return
        }
        db.execSQL("UPDATE records SET deleted=1, dirty=1, mtime=? WHERE dataset=? AND uid=?", arrayOf<Any>(System.currentTimeMillis() / 1000, ds, uid))
    }

    /** Состояние после синхронизации: data = base = итог, не «грязная». */
    fun applySynced(ds: String, uid: String, data: JSONObject?, deleted: Boolean, newUid: String? = null) {
        val db = writableDatabase
        if (newUid != null && newUid != uid) {
            db.delete("records", "dataset=? AND uid=?", arrayOf(ds, newUid))
            db.execSQL("UPDATE records SET uid=? WHERE dataset=? AND uid=?", arrayOf(newUid, ds, uid))
        }
        val u = newUid ?: uid
        if (deleted || data == null) { db.delete("records", "dataset=? AND uid=?", arrayOf(ds, u)); return }
        val cv = ContentValues().apply {
            put("dataset", ds); put("uid", u); put("data", data.toString()); put("base", data.toString()); put("dirty", 0); put("deleted", 0)
            put("mtime", System.currentTimeMillis() / 1000)
        }
        if (db.update("records", cv, "dataset=? AND uid=?", arrayOf(ds, u)) == 0) db.insert("records", null, cv)
    }

    fun dirty(ds: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        readableDatabase.rawQuery("SELECT uid, data, base, deleted, mtime FROM records WHERE dataset=? AND dirty=1", arrayOf(ds)).use { c ->
            while (c.moveToNext()) {
                val base = c.getString(2)
                out.add(JSONObject().put("uid", c.getString(0)).put("data", JSONObject(c.getString(1) ?: "{}"))
                    .put("base", if (base.isNullOrEmpty()) JSONObject.NULL else JSONObject(base)).put("deleted", c.getInt(3) == 1)
                    .put("mtime", c.getLong(4)))
            }
        }
        return out
    }

    fun counts(): Map<String, IntArray> {
        val m = HashMap<String, IntArray>()
        readableDatabase.rawQuery("SELECT dataset, SUM(CASE WHEN deleted=0 THEN 1 ELSE 0 END), SUM(dirty) FROM records GROUP BY dataset", null).use { c ->
            while (c.moveToNext()) m[c.getString(0)] = intArrayOf(c.getInt(1), c.getInt(2))
        }
        return m
    }

    fun meta(k: String): String? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun meta(k: String, v: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES(?,?)", arrayOf(k, v)) }

    // ── хранилище своих офлайн-модулей ──
    fun kvGet(module: String, k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE module=? AND k=?", arrayOf(module, k)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun kvSet(module: String, k: String, v: String?) {
        if (v == null) writableDatabase.delete("kv", "module=? AND k=?", arrayOf(module, k))
        else writableDatabase.execSQL("INSERT OR REPLACE INTO kv(module, k, v) VALUES(?,?,?)", arrayOf(module, k, v))
    }
    fun kvKeys(module: String): JSONArray {
        val a = JSONArray()
        readableDatabase.rawQuery("SELECT k FROM kv WHERE module=?", arrayOf(module)).use { while (it.moveToNext()) a.put(it.getString(0)) }
        return a
    }

    // ── скачанные медиа ──
    fun mediaList(): JSONArray {
        val a = JSONArray()
        readableDatabase.rawQuery("SELECT id, title, artist, album, media_type, filename, size, duration FROM media ORDER BY added DESC", null).use { c ->
            while (c.moveToNext()) a.put(JSONObject().put("id", c.getLong(0)).put("title", c.getString(1)).put("artist", c.getString(2))
                .put("album", c.getString(3)).put("media_type", c.getString(4)).put("filename", c.getString(5)).put("size", c.getLong(6))
                .put("duration", c.getDouble(7)))
        }
        return a
    }
    fun mediaPath(id: Long): String? = readableDatabase.rawQuery("SELECT path FROM media WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
    fun mediaIds(): JSONArray { val a = JSONArray(); readableDatabase.rawQuery("SELECT id FROM media", null).use { while (it.moveToNext()) a.put(it.getLong(0)) }; return a }
    fun mediaPut(o: JSONObject, path: String, size: Long) {
        val cv = ContentValues().apply {
            put("id", o.optLong("id")); put("title", o.optString("title")); put("artist", o.optString("artist")); put("album", o.optString("album"))
            put("media_type", o.optString("media_type", "audio")); put("filename", o.optString("filename")); put("path", path); put("size", size)
            put("duration", o.optDouble("duration", 0.0)); put("added", System.currentTimeMillis() / 1000)
        }
        writableDatabase.insertWithOnConflict("media", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun mediaDelete(id: Long) { writableDatabase.delete("media", "id=?", arrayOf(id.toString())) }

    companion object {
        @Volatile private var inst: LocalStore? = null
        fun get(ctx: Context): LocalStore = inst ?: synchronized(this) { inst ?: LocalStore(ctx.applicationContext).also { inst = it } }
    }
}

object SubSync {
    val DATASETS = listOf("tasks", "shopping", "calendar", "notes", "wallet")
    @Volatile var running = false
    @Volatile var lastError = ""
    @Volatile var serverName = ""
    @Volatile var online = false

    fun nodeId(ctx: Context): String {
        val p = Prefs(ctx)
        return p.subNodeId
    }

    /** Полный цикл: hello → (обновить офлайн-пакет) → отправить своё → забрать чужое. */
    @Synchronized
    fun sync(ctx: Context): JSONObject {
        val p = Prefs(ctx)
        val st = LocalStore.get(ctx)
        if (!p.paired) return JSONObject().put("ok", false).put("error", "Телефон не привязан к SWDS")
        running = true
        val report = JSONObject()
        try {
            val info = JSONObject().put("model", android.os.Build.MODEL).put("lan", p.subLan).put("port", p.subPort)
            val hi = Api.post(ctx, "/api/sync/hello", JSONObject().put("node_id", nodeId(ctx)).put("name", p.deviceName).put("kind", "pocket")
                .put("level", p.subLevel).put("version", BuildConfig.VERSION_NAME).put("window_days", p.subWindowDays)
                .put("datasets", JSONArray(p.subDatasets)).put("info", info), 8000, 30000)
            online = true
            serverName = hi.optJSONObject("server")?.optString("name").orEmpty()
            st.meta("server", hi.optJSONObject("server")?.toString() ?: "{}")
            val avail = HashSet<String>()
            hi.optJSONArray("datasets")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("name")?.let { avail.add(it) } }
            st.meta("datasets", hi.optJSONArray("datasets")?.toString() ?: "[]")
            runCatching { updatePack(ctx, hi.optJSONObject("pack")) }
            for (ds in p.subDatasets.filter { it in avail }) {
                var sent = 0
                var got = 0
                // 1) своё
                val mine = st.dirty(ds)
                for (chunk in mine.chunked(150)) {
                    val r = Api.post(ctx, "/api/sync/push", JSONObject().put("node_id", nodeId(ctx)).put("dataset", ds).put("records", JSONArray(chunk)), 8000, 120000)
                    val res = r.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until res.length()) {
                        val x = res.optJSONObject(i) ?: continue
                        if (x.optString("status") == "error") continue
                        st.applySynced(ds, x.optString("uid"), x.optJSONObject("data"), x.optBoolean("deleted"),
                            x.optString("canonical").takeIf { it.isNotEmpty() })
                    }
                    sent += chunk.size
                }
                // 2) чужое
                var cursor = st.meta("cursor:$ds")?.toIntOrNull() ?: 0
                while (true) {
                    val r = Api.post(ctx, "/api/sync/pull", JSONObject().put("node_id", nodeId(ctx)).put("dataset", ds).put("cursor", cursor)
                        .put("window_days", p.subWindowDays), 8000, 120000)
                    val recs = r.optJSONArray("records") ?: JSONArray()
                    for (i in 0 until recs.length()) {
                        val x = recs.optJSONObject(i) ?: continue
                        val uid = x.optString("uid")
                        val local = st.get(ds, uid)
                        if (local != null && local.optBoolean("dirty")) continue   // наша правка уйдёт следующим push и сольётся там
                        if (x.optBoolean("deleted") || x.optBoolean("out_of_window")) st.applySynced(ds, uid, null, true)
                        else st.applySynced(ds, uid, x.optJSONObject("data"), false)
                        got++
                    }
                    cursor = r.optInt("cursor", cursor)
                    st.meta("cursor:$ds", cursor.toString())
                    if (!r.optBoolean("more")) break
                }
                report.put(ds, JSONObject().put("sent", sent).put("received", got))
            }
            st.meta("last_sync", (System.currentTimeMillis() / 1000).toString())
            lastError = ""
            return JSONObject().put("ok", true).put("report", report)
                .put("message", "Синхронизировано: " + report.keys().asSequence().joinToString(", ") { k ->
                    val o = report.getJSONObject(k); "$k ⬆${o.optInt("sent")} ⬇${o.optInt("received")}" })
        } catch (e: Exception) {
            online = false
            lastError = e.message ?: "нет связи"
            return JSONObject().put("ok", false).put("error", lastError)
        } finally {
            running = false
        }
    }

    /** Офлайн-пакет (приложение + свои модули) с основного сервера: обновляется, когда меняется версия. */
    private fun updatePack(ctx: Context, pack: JSONObject?) {
        val ver = pack?.optString("version").orEmpty()
        val st = LocalStore.get(ctx)
        if (ver.isEmpty() || ver == st.meta("pack_version")) return
        val dir = File(ctx.filesDir, "offline")
        val tmp = File(ctx.filesDir, "offline.new")
        tmp.deleteRecursively(); tmp.mkdirs()
        Api.withServer(ctx, "/api/sync/pack.zip", "GET", 8000, 120000) { conn ->
            if (conn.responseCode != 200) throw ApiException("pack ${conn.responseCode}", conn.responseCode)
            ZipInputStream(conn.inputStream).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val f = File(tmp, e.name)
                    if (!f.canonicalPath.startsWith(tmp.canonicalPath)) throw SecurityException("zip path")
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    e = z.nextEntry
                }
            }
            conn.disconnect()
        }
        dir.deleteRecursively()
        tmp.renameTo(dir)
        st.meta("pack_version", ver)
    }

    fun state(ctx: Context): JSONObject {
        val p = Prefs(ctx)
        val st = LocalStore.get(ctx)
        val counts = st.counts()
        val ds = JSONArray()
        val avail = runCatching { JSONArray(st.meta("datasets") ?: "[]") }.getOrDefault(JSONArray())
        val known = HashMap<String, JSONObject>()
        for (i in 0 until avail.length()) avail.optJSONObject(i)?.let { known[it.optString("name")] = it }
        var dirty = 0
        for (name in p.subDatasets) {
            val c = counts[name] ?: intArrayOf(0, 0)
            dirty += c[1]
            val k = known[name]
            ds.put(JSONObject().put("name", name).put("title", k?.optString("title") ?: name).put("icon", k?.optString("icon") ?: "📦")
                .put("count", c[0]).put("dirty", c[1]))
        }
        val mods = JSONArray()
        val mdir = File(ctx.filesDir, "offline/modules")
        mdir.listFiles()?.sortedBy { it.name }?.forEach { d ->
            if (File(d, "index.html").exists()) {
                val meta = runCatching { JSONObject(File(d, "module.json").readText()) }.getOrDefault(JSONObject())
                mods.put(JSONObject().put("id", d.name).put("name", meta.optString("name", d.name)).put("icon", meta.optString("icon", "🧩"))
                    .put("description", meta.optString("description")))
            }
        }
        val server = runCatching { JSONObject(st.meta("server") ?: "{}") }.getOrDefault(JSONObject())
        return JSONObject().put("ok", true).put("node_id", nodeId(ctx)).put("node_name", p.deviceName).put("level", p.subLevel)
            .put("window_days", p.subWindowDays).put("server_name", server.optString("name", serverName)).put("server_level", server.optInt("level", 1))
            .put("server_online", online).put("last_sync", st.meta("last_sync")?.toLongOrNull() ?: JSONObject.NULL)
            .put("dirty", dirty).put("datasets", ds).put("modules", mods).put("media", st.mediaList().length())
            .put("running", running).put("error", lastError).put("pack_version", st.meta("pack_version") ?: "")
    }
}

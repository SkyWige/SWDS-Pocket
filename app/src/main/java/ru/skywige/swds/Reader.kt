package ru.skywige.swds

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * «Библиотека» на телефоне: скачанные книги (library.html) и читалка (reader.html — та же, что в SWDS,
 * только данные берутся из памяти телефона). Работает без связи; прогресс и цитаты уходят на сервер позже.
 */
class LibraryActivity : LocalPageActivity() {
    override val page = "library"

    override fun onResume() {
        super.onResume()
        emit("resume", JSONObject())
    }

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? = when (method) {
        "lib_list" -> ok(Library.list(this))
        "lib_sync" -> { SyncWorker.now(this, "library", manual = a.optBoolean("manual", true)); ok() }
        "lib_set" -> {
            val p = Prefs(this)
            if (a.has("enabled")) { p.libraryEnabled = a.optBoolean("enabled"); if (p.libraryEnabled) SyncWorker.now(this, "library") }
            if (a.has("wifi_only")) p.libraryWifiOnly = a.optBoolean("wifi_only")
            ok(p.toJson())
        }
        "lib_delete" -> { Library.delete(this, a.optString("id")); ok() }
        "lib_clear" -> { Library.clearAll(this); ok() }
        "open_reader" -> {
            val bid = a.optString("id")
            if (Library.isLocal(this, bid)) { main.post { startActivity(Intent(this, ReaderActivity::class.java).putExtra("id", bid)) }; ok() }
            else { SyncWorker.now(this, "library", manual = true); err("Книга ещё не скачана — начал скачивать") }
        }
        else -> super.handle(id, method, a)
    }
}

class ReaderActivity : LocalPageActivity() {
    override val page = "reader"
    private val bookId by lazy { intent?.getStringExtra("id") ?: intent?.data?.lastPathSegment ?: "" }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pdf: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null
    private var lastPush = 0L

    override fun extraState(): JSONObject = JSONObject().put("id", bookId)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // картинки книги лежат в памяти приложения: file:///data/…/files/library/<id>/assets/…
        web.settings.allowFileAccess = true
        web.setBackgroundColor(Color.parseColor("#fbfaf7"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#fbfaf7")
    }

    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("window.__rdSave && __rdSave()", null)
        SyncWorker.now(this, "library_push")
    }

    override fun onDestroy() {
        runCatching { tts?.shutdown() }
        runCatching { pdf?.close(); pdfFd?.close() }
        super.onDestroy()
    }

    /** Громкость − / + листает страницы (как в электронных книгах). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == KeyEvent.ACTION_DOWN)
                web.evaluateJavascript("window.__rdKey && __rdKey('${if (k == KeyEvent.KEYCODE_VOLUME_DOWN) "next" else "prev"}')", null)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openPdf(): PdfRenderer? {
        pdf?.let { return it }
        val f = File(Library.bookDir(this, bookId), "book.pdf")
        if (!f.exists()) return null
        pdfFd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        pdf = PdfRenderer(pdfFd!!)
        return pdf
    }

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? = when (method) {
        "lib_open" -> {
            val b = Library.book(this, bookId)
            val pk = Library.pack(this, bookId)
            if (b == null || pk == null) err("Книга не скачана")
            else ok(JSONObject().put("book", JSONObject(b.toString()).put("id", bookId)).put("pack", pk)
                .put("marks", Library.marks(this, bookId)).put("root", "file://" + Library.bookDir(this, bookId).absolutePath + "/"))
        }
        "lib_chapter" -> ok(JSONObject().put("html", Library.chapter(this, bookId, a.optInt("n"))))
        "lib_progress" -> {
            Library.saveProgress(this, bookId, a)
            val now = System.currentTimeMillis()
            if (a.optBoolean("final") || now - lastPush > 60_000) { lastPush = now; SyncWorker.now(this, "library_push") }
            ok()
        }
        "lib_mark" -> {
            Library.saveMark(this, bookId, a)
            SyncWorker.now(this, "library_push")
            ok()
        }
        "lib_search" -> ok(JSONObject().put("hits", Library.search(this, bookId, a.optString("q"))))
        "pdf_info" -> synchronized(this) {
            val r = openPdf()
            if (r == null) err("Нет PDF") else {
                val sizes = JSONArray()
                for (i in 0 until r.pageCount) r.openPage(i).use { pg -> sizes.put(JSONObject().put("w", pg.width).put("h", pg.height)) }
                ok(JSONObject().put("pages", r.pageCount).put("sizes", sizes))
            }
        }
        "pdf_page" -> synchronized(this) {
            val r = openPdf()
            val n = a.optInt("n", 1)
            val w = a.optInt("w", 1080).coerceIn(200, 2400)
            if (r == null || n < 1 || n > r.pageCount) err("Нет страницы") else {
                val out = File(cacheDir, "pdf/${bookId}_${n}_$w.png")
                if (!out.exists()) {
                    out.parentFile?.mkdirs()
                    r.openPage(n - 1).use { pg ->
                        val h = (w.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                    }
                    trimPdfCache()
                }
                ok(JSONObject().put("url", "file://" + out.absolutePath))
            }
        }
        "tts_speak" -> { speak(a.optString("text"), a.optDouble("rate", 1.0).toFloat(), a.optString("lang")); ok() }
        "tts_stop" -> { runCatching { tts?.stop() }; ok() }
        "open_original" -> {
            val f = Library.originalFile(this, bookId)
            if (f == null) err("Файла нет") else {
                main.post {
                    runCatching {
                        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть в…"))
                    }.onFailure { Events.toast(this, "Нет приложения для этого файла") }
                }
                ok()
            }
        }
        "theme_bar" -> {
            val c = runCatching { Color.parseColor(a.optString("color")) }.getOrNull()
            if (c != null) main.post {
                window.statusBarColor = c; window.navigationBarColor = c; web.setBackgroundColor(c)
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (Theme.isLight(c))
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            }
            ok()
        }
        else -> super.handle(id, method, a)
    }

    private fun trimPdfCache() {
        val files = File(cacheDir, "pdf").listFiles() ?: return
        if (files.size < 80) return
        files.sortedBy { it.lastModified() }.take(files.size - 60).forEach { it.delete() }
    }

    private fun speak(text: String, rate: Float, lang: String) {
        val go = {
            tts?.setSpeechRate(rate)
            val loc = if (lang.isNotEmpty()) Locale.forLanguageTag(lang) else Locale("ru", "RU")
            if ((tts?.isLanguageAvailable(loc) ?: -1) >= TextToSpeech.LANG_AVAILABLE) tts?.language = loc
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
        }
        if (tts != null && ttsReady) { go(); return }
        main.post {
            tts = TextToSpeech(this) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (!ttsReady) { emit("tts_error", JSONObject()); return@TextToSpeech }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { emit("tts_done", JSONObject()) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { emit("tts_done", JSONObject()) }
                })
                go()
            }
        }
    }
}

package ru.skywige.swds

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.sqrt

/**
 * Ассистент SWDS поверх любого экрана: долгое нажатие «Домой» (если SWDS выбран цифровым ассистентом),
 * виджет 🎙 или плитка. Речь распознаёт телефон (быстро) или сервер SWDS (Vosk, без Google),
 * ответ — текстом и голосом.
 */
class AssistActivity : LocalPageActivity() {
    override val page = "assist"
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    @Volatile private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setGravity(Gravity.BOTTOM)
        val h = (resources.displayMetrics.heightPixels * 0.66).toInt()
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, h)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.45f)
        web.setBackgroundColor(Color.TRANSPARENT)
        setFinishOnTouchOutside(true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        emit("again", JSONObject())                                    // повторное нажатие «Домой» — слушать снова
    }

    override fun onDestroy() {
        recording = false
        recognizer?.let { runCatching { it.destroy() } }
        tts?.let { runCatching { it.shutdown() } }
        player?.let { runCatching { it.release() } }
        super.onDestroy()
    }

    override fun extraState(): JSONObject = JSONObject()
        .put("phone_stt", SpeechRecognizer.isRecognitionAvailable(this))
        .put("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        .put("from", intent?.action ?: "")

    override fun handle(id: String, method: String, a: JSONObject): JSONObject? {
        val p = Prefs(this)
        return when (method) {
            "listen" -> {
                val engine = if (p.assistEngine == "phone" && SpeechRecognizer.isRecognitionAvailable(this)) "phone" else "server"
                if (engine == "phone") main.post { listenPhone(id) } else Thread { listenServer(id) }.start()
                null
            }
            "stop_listen" -> {
                recording = false
                main.post { recognizer?.stopListening() }
                ok()
            }
            "ask" -> {
                val r = Api.post(this, "/api/assistant/text", JSONObject().put("text", a.optString("text"))
                    .put("source", "mobile").put("session", "phone_" + p.deviceId)
                    .put("speak", p.assistSpeak && p.assistVoice == "server"), readTimeoutMs = 120000)
                ok(r)
            }
            "speak" -> { speak(a.optString("text"), a.optString("audio")); ok() }
            "stop_speak" -> { main.post { tts?.stop(); player?.let { runCatching { it.stop() } } }; ok() }
            "save" -> {
                if (a.has("auto_listen")) p.assistAutoListen = a.optBoolean("auto_listen")
                if (a.has("speak")) p.assistSpeak = a.optBoolean("speak")
                if (a.has("engine")) p.assistEngine = a.optString("engine")
                if (a.has("voice")) p.assistVoice = a.optString("voice")
                ok(p.toJson())
            }
            else -> super.handle(id, method, a)
        }
    }

    // ───────── распознавание телефоном ─────────
    private fun listenPhone(id: String) {
        recognizer?.destroy()
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        var lastLevel = 0L
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { emit("state", JSONObject().put("state", "listening")) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                val now = System.currentTimeMillis()
                if (now - lastLevel > 90) { lastLevel = now; emit("level", JSONObject().put("level", ((rmsdB + 2) / 12).coerceIn(0f, 1f))) }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { emit("state", JSONObject().put("state", "thinking")) }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал — повторите"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания (попробуйте «распознаёт SWDS»)"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, повторите"
                    else -> "Ошибка распознавания ($error)"
                }
                reply(id, err(msg))
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                reply(id, if (t.isBlank()) err("Не расслышал — повторите") else ok(JSONObject().put("text", t)))
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    emit("partial", JSONObject().put("text", it))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        rec.startListening(i)
    }

    // ───────── распознавание сервером SWDS: запись 16 кГц → /api/assistant/voice ─────────
    @Suppress("MissingPermission")
    private fun listenServer(id: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reply(id, err("Нет доступа к микрофону")); return
        }
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        } catch (e: Exception) { reply(id, err("Микрофон занят")); return }
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(1600)                                  // 100 мс
        var spoke = false
        var silentMs = 0
        var total = 0
        recording = true
        emit("state", JSONObject().put("state", "listening"))
        try {
            rec.startRecording()
            while (recording && total < 12_000) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (k in 0 until n) { sum += buf[k] * buf[k].toDouble(); pcm.write(buf[k].toInt() and 0xFF); pcm.write(buf[k].toInt() shr 8 and 0xFF) }
                val rms = sqrt(sum / n)
                emit("level", JSONObject().put("level", (rms / 3000).coerceIn(0.0, 1.0)))
                total += n * 1000 / rate
                if (rms > 900) { spoke = true; silentMs = 0 } else silentMs += n * 1000 / rate
                if (spoke && silentMs > 1200) break                 // договорил
                if (!spoke && total > 6000) break                   // так и не заговорил
            }
        } finally {
            recording = false
            runCatching { rec.stop() }; rec.release()
        }
        if (!spoke) { reply(id, err("Не расслышал — повторите")); return }
        emit("state", JSONObject().put("state", "thinking"))
        val wav = wav(pcm.toByteArray(), rate)
        try {
            val p = Prefs(this)
            val r = Api.upload(this, "/api/assistant/voice", "voice.wav", "audio/wav", wav.size.toLong(),
                { wav.inputStream() }, mapOf("source" to "mobile", "session" to "phone_" + p.deviceId,
                    "speak" to if (p.assistSpeak && p.assistVoice == "server") "1" else "0"), fileField = "audio")
            reply(id, ok(JSONObject().put("text", r.optString("heard")).put("result", r)))
        } catch (e: Exception) { reply(id, err(e.message)) }
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVEfmt ".toByteArray())
        i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    // ───────── ответ голосом ─────────
    private fun speak(text: String, audioUrl: String) {
        val p = Prefs(this)
        if (!p.assistSpeak || text.isBlank() && audioUrl.isBlank()) { emit("spoken", JSONObject()); return }
        if (p.assistVoice == "server" && audioUrl.isNotEmpty()) {
            main.post {
                try {
                    player?.release()
                    val url = p.activeServer + audioUrl + (if ('?' in audioUrl) "&" else "?") + "token=" + Api.enc(p.token)
                    player = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        setDataSource(url)
                        setOnCompletionListener { emit("spoken", JSONObject()) }
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                } catch (e: Exception) { speakPhone(text) }
            }
            return
        }
        speakPhone(text)
    }

    private fun speakPhone(text: String) = main.post {
        val say = text.replace(Regex("[*_#`>]|\\p{So}"), " ").take(600)
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale("ru", "RU")
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { emit("spoken", JSONObject()) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { emit("spoken", JSONObject()) }
                    })
                    tts?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "swds")
                } else emit("spoken", JSONObject())
            }
        } else if (ttsReady) tts?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "swds")
    }
}

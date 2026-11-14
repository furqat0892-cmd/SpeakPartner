package uz.speakpartner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
        val FAST_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash")
        val SMART_MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
        val TTS_MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")
        const val VOICE = "Kore" // ayol ovozi

        const val K_USER = 0
        const val K_EN = 1
        const val K_UZ = 2
        const val K_TIP = 3

        const val SYSTEM_PROMPT =
            "You are Emma, a warm, friendly young woman who teaches English through conversation only. " +
            "The user is a COMPLETE BEGINNER from Uzbekistan and speaks Uzbek (write Uzbek in Latin script). " +
            "Teach step by step: each turn teach or practice ONE very short English phrase (3-6 words) using very simple words. " +
            "Be quick and concise. Always reply in exactly this format, each on its own line:\n" +
            "EN: <1-2 very short, simple English sentences, max 15 words>\n" +
            "UZ: <friendly Uzbek translation and explanation, and what the user should say next, max 25 words>\n" +
            "Tip: <ONLY if the user made a mistake: the corrected English sentence and a very short Uzbek reason>\n" +
            "If the user speaks Uzbek, understand it, answer in the same format and show how to say it in English. " +
            "Each turn ask the user to repeat or say a phrase. Praise small successes. " +
            "Never use emojis, markdown or lists. " +
            "Start by greeting in English and Uzbek, then teach: Hello, my name is ..."
    }

    private class Seg(val text: String, val label: String) {
        @Volatile var file: File? = null
        val done = CountDownLatch(1)
    }

    private class Cancelled : Exception()

    private val END = Seg("", "END")

    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private val turnCounter = AtomicInteger(0)

    @Volatile private var playLatch: CountDownLatch? = null
    @Volatile private var usedFallback = false
    @Volatile private var lastTtsError: String? = null
    @Volatile private var turnError: String? = null

    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var micBtn: Button
    private lateinit var micBg: GradientDrawable
    private lateinit var langBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var inputBox: EditText
    private var typingView: TextView? = null

    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var fallbackTts: TextToSpeech? = null
    private var fallbackReady = false
    private var listening = false
    private var uzMode = false
    private var autoMode = false
    private var smartMode = false

    private val prefs by lazy { getSharedPreferences("sp", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        smartMode = prefs.getBoolean("smart", false)
        autoMode = prefs.getBoolean("auto", false)
        try {
            cacheDir.listFiles { f -> f.name.startsWith("emma_") }?.forEach { it.delete() }
        } catch (_: Exception) {}
        buildUi()
        fallbackTts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                fallbackTts?.language = Locale.US
                fallbackReady = true
            }
        }
        if (prefs.getString("key", "").isNullOrBlank()) askKey() else greet()
    }

    // ---------------- UI ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun smallBtn(onClick: () -> Unit): Button = Button(this).apply {
        isAllCaps = false
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F0FB"))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#6C4AB6"))
            setPadding(dp(16), dp(36), dp(4), dp(10))
        }
        top.addView(TextView(this).apply {
            text = "Emma  •  Speaking Partner"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Button(this).apply {
            text = "↺"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { resetChat() }
        }, LinearLayout.LayoutParams(dp(52), -2))
        top.addView(Button(this).apply {
            text = "⚙"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { askKey() }
        }, LinearLayout.LayoutParams(dp(52), -2))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        scroll = ScrollView(this)
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(chatBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            text = "Mikrofonni bosing va gapiring"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.parseColor("#444444"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        // Yozish qatori
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputBox = EditText(this).apply {
            hint = "Yozib yuborish (English yoki o'zbekcha)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
        }
        row.addView(inputBox, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Send"
            setOnClickListener { sendTyped() }
        }, LinearLayout.LayoutParams(-2, -2))
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), 0, dp(16), 0)
        })

        // Sozlama tugmalari
        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        langBtn = smallBtn {
            uzMode = !uzMode
            refreshButtons()
        }
        modeBtn = smallBtn {
            smartMode = !smartMode
            prefs.edit().putBoolean("smart", smartMode).apply()
            refreshButtons()
        }
        autoBtn = smallBtn {
            autoMode = !autoMode
            prefs.edit().putBoolean("auto", autoMode).apply()
            refreshButtons()
        }
        opts.addView(langBtn, LinearLayout.LayoutParams(0, -2, 1f))
        opts.addView(modeBtn, LinearLayout.LayoutParams(0, -2, 1f))
        opts.addView(autoBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(opts, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), 0, dp(12), 0)
        })

        micBg = GradientDrawable().apply {
            cornerRadius = dp(40).toFloat()
            setColor(Color.parseColor("#6C4AB6"))
        }
        micBtn = Button(this).apply {
            text = "🎤  Gapirish"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = micBg
            setOnClickListener { onMic() }
        }
        root.addView(micBtn, LinearLayout.LayoutParams(-1, dp(72)).apply {
            setMargins(dp(24), dp(4), dp(24), dp(20))
        })
        setContentView(root)
        refreshButtons()
    }

    private fun refreshButtons() {
        langBtn.text = if (uzMode) "Men: O'ZBEK 🇺🇿" else "Men: ENGLISH 🇬🇧"
        modeBtn.text = if (smartMode) "🧠 Aqlli" else "⚡ Tez"
        autoBtn.text = if (autoMode) "Avto: YOQIQ" else "Avto: o'chiq"
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun addBubble(text: String, kind: Int, seg: Seg?) {
        if (kind != K_USER) hideTyping()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(
                when (kind) {
                    K_USER -> Color.WHITE
                    K_TIP -> Color.parseColor("#7A4B00")
                    else -> Color.parseColor("#222222")
                }
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(
                    when (kind) {
                        K_USER -> Color.parseColor("#6C4AB6")
                        K_TIP -> Color.parseColor("#FFF1CC")
                        K_UZ -> Color.parseColor("#E8DFF7")
                        else -> Color.WHITE
                    }
                )
            }
            if (seg != null) {
                setOnClickListener {
                    val f = seg.file
                    if (f != null && f.exists()) {
                        cancelTurn()
                        playFile(f) {}
                    }
                }
            }
        }
        chatBox.addView(tv, LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(0, dp(4), 0, dp(4))
            gravity = if (kind == K_USER) Gravity.END else Gravity.START
        })
        scrollDown()
    }

    private fun showTyping() {
        hideTyping()
        val tv = TextView(this).apply {
            text = "Emma yozyapti…"
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        typingView = tv
        chatBox.addView(tv)
        scrollDown()
    }

    private fun hideTyping() {
        typingView?.let { chatBox.removeView(it) }
        typingView = null
    }

    private fun setStatus(s: String) {
        ui.post { status.text = s }
    }

    private fun micIdle() {
        listening = false
        micBtn.text = "🎤  Gapirish"
        micBg.setColor(Color.parseColor("#6C4AB6"))
        micBtn.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
    }

    private fun micListening() {
        listening = true
        micBtn.text = "⏹  Eshityapman... (to'xtatish)"
        micBg.setColor(Color.parseColor("#D32F2F"))
    }

    private fun askKey() {
        val input = EditText(this).apply {
            hint = "Gemini API key (AIza...)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("key", ""))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setMessage("Bepul kalit: aistudio.google.com/apikey saytidan oling.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("key", input.text.toString().trim()).apply()
                if (history.length() == 0) greet()
            }
            .show()
    }

    // ---------------- Boshqaruv ----------------
    private fun greet() {
        if (history.length() > 0) return
        converse("Salom Emma! Please greet me and start our English lesson.", showUser = false)
    }

    private fun resetChat() {
        cancelTurn()
        synchronized(history) { while (history.length() > 0) history.remove(0) }
        chatBox.removeAllViews()
        greet()
    }

    private fun cancelTurn() {
        turnCounter.incrementAndGet()
        stopPlayback()
        hideTyping()
    }

    private fun sendTyped() {
        val t = inputBox.text.toString().trim()
        if (t.isEmpty()) return
        inputBox.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(inputBox.windowToken, 0)
        if (listening) { recognizer?.cancel(); micIdle() }
        converse(t, showUser = true)
    }

    private fun onMic() {
        micBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (listening) {
            recognizer?.stopListening()
            return
        }
        cancelTurn() // Emma gapirayotgan bo'lsa ham to'xtatadi
        startListening()
    }

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Telefonda Google ovoz xizmati (Speech Recognition) topilmadi")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                micListening()
                setStatus("🎙 Gapiring, eshityapman...")
            }
            override fun onBeginningOfSpeech() { setStatus("🎙 Eshityapman...") }
            override fun onRmsChanged(v: Float) {
                val sc = 1f + (v.coerceIn(0f, 12f) / 12f) * 0.12f
                micBtn.animate().scaleX(sc).scaleY(sc).setDuration(100).start()
            }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("Tushunyapman...") }
            override fun onError(e: Int) {
                micIdle()
                setStatus(
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        "Eshitmadim. Mikrofonni bosib qayta urinib ko'ring."
                    else "Mikrofon xatosi ($e). Qayta urinib ko'ring."
                )
            }
            override fun onResults(r: Bundle?) {
                micIdle()
                val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) setStatus("Tushunmadim. Qayta urinib ko'ring.")
                else converse(text, showUser = true)
            }
            override fun onPartialResults(p: Bundle?) {
                val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) setStatus("🗣 \"$t\"")
            }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val lang = if (uzMode) "uz-UZ" else "en-US"
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
        }
        micListening()
        recognizer?.startListening(i)
    }

    // ---------------- Suhbat ----------------
    private fun msg(role: String, text: String) = JSONObject().put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun addUserToHistory(text: String) {
        synchronized(history) {
            val n = history.length()
            if (n > 0) {
                val last = history.getJSONObject(n - 1)
                if (last.getString("role") == "user") {
                    last.getJSONArray("parts").put(JSONObject().put("text", text))
                    return
                }
            }
            history.put(msg("user", text))
        }
    }

    private fun requestContents(): JSONArray {
        synchronized(history) {
            val out = JSONArray()
            var start = maxOf(0, history.length() - 16)
            while (start < history.length() && history.getJSONObject(start).getString("role") != "user") start++
            for (i in start until history.length()) out.put(history.get(i))
            return out
        }
    }

    private fun converse(userText: String, showUser: Boolean) {
        cancelTurn()
        val myTurn = turnCounter.get()
        usedFallback = false
        turnError = null
        if (showUser) addBubble(userText, K_USER, null)
        addUserToHistory(userText)
        showTyping()
        setStatus("Emma o'ylayapti...")
        val queue = LinkedBlockingQueue<Seg>()
        startPlayer(myTurn, queue)
        thread { runTurn(myTurn, queue) }
    }

    private fun isCurrent(t: Int) = turnCounter.get() == t

    private fun runTurn(myTurn: Int, queue: LinkedBlockingQueue<Seg>) {
        var en = ""
        var started = false
        var idx = 0

        fun emit(text: String, kind: Int, label: String) {
            val seg = Seg(text, label)
            val n = idx++
            thread {
                try {
                    seg.file = ttsFile(text, myTurn, n)
                } finally {
                    seg.done.countDown()
                }
            }
            queue.add(seg)
            ui.post { if (isCurrent(myTurn)) addBubble(text, kind, seg) }
        }

        fun handle(line: String) {
            val t = line.trim()
            if (t.isEmpty()) return
            started = true
            when {
                t.startsWith("EN:") -> { en = t.removePrefix("EN:").trim(); if (en.isNotEmpty()) emit(en, K_EN, "EN") }
                t.startsWith("UZ:") -> { val u = t.removePrefix("UZ:").trim(); if (u.isNotEmpty()) emit(u, K_UZ, "UZ") }
                t.startsWith("Tip:") -> ui.post { if (isCurrent(myTurn)) addBubble(t, K_TIP, null) }
                en.isEmpty() -> { en = t; emit(en, K_EN, "EN") }
            }
        }

        try {
            val models = if (smartMode) SMART_MODELS else FAST_MODELS
            var lastErr: Exception? = null
            var full: String? = null
            outer@ for (m in models) {
                for (thinking in listOf(true, false)) {
                    try {
                        full = streamChat(m, thinking, myTurn) { handle(it) }
                        break@outer
                    } catch (e: Cancelled) {
                        throw e
                    } catch (e: Exception) {
                        lastErr = e
                        if (started) break@outer
                        val badReq = (e.message ?: "").startsWith("HTTP 400")
                        if (!(thinking && badReq)) break
                    }
                }
            }
            val reply: String = full ?: throw (lastErr ?: RuntimeException("javob kelmadi"))
            if (isCurrent(myTurn) && reply.isNotBlank()) {
                synchronized(history) { history.put(msg("model", reply)) }
            }
        } catch (e: Cancelled) {
            // foydalanuvchi to'xtatdi
        } catch (e: Exception) {
            if (isCurrent(myTurn)) {
                turnError = "Xato: ${e.message}"
                ui.post { hideTyping() }
            }
        } finally {
            queue.add(END)
        }
    }

    private fun streamChat(model: String, thinking: Boolean, myTurn: Int, onLine: (String) -> Unit): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", requestContents())
        if (thinking) {
            body.put("generationConfig",
                JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        }
        val c = URL("$BASE$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key())
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("HTTP $code: ${err.take(200)}")
        }
        val all = StringBuilder()
        var done = 0
        c.inputStream.bufferedReader().use { r ->
            while (true) {
                if (!isCurrent(myTurn)) {
                    c.disconnect()
                    throw Cancelled()
                }
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val js = line.removePrefix("data:").trim()
                if (js.isEmpty() || js == "[DONE]") continue
                val j = try { JSONObject(js) } catch (e: Exception) { continue }
                val parts = j.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (k in 0 until parts.length()) {
                    val p = parts.getJSONObject(k)
                    if (p.optBoolean("thought", false)) continue
                    all.append(p.optString("text", ""))
                }
                while (true) {
                    val nl = all.indexOf("\n", done)
                    if (nl < 0) break
                    onLine(all.substring(done, nl))
                    done = nl + 1
                }
            }
        }
        if (done < all.length) onLine(all.substring(done))
        return all.toString()
    }

    private fun key() = prefs.getString("key", "") ?: ""

    private fun postTts(model: String, body: JSONObject): String {
        val c = URL("$BASE$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key())
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val s = (if (code in 200..299) c.inputStream else c.errorStream)
            .bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${s.take(200)}")
        return s
    }

    private fun ttsFile(text: String, turn: Int, idx: Int): File? {
        for (m in TTS_MODELS) {
            try {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts",
                        JSONArray().put(JSONObject().put("text", text)
                            .put("speech_metadata", JSONObject().put("style", "warm, friendly, natural, conversational"))))))
                    .put("generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", VOICE))))
                val j = JSONObject(postTts(m, body))
                val b64 = j.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getJSONObject("inlineData").getString("data")
                val f = File(cacheDir, "emma_${turn}_$idx.wav")
                f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                return f
            } catch (e: Exception) {
                lastTtsError = e.message
            }
        }
        return null
    }

    // ---------------- Ovoz ijrosi ----------------
    private fun startPlayer(myTurn: Int, queue: LinkedBlockingQueue<Seg>) {
        thread {
            try {
                while (true) {
                    val seg = queue.take()
                    if (seg === END) break
                    if (!isCurrent(myTurn)) continue
                    seg.done.await()
                    if (!isCurrent(myTurn)) continue
                    val f = seg.file
                    if (f != null) {
                        val l = CountDownLatch(1)
                        playLatch = l
                        ui.post {
                            if (isCurrent(myTurn)) playFile(f) { l.countDown() } else l.countDown()
                        }
                        l.await(90, TimeUnit.SECONDS)
                    } else {
                        usedFallback = true
                        if (seg.label == "EN") ui.post { fallbackSpeak(seg.text) }
                    }
                }
            } catch (e: InterruptedException) {
            }
            if (isCurrent(myTurn)) ui.post { onTurnFinished(myTurn) }
        }
    }

    private fun onTurnFinished(myTurn: Int) {
        if (!isCurrent(myTurn)) return
        hideTyping()
        val err = turnError
        when {
            err != null -> status.text = err
            usedFallback -> status.text = "Sizning navbatingiz! (zaxira ovoz: ${lastTtsError?.take(80)})"
            else -> status.text = "Sizning navbatingiz — mikrofonni bosing yoki yozing"
        }
        if (autoMode && err == null && !usedFallback && !listening) startListening()
    }

    private fun fallbackSpeak(text: String) {
        if (!fallbackReady) return
        fallbackTts?.language = Locale.US
        fallbackTts?.speak(text, TextToSpeech.QUEUE_ADD, null, "emma")
    }

    private fun playFile(f: File, done: () -> Unit) {
        try { player?.release() } catch (_: Exception) {}
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { done() }
            setOnErrorListener { _, _, _ -> done(); true }
            setOnPreparedListener { start() }
            prepareAsync()
        }
    }

    private fun stopPlayback() {
        playLatch?.countDown()
        try { player?.release() } catch (_: Exception) {}
        player = null
        fallbackTts?.stop()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) {
            cancelTurn()
            startListening()
        } else toast("Mikrofon ruxsati kerak")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        turnCounter.incrementAndGet()
        recognizer?.destroy()
        stopPlayback()
        fallbackTts?.shutdown()
        super.onDestroy()
    }
}

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
import android.view.View
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
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.inputmethod.InputMethodManager

class MainActivity : AppCompatActivity() {

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
        val CHAT_MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
        val TTS_MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")
        const val VOICE = "Kore" // ayol ovozi
        const val SYSTEM_PROMPT =
            "You are Emma, a warm, friendly young woman who teaches English through conversation only. " +
            "The user is a COMPLETE BEGINNER from Uzbekistan and speaks Uzbek (write Uzbek in Latin script). " +
            "Teach step by step: each turn teach or practice ONE very short English phrase (3-6 words) using very simple words. " +
            "Always reply in exactly this format, each on its own line:\n" +
            "EN: <1-2 very short, simple English sentences>\n" +
            "UZ: <friendly Uzbek translation and explanation of what you said, and what the user should say next, 1-2 sentences>\n" +
            "Tip: <ONLY if the user made a mistake: the corrected English sentence and a very short Uzbek reason>\n" +
            "If the user speaks Uzbek, understand it, answer in the same format and show how to say it in English. " +
            "Each turn ask the user to repeat or say a phrase. Praise small successes. " +
            "Never use emojis, markdown or lists. " +
            "Start by greeting in English and Uzbek, then teach: Hello, my name is ..."
    }

    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()

    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var micBtn: Button

    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var fallbackTts: TextToSpeech? = null
    private var fallbackReady = false
    private var busy = false
    private var listening = false
    private var uzMode = false
    private lateinit var langBtn: Button
    private lateinit var inputBox: EditText

    private val prefs by lazy { getSharedPreferences("sp", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F0FB"))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#6C4AB6"))
            setPadding(dp(16), dp(36), dp(8), dp(12))
        }
        val title = TextView(this).apply {
            text = "Emma  •  English Speaking Partner"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = Button(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { askKey() }
        }
        top.addView(settings, LinearLayout.LayoutParams(dp(56), -2))
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
            setTextColor(Color.parseColor("#555555"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputBox = EditText(this).apply {
            hint = "Yozib yuborish (English yoki o'zbekcha)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
        }
        val sendBtn = Button(this).apply {
            text = "Send"
            setOnClickListener { sendTyped() }
        }
        row.addView(inputBox, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(sendBtn, LinearLayout.LayoutParams(-2, -2))
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })

        langBtn = Button(this).apply {
            text = "Men gapiraman: English  (o'zbekchaga o'tish)"
            isAllCaps = false
            setOnClickListener {
                uzMode = !uzMode
                text = if (uzMode) "Men gapiraman: O'zbek  (inglizchaga o'tish)"
                else "Men gapiraman: English  (o'zbekchaga o'tish)"
            }
        }
        root.addView(langBtn, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })

        micBtn = Button(this).apply {
            text = "🎤  Speak"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(40).toFloat()
                setColor(Color.parseColor("#6C4AB6"))
            }
            setOnClickListener { onMic() }
        }
        val lp = LinearLayout.LayoutParams(-1, dp(72)).apply {
            setMargins(dp(24), dp(4), dp(24), dp(24))
        }
        root.addView(micBtn, lp)
        setContentView(root)
    }

    private fun addBubble(text: String, mine: Boolean, tip: Boolean = false) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(if (mine) Color.WHITE else if (tip) Color.parseColor("#7A4B00") else Color.parseColor("#222222"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(
                    when {
                        mine -> Color.parseColor("#6C4AB6")
                        tip -> Color.parseColor("#FFF1CC")
                        else -> Color.WHITE
                    }
                )
            }
        }
        val p = LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(0, dp(4), 0, dp(4))
            gravity = if (mine) Gravity.END else Gravity.START
        }
        chatBox.addView(tv, p)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) = ui.post { status.text = s }

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

    // ---------------- Flow ----------------
    private fun greet() {
        if (history.length() > 0) return
        converse("Salom Emma! Please greet me and start our English lesson.", showUser = false)
    }

    private fun sendTyped() {
        val t = inputBox.text.toString().trim()
        if (t.isEmpty() || busy) return
        inputBox.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(inputBox.windowToken, 0)
        stopPlayback()
        converse(t, showUser = true)
    }

    private fun onMic() {
        if (busy) return
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
        stopPlayback()
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Telefonda Google ovoz xizmati (Speech Recognition) topilmadi")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                listening = true
                micBtn.text = "⏹  Eshityapman... (to'xtatish uchun bosing)"
                setStatus("Eshityapman...")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                listening = false
                micBtn.text = "🎤  Speak"
                setStatus(
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        "I didn't hear you. Tap and try again."
                    else "Mic error ($e). Tap and try again."
                )
            }
            override fun onResults(r: Bundle?) {
                listening = false
                micBtn.text = "🎤  Speak"
                val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) setStatus("I didn't catch that. Try again.")
                else converse(text, showUser = true)
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val lang = if (uzMode) "uz-UZ" else "en-US"
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        }
        recognizer?.startListening(i)
    }

    private fun converse(userText: String, showUser: Boolean) {
        if (showUser) addBubble(userText, true)
        busy = true
        setStatus("Emma o'ylayapti...")
        history.put(msg("user", userText))
        thread {
            try {
                val reply = chat()
                history.put(msg("model", reply))
                var en = ""; var uz = ""; var tip = ""
                for (l in reply.trim().lines()) {
                    val t = l.trim()
                    when {
                        t.startsWith("EN:") -> en = t.removePrefix("EN:").trim()
                        t.startsWith("UZ:") -> uz = t.removePrefix("UZ:").trim()
                        t.startsWith("Tip:") -> tip = t
                        t.isNotEmpty() && en.isEmpty() -> en = t
                    }
                }
                ui.post {
                    if (en.isNotEmpty()) addBubble(en, false)
                    if (uz.isNotEmpty()) addBubble(uz, false)
                    if (tip.isNotEmpty()) addBubble(tip, false, tip = true)
                }
                setStatus("Emma gapiryapti...")
                speak(listOf(en, uz).filter { it.isNotEmpty() })
            } catch (e: Exception) {
                if (history.length() > 0) history.remove(history.length() - 1)
                setStatus("Xato: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun msg(role: String, text: String) = JSONObject().put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun key() = prefs.getString("key", "") ?: ""

    private fun post(model: String, body: JSONObject): String {
        val c = URL("$BASE$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 60000
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

    private fun chat(): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", history)
        var last: Exception? = null
        for (m in CHAT_MODELS) {
            try {
                val j = JSONObject(post(m, body))
                return j.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: RuntimeException("chat failed")
    }

    private var lastTtsError: String? = null

    private fun ttsFile(text: String, idx: Int): File? {
        for (m in TTS_MODELS) {
            try {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts",
                        JSONArray().put(JSONObject().put("text", text)
                            .put("speech_metadata", JSONObject().put("style", "warm, friendly, natural, conversational"))))))
                    .put("generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", VOICE))))
                val j = JSONObject(post(m, body))
                val b64 = j.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getJSONObject("inlineData").getString("data")
                val f = File(cacheDir, "emma_$idx.wav")
                f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                return f
            } catch (e: Exception) {
                lastTtsError = e.message
            }
        }
        return null
    }

    private fun speak(segments: List<String>) {
        val results = arrayOfNulls<File>(segments.size)
        val workers = segments.mapIndexed { i, t -> thread { results[i] = ttsFile(t, i) } }
        var played = false
        for (i in segments.indices) {
            workers[i].join()
            val f = results[i] ?: continue
            played = true
            val latch = CountDownLatch(1)
            ui.post { playFile(f) { latch.countDown() } }
            latch.await(90, TimeUnit.SECONDS)
        }
        if (played) {
            setStatus("Sizning navbatingiz! Mikrofonni bosing yoki yozing")
            return
        }
        // Zaxira: telefonning o'z ovozi
        ui.post {
            if (fallbackReady && segments.isNotEmpty()) {
                fallbackTts?.language = Locale.US
                fallbackTts?.speak(segments[0], TextToSpeech.QUEUE_FLUSH, null, "emma")
                setStatus("Sizning navbatingiz! (zaxira ovoz: ${lastTtsError?.take(60)})")
            } else setStatus("Sizning navbatingiz!")
        }
    }

    private fun playFile(f: File, done: () -> Unit) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { done() }
            setOnErrorListener { _, _, _ -> done(); true }
            setOnPreparedListener { start() }
            prepareAsync()
        }
    }

    private fun stopPlayback() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        fallbackTts?.stop()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) startListening()
        else toast("Mikrofon ruxsati kerak")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        recognizer?.destroy()
        stopPlayback()
        fallbackTts?.shutdown()
        super.onDestroy()
    }
}

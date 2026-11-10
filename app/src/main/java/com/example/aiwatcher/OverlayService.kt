package com.example.aiwatcher

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("cap").also { it.start() }
    private val bg by lazy { Handler(bgThread.looper) }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var replyView: TextView
    private lateinit var personaBtn: Button
    private lateinit var micBtn: Button
    private lateinit var screenBtn: Button
    private lateinit var ttsBtn: Button

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var latest: Bitmap? = null
    private var lastGrab = 0L
    private var lastThumb: IntArray? = null
    private var lastAuto = 0L

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var listening = false
    @Volatile private var speaking = false
    @Volatile private var busy = false
    private val history = ArrayList<Pair<String, String>>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = speakDone()
                    @Deprecated("") override fun onError(id: String?) = speakDone()
                })
            }
        }
        buildOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = NotificationChannel("ai", "AI 助手", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, "ai").setContentTitle("AI 屏幕助手运行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>("data")
        if (data != null && projection == null) setupProjection(code, data)
        return START_NOT_STICKY
    }

    // ---------------- 屏幕捕获 ----------------
    private fun screenSize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        val (w, h, dpi) = screenSize()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = mp.createVirtualDisplay("cap", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.uptimeMillis()
                if (!Store.useScreen || now - lastGrab < 1000) return@setOnImageAvailableListener
                lastGrab = now
                val iw = img.width; val ih = img.height
                val p = img.planes[0]
                val pad = p.rowStride - p.pixelStride * iw
                val full = Bitmap.createBitmap(iw + pad / p.pixelStride, ih, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(p.buffer)
                val bmp = if (full.width == iw) full else Bitmap.createBitmap(full, 0, 0, iw, ih).also { full.recycle() }
                latest = bmp
                onFrame(bmp)
            } finally { img.close() }
        }, bg)
    }

    /** 主动评论模式:屏幕明显变化时才问 AI,避免狂烧额度 */
    private fun onFrame(b: Bitmap) {
        if (!Store.autoComment) return
        val t = Bitmap.createScaledBitmap(b, 16, 28, true)
        val px = IntArray(16 * 28); t.getPixels(px, 0, 16, 0, 0, 16, 28)
        val prev = lastThumb; lastThumb = px
        if (prev == null) return
        var d = 0L
        for (i in px.indices) d += abs(Color.green(px[i]) - Color.green(prev[i]))
        val now = SystemClock.uptimeMillis()
        if (d / px.size > 14 && now - lastAuto > Store.autoInterval * 1000L && !busy && !speaking) {
            lastAuto = now
            ask("(屏幕内容发生了变化,请简短评论或给点帮助;如果没什么值得说的,只回复“无”)", auto = true)
        }
    }

    // ---------------- 问 AI ----------------
    private fun ask(text: String, auto: Boolean = false) {
        if (busy) return
        busy = true
        if (!auto) main.post { replyView.text = "思考中…" }
        val persona = Store.active()
        val frame = if (Store.useScreen) latest else null
        Thread {
            try {
                val out = Ai.chat(persona, history, text, frame)
                history.add("user" to text); history.add("assistant" to out)
                while (history.size > 16) history.removeAt(0)
                val skip = auto && out.trim().trimEnd('。', '.') == "无"
                if (!skip) main.post {
                    replyView.text = out
                    if (!Store.tts && panel.visibility != View.VISIBLE) panel.visibility = View.VISIBLE
                    speak(out)
                }
            } catch (e: Exception) {
                if (!auto) main.post { replyView.text = "出错:" + e.message }
            } finally { busy = false }
        }.start()
    }

    // ---------------- 语音 ----------------
    private fun speak(t: String) {
        if (!Store.tts || !ttsReady) return
        speaking = true
        recognizer?.cancel()
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun speakDone() {
        speaking = false
        if (listening) main.postDelayed({ beginRec() }, 300)
    }

    private fun beginRec() {
        if (!listening || speaking) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle?) {
                        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!t.isNullOrBlank()) ask(t)
                        main.postDelayed({ beginRec() }, 500)
                    }
                    override fun onError(code: Int) {
                        if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { setListening(false); return }
                        main.postDelayed({ beginRec() }, if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000 else 400)
                    }
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
            }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        try { recognizer?.startListening(i) } catch (_: Exception) {}
    }

    private fun setListening(on: Boolean) {
        if (on && !SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "本机没有语音识别服务(国产系统常见),见 README", Toast.LENGTH_LONG).show()
            return
        }
        listening = on
        micBtn.text = if (on) "🎤 聆听中(点击停止)" else "🎤 开始聆听"
        if (on) beginRec() else recognizer?.cancel()
    }

    // ---------------- 悬浮窗界面 ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false
        minHeight = 0; minimumHeight = 0; setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun buildOverlay() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "AI"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF6750A4.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xEE222222.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        personaBtn = btn("角色:" + Store.active().name) {
            val l = Store.personas()
            val next = l[(l.indexOfFirst { it.id == Store.activeId } + 1) % l.size]
            Store.activeId = next.id; history.clear()
            personaBtn.text = "角色:" + next.name
        }
        micBtn = btn("🎤 开始聆听") { setListening(!listening) }
        screenBtn = btn(if (Store.useScreen) "👁 监看:开" else "👁 监看:关") {
            Store.useScreen = !Store.useScreen
            screenBtn.text = if (Store.useScreen) "👁 监看:开" else "👁 监看:关"
        }
        ttsBtn = btn(if (Store.tts) "🔊 语音:开" else "🔊 语音:关") {
            Store.tts = !Store.tts
            ttsBtn.text = if (Store.tts) "🔊 语音:开" else "🔊 语音:关"
            if (!Store.tts) tts?.stop()
        }
        val lookBtn = btn("👀 看看我的屏幕") { ask("请看看我现在的屏幕,说说你的看法。") }
        val closeBtn = btn("退出助手") { stopSelf() }

        replyView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; text = "点“开始聆听”直接对我说话" }
        val scroll = ScrollView(this).apply {
            addView(replyView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(8) }
        }

        fun row(vararg v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        panel.addView(personaBtn)
        panel.addView(row(micBtn))
        panel.addView(row(screenBtn, ttsBtn))
        panel.addView(row(lookBtn, closeBtn))
        panel.addView(scroll)

        root.addView(bubble); root.addView(panel)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(200) }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(root, lp)
                    }
                    MotionEvent.ACTION_UP -> if (!moved)
                        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                return true
            }
        })
        wm.addView(root, lp)
    }

    override fun onDestroy() {
        listening = false
        try { wm.removeView(root) } catch (_: Exception) {}
        recognizer?.destroy(); tts?.shutdown()
        vd?.release(); reader?.close(); projection?.stop(); bgThread.quitSafely()
        super.onDestroy()
    }
}

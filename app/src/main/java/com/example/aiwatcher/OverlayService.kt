package com.example.aiwatcher

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
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
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("cap").also { it.start() }
    private val bg by lazy { Handler(bgThread.looper) }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var panelBg: GradientDrawable
    private lateinit var bubble: ImageView
    private lateinit var replyView: TextView
    private lateinit var personaBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var screenBtn: TextView
    private lateinit var ttsBtn: TextView

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
    private val masks = CopyOnWriteArrayList<Mask>()

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

    /** 把所有遮挡框区域涂黑,AI 永远看不到这些位置 */
    private fun applyMasks(b: Bitmap) {
        if (masks.isEmpty()) return
        val c = Canvas(b)
        val p = Paint().apply { color = Color.BLACK }
        masks.forEach {
            c.drawRect(it.lp.x.toFloat(), it.lp.y.toFloat(),
                (it.lp.x + it.lp.width).toFloat(), (it.lp.y + it.lp.height).toFloat(), p)
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
                applyMasks(bmp)
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
        // 发送前再涂一次黑(遮挡框可能刚被移动过)
        val frame = if (Store.useScreen) latest?.copy(Bitmap.Config.ARGB_8888, true)?.also { applyMasks(it) } else null
        Thread {
            try {
                val out = Ai.chat(persona, history, text, frame)
                history.add("user" to text); history.add("assistant" to out)
                while (history.size > 16) history.removeAt(0)
                val skip = auto && out.trim().trimEnd('。', '.') == "无"
                if (!skip) main.post {
                    replyView.text = out
                    if (!Store.tts && panel.visibility != View.VISIBLE) showPanel(true)
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
        setOn(micBtn, on)
        if (on) beginRec() else recognizer?.cancel()
    }

    // ---------------- 遮挡框 ----------------
    /** 黑色遮挡框:点一下删除,长按后拖动移动,拖右下角 ◢ 调整大小 */
    private inner class Mask {
        val lp = WindowManager.LayoutParams(
            dp(200), dp(120), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (sw, sh, _) = screenSize()
            x = (sw - dp(200)) / 2; y = sh / 3
        }
        val view = FrameLayout(this@OverlayService).apply { setBackgroundColor(Color.BLACK) }

        fun remove() {
            try { wm.removeView(view) } catch (_: Exception) {}
            masks.remove(this)
        }

        init {
            val handle = TextView(this@OverlayService).apply {
                text = "◢"; setTextColor(0xFF4DA8FF.toInt()); textSize = 20f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.END)
            }
            view.addView(handle)

            var rw = 0; var rh = 0; var rx = 0f; var ry = 0f
            handle.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { rw = lp.width; rh = lp.height; rx = e.rawX; ry = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.width = max(dp(40), rw + (e.rawX - rx).toInt())
                        lp.height = max(dp(40), rh + (e.rawY - ry).toInt())
                        wm.updateViewLayout(view, lp)
                    }
                }
                true
            }

            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            var dragging = false; var moved = false
            val longPress = Runnable {
                dragging = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            view.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY
                        dragging = false; moved = false
                        main.postDelayed(longPress, 350)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                        if (!dragging && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                            moved = true; main.removeCallbacks(longPress)
                        }
                        if (dragging) { lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(view, lp) }
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(longPress)
                        if (!dragging && !moved) remove()
                        dragging = false
                    }
                    MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); dragging = false }
                }
                true
            }
            wm.addView(view, lp)
            masks.add(this)
        }
    }

    // ---------------- 悬浮窗界面 ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 18) =
        GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(color) }

    private fun blueGrad(r: Int = 18) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0084FF.toInt(), 0xFF4DA8FF.toInt())
    ).apply { cornerRadius = dp(r).toFloat() }

    private fun setOn(v: TextView, on: Boolean) { v.background = if (on) blueGrad() else rounded(0x26FFFFFF) }

    private fun pill(label: String, on: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 12.5f; gravity = Gravity.CENTER
        setPadding(dp(6), dp(9), dp(6), dp(9)); setOn(this, on)
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
    }

    private fun label(t: String, size: Float = 11f, color: Int = 0x99FFFFFF.toInt()) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); setPadding(dp(4), dp(6), dp(4), dp(2))
    }

    private fun showPanel(show: Boolean) {
        panel.visibility = if (show) View.VISIBLE else View.GONE
        applyOpacity()
    }

    private fun applyOpacity() {
        val o = Store.opacity.coerceIn(20, 100)
        panelBg.alpha = o * 255 / 100
        bubble.alpha = o / 100f
    }

    private fun buildOverlay() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bubble = ImageView(this).apply {
            val f = File(filesDir, "avatar.png")
            if (f.exists()) setImageBitmap(BitmapFactory.decodeFile(f.path)) else setImageResource(R.drawable.avatar)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xFF0084FF.toInt()); setStroke(dp(2), 0xCCFFFFFF.toInt())
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }

        panelBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF182338.toInt(), 0xFF0C1220.toInt())
        ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x40FFFFFF) }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = panelBg
            layoutParams = LinearLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }

        personaBtn = pill("角色:" + Store.active().name, on = true) {
            val l = Store.personas()
            val next = l[(l.indexOfFirst { it.id == Store.activeId } + 1) % l.size]
            Store.activeId = next.id; history.clear()
            personaBtn.text = "角色:" + next.name
        }
        micBtn = pill("🎤 开始聆听") { setListening(!listening) }
        screenBtn = pill(if (Store.useScreen) "👁 监看:开" else "👁 监看:关", Store.useScreen) {
            Store.useScreen = !Store.useScreen
            screenBtn.text = if (Store.useScreen) "👁 监看:开" else "👁 监看:关"
            setOn(screenBtn, Store.useScreen)
        }
        ttsBtn = pill(if (Store.tts) "🔊 语音:开" else "🔊 语音:关", Store.tts) {
            Store.tts = !Store.tts
            ttsBtn.text = if (Store.tts) "🔊 语音:开" else "🔊 语音:关"
            setOn(ttsBtn, Store.tts)
            if (!Store.tts) tts?.stop()
        }
        val lookBtn = pill("👀 看看屏幕") { ask("请看看我现在的屏幕,说说你的看法。") }
        val quitBtn = pill("退出助手") { stopSelf() }
        val addMask = pill("⬛ 新建遮挡框") { Mask() }
        val clearMask = pill("清除遮挡框") { masks.toList().forEach { it.remove() } }

        val seek = SeekBar(this).apply {
            max = 80; progress = Store.opacity.coerceIn(20, 100) - 20
            progressTintList = ColorStateList.valueOf(0xFF4DA8FF.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF4DA8FF.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    Store.opacity = p + 20; applyOpacity()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val opRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label("透明度"))
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        replyView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setLineSpacing(0f, 1.15f)
            text = "点“开始聆听”直接对我说话"; setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val scroll = ScrollView(this).apply {
            background = rounded(0x14FFFFFF, 14); addView(replyView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)).apply { topMargin = dp(8) }
        }

        panel.addView(label("AI 屏幕助手", 14f, Color.WHITE))
        panel.addView(row(personaBtn))
        panel.addView(row(micBtn))
        panel.addView(row(screenBtn, ttsBtn))
        panel.addView(row(lookBtn, quitBtn))
        panel.addView(label("工具"))
        panel.addView(row(addMask, clearMask))
        panel.addView(label("遮挡框:长按拖动 · 拖 ◢ 缩放 · 点一下删除。框内区域 AI 看不到", 10f))
        panel.addView(opRow)
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
                    MotionEvent.ACTION_UP -> if (!moved) showPanel(panel.visibility != View.VISIBLE)
                }
                return true
            }
        })
        applyOpacity()
        wm.addView(root, lp)
    }

    override fun onDestroy() {
        listening = false
        masks.toList().forEach { it.remove() }
        try { wm.removeView(root) } catch (_: Exception) {}
        recognizer?.destroy(); tts?.shutdown()
        vd?.release(); reader?.close(); projection?.stop(); bgThread.quitSafely()
        super.onDestroy()
    }
}

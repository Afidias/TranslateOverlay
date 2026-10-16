package com.dsh.translateoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮翻译窗服务。
 *
 * ## 结构
 * - **悬浮球**：常驻，可拖动；单击展开翻译面板。
 * - **翻译面板**：输入原文 → 调用大模型 → 展示译文 → 一键复制。
 *
 * ## 为什么要前台服务
 * Android 8+ 对后台进程管控严格，普通 Service 会在应用退到后台后被回收，
 * 悬浮窗随之消失。前台服务 + 常驻通知可以让悬浮球稳定存活在任意应用之上
 * （包括百度输入法的候选栏区域）。
 *
 * ## 窗口类型
 * - Android 8.0（API 26）及以上必须用 [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]；
 * - 更低版本回退到 TYPE_PHONE。
 * 两者都需要用户在系统设置里授予「显示在其他应用上层」。
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "translate_overlay_channel"
        private const val NOTIFICATION_ID = 1101

        const val ACTION_START = "com.dsh.translateoverlay.START"
        const val ACTION_STOP = "com.dsh.translateoverlay.STOP"

        /** 供界面查询服务是否在运行。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 判定拖动与点击的距离阈值（px）。 */
        private const val DRAG_SLOP = 12

        /** 悬浮球直径（dp），需与 overlay_ball.xml 保持一致。 */
        private const val BALL_SIZE_DP = 56

        /**
         * 贴边时保留的可见宽度比例。
         * 0.4 ≈ 露出一小条（约 22dp），既几乎不挡视线，又足够手指点中。
         */
        private const val BALL_EDGE_VISIBLE_RATIO = 0.4f

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }

        /** 服务是否被用户允许显示悬浮窗。 */
        fun canDrawOverlay(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: AppPrefs

    private var ballView: View? = null
    private var panelView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 面板控件引用，避免每次都用 findViewById。 */
    private var etInput: EditText? = null
    private var tvResult: TextView? = null
    private var tvStatus: TextView? = null
    private var tvLangPair: TextView? = null
    private var btnTranslate: View? = null

    /** 上一次翻译的原文，用于「复制」时判断复制什么。 */
    @Volatile
    private var lastTranslated: String = ""

    @Volatile
    private var translating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = AppPrefs(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 已在前台服务中：确保悬浮球存在
                if (ballView == null) showBall()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        removePanel()
        ballView?.let { v ->
            try { windowManager.removeView(v) } catch (_: Exception) { }
        }
        ballView = null
        super.onDestroy()
    }

    // ── 通知 ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "保持悬浮翻译框运行"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_status_on))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    // ── 悬浮球 ───────────────────────────────────────────────────

    private fun ballParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 不抢焦点（不拦截键盘），但可触摸。
            // 注意：FLAG_NOT_FOCUSABLE 已隐含 FLAG_NOT_TOUCH_MODAL，
            // 所以球外面的触摸会正常传给下层应用。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 允许球「贴边半隐藏」——用户常这么干来避免挡视线。
            // 但会校验至少有一条露在屏幕内，避免彻底点不到。
            x = resolveSavedX()
            y = resolveSavedY()
        }
    }

    /**
     * 还原上次的 X 位置。
     *
     * 允许负值（球贴左边只露一条），只要还有可见部分就沿用；
     * 完全跑到屏幕外时视为无效数据，重置回默认位置。
     */
    private fun resolveSavedX(): Int {
        if (!prefs.ballPosSet) return defaultBallX()
        val ballW = dp(BALL_SIZE_DP)
        val screenW = resources.displayMetrics.widthPixels
        val saved = prefs.ballX

        // 完全不可见 → 重置，否则用户再也点不到
        if (saved + ballW <= 0 || saved >= screenW) return defaultBallX()

        // 露出宽度太小（例如只剩十几像素）→ 吸附到最近一侧，
        // 补足到最小可点击宽度，避免"看得见却点不中"
        val visibleLeft = maxOf(saved, 0)
        val visibleRight = minOf(saved + ballW, screenW)
        val visibleWidth = visibleRight - visibleLeft
        if (visibleWidth < minVisibleWidth()) {
            val centerX = saved + ballW / 2f
            return if (centerX < screenW / 2f) {
                -(ballW - minVisibleWidth())
            } else {
                screenW - minVisibleWidth()
            }
        }
        return saved
    }

    /** 还原上次的 Y 位置（上下方向始终保持整颗球在屏幕内）。 */
    private fun resolveSavedY(): Int {
        if (!prefs.ballPosSet) return defaultBallY()
        return prefs.ballY.coerceIn(0, maxBallY())
    }

    /** 贴边时至少保留的可见宽度（px）。 */
    private fun minVisibleWidth(): Int =
        (dp(BALL_SIZE_DP) * BALL_EDGE_VISIBLE_RATIO).roundToInt()

    /**
     * 松手后收边。
     *
     * - 球完全在屏幕内 → 保持原位（用户可能就想放在中间）
     * - 球越过左右边缘 → 吸附到最近的一侧，只露出一小条
     *
     * 这样既满足「只露一点点、不挡视线」，又保证永远点得到。
     */
    private fun snapBallToEdge(view: View, params: WindowManager.LayoutParams) {
        try {
            val ballW = dp(BALL_SIZE_DP)
            val screenW = resources.displayMetrics.widthPixels
            val visible = minVisibleWidth()
            if (params.x < 0 || params.x + ballW > screenW) {
                val centerX = params.x + ballW / 2f
                params.x = if (centerX < screenW / 2f) {
                    -(ballW - visible)      // 贴左边，露出 visible 宽
                } else {
                    screenW - visible       // 贴右边，露出 visible 宽
                }
                windowManager.updateViewLayout(view, params)
            }
        } catch (_: Exception) { }
    }

    /** 悬浮球能停留的最大 X（保证整颗球都在屏幕内）。 */
    private fun maxBallX(): Int =
        (resources.displayMetrics.widthPixels - dp(BALL_SIZE_DP)).coerceAtLeast(0)

    /** 悬浮球能停留的最大 Y。 */
    private fun maxBallY(): Int =
        (resources.displayMetrics.heightPixels - dp(BALL_SIZE_DP)).coerceAtLeast(0)

    private fun defaultBallX(): Int =
        (resources.displayMetrics.widthPixels - dp(BALL_SIZE_DP) - dp(8)).coerceAtLeast(0)

    private fun defaultBallY(): Int {
        val h = resources.displayMetrics.heightPixels
        return (h * 0.55f).roundToInt().coerceIn(0, maxBallY())
    }

    private fun showBall() {
        if (ballView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_ball, null)
        val params = ballParams()
        attachBallDrag(view, params)
        try {
            windowManager.addView(view, params)
            ballView = view
        } catch (e: Exception) {
            // 权限被回收时会抛异常，静默失败避免崩溃
            stopSelf()
        }
    }

    /** 悬浮球拖动 + 点击（拖动位移小于阈值视为点击）。 */
    private fun attachBallDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP) moved = true
                    if (moved) {
                        // X 不设限：允许拖到屏幕外，实现"贴边只露一条"的效果。
                        // Y 限制在屏幕内：上下跑丢就真的找不回来了。
                        params.x = (startX + dx).roundToInt()
                        params.y = (startY + dy).roundToInt().coerceIn(0, maxBallY())
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // 松手收边：越过边缘就吸附到最近一侧，只露一小条
                        snapBallToEdge(view, params)
                        prefs.ballX = params.x
                        prefs.ballY = params.y
                        prefs.ballPosSet = true
                    } else {
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ── 翻译面板 ─────────────────────────────────────────────────

    private fun togglePanel() {
        if (panelView == null) showPanel() else removePanel()
    }

    private fun panelParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val width = (resources.displayMetrics.widthPixels * 0.92f).roundToInt()

        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 注意 1：不能加 FLAG_NOT_FOCUSABLE，否则输入框拿不到焦点、键盘弹不出来。
            // 注意 2：必须加 FLAG_NOT_TOUCH_MODAL —— 它让「窗口范围之外的触摸事件」
            //         继续下发给后面的应用窗口。没有它，面板会独占整个屏幕的触摸，
            //         表现就是"开了翻译面板之后别的什么都点不动了"。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((resources.displayMetrics.widthPixels - width) / 2f).roundToInt()
            // 起始位置放屏幕偏上：键盘弹出时占下半屏，避免直接盖住面板
            y = (resources.displayMetrics.heightPixels * 0.12f).roundToInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
    }

    private fun showPanel() {
        if (panelView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val params = panelParams()

        etInput = view.findViewById(R.id.etInput)
        tvResult = view.findViewById(R.id.tvResult)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvLangPair = view.findViewById(R.id.tvLangPair)
        btnTranslate = view.findViewById(R.id.btnTranslate)

        // 预填上次结果
        if (prefs.lastResult.isNotBlank()) {
            tvResult?.text = prefs.lastResult
            tvResult?.setTextColor(0xFFE6E9F2.toInt())
        }
        refreshLangLabel()

        // 自动读取剪贴板（方便直接翻译别人发来的内容）
        if (prefs.autoPaste) {
            readClipboardInto(etInput)
        }

        view.findViewById<View>(R.id.btnClose).setOnClickListener { removePanel() }
        view.findViewById<View>(R.id.btnTranslate).setOnClickListener { doTranslate() }
        view.findViewById<View>(R.id.btnCopy).setOnClickListener { copyResult() }
        view.findViewById<View>(R.id.btnClear).setOnClickListener {
            etInput?.setText("")
            tvResult?.text = getString(R.string.overlay_result_placeholder)
            tvResult?.setTextColor(0xFF9AA3B8.toInt())
            lastTranslated = ""
        }
        view.findViewById<View>(R.id.btnSwap).setOnClickListener {
            val s = prefs.sourceLang
            prefs.sourceLang = prefs.targetLang
            prefs.targetLang = s
            refreshLangLabel()
        }

        // 输入框获得焦点（用户准备打字）时，把面板移到屏幕顶部，
        // 避免被随之弹出的输入法遮住。键盘收起后不再自动移回，用户可自行拖动。
        etInput?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) movePanelToTop(view, params)
        }

        attachPanelDrag(view.findViewById(R.id.panelHeader), view, params)

        try {
            windowManager.addView(view, params)
            panelView = view
            // 隐藏悬浮球，避免遮挡面板
            ballView?.visibility = View.GONE
            // 刻意不自动 requestFocus：一打开就弹键盘会挡住内容，
            // 也不方便"一边翻译一边操作其它应用"。用户点输入框时自然弹出。
        } catch (e: Exception) {
            panelView = null
        }
    }

    /** 把面板顶到屏幕上方（键盘弹出前的避让）。 */
    private fun movePanelToTop(panel: View, params: WindowManager.LayoutParams) {
        try {
            val top = dp(12)
            if (params.y > top) {
                params.y = top
                windowManager.updateViewLayout(panel, params)
            }
        } catch (_: Exception) { }
    }

    /** 面板通过顶部标题栏拖动。 */
    private fun attachPanelDrag(
        handle: View,
        panel: View,
        params: WindowManager.LayoutParams
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (event.rawX - downX)).roundToInt()
                    params.y = (startY + (event.rawY - downY)).roundToInt()
                    try { windowManager.updateViewLayout(panel, params) } catch (_: Exception) { }
                    true
                }
                else -> false
            }
        }
    }

    private fun removePanel() {
        hideKeyboard()
        panelView?.let { v ->
            try { windowManager.removeView(v) } catch (_: Exception) { }
        }
        panelView = null
        etInput = null
        tvResult = null
        tvStatus = null
        tvLangPair = null
        btnTranslate = null
        ballView?.visibility = View.VISIBLE
    }

    private fun refreshLangLabel() {
        tvLangPair?.text = "${prefs.sourceLang} → ${prefs.targetLang}"
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            etInput?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) { }
    }

    private fun readClipboardInto(target: EditText?) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            val s = text?.toString().orEmpty()
            if (s.isNotBlank() && target?.text.isNullOrBlank()) {
                target?.setText(s)
                target?.setSelection(s.length)
            }
        } catch (_: Exception) { }
    }

    // ── 翻译 ─────────────────────────────────────────────────────

    private fun doTranslate() {
        if (translating) return
        val input = etInput?.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            setStatus("请输入内容")
            return
        }

        translating = true
        btnTranslate?.isEnabled = false
        setStatus(getString(R.string.overlay_translating))
        tvResult?.text = "…"
        tvResult?.setTextColor(0xFF9AA3B8.toInt())

        val sourceLang = prefs.sourceLang
        val targetLang = prefs.targetLang
        val apiKey = prefs.apiKey
        val baseUrl = prefs.baseUrl
        val model = prefs.model

        // 网络请求必须在后台线程
        Thread {
            val result = TranslateApi.translate(
                text = input,
                sourceLang = sourceLang,
                targetLang = targetLang,
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model
            )
            mainHandler.post {
                translating = false
                btnTranslate?.isEnabled = true
                when (result) {
                    is TranslateApi.Result.Ok -> {
                        lastTranslated = result.text
                        prefs.lastResult = result.text
                        tvResult?.text = result.text
                        tvResult?.setTextColor(0xFFE6E9F2.toInt())
                        setStatus("完成 · ${result.model.ifBlank { model }}")
                    }
                    is TranslateApi.Result.Err -> {
                        tvResult?.text = "❌ ${result.message}"
                        tvResult?.setTextColor(0xFFFF8A8A.toInt())
                        setStatus("失败")
                    }
                }
            }
        }.start()
    }

    private fun setStatus(text: String) {
        tvStatus?.text = text
    }

    private fun copyResult() {
        val text = lastTranslated.ifBlank {
            tvResult?.text?.toString().orEmpty()
        }
        if (text.isBlank() || text == getString(R.string.overlay_result_placeholder)) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("translation", text))
            setStatus(getString(R.string.overlay_copied))
        } catch (_: Exception) { }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}

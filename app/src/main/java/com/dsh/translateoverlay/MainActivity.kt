package com.dsh.translateoverlay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dsh.translateoverlay.databinding.ActivityMainBinding

/**
 * 主界面：配置 API、授予悬浮窗权限、开关悬浮翻译框。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 请求悬浮窗权限的 requestCode。 */
    private val overlayPermissionRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        loadPrefsIntoUi()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayState()
    }

    private fun loadPrefsIntoUi() {
        binding.etApiKey.setText(prefs.apiKey)
        binding.etBaseUrl.setText(prefs.baseUrl)
        binding.etModel.setText(prefs.model)
        binding.etSourceLang.setText(prefs.sourceLang)
        binding.etTargetLang.setText(prefs.targetLang)
        binding.swAutoPaste.isChecked = prefs.autoPaste
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            savePrefs()
            toast("配置已保存")
        }

        binding.btnTest.setOnClickListener {
            savePrefs()
            testConnection()
        }

        binding.swOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                // 打开悬浮窗需要权限，未授权时先跳去授权页
                if (!OverlayService.canDrawOverlay(this)) {
                    binding.swOverlay.isChecked = false
                    requestOverlayPermission()
                } else {
                    startOverlay()
                }
            } else {
                stopOverlay()
            }
        }

        binding.swAutoPaste.setOnCheckedChangeListener { _, checked ->
            prefs.autoPaste = checked
        }
    }

    private fun savePrefs() {
        prefs.apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        prefs.baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { AppPrefs.DEFAULT_BASE_URL }
        prefs.model = binding.etModel.text?.toString()?.trim().orEmpty()
            .ifBlank { AppPrefs.DEFAULT_MODEL }
        prefs.sourceLang = binding.etSourceLang.text?.toString()?.trim().orEmpty()
            .ifBlank { "auto" }
        prefs.targetLang = binding.etTargetLang.text?.toString()?.trim().orEmpty()
            .ifBlank { "English" }
        prefs.autoPaste = binding.swAutoPaste.isChecked
    }

    // ── 悬浮窗权限 ───────────────────────────────────────────────

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            startOverlay()
            return
        }
        toast("请允许「显示在其他应用上层」")
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, overlayPermissionRequest)
        } catch (e: Exception) {
            // 个别 ROM 没有这个页面，退回应用详情页
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) { }
        }
    }

    @Deprecated("startActivityForResult 在自用应用里足够，避免引入 activity-result 依赖")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayPermissionRequest) {
            if (OverlayService.canDrawOverlay(this)) {
                binding.swOverlay.isChecked = true
                startOverlay()
            } else {
                toast("未获得悬浮窗权限，无法显示翻译框")
            }
        }
    }

    private fun startOverlay() {
        savePrefs()
        if (!prefs.hasApiKey) {
            toast("提示：还没有填写 API Key，悬浮框将无法翻译")
        }
        OverlayService.start(this)
        // 给服务一点启动时间再刷新状态
        mainHandler.postDelayed({ refreshOverlayState() }, 350)
    }

    private fun stopOverlay() {
        OverlayService.stop(this)
        mainHandler.postDelayed({ refreshOverlayState() }, 350)
    }

    private fun refreshOverlayState() {
        val running = OverlayService.isRunning
        val allowed = OverlayService.canDrawOverlay(this)
        binding.swOverlay.isChecked = running

        val status = when {
            running -> getString(R.string.overlay_status_on)
            !allowed -> getString(R.string.overlay_status_need_permission)
            else -> getString(R.string.overlay_status_off)
        }
        binding.tvOverlayStatus.text = status
    }

    // ── 测试连接 ─────────────────────────────────────────────────

    private fun testConnection() {
        val tv: TextView = binding.tvTestResult
        tv.text = "正在测试连接…"
        tv.setTextColor(0xFF8E96A8.toInt())

        val apiKey = prefs.apiKey
        val baseUrl = prefs.baseUrl
        val model = prefs.model

        Thread {
            val result = TranslateApi.testConnection(apiKey, baseUrl, model)
            mainHandler.post {
                when (result) {
                    is TranslateApi.Result.Ok ->
                        tv.text = "✅ 连接成功\n模型：$model\n返回：${result.text.take(80)}"
                            .also { tv.setTextColor(0xFF7BE39B.toInt()) }
                    is TranslateApi.Result.Err ->
                        tv.text = "❌ ${result.message}"
                            .also { tv.setTextColor(0xFFFF8A8A.toInt()) }
                }
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

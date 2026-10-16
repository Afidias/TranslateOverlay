package com.dsh.translateoverlay

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量配置存储。
 *
 * 首次使用时预置 DeepSeek 默认值，用户可在主界面修改。
 * 所有值写入 SharedPreferences，退出后保留。
 */
class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("translate_overlay", Context.MODE_PRIVATE)

    /** 翻译后端 API Key。 */
    var apiKey: String
        get() = sp.getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
        set(v) = sp.edit().putString(KEY_API_KEY, v).apply()

    /** API Base URL（兼容 OpenAI 格式）。 */
    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString(KEY_BASE_URL, v).apply()

    /** 模型名。 */
    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString(KEY_MODEL, v).apply()

    /** 目标语言（中文翻译成什么语言）。 */
    var targetLang: String
        get() = sp.getString(KEY_TARGET_LANG, "English") ?: "English"
        set(v) = sp.edit().putString(KEY_TARGET_LANG, v).apply()

    /** 源语言，"auto" 表示自动检测。 */
    var sourceLang: String
        get() = sp.getString(KEY_SOURCE_LANG, "中文") ?: "中文"
        set(v) = sp.edit().putString(KEY_SOURCE_LANG, v).apply()

    /** 悬浮球上次停留的 X 坐标。贴左边时可为负值（球只露一条）。 */
    var ballX: Int
        get() = sp.getInt(KEY_BALL_X, 0)
        set(v) = sp.edit().putInt(KEY_BALL_X, v).apply()

    /** 悬浮球上次停留的 Y 坐标。 */
    var ballY: Int
        get() = sp.getInt(KEY_BALL_Y, -1)
        set(v) = sp.edit().putInt(KEY_BALL_Y, v).apply()

    /**
     * 是否已经记录过悬浮球位置。
     *
     * 单独用一个布尔量而不是「X == -1」来判定：因为贴左边时 X 本身就是负数，
     * 用值本身当哨兵会混淆「没记录过」和「贴在左边外面」两种情况。
     */
    var ballPosSet: Boolean
        get() = sp.getBoolean(KEY_BALL_POS_SET, false)
        set(v) = sp.edit().putBoolean(KEY_BALL_POS_SET, v).apply()

    /** 是否在打开面板时自动读取剪贴板内容。 */
    var autoPaste: Boolean
        get() = sp.getBoolean(KEY_AUTO_PASTE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_PASTE, v).apply()

    /** 上次翻译结果，便于误关面板后恢复。 */
    var lastResult: String
        get() = sp.getString(KEY_LAST_RESULT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_RESULT, v).apply()

    val hasApiKey: Boolean
        get() = apiKey.isNotBlank() && !apiKey.contains("你的")

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_BALL_X = "ball_x"
        private const val KEY_BALL_Y = "ball_y"
        private const val KEY_BALL_POS_SET = "ball_pos_set"
        private const val KEY_AUTO_PASTE = "auto_paste"
        private const val KEY_LAST_RESULT = "last_result"

        /** 默认 Base URL：DeepSeek。 */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /** 默认模型：DeepSeek 现役 flash 模型。 */
        const val DEFAULT_MODEL = "deepseek-flash"

        /** API Key 占位符，用户需自行替换。 */
        const val DEFAULT_API_KEY = "sk-你的DeepSeek密钥"
    }
}

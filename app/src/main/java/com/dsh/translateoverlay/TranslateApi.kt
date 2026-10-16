package com.dsh.translateoverlay

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek / OpenAI 兼容接口客户端。
 *
 * 直接用原生 `HttpURLConnection`：
 * - 无需引入 OkHttp 等依赖，APK 更小；
 * - 4xx/5xx 时能读 `errorStream` 拿到服务端真实报错，
 *   而不是被上层库归一化成无意义的 "closed"。
 */
object TranslateApi {

    /** 翻译结果。 */
    sealed class Result {
        data class Ok(val text: String, val model: String) : Result()
        data class Err(val message: String, val code: Int? = null) : Result()
    }

    /** 一次翻译请求的超时（毫秒）。 */
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 60_000

    /**
     * 把 [text] 从 [sourceLang] 翻译到 [targetLang]。
     *
     * 该方法是阻塞的，必须在后台线程调用。
     */
    fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        baseUrl: String,
        model: String
    ): Result {
        if (text.isBlank()) return Result.Err("没有需要翻译的内容")
        if (apiKey.isBlank() || apiKey.contains("你的")) {
            return Result.Err("请先在主界面填写 DeepSeek API Key")
        }

        val url = buildUrl(baseUrl)
        val systemPrompt = buildSystemPrompt(sourceLang, targetLang)

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("stream", false)
            put("max_tokens", 2048)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
        }.toString()

        var conn: HttpURLConnection? = null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
            }

            conn.outputStream.use { out ->
                out.write(bytes)
                out.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.let { readAll(it) }.orEmpty()
                return Result.Err("请求失败 ($code)${formatError(err)}", code)
            }

            val raw = conn.inputStream?.let { readAll(it) } ?: ""
            parseContent(raw)
        } catch (e: Exception) {
            Result.Err("网络错误: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * 测试连通性：发送一个极短的请求确认 Key / URL / 模型都可用。
     */
    fun testConnection(
        apiKey: String,
        baseUrl: String,
        model: String
    ): Result {
        if (apiKey.isBlank()) return Result.Err("API Key 为空")

        val url = buildUrl(baseUrl)
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 16)
            put("temperature", 0.0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with the single word: OK")
                })
            })
        }.toString()

        var conn: HttpURLConnection? = null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = 30_000
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes); it.flush() }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.let { readAll(it) }.orEmpty()
                return Result.Err("连接失败 ($code)${formatError(err)}", code)
            }
            when (val r = parseContent(conn.inputStream?.let { readAll(it) } ?: "")) {
                is Result.Ok -> Result.Ok(r.text, model)
                is Result.Err -> r
            }
        } catch (e: Exception) {
            Result.Err("连接异常: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    /** 拼接 chat/completions 端点，自动处理 baseUrl 是否已带 /v1。 */
    private fun buildUrl(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.isEmpty()) b = AppPrefs.DEFAULT_BASE_URL
        // 已经指向具体端点时直接用
        if (b.endsWith("/chat/completions")) return b
        return if (b.endsWith("/v1")) "$b/chat/completions" else "$b/v1/chat/completions"
    }

    /** 构造翻译用 system prompt。 */
    private fun buildSystemPrompt(sourceLang: String, targetLang: String): String {
        val from = if (sourceLang.isBlank() || sourceLang == "auto") "" else " from $sourceLang"
        return buildString {
            append("You are a professional translator. ")
            append("Translate the user's text$from to $targetLang. ")
            append("Output ONLY the translated text — no explanations, no quotes, no markdown, ")
            append("no pinyin, no original text. ")
            append("Preserve the original formatting: keep line breaks, numbers, emoji and URLs unchanged.")
        }
    }

    /** 解析 OpenAI 兼容响应里的 choices[0].message.content。 */
    private fun parseContent(raw: String): Result {
        if (raw.isBlank()) return Result.Err("服务端返回空响应")
        return try {
            val json = JSONObject(raw)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return Result.Err("响应中没有 choices 字段")
            }
            val msg = choices.getJSONObject(0).optJSONObject("message")
            var content = msg?.optString("content", "") ?: ""
            // 个别模型会把内容放在 reasoning_content
            if (content.isBlank()) content = msg?.optString("reasoning_content", "") ?: ""
            content = content.trim()
            if (content.isEmpty()) Result.Err("模型返回了空文本") else Result.Ok(content, json.optString("model", ""))
        } catch (e: Exception) {
            Result.Err("解析响应失败: ${e.message}")
        }
    }

    /** 把服务端的 error 字段提取成简短提示。 */
    private fun formatError(errBody: String): String {
        if (errBody.isBlank()) return ""
        return try {
            val msg = JSONObject(errBody).optJSONObject("error")?.optString("message", "")
            if (!msg.isNullOrBlank()) ": $msg" else ": ${errBody.take(180)}"
        } catch (e: Exception) {
            ": ${errBody.take(180)}"
        }
    }

    private fun readAll(input: InputStream): String {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            val sb = StringBuilder()
            var line = r.readLine()
            while (line != null) {
                sb.append(line)
                line = r.readLine()
            }
            return sb.toString()
        }
    }
}

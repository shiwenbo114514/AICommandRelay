package com.aicmd.relay.net

import com.aicmd.relay.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 发送给 API 的消息。 */
/** 一张以 base64 传递的图片（OpenAI 兼容 image_url data URL 格式）。 */
data class ApiImage(val mime: String, val base64: String)

data class ApiMessage(
    val role: String,
    val content: String?,
    val toolCalls: List<ApiToolCall>? = null,
    val toolCallId: String? = null,
    val reasoningContent: String? = null,
    val images: List<ApiImage>? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("role", role)
        if (images.isNullOrEmpty()) {
            o.put("content", content ?: "")
        } else {
            // 视觉模型：content 用数组（文本 + image_url data URL）
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", content ?: ""))
            images.forEach { img ->
                arr.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${img.mime};base64,${img.base64}"))
                )
            }
            o.put("content", arr)
        }
        toolCalls?.let { tcs ->
            val arr = JSONArray()
            tcs.forEach { tc ->
                arr.put(
                    JSONObject().put("id", tc.id)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", tc.name).put("arguments", tc.arguments))
                )
            }
            o.put("tool_calls", arr)
        }
        toolCallId?.let { o.put("tool_call_id", it) }
        // DeepSeek 思考模式要求：携带 tools 的对话必须完整回传 reasoning_content，否则 400
        reasoningContent?.let { o.put("reasoning_content", it) }
        return o
    }
}

data class ApiToolCall(val id: String, val name: String, val arguments: String)

/** 流式增量事件。 */
sealed class Delta {
    data class Content(val text: String) : Delta()
    data class Reasoning(val text: String) : Delta()
    data class ToolCallArgs(val index: Int, val id: String?, val name: String?, val chunk: String) : Delta()
    data class Finished(val finishReason: String) : Delta()
    data class Fail(val message: String) : Delta()
}

class ApiException(val statusCode: Int, val serverMessage: String) :
    Exception("HTTP $statusCode: ${serverMessage.ifBlank { "未知错误" }}")

class OpenAIClient(private val client: OkHttpClient = defaultClient()) {

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 流式长输出一般几分钟内完成；设 5 分钟硬超时，防止请求永久挂起拖住进程烧 CPU
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

        /** 判断错误是否属于"模型不支持工具调用"（覆盖常见网关的 400/422 与各种措辞）。 */
        fun isToolsUnsupported(e: Exception): Boolean {
            if (e !is ApiException) return false
            if (e.statusCode != 400 && e.statusCode != 422) return false
            val m = e.serverMessage.lowercase()
            return m.contains("tool") || m.contains("function") || m.contains("tools") ||
                m.contains("function calling") || m.contains("调用工具") || m.contains("不支持") ||
                m.contains("do not support") || m.contains("not support") || m.contains("unknown parameter") ||
                m.contains("unexpected parameter")
        }
    }

    private val toolSchema = JSONObject()
        .put("type", "function")
        .put(
            "function", JSONObject()
                .put("name", "run_shell")
                .put(
                    "description",
                    "在 Android 设备上执行一条 shell 命令并返回输出。用于读取系统状态、执行任务、安装或卸载应用等。危险命令（重启、关机、dd 写入、pm install 等）执行前会由 App 弹窗让用户确认。建议先执行只读命令确认状态再操作。"
                )
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put(
                                    "command", JSONObject()
                                        .put("type", "string")
                                        .put("description", "要执行的 shell 命令，例如：dumpsys battery、ls /sdcard、pm list packages")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
        )

    fun buildBody(settings: AppSettings, messages: List<ApiMessage>, useTools: Boolean): JSONObject {
        val o = JSONObject()
            .put("model", settings.model)
            .put("stream", settings.streamEnabled)
            .put(
                "messages", JSONArray().also { arr ->
                    messages.forEach { arr.put(it.toJson()) }
                }
            )
        if (useTools) {
            o.put("tools", JSONArray().put(toolSchema))
            o.put("tool_choice", "auto")
        }
        if (settings.thinkingStrength != "off" && supportsReasoningEffort(settings.model)) {
            o.put("reasoning_effort", settings.thinkingStrength)
        }
        return o
    }

    private fun supportsReasoningEffort(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("o1") || m.contains("o3") || m.contains("thinking") ||
            m.contains("qwq") || m.contains("glm-z1") || m.contains("kimi-k2-thinking")
    }

    private fun headers(settings: AppSettings) = "Bearer ${settings.apiKey.trim()}"

    /**
     * 调用 chat/completions。流式时逐段回调 onDelta，结束时回调 onDone。
     * 返回 finish_reason（可能为 null）。
     */
    suspend fun streamChat(
        settings: AppSettings,
        messages: List<ApiMessage>,
        useTools: Boolean,
        onDelta: (Delta) -> Unit,
        onDone: () -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val url = settings.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", headers(settings))
            .post(buildBody(settings, messages, useTools).toString().toRequestBody("application/json".toMediaType()))
            .build()
        var finish: String? = null
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw parseError(resp)
                val body = resp.body ?: throw IOException("空响应")
                if (settings.streamEnabled) {
                    val source = body.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val json = try { JSONObject(payload) } catch (e: Exception) { continue }
                        val choices = json.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) continue
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")
                        if (delta != null) {
                            if (delta.has("content") && !delta.isNull("content")) {
                                onDelta(Delta.Content(delta.getString("content")))
                            }
                            if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                onDelta(Delta.Reasoning(delta.getString("reasoning_content")))
                            }
                            val tcs = delta.optJSONArray("tool_calls")
                            if (tcs != null) {
                                for (i in 0 until tcs.length()) {
                                    val tc = tcs.getJSONObject(i)
                                    val idx = tc.optInt("index", 0)
                                    val id = if (tc.has("id") && !tc.isNull("id")) tc.getString("id") else null
                                    val fn = tc.optJSONObject("function")
                                    val name = if (fn != null && fn.has("name") && !fn.isNull("name")) fn.getString("name") else null
                                    val chunk = if (fn != null && fn.has("arguments") && !fn.isNull("arguments")) fn.getString("arguments") else ""
                                    onDelta(Delta.ToolCallArgs(idx, id, name, chunk))
                                }
                            }
                        }
                        val fr = choice.optString("finish_reason")
                        if (fr.isNotEmpty() && fr != "null") finish = fr
                    }
                } else {
                    val text = body.string()
                    val json = JSONObject(text)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val msg = choice?.optJSONObject("message")
                    if (msg != null) {
                        if (msg.has("content") && !msg.isNull("content")) {
                            onDelta(Delta.Content(msg.getString("content")))
                        }
                        if (msg.has("reasoning_content") && !msg.isNull("reasoning_content")) {
                            onDelta(Delta.Reasoning(msg.getString("reasoning_content")))
                        }
                        val tcs = msg.optJSONArray("tool_calls")
                        if (tcs != null) {
                            for (i in 0 until tcs.length()) {
                                val tc = tcs.getJSONObject(i)
                                val fn = tc.optJSONObject("function")
                                onDelta(
                                    Delta.ToolCallArgs(
                                        i,
                                        tc.optString("id"),
                                        fn?.optString("name"),
                                        fn?.optString("arguments") ?: ""
                                    )
                                )
                            }
                        }
                        finish = choice.optString("finish_reason")
                    }
                }
            }
            onDone()
        } catch (e: Exception) {
            onDelta(Delta.Fail(e.message ?: e.javaClass.simpleName))
            throw e
        }
        finish
    }

    private fun parseError(resp: Response): ApiException {
        val raw = try { resp.body?.string()?.take(600) } catch (e: Exception) { null } ?: ""
        val nice = try {
            val j = JSONObject(raw)
            val e = j.optJSONObject("error")
            e?.optString("message") ?: j.optString("message")
        } catch (e: Exception) {
            raw
        }
        return ApiException(resp.code, nice.ifBlank { "未知错误" })
    }

    /** 测试连接：请求 /models 并返回可用模型数量。 */
    suspend fun testConnection(settings: AppSettings): String = withContext(Dispatchers.IO) {
        val url = settings.baseUrl.trim().trimEnd('/') + "/models"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", headers(settings))
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw parseError(resp)
            val j = JSONObject(resp.body?.string() ?: "{}")
            val arr = j.optJSONArray("data")
            val count = arr?.length() ?: 0
            val first = if (count > 0) arr.getJSONObject(0).optString("id") else ""
            "连接成功，可用模型 $count 个${if (first.isNotBlank()) "，例如：$first" else ""}"
        }
    }

    /**
     * 非流式单轮调用（不带工具）：返回回复文本。
     * 用于会话标题生成等轻量任务；失败返回 null，由调用方兜底。
     */
    suspend fun chatOnce(settings: AppSettings, system: String, user: String): String? = withContext(Dispatchers.IO) {
        val url = settings.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val body = JSONObject()
            .put("model", settings.model)
            .put("stream", false)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", headers(settings))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw parseError(resp)
                val msg = JSONObject(resp.body?.string() ?: "{}")
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val c = msg?.optString("content")?.trim()
                c?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}

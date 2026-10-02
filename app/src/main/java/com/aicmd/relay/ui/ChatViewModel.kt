package com.aicmd.relay.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aicmd.relay.BuildConfig
import com.aicmd.relay.agent.FileAccessHelper
import com.aicmd.relay.agent.RiskDetector
import com.aicmd.relay.agent.ShellExecutor
import com.aicmd.relay.data.AppSettings
import com.aicmd.relay.data.Attachment
import com.aicmd.relay.data.AttachmentType
import com.aicmd.relay.data.ChatMessage
import com.aicmd.relay.data.MsgStatus
import com.aicmd.relay.data.ReasoningSegment
import com.aicmd.relay.data.Role
import com.aicmd.relay.data.SessionMeta
import com.aicmd.relay.data.SessionStore
import com.aicmd.relay.data.SettingsRepo
import com.aicmd.relay.data.ToolCall
import com.aicmd.relay.data.ToolCallState
import com.aicmd.relay.data.ToolResult
import com.aicmd.relay.net.ApiException
import com.aicmd.relay.net.ApiImage
import com.aicmd.relay.net.ApiMessage
import com.aicmd.relay.net.ApiToolCall
import com.aicmd.relay.net.Delta
import com.aicmd.relay.net.OpenAIClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepo(app)
    private val client = OpenAIClient()

    var settings by mutableStateOf(AppSettings())
        private set

    val messages = mutableStateListOf<ChatMessage>()

    var busy by mutableStateOf(false)
        private set

    /** 当前等待用户确认的高危指令弹窗。 */
    data class RiskPrompt(val command: String, val reason: String)
    var pendingRisk by mutableStateOf<RiskPrompt?>(null)
        private set

    var toast by mutableStateOf<String?>(null)
        private set

    /** AI 执行命令被系统拒绝访问文件时置 true，UI 弹出"所有文件访问"授权引导。 */
    var askStorageAccess by mutableStateOf(false)
        private set

    fun consumeStorageRequest() {
        askStorageAccess = false
    }

    /** 当前是否已获得所有文件访问权限（设置页展示用）。 */
    fun storageAccessGranted(): Boolean = FileAccessHelper.isGranted()

    private var runJob: Job? = null
    private val riskChannel = Channel<Boolean>(Channel.BUFFERED)

    // ---------- 历史会话 ----------
    private val sessionStore by lazy { SessionStore(getApplication()) }

    var currentSessionId by mutableStateOf("")
        private set
    var currentTitle by mutableStateOf("")
        private set
    var sessions by mutableStateOf<List<SessionMeta>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            repo.settings.collect { settings = it }
        }
        // 恢复最近一次会话；无历史则开新会话
        val meta = sessionStore.list().firstOrNull()
        val loaded = meta?.let { sessionStore.load(it.id) }
        if (loaded != null && loaded.isNotEmpty()) {
            messages.addAll(loaded)
            currentSessionId = meta.id
            currentTitle = meta.title
        } else {
            currentSessionId = UUID.randomUUID().toString()
        }
        refreshSessions()
    }

    private fun refreshSessions() {
        sessions = sessionStore.list()
    }

    private var lastSaveAt = 0L

    /** 流式过程中节流保存，避免每 token 写盘。 */
    private fun maybeSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveAt > 1200) {
            lastSaveAt = now
            saveCurrent()
        }
    }

    private fun saveCurrent() {
        if (messages.isEmpty()) return
        sessionStore.save(messages.toList(), currentSessionId, currentTitle)
        refreshSessions()
    }

    /** 新建会话（当前会话先保存）。 */
    fun newSession() {
        if (messages.isNotEmpty()) saveCurrent()
        runJob?.cancel()
        messages.clear()
        currentSessionId = UUID.randomUUID().toString()
        currentTitle = ""
        refreshSessions()
    }

    /** 切换到指定历史会话。 */
    fun switchSession(id: String) {
        if (id == currentSessionId) return
        if (messages.isNotEmpty()) saveCurrent()
        runJob?.cancel()
        val loaded = sessionStore.load(id)
        messages.clear()
        if (loaded != null) messages.addAll(loaded)
        currentSessionId = id
        currentTitle = sessionStore.list().firstOrNull { it.id == id }?.title ?: sessionStore.titleOf(messages)
        refreshSessions()
    }

    /** 删除指定会话；删除的是当前会话则开新会话。 */
    fun deleteSession(id: String) {
        sessionStore.delete(id)
        if (id == currentSessionId) {
            runJob?.cancel()
            messages.clear()
            currentSessionId = UUID.randomUUID().toString()
            currentTitle = ""
        }
        refreshSessions()
    }

    /**
     * 让当前接入的 AI 自己概括会话标题：
     * 给它看首条用户提问和 AI 的回答，让它用一句话概括对话主题。
     * 失败时保留兜底标题（首条用户消息截取），不影响使用。
     */
    private fun generateTitle() {
        val firstUser = messages.firstOrNull { it.role == Role.USER }?.content?.trim().orEmpty()
        if (firstUser.isBlank()) return
        val lastReply = messages.lastOrNull()?.content?.trim().orEmpty()
        viewModelScope.launch {
            runCatching {
                client.chatOnce(
                    settings,
                    system = "你是会话标题生成器。用不超过12个汉字概括这段对话的主题，直接输出标题本身，不要引号、不要解释、不要任何多余文字。",
                    user = "用户问：${firstUser.take(200)}\nAI回答：${lastReply.take(300)}",
                )
            }.getOrNull()
                ?.replace("\n", " ")
                ?.trim()
                ?.take(24)
                ?.takeIf { it.isNotBlank() }
                ?.let { t ->
                    currentTitle = t
                    saveCurrent()
                }
        }
    }

    // ---------- 对外操作 ----------

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val t = text.trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        if (busy) return
        // 图片必须有视觉能力才允许发送
        if (attachments.any { it.type == AttachmentType.IMAGE } && !supportsVision()) {
            toast = "当前模型不支持图片识别，无法发送图片。可在设置里切换支持视觉的模型，或手动开启图片识别。"
            return
        }
        messages += ChatMessage(UUID.randomUUID().toString(), Role.USER).apply {
            content = t
            this.attachments += attachments
        }
        busy = true
        runJob = viewModelScope.launch {
            val asst = ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT)
            asst.status = MsgStatus.THINKING
            messages += asst
            var useTools = true
            var stop = false
            var streamOn = settings.streamEnabled   // 流式失败自动降级为一次性返回
            try {
                var iter = 0
                while (!stop && iter < settings.maxIterations) {
                    iter++
                    asst.iterations = iter
                    try {
                        runRound(asst, useTools, streamOn)
                    } catch (e: ApiException) {
                        if (useTools && OpenAIClient.isToolsUnsupported(e)) {
                            useTools = false
                            iter--   // 同一轮用无工具方式重试
                            continue
                        }
                        if (streamOn && e.statusCode == 400) {
                            // 部分网关对流式/特定参数兼容差：降级为一次性返回重试同一轮
                            streamOn = false
                            iter--
                            continue
                        }
                        throw e
                    } catch (e: java.io.IOException) {
                        if (streamOn) {
                            streamOn = false
                            iter--
                            continue
                        }
                        throw e
                    } finally {
                        asst.finishReasoningRound()
                    }

                    if (useTools) {
                        val pending = asst.toolCalls.filter { it.state == ToolCallState.PENDING }
                        if (pending.isEmpty()) {
                            // 模型没有要求执行命令 → 本轮就是最终回答，结束
                            stop = true
                            continue
                        }
                        if (iter >= settings.maxIterations) {
                            asst.content += "\n\n（已到达最大连续调用次数 ${settings.maxIterations}，停止继续执行）"
                            pending.forEach { it.state = ToolCallState.CANCELLED }
                            stop = true
                            continue
                        }
                        if (asst.contentBeforeExec.isBlank()) asst.contentBeforeExec = asst.content
                        asst.status = MsgStatus.EXECUTING
                        for (tc in pending) {
                            executeToolCall(asst, tc)
                        }
                        // 执行完命令：不置 stop，下一轮必须再调用一次模型，让它根据结果继续输出总结
                    } else {
                        // 代码块模式：解析 ```bash 块，执行新增的块，把结果回传
                        val blocks = extractBashBlocks(asst.content)
                        val executedCount = asst.toolResults.count { it.toolCallId.startsWith("block_") }
                        val newBlocks = blocks.drop(executedCount)
                        if (newBlocks.isEmpty()) {
                            // 本轮没有新命令块 → 模型已经完成回答，结束
                            stop = true
                            continue
                        }
                        if (iter >= settings.maxIterations) {
                            asst.content += "\n\n（已到达最大连续调用次数 ${settings.maxIterations}，停止继续执行）"
                            stop = true
                            continue
                        }
                        if (asst.contentBeforeExec.isBlank()) asst.contentBeforeExec = asst.content
                        asst.status = MsgStatus.EXECUTING
                        for ((offset, cmd) in newBlocks.withIndex()) {
                            executeBlock(asst, executedCount + offset, cmd)
                        }
                        // 执行完命令：不置 stop，下一轮必须再调用一次模型，让它根据结果继续输出总结
                    }
                }

                // 兜底保险：只要执行过命令、且模型最终没吐出任何正文总结，就强制再问一次
                if (asst.toolResults.isNotEmpty() && asst.content.length <= asst.contentBeforeExec.length) {
                    runCatching { runRound(asst, useTools = false, stream = streamOn) }
                }
                asst.status = MsgStatus.DONE
            } catch (e: CancellationException) {
                asst.status = MsgStatus.STOPPED
            } catch (e: Exception) {
                asst.status = MsgStatus.ERROR
                asst.content += "\n\n[错误] ${e.message ?: e.javaClass.simpleName}"
                // 若工具模式因参数问题失败，提示用户可在设置关闭流式再试
            } finally {
                busy = false
                pendingRisk = null
                // 对话结束：保存会话，并让当前 AI 概括标题
                if (messages.isNotEmpty()) {
                    saveCurrent()
                    generateTitle()
                }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
    }

    fun answerRisk(allow: Boolean) {
        pendingRisk?.let { riskChannel.trySend(allow) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            repo.update(transform)
            ShellExecutor.resetRootCache()
        }
    }

    fun resetSystemPrompt() {
        updateSettings { it.copy(systemPrompt = com.aicmd.relay.data.DEFAULT_SYSTEM_PROMPT) }
    }

    fun testConnection() {
        viewModelScope.launch {
            toast = null
            try {
                toast = client.testConnection(settings)
            } catch (e: Exception) {
                toast = "连接失败：${e.message}"
            }
        }
    }

    fun appVersion(): String = BuildConfig.VERSION_NAME

    /** 当前模型是否支持图片识别：设置强制 on/off，或按模型名自动检测。 */
    fun supportsVision(): Boolean = when (settings.visionMode) {
        "on" -> true
        "off" -> false
        else -> modelSupportsVision(settings.model)
    }

    companion object {
        /** 常见视觉模型名关键词。命中即认为该模型可以看图。 */
        private val VISION_KEYWORDS = listOf(
            "vision", "-vl", "_vl", "vlm", "4o", "omni", "gemini", "claude",
            "llava", "internvl", "minicpm", "glm-4v", "qwen2.5-vl", "qwen-vl",
            "gpt-4-turbo", "gpt-5", "o4-mini", "qwen-vl-max",
        )

        fun modelSupportsVision(model: String): Boolean {
            val m = model.lowercase()
            return VISION_KEYWORDS.any { m.contains(it) }
        }
    }

    fun clearToast() {
        toast = null
    }

    /** UI 内部提示（如"已复制"）。 */
    fun notify(msg: String) {
        toast = msg
    }

    // ---------- 内部：单轮对话 ----------

    private suspend fun runRound(asst: ChatMessage, useTools: Boolean, stream: Boolean = settings.streamEnabled) {
        asst.nextRound()
        val apiMessages = buildApiMessages()
        client.streamChat(
            settings = if (stream == settings.streamEnabled) settings else settings.copy(streamEnabled = stream),
            messages = apiMessages,
            useTools = useTools,
            onDelta = { d -> handleDelta(asst, d) },
            onDone = {},
        )
    }

    private fun handleDelta(asst: ChatMessage, d: Delta) {
        when (d) {
            is Delta.Content -> {
                asst.content += d.text
                asst.textSegments.lastOrNull()?.text += d.text
                maybeSave()
                if (asst.status == MsgStatus.THINKING || asst.status == MsgStatus.EXECUTING) {
                    asst.status = MsgStatus.STREAMING
                }
            }
            is Delta.Reasoning -> {
                var seg = asst.reasoning.lastOrNull()
                if (seg == null || seg.sealed) {
                    seg = ReasoningSegment(round = asst.round)
                    asst.reasoning += seg
                }
                seg.open = true
                seg.text += d.text
            }
            is Delta.ToolCallArgs -> {
                // 并行工具调用可能共用 index，必须优先用 id 区分；无 id 的增量 chunk 归属最近活跃的工具
                val id = d.id
                val tc = if (!id.isNullOrEmpty()) {
                    asst.toolCalls.firstOrNull { it.apiId == id }
                        ?: ToolCall(key = "id_$id", apiId = id, round = asst.round).also { asst.toolCalls += it }
                } else {
                    asst.toolCalls.lastOrNull()
                        ?: ToolCall(key = "k${d.index}", round = asst.round).also { asst.toolCalls += it }
                }
                d.name?.let { tc.name = it }
                // 去重累积：某些网关/代理会重复发送已累积的 arguments（或整段重发）
                val prev = tc.args
                tc.args = when {
                    d.chunk == prev -> prev
                    d.chunk.startsWith(prev) -> prev + d.chunk.substring(prev.length)
                    prev.length > d.chunk.length && prev.endsWith(d.chunk) -> prev
                    else -> prev + d.chunk
                }
            }
            else -> {}
        }
    }

    /** 封口当前轮思考段：下一轮新思考会另起一段。 */
    private fun ChatMessage.finishReasoningRound() {
        reasoning.lastOrNull()?.let { it.sealed = true }
    }

    /** 从工具参数 JSON 里提取 command；JSON 损坏时用正则兜底。 */
    private fun parseCommandArg(args: String): String {
        if (args.isBlank()) return ""
        return runCatching { JSONObject(args).optString("command").trim() }
            .getOrElse {
                Regex("\"command\"\\s*:\\s*\"([^\"]*)\"").find(args)?.groupValues?.get(1)?.trim() ?: ""
            }
    }

    private suspend fun executeToolCall(asst: ChatMessage, tc: ToolCall) {
        tc.state = ToolCallState.RUNNING
        tc.apiId = tc.apiId.ifBlank { "call_${tc.key}" }
        val cmd = parseCommandArg(tc.args)
        if (cmd.isEmpty()) {
            tc.state = ToolCallState.FAILED
            asst.toolResults += ToolResult(
                toolCallId = tc.apiId,
                command = tc.name,
                exitCode = -1,
                stdout = "",
                stderr = "参数解析失败：${tc.args.take(300)}",
                durationMs = 0,
                usedRoot = false,
                round = asst.round,
            )
            return
        }
        val risk = RiskDetector.check(cmd)
        if (risk != null && settings.highRiskConfirm) {
            pendingRisk = RiskPrompt(cmd, risk)
            val allow = riskChannel.receive()
            pendingRisk = null
            if (!allow) {
                tc.state = ToolCallState.CANCELLED
                asst.toolResults += ToolResult(tc.apiId, cmd, -1, "", "", 0, false, cancelled = true, round = asst.round)
                return
            }
        }
        val res = ShellExecutor.execute(getApplication(), cmd, settings.rootMode)
        tc.state = if (res.exitCode == 0) ToolCallState.DONE else ToolCallState.FAILED
        maybeAskFileAccess(cmd, res.stderr)
        asst.toolResults += ToolResult(
            toolCallId = tc.apiId,
            command = cmd,
            exitCode = res.exitCode,
            stdout = res.stdout,
            stderr = res.stderr,
            durationMs = res.durationMs,
            usedRoot = res.usedRoot,
            round = asst.round,
        )
        maybeSave()
    }

    /** AI 主动申请"所有文件访问"权限：命令访问 /sdcard 被拒（Permission denied）且未授权时，弹窗引导去系统设置授权。 */
    private fun maybeAskFileAccess(cmd: String, stderr: String) {
        if (askStorageAccess) return
        if (!FileAccessHelper.isGranted() &&
            FileAccessHelper.touchesSdcard(cmd) &&
            (stderr.contains("Permission denied") || stderr.contains("permission denied") || stderr.contains("EACCES"))
        ) {
            askStorageAccess = true
        }
    }

    private suspend fun executeBlock(asst: ChatMessage, ordinal: Int, cmd: String) {
        val risk = RiskDetector.check(cmd)
        if (risk != null && settings.highRiskConfirm) {
            pendingRisk = RiskPrompt(cmd, risk)
            val allow = riskChannel.receive()
            pendingRisk = null
            if (!allow) {
                asst.toolResults += ToolResult("block_$ordinal", cmd, -1, "", "", 0, false, cancelled = true, round = asst.round)
                return
            }
        }
        val res = ShellExecutor.execute(getApplication(), cmd, settings.rootMode)
        maybeAskFileAccess(cmd, res.stderr)
        asst.toolResults += ToolResult(
            toolCallId = "block_$ordinal",
            command = cmd,
            exitCode = res.exitCode,
            stdout = res.stdout,
            stderr = res.stderr,
            durationMs = res.durationMs,
            usedRoot = res.usedRoot,
            round = asst.round,
        )
        maybeSave()
    }

    // ---------- 内部：历史消息组装 ----------

    private fun buildApiMessages(): List<ApiMessage> {
        val list = mutableListOf<ApiMessage>()
        list += ApiMessage("system", settings.systemPrompt)

        val all = messages.toList()
        var start = (all.size - 40).coerceAtLeast(0)
        // 避免从"带工具调用/结果的助手消息"中间截断，导致工具结果缺失
        while (start < all.size && all[start].role == Role.ASSISTANT &&
            (all[start].toolCalls.isNotEmpty() || all[start].toolResults.isNotEmpty())
        ) {
            start++
        }
        for (m in all.drop(start)) {
            when (m.role) {
                Role.USER -> {
                    if (m.content.isNotBlank() || m.attachments.isNotEmpty()) {
                        val sb = StringBuilder(m.content)
                        val images = mutableListOf<ApiImage>()
                        val vision = supportsVision()
                        m.attachments.forEach { a ->
                            when (a.type) {
                                AttachmentType.IMAGE -> {
                                    if (vision) {
                                        val b64 = a.base64 ?: runCatching {
                                            Base64.getEncoder().encodeToString(File(a.localPath).readBytes())
                                        }.getOrNull()
                                        if (b64 != null) {
                                            a.base64 = b64
                                            images += ApiImage(a.mime.ifBlank { "image/png" }, b64)
                                        } else {
                                            sb.append("\n\n[用户上传了图片 ${a.name}，但读取失败]")
                                        }
                                    } else {
                                        sb.append(
                                            "\n\n[用户上传了图片：${a.name}，路径 ${a.localPath}。当前模型不支持图片识别，看不到画面；" +
                                                "若需要看图请换用支持视觉的模型，或在设置里开启图片识别。]"
                                        )
                                    }
                                }
                                AttachmentType.TEXT -> {
                                    // 文本也一律当文件处理：只给路径，让 AI 用 shell 读取，不内联全文，省 token 省上下文
                                    sb.append(
                                        "\n\n[用户上传了文本文件 ${a.name}（${a.size} 字节），保存在：${a.localPath}。" +
                                            "请用 shell 命令读取分析（如 head、tail、cat、grep、awk、wc），不要臆测内容，再回答。]"
                                    )
                                }
                                AttachmentType.BINARY -> {
                                    sb.append(
                                        "\n\n[用户上传了文件 ${a.name}（${a.size} 字节），保存在：${a.localPath}。" +
                                            "你可以用 shell 命令读取/处理它（如 file、cat、strings、tar、pm install 等），并根据结果回答。]"
                                    )
                                }
                            }
                        }
                        if (m.content.isNotBlank() || sb.isNotBlank()) {
                            list += ApiMessage("user", sb.toString().trim(), images = images.takeIf { it.isNotEmpty() })
                        }
                    }
                }
                Role.ASSISTANT -> {
                    // DeepSeek 思考模式要求完整回传 reasoning_content，否则后续请求 400
                    val reasoningText = m.reasoning.joinToString("") { it.text }.ifBlank { null }
                    val tcs = m.toolCalls.filter { it.state != ToolCallState.PENDING }
                    if (tcs.isNotEmpty()) {
                        // 工具模式：真实 tool_calls + tool 结果
                        list += ApiMessage(
                            role = "assistant",
                            content = m.content,
                            toolCalls = tcs.map {
                                ApiToolCall(it.apiId, it.name.ifBlank { "run_shell" }, it.args.ifBlank { "{}" })
                            },
                            reasoningContent = reasoningText,
                        )
                        for (tr in m.toolResults) {
                            list += ApiMessage("tool", toolResultPayload(tr), toolCallId = tr.toolCallId)
                        }
                    } else if (m.toolResults.isNotEmpty()) {
                        // 代码块模式：执行结果用普通用户消息回传（对不支持函数调用的模型最稳）
                        list += ApiMessage("assistant", m.content, reasoningContent = reasoningText)
                        val sb = StringBuilder("以下是上一条消息中命令的执行结果，请基于这些结果继续回答/总结：\n")
                        m.toolResults.forEachIndexed { i, tr ->
                            sb.append("【执行 #${i + 1}】$ ${tr.command}\n")
                            sb.append("退出码: ${tr.exitCode}，耗时: ${tr.durationMs}ms\n")
                            if (tr.cancelled) sb.append("用户取消了执行\n")
                            if (tr.stdout.isNotBlank()) sb.append("标准输出:\n${tr.stdout.take(4000)}\n")
                            if (tr.stderr.isNotBlank()) sb.append("错误输出:\n${tr.stderr.take(1500)}\n")
                            sb.append("\n")
                        }
                        list += ApiMessage("user", sb.toString().trim())
                    } else if (m.content.isNotBlank()) {
                        list += ApiMessage("assistant", m.content, reasoningContent = reasoningText)
                    }
                    // 空助手消息（刚创建、流式未开始）直接跳过
                }
            }
        }
        return list
    }

    private fun toolResultPayload(tr: com.aicmd.relay.data.ToolResult): String = JSONObject()
        .put("exit_code", tr.exitCode)
        .put("stdout", tr.stdout.take(8000))
        .put("stderr", tr.stderr.take(2000))
        .put("cancelled", tr.cancelled)
        .toString()

    private fun extractBashBlocks(text: String): List<String> {
        val re = Regex("```(?:bash|sh|shell)?\\s*\\n?([\\s\\S]*?)```")
        return re.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }
}

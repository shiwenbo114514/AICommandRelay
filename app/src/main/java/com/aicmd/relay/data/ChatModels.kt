package com.aicmd.relay.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Role { USER, ASSISTANT }

enum class MsgStatus { THINKING, STREAMING, EXECUTING, DONE, ERROR, STOPPED }

enum class ToolCallState { PENDING, RUNNING, DONE, CANCELLED, FAILED }

/** 附件类型。图片走视觉通道；文本直接读内容；其余交给 AI 用 shell 处理。 */
enum class AttachmentType { IMAGE, TEXT, BINARY }

/** 用户上传的附件。localPath 是复制到 App 私有目录后的绝对路径，AI 可通过 shell 读取。 */
class Attachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val type: AttachmentType,
    val localPath: String,
) {
    /** 图片 base64（首次生成后复用，避免每轮重复读文件）。 */
    var base64: String? = null

    /** 文本内容缓存（首次读取后复用）。 */
    var textContent: String? = null
}

/** 一次工具调用。key 用于 UI 内稳定标识，apiId 是传给 API 的 tool_call_id，round 标记它属于第几轮 AI 调用。 */
class ToolCall(
    val key: String,
    var apiId: String = "",
    var name: String = "",
    var args: String = "",
    var state: ToolCallState = ToolCallState.PENDING,
    val round: Int = 0,
)

/** 一段独立的思考过程（对应一次 AI 调用的思考部分），可单独折叠。round 标记所属轮次。 */
class ReasoningSegment(val round: Int = 0) {
    var text by mutableStateOf("")
    var open by mutableStateOf(true)
    var sealed by mutableStateOf(false)
}

/** 一轮 AI 调用的正文输出。round 渲染时与思考段、执行框交错排列。 */
class TextSegment {
    var text by mutableStateOf("")
}

/** 一次命令执行的结果。toolCallId 关联到 ToolCall；块模式下为 "block_序号"。 */
data class ToolResult(
    val toolCallId: String,
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val usedRoot: Boolean,
    val cancelled: Boolean = false,
    val round: Int = 0,
)

/**
 * 一条聊天消息。字段全部可观察，流式更新时只触发局部重组。
 */
class ChatMessage(
    val id: String,
    val role: Role,
) {
    var content by mutableStateOf("")
    var thinkingOpen by mutableStateOf(false)
    var status by mutableStateOf(MsgStatus.THINKING)
    var iterations by mutableStateOf(1)

    /** 当前 AI 调用轮次（1 起）。每轮 runRound 前 +1。 */
    var round by mutableStateOf(0)

    /** 按 AI 调用轮次分段的思考过程，每轮一段，可分别折叠。 */
    val reasoning = mutableStateListOf<ReasoningSegment>()

    /** 每轮调用的正文输出（按轮次分段），用于与思考段、执行框按时间线交错渲染。 */
    val textSegments = mutableStateListOf<TextSegment>()

    /** 用户消息携带的附件（图片 / 文本 / 二进制文件）。 */
    val attachments = mutableStateListOf<Attachment>()

    /** 首次执行命令前的正文快照，用于引擎判断"模型是否没有继续输出总结"。 */
    var contentBeforeExec by mutableStateOf("")
    val toolCalls = mutableStateListOf<ToolCall>()
    val toolResults = mutableStateListOf<ToolResult>()

    /** 进入新一轮 AI 调用：轮次 +1 并准备新一轮正文段。 */
    fun nextRound() {
        round++
        textSegments += TextSegment()
    }
}

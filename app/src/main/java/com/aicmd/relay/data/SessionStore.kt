package com.aicmd.relay.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 会话列表中的一条元信息。 */
data class SessionMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * 历史会话持久化：每个会话一个 JSON 文件（App 私有目录 sessions/ 下）。
 * 序列化/反序列化 ChatMessage 的完整字段，切换会话后可原样恢复。
 */
class SessionStore(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun list(): List<SessionMeta> {
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                SessionMeta(
                    id = o.optString("id"),
                    title = o.optString("title", "未命名对话"),
                    updatedAt = f.lastModified(),
                    messageCount = o.optJSONArray("messages")?.length() ?: 0,
                )
            }.getOrNull()
        }
    }

    fun save(messages: List<ChatMessage>, sessionId: String, title: String) {
        if (sessionId.isBlank()) return
        val arr = JSONArray()
        messages.forEach { arr.put(serialize(it)) }
        val o = JSONObject()
            .put("id", sessionId)
            .put("title", title.ifBlank { "未命名对话" })
            .put("savedAt", System.currentTimeMillis())
            .put("messages", arr)
        val f = File(dir, "$sessionId.json")
        runCatching { f.writeText(o.toString()) }
    }

    fun load(sessionId: String): List<ChatMessage>? {
        val f = File(dir, "$sessionId.json")
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("messages") ?: return null
            (0 until arr.length()).map { deserialize(arr.getJSONObject(it)) }
        }.getOrNull()
    }

    fun delete(sessionId: String) {
        File(dir, "$sessionId.json").delete()
    }

    /** 兜底标题：首条用户消息前 20 字（AI 生成标题失败时使用）。 */
    fun titleOf(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == Role.USER }?.content?.trim().orEmpty()
        return first.replace("\n", " ").take(20).ifBlank { "未命名对话" }
    }

    private fun serialize(m: ChatMessage): JSONObject {
        val o = JSONObject()
        o.put("id", m.id)
        o.put("role", m.role.name)
        o.put("content", m.content)
        o.put("status", m.status.name)
        o.put("iterations", m.iterations)
        o.put("round", m.round)
        o.put("contentBeforeExec", m.contentBeforeExec)

        val rs = JSONArray()
        m.reasoning.forEach { r ->
            rs.put(
                JSONObject()
                    .put("text", r.text)
                    .put("open", r.open)
                    .put("sealed", r.sealed)
                    .put("round", r.round)
            )
        }
        o.put("reasoning", rs)

        val ts = JSONArray()
        m.textSegments.forEach { t -> ts.put(JSONObject().put("text", t.text)) }
        o.put("textSegments", ts)

        val asArr = JSONArray()
        m.attachments.forEach { a ->
            asArr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("mime", a.mime)
                    .put("size", a.size)
                    .put("type", a.type.name)
                    .put("localPath", a.localPath)
            )
        }
        o.put("attachments", asArr)

        val trs = JSONArray()
        m.toolResults.forEach { r ->
            trs.put(
                JSONObject()
                    .put("toolCallId", r.toolCallId)
                    .put("command", r.command)
                    .put("exitCode", r.exitCode)
                    .put("stdout", r.stdout)
                    .put("stderr", r.stderr)
                    .put("durationMs", r.durationMs)
                    .put("usedRoot", r.usedRoot)
                    .put("cancelled", r.cancelled)
                    .put("round", r.round)
            )
        }
        o.put("toolResults", trs)

        val tcs = JSONArray()
        m.toolCalls.forEach { t ->
            tcs.put(
                JSONObject()
                    .put("key", t.key)
                    .put("apiId", t.apiId)
                    .put("name", t.name)
                    .put("args", t.args)
                    .put("state", t.state.name)
                    .put("round", t.round)
            )
        }
        o.put("toolCalls", tcs)
        return o
    }

    private fun deserialize(o: JSONObject): ChatMessage {
        val m = ChatMessage(o.optString("id"), runCatching { Role.valueOf(o.optString("role")) }.getOrDefault(Role.USER))
        m.content = o.optString("content")
        m.status = runCatching { MsgStatus.valueOf(o.optString("status")) }.getOrDefault(MsgStatus.DONE)
        m.iterations = o.optInt("iterations", 1)
        m.round = o.optInt("round", 0)
        m.contentBeforeExec = o.optString("contentBeforeExec")

        o.optJSONArray("reasoning")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                val r = arr.getJSONObject(i)
                val seg = ReasoningSegment(r.optInt("round"))
                seg.text = r.optString("text")
                seg.open = r.optBoolean("open", true)
                seg.sealed = r.optBoolean("sealed", false)
                m.reasoning += seg
            }
        }
        o.optJSONArray("textSegments")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                m.textSegments += TextSegment().apply { text = arr.getJSONObject(i).optString("text") }
            }
        }
        o.optJSONArray("attachments")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                val a = arr.getJSONObject(i)
                m.attachments += Attachment(
                    a.optString("id"),
                    a.optString("name"),
                    a.optString("mime"),
                    a.optLong("size"),
                    runCatching { AttachmentType.valueOf(a.optString("type")) }.getOrDefault(AttachmentType.BINARY),
                    a.optString("localPath"),
                )
            }
        }
        o.optJSONArray("toolResults")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                val r = arr.getJSONObject(i)
                m.toolResults += ToolResult(
                    r.optString("toolCallId"),
                    r.optString("command"),
                    r.optInt("exitCode"),
                    r.optString("stdout"),
                    r.optString("stderr"),
                    r.optLong("durationMs"),
                    r.optBoolean("usedRoot"),
                    r.optBoolean("cancelled"),
                    r.optInt("round"),
                )
            }
        }
        o.optJSONArray("toolCalls")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                val t = arr.getJSONObject(i)
                m.toolCalls += ToolCall(
                    t.optString("key"),
                    t.optString("apiId"),
                    t.optString("name"),
                    t.optString("args"),
                    runCatching { ToolCallState.valueOf(t.optString("state")) }.getOrDefault(ToolCallState.DONE),
                    t.optInt("round"),
                )
            }
        }
        return m
    }
}

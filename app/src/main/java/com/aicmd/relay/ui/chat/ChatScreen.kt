package com.aicmd.relay.ui.chat

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicmd.relay.agent.FileAccessHelper
import com.aicmd.relay.data.Attachment
import com.aicmd.relay.data.AttachmentType
import com.aicmd.relay.data.ChatMessage
import com.aicmd.relay.data.MsgStatus
import com.aicmd.relay.data.ReasoningSegment
import com.aicmd.relay.data.Role
import com.aicmd.relay.data.SessionMeta
import com.aicmd.relay.data.ToolResult
import com.aicmd.relay.ui.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private val CodeBg = Color(0xFF0D1219)
private val Success = Color(0xFF34D399)
private val ErrorC = Color(0xFFF87171)

// ---------- 内容分段 ----------

private sealed class Seg {
    data class Text(val s: String) : Seg()
    data class Code(val lang: String, val body: String) : Seg()
}

private fun parseSegments(text: String): List<Seg> {
    val result = mutableListOf<Seg>()
    val fence = Regex("```(\\w*)\\s*\\n?")
    val opens = fence.findAll(text).toList()
    var last = 0
    var idx = 0
    while (idx < opens.size) {
        val open = opens[idx]
        if (open.range.first > last) result += Seg.Text(text.substring(last, open.range.first))
        val closeRel = text.indexOf("```", open.range.last + 1)
        if (closeRel < 0) {
            result += Seg.Text(text.substring(open.range.first))
            last = text.length
            break
        }
        val lang = open.groupValues[1]
        val body = text.substring(open.range.last + 1, closeRel).trim('\n')
        result += Seg.Code(lang, body)
        last = closeRel + 3
        while (idx < opens.size && opens[idx].range.first < closeRel + 3) idx++
    }
    if (last < text.length) result += Seg.Text(text.substring(last))
    return result
}

/** 简易行内排版：**加粗**、`行内代码` */
private fun annotated(s: String, codeBg: Color, codeFg: Color): AnnotatedString {
    val out = AnnotatedString.Builder()
    val re = Regex("""(\*\*[^*]+\*\*|`[^`]+`)""")
    var last = 0
    for (m in re.findAll(s)) {
        out.append(s.substring(last, m.range.first))
        val tok = m.value
        if (tok.startsWith("**")) {
            out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tok.drop(2).dropLast(2)) }
        } else {
            out.withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBg,
                    color = codeFg
                )
            ) { append(tok.drop(1).dropLast(1)) }
        }
        last = m.range.last + 1
    }
    out.append(s.substring(last))
    return out.toAnnotatedString()
}

// ---------- 主界面 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val listState = rememberLazyListState()
    val lastMsg = vm.messages.lastOrNull()
    var selectedResult by remember { mutableStateOf<ToolResult?>(null) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // 是否停在底部：决定"内容输出时是否自动跟随"以及"回到最新"箭头是否显示
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount
        }.collect { (lastVisible, total) ->
            atBottom = lastVisible >= total - 1
        }
    }

    // 跟随策略：用户在底部 → 新消息出现 / 流式内容持续输出都自动滚到底，始终看到最新文本；
    // 用户往上滑走后 → 完全自由滑动，任何输出都不打扰，只有右下角"回到最新"箭头。
    LaunchedEffect(vm.messages) {
        snapshotFlow {
            vm.messages.size to vm.messages.sumOf { m ->
                m.content.length + m.reasoning.sumOf { it.text.length } +
                    m.textSegments.sumOf { it.text.length } + m.toolResults.size
            }
        }.collect { _ ->
            if (atBottom && vm.messages.isNotEmpty()) {
                listState.scrollToItem(vm.messages.lastIndex)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                vm = vm,
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("AI指令中转站", fontWeight = FontWeight.SemiBold)
                            }
                            if (vm.currentTitle.isNotBlank()) {
                                Text(
                                    vm.currentTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "历史会话", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = { InputBar(vm) },
        ) { padding ->
            if (vm.messages.isEmpty()) {
                EmptyState(Modifier.fillMaxSize().padding(padding))
            } else {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(vm.messages, key = { it.id }) { msg ->
                            MessageItem(vm, msg) { selectedResult = it }
                        }
                    }
                    // 回到最新：滑到上面阅读时右下角出现箭头，点一下才跳回最新内容
                    if (!atBottom) {
                        FilledTonalIconButton(
                            onClick = { scope.launch { listState.animateScrollToItem(vm.messages.lastIndex) } },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 14.dp),
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "回到最新",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    vm.pendingRisk?.let { risk ->
        RiskDialog(
            risk = risk,
            onConfirm = { vm.answerRisk(true) },
            onCancel = { vm.answerRisk(false) },
        )
    }

    // AI 主动申请"所有文件访问"权限：执行 /sdcard 命令被拒时弹窗引导去系统设置授权
    if (vm.askStorageAccess) {
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { vm.consumeStorageRequest() },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("AI 需要访问所有文件") },
            text = {
                Text(
                    "AI 在读取 /sdcard 文件时被系统拒绝（Permission denied）。\n\n" +
                        "授予「所有文件访问」权限后，AI 才能读取 SD 卡下任意文件夹。点击前往授权，在系统设置里打开开关即可。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    FileAccessHelper.openSettings(ctx)
                    vm.consumeStorageRequest()
                }) {
                    Text("前往授权", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.consumeStorageRequest() }) {
                    Text("暂不")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    selectedResult?.let { r ->
        ExecutionSheet(r = r, onDismiss = { selectedResult = null })
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("直接把任务丢给我", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            "例如：查看当前电量；连续读两次确认数值；把微信消息列表导出来；\n也可以直接说：帮我执行 ls /sdcard",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- 历史会话抽屉 ----------

@Composable
private fun SessionDrawer(vm: ChatViewModel, onClose: () -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("历史会话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "共 ${vm.sessions.size} 个对话 · 标题由 AI 自动概括",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.newSession(); onClose() }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            if (vm.sessions.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("还没有历史会话", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("发送第一条消息后自动保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(vm.sessions, key = { it.id }) { s: SessionMeta ->
                        SessionRow(
                            meta = s,
                            active = s.id == vm.currentSessionId,
                            onClick = { vm.switchSession(s.id); onClose() },
                            onDelete = { vm.deleteSession(s.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(meta: SessionMeta, active: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    meta.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${meta.messageCount} 条消息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "当前会话",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除会话",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageItem(vm: ChatViewModel, msg: ChatMessage, onOpenResult: (ToolResult) -> Unit) {
    when (msg.role) {
        Role.USER -> UserBubble(msg)
        Role.ASSISTANT -> AssistantCard(vm, msg, onOpenResult)
    }
}

// ---------- 用户气泡 ----------

@Composable
private fun UserBubble(msg: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (msg.attachments.isNotEmpty()) {
                    msg.attachments.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (a.type) {
                                AttachmentType.IMAGE -> Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(14.dp))
                                AttachmentType.TEXT -> Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(14.dp))
                                AttachmentType.BINARY -> Icon(Icons.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(
                                a.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                    if (msg.content.isNotBlank()) Spacer(Modifier.height(4.dp))
                }
                if (msg.content.isNotBlank()) {
                    Text(
                        msg.content,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

// ---------- 助手消息 ----------

@Composable
private fun AssistantCard(vm: ChatViewModel, msg: ChatMessage, onOpenResult: (ToolResult) -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 6 },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Timeline(msg, onOpenResult)
            when (msg.status) {
                MsgStatus.THINKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 静态指示（不用转圈动画，避免后台持续重绘烧 CPU）
                        Text("⟳", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(6.dp))
                        Text("思考中 · 第 ${msg.iterations} 轮", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                MsgStatus.EXECUTING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚙", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("执行命令中 · 第 ${msg.iterations} 轮", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                MsgStatus.STOPPED -> Text("（已停止）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MsgStatus.ERROR -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("请求出错", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    // 显示具体错误原因（如 HTTP 400 + 服务器返回的 message），便于排查网关兼容问题
                    val errText = msg.content.substringAfter("[错误]", "").trim()
                    if (errText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                errText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                MsgStatus.DONE -> if (msg.toolResults.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "执行了 ${msg.toolResults.size} 条命令 · 共调用 ${msg.iterations} 轮",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {}
            }
        }
    }
}

// ---------- 时间线（按轮次交替：思考 → 正文 → 执行框） ----------

/**
 * 按 AI 调用轮次把"思考段 / 正文 / 命令执行框"按发生顺序交错渲染，
 * 而不是把全部思考堆在最上面。
 */
@Composable
private fun Timeline(msg: ChatMessage, onOpenResult: (ToolResult) -> Unit) {
    val thinkingNow = msg.status == MsgStatus.THINKING
    val streaming = msg.status == MsgStatus.STREAMING
    val maxRound = maxOf(
        msg.reasoning.maxOfOrNull { it.round } ?: 0,
        msg.toolResults.maxOfOrNull { it.round } ?: 0,
        msg.round,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var execOrdinal = 0
        for (r in 1..maxRound) {
            // 1) 本轮思考段（可多段，各自独立折叠）
            val roundSegs = msg.reasoning.filter { it.round == r }
            roundSegs.forEachIndexed { i, seg ->
                val isLiveLast = thinkingNow && seg === msg.reasoning.lastOrNull() && r == msg.round
                ThinkingCard(
                    round = r,
                    index = i,
                    totalInRound = roundSegs.size,
                    seg = seg,
                    isLast = isLiveLast,
                    streaming = thinkingNow,
                )
            }
            // 2) 本轮正文（含代码块模式下内联的执行框）
            msg.textSegments.getOrNull(r - 1)?.takeIf { it.text.isNotBlank() }?.let { ts ->
                val blockBase = msg.textSegments.take(r - 1).sumOf { s -> countBashBlocks(s.text, 0, s.text.length) }
                renderSegments(msg, ts.text, blockBase, onOpenResult)
                if (streaming && r == msg.round) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BlinkingCursor()
                        Spacer(Modifier.width(4.dp))
                        Text("生成中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // 3) 本轮命令执行框（工具模式；块模式的框已内联在正文里）
            msg.toolResults.filter { !it.toolCallId.startsWith("block_") && it.round == r }
                .forEach { tr ->
                    ExecutedBox(index = execOrdinal, r = tr, onClick = { onOpenResult(tr) })
                    execOrdinal++
                }
        }
    }
}

// ---------- 思考折叠（按轮次分段，可分别折叠） ----------

@Composable
private fun ThinkingCard(
    round: Int,
    index: Int,
    totalInRound: Int,
    seg: ReasoningSegment,
    isLast: Boolean,
    streaming: Boolean,
) {
    if (seg.text.isBlank() && !(streaming && isLast)) return
    val rotation by animateFloatAsState(if (seg.open) 180f else 0f, label = "chev")
    val title = if (streaming && isLast) {
        "思考中 · 第${round}轮"
    } else {
        if (totalInRound > 1) "思考过程 · 第${round}轮 · 段${index + 1}" else "思考过程 · 第${round}轮"
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { seg.open = !seg.open }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
                if (streaming && isLast) ThreeDots()
                Spacer(Modifier.weight(1f))
                Text(
                    "${seg.text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (seg.open) "折叠思考" else "展开思考",
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = seg.open && seg.text.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Text(
                        seg.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreeDots() {
    // 静态三点（原为无限动画，改为静态以降低后台 CPU 占用）
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(5.dp)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f), CircleShape)
            )
            if (i < 2) Spacer(Modifier.width(3.dp))
        }
    }
}

@Composable
private fun BlinkingCursor() {
    // 静态光标（原为无限闪烁动画，改为静态以降低后台 CPU 占用）
    Text("▍", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
}

// ---------- 内容渲染 ----------

private fun countBashBlocks(text: String, from: Int, to: Int): Int {
    val re = Regex("```(?:bash|sh|shell)?\\s*\\n?([\\s\\S]*?)```")
    val part = text.substring(from, to.coerceAtMost(text.length))
    return re.findAll(part).count { it.groupValues[1].trim().isNotEmpty() }
}

@Composable
private fun renderSegments(msg: ChatMessage, text: String, baseOrdinal: Int, onOpenResult: (ToolResult) -> Unit) {
    val segments = remember(text) { parseSegments(text) }
    var codeIndex = -1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is Seg.Text -> if (seg.s.isNotBlank()) {
                    Text(
                        annotated(seg.s, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                is Seg.Code -> {
                    codeIndex++
                    val ordinal = baseOrdinal + codeIndex
                    val result = msg.toolResults.firstOrNull { it.toolCallId == "block_$ordinal" }
                    CodeBox(lang = seg.lang, body = seg.body, result = result, onClick = { result?.let(onOpenResult) })
                }
            }
        }
    }
}

// ---------- 代码框（方形框） ----------

@Composable
private fun CodeBox(lang: String, body: String, result: ToolResult?, onClick: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val borderColor = when {
        result?.cancelled == true -> MaterialTheme.colorScheme.outline
        result?.exitCode == 0 -> Success
        result != null -> ErrorC
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = CodeBg,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Terminal, contentDescription = null, tint = Success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    body.lineSequence().firstOrNull() ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9FE8C8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (lang.isNotBlank()) {
                    Text(
                        lang,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(body)) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
            if (body.contains('\n')) {
                Text(
                    body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFB8C4CE),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            result?.let { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExitBadge(r)
                    Spacer(Modifier.width(10.dp))
                    Text("${r.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.usedRoot) {
                        Spacer(Modifier.width(8.dp))
                        Text("root", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("查看详情 ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ExitBadge(r: ToolResult) {
    val (label, color) = when {
        r.cancelled -> "已取消" to MaterialTheme.colorScheme.onSurfaceVariant
        r.exitCode == 0 -> "退出码 0" to Success
        else -> "退出码 ${r.exitCode}" to ErrorC
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------- 已执行命令框 ----------

@Composable
private fun ExecutedBox(index: Int, r: ToolResult, onClick: () -> Unit) {
    val borderColor = if (r.exitCode == 0) Success else ErrorC
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = CodeBg,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("$", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Success)
            Spacer(Modifier.width(6.dp))
            Text(
                r.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ExitBadge(r)
        }
    }
}

// ---------- 半屏执行详情 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExecutionSheet(r: ToolResult, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("命令执行详情", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))

            SectionLabel("指令")
            Surface(shape = RoundedCornerShape(10.dp), color = CodeBg, modifier = Modifier.fillMaxWidth()) {
                Text(
                    r.command,
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9FE8C8),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExitBadge(r)
                Spacer(Modifier.width(10.dp))
                Text("耗时 ${r.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(if (r.usedRoot) "root 通道" else "普通 shell", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(r.command))
                }) {
                    Text("复制指令", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString("$ ${r.command}\n\n退出码: ${r.exitCode}\n\n标准输出:\n${r.stdout}\n\n错误输出:\n${r.stderr}"))
                }) {
                    Text("复制全部", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.height(6.dp))
            SectionLabel("标准输出")
            Surface(shape = RoundedCornerShape(10.dp), color = CodeBg, modifier = Modifier.fillMaxWidth()) {
                Text(
                    r.stdout.ifBlank { "（无输出）" },
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel("错误输出")
            Surface(shape = RoundedCornerShape(10.dp), color = CodeBg, modifier = Modifier.fillMaxWidth()) {
                Text(
                    r.stderr.ifBlank { "（无错误）" },
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (r.stderr.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else ErrorC,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

// ---------- 高危指令确认 ----------

@Composable
private fun RiskDialog(
    risk: ChatViewModel.RiskPrompt,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("高危指令", color = MaterialTheme.colorScheme.error) },
        text = {
            Column {
                Text(
                    "该指令可能对设备造成影响：${risk.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = CodeBg, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        risk.command,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF9FE8C8),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "是否仍然执行？也可以在设置里关闭高危指令确认，之后不会再询问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("仍然执行", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

// ---------- 输入栏 ----------

@Composable
private fun InputBar(vm: ChatViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    val ctx = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            // 一次查询取元信息；必须 moveToFirst()，否则游标停在 -1 行，getString/getLong 直接抛异常
            var name: String? = null
            var size = -1L
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            val fileName = name ?: "file"
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            if (size > MAX_ATTACH_SIZE) {
                vm.notify("文件超过 ${MAX_ATTACH_SIZE / 1024 / 1024}MB，无法上传")
                return@rememberLauncherForActivityResult
            }
            val type = when {
                mime.startsWith("image/") -> AttachmentType.IMAGE
                mime.startsWith("text/") || fileName.substringAfterLast('.', "").lowercase() in TEXT_EXTS ->
                    AttachmentType.TEXT
                else -> AttachmentType.BINARY
            }
            if (type == AttachmentType.IMAGE && !vm.supportsVision()) {
                vm.notify("当前模型不支持图片识别，无法上传图片")
                return@rememberLauncherForActivityResult
            }
            // 复制到 App 私有目录，URI 授权失效后 AI 依然能通过 shell 读取
            val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[^\\w.\\-()\\u4e00-\\u9fa5]"), "_")
            val dest = File(dir, "${System.currentTimeMillis()}_$safeName")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            attachments = attachments + Attachment(
                id = UUID.randomUUID().toString(),
                name = fileName,
                mime = mime,
                size = if (size >= 0) size else dest.length(),
                type = type,
                localPath = dest.absolutePath,
            )
        }.onFailure { vm.notify("文件读取失败：${it.message}") }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments, key = { it.id }) { a ->
                        AttachmentChip(a, onRemove = { attachments = attachments - a })
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "上传图片或文件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入指令或问题…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                if (vm.busy) {
                    FilledIconButton(onClick = { vm.stop() }) {
                        Icon(Icons.Filled.Close, contentDescription = "停止", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                } else {
                    FilledIconButton(onClick = {
                        if (text.isNotBlank() || attachments.isNotEmpty()) {
                            vm.send(text, attachments)
                            text = ""
                            attachments = emptyList()
                        }
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

private val TEXT_EXTS = setOf("txt", "json", "xml", "log", "md", "csv", "yaml", "yml", "conf", "ini", "sh", "py", "java", "kt", "sql", "html", "css", "js", "ts", "cfg")
private const val MAX_ATTACH_SIZE = 20L * 1024 * 1024

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (a.type) {
                AttachmentType.IMAGE -> Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                AttachmentType.TEXT -> Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                AttachmentType.BINARY -> Icon(
                    Icons.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                a.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "移除附件", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

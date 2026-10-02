package com.aicmd.relay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicmd.relay.agent.FileAccessHelper
import com.aicmd.relay.data.DEFAULT_SYSTEM_PROMPT
import com.aicmd.relay.ui.ChatViewModel
import kotlinx.coroutines.delay

private val modelPresets = listOf("deepseek-reasoner", "deepseek-chat", "gpt-4o", "自定义")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("设置", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item { SectionTitle("API 连接") }

            item {
                LabeledField(
                    label = "API 主机地址",
                    value = vm.settings.baseUrl,
                    placeholder = "https://api.deepseek.com/v1",
                    onValue = { vm.updateSettings { s -> s.copy(baseUrl = it) } },
                )
            }
            item {
                var showKey by remember { mutableStateOf(false) }
                LabeledField(
                    label = "API 密钥",
                    value = vm.settings.apiKey,
                    placeholder = "sk-...",
                    onValue = { vm.updateSettings { s -> s.copy(apiKey = it) } },
                    trailing = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "隐藏密钥" else "显示密钥",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    isPassword = !showKey,
                )
            }
            item {
                LabeledField(
                    label = "模型",
                    value = vm.settings.model,
                    placeholder = "deepseek-reasoner",
                    onValue = { vm.updateSettings { s -> s.copy(model = it) } },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modelPresets.forEach { p ->
                        OutlinedButton(
                            onClick = { vm.updateSettings { s -> s.copy(model = if (p == "自定义") s.model else p) } },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text(p, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.testConnection() }, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("测试连接（请求 /models）")
                }
            }

            item { SectionTitle("思考与输出") }
            item {
                SettingToggle(
                    title = "流式输出",
                    desc = "逐字返回结果；关闭后一次性返回全文",
                    checked = vm.settings.streamEnabled,
                    onChecked = { vm.updateSettings { s -> s.copy(streamEnabled = it) } },
                )
            }
            item {
                PillSelector(
                    title = "思考强度",
                    options = listOf("high" to "高", "medium" to "中", "low" to "低", "off" to "关闭"),
                    selected = vm.settings.thinkingStrength,
                    onSelect = { vm.updateSettings { s -> s.copy(thinkingStrength = it) } },
                )
            }
            item {
                PillSelector(
                    title = "图片识别",
                    desc = "自动：模型名含 vision / vl / 4o / omni / gemini / claude 等则允许传图",
                    options = listOf("auto" to "自动", "on" to "允许", "off" to "禁止"),
                    selected = vm.settings.visionMode,
                    onSelect = { vm.updateSettings { s -> s.copy(visionMode = it) } },
                )
            }

            item { SectionTitle("智能体行为") }
            item {
                SettingToggle(
                    title = "高危指令执行前确认",
                    desc = "重启 / 关机 / dd 写入 / pm install 等命令执行前弹窗；关闭后不再询问",
                    checked = vm.settings.highRiskConfirm,
                    onChecked = { vm.updateSettings { s -> s.copy(highRiskConfirm = it) } },
                )
            }
            item {
                StepperRow(
                    title = "最大连续调用次数",
                    value = vm.settings.maxIterations,
                    onValue = { vm.updateSettings { s -> s.copy(maxIterations = it) } },
                )
            }
            item {
                PillSelector(
                    title = "Root 模式",
                    desc = "自动：有 root 用 root，没有则普通 shell",
                    options = listOf("auto" to "自动", "root" to "强制 Root", "normal" to "普通"),
                    selected = vm.settings.rootMode,
                    onSelect = { vm.updateSettings { s -> s.copy(rootMode = it) } },
                )
            }
            item {
                // 所有文件访问权限（Android 11+ 访问 /sdcard 任意目录必需）
                val granted = vm.storageAccessGranted()
                val ctx = LocalContext.current
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { FileAccessHelper.openSettings(ctx) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "所有文件访问权限",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (granted) {
                                    "已授权：AI 可读取 /sdcard 下任意文件夹"
                                } else {
                                    "未授权：AI 无法读取 /sdcard 文件夹，点击前往授权"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = "文件访问权限",
                            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SectionTitle("系统提示词") }
            item {
                var prompt by remember { mutableStateOf(vm.settings.systemPrompt) }
                var promptCommitted by remember { mutableStateOf(vm.settings.systemPrompt) }
                // 外部变化（恢复默认）时同步本地
                LaunchedEffect(vm.settings.systemPrompt) {
                    if (vm.settings.systemPrompt != promptCommitted) {
                        prompt = vm.settings.systemPrompt
                        promptCommitted = vm.settings.systemPrompt
                    }
                }
                // 防抖提交
                LaunchedEffect(prompt) {
                    if (prompt != promptCommitted) {
                        delay(500)
                        if (prompt != promptCommitted) {
                            vm.updateSettings { s -> s.copy(systemPrompt = prompt) }
                            promptCommitted = prompt
                        }
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    shape = RoundedCornerShape(12.dp),
                )
                TextButton(onClick = {
                    vm.resetSystemPrompt()
                }) {
                    Text("恢复默认提示词", color = MaterialTheme.colorScheme.secondary)
                }
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                Text(
                    "当前版本：${vm.appVersion()}\n" +
                        "说明：\n" +
                        "· 内置电池命令：让 AI 执行 `battery` 可免 root 读取电量。\n" +
                        "· 大部分系统命令（dumpsys、pm install、reboot 等）需要 root 权限。\n" +
                        "· 高危指令（重启/关机/dd/pm install 等）默认弹窗确认，可在上方关闭。\n" +
                        "· 模型不支持函数调用时（如部分 reasoner），会自动切换到代码块模式执行。\n" +
                        "· 密钥只保存在本机，直接发给你在设置里填写的 API 地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    isPassword: Boolean = false,
) {
    // 本地状态 + 防抖提交：避免每次敲键都写磁盘导致输入框重建、光标乱跳
    var local by remember { mutableStateOf(value) }
    var committed by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != committed) {
            local = value
            committed = value
        }
    }
    LaunchedEffect(local) {
        if (local != committed) {
            delay(500)
            if (local != committed) {
                onValue(local)
                committed = local
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = local,
            onValueChange = { local = it },
            placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = { trailing?.invoke() },
        )
    }
}

@Composable
private fun SettingToggle(title: String, desc: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun PillSelector(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    desc: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        desc?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                val active = key == selected
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { onSelect(key) },
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepperRow(title: String, value: Int, onValue: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("达到上限后停止继续调用模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { onValue((value - 1).coerceAtLeast(1)) }, shape = RoundedCornerShape(12.dp)) {
            Text("−", fontSize = 18.sp)
        }
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        OutlinedButton(onClick = { onValue((value + 1).coerceAtMost(30)) }, shape = RoundedCornerShape(12.dp)) {
            Text("+", fontSize = 18.sp)
        }
    }
}

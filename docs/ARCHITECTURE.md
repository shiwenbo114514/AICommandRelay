# 架构说明

## 总体结构

```
AICommandRelay/
├── app/src/main/java/com/aicmd/relay/
│   ├── MainActivity.kt          # 入口，请求通知权限，启动保活前台服务
│   ├── KeepAliveService.kt      # 前台服务（保活）
│   ├── ui/
│   │   ├── ChatViewModel.kt     # 智能体引擎 + 会话管理 + 附件 + 文件权限触发
│   │   ├── chat/ChatScreen.kt   # 聊天页（时间线渲染、抽屉、附件、授权弹窗）
│   │   └── settings/SettingsScreen.kt
│   ├── net/OpenAIClient.kt      # OpenAI 兼容客户端（流式/工具/图片/降级）
│   ├── agent/
│   │   ├── ShellExecutor.kt     # shell 执行（普通 / root 双通道）
│   │   ├── RiskDetector.kt      # 高危指令识别
│   │   └── FileAccessHelper.kt  # MANAGE_EXTERNAL_STORAGE 权限辅助
│   └── data/
│       ├── AppSettings.kt       # 设置模型 + 默认系统提示词
│       ├── SettingsRepo.kt      # DataStore
│       ├── ChatModels.kt        # ChatMessage / 思考段 / 工具调用 / 结果
│       └── SessionStore.kt      # 历史会话 JSON 持久化
└── app/build.gradle.kts
```

## 智能体引擎（多轮循环）

`ChatViewModel.send()` 驱动一次完整任务：

```
用户消息
  └─→ 循环（最多 maxIterations 次）：
        1. runRound()：调用 chat/completions（流式）
           - 流式增量写入「思考段 / 正文段 / 工具参数」
        2. 若模型返回工具调用（run_shell）：
           - RiskDetector 高危检查 → 弹窗确认
           - ShellExecutor.execute() 执行命令
           - 结果（stdout/stderr/exitCode）追加为 tool result
           - 不结束循环：下一轮必须再调用模型，让它总结
        3. 若模型没要工具调用 → 本轮即最终回答，结束
  └─→ 兜底：执行过命令但无正文总结 → 强制再问一次
  └─→ 保存会话 + 让 AI 生成会话标题
```

### 双通道降级

| 情况 | 处理 |
|---|---|
| 模型支持工具调用 | `tools=[run_shell]` 标准工具模式 |
| 模型不支持工具（400/422 + 相关措辞） | 自动切「代码块模式」：解析 ```bash 块执行 |
| 网关不支持流式（400） | 关闭 stream 一次性返回重试同一轮 |
| 请求永久挂起 | OkHttp readTimeout 300s 硬超时 |

### 并行工具调用

多个工具参数增量按 `id`（而非 index）区分归属，参数去重累积，JSON 损坏时正则兜底提取 command。

### DeepSeek 思考模式

携带 tools 的请求必须**完整回传上一轮 assistant 的 `reasoning_content`**，否则 400。`buildApiMessages()` 为所有 assistant 消息回传思考内容。

## 思考分段渲染

每轮 AI 调用是独立的 `ReasoningSegment`（round 标记）；`ChatMessage.textSegments` 存每轮正文。
UI 按 round 交错渲染：思考卡（可折叠）→ 正文 → 命令执行框，避免"多段思考挤在第一段"的视觉错误。

## 会话持久化

- 每个会话一个 JSON 文件：`filesDir/sessions/{sessionId}.json`
- 保存时机：流式输出节流 1.2s / 命令执行完立即 / 对话结束完整保存
- 启动时自动恢复最近会话
- 标题：对话结束后调用当前 API 让 AI 概括（≤12 字），失败兜底取首句

## 后台省电设计

- 全部无限动画静态化（转圈→字符、光标→静态、三点→静态），消除持续重绘
- OkHttp readTimeout 300s，防止挂起请求拖住进程
- KeepAliveService 前台服务保活

## 文件上传策略

- 图片：OpenAI 兼容 `image_url` data URL 传视觉模型（按模型名关键词自动检测视觉能力）
- 文本/二进制：只给 AI **绝对路径**，由 AI 自行 `ls/file/head/cat/grep/strings` 分析，不内联内容、不浪费上下文
- 文件复制进私有目录 `attachments/` 供 shell 读取

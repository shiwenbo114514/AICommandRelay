# API 配置说明

## 通用配置

| 字段 | 说明 | 示例 |
|---|---|---|
| 主机地址 | OpenAI 兼容 base URL，**需包含 /v1**（App 会自动补 `/chat/completions`） | `https://api.deepseek.com/v1` |
| API Key | 服务商后台获取 | `sk-...` |
| 模型名 | 服务商支持的模型 id | `deepseek-reasoner` |

填写后点"测试连接"：App 请求 `GET {base}/models`，返回可用模型数量即配置正确。

## 常见服务商

### DeepSeek

- 主机：`https://api.deepseek.com/v1`
- 模型：`deepseek-reasoner`（思考）、`deepseek-chat`（普通）
- 注意：思考模型携带工具调用时，必须回传 `reasoning_content`（App 已内置处理）；工具不支持时自动降级。

### ModelScope（魔搭）

- 主机：`https://api-inference.modelscope.cn/v1`
- 模型：如 `deepseek-ai/deepseek-v4.1-flash`
- 注意：部分网关对流式 / 工具参数的兼容性差异，App 已实现"流式→非流式→代码块"阶梯降级；若仍失败，界面会显示具体错误文本，可反馈给服务商。

### OpenAI

- 主机：`https://api.openai.com/v1`
- 模型：`gpt-4o`（支持视觉，可发图片）、`gpt-4o-mini`

### 通义千问 / 其它

- 任意 OpenAI 兼容 `/chat/completions` 接口均可。

## 视觉（图片）能力

设置页"图片识别"三档：

| 档位 | 行为 |
|---|---|
| auto（默认） | 按模型名关键词自动判断：vision / -vl / 4o / omni / gemini / claude / llava 等 |
| on | 强制允许发图 |
| off | 禁止发图 |

## 思考强度

设置页可选手动"超高思考 / 高 / 中 / 低 / 关闭"：
- 仅对支持 `reasoning_effort` 的模型（o1/o3/thinking/qwq/glm-z1/kimi-k2-thinking 等）生效；
- 其余模型忽略该参数。

## 错误排查

| 现象 | 原因与处理 |
|---|---|
| 测试连接成功，但发消息报错 | 测试走 GET /models，对话走 POST /chat/completions；App 已做流式/工具降级，若仍失败，消息内会显示真实 HTTP 错误文本，按提示排查 |
| `reasoning_content` 相关 400 | DeepSeek 思考模型必须回传思考内容（已内置），第三方代理若报错请确认其兼容性 |
| Permission denied（访问 /sdcard） | Android 11+ 需授予"所有文件访问"权限，App 会弹窗引导到系统设置 |
| 请求永久无响应 | 已设 300s 硬超时，超时后自动报错并结束该轮 |

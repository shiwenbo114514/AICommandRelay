# AI指令中转站 (AICommandRelay)

> 让普通 OpenAI 兼容 API 变成真正能"动手干活"的安卓 AI 智能体。

把 DeepSeek、通义、ModelScope、OpenAI 等任何 **OpenAI 兼容接口** 接入手机，AI 不再只动嘴告诉你"应该怎么做"，而是通过内置的 `run_shell` 工具**直接在 Android 上执行 shell 命令**：查电量、读传感器、看 CPU、管理应用、处理文件…… 你说一句话，它自己思考、自己敲命令、自己看结果、自己总结。

## 功能特性

- 🔑 **任意 OpenAI 兼容 API**：手动填 API Key、主机地址、模型名，自带"测试连接"（GET /models 校验）。
- 🤖 **AI 智能体引擎**：AI 识别到 `run_shell` 工具后，通过工具调用（tools）直接在系统 shell 执行命令；不支持工具调用的模型自动降级为解析 ```bash 代码块双通道。
- 🔁 **多轮自动执行**：一次任务可连续多次调用模型、连续执行多条命令（如"连续读两次电量确认稳定性"），执行完命令后**强制再问一次模型**做总结，绝不干完活就闭嘴。
- 🧠 **思考过程按轮分段**：每轮 AI 调用的思考内容独立成段、独立折叠，时间线交错展示"思考 → 正文 → 命令执行框"。
- 🖥️ **命令执行详情**：AI 执行的命令显示在方形代码框里，点击弹出半屏详情（命令 + 退出码 + 完整输出 + 耗时）。
- ⚡ **流式输出 + 自动降级**：默认流式；网关不支持流式/工具时自动降级为一次性返回/代码块，错误详情直接显示在消息里。
- 🛡️ **高危指令保护**：重启 / 关机 / dd 写入 / pm install 等命令执行前弹窗确认，可一次关闭询问。
- 📂 **文件/图片上传**：支持上传图片与任意文件；自动检测模型是否支持视觉，不支持则禁止发图；文件一律给 AI **绝对路径**，由 AI 自己 `ls/cat/grep` 分析，不浪费上下文。
- 🗂️ **历史会话**：每个对话本地持久化，左侧抽屉自由切换 / 新建 / 删除；**标题由 AI 自动概括**。
- 🧘 **低后台占用**：全部无限动画静态化，前台服务保活不被杀，尽量省电。
- 📱 **所有文件访问**：AI 访问 /sdcard 被拒时自动弹窗引导授权 MANAGE_EXTERNAL_STORAGE。

## 界面预览

（仓库不含截图，安装后即可体验）

## 快速开始

### 方式一：直接安装（推荐体验）

1. 从 [Release](https://github.com/yourname/AICommandRelay/releases) 下载 `AI指令中转站-v1.0-release.apk`（R8 混淆正式版）或 `-debug.apk`（测试版）。
2. 安装后打开，左上角 ☰ → 设置。
3. 填入 API 主机地址、API Key、模型名（例如 DeepSeek：`https://api.deepseek.com/v1` + `deepseek-reasoner`）。
4. 点"测试连接"，成功后回到聊天页直接说："查看当前电量"。

> Android 11+ 若要 AI 读写 /sdcard 任意文件夹，需在系统设置里授予"所有文件访问"权限（App 会在遇到 Permission denied 时弹窗引导）。

### 方式二：从源码构建

环境要求：JDK 17、Android SDK（compileSdk 34 / build-tools 34）、Gradle 8.7。

```bash
# 1. 配置 SDK
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 2. 构建 debug（测试版，未混淆）
./gradlew assembleDebug

# 3. 构建 release（R8 混淆 + 资源压缩）
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/{debug,release}/`。

> release 默认需要 `keystore/release.jks` 签名。首次构建请先执行下方命令生成自己的签名文件，并把 `app/build.gradle.kts` 里的 storePassword/keyPassword 改成你自己的：

```bash
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias aicmd -storepass aicmd123456 -keypass aicmd123456 -dname "CN=AICommandRelay, OU=Dev, O=AICommandRelay, L=City, ST=State, C=CN"
```

## 支持的 API 说明

- 任何 OpenAI 兼容 `/chat/completions` 接口。
- DeepSeek：`https://api.deepseek.com/v1`（思考模型 `deepseek-reasoner` 要求工具请求回传 `reasoning_content`，已内置处理）。
- ModelScope：`https://api-inference.modelscope.cn/v1`（流式/工具兼容性差异已做自动降级）。
- 更多参见 [docs/API_CONFIG.md](docs/API_CONFIG.md)。

## 架构

- **UI**：Kotlin + Jetpack Compose（Material 3），流式渲染按轮次时间线交错展示思考/正文/执行。
- **引擎**：`ChatViewModel` 驱动多轮循环 —— 调用模型 → 解析工具调用 → ShellExecutor 执行 → 结果回传 → 再调用模型总结。
- **网络**：OkHttp 自实现 OpenAI 兼容客户端（流式 SSE、工具调用、图片 data URL、reasoning_content 回传）。
- **执行**：`ShellExecutor` 支持普通 shell 与 root 双通道，`RiskDetector` 高危指令识别，内置 `battery` 免 root 电量命令。
- **持久化**：设置走 DataStore；历史会话 JSON 存 App 私有目录 `sessions/`。
- 详细设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 权限清单

| 权限 | 用途 |
|---|---|
| INTERNET | 调用 AI API |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC | 前台服务保活，防止后台被杀 |
| POST_NOTIFICATIONS | 保活通知 |
| MANAGE_EXTERNAL_STORAGE | AI 读写 /sdcard 任意文件（可选，系统设置页手动授予） |
| WAKE_LOCK | 保活辅助 |

## 安全与免责

- 本工具赋予 AI **执行系统命令**的能力，高危命令（重启/关机/dd/pm install 等）默认弹窗确认。
- 仅供学习与个人自动化使用，请勿在未授权设备上使用，滥用导致的后果由使用者自行承担。

## License

[MIT](LICENSE) © AICommandRelay contributors

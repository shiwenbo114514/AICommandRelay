# AI指令中转站 (AICommandRelay) — R8 混淆规则
# 策略：业务类不整体 keep，交给 R8 全量混淆/裁剪；仅保留第三方库与反射所需。

# OkHttp / Okio：运行时依赖反射与内部结构，官方建议保留
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.coroutines：内部协程调度结构
-dontwarn kotlinx.coroutines.**

# 保留签名与注解（OkHttp、Compose 等依赖）
-keepattributes Signature
-keepattributes *Annotation*, InnerClasses

# 保留 Kotlin 元数据，避免 Compose / 协程 / 序列化运行期问题
-keep class kotlin.Metadata { *; }

# 保留清单中声明的组件（双保险，AGP 默认也会保留）
-keep class com.aicmd.relay.MainActivity { *; }
-keep class com.aicmd.relay.KeepAliveService { *; }

package com.aicmd.relay.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val usedRoot: Boolean,
    val cancelled: Boolean = false,
)

object ShellExecutor {

    private const val TIMEOUT_MS = 30_000L
    private const val OUTPUT_CAP = 64 * 1024

    @Volatile
    private var rootCache: Boolean? = null

    /**
     * 执行一条命令。
     * - 内置虚拟命令 `battery`：直接用系统 API 读取电量，无需 root。
     * - rootMode: auto（有 root 用 root，没有则普通）/ root（强制 su）/ normal（强制普通 shell）
     */
    suspend fun execute(context: Context, command: String, rootMode: String): ExecResult {
        val cmd = command.trim()
        if (cmd.isEmpty()) return ExecResult(-1, "", "空命令", 0, false)
        if (cmd == "battery" || cmd.startsWith("battery ")) return readBattery(context)

        val useRoot = when (rootMode) {
            "root" -> true
            "normal" -> false
            else -> hasRootAccess()
        }

        val start = SystemClock.elapsedRealtime()
        var exit = -1
        var timedOut = false
        var stdout = ""
        var stderr = ""
        var process: Process? = null

        try {
            coroutineScope {
                val job = async(Dispatchers.IO) {
                    val pb = if (useRoot) ProcessBuilder("su", "-c", cmd)
                    else ProcessBuilder("/system/bin/sh", "-c", cmd)
                    pb.redirectErrorStream(false)
                    process = pb.start()
                    val out = async { process!!.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
                    val err = async { process!!.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
                    stdout = out.await()
                    stderr = err.await()
                    exit = process!!.waitFor()
                }
                try {
                    withTimeout(TIMEOUT_MS) { job.await() }
                } catch (e: TimeoutCancellationException) {
                    timedOut = true
                    runCatching { process?.destroy() }
                }
            }
        } catch (e: Exception) {
            stderr = (stderr + "\n" + (e.message ?: "命令启动失败")).trim()
        }
        if (timedOut) {
            stderr = (stderr + "\n[超时] 命令 ${TIMEOUT_MS / 1000} 秒未执行完，已强制终止").trim()
        }
        return ExecResult(
            exitCode = exit,
            stdout = stdout.trim().take(OUTPUT_CAP),
            stderr = stderr.trim().take(OUTPUT_CAP),
            durationMs = SystemClock.elapsedRealtime() - start,
            usedRoot = useRoot,
        )
    }

    /** 检测设备是否可用 root（带缓存）。 */
    fun hasRootAccess(): Boolean {
        rootCache?.let { return it }
        val hasBinary = runCatching {
            val p = ProcessBuilder("sh", "-c", "command -v su || which su").start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.isNotEmpty()
        }.getOrDefault(false)
        if (!hasBinary) {
            rootCache = false
            return false
        }
        val works = runCatching {
            val p = ProcessBuilder("su", "-c", "id").start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        }.getOrDefault(false)
        rootCache = works
        return works
    }

    /** 重置 root 检测缓存（用户切换 Root 模式后调用）。 */
    fun resetRootCache() {
        rootCache = null
    }

    private fun readBattery(context: Context): ExecResult = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0

        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC 电源"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
            else -> "未插电"
        }
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            else -> "未知"
        }
        val text = buildString {
            appendLine("电量: $level%")
            appendLine("状态: $statusText ($source)")
            appendLine("温度: ${temp / 10.0}°C")
            appendLine("电压: ${voltage / 1000.0} V")
            appendLine("健康: $healthText")
        }
        ExecResult(0, text.trim(), "", 0, false)
    }.getOrElse { e ->
        ExecResult(-1, "", "读取电量失败: ${e.message}", 0, false)
    }
}

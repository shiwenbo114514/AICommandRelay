package com.aicmd.relay.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * "所有文件访问"权限（MANAGE_EXTERNAL_STORAGE，Android 11+）。
 * 授予后 AI 的 shell 命令才能读取 /sdcard 下任意文件夹（分区存储之外）。
 */
object FileAccessHelper {

    /** 是否已授予所有文件访问权限（Android 10 及以下天然可访问，视为已授权）。 */
    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** 跳转系统"所有文件访问权限"授权页（需用户手动打开开关）。 */
    fun openSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 部分 ROM 不支持按包名跳转，退回全局授权页
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 命令是否访问了 /sdcard（判断是否需要该权限的粗略信号）。 */
    fun touchesSdcard(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c.contains("/sdcard") || c.contains("/storage/emulated") || c.contains("/storage/self/primary")
    }
}

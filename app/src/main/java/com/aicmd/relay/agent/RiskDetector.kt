package com.aicmd.relay.agent

/**
 * 高危指令识别。命中任意规则则返回风险原因（用于弹窗提示）。
 */
object RiskDetector {

    private data class Rule(val pattern: Regex, val reason: String)

    private val rules = listOf(
        Rule(Regex("""(?<![A-Za-z0-9_])reboot(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE), "重启设备，会中断所有正在运行的程序"),
        Rule(Regex("""(?<![A-Za-z0-9_])(shutdown|poweroff|halt)(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE), "关机/停机设备"),
        Rule(Regex("""(?<![A-Za-z0-9_])dd(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE), "dd 磁盘写入，可能损坏分区或数据"),
        Rule(
            Regex("""(^|[\s;&|])pm\s+(install|uninstall|disable|enable)|(^|[\s;&|])cmd\s+package\s+(install|uninstall)""", RegexOption.IGNORE_CASE),
            "安装/卸载/停用应用"
        ),
        Rule(Regex("""rm\s+-[a-zA-Z]*[rf]""", RegexOption.IGNORE_CASE), "递归或强制删除文件"),
        Rule(
            Regex("""(?<![A-Za-z0-9_])(mkfs|fdisk|parted|fastboot|wipe)(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE),
            "磁盘格式化/分区/刷机/擦除操作"
        ),
        Rule(Regex("""format\s+/dev""", RegexOption.IGNORE_CASE), "格式化磁盘分区"),
        Rule(Regex("""mount\s+-o\s+remount|umount\s+/""", RegexOption.IGNORE_CASE), "重挂载/卸载系统分区"),
        Rule(Regex("""(echo|cat|printf|dd)\b[^|;]*\s*>\s*/dev/""", RegexOption.IGNORE_CASE), "直接写入块设备"),
        Rule(Regex("""chmod\s+-R\s+777\s*/|chown\s+-R\s+[^\s]+\s*/""", RegexOption.IGNORE_CASE), "递归修改根目录权限"),
        Rule(Regex("""setenforce\s+0|setprop\s+selinux\s+permissive""", RegexOption.IGNORE_CASE), "关闭 SELinux 安全防护"),
        Rule(Regex("""(curl|wget)\s+\S+.*\|\s*(sh|bash)\b""", RegexOption.IGNORE_CASE), "下载脚本并直接执行（远程代码执行）"),
    )

    /** 返回命中的风险原因；未命中返回 null。 */
    fun check(command: String): String? {
        val normalized = command.trim()
        if (normalized.isEmpty()) return null
        return rules.firstOrNull { it.pattern.containsMatchIn(normalized) }?.reason
    }
}

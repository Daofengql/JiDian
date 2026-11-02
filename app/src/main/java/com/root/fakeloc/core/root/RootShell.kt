package com.root.fakeloc.core.root

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root 特化执行器
 * 负责与系统底层 `su` 守护进程交互，直接以 root 身份提权、配置 AppOps 策略、
 * 授予权限并执行底层系统命令，无需任何 adb 调试连接。
 */
object RootShell {

    private const val TAG = "RootShell"
    @Volatile var environmentReady = false
        private set

    data class EnvironmentResult(val status: String, val detail: String)

    fun invalidateEnvironment() { environmentReady = false }

    /** One SU request configures this app only; the UI has no non-root fallback. */
    fun prepareEnvironment(context: Context): EnvironmentResult {
        environmentReady = false
        val pkg = context.packageName
        val userId = android.os.Process.myUid() / 100_000
        val commands = mutableListOf(
            "id -u | grep -qx 0 || exit 77",
            "echo __ROOT_GRANTED__",
            "appops set --user $userId $pkg android:mock_location allow || exit 81",
            "pm grant --user $userId $pkg android.permission.ACCESS_COARSE_LOCATION || exit 82",
            "pm grant --user $userId $pkg android.permission.ACCESS_FINE_LOCATION || exit 83"
        )
        // Notification permission is optional for the foreground service.
        if (android.os.Build.VERSION.SDK_INT >= 33) commands.add("pm grant --user $userId $pkg android.permission.POST_NOTIFICATIONS || true")
        commands.add("appops set --user $userId $pkg android:system_alert_window allow || true")
        commands.add("echo __ROOT_ENV_READY__")
        val result = exec(commands)
        val granted = result.output.lineSequence().any { it == "__ROOT_GRANTED__" }
        if (!granted) {
            val missing = result.error.contains("No such file", true) || result.error.contains("error=2")
            return EnvironmentResult(if (missing) "unavailable" else "denied",
                if (missing) "设备未提供 SU，当前无法使用 Root 定位。" else "未取得 SU 授权，请在 Root 管理器中允许 JiDian，返回应用后会自动重试。")
        }
        if (!result.isSuccess || !result.output.lineSequence().any { it == "__ROOT_ENV_READY__" }) {
            return EnvironmentResult("error", "Root 已授权，自动配置未完成，返回应用后会自动重试。")
        }
        environmentReady = true
        return EnvironmentResult("granted", "Root 可用，定位环境已自动配置。")
    }

    /**
     * 批量执行 Root 命令
     */
    private fun exec(commands: List<String>): CommandResult {
        var process: Process? = null
        var os: DataOutputStream? = null
        val outputExecutor = Executors.newSingleThreadExecutor()

        try {
            process = ProcessBuilder("su").redirectErrorStream(true).start()
            val rootProcess = process
            val output = outputExecutor.submit<String> {
                rootProcess.inputStream.bufferedReader().use { it.readText() }
            }
            os = DataOutputStream(process.outputStream)

            for (cmd in commands) {
                os.writeBytes("$cmd\n")
            }
            os.writeBytes("exit\n")
            os.flush()

            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return CommandResult(-1, "", "SU 授权超时")
            }
            val exitCode = process.exitValue()
            val text = output.get(2, TimeUnit.SECONDS).trim()

            return CommandResult(
                exitCode = exitCode,
                output = text,
                error = if (exitCode == 0) "" else text
            )
        } catch (e: Exception) {
            Log.e(TAG, "Root execution failed: ${e.message}")
            return CommandResult(
                exitCode = -1,
                output = "",
                error = e.message ?: "Unknown exception"
            )
        } finally {
            try { os?.close() } catch (_: Exception) {}
            process?.destroy()
            outputExecutor.shutdownNow()
        }
    }

    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val error: String
    ) {
        val isSuccess: Boolean
            get() = exitCode == 0
    }
}

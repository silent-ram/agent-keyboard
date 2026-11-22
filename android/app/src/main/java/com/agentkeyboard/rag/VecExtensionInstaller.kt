package com.agentkeyboard.rag

import com.agentkeyboard.util.Logs
import android.content.Context
import android.util.Log

/** Temporary SQLite extension loader used only while migrating databases from the local-index version. */
object VecExtensionInstaller {

    private const val TAG = "KbRag"

    /** 每连接注册的 so 绝对路径;null = 未安装(不挂扩展) */
    @Volatile
    var soPath: String? = null
        private set

    private const val MIN_SO_BYTES = 100L * 1024 // 各 ABI vec0.so 实际 ~133-152KB

    /** Copies the legacy vec0 module so the database migration can drop its virtual table. */
    @Synchronized
    fun prepare(context: Context): Boolean {
        soPath?.let { return true }
        return try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return false
            val dir = java.io.File(context.filesDir, "sqlite-vec/$abi")
            val so = java.io.File(dir, "vec0.so")
            if (!so.exists() || so.length() < MIN_SO_BYTES) {
                dir.mkdirs()
                val tmp = java.io.File(dir, "vec0.so.tmp")
                context.assets.open("sqlite-vec/$abi/vec0.so").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (tmp.length() < MIN_SO_BYTES) {
                    tmp.delete()
                    Log.w(TAG, "vec0.so copy too small: ${tmp.length()}")
                    return false
                }
                if (!tmp.renameTo(so)) {
                    // 目标已存在等场景:校验现有文件后视为就绪
                    if (!so.exists() || so.length() < MIN_SO_BYTES) return false
                    tmp.delete()
                }
            }
            soPath = so.absolutePath
            Logs.i(TAG, "vec0 extension ready: ${so.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "vec0 install failed: ${e.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun removeLegacyCopy(context: Context) {
        soPath = null
        val dir = java.io.File(context.filesDir, "sqlite-vec")
        if (dir.exists() && !dir.deleteRecursively()) {
            throw java.io.IOException("无法删除旧向量扩展文件")
        }
    }
}

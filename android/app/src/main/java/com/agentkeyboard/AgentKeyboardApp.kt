package com.agentkeyboard

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication

/** Removes data owned by the retired local knowledge index after the database migration. */
class AgentKeyboardApp : FcitxApplication() {

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startupScope.launch {
            runCatching { com.agentkeyboard.rag.LegacyKnowledgeDataCleanup.run(this@AgentKeyboardApp) }
                .onFailure { Log.e("AgentKeyboard", "旧知识库数据清理失败", it) }
        }
    }
}

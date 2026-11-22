package com.agentkeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.agentkeyboard.ui.AssistantTheme
import androidx.compose.runtime.mutableStateOf
import com.agentkeyboard.agent.AgentFactory
import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.ui.AppTheme

internal val PrimaryBlue @Composable get() = AppTheme.componentColors.primary
internal val BodyText @Composable get() = AppTheme.componentColors.bodyText
internal val MutedText @Composable get() = AppTheme.componentColors.mutedText
internal val SectionText @Composable get() = AppTheme.componentColors.sectionText
internal val ErrorText @Composable get() = AppTheme.componentColors.errorText
internal val ErrorBorder @Composable get() = AppTheme.componentColors.errorBorder
internal val SystemBg @Composable get() = AppTheme.componentColors.systemBg
internal val OkGreen @Composable get() = AppTheme.componentColors.okGreen

class MainActivity : ComponentActivity() {
    /**
     * AgentKit 与明示宿主随 ViewModel 存活:旋转/深色切换等配置变更不再
     * 取消流式回答或把待决确认误判为拒绝(2026-11-19 审查 P2)。
     * 真正退出(Activity 最终销毁)时由 onCleared 取消孤儿协程。
     */
    private val appModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[AppAgentViewModel::class.java]
    }
    private val requestedOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOpen.value = intent.getStringExtra(EXTRA_OPEN)
        setContent {
            AssistantTheme {
                KnowledgeAssistantRoot(this, appModel.agentKit, appModel.disclosureHost, requestedOpen.value)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedOpen.value = intent.getStringExtra(EXTRA_OPEN)
    }

    class AppAgentViewModel(app: android.app.Application) : androidx.lifecycle.AndroidViewModel(app) {
        val disclosureHost = DisclosurePromptHost()
        val agentKit = AgentFactory.createKnowledgeAssistant(
            app,
            ConfirmationResolver(disclosureHost::resolve),
        )

        override fun onCleared() {
            agentKit.engine.cancel() // 确认挂起中销毁时取消孤儿协程
            disclosureHost.answer(false)
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_MANAGE = "manage"
        const val OPEN_LIBRARY = "library"
        const val OPEN_SETTINGS = "settings"
    }
}

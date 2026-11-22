package com.agentkeyboard.ime

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * IME 服务专用的 lifecycle owner(即任务书中的 ServiceLifecycleOwner)。
 *
 * InputMethodService 的视图树不属于任何 Activity,系统不会为它安装
 * Lifecycle/ViewModelStore/SavedStateRegistry,ComposeView 挂载时会因找不到
 * ViewTreeLifecycleOwner 而崩溃。这里自建一个 Registry 实现三者,由
 * [AgentKeyboardService] 按如下映射分发事件:
 * - onCreate        → ON_CREATE
 * - onCreateInputView → ON_START
 * - onStartInputView  → ON_RESUME
 * - onFinishInputView → ON_PAUSE(不进 ON_STOP)
 * - onDestroy       → ON_DESTROY
 *
 * 注意:输入视图在多次输入会话间被复用(不 detach),生命周期在 ON_PAUSE 后
 * 停留在 STARTED(不到 STOPPED/DESTROYED),Compose 的组合可安全复用;
 * 视图真正重建由 AgentKeyboardService.onCreateInputView 每次 new 出新树保证。
 * M1 注意(FAIL-SAFE):ImeContextProvider.detect(null)→NORMAL,在接入
 * read_context 前必须改为 PASSWORD(见设计 11-权限系统)。
 */
class ImeLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreInstance = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        // Compose rememberSaveable 依赖 SavedStateRegistry 处于 attached 状态
        savedStateRegistryController.performRestore(null)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance

    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun dispatch(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}

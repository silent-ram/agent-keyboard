package com.agentkeyboard.ime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * V-A 直出路径状态机单测(QuickActionRunner):转圈/完成/失败/取消/并行互斥/空源不启动;
 * 附长按阈值纯函数(QuickActionLongPress)与直出错误分类(QuickActions.classifyQuickError)。
 */
class QuickActionRunnerTest {

    /** 记录型回调:收集状态机各出路 */
    private class RecordingCallbacks : QuickActionRunner.Callbacks {
        val runningChanges = mutableListOf<Pair<QuickAction, Boolean>>()
        val results = mutableListOf<Triple<QuickAction, String, String>>() // action, result, source
        val errors = mutableListOf<Pair<QuickAction, String>>()
        val cancelled = mutableListOf<QuickAction>()

        override fun onRunningChanged(action: QuickAction, running: Boolean) {
            runningChanges += action to running
        }

        override fun onResult(action: QuickAction, result: String, source: String, fromSelection: Boolean) {
            results += Triple(action, result, source)
        }

        override fun onError(action: QuickAction, kind: String) {
            errors += action to kind
        }

        override fun onCancelled(action: QuickAction) {
            cancelled += action
        }

        fun runningOf(action: QuickAction): List<Boolean> =
            runningChanges.filter { it.first == action }.map { it.second }
    }

    // ==== 转圈 → 完成 ====

    @Test
    fun `点击后进入转圈态,完成回调携带结果并复原`() = runTest {
        val cb = RecordingCallbacks()
        val gate = CompletableDeferred<String>()
        // scope 用 TestScope 本身(backgroundScope 协程在 1.8.1 不会被 advanceUntilIdle 推进)
        val runner = QuickActionRunner(this, { _, _ -> gate.await() }, cb)
        runner.onButton(QuickAction.REWRITE) { QuickActionRunner.SourcePick("原文句子", true) }
        assertTrue(QuickAction.REWRITE in runner.running.value) // 转圈态
        gate.complete("改写后的句子")
        advanceUntilIdle()
        assertEquals(listOf(Triple(QuickAction.REWRITE, "改写后的句子", "原文句子")), cb.results)
        assertTrue(runner.running.value.isEmpty()) // 按钮复原
        assertEquals(listOf(true, false), cb.runningOf(QuickAction.REWRITE)) // 复原成对
    }

    // ==== 转圈 → 失败 ====

    @Test
    fun `失败路径 - onError 回调并复原,不产生结果`() = runTest {
        val cb = RecordingCallbacks()
        val runner = QuickActionRunner(this, { _, _ -> throw UnknownHostException("dns") }, cb)
        runner.onButton(QuickAction.TRANSLATE) { QuickActionRunner.SourcePick("文本", false) }
        assertTrue(QuickAction.TRANSLATE in runner.running.value)
        advanceUntilIdle()
        assertEquals(listOf(QuickAction.TRANSLATE to "NO_NETWORK"), cb.errors)
        assertTrue(cb.results.isEmpty())
        assertTrue(runner.running.value.isEmpty())
        assertEquals(listOf(true, false), cb.runningOf(QuickAction.TRANSLATE))
    }

    // ==== 转圈 → 取消(同按钮互斥:再点 = 取消)====

    @Test
    fun `运行中再点同按钮等于取消,不读输入框不产生结果`() = runTest {
        val cb = RecordingCallbacks()
        val gate = CompletableDeferred<String>()
        val runner = QuickActionRunner(this, { _, _ -> gate.await() }, cb)
        runner.onButton(QuickAction.TRANSLATE) { QuickActionRunner.SourcePick("文本", false) }
        advanceUntilIdle() // 让协程跑到 await 挂起点(生产 Main.immediate 下 launch 即同步执行至此)
        // 再点:取消路径不应触碰输入框(provider 抛错即测试失败)
        runner.onButton(QuickAction.TRANSLATE) { error("取消路径不应读输入框") }
        advanceUntilIdle()
        assertEquals(listOf(QuickAction.TRANSLATE), cb.cancelled)
        assertTrue(cb.results.isEmpty())
        assertTrue(runner.running.value.isEmpty())
        assertEquals(listOf(true, false), cb.runningOf(QuickAction.TRANSLATE))
        // 迟到的 complete 返回也不再产生结果回调
        gate.complete("迟到结果")
        advanceUntilIdle()
        assertTrue(cb.results.isEmpty())
    }

    // ==== 并行与互斥 ====

    @Test
    fun `不同动作可并行,同按钮完成后可重新启动`() = runTest {
        val cb = RecordingCallbacks()
        val gates = QuickAction.entries.associateWith { CompletableDeferred<String>() }
        val runner = QuickActionRunner(this, { action, _ -> gates[action]!!.await() }, cb)
        runner.onButton(QuickAction.REWRITE) { QuickActionRunner.SourcePick("a", false) }
        runner.onButton(QuickAction.TRANSLATE) { QuickActionRunner.SourcePick("b", false) }
        assertTrue(QuickAction.REWRITE in runner.running.value)
        assertTrue(QuickAction.TRANSLATE in runner.running.value) // 多动作并行
        gates[QuickAction.REWRITE]!!.complete("新A")
        advanceUntilIdle()
        assertFalse(QuickAction.REWRITE in runner.running.value)
        assertTrue(QuickAction.TRANSLATE in runner.running.value) // 互不干扰
        // REWRITE 完成后同按钮可再次点击并正常启动(互斥只约束运行中)
        runner.onButton(QuickAction.REWRITE) { QuickActionRunner.SourcePick("c", false) }
        assertTrue(QuickAction.REWRITE in runner.running.value)
        // 收尾:取消在途动作(runTest 会等待子协程)
        runner.cancel(QuickAction.TRANSLATE)
        runner.cancel(QuickAction.REWRITE)
        advanceUntilIdle()
    }

    // ==== 空源不启动 ====

    @Test
    fun `空源不启动,不进入转圈态`() = runTest {
        val cb = RecordingCallbacks()
        val runner = QuickActionRunner(this, { _, _ -> error("空源不应调用 complete") }, cb)
        runner.onButton(QuickAction.HELP_WRITE) { null }
        advanceUntilIdle()
        assertTrue(runner.running.value.isEmpty())
        assertTrue(cb.runningChanges.isEmpty())
        assertTrue(cb.errors.isEmpty())
        assertTrue(cb.results.isEmpty())
    }

    // ==== 错误分类纯函数 ====

    @Test
    fun `直出错误分类 - 未配置_超时_无网_其他`() {
        assertEquals("NOT_CONFIGURED", QuickActions.classifyQuickError(ModelNotConfiguredException()))
        assertEquals("TIMEOUT", QuickActions.classifyQuickError(SocketTimeoutException()))
        assertEquals("NO_NETWORK", QuickActions.classifyQuickError(UnknownHostException()))
        assertEquals("OTHER", QuickActions.classifyQuickError(RuntimeException("x")))
    }

    // ==== P3(评审修正单):jobs map 完成后清理,防驻留 ====

    @Test
    fun `完成后与取消后 jobs 均已清理,不驻留 Job 引用`() = runTest {
        val cb = RecordingCallbacks()
        val gate = CompletableDeferred<String>()
        val runner = QuickActionRunner(this, { _, _ -> gate.await() }, cb)
        runner.onButton(QuickAction.REWRITE) { QuickActionRunner.SourcePick("a", false) }
        assertTrue("运行中应持有 job", runner.jobForTest(QuickAction.REWRITE) != null)
        gate.complete("结果")
        advanceUntilIdle()
        assertTrue("完成后 job 应被清理", runner.jobForTest(QuickAction.REWRITE) == null)
        // 取消路径同样清理(同按钮互斥取消)
        val gate2 = CompletableDeferred<String>()
        val runner2 = QuickActionRunner(this, { _, _ -> gate2.await() }, cb)
        runner2.onButton(QuickAction.TRANSLATE) { QuickActionRunner.SourcePick("b", false) }
        runner2.cancel(QuickAction.TRANSLATE)
        advanceUntilIdle()
        assertTrue("取消后 job 应被清理", runner2.jobForTest(QuickAction.TRANSLATE) == null)
    }

    // ==== 长按阈值纯函数 ====

    @Test
    fun `长按阈值 - 400ms 及以上打开面板,短按不开`() {
        assertFalse(QuickActionLongPress.shouldOpenPanel(0L))
        assertFalse(QuickActionLongPress.shouldOpenPanel(399L))
        assertTrue(QuickActionLongPress.shouldOpenPanel(400L))
        assertTrue(QuickActionLongPress.shouldOpenPanel(1200L))
    }
}

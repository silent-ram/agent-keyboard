package com.agentkeyboard.agent

import com.agentkeyboard.tools.ToolIds

/**
 * One user-submitted turn and the application-approved tools available to it.
 * The model may choose among these tools, but cannot expand this set.
 */
data class TaskRequest(
    val task: Task,
    val allowedToolIds: Set<String>,
) {
    init {
        require(task.userText.isNotBlank()) { "任务内容不能为空" }
        require(allowedToolIds.none(String::isBlank)) { "工具 ID 不能为空" }
    }

    companion object {
        fun create(task: Task, registeredToolIds: Set<String>): TaskRequest {
            val allowed = when {
                task.singleTurn -> emptySet()
                else -> registeredToolIds.filterTo(linkedSetOf()) { toolId ->
                    task.kbEnabled || toolId != ToolIds.KB_SEARCH
                }
            }
            return TaskRequest(task, allowed)
        }
    }
}

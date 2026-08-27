package com.iris.android.agent

interface PermissionBroker {
    suspend fun requestApproval(toolName: String, summary: String, detail: String? = null): Boolean
}

interface ToolExecutor {
    suspend fun execute(name: String, args: Map<String, Any?>): String
}

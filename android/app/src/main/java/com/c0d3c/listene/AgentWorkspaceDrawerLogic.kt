package com.c0d3c.listene

// 工作区 / 抽屉导航与刷新调度、以及乐观追加用户消息的纯逻辑（无 Compose/Android 依赖、可单测）。
// 从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；均为 internal，同模块调用点无需改动。

internal fun agentLibraryDeleteConsequenceText(libraryTitle: String): String {
    val normalizedTitle = libraryTitle.trim().ifBlank { "当前库" }
    val libraryName = if (normalizedTitle.endsWith("库")) normalizedTitle else "${normalizedTitle}库"
    return "不会再出现在${libraryName}中"
}

internal fun agentWorkspaceDeleteConsequenceText(): String =
    "删除后不会再出现在工作区中。"

internal fun agentWorkspaceDisplayDescription(workspace: LearningWorkspace): String =
    listOf(workspace.summary, workspace.need, workspace.plan.summary)
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && !agentIsGeneratedWorkspaceDescription(it) }
        .orEmpty()

internal enum class AgentDrawerBackTarget {
    DrawerHome
}

internal fun agentDrawerFeatureBackTarget(): AgentDrawerBackTarget = AgentDrawerBackTarget.DrawerHome

internal fun agentDrawerSystemBackTarget(drawerOpen: Boolean, onFeaturePage: Boolean): AgentDrawerBackTarget? =
    when {
        drawerOpen && onFeaturePage -> AgentDrawerBackTarget.DrawerHome
        drawerOpen -> AgentDrawerBackTarget.DrawerHome
        else -> null
    }

internal enum class AgentDrawerWorkspaceRefreshMode {
    RemoteSync
}

internal fun agentDrawerWorkspaceRefreshMode(): AgentDrawerWorkspaceRefreshMode =
    AgentDrawerWorkspaceRefreshMode.RemoteSync

internal fun agentDispatchWorkspaceRefresh(
    mode: AgentDrawerWorkspaceRefreshMode,
    refreshRemote: () -> Unit,
    reloadLocal: () -> Unit
) {
    when (mode) {
        AgentDrawerWorkspaceRefreshMode.RemoteSync -> refreshRemote()
    }
}

internal fun agentOpenWorkspaceDirectoryWithSync(
    refreshWorkspaces: () -> Unit,
    openDirectory: () -> Unit
) {
    refreshWorkspaces()
    openDirectory()
}

internal fun agentReturnToDrawerHomeWithSync(
    refreshWorkspaces: () -> Unit,
    returnHome: () -> Unit
) {
    refreshWorkspaces()
    returnHome()
}

internal fun agentWorkspaceAfterDelete(
    previousActiveId: String?,
    deletedWorkspaceId: String,
    remaining: List<LearningWorkspace>
): LearningWorkspace? {
    val preferredActiveId = previousActiveId
        ?.takeIf { it.isNotBlank() && it != deletedWorkspaceId }
    return WorkspaceStore.activeAfterWorkspaceRemoval(preferredActiveId ?: deletedWorkspaceId, remaining)
}

internal fun agentWorkspacesAfterRemoteUpdate(
    current: List<LearningWorkspace>,
    remote: LearningWorkspace
): List<LearningWorkspace> =
    WorkspaceStore.sortForDisplay(listOf(remote) + current.filterNot { it.id == remote.id })

internal fun agentActiveWorkspaceAfterRemoteUpdate(
    active: LearningWorkspace?,
    remote: LearningWorkspace
): LearningWorkspace? =
    if (active?.id == remote.id) remote else active

internal fun agentMessagesWithOptimisticUserInput(
    priorMessages: List<AgentChatMessage>,
    userText: String,
    displayText: String?,
    attachments: List<AgentInputAttachment>,
    id: Long,
    appendUserMessage: Boolean = true
): List<AgentChatMessage> =
    if (!appendUserMessage) {
        priorMessages.takeLast(80)
    } else {
    (
        priorMessages + AgentChatMessage(
            id = id,
            role = AgentChatRole.User,
            text = displayText ?: userText,
            attachments = attachments
        )
        ).takeLast(80)
    }

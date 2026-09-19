package com.c0d3c.listene

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class AppNavEvent {
    data class OpenListeningRecord(val recordId: String) : AppNavEvent()
    data class OpenWorkspaceListeningRecord(val workspaceId: String, val recordId: String, val openAnalysis: Boolean = false) : AppNavEvent()
    data class StartWorkspaceMaterial(val workspaceId: String) : AppNavEvent()
    data object OpenSettings : AppNavEvent()
}

/** 通知栏点击等场景向 UI 层派发导航事件 */
object AppNavigationBus {
    private val _events = MutableSharedFlow<AppNavEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AppNavEvent> = _events.asSharedFlow()

    fun emit(event: AppNavEvent) {
        _events.tryEmit(event)
    }
}

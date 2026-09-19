package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceProgressTest {
    private val steps = listOf(
        WorkspaceStep("material", "生成听力素材", "生成脚本、音频和题目", "pending"),
        WorkspaceStep("practice", "完成听力练习", "听音频并答题", "pending"),
        WorkspaceStep("analysis", "AI 错因分析", "定位薄弱点", "pending"),
        WorkspaceStep("review", "词句复练与计划", "沉淀后续复习", "pending")
    )

    @Test
    fun marksStepsAroundCurrentStep() {
        val updated = WorkspaceStore.stepsForCurrentStep(steps, "analysis")

        assertEquals(
            listOf("done", "done", "running", "pending"),
            updated.map { it.status }
        )
    }

    @Test
    fun removesWorkspaceAndKeepsCurrentWhenItStillExists() {
        val oldActive = workspace("ws_old", updatedAt = 10L)
        val newActive = workspace("ws_new", updatedAt = 20L)

        val remaining = WorkspaceStore.removeWorkspace(listOf(newActive, oldActive), "ws_new")
        val active = WorkspaceStore.activeAfterWorkspaceRemoval("ws_old", remaining)

        assertEquals(listOf("ws_old"), remaining.map { it.id })
        assertEquals("ws_old", active?.id)
    }

    @Test
    fun selectsNextActiveWorkspaceAfterDeletingCurrent() {
        val deleted = workspace("ws_deleted", updatedAt = 30L)
        val next = workspace("ws_next", updatedAt = 20L)
        val paused = workspace("ws_paused", updatedAt = 40L, status = WorkspaceStatus.Paused)

        val remaining = WorkspaceStore.removeWorkspace(listOf(paused, deleted, next), "ws_deleted")
        val active = WorkspaceStore.activeAfterWorkspaceRemoval("ws_deleted", remaining)

        assertEquals(listOf("ws_paused", "ws_next"), remaining.map { it.id })
        assertEquals("ws_next", active?.id)
    }

    @Test
    fun deletingDifferentWorkspaceKeepsCurrentWorkspaceSelected() {
        val current = workspace("ws_current", updatedAt = 20L)
        val deleted = workspace("ws_deleted", updatedAt = 30L)
        val paused = workspace("ws_paused", updatedAt = 40L, status = WorkspaceStatus.Paused)

        val remaining = WorkspaceStore.removeWorkspace(listOf(paused, deleted, current), "ws_deleted")
        val active = agentWorkspaceAfterDelete("ws_current", "ws_deleted", remaining)

        assertEquals("ws_current", active?.id)
    }

    @Test
    fun deletingCurrentWorkspaceSelectsFallbackWorkspace() {
        val current = workspace("ws_current", updatedAt = 30L)
        val next = workspace("ws_next", updatedAt = 20L)
        val paused = workspace("ws_paused", updatedAt = 40L, status = WorkspaceStatus.Paused)

        val remaining = WorkspaceStore.removeWorkspace(listOf(paused, current, next), "ws_current")
        val active = agentWorkspaceAfterDelete("ws_current", "ws_current", remaining)

        assertEquals("ws_next", active?.id)
    }

    @Test
    fun collectsCascadeTargetsFromLinkedIdsAndEvents() {
        val workspace = workspace("ws_targets", updatedAt = 40L).copy(
            linkedRecordIds = listOf("record_1"),
            linkedContainerIds = listOf("container_1"),
            linkedPlanTaskIds = listOf("task_1"),
            events = listOf(
                WorkspaceEvent(
                    id = "event_1",
                    type = "material_record_created",
                    title = "生成素材",
                    description = "",
                    recordId = "record_2",
                    createdAt = 10L
                ),
                WorkspaceEvent(
                    id = "event_2",
                    type = "word_container_created",
                    title = "生成词句包",
                    description = "",
                    containerId = "container_2",
                    taskId = "task_2",
                    targetKind = "history",
                    targetId = "record_3",
                    createdAt = 20L
                )
            )
        )

        val targets = WorkspaceStore.cascadeTargetsFor(workspace)

        assertEquals(listOf("record_1", "record_2", "record_3"), targets.recordIds)
        assertEquals(listOf("container_1", "container_2"), targets.containerIds)
        assertEquals(listOf("task_1", "task_2"), targets.taskIds)
    }

    @Test
    fun workspacePlanKeepsVoiceDirectives() {
        val plan = WorkspacePlan(
            title = "雅思听力",
            summary = "低音英音慢速",
            contentType = "dialogue",
            materialPrompt = "雅思听力",
            difficulty = "普通",
            speechRate = "slow",
            voiceGender = "female",
            voiceProfile = "Female gentle low-pitched British tutor voice",
            pitch = "low",
            accent = "british",
            tone = "gentle"
        )

        assertEquals("Female gentle low-pitched British tutor voice", plan.voiceProfile)
        assertEquals("low", plan.pitch)
        assertEquals("british", plan.accent)
        assertEquals("gentle", plan.tone)
    }

    @Test
    fun workspaceAnalysisEventPayloadCarriesWeakPointsForMemory() {
        val payload = WorkspaceStore.eventPayloadJson(
            type = "analysis_completed",
            title = "AI 错因分析完成",
            description = "Numbers were confusing.",
            recordId = "record_1",
            currentStep = "review",
            weakPoints = listOf("数字听辨容易错", "转折信号 however 漏听")
        )

        assertEquals("analysis_completed", payload.str("type"))
        assertEquals("review", payload.str("currentStep"))
        val weakPoints = payload.arrOrNull("weakPoints")!!
        assertEquals(2, weakPoints.size)
        assertEquals("数字听辨容易错", weakPoints.str(0))
        assertEquals("转折信号 however 漏听", weakPoints.str(1))
    }

    @Test
    fun workspaceMergeKeepsRemoteMemoryWhenLocalMetadataIsNewer() {
        val remote = workspace("ws_memory", updatedAt = 100L).copy(
            memorySummary = "User practiced present perfect.",
            memory = listOf(
                WorkspaceMemoryEntry(
                    key = "weakness:already_yet",
                    type = "weakness",
                    content = "Confuses already and yet.",
                    importance = 0.9,
                    updatedAt = 100L
                )
            )
        )
        val local = workspace("ws_memory", updatedAt = 200L).copy(
            title = "本地重命名",
            memorySummary = "",
            memory = emptyList()
        )

        val merged = WorkspaceStore.mergeWorkspaceVersions(listOf(remote, local))

        assertEquals("本地重命名", merged.title)
        assertEquals("User practiced present perfect.", merged.memorySummary)
        assertEquals(1, merged.memory.size)
        assertEquals("weakness:already_yet", merged.memory.single().key)
    }

    private fun workspace(
        id: String,
        updatedAt: Long,
        status: WorkspaceStatus = WorkspaceStatus.Active
    ) = LearningWorkspace(
        id = id,
        title = "$id 工作区",
        need = "练听力",
        summary = "完整闭环",
        status = status,
        currentStep = "practice",
        createdAt = 1L,
        updatedAt = updatedAt,
        plan = WorkspacePlan(
            title = "$id 工作区",
            summary = "完整闭环",
            contentType = "dialogue",
            materialPrompt = "校园场景",
            difficulty = "普通",
            speechRate = "medium",
            voiceGender = "female",
            steps = WorkspaceStore.stepsForCurrentStep(steps, "practice")
        )
    )

}

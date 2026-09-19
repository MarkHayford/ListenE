package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File

class AgentWorkspaceRecordBindingTest {
    @Test
    fun importedListeningManifestKeepsSpeakers() {
        val manifest = buildJsonObject { put("format", "listene.listening"); put("version", 1); put("contentType", "dialogue"); put("scene", "Cafe dialogue"); put("content", buildJsonObject { put("title", "Cafe"); put("script", "Alice: Hello.\n\nBen: Hi."); put("questions", buildJsonArray { add(buildJsonObject { put("questionText", "Who says hello?"); put("options", buildJsonArray { (listOf("Alice", "Ben")).forEach { add(it) } }); put("correctAnswer", 0) }) }); put("speakers", buildJsonArray { add(buildJsonObject { put("speakerId", "P1"); put("speakerName", "Alice"); put("speakerGender", "female") }); add(buildJsonObject { put("speakerId", "P2"); put("speakerName", "Ben"); put("speakerGender", "male") }) }) }) }

        val content = parseListeningContentFromManifest(manifest.objOrNull("content")!!)

        assertEquals(2, content.speakers.size)
        assertEquals("Alice", content.speakers[0].speakerName)
        assertEquals("Ben", content.speakers[1].speakerName)
    }

    @Test
    fun importedListeningManifestParsesCommonAnswerFormatsAndDropsInvalidQuestions() {
        val content = parseListeningContentFromManifest(
            buildJsonObject { put("title", "Quiz"); put("script", "Listen and answer."); put("questions", buildJsonArray { add(buildJsonObject { put("questionText", "Which option is fourth?"); put("options", buildJsonArray { (listOf("one", "two", "three", "four")).forEach { add(it) } }); put("correctAnswer", "4") }); add(buildJsonObject { put("questionText", "Which option is first?"); put("options", buildJsonArray { (listOf("alpha", "beta", "gamma", "delta")).forEach { add(it) } }); put("answer", "A)") }); add(buildJsonObject { put("questionText", "Which option is invalid?"); put("options", buildJsonArray { (listOf("alpha", "beta")).forEach { add(it) } }); put("correctAnswer", "missing") }) }) }
        )

        assertEquals(2, content.questions.size)
        assertEquals(3, content.questions[0].correctAnswer)
        assertEquals(0, content.questions[1].correctAnswer)
    }

    @Test
    fun exportedListeningManifestKeepsSpeakers() {
        val record = record("record_speakers").copy(
            content = record("record_speakers").content.copy(
                speakers = listOf(
                    ListeningSpeaker(speakerId = "P1", speakerName = "Alice", speakerGender = "female"),
                    ListeningSpeaker(speakerId = "P2", speakerName = "Ben", speakerGender = "male")
                )
            )
        )

        val manifest = (parseJsonObjectOrNull(listeningManifestJson(record))!!)
        val speakers = manifest.objOrNull("content")!!.arrOrNull("speakers")!!

        assertEquals(2, speakers.size)
        assertEquals("Alice", speakers.objOrNull(0)!!.str("speakerName"))
        assertEquals("Ben", speakers.objOrNull(1)!!.str("speakerName"))
    }

    @Test
    fun usesGeneratedSuccessRecordBeforeWorkspaceEventLinksIt() {
        val workspace = workspace("ws_active")
        val record = record("record_generated")
        val state = GenerationState.Success(
            content = record.content,
            recordId = record.id,
            scene = record.scene
        )

        val current = agentCardCurrentRecord(
            activeWorkspace = workspace,
            history = listOf(record),
            state = state,
            activeViewModelWorkspaceId = workspace.id
        )

        assertEquals(record.id, current?.id)
        assertEquals("/tmp/audio.wav", current?.content?.audioUrl)
    }

    @Test
    fun ignoresActiveSuccessRecordWhenOpeningDifferentWorkspace() {
        val jobRecord = record("record_job").copy(createdAt = 10L)
        val vocabRecord = record("record_vocab").copy(createdAt = 20L)
        val jobWorkspace = workspace("ws_job").copy(linkedRecordIds = listOf(jobRecord.id))
        val state = GenerationState.Success(
            content = vocabRecord.content,
            recordId = vocabRecord.id,
            scene = vocabRecord.scene
        )

        val current = agentCardCurrentRecord(
            activeWorkspace = jobWorkspace,
            history = listOf(jobRecord, vocabRecord),
            state = state,
            activeViewModelWorkspaceId = jobWorkspace.id
        )

        assertEquals(jobRecord.id, current?.id)
    }

    @Test
    fun usesLatestSuccessAnswerStateBeforeHistoryRefreshes() {
        val workspace = workspace("ws_active").copy(linkedRecordIds = listOf("record_answered"))
        val staleHistory = record("record_answered").copy(
            selectedAnswers = emptyMap(),
            answersRevealed = false
        )
        val state = GenerationState.Success(
            content = staleHistory.content,
            recordId = staleHistory.id,
            initialSelectedAnswers = mapOf(0 to 0),
            initialAnswersRevealed = true,
            scene = staleHistory.scene
        )

        val current = agentCardCurrentRecord(
            activeWorkspace = workspace,
            history = listOf(staleHistory),
            state = state,
            activeViewModelWorkspaceId = workspace.id
        )

        assertEquals(true, current?.answersRevealed)
        assertEquals(mapOf(0 to 0), current?.selectedAnswers)
    }

    @Test
    fun fallsBackToWorkspaceLinkedHistoryRecordWhenNoFreshSuccessExists() {
        val record = record("record_linked")
        val workspace = workspace("ws_active").copy(linkedRecordIds = listOf(record.id))

        val current = agentCardCurrentRecord(
            activeWorkspace = workspace,
            history = listOf(record),
            state = GenerationState.Idle,
            activeViewModelWorkspaceId = workspace.id
        )

        assertEquals(record.id, current?.id)
    }

    @Test
    fun currentWorkspaceRecordSelectionRequiresActiveWorkspaceRecord() {
        val current = agentCurrentWorkspaceRecordForEntry(
            activeWorkspace = null,
            history = listOf(record("record_linked")),
            state = GenerationState.Idle,
            activeViewModelWorkspaceId = null
        )

        assertEquals(null, current)
    }

    @Test
    fun currentWorkspaceRecordSelectionUsesLinkedHistoryRecord() {
        val record = record("record_linked")
        val workspace = workspace("ws_active").copy(linkedRecordIds = listOf(record.id))

        val current = agentCurrentWorkspaceRecordForEntry(
            activeWorkspace = workspace,
            history = listOf(record),
            state = GenerationState.Idle,
            activeViewModelWorkspaceId = null
        )

        assertEquals(record.id, current?.id)
    }

    @Test
    fun treatsAnalysisResultAsCurrentWorkspaceBeforeWorkspaceEventLinksRecord() {
        val workspace = workspace("ws_active")

        val belongs = agentAnalysisResultBelongsToWorkspace(
            workspace = workspace,
            recordId = "record_analyzed",
            activeViewModelWorkspaceId = workspace.id
        )

        assertEquals(true, belongs)
    }

    @Test
    fun doesNotTreatAnalysisResultAsDifferentWorkspaceWhenRecordIsUnlinked() {
        val workspace = workspace("ws_active")

        val belongs = agentAnalysisResultBelongsToWorkspace(
            workspace = workspace,
            recordId = "record_analyzed",
            activeViewModelWorkspaceId = "ws_other"
        )

        assertEquals(false, belongs)
    }


    @Test
    fun libraryCardBindsToWorkspaceRecordForDynamicMaterialComponents() {
        val record = record("record_linked")
        val workspace = workspace("ws_active").copy(linkedRecordIds = listOf(record.id))
        val item = libraryItem(workspaceId = workspace.id)

        val current = agentLibraryItemCurrentRecord(
            item = item,
            workspaces = listOf(workspace),
            history = listOf(record),
            state = GenerationState.Idle,
            activeViewModelWorkspaceId = null
        )

        assertNotNull(current)
        assertEquals(record.id, current?.id)
        assertEquals("/tmp/audio.wav", current?.content?.audioUrl)
    }

    @Test
    fun restoresFileLibraryAssetForMenuActions() {
        val lesson = File.createTempFile("listene-lesson", ".md").apply { deleteOnExit() }
        val item = UserLibraryItem(
            id = "file_1",
            kind = "files",
            title = "lesson.md",
            summary = "text/markdown",
            data = buildJsonObject { put("workspaceId", "ws_active"); put("mimeType", "text/markdown"); put("sizeBytes", 42L); put("localPath", lesson.absolutePath); put("downloadUrl", "https://example.com/lesson.md"); put("generated", true) },
            createdAt = 1L,
            updatedAt = 2L
        )

        val asset = userLibraryFileAsset(item)
        val attachment = asset.toAttachment()

        assertEquals("lesson.md", asset.name)
        assertEquals("text/markdown", asset.mimeType)
        assertEquals(42L, asset.sizeBytes)
        assertEquals(lesson.absolutePath, asset.localPath)
        assertEquals("https://example.com/lesson.md", asset.downloadUrl)
        assertNotNull(attachment)
        assertEquals("lesson.md", attachment?.name)
        assertEquals(true, attachment?.generated)
    }

    @Test
    fun classifiesFileLibraryPreviewModes() {
        val notes = File.createTempFile("listene-notes", ".md").apply { deleteOnExit() }
        val cover = File.createTempFile("listene-cover", ".png").apply { deleteOnExit() }

        assertEquals(
            UserLibraryFilePreviewMode.Text,
            userLibraryFilePreviewMode(fileItem("notes.md", "text/markdown", localPath = notes.absolutePath))
        )
        assertEquals(
            UserLibraryFilePreviewMode.Image,
            userLibraryFilePreviewMode(fileItem("cover.png", "image/png", localPath = cover.absolutePath))
        )
        assertEquals(
            UserLibraryFilePreviewMode.Web,
            userLibraryFilePreviewMode(fileItem("report.html", "text/html", downloadUrl = "https://example.com/report.html"))
        )
        assertEquals(
            UserLibraryFilePreviewMode.Audio,
            userLibraryFilePreviewMode(fileItem("clip.mp3", "audio/mpeg", downloadUrl = "https://example.com/clip.mp3"))
        )
        assertEquals(
            UserLibraryFilePreviewMode.External,
            userLibraryFilePreviewMode(fileItem("deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        )
        assertEquals(
            UserLibraryFilePreviewMode.External,
            userLibraryFilePreviewMode(
                fileItem(
                    "deck-online.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    downloadUrl = "https://example.com/deck-online.pptx"
                )
            )
        )
    }

    @Test
    fun staleLocalFilePathFallsBackToOnlineFilePreview() {
        val item = fileItem(
            "remote-notes.md",
            "text/markdown",
            localPath = "/tmp/listene/missing-remote-notes.md",
            downloadUrl = "https://example.com/remote-notes.md"
        )

        assertEquals(UserLibraryFilePreviewMode.Web, userLibraryFilePreviewMode(item))
    }

    @Test
    fun remoteOnlyAndroidLocalPathWithoutOnlineUrlIsNotWebPreviewOrAttachment() {
        val item = fileItem(
            "remote-only-notes.md",
            "text/markdown",
            localPath = "/data/user/0/com.c0d3c.listene/files/remote-only-notes.md"
        )

        assertEquals("", userLibraryFileAsset(item).previewUrl)
        assertNull(userLibraryFileAsset(item).toAttachment())
        assertEquals(UserLibraryFilePreviewMode.External, userLibraryFilePreviewMode(item))
    }

    @Test
    fun remoteOnlyLocalPathUsesOnlineUrlWhenDownloadUrlExists() {
        val item = fileItem(
            "remote-online-notes.md",
            "text/markdown",
            localPath = "/tmp/listene/missing-remote-online-notes.md",
            downloadUrl = "https://example.com/remote-online-notes.md"
        )

        val asset = userLibraryFileAsset(item)

        assertEquals("", asset.previewUrl)
        assertEquals("https://example.com/remote-online-notes.md", asset.downloadUrl)
        assertNull(asset.toAttachment())
        assertEquals(UserLibraryFilePreviewMode.Web, userLibraryFilePreviewMode(item))
    }

    @Test
    fun filePreviewSourceUsesOnlineUrlWhenLocalPathIsMissing() {
        val asset = userLibraryFileAsset(
            fileItem(
                "remote-audio.mp3",
                "audio/mpeg",
                localPath = "/tmp/listene/missing-remote-audio.mp3",
                downloadUrl = "https://example.com/remote-audio.mp3"
            )
        )

        assertEquals("https://example.com/remote-audio.mp3", agentFilePreviewSourceForTest(asset))
    }

    @Test
    fun fileLibraryKeepsDistinctRemoteFilesWithoutClientIds() {
        val first = fileItem("lesson.md", "text/markdown", downloadUrl = "https://example.com/a/lesson.md")
            .copy(id = "file_remote_a", updatedAt = 10L)
        val second = fileItem("lesson.md", "text/markdown", downloadUrl = "https://example.com/b/lesson.md")
            .copy(id = "file_remote_b", updatedAt = 20L)

        val merged = mergeUserLibraryItems(localItems = emptyList(), remoteItems = listOf(first, second))

        assertEquals(listOf("file_remote_b", "file_remote_a"), merged.map { it.id })
    }

    @Test
    fun sortsPinnedLibraryItemsBeforeRecentUnpinnedItems() {
        val oldPinned = cardItem("old-pinned", "旧置顶", updatedAt = 10L, pinnedAt = 90L)
        val newUnpinned = cardItem("new-unpinned", "新普通", updatedAt = 300L)
        val newPinned = cardItem("new-pinned", "新置顶", updatedAt = 20L, pinnedAt = 120L)

        val sorted = sortUserLibraryItemsForDisplay(listOf(oldPinned, newUnpinned, newPinned))

        assertEquals(listOf("new-pinned", "old-pinned", "new-unpinned"), sorted.map { it.id })
    }

    @Test
    fun updatesLibraryCardTitleWithoutLosingMicroCard() {
        val item = cardItem("card_rename", "原始卡片")

        val renamed = renameUserLibraryCardItem(item, " 重命名卡片 ")

        assertEquals("重命名卡片", renamed.title)
        assertEquals("重命名卡片", renamed.data.objOrNull("microCard")?.str("title"))
        assertEquals(true, userLibraryMicroCard(renamed)?.nodes?.isNotEmpty())
    }

    @Test
    fun sortsPinnedWorkspacesBeforeRecentUnpinnedWorkspaces() {
        val oldPinned = workspace("ws_old_pinned").copy(updatedAt = 10L, pinnedAt = 90L)
        val newUnpinned = workspace("ws_new_unpinned").copy(updatedAt = 300L)
        val newPinned = workspace("ws_new_pinned").copy(updatedAt = 20L, pinnedAt = 120L)

        val sorted = WorkspaceStore.sortForDisplay(listOf(oldPinned, newUnpinned, newPinned))

        assertEquals(listOf("ws_new_pinned", "ws_old_pinned", "ws_new_unpinned"), sorted.map { it.id })
    }

    @Test
    fun workspaceDirectoryDescriptionSkipsGeneratedLearningContextSummary() {
        val workspace = workspace("ws_generic_summary").copy(
            need = "解释一下现在完成时",
            summary = "已按首句建立学习上下文；后续每条消息都会独立判断是问答、生成素材还是打开练习卡片。",
            plan = workspace("ws_generic_summary").plan.copy(
                summary = "已按首句建立学习上下文；后续每条消息会独立判断是答疑、生成素材还是组装练习卡片。"
            )
        )

        assertEquals("解释一下现在完成时", agentWorkspaceDisplayDescription(workspace))
    }

    @Test
    fun libraryDeleteConsequenceTextUsesCurrentLibraryName() {
        assertEquals("不会再出现在卡片库中", agentLibraryDeleteConsequenceText("卡片库"))
        assertEquals("不会再出现在文件库中", agentLibraryDeleteConsequenceText("文件库"))
    }

    @Test
    fun workspaceDeleteConsequenceTextUsesWorkspaceName() {
        assertEquals("删除后不会再出现在工作区中。", agentWorkspaceDeleteConsequenceText())
    }

    @Test
    fun libraryRowClickDestinationsOpenCardAndFileDetails() {
        assertEquals(AgentLibraryClickDestination.CardDetail, agentLibraryClickDestination("cards"))
        assertEquals(AgentLibraryClickDestination.FilePreview, agentLibraryClickDestination("files"))
    }

    @Test
    fun cardLibraryUsesLongPressMenuWithoutInlineDeleteAction() {
        assertEquals(false, agentLibraryRowShowsInlineDelete("cards"))
        assertEquals(
            listOf("导出题库", "置顶", "重命名", "删除"),
            agentLibraryCardMenuActions(isPinned = false).map { it.label }
        )
        assertEquals(
            listOf("导出题库", "取消置顶", "重命名", "删除"),
            agentLibraryCardMenuActions(isPinned = true).map { it.label }
        )
        assertEquals(
            listOf("export_question_bank", "pin", "rename", "delete"),
            agentLibraryCardMenuActions(isPinned = false).map { it.id }
        )
        assertEquals(
            listOf("pin", "rename", "delete"),
            agentLibraryCardMenuActions(isPinned = false, canExportQuestionBank = false).map { it.id }
        )
    }

    @Test
    fun fileLibraryUsesLongPressMenuWithDownloadExportShareDeleteActions() {
        assertEquals(false, agentLibraryRowShowsInlineDelete("files"))
        assertEquals(
            listOf("下载", "导出", "分享", "删除"),
            agentLibraryFileMenuActions().map { it.label }
        )
        assertEquals(
            listOf("download", "export", "share", "delete"),
            agentLibraryFileMenuActions().map { it.id }
        )
    }

    @Test
    fun remoteFileLinkActionsHaveSuccessNotices() {
        assertEquals("下载链接已打开", agentRemoteFileDownloadSuccessNotice())
        assertEquals("文件链接已打开", agentRemoteFileExportSuccessNotice())
    }


    @Test
    fun libraryChangeKindMatchesCurrentPanelRoute() {
        assertEquals(true, userLibraryChangeMatchesKind("file", "files"))
        assertEquals(true, userLibraryChangeMatchesKind("cards", "card"))
        assertEquals(false, userLibraryChangeMatchesKind("files", "cards"))
    }

    @Test
    fun removingWorkspaceFiltersAssociatedCardAndFileLibraryItems() {
        val card = cardItem("card_ws", "工作区卡片").copy(
            data = buildJsonObject { put("workspaceId", "ws_remove") }
        )
        val file = fileItem("lesson.md", "text/markdown").copy(
            id = "file_ws",
            data = buildJsonObject { put("workspaceId", "ws_remove"); put("mimeType", "text/markdown") }
        )
        val recordOnlyFile = fileItem("record-only.zip", "application/zip").copy(
            id = "file_record_only",
            data = buildJsonObject { put("recordId", "record_remove"); put("mimeType", "application/zip") }
        )
        val keep = cardItem("card_keep", "保留卡片").copy(
            data = buildJsonObject { put("workspaceId", "ws_keep") }
        )

        val purged = removeUserLibraryItemsForWorkspace(
            items = listOf(card, file, recordOnlyFile, keep),
            workspaceId = "ws_remove",
            recordIds = setOf("record_remove")
        )

        assertEquals(listOf("card_ws", "file_ws", "file_record_only"), purged.removed.map { it.id })
        assertEquals(listOf("card_keep"), purged.remaining.map { it.id })
    }

    @Test
    fun drawerFeatureBackReturnsToDrawerHome() {
        assertEquals(AgentDrawerBackTarget.DrawerHome, agentDrawerFeatureBackTarget())
    }

    @Test
    fun drawerSystemBackOnFeaturePageReturnsToDrawerHomeBeforeClosingDrawer() {
        assertEquals(
            AgentDrawerBackTarget.DrawerHome,
            agentDrawerSystemBackTarget(drawerOpen = true, onFeaturePage = true)
        )
        assertEquals(
            AgentDrawerBackTarget.DrawerHome,
            agentDrawerSystemBackTarget(drawerOpen = true, onFeaturePage = false)
        )
    }

    @Test
    fun openingWorkspaceDirectoryTriggersWorkspaceSyncBeforeShowingPage() {
        val events = mutableListOf<String>()

        agentOpenWorkspaceDirectoryWithSync(
            refreshWorkspaces = { events += "refresh" },
            openDirectory = { events += "open" }
        )

        assertEquals(listOf("refresh", "open"), events)
    }

    @Test
    fun returningDrawerFeaturePageTriggersWorkspaceSyncBeforeDrawerHome() {
        val events = mutableListOf<String>()

        agentReturnToDrawerHomeWithSyncForTest(
            refreshWorkspaces = { events += "refresh" },
            returnHome = { events += "home" }
        )

        assertEquals(listOf("refresh", "home"), events)
    }

    @Test
    fun drawerWorkspaceRefreshUsesRemoteSyncInsteadOfLocalReload() {
        val events = mutableListOf<String>()

        agentDispatchWorkspaceRefresh(
            mode = agentDrawerWorkspaceRefreshMode(),
            refreshRemote = { events += "remote" },
            reloadLocal = { events += "local" }
        )

        assertEquals(listOf("remote"), events)
    }

    @Test
    fun userMessageIsInsertedBeforeAgentDecisionForExistingWorkspace() {
        val prior = listOf(AgentChatMessage(1L, AgentChatRole.Agent, "上一条回复"))

        val next = agentMessagesWithOptimisticUserInput(
            priorMessages = prior,
            userText = "帮我生成一个听力训练",
            displayText = null,
            attachments = emptyList(),
            id = 2L
        )

        assertEquals(listOf(AgentChatRole.Agent, AgentChatRole.User), next.map { it.role })
        assertEquals("帮我生成一个听力训练", next.last().text)
    }

    @Test
    fun optimisticUserMessageUsesDisplayTextAndKeepsAttachments() {
        val attachment = AgentInputAttachment(name = "lesson.pdf", mimeType = "application/pdf")

        val next = agentMessagesWithOptimisticUserInput(
            priorMessages = emptyList(),
            userText = "请理解我发送的文件。",
            displayText = "我上传了 lesson.pdf",
            attachments = listOf(attachment),
            id = 8L
        )

        assertEquals(AgentChatRole.User, next.single().role)
        assertEquals("我上传了 lesson.pdf", next.single().text)
        assertEquals(listOf(attachment), next.single().attachments)
    }

    @Test
    fun remoteChatSyncKeepsOptimisticUserMessageAddedWhileLoading() {
        val remote = listOf(AgentChatMessage(1L, AgentChatRole.Agent, "远端旧回复"))
        val optimistic = listOf(
            AgentChatMessage(1L, AgentChatRole.Agent, "远端旧回复"),
            AgentChatMessage(2L, AgentChatRole.User, "帮我生成一个听力训练")
        )

        val merged = mergeAgentChatMessagesForSync(current = optimistic, incoming = remote)

        assertEquals(listOf("远端旧回复", "帮我生成一个听力训练"), merged.map { it.text })
        assertEquals(listOf(AgentChatRole.Agent, AgentChatRole.User), merged.map { it.role })
    }

    @Test
    fun remoteChatSaveRefreshesActiveWorkspaceMemory() {
        val stale = workspace("ws_memory").copy(
            updatedAt = 100L,
            memorySummary = "",
            memory = emptyList()
        )
        val updated = stale.copy(
            updatedAt = 200L,
            memorySummary = "User prefers British pronunciation practice.",
            memory = listOf(
                WorkspaceMemoryEntry(
                    key = "preference:british_pronunciation",
                    type = "preference",
                    content = "British pronunciation practice",
                    importance = 1.0,
                    updatedAt = 200L
                )
            )
        )
        val other = workspace("ws_other")

        val nextWorkspaces = agentWorkspacesAfterRemoteUpdate(listOf(stale, other), updated)
        val nextActive = agentActiveWorkspaceAfterRemoteUpdate(stale, updated)

        assertEquals("User prefers British pronunciation practice.", nextActive?.memorySummary)
        assertEquals(1, nextActive?.memory?.size)
        assertEquals("ws_memory", nextWorkspaces.first().id)
        assertEquals("British pronunciation practice", nextWorkspaces.first().memory.single().content)
    }

    @Test
    fun detectsExplicitQuestionExportRequests() {
        assertEquals(true, agentUserRequestsCurrentQuestionExport("把这套题导出成 docx"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("export the questions as a Word file"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("压缩包里放题目和音频"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("导出听力包"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("把这个听力练习导出"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("export this listening practice"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("打包录音"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("把录音打包发给我"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("只要题目和答案不要音频"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("发给我 ZIP"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("导出 docx"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("只要题不要录音"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("导出刚才那套"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("把刚才那套发给我"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("把这个发我一个包"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("我要文件卡片"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("打包录音和题目"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("导出这个听力 zip"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("export this listening zip"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("export_the_previous_set_for_me"))
        assertEquals(true, agentUserRequestsCurrentQuestionExport("send_previous_set_as_file"))
        assertEquals(false, agentUserRequestsCurrentQuestionExport("再生成三道听力题"))
        assertEquals(false, agentUserRequestsCurrentQuestionExport("帮我分析错题"))
        assertEquals(false, agentUserRequestsCurrentQuestionExport("CET4 word bank cloze practice 5 questions no audio no transcript"))
        assertEquals(
            false,
            agentUserRequestsCurrentQuestionExport(
                "B1 邮件语气转换：把 Can you send me the report today? 和 I need your feedback now. 改成更正式礼貌的英文。只要 2 条 casual -> formal 转换，不要场景对话，不要例句，不要语法讲解，不要评分标准，不要题组。"
            )
        )
        assertEquals(
            false,
            agentUserRequestsCurrentQuestionExport(
                "B1 多空填空卡：请出 1 个三空 cloze/填空练习，只要 cloze，不要选择题组，不要音频，只要题目和答案不要音频。"
            )
        )
        assertEquals(
            false,
            agentUserRequestsCurrentQuestionExport(
                "B1 写作练习卡：请让我写一封 80-100 词英文邮件，只要写作练习，必须能输入正文，不要 question_set。"
            )
        )
    }

    @Test
    fun classifiesCurrentQuestionExportFormatFromNaturalLanguage() {
        assertEquals(
            AgentCurrentQuestionExportFormat.ZipWithAudio,
            agentCurrentQuestionExportFormat("导出题目")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.ZipWithAudio,
            agentCurrentQuestionExportFormat("把当前题目导出")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.ZipWithAudio,
            agentCurrentQuestionExportFormat("这套题打包成 zip 带录音")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.ZipWithAudio,
            agentCurrentQuestionExportFormat("压缩包里放题目和音频")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.ZipWithAudio,
            agentCurrentQuestionExportFormat("导出听力包")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.ZipWithAudio,
            agentCurrentQuestionExportFormat("打包录音")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.DocxWithoutAudio,
            agentCurrentQuestionExportFormat("把当前题目导出成 docx")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.DocxWithoutAudio,
            agentCurrentQuestionExportFormat("把当前题目导出成 Word")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.DocxWithoutAudio,
            agentCurrentQuestionExportFormat("只要题目和答案不要音频")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.ZipWithAudio,
            agentCurrentQuestionExportFormat("发给我 ZIP")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.DocxWithoutAudio,
            agentCurrentQuestionExportFormat("导出 docx")
        )
        assertEquals(
            AgentCurrentQuestionExportFormat.DocxWithoutAudio,
            agentCurrentQuestionExportFormat("只要题不要录音")
        )
    }

    @Test
    fun currentQuestionExportReplyAndAttachmentTypeFollowRequestedFormat() {
        val record = record("record_export_format")

        val zip = requireNotNull(agentCurrentQuestionExportSpec(record, "导出听力包"))
        assertEquals("校园对话.zip", zip.fileName)
        assertEquals("application/zip", zip.mimeType)
        assertEquals("已导出当前听力题 ZIP 包，包含题目、原文和听力录音。", agentCurrentQuestionExportSuccessReply(zip.format))

        val docx = requireNotNull(agentCurrentQuestionExportSpec(record, "把当前题目导出成 Word，只要题目和答案不要音频"))
        assertEquals("校园对话.docx", docx.fileName)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx.mimeType)
        assertEquals("已导出当前听力题 Word 文档，包含题目和答案，不包含录音。", agentCurrentQuestionExportSuccessReply(docx.format))
    }

    @Test
    fun currentQuestionExportRequiresRecordWithQuestions() {
        assertEquals(null, agentCurrentQuestionExportSpec(null, "导出听力包"))

        val noQuestions = record("record_without_questions").copy(
            content = record("record_without_questions").content.copy(questions = emptyList())
        )

        assertEquals(null, agentCurrentQuestionExportSpec(noQuestions, "把当前题目导出成 docx"))
    }

    @Test
    fun exportRequestWithoutCurrentRecordGetsUserFacingMessage() {
        assertEquals(
            "当前工作区还没有可导出的题目。",
            agentCurrentQuestionExportUnavailableReply(null)
        )
    }

    @Test
    fun exportRequestWithCurrentRecordBuildsZipAndDocxSpecs() {
        val record = record("record_export_branches")

        val zip = requireNotNull(agentCurrentQuestionExportSpec(record, "导出听力练习"))
        val docx = requireNotNull(agentCurrentQuestionExportSpec(record, "发给我 docx"))

        assertEquals(AgentCurrentQuestionExportFormat.ZipWithAudio, zip.format)
        assertEquals("校园对话.zip", zip.fileName)
        assertEquals("application/zip", zip.mimeType)
        assertEquals(AgentCurrentQuestionExportFormat.DocxWithoutAudio, docx.format)
        assertEquals("校园对话.docx", docx.fileName)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx.mimeType)
    }






    @Test
    fun explicitListeningOrZipExportRequestsPreferListeningBranch() {
        assertTrue(agentQuestionExportShouldPreferListening("导出这个听力 zip"))
        assertTrue(agentQuestionExportShouldPreferListening("打包录音和题目"))
        assertTrue(agentQuestionExportShouldPreferListening("export this listening package"))
        assertFalse(agentQuestionExportShouldPreferListening("导出刚才那套题库 docx"))
    }


    @Test
    fun aiAnalysisRequestUsesLatestStoredRevealedAnswers() {
        val stale = record("record_ai").copy(
            selectedAnswers = emptyMap(),
            answersRevealed = false
        )
        val stored = record("record_ai").copy(
            selectedAnswers = mapOf(0 to 0),
            answersRevealed = true
        )

        val latest = agentAnalysisRecordForRequest(stored, stale)

        assertEquals(true, latest.answersRevealed)
        assertEquals(mapOf(0 to 0), latest.selectedAnswers)
        assertEquals(null, agentAiReviewChatStatusMessage(latest))
    }

    @Test
    fun aiAnalysisRequestKeepsRequestedRevealedStateWhenStoredRecordIsMissing() {
        val requested = record("record_ai_missing").copy(
            selectedAnswers = mapOf(0 to 0),
            answersRevealed = true
        )

        val latest = agentAnalysisRecordForRequest(null, requested)

        assertEquals(true, latest.answersRevealed)
        assertEquals(mapOf(0 to 0), latest.selectedAnswers)
    }

    @Test
    fun aiAnalysisChatStatusOnlyWarnsWhenAnswersAreNotRevealed() {
        assertEquals(null, agentAiReviewChatStatusMessage(record("record_revealed").copy(answersRevealed = true)))
        assertEquals(
            "请先在练习页核对答案后再使用 AI 分析。",
            agentAiReviewChatStatusMessage(record("record_not_revealed").copy(answersRevealed = false))
        )
    }

    @Test
    fun aiAnalysisEntryReturnsPromptForUnrevealedRecord() {
        val decision = agentAiReviewEntryDecision(
            stored = null,
            requested = record("record_not_revealed").copy(answersRevealed = false),
            existingMessages = emptyList()
        )

        assertEquals(false, decision.canAnalyze)
        assertEquals("请先在练习页核对答案后再使用 AI 分析。", decision.statusMessage)
        assertEquals(true, decision.shouldAppendStatusMessage)
    }

    @Test
    fun aiAnalysisEntryAllowsRevealedRecordWithoutRepeatingPrompt() {
        val status = "请先在练习页核对答案后再使用 AI 分析。"
        val decision = agentAiReviewEntryDecision(
            stored = null,
            requested = record("record_revealed").copy(
                selectedAnswers = mapOf(0 to 0),
                answersRevealed = true
            ),
            existingMessages = listOf(AgentChatMessage(1L, AgentChatRole.Agent, status))
        )

        assertEquals(true, decision.canAnalyze)
        assertEquals(null, decision.statusMessage)
        assertEquals(false, decision.shouldAppendStatusMessage)
        assertEquals(true, decision.record.answersRevealed)
        assertEquals(mapOf(0 to 0), decision.record.selectedAnswers)
    }

    @Test
    fun aiAnalysisResultReplyReturnsChatCardOnceForWorkspace() {
        val workspace = workspace("ws_analysis").copy(linkedRecordIds = listOf("record_analysis"))
        val record = record("record_analysis").copy(
            analysisResult = AnalysisResult(summary = "错在关键词定位。")
        )
        val reply = AgentCardEngine.analysisReady(record)

        val first = agentAnalysisResultChatAppendDecision(
            workspace = workspace,
            recordId = record.id,
            resultSummary = record.analysisResult?.summary.orEmpty(),
            activeViewModelWorkspaceId = null,
            existingMessages = emptyList(),
            reply = reply
        )
        val second = agentAnalysisResultChatAppendDecision(
            workspace = workspace,
            recordId = record.id,
            resultSummary = record.analysisResult?.summary.orEmpty(),
            activeViewModelWorkspaceId = null,
            existingMessages = listOf(AgentChatMessage(1L, AgentChatRole.Agent, reply.text)),
            reply = reply
        )

        assertEquals(true, first.shouldAppend)
        assertEquals(reply.text, first.reply?.text)
        // 旧渲染器退役 C2：分析完成是纯文本通知（摘要/薄弱点/建议进文案），不再造 cardSpec。
        assertTrue(reply.text.contains("错在关键词定位。"))
        assertEquals(false, second.shouldAppend)
    }

    @Test
    fun aiAnalysisStatusPromptIsNotDuplicatedConsecutively() {
        val status = "请先在练习页核对答案后再使用 AI 分析。"

        assertEquals(
            false,
            agentShouldAppendAiReviewStatusMessage(
                status,
                listOf(AgentChatMessage(1L, AgentChatRole.Agent, status))
            )
        )
        assertEquals(
            true,
            agentShouldAppendAiReviewStatusMessage(
                status,
                listOf(AgentChatMessage(1L, AgentChatRole.Agent, "上一条回复"))
            )
        )
    }

    @Test
    fun detectsNaturalAiAnalysisRequestsForLocalHandling() {
        assertEquals(true, agentUserRequestsCurrentAiAnalysis("帮我分析错题"))
        assertEquals(true, agentUserRequestsCurrentAiAnalysis("复盘一下我错哪了"))
        assertEquals(true, agentUserRequestsCurrentAiAnalysis("analyze my wrong answers"))
        assertEquals(false, agentUserRequestsCurrentAiAnalysis("生成一个错题相关的新练习"))
        assertEquals(false, agentUserRequestsCurrentAiAnalysis("再来三道听力题"))
    }

    @Test
    fun showsChatAnalysisLoadingOnlyForCurrentAnalysisRecordWorkspace() {
        val record = record("record_analysis")
        val workspace = workspace("ws_analysis").copy(linkedRecordIds = listOf(record.id))
        val otherWorkspace = workspace("ws_other").copy(linkedRecordIds = listOf("other_record"))

        assertEquals(
            true,
            agentShouldShowChatAnalysisLoading(
                analysisInProgressRecordId = record.id,
                activeWorkspace = workspace,
                activeViewModelWorkspaceId = workspace.id
            )
        )
        assertEquals(
            false,
            agentShouldShowChatAnalysisLoading(
                analysisInProgressRecordId = null,
                activeWorkspace = workspace,
                activeViewModelWorkspaceId = workspace.id
            )
        )
        assertEquals(
            false,
            agentShouldShowChatAnalysisLoading(
                analysisInProgressRecordId = record.id,
                activeWorkspace = otherWorkspace,
                activeViewModelWorkspaceId = otherWorkspace.id
            )
        )
        assertEquals(
            false,
            agentShouldShowChatAnalysisLoading(
                analysisInProgressRecordId = "",
                activeWorkspace = workspace,
                activeViewModelWorkspaceId = workspace.id
            )
        )
        assertEquals(false, agentShouldShowChatThinkingLoading(loading = true, analysisLoading = true))
        assertEquals(true, agentShouldShowChatThinkingLoading(loading = true, analysisLoading = false))
    }

    @Test
    fun hidesBottomAnalysisLoadingWhenLatestAgentMessageAlreadyShowsAnalysisProgress() {
        val record = record("record_analysis")
        val workspace = workspace("ws_analysis").copy(linkedRecordIds = listOf(record.id))

        assertEquals(
            false,
            agentShouldShowChatAnalysisLoading(
                analysisInProgressRecordId = record.id,
                activeWorkspace = workspace,
                activeViewModelWorkspaceId = workspace.id,
                existingMessages = listOf(
                    AgentChatMessage(1L, AgentChatRole.Agent, "AI 正在分析你的答题结果")
                )
            )
        )
        assertEquals(
            false,
            agentShouldShowChatAnalysisLoading(
                analysisInProgressRecordId = record.id,
                activeWorkspace = workspace,
                activeViewModelWorkspaceId = workspace.id,
                existingMessages = listOf(
                    AgentChatMessage(1L, AgentChatRole.Agent, "AI 正在分析中...")
                )
            )
        )
        assertEquals(
            true,
            agentShouldShowChatAnalysisLoading(
                analysisInProgressRecordId = record.id,
                activeWorkspace = workspace,
                activeViewModelWorkspaceId = workspace.id,
                existingMessages = listOf(
                    AgentChatMessage(1L, AgentChatRole.Agent, "上一条回复")
                )
            )
        )
    }

    @Test
    fun materialReadyFollowupPromptDoesNotExposeInternalCardInstructions() {
        val workspace = workspace("ws_material")
        val prompt = agentMaterialReadyFollowupPrompt(record("record_material"), workspace)

        assertEquals(false, agentCardTextContainsInternalInstructionLeak(prompt))
        assertEquals(false, prompt.contains("最近对话和用户目标"))
        assertEquals(false, prompt.contains("只实时渲染当前需要"))
    }

    @Test
    fun analysisFollowupPromptDoesNotExposeInternalCardInstructions() {
        val prompt = agentAnalysisReadyFollowupPrompt("错在细节定位。")

        assertEquals(false, agentCardTextContainsInternalInstructionLeak(prompt))
        assertEquals(false, prompt.contains("最近对话"))
        assertEquals(false, prompt.contains("捆绑多个练习模式"))
    }

    @Test
    fun detectsInternalInstructionLeakInVisibleCardText() {
        assertEquals(
            true,
            agentCardTextContainsInternalInstructionLeak("素材已生成：Job Interview Practice。请根据当前工作区、最近对话和用户目标判断下一步，只实时渲染当前需要的一张组件卡片。")
        )
        assertEquals(false, agentCardTextContainsInternalInstructionLeak("素材已生成，可以播放音频、查看原文并答题。"))
    }

    @Test
    fun appendingPlainStatusClearsPreviousCardAnchor() {
        assertEquals(null, agentCardAnchorAfterAppend(previousAnchor = 42L, appendedCard = null))
        assertEquals(42L, agentCardAnchorAfterAppend(previousAnchor = null, appendedCard = 42L))
    }

    @Test
    fun localLibraryUpsertKeepsGeneratedFileVisibleWhenRemoteSyncFails() {
        val file = UserLibraryItem(
            id = "file_zip",
            kind = "files",
            title = "Ordering_Coffee.zip",
            summary = "application/zip",
            data = buildJsonObject { put("workspaceId", "ws_active"); put("mimeType", "application/zip"); put("sizeBytes", 808445L); put("localPath", "/tmp/Ordering_Coffee.zip"); put("generated", true) },
            createdAt = 10L,
            updatedAt = 10L
        )

        val next = upsertUserLibraryItem(emptyList(), file)
        val asset = userLibraryFileAsset(next.single())

        assertEquals("Ordering_Coffee.zip", asset.name)
        assertEquals("application/zip", asset.mimeType)
        assertEquals("/tmp/Ordering_Coffee.zip", asset.localPath)
        assertEquals(true, asset.generated)
    }

    @Test
    fun exportedListeningPracticeAttachmentBecomesFileLibraryItem() {
        val attachment = AgentInputAttachment(
            name = "校园对话.zip",
            mimeType = "application/zip",
            sizeBytes = 4096L,
            localPath = "/tmp/listene/校园对话.zip",
            generated = true
        )
        val data = userLibraryGeneratedFileData("ws_active", attachment)
            .with("recordId", "record_export_questions")
            .with("downloadUrl", "")
        val file = UserLibraryItem(
            id = "file_exported_listening_zip",
            kind = "files",
            title = attachment.name,
            summary = attachment.mimeType,
            data = data,
            createdAt = 10L,
            updatedAt = 10L
        )

        val items = upsertUserLibraryItem(emptyList(), file)
        val asset = userLibraryFileAsset(items.single())

        assertEquals("校园对话.zip", items.single().title)
        assertEquals("files", normalizeUserLibraryKind(items.single().kind))
        assertEquals("ws_active", userLibraryWorkspaceId(items.single()))
        assertEquals("record_export_questions", userLibraryRecordId(items.single()))
        assertEquals("校园对话.zip", items.single().data.str("name"))
        assertEquals("/tmp/listene/校园对话.zip", asset.localPath)
        assertEquals("application/zip", asset.mimeType)
        assertEquals(true, asset.generated)
        assertEquals(UserLibraryFilePreviewMode.External, userLibraryFilePreviewMode(items.single()))
    }

    @Test
    fun exportedCardQuestionBankFileKeepsSourceCardMetadata() {
        val exported = File.createTempFile("Reading_Practice_question_bank", ".docx").apply {
            writeText("question bank")
            deleteOnExit()
        }
        val attachment = AgentInputAttachment(
            name = "Reading_Practice_question_bank.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            sizeBytes = exported.length(),
            localPath = exported.absolutePath,
            generated = true
        )
        val data = userLibraryGeneratedFileData(
            workspaceId = "ws_card_export",
            attachment = attachment,
            extraData = buildJsonObject { put("sourceKind", "card_question_bank"); put("sourceCardId", "card_reading_practice"); put("sourceCardTitle", "Reading Practice"); put("cardTitle", "Reading Practice") }
        )
        val item = UserLibraryItem(
            id = "file_card_question_bank",
            kind = "files",
            title = attachment.name,
            summary = attachment.mimeType,
            data = data,
            createdAt = 10L,
            updatedAt = 10L
        )

        assertEquals("ws_card_export", userLibraryWorkspaceId(item))
        assertEquals("card_question_bank", item.data.str("sourceKind"))
        assertEquals("card_reading_practice", item.data.str("sourceCardId"))
        assertEquals("Reading Practice", item.data.str("sourceCardTitle"))
        assertEquals("Reading_Practice_question_bank.docx", userLibraryFileAsset(item).name)
        assertEquals(UserLibraryFilePreviewMode.Text, userLibraryFilePreviewMode(item))
    }

    @Test
    fun exportedChatQuestionBankFileKeepsSourceMessageMetadata() {
        val exported = File.createTempFile("Travel_Practice_question_bank", ".docx").apply {
            writeText("question bank")
            deleteOnExit()
        }
        val attachment = AgentInputAttachment(
            name = "Travel_Practice_question_bank.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            sizeBytes = exported.length(),
            localPath = exported.absolutePath,
            generated = true,
            sourceKind = "chat_question_bank",
            sourceCardTitle = "Travel Practice",
            sourceMessageId = 42L,
            copyToDownloads = true
        )
        val data = userLibraryGeneratedFileData(
            workspaceId = "ws_chat_export",
            attachment = attachment,
            extraData = buildJsonObject { put("sourceKind", attachment.sourceKind); put("sourceCardTitle", attachment.sourceCardTitle); put("sourceMessageId", attachment.sourceMessageId) }
        )
        val item = UserLibraryItem(
            id = "file_chat_question_bank",
            kind = "files",
            title = attachment.name,
            summary = attachment.mimeType,
            data = data,
            createdAt = 10L,
            updatedAt = 10L
        )

        assertEquals("ws_chat_export", userLibraryWorkspaceId(item))
        assertEquals("chat_question_bank", item.data.str("sourceKind"))
        assertEquals("Travel Practice", item.data.str("sourceCardTitle"))
        assertEquals(42L, item.data.long("sourceMessageId"))
        assertEquals("Travel_Practice_question_bank.docx", userLibraryFileAsset(item).name)
        assertEquals(UserLibraryFilePreviewMode.Text, userLibraryFilePreviewMode(item))
    }

    @Test
    fun localAndRemoteLibraryItemsPreferNewestLocalGeneratedFile() {
        val remote = fileItem(
            title = "Ordering_Coffee.zip",
            mimeType = "application/zip",
            localPath = ""
        ).copy(
            id = "remote_file",
            data = buildJsonObject { put("clientId", "file-client-merge"); put("mimeType", "application/zip") },
            updatedAt = 5L
        )
        val local = remote.copy(
            id = "local_file",
            data = remote.data
                .with("localPath", "/tmp/Ordering_Coffee.zip")
                .with("generated", true),
            updatedAt = 10L
        )

        val merged = mergeUserLibraryItems(localItems = listOf(local), remoteItems = listOf(remote))

        assertEquals(1, merged.size)
        assertEquals("/tmp/Ordering_Coffee.zip", userLibraryFileAsset(merged.single()).localPath)
    }

    @Test
    fun newerRemoteFileWithoutLocalPathDoesNotHideLocalGeneratedFilePath() {
        val local = fileItem(
            title = "Ordering_Coffee.zip",
            mimeType = "application/zip",
            localPath = "/tmp/Ordering_Coffee.zip"
        ).copy(
            id = "local_file",
            data = buildJsonObject { put("clientId", "file-client-1"); put("mimeType", "application/zip"); put("localPath", "/tmp/Ordering_Coffee.zip"); put("generated", true) },
            updatedAt = 10L
        )
        val remote = local.copy(
            id = "remote_file",
            data = buildJsonObject { put("clientId", "file-client-1"); put("mimeType", "application/zip"); put("localPath", ""); put("generated", true) },
            updatedAt = 20L
        )

        val merged = mergeUserLibraryItems(localItems = listOf(local), remoteItems = listOf(remote))

        assertEquals(1, merged.size)
        assertEquals("/tmp/Ordering_Coffee.zip", userLibraryFileAsset(merged.single()).localPath)
        assertEquals("", userLibraryFileAsset(merged.single()).previewUrl)
        assertEquals(20L, merged.single().updatedAt)
    }

    @Test
    fun remoteSingularFileKindMergesWithLocalPluralFileKind() {
        val remote = fileItem(
            title = "Ordering_Coffee.zip",
            mimeType = "application/zip",
            localPath = ""
        ).copy(
            id = "remote_file",
            kind = "file",
            data = buildJsonObject { put("clientId", "file-client-kind-merge"); put("mimeType", "application/zip") },
            updatedAt = 5L
        )
        val local = remote.copy(
            id = "local_file",
            kind = "files",
            data = remote.data
                .with("localPath", "/tmp/Ordering_Coffee.zip")
                .with("generated", true),
            updatedAt = 10L
        )

        val merged = mergeUserLibraryItems(localItems = listOf(local), remoteItems = listOf(remote))

        assertEquals(1, merged.size)
        assertEquals("files", normalizeUserLibraryKind(merged.single().kind))
        assertEquals("/tmp/Ordering_Coffee.zip", userLibraryFileAsset(merged.single()).localPath)
    }

    @Test
    fun stableLibraryKeyTreatsSingularAndPluralKindsAsSameItem() {
        val remote = cardItem(id = "remote_card", title = "Daily practice", updatedAt = 5L)
            .copy(kind = "card")
        val local = remote.copy(id = "local_card", kind = "cards", updatedAt = 10L)

        val next = upsertUserLibraryItem(listOf(remote), local)

        assertEquals(1, next.size)
        assertEquals("local_card", next.single().id)
        assertEquals(userLibraryItemStableKey(remote), userLibraryItemStableKey(local))
    }

    @Test
    fun libraryMergeFiltersBrokenCardsFromHistoryAndRemote() {
        val broken = UserLibraryItem(
            id = "broken_card",
            kind = "cards",
            title = "AI 卡片已重置",
            summary = "bad",
            data = buildJsonObject { put("workspaceId", "ws_active") },
            createdAt = 1L,
            updatedAt = 1L
        )
        val good = cardItem(id = "good_card", title = "词汇练习", updatedAt = 2L)

        val merged = mergeUserLibraryItems(localItems = listOf(broken), remoteItems = listOf(good))

        assertEquals(listOf("good_card"), merged.map { it.id })
    }

    @Test
    fun stableLibraryKeyUsesClientIdSoOfflineRenameDoesNotDuplicateCard() {
        val remote = cardItem(id = "remote_card", title = "Daily practice", updatedAt = 5L)
            .copy(data = cardItem(id = "remote_card", title = "Daily practice").data.with("clientId", "client_1"))
        val renamed = renameUserLibraryCardItem(remote, "Daily practice renamed")

        assertEquals(userLibraryItemStableKey(remote), userLibraryItemStableKey(renamed))
    }

    @Test
    fun retryUsesLastAgentRequestWhenInputWasClearedAfterFailure() {
        val last = AgentRetryRequest(
            message = "练习英语四级选词填空",
            attachments = emptyList(),
            displayText = null
        )

        val retry = agentRetryRequestForError(input = "", lastRequest = last, messages = emptyList())

        assertEquals("练习英语四级选词填空", retry?.message)
        assertEquals(true, retry?.reusesVisibleUserMessage)
    }




    @Test
    fun questionOptionsAreDisabledAfterReveal() {
        assertEquals(true, agentQuestionOptionEnabled(revealed = false))
        assertEquals(false, agentQuestionOptionEnabled(revealed = true))
    }

    @Test
    fun retryDoesNotSendCurrentInputAsANewAgentMessage() {
        val retry = agentRetryRequestForError(input = "重新生成一套题", lastRequest = null, messages = emptyList())

        assertEquals(null, retry)
        assertEquals("没有可重试的上一条请求，请重新输入需求。", agentRetryUnavailableMessage())
    }

    @Test
    fun retryUsesLatestVisibleUserMessageWhenRuntimeMemoryWasLost() {
        val attachment = AgentInputAttachment(name = "cet4.txt", mimeType = "text/plain")
        val messages = listOf(
            AgentChatMessage(1L, AgentChatRole.Agent, "可以开始。"),
            AgentChatMessage(2L, AgentChatRole.User, "练习英语四级选词填空", attachments = listOf(attachment))
        )

        val retry = agentRetryRequestForError(input = "", lastRequest = null, messages = messages)

        assertEquals("练习英语四级选词填空", retry?.message)
        assertEquals(listOf(attachment), retry?.attachments)
        assertEquals(true, retry?.reusesVisibleUserMessage)
    }

    @Test
    fun optimisticRetryCanReuseVisibleUserMessageWithoutAppendingDuplicate() {
        val prior = listOf(
            AgentChatMessage(1L, AgentChatRole.User, "练习英语四级选词填空")
        )

        val next = agentMessagesWithOptimisticUserInput(
            priorMessages = prior,
            userText = "练习英语四级选词填空",
            displayText = null,
            attachments = emptyList(),
            id = 2L,
            appendUserMessage = false
        )

        assertEquals(1, next.size)
        assertEquals("练习英语四级选词填空", next.single().text)
    }

    @Test
    fun errorRetryButtonUsesLastAgentRequestInsteadOfSendingRetryText() {
        val last = AgentRetryRequest(message = "练习英语四级选词填空")
        val retry = requireNotNull(
            agentRetryRequestForError(
                input = "重试",
                lastRequest = last,
                messages = listOf(AgentChatMessage(1L, AgentChatRole.User, "练习英语四级选词填空"))
            )
        )

        val next = agentMessagesWithOptimisticUserInput(
            priorMessages = listOf(AgentChatMessage(1L, AgentChatRole.User, "练习英语四级选词填空")),
            userText = retry.message,
            displayText = retry.displayText,
            attachments = retry.attachments,
            id = 2L,
            appendUserMessage = !retry.reusesVisibleUserMessage
        )

        assertEquals("练习英语四级选词填空", retry.message)
        assertEquals(true, retry.reusesVisibleUserMessage)
        assertEquals(listOf("练习英语四级选词填空"), next.map { it.text })
    }

    @Test
    fun retryOnlyInputReusesLatestVisibleUserMessageWithoutAppendingDuplicate() {
        val prior = listOf(
            AgentChatMessage(1L, AgentChatRole.User, "练习英语四级选词填空")
        )
        val retry = requireNotNull(
            agentRetryRequestForError(
                input = "重试生成这张卡",
                lastRequest = null,
                messages = prior
            )
        )

        val next = agentMessagesWithOptimisticUserInput(
            priorMessages = prior,
            userText = retry.message,
            displayText = retry.displayText,
            attachments = retry.attachments,
            id = 2L,
            appendUserMessage = !retry.reusesVisibleUserMessage
        )

        assertEquals("练习英语四级选词填空", retry.message)
        assertEquals(true, retry.reusesVisibleUserMessage)
        assertEquals(listOf("练习英语四级选词填空"), next.map { it.text })
    }

    @Test
    fun retryOnlyInputWithoutContextReturnsNoRequestAndHasUserFacingMessage() {
        val retry = agentRetryRequestForError(input = "重连", lastRequest = null, messages = emptyList())

        assertEquals(null, retry)
        assertEquals("没有可重试的上一条请求，请重新输入需求。", agentRetryUnavailableMessage())
    }

    @Test
    fun agentConnectionFailureRetryIsReconnectNotAgentResend() {
        assertEquals("重新连接", agentConnectionRetryButtonText())
    }

    @Test
    fun retryOnlyPromptClassificationDoesNotCatchRealUserRequests() {
        assertEquals(true, agentIsRetryOnlyPrompt("重试"))
        assertEquals(true, agentIsRetryOnlyPrompt("retry"))
        assertEquals(true, agentIsRetryOnlyPrompt("重试生成这张卡"))
        assertEquals(false, agentIsRetryOnlyPrompt("重新生成一套四级选词填空"))
    }

    @Test
    fun interruptionMessageFallsBackWhenExceptionHasNoMessage() {
        assertEquals("Agent 处理失败，请重试。", agentInterruptionMessage(RuntimeException()))
    }

    @Test
    fun cet4WordBankClozePracticeIsNotListeningMaterialRequest() {
        assertEquals(false, AgentConversationService.wantsNewListeningPractice("CET4 word bank cloze practice"))
        assertEquals(false, AgentConversationService.wantsNewListeningPractice("我想要练习英语四级的选词填空"))
    }

    // 本地固定题型引擎已退役（旧渲染器退役 #2）：sentence_builder/cloze/writing 的正则劫持与本地兜底卡
    // 均已删除——练习卡一律由服务端微元引擎生成，decisionFromObj 不再改写远端决策。

    @Test
    fun currentRecordSummaryIncludesAnswersForQuestionExport() {
        val record = record("record_export_questions").copy(
            content = record("record_export_questions").content.copy(
                title = "At the Airport Check-in Counter",
                questions = listOf(
                    Question(
                        questionText = "What does David need to show first?",
                        options = listOf("His boarding pass", "His passport", "His hotel key", "His phone"),
                        correctAnswer = 1,
                        explanation = "He is asked to show his passport first."
                    )
                )
            )
        )

        val summary = AgentConversationService.currentRecordSummaryJson(record)
        val question = summary.arrOrNull("questions")!!.objOrNull(0)!!

        assertEquals("At the Airport Check-in Counter", summary.str("title"))
        assertEquals("What does David need to show first?", question.str("questionText"))
        assertEquals("His passport", question.arrOrNull("options")!!.str(1))
        assertEquals(1, question.int("correctAnswer"))
        assertEquals("He is asked to show his passport first.", question.str("explanation"))
    }

    @Test
    fun workspaceMemoryPayloadKeepsStructuredEntries() {
        val workspace = workspace("ws_memory").copy(
            memorySummary = "User practiced present perfect.",
            memory = listOf(
                WorkspaceMemoryEntry(
                    key = "weakness:already_yet",
                    type = "weakness",
                    content = "Confuses already and yet.",
                    importance = 0.9,
                    updatedAt = 1000L
                )
            )
        )

        val memory = agentWorkspaceMemoryPayloadJson(workspace)

        assertEquals(1, memory.size)
        val first = memory.objOrNull(0)!!
        assertEquals("weakness:already_yet", first.str("key"))
        assertEquals("weakness", first.str("type"))
        assertEquals("Confuses already and yet.", first.str("content"))
        assertEquals(0.9, first.double("importance"), 0.001)
        assertEquals(1000L, first.long("updatedAt"))
    }

    @Test
    fun materialDifficultyUsesCurrentUserRequestBeforeWorkspaceDefault() {
        val plan = workspace("ws_difficulty").plan.copy(difficulty = "普通")

        assertEquals("困难", agentMaterialDifficulty("三人商务会议对话，困难难度，出5道题", plan))
        assertEquals("简单", agentMaterialDifficulty("生成基础 A2 校园对话，3道题", plan))
        assertEquals("普通", agentMaterialDifficulty("生成三人校园讨论，5道题", plan))
    }

    @Test
    fun createsFreshWorkspaceForDifferentListeningMaterialRequest() {
        val parkRecord = record("record_park").copy(
            scene = "A1 Park Dialogue Listening",
            content = record("record_park").content.copy(
                title = "A1 Park Dialogue Listening",
                script = "Guide: Welcome to the park.\nVisitor: It is beautiful."
            )
        )
        val parkWorkspace = workspace("ws_park").copy(
            title = "A1 Park Dialogue Listening",
            need = "A1 Park Dialogue Listening",
            linkedRecordIds = listOf(parkRecord.id),
            plan = workspace("ws_park").plan.copy(
                title = "A1 Park Dialogue Listening",
                materialPrompt = "park dialogue",
                summary = "A1 park dialogue with two speakers"
            )
        )

        assertEquals(
            true,
            agentShouldCreateWorkspaceForListeningRequest(
                currentWorkspace = parkWorkspace,
                materialNeed = "B1 listening material restaurant reservation dialogue 3 speakers exactly 4 questions normal speed British accent only audio transcript and questions no extra cards",
                currentRecord = parkRecord
            )
        )
        assertEquals(
            false,
            agentShouldCreateWorkspaceForListeningRequest(
                currentWorkspace = parkWorkspace,
                materialNeed = "generate another A1 park dialogue listening with 3 questions",
                currentRecord = parkRecord
            )
        )
        assertEquals(
            false,
            agentShouldCreateWorkspaceForListeningRequest(
                currentWorkspace = null,
                materialNeed = "restaurant reservation listening material",
                currentRecord = null
            )
        )
    }

    @Test
    fun createsFreshWorkspaceWhenCurrentRecordTopicDiffersFromNewListeningRequest() {
        val restaurantRecord = record("record_restaurant").copy(
            scene = "restaurant reservation dialogue",
            content = record("record_restaurant").content.copy(
                title = "Booking a Table at The Olive Branch",
                script = "Alice: Thank you for calling the restaurant.\nBen: I want to make a reservation."
            )
        )
        val parkWorkspaceWithRestaurantRecord = workspace("ws_park").copy(
            title = "A1 Park Dialogue Listening",
            need = "generate A1 listening material at a park 2 speakers exactly 2 questions",
            linkedRecordIds = listOf("record_park", restaurantRecord.id),
            plan = workspace("ws_park").plan.copy(
                title = "A1 Park Dialogue Listening",
                materialPrompt = "park dialogue"
            )
        )

        assertEquals(
            true,
            agentShouldCreateWorkspaceForListeningRequest(
                currentWorkspace = parkWorkspaceWithRestaurantRecord,
                materialNeed = "fixverifyws1 generate B1 listening material hotel check in dialogue 3 speakers exactly 4 questions normal speed British accent only audio transcript and questions no extra cards",
                currentRecord = restaurantRecord
            )
        )
    }

    @Test
    fun createsFreshWorkspaceForDifferentListeningRequestEvenWhenActiveWorkspaceLinksAreStale() {
        val staleParkWorkspace = workspace("ws_stale_park").copy(
            title = "A1 Park Dialogue Listening",
            need = "generate A1 listening material at a park 2 speakers exactly 2 questions",
            linkedRecordIds = emptyList(),
            events = emptyList(),
            currentStep = "practice",
            plan = workspace("ws_stale_park").plan.copy(
                contentType = "dialogue",
                materialPrompt = "park dialogue"
            )
        )

        assertEquals(
            true,
            agentShouldCreateWorkspaceForListeningRequest(
                currentWorkspace = staleParkWorkspace,
                materialNeed = "fixverifyws2 generate B1 listening material airport security dialogue 3 speakers exactly 4 questions normal speed British accent only audio transcript and questions no extra cards",
                currentRecord = null
            )
        )
    }

    @Test
    fun ignoresGenericCardTokenWhenComparingListeningTopics() {
        val pharmacyRecord = record("record_pharmacy").copy(
            scene = "pharmacy medicine advice dialogue",
            content = record("record_pharmacy").content.copy(
                title = "At the Pharmacy: Advice for a Cold",
                script = "Sarah: I am worried about this cold.\nTom: This medicine might help."
            )
        )
        val pharmacyWorkspace = workspace("ws_pharmacy").copy(
            title = "A1 Park Dialogue Listening",
            need = "generate A1 listening material at a park",
            linkedRecordIds = listOf(pharmacyRecord.id),
            currentStep = "practice",
            plan = workspace("ws_pharmacy").plan.copy(
                contentType = "dialogue",
                materialPrompt = "park dialogue"
            )
        )

        assertEquals(
            true,
            agentShouldCreateWorkspaceForListeningRequest(
                currentWorkspace = pharmacyWorkspace,
                materialNeed = "generate B1 listening material at a library about a lost card 3 speakers exactly 4 questions normal speed British accent only audio transcript and questions no extra cards",
                currentRecord = pharmacyRecord
            )
        )
    }

    @Test
    fun explicitFreshListeningMaterialRequestCreatesWorkspaceUnlessUserAsksForCurrentWorkspace() {
        val currentRecord = record("record_current")
        val listeningWorkspace = workspace("ws_listening").copy(
            linkedRecordIds = listOf(currentRecord.id),
            currentStep = "practice",
            plan = workspace("ws_listening").plan.copy(
                contentType = "dialogue",
                materialPrompt = "park dialogue"
            )
        )

        assertEquals(
            true,
            agentShouldCreateFreshWorkspaceForExplicitListeningRequest(
                currentWorkspace = listeningWorkspace,
                message = "generate B1 listening material museum directions dialogue 3 speakers exactly 4 questions",
                currentRecord = currentRecord
            )
        )
        assertEquals(
            false,
            agentShouldCreateFreshWorkspaceForExplicitListeningRequest(
                currentWorkspace = listeningWorkspace,
                message = "continue current workspace generate B1 listening material museum directions dialogue 3 speakers exactly 4 questions",
                currentRecord = currentRecord
            )
        )
    }

    private fun workspace(id: String) = LearningWorkspace(
        id = id,
        title = "听力工作区",
        need = "生成听力练习",
        summary = "听力训练",
        currentStep = "material",
        createdAt = 1L,
        updatedAt = 1L,
        plan = WorkspacePlan(
            title = "听力工作区",
            summary = "听力训练",
            contentType = "dialogue",
            materialPrompt = "校园对话",
            difficulty = "普通",
            speechRate = "medium",
            voiceGender = "female"
        )
    )

    private fun record(id: String) = HistoryRecord(
        id = id,
        scene = "校园对话",
        createdAt = 2L,
        contentType = "dialogue",
        content = ListeningContent(
            title = "校园对话",
            script = "A: Hello.\nB: Hi.",
            questions = listOf(
                Question(
                    questionText = "What does B say?",
                    options = listOf("Hi", "Bye"),
                    correctAnswer = 0
                )
            ),
            audioUrl = "/tmp/audio.wav"
        )
    )

    private fun testMicroCardJson(title: String) = buildJsonObject {
        put("title", title)
        put("nodes", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "内容") }) })
    }

    private fun libraryItem(workspaceId: String) = UserLibraryItem(
        id = "card_1",
        kind = "card",
        title = "听力素材",
        summary = "",
        data = buildJsonObject { put("workspaceId", workspaceId); put("microCard", testMicroCardJson("听力素材")) },
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun fileItem(
        title: String,
        mimeType: String,
        localPath: String = "",
        downloadUrl: String = ""
    ) = UserLibraryItem(
        id = "file_$title",
        kind = "files",
        title = title,
        summary = mimeType,
        data = buildJsonObject { put("mimeType", mimeType); put("localPath", localPath); put("downloadUrl", downloadUrl) },
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun cardItem(
        id: String,
        title: String,
        updatedAt: Long = 2L,
        pinnedAt: Long = 0L
    ) = UserLibraryItem(
        id = id,
        kind = "cards",
        title = title,
        summary = "",
        data = buildJsonObject {
            put("microCard", testMicroCardJson(title))
            if (pinnedAt > 0L) put("pinnedAt", pinnedAt)
        },
        createdAt = 1L,
        updatedAt = updatedAt
    )

    private fun agentFilePreviewSourceForTest(asset: UserLibraryFileAsset): String {
        val method = Class.forName("com.c0d3c.listene.AgentFileIoKt")
            .getDeclaredMethod("agentFilePreviewSource", UserLibraryFileAsset::class.java)
        method.isAccessible = true
        return method.invoke(null, asset) as String
    }

    private fun agentReturnToDrawerHomeWithSyncForTest(
        refreshWorkspaces: () -> Unit,
        returnHome: () -> Unit
    ) {
        val method = Class.forName("com.c0d3c.listene.AgentWorkspaceDrawerLogicKt")
            .getDeclaredMethod("agentReturnToDrawerHomeWithSync", Function0::class.java, Function0::class.java)
        method.isAccessible = true
        method.invoke(null, refreshWorkspaces, returnHome)
    }

    private fun JsonArray.toStringList(): List<String> =
        (0 until size).map { index -> str(index) }
}

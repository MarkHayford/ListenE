package com.c0d3c.listene

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListenEApp(
    viewModel: ListeningViewModel = viewModel(),
    initialNavIntent: NavIntent? = null,
    authSession: AuthSession? = null,
    onLogout: () -> Unit = {},
    onAccountDeleted: () -> Unit = {},
    onSessionUpdated: (AuthSession) -> Unit = {},
    onSessionExpired: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    val history by viewModel.history.collectAsState()
    val analysisInProgressRecordId by viewModel.analysisInProgressRecordId.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var activeWorkspace by remember { mutableStateOf<LearningWorkspace?>(null) }
    var workspaces by remember { mutableStateOf(emptyList<LearningWorkspace>()) }
    var workspaceLoading by remember { mutableStateOf(false) }
    var reconnectingAgent by remember { mutableStateOf(false) }
    // 练习卡生成进行中（仅练习卡请求为 true）：用于在聊天底部显示“进度条卡片”，生成完成后由结果卡接替。
    var agentPracticeCardGenerating by remember { mutableStateOf(false) }
    // ②流式生成阶段文案（SSE：理解意图/组卡/校验…），显示在生成进度卡上。
    var agentGenStageText by remember { mutableStateOf("") }
    // 回复正文的流式增量（SSE delta 事件）：生成期间打字机式上屏，done 后由正式消息接替。
    var agentStreamingReply by remember { mutableStateOf("") }
    var workspaceSyncing by remember { mutableStateOf(false) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var chatInput by rememberSaveable { mutableStateOf("") }
    // 正在进行的 AI 回复请求：保留 Job 以支持「停止生成」；token 用于作废迟到的旧请求结果
    // （阻塞式网络读不受协程取消即时中断，旧请求完成后凭 token 丢弃其状态写入）。
    var agentReplyJob by remember { mutableStateOf<Job?>(null) }
    var agentReplyToken by remember { mutableLongStateOf(0L) }
    // 待发送附件：选完先在输入框上方预览（可删、可补文字说明），点发送才随消息发出。
    val pendingChatAttachments = remember { mutableStateListOf<AgentInputAttachment>() }
    var drawerSearchQuery by rememberSaveable { mutableStateOf("") }
    var agentReplyMode by rememberSaveable { mutableStateOf("text") }
    var agentVoiceProfile by rememberSaveable { mutableStateOf("") }
    // 口语陪练（聊天卡片版）：陪练键点一下＝直接开/结束自由聊天；长按＝打开设置面板（自定义场景+推荐+音色）后开始。
    var showRoleplaySettings by remember { mutableStateOf(false) }
    // 新版口语陪练：主输入框驱动 + 右侧按钮开关。活动会话由 App 持有；结束后快照成聊天里的「已结束卡」。
    var roleplayActiveKey by remember { mutableStateOf<String?>(null) }
    var roleplayScenario by rememberSaveable { mutableStateOf("") }
    var roleplayGoal by rememberSaveable { mutableStateOf("") }
    val roleplayMessages = remember { mutableStateListOf<RoleplayMessage>() }
    var roleplayLoading by remember { mutableStateOf(false) }
    var roleplayTtsBusy by remember { mutableStateOf("") }
    val roleplayPlayer = remember { mutableStateOf<AgentExoAudio?>(null) }
    var duePlan by remember { mutableStateOf<StudyPlan?>(null) }
    var showWorkspaceDirectory by rememberSaveable { mutableStateOf(false) }
    var activeLibraryKind by rememberSaveable { mutableStateOf<AgentLibraryKind?>(null) }
    var drawerFeaturePageOpen by remember { mutableStateOf(false) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var audioRecordingJob by remember { mutableStateOf<Job?>(null) }
    var voiceRecordStartAt by remember { mutableLongStateOf(0L) }
    val chatMessagesByWorkspace = remember { mutableStateMapOf<String, List<AgentChatMessage>>() }
    val announcedAgentStates = remember { mutableStateMapOf<String, Boolean>() }
    // 本会话已入卡片库的听力 recordId，避免 Success 态重复触发时反复写库/刷列表。
    val savedListeningLibraryRecordIds = remember { mutableStateMapOf<String, Boolean>() }
    var workspaceToDelete by remember { mutableStateOf<LearningWorkspace?>(null) }
    var workspaceToRename by remember { mutableStateOf<LearningWorkspace?>(null) }
    var workspaceActionTarget by remember { mutableStateOf<AgentWorkspaceActionMenuTarget?>(null) }
    var workspaceMoveCategoryTarget by remember { mutableStateOf<LearningWorkspace?>(null) }
    var chatBottomScrollRequest by rememberSaveable { mutableIntStateOf(0) }
    var chatCardTopAnchorRequest by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingAiReviewScrollWorkspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAgentRetryRequest by remember { mutableStateOf<AgentRetryRequest?>(null) }
    val chatScrollStates = remember { mutableMapOf<String, ScrollState>() }
    var routeAgentMessageRef: (String, List<AgentInputAttachment>, String?) -> Unit = { _, _, _ -> }
    var importListeningRecordIntoAgentWorkspaceRef: (HistoryRecord) -> Unit = {}
    var requestAudioPermissionRef: () -> Unit = {}

    fun startAgentVoiceRecording(): Boolean {
        if (audioRecordingJob != null) return false
        val file = File(ctx.cacheDir, "agent_voice_${System.currentTimeMillis()}.wav")
        recordingFile = file
        voiceRecordStartAt = System.currentTimeMillis()
        audioRecordingJob = scope.launch(Dispatchers.IO) {
            runCatching {
                recordAgentWavFile(file)
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    recordingFile = null
                    audioRecordingJob = null
                    workspaceError = e.message ?: "录音启动失败"
                    AppNoticeBus.error(workspaceError ?: "录音启动失败")
                }
            }
        }
        return true
    }

    fun stopAgentVoiceRecordingAndSend() {
        val file = recordingFile
        recordingFile = null
        val job = audioRecordingJob
        audioRecordingJob = null
        // 按下即录后，轻点/过短按压不算一条语音：静默丢弃并提示，不再报「录音文件无效」。
        val tooShort = System.currentTimeMillis() - voiceRecordStartAt < 600L
        scope.launch {
            if (file == null) {
                AppNoticeBus.error("录音文件无效")
                return@launch
            }
            job?.cancelAndJoin()
            if (tooShort) {
                runCatching { file.delete() }
                AppNoticeBus.show("按住说话，松开发送")
                return@launch
            }
            if (!file.exists() || file.length() <= 44L) {
                AppNoticeBus.error("录音文件无效")
                return@launch
            }
            var attachment: AgentInputAttachment? = null
            workspaceLoading = true
            workspaceError = null
            try {
                attachment = withContext(Dispatchers.IO) {
                    readAgentAttachmentFromFile(file, "audio/wav")
                }
            } catch (e: Exception) {
                workspaceError = e.message ?: "语音读取失败"
            } finally {
                workspaceLoading = false
            }
            attachment?.let {
                routeAgentMessageRef("请理解这段语音。", listOf(it), "")
            }
        }
    }

    fun cancelAgentVoiceRecording() {
        val file = recordingFile
        recordingFile = null
        val job = audioRecordingJob
        audioRecordingJob = null
        scope.launch {
            job?.cancelAndJoin()
            file?.let { runCatching { it.delete() } }
        }
    }

    fun startAgentVoiceRecordingWithPermission(): Boolean {
        if (recordingFile != null) return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return startAgentVoiceRecording()
        } else {
            requestAudioPermissionRef()
            return false
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AppNoticeBus.show("已获得麦克风权限，请再次操作")
        else AppNoticeBus.error("需要麦克风权限才能使用语音")
    }
    requestAudioPermissionRef = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            var attachment: AgentInputAttachment? = null
            workspaceError = null
            try {
                val name = queryAgentDisplayName(ctx, uri).ifBlank { "attachment" }
                val mimeType = (ctx.contentResolver.getType(uri) ?: inferAgentMimeType(name)).lowercase()
                val bytes = withContext(Dispatchers.IO) { readUriBytes(ctx, uri) }
                if (agentUriLooksLikeListeningPackage(name, mimeType) && peekListeningManifestRoot(bytes)?.str("format") == LISTENING_PACKAGE_FORMAT) {
                    val record = withContext(Dispatchers.IO) { importListeningPackage(ctx, bytes) }
                    importListeningRecordIntoAgentWorkspaceRef(record)
                    return@launch
                } else {
                    attachment = withContext(Dispatchers.IO) {
                        buildAgentInputAttachment(name, mimeType, bytes)
                    }
                }
            } catch (e: Exception) {
                AppNoticeBus.error(e.message ?: "文件读取失败")
            }
            // 附件先进预览区（输入框上方），用户可补充说明/删除，点发送才真正发出。
            attachment?.let { pendingChatAttachments.add(it) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioRecordingJob?.cancel()
            audioRecordingJob = null
        }
    }

    fun chatKey(workspace: LearningWorkspace? = activeWorkspace): String = workspace?.id ?: "new"

    // 草稿写透：输入框内容随键入按 chatKey 持久化，切会话/杀进程都不丢。
    fun updateChatInput(text: String) {
        chatInput = text
        AgentChatDraftStore.set(ctx, chatKey(), text)
    }

    // 按住说话·上滑到「语音转文字」区松开：停止录音并把这段语音转写成文字塞进输入框（不发消息）。
    fun stopAgentVoiceRecordingAndTranscribe() {
        val file = recordingFile
        recordingFile = null
        val job = audioRecordingJob
        audioRecordingJob = null
        val tooShort = System.currentTimeMillis() - voiceRecordStartAt < 600L
        scope.launch {
            if (file == null) { AppNoticeBus.error("录音文件无效"); return@launch }
            job?.cancelAndJoin()
            if (tooShort) {
                runCatching { file.delete() }
                AppNoticeBus.show("按住说话，上滑到「转文字」松开")
                return@launch
            }
            if (!file.exists() || file.length() <= 44L) {
                runCatching { file.delete() }
                AppNoticeBus.error("没有录到声音，请再试一次")
                return@launch
            }
            AppNoticeBus.show("语音转写中…")
            try {
                val attachment = withContext(Dispatchers.IO) {
                    readAgentAttachmentFromFile(file, "audio/wav")
                }
                val text = AgentConversationService.transcribeAudio(attachment.base64, "audio/wav")
                if (text.isBlank()) {
                    AppNoticeBus.show("没有识别到内容，请再试一次")
                } else {
                    updateChatInput(if (chatInput.isBlank()) text else chatInput.trimEnd() + " " + text)
                }
            } catch (e: Exception) {
                AppNoticeBus.error(e.message ?: "转写失败，请重试")
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    fun returnToChatHome(clearInput: Boolean = false, clearWorkspace: Boolean = true) {
        showWorkspaceDirectory = false
        activeLibraryKind = null
        if (clearWorkspace) activeWorkspace = null
        if (clearInput) updateChatInput("")
    }

    // 切换会话时恢复各自草稿；「新会话→首条消息自动建的工作区」视为同一会话，正在敲的内容与附件跟随迁移。
    var chatDraftKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeWorkspace?.id) {
        val newKey = activeWorkspace?.id ?: "new"
        val prevKey = chatDraftKey
        if (prevKey == newKey) return@LaunchedEffect
        chatDraftKey = newKey
        val autoMigratedFromNew = prevKey == "new" && newKey != "new"
        val stored = AgentChatDraftStore.get(ctx, newKey)
        when {
            stored.isNotBlank() -> chatInput = stored
            autoMigratedFromNew && chatInput.isNotBlank() -> {
                AgentChatDraftStore.set(ctx, newKey, chatInput)
                AgentChatDraftStore.set(ctx, "new", "")
            }
            else -> chatInput = ""
        }
        if (!autoMigratedFromNew) pendingChatAttachments.clear()
    }

    fun saveChatMessages(workspaceId: String, messages: List<AgentChatMessage>) {
        val sanitized = sanitizeStoredAgentChatMessages(messages)
        AgentChatMessageStore.save(ctx, workspaceId, sanitized)
        scope.launch {
            val remoteWorkspace = AgentChatMessageStore.saveRemote(ctx, workspaceId, sanitized)
            if (remoteWorkspace != null) {
                workspaces = agentWorkspacesAfterRemoteUpdate(workspaces, remoteWorkspace)
                activeWorkspace = agentActiveWorkspaceAfterRemoteUpdate(activeWorkspace, remoteWorkspace)
            }
        }
    }

    fun deleteChatMessages(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val key = chatKey()
        val current = chatMessagesByWorkspace[key].orEmpty()
        val next = current.filterNot { ids.contains(it.id) }
        chatMessagesByWorkspace[key] = next
        activeWorkspace?.id?.let { saveChatMessages(it, next) }
    }

    // 微元卡「核对答案」→ 把作答表挂到携带该卡的消息上（落盘+云同步），供「AI 分析」逐题复盘。
    fun attachMicroAnswersToMessage(messageId: Long, sheetJson: String) {
        if (sheetJson.isBlank()) return
        val key = chatKey()
        val current = chatMessagesByWorkspace[key].orEmpty()
        if (current.none { it.id == messageId }) return
        val next = current.map { if (it.id == messageId) it.copy(microAnswersJson = sheetJson) else it }
        chatMessagesByWorkspace[key] = next
        activeWorkspace?.id?.let { saveChatMessages(it, next) }
    }

    fun appendChat(
        role: AgentChatRole,
        text: String,
        workspace: LearningWorkspace? = activeWorkspace,
        attachments: List<AgentInputAttachment> = emptyList(),
        audioUrl: String = "",
        scrollToBottom: Boolean = false,
        microCardJson: String = "",
        solveJson: String = "",
        roleplayJson: String = "",
        listeningRecordId: String = "",
        shadowingJson: String = ""
    ) {
        val key = chatKey(workspace)
        val current = chatMessagesByWorkspace[key].orEmpty()
        val id = System.currentTimeMillis() + current.size
        val next = (current + AgentChatMessage(id, role, text, attachments = attachments, audioUrl = audioUrl, microCardJson = microCardJson, solveJson = solveJson, roleplayJson = roleplayJson, listeningRecordId = listeningRecordId, shadowingJson = shadowingJson)).takeLast(80)
        chatMessagesByWorkspace[key] = next
        workspace?.id?.let { saveChatMessages(it, next) }
        val cardLikePayload = microCardJson.isNotBlank() || solveJson.isNotBlank() || roleplayJson.isNotBlank() ||
            listeningRecordId.isNotBlank() || shadowingJson.isNotBlank()
        if (agentShouldScrollForAppendedChat(role, cardLikePayload, attachments, scrollToBottom)) {
            chatBottomScrollRequest += 1
        }
        if (agentShouldAnchorAppendedCardAtTop(role, cardLikePayload, scrollToBottom)) {
            chatCardTopAnchorRequest = id
        } else if (!cardLikePayload) {
            chatCardTopAnchorRequest = agentCardAnchorAfterAppend(chatCardTopAnchorRequest, null)
        }
    }

    fun appendAgentReply(
        text: String,
        workspace: LearningWorkspace? = activeWorkspace,
        attachments: List<AgentInputAttachment> = emptyList(),
        scrollToBottom: Boolean = false,
        listeningRecordId: String = ""
    ) {
        workspace?.id?.let { workspaceId ->
            attachments.filter { it.generated }.forEach { attachment ->
                scope.launch {
                    runCatching {
                        UserLibraryStore.saveGeneratedFile(
                            ctx = ctx,
                            workspaceId = workspaceId,
                            attachment = attachment,
                            extraData = agentGeneratedAttachmentLibraryData(attachment)
                        )
                        exportAgentGeneratedAttachmentIfRequested(ctx, attachment)
                    }
                        .onSuccess { AppNoticeBus.success("文件已加入文件库") }
                        .onFailure { AppNoticeBus.error(it.message ?: "文件库保存失败") }
                }
            }
        }
        val cleanText = agentChatReplyText(text, attachments)
        if (!agentShouldAppendAgentReply(cleanText, attachments)) return
        // 素材锚点公告与卡片同理不做 TTS（voice 模式只读纯聊天回复）。
        if (listeningRecordId.isNotBlank() || !shouldSynthesizeAgentReplySpeech(agentReplyMode, cleanText, attachments)) {
            appendChat(AgentChatRole.Agent, cleanText, workspace, attachments, scrollToBottom = scrollToBottom, listeningRecordId = listeningRecordId)
            return
        }
        scope.launch {
            val audioUrl = runCatching {
                AgentConversationService.synthesizeSpeech(
                    text = cleanText,
                    voiceProfile = agentVoiceProfile,
                    voiceMode = if (agentVoiceProfile.isBlank()) "default" else "custom"
                ).audioUrl
            }.getOrDefault("")
            appendChat(AgentChatRole.Agent, cleanText, workspace, attachments, audioUrl, scrollToBottom, listeningRecordId = listeningRecordId)
        }
    }

    // 练习卡作答分析（共享执行体）：聊天「分析」意图与卡内「交给 AI 分析」按钮共用。
    suspend fun runMicroSheetAnalysis(
        sheetJson: String,
        workspace: LearningWorkspace,
        stillWanted: () -> Boolean = { true }
    ) {
        val analysis = runCatching { AgentConversationService.analyzeMicroPractice(sheetJson) }
        if (!stillWanted()) return
        analysis.onSuccess { res ->
            val (total, correct) = agentMicroSheetScore(sheetJson)
            appendChat(
                role = AgentChatRole.Agent,
                text = agentMicroAnalysisReplyText(res, total, correct),
                workspace = workspace,
                scrollToBottom = true
            )
        }.onFailure { e ->
            appendAgentReply("练习卡分析失败：${e.message ?: "请稍后重试"}", workspace, scrollToBottom = true)
        }
    }

    // 卡内「交给 AI 分析」按钮：带最新作答表直接分析（同时把作答表挂回消息，供后续「分析」意图复用）。
    fun requestMicroAnalysisFromCard(messageId: Long, sheetJson: String) {
        if (sheetJson.isBlank()) return
        val workspace = activeWorkspace ?: return
        attachMicroAnswersToMessage(messageId, sheetJson)
        if (workspaceLoading) {
            AppNoticeBus.show("正在回复中，稍后再试")
            return
        }
        scope.launch {
            workspaceLoading = true
            try {
                runMicroSheetAnalysis(sheetJson, workspace)
            } finally {
                workspaceLoading = false
            }
        }
    }

    fun buildAgentOutputAttachments(files: List<AgentOutputFileSpec>): List<AgentInputAttachment> {
        if (files.isEmpty()) return emptyList()
        return runCatching { writeAgentOutputFiles(ctx, files) }
            .onFailure { AppNoticeBus.error(it.message ?: "文件生成失败") }
            .getOrDefault(emptyList())
    }

    suspend fun buildListeningZipAttachment(record: HistoryRecord): AgentInputAttachment? =
        runCatching {
            val exportRecord = viewModel.ensureImportedRecordAudio(ctx, record)
            writeListeningZipAttachment(ctx, exportRecord)
        }
            .onFailure { AppNoticeBus.error(it.message ?: "听力包导出失败") }
            .getOrNull()

    suspend fun buildCurrentQuestionExportAttachment(
        record: HistoryRecord,
        format: AgentCurrentQuestionExportFormat
    ): AgentInputAttachment? =
        when (format) {
            AgentCurrentQuestionExportFormat.ZipWithAudio -> buildListeningZipAttachment(record)
            AgentCurrentQuestionExportFormat.DocxWithoutAudio -> runCatching {
                writeListeningDocxAttachment(ctx, record)
            }
                .onFailure { AppNoticeBus.error(it.message ?: "Word 文档导出失败") }
                .getOrNull()
        }

    fun ensureWorkspaceChatLoaded(workspace: LearningWorkspace) {
        if (chatMessagesByWorkspace[workspace.id].isNullOrEmpty()) {
            val stored = sanitizeStoredAgentChatMessages(AgentChatMessageStore.load(ctx, workspace.id))
            if (stored.isNotEmpty()) {
                chatMessagesByWorkspace[workspace.id] = stored
                saveChatMessages(workspace.id, stored)
            }
            scope.launch {
                val remote = sanitizeStoredAgentChatMessages(AgentChatMessageStore.loadRemote(ctx, workspace.id))
                if (remote.isNotEmpty()) {
                    val merged = mergeAgentChatMessagesForSync(
                        current = chatMessagesByWorkspace[workspace.id].orEmpty(),
                        incoming = remote
                    )
                    chatMessagesByWorkspace[workspace.id] = merged
                    AgentChatMessageStore.save(ctx, workspace.id, merged)
                }
            }
        }
    }

    fun reloadLocalWorkspaces(preferredWorkspaceId: String? = activeWorkspace?.id) {
        val next = WorkspaceStore.loadWorkspaces(ctx)
        workspaces = next
        val selected = preferredWorkspaceId
            ?.let { id -> next.firstOrNull { it.id == id } }
            ?: activeWorkspace?.id?.let { id -> next.firstOrNull { it.id == id } }
            ?: next.firstOrNull { it.status == WorkspaceStatus.Active }
        activeWorkspace = selected
        selected?.let { ensureWorkspaceChatLoaded(it) }
    }

    fun importListeningRecordIntoAgentWorkspace(record: HistoryRecord) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    viewModel.insertImportedRecord(ctx, record)
                }
                val workspace = WorkspaceStore.createWorkspace(ctx, agentImportedListeningWorkspaceNeed(record))
                WorkspaceStore.recordEvent(
                    ctx = ctx,
                    workspaceId = workspace.id,
                    type = "material_imported",
                    title = "导入听力练习",
                    description = record.content.title.ifBlank { record.scene },
                    recordId = record.id,
                    currentStep = "practice"
                )
                val freshWorkspace = WorkspaceStore.loadWorkspaces(ctx).firstOrNull { it.id == workspace.id }
                    ?: workspace.copy(
                        currentStep = "practice",
                        linkedRecordIds = listOf(record.id)
                    )
                withContext(Dispatchers.Main) {
                    workspaces = WorkspaceStore.loadWorkspaces(ctx)
                    activeWorkspace = freshWorkspace
                    ensureWorkspaceChatLoaded(freshWorkspace)
                    viewModel.openHistoryRecordForWorkspace(record, freshWorkspace.id)
                    appendAgentReply(
                        text = "已导入听力练习，可以直接播放音频、查看原文并答题。",
                        workspace = freshWorkspace,
                        scrollToBottom = true,
                        listeningRecordId = AgentCardEngine.materialReady(record).listeningRecordId
                    )
                    AppNoticeBus.success("听力包导入成功")
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    workspaceError = error.message ?: "听力包导入失败"
                    AppNoticeBus.error(workspaceError ?: "听力包导入失败")
                }
            }
        }
    }
    importListeningRecordIntoAgentWorkspaceRef = ::importListeningRecordIntoAgentWorkspace

    suspend fun syncRemoteWorkspaces(
        preferredWorkspaceId: String? = activeWorkspace?.id,
        selectFallbackWorkspace: Boolean = false
    ) {
        if (workspaceSyncing) return
        workspaceSyncing = true
        workspaceError = null
        try {
            val remote = WorkspaceStore.refreshRemote(ctx)
            workspaces = remote
            val selected = preferredWorkspaceId
                ?.let { id -> remote.firstOrNull { it.id == id } }
                ?: activeWorkspace?.id?.let { id -> remote.firstOrNull { it.id == id } }
                ?: if (selectFallbackWorkspace) {
                    remote.firstOrNull { it.status == WorkspaceStatus.Active } ?: remote.firstOrNull()
                } else {
                    null
                }
            activeWorkspace = selected
            selected?.let { ensureWorkspaceChatLoaded(it) }
        } catch (e: Exception) {
            // 后台自动同步（进入 App/打开抽屉时触发）失败不弹聊天区「连接失败」大卡：回落本地数据即可。
            // 若确系登录态失效(401)，交给统一的会话过期处理（静默续期/跳登录），而不是没头没脑弹卡。
            reloadLocalWorkspaces(preferredWorkspaceId)
            if (agentErrorLooksLikeAuthExpiry(e)) onSessionExpired()
        } finally {
            workspaceSyncing = false
        }
    }

    fun workspaceForRecord(recordId: String?): LearningWorkspace? {
        if (recordId.isNullOrBlank()) return null
        return workspaces.firstOrNull { workspaceHasRecord(it, recordId) }
            ?: WorkspaceStore.loadWorkspaces(ctx).firstOrNull { workspaceHasRecord(it, recordId) }
    }

    fun workspaceRecords(workspace: LearningWorkspace?): List<HistoryRecord> {
        val ws = workspace ?: return emptyList()
        val ids = buildList {
            addAll(ws.events.map { it.recordId })
            addAll(ws.linkedRecordIds.asReversed())
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val localById = ids.mapNotNull { recordId ->
            viewModel.getHistoryRecord(ctx, recordId) ?: history.firstOrNull { it.id == recordId }
        }
        val historyMatches = workspaceHistoryRecords(ws, history)
        return (localById + historyMatches)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
    }

    fun currentWorkspaceRecord(workspace: LearningWorkspace? = activeWorkspace): HistoryRecord? {
        val ws = workspace ?: return null
        val linkedRecords = workspaceRecords(ws)
        fun recordBelongsToWorkspace(recordId: String?): Boolean {
            if (recordId.isNullOrBlank()) return false
            return workspaceHasRecord(ws, recordId) ||
                linkedRecords.any { it.id == recordId } ||
                linkedRecords.isEmpty()
        }
        if (viewModel.activeWorkspaceIdSnapshot() == ws.id) {
            when (val current = state) {
                is GenerationState.Success -> if (recordBelongsToWorkspace(current.recordId)) {
                    viewModel.getHistoryRecord(ctx, current.recordId)?.let { return it }
                }
                is GenerationState.AnalysisLoading -> if (recordBelongsToWorkspace(current.record.id)) return current.record
                is GenerationState.AnalysisResultScreen -> if (recordBelongsToWorkspace(current.record.id)) return current.record
                is GenerationState.Message -> current.record?.takeIf { recordBelongsToWorkspace(it.id) }?.let { return it }
                is GenerationState.Error -> current.record?.takeIf { recordBelongsToWorkspace(it.id) }?.let { return it }
                else -> Unit
            }
        }
        return linkedRecords.firstOrNull()
    }

    fun startWorkspaceMaterial(workspace: LearningWorkspace, materialNeed: String = "", introText: String = "") {
        val generatingCurrentWorkspace = state is GenerationState.Loading && viewModel.activeWorkspaceIdSnapshot() == workspace.id
        // 正在生成同一工作区：不重复启动，聊天里的实时进度条已在显示。
        if (generatingCurrentWorkspace) return
        val cleanNeed = materialNeed.trim()
        // 生成状态改由聊天内「实时进度条」呈现（订阅 viewModel.materialProgress），不再追加静态「素材生成中」文案。
        viewModel.startAgentWorkspace(ctx, workspace, materialNeed = cleanNeed)
    }

    fun continueInitialMaterial(workspace: LearningWorkspace) {
        startWorkspaceMaterial(workspace)
    }

    fun openWorkspace(workspace: LearningWorkspace, targetStep: String = workspace.currentStep) {
        val freshWorkspace = WorkspaceStore.loadWorkspaces(ctx).firstOrNull { it.id == workspace.id } ?: workspace
        showWorkspaceDirectory = false
        activeLibraryKind = null
        activeWorkspace = freshWorkspace
        ensureWorkspaceChatLoaded(freshWorkspace)
        if (chatMessagesByWorkspace[freshWorkspace.id].isNullOrEmpty()) {
            val reply = AgentCardEngine.enteredWorkspace(freshWorkspace)
            val initial = listOf(
                AgentChatMessage(
                    id = System.currentTimeMillis(),
                    role = AgentChatRole.Agent,
                    text = reply.text
                )
            )
            chatMessagesByWorkspace[freshWorkspace.id] = initial
            saveChatMessages(freshWorkspace.id, initial)
        }
        scope.launch {
            drawerState.close()
            val step = freshWorkspace.plan.steps.firstOrNull { it.id == targetStep }?.id ?: targetStep
            when (step) {
                "chat" -> Unit
                "material" -> {
                    currentWorkspaceRecord(freshWorkspace)?.let { record ->
                        viewModel.openHistoryRecordForWorkspace(record, freshWorkspace.id)
                    } ?: continueInitialMaterial(freshWorkspace)
                }
                "practice" -> {
                    val record = currentWorkspaceRecord(freshWorkspace)
                    if (record != null) viewModel.openHistoryRecordForWorkspace(record, freshWorkspace.id)
                    else continueInitialMaterial(freshWorkspace)
                }
                "analysis" -> {
                    val record = currentWorkspaceRecord(freshWorkspace)
                    if (record != null) {
                        viewModel.openHistoryRecordForWorkspace(record, freshWorkspace.id)
                    } else {
                        continueInitialMaterial(freshWorkspace)
                    }
                }
                "review" -> {
                    val record = currentWorkspaceRecord(freshWorkspace)
                    if (record != null) {
                        viewModel.activateWorkspace(freshWorkspace.id)
                        viewModel.openHistoryRecordForWorkspace(record, freshWorkspace.id)
                    } else {
                        continueInitialMaterial(freshWorkspace)
                    }
                }
                else -> {
                    continueInitialMaterial(freshWorkspace)
                }
            }
        }
    }

    suspend fun createWorkspaceForFirstMessage(
        need: String,
        userText: String,
        displayText: String?,
        attachments: List<AgentInputAttachment>,
        sourceChatKeyToClear: String? = null,
        seedMessages: List<AgentChatMessage> = emptyList()
    ): LearningWorkspace? {
        val cleanNeed = need.trim().ifBlank { userText.trim() }
        if (cleanNeed.isBlank() && attachments.isEmpty()) {
            AppNoticeBus.show("请输入学习需求")
            return null
        }
        return runCatching { WorkspaceStore.createWorkspace(ctx, cleanNeed.ifBlank { "新的学习问题" }) }
            .map { workspace ->
                val firstMessage = AgentChatMessage(
                    id = System.currentTimeMillis(),
                    role = AgentChatRole.User,
                    text = displayText ?: userText,
                    attachments = attachments
                )
                val sourceMessages = sourceChatKeyToClear
                    ?.let { chatMessagesByWorkspace[it].orEmpty() }
                    .orEmpty()
                val initialMessages = seedMessages
                    .ifEmpty { sourceMessages }
                    .ifEmpty { listOf(firstMessage) }
                    .takeLast(80)
                chatMessagesByWorkspace[workspace.id] = initialMessages
                saveChatMessages(workspace.id, initialMessages)
                reloadLocalWorkspaces(workspace.id)
                updateChatInput("")
                activeWorkspace = workspace
                sourceChatKeyToClear
                    ?.takeIf { it != workspace.id }
                    ?.let { chatMessagesByWorkspace.remove(it) }
                workspace
            }
            .onFailure {
                // runCatching 会连协程取消一起吞掉：用户点「停止」时若正挂起在建工作区的
                // HTTP 调用里，会把 "StandaloneCoroutine was cancelled" 当业务错误弹连接失败面板。
                // 取消必须原样向上传播，交由外层按 replyStillWanted 静默收尾。
                if (it is CancellationException) throw it
                workspaceError = it.message ?: "创建工作区失败"
            }
            .getOrNull()
    }

    fun workspaceNeedForFirstMessage(
        userText: String,
        attachments: List<AgentInputAttachment>
    ): String {
        val attachmentNames = attachments
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
        val attachmentPart = if (attachmentNames.isEmpty()) "" else " 附件：${attachmentNames.joinToString("、")}"
        return (userText.trim().ifBlank { "请理解我发送的文件。" } + attachmentPart).take(800)
    }

    // 微元缺失兜底（本地固定题型引擎已退役）：不再本地拼卡，明确告知并请用户重试。
    fun appendPracticeCardFromAgent(
        workspace: LearningWorkspace,
        message: String,
        agentText: String = ""
    ) {
        appendAgentReply(
            agentText.trim().ifBlank { "这次没有拼出练习卡（AI 返回为空），请换个说法再试一次。" },
            workspace
        )
    }

    // 微元练习卡自动入库（与旧卡 saveGeneratedCard 同策略：有可导出题目才值得存）。
    fun saveMicroCardToLibraryIfWorthy(workspaceId: String, microCardJson: String) {
        val obj = parseJsonObjectOrNull(microCardJson) ?: return
        val card = MicroCardParser.parse(obj) ?: return
        if (!microCardCanExportQuestionBank(card)) return
        scope.launch {
            runCatching { UserLibraryStore.saveGeneratedMicroCard(ctx, workspaceId, obj) }
                .onFailure { AppNoticeBus.error(it.message ?: "卡片库保存失败") }
        }
    }

    // 听力练习就绪后自动入卡片库（与练习卡并列）：有原文或题目才值得存；clientId 幂等去重，重复就绪不会重复入库。
    fun saveListeningToLibraryIfWorthy(workspaceId: String, record: HistoryRecord) {
        val hasContent = record.content.script.isNotBlank() ||
            record.content.questions.any { it.questionText.isNotBlank() }
        if (!hasContent) return
        val dedupeKey = "$workspaceId:${record.id}"
        if (savedListeningLibraryRecordIds[dedupeKey] == true) return
        savedListeningLibraryRecordIds[dedupeKey] = true
        scope.launch {
            runCatching { UserLibraryStore.saveGeneratedListeningCard(ctx, workspaceId, record) }
                .onFailure {
                    savedListeningLibraryRecordIds.remove(dedupeKey)
                    AppNoticeBus.error(it.message ?: "听力卡入库失败")
                }
        }
    }

    fun requestAiReviewFromChat(record: HistoryRecord) {
        val workspace = activeWorkspace ?: return
        pendingAiReviewScrollWorkspaceId = workspace.id
        val decision = agentAiReviewEntryDecision(
            stored = viewModel.getHistoryRecord(ctx, record.id),
            requested = record,
            existingMessages = chatMessagesByWorkspace[workspace.id].orEmpty()
        )
        decision.statusMessage?.let { status ->
            if (decision.shouldAppendStatusMessage) {
                appendChat(
                    AgentChatRole.Agent,
                    status,
                    workspace,
                    scrollToBottom = true
                )
            }
            return
        }
        viewModel.activateWorkspace(workspace.id)
        viewModel.analyzeRecord(ctx, decision.record)
        chatBottomScrollRequest += 1
    }

    // 口语陪练 TTS：AI 回复自动朗读（复用合成+播放器；活动会话与已结束卡「重听」共用）。
    // 按设置面板里的音色播报（agentVoiceProfile 为空则默认），与听力素材生成链路口径一致。
    fun playRoleplayTts(text: String) {
        if (text.isBlank()) return
        roleplayTtsBusy = text
        scope.launch {
            val res = runCatching {
                AgentConversationService.synthesizeSpeech(
                    text = text,
                    voiceProfile = agentVoiceProfile,
                    voiceMode = if (agentVoiceProfile.isBlank()) "default" else "custom"
                )
            }.getOrNull()
            if (res == null || res.audioUrl.isBlank()) { roleplayTtsBusy = ""; return@launch }
            val playPath = AudioCache.localPath(ctx, res.audioUrl)
            withContext(Dispatchers.Main) {
                runCatching {
                    roleplayPlayer.value?.release()
                    val mp = AgentExoAudio(ctx)
                    roleplayPlayer.value = mp
                    mp.onPrepared = { mp.start(); roleplayTtsBusy = "" }
                    mp.onCompletion = { mp.release(); if (roleplayPlayer.value === mp) roleplayPlayer.value = null }
                    mp.onError = { roleplayTtsBusy = "" }
                    mp.setDataSource(playPath)
                    mp.prepare()
                }.onFailure { roleplayTtsBusy = "" }
            }
        }
    }

    // 开启一次口语陪练：resume=true 带上该「场景+目标」的历史记忆继续；否则清掉旧记忆开新对话。
    fun startRoleplay(scenario: String, goal: String, resume: Boolean) {
        val node = MicroNode.RoleplayTurn(scenario = scenario, opening = "", goal = goal, turns = 0)
        val key = RoleplayMemoryStore.memoryKey(node)
        roleplayScenario = scenario
        roleplayGoal = goal
        roleplayMessages.clear()
        if (resume) {
            roleplayMessages.addAll(RoleplayMemoryStore.load(ctx, key))
        } else {
            RoleplayMemoryStore.clear(ctx, key)
        }
        if (roleplayMessages.isEmpty()) {
            val freeChat = scenario.isBlank() ||
                Regex("自由|闲聊|small talk|free chat", RegexOption.IGNORE_CASE).containsMatchIn(scenario)
            val opening = if (freeChat) "Hey! Good to see you again. What's on your mind today?" else ""
            if (opening.isNotBlank()) {
                roleplayMessages.add(RoleplayMessage("assistant", opening))
                RoleplayMemoryStore.save(ctx, key, roleplayMessages.toList())
            }
        }
        roleplayActiveKey = key
        agentReplyMode = "roleplay"
        chatBottomScrollRequest += 1
    }

    // 陪练进行中：主输入框发出的内容走这里（不生成别的卡）。
    fun sendRoleplayMessage(text: String) {
        val key = roleplayActiveKey ?: return
        val t = text.trim()
        if (t.isBlank() || roleplayLoading) return
        updateChatInput("")
        roleplayMessages.add(RoleplayMessage("user", t))
        RoleplayMemoryStore.save(ctx, key, roleplayMessages.toList())
        roleplayLoading = true
        chatBottomScrollRequest += 1
        scope.launch {
            val scenarioEff = roleplayScenario.ifBlank {
                "Friendly free English chat: be a warm, natural partner. Keep replies short, ask follow-up questions, gently correct only clear mistakes."
            }
            runCatching { AgentConversationService.roleplayTurn(scenarioEff, roleplayMessages.toList(), "") }
                .onSuccess { turn ->
                    roleplayMessages.add(RoleplayMessage("assistant", turn.reply, turn.hint))
                    RoleplayMemoryStore.save(ctx, key, roleplayMessages.toList())
                    playRoleplayTts(turn.reply)
                }
                .onFailure { AppNoticeBus.error(it.message ?: "对话失败，请重试") }
            roleplayLoading = false
            chatBottomScrollRequest += 1
        }
    }

    // 结束陪练：自动评分并把对话快照成一张「已结束」卡留在聊天里；主输入框解除独占。
    fun endRoleplay() {
        val key = roleplayActiveKey ?: return
        val scenario = roleplayScenario
        val goal = roleplayGoal
        val msgs = roleplayMessages.toList()
        roleplayActiveKey = null
        agentReplyMode = "text"
        roleplayPlayer.value?.release(); roleplayPlayer.value = null
        roleplayTtsBusy = ""
        roleplayMessages.clear()
        if (msgs.none { it.role == "user" }) return
        val ws = activeWorkspace
        AppNoticeBus.success("正在结算本次陪练…")
        scope.launch {
            val fb = runCatching {
                AgentConversationService.roleplayFeedback(scenario.ifBlank { "Friendly free English chat" }, msgs)
            }.getOrNull()
            runCatching { fb?.improvements?.let { UserModelStore.pushWeaknesses(ctx, it) } }
            appendChat(
                AgentChatRole.Agent,
                "",
                workspace = ws,
                scrollToBottom = true,
                roleplayJson = buildRoleplayFinishedJson(scenario, goal, key, msgs, fb)
            )
        }
    }

    fun routeAgentMessage(
        message: String,
        attachments: List<AgentInputAttachment> = emptyList(),
        displayText: String? = null,
        appendUserMessage: Boolean = true
    ) {
        val clean = message.trim()
        if (clean.isBlank() && attachments.isEmpty()) return
        if (workspaceLoading) {
            AppNoticeBus.show("正在回复中，可点右下角停止")
            return
        }
        val workspace = activeWorkspace
        val sourceChatKey = chatKey(workspace)
        val priorMessages = chatMessagesByWorkspace[sourceChatKey].orEmpty()
        val userText = clean.ifBlank { "请理解我发送的文件。" }
        lastAgentRetryRequest = AgentRetryRequest(userText, attachments, displayText)
        val optimisticMessages = agentMessagesWithOptimisticUserInput(
            priorMessages = priorMessages,
            userText = userText,
            displayText = displayText,
            attachments = attachments,
            id = System.currentTimeMillis() + priorMessages.size,
            appendUserMessage = appendUserMessage
        )
        val currentUserMessage = AgentChatMessage(
            id = System.currentTimeMillis() + priorMessages.size,
            role = AgentChatRole.User,
            text = displayText ?: userText,
            attachments = attachments
        )
        chatMessagesByWorkspace[sourceChatKey] = optimisticMessages
        workspace?.id?.let { saveChatMessages(it, optimisticMessages) }
        chatBottomScrollRequest += 1
        val decisionContextMessages = priorMessages
        val seedMessages = if (workspace == null) optimisticMessages else emptyList()
        updateChatInput("")
        val replyToken = ++agentReplyToken
        // 请求是否仍有效：用户点「停止」会递增 token，迟到的旧结果凭此丢弃。
        fun replyStillWanted() = agentReplyToken == replyToken
        agentReplyJob = scope.launch {
            var agentNoticeReply: String? = null
            var agentHandsOffToListening = false
            workspaceLoading = true
            workspaceError = null
            try {
                val targetWorkspace = workspace ?: createWorkspaceForFirstMessage(
                    need = workspaceNeedForFirstMessage(userText, attachments),
                    userText = userText,
                    displayText = displayText,
                    attachments = attachments,
                    sourceChatKeyToClear = sourceChatKey,
                    seedMessages = seedMessages
                ) ?: return@launch
                // 拍照答疑：带图片附件时走结构化多模态解题，把结果作为一条 AI 消息内联渲染（替代原独立页）。
                if (attachments.any { it.mimeType.startsWith("image/", ignoreCase = true) }) {
                    val solved = SolveStore.solve(ctx, userText, attachments)
                    if (!replyStillWanted()) return@launch
                    if (solved != null && !solved.isEmpty) {
                        appendChat(
                            role = AgentChatRole.Agent,
                            text = solved.comment.ifBlank { "已读题并给出解答，可点下方按钮加入错题本/生词本。" },
                            workspace = targetWorkspace,
                            scrollToBottom = true,
                            solveJson = solveResultToJson(solved).toString()
                        )
                        return@launch
                    }
                    // 解题失败/空 → 继续走常规回复，不至于卡死。
                }
                val currentRecord = currentWorkspaceRecord(targetWorkspace)
                if (agentUserRequestsCurrentAiAnalysis(userText)) {
                    // 取「最近的作答产物」：已核对的练习卡（消息挂作答表）比听力记录更新时，走练习卡逐题分析。
                    val analysisMessages = chatMessagesByWorkspace[targetWorkspace.id].orEmpty()
                    val gradedMicro = agentLatestGradedMicroMessage(analysisMessages)
                    when {
                        gradedMicro != null && agentPreferMicroAnalysis(analysisMessages, currentRecord != null) -> {
                            runMicroSheetAnalysis(gradedMicro.microAnswersJson, targetWorkspace) { replyStillWanted() }
                        }
                        currentRecord != null -> requestAiReviewFromChat(currentRecord)
                        else -> appendAgentReply(
                            "当前工作区还没有可分析的答题记录。先做一张练习卡并核对答案，或完成听力素材答题后再让我分析。",
                            targetWorkspace,
                            scrollToBottom = true
                        )
                    }
                    return@launch
                }
                if (agentUserRequestsCurrentQuestionExport(userText)) {
                    val targetMessages = chatMessagesByWorkspace[targetWorkspace.id].orEmpty()
                    // 题库导出只走微元卡（cardSpec 已退役；聊天里不再出现旧协议题卡）。
                    val microSelection = latestMicroQuestionBankCardSelection(targetMessages)
                    if (microSelection != null && !agentQuestionExportShouldPreferListening(userText)) {
                        val microCard = microSelection.card
                        val outputAttachments = listOfNotNull(
                            runCatching { writeMicroCardQuestionBankAttachment(ctx, microCard) }
                                .onFailure { AppNoticeBus.error(it.message ?: "题库 Word 文档导出失败") }
                                .getOrNull()
                                ?.copy(
                                    sourceKind = "chat_question_bank",
                                    sourceCardTitle = microCard.title,
                                    sourceMessageId = microSelection.messageId,
                                    copyToDownloads = true
                                )
                        )
                        appendAgentReply(
                            if (outputAttachments.isNotEmpty()) agentCardQuestionBankExportSuccessReply()
                            else agentCardQuestionBankExportFailureReply(),
                            targetWorkspace,
                            attachments = outputAttachments,
                            scrollToBottom = true
                        )
                    } else {
                        val exportSpec = agentCurrentQuestionExportSpec(currentRecord, userText)
                        if (exportSpec != null && currentRecord != null) {
                            val outputAttachments = listOfNotNull(
                                buildCurrentQuestionExportAttachment(currentRecord, exportSpec.format)
                            )
                            appendAgentReply(
                                if (outputAttachments.isNotEmpty()) agentCurrentQuestionExportSuccessReply(exportSpec.format)
                                else agentCurrentQuestionExportFailureReply(exportSpec.format),
                                targetWorkspace,
                                attachments = outputAttachments,
                                scrollToBottom = true
                            )
                        } else {
                            appendAgentReply(
                                agentCurrentQuestionExportUnavailableReply(currentRecord),
                                targetWorkspace,
                                scrollToBottom = true
                            )
                        }
                    }
                    return@launch
                }
                if (agentUserRequestsShadowing(userText)) {
                    val (shadowTopic, shadowLevel) = agentShadowingParamsFromMessage(userText)
                    val shadowSentences = runCatching {
                        AgentConversationService.generateShadowing(shadowTopic, shadowLevel, 6)
                    }.getOrDefault(emptyList())
                    if (!replyStillWanted()) return@launch
                    if (shadowSentences.isEmpty()) {
                        appendAgentReply("生成跟读句子失败，请重试。", targetWorkspace, scrollToBottom = true)
                    } else {
                        appendChat(
                            role = AgentChatRole.Agent,
                            text = "已生成影子跟读练习，听一句、跟读一句，我来给你的发音和流利度打分。",
                            workspace = targetWorkspace,
                            scrollToBottom = true,
                            shadowingJson = buildShadowingCardJson(shadowTopic, shadowLevel, shadowSentences)
                        )
                    }
                    return@launch
                }
                // 仅当这条消息看起来在“要练习卡片”时，生成期间显示进度条卡片（生成完成后由结果卡原位接替）。
                agentPracticeCardGenerating = agentUserRequestsPracticeCard(userText)
                agentGenStageText = ""
                agentStreamingReply = ""
                // ②优先用 SSE 流式（阶段进度 + 正文打字机上屏）；断流先原地重试一次 stream
                //（移动网络抖动最常见，重试通常即恢复），再失败才回退阻塞 decide()，保证鲁棒。
                suspend fun tryDecideStream() = AgentConversationService.decideStream(
                    ctx = ctx,
                    message = userText,
                    workspace = targetWorkspace,
                    recentMessages = decisionContextMessages,
                    attachments = attachments,
                    currentRecord = currentRecord,
                    onStage = { stage, text ->
                        if (replyStillWanted()) {
                            agentGenStageText = text
                            // generating/validating 只在微元组卡时出现：本地正则漏判「要练习卡」时，
                            // 据后端阶段事件补显进度卡，避免长时间只有一行小字没有卡位。
                            if (stage == "generating" || stage == "validating") agentPracticeCardGenerating = true
                        }
                    },
                    onReplyDelta = { delta -> if (replyStillWanted()) agentStreamingReply += delta }
                )
                val decision = try {
                    tryDecideStream()
                } catch (_: Throwable) {
                    // 清掉半截增量，重试从零累计，避免正文重复。
                    agentGenStageText = ""
                    agentStreamingReply = ""
                    try {
                        tryDecideStream()
                    } catch (_: Throwable) {
                        agentGenStageText = ""
                        agentStreamingReply = ""
                        AgentConversationService.decide(
                            ctx = ctx,
                            message = userText,
                            workspace = targetWorkspace,
                            recentMessages = decisionContextMessages,
                            attachments = attachments,
                            currentRecord = currentRecord
                        )
                    }
                }
                if (!replyStillWanted()) return@launch
                val outputAttachments = buildAgentOutputAttachments(decision.outputFiles)
                when (decision.intent) {
                    AgentConversationIntent.Chat -> {
                        appendAgentReply(decision.reply, targetWorkspace, attachments = outputAttachments)
                        agentNoticeReply = decision.reply
                    }
                    AgentConversationIntent.NewListeningPractice -> {
                        val practiceNeed = decision.practiceNeed.ifBlank { userText }
                        val currentRecord = currentWorkspaceRecord(targetWorkspace)
                        val shouldCreateMaterialWorkspace = workspace != null &&
                            (
                                agentShouldCreateFreshWorkspaceForExplicitListeningRequest(targetWorkspace, userText, currentRecord) ||
                                agentShouldCreateWorkspaceForListeningRequest(targetWorkspace, practiceNeed, currentRecord) ||
                                    agentShouldCreateWorkspaceForListeningRequest(targetWorkspace, userText, currentRecord)
                                )
                        val materialWorkspace = if (shouldCreateMaterialWorkspace) {
                            createWorkspaceForFirstMessage(
                                need = workspaceNeedForFirstMessage(userText, attachments),
                                userText = userText,
                                displayText = displayText,
                                attachments = attachments,
                                seedMessages = listOf(currentUserMessage)
                            ) ?: targetWorkspace
                        } else {
                            targetWorkspace
                        }
                        if (outputAttachments.isNotEmpty()) {
                            appendAgentReply(decision.reply, materialWorkspace, attachments = outputAttachments)
                            agentNoticeReply = decision.reply
                            return@launch
                        }
                        agentHandsOffToListening = true
                        startWorkspaceMaterial(materialWorkspace, materialNeed = practiceNeed, introText = decision.reply)
                    }
                    AgentConversationIntent.PracticeCard -> {
                        if (outputAttachments.isNotEmpty()) {
                            appendAgentReply(decision.reply, targetWorkspace, attachments = outputAttachments)
                            agentNoticeReply = decision.reply
                            return@launch
                        }
                        // 练习卡“生成”走微元：优先用 /agent/chat 原生附带的微元卡（withMicro 请求），
                        // 直接渲染在聊天里，省去单独再请求 /agent/micro 的一次往返；微元为空/无效时回退纯文本回复。
                        val chatMicroJson = decision.microCardJson.takeIf { json ->
                            json.isNotBlank() && (MicroCardParser.parse(json)?.nodes?.isNotEmpty() == true)
                        }
                        val microGenerated = if (chatMicroJson != null) {
                            appendChat(
                                role = AgentChatRole.Agent,
                                text = decision.reply.ifBlank { "已生成微元练习卡（AI 实时拼装）。" },
                                workspace = targetWorkspace,
                                scrollToBottom = true,
                                microCardJson = chatMicroJson
                            )
                            saveMicroCardToLibraryIfWorthy(targetWorkspace.id, chatMicroJson)
                            true
                        } else {
                            false
                        }
                        if (!microGenerated) {
                            appendPracticeCardFromAgent(
                                workspace = targetWorkspace,
                                message = userText,
                                agentText = decision.reply
                            )
                        }
                        agentNoticeReply = decision.reply
                    }
                    AgentConversationIntent.StudyPlan -> {
                        val drafts = decision.planItems.map { item ->
                            StudyPlanDraft(
                                title = item.title,
                                detail = item.detail,
                                scheduledAt = agentPlanItemToEpochMillis(item.dayOffset, item.hour, item.minute),
                                recurrence = item.recurrence
                            )
                        }
                        val created = PlanStore.createPlans(ctx, drafts)
                        val summary = if (created.isNotEmpty()) {
                            "\n\n已加入「计划表」：\n" + created.joinToString("\n") { plan ->
                                "• ${plan.title}（${agentPlanScheduleLabel(plan.scheduledAt, plan.recurrence)}）"
                            }
                        } else {
                            ""
                        }
                        appendAgentReply(decision.reply + summary, targetWorkspace)
                        agentNoticeReply = decision.reply
                        if (created.isNotEmpty()) {
                            PlanReminderScheduler.rescheduleAll(ctx)
                        }
                    }
                }
            } catch (e: CancellationException) {
                agentNoticeReply = null
                throw e
            } catch (e: Exception) {
                agentNoticeReply = null
                if (!replyStillWanted()) return@launch
                if (agentErrorLooksLikeAuthExpiry(e)) {
                    // 登录态失效：走统一的会话过期处理（静默续期/跳登录），不弹「连接失败」大卡。
                    onSessionExpired()
                } else {
                    val message = agentInterruptionMessage(e)
                    workspaceError = message
                    val failKey = chatKey()
                    val failMessages = chatMessagesByWorkspace[failKey].orEmpty()
                    val failIndex = failMessages.indexOfLast { it.role == AgentChatRole.User }
                    if (failIndex >= 0 && !failMessages[failIndex].failed) {
                        val markedMessages = failMessages.toMutableList().also {
                            it[failIndex] = it[failIndex].copy(failed = true)
                        }
                        chatMessagesByWorkspace[failKey] = markedMessages
                        activeWorkspace?.id?.let { saveChatMessages(it, markedMessages) }
                    }
                    if (!AppForegroundTracker.isForeground) {
                        GenerationNotificationManager.showAgentReplyError(ctx, message)
                    }
                }
            } finally {
                if (replyStillWanted()) {
                    workspaceLoading = false
                    agentPracticeCardGenerating = false
                    agentGenStageText = ""
                    agentStreamingReply = ""
                    agentNoticeReply?.let { reply ->
                        if (!agentHandsOffToListening && !AppForegroundTracker.isForeground) {
                            GenerationNotificationManager.showAgentReplyComplete(ctx, reply)
                        }
                    }
                }
            }
        }
    }
    routeAgentMessageRef = { text, attachments, displayText -> routeAgentMessage(text, attachments, displayText) }

    // 停止生成：作废当前请求（token 递增让迟到结果被丢弃）、取消协程并立即复位 UI 状态；
    // 用户消息保留在聊天里，可重新发送。
    fun stopAgentReply() {
        val job = agentReplyJob
        agentReplyJob = null
        if (job != null && job.isActive) {
            agentReplyToken += 1
            job.cancel()
            workspaceLoading = false
            agentPracticeCardGenerating = false
            agentGenStageText = ""
            agentStreamingReply = ""
            AppNoticeBus.show("已停止生成")
        }
    }

    fun retryAgentAfterError() {
        val retry = agentRetryRequestForError(
            input = chatInput,
            lastRequest = lastAgentRetryRequest,
            messages = chatMessagesByWorkspace[chatKey()].orEmpty()
        ) ?: run {
            AppNoticeBus.error(agentRetryUnavailableMessage())
            return
        }
        workspaceError = null
        routeAgentMessage(
            message = retry.message,
            attachments = retry.attachments,
            displayText = retry.displayText,
            appendUserMessage = !retry.reusesVisibleUserMessage
        )
    }

    fun retryFailedUserMessage(failedMessage: AgentChatMessage) {
        val key = chatKey()
        val current = chatMessagesByWorkspace[key].orEmpty()
        if (current.any { it.id == failedMessage.id && it.failed }) {
            val cleared = current.map { if (it.id == failedMessage.id) it.copy(failed = false) else it }
            chatMessagesByWorkspace[key] = cleared
            activeWorkspace?.id?.let { saveChatMessages(it, cleared) }
        }
        workspaceError = null
        routeAgentMessage(
            message = failedMessage.text,
            attachments = failedMessage.attachments,
            displayText = failedMessage.text,
            appendUserMessage = false
        )
    }

    fun reconnectAgentAfterError() {
        workspaceError = null
        scope.launch {
            workspaceLoading = true
            reconnectingAgent = true
            runCatching {
                AuthStore.refreshMe(ctx)
                syncRemoteWorkspaces(preferredWorkspaceId = activeWorkspace?.id, selectFallbackWorkspace = activeWorkspace == null)
            }.onSuccess {
                AppNoticeBus.success(agentReconnectSuccessToast())
            }.onFailure {
                workspaceError = agentReconnectFailureToast(it.message)
                AppNoticeBus.error(workspaceError ?: agentReconnectFailureToast(null))
            }
            workspaceLoading = false
            reconnectingAgent = false
        }
    }

    fun announceAgentStateOnce(
        key: String,
        workspace: LearningWorkspace,
        reply: AgentCardReply,
        scrollToBottom: Boolean = false
    ) {
        if (announcedAgentStates[key] == true) return
        val existing = chatMessagesByWorkspace[workspace.id].orEmpty()
        if (existing.any { it.role == AgentChatRole.Agent && it.text == reply.text }) {
            announcedAgentStates[key] = true
            return
        }
        announcedAgentStates[key] = true
        appendAgentReply(reply.text, workspace, scrollToBottom = scrollToBottom, listeningRecordId = reply.listeningRecordId)
    }

    LaunchedEffect(state, activeWorkspace?.id) {
        val workspace = activeWorkspace ?: return@LaunchedEffect
        when (val current = state) {
            is GenerationState.Success -> {
                val recordId = current.recordId.orEmpty()
                if (recordId.isNotBlank() && viewModel.activeWorkspaceIdSnapshot() == workspace.id) {
                    val record = viewModel.getHistoryRecord(ctx, recordId)
                    announceAgentStateOnce(
                        key = "${workspace.id}:material_card:$recordId",
                        workspace = workspace,
                        reply = AgentCardEngine.materialReady(record),
                        scrollToBottom = true
                    )
                    if (record != null) saveListeningToLibraryIfWorthy(workspace.id, record)
                }
            }
            is GenerationState.AnalysisResultScreen -> {
                val fallback = AgentCardEngine.analysisReady(current.record.copy(analysisResult = current.result))
                val appendDecision = agentAnalysisResultChatAppendDecision(
                    workspace = workspace,
                    recordId = current.record.id,
                    resultSummary = current.result.summary,
                    activeViewModelWorkspaceId = viewModel.activeWorkspaceIdSnapshot(),
                    existingMessages = chatMessagesByWorkspace[workspace.id].orEmpty(),
                    reply = fallback
                )
                if (appendDecision.shouldAppend) {
                    val shouldScroll = pendingAiReviewScrollWorkspaceId == workspace.id
                    // 直接呈现已算好的分析结果（摘要 + 薄弱点 + 下一步建议）。此前会再向 AI 请求「生成一张分析卡」，
                    // 但该请求被后端归为 practice_card 走练习微元引擎——生成不出反馈型「分析卡」，于是聊天里只留下
                    // 一句「好的，分析卡来了」、长时间等不到卡。分析结论本就是纯文本通知（见 AgentCardEngine 设计注释），
                    // 故去掉这次多余且易失败的往返，直接把分析内容发出来。
                    announceAgentStateOnce(
                        key = "${workspace.id}:analysis_card:${current.record.id}:${current.result.summary.hashCode()}",
                        workspace = workspace,
                        reply = appendDecision.reply ?: fallback,
                        scrollToBottom = shouldScroll
                    )
                    if (shouldScroll) pendingAiReviewScrollWorkspaceId = null
                }
            }
            else -> Unit
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshHistory(ctx)
        syncRemoteWorkspaces(selectFallbackWorkspace = true)
    }

    // 相册式分类云端同步：进入时拉取；本地分类/归属变更后防抖上传（登录用户）。
    LaunchedEffect(Unit) {
        CategoryCloudStore.pull(ctx)
    }
    LaunchedEffect(Unit) {
        LibraryCategoryStore.changes.collectLatest {
            delay(1500)
            CategoryCloudStore.push(ctx)
        }
    }

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            syncRemoteWorkspaces()
        }
    }

    LaunchedEffect(history, state) {
        activeWorkspace?.id?.let { reloadLocalWorkspaces(it) }
    }

    LaunchedEffect(initialNavIntent) {
        when (initialNavIntent) {
            is NavIntent.Listening -> {
                viewModel.refreshHistory(ctx)
                workspaceForRecord(initialNavIntent.recordId)?.let { workspace ->
                    activeWorkspace = workspace
                    viewModel.getOrFetchHistoryRecord(ctx, initialNavIntent.recordId)
                        ?.let { viewModel.openHistoryRecordForWorkspace(it, workspace.id) }
                } ?: run {
                    scope.launch { drawerState.open() }
                }
            }
            null -> Unit
        }
    }

    LaunchedEffect(Unit) {
        AppNavigationBus.events.collect { event ->
            when (event) {
                is AppNavEvent.OpenListeningRecord -> {
                    workspaceForRecord(event.recordId)?.let { workspace ->
                        activeWorkspace = workspace
                        viewModel.getOrFetchHistoryRecord(ctx, event.recordId)
                            ?.let { viewModel.openHistoryRecordForWorkspace(it, workspace.id) }
                    } ?: run {
                        drawerState.open()
                    }
                }
                is AppNavEvent.OpenWorkspaceListeningRecord -> {
                    WorkspaceStore.loadWorkspaces(ctx).firstOrNull { it.id == event.workspaceId }?.let { activeWorkspace = it }
                    viewModel.getOrFetchHistoryRecord(ctx, event.recordId)?.let {
                        viewModel.openHistoryRecordForWorkspace(it, event.workspaceId)
                        if (event.openAnalysis) viewModel.analyzeRecord(ctx, it)
                    }
                }
                is AppNavEvent.StartWorkspaceMaterial -> {
                    WorkspaceStore.loadWorkspaces(ctx).firstOrNull { it.id == event.workspaceId }?.let { openWorkspace(it, "material") }
                }
                AppNavEvent.OpenSettings -> {
                    drawerState.open()
                }
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen && !drawerFeaturePageOpen) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
    }
    val activeChatKey = chatKey()
    val activeChatScrollState = remember(activeChatKey) {
        chatScrollStates.getOrPut(activeChatKey) { ScrollState(0) }
    }

    workspaceToDelete?.let { target ->
        AgentDeleteWorkspaceDialog(
            workspace = target,
            onDismiss = { workspaceToDelete = null },
            onConfirm = {
                workspaceToDelete = null
                scope.launch {
                    workspaceLoading = true
                    workspaceError = null
                    runCatching {
                        val previousActiveId = activeWorkspace?.id
                        WorkspaceStore.deleteWorkspace(ctx, target.id)
                        AgentChatMessageStore.delete(ctx, target.id)
                        chatMessagesByWorkspace.remove(target.id)
                        val remaining = WorkspaceStore.loadWorkspaces(ctx)
                        workspaces = remaining
                        val nextActive = agentWorkspaceAfterDelete(previousActiveId, target.id, remaining)
                        if (nextActive != null) {
                            activeWorkspace = nextActive
                            ensureWorkspaceChatLoaded(nextActive)
                            showWorkspaceDirectory = false
                            activeLibraryKind = null
                        } else {
                            returnToChatHome()
                        }
                        viewModel.refreshHistory(ctx)
                        drawerState.close()
                    }.onSuccess {
                        AppNoticeBus.success("工作区删除成功")
                    }.onFailure {
                        workspaceError = it.message ?: "删除工作区失败"
                        AppNoticeBus.error(workspaceError ?: "删除工作区失败")
                    }
                    workspaceLoading = false
                }
            }
        )
    }
    workspaceActionTarget?.let { target ->
        AgentWorkspaceActionMenuDialog(
            target = target,
            onDismiss = { workspaceActionTarget = null },
            onPin = {
                workspaceActionTarget = null
                scope.launch {
                    val pinned = target.workspace.pinnedAt <= 0L
                    runCatching { WorkspaceStore.setWorkspacePinned(ctx, target.workspace.id, pinned) }
                        .onSuccess {
                            reloadLocalWorkspaces()
                            AppNoticeBus.success(if (pinned) "工作区置顶成功" else "工作区已取消置顶")
                        }
                        .onFailure { AppNoticeBus.error(it.message ?: "置顶失败") }
                }
            },
            onRename = {
                workspaceActionTarget = null
                workspaceToRename = target.workspace
            },
            onDelete = {
                workspaceActionTarget = null
                workspaceToDelete = target.workspace
            },
            onMoveToCategory = {
                val moved = target.workspace
                workspaceActionTarget = null
                workspaceMoveCategoryTarget = moved
            }
        )
    }
    workspaceMoveCategoryTarget?.let { ws ->
        AgentCategoryPickerDialog(
            categories = LibraryCategoryStore.categories(ctx, LibraryCategoryStore.DOMAIN_WORKSPACE),
            currentId = LibraryCategoryStore.categoryOf(ctx, LibraryCategoryStore.DOMAIN_WORKSPACE, ws.id),
            onPick = { catId ->
                LibraryCategoryStore.setCategory(ctx, LibraryCategoryStore.DOMAIN_WORKSPACE, ws.id, catId)
                AppNoticeBus.success("已移动到分类")
            },
            onCreateAndPick = { name ->
                val c = LibraryCategoryStore.createCategory(ctx, LibraryCategoryStore.DOMAIN_WORKSPACE, name)
                if (c != null) LibraryCategoryStore.setCategory(ctx, LibraryCategoryStore.DOMAIN_WORKSPACE, ws.id, c.id)
                AppNoticeBus.success("已移动到分类")
            },
            onDismiss = { workspaceMoveCategoryTarget = null }
        )
    }
    workspaceToRename?.let { target ->
        AgentRenameItemDialog(
            title = "重命名工作区",
            label = "工作区名称",
            initialName = target.title,
            onDismiss = { workspaceToRename = null },
            onConfirm = { name ->
                workspaceToRename = null
                scope.launch {
                    runCatching { WorkspaceStore.renameWorkspace(ctx, target.id, name) }
                        .onSuccess {
                            reloadLocalWorkspaces()
                            AppNoticeBus.success("工作区重命名成功")
                        }
                        .onFailure { AppNoticeBus.error(it.message ?: "重命名失败") }
                }
            }
        )
    }

    if (showRoleplaySettings) {
        Dialog(onDismissRequest = { showRoleplaySettings = false }) {
            AgentRoleplaySetupPanel(
                voiceProfile = agentVoiceProfile,
                onVoiceProfileChange = { agentVoiceProfile = it },
                onStart = { scenario, goal ->
                    showRoleplaySettings = false
                    startRoleplay(scenario, goal, resume = false)
                },
                onClose = { showRoleplaySettings = false }
            )
        }
    }

    LaunchedEffect(Unit) {
        if (duePlan == null) duePlan = PlanReminderScheduler.loadDues(ctx).firstOrNull()
        PlanDueBus.events.collect {
            if (duePlan == null) duePlan = PlanReminderScheduler.loadDues(ctx).firstOrNull()
        }
    }

    duePlan?.let { due ->
        AgentPlanDueDialog(
            plan = due,
            onStart = {
                PlanReminderScheduler.removeDue(ctx, due.id)
                duePlan = PlanReminderScheduler.loadDues(ctx).firstOrNull()
                scope.launch { drawerState.close() }
                val content = if (due.detail.isNotBlank()) "${due.title}：${due.detail}" else due.title
                routeAgentMessage(content)
            },
            onSkip = {
                PlanReminderScheduler.removeDue(ctx, due.id)
                duePlan = PlanReminderScheduler.loadDues(ctx).firstOrNull()
            },
            onPostpone = {
                scope.launch {
                    PlanReminderScheduler.removeDue(ctx, due.id)
                    PlanStore.updatePlanSchedule(ctx, due.id, System.currentTimeMillis() + 30L * 60 * 1000, due.recurrence)
                    PlanReminderScheduler.rescheduleAll(ctx)
                    AppNoticeBus.success("已推迟 30 分钟")
                }
                duePlan = null
            },
            onDismiss = { duePlan = null }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Transparent,
        drawerContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                    AgentWorkspaceDrawer(
                        workspaces = workspaces,
                        activeWorkspace = activeWorkspace,
                        history = history,
                        state = state,
                        activeViewModelWorkspaceId = viewModel.activeWorkspaceIdSnapshot(),
                        viewModel = viewModel,
                        searchQuery = drawerSearchQuery,
                    onSearchQueryChange = { drawerSearchQuery = it },
                    loading = workspaceLoading || workspaceSyncing,
                    error = workspaceError,
                    onNewWorkspace = {
                        // 侧边栏“聊天”按钮：返回当前工作区的聊天，而不是新建工作区（新建仍可用顶部“+新工作区”）。
                        returnToChatHome(clearInput = false, clearWorkspace = false)
                        drawerSearchQuery = ""
                        scope.launch { drawerState.close() }
                    },
                    onOpenWorkspace = { workspace -> openWorkspace(workspace) },
                    onRefreshWorkspaces = {
                        agentDispatchWorkspaceRefresh(
                            mode = agentDrawerWorkspaceRefreshMode(),
                            refreshRemote = { scope.launch { syncRemoteWorkspaces() } },
                            reloadLocal = { reloadLocalWorkspaces() }
                        )
                    },
                    onOpenWorkspaceMenu = { target -> workspaceActionTarget = target },
                        onCloseDrawer = {
                            scope.launch { drawerState.close() }
                        },
                        onFeaturePageChange = { drawerFeaturePageOpen = it },
                    onRequestAiReview = { record ->
                        val target = workspaces.firstOrNull { workspaceHasRecord(it, record.id) }
                            ?: WorkspaceStore.loadWorkspaces(ctx).firstOrNull { workspaceHasRecord(it, record.id) }
                        target?.let {
                            activeWorkspace = it
                            ensureWorkspaceChatLoaded(it)
                            pendingAiReviewScrollWorkspaceId = it.id
                        }
                        val canAnalyze = target?.let { workspace ->
                            val decision = agentAiReviewEntryDecision(
                                stored = viewModel.getHistoryRecord(ctx, record.id),
                                requested = record,
                                existingMessages = chatMessagesByWorkspace[workspace.id].orEmpty()
                            )
                            if (decision.statusMessage != null) {
                                if (decision.shouldAppendStatusMessage) {
                                    appendChat(
                                        AgentChatRole.Agent,
                                        decision.statusMessage,
                                        workspace,
                                        scrollToBottom = true
                                    )
                                }
                                false
                            } else {
                                viewModel.activateWorkspace(workspace.id)
                                viewModel.analyzeRecord(ctx, decision.record)
                                true
                            }
                        } ?: false
                        if (canAnalyze) {
                            chatBottomScrollRequest += 1
                        }
                        scope.launch { drawerState.close() }
                    },
                    authSession = authSession,
                    onLogout = onLogout,
                    onAccountDeleted = onAccountDeleted,
                    onSessionUpdated = onSessionUpdated
                )
                AgentDrawerEdgeShadow(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                )
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AgentAiBackdrop(Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize()) {
                    AgentAppHeader(
                        workspaceTitle = agentVisibleWorkspaceTitle(activeWorkspace),
                        hasActiveWorkspace = activeWorkspace != null,
                        onMenu = { scope.launch { drawerState.open() } },
                        onNewWorkspace = {
                            returnToChatHome(clearInput = true)
                        }
                    )
                    AgentMainStage(
                            modifier = Modifier.weight(1f),
                        activeWorkspace = activeWorkspace,
                        messages = chatMessagesByWorkspace[chatKey()].orEmpty(),
                        chatScrollState = activeChatScrollState,
                        history = history,
                        state = state,
                        activeViewModelWorkspaceId = viewModel.activeWorkspaceIdSnapshot(),
                        input = chatInput,
                        onInputChange = { updateChatInput(it) },
                        pendingAttachments = pendingChatAttachments,
                        onRemoveAttachment = { index -> pendingChatAttachments.removeAt(index) },
                        onStopGenerating = { stopAgentReply() },
                        replyMode = if (roleplayActiveKey != null) "roleplay" else "text",
                        voiceProfile = agentVoiceProfile,
                        recording = recordingFile != null,
                        loading = workspaceLoading,
                        reconnecting = reconnectingAgent,
                        practiceCardGenerating = agentPracticeCardGenerating,
                        genStageText = agentGenStageText,
                        streamingReplyText = agentStreamingReply,
                        analysisLoading = agentShouldShowChatAnalysisLoading(
                            analysisInProgressRecordId = analysisInProgressRecordId,
                            activeWorkspace = activeWorkspace,
                            activeViewModelWorkspaceId = viewModel.activeWorkspaceIdSnapshot(),
                            existingMessages = chatMessagesByWorkspace[chatKey()].orEmpty()
                        ),
                        error = workspaceError,
                        scrollToBottomRequest = chatBottomScrollRequest,
                        onScrollToBottomRequestHandled = { chatBottomScrollRequest = 0 },
                        cardTopAnchorMessageId = chatCardTopAnchorRequest,
                        onCardTopAnchorHandled = { chatCardTopAnchorRequest = null },
                        viewModel = viewModel,
                        onSend = {
                            if (roleplayActiveKey != null) {
                                sendRoleplayMessage(chatInput)
                            } else if (!workspaceLoading) {
                                val outgoing = pendingChatAttachments.toList()
                                pendingChatAttachments.clear()
                                routeAgentMessage(chatInput, outgoing)
                            }
                        },
                        onCreateWorkspace = {
                            if (roleplayActiveKey != null) {
                                sendRoleplayMessage(chatInput)
                            } else if (!workspaceLoading) {
                                val outgoing = pendingChatAttachments.toList()
                                pendingChatAttachments.clear()
                                routeAgentMessage(chatInput, outgoing)
                            }
                        },
                        onRetryAfterError = { reconnectAgentAfterError() },
                        onRetryMessage = { failedMessage -> retryFailedUserMessage(failedMessage) },
                        onDeleteMessages = { ids -> deleteChatMessages(ids) },
                        onAttachFile = { mimeType -> filePickerLauncher.launch(mimeType) },
                        onVoicePressStart = { startAgentVoiceRecordingWithPermission() },
                        onVoicePressEnd = { stopAgentVoiceRecordingAndSend() },
                        onVoicePressCancel = { cancelAgentVoiceRecording() },
                        onVoiceConvertToText = { stopAgentVoiceRecordingAndTranscribe() },
                        onToggleReplyMode = { if (roleplayActiveKey != null) endRoleplay() else startRoleplay("", "", resume = false) },
                        onOpenVoiceSettings = { showRoleplaySettings = true },
                        roleplayActive = roleplayActiveKey != null,
                        roleplayScenario = roleplayScenario,
                        roleplayGoal = roleplayGoal,
                        roleplayMessages = roleplayMessages,
                        roleplayLoading = roleplayLoading,
                        roleplayTtsBusy = roleplayTtsBusy,
                        onRoleplayReplay = { playRoleplayTts(it) },
                        onRoleplayResume = { sc, goal -> startRoleplay(sc, goal, resume = true) },
                        onRequestAiReview = { record -> requestAiReviewFromChat(record) },
                        onMicroCardGraded = { messageId, sheetJson -> attachMicroAnswersToMessage(messageId, sheetJson) },
                        onMicroCardAnalyze = { messageId, sheetJson -> requestMicroAnalysisFromCard(messageId, sheetJson) }
                    )
                }
            }
        }
    }
}

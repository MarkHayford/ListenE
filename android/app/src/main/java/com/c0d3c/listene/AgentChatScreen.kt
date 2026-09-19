package com.c0d3c.listene

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import android.widget.Toast
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 聊天主屏（Chat Screen）：聊天整屏 + 选择栏 + 选择操作。从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentChatScreen(
    activeWorkspace: LearningWorkspace?,
    messages: List<AgentChatMessage>,
    scrollState: ScrollState,
    history: List<HistoryRecord>,
    state: GenerationState,
    activeViewModelWorkspaceId: String?,
    viewModel: ListeningViewModel,
    input: String,
    onInputChange: (String) -> Unit,
    replyMode: String,
    voiceProfile: String,
    recording: Boolean,
    loading: Boolean,
    analysisLoading: Boolean,
    reconnecting: Boolean,
    practiceCardGenerating: Boolean,
    genStageText: String = "",
    streamingReplyText: String = "",
    error: String?,
    pendingAttachments: List<AgentInputAttachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    onStopGenerating: () -> Unit = {},
    scrollToBottomRequest: Int,
    onScrollToBottomRequestHandled: () -> Unit,
    cardTopAnchorMessageId: Long?,
    onCardTopAnchorHandled: () -> Unit,
    onSend: () -> Unit,
    onCreateWorkspace: () -> Unit,
    onRetryAfterError: () -> Unit,
    onAttachFile: (String) -> Unit,
    onVoicePressStart: () -> Boolean,
    onVoicePressEnd: () -> Unit,
    onVoicePressCancel: () -> Unit,
    onVoiceConvertToText: () -> Unit = {},
    onToggleReplyMode: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onRequestAiReview: (HistoryRecord) -> Unit,
    onMicroCardGraded: (Long, String) -> Unit = { _, _ -> },
    onMicroCardAnalyze: (Long, String) -> Unit = { _, _ -> },
    onRetryMessage: (AgentChatMessage) -> Unit,
    onDeleteMessages: (Set<Long>) -> Unit,
    roleplayActive: Boolean = false,
    roleplayScenario: String = "",
    roleplayGoal: String = "",
    roleplayMessages: List<RoleplayMessage> = emptyList(),
    roleplayLoading: Boolean = false,
    roleplayTtsBusy: String = "",
    onRoleplayReplay: (String) -> Unit = {},
    onRoleplayResume: (String, String) -> Unit = { _, _ -> }
) {
    val currentRecord = remember(activeWorkspace, history, state, activeViewModelWorkspaceId) {
        agentCardCurrentRecord(activeWorkspace, history, state, activeViewModelWorkspaceId)
    }
    // 听力素材生成·实时进度：非空且属当前工作区时，在消息流底部渲染进度条卡（替代静态「素材生成中」）。
    val materialProgress by viewModel.materialProgress.collectAsState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeBottomDp = with(density) { imeBottomPx.toDp() }
    val imeVisible = imeBottomPx > 0
    var chatInputFocused by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val selectionContext = LocalContext.current
    var selectionMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(setOf<Long>()) }
    fun exitSelection() {
        selectionMode = false
        selectedMessageIds = emptySet()
    }
    fun toggleMessageSelection(id: Long) {
        selectedMessageIds = if (selectedMessageIds.contains(id)) selectedMessageIds - id else selectedMessageIds + id
        if (selectedMessageIds.isEmpty()) selectionMode = false
    }
    LaunchedEffect(activeWorkspace?.id) { exitSelection() }
    LaunchedEffect(scrollToBottomRequest) {
        if (scrollToBottomRequest > 0) {
            scrollState.animateToSettledBottom()
            onScrollToBottomRequestHandled()
        }
    }
    LaunchedEffect(imeVisible, chatInputFocused) {
        if (agentShouldAutoScrollOnIme(imeVisible, chatInputFocused)) {
            scrollState.animateToSettledBottom(initialDelayMillis = 260)
        }
    }
    // 流式上屏期间贴底跟随：用户在底部附近时随增量滚动；翻上去看历史则不打扰。
    LaunchedEffect(streamingReplyText.length) {
        if (streamingReplyText.isNotBlank() && scrollState.maxValue - scrollState.value <= 320) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    fun anchorCardMessageAtTop(messageId: Long, yInRoot: Float) {
        if (cardTopAnchorMessageId != messageId) return
        val target = (scrollState.value + yInRoot.roundToInt() - 92).coerceAtLeast(0)
        scope.launch {
            delay(120)
            scrollState.animateScrollTo(target.coerceAtMost(scrollState.maxValue))
            onCardTopAnchorHandled()
        }
    }

    val showWelcome = messages.isEmpty() && !loading && !analysisLoading && error == null && !roleplayActive
    // 回到底部悬浮球：离底部较远时出现；期间有新消息落底则带上小圆点提示。
    val nearBottom by remember {
        derivedStateOf { scrollState.maxValue - scrollState.value <= 240 }
    }
    var unseenWhileAway by remember { mutableStateOf(false) }
    var lastMessageCount by remember { mutableIntStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > lastMessageCount && !nearBottom) unseenWhileAway = true
        lastMessageCount = messages.size
    }
    LaunchedEffect(nearBottom) {
        if (nearBottom) unseenWhileAway = false
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                messages.forEach { message ->
                    AnimatedVisibility(
                        visible = true,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            if (message.microCardJson.isNotBlank() || message.solveJson.isNotBlank() || message.roleplayJson.isNotBlank() || message.listeningRecordId.isNotBlank() || message.shadowingJson.isNotBlank()) {
                                anchorCardMessageAtTop(message.id, coordinates.positionInRoot().y)
                            }
                        },
                        enter = fadeIn(tween(180)) + slideInVertically(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            initialOffsetY = { it / 5 }
                        ),
                        exit = fadeOut(tween(120))
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AgentChatBubble(
                                message,
                                onRetry = { onRetryMessage(message) },
                                selectionMode = selectionMode,
                                selected = selectedMessageIds.contains(message.id),
                                onToggleSelect = { toggleMessageSelection(message.id) },
                                onEnterMultiSelect = {
                                    selectionMode = true
                                    selectedMessageIds = selectedMessageIds + message.id
                                },
                                onDeleteMessage = { onDeleteMessages(setOf(message.id)) }
                            )
                            if (message.listeningRecordId.isNotBlank()) {
                                // 听力素材锚点：自包含听力微元（cardSpec 退役后的作答入口）。
                                val anchorRecord = remember(message.id, message.listeningRecordId, history, currentRecord) {
                                    history.firstOrNull { it.id == message.listeningRecordId } ?: currentRecord
                                }
                                if (anchorRecord != null) {
                                    AgentSurface {
                                        ListeningMicroCardView(
                                            record = anchorRecord,
                                            instanceKey = "message_listening:${message.id}",
                                            viewModel = viewModel,
                                            onRequestAiReview = onRequestAiReview
                                        )
                                    }
                                }
                            }
                            if (message.microCardJson.isNotBlank()) {
                                val microCard = remember(message.id, message.microCardJson) {
                                    MicroCardParser.parse(message.microCardJson)
                                }
                                if (microCard != null && microCard.nodes.isNotEmpty()) {
                                    AgentSurface {
                                        CompositionLocalProvider(
                                            LocalAgentMicroOnGraded provides { sheetJson -> onMicroCardGraded(message.id, sheetJson) },
                                            LocalAgentMicroOnRequestSheetAnalysis provides { sheetJson -> onMicroCardAnalyze(message.id, sheetJson) }
                                        ) {
                                            MicroCardView(microCard, instanceKey = "message_micro:${message.id}")
                                        }
                                    }
                                }
                            }
                            if (message.solveJson.isNotBlank()) {
                                val solve = remember(message.id, message.solveJson) {
                                    parseJsonObjectOrNull(message.solveJson)?.let { parseSolveResult(it) }
                                }
                                if (solve != null && !solve.isEmpty) {
                                    AgentSurface {
                                        AgentSolveInlineResult(solve = solve, messageId = message.id)
                                    }
                                }
                            }
                            if (message.roleplayJson.isNotBlank()) {
                                AgentRoleplayFinishedCard(json = message.roleplayJson, onResume = onRoleplayResume)
                            }
                            if (message.shadowingJson.isNotBlank()) {
                                val shadowing = remember(message.id, message.shadowingJson) {
                                    parseShadowingCardJson(message.shadowingJson)
                                }
                                if (shadowing != null) {
                                    AgentSurface {
                                        AgentCardShadowingComponent(shadowing)
                                    }
                                }
                            }
                        }
                    }
                }
                if (roleplayActive) {
                    AgentRoleplayActivePanel(
                        scenario = roleplayScenario,
                        goal = roleplayGoal,
                        messages = roleplayMessages,
                        loading = roleplayLoading,
                        ttsBusyContent = roleplayTtsBusy,
                        onReplay = onRoleplayReplay
                    )
                }
                // 正文流式上屏：delta 到达后打字机式增长，完成后由正式消息接替（此块随 loading 结束消失）。
                // 练习卡请求例外：后端会先把「好的，练习卡来了」这句 reply 流式发完，再花较长时间生成微元卡才发 done。
                // 若此时把这句话上屏，就会把进度卡顶掉、随后长时间干等。故练习卡生成期间不预显这句，交由进度卡占位，
                // 待卡片随 done 到达后再作为该消息的正文一起出现。
                if (loading && streamingReplyText.isNotBlank() && !practiceCardGenerating) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text(
                            streamingReplyText,
                            color = MaterialTheme.colorScheme.onSurface,
                            // 与最终气泡正文同字号（bodyLarge/16sp），流式打字与落定后大小一致、不跳变。
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                // 生成进度：练习卡生成期间持续显示进度卡（带阶段文案+已用时），直到卡片随 done 到达才消失；
                // 非练习卡且尚无流式正文时才退回「正在思考中」。
                if (agentShouldShowChatThinkingLoading(loading, analysisLoading)) {
                    when {
                        practiceCardGenerating -> AgentGeneratingCard(reconnecting = reconnecting, stageText = genStageText)
                        streamingReplyText.isBlank() -> AgentThinkingText(if (reconnecting) "正在尝试重连..." else "正在思考中...")
                    }
                }
                // 听力素材生成中：实时进度条（阶段+百分比+已用时），生成完成后由结果卡在下方接替。
                materialProgress?.takeIf { it.workspaceId == activeWorkspace?.id }?.let { mp ->
                    AgentMaterialProgressCard(stage = mp.stage, percent = mp.percent)
                }
                if (analysisLoading) AgentThinkingText("AI 正在分析中...")
                error?.let {
                    AgentMessagePanel(
                        agentConnectionFailureTitle(),
                        agentConnectionFailureMessage(it),
                        agentConnectionRetryButtonText(),
                        onRetryAfterError
                    )
                }
                Spacer(Modifier.height(AgentChatMessageBottomSpacer + imeBottomDp))
            }
            if (showWelcome) {
                AgentChatEmptyState(
                    activeWorkspace = activeWorkspace,
                    onPromptClick = onInputChange,
                    modifier = Modifier
                        .matchParentSize()
                        .padding(start = 22.dp, end = 22.dp, bottom = AgentChatMessageBottomSpacer)
                )
            }
        }
        AgentChatInputBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            value = input,
            enabled = true,
            generating = loading,
            replyMode = replyMode,
            voiceProfile = voiceProfile,
            recording = recording,
            placeholder = if (loading) "正在回复中，可继续输入…" else "想学些什么呢？",
            pendingAttachments = pendingAttachments,
            onRemoveAttachment = onRemoveAttachment,
            onStopGenerating = onStopGenerating,
            onValueChange = onInputChange,
            onSend = if (activeWorkspace == null) onCreateWorkspace else onSend,
            onAttachFile = onAttachFile,
            onVoicePressStart = onVoicePressStart,
            onVoicePressEnd = onVoicePressEnd,
            onVoicePressCancel = onVoicePressCancel,
            onVoiceConvertToText = onVoiceConvertToText,
            onFocusChanged = { chatInputFocused = it },
            onToggleReplyMode = onToggleReplyMode,
            onOpenVoiceSettings = onOpenVoiceSettings
        )
        AnimatedVisibility(
            visible = !nearBottom && !showWelcome,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .imePadding()
                .padding(end = 16.dp, bottom = 128.dp),
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.82f, animationSpec = tween(160, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.82f, animationSpec = tween(120, easing = FastOutSlowInEasing))
        ) {
            Box {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .agentSoftShadow(cornerRadius = 21.dp, alpha = 0.10f, blur = 14.dp, spread = 2.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable {
                            unseenWhileAway = false
                            scope.launch { scrollState.animateToSettledBottom(initialDelayMillis = 0) }
                        }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = "回到底部",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (unseenWhileAway) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface)
                        )
                    }
                }
            }
        }
        if (selectionMode) {
            AgentChatSelectionBar(
                count = selectedMessageIds.size,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
                onCopy = {
                    val text = messages
                        .filter { selectedMessageIds.contains(it.id) }
                        .joinToString("\n\n") { msg ->
                            val display = if (msg.role == AgentChatRole.Agent)
                                sanitizeAgentChatDisplayText(msg.text)
                            else msg.text
                            display.ifBlank { msg.text }
                        }
                        .trim()
                    if (text.isNotBlank()) {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("listene", text)))
                        }
                        Toast.makeText(selectionContext, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    exitSelection()
                },
                onDelete = {
                    onDeleteMessages(selectedMessageIds)
                    exitSelection()
                },
                onCancel = { exitSelection() }
            )
        }
    }
}

@Composable
private fun AgentChatSelectionBar(
    count: Int,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth(0.94f)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(14.dp),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.07f)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "已选 $count 项",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            AgentChatSelectionAction(text = "取消", enabled = true, onClick = onCancel)
            AgentChatSelectionAction(text = "复制", enabled = count > 0, onClick = onCopy)
            AgentChatSelectionAction(text = "删除", enabled = count > 0, danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun AgentChatSelectionAction(
    text: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

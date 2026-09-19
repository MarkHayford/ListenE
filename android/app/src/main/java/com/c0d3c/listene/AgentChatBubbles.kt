package com.c0d3c.listene

import android.content.ClipData
import android.net.Uri
import android.util.Base64
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import android.widget.Toast
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

// 聊天消息气泡（Chat Bubbles）：消息菜单 + 气泡 + 语音/文件附件气泡 + 附件预览。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
private fun AgentChatMessageMenu(
    canCopyText: Boolean,
    alignEnd: Boolean,
    onDismiss: () -> Unit,
    onCopyWhole: () -> Unit,
    onSelectText: () -> Unit,
    onMultiSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val menuWidth = 180.dp
    val menuCorner = 20.dp
    val gapPx = with(density) { 6.dp.roundToPx() }
    val marginPx = with(density) { 10.dp.roundToPx() }
    var appeared by remember { mutableStateOf(false) }
    // 紧贴被长按的那一条消息：基于该条锚点边界定位（下方优先，空间不足翻到上方）。
    val positionProvider = remember(alignEnd, gapPx, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
                var y = anchorBounds.bottom + gapPx
                if (y + popupContentSize.height > windowSize.height - marginPx) {
                    val above = anchorBounds.top - popupContentSize.height - gapPx
                    if (above >= marginPx) y = above
                }
                return IntOffset(
                    x.coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)),
                    y.coerceIn(marginPx, (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
                )
            }
        }
    }
    LaunchedEffect(Unit) { appeared = true }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
    ) {
        val enterProgress by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            label = "chatMessageMenuEnter"
        )
        Surface(
            shape = RoundedCornerShape(menuCorner),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                .width(menuWidth)
                .graphicsLayer {
                    alpha = enterProgress
                    scaleX = 0.96f + enterProgress * 0.04f
                    scaleY = 0.96f + enterProgress * 0.04f
                    transformOrigin = TransformOrigin(if (alignEnd) 0.82f else 0.18f, 0f)
                }
                .agentSoftShadow(
                    cornerRadius = menuCorner,
                    alpha = 0.060f,
                    blur = 18.dp,
                    spread = 1.dp
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (canCopyText) {
                    AgentWorkspaceActionMenuRow(text = "复制整条", onClick = onCopyWhole)
                    AgentWorkspaceActionMenuRow(text = "选择文字", onClick = onSelectText)
                }
                AgentWorkspaceActionMenuRow(text = "多选", onClick = onMultiSelect)
                AgentWorkspaceActionMenuRow(text = "删除", tint = AgentPracticeWrong, onClick = onDelete)
            }
        }
    }
}

@Composable
internal fun AgentChatBubble(
    message: AgentChatMessage,
    onRetry: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onEnterMultiSelect: () -> Unit = {},
    onDeleteMessage: () -> Unit = {}
) {
    val isUser = message.role == AgentChatRole.User
    val userVoiceOnly = isUser &&
        message.text.isBlank() &&
        message.attachments.isNotEmpty() &&
        message.attachments.all { it.isAgentVoiceMessage() }
    val displayText = if (isUser) message.text else sanitizeAgentChatDisplayText(message.text)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember(message.id) { mutableStateOf(false) }
    var selectableText by remember(message.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                else Modifier
            )
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() },
                onLongClick = {
                    if (selectionMode) onToggleSelect()
                    else if (!selectableText) menuExpanded = true
                }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(22.dp)
                    .then(
                        if (selected) Modifier
                        else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已选择",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
        val availableWidth = this.maxWidth
        val userBubbleMaxWidth = availableWidth * if (message.attachments.isEmpty()) 0.78f else 0.84f
        val userVoiceMaxWidth = availableWidth * 0.74f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isUser && message.failed) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "重新发送",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable(onClick = onRetry)
                        .size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (userVoiceOnly) {
                AgentAttachmentList(
                    attachments = message.attachments,
                    modifier = Modifier.widthIn(max = userVoiceMaxWidth)
                )
            } else if (isUser) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)),
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .widthIn(min = 44.dp, max = userBubbleMaxWidth)
                        .shadow(
                            elevation = 14.dp,
                            shape = RoundedCornerShape(18.dp),
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.035f),
                            spotColor = Color.Black.copy(alpha = 0.050f)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (message.text.isNotBlank()) {
                            AgentSelectableText(message.text, selectableText, onDoneSelecting = { selectableText = false })
                        }
                        AgentAttachmentList(message.attachments)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (displayText.isNotBlank()) {
                        AgentSelectableText(displayText, selectableText, onDoneSelecting = { selectableText = false })
                    }
                    if (message.audioUrl.isNotBlank()) {
                        AgentAudioReplyPlayer(message.audioUrl)
                    }
                    AgentAttachmentList(message.attachments)
                }
            }
        }
            if (menuExpanded) {
                AgentChatMessageMenu(
                    canCopyText = displayText.isNotBlank(),
                    alignEnd = isUser,
                    onDismiss = { menuExpanded = false },
                    onCopyWhole = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("listene", displayText)))
                        }
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        menuExpanded = false
                    },
                    onSelectText = {
                        selectableText = true
                        menuExpanded = false
                    },
                    onMultiSelect = {
                        menuExpanded = false
                        onEnterMultiSelect()
                    },
                    onDelete = {
                        menuExpanded = false
                        onDeleteMessage()
                    }
                )
            }
    }
    }
}

@Composable
private fun AgentAttachmentList(
    attachments: List<AgentInputAttachment>,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { attachment ->
            if (attachment.isAgentVoiceMessage()) {
                AgentVoiceMessageBubble(attachment)
            } else {
                AgentFileAttachmentBubble(attachment)
            }
        }
    }
}

@Composable
private fun AgentVoiceMessageBubble(attachment: AgentInputAttachment) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<AgentExoAudio?>(null) }
    var playing by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun releasePlayer() {
        player?.release()
        player = null
        playing = false
    }

    fun playVoice() {
        val file = File(attachment.localPath)
        if (!file.exists()) {
            AppNoticeBus.error("录音文件不存在")
            return
        }
        when {
            playing -> {
                player?.pause()
                playing = false
            }
            player != null -> {
                player?.start()
                playing = true
            }
            else -> {
                runCatching {
                    val mp = AgentExoAudio(ctx)
                    mp.onPrepared = {
                        playing = true
                        mp.start()
                    }
                    mp.onCompletion = { releasePlayer() }
                    mp.onError = {
                        error = "播放失败"
                        releasePlayer()
                    }
                    mp.setDataSource(Uri.fromFile(file))
                    mp.prepare()
                    mp
                }.onSuccess {
                    player = it
                    error = ""
                }.onFailure {
                    error = it.message ?: "播放失败"
                    releasePlayer()
                }
            }
        }
    }

    fun transcribeVoice() {
        if (transcribing) return
        scope.launch {
            transcribing = true
            error = ""
            val base64 = attachment.base64.ifBlank {
                runCatching {
                    withContext(Dispatchers.IO) {
                        Base64.encodeToString(FileInputStream(File(attachment.localPath)).use { it.readBytes() }, Base64.NO_WRAP)
                    }
                }.getOrDefault("")
            }
            if (base64.isBlank()) {
                error = "无法读取录音"
                transcribing = false
                return@launch
            }
            transcript = runCatching {
                AgentConversationService.transcribeAudio(base64, attachment.mimeType)
            }.getOrElse {
                error = it.message ?: "转文字失败"
                ""
            }.ifBlank { "未识别到文字" }
            transcribing = false
        }
    }

    DisposableEffect(Unit) { onDispose { releasePlayer() } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(22.dp),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.035f),
                spotColor = Color.Black.copy(alpha = 0.055f)
            )
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)),
                RoundedCornerShape(22.dp)
            )
            .pointerInput(attachment.localPath, attachment.base64, transcribing) {
                detectTapGestures(
                    onTap = { playVoice() },
                    onLongPress = { transcribeVoice() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface
            ) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "暂停录音" else "播放录音",
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier
                            .size(17.dp)
                            .then(if (playing) Modifier else Modifier.offset(x = 1.dp))
                    )
                }
            }
            AgentVoiceWaveform(Modifier.weight(1f), animated = playing)
            Text(
                if (transcribing) "转写中" else "语音",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (transcript.isNotBlank()) {
            Text(
                transcript,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun AgentVoiceWaveform(
    modifier: Modifier = Modifier,
    animated: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "agentVoiceWaveform")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "agentVoiceWavePulse"
    )
    Row(
        modifier = modifier.height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val bars = listOf(10.dp, 16.dp, 22.dp, 13.dp, 19.dp, 26.dp, 14.dp, 20.dp, 12.dp)
        bars.forEachIndexed { index, height ->
            val phase = if (animated) (pulse + index * 0.17f) % 1f else 0.5f
            val boost = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            val barHeight = if (animated) (height.value * (0.58f + boost * 0.50f)).dp else height
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (animated) 0.68f else 0.46f))
            )
        }
    }
}

@Composable
private fun AgentFileAttachmentBubble(attachment: AgentInputAttachment) {
    val ctx = LocalContext.current
    var previewOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "agentFileAttachmentPress"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(12.dp),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.030f),
                spotColor = Color.Black.copy(alpha = 0.045f)
            )
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f)),
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = attachment.localPath.isNotBlank()
            ) {
                previewOpen = true
            }
            .testTag(agentAttachmentFileTestTag(attachment.name))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.AttachFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                attachment.name.ifBlank { "attachment" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                buildString {
                    if (attachment.generated) append("Agent 生成 · ")
                    append(attachment.mimeType.ifBlank { "file" })
                    append(" · ")
                    append(formatAgentFileSize(attachment.sizeBytes))
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (previewOpen) {
        AgentAttachmentPreviewDialog(
            attachment = attachment,
            onDismiss = { previewOpen = false },
            onOpenExternal = { openAgentLocalAttachment(ctx, attachment) },
            onExport = { exportAgentAttachmentFile(ctx, attachment) },
            onShare = { shareAgentAttachmentFile(ctx, attachment) }
        )
    }
}

@Composable
private fun AgentAttachmentPreviewDialog(
    attachment: AgentInputAttachment,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    val asset = remember(attachment.localPath, attachment.name, attachment.mimeType, attachment.sizeBytes) {
        attachment.toUserLibraryFileAsset()
    }
    val previewMode = remember(asset) { agentAttachmentPreviewMode(asset) }
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Default.AttachFile,
                title = asset.name.ifBlank { "文件预览" },
                subtitle = formatAgentFilePreviewMeta(asset, System.currentTimeMillis())
            )
            AgentFilePreviewActions(
                onOpenExternal = onOpenExternal,
                onDownload = onExport,
                onExport = onExport,
                onShare = onShare
            )
            AgentFilePreviewContent(
                asset = asset,
                previewMode = previewMode,
                onOpenExternal = onOpenExternal
            )
            AgentTextAction("关闭", onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AgentAudioReplyPlayer(audioUrl: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("语音回复", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
            AgentAudioPlayer(audioUrl)
        }
    }
}

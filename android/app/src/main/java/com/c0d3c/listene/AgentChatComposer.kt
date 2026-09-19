package com.c0d3c.listene

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 聊天输入区（Composer）：输入栏 + 附件菜单 + 回复/发送 + 角色扮演内联/设置 + 按住说话。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

// 按住说话·松手落点：SEND=直接发语音；CANCEL=取消发送；CONVERT=语音转文字（识别进输入框）。
internal enum class VoiceGestureZone { SEND, CANCEL, CONVERT }

// 手指窗口坐标 → 落点区域：手指升入「区域带」（两区下沿 + 松弛）后，按 x 就近划归左(取消)/右(转文字)；
// 仍停在按钮附近（未升起）则为 SEND。两区任一未测量到 → SEND（安全兜底）。
internal fun voiceZoneForPointer(finger: Offset?, cancel: Rect?, convert: Rect?, slackPx: Float): VoiceGestureZone {
    if (finger == null || cancel == null || convert == null) return VoiceGestureZone.SEND
    val armLine = maxOf(cancel.bottom, convert.bottom) + slackPx
    if (finger.y > armLine) return VoiceGestureZone.SEND
    val toCancel = abs(finger.x - cancel.center.x)
    val toConvert = abs(finger.x - convert.center.x)
    return if (toCancel <= toConvert) VoiceGestureZone.CANCEL else VoiceGestureZone.CONVERT
}

@Composable
internal fun AgentChatInputBar(
    modifier: Modifier = Modifier,
    value: String,
    enabled: Boolean,
    replyMode: String,
    voiceProfile: String,
    recording: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachFile: (String) -> Unit,
    onVoicePressStart: () -> Boolean,
    onVoicePressEnd: () -> Unit,
    onVoicePressCancel: () -> Unit,
    onVoiceConvertToText: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit,
    onToggleReplyMode: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    generating: Boolean = false,
    pendingAttachments: List<AgentInputAttachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    onStopGenerating: () -> Unit = {}
) {
    var inputFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible, value) {
        if (!imeVisible && value.isBlank()) {
            inputFocused = false
            focusManager.clearFocus()
        }
    }
    val expanded = inputFocused || imeVisible || value.isNotBlank() || pendingAttachments.isNotEmpty()
    val widthFraction by animateFloatAsState(
        targetValue = if (expanded) 0.94f else 0.86f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "agentInputWidth"
    )
    val bottomPadding by animateDpAsState(
        targetValue = if (expanded) 12.dp else 28.dp,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "agentInputBottomPadding"
    )
    val fieldMinHeight by animateDpAsState(
        targetValue = if (expanded) 56.dp else 52.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "agentInputMinHeight"
    )
    val fieldCorner by animateDpAsState(
        targetValue = if (expanded) 30.dp else 28.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "agentInputCorner"
    )
    val actionButtonSize by animateDpAsState(
        targetValue = if (expanded) 44.dp else 42.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "agentInputActionSize"
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (expanded) 0.07f else 0.05f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "agentInputShadowAlpha"
    )
    val fieldContainerAlpha by animateFloatAsState(
        targetValue = if (expanded) 0.92f else 0.85f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "agentInputContainerAlpha"
    )
    var voicePressActive by remember { mutableStateOf(false) }
    // 按住说话手势的当前落点区域（SEND/CANCEL/CONVERT）；上滑到「取消发送 / 语音转文字」两区之一高亮。
    var voiceZone by remember { mutableStateOf(VoiceGestureZone.SEND) }
    val voiceDragCancel = voiceZone == VoiceGestureZone.CANCEL
    // 两区在窗口坐标系里的实测边界（供手势按手指位置就近判定）。
    var cancelZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var convertZoneBounds by remember { mutableStateOf<Rect?>(null) }
    val zoneArmSlackPx = with(density) { 28.dp.toPx() }
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    // 录音计时：录音激活时每秒累加，供录音态左侧显示 m:ss。
    val voiceActive = recording || voicePressActive
    var recordSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(voiceActive) {
        recordSeconds = 0
        if (voiceActive) {
            while (true) {
                delay(1000)
                recordSeconds += 1
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = bottomPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
        // 待发送附件预览条：可删、可继续补文字说明，点发送才一起发出。
        if (pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingAttachments.forEachIndexed { index, attachment ->
                    AgentPendingAttachmentChip(
                        attachment = attachment,
                        onRemove = { onRemoveAttachment(index) }
                    )
                }
            }
        }
        // 按住说话·上滑双区：录音中浮现「取消发送 / 语音转文字」两区，
        // 手指移到哪区哪区高亮、松手即执行该区动作；停在按钮上松手＝直接发语音。
        if (voicePressActive) {
            AgentVoiceGestureZones(
                activeZone = voiceZone,
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .padding(bottom = 8.dp),
                onCancelBounds = { cancelZoneBounds = it },
                onConvertBounds = { convertZoneBounds = it }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .agentSoftShadow(
                    cornerRadius = fieldCorner,
                    alpha = shadowAlpha,
                    blur = 20.dp,
                    spread = 3.dp
                )
                .agentSoftShadow(
                    cornerRadius = fieldCorner,
                    alpha = shadowAlpha * 0.55f,
                    blur = 8.dp,
                    spread = 1.dp
                )
                .clip(RoundedCornerShape(fieldCorner))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = fieldContainerAlpha))
                .border(
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (expanded) 0.5f else 0.4f)
                    ),
                    RoundedCornerShape(fieldCorner)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled
                ) {
                    inputFocused = true
                    onFocusChanged(true)
                    focusRequester.requestFocus()
                }
                .padding(start = 10.dp, end = 7.dp, top = 7.dp, bottom = 7.dp)
                .animateContentSize(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                AgentInputPlainIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "选择发送的文件",
                    onClick = {
                        focusManager.clearFocus()
                        inputFocused = false
                        onFocusChanged(false)
                        attachmentMenuExpanded = true
                    },
                    enabled = enabled && !generating,
                    size = actionButtonSize,
                    iconSize = 30.dp
                )
                AgentAttachmentTypeMenu(
                    expanded = attachmentMenuExpanded,
                    onDismiss = { attachmentMenuExpanded = false },
                    onPick = { mimeType ->
                        attachmentMenuExpanded = false
                        onAttachFile(mimeType)
                    }
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                enabled = enabled,
                // 软键盘回车=换行（微信式，支持多段输入），发送走右侧按钮；
                // 硬键盘 Enter=发送、Shift+Enter=换行（见 onPreviewKeyEvent）。
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                minLines = 1,
                maxLines = 6,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = fieldMinHeight)
                    .testTag("agent_chat_input")
                    .onPreviewKeyEvent { event ->
                        if (
                            event.key == Key.Enter &&
                            event.type == KeyEventType.KeyDown &&
                            !event.isShiftPressed &&
                            agentChatInputEnterKeyShouldSend(enabled && !generating, value)
                        ) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    }
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        inputFocused = it.isFocused
                        onFocusChanged(it.isFocused)
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (recording || voicePressActive) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    "%d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                AgentVoiceWaveform(
                                    modifier = Modifier.weight(1f),
                                    animated = !voiceDragCancel
                                )
                                Text(
                                    when (voiceZone) {
                                        VoiceGestureZone.CANCEL -> "松开取消"
                                        VoiceGestureZone.CONVERT -> "松开转文字"
                                        VoiceGestureZone.SEND -> "上滑选择"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else if (value.isBlank()) {
                            Text(
                                placeholder,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            innerTextField()
                        } else {
                            innerTextField()
                        }
                    }
                }
            )
            AgentHoldToTalkButton(
                recording = recording || voicePressActive,
                canceling = voiceDragCancel,
                enabled = enabled && !generating,
                size = actionButtonSize,
                iconSize = 24.dp,
                cancelBounds = { cancelZoneBounds },
                convertBounds = { convertZoneBounds },
                zoneArmSlackPx = zoneArmSlackPx,
                onPressStart = {
                    focusManager.clearFocus()
                    inputFocused = false
                    onFocusChanged(false)
                    val started = onVoicePressStart()
                    if (started) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    voicePressActive = started
                    voiceZone = VoiceGestureZone.SEND
                    started
                },
                onRelease = { zone ->
                    if (voicePressActive) {
                        when (zone) {
                            VoiceGestureZone.SEND -> onVoicePressEnd()
                            VoiceGestureZone.CANCEL -> onVoicePressCancel()
                            VoiceGestureZone.CONVERT -> onVoiceConvertToText()
                        }
                    }
                    voicePressActive = false
                    voiceZone = VoiceGestureZone.SEND
                },
                onZoneChange = { zone ->
                    if (voiceZone != zone) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    voiceZone = zone
                }
            )
            AgentReplyOrSendButton(
                hasText = value.isNotBlank() || pendingAttachments.isNotEmpty(),
                replyMode = replyMode,
                voiceProfile = voiceProfile,
                enabled = enabled,
                generating = generating,
                size = actionButtonSize,
                onSend = onSend,
                onStop = onStopGenerating,
                onToggleReplyMode = onToggleReplyMode,
                onOpenVoiceSettings = onOpenVoiceSettings
            )
        }
        // 生成式 AI 合规提示：常驻输入框下方的一行极小字。
        Text(
            "内容由 AI 生成，注意甄别",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
        }
    }
}

@Composable
private fun AgentAttachmentTypeMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val visibilityState = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) {
        visibilityState.targetState = expanded
    }
    if (!visibilityState.currentState && !visibilityState.targetState && visibilityState.isIdle) return
    val density = LocalDensity.current
    val menuScrollState = rememberScrollState()
    val shadowPadding = 22.dp
    val xOffset = with(density) { (-shadowPadding).roundToPx() }
    val yOffset = with(density) { (-34.dp - shadowPadding).roundToPx() }
    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(x = xOffset, y = yOffset),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        AnimatedVisibility(
            visibleState = visibilityState,
            enter = fadeIn(tween(durationMillis = 120, easing = FastOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 8 }
                ) +
                scaleIn(
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(0f, 1f)
                ),
            exit = fadeOut(tween(durationMillis = 90, easing = FastOutSlowInEasing)) +
                slideOutVertically(
                    animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
                    targetOffsetY = { it / 10 }
                ) +
                scaleOut(
                    animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
                    targetScale = 0.96f,
                    transformOrigin = TransformOrigin(0f, 1f)
                )
        ) {
            Box(
                modifier = Modifier
                    .padding(shadowPadding)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 1f)
                    }
            ) {
                Surface(
                    modifier = Modifier
                        .width(282.dp)
                        .heightIn(max = 352.dp)
                        .agentSoftShadow(
                            cornerRadius = 28.dp,
                            alpha = 0.085f,
                            blur = 28.dp,
                            spread = 6.dp
                        )
                        .agentSoftShadow(
                            cornerRadius = 28.dp,
                            alpha = 0.045f,
                            blur = 10.dp,
                            spread = 1.dp
                        ),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(menuScrollState)
                                .padding(start = 14.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AgentAttachmentMenuItem(
                                icon = Icons.Default.Collections,
                                text = "照片",
                                onClick = { onPick("image/*") }
                            )
                            AgentAttachmentMenuItem(
                                icon = Icons.Default.AttachFile,
                                text = "文件",
                                onClick = { onPick("*/*") }
                            )
                            AgentAttachmentMenuItem(
                                icon = Icons.Default.VideoFile,
                                text = "视频",
                                onClick = { onPick("video/*") }
                            )
                            AgentAttachmentMenuItem(
                                icon = Icons.Default.GraphicEq,
                                text = "音频",
                                onClick = { onPick("audio/*") }
                            )
                        }
                        if (menuScrollState.maxValue > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 9.dp)
                                    .width(4.dp)
                                    .height(78.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentAttachmentMenuItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "agentAttachmentMenuItemPress"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .height(54.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AgentInputPlainIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    size: Dp,
    iconSize: Dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "agentInputPlainButtonPress"
    )
    Box(
        modifier = Modifier
            .scale(pressScale)
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun AgentReplyOrSendButton(
    hasText: Boolean,
    replyMode: String,
    voiceProfile: String,
    enabled: Boolean,
    size: Dp,
    onSend: () -> Unit,
    onToggleReplyMode: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    generating: Boolean = false,
    onStop: () -> Unit = {}
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "agentReplyOrSendPress"
    )
    val roleplayActive = replyMode == "roleplay"
    val container = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        generating -> MaterialTheme.colorScheme.surface
        hasText -> MaterialTheme.colorScheme.surface
        roleplayActive -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
    }
    Box(
        modifier = Modifier
            .scale(pressScale)
            .size(size)
            .testTag("agent_chat_send")
            .clip(CircleShape)
            .background(container)
            .pointerInput(enabled, hasText, replyMode, voiceProfile, generating) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onLongPress = {
                        if (enabled && !generating) onOpenVoiceSettings()
                    },
                    onTap = {
                        if (!enabled) return@detectTapGestures
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        when {
                            generating -> onStop()
                            hasText -> onSend()
                            else -> onToggleReplyMode()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            when {
                generating -> Icons.Default.Stop
                hasText -> AgentSendArrowUp
                roleplayActive -> Icons.Default.Cancel
                else -> Icons.Default.Forum
            },
            contentDescription = when {
                generating -> "停止生成"
                hasText -> "发送"
                roleplayActive -> "退出口语陪练"
                else -> "进入口语陪练，长按设置场景与音色"
            },
            tint = content,
            modifier = Modifier.size(if (hasText || generating) 24.dp else 25.dp)
        )
    }
}

// 待发送附件小片：图片显示缩略图，其它类型显示图标 + 文件名 + 大小，右侧 × 移除。
@Composable
private fun AgentPendingAttachmentChip(
    attachment: AgentInputAttachment,
    onRemove: () -> Unit
) {
    val isImage = attachment.mimeType.startsWith("image/", ignoreCase = true)
    val thumbnail = remember(attachment.base64, isImage) {
        if (!isImage || attachment.base64.isBlank()) return@remember null
        runCatching {
            val bytes = android.util.Base64.decode(attachment.base64, android.util.Base64.NO_WRAP)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 96 && bounds.outHeight / (sample * 2) >= 96) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 3.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when {
                            isImage -> Icons.Default.Collections
                            attachment.mimeType.startsWith("video/", ignoreCase = true) -> Icons.Default.VideoFile
                            attachment.mimeType.startsWith("audio/", ignoreCase = true) -> Icons.Default.GraphicEq
                            else -> Icons.Default.AttachFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    attachment.name.ifBlank { "attachment" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 128.dp)
                )
                Text(
                    formatAgentFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AgentInputPlainIconButton(
                icon = Icons.Default.Close,
                contentDescription = "移除附件 ${attachment.name}",
                onClick = onRemove,
                enabled = true,
                size = 28.dp,
                iconSize = 17.dp
            )
        }
    }
}

// 口语陪练设置面板（长按陪练键打开）：自定义场景/目标 + 推荐场景（点一下填入可再改）+ AI 音色，底部「开始陪练」。
@Composable
internal fun AgentRoleplaySetupPanel(
    voiceProfile: String,
    onVoiceProfileChange: (String) -> Unit,
    onStart: (scenario: String, goal: String) -> Unit,
    onClose: () -> Unit
) {
    var scenario by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    AgentFloatingPanel {
        AgentFloatingPanelHeader(
            icon = Icons.Default.Forum,
            title = "口语陪练设置",
            subtitle = "自定义场景与 AI 音色，或从推荐里挑一个。场景留空即自由聊天。"
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "场景",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AgentRoleplayInputField(
                    value = scenario,
                    onValueChange = { scenario = it },
                    placeholder = "自定义场景，如：在伦敦和房东沟通租房",
                    modifier = Modifier.fillMaxWidth()
                )
                AgentRoleplayInputField(
                    value = goal,
                    onValueChange = { goal = it },
                    placeholder = "本次目标（可选），如：练习礼貌地砍价",
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "推荐场景（点一下填入，可再改）",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    agentRoleplayScenarios.forEach { sc ->
                        AgentSegmentPill(
                            text = sc.label,
                            selected = scenario == sc.label,
                            onClick = {
                                scenario = sc.label
                                goal = sc.desc
                            }
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "AI 音色",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f))
                ) {
                    BasicTextField(
                        value = voiceProfile,
                        onValueChange = onVoiceProfileChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth()) {
                                if (voiceProfile.isBlank()) {
                                    Text(
                                        "例如：自然亲切的英式女声，语速适中。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        lineHeight = 20.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf("清晰温和", "沉稳克制", "自然亲近", "活力明快", "知性优雅").forEach { sample ->
                        AgentSegmentPill(
                            text = sample,
                            selected = voiceProfile == sample,
                            onClick = { onVoiceProfileChange(sample) }
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AgentTextAction(
                text = "关闭",
                onClick = onClose,
                modifier = Modifier.weight(1f),
                height = 44.dp
            )
            AgentTextAction(
                text = "开始陪练",
                onClick = { onStart(scenario.trim(), goal.trim()) },
                modifier = Modifier.weight(1f),
                height = 44.dp,
                primary = true
            )
        }
    }
}

// 按住说话上滑时浮现的双区面板：左「取消发送」右「语音转文字」；命中区高亮，各自上报窗口边界给手势命中判定。
@Composable
private fun AgentVoiceGestureZones(
    activeZone: VoiceGestureZone,
    modifier: Modifier = Modifier,
    onCancelBounds: (Rect) -> Unit,
    onConvertBounds: (Rect) -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AgentVoiceZoneChip(
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { onCancelBounds(it.boundsInWindow()) },
            icon = Icons.Default.Close,
            label = "取消发送",
            active = activeZone == VoiceGestureZone.CANCEL,
            activeColor = MaterialTheme.colorScheme.error
        )
        AgentVoiceZoneChip(
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { onConvertBounds(it.boundsInWindow()) },
            icon = Icons.Default.Keyboard,
            label = "语音转文字",
            active = activeZone == VoiceGestureZone.CONVERT,
            activeColor = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun AgentVoiceZoneChip(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color
) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.06f else 1f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "agentVoiceZoneChip"
    )
    val container = if (active) activeColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val content = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .border(
                BorderStroke(1.dp, if (active) activeColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                RoundedCornerShape(16.dp)
            )
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(24.dp))
        Text(
            label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AgentHoldToTalkButton(
    recording: Boolean,
    canceling: Boolean,
    enabled: Boolean,
    size: Dp,
    iconSize: Dp,
    cancelBounds: () -> Rect?,
    convertBounds: () -> Rect?,
    zoneArmSlackPx: Float,
    onPressStart: () -> Boolean,
    onRelease: (VoiceGestureZone) -> Unit,
    onZoneChange: (VoiceGestureZone) -> Unit
) {
    val pressScale by animateFloatAsState(
        targetValue = if (recording) 0.92f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "agentHoldToTalkPress"
    )
    // 本按钮在窗口坐标系里的定位，用于把手指的按钮内相对坐标换算成窗口绝对坐标，再与两区边界做命中判定。
    var buttonCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Surface(
        modifier = Modifier
            .scale(pressScale)
            .size(size)
            .clip(CircleShape)
            .onGloballyPositioned { buttonCoords = it }
            // 按下即录（无长按延迟），录音中跟踪手指绝对位置：升入「取消发送 / 语音转文字」两区之一则高亮，
            // 松手按落点执行（默认 SEND=直接发）。
            .pointerInput(enabled, zoneArmSlackPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) return@awaitEachGesture
                    val started = onPressStart()
                    if (!started) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    down.consume()
                    var zone = VoiceGestureZone.SEND
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        val fingerWindow = buttonCoords?.localToWindow(change.position)
                        val nextZone = voiceZoneForPointer(fingerWindow, cancelBounds(), convertBounds(), zoneArmSlackPx)
                        if (nextZone != zone) {
                            zone = nextZone
                            onZoneChange(zone)
                        }
                        change.consume()
                        if (event.changes.all { !it.pressed }) {
                            onRelease(zone)
                            finished = true
                        }
                    }
                }
            },
        shape = CircleShape,
        color = when {
            canceling -> MaterialTheme.colorScheme.surfaceVariant
            recording -> MaterialTheme.colorScheme.inverseSurface
            else -> Color.Transparent
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (canceling) Icons.Default.Cancel else Icons.Default.Mic,
                contentDescription = if (canceling) "松开取消语音" else "按住说话",
                tint = when {
                    canceling -> MaterialTheme.colorScheme.onSurfaceVariant
                    recording -> MaterialTheme.colorScheme.inverseOnSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
internal fun AgentPlayPauseButton(
    playing: Boolean,
    preparing: Boolean,
    size: Dp,
    progressSize: Dp,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = !preparing, onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (preparing) {
                CircularProgressIndicator(Modifier.size(progressSize), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(24.dp)
                        .then(if (playing) Modifier else Modifier.offset(x = 1.dp))
                )
            }
        }
    }
}

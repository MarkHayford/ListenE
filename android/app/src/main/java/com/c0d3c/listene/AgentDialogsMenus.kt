package com.c0d3c.listene

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

// 对话框与菜单（Dialogs/Menus）：语音设置 + 删除工作区 + 浮层头 + 各动作菜单弹窗。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
private fun AgentVoiceSettingsPanel(
    voiceProfile: String,
    onVoiceProfileChange: (String) -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit
) {
    AgentFloatingPanel {
        AgentFloatingPanelHeader(
            icon = Icons.Default.Tune,
            title = "自定义音色",
            subtitle = "AI 语音回复会按这里的描述发声。"
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "音色描述",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (voiceProfile.isBlank()) "默认音色" else "${voiceProfile.length} 字",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    )
                }
                BasicTextField(
                    value = voiceProfile,
                    onValueChange = onVoiceProfileChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 4,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (voiceProfile.isBlank()) {
                                Text(
                                    "例如：清晰克制，语速自然，适合英语陪练。",
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
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "快速选择",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(
                    "清晰温和",
                    "沉稳克制",
                    "自然亲近",
                    "活力明快",
                    "知性优雅"
                ).forEach { sample ->
                    AgentSegmentPill(
                        text = sample,
                        selected = voiceProfile == sample,
                        onClick = { onVoiceProfileChange(sample) }
                    )
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
                text = "保存",
                onClick = onSave,
                modifier = Modifier.weight(1f),
                primary = true,
                height = 44.dp
            )
        }
    }
}

@Composable
internal fun AgentDeleteWorkspaceDialog(
    workspace: LearningWorkspace,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Default.Delete,
                title = "删除工作区",
                subtitle = "这会移除工作区及关联的本地记录。",
                accent = AgentPracticeWrong
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AgentPracticeWrong.copy(alpha = 0.06f),
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, AgentPracticeWrong.copy(alpha = 0.22f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "将删除",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        workspace.title.ifBlank { "未命名工作区" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        agentWorkspaceDeleteConsequenceText(),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgentTextAction(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    height = 44.dp
                )
                AgentTextAction(
                    text = "删除工作区",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                    accent = AgentPracticeWrong,
                    filledAccent = true
                )
            }
        }
    }
}

@Composable
internal fun AgentFloatingPanel(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .agentSoftShadow(
                cornerRadius = 22.dp,
                alpha = 0.072f,
                blur = 22.dp,
                spread = 3.dp
            )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content
        )
    }
}

@Composable
internal fun AgentFloatingPanelHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun AgentWorkspaceActionMenuDialog(
    target: AgentWorkspaceActionMenuTarget,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToCategory: () -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var appeared by remember(target.workspace.id, target.anchorTopLeft) { mutableStateOf(false) }
    val menuWidth = 196.dp
    val menuCorner = 20.dp
    val horizontalMargin = 18.dp
    val topMargin = 14.dp
    val estimatedMenuHeight = 144.dp
    val offset = with(density) {
        val screenWidthPx = configuration.screenWidthDp.dp.roundToPx()
        val screenHeightPx = configuration.screenHeightDp.dp.roundToPx()
        val menuWidthPx = menuWidth.roundToPx()
        val estimatedMenuHeightPx = estimatedMenuHeight.roundToPx()
        val horizontalMarginPx = horizontalMargin.roundToPx()
        val topMarginPx = topMargin.roundToPx()
        val preferredX = target.anchorTopLeft.x + target.anchorSize.width - menuWidthPx - 6.dp.roundToPx()
        val preferredY = target.anchorTopLeft.y +
            (target.anchorSize.height / 2) -
            (estimatedMenuHeightPx / 2)
        IntOffset(
            x = preferredX.coerceIn(
                horizontalMarginPx,
                (screenWidthPx - menuWidthPx - horizontalMarginPx).coerceAtLeast(horizontalMarginPx)
            ),
            y = preferredY.coerceIn(
                topMarginPx,
                (screenHeightPx - estimatedMenuHeightPx - topMarginPx).coerceAtLeast(topMarginPx)
            )
        )
    }
    LaunchedEffect(target.workspace.id, target.anchorTopLeft) {
        appeared = true
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
    ) {
        val enterProgress by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            label = "workspaceActionMenuEnter"
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
                    transformOrigin = TransformOrigin(0.88f, 0.5f)
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
                AgentWorkspaceActionMenuRow(text = "移动到分类", onClick = onMoveToCategory)
                AgentWorkspaceActionMenuRow(
                    text = if (target.workspace.pinnedAt > 0L) "取消置顶" else "置顶",
                    onClick = onPin
                )
                AgentWorkspaceActionMenuRow(
                    text = "重命名",
                    onClick = onRename
                )
                AgentWorkspaceActionMenuRow(
                    text = "删除",
                    tint = AgentPracticeWrong,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
internal fun AgentLibraryCardActionMenuDialog(
    target: AgentLibraryCardActionMenuTarget,
    onDismiss: () -> Unit,
    onExportQuestionBank: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToCategory: () -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var appeared by remember(target.item.id, target.anchorTopLeft) { mutableStateOf(false) }
    val menuWidth = 196.dp
    val menuCorner = 20.dp
    val horizontalMargin = 18.dp
    val topMargin = 14.dp
    val estimatedMenuHeight = 188.dp
    val offset = with(density) {
        val screenWidthPx = configuration.screenWidthDp.dp.roundToPx()
        val screenHeightPx = configuration.screenHeightDp.dp.roundToPx()
        val menuWidthPx = menuWidth.roundToPx()
        val estimatedMenuHeightPx = estimatedMenuHeight.roundToPx()
        val horizontalMarginPx = horizontalMargin.roundToPx()
        val topMarginPx = topMargin.roundToPx()
        val preferredX = target.anchorTopLeft.x + target.anchorSize.width - menuWidthPx - 6.dp.roundToPx()
        val preferredY = target.anchorTopLeft.y +
            (target.anchorSize.height / 2) -
            (estimatedMenuHeightPx / 2)
        IntOffset(
            x = preferredX.coerceIn(
                horizontalMarginPx,
                (screenWidthPx - menuWidthPx - horizontalMarginPx).coerceAtLeast(horizontalMarginPx)
            ),
            y = preferredY.coerceIn(
                topMarginPx,
                (screenHeightPx - estimatedMenuHeightPx - topMarginPx).coerceAtLeast(topMarginPx)
            )
        )
    }
    LaunchedEffect(target.item.id, target.anchorTopLeft) {
        appeared = true
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
    ) {
        val enterProgress by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            label = "libraryCardActionMenuEnter"
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
                    transformOrigin = TransformOrigin(0.88f, 0.5f)
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
                val actions = agentLibraryCardMenuActions(
                    isPinned = userLibraryPinnedAt(target.item) > 0L,
                    canExportQuestionBank = userLibraryMicroCard(target.item)?.let(::microCardCanExportQuestionBank) == true
                )
                AgentWorkspaceActionMenuRow(text = "移动到分类", onClick = onMoveToCategory)
                actions.forEach { action ->
                    when (action.id) {
                        "export_question_bank" -> AgentWorkspaceActionMenuRow(
                            text = action.label,
                            onClick = onExportQuestionBank
                        )
                        "pin" -> AgentWorkspaceActionMenuRow(
                            text = action.label,
                            onClick = onPin
                        )
                        "rename" -> AgentWorkspaceActionMenuRow(
                            text = action.label,
                            onClick = onRename
                        )
                        "delete" -> AgentWorkspaceActionMenuRow(
                            text = action.label,
                            tint = AgentPracticeWrong,
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentLibraryFileActionMenuDialog(
    target: AgentLibraryFileActionMenuTarget,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMoveToCategory: () -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var appeared by remember(target.item.id, target.anchorTopLeft) { mutableStateOf(false) }
    val menuWidth = 196.dp
    val menuCorner = 20.dp
    val horizontalMargin = 18.dp
    val topMargin = 14.dp
    val estimatedMenuHeight = 188.dp
    val offset = with(density) {
        val screenWidthPx = configuration.screenWidthDp.dp.roundToPx()
        val screenHeightPx = configuration.screenHeightDp.dp.roundToPx()
        val menuWidthPx = menuWidth.roundToPx()
        val estimatedMenuHeightPx = estimatedMenuHeight.roundToPx()
        val horizontalMarginPx = horizontalMargin.roundToPx()
        val topMarginPx = topMargin.roundToPx()
        val preferredX = target.anchorTopLeft.x + target.anchorSize.width - menuWidthPx - 6.dp.roundToPx()
        val preferredY = target.anchorTopLeft.y +
            (target.anchorSize.height / 2) -
            (estimatedMenuHeightPx / 2)
        IntOffset(
            x = preferredX.coerceIn(
                horizontalMarginPx,
                (screenWidthPx - menuWidthPx - horizontalMarginPx).coerceAtLeast(horizontalMarginPx)
            ),
            y = preferredY.coerceIn(
                topMarginPx,
                (screenHeightPx - estimatedMenuHeightPx - topMarginPx).coerceAtLeast(topMarginPx)
            )
        )
    }
    LaunchedEffect(target.item.id, target.anchorTopLeft) {
        appeared = true
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
    ) {
        val enterProgress by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            label = "libraryFileActionMenuEnter"
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
                    transformOrigin = TransformOrigin(0.88f, 0.5f)
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
                val actions = agentLibraryFileMenuActions()
                AgentWorkspaceActionMenuRow(text = "移动到分类", onClick = onMoveToCategory)
                AgentWorkspaceActionMenuRow(
                    text = actions[0].label,
                    onClick = onDownload
                )
                AgentWorkspaceActionMenuRow(
                    text = actions[1].label,
                    onClick = onExport
                )
                AgentWorkspaceActionMenuRow(
                    text = actions[2].label,
                    onClick = onShare
                )
                AgentWorkspaceActionMenuRow(
                    text = actions[3].label,
                    tint = AgentPracticeWrong,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
internal fun AgentWorkspaceActionMenuRow(
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "workspaceActionMenuPress"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .scale(pressScale)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 5.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text,
            color = tint,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

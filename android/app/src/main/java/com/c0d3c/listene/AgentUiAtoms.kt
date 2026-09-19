package com.c0d3c.listene

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add

// 通用 UI 原子：图标控件/文字动作/分段药丸/线性进度/进度拖拽 + 思考/生成中/消息面板/背景/Surface/小节标题/空态/状态与辅助 Chip + 段落分组助手。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

// 入场动效：首次组合时轻微上移 + 淡入；传 delayMillis 可让一组元素错位登场（stagger），是「高级感」里最省成本的一招。
@Composable
internal fun Modifier.agentEnter(delayMillis: Int = 0, fromOffset: Dp = 8.dp): Modifier {
    var shown by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 360, delayMillis = delayMillis, easing = FastOutSlowInEasing),
        label = "agentEnter"
    )
    LaunchedEffect(Unit) { shown = true }
    val fromPx = with(LocalDensity.current) { fromOffset.toPx() }
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * fromPx
    }
}

// 骨架屏原子：中性底 + 微光从左到右循环滑过（shimmer）。加载态用它替代进度条，观感更稳。
@Composable
internal fun AgentSkeleton(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "agentSkeleton")
    val shift by transition.animateFloat(
        initialValue = -1.2f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "agentSkeletonShift"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    // 微光要比底更亮：浅色用纯白 surface；暗色下 surface 反而更暗，改用白色低透明叠底。
    val glint = if (base.luminance() < 0.5f) {
        Color.White.copy(alpha = 0.09f).compositeOver(base)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val w = widthPx.toFloat().coerceAtLeast(1f)
    val brush = Brush.linearGradient(
        colors = listOf(base, glint, base),
        start = Offset(shift * w, 0f),
        end = Offset((shift + 1f) * w, 0f)
    )
    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width }
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
    )
}

@Composable
internal fun AgentIconControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    size: Dp = 46.dp,
    iconSize: Dp = 23.dp,
    filled: Boolean = false,
    bordered: Boolean = true,
    testTag: String = ""
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "agentIconPress"
    )
    Surface(
        modifier = Modifier
            .scale(pressScale)
            .size(size)
            .clip(CircleShape)
            .then(if (testTag.isBlank()) Modifier else Modifier.testTag(testTag))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        shape = CircleShape,
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            filled -> accent
            else -> Color.Transparent
        },
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> Color.Transparent
                filled -> Color.Transparent
                !bordered -> Color.Transparent
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
            }
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
                    filled -> MaterialTheme.colorScheme.onPrimary
                    else -> accent
                },
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
internal fun AgentTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 42.dp,
    horizontalPadding: Dp = 12.dp,
    accent: Color? = null,
    filledAccent: Boolean = false,
    semanticsText: String? = null
) {
    val actionAccent = accent ?: if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "agentTextActionPress"
    )
    val pressTint by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "agentTextActionTint"
    )
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        primary -> MaterialTheme.colorScheme.primary
        accent != null && filledAccent -> actionAccent
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f * pressTint)
            .compositeOver(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f))
    }
    Row(
        modifier = modifier
            .scale(pressScale)
            .height(height)
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .border(
                BorderStroke(
                    1.dp,
                    when {
                        primary -> Color.Transparent
                        accent != null && filledAccent -> Color.Transparent
                        accent != null -> actionAccent.copy(alpha = 0.28f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                    }
                ),
                RoundedCornerShape(22.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .then(
                semanticsText?.let { label ->
                    Modifier.semantics { this.text = AnnotatedString(label) }
                } ?: Modifier
            )
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
            tint = when {
                primary -> MaterialTheme.colorScheme.onPrimary
                accent != null && filledAccent -> Color.White
                else -> actionAccent
            },
            modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.54f)
                primary -> MaterialTheme.colorScheme.onPrimary
                accent != null && filledAccent -> Color.White
                accent != null -> actionAccent
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AgentSegmentPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val accent = AgentStudyBlue
    val activeBrush = Brush.horizontalGradient(listOf(accent, accent))
    val inactiveBrush = Brush.horizontalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        )
    )
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (selected) activeBrush else inactiveBrush)
            .border(
                BorderStroke(
                    1.dp,
                    when {
                        selected -> accent
                        enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.82f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
                    }
                ),
                RoundedCornerShape(19.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
                selected -> Color.White
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AgentLinearProgress(
    modifier: Modifier = Modifier,
    progress: Float? = null
) {
    val fraction = (progress ?: 0.42f).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(7.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
internal fun AgentProgressScrubber(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).coerceAtLeast(1f)
    val fraction = ((value - start) / span).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val thumbOffset = with(density) {
        ((widthPx - 18.dp.roundToPx()).coerceAtLeast(0) * fraction).toDp()
    }

    fun emitFromX(x: Float) {
        val next = start + span * (x / widthPx.toFloat()).coerceIn(0f, 1f)
        onValueChange(next.coerceIn(start, end))
    }

    Box(
        modifier = modifier
            .height(30.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> emitFromX(offset.x) }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> emitFromX(offset.x) },
                    onHorizontalDrag = { change, _ -> emitFromX(change.position.x) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.80f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(7.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(5.dp, MaterialTheme.colorScheme.primary), CircleShape)
        )
    }
}

internal fun buildAgentParagraphSentenceGroups(content: ListeningContent): List<AgentParagraphSentenceGroup> {
    val precise = content.audioSegments
        .filter { it.kind.equals("sentence", ignoreCase = true) && it.endMs > it.startMs }
        .sortedWith(compareBy<ListeningAudioSegment> { it.turnIndex }.thenBy { it.sentenceIndex }.thenBy { it.startMs })
    if (precise.isNotEmpty()) {
        val fallbackTurns = TranscriptSentenceSplitter.splitTurnsForListening(content.script).associateBy { it.turnIndex }
        return precise.groupBy { it.turnIndex }.toSortedMap().values.mapIndexedNotNull { paragraphIndex, group ->
            if (group.isEmpty()) return@mapIndexedNotNull null
            val first = group.first()
            val paragraphText = content.ttsSegments.getOrNull(first.turnIndex)?.text?.takeIf { it.isNotBlank() }
                ?: fallbackTurns[first.turnIndex]?.text
                ?: group.joinToString(" ") { it.text }
            val title = first.speakerName.ifBlank {
                content.ttsSegments.getOrNull(first.turnIndex)?.speakerName.orEmpty()
            }.ifBlank { "段落 ${paragraphIndex + 1}" }
            AgentParagraphSentenceGroup(
                id = "paragraph-${first.turnIndex}",
                title = title,
                text = paragraphText,
                sentences = group.mapIndexed { sentenceIndex, segment ->
                    segment.toAgentTranscriptClip(AgentTranscriptClipScope.Sentences, sentenceIndex, precise = true)
                }
            )
        }
    }
    return buildEstimatedAgentParagraphSentenceGroups(content)
}

private fun buildEstimatedAgentParagraphSentenceGroups(content: ListeningContent): List<AgentParagraphSentenceGroup> {
    val turns = TranscriptSentenceSplitter.splitTurnsForListening(content.script)
    if (turns.isEmpty()) return emptyList()
    val entries = turns.flatMap { turn ->
        turn.sentences.mapIndexed { sentenceIndex, sentence ->
            Triple(turn, sentenceIndex, sentence)
        }
    }
    val weights = entries.map { agentEnglishWordCount(it.third).coerceAtLeast(1) }
    val totalWeight = weights.sum().coerceAtLeast(1)
    var before = 0
    val clipsByTurn = linkedMapOf<Int, MutableList<AgentTranscriptClip>>()
    entries.forEachIndexed { index, (turn, sentenceIndex, sentence) ->
        val start = before.toFloat() / totalWeight
        before += weights[index]
        val end = before.toFloat() / totalWeight
        val clip = AgentTranscriptClip(
            id = "estimated-turn-${turn.turnIndex}-sentence-$sentenceIndex",
            scope = AgentTranscriptClipScope.Sentences,
            speakerName = turn.speakerName,
            text = sentence,
            startRatio = start,
            endRatio = end,
            turnIndex = turn.turnIndex,
            sentenceIndex = sentenceIndex,
            precise = false
        )
        clipsByTurn.getOrPut(turn.turnIndex) { mutableListOf() }.add(clip)
    }
    return turns.mapIndexedNotNull { index, turn ->
        val clips = clipsByTurn[turn.turnIndex].orEmpty()
        if (clips.isEmpty()) null else AgentParagraphSentenceGroup(
            id = "estimated-paragraph-${turn.turnIndex}",
            title = turn.speakerName.ifBlank { "段落 ${index + 1}" },
            text = turn.text,
            sentences = clips
        )
    }
}

private fun ListeningAudioSegment.toAgentTranscriptClip(
    scope: AgentTranscriptClipScope,
    fallbackIndex: Int,
    precise: Boolean
): AgentTranscriptClip = AgentTranscriptClip(
    id = id.ifBlank {
        if (scope == AgentTranscriptClipScope.Turns) "turn-$turnIndex" else "turn-$turnIndex-sentence-$sentenceIndex"
    }.ifBlank { "${scope.name.lowercase()}-$fallbackIndex" },
    scope = scope,
    speakerName = speakerName,
    text = text,
    startMs = startMs,
    endMs = endMs,
    turnIndex = turnIndex,
    sentenceIndex = sentenceIndex,
    precise = precise
)

internal fun resolveAgentTranscriptClipRange(clip: AgentTranscriptClip, durationMs: Int): Pair<Int, Int> {
    val total = durationMs.coerceAtLeast(1)
    if (clip.endMs > clip.startMs) {
        return coerceAgentTranscriptClipRange(clip.startMs, clip.endMs, total)
    }
    val startRatio = (clip.startRatio ?: 0f).coerceIn(0f, 1f)
    val endRatio = (clip.endRatio ?: 1f).coerceIn(startRatio, 1f)
    val padding = if (clip.scope == AgentTranscriptClipScope.Sentences) 250 else 120
    val start = (total * startRatio).toInt() - padding
    val end = (total * endRatio).toInt() + padding
    return coerceAgentTranscriptClipRange(start, end, total)
}

private fun coerceAgentTranscriptClipRange(start: Int, end: Int, duration: Int): Pair<Int, Int> {
    val total = duration.coerceAtLeast(1)
    val safeStart = start.coerceIn(0, total)
    val minEnd = (safeStart + 160).coerceAtMost(total)
    val safeEnd = end.coerceIn(minEnd, total.coerceAtLeast(minEnd))
    return safeStart to safeEnd
}

private fun agentEnglishWordCount(sentence: String): Int =
    Regex("""[A-Za-z]+(?:'[A-Za-z]+)?""").findAll(sentence).count()

internal fun String.splitLinesForAgentCard(): List<String> =
    split("\n", "；", ";")
        .map { it.trim() }
        .filter { it.isNotBlank() }

internal fun splitAgentCardPair(raw: String): Pair<String, String> {
    val text = raw.trim()
    if (text.isBlank()) return "" to ""
    val delimiters = listOf("->", "=>", "→", " = ", "=", "｜", "|", "：", ": ", " - ", " — ", " – ")
    delimiters.forEach { delimiter ->
        val index = text.indexOf(delimiter)
        if (index > 0) {
            val left = text.take(index).trim()
            val right = text.drop(index + delimiter.length).trim()
            if (left.isNotBlank() && right.isNotBlank()) return left to right
        }
    }
    return text to ""
}

private val AGENT_WORD_POS_PREFIX = Regex(
    """^\s*(?:v\.|n\.|adj\.|adv\.|prep\.|conj\.|pron\.|art\.|int\.|aux\.|num\.|phr\.)\s*""",
    RegexOption.IGNORE_CASE
)

@Composable
internal fun AgentThinkingText(text: String = "正在思考中...") {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
internal fun AgentGeneratingCard(reconnecting: Boolean, stageText: String = "") {
    // 生成中进度条卡片：练习卡 / 长内容生成期间显示，带不确定进度条 + 已用时秒数，
    // 让较慢的生成（如长篇阅读）有明确进度反馈，避免被误判为卡死；生成完成后由结果卡在此位置接替显示。
    val startMs = remember { System.currentTimeMillis() }
    val elapsed = remember { mutableStateOf(0L) }
    LaunchedEffect(reconnecting) {
        while (true) {
            elapsed.value = (System.currentTimeMillis() - startMs) / 1000
            delay(1000)
        }
    }
    AgentSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (reconnecting) "正在尝试重连…" else stageText.ifBlank { "正在生成练习卡片…" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (reconnecting) "网络不稳定，正在自动重试…"
                else "已用时 ${elapsed.value}s · 长篇阅读等内容生成较慢，请耐心等待，生成完成后会直接显示在这里",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun AgentMaterialProgressCard(stage: String, percent: Int) {
    // 听力素材生成·实时进度条：订阅 ViewModel 的真实阶段（连接→文本题目→合成音频→下载→整理），
    // 显示确定性进度条 + 百分比 + 当前阶段 + 已用时；替代原先静态的「素材生成中」文案。
    // 阶段间（如 TTS 合成）耗时较长时进度条会停在该百分比，已用时秒数保证仍有“在动”的反馈。
    val startMs = remember { System.currentTimeMillis() }
    val elapsed = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed.value = (System.currentTimeMillis() - startMs) / 1000
            delay(1000)
        }
    }
    val clamped = percent.coerceIn(0, 100)
    val animated by animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = tween(durationMillis = 600),
        label = "materialProgress"
    )
    AgentSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "听力素材生成中",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "$clamped%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "${stage.ifBlank { "正在准备…" }} · 已用时 ${elapsed.value}s",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun AgentMessagePanel(title: String, message: String, action: String, onAction: () -> Unit) {
    AgentSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AgentTextAction(
                text = action,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun AgentAiBackdrop(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background))
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AgentSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val interactive = onClick != null || onLongClick != null
    val pressScale by animateFloatAsState(
        targetValue = if (interactive && pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "agentSurfacePress"
    )
    // 按压时在缩放之外再叠一层极淡压色（状态层），反馈更细腻。
    val pressTint by animateFloatAsState(
        targetValue = if (interactive && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "agentSurfaceTint"
    )
    val base = modifier.fillMaxWidth()
    Surface(
        modifier = (if (!interactive) {
            base
        } else {
            base
                .scale(pressScale)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onLongClick = onLongClick,
                    onClick = onClick ?: {}
                )
        })
            .animateContentSize(tween(220, easing = FastOutSlowInEasing)),
        // 去 ghost-card：仅保留 1dp 描边，不再叠加投影（描边+淡投影同用是 AI 味）。
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f * pressTint)
            .compositeOver(MaterialTheme.colorScheme.surface),
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f))
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
internal fun AgentSectionTitle(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AgentDrawerCenteredEmptyText(
    modifier: Modifier = Modifier,
    title: String = "暂无",
    description: String = ""
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun AgentStatusChip(text: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    ) {
        Text(
            text.ifBlank { "-" },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun AgentAssistChip(text: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    ) {
        Text(
            text.ifBlank { "-" },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
    }
}

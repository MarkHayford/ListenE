package com.c0d3c.listene

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 音频播放（Audio）：音频播放器 + 分段裁剪播放器 + 转写句子列表。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentAudioPlayer(audioUrl: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<AgentExoAudio?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun release() {
        player?.release()
        player = null
        playing = false
        preparing = false
        duration = 0
        position = 0
    }

    DisposableEffect(Unit) { onDispose { release() } }
    LaunchedEffect(player, playing) {
        while (playing) {
            player?.let {
                runCatching {
                    position = it.currentPosition
                    duration = it.duration.coerceAtLeast(0)
                }
            }
            delay(500)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentProgressScrubber(
            value = position.toFloat(),
            onValueChange = {
                position = it.toInt()
                player?.seekTo(position)
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = duration > 0
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatTime(position), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
            AgentIconControl(
                icon = Icons.Default.Replay10,
                contentDescription = "后退",
                onClick = { player?.seekTo((position - 10000).coerceAtLeast(0)) },
                accent = MaterialTheme.colorScheme.secondary,
                enabled = duration > 0,
                size = 40.dp,
                iconSize = 20.dp
            )
            AgentPlayPauseButton(
                playing = playing,
                preparing = preparing,
                size = 58.dp,
                progressSize = 24.dp,
                contentDescription = when {
                    preparing -> "音频准备中"
                    playing -> "暂停音频"
                    else -> "播放音频"
                }
            ) {
                if (audioUrl.isNullOrBlank()) {
                    error = "音频不可用"
                    return@AgentPlayPauseButton
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
                        preparing = true
                        error = null
                        val src = audioUrl
                        scope.launch {
                            val playUrl = AudioCache.localPath(ctx, src)
                            withContext(Dispatchers.Main) {
                                runCatching {
                                    val uri = if (playUrl.startsWith("/")) Uri.fromFile(File(playUrl)) else Uri.parse(playUrl)
                                    val mp = AgentExoAudio(ctx)
                                    mp.onPrepared = {
                                        duration = mp.duration
                                        preparing = false
                                        playing = true
                                        mp.start()
                                    }
                                    mp.onCompletion = { release() }
                                    mp.onError = {
                                        error = "播放失败"
                                        release()
                                    }
                                    mp.setDataSource(uri)
                                    mp.prepare()
                                    mp
                                }.onSuccess { player = it }.onFailure {
                                    error = it.message ?: "播放失败"
                                    release()
                                }
                            }
                        }
                    }
                }
            }
            AgentIconControl(
                icon = Icons.Default.Forward10,
                contentDescription = "前进",
                onClick = { player?.seekTo((position + 10000).coerceAtMost(duration)) },
                accent = MaterialTheme.colorScheme.secondary,
                enabled = duration > 0,
                size = 40.dp,
                iconSize = 20.dp
            )
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

internal data class AgentTranscriptClip(
    val id: String,
    val scope: AgentTranscriptClipScope,
    val speakerName: String = "",
    val text: String,
    val startMs: Int = 0,
    val endMs: Int = 0,
    val startRatio: Float? = null,
    val endRatio: Float? = null,
    val turnIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val precise: Boolean = false
)

internal data class AgentParagraphSentenceGroup(
    val id: String,
    val title: String,
    val text: String,
    val sentences: List<AgentTranscriptClip>
)

@Composable
internal fun AgentSegmentClipPlayer(
    audioUrl: String?,
    clip: AgentTranscriptClip,
    autoPlayRequest: Int = 0
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember(audioUrl, clip.id) { mutableStateOf<AgentExoAudio?>(null) }
    var preparing by remember(audioUrl, clip.id) { mutableStateOf(false) }
    var playing by remember(audioUrl, clip.id) { mutableStateOf(false) }
    var duration by remember(audioUrl, clip.id) { mutableIntStateOf(0) }
    var position by remember(audioUrl, clip.id) { mutableIntStateOf(0) }
    var clipStart by remember(audioUrl, clip.id) { mutableIntStateOf(clip.startMs) }
    var clipEnd by remember(audioUrl, clip.id) { mutableIntStateOf(clip.endMs) }
    var error by remember(audioUrl, clip.id) { mutableStateOf<String?>(null) }

    fun release() {
        player?.release()
        player = null
        preparing = false
        playing = false
        duration = 0
        position = 0
        clipStart = clip.startMs
        clipEnd = clip.endMs
    }

    fun prepareAndPlay() {
        if (audioUrl.isNullOrBlank()) {
            error = "缺少音频，无法播放当前句子片段。"
            return
        }
        release()
        preparing = true
        error = null
        val src = audioUrl
        scope.launch {
            val playUrl = AudioCache.localPath(ctx, src)
            withContext(Dispatchers.Main) {
                runCatching {
                    val uri = if (playUrl.startsWith("/")) Uri.fromFile(File(playUrl)) else Uri.parse(playUrl)
                    val mp = AgentExoAudio(ctx)
                    mp.onPrepared = {
                        val total = mp.duration.coerceAtLeast(1)
                        val range = resolveAgentTranscriptClipRange(clip, total)
                        duration = total
                        clipStart = range.first
                        clipEnd = range.second
                        position = range.first
                        preparing = false
                        playing = true
                        mp.seekTo(range.first)
                        mp.start()
                    }
                    mp.onCompletion = {
                        playing = false
                        runCatching { mp.seekTo(clipStart) }
                        position = clipStart
                    }
                    mp.onError = {
                        error = "句子片段播放失败"
                        release()
                    }
                    mp.setDataSource(uri)
                    mp.prepare()
                    mp
                }.onSuccess { player = it }.onFailure {
                    error = it.message ?: "句子片段播放失败"
                    release()
                }
            }
        }
    }

    DisposableEffect(audioUrl, clip.id) {
        onDispose { release() }
    }

    LaunchedEffect(audioUrl, clip.id, autoPlayRequest) {
        if (autoPlayRequest > 0) {
            prepareAndPlay()
        }
    }

    LaunchedEffect(player, playing, clipEnd) {
        while (playing) {
            delay(120)
            player?.let {
                val current = runCatching { it.currentPosition }.getOrDefault(position)
                position = current
                if (clipEnd > clipStart && current >= clipEnd) {
                    runCatching {
                        it.pause()
                        it.seekTo(clipStart)
                    }
                    position = clipStart
                    playing = false
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(clip.speakerName.ifBlank { if (clip.scope == AgentTranscriptClipScope.Turns) "当前对话" else "当前句子" }, fontWeight = FontWeight.Black)
                    Text(
                        if (clipEnd > clipStart) "${formatTime(clipStart)} - ${formatTime(clipEnd)} · ${if (clip.precise) "精准" else "估算"}"
                        else "准备播放片段",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                AgentPlayPauseButton(
                    playing = playing,
                    preparing = preparing,
                    size = 48.dp,
                    progressSize = 20.dp,
                    contentDescription = when {
                        preparing -> "片段准备中"
                        playing -> "暂停片段"
                        else -> "播放片段"
                    }
                ) {
                    when {
                        player == null -> prepareAndPlay()
                        playing -> {
                            player?.pause()
                            playing = false
                        }
                        else -> {
                            val target = position.coerceIn(clipStart, clipEnd.coerceAtLeast(clipStart))
                            player?.seekTo(target)
                            player?.start()
                            playing = true
                        }
                    }
                }
            }
            if (duration > 0 && clipEnd > clipStart) {
                AgentProgressScrubber(
                    value = position.coerceIn(clipStart, clipEnd).toFloat(),
                    onValueChange = {
                        val target = it.toInt().coerceIn(clipStart, clipEnd)
                        position = target
                        player?.seekTo(target)
                    },
                    valueRange = clipStart.toFloat()..clipEnd.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                AgentTextAction(
                    text = "再听一次",
                    onClick = {
                        player?.seekTo(clipStart)
                        player?.start()
                        playing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.PlayArrow
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun AgentTranscriptSentenceList(
    groups: List<AgentParagraphSentenceGroup>,
    activeParagraphIndex: Int,
    activeSentenceIndex: Int,
    onSentenceClick: (Int, Int, AgentTranscriptClip) -> Unit
) {
    AgentSectionTitle("完整原文", "直接点击原文里的句子播放对应音频")
    AgentSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            groups.forEachIndexed { paragraphIndex, group ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            group.title,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${group.sentences.size} 句",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val sentenceOnSurface = MaterialTheme.colorScheme.onSurface
                    val sentencePrimary = MaterialTheme.colorScheme.primary
                    val annotated = buildAnnotatedString {
                        group.sentences.forEachIndexed { sentenceIndex, clip ->
                            val isActive = paragraphIndex == activeParagraphIndex && sentenceIndex == activeSentenceIndex
                            val link = LinkAnnotation.Clickable(
                                tag = "$paragraphIndex:$sentenceIndex",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = if (isActive) sentencePrimary else sentenceOnSurface,
                                        fontWeight = if (isActive) FontWeight.Black else FontWeight.Normal,
                                        background = if (isActive) sentencePrimary.copy(alpha = 0.13f) else Color.Transparent
                                    )
                                )
                            ) { clickedLink ->
                                val parts = (clickedLink as? LinkAnnotation.Clickable)?.tag?.split(":")
                                if (parts != null && parts.size == 2) {
                                    val paragraph = parts[0].toIntOrNull()
                                    val sentence = parts[1].toIntOrNull()
                                    if (paragraph != null && sentence != null) {
                                        groups.getOrNull(paragraph)?.sentences?.getOrNull(sentence)?.let { sentenceClip ->
                                            onSentenceClick(paragraph, sentence, sentenceClip)
                                        }
                                    }
                                }
                            }
                            withLink(link) {
                                append(clip.text.trim())
                            }
                            if (sentenceIndex < group.sentences.lastIndex) {
                                append(" ")
                            }
                        }
                    }
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
                    )
                }
            }
        }
    }
}

package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add

// 词汇/默写面板（Vocab）：单词统计 + 词汇练习整屏。从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentVocabStat(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
internal fun AgentVocabPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lookupInput by remember { mutableStateOf("") }
    var looking by remember { mutableStateOf(false) }
    var lookupResult by remember { mutableStateOf<VocabLookup?>(null) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf(VocabList(emptyList(), 0, 0)) }
    var loading by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var speakingWord by remember { mutableStateOf<String?>(null) }
    val player = remember { mutableStateOf<AgentExoAudio?>(null) }
    LaunchedEffect(refreshTick) {
        loading = true
        data = VocabStore.fetchList(ctx)
        loading = false
        cursor = 0; revealed = false
    }
    val dueItems = remember(data) { data.items.filter { it.due } }
    fun advance() { cursor += 1; revealed = false }
    DisposableEffect(Unit) { onDispose { player.value?.release(); player.value = null } }
    fun speakWord(word: String) {
        if (word.isBlank()) return
        speakingWord = word
        scope.launch {
            val res = runCatching { AgentConversationService.synthesizeSpeech(word) }.getOrNull()
            if (res == null || res.audioUrl.isBlank()) { speakingWord = null; return@launch }
            val playPath = AudioCache.localPath(ctx, res.audioUrl)
            withContext(Dispatchers.Main) {
                runCatching {
                    player.value?.release()
                    val mp = AgentExoAudio(ctx)
                    player.value = mp
                    mp.onPrepared = { mp.start(); speakingWord = null }
                    mp.onCompletion = { mp.release(); if (player.value === mp) player.value = null }
                    mp.onError = { speakingWord = null }
                    mp.setDataSource(playPath)
                    mp.prepare()
                }.onFailure { speakingWord = null }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AgentPullRefreshColumn(
            refreshing = loading,
            onRefresh = { refreshTick += 1 },
            modifier = Modifier.fillMaxSize(),
            horizontalPadding = 0.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentIconControl(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回侧工具栏",
                    onClick = onBack,
                    accent = MaterialTheme.colorScheme.onSurface,
                    size = 30.dp,
                    iconSize = 23.dp,
                    bordered = false
                )
                AgentSectionTitle("生词本", modifier = Modifier.weight(1f))
            }
            // 查词
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentRoleplayInputField(
                    value = lookupInput,
                    onValueChange = { lookupInput = it },
                    placeholder = "输入要查的单词或短语…",
                    modifier = Modifier.weight(1f)
                )
                AgentTextAction(
                    text = if (looking) "查询中" else "查词",
                    onClick = {
                        val term = lookupInput.trim()
                        if (term.isBlank() || looking) return@AgentTextAction
                        looking = true; lookupError = null; lookupResult = null
                        scope.launch {
                            val r = VocabStore.lookup(ctx, term)
                            looking = false
                            if (r == null) lookupError = "查询失败，请重试" else lookupResult = r
                        }
                    },
                    primary = true,
                    enabled = lookupInput.isNotBlank() && !looking,
                    icon = Icons.Default.Search,
                    height = 44.dp
                )
            }
            lookupError?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = AgentPracticeWrong) }
            lookupResult?.let { res ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AgentStudyTeal.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, AgentStudyTeal.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(res.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            if (res.phonetic.isNotBlank()) Text(res.phonetic, style = MaterialTheme.typography.bodySmall, color = AgentStudyTeal, modifier = Modifier.padding(bottom = 2.dp))
                            Spacer(Modifier.weight(1f))
                            AgentIconControl(
                                icon = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "朗读单词",
                                onClick = { speakWord(res.word) },
                                accent = AgentStudyTeal,
                                size = 30.dp,
                                iconSize = 19.dp,
                                bordered = false
                            )
                        }
                        if (res.meaning.isNotBlank()) Text(res.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (res.example.isNotBlank()) Text(res.example, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AgentTextAction(
                            text = "加入生词本",
                            onClick = {
                                scope.launch {
                                    val ok = VocabStore.add(ctx, res)
                                    if (ok) { AppNoticeBus.success("已加入生词本"); lookupResult = null; lookupInput = ""; refreshTick += 1 }
                                    else AppNoticeBus.error("加入失败，请重试")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            primary = true,
                            icon = Icons.Default.AddCircle,
                            height = 38.dp
                        )
                    }
                }
            }
            AgentCardDivider()
            if (data.total == 0) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AgentStudyTeal.copy(alpha = 0.06f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("生词本还是空的", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "查词后点「加入生词本」，之后会用间隔重复在你快忘时提醒复习。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                        )
                    }
                }
            } else {
                val newCount = data.items.count { it.box <= 0 }
                val learningCount = data.items.count { it.box in 1..3 }
                val masteredCount = data.items.count { it.box >= 4 }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AgentVocabStat("待复习", dueItems.size, AgentStudyRose, Modifier.weight(1f))
                        AgentVocabStat("新词", newCount, AgentStudyBlue, Modifier.weight(1f))
                        AgentVocabStat("巩固中", learningCount, AgentStudyAmber, Modifier.weight(1f))
                        AgentVocabStat("已掌握", masteredCount, AgentPracticeSuccess, Modifier.weight(1f))
                    }
                }
                if (cursor < dueItems.size) {
                    val item = dueItems[cursor]
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("第 ${cursor + 1}/${dueItems.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                AgentIconControl(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "移出生词本",
                                    onClick = { scope.launch { VocabStore.delete(ctx, item.id); advance() } },
                                    accent = AgentPracticeWrong,
                                    size = 28.dp,
                                    iconSize = 19.dp,
                                    bordered = false
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                if (revealed && item.phonetic.isNotBlank()) Text(item.phonetic, style = MaterialTheme.typography.bodySmall, color = AgentStudyTeal, modifier = Modifier.padding(bottom = 4.dp))
                                Spacer(Modifier.weight(1f))
                                AgentIconControl(
                                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "朗读单词",
                                    onClick = { speakWord(item.word) },
                                    accent = AgentStudyTeal,
                                    size = 32.dp,
                                    iconSize = 20.dp,
                                    bordered = false
                                )
                            }
                            if (revealed) {
                                if (item.meaning.isNotBlank()) Text(item.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                if (item.example.isNotBlank()) Text(item.example, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AgentTextAction(
                                        text = "忘了",
                                        onClick = {
                                            scope.launch {
                                                VocabStore.grade(ctx, item.id, false)
                                                runCatching { ProgressStore.record(ctx, "vocabulary", "词汇", 1, 0) }
                                                advance()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        primary = false,
                                        icon = Icons.Default.Replay10,
                                        height = 40.dp
                                    )
                                    AgentTextAction(
                                        text = "记得",
                                        onClick = {
                                            scope.launch {
                                                VocabStore.grade(ctx, item.id, true)
                                                runCatching { ProgressStore.record(ctx, "vocabulary", "词汇", 1, 1) }
                                                advance()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        primary = true,
                                        icon = Icons.Default.CheckCircle,
                                        height = 40.dp
                                    )
                                }
                            } else {
                                AgentTextAction(
                                    text = "看释义",
                                    onClick = { revealed = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    primary = true,
                                    icon = Icons.AutoMirrored.Filled.MenuBook,
                                    height = 40.dp
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = AgentPracticeSuccess.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, AgentPracticeSuccess.copy(alpha = 0.30f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AgentPracticeSuccess, modifier = Modifier.size(22.dp))
                            Text(
                                "到期生词都复习完了，下拉可刷新。",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = AgentPracticeSuccess
                            )
                        }
                    }
                }
                AgentCardDivider()
                Text("全部生词 ${data.total}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 4.dp))
                AgentRoleplayInputField(value = query, onValueChange = { query = it }, placeholder = "搜索生词…", modifier = Modifier.fillMaxWidth())
                val filtered = remember(data, query) {
                    val q = query.trim().lowercase()
                    if (q.isBlank()) data.items else data.items.filter { it.word.lowercase().contains(q) || it.meaning.lowercase().contains(q) }
                }
                if (filtered.isEmpty()) {
                    Text("没有匹配的生词", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 4.dp))
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            filtered.forEachIndexed { index, w ->
                                if (index > 0) {
                                    Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)))
                                }
                                val masteryLabel = when { w.box >= 4 -> "已掌握"; w.box >= 1 -> "巩固中"; else -> "新词" }
                                val masteryColor = when { w.box >= 4 -> AgentPracticeSuccess; w.box >= 1 -> AgentStudyAmber; else -> AgentStudyBlue }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AgentIconControl(
                                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "朗读",
                                        onClick = { speakWord(w.word) },
                                        accent = AgentStudyTeal,
                                        size = 30.dp,
                                        iconSize = 18.dp,
                                        bordered = false
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(w.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                        if (w.meaning.isNotBlank()) Text(w.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Surface(shape = CircleShape, color = masteryColor.copy(alpha = 0.14f)) {
                                        Text(masteryLabel, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = masteryColor)
                                    }
                                    AgentIconControl(
                                        icon = Icons.Default.Delete,
                                        contentDescription = "移除",
                                        onClick = { scope.launch { VocabStore.delete(ctx, w.id); refreshTick += 1 } },
                                        accent = AgentPracticeWrong,
                                        size = 28.dp,
                                        iconSize = 17.dp,
                                        bordered = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

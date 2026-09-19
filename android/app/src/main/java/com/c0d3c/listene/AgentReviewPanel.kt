package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

// 复习面板（Review）：错题/复习项整屏 + 复习项弹窗。从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentReviewPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(ReviewList(emptyList(), 0, 0)) }
    var loading by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var skillFilter by remember { mutableStateOf<String?>(null) }
    var reviewItemDialog by remember { mutableStateOf<ReviewItem?>(null) }
    LaunchedEffect(refreshTick) {
        loading = true
        data = ReviewStore.fetchList(ctx)
        loading = false
        cursor = 0; revealed = false; picked = null
    }
    val dueItems = remember(data) { data.items.filter { it.due } }
    // 复习队列：优先「到期」错题；若当前没有到期项，则回退为「全部错题（按掌握度从低到高）」，
    // 保证只要有错题，顶部的「答题→看答案→记得/忘了」交互卡就一定可用，而不是只剩底部只读列表。
    val reviewQueue = remember(data) {
        val due = data.items.filter { it.due }
        if (due.isNotEmpty()) due else data.items.sortedBy { it.box }
    }
    val reviewingDueOnly = dueItems.isNotEmpty()
    fun advance() { cursor += 1; revealed = false; picked = null }

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
                AgentSectionTitle("错题本", modifier = Modifier.weight(1f))
            }
            if (data.total == 0) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AgentStudyRose.copy(alpha = 0.07f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有错题", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "做练习答错的选择题会自动收进这里，用间隔重复(SRS)帮你定时复习、各个击破。",
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
                        AgentVocabStat("待巩固", newCount, AgentStudyBlue, Modifier.weight(1f))
                        AgentVocabStat("巩固中", learningCount, AgentStudyAmber, Modifier.weight(1f))
                        AgentVocabStat("已掌握", masteredCount, AgentPracticeSuccess, Modifier.weight(1f))
                    }
                }
                if (cursor < reviewQueue.size) {
                    val item = reviewQueue[cursor]
                    val correctIndex = item.options.indexOf(item.answer)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (item.skill.isNotBlank()) AgentReadingTagChip(item.skill)
                                Text(
                                    if (reviewingDueOnly) "第 ${cursor + 1}/${reviewQueue.size} 题 · 错 ${item.timesWrong} 次"
                                    else "复习全部 · 第 ${cursor + 1}/${reviewQueue.size} 题 · 错 ${item.timesWrong} 次",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                AgentIconControl(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "移出错题本",
                                    onClick = { scope.launch { ReviewStore.delete(ctx, item.id); advance() } },
                                    accent = AgentPracticeWrong,
                                    size = 28.dp,
                                    iconSize = 19.dp,
                                    bordered = false
                                )
                            }
                            Text(item.prompt, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            if (item.options.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    item.options.forEachIndexed { i, opt ->
                                        AgentCardChoiceChip(
                                            text = opt,
                                            selected = picked == i,
                                            correct = i == correctIndex,
                                            revealed = revealed,
                                            enabled = !revealed,
                                            onClick = { picked = i }
                                        )
                                    }
                                }
                            }
                            if (!revealed) {
                                AgentTextAction(
                                    text = "看答案",
                                    onClick = { revealed = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    primary = true,
                                    enabled = item.options.isEmpty() || picked != null,
                                    icon = Icons.AutoMirrored.Filled.FactCheck,
                                    height = 40.dp
                                )
                            } else {
                                AgentCardPracticeFeedback(
                                    correct = item.options.isEmpty() || picked == correctIndex,
                                    answer = item.answer,
                                    explanation = reviewItemDisplayExplanation(item)
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AgentTextAction(
                                        text = "忘了",
                                        onClick = { scope.launch { ReviewStore.grade(ctx, item.id, false); advance() } },
                                        modifier = Modifier.weight(1f),
                                        primary = false,
                                        icon = Icons.Default.Replay10,
                                        height = 40.dp
                                    )
                                    AgentTextAction(
                                        text = "记得",
                                        onClick = { scope.launch { ReviewStore.grade(ctx, item.id, true); advance() } },
                                        modifier = Modifier.weight(1f),
                                        primary = true,
                                        icon = Icons.Default.CheckCircle,
                                        height = 40.dp
                                    )
                                }
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
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(if (reviewingDueOnly) "到期错题都复习完了" else "这轮错题都过完了", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AgentPracticeSuccess)
                                Text("记得的拉长间隔，忘了的很快再出现。下拉可刷新。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
                AgentCardDivider()
                Text("全部错题 ${data.total}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 4.dp))
                val skills = remember(data) { data.items.map { it.skill }.filter { it.isNotBlank() }.distinct() }
                if (skills.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentSegmentPill(text = "全部", selected = skillFilter == null, onClick = { skillFilter = null })
                        skills.forEach { s ->
                            AgentSegmentPill(text = s, selected = skillFilter == s, onClick = { skillFilter = if (skillFilter == s) null else s })
                        }
                    }
                }
                AgentRoleplayInputField(value = query, onValueChange = { query = it }, placeholder = "搜索错题…", modifier = Modifier.fillMaxWidth())
                val filtered = remember(data, query, skillFilter) {
                    val q = query.trim().lowercase()
                    data.items.filter { (skillFilter == null || it.skill == skillFilter) && (q.isBlank() || it.prompt.lowercase().contains(q) || it.answer.lowercase().contains(q)) }
                }
                if (filtered.isEmpty()) {
                    Text("没有匹配的错题", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 4.dp))
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            filtered.forEachIndexed { index, rev ->
                                if (index > 0) {
                                    Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)))
                                }
                                val masteryLabel = when { rev.box >= 4 -> "已掌握"; rev.box >= 1 -> "巩固中"; else -> "待巩固" }
                                val masteryColor = when { rev.box >= 4 -> AgentPracticeSuccess; rev.box >= 1 -> AgentStudyAmber; else -> AgentStudyBlue }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { reviewItemDialog = rev }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(rev.prompt, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (rev.skill.isNotBlank()) Text(rev.skill, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                                            Text("点按复习作答", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Surface(shape = CircleShape, color = masteryColor.copy(alpha = 0.14f)) {
                                        Text(masteryLabel, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = masteryColor)
                                    }
                                    AgentIconControl(
                                        icon = Icons.Default.Delete,
                                        contentDescription = "移除",
                                        onClick = { scope.launch { ReviewStore.delete(ctx, rev.id); refreshTick += 1 } },
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
        reviewItemDialog?.let { dlg ->
            AgentReviewItemDialog(
                item = dlg,
                onGrade = { remembered ->
                    scope.launch {
                        ReviewStore.grade(ctx, dlg.id, remembered)
                        reviewItemDialog = null
                        refreshTick += 1
                    }
                },
                onDelete = {
                    scope.launch {
                        ReviewStore.delete(ctx, dlg.id)
                        reviewItemDialog = null
                        refreshTick += 1
                    }
                },
                onDismiss = { reviewItemDialog = null }
            )
        }
    }
}

@Composable
private fun AgentReviewItemDialog(
    item: ReviewItem,
    onGrade: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var revealed by remember(item.id) { mutableStateOf(false) }
    var picked by remember(item.id) { mutableStateOf<Int?>(null) }
    val correctIndex = item.options.indexOf(item.answer)
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.AutoMirrored.Filled.FactCheck,
                title = "复习这道错题",
                subtitle = if (item.skill.isNotBlank()) "${item.skill} · 错 ${item.timesWrong} 次" else "错 ${item.timesWrong} 次"
            )
            Text(item.prompt, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (item.options.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    item.options.forEachIndexed { i, opt ->
                        AgentCardChoiceChip(
                            text = opt,
                            selected = picked == i,
                            correct = i == correctIndex,
                            revealed = revealed,
                            enabled = !revealed,
                            onClick = { picked = i }
                        )
                    }
                }
            }
            if (!revealed) {
                AgentTextAction(
                    text = "看答案",
                    onClick = { revealed = true },
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    enabled = item.options.isEmpty() || picked != null,
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    height = 42.dp
                )
            } else {
                AgentCardPracticeFeedback(
                    correct = item.options.isEmpty() || picked == correctIndex,
                    answer = item.answer,
                    explanation = reviewItemDisplayExplanation(item)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentTextAction(
                        text = "忘了",
                        onClick = { onGrade(false) },
                        modifier = Modifier.weight(1f),
                        primary = false,
                        icon = Icons.Default.Replay10,
                        height = 42.dp
                    )
                    AgentTextAction(
                        text = "记得",
                        onClick = { onGrade(true) },
                        modifier = Modifier.weight(1f),
                        primary = true,
                        icon = Icons.Default.CheckCircle,
                        height = 42.dp
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTextAction(text = "移出错题本", onClick = onDelete, modifier = Modifier.weight(1f), height = 40.dp, icon = Icons.Default.Delete)
                AgentTextAction(text = "关闭", onClick = onDismiss, modifier = Modifier.weight(1f), height = 40.dp)
            }
        }
    }
}

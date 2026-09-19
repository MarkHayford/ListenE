package com.c0d3c.listene

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// 杂项屏与脚手架：下拉刷新 + 工作区目录页/行 + 卡片类型面板 + 每日挑战卡 + 跟读卡组件 + 删除资料弹窗 + 主舞台 + 应用头部。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentPullRefreshColumn(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    val currentOnRefresh = rememberUpdatedState(onRefresh)
    var pullDistancePx by remember { mutableStateOf(0f) }
    var gestureRefreshing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { 58.dp.toPx() }
    val maxPullPx = with(density) { 92.dp.toPx() }
    val visualRefreshing = refreshing || gestureRefreshing
    val pullProgress = (pullDistancePx / thresholdPx).coerceIn(0f, 1f)
    val targetStretchHeight = with(density) {
        val minHeight = 18.dp.toPx()
        val maxHeight = 58.dp.toPx()
        val refreshHeight = 52.dp.toPx()
        (if (visualRefreshing) refreshHeight else minHeight + pullDistancePx * 0.58f)
            .coerceIn(0f, maxHeight)
            .toDp()
    }
    val stretchHeight by animateDpAsState(
        targetValue = if (visualRefreshing || pullProgress > 0.02f) targetStretchHeight else 0.dp,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "pullRefreshStretchHeight"
    )
    val contentOffset = with(density) {
        val maxOffset = 54.dp.toPx()
        val refreshOffset = 48.dp.toPx()
        val stretchOffset = targetStretchHeight.toPx() + 6.dp.toPx()
        when {
            visualRefreshing -> refreshOffset
            pullProgress > 0.02f -> maxOf(pullDistancePx * 0.42f, stretchOffset)
            else -> 0f
        }
            .coerceIn(0f, maxOffset)
            .toDp()
    }
    val animatedContentOffset by animateDpAsState(
        targetValue = contentOffset,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "pullRefreshContentOffset"
    )
    val contentOffsetPx = with(density) { animatedContentOffset.toPx() }
    val nestedConnection = remember(scrollState, thresholdPx, maxPullPx, visualRefreshing) {
        object : NestedScrollConnection {
            suspend fun finishPull(): Velocity {
                if (pullDistancePx >= thresholdPx && !visualRefreshing) {
                    pullDistancePx = 0f
                    gestureRefreshing = true
                    currentOnRefresh.value()
                    delay(620)
                    gestureRefreshing = false
                    return Velocity.Zero
                }
                pullDistancePx = 0f
                return Velocity.Zero
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || visualRefreshing) return Offset.Zero
                if (pullDistancePx > 0f && available.y < 0f) {
                    val consumedY = available.y.coerceAtLeast(-pullDistancePx)
                    pullDistancePx = (pullDistancePx + consumedY).coerceAtLeast(0f)
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || visualRefreshing) return Offset.Zero
                val atTop = scrollState.value <= 0
                if (atTop && available.y > 0f) {
                    pullDistancePx = (pullDistancePx + available.y).coerceIn(0f, maxPullPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return finishPull()
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return finishPull()
            }
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(nestedConnection)
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = contentOffsetPx
                    scaleY = 1f + (pullProgress * 0.012f)
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .verticalScroll(scrollState)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
            Spacer(Modifier.height(104.dp))
        }
        AgentPullRefreshIndicator(
            progress = pullProgress,
            refreshing = visualRefreshing,
            stretchHeight = stretchHeight,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
        )
    }
}

@Composable
private fun AgentPullRefreshIndicator(
    progress: Float,
    refreshing: Boolean,
    stretchHeight: Dp,
    modifier: Modifier = Modifier
) {
    val visible = refreshing || progress > 0.02f
    val animatedProgress by animateFloatAsState(
        targetValue = if (refreshing) 1f else progress,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "pullRefreshProgress"
    )
    val pulse by rememberInfiniteTransition(label = "pullRefreshPulse").animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pullRefreshPulse"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(160, easing = FastOutSlowInEasing), initialOffsetY = { -it / 3 }),
        exit = fadeOut(tween(110, easing = FastOutSlowInEasing)) +
            slideOutVertically(tween(140, easing = FastOutSlowInEasing), targetOffsetY = { -it / 4 })
    ) {
        Box(
            modifier = modifier.height(stretchHeight),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .graphicsLayer { alpha = (0.28f + animatedProgress * 0.72f).coerceIn(0f, 1f) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(54.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedProgress.coerceIn(0.08f, 1f))
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { index ->
                        val dotAlpha = if (refreshing) {
                            (0.34f + pulse * 0.36f + index * 0.06f).coerceAtMost(0.86f)
                        } else {
                            (0.28f + animatedProgress * 0.48f).coerceAtMost(0.78f)
                        }
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha))
                        )
                    }
                }
                Text(
                    text = if (refreshing) "同步中" else if (animatedProgress >= 1f) "松开同步" else "继续下拉",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentWorkspaceDirectoryPage(
    workspaces: List<LearningWorkspace>,
    activeWorkspace: LearningWorkspace?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWorkspace: (LearningWorkspace) -> Unit,
    onOpenWorkspaceMenu: (AgentWorkspaceActionMenuTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val showEmpty = !loading && error == null && workspaces.isEmpty()
    var query by remember { mutableStateOf("") }
    val catCtx = LocalContext.current
    var catTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { LibraryCategoryStore.changes.collect { catTick++ } }
    val wsCategories = remember(catTick) { LibraryCategoryStore.categories(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE) }
    val wsAssignments = remember(catTick) { LibraryCategoryStore.assignments(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE) }
    var wsCategoryFilter by remember { mutableStateOf(LibraryCategoryStore.ALL) }
    var showWsManageCategories by remember { mutableStateOf(false) }
    var wsOrganizeProposal by remember { mutableStateOf<OrganizeProposal?>(null) }
    var wsOrganizing by remember { mutableStateOf(false) }
    val catScope = rememberCoroutineScope()
    var wsSelectionMode by remember { mutableStateOf(false) }
    val wsSelectedIds = remember { mutableStateListOf<String>() }
    var showWsBatchPicker by remember { mutableStateOf(false) }
    if (showWsBatchPicker) {
        AgentCategoryPickerDialog(
            categories = wsCategories,
            currentId = null,
            onPick = { catId ->
                val ids = wsSelectedIds.toList()
                ids.forEach { LibraryCategoryStore.setCategory(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE, it, catId) }
                wsSelectedIds.clear(); wsSelectionMode = false
                AppNoticeBus.success("已移动 ${ids.size} 个")
            },
            onCreateAndPick = { name ->
                val c = LibraryCategoryStore.createCategory(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE, name)
                val ids = wsSelectedIds.toList()
                if (c != null) ids.forEach { LibraryCategoryStore.setCategory(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE, it, c.id) }
                wsSelectedIds.clear(); wsSelectionMode = false
                AppNoticeBus.success("已移动 ${ids.size} 个")
            },
            onDismiss = { showWsBatchPicker = false }
        )
    }
    wsOrganizeProposal?.let { proposal ->
        AgentOrganizePreviewDialog(
            proposal = proposal,
            onConfirm = {
                val n = applyOrganizeProposal(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE, proposal)
                wsOrganizeProposal = null
                AppNoticeBus.success("已整理 $n 个工作区")
            },
            onDismiss = { wsOrganizeProposal = null }
        )
    }
    if (showWsManageCategories) {
        AgentManageCategoriesDialog(
            categories = wsCategories,
            onCreate = { name -> LibraryCategoryStore.createCategory(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE, name) },
            onRename = { id, name -> LibraryCategoryStore.renameCategory(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE, id, name) },
            onDelete = { id ->
                LibraryCategoryStore.deleteCategory(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE, id)
                if (wsCategoryFilter == id) wsCategoryFilter = LibraryCategoryStore.ALL
            },
            onDismiss = { showWsManageCategories = false }
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        AgentPullRefreshColumn(
            refreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            horizontalPadding = 0.dp,
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
                AgentSectionTitle(
                    title = "工作区",
                    modifier = Modifier.weight(1f)
                )
                if (workspaces.isNotEmpty()) {
                    AgentTextAction(
                        text = if (wsSelectionMode) "完成" else "多选",
                        onClick = { wsSelectionMode = !wsSelectionMode; if (!wsSelectionMode) wsSelectedIds.clear() },
                        height = 34.dp
                    )
                }
            }

            error?.let { AgentMessagePanel("工作区同步失败", it, "重试", onRefresh) }

            if (workspaces.isNotEmpty()) {
                AgentCategoryBar(
                    categories = wsCategories,
                    selectedId = wsCategoryFilter,
                    onSelect = { wsCategoryFilter = it },
                    onManage = { showWsManageCategories = true },
                    onDropItem = { catId, itemId ->
                        LibraryCategoryStore.setCategory(catCtx, LibraryCategoryStore.DOMAIN_WORKSPACE, itemId, catId)
                        AppNoticeBus.success("已归类")
                    },
                    organizing = wsOrganizing,
                    onAiOrganize = {
                        if (!wsOrganizing && workspaces.isNotEmpty()) {
                            wsOrganizing = true
                            catScope.launch {
                                val payloadItems = workspaces.map { OrganizeStore.Item(it.id, it.title, agentWorkspaceDisplayDescription(it)) }
                                val res = OrganizeStore.organize(catCtx, payloadItems, wsCategories.map { it.name })
                                wsOrganizing = false
                                if (res != null && !res.isEmpty) wsOrganizeProposal = res
                                else AppNoticeBus.error("AI 整理失败，请稍后再试")
                            }
                        }
                    }
                )
                AgentRoleplayInputField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索工作区…",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val filteredWorkspaces = run {
                val q = query.trim().lowercase()
                workspaces.filter { ws ->
                    val catOk = when (wsCategoryFilter) {
                        LibraryCategoryStore.ALL -> true
                        LibraryCategoryStore.UNCATEGORIZED -> wsAssignments[ws.id] == null
                        else -> wsAssignments[ws.id] == wsCategoryFilter
                    }
                    val qOk = q.isBlank() ||
                        ws.title.lowercase().contains(q) ||
                        agentWorkspaceDisplayDescription(ws).lowercase().contains(q)
                    catOk && qOk
                }
            }
            filteredWorkspaces.forEach { workspace ->
                AgentWorkspaceDirectoryRow(
                    workspace = workspace,
                    active = workspace.id == activeWorkspace?.id,
                    onOpen = { onOpenWorkspace(workspace) },
                    onOpenMenu = onOpenWorkspaceMenu,
                    selectionMode = wsSelectionMode,
                    selected = wsSelectedIds.contains(workspace.id),
                    onToggleSelect = {
                        if (wsSelectedIds.contains(workspace.id)) wsSelectedIds.remove(workspace.id) else wsSelectedIds.add(workspace.id)
                    }
                )
            }
            if (query.isNotBlank() && filteredWorkspaces.isEmpty()) {
                Text(
                    "没有匹配的工作区",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        if (showEmpty) {
            AgentDrawerCenteredEmptyText(
                modifier = Modifier
                    .matchParentSize()
                    .padding(top = 58.dp, bottom = 104.dp),
                title = "还没有工作区",
                description = "每开始一个新的学习需求，都会自动建一个工作区，聊天和素材都收在里面。"
            )
        }
        if (wsSelectionMode && wsSelectedIds.isNotEmpty()) {
            AgentSurface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "已选 ${wsSelectedIds.size} 项",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AgentTextAction(text = "移动到分类", onClick = { showWsBatchPicker = true }, primary = true, height = 40.dp)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AgentWorkspaceDirectoryRow(
    workspace: LearningWorkspace,
    active: Boolean,
    onOpen: () -> Unit,
    onOpenMenu: (AgentWorkspaceActionMenuTarget) -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null
) {
    var anchorTopLeft by remember { mutableStateOf(IntOffset.Zero) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    val description = agentWorkspaceDisplayDescription(workspace)
    AgentSurface(
        modifier = Modifier
            .then(if (!selectionMode) Modifier.categoryDragSource(workspace.id) else Modifier)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                anchorTopLeft = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                anchorSize = coordinates.size
            },
        onClick = if (selectionMode) ({ onToggleSelect?.invoke() ?: Unit }) else onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) AgentStudyBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        workspace.title.ifBlank { "未命名工作区" },
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (workspace.pinnedAt > 0L) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = "已置顶",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                if (description.isNotBlank()) {
                    Text(
                        description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    formatHistoryTime(workspace.updatedAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            if (!selectionMode) {
                AgentIconControl(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "工作区菜单",
                    onClick = {
                        onOpenMenu(
                            AgentWorkspaceActionMenuTarget(
                                workspace = workspace,
                                anchorTopLeft = anchorTopLeft,
                                anchorSize = anchorSize
                            )
                        )
                    },
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 38.dp,
                    iconSize = 20.dp,
                    bordered = false
                )
            }
        }
    }
}

@Composable
internal fun AgentDailyResultHeader(score: Int, correct: Int, total: Int) {
    val color = agentSpeakingScoreColor(score)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)).border(2.dp, color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("$score", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("今日得分", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (correct >= 0) "答对 $correct / $total 题" else "共 $total 题",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AgentDailyQuestionCard(index: Int, question: Question, selected: Int?, revealed: Boolean, onAnswer: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(13.dp)) {
            AgentCardQuestionAtom(index = index, question = question, selectedAnswer = selected, revealed = revealed, onAnswer = onAnswer)
            if (revealed && question.explanation.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(question.explanation, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun agentDailyTypeLabel(type: String): String = when (type) {
    DailyQuestionType.CLOZE -> "选词填空"
    DailyQuestionType.SENTENCE_BUILDER -> "连词成句"
    DailyQuestionType.ORDERING -> "句子排序"
    DailyQuestionType.SHORT_ANSWER -> "短答"
    DailyQuestionType.TRANSLATION -> "翻译"
    else -> "选择题"
}

private fun agentDailyPromptText(question: DailyQuestion): String =
    if (question.type == DailyQuestionType.TRANSLATION) "翻译成英文：${question.questionText}" else question.questionText

private fun agentDailyCorrectAnswerText(q: DailyQuestion): String = when {
    q.isPickOne() -> q.options.getOrNull(q.correctAnswer) ?: q.answer
    q.type == DailyQuestionType.ORDERING -> q.answerList.joinToString("  →  ")
    else -> q.answer
}

// 多重集合扣减：从可选块里移除已排入的块（支持重复词）。
private fun agentRemainingPieces(pieces: List<String>, chosen: List<String>): List<String> {
    val remaining = pieces.toMutableList()
    chosen.forEach { c ->
        val idx = remaining.indexOf(c)
        if (idx >= 0) remaining.removeAt(idx)
    }
    return remaining
}

// 每日挑战答题卡：按题型渲染（选择/选词、连词成句/句子排序、短答/翻译）。
@Composable
internal fun AgentDailyAnswerCard(
    index: Int,
    question: DailyQuestion,
    draft: DailyAnswerDraft,
    onDraft: (DailyAnswerDraft) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                AgentReadingTagChip(agentDailyTypeLabel(question.type))
                if (question.skill.isNotBlank()) Text(question.skill, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(agentDailyPromptText(question), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            when {
                question.isPickOne() -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        question.options.forEachIndexed { i, opt ->
                            AgentCardChoiceChip(
                                text = opt,
                                selected = draft.choice == i,
                                correct = false,
                                revealed = false,
                                enabled = true,
                                onClick = { onDraft(draft.copy(choice = i)) }
                            )
                        }
                    }
                }
                question.isArrange() -> {
                    val pieces = question.arrangePieces()
                    val chosen = draft.order
                    if (chosen.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), modifier = Modifier.fillMaxWidth()) {
                            FlowRow(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                chosen.forEachIndexed { pos, piece ->
                                    AgentSegmentPill(text = piece, selected = true, onClick = {
                                        onDraft(draft.copy(order = chosen.toMutableList().also { it.removeAt(pos) }))
                                    })
                                }
                            }
                        }
                    }
                    val remaining = agentRemainingPieces(pieces, chosen)
                    if (remaining.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            remaining.forEach { piece ->
                                AgentSegmentPill(text = piece, selected = false, onClick = { onDraft(draft.copy(order = chosen + piece)) })
                            }
                        }
                    }
                    if (chosen.isNotEmpty()) {
                        AgentTextAction(text = "清空重排", onClick = { onDraft(draft.copy(order = emptyList())) }, height = 34.dp)
                    }
                }
                else -> {
                    AgentRoleplayInputField(
                        value = draft.text,
                        onValueChange = { onDraft(draft.copy(text = it)) },
                        placeholder = if (question.type == DailyQuestionType.TRANSLATION) "输入英文翻译…" else "输入答案…",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// 每日挑战结果卡：按题型展示用户作答、正确答案与解析。
@Composable
internal fun AgentDailyResultCard(index: Int, question: DailyQuestion) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                AgentReadingTagChip(agentDailyTypeLabel(question.type))
                if (question.skill.isNotBlank()) Text(question.skill, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    if (question.correct) "答对" else "答错",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (question.correct) AgentPracticeSuccess else AgentPracticeWrong
                )
            }
            Text(agentDailyPromptText(question), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            when {
                question.isPickOne() -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        question.options.forEachIndexed { i, opt ->
                            AgentCardChoiceChip(
                                text = opt,
                                selected = question.your == i,
                                correct = i == question.correctAnswer,
                                revealed = true,
                                enabled = false,
                                onClick = {}
                            )
                        }
                    }
                }
                question.isArrange() -> {
                    val sep = if (question.type == DailyQuestionType.ORDERING) "  →  " else " "
                    val yourText = question.yourOrder.joinToString(sep)
                    Text(
                        "你的排列：${yourText.ifBlank { "（未排）" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (question.correct) AgentPracticeSuccess else AgentPracticeWrong
                    )
                }
                else -> {
                    Text(
                        "你的回答：${question.yourText.ifBlank { "（空）" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (question.correct) AgentPracticeSuccess else AgentPracticeWrong
                    )
                }
            }
            AgentCardPracticeFeedback(
                correct = question.correct,
                answer = agentDailyCorrectAnswerText(question),
                explanation = question.explanation
            )
        }
    }
}

internal fun agentUserRequestsShadowing(message: String): Boolean {
    val text = message.trim()
    if (text.isBlank()) return false
    return Regex("影子跟读|shadowing|shadow[_\\s-]*reading|跟读练习|练习跟读|带我跟读", RegexOption.IGNORE_CASE)
        .containsMatchIn(text)
}

internal fun agentShadowingParamsFromMessage(message: String): Pair<String, String> {
    val level = Regex("\\b(A2|B1|C1)\\b", RegexOption.IGNORE_CASE).find(message)?.value?.uppercase() ?: "B1"
    var topic = Regex("(?:主题|关于|topic|about)[:：]?\\s*([^，,。.\\n]+)", RegexOption.IGNORE_CASE)
        .find(message)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    topic = topic.replace(Regex("影子跟读|shadowing|shadow[_\\s-]*reading|跟读练习|练习跟读|带我跟读|练习|跟读", RegexOption.IGNORE_CASE), "").trim()
    return topic to level
}

// cardSpec 彻底退役：跟读卡改走消息级 shadowingJson（topic/level/sentences），渲染直连跟读组件。
internal data class ShadowingCardData(
    val topic: String,
    val level: String,
    val sentences: List<ShadowingSentence>
)

internal fun buildShadowingCardJson(topic: String, level: String, sentences: List<ShadowingSentence>): String =
    kotlinx.serialization.json.buildJsonObject {
        put("topic", kotlinx.serialization.json.JsonPrimitive(topic))
        put("level", kotlinx.serialization.json.JsonPrimitive(level))
        put(
            "sentences",
            kotlinx.serialization.json.buildJsonArray {
                sentences.forEach { sentence ->
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("text", kotlinx.serialization.json.JsonPrimitive(sentence.text))
                            put("translation", kotlinx.serialization.json.JsonPrimitive(sentence.translation))
                        }
                    )
                }
            }
        )
    }.toString()

internal fun parseShadowingCardJson(json: String): ShadowingCardData? {
    val obj = parseJsonObjectOrNull(json) ?: return null
    val sentences = obj.arrOrNull("sentences")?.let { arr ->
        List(arr.size) { i ->
            val o = arr.objOrNull(i) ?: return@List null
            ShadowingSentence(o.str("text"), o.str("translation"))
        }.filterNotNull().filter { it.text.isNotBlank() }
    }.orEmpty()
    if (sentences.isEmpty()) return null
    return ShadowingCardData(topic = obj.str("topic"), level = obj.str("level").ifBlank { "B1" }, sentences = sentences)
}

@Composable
internal fun AgentCardShadowingComponent(data: ShadowingCardData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val topic = data.topic
    val level = data.level.ifBlank { "B1" }
    var sentences by remember(data) { mutableStateOf(data.sentences) }
    var cursor by remember(data) { mutableStateOf(0) }
    var generating by remember { mutableStateOf(false) }
    var assessing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SpeakingAssessment?>(null) }
    var recording by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val recordFile = remember { mutableStateOf<File?>(null) }
    val recordJob = remember { mutableStateOf<Job?>(null) }
    val player = remember { mutableStateOf<AgentExoAudio?>(null) }
    var ttsBusyText by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) errorText = "需要麦克风权限才能跟读"
    }
    fun playTts(text: String) {
        if (text.isBlank()) return
        ttsBusyText = text
        scope.launch {
            val res = runCatching { AgentConversationService.synthesizeSpeech(text) }.getOrNull()
            if (res == null || res.audioUrl.isBlank()) { ttsBusyText = null; return@launch }
            val playPath = AudioCache.localPath(ctx, res.audioUrl)
            withContext(Dispatchers.Main) {
                runCatching {
                    player.value?.release()
                    val mp = AgentExoAudio(ctx)
                    player.value = mp
                    mp.onPrepared = { mp.start(); ttsBusyText = null }
                    mp.onCompletion = { mp.release(); if (player.value === mp) player.value = null }
                    mp.onError = { ttsBusyText = null }
                    mp.setDataSource(playPath)
                    mp.prepare()
                }.onFailure { ttsBusyText = null }
            }
        }
    }
    fun startRecording() {
        if (recording || assessing) return
        errorText = null
        val file = File(ctx.cacheDir, "sh_${System.currentTimeMillis()}.wav")
        recordFile.value = file
        recording = true
        recordJob.value = scope.launch(Dispatchers.IO) {
            runCatching { recordAgentWavFile(file) }.onFailure {
                withContext(Dispatchers.Main) { recording = false; recordJob.value = null; recordFile.value = null; errorText = "录音启动失败" }
            }
        }
    }
    fun stopRecordingAndScore(target: String) {
        if (!recording) return
        recording = false
        val job = recordJob.value
        recordJob.value = null
        val file = recordFile.value
        recordFile.value = null
        scope.launch {
            job?.cancelAndJoin()
            if (file == null || !file.exists() || file.length() <= 44L) {
                errorText = "没有录到声音，请重试"
                file?.let { runCatching { it.delete() } }
                return@launch
            }
            assessing = true
            errorText = null
            try {
                val base64 = withContext(Dispatchers.IO) { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }
                val r = AgentConversationService.assessSpeaking(
                    base64 = base64,
                    prompt = "Shadowing practice: read this sentence aloud as accurately as you can: \"$target\"",
                    durationMs = 0L
                )
                result = r
                runCatching { ProgressStore.record(ctx, "speaking_prompt", "口语", 0, 0, r.overall) }
            } catch (e: Exception) {
                errorText = e.message ?: "评分失败"
            } finally {
                assessing = false
                runCatching { file.delete() }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.value?.release(); player.value = null
            recordJob.value?.cancel()
            recordFile.value?.let { runCatching { it.delete() } }
        }
    }
    if (sentences.isEmpty()) {
        AgentCardInlineNotice("没有可跟读的句子，换一批试试。")
        return
    }
    val item = sentences[cursor.coerceIn(0, sentences.lastIndex)]
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentCardInlineHeader(
            icon = Icons.Default.Headset,
            title = "影子跟读",
            text = listOfNotNull(topic.takeIf { it.isNotBlank() }, "第 ${cursor + 1}/${sentences.size} 句").joinToString(" · ")
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (item.translation.isNotBlank()) Text(item.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentTextAction(
                        text = if (ttsBusyText == item.text) "生成中…" else "播放原音",
                        onClick = { playTts(item.text) },
                        modifier = Modifier.weight(1f),
                        primary = false,
                        icon = Icons.Default.PlayArrow,
                        height = 42.dp
                    )
                    AgentTextAction(
                        text = when { assessing -> "评分中…"; recording -> "停止跟读"; else -> "跟读" },
                        onClick = {
                            if (assessing) return@AgentTextAction
                            if (recording) stopRecordingAndScore(item.text)
                            else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { result = null; startRecording() }
                            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.weight(1f),
                        primary = true,
                        enabled = !assessing,
                        icon = if (recording) Icons.Default.Stop else Icons.Default.Mic,
                        height = 42.dp
                    )
                }
                errorText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = AgentPracticeWrong) }
                result?.let { r ->
                    AgentCardDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { AgentSpeakingScoreBar("发音", r.scores.pronunciation) }
                        Column(Modifier.weight(1f)) { AgentSpeakingScoreBar("流利度", r.scores.fluency) }
                    }
                    if (r.transcript.isNotBlank()) {
                        Text("你读的：${r.transcript}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AgentPronunciationDetailSection(r.pronunciationDetail)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AgentTextAction(
                text = if (generating) "生成中…" else "换一批",
                onClick = {
                    if (generating) return@AgentTextAction
                    generating = true; errorText = null
                    scope.launch {
                        try {
                            val list = AgentConversationService.generateShadowing(topic, level, 6)
                            if (list.isEmpty()) errorText = "生成失败，请重试" else { sentences = list; cursor = 0; result = null }
                        } catch (e: Exception) { errorText = e.message ?: "生成失败" }
                        finally { generating = false }
                    }
                },
                modifier = Modifier.weight(1f),
                primary = false,
                enabled = !generating,
                icon = Icons.Default.Replay10,
                height = 42.dp
            )
            AgentTextAction(
                text = if (cursor >= sentences.lastIndex) "已是最后一句" else "下一句",
                onClick = { if (cursor < sentences.lastIndex) { cursor += 1; result = null } },
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = cursor < sentences.lastIndex,
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                height = 42.dp
            )
        }
    }
}

@Composable
internal fun AgentDeleteLibraryItemDialog(
    kind: AgentLibraryKind,
    item: UserLibraryItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = when (kind) {
        AgentLibraryKind.Cards -> "删除卡片"
        AgentLibraryKind.Files -> "删除文件"
    }
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Default.Delete,
                title = title,
                subtitle = "这会从${kind.title}移除该条目。",
                accent = AgentPracticeWrong
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
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        item.title.ifBlank { kind.title },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        agentLibraryDeleteConsequenceText(kind.title),
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
                    height = 40.dp
                )
                AgentTextAction(
                    text = "删除",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    accent = AgentPracticeWrong
                )
            }
        }
    }
}

@Composable
internal fun AgentMainStage(
    modifier: Modifier = Modifier,
    activeWorkspace: LearningWorkspace?,
    messages: List<AgentChatMessage>,
    chatScrollState: ScrollState,
    history: List<HistoryRecord>,
    state: GenerationState,
    activeViewModelWorkspaceId: String?,
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
    viewModel: ListeningViewModel,
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
    Box(modifier = modifier) {
        AgentChatScreen(
            activeWorkspace = activeWorkspace,
            messages = messages,
            scrollState = chatScrollState,
            history = history,
            state = state,
            activeViewModelWorkspaceId = activeViewModelWorkspaceId,
            viewModel = viewModel,
            input = input,
            onInputChange = onInputChange,
            replyMode = replyMode,
            voiceProfile = voiceProfile,
            recording = recording,
            loading = loading,
            analysisLoading = analysisLoading,
            reconnecting = reconnecting,
            practiceCardGenerating = practiceCardGenerating,
            genStageText = genStageText,
            streamingReplyText = streamingReplyText,
            error = error,
            pendingAttachments = pendingAttachments,
            onRemoveAttachment = onRemoveAttachment,
            onStopGenerating = onStopGenerating,
            scrollToBottomRequest = scrollToBottomRequest,
            onScrollToBottomRequestHandled = onScrollToBottomRequestHandled,
            cardTopAnchorMessageId = cardTopAnchorMessageId,
            onCardTopAnchorHandled = onCardTopAnchorHandled,
            onSend = onSend,
            onCreateWorkspace = onCreateWorkspace,
            onRetryAfterError = onRetryAfterError,
            onAttachFile = onAttachFile,
            onVoicePressStart = onVoicePressStart,
            onVoicePressEnd = onVoicePressEnd,
            onVoicePressCancel = onVoicePressCancel,
            onVoiceConvertToText = onVoiceConvertToText,
            onToggleReplyMode = onToggleReplyMode,
            onOpenVoiceSettings = onOpenVoiceSettings,
            onRequestAiReview = onRequestAiReview,
            onMicroCardGraded = onMicroCardGraded,
            onMicroCardAnalyze = onMicroCardAnalyze,
            onRetryMessage = onRetryMessage,
            onDeleteMessages = onDeleteMessages,
            roleplayActive = roleplayActive,
            roleplayScenario = roleplayScenario,
            roleplayGoal = roleplayGoal,
            roleplayMessages = roleplayMessages,
            roleplayLoading = roleplayLoading,
            roleplayTtsBusy = roleplayTtsBusy,
            onRoleplayReplay = onRoleplayReplay,
            onRoleplayResume = onRoleplayResume
        )
    }
}

internal suspend fun ScrollState.animateToSettledBottom(initialDelayMillis: Long = 120L) {
    delay(initialDelayMillis)
    repeat(3) {
        val target = maxValue
        if (target > 0) animateScrollTo(target)
        delay(90)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentAppHeader(
    workspaceTitle: String,
    hasActiveWorkspace: Boolean,
    onMenu: () -> Unit,
    onNewWorkspace: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AgentIconControl(
            icon = Icons.Default.Menu,
            contentDescription = "工作区目录",
            onClick = onMenu,
            accent = MaterialTheme.colorScheme.onSurface,
            size = 44.dp,
            iconSize = 24.dp,
            bordered = false,
            testTag = "agent_drawer_menu"
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (hasActiveWorkspace) workspaceTitle.ifBlank { "学习工作区" } else "ListenE",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = if (hasActiveWorkspace) 21.sp else 27.sp,
                lineHeight = if (hasActiveWorkspace) 26.sp else 31.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AgentIconControl(
            icon = Icons.Default.AddCircle,
            contentDescription = "新工作区",
            onClick = onNewWorkspace,
            accent = MaterialTheme.colorScheme.onSurface,
            size = 44.dp,
            iconSize = 24.dp,
            bordered = false
        )
    }
}

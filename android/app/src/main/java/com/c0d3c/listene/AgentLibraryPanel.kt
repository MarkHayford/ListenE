package com.c0d3c.listene

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ModeEdit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 资料库面板（Library）：题库/资料整屏 + 列表行 + 卡片详情 + 文件预览详情 + 重命名弹窗。从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentLibraryPanel(
    kind: AgentLibraryKind,
    workspaces: List<LearningWorkspace>,
    history: List<HistoryRecord>,
    state: GenerationState,
    activeViewModelWorkspaceId: String?,
    viewModel: ListeningViewModel,
    onBack: () -> Unit,
    onRequestAiReview: (HistoryRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(kind) { mutableStateOf<List<UserLibraryItem>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(true) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var refreshTick by remember(kind) { mutableIntStateOf(0) }
    var selectedCardItem by remember(kind) { mutableStateOf<UserLibraryItem?>(null) }
    var selectedFileItem by remember(kind) { mutableStateOf<UserLibraryItem?>(null) }
    var itemToDelete by remember(kind) { mutableStateOf<UserLibraryItem?>(null) }
    var fileActionTarget by remember(kind) { mutableStateOf<AgentLibraryFileActionMenuTarget?>(null) }
    var cardActionTarget by remember(kind) { mutableStateOf<AgentLibraryCardActionMenuTarget?>(null) }
    var cardToRename by remember(kind) { mutableStateOf<UserLibraryItem?>(null) }
    var query by remember(kind) { mutableStateOf("") }
    val catDomain = if (kind == AgentLibraryKind.Cards) LibraryCategoryStore.DOMAIN_CARDS else LibraryCategoryStore.DOMAIN_FILES
    var categories by remember(kind) { mutableStateOf(LibraryCategoryStore.categories(ctx, catDomain)) }
    var assignments by remember(kind) { mutableStateOf(LibraryCategoryStore.assignments(ctx, catDomain)) }
    var categoryFilter by remember(kind) { mutableStateOf(LibraryCategoryStore.ALL) }
    var showManageCategories by remember(kind) { mutableStateOf(false) }
    var moveCategoryTarget by remember(kind) { mutableStateOf<UserLibraryItem?>(null) }
    var organizeProposal by remember(kind) { mutableStateOf<OrganizeProposal?>(null) }
    var organizing by remember(kind) { mutableStateOf(false) }
    var selectionMode by remember(kind) { mutableStateOf(false) }
    val selectedIds = remember(kind) { mutableStateListOf<String>() }
    var showBatchCategoryPicker by remember(kind) { mutableStateOf(false) }
    fun reloadCategories() {
        categories = LibraryCategoryStore.categories(ctx, catDomain)
        assignments = LibraryCategoryStore.assignments(ctx, catDomain)
    }

    fun refresh() {
        refreshTick += 1
    }

    fun exportCardQuestionBank(item: UserLibraryItem) {
        scope.launch {
            runCatching { exportUserLibraryCardQuestionBank(ctx, item) }
                .onSuccess { fileName -> AppNoticeBus.success(agentExportSuccessNotice(fileName)) }
                .onFailure { AppNoticeBus.error(it.message ?: "当前卡片没有可导出的题目") }
        }
    }

    // 听力卡导出 ZIP（原文+题目+音频），并加入文件库（saveGeneratedFile → kind=files，会触发文件库刷新）。
    fun exportListeningZip(item: UserLibraryItem) {
        val record = userLibraryListeningRecord(item)
        if (record == null) {
            AppNoticeBus.error("听力数据缺失，无法导出")
            return
        }
        scope.launch {
            runCatching {
                val attachment = writeListeningZipAttachment(ctx, record)
                UserLibraryStore.saveGeneratedFile(
                    ctx = ctx,
                    workspaceId = userLibraryWorkspaceId(item),
                    attachment = attachment,
                    extraData = agentGeneratedAttachmentLibraryData(attachment)
                )
            }
                .onSuccess { AppNoticeBus.success("已导出听力包，已加入文件库") }
                .onFailure { AppNoticeBus.error(it.message ?: "听力包导出失败，请确认有可用录音") }
        }
    }

    LaunchedEffect(kind, refreshTick) {
        loading = true
        error = null
        runCatching { UserLibraryStore.listItems(ctx, kind.route) }
            .onSuccess { items = sortUserLibraryItemsForDisplay(it) }
            .onFailure { error = it.message ?: "库同步失败" }
        loading = false
    }

    LaunchedEffect(kind) {
        UserLibraryStore.changes.collect { event ->
            if (userLibraryChangeMatchesKind(event.kind, kind.route)) {
                refresh()
            }
        }
    }

    itemToDelete?.let { target ->
        AgentDeleteLibraryItemDialog(
            kind = kind,
            item = target,
            onDismiss = { itemToDelete = null },
            onConfirm = {
                itemToDelete = null
                scope.launch {
                    runCatching { UserLibraryStore.deleteItem(ctx, kind.route, target.id) }
                        .onSuccess {
                            items = items.filterNot { it.id == target.id }
                            if (selectedCardItem?.id == target.id) selectedCardItem = null
                            if (selectedFileItem?.id == target.id) selectedFileItem = null
                            AppNoticeBus.success(if (kind == AgentLibraryKind.Cards) "卡片删除成功" else "文件删除成功")
                        }
                        .onFailure { AppNoticeBus.error(it.message ?: "删除失败") }
                }
            }
        )
    }
    cardActionTarget?.let { target ->
        AgentLibraryCardActionMenuDialog(
            target = target,
            onDismiss = { cardActionTarget = null },
            onExportQuestionBank = {
                cardActionTarget = null
                exportCardQuestionBank(target.item)
            },
            onPin = {
                cardActionTarget = null
                scope.launch {
                    val pinned = userLibraryPinnedAt(target.item) <= 0L
                    val next = setUserLibraryPinned(target.item, pinned)
                    runCatching { UserLibraryStore.updateItem(ctx, AgentLibraryKind.Cards.route, next) }
                        .onSuccess { updated ->
                            items = sortUserLibraryItemsForDisplay(listOf(updated) + items.filterNot { it.id == updated.id })
                            if (selectedCardItem?.id == updated.id) selectedCardItem = updated
                            AppNoticeBus.success(if (pinned) "卡片置顶成功" else "卡片已取消置顶")
                        }
                        .onFailure { AppNoticeBus.error(it.message ?: "置顶失败") }
                }
            },
            onRename = {
                cardActionTarget = null
                cardToRename = target.item
            },
            onDelete = {
                cardActionTarget = null
                itemToDelete = target.item
            },
            onMoveToCategory = {
                val moved = target.item
                cardActionTarget = null
                moveCategoryTarget = moved
            }
        )
    }
    cardToRename?.let { target ->
        AgentRenameItemDialog(
            title = "重命名卡片",
            label = "卡片名称",
            initialName = target.title,
            onDismiss = { cardToRename = null },
            onConfirm = { name ->
                cardToRename = null
                scope.launch {
                    val next = renameUserLibraryCardItem(target, name)
                    runCatching { UserLibraryStore.updateItem(ctx, AgentLibraryKind.Cards.route, next) }
                        .onSuccess { updated ->
                            items = sortUserLibraryItemsForDisplay(listOf(updated) + items.filterNot { it.id == updated.id })
                            if (selectedCardItem?.id == updated.id) selectedCardItem = updated
                            AppNoticeBus.success("卡片重命名成功")
                        }
                        .onFailure { AppNoticeBus.error(it.message ?: "重命名失败") }
                }
            }
        )
    }
    fileActionTarget?.let { target ->
        AgentLibraryFileActionMenuDialog(
            target = target,
            onDismiss = { fileActionTarget = null },
            onDownload = {
                fileActionTarget = null
                downloadUserLibraryFile(ctx, target.item)
            },
            onExport = {
                fileActionTarget = null
                exportUserLibraryFile(ctx, target.item)
            },
            onShare = {
                fileActionTarget = null
                shareUserLibraryFile(ctx, target.item)
            },
            onDelete = {
                fileActionTarget = null
                itemToDelete = target.item
            },
            onMoveToCategory = {
                val moved = target.item
                fileActionTarget = null
                moveCategoryTarget = moved
            }
        )
    }
    moveCategoryTarget?.let { item ->
        AgentCategoryPickerDialog(
            categories = categories,
            currentId = assignments[item.id],
            onPick = { catId ->
                LibraryCategoryStore.setCategory(ctx, catDomain, item.id, catId)
                reloadCategories()
                AppNoticeBus.success("已移动到分类")
            },
            onCreateAndPick = { name ->
                val c = LibraryCategoryStore.createCategory(ctx, catDomain, name)
                if (c != null) LibraryCategoryStore.setCategory(ctx, catDomain, item.id, c.id)
                reloadCategories()
                AppNoticeBus.success("已移动到分类")
            },
            onDismiss = { moveCategoryTarget = null }
        )
    }
    if (showManageCategories) {
        AgentManageCategoriesDialog(
            categories = categories,
            onCreate = { name -> LibraryCategoryStore.createCategory(ctx, catDomain, name); reloadCategories() },
            onRename = { id, name -> LibraryCategoryStore.renameCategory(ctx, catDomain, id, name); reloadCategories() },
            onDelete = { id ->
                LibraryCategoryStore.deleteCategory(ctx, catDomain, id)
                if (categoryFilter == id) categoryFilter = LibraryCategoryStore.ALL
                reloadCategories()
            },
            onDismiss = { showManageCategories = false }
        )
    }
    organizeProposal?.let { proposal ->
        AgentOrganizePreviewDialog(
            proposal = proposal,
            onConfirm = {
                val n = applyOrganizeProposal(ctx, catDomain, proposal)
                reloadCategories()
                organizeProposal = null
                AppNoticeBus.success("已整理 $n 个项目")
            },
            onDismiss = { organizeProposal = null }
        )
    }
    if (showBatchCategoryPicker) {
        AgentCategoryPickerDialog(
            categories = categories,
            currentId = null,
            onPick = { catId ->
                val ids = selectedIds.toList()
                ids.forEach { LibraryCategoryStore.setCategory(ctx, catDomain, it, catId) }
                reloadCategories()
                selectedIds.clear()
                selectionMode = false
                AppNoticeBus.success("已移动 ${ids.size} 个")
            },
            onCreateAndPick = { name ->
                val c = LibraryCategoryStore.createCategory(ctx, catDomain, name)
                val ids = selectedIds.toList()
                if (c != null) ids.forEach { LibraryCategoryStore.setCategory(ctx, catDomain, it, c.id) }
                reloadCategories()
                selectedIds.clear()
                selectionMode = false
                AppNoticeBus.success("已移动 ${ids.size} 个")
            },
            onDismiss = { showBatchCategoryPicker = false }
        )
    }

    val selected = selectedCardItem
    val selectedFile = selectedFileItem
    val showEmpty = !loading && error == null && items.isEmpty() && selected == null && selectedFile == null
    BackHandler(enabled = selected != null || selectedFile != null) {
        if (selectedFile != null) selectedFileItem = null
        else if (selected != null) selectedCardItem = null
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (kind == AgentLibraryKind.Cards && selected != null) {
            AgentLibraryCardDetail(
                item = selected,
                workspaces = workspaces,
                history = history,
                state = state,
                activeViewModelWorkspaceId = activeViewModelWorkspaceId,
                viewModel = viewModel,
                onBack = { selectedCardItem = null },
                onExportQuestionBank = { exportCardQuestionBank(selected) },
                onExportListeningZip = if (isListeningLibraryItem(selected)) {
                    { exportListeningZip(selected) }
                } else {
                    null
                },
                onRequestAiReview = onRequestAiReview,
                modifier = Modifier.fillMaxSize()
            )
        } else if (kind == AgentLibraryKind.Files && selectedFile != null) {
            AgentLibraryFilePreviewDetail(
                item = selectedFile,
                onBack = { selectedFileItem = null },
                onOpenExternal = { openUserLibraryFile(ctx, selectedFile) },
                onDownload = { downloadUserLibraryFile(ctx, selectedFile) },
                onExport = { exportUserLibraryFile(ctx, selectedFile) },
                onShare = { shareUserLibraryFile(ctx, selectedFile) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AgentPullRefreshColumn(
                refreshing = loading,
                onRefresh = { refresh() },
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
                    AgentSectionTitle(kind.title, modifier = Modifier.weight(1f))
                    if (items.isNotEmpty()) {
                        AgentTextAction(
                            text = if (selectionMode) "完成" else "多选",
                            onClick = {
                                selectionMode = !selectionMode
                                if (!selectionMode) selectedIds.clear()
                            },
                            height = 34.dp
                        )
                    }
                }

                error?.let { AgentMessagePanel("库同步失败", it, "重试") { refresh() } }

                if (items.isNotEmpty()) {
                    AgentCategoryBar(
                        categories = categories,
                        selectedId = categoryFilter,
                        onSelect = { categoryFilter = it },
                        onManage = { showManageCategories = true },
                        onDropItem = { catId, itemId ->
                            LibraryCategoryStore.setCategory(ctx, catDomain, itemId, catId)
                            reloadCategories()
                            AppNoticeBus.success("已归类")
                        },
                        organizing = organizing,
                        onAiOrganize = {
                            if (!organizing && items.isNotEmpty()) {
                                organizing = true
                                scope.launch {
                                    val payloadItems = items.map { OrganizeStore.Item(it.id, it.title, it.summary) }
                                    val res = OrganizeStore.organize(ctx, payloadItems, categories.map { it.name })
                                    organizing = false
                                    if (res != null && !res.isEmpty) organizeProposal = res
                                    else AppNoticeBus.error("AI 整理失败，请稍后再试")
                                }
                            }
                        }
                    )
                    AgentRoleplayInputField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = if (kind == AgentLibraryKind.Cards) "搜索卡片…" else "搜索文件…",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val filteredItems = run {
                    val q = query.trim().lowercase()
                    items.filter { item ->
                        val catOk = when (categoryFilter) {
                            LibraryCategoryStore.ALL -> true
                            LibraryCategoryStore.UNCATEGORIZED -> assignments[item.id] == null
                            else -> assignments[item.id] == categoryFilter
                        }
                        val qOk = q.isBlank() ||
                            item.title.lowercase().contains(q) ||
                            item.summary.lowercase().contains(q)
                        catOk && qOk
                    }
                }
                filteredItems.forEach { item ->
                    AgentLibraryItemRow(
                        kind = kind,
                        item = item,
                        onOpen = {
                            when (agentLibraryClickDestination(kind.route)) {
                                AgentLibraryClickDestination.CardDetail -> selectedCardItem = item
                                AgentLibraryClickDestination.FilePreview -> selectedFileItem = item
                            }
                        },
                        onDelete = if (agentLibraryRowShowsInlineDelete(kind.route)) {
                            { itemToDelete = item }
                        } else {
                            null
                        },
                        onOpenCardMenu = if (kind == AgentLibraryKind.Cards) {
                            { target -> cardActionTarget = target }
                        } else {
                            null
                        },
                        onExportCardQuestionBank = when {
                            kind != AgentLibraryKind.Cards -> null
                            isListeningLibraryItem(item) -> ({ exportListeningZip(item) })
                            userLibraryMicroCard(item)?.let(::microCardCanExportQuestionBank) == true ->
                                ({ exportCardQuestionBank(item) })
                            else -> null
                        },
                        onOpenFileMenu = if (kind == AgentLibraryKind.Files) {
                            { target -> fileActionTarget = target }
                        } else {
                            null
                        },
                        selectionMode = selectionMode,
                        selected = selectedIds.contains(item.id),
                        onToggleSelect = {
                            if (selectedIds.contains(item.id)) selectedIds.remove(item.id) else selectedIds.add(item.id)
                        }
                    )
                }
                if (query.isNotBlank() && filteredItems.isEmpty()) {
                    Text(
                        if (kind == AgentLibraryKind.Cards) "没有匹配的卡片" else "没有匹配的文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
        if (showEmpty) {
            AgentDrawerCenteredEmptyText(
                modifier = Modifier
                    .matchParentSize()
                    .padding(top = 58.dp, bottom = 104.dp),
                title = if (kind == AgentLibraryKind.Cards) "卡片库还是空的" else "文件库还是空的",
                description = if (kind == AgentLibraryKind.Cards)
                    "对话里生成的练习卡和听力练习，会收在这里，方便随时回看重练、导出。"
                else
                    "导出的听力包 / Word 文档会收在这里，方便回看和分享。"
            )
        }
        if (selectionMode && selectedIds.isNotEmpty()) {
            AgentSurface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "已选 ${selectedIds.size} 项",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AgentTextAction(text = "移动到分类", onClick = { showBatchCategoryPicker = true }, primary = true, height = 40.dp)
                }
            }
        }
    }
}

@Composable
private fun AgentLibraryItemRow(
    kind: AgentLibraryKind,
    item: UserLibraryItem,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onOpenCardMenu: ((AgentLibraryCardActionMenuTarget) -> Unit)?,
    onExportCardQuestionBank: (() -> Unit)?,
    onOpenFileMenu: ((AgentLibraryFileActionMenuTarget) -> Unit)?,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null
) {
    var anchorTopLeft by remember { mutableStateOf(IntOffset.Zero) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    AgentSurface(
        modifier = Modifier
            .then(
                if (kind == AgentLibraryKind.Cards) Modifier.testTag("agent_library_card_row_${item.id}")
                else Modifier
            )
            .then(if (!selectionMode) Modifier.categoryDragSource(item.id) else Modifier)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                anchorTopLeft = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                anchorSize = coordinates.size
            },
        onClick = if (selectionMode) ({ onToggleSelect?.invoke() ?: Unit }) else onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) AgentStudyBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (kind != AgentLibraryKind.Cards) {
                Icon(
                    when (kind) {
                        AgentLibraryKind.Files -> Icons.Default.AttachFile
                        AgentLibraryKind.Cards -> Icons.Default.AutoAwesome
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val displayTitle = if (kind == AgentLibraryKind.Cards) {
                    agentLibraryCardDisplayTitle(item.title, kind.title)
                } else {
                    item.title.ifBlank { kind.title }
                }
                Text(
                    displayTitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.summary.ifBlank { formatHistoryTime(item.updatedAt) },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatHistoryTime(item.updatedAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            if (selectionMode) {
                // 多选模式下隐藏行末菜单/导出，避免误触
            } else if (kind == AgentLibraryKind.Files) {
                AgentIconControl(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "长按打开文件菜单",
                    onClick = {
                        onOpenFileMenu?.invoke(
                            AgentLibraryFileActionMenuTarget(
                                item = item,
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
            } else if (kind == AgentLibraryKind.Cards) {
                if (onExportCardQuestionBank != null) {
                    AgentIconControl(
                        icon = Icons.Default.AttachFile,
                        contentDescription = "导出",
                        onClick = onExportCardQuestionBank,
                        accent = MaterialTheme.colorScheme.primary,
                        size = 38.dp,
                        iconSize = 20.dp,
                        bordered = false,
                        testTag = "agent_library_card_export_${item.id}"
                    )
                }
                if (onOpenCardMenu != null) {
                    AgentIconControl(
                        icon = Icons.Default.MoreVert,
                        contentDescription = "卡片菜单",
                        onClick = {
                            onOpenCardMenu(
                                AgentLibraryCardActionMenuTarget(
                                    item = item,
                                    anchorTopLeft = anchorTopLeft,
                                    anchorSize = anchorSize
                                )
                            )
                        },
                        accent = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 38.dp,
                        iconSize = 20.dp,
                        bordered = false,
                        testTag = "agent_library_card_menu_${item.id}"
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentLibraryCardDetail(
    item: UserLibraryItem,
    workspaces: List<LearningWorkspace>,
    history: List<HistoryRecord>,
    state: GenerationState,
    activeViewModelWorkspaceId: String?,
    viewModel: ListeningViewModel,
    onBack: () -> Unit,
    onExportQuestionBank: () -> Unit,
    onExportListeningZip: (() -> Unit)? = null,
    onRequestAiReview: (HistoryRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    // 卡片库详情：听力卡内联渲染可练习听力卡（真音频+答题+核对+复盘）；其它走微元渲染（只认原生 microCard）。
    val listeningRecord = remember(item.id, item.updatedAt) {
        if (isListeningLibraryItem(item)) userLibraryListeningRecord(item) else null
    }
    val microCard = remember(item.id, item.updatedAt) {
        if (listeningRecord != null) null else userLibraryMicroCard(item)
    }
    val canExportQuestionBank = remember(item.id, item.updatedAt) {
        microCard?.let(::microCardCanExportQuestionBank) == true
    }
    val currentRecord = remember(item, workspaces, history, state, activeViewModelWorkspaceId) {
        agentLibraryItemCurrentRecord(item, workspaces, history, state, activeViewModelWorkspaceId)
    }
    val displayTitle = remember(item.title, microCard?.title) {
        agentLibraryCardDisplayTitle(item.title, microCard?.title.orEmpty())
    }
    AgentPullRefreshColumn(
        refreshing = false,
        onRefresh = {},
        modifier = modifier,
        horizontalPadding = 0.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AgentIconControl(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回卡片库",
                onClick = onBack,
                accent = MaterialTheme.colorScheme.onSurface,
                size = 30.dp,
                iconSize = 23.dp,
                bordered = false
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatHistoryTime(item.updatedAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            if (onExportListeningZip != null) {
                AgentIconControl(
                    icon = Icons.Default.AttachFile,
                    contentDescription = "导出听力 ZIP",
                    onClick = onExportListeningZip,
                    accent = MaterialTheme.colorScheme.primary,
                    size = 38.dp,
                    iconSize = 20.dp,
                    bordered = false,
                    testTag = "agent_library_card_detail_export_zip"
                )
            } else if (canExportQuestionBank) {
                AgentIconControl(
                    icon = Icons.Default.AttachFile,
                    contentDescription = "导出题库",
                    onClick = onExportQuestionBank,
                    accent = MaterialTheme.colorScheme.primary,
                    size = 38.dp,
                    iconSize = 20.dp,
                    bordered = false,
                    testTag = "agent_library_card_detail_export"
                )
            }
        }
        if (listeningRecord != null) {
            AgentSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListeningMicroCardView(
                        record = listeningRecord,
                        instanceKey = "library:${item.id}:${item.updatedAt}",
                        viewModel = viewModel,
                        onRequestAiReview = onRequestAiReview
                    )
                }
            }
        } else if (microCard != null && microCard.nodes.isNotEmpty()) {
            AgentSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompositionLocalProvider(
                        LocalAgentMicroRecord provides currentRecord,
                        LocalAgentMicroViewModel provides viewModel,
                        LocalAgentMicroOnRequestAiReview provides onRequestAiReview
                    ) {
                        MicroCardView(microCard, instanceKey = "library:${item.id}:${item.updatedAt}")
                    }
                }
            }
        } else {
            Text(
                "这张卡片没有可渲染的内容。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AgentLibraryFilePreviewDetail(
    item: UserLibraryItem,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
    onDownload: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val asset = remember(item.id, item.updatedAt) { userLibraryFileAsset(item) }
    val previewMode = remember(item.id, item.updatedAt) { userLibraryFilePreviewMode(item) }
    AgentPullRefreshColumn(
        refreshing = false,
        onRefresh = {},
        modifier = modifier,
        horizontalPadding = 0.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AgentIconControl(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回文件库",
                onClick = onBack,
                accent = MaterialTheme.colorScheme.onSurface,
                size = 30.dp,
                iconSize = 23.dp,
                bordered = false
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    asset.name.ifBlank { item.title.ifBlank { "文件" } },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatAgentFilePreviewMeta(asset, item.updatedAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        AgentFilePreviewActions(
            onOpenExternal = onOpenExternal,
            onDownload = onDownload,
            onExport = onExport,
            onShare = onShare
        )

        AgentFilePreviewContent(
            asset = asset,
            previewMode = previewMode,
            onOpenExternal = onOpenExternal
        )
    }
}

@Composable
internal fun AgentRenameItemDialog(
    title: String,
    label: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val cleanName = name.trim()
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.ModeEdit,
                title = title,
                subtitle = "名称会同步到当前账号。"
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
                    Text(
                        label,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(80) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                if (name.isBlank()) {
                                    Text(
                                        "输入名称",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AgentTextAction("取消", onDismiss, modifier = Modifier.weight(1f))
                AgentTextAction(
                    text = "保存",
                    onClick = { onConfirm(cleanName) },
                    modifier = Modifier.weight(1f),
                    primary = true,
                    enabled = cleanName.isNotBlank()
                )
            }
        }
    }
}

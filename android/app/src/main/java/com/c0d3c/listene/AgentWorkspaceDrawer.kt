package com.c0d3c.listene

import android.graphics.BlurMaskFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

// 工作区抽屉（Workspace Drawer）：抽屉主体 + 首页 + 头部 + 账户气泡 + 各类行/按钮 + 边缘阴影。从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentDrawerEdgeShadow(modifier: Modifier = Modifier) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    Box(
        modifier = modifier
            .width(1.dp)
            .drawBehind {
                val softPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb((0.065f * 255).toInt(), 0, 0, 0)
                    maskFilter = BlurMaskFilter(18.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                }
                val tightPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb((0.040f * 255).toInt(), 0, 0, 0)
                    maskFilter = BlurMaskFilter(8.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                }
                drawIntoCanvas { canvas ->
                    val edgeX = size.width
                    val verticalSpread = 24.dp.toPx()
                    canvas.nativeCanvas.drawRect(
                        edgeX - 1.dp.toPx(),
                        -verticalSpread,
                        edgeX + 3.dp.toPx(),
                        size.height + verticalSpread,
                        softPaint
                    )
                    canvas.nativeCanvas.drawRect(
                        edgeX - 0.5.dp.toPx(),
                        -verticalSpread,
                        edgeX + 1.5.dp.toPx(),
                        size.height + verticalSpread,
                        tightPaint
                    )
                }
                drawRect(color = dividerColor)
            }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentWorkspaceDrawer(
    workspaces: List<LearningWorkspace>,
    activeWorkspace: LearningWorkspace?,
    history: List<HistoryRecord>,
    state: GenerationState,
    activeViewModelWorkspaceId: String?,
    viewModel: ListeningViewModel,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onNewWorkspace: () -> Unit,
    onOpenWorkspace: (LearningWorkspace) -> Unit,
    onRefreshWorkspaces: () -> Unit,
    onOpenWorkspaceMenu: (AgentWorkspaceActionMenuTarget) -> Unit,
    onCloseDrawer: () -> Unit,
    onFeaturePageChange: (Boolean) -> Unit,
    onRequestAiReview: (HistoryRecord) -> Unit,
    authSession: AuthSession?,
    onLogout: () -> Unit,
    onAccountDeleted: () -> Unit = {},
    onSessionUpdated: (AuthSession) -> Unit = {}
) {
    var drawerPage by remember { mutableStateOf<AgentDrawerPage>(AgentDrawerPage.Home) }
    DisposableEffect(Unit) {
        onDispose { onFeaturePageChange(false) }
    }
    LaunchedEffect(drawerPage) {
        onFeaturePageChange(drawerPage != AgentDrawerPage.Home)
    }
    var searchExpanded by rememberSaveable { mutableStateOf(searchQuery.isNotBlank()) }
    var accountMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val filteredWorkspaces = remember(workspaces, searchQuery) {
        val cleanQuery = searchQuery.trim()
        if (cleanQuery.isBlank()) {
            workspaces
        } else {
            workspaces.filter { workspace ->
                workspace.title.contains(cleanQuery, ignoreCase = true) ||
                    workspace.summary.contains(cleanQuery, ignoreCase = true) ||
                    workspace.plan.contentType.contains(cleanQuery, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }

    fun closeSearch() {
        searchExpanded = false
        onSearchQueryChange("")
        focusManager.clearFocus()
    }

    fun closeAccountMenu() {
        accountMenuExpanded = false
    }
    fun openDrawerPage(page: AgentDrawerPage) {
        closeSearch()
        closeAccountMenu()
        drawerPage = page
    }
    fun backToDrawerHome() {
        closeSearch()
        closeAccountMenu()
        drawerPage = AgentDrawerPage.Home
    }
    fun backToDrawerHomeWithSync() {
        agentReturnToDrawerHomeWithSync(
            refreshWorkspaces = onRefreshWorkspaces,
            returnHome = { backToDrawerHome() }
        )
    }
    BackHandler(enabled = drawerPage != AgentDrawerPage.Home) {
        backToDrawerHomeWithSync()
    }
    val accountMenuVisibility = remember { MutableTransitionState(false) }
    LaunchedEffect(accountMenuExpanded) {
        accountMenuVisibility.targetState = accountMenuExpanded
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top))
            .padding(start = 26.dp, end = 26.dp, top = 28.dp, bottom = 26.dp)
    ) {
        AnimatedContent(
            targetState = drawerPage,
            transitionSpec = {
                val enteringHome = targetState == AgentDrawerPage.Home
                val direction = if (enteringHome) -1 else 1
                (fadeIn(tween(130, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(
                        animationSpec = tween(210, easing = FastOutSlowInEasing),
                        initialOffsetX = { direction * it / 5 }
                    )).togetherWith(
                    fadeOut(tween(100, easing = FastOutSlowInEasing)) +
                        slideOutHorizontally(
                            animationSpec = tween(170, easing = FastOutSlowInEasing),
                            targetOffsetX = { -direction * it / 7 }
                        )
                )
            },
            label = "drawerPageTransition"
        ) { page ->
            when (page) {
                AgentDrawerPage.Home -> {
                    AgentWorkspaceDrawerHomePage(
                        workspaces = workspaces,
                        filteredWorkspaces = filteredWorkspaces,
                        activeWorkspace = activeWorkspace,
                        searchQuery = searchQuery,
                        searchExpanded = searchExpanded,
                        searchFocusRequester = searchFocusRequester,
                        authSession = authSession,
                        accountMenuExpanded = accountMenuExpanded,
                        loading = loading,
                        error = error,
                        onSearchQueryChange = onSearchQueryChange,
                        onToggleSearch = {
                            closeAccountMenu()
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) closeSearch()
                        },
                        onToggleAccount = authSession?.let {
                            {
                                closeSearch()
                                accountMenuExpanded = !accountMenuExpanded
                            }
                        },
                        onOpenSettings = { openDrawerPage(AgentDrawerPage.Settings) },
                        onOpenWorkspaceDirectory = {
                            agentOpenWorkspaceDirectoryWithSync(
                                refreshWorkspaces = onRefreshWorkspaces,
                                openDirectory = { openDrawerPage(AgentDrawerPage.Workspaces) }
                            )
                        },
                        onOpenLibrary = { kind -> openDrawerPage(AgentDrawerPage.Library(kind)) },
                        onOpenPlans = { openDrawerPage(AgentDrawerPage.Plans) },
                        onOpenProgress = { openDrawerPage(AgentDrawerPage.Progress) },
                        onOpenReview = { openDrawerPage(AgentDrawerPage.Review) },
                        onOpenVocab = { openDrawerPage(AgentDrawerPage.Vocab) },
                        onOpenHelp = { openDrawerPage(AgentDrawerPage.Help) },
                        onOpenWorkspace = { workspace ->
                            closeAccountMenu()
                            drawerPage = AgentDrawerPage.Home
                            onOpenWorkspace(workspace)
                        },
                        onOpenWorkspaceMenu = { target ->
                            closeSearch()
                            closeAccountMenu()
                            onOpenWorkspaceMenu(target)
                        },
                        onNewWorkspace = {
                            drawerPage = AgentDrawerPage.Home
                            onNewWorkspace()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AgentDrawerPage.Workspaces -> {
                    AgentWorkspaceDirectoryPage(
                        workspaces = workspaces,
                        activeWorkspace = activeWorkspace,
                        loading = loading,
                        error = error,
                        onBack = { backToDrawerHomeWithSync() },
                        onRefresh = onRefreshWorkspaces,
                        onOpenWorkspace = { workspace ->
                            drawerPage = AgentDrawerPage.Home
                            onOpenWorkspace(workspace)
                        },
                        onOpenWorkspaceMenu = { target ->
                            closeSearch()
                            closeAccountMenu()
                            onOpenWorkspaceMenu(target)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AgentDrawerPage.Plans -> {
                    AgentPlansPanel(
                        onBack = { backToDrawerHomeWithSync() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AgentDrawerPage.Progress -> {
                    AgentProgressPanel(
                        onBack = { backToDrawerHomeWithSync() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AgentDrawerPage.Review -> {
                    AgentReviewPanel(
                        onBack = { backToDrawerHomeWithSync() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AgentDrawerPage.Vocab -> {
                    AgentVocabPanel(
                        onBack = { backToDrawerHomeWithSync() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AgentDrawerPage.Settings -> {
                    AgentSettingsPanel(
                        authSession = authSession,
                        onOpenAccount = { openDrawerPage(AgentDrawerPage.Account) },
                        onOpenHelp = { openDrawerPage(AgentDrawerPage.Help) },
                        onBack = { backToDrawerHomeWithSync() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AgentDrawerPage.Help -> {
                    AgentHelpPanel(
                        onBack = { backToDrawerHomeWithSync() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AgentDrawerPage.Account -> {
                    val session = authSession
                    if (session != null) {
                        AgentAccountPanel(
                            session = session,
                            onSessionUpdated = onSessionUpdated,
                            onBack = { backToDrawerHomeWithSync() },
                            onLogout = {
                                backToDrawerHome()
                                onLogout()
                            },
                            onAccountDeleted = {
                                backToDrawerHome()
                                onAccountDeleted()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LaunchedEffect(Unit) { backToDrawerHome() }
                    }
                }
                is AgentDrawerPage.Library -> {
                    AgentLibraryPanel(
                        kind = page.kind,
                        workspaces = workspaces,
                        history = history,
                        state = state,
                        activeViewModelWorkspaceId = activeViewModelWorkspaceId,
                        viewModel = viewModel,
                        onBack = { backToDrawerHomeWithSync() },
                        onRequestAiReview = onRequestAiReview,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if ((accountMenuVisibility.currentState || accountMenuVisibility.targetState) && authSession != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { closeAccountMenu() }
                    )
            )
            AnimatedVisibility(
                visibleState = accountMenuVisibility,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 58.dp),
                enter = fadeIn(tween(130, easing = FastOutSlowInEasing)) +
                    slideInVertically(
                        animationSpec = tween(190, easing = FastOutSlowInEasing),
                        initialOffsetY = { -it / 5 }
                    ) +
                    scaleIn(
                        animationSpec = tween(190, easing = FastOutSlowInEasing),
                        initialScale = 0.94f,
                        transformOrigin = TransformOrigin(0.92f, 0f)
                    ),
                exit = fadeOut(tween(100, easing = FastOutSlowInEasing)) +
                    slideOutVertically(
                        animationSpec = tween(140, easing = FastOutSlowInEasing),
                        targetOffsetY = { -it / 6 }
                    ) +
                    scaleOut(
                        animationSpec = tween(140, easing = FastOutSlowInEasing),
                        targetScale = 0.97f,
                        transformOrigin = TransformOrigin(0.92f, 0f)
                    )
            ) {
                AgentWorkspaceDrawerAccountPopover(
                    session = authSession,
                    onOpenAccount = { openDrawerPage(AgentDrawerPage.Account) },
                    onLogout = {
                        closeAccountMenu()
                        onLogout()
                    }
                )
            }
        }
        if (searchExpanded) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(top = 66.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { closeSearch() }
                    )
            )
        }
    }
}

@Composable
private fun AgentWorkspaceDrawerHomePage(
    workspaces: List<LearningWorkspace>,
    filteredWorkspaces: List<LearningWorkspace>,
    activeWorkspace: LearningWorkspace?,
    searchQuery: String,
    searchExpanded: Boolean,
    searchFocusRequester: FocusRequester,
    authSession: AuthSession?,
    accountMenuExpanded: Boolean,
    loading: Boolean,
    error: String?,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleAccount: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOpenWorkspaceDirectory: () -> Unit,
    onOpenLibrary: (AgentLibraryKind) -> Unit,
    onOpenPlans: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenVocab: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenWorkspace: (LearningWorkspace) -> Unit,
    onOpenWorkspaceMenu: (AgentWorkspaceActionMenuTarget) -> Unit,
    onNewWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            AgentWorkspaceDrawerHeader(
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                searchFocusRequester = searchFocusRequester,
                authSession = authSession,
                accountExpanded = accountMenuExpanded,
                onSearchQueryChange = onSearchQueryChange,
                onToggleSearch = onToggleSearch,
                onToggleAccount = onToggleAccount,
                onOpenSettings = onOpenSettings
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
            Spacer(Modifier.height(42.dp))
            var featuresExpanded by rememberSaveable { mutableStateOf(false) }
            var showMicroLab by rememberSaveable { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // 默认显示 4 个核心入口
                AgentWorkspaceDrawerFeatureRow(
                    icon = Icons.Outlined.Folder,
                    text = "工作区",
                    onClick = onOpenWorkspaceDirectory,
                    modifier = Modifier.agentEnter(delayMillis = 0)
                )
                AgentWorkspaceDrawerFeatureRow(
                    icon = Icons.Outlined.AutoAwesome,
                    text = "卡片库",
                    onClick = { onOpenLibrary(AgentLibraryKind.Cards) },
                    testTag = "agent_library_cards_entry",
                    modifier = Modifier.agentEnter(delayMillis = 45)
                )
                AgentWorkspaceDrawerFeatureRow(
                    icon = Icons.Outlined.AttachFile,
                    text = "文件库",
                    onClick = { onOpenLibrary(AgentLibraryKind.Files) },
                    modifier = Modifier.agentEnter(delayMillis = 90)
                )
                AgentWorkspaceDrawerFeatureRow(
                    icon = Icons.Outlined.Event,
                    text = "计划表",
                    onClick = onOpenPlans,
                    testTag = "agent_plans_entry",
                    modifier = Modifier.agentEnter(delayMillis = 135)
                )
                // 其余功能折叠在「更多功能」里
                AnimatedVisibility(visible = featuresExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        AgentWorkspaceDrawerFeatureRow(
                            icon = Icons.Outlined.Analytics,
                            text = "我的进度",
                            onClick = onOpenProgress,
                            testTag = "agent_progress_entry"
                        )
                        AgentWorkspaceDrawerFeatureRow(
                            icon = Icons.AutoMirrored.Outlined.FactCheck,
                            text = "错题本",
                            onClick = onOpenReview,
                            testTag = "agent_review_entry"
                        )
                        AgentWorkspaceDrawerFeatureRow(
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            text = "生词本",
                            onClick = onOpenVocab,
                            testTag = "agent_vocab_entry"
                        )
                        AgentWorkspaceDrawerFeatureRow(
                            icon = Icons.AutoMirrored.Filled.HelpOutline,
                            text = "使用帮助",
                            onClick = onOpenHelp,
                            testTag = "agent_help_entry"
                        )
                        // 实验/调试入口只在 debug 构建可见，release 用户看不到内部术语("微元/灰度")。
                        if (BuildConfig.DEBUG) {
                            AgentWorkspaceDrawerFeatureRow(
                                icon = Icons.Outlined.AutoAwesome,
                                text = "微元卡 (实验)",
                                onClick = { showMicroLab = true },
                                testTag = "agent_micro_lab_entry"
                            )
                        }
                    }
                }
                AgentWorkspaceDrawerFeatureRow(
                    icon = if (featuresExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    text = if (featuresExpanded) "收起" else "更多功能",
                    onClick = { featuresExpanded = !featuresExpanded },
                    testTag = "agent_more_features_toggle"
                )
            }
            if (showMicroLab) {
                Dialog(
                    onDismissRequest = { showMicroLab = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            AgentTextAction(
                                text = "关闭",
                                onClick = { showMicroLab = false },
                                modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)
                            )
                            MicroCardLabScreen()
                        }
                    }
                }
            }
            Spacer(Modifier.height(38.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (searchQuery.isBlank()) "最近" else "搜索结果",
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(18.dp))
            if (loading && filteredWorkspaces.isNotEmpty()) {
                // 已有内容时的刷新：保留细进度条不打断列表。
                AgentLinearProgress(Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (filteredWorkspaces.isEmpty()) {
                    if (loading) {
                        // 首载空列表：骨架屏（shimmer）代替进度条 + 「暂无」，观感更稳。
                        listOf(0.86f, 0.62f, 0.74f, 0.5f).forEachIndexed { index, widthFraction ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .agentEnter(delayMillis = index * 45),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                AgentSkeleton(
                                    modifier = Modifier
                                        .fillMaxWidth(widthFraction)
                                        .height(15.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            "暂无",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                } else {
                    // 「最近」默认只显示前 8 条，其余收进「工作区」整页（搜索时不设上限，便于找全）。
                    val recentLimit = 8
                    val capped = searchQuery.isBlank() && filteredWorkspaces.size > recentLimit
                    val shownWorkspaces = if (capped) filteredWorkspaces.take(recentLimit) else filteredWorkspaces
                    shownWorkspaces.forEach { workspace ->
                        AgentWorkspaceDrawerRow(
                            workspace = workspace,
                            active = workspace.id == activeWorkspace?.id,
                            onOpen = { onOpenWorkspace(workspace) },
                            onOpenMenu = onOpenWorkspaceMenu
                        )
                    }
                    if (capped) {
                        Text(
                            "查看全部 ${filteredWorkspaces.size} 个工作区 →",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenWorkspaceDirectory() }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
                Spacer(Modifier.height(116.dp))
            }
            }
        }
        AgentWorkspaceDrawerChatButton(
            onClick = onNewWorkspace,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 4.dp)
        )
    }
}

@Composable
private fun AgentWorkspaceDrawerHeader(
    searchExpanded: Boolean,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    authSession: AuthSession?,
    accountExpanded: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleAccount: (() -> Unit)?,
    onOpenSettings: () -> Unit
) {
    val searchReveal by animateFloatAsState(
        targetValue = if (searchExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "drawerSearchReveal"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Text(
                "ListenE",
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - searchReveal
                    translationY = -6.dp.toPx() * searchReveal
                }
            )
            if (searchExpanded || searchReveal > 0.01f) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .graphicsLayer {
                            alpha = searchReveal
                            scaleX = 0.88f + searchReveal * 0.12f
                            scaleY = 0.94f + searchReveal * 0.06f
                            translationX = 22.dp.toPx() * (1f - searchReveal)
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f))
                        .focusRequester(searchFocusRequester)
                        .padding(horizontal = 18.dp),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isBlank()) {
                                Text(
                                    "搜索工作区",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
            border = null,
            modifier = Modifier.height(46.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AgentDrawerPillIcon(
                    icon = Icons.Outlined.Search,
                    contentDescription = "搜索工作区",
                    selected = searchExpanded,
                    onClick = onToggleSearch
                )
                AgentDrawerPillIcon(
                    icon = Icons.Outlined.Person,
                    contentDescription = authSession?.user?.displayName?.ifBlank { authSession.user.email } ?: "账号",
                    selected = accountExpanded,
                    onClick = onToggleAccount
                )
                AgentDrawerPillIcon(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "设置",
                    selected = false,
                    onClick = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun AgentWorkspaceDrawerAccountPopover(
    session: AuthSession,
    onOpenAccount: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(244.dp)
            .agentSoftShadow(
                cornerRadius = 18.dp,
                alpha = 0.075f,
                blur = 18.dp,
                spread = 3.dp
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AgentAvatar(user = session.user, size = 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        session.user.displayName.ifBlank { "ListenE 用户" },
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        session.user.email,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            AgentTextAction(
                text = "账号管理",
                onClick = onOpenAccount,
                modifier = Modifier.fillMaxWidth(),
                height = 38.dp,
                horizontalPadding = 12.dp,
                primary = true
            )
            AgentTextAction(
                text = "退出登录",
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                height = 38.dp,
                horizontalPadding = 12.dp
            )
        }
    }
}

@Composable
internal fun AgentDrawerPillIcon(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    onClick: (() -> Unit)?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "drawerPillIconPress"
    )
    val activeAlpha by animateFloatAsState(
        targetValue = if (selected || pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "drawerPillIconActive"
    )
    val iconLift by animateDpAsState(
        targetValue = if (pressed) 1.dp else 0.dp,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "drawerPillIconLift"
    )
    val baseModifier = Modifier
        .size(30.dp)
        .scale(pressScale)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = activeAlpha * 0.82f))
        .border(
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = activeAlpha * 0.72f)),
            CircleShape
        )
    Box(
        modifier = if (onClick == null) {
            baseModifier
        } else {
            baseModifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
        },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .offset(y = iconLift)
                .size(23.dp)
        )
    }
}

@Composable
private fun AgentWorkspaceDrawerFeatureRow(
    icon: ImageVector,
    text: String,
    onClick: (() -> Unit)? = null,
    testTag: String = "",
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "drawerFeaturePress"
    )
    Row(
        modifier = modifier
            .scale(pressScale)
            .fillMaxWidth()
            .height(38.dp)
            .then(if (testTag.isBlank()) Modifier else Modifier.testTag(testTag))
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AgentWorkspaceDrawerRow(
    workspace: LearningWorkspace,
    active: Boolean,
    onOpen: () -> Unit,
    onOpenMenu: (AgentWorkspaceActionMenuTarget) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    var anchorTopLeft by remember { mutableStateOf(IntOffset.Zero) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                anchorTopLeft = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                anchorSize = coordinates.size
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpen,
                onLongClick = {
                    onOpenMenu(
                        AgentWorkspaceActionMenuTarget(
                            workspace = workspace,
                            anchorTopLeft = anchorTopLeft,
                            anchorSize = anchorSize
                        )
                    )
                }
            )
            .padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            workspace.title.ifBlank { "未命名工作区" },
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (workspace.pinnedAt > 0L) {
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = "已置顶",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun AgentWorkspaceDrawerChatButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "drawerChatButtonPress"
    )
    Row(
        modifier = modifier
            .scale(pressScale)
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .height(52.dp)
            .width(128.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.EditNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "聊天",
            color = MaterialTheme.colorScheme.surface,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

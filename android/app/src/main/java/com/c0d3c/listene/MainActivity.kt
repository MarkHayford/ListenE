package com.c0d3c.listene

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.c0d3c.listene.ui.theme.ListenETheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveFullscreen()
        val initialNavIntent = parseNavIntent(intent)
        setContent {
            ListenETheme {
                var showSplash by remember { mutableStateOf(true) }
                var authSession by remember { mutableStateOf<AuthSession?>(null) }
                var authLoading by remember { mutableStateOf(false) }
                var authError by remember { mutableStateOf<String?>(null) }
                var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    updateInfo = AppUpdateStore.check(this@MainActivity)
                }

                LaunchedEffect(Unit) {
                    authSession = AuthStore.loadSession(this@MainActivity)
                    if (authSession != null) {
                        // 启动即滑动续期：换发新 token 顺延有效期；token 已失效则清会话→回落登录页（自动重登）。
                        authSession = AuthStore.refreshToken(this@MainActivity)
                    }
                    if (authSession != null) {
                        runCatching { PlanStore.refreshRemote(this@MainActivity) }
                        PlanReminderScheduler.rescheduleAll(this@MainActivity)
                    }
                }
                LaunchedEffect(Unit) {
                    delay(1200)
                    showSplash = false
                }
                // 通知权限：先说明用途再请求（一次性）。仅登录后、且系统权限未授予、且没提示过时出现。
                var showNotifPrompt by remember { mutableStateOf(false) }
                LaunchedEffect(showSplash, authSession) {
                    if (!showSplash && authSession != null && needsNotificationPrompt()) {
                        showNotifPrompt = true
                    }
                }
                if (showNotifPrompt) {
                    NotificationPermissionPromptDialog(
                        onAllow = {
                            showNotifPrompt = false
                            AppSettings.setNotifPromptShown(this@MainActivity)
                            requestNotificationPermissionIfNeeded()
                        },
                        onLater = {
                            showNotifPrompt = false
                            AppSettings.setNotifPromptShown(this@MainActivity)
                        }
                    )
                }
                Box(Modifier.fillMaxSize()) {
                    val update = updateInfo
                    if (update != null && update.mustUpdate) {
                        AppForceUpdateScreen(
                            info = update,
                            onUpdate = {
                                runCatching {
                                    this@MainActivity.startActivity(
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(update.downloadUrl))
                                    )
                                }
                            },
                            onExit = { this@MainActivity.finishAffinity() }
                        )
                    } else if (showSplash) {
                        SplashScreen()
                    } else if (authSession == null) {
                        AuthGateScreen(
                            loading = authLoading,
                            error = authError,
                            onLogin = { email, password ->
                                scope.launch {
                                    authLoading = true
                                    authError = null
                                    runCatching { AuthStore.login(this@MainActivity, email, password) }
                                        .onSuccess {
                                            authSession = it
                                            AppNoticeBus.success("登录成功")
                                        }
                                        .onFailure { authError = it.message ?: "登录失败" }
                                    authLoading = false
                                }
                            },
                            onRegister = { email, password, displayName ->
                                scope.launch {
                                    authLoading = true
                                    authError = null
                                    runCatching { AuthStore.register(this@MainActivity, email, password, displayName) }
                                        .onSuccess {
                                            authSession = it
                                            AppNoticeBus.success("注册成功")
                                        }
                                        .onFailure { authError = it.message ?: "注册失败" }
                                    authLoading = false
                                }
                            }
                        )
                    } else {
                        AgentListenEApp(
                            initialNavIntent = initialNavIntent,
                            authSession = authSession,
                            onLogout = {
                                val token = AuthStore.loadSession(this@MainActivity)?.token.orEmpty()
                                AuthStore.clearSession(this@MainActivity)
                                authSession = null
                                AppNoticeBus.success("退出登录成功")
                                if (token.isNotBlank()) {
                                    scope.launch { AuthStore.notifyLogout(token) }
                                }
                            },
                            onAccountDeleted = {
                                // deleteAccount 已清本地会话；这里只需回登录页并提示。
                                authSession = null
                                AppNoticeBus.success("账号已注销")
                            },
                            onSessionUpdated = { authSession = it },
                            onSessionExpired = {
                                // 收到 401/登录态失效：先静默续期；续期也失败（账号真失效）就清会话→回登录页，
                                // 而不是让后台/发送请求把「连接失败」大卡糊到聊天区。
                                scope.launch {
                                    val refreshed = AuthStore.refreshToken(this@MainActivity)
                                    if (refreshed == null) {
                                        authSession = null
                                        AppNoticeBus.error("登录已过期，请重新登录")
                                    } else {
                                        authSession = refreshed
                                    }
                                }
                            }
                        )
                    }
                    AppNoticeHost()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppForegroundTracker.onEnterForeground()
    }

    override fun onStop() {
        super.onStop()
        AppForegroundTracker.onEnterBackground()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveFullscreen()
        PlanDueBus.signal()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatchNavIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 是否需要弹「开启通知」说明：API 33+、未授权、且从未提示过。 */
    private fun needsNotificationPrompt(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return !granted && !AppSettings.isNotifPromptShown(this)
    }

    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dispatchNavIntent(intent: Intent?) {
        parseNavIntent(intent)?.let { nav ->
            when (nav) {
                is NavIntent.Listening -> AppNavigationBus.emit(AppNavEvent.OpenListeningRecord(nav.recordId))
            }
        }
    }

    private fun parseNavIntent(intent: Intent?): NavIntent? {
        if (intent == null) return null
        intent.getStringExtra("recordId")?.let { return NavIntent.Listening(it) }
        intent.getStringExtra(GenerationNotificationManager.EXTRA_OPEN_LISTENING_RECORD)?.let { return NavIntent.Listening(it) }
        val data = intent.data ?: return null
        if (data.scheme == "listene") {
            data.getQueryParameter("recordId")?.let { return NavIntent.Listening(it) }
            data.getQueryParameter(GenerationNotificationManager.EXTRA_OPEN_LISTENING_RECORD)?.let { return NavIntent.Listening(it) }
        }
        return null
    }
}

@Composable
internal fun NotificationPermissionPromptDialog(
    onAllow: () -> Unit,
    onLater: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onLater) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Default.Notifications,
                title = "开启通知",
                subtitle = "生成完成、学习计划到点时提醒你；仅此两类，不发营销消息。"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgentTextAction(
                    text = "暂不",
                    onClick = onLater,
                    modifier = Modifier.weight(1f),
                    height = 44.dp
                )
                AgentTextAction(
                    text = "开启通知",
                    onClick = onAllow,
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                    primary = true
                )
            }
        }
    }
}

@Composable
fun SplashScreen() {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "splashEnter"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = 28.dp, end = 28.dp)
                .widthIn(max = 360.dp)
                .alpha(enterProgress)
                .scale(0.96f + 0.04f * enterProgress),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                "ListenE",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

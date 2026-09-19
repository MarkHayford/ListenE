package com.c0d3c.listene

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.c0d3c.listene.ui.theme.AppThemeState
import kotlinx.coroutines.launch

// 设置面板（Settings）：侧边栏顶部「设置」入口打开的抽屉页。
// 复用既有面板模式：返回头 + 滚动列 + AgentSurface/AgentTextAction，保持全局 UI 一致。

@Composable
internal fun AgentSettingsPanel(
    authSession: AuthSession?,
    onOpenHelp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAccount: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var notifyEnabled by remember {
        mutableStateOf(AppSettings.isNotifyOnCompleteEnabled(ctx))
    }
    var showPrivacy by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutDialog(onClose = { showAbout = false })
    }
    if (showPrivacy) {
        AgentPrivacyPolicyDialog(onClose = { showPrivacy = false })
    }
    if (showTerms) {
        AgentTermsDialog(onClose = { showTerms = false })
    }
    if (showLicenses) {
        AgentLicensesDialog(onClose = { showLicenses = false })
    }
    val hasNotifPermission = GenerationNotificationManager.hasNotificationPermission(ctx)
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                AgentSectionTitle("设置", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))

            if (authSession != null) {
                AgentSurface(modifier = Modifier.agentEnter(delayMillis = 0), onClick = onOpenAccount) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AgentAvatar(user = authSession.user, size = 40.dp)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                authSession.user.displayName.ifBlank { "ListenE 用户" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "账号管理：头像、昵称、邮箱、密码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "打开账号管理",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 25)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "生成完成后通知我",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "离开 App 时若仍在后台生成（如 AI 回复），完成或失败会发通知提醒你。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notifyEnabled,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                notifyEnabled = it
                                AppSettings.setNotifyOnCompleteEnabled(ctx, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    if (notifyEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                if (hasNotifPermission) "系统通知权限：已开启" else "系统通知权限未开启，通知无法送达",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            AgentTextAction(
                                text = if (hasNotifPermission) "通知设置" else "去开启",
                                onClick = { openAppNotificationSettings(ctx) },
                                primary = !hasNotifPermission,
                                height = 36.dp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 40)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "外观",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "深色可跟随系统自动切换，也可以手动固定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val themeMode = AppThemeState.mode.value ?: AppSettings.THEME_SYSTEM
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            AppSettings.THEME_SYSTEM to "跟随系统",
                            AppSettings.THEME_LIGHT to "浅色",
                            AppSettings.THEME_DARK to "深色"
                        ).forEach { (value, label) ->
                            AgentTextAction(
                                text = label,
                                onClick = { AppThemeState.set(ctx, value) },
                                primary = themeMode == value,
                                height = 34.dp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 80), onClick = onOpenHelp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "使用帮助",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "快速上手与常见问题",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "打开使用帮助",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 100)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "条款与许可",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    SettingsLegalRow(Icons.Outlined.Description, "用户协议") { showTerms = true }
                    SettingsLegalRow(Icons.Outlined.PrivacyTip, "隐私政策") { showPrivacy = true }
                    SettingsLegalRow(Icons.Outlined.Code, "开源许可") { showLicenses = true }
                }
            }

            Spacer(Modifier.height(14.dp))

            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 120), onClick = { showAbout = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "关于 ListenE",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "版本 ${BuildConfig.VERSION_NAME} · 点击查看详情与检查更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "打开关于页",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var resultText by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        AgentFloatingPanel {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    "ListenE",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "AI 听力练习",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "作者 C0D3C",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "gaoxingyu2006@icloud.com",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:gaoxingyu2006@icloud.com"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                if (resultText.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        resultText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
                val pendingUpdate = updateInfo
                if (pendingUpdate != null && pendingUpdate.downloadUrl.isNotBlank()) {
                    AgentTextAction(
                        text = "下载新版本",
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(pendingUpdate.downloadUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                        primary = true
                    )
                } else {
                    AgentTextAction(
                        text = if (checking) "检查中…" else "检查更新",
                        onClick = {
                            if (checking) return@AgentTextAction
                            checking = true
                            resultText = ""
                            scope.launch {
                                val info = AppUpdateStore.check(ctx)
                                checking = false
                                when {
                                    info == null -> resultText = "检查失败，请稍后再试"
                                    info.latestVersionCode > BuildConfig.VERSION_CODE -> {
                                        updateInfo = info
                                        resultText = "发现新版本 v${info.latestVersionName.ifBlank { "?" }}"
                                    }
                                    else -> resultText = "已是最新版本"
                                }
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                        primary = true
                    )
                }
            }
        }
    }
}

@Composable
internal fun DeleteAccountDialog(
    email: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!deleting) onDismiss() }) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.PersonRemove,
                title = "注销账号",
                subtitle = "此操作不可恢复，请谨慎确认。",
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
                        "将永久删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        email,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "账号及服务器上的全部数据：工作区、对话与练习记录、学习计划、生词本、错题本、分类等。删除后无法找回，本机也将退出登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }
            }
            if (error.isNotBlank()) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgentTextAction(
                    text = "取消",
                    onClick = onDismiss,
                    enabled = !deleting,
                    modifier = Modifier.weight(1f),
                    height = 44.dp
                )
                AgentTextAction(
                    text = if (deleting) "注销中…" else "确认注销",
                    onClick = {
                        if (deleting) return@AgentTextAction
                        deleting = true
                        error = ""
                        scope.launch {
                            AuthStore.deleteAccount(ctx)
                                .onSuccess { onDeleted() }
                                .onFailure {
                                    error = it.message ?: "注销失败，请稍后重试"
                                    deleting = false
                                }
                        }
                    },
                    enabled = !deleting,
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
private fun SettingsLegalRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "打开$title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
        )
    }
}

private fun openAppNotificationSettings(ctx: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", ctx.packageName, null))
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}

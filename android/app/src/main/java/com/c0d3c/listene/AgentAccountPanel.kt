package com.c0d3c.listene

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.util.Locale

// 账号管理（Account）：侧栏账号弹层进入的抽屉页——预设头像 / 改昵称 / 改邮箱 / 改密码。
// 头像用「预设 emoji」方案存 user.avatar（空串 = 昵称首字母圆形头像），随账号同步。

internal val agentAvatarPresets = listOf(
    "🦊", "🐼", "🐯", "🦁", "🐨", "🐸",
    "🐳", "🦉", "🐰", "🦄", "🐙", "🦅",
    "🌵", "🌙", "⚡", "🎧", "📚", "🎯"
)

/** 圆形头像：有预设 emoji 显示 emoji；否则昵称/邮箱首字符；再否则人形图标。 */
@Composable
internal fun AgentAvatar(
    user: AuthUser?,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val avatar = user?.avatar.orEmpty()
    val fallbackChar = (user?.displayName?.trim().orEmpty().ifBlank { user?.email.orEmpty() })
        .firstOrNull()?.uppercase(Locale.ROOT).orEmpty()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.84f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            avatar.isNotBlank() -> Text(
                avatar,
                fontSize = (size.value * 0.5f).sp,
                lineHeight = (size.value * 0.58f).sp
            )
            fallbackChar.isNotBlank() -> Text(
                fallbackChar,
                fontSize = (size.value * 0.42f).sp,
                lineHeight = (size.value * 0.5f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            else -> Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size * 0.58f)
            )
        }
    }
}

@Composable
internal fun AgentAccountPanel(
    session: AuthSession,
    onSessionUpdated: (AuthSession) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
    onAccountDeleted: () -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showEditName by remember { mutableStateOf(false) }
    var showEditEmail by remember { mutableStateOf(false) }
    var showEditPassword by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    if (showDeleteAccount) {
        DeleteAccountDialog(
            email = session.user.email,
            onDismiss = { showDeleteAccount = false },
            onDeleted = {
                showDeleteAccount = false
                onAccountDeleted()
            }
        )
    }

    if (showAvatarPicker) {
        AvatarPickerDialog(
            current = session.user.avatar,
            busy = busy,
            onPick = { picked ->
                busy = true
                scope.launch {
                    AuthStore.updateProfile(ctx, avatar = picked)
                        .onSuccess {
                            AppNoticeBus.success("头像已更新")
                            onSessionUpdated(it)
                            showAvatarPicker = false
                        }
                        .onFailure { AppNoticeBus.error(it.message ?: "头像更新失败") }
                    busy = false
                }
            },
            onDismiss = { if (!busy) showAvatarPicker = false }
        )
    }
    if (showEditName) {
        EditNameDialog(
            initial = session.user.displayName,
            onSaved = { onSessionUpdated(it); showEditName = false },
            onDismiss = { showEditName = false }
        )
    }
    if (showEditEmail) {
        EditEmailDialog(
            currentEmail = session.user.email,
            onSaved = { onSessionUpdated(it); showEditEmail = false },
            onDismiss = { showEditEmail = false }
        )
    }
    if (showEditPassword) {
        EditPasswordDialog(onDone = { showEditPassword = false }, onDismiss = { showEditPassword = false })
    }

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
                AgentSectionTitle("账号管理", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .agentEnter(delayMillis = 0),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val avatarInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier.clickable(
                        interactionSource = avatarInteraction,
                        indication = null
                    ) { showAvatarPicker = true }
                ) {
                    AgentAvatar(user = session.user, size = 84.dp)
                }
                AgentTextAction(
                    text = "更换头像",
                    onClick = { showAvatarPicker = true },
                    height = 34.dp
                )
            }

            Spacer(Modifier.height(24.dp))

            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 55)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AccountFieldRow("昵称", session.user.displayName.ifBlank { "未设置" }) { showEditName = true }
                    AccountFieldRow("邮箱", session.user.email) { showEditEmail = true }
                    AccountFieldRow("密码", "••••••••") { showEditPassword = true }
                }
            }

            Spacer(Modifier.height(24.dp))
            AgentTextAction(
                text = "退出登录",
                onClick = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .agentEnter(delayMillis = 110),
                height = 46.dp
            )
            Spacer(Modifier.height(10.dp))
            AgentTextAction(
                text = "注销账号",
                onClick = { showDeleteAccount = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .agentEnter(delayMillis = 140),
                height = 46.dp,
                accent = AgentPracticeWrong
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountFieldRow(label: String, value: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        AgentTextAction(text = "修改", onClick = onEdit, height = 32.dp)
    }
}

@Composable
private fun AvatarPickerDialog(
    current: String,
    busy: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.Person,
                title = "更换头像",
                subtitle = if (busy) "保存中…" else "选一个预设头像；选「Aa」则用昵称首字母。"
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf("") + agentAvatarPresets) { preset ->
                    val selected = preset == current
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.84f)
                            )
                            .clickable(enabled = !busy) { onPick(preset) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (preset.isBlank()) {
                            Text(
                                "Aa",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(preset, fontSize = 22.sp, lineHeight = 26.sp)
                        }
                    }
                }
            }
            AgentTextAction(
                text = "取消",
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                height = 42.dp
            )
        }
    }
}

@Composable
private fun EditNameDialog(
    initial: String,
    onSaved: (AuthSession) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.Person,
                title = "修改昵称",
                subtitle = "昵称会同步到你的账号。"
            )
            AuthTextField(value = name, onValueChange = { name = it }, label = "昵称", placeholder = "例如：Alex")
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTextAction(text = "取消", onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f), height = 44.dp)
                AgentTextAction(
                    text = if (busy) "保存中…" else "保存",
                    onClick = {
                        val clean = name.trim()
                        if (clean.isBlank()) { error = "昵称不能为空"; return@AgentTextAction }
                        busy = true; error = ""
                        scope.launch {
                            AuthStore.updateProfile(ctx, displayName = clean)
                                .onSuccess { AppNoticeBus.success("昵称已更新"); onSaved(it) }
                                .onFailure { error = it.message ?: "修改失败" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                    primary = true
                )
            }
        }
    }
}

@Composable
private fun EditEmailDialog(
    currentEmail: String,
    onSaved: (AuthSession) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.Person,
                title = "修改邮箱",
                subtitle = "当前：$currentEmail"
            )
            AuthTextField(value = email, onValueChange = { email = it }, label = "新邮箱", placeholder = "you@example.com")
            AuthTextField(value = password, onValueChange = { password = it }, label = "当前密码（确认身份）", placeholder = "输入当前密码", hidden = true)
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTextAction(text = "取消", onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f), height = 44.dp)
                AgentTextAction(
                    text = if (busy) "保存中…" else "保存",
                    onClick = {
                        val clean = email.trim()
                        when {
                            !clean.contains("@") || clean.length < 5 -> { error = "邮箱格式不正确"; return@AgentTextAction }
                            clean.equals(currentEmail, ignoreCase = true) -> { error = "与当前邮箱相同"; return@AgentTextAction }
                            password.isBlank() -> { error = "请输入当前密码"; return@AgentTextAction }
                        }
                        busy = true; error = ""
                        scope.launch {
                            AuthStore.changeEmail(ctx, clean, password)
                                .onSuccess { AppNoticeBus.success("邮箱已更新"); onSaved(it) }
                                .onFailure { error = it.message ?: "修改失败" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                    primary = true
                )
            }
        }
    }
}

@Composable
private fun EditPasswordDialog(
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.Person,
                title = "修改密码",
                subtitle = "新密码至少 8 位。"
            )
            AuthTextField(value = oldPassword, onValueChange = { oldPassword = it }, label = "当前密码", placeholder = "输入当前密码", hidden = true)
            AuthTextField(value = newPassword, onValueChange = { newPassword = it }, label = "新密码", placeholder = "至少 8 位", hidden = true)
            AuthTextField(value = confirmPassword, onValueChange = { confirmPassword = it }, label = "确认新密码", placeholder = "再输一遍", hidden = true)
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTextAction(text = "取消", onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f), height = 44.dp)
                AgentTextAction(
                    text = if (busy) "保存中…" else "保存",
                    onClick = {
                        when {
                            oldPassword.isBlank() -> { error = "请输入当前密码"; return@AgentTextAction }
                            newPassword.length < 8 -> { error = "新密码至少 8 位"; return@AgentTextAction }
                            newPassword != confirmPassword -> { error = "两次输入的新密码不一致"; return@AgentTextAction }
                            newPassword == oldPassword -> { error = "新密码不能与当前密码相同"; return@AgentTextAction }
                        }
                        busy = true; error = ""
                        scope.launch {
                            AuthStore.changePassword(ctx, oldPassword, newPassword)
                                .onSuccess { AppNoticeBus.success("密码已更新"); onDone() }
                                .onFailure { error = it.message ?: "修改失败" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                    primary = true
                )
            }
        }
    }
}

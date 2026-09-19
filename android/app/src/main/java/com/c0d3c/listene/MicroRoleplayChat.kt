package com.c0d3c.listene

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

// 口语陪练 · 聊天卡（完整版，方案 C）：把输入框旁的口语陪练整合进聊天卡片，并补齐三块——
//   1) 自由聊天模式（scenario 留空/日常闲聊 → 无固定情景的陪练伙伴）
//   2) 语音：🎤 录音 → ASR 转写自动发送；AI 回复自动朗读(TTS) + 逐句「重听」
//   3) 跨会话记忆：对话按 场景/目标 本地持久化，退出重开自动恢复，历史每轮回传模型 → 真·记得你
// 复用既有后端 roleplayTurn/roleplayFeedback、ASR(transcribeAudio)、TTS(synthesizeSpeech)
// 与录音/播放器 recordAgentWavFile / AgentExoAudio / AudioCache；UI 复用 AgentRoleplayBubble/FeedbackReport。

// 跨会话记忆：把一段陪练对话按稳定 key 持久化到本地（SharedPreferences，JSON）。
internal object RoleplayMemoryStore {
    private const val PREFS = "roleplay_memory"
    private const val MAX_KEEP = 40

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS), Context.MODE_PRIVATE)

    // 稳定 key：同一「场景+目标」的陪练视为同一段可延续的对话（留空即"自由聊天"伙伴）。
    fun memoryKey(node: MicroNode.RoleplayTurn): String {
        val basis = (node.scenario.trim() + "|" + node.goal.trim()).trim('|', ' ').ifBlank { "free_chat" }
        return "rp_" + basis.hashCode().toUInt().toString(16)
    }

    fun load(ctx: Context, key: String): MutableList<RoleplayMessage> {
        val raw = prefs(ctx).getString(key, null) ?: return mutableListOf()
        val obj = parseJsonObjectOrNull(raw) ?: return mutableListOf()
        val arr = obj.arrOrNull("messages") ?: return mutableListOf()
        val out = ArrayList<RoleplayMessage>(arr.size)
        for (i in 0 until arr.size) {
            val o = arr.objOrNull(i) ?: continue
            val content = o.str("content").trim()
            if (content.isNotBlank()) {
                out.add(RoleplayMessage(o.str("role").ifBlank { "assistant" }, content, o.str("hint")))
            }
        }
        return out
    }

    fun save(ctx: Context, key: String, messages: List<RoleplayMessage>) {
        val trimmed = messages.takeLast(MAX_KEEP)
        val json = buildJsonObject {
            putJsonArray("messages") {
                trimmed.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                        put("hint", m.hint)
                    }
                }
            }
        }.toString()
        prefs(ctx).edit().putString(key, json).apply()
    }

    fun clear(ctx: Context, key: String) {
        prefs(ctx).edit().remove(key).apply()
    }
}

// 从「输入框口语陪练按钮」直接在聊天里插入一张口语陪练卡（roleplay_turn 微元）用的 JSON。
// scenario 留空/含"自由/闲聊/small talk"→卡内自动走自由聊天；否则按 场景+目标 开一段带持久化记忆的陪练。
internal fun buildRoleplayTurnCardJson(scenario: String, goal: String, opening: String = ""): String =
    buildJsonObject {
        put("title", "口语陪练")
        putJsonArray("nodes") {
            addJsonObject {
                put("type", "roleplay_turn")
                put("scenario", scenario)
                put("opening", opening)
                put("goal", goal)
                put("turns", 0)
            }
        }
    }.toString()

@Composable
internal fun MicroRoleplayChatView(node: MicroNode.RoleplayTurn, instanceKey: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 自由聊天：无固定情景（或明确日常闲聊）→ 无场景约束的陪练伙伴。
    val freeChat = node.scenario.isBlank() ||
        Regex("自由|闲聊|随便聊|small talk|free chat", RegexOption.IGNORE_CASE).containsMatchIn(node.scenario)
    val effectiveScenario = node.scenario.ifBlank {
        "Friendly free English chat: be a warm, natural conversation partner. Keep replies short, ask follow-up questions, and gently correct only clear mistakes."
    }
    val memKey = remember(node) { RoleplayMemoryStore.memoryKey(node) }
    val defaultOpening = if (freeChat) "Hey! Good to see you again. What's on your mind today?" else node.opening

    val messages = remember(memKey) {
        mutableStateListOf<RoleplayMessage>().apply {
            addAll(RoleplayMemoryStore.load(ctx, memKey))
            if (isEmpty() && defaultOpening.isNotBlank()) add(RoleplayMessage("assistant", defaultOpening))
        }
    }
    var input by rememberSaveable(memKey) { mutableStateOf("") }
    var loading by remember(memKey) { mutableStateOf(false) }
    var errorText by remember(memKey) { mutableStateOf<String?>(null) }
    var feedback by remember(memKey) { mutableStateOf<RoleplayFeedbackResult?>(null) }
    var feedbackLoading by remember(memKey) { mutableStateOf(false) }
    var recording by remember(memKey) { mutableStateOf(false) }
    var transcribing by remember(memKey) { mutableStateOf(false) }
    var ttsBusyContent by remember(memKey) { mutableStateOf<String?>(null) }
    var confirmReset by remember(memKey) { mutableStateOf(false) } // "清空记忆"二次确认
    val recordFile = remember { mutableStateOf<File?>(null) }
    val recordJob = remember { mutableStateOf<Job?>(null) }
    val player = remember { mutableStateOf<AgentExoAudio?>(null) }

    fun persist() = RoleplayMemoryStore.save(ctx, memKey, messages.toList())

    fun playTts(text: String) {
        if (text.isBlank()) return
        ttsBusyContent = text
        scope.launch {
            val res = runCatching { AgentConversationService.synthesizeSpeech(text) }.getOrNull()
            if (res == null || res.audioUrl.isBlank()) { ttsBusyContent = null; return@launch }
            val playPath = AudioCache.localPath(ctx, res.audioUrl)
            withContext(Dispatchers.Main) {
                runCatching {
                    player.value?.release()
                    val mp = AgentExoAudio(ctx)
                    player.value = mp
                    mp.onPrepared = { mp.start(); ttsBusyContent = null }
                    mp.onCompletion = { mp.release(); if (player.value === mp) player.value = null }
                    mp.onError = { ttsBusyContent = null }
                    mp.setDataSource(playPath)
                    mp.prepare()
                }.onFailure { ttsBusyContent = null }
            }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isBlank() || loading) return
        input = ""
        errorText = null
        confirmReset = false
        messages.add(RoleplayMessage("user", t))
        persist()
        loading = true
        scope.launch {
            try {
                val turn = AgentConversationService.roleplayTurn(effectiveScenario, messages.toList(), "")
                messages.add(RoleplayMessage("assistant", turn.reply, turn.hint))
                persist()
                playTts(turn.reply)
            } catch (e: Exception) {
                errorText = e.message ?: "对话失败，请重试"
            } finally {
                loading = false
            }
        }
    }

    fun startRecording() {
        if (recording || loading || transcribing) return
        errorText = null
        val file = File(ctx.cacheDir, "rp_${System.currentTimeMillis()}.wav")
        recordFile.value = file
        recording = true
        recordJob.value = scope.launch(Dispatchers.IO) {
            runCatching { recordAgentWavFile(file) }.onFailure {
                withContext(Dispatchers.Main) {
                    recording = false; recordJob.value = null; recordFile.value = null; errorText = "录音启动失败"
                }
            }
        }
    }

    fun stopRecordingAndSend() {
        if (!recording) return
        recording = false
        val job = recordJob.value
        recordJob.value = null
        val file = recordFile.value
        recordFile.value = null
        transcribing = true
        scope.launch {
            job?.cancelAndJoin()
            if (file == null || !file.exists() || file.length() <= 44L) {
                transcribing = false
                errorText = "没有录到声音，请重试"
                file?.let { runCatching { it.delete() } }
                return@launch
            }
            val base64 = withContext(Dispatchers.IO) { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }
            val text = runCatching { AgentConversationService.transcribeAudio(base64, "audio/wav", "en") }.getOrNull()
            transcribing = false
            runCatching { file.delete() }
            if (!text.isNullOrBlank()) send(text) else errorText = "没听清，请再说一次"
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else errorText = "需要麦克风权限才能语音对话"
    }

    fun toggleMic() {
        if (recording) { stopRecordingAndSend(); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun requestFeedback() {
        if (feedbackLoading || messages.none { it.role == "user" }) return
        errorText = null
        feedbackLoading = true
        scope.launch {
            try {
                val fb = AgentConversationService.roleplayFeedback(effectiveScenario, messages.toList())
                feedback = fb
                // opt3：把口语反馈要点回灌统一用户模型（跨功能画像更全）。best-effort。
                runCatching { UserModelStore.pushWeaknesses(ctx, fb.improvements) }
            } catch (e: Exception) {
                errorText = e.message ?: "评分失败，请重试"
            } finally {
                feedbackLoading = false
            }
        }
    }

    fun resetConversation() {
        if (loading || recording || transcribing) return
        RoleplayMemoryStore.clear(ctx, memKey)
        messages.clear()
        if (defaultOpening.isNotBlank()) messages.add(RoleplayMessage("assistant", defaultOpening))
        feedback = null
        errorText = null
    }

    DisposableEffect(Unit) {
        onDispose {
            player.value?.release(); player.value = null
            recordJob.value?.cancel()
            recordFile.value?.let { runCatching { it.delete() } }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val header = buildString {
            if (freeChat) append("🗣️ 自由聊天") else append("🎭 ").append(node.scenario)
            if (node.goal.isNotBlank()) append("\n🎯 ").append(node.goal)
            append("\n💾 会记住我们的对话（下次打开继续）")
        }
        AgentCardInlineNotice(header)
        messages.forEach { message ->
            AgentRoleplayBubble(
                message = message,
                ttsLoading = ttsBusyContent == message.content,
                onReplay = { playTts(message.content) }
            )
        }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("对方正在回复…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (recording) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = AgentPracticeWrong, modifier = Modifier.size(16.dp))
                Text("正在录音…点停止发送", style = MaterialTheme.typography.bodySmall, color = AgentPracticeWrong)
            }
        }
        if (transcribing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AgentStudyBlue)
                Text("正在识别你说的话…", style = MaterialTheme.typography.bodySmall, color = AgentStudyBlue)
            }
        }
        errorText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AgentPracticeWrong) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentRoleplayInputField(
                value = input,
                onValueChange = { input = it },
                placeholder = if (freeChat) "用英文说点什么…" else "用英文回一句…",
                modifier = Modifier.weight(1f)
            )
            AgentIconControl(
                icon = if (recording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (recording) "停止并发送" else "语音输入",
                onClick = { toggleMic() },
                accent = if (recording) AgentPracticeWrong else AgentStudyBlue,
                enabled = !loading && !transcribing,
                size = 44.dp,
                iconSize = 22.dp,
                filled = recording
            )
            AgentTextAction(
                text = "发送",
                onClick = { send(input) },
                primary = true,
                enabled = !loading && !recording && !transcribing && input.isNotBlank(),
                height = 44.dp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (messages.any { it.role == "user" }) {
                AgentTextAction(
                    text = if (feedbackLoading) "评分中…" else "结束并评分",
                    onClick = { requestFeedback() },
                    modifier = Modifier.weight(1f),
                    enabled = !feedbackLoading,
                    icon = Icons.Default.Analytics,
                    height = 38.dp
                )
            }
            AgentTextAction(
                text = if (confirmReset) "确认清空？(不可恢复)" else "清空记忆",
                onClick = {
                    if (confirmReset) {
                        resetConversation()
                        confirmReset = false
                    } else {
                        confirmReset = true
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !loading && !recording && !transcribing,
                icon = Icons.Default.Refresh,
                accent = if (confirmReset) AgentPracticeWrong else null,
                height = 38.dp
            )
        }
        feedback?.let {
            AgentCardDivider()
            AgentRoleplayFeedbackReport(it)
        }
    }
}

// ===== 新版口语陪练：主输入框驱动 + 右侧按钮开关 + 已结束卡可「继续上次对话」 =====

internal data class RoleplayFinishedData(
    val scenario: String,
    val goal: String,
    val key: String,
    val messages: List<RoleplayMessage>,
    val overall: Int,        // -1 表示无评分
    val comment: String,
    val improvements: List<String>
)

private fun roleplayHeaderText(scenario: String, goal: String): String {
    val freeChat = scenario.isBlank() ||
        Regex("自由|闲聊|small talk|free chat", RegexOption.IGNORE_CASE).containsMatchIn(scenario)
    return buildString {
        if (freeChat) append("🗣️ 自由聊天") else append("🎭 ").append(scenario)
        if (goal.isNotBlank()) append("\n🎯 ").append(goal)
    }
}

// 把一段「已结束」的陪练对话(含可选评分)序列化为聊天消息 roleplayJson。
internal fun buildRoleplayFinishedJson(
    scenario: String,
    goal: String,
    key: String,
    messages: List<RoleplayMessage>,
    feedback: RoleplayFeedbackResult?
): String = buildJsonObject {
    put("scenario", scenario)
    put("goal", goal)
    put("key", key)
    put("overall", feedback?.overall ?: -1)
    put("comment", feedback?.comment ?: "")
    putJsonArray("improvements") { (feedback?.improvements ?: emptyList()).forEach { add(it) } }
    putJsonArray("messages") {
        messages.forEach { m ->
            addJsonObject { put("role", m.role); put("content", m.content); put("hint", m.hint) }
        }
    }
}.toString()

internal fun parseRoleplayFinished(json: String): RoleplayFinishedData? {
    val o = parseJsonObjectOrNull(json) ?: return null
    val arr = o.arrOrNull("messages") ?: return null
    val msgs = ArrayList<RoleplayMessage>(arr.size)
    for (i in 0 until arr.size) {
        val m = arr.objOrNull(i) ?: continue
        val content = m.str("content").trim()
        if (content.isNotBlank()) msgs.add(RoleplayMessage(m.str("role").ifBlank { "assistant" }, content, m.str("hint")))
    }
    val impArr = o.arrOrNull("improvements")
    val improvements = if (impArr == null) emptyList() else List(impArr.size) { impArr.str(it).trim() }.filter { it.isNotBlank() }
    return RoleplayFinishedData(
        scenario = o.str("scenario"),
        goal = o.str("goal"),
        key = o.str("key"),
        messages = msgs,
        overall = o.long("overall", -1L).toInt(),
        comment = o.str("comment"),
        improvements = improvements
    )
}

// 进行中的口语陪练面板：只作对话展示，输入由主输入框驱动；没有自带输入框/关闭按钮。
@Composable
internal fun AgentRoleplayActivePanel(
    scenario: String,
    goal: String,
    messages: List<RoleplayMessage>,
    loading: Boolean,
    ttsBusyContent: String,
    onReplay: (String) -> Unit
) {
    AgentSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentCardInlineNotice(roleplayHeaderText(scenario, goal) + "\n💬 在下方输入框对话；点右侧口语陪练按钮可结束并评分")
            messages.forEach { m ->
                AgentRoleplayBubble(message = m, ttsLoading = ttsBusyContent == m.content, onReplay = { onReplay(m.content) })
            }
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("对方正在回复…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// 已结束的口语陪练卡：对话快照 + 评分 + 「继续上次对话」。退出/继续都不在卡里另设关闭按钮。
@Composable
internal fun AgentRoleplayFinishedCard(json: String, onResume: (String, String) -> Unit) {
    val data = remember(json) { parseRoleplayFinished(json) } ?: return
    AgentSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentCardInlineNotice(roleplayHeaderText(data.scenario, data.goal) + "\n✅ 本次陪练已结束")
            data.messages.forEach { m ->
                AgentRoleplayBubble(message = m, ttsLoading = false, onReplay = {})
            }
            if (data.overall in 0..100) {
                AgentCardDivider()
                Text(
                    "本次评分：${data.overall} 分",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = agentSpeakingScoreColor(data.overall)
                )
                if (data.comment.isNotBlank()) {
                    Text(data.comment, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (data.improvements.isNotEmpty()) {
                    Text("可改进：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    data.improvements.take(3).forEach {
                        Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            AgentTextAction(
                text = "继续上次对话",
                onClick = { onResume(data.scenario, data.goal) },
                primary = true,
                icon = Icons.Default.Forum,
                height = 40.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

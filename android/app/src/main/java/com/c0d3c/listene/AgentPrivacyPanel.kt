package com.c0d3c.listene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// 法务文档（隐私政策 / 用户协议 / 开源许可）：登录页与设置页共用的全屏弹层。
// 内容按应用实际行为撰写（账号/学习内容/语音/权限/服务器处理/实际依赖清单）。

private data class LegalSection(val title: String, val body: String)

private class LegalDoc(val title: String, val updatedAt: String, val sections: List<LegalSection>)

private val privacyDoc = LegalDoc(
    title = "隐私政策",
    updatedAt = "更新日期：2026 年 7 月 2 日",
    sections = listOf(
        LegalSection(
            "引言",
            "ListenE（下称「本应用」）是一款 AI 听力与英语学习应用。本政策说明我们收集哪些信息、如何使用与存储，以及你享有的控制权。注册或登录即表示你同意本政策。"
        ),
        LegalSection(
            "我们收集的信息",
            "· 账号信息：注册 / 登录时提供的邮箱、密码与可选昵称。密码仅用于登录验证，App 本地只保存登录令牌，不明文保存密码。\n" +
                "· 学习内容：你创建的工作区与对话消息、发送给 AI 的文字与附件（图片 / 音频 / 视频 / 文档）、练习与批改记录、错题、生词、学习计划与分类。\n" +
                "· 语音：使用跟读打分、口语对话等功能时，经你授权采集的录音，用于发音评分与语音识别。\n" +
                "· 版本信息：App 版本号，用于检查更新。\n" +
                "我们不收集你的位置、通讯录或设备标识，也未接入任何第三方广告或统计 SDK。"
        ),
        LegalSection(
            "信息如何使用",
            "· 生成与批改：你的输入内容与录音会通过加密连接发送到我们的服务器，由 AI 模型生成听力材料、练习卡、批改与评分结果。\n" +
                "· 同步：登录后，工作区、学习记录、计划与分类会与你的账号绑定，在服务器保存，便于换设备后继续学习。\n" +
                "· 提醒：在你允许通知后，用于生成完成提醒与学习计划提醒。\n" +
                "我们不会把你的信息用于广告，也不会出售或提供给无关第三方。"
        ),
        LegalSection(
            "存储与删除",
            "练习记录、生词、错题与设置保存在你的设备本地；登录后学习数据同时保存在服务器。你可以在 App 内删除工作区、记录或分类，对应的服务器数据会随之删除；退出登录会清除本机的登录令牌。"
        ),
        LegalSection(
            "权限说明",
            "· 麦克风：仅在你使用口语 / 跟读练习时录音，不在后台采集。\n" +
                "· 通知：生成完成与学习计划提醒；可随时在系统设置中关闭。\n" +
                "· 精确闹钟与开机自启：保证学习计划提醒准时触达。\n" +
                "· 前台服务：生成任务进行中退到后台时，保障任务继续完成（期间显示进度通知）。\n" +
                "· 忽略电池优化（可选）：进一步降低后台生成被系统中断的概率。\n" +
                "以上权限均可拒绝，仅影响对应功能。"
        ),
        LegalSection(
            "安全",
            "与服务器的通信全程使用 HTTPS 加密；登录令牌保存在应用私有存储中。请妥善保管你的账号密码。"
        ),
        LegalSection(
            "未成年人",
            "若你是未成年人，请在监护人指导下使用本应用；我们不会主动向未成年人收集超出功能所需的信息。"
        ),
        LegalSection(
            "政策变更与联系我们",
            "政策如有变更会在 App 内公示，重大变更会显著提示。如对本政策或你的数据有任何疑问，可通过应用发布渠道联系开发者。"
        )
    )
)

private val termsDoc = LegalDoc(
    title = "用户协议",
    updatedAt = "更新日期：2026 年 7 月 2 日",
    sections = listOf(
        LegalSection(
            "协议的接受",
            "本协议是你与 ListenE 开发者之间就使用本应用达成的约定。注册、登录或使用本应用即表示你已阅读并同意本协议与《隐私政策》；如不同意，请停止使用。"
        ),
        LegalSection(
            "账号",
            "· 注册需提供有效邮箱并设置密码；请妥善保管账号凭据，经你账号进行的操作视为你本人行为。\n" +
                "· 账号仅限本人学习使用，不得出售、出租或转让。\n" +
                "· 若发现账号被盗用或异常，请及时修改密码并联系我们。"
        ),
        LegalSection(
            "服务内容",
            "本应用基于 AI 模型提供听力材料生成、练习卡、批改评分、口语对话与学习管理等服务。服务会持续迭代，功能可能新增、调整或下线，重大调整会在 App 内说明。"
        ),
        LegalSection(
            "用户行为规范",
            "使用本应用时你承诺：\n" +
                "· 不利用本服务生成、存储或传播违反法律法规、侵害他人权益的内容；\n" +
                "· 上传的文字、音频、图片、文件等内容不侵犯他人知识产权、隐私权等合法权利；\n" +
                "· 不对本应用及服务器进行逆向工程、恶意爬取、攻击、刷量或其他干扰正常运行的行为。\n" +
                "违反上述规范的，我们有权暂停或终止向你提供服务。"
        ),
        LegalSection(
            "AI 生成内容声明",
            "本应用的听力材料、练习题、批改与评分等内容由 AI 模型自动生成：\n" +
                "· 生成内容可能存在错误、偏差或不合时宜之处，仅供语言学习参考，不构成任何专业建议；\n" +
                "· 请自行甄别生成内容并对使用后果负责；对重要信息请以权威来源为准。"
        ),
        LegalSection(
            "知识产权",
            "· 本应用的软件、界面设计、标识等归开发者所有。\n" +
                "· 你上传的内容权利仍归你；为向你提供服务（生成、批改、同步等），你授予我们在服务范围内处理这些内容的许可。\n" +
                "· AI 生成结果供你在法律允许范围内用于个人学习。"
        ),
        LegalSection(
            "免责与责任限制",
            "本服务按「现状」提供，我们尽力保障稳定，但不保证服务不中断、无错误。在法律允许的最大范围内，我们不对因使用或无法使用本服务导致的间接损失承担责任。"
        ),
        LegalSection(
            "协议变更与终止",
            "我们可能适时修订本协议，修订后在 App 内公示；你继续使用即视为接受修订。你可随时停止使用并退出登录；如需删除账号数据，可通过发布渠道联系我们处理。"
        ),
        LegalSection(
            "法律适用",
            "本协议适用中华人民共和国法律。因本协议产生的争议，双方应友好协商解决；协商不成的，提交开发者所在地有管辖权的法院处理。"
        )
    )
)

private val licensesDoc = LegalDoc(
    title = "开源许可",
    updatedAt = "本应用使用了以下开源软件与字体，感谢开源社区。",
    sections = listOf(
        LegalSection(
            "字体",
            "· Inter — The Inter Project Authors，SIL Open Font License 1.1\n" +
                "· Sora — Sora Project Authors，SIL Open Font License 1.1"
        ),
        LegalSection(
            "库",
            "· Kotlin 及 kotlinx.serialization — JetBrains，Apache License 2.0\n" +
                "· AndroidX / Jetpack Compose / Material 3 / Material Icons — The Android Open Source Project，Apache License 2.0\n" +
                "· Media3 (ExoPlayer) — Google，Apache License 2.0\n" +
                "· OkHttp — Square, Inc.，Apache License 2.0\n" +
                "· desugar_jdk_libs — 基于 OpenJDK，GPL v2 with Classpath Exception"
        ),
        LegalSection(
            "许可文本",
            "各许可证全文可在对应项目主页查阅：Apache License 2.0（apache.org/licenses/LICENSE-2.0）、SIL OFL 1.1（scripts.sil.org/OFL）。"
        )
    )
)

@Composable
private fun AgentLegalDialog(doc: LegalDoc, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = 26.dp, end = 26.dp, top = 28.dp, bottom = 26.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentIconControl(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "关闭${doc.title}",
                        onClick = onClose,
                        accent = MaterialTheme.colorScheme.onSurface,
                        size = 30.dp,
                        iconSize = 23.dp,
                        bordered = false
                    )
                    AgentSectionTitle(doc.title, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    doc.updatedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    doc.sections.forEachIndexed { index, section ->
                        AgentSurface(modifier = Modifier.agentEnter(delayMillis = 30 * index)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    section.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    section.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
internal fun AgentPrivacyPolicyDialog(onClose: () -> Unit) = AgentLegalDialog(privacyDoc, onClose)

@Composable
internal fun AgentTermsDialog(onClose: () -> Unit) = AgentLegalDialog(termsDoc, onClose)

@Composable
internal fun AgentLicensesDialog(onClose: () -> Unit) = AgentLegalDialog(licensesDoc, onClose)

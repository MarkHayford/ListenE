package com.c0d3c.listene

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// 帮助面板（Help）：侧边栏首页「更多功能 → 使用帮助」打开的抽屉页。
// 复用既有面板模式：返回头 + 滚动列 + AgentSurface 卡片，常见问题可点开展开，保持全局 UI 一致。

private data class HelpFeature(val name: String, val desc: String)

private data class HelpFaq(val question: String, val answer: String)

private val helpFeatures = listOf(
    HelpFeature("工作区", "把相关练习归到一起；标题栏「多选」可批量归类整理"),
    HelpFeature("卡片库 / 文件库", "沉淀生成的练习卡与素材文件，支持多选批量移动分类"),
    HelpFeature("计划表", "安排你的学习计划与节奏"),
    HelpFeature("我的进度", "查看练习数据与学习趋势"),
    HelpFeature("错题本", "自动收集做错的题，针对性复习"),
    HelpFeature("生词本", "收藏生词，随时回来背记")
)

private val helpFaqs = listOf(
    HelpFaq(
        "生成时切到后台或锁屏，会中断吗？",
        "不会。生成一开始就会启动一个前台服务把任务「钉」住，退到后台或锁屏也会继续跑完；完成或失败会发通知提醒（受设置里「生成完成后通知我」开关控制）。回到前台则照常在界面显示结果。"
    ),
    HelpFaq(
        "怎么收到生成完成的通知？",
        "点侧边栏顶部齿轮进入「设置」，打开「生成完成后通知我」（默认已开）。若系统通知权限未授权，页面会提示并给出「去开启」按钮，一键跳到系统通知设置。"
    ),
    HelpFaq(
        "如何整理、归类工作区和卡片？",
        "在工作区页 / 卡片库 / 文件库点标题栏的「多选」，勾选多项后用底部的「移动到分类」一次性归类；分类可以在弹窗里当场新建并移入。"
    ),
    HelpFaq(
        "我的数据存在哪里？",
        "练习记录、生词、错题等默认保存在本机；登录账号后，你的工作区与学习记录会与账号同步，换设备也能接着用。"
    ),
    HelpFaq(
        "生成的内容可靠吗？",
        "听力材料、练习题与批改点评均由 AI 模型自动生成，可能存在错误或偏差，仅供语言学习参考。发现明显问题可以重新生成，重要信息请以权威来源为准。"
    )
)

@Composable
internal fun AgentHelpPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                AgentSectionTitle("使用帮助", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))

            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 0)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "快速上手",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    HelpStep(1, "在首页输入框告诉 AI 你想练什么（话题、难度、题型），或直接把文章 / 文件发给它。")
                    HelpStep(2, "AI 生成听力内容和练习卡，边听边做题，即时判分。")
                    HelpStep(3, "做过的内容自动进入卡片库 / 文件库，可随时回看与复习。")
                }
            }

            Spacer(Modifier.height(14.dp))

            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 55)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "功能一览",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    helpFeatures.forEach { feature ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                feature.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                feature.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            AgentSectionTitle("常见问题")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                helpFaqs.forEachIndexed { index, faq ->
                    HelpFaqCard(faq, modifier = Modifier.agentEnter(delayMillis = 40 * index))
                }
            }

            Spacer(Modifier.height(14.dp))
            AgentSurface(modifier = Modifier.agentEnter(delayMillis = 40 * helpFaqs.size)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "关于 ListenE",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "AI 听力练习 · 版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HelpStep(index: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$index.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HelpFaqCard(faq: HelpFaq, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(faq.question) { mutableStateOf(false) }
    AgentSurface(modifier = modifier, onClick = { expanded = !expanded }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    faq.question,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    faq.answer,
                    modifier = Modifier.padding(end = 34.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

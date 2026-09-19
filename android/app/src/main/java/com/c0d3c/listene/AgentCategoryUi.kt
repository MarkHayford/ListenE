package com.c0d3c.listene

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

// 相册式分类的可复用 UI：顶部 chips 筛选条、「移动到分类」选择弹窗、分类管理弹窗。三个列表共用。

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentCategoryBar(
    categories: List<LibraryCategoryStore.Category>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    organizing: Boolean = false,
    onAiOrganize: (() -> Unit)? = null,
    onDropItem: ((String?, String) -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AgentSegmentPill(text = "全部", selected = selectedId == LibraryCategoryStore.ALL, onClick = { onSelect(LibraryCategoryStore.ALL) })
        if (onDropItem != null) {
            DroppableCategoryPill(text = "未分类", selected = selectedId == LibraryCategoryStore.UNCATEGORIZED, onClick = { onSelect(LibraryCategoryStore.UNCATEGORIZED) }, onDropItemId = { id -> onDropItem(null, id) })
        } else {
            AgentSegmentPill(text = "未分类", selected = selectedId == LibraryCategoryStore.UNCATEGORIZED, onClick = { onSelect(LibraryCategoryStore.UNCATEGORIZED) })
        }
        categories.forEach { c ->
            if (onDropItem != null) {
                DroppableCategoryPill(text = c.name, selected = selectedId == c.id, onClick = { onSelect(c.id) }, onDropItemId = { id -> onDropItem(c.id, id) })
            } else {
                AgentSegmentPill(text = c.name, selected = selectedId == c.id, onClick = { onSelect(c.id) })
            }
        }
        if (onAiOrganize != null) {
            AgentSegmentPill(text = if (organizing) "✨ 整理中…" else "✨ AI 整理", selected = false, onClick = { if (!organizing) onAiOrganize() })
        }
        AgentSegmentPill(text = "＋ 管理分类", selected = false, onClick = onManage)
    }
        if (categories.isEmpty() && (onDropItem != null || onAiOrganize != null)) {
            Text(
                "长按项目可拖到分类，或点「✨ AI 整理」自动归类",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// AI 一键整理的预览：展示建议分类与各自项目数，确认后套用。
@Composable
internal fun AgentOrganizePreviewDialog(
    proposal: OrganizeProposal,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val counts = remember(proposal) { proposal.assignments.values.groupingBy { it }.eachCount() }
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.AutoAwesome,
                title = "AI 整理建议",
                subtitle = "确认后会创建这些分类并把项目归类"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                proposal.categories.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text("${counts[name] ?: 0} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "共整理 ${proposal.assignments.size} 个项目 → ${proposal.categories.size} 个分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AgentCardDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentTextAction(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f), height = 44.dp)
                    AgentTextAction(text = "套用", onClick = onConfirm, primary = true, modifier = Modifier.weight(1f), height = 44.dp, icon = Icons.Outlined.AutoAwesome)
                }
            }
        }
    }
}

// 为单个项目选择分类（或未分类），也可当场新建分类并移入。
@Composable
internal fun AgentCategoryPickerDialog(
    categories: List<LibraryCategoryStore.Category>,
    currentId: String?,
    onPick: (String?) -> Unit,
    onCreateAndPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.Folder,
                title = "移动到分类",
                subtitle = "选一个分类，或新建一个"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTextAction(
                    text = if (currentId == null) "未分类 ✓" else "未分类",
                    onClick = { onPick(null); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    height = 42.dp
                )
                categories.forEach { c ->
                    AgentTextAction(
                        text = if (c.id == currentId) "${c.name} ✓" else c.name,
                        onClick = { onPick(c.id); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                        height = 42.dp,
                        primary = c.id == currentId
                    )
                }
                AgentCardDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentRoleplayInputField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "新建分类名…",
                        modifier = Modifier.weight(1f)
                    )
                    AgentTextAction(
                        text = "新建并移入",
                        onClick = {
                            val n = newName.trim()
                            if (n.isNotBlank()) { onCreateAndPick(n); onDismiss() }
                        },
                        primary = true,
                        enabled = newName.isNotBlank(),
                        icon = Icons.Outlined.CreateNewFolder,
                        height = 42.dp
                    )
                }
            }
        }
    }
}

// 分类管理：新建 / 改名(行内) / 删除。
@Composable
internal fun AgentManageCategoriesDialog(
    categories: List<LibraryCategoryStore.Category>,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Outlined.Folder,
                title = "管理分类",
                subtitle = "新建、改名或删除分类（删除后其中项目回到「未分类」）"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentRoleplayInputField(
                            value = c.name,
                            onValueChange = { onRename(c.id, it) },
                            placeholder = "分类名",
                            modifier = Modifier.weight(1f)
                        )
                        AgentIconControl(
                            icon = Icons.Default.Delete,
                            contentDescription = "删除分类",
                            onClick = { onDelete(c.id) },
                            accent = AgentPracticeWrong,
                            size = 40.dp,
                            iconSize = 20.dp
                        )
                    }
                }
                if (categories.isEmpty()) {
                    Text("还没有分类，在下面新建一个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AgentCardDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentRoleplayInputField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "新建分类名…",
                        modifier = Modifier.weight(1f)
                    )
                    AgentTextAction(
                        text = "新建",
                        onClick = {
                            val n = newName.trim()
                            if (n.isNotBlank()) { onCreate(n); newName = "" }
                        },
                        primary = true,
                        enabled = newName.isNotBlank(),
                        icon = Icons.Outlined.CreateNewFolder,
                        height = 42.dp
                    )
                }
                AgentTextAction(text = "完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), height = 42.dp)
            }
        }
    }
}

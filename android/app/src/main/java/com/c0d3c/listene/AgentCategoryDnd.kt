package com.c0d3c.listene

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

// P2 相册式拖拽归类：把项目行长按拖到分类 chip 上即完成归类。
const val CATEGORY_DND_MIME = "application/x-listene-item-id"

// 行作为拖拽源：Compose 自带长按拖拽检测，这里只需返回要携带的数据(项目 id)。
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.categoryDragSource(itemId: String): Modifier = this.dragAndDropSource(
    drawDragDecoration = { drawRoundRect(color = Color.Black.copy(alpha = 0.10f)) }
) { _ ->
    DragAndDropTransferData(
        ClipData(
            ClipDescription("listene-item", arrayOf(CATEGORY_DND_MIME)),
            ClipData.Item(itemId)
        )
    )
}

// chip 作为拖放目标：悬停高亮，落下时把项目归入该分类。
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DroppableCategoryPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDropItemId: (String) -> Unit
) {
    var hovered by remember { mutableStateOf(false) }
    val target = remember(onDropItemId) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovered = false
                val clip = event.toAndroidDragEvent().clipData ?: return false
                val id = if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                if (id.isNullOrBlank()) return false
                onDropItemId(id)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) { hovered = true }
            override fun onExited(event: DragAndDropEvent) { hovered = false }
            override fun onEnded(event: DragAndDropEvent) { hovered = false }
        }
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .then(
                if (hovered) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
                else Modifier
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> event.mimeTypes().contains(CATEGORY_DND_MIME) },
                target = target
            )
    ) {
        AgentSegmentPill(text = text, selected = selected, onClick = onClick)
    }
}

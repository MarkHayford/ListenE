package com.c0d3c.listene

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class AppNoticeKind {
    Info,
    Success,
    Error
}

data class AppNotice(
    val message: String,
    val kind: AppNoticeKind = AppNoticeKind.Info,
    val durationMs: Long = 2600L,
    val id: Long = System.nanoTime()
)

object AppNoticeBus {
    private val _notices = MutableSharedFlow<AppNotice>(
        extraBufferCapacity = 16
    )
    val notices = _notices.asSharedFlow()

    fun show(message: String, kind: AppNoticeKind = AppNoticeKind.Info, durationMs: Long = 2600L) {
        val clean = message.trim()
        if (clean.isBlank()) return
        _notices.tryEmit(AppNotice(clean, kind, durationMs))
    }

    fun success(message: String) = show(message, AppNoticeKind.Success)
    fun error(message: String) = show(message, AppNoticeKind.Error, durationMs = 3400L)
}

@Composable
fun AppNoticeHost(modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<AppNotice?>(null) }

    LaunchedEffect(Unit) {
        AppNoticeBus.notices.collect { notice ->
            current = notice
        }
    }

    current?.let { notice ->
        LaunchedEffect(notice.id) {
            delay(notice.durationMs)
            if (current?.id == notice.id) current = null
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn(tween(140, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(190, easing = FastOutSlowInEasing)) { it },
            exit = fadeOut(tween(120, easing = FastOutSlowInEasing)) +
                slideOutVertically(tween(150, easing = FastOutSlowInEasing)) { it / 2 },
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 104.dp)
        ) {
            current?.let { notice ->
                AppNoticeCard(notice)
            }
        }
    }
}

@Composable
private fun AppNoticeCard(notice: AppNotice) {
    val (accent, icon) = noticeVisuals(notice.kind)
    Surface(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 7.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f))
                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.18f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                notice.message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun noticeVisuals(kind: AppNoticeKind): Pair<Color, ImageVector> =
    when (kind) {
        AppNoticeKind.Success -> Color(0xFF15803D) to Icons.Default.CheckCircle
        AppNoticeKind.Error -> MaterialTheme.colorScheme.error to Icons.Default.Error
        AppNoticeKind.Info -> MaterialTheme.colorScheme.primary to Icons.Default.Info
    }

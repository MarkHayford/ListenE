package com.c0d3c.listene

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 文件预览（File Preview）：操作栏 + 各类型预览(文本/图片/音频/视频/网页/外部) + 加载态。从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentFilePreviewActions(
    onOpenExternal: () -> Unit,
    onDownload: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    val actions = mapOf(
        "打开" to (Icons.Default.Visibility to onOpenExternal),
        "下载" to (Icons.Default.CloudDownload to onDownload),
        "导出" to (Icons.Default.AttachFile to onExport),
        "分享" to (Icons.Default.Share to onShare)
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        agentFilePreviewActionLayoutLabels().forEach { rowLabels ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowLabels.forEach { label ->
                    val (icon, onClick) = actions.getValue(label)
                    AgentTextAction(
                        text = label,
                        onClick = onClick,
                        icon = icon,
                        height = 38.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

internal fun agentFilePreviewActionLayoutLabels(): List<List<String>> =
    listOf(
        listOf("打开", "下载"),
        listOf("导出", "分享")
    )

@Composable
internal fun AgentFilePreviewContent(
    asset: UserLibraryFileAsset,
    previewMode: UserLibraryFilePreviewMode,
    onOpenExternal: () -> Unit
) {
    AgentSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                agentFilePreviewModeLabel(previewMode),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            when (previewMode) {
                UserLibraryFilePreviewMode.Text -> AgentTextFilePreview(asset)
                UserLibraryFilePreviewMode.Image -> AgentImageFilePreview(asset)
                UserLibraryFilePreviewMode.Audio -> AgentAudioFilePreview(asset)
                UserLibraryFilePreviewMode.Video -> AgentVideoFilePreview(asset, onOpenExternal)
                UserLibraryFilePreviewMode.Web -> AgentWebFilePreview(asset, onOpenExternal)
                UserLibraryFilePreviewMode.External -> AgentExternalFilePreview(asset, onOpenExternal)
            }
        }
    }
}

@Composable
private fun AgentTextFilePreview(asset: UserLibraryFileAsset) {
    var text by remember(asset.localPath) { mutableStateOf<String?>(null) }
    var error by remember(asset.localPath) { mutableStateOf<String?>(null) }
    var loading by remember(asset.localPath) { mutableStateOf(true) }
    LaunchedEffect(asset.localPath) {
        loading = true
        error = null
        text = null
        runCatching {
            withContext(Dispatchers.IO) {
                val file = File(asset.localPath)
                if (asset.localPath.isBlank() || !file.exists()) throw IllegalStateException("文件不存在")
                readAgentPreviewText(file)
            }
        }.onSuccess {
            text = it
        }.onFailure {
            error = it.message ?: "无法预览文本"
        }
        loading = false
    }
    when {
        loading -> AgentFilePreviewLoading("正在读取文本")
        error != null -> AgentCardInlineNotice(error.orEmpty())
        text.isNullOrBlank() -> AgentCardInlineNotice("文本为空")
        else -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 380.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)), RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun AgentImageFilePreview(asset: UserLibraryFileAsset) {
    var image by remember(asset.localPath) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(asset.localPath) { mutableStateOf<String?>(null) }
    var loading by remember(asset.localPath) { mutableStateOf(true) }
    LaunchedEffect(asset.localPath) {
        loading = true
        error = null
        image = null
        runCatching {
            withContext(Dispatchers.IO) {
                val file = File(asset.localPath)
                if (asset.localPath.isBlank() || !file.exists()) throw IllegalStateException("图片不存在")
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    ?: throw IllegalStateException("图片无法解码")
            }
        }.onSuccess {
            image = it
        }.onFailure {
            error = it.message ?: "无法预览图片"
        }
        loading = false
    }
    when {
        loading -> AgentFilePreviewLoading("正在读取图片")
        error != null -> AgentCardInlineNotice(error.orEmpty())
        image != null -> Image(
            bitmap = image!!,
            contentDescription = asset.name.ifBlank { "文件图片预览" },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)), RoundedCornerShape(8.dp))
                .padding(6.dp)
        )
    }
}

@Composable
private fun AgentAudioFilePreview(asset: UserLibraryFileAsset) {
    val source = agentFilePreviewSource(asset)
    if (source.isBlank()) {
        AgentCardInlineNotice("音频暂不可用")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentAudioPlayer(source)
        Text(
            if (asset.localPath.isNotBlank()) "本地音频" else "在线音频",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun AgentVideoFilePreview(asset: UserLibraryFileAsset, onOpenExternal: () -> Unit) {
    val source = agentFilePreviewSource(asset)
    if (source.isBlank()) {
        AgentExternalFilePreview(asset, onOpenExternal)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            factory = { context ->
                VideoView(context).apply {
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnClickListener {
                        if (isPlaying) pause() else start()
                    }
                }
            },
            update = { videoView ->
                val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else Uri.parse(source)
                videoView.setVideoURI(uri)
                videoView.seekTo(1)
            }
        )
        Text(
            "轻触视频区域播放或暂停",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun AgentWebFilePreview(asset: UserLibraryFileAsset, onOpenExternal: () -> Unit) {
    val url = agentFileWebPreviewUrl(asset)
    if (url.isBlank()) {
        AgentExternalFilePreview(asset, onOpenExternal)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)), RoundedCornerShape(8.dp)),
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = true
                    loadUrl(url)
                }
            },
            update = { webView ->
                if (webView.url != url) webView.loadUrl(url)
            }
        )
        Text(
            if (asset.localPath.isNotBlank() && url.startsWith("file:")) "本地网页预览" else "在线预览",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun AgentExternalFilePreview(asset: UserLibraryFileAsset, onOpenExternal: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentCardInlineNotice("当前格式需要使用系统应用查看。")
        AgentTextAction(
            text = "打开文件",
            onClick = onOpenExternal,
            primary = true,
            icon = Icons.Default.Visibility,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            asset.mimeType.ifBlank { "未知格式" },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AgentFilePreviewLoading(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

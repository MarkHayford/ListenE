package com.c0d3c.listene

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.text
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// 文件/附件/导出 IO（File IO）：回复模式/录音 wav/附件构建/输出文件(zip/docx/题库)生成/本地打开/分享/导出/预览源与元信息/下载等纯逻辑。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

private fun updateAgentReplyModeFromMessage(message: String): String? {
    val text = message.lowercase()
    val wantsVoice = listOf(
        "一直语音回复",
        "用语音回复",
        "语音回复",
        "以后都语音",
        "speak back",
        "voice reply",
        "audio reply"
    ).any { text.contains(it) }
    val wantsText = listOf(
        "一直文字回复",
        "用文字回复",
        "文字回复",
        "文本回复",
        "以后都文字",
        "text reply"
    ).any { text.contains(it) }
    return when {
        wantsVoice && !wantsText -> "voice"
        wantsText && !wantsVoice -> "text"
        else -> null
    }
}

private const val AGENT_MAX_ATTACHMENT_BYTES = 18L * 1024L * 1024L
private const val AGENT_MAX_OUTPUT_FILE_CHARS = 120_000
private const val AGENT_FILE_PREVIEW_MAX_CHARS = 120_000

internal fun readAgentAttachmentFromFile(file: File, mimeType: String): AgentInputAttachment {
    val bytes = FileInputStream(file).use { it.readBytes() }
    return buildAgentInputAttachment(file.name.ifBlank { "voice.m4a" }, mimeType.lowercase(), bytes)
        .copy(localPath = file.absolutePath)
}

@SuppressLint("MissingPermission")
internal suspend fun recordAgentWavFile(file: File) {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
    if (minBuffer <= 0) throw IllegalStateException("录音设备不可用")
    val bufferSize = minBuffer.coerceAtLeast(sampleRate)
    val buffer = ByteArray(bufferSize)
    val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        channelConfig,
        encoding,
        bufferSize
    )
    if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
        audioRecord.release()
        throw IllegalStateException("录音初始化失败")
    }
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { output ->
        output.write(ByteArray(44))
        audioRecord.startRecording()
        try {
            while (currentCoroutineContext().isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) output.write(buffer, 0, read)
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }
    writeAgentWavHeader(file, sampleRate, channels = 1, bitsPerSample = 16)
}

private fun writeAgentWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
    val dataSize = (file.length() - 44L).coerceAtLeast(0L)
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    RandomAccessFile(file, "rw").use { wav ->
        wav.seek(0)
        wav.writeBytes("RIFF")
        wav.writeIntLE((36L + dataSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        wav.writeBytes("WAVE")
        wav.writeBytes("fmt ")
        wav.writeIntLE(16)
        wav.writeShortLE(1)
        wav.writeShortLE(channels)
        wav.writeIntLE(sampleRate)
        wav.writeIntLE(byteRate)
        wav.writeShortLE(blockAlign)
        wav.writeShortLE(bitsPerSample)
        wav.writeBytes("data")
        wav.writeIntLE(dataSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}

private fun RandomAccessFile.writeIntLE(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun RandomAccessFile.writeShortLE(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}

internal fun buildAgentInputAttachment(name: String, mimeType: String, bytes: ByteArray): AgentInputAttachment {
    if (bytes.isEmpty()) throw IllegalArgumentException("文件为空")
    if (bytes.size > AGENT_MAX_ATTACHMENT_BYTES) {
        throw IllegalArgumentException("文件不能超过 ${formatAgentFileSize(AGENT_MAX_ATTACHMENT_BYTES)}")
    }
    ensureAgentSupportedAttachment(mimeType)
    return AgentInputAttachment(
        name = name,
        mimeType = mimeType,
        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        sizeBytes = bytes.size.toLong()
    )
}

internal fun writeAgentOutputFiles(ctx: Context, specs: List<AgentOutputFileSpec>): List<AgentInputAttachment> {
    val outputDir = File(ctx.filesDir, "agent_outputs").apply { mkdirs() }
    return specs.take(3).map { spec ->
        val output = agentOutputFileBytes(spec)
        val file = uniqueAgentOutputFile(outputDir, output.name)
        file.writeBytes(output.bytes)
        AgentInputAttachment(
            name = file.name,
            mimeType = output.mimeType,
            sizeBytes = file.length(),
            localPath = file.absolutePath,
            generated = true
        )
    }
}

internal suspend fun writeListeningZipAttachment(ctx: Context, record: HistoryRecord): AgentInputAttachment =
    withContext(Dispatchers.IO) {
        val audioEntry = resolveListeningAudioZipEntry(record.content.audioUrl)
            ?: error("当前题目没有可用录音")
        val outputDir = File(ctx.filesDir, "agent_outputs").apply { mkdirs() }
        val file = uniqueAgentOutputFile(outputDir, agentListeningExportZipName(record))
        file.writeBytes(buildListeningZipBytes(record, audioEntry))
        AgentInputAttachment(
            name = file.name,
            mimeType = "application/zip",
            sizeBytes = file.length(),
            localPath = file.absolutePath,
            generated = true,
            sourceRecordId = record.id
        )
    }

internal suspend fun writeListeningDocxAttachment(ctx: Context, record: HistoryRecord): AgentInputAttachment =
    withContext(Dispatchers.IO) {
        val outputDir = File(ctx.filesDir, "agent_outputs").apply { mkdirs() }
        val file = uniqueAgentOutputFile(outputDir, agentListeningExportDocxName(record))
        file.writeBytes(DocxIo.writeToBytes(buildListeningQuestionDocxParagraphs(record)))
        AgentInputAttachment(
            name = file.name,
            mimeType = AGENT_DOCX_MIME_TYPE,
            sizeBytes = file.length(),
            localPath = file.absolutePath,
            generated = true,
            sourceRecordId = record.id
        )
    }

// 微元卡题库导出（cardSpec 退役后的聊天练习卡形态）：与旧卡版同一输出目录/命名/文档结构。
internal suspend fun writeMicroCardQuestionBankAttachment(ctx: Context, card: MicroCard): AgentInputAttachment =
    withContext(Dispatchers.IO) {
        if (!microCardCanExportQuestionBank(card)) error("当前卡片没有可导出的题目")
        val outputDir = File(ctx.filesDir, "agent_outputs").apply { mkdirs() }
        val file = uniqueAgentOutputFile(outputDir, microCardQuestionBankExportName(card))
        file.writeBytes(DocxIo.writeToBytes(buildMicroCardQuestionBankDocxParagraphs(card)))
        AgentInputAttachment(
            name = file.name,
            mimeType = AGENT_DOCX_MIME_TYPE,
            sizeBytes = file.length(),
            localPath = file.absolutePath,
            generated = true
        )
    }

internal data class AgentOutputFileBytes(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
)

internal fun agentOutputFileBytes(spec: AgentOutputFileSpec): AgentOutputFileBytes {
    val safeName = sanitizeAgentFileName(spec.name.ifBlank { "agent-output" })
    val format = normalizeAgentOutputFormat(spec.format, spec.mimeType, safeName)
    val fileName = ensureAgentFileExtension(safeName, format)
    val mimeType = normalizeAgentOutputMimeType(spec.mimeType, format)
    val content = spec.content.take(AGENT_MAX_OUTPUT_FILE_CHARS)
    val bytes = when (format) {
        "docx" -> DocxIo.writeToBytes(agentOutputDocxParagraphs(content, fileName))
        "zip" -> agentOutputZipBytes(content)
        else -> content.toByteArray(Charsets.UTF_8)
    }
    return AgentOutputFileBytes(
        name = fileName,
        mimeType = mimeType,
        bytes = bytes
    )
}

private fun agentOutputZipBytes(content: String): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zip ->
        zip.putNextEntry(ZipEntry("content.txt"))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return bos.toByteArray()
}

private fun agentOutputDocxParagraphs(content: String, fileName: String): List<String> {
    val lines = cleanAgentOutputDocxMarkdownLines(content.lines())
    val derivedTitle = agentOutputTitleFromFileName(fileName)
    if (derivedTitle.isBlank() || lines.isEmpty()) return lines
    val firstIndex = lines.indexOfFirst { it.isNotBlank() }
    if (firstIndex < 0) return listOf(derivedTitle)
    val first = lines[firstIndex].trim()
    if (!agentOutputDocxTitleIsGeneric(first)) return lines
    return lines.toMutableList().also { it[firstIndex] = derivedTitle }
}

private fun cleanAgentOutputDocxMarkdownLines(lines: List<String>): List<String> {
    val cleaned = lines.flatMap { rawLine ->
        val line = rawLine.trim()
        when {
            line.isBlank() -> listOf("")
            Regex("^[-*_]{3,}$").matches(line) -> emptyList()
            Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$").matches(line) -> emptyList()
            line.contains("|") && line.trim().startsWith("|") && line.trim().endsWith("|") ->
                cleanAgentOutputDocxMarkdownTableLine(line)?.let(::listOf) ?: emptyList()
            else -> listOf(cleanAgentOutputDocxMarkdownInline(line))
        }
    }
    return cleaned.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
}

private fun cleanAgentOutputDocxMarkdownTableLine(line: String): String? {
    val cells = line.trim().trim('|')
        .split('|')
        .map { cleanAgentOutputDocxMarkdownInline(it.trim()) }
        .filter { it.isNotBlank() }
    if (cells.isEmpty()) return null
    if (cells.all { Regex(":?-{3,}:?").matches(it) }) return null
    return cells.joinToString(" - ")
}

private fun cleanAgentOutputDocxMarkdownInline(value: String): String =
    value
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")
        .replace(Regex("(?<!_)_([^_\\n]+)_(?!_)"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .trim()

private fun agentOutputDocxTitleIsGeneric(value: String): Boolean {
    val normalized = value.lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), " ").trim()
    return normalized in setOf(
        "question set",
        "questions",
        "quiz",
        "reading questions",
        "listening questions",
        "exercise",
        "practice"
    )
}

private fun agentOutputTitleFromFileName(fileName: String): String {
    val withoutExt = fileName.substringBeforeLast('.', fileName)
    return withoutExt
        .replace(Regex("[_-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            if (word.all { ch -> ch.isUpperCase() || ch.isDigit() } && word.length <= 4) {
                word
            } else {
                word.lowercase(Locale.ROOT).replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) }
            }
        }
        .take(120)
}

internal fun openAgentLocalAttachment(ctx: Context, attachment: AgentInputAttachment) {
    val file = File(attachment.localPath)
    if (!file.exists()) {
        AppNoticeBus.error("文件不存在")
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mimeType.ifBlank { inferAgentMimeType(file.name) })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        ctx.startActivity(Intent.createChooser(intent, "打开文件"))
        AppNoticeBus.success("文件打开成功")
    }.onFailure { error ->
        if (error is ActivityNotFoundException) {
            AppNoticeBus.error("没有可打开这个文件的应用")
        } else {
            AppNoticeBus.error(error.message ?: "打开文件失败")
        }
    }
}

internal fun AgentInputAttachment.toUserLibraryFileAsset(): UserLibraryFileAsset =
    UserLibraryFileAsset(
        name = name.ifBlank { File(localPath).name.ifBlank { "Agent 文件" } },
        mimeType = mimeType.ifBlank { inferAgentMimeType(name.ifBlank { localPath }) },
        sizeBytes = sizeBytes,
        localPath = localPath,
        downloadUrl = "",
        generated = generated
    )

internal fun agentGeneratedAttachmentLibraryData(attachment: AgentInputAttachment): JsonObject =
    buildJsonObject {
        if (attachment.sourceKind.isNotBlank()) put("sourceKind", attachment.sourceKind)
        if (attachment.sourceCardId.isNotBlank()) put("sourceCardId", attachment.sourceCardId)
        if (attachment.sourceCardTitle.isNotBlank()) put("sourceCardTitle", attachment.sourceCardTitle)
        if (attachment.sourceMessageId > 0L) put("sourceMessageId", attachment.sourceMessageId)
    }

internal fun exportAgentGeneratedAttachmentIfRequested(ctx: Context, attachment: AgentInputAttachment) {
    if (!attachment.copyToDownloads) return
    val asset = attachment.toUserLibraryFileAsset()
    val file = File(asset.localPath)
    if (asset.localPath.isBlank() || !file.exists()) return
    copyAgentFileToDownloads(ctx, file, asset)
}

internal fun agentAttachmentPreviewMode(asset: UserLibraryFileAsset): UserLibraryFilePreviewMode {
    val extension = asset.name.substringAfterLast('.', "").lowercase()
    val mime = asset.mimeType.lowercase()
    return when {
        mime.startsWith("audio/") -> UserLibraryFilePreviewMode.Audio
        mime.startsWith("video/") -> UserLibraryFilePreviewMode.Video
        mime.startsWith("image/") -> UserLibraryFilePreviewMode.Image
        mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            extension in setOf("txt", "md", "markdown", "json", "csv", "xml", "log", "srt", "vtt", "docx") ->
            UserLibraryFilePreviewMode.Text
        else -> UserLibraryFilePreviewMode.External
    }
}

internal fun exportAgentAttachmentFile(ctx: Context, attachment: AgentInputAttachment) {
    val asset = attachment.toUserLibraryFileAsset()
    val file = File(asset.localPath)
    if (asset.localPath.isBlank() || !file.exists()) {
        AppNoticeBus.error("文件不存在，无法导出")
        return
    }
    runCatching {
        copyAgentFileToDownloads(ctx, file, asset)
    }.onSuccess {
        AppNoticeBus.success(agentExportSuccessNotice(asset.name.ifBlank { file.name }))
    }.onFailure { error ->
        AppNoticeBus.error(error.message ?: "导出失败")
    }
}

internal fun shareAgentAttachmentFile(ctx: Context, attachment: AgentInputAttachment) {
    val asset = attachment.toUserLibraryFileAsset()
    val file = File(asset.localPath)
    if (asset.localPath.isBlank() || !file.exists()) {
        AppNoticeBus.error("没有可分享的文件")
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(asset.mimeType.ifBlank { inferAgentMimeType(file.name) })
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        ctx.startActivity(Intent.createChooser(intent, "分享文件"))
        AppNoticeBus.success("文件分享成功")
    }.onFailure { error ->
        if (error is ActivityNotFoundException) {
            AppNoticeBus.error("没有可分享文件的应用")
        } else {
            AppNoticeBus.error(error.message ?: "分享失败")
        }
    }
}

internal fun openUserLibraryFile(ctx: Context, item: UserLibraryItem) {
    val asset = userLibraryFileAsset(item)
    val attachment = asset.toAttachment()
    when {
        attachment != null -> openAgentLocalAttachment(ctx, attachment)
        asset.downloadUrl.isNotBlank() -> openAgentExternalUrl(ctx, asset.downloadUrl, "文件链接打开失败")
        else -> AppNoticeBus.error("文件暂时不可打开")
    }
}

internal fun agentFilePreviewSource(asset: UserLibraryFileAsset): String =
    asset.localPath
        .takeIf { it.isNotBlank() && File(it).exists() }
        ?: asset.previewUrl.takeIf { it.isNotBlank() }
        ?: asset.downloadUrl

internal fun agentFileWebPreviewUrl(asset: UserLibraryFileAsset): String {
    val localFile = asset.localPath.takeIf { it.isNotBlank() }?.let { File(it) }
    if (localFile != null && localFile.exists()) return Uri.fromFile(localFile).toString()
    return asset.previewUrl.takeIf { it.isNotBlank() } ?: asset.downloadUrl
}

internal fun agentFilePreviewModeLabel(mode: UserLibraryFilePreviewMode): String =
    when (mode) {
        UserLibraryFilePreviewMode.Text -> "文本预览"
        UserLibraryFilePreviewMode.Image -> "图片预览"
        UserLibraryFilePreviewMode.Audio -> "音频预览"
        UserLibraryFilePreviewMode.Video -> "视频预览"
        UserLibraryFilePreviewMode.Web -> "在线预览"
        UserLibraryFilePreviewMode.External -> "系统预览"
    }

internal fun formatAgentFilePreviewMeta(asset: UserLibraryFileAsset, updatedAt: Long): String =
    listOfNotNull(
        asset.mimeType.ifBlank { null },
        asset.sizeBytes.takeIf { it > 0L }?.let { formatAgentFileSize(it) },
        formatHistoryTime(updatedAt).takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { "文件" }

internal fun readAgentPreviewText(file: File): String {
    if (file.extension.equals("docx", ignoreCase = true)) {
        val text = FileInputStream(file).use { DocxIo.readText(it) }
        val truncated = text.length > AGENT_FILE_PREVIEW_MAX_CHARS
        val preview = text.take(AGENT_FILE_PREVIEW_MAX_CHARS)
        return if (truncated) "$preview\n\n... 内容较长，已截断预览" else preview
    }
    val maxBytes = AGENT_FILE_PREVIEW_MAX_CHARS + 1
    val bytes = FileInputStream(file).use { input ->
        val buffer = ByteArray(maxBytes)
        val read = input.read(buffer)
        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }
    val decoded = String(bytes, StandardCharsets.UTF_8)
    val truncated = file.length() > AGENT_FILE_PREVIEW_MAX_CHARS || decoded.length > AGENT_FILE_PREVIEW_MAX_CHARS
    val text = decoded.take(AGENT_FILE_PREVIEW_MAX_CHARS)
    return if (truncated) "$text\n\n... 内容较长，已截断预览" else text
}

internal fun downloadUserLibraryFile(ctx: Context, item: UserLibraryItem) {
    val asset = userLibraryFileAsset(item)
    when {
        asset.localPath.isNotBlank() -> exportUserLibraryFile(ctx, item)
        asset.downloadUrl.isNotBlank() -> {
            if (openAgentExternalUrl(ctx, asset.downloadUrl, "下载链接打开失败")) {
                AppNoticeBus.success(agentRemoteFileDownloadSuccessNotice())
            }
        }
        else -> AppNoticeBus.error("没有可下载的文件")
    }
}

internal fun exportUserLibraryFile(ctx: Context, item: UserLibraryItem) {
    val asset = userLibraryFileAsset(item)
    val file = File(asset.localPath)
    if (asset.localPath.isBlank() || !file.exists()) {
        if (asset.downloadUrl.isNotBlank()) {
            if (openAgentExternalUrl(ctx, asset.downloadUrl, "文件链接打开失败")) {
                AppNoticeBus.success(agentRemoteFileExportSuccessNotice())
            }
        } else {
            AppNoticeBus.error("文件不存在，无法导出")
        }
        return
    }
    runCatching {
        copyAgentFileToDownloads(ctx, file, asset)
    }.onSuccess {
        AppNoticeBus.success(agentExportSuccessNotice(asset.name.ifBlank { file.name }))
    }.onFailure { error ->
        AppNoticeBus.error(error.message ?: "导出失败")
    }
}

internal suspend fun exportUserLibraryCardQuestionBank(ctx: Context, item: UserLibraryItem): String =
    withContext(Dispatchers.IO) {
        val card = userLibraryMicroCard(item)
        if (card == null || !microCardCanExportQuestionBank(card)) {
            error("当前卡片没有可导出的题目")
        }
        val fileName = microCardQuestionBankExportName(card, item.title)
        val outputDir = File(ctx.filesDir, "agent_outputs").apply { mkdirs() }
        val file = uniqueAgentOutputFile(outputDir, fileName)
        file.writeBytes(DocxIo.writeToBytes(buildMicroCardQuestionBankDocxParagraphs(card)))
        val asset = UserLibraryFileAsset(
            name = file.name,
            mimeType = AGENT_DOCX_MIME_TYPE,
            sizeBytes = file.length(),
            localPath = file.absolutePath,
            downloadUrl = "",
            generated = true
        )
        val workspaceId = userLibraryWorkspaceId(item)
        UserLibraryStore.saveGeneratedFile(
            ctx = ctx,
            workspaceId = workspaceId,
            attachment = AgentInputAttachment(
                name = asset.name,
                mimeType = asset.mimeType,
                sizeBytes = asset.sizeBytes,
                localPath = asset.localPath,
                generated = asset.generated
            ),
            extraData = buildJsonObject {
                put("sourceKind", "card_question_bank")
                put("sourceCardId", item.id)
                put("sourceCardTitle", item.title.ifBlank { card.title })
                put("cardTitle", card.title)
                put("workspaceId", workspaceId)
            }
        )
        copyAgentFileToDownloads(ctx, file, asset)
        file.name
    }

internal fun agentExportSuccessNotice(fileName: String): String {
    val cleanName = fileName.trim().ifBlank { "文件" }
    return "已导出到下载目录 ListenE/$cleanName"
}

internal fun agentRemoteFileDownloadSuccessNotice(): String = "下载链接已打开"

internal fun agentRemoteFileExportSuccessNotice(): String = "文件链接已打开"

internal fun shareUserLibraryFile(ctx: Context, item: UserLibraryItem) {
    val asset = userLibraryFileAsset(item)
    val file = File(asset.localPath)
    if (asset.localPath.isNotBlank() && file.exists()) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(asset.mimeType.ifBlank { inferAgentMimeType(file.name) })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            ctx.startActivity(Intent.createChooser(intent, "分享文件"))
            AppNoticeBus.success("文件分享成功")
        }.onFailure { error ->
            if (error is ActivityNotFoundException) {
                AppNoticeBus.error("没有可分享文件的应用")
            } else {
                AppNoticeBus.error(error.message ?: "分享失败")
            }
        }
        return
    }
    if (asset.downloadUrl.isNotBlank()) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, asset.downloadUrl)
        runCatching {
            ctx.startActivity(Intent.createChooser(intent, "分享链接"))
            AppNoticeBus.success("链接分享成功")
        }.onFailure { error ->
            if (error is ActivityNotFoundException) {
                AppNoticeBus.error("没有可分享链接的应用")
            } else {
                AppNoticeBus.error(error.message ?: "分享失败")
            }
        }
    } else {
        AppNoticeBus.error("没有可分享的文件")
    }
}

private fun openAgentExternalUrl(ctx: Context, url: String, failureMessage: String): Boolean {
    val cleanUrl = url.trim()
    if (cleanUrl.isBlank()) {
        AppNoticeBus.error(failureMessage)
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))
    val opened = runCatching { ctx.startActivity(intent) }
    opened.onFailure { error ->
            if (error is ActivityNotFoundException) {
                AppNoticeBus.error("没有可打开链接的应用")
            } else {
                AppNoticeBus.error(error.message ?: failureMessage)
            }
        }
    return opened.isSuccess
}

private fun copyAgentFileToDownloads(ctx: Context, file: File, asset: UserLibraryFileAsset) {
    val resolver = ctx.contentResolver
    val fileName = asset.name.ifBlank { file.name }.replace(Regex("""[\\/:*?"<>|]"""), "_")
    val mimeType = asset.mimeType.ifBlank { inferAgentMimeType(fileName) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ListenE")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("无法创建导出文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            } ?: error("无法写入导出文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ListenE")
        dir.mkdirs()
        val target = uniqueAgentOutputFile(dir, fileName)
        FileInputStream(file).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }
}

private fun sanitizeAgentFileName(name: String): String {
    val clean = name
        .replace(Regex("""[\\/:*?"<>|]+"""), "_")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return clean.ifBlank { "agent-output" }.take(80)
}

private fun normalizeAgentOutputFormat(format: String, mimeType: String, name: String): String {
    val raw = format.lowercase().trim().removePrefix(".")
    if (raw.isNotBlank()) return normalizeAgentOutputTextFormat(raw)
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isNotBlank() && ext != name.lowercase()) return normalizeAgentOutputTextFormat(ext)
    return normalizeAgentOutputTextFormat(when (mimeType.lowercase()) {
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/json" -> "json"
        "text/markdown" -> "md"
        "text/csv" -> "csv"
        "text/html" -> "html"
        "application/zip", "application/x-zip-compressed" -> "zip"
        else -> "txt"
    })
}

private fun normalizeAgentOutputMimeType(mimeType: String, format: String): String {
    val clean = mimeType.lowercase().trim()
    return when (format.lowercase()) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "json" -> "application/json"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "zip" -> "application/zip"
        else -> if (clean.startsWith("text/")) clean else "text/plain"
    }
}

private fun normalizeAgentOutputTextFormat(format: String): String =
    when (format.lowercase().trim()) {
        "doc", "docx", "word" -> "docx"
        "json" -> "json"
        "md", "markdown" -> "md"
        "csv" -> "csv"
        "html", "htm" -> "html"
        "zip" -> "zip"
        "txt", "text" -> "txt"
        else -> "txt"
    }

private fun ensureAgentFileExtension(name: String, format: String): String {
    val ext = when (format.lowercase()) {
        "markdown" -> "md"
        "htm" -> "html"
        else -> format.lowercase().ifBlank { "txt" }
    }
    val currentExt = name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it != name }
    if (currentExt.equals(ext, ignoreCase = true)) return name
    if (currentExt != null && currentExt.lowercase() in setOf("zip", "rar", "7z", "tar", "gz")) {
        return "${name.substringBeforeLast('.')}.$ext"
    }
    return "$name.$ext"
}

private fun uniqueAgentOutputFile(dir: File, fileName: String): File {
    val base = fileName.substringBeforeLast('.', fileName)
    val ext = fileName.substringAfterLast('.', "")
    var candidate = File(dir, fileName)
    var index = 1
    while (candidate.exists()) {
        candidate = File(dir, if (ext.isBlank()) "$base-$index" else "$base-$index.$ext")
        index += 1
    }
    return candidate
}

internal fun ensureAgentSupportedAttachment(mimeType: String) {
    if (
        !mimeType.startsWith("image/") &&
        !mimeType.startsWith("audio/") &&
        !mimeType.startsWith("video/") &&
        mimeType != "application/zip" &&
        !isAgentDocumentMimeType(mimeType)
    ) {
        throw IllegalArgumentException("暂只支持图片、音频、视频和常见文档文件")
    }
}

private fun isAgentDocumentMimeType(mimeType: String): Boolean {
    val clean = mimeType.lowercase()
    return clean.startsWith("text/") ||
        clean == "application/json" ||
        clean == "application/pdf" ||
        clean == "application/msword" ||
        clean == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
        clean == "application/vnd.ms-excel" ||
        clean == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
        clean == "application/vnd.ms-powerpoint" ||
        clean == "application/vnd.openxmlformats-officedocument.presentationml.presentation"
}

internal fun queryAgentDisplayName(ctx: Context, uri: Uri): String {
    return runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else ""
            } else {
                ""
            }
        }.orEmpty()
    }.getOrDefault("").ifBlank {
        uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    }
}

internal fun inferAgentMimeType(name: String): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a", "mp4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "webm" -> "video/webm"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "txt" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

internal fun formatAgentFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 B"
    val kb = sizeBytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}

internal fun AgentInputAttachment.isAgentVoiceMessage(): Boolean {
    val normalizedName = name.lowercase()
    return mimeType.startsWith("audio/") &&
        normalizedName.startsWith("agent_voice_") &&
        normalizedName.endsWith(".wav")
}

internal fun Modifier.agentSoftShadow(
    cornerRadius: Dp,
    alpha: Float,
    blur: Dp,
    spread: Dp
): Modifier = drawBehind {
    val blurPx = blur.toPx()
    val spreadPx = spread.toPx()
    val radiusPx = cornerRadius.toPx() + spreadPx
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
        maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(
            -spreadPx,
            -spreadPx,
            size.width + spreadPx,
            size.height + spreadPx,
            radiusPx,
            radiusPx,
            paint
        )
    }
}

internal fun workspaceHasRecord(workspace: LearningWorkspace?, recordId: String?): Boolean {
    if (workspace == null || recordId.isNullOrBlank()) return false
    return recordId in workspace.linkedRecordIds || workspace.events.any { it.recordId == recordId }
}

internal fun workspaceHistoryRecords(workspace: LearningWorkspace?, history: List<HistoryRecord>): List<HistoryRecord> {
    if (workspace == null) return emptyList()
    return history
        .filter { record -> workspaceHasRecord(workspace, record.id) }
        .sortedByDescending { it.createdAt }
}

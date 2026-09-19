package com.c0d3c.listene

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val LISTENING_FORMAT = "listene_listening"
internal const val LISTENING_PACKAGE_FORMAT = LISTENING_FORMAT
private const val IO_VERSION = 1

fun sanitizeExportName(raw: String): String =
    raw.trim().replace(Regex("""[^\w\u4e00-\u9fa5-]+"""), "_").take(40).ifBlank { "practice" }

// 微元卡题库导出（cardSpec 退役后聊天里的练习卡都是微元卡）：文档结构与旧卡版一致。
internal fun microCardQuestionBankExportName(card: MicroCard, itemTitle: String = ""): String {
    val base = itemTitle.ifBlank { card.title }.ifBlank { "card_question_bank" }
    return "${sanitizeExportName(base)}_question_bank.docx"
}

internal fun buildMicroCardQuestionBankDocxParagraphs(card: MicroCard): List<String> {
    val lines = mutableListOf<String>()
    lines += "ListenE 卡片题库导出"
    lines += "卡片：${card.title.ifBlank { "未命名卡片" }}"
    lines += "导出时间：${formatHistoryTime(System.currentTimeMillis())}"
    val sections = card.nodes.mapNotNull(::microNodeExportSection)
    if (sections.isNotEmpty()) {
        lines += ""
        lines += "【学习材料】"
        sections.forEach { section ->
            lines += section
            lines += ""
        }
    }
    val questions = card.nodes.mapNotNull(::microNodeExportQuestion)
    lines += ""
    lines += "【题目与答案】"
    if (questions.isEmpty()) {
        lines += "当前卡片没有可导出的题目。"
    } else {
        questions.forEachIndexed { index, q ->
            lines += "${index + 1}. ${q.prompt}"
            q.options.forEachIndexed { optIdx, opt -> lines += "${optionLabel(optIdx)} $opt" }
            if (q.answer.isNotBlank()) lines += "正确答案：${q.answer}"
            if (q.explanation.isNotBlank()) lines += "解析：${q.explanation}"
            lines += ""
        }
    }
    return lines.dropLastWhile { it.isBlank() }
}

fun buildListeningDocxParagraphs(record: HistoryRecord): List<String> {
    val c = record.content
    val lines = mutableListOf<String>()
    lines += "ListenE 听力练习导出"
    lines += "场景：${record.scene}"
    lines += "类型：${if (record.contentType == "article") "文章听力" else "对话听力"}"
    lines += "导出时间：${formatHistoryTime(System.currentTimeMillis())}"
    lines += ""
    lines += "【听力文案】"
    lines += c.script
    lines += ""
    lines += "【题目与解析】"
    c.questions.forEachIndexed { index, q ->
        lines += "${index + 1}. ${q.questionText}"
        q.options.forEachIndexed { optIdx, opt ->
            lines += "${optionLabel(optIdx)} $opt"
        }
        lines += "正确答案：${optionLabel(q.correctAnswer)}"
        if (q.explanation.isNotBlank()) lines += "解析：${q.explanation}"
        lines += ""
    }
    record.analysisResult?.let { analysis ->
        lines += "【AI 学习报告】"
        lines += analysis.summary
        if (analysis.weakPoints.isNotEmpty()) {
            lines += "薄弱点："
            analysis.weakPoints.forEach { lines += "· $it" }
        }
        if (analysis.suggestions.isNotEmpty()) {
            lines += "建议："
            analysis.suggestions.forEach { lines += "· $it" }
        }
    }
    return lines
}

fun buildListeningQuestionDocxParagraphs(record: HistoryRecord): List<String> {
    val c = record.content
    val lines = mutableListOf<String>()
    lines += "ListenE 听力题目导出"
    lines += "场景：${record.scene}"
    lines += "导出时间：${formatHistoryTime(System.currentTimeMillis())}"
    lines += ""
    lines += "【题目与答案】"
    c.questions.forEachIndexed { index, q ->
        lines += "${index + 1}. ${q.questionText}"
        q.options.forEachIndexed { optIdx, opt ->
            lines += "${optionLabel(optIdx)} $opt"
        }
        lines += "正确答案：${optionLabel(q.correctAnswer)}"
        if (q.explanation.isNotBlank()) lines += "解析：${q.explanation}"
        lines += ""
    }
    return lines
}

fun buildListeningZipBytes(ctx: Context, record: HistoryRecord): ByteArray {
    val audioEntry = resolveListeningAudioZipEntry(record.content.audioUrl)
    return buildListeningZipBytes(record, audioEntry)
}

fun buildListeningZipBytes(record: HistoryRecord, audioEntry: Pair<String, ByteArray>?): ByteArray {
    val normalizedAudioEntry = audioEntry
        ?.takeIf { (_, bytes) -> bytes.isNotEmpty() }
        ?.let { (name, bytes) -> normalizeAudioZipEntryName(name) to bytes }
        ?: error("听力包导出需要可用音频")
    val manifest = listeningManifestJson(record, normalizedAudioEntry)
    val docx = DocxIo.writeToBytes(buildListeningDocxParagraphs(record))
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zip ->
        putZipBytes(zip, "manifest.json", manifest.toByteArray(Charsets.UTF_8))
        putZipBytes(zip, "content.docx", docx)
        putZipBytes(zip, "transcript.txt", listeningTranscriptText(record).toByteArray(Charsets.UTF_8))
        putZipBytes(zip, "questions.txt", listeningQuestionsText(record).toByteArray(Charsets.UTF_8))
        val (entryName, bytes) = normalizedAudioEntry
        putZipBytes(zip, entryName, bytes)
    }
    return bos.toByteArray()
}

private fun listeningTranscriptText(record: HistoryRecord): String =
    record.content.script.ifBlank { "Transcript unavailable." }

private fun listeningQuestionsText(record: HistoryRecord): String {
    val rows = record.content.questions.mapIndexed { index, q ->
        buildString {
            append("${index + 1}. ${q.questionText}")
            q.options.forEachIndexed { optIdx, opt ->
                append('\n').append("${optionLabel(optIdx)} $opt")
            }
            append('\n').append("正确答案：${optionLabel(q.correctAnswer)}")
            if (q.explanation.isNotBlank()) append('\n').append("解析：${q.explanation}")
        }
    }
    return rows.joinToString("\n\n").ifBlank { "Questions unavailable." }
}

fun importListeningPackage(ctx: Context, bytes: ByteArray): HistoryRecord {
    if (isZipBytes(bytes)) {
        val entries = readZipEntries(bytes)
        val manifestRaw = findZipEntryText(entries, "manifest.json")
            ?: error("无效的听力包：缺少 manifest.json")
        return parseListeningManifest(ctx, manifestRaw, entries)
    }
    val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
    if (text.startsWith("{")) {
        return parseListeningManifest(ctx, text, emptyMap())
    }
    error("无效的听力包：缺少 manifest.json")
}

internal fun parseListeningManifest(
    ctx: Context,
    manifestRaw: String,
    entries: Map<String, ByteArray>
): HistoryRecord {
    val manifest = parseJsonObjectOrNull(manifestRaw) ?: error("无效的听力包：manifest 解析失败")
    require(manifest.str("format") == LISTENING_FORMAT) { "不是 ListenE 听力练习包" }
    val scene = manifest.str("scene").ifBlank { "导入的听力练习" }
    val contentType = manifest.str("contentType", "dialogue")
    val contentJson = manifest.objOrNull("content") ?: error("无效的听力包：缺少 content")
    val content = parseListeningContentFromManifest(contentJson)
    val manifestAudioPath = contentJson.str("audioPath")
        .ifBlank { contentJson.objOrNull("audio")?.str("path").orEmpty() }
    val audioEntry = manifestAudioPath
        .takeIf { it.isNotBlank() }
        ?.let { path ->
            findZipEntry(entries) { name -> name.equals(path, ignoreCase = true) }
                ?.let { normalizeZipEntryName(it.key) to it.value }
        }
        ?: entries.entries.firstOrNull { (name, _) ->
            val normalized = normalizeZipEntryName(name)
            normalized.substringAfterLast('/').startsWith("audio.")
        }?.let { normalizeZipEntryName(it.key) to it.value }
    val localAudio = audioEntry?.let { (entryPath, audio) ->
        val file = File(ctx.filesDir, listeningImportAudioFileName(manifestAudioPath.ifBlank { entryPath }, entries.keys))
        file.writeBytes(audio)
        file.absolutePath
    }
    val finalContent = if (localAudio != null) content.copy(audioUrl = localAudio) else content
    val analysis = manifest.objOrNull("analysisResult")?.let { parseAnalysisFromJson(it) }
    return HistoryRecord(
        id = System.currentTimeMillis().toString(),
        scene = scene,
        createdAt = System.currentTimeMillis(),
        content = finalContent,
        analysisResult = analysis,
        contentType = contentType
    )
}

internal fun listeningManifestJson(
    record: HistoryRecord,
    audioEntry: Pair<String, ByteArray>? = null
): String {
    val content = record.content
    val contentJson = buildJsonObject {
        put("title", content.title)
        put("script", content.script)
        putJsonArray("questions") {
            content.questions.forEach { q ->
                addJsonObject {
                    put("questionText", q.questionText)
                    putJsonArray("options") { q.options.forEach { add(it) } }
                    put("correctAnswer", q.correctAnswer)
                    put("explanation", q.explanation)
                }
            }
        }
        put("ttsPrompt", content.ttsPrompt)
        put("speakers", listeningSpeakersJson(content.speakers))
        put("ttsSegments", listeningTtsSegmentsJson(content.ttsSegments))
        put("audioSegments", listeningAudioSegmentsJson(content.audioSegments))
        audioEntry?.let { (path, bytes) ->
            put("audioPath", path)
            putJsonObject("audio") {
                put("path", path)
                put("mimeType", listeningAudioMimeType(path))
                put("sizeBytes", bytes.size)
            }
        }
    }
    val root = buildJsonObject {
        put("format", LISTENING_FORMAT)
        put("version", IO_VERSION)
        put("contentType", record.contentType)
        put("scene", record.scene)
        put("content", contentJson)
        record.analysisResult?.let { put("analysisResult", analysisToJson(it)) }
    }
    return root.toString()
}

internal fun parseListeningContentFromManifest(json: JsonObject): ListeningContent {
    val questions = buildList {
        val arr = json.arrOrNull("questions") ?: JsonArray(emptyList())
        for (i in 0 until arr.size) {
            val q = arr.objOrNull(i) ?: continue
            val options = jsonArrayToStrings(q.arrOrNull("options"))
            val rawAnswer = when {
                q.containsKey("answer") -> q.str("answer")
                q.containsKey("correctAnswer") -> q.str("correctAnswer")
                q.containsKey("correct_answer") -> q.str("correct_answer")
                q.containsKey("correct") -> q.str("correct")
                else -> ""
            }
            val answerIndex = agentQuestionAnswerIndex(rawAnswer, options) ?: continue
            add(
                Question(
                    questionText = q.str("questionText"),
                    options = options,
                    correctAnswer = answerIndex,
                    explanation = q.str("explanation")
                )
            )
        }
    }
    return ListeningContent(
        title = json.str("title", "Practice"),
        script = json.str("script"),
        questions = questions,
        ttsPrompt = json.str("ttsPrompt"),
        speakers = parseListeningSpeakersFromManifest(json.arrOrNull("speakers")),
        ttsSegments = parseListeningTtsSegmentsFromManifest(json.arrOrNull("ttsSegments")),
        audioSegments = parseListeningAudioSegmentsFromManifest(json.arrOrNull("audioSegments"))
    )
}

private fun findZipEntry(
    entries: Map<String, ByteArray>,
    matcher: (String) -> Boolean
): Map.Entry<String, ByteArray>? =
    entries.entries.firstOrNull { matcher(normalizeZipEntryName(it.key)) }

fun readUriBytes(ctx: Context, uri: Uri): ByteArray =
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("无法读取文件")

// --- ZIP helpers ---

internal fun isZipBytes(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() &&
        bytes[1] == 0x4B.toByte() &&
        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
        (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())

private fun normalizeZipEntryName(name: String): String =
    name.replace('\\', '/').removePrefix("./").trim('/')

internal fun peekListeningManifestRoot(bytes: ByteArray): JsonObject? {
    if (isZipBytes(bytes)) {
        val entries = readZipEntries(bytes)
        val raw = findZipEntryText(entries, "manifest.json") ?: return null
        return parseJsonObjectOrNull(raw)
    }
    val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
    if (!text.startsWith("{")) return null
    return parseJsonObjectOrNull(text)
}

private fun findZipEntryText(entries: Map<String, ByteArray>, targetName: String): String? {
    val wanted = normalizeZipEntryName(targetName)
    entries.entries.firstOrNull { normalizeZipEntryName(it.key).equals(wanted, ignoreCase = true) }
        ?.let { return it.value.toString(Charsets.UTF_8) }
    entries.entries.firstOrNull { normalizeZipEntryName(it.key).endsWith("/$wanted", ignoreCase = true) }
        ?.let { return it.value.toString(Charsets.UTF_8) }
    return null
}

private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val map = linkedMapOf<String, ByteArray>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val name = normalizeZipEntryName(entry.name)
                map[name] = zip.readBytes()
            }
            entry = zip.nextEntry
        }
    }
    return map
}

private fun putZipBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(bytes)
    zip.closeEntry()
}

private fun normalizeAudioZipEntryName(name: String): String {
    val clean = name.substringAfterLast('/').substringAfterLast('\\').trim()
    val extension = clean.substringAfterLast('.', "wav").ifBlank { "wav" }
    return "audio.$extension"
}

internal fun listeningImportAudioFileName(
    manifestAudioPath: String,
    entryNames: Set<String>
): String {
    val extension = listeningAudioExtensionFromPath(manifestAudioPath)
        ?: entryNames.asSequence()
            .map(::normalizeZipEntryName)
            .firstOrNull { it.substringAfterLast('/').startsWith("audio.") }
            ?.let(::listeningAudioExtensionFromPath)
        ?: "wav"
    return "audio_import_${System.currentTimeMillis()}.$extension"
}

private fun listeningAudioExtensionFromPath(path: String): String? {
    val extension = path.substringAfterLast('/').substringAfterLast('\\')
        .substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")
    return extension.takeIf { it in setOf("wav", "mp3", "m4a", "mp4a", "aac", "ogg") }
}

private fun listeningAudioExtensionFromContentType(contentType: String?): String? {
    return when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
        else -> null
    }
}

private fun listeningAudioMimeType(path: String): String =
    when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

internal fun resolveListeningAudioZipEntry(audioUrl: String?): Pair<String, ByteArray>? {
    val clean = audioUrl?.takeIf { it.isNotBlank() } ?: return null
    val localFile = resolveLocalFile(clean)
    if (localFile != null && localFile.exists() && localFile.isFile) {
        val extension = localFile.extension.takeIf { it.isNotBlank() } ?: "wav"
        return "audio.$extension" to localFile.readBytes()
    }
    if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val connection = (URL(clean).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 120_000
        requestMethod = "GET"
        instanceFollowRedirects = true
    }
    try {
        if (connection.responseCode !in 200..299) error("音频下载失败")
        return connection.inputStream.use { input ->
            val extension = listeningAudioExtensionFromPath(clean.substringBefore('?'))
                ?: listeningAudioExtensionFromContentType(connection.contentType)
                ?: "wav"
            "audio.$extension" to input.readBytes()
        }
    } finally {
        connection.disconnect()
    }
}

private fun resolveLocalFile(path: String): File? = when {
    path.startsWith("file://") -> File(path.removePrefix("file://"))
    File(path).isAbsolute -> File(path)
    else -> null
}

private fun jsonArrayToStrings(arr: JsonArray?): List<String> =
    List(arr?.size ?: 0) { arr!!.str(it) }

private fun listeningSpeakersJson(speakers: List<ListeningSpeaker>): JsonArray = buildJsonArray {
    speakers.filter { it.speakerId.isNotBlank() || it.speakerName.isNotBlank() }.forEach { speaker ->
        addJsonObject {
            put("speakerId", speaker.speakerId)
            put("speakerName", speaker.speakerName)
            put("speakerGender", speaker.speakerGender)
        }
    }
}

private fun parseListeningSpeakersFromManifest(arr: JsonArray?): List<ListeningSpeaker> {
    if (arr == null) return emptyList()
    return List(arr.size) { index ->
        val item = arr.objOrNull(index) ?: JsonObject(emptyMap())
        ListeningSpeaker(
            speakerId = item.str("speakerId"),
            speakerName = item.str("speakerName"),
            speakerGender = item.str("speakerGender")
        )
    }.filter { it.speakerId.isNotBlank() || it.speakerName.isNotBlank() }
}

private fun listeningTtsSegmentsJson(segments: List<ListeningTtsSegment>): JsonArray = buildJsonArray {
    segments.filter { it.text.isNotBlank() }.forEach { segment ->
        addJsonObject {
            put("speakerId", segment.speakerId)
            put("speakerName", segment.speakerName)
            put("speakerGender", segment.speakerGender)
            put("voiceProfile", segment.voiceProfile)
            put("text", segment.text)
        }
    }
}

private fun parseListeningTtsSegmentsFromManifest(arr: JsonArray?): List<ListeningTtsSegment> {
    if (arr == null) return emptyList()
    return List(arr.size) { index ->
        val item = arr.objOrNull(index) ?: JsonObject(emptyMap())
        ListeningTtsSegment(
            speakerId = item.str("speakerId"),
            speakerName = item.str("speakerName"),
            speakerGender = item.str("speakerGender"),
            voiceProfile = item.str("voiceProfile"),
            text = item.str("text")
        )
    }.filter { it.text.isNotBlank() }
}

private fun listeningAudioSegmentsJson(segments: List<ListeningAudioSegment>): JsonArray = buildJsonArray {
    segments.filter { it.text.isNotBlank() && it.endMs > it.startMs }.forEach { segment ->
        addJsonObject {
            put("id", segment.id)
            put("kind", segment.kind)
            put("speakerId", segment.speakerId)
            put("speakerName", segment.speakerName)
            put("speakerGender", segment.speakerGender)
            put("text", segment.text)
            put("startMs", segment.startMs)
            put("endMs", segment.endMs)
            put("turnIndex", segment.turnIndex)
            put("sentenceIndex", segment.sentenceIndex)
        }
    }
}

private fun parseListeningAudioSegmentsFromManifest(arr: JsonArray?): List<ListeningAudioSegment> {
    if (arr == null) return emptyList()
    return List(arr.size) { index ->
        val item = arr.objOrNull(index) ?: JsonObject(emptyMap())
        ListeningAudioSegment(
            id = item.str("id"),
            kind = item.str("kind", "sentence"),
            speakerId = item.str("speakerId"),
            speakerName = item.str("speakerName"),
            speakerGender = item.str("speakerGender"),
            text = item.str("text"),
            startMs = item.int("startMs", 0).coerceAtLeast(0),
            endMs = item.int("endMs", 0).coerceAtLeast(0),
            turnIndex = item.int("turnIndex", 0),
            sentenceIndex = item.int("sentenceIndex", 0)
        )
    }.filter { it.text.isNotBlank() && it.endMs > it.startMs }
}

private fun analysisToJson(r: AnalysisResult): JsonObject = buildJsonObject {
    put("summary", r.summary)
    putJsonArray("weakPoints") { r.weakPoints.forEach { add(it) } }
    putJsonArray("suggestions") { r.suggestions.forEach { add(it) } }
}

private fun parseAnalysisFromJson(j: JsonObject) = AnalysisResult(
    summary = j.str("summary"),
    weakPoints = jsonArrayToStrings(j.arrOrNull("weakPoints")),
    suggestions = jsonArrayToStrings(j.arrOrNull("suggestions"))
)

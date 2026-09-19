package com.c0d3c.listene

data class TranscriptTurn(
    val speakerName: String = "",
    val text: String,
    val sentences: List<String>,
    val turnIndex: Int
)

object TranscriptSentenceSplitter {
    private const val SPEAKER_NAME_PATTERN =
        """(?:Speaker\s*[A-Za-z0-9]+|Narrator|Host|Interviewer|Interviewee|Teacher|Student|Man|Woman|Boy|Girl|[A-Z]|[A-Z][A-Za-z][A-Za-z'\- ]{0,30})"""
    private val embeddedSpeakerTurn = Regex(
        """\s+(($SPEAKER_NAME_PATTERN)[:：]\s*)"""
    )
    private val speakerPrefix = Regex(
        """^\s*$SPEAKER_NAME_PATTERN[:：]\s*"""
    )
    private val speakerCapture = Regex(
        """^\s*($SPEAKER_NAME_PATTERN)[:：]\s*(.*)$"""
    )
    private val leadingMarker = Regex("""^\s*(?:[-*•]\s+|\(?\d{1,3}[.)、）]\s*|[A-D][.)、）]\s*)""")
    private val titleAbbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st")
    private val inlineAbbreviations = setOf("e.g", "i.e", "vs")
    private val terminalAbbreviations = setOf("a.m", "p.m", "etc")
    private val closingChars = setOf('"', '\'', ')', ']', '}', '”', '’', '）', '】')

    fun splitTurnsForListening(script: String): List<TranscriptTurn> {
        if (script.isBlank()) return emptyList()
        val normalizedScript = script
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val hasSpeakerMarkers = normalizedScript
            .lineSequence()
            .any { speakerCapture.find(it.trim().replace(leadingMarker, "")) != null } ||
            embeddedSpeakerTurn.containsMatchIn(normalizedScript)

        if (!hasSpeakerMarkers) {
            val paragraphs = normalizedScript
                .split(Regex("""\n\s*\n+"""))
                .map { it.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(normalizedScript.replace(Regex("""\s+"""), " ").trim()).filter { it.isNotBlank() } }
            return paragraphs.mapIndexedNotNull { index, paragraph ->
                val sentences = splitLineIntoSentences(paragraph)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                if (sentences.isEmpty()) null else TranscriptTurn(
                    text = paragraph,
                    sentences = sentences,
                    turnIndex = index
                )
            }
        }

        val rawLines = normalizedScript
            .lineSequence()
            .flatMap { line ->
                embeddedSpeakerTurn.replace(line) { "\n${it.groupValues[1]}" }
                    .lineSequence()
            }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (rawLines.isEmpty()) return emptyList()

        val parsed = mutableListOf<Pair<String, String>>()
        var currentSpeaker = ""
        var currentText = StringBuilder()
        fun flush() {
            val text = currentText.toString().replace(Regex("""\s+"""), " ").trim()
            if (text.isNotBlank()) parsed += currentSpeaker to text
            currentText = StringBuilder()
        }

        rawLines.forEach { raw ->
            val withoutMarker = raw.replace(leadingMarker, "").trim()
            val match = speakerCapture.find(withoutMarker)
            if (match != null) {
                flush()
                currentSpeaker = match.groupValues[1].trim()
                currentText.append(match.groupValues[2].trim())
            } else if (currentText.isNotBlank()) {
                currentText.append(' ').append(withoutMarker)
            } else {
                currentSpeaker = ""
                currentText.append(withoutMarker)
            }
        }
        flush()

        return parsed.mapIndexedNotNull { index, (speaker, text) ->
            val sentences = splitLineIntoSentences(text)
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (sentences.isEmpty()) null else TranscriptTurn(
                speakerName = speaker,
                text = text,
                sentences = sentences,
                turnIndex = index
            )
        }.ifEmpty {
            val fallbackText = script.replace(Regex("""\s+"""), " ").trim()
            val sentences = splitLineIntoSentences(fallbackText).filter { it.isNotBlank() }
            if (sentences.isEmpty()) emptyList() else listOf(
                TranscriptTurn(text = fallbackText, sentences = sentences, turnIndex = 0)
            )
        }
    }

    private fun splitLineIntoSentences(line: String): List<String> {
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        var i = 0
        while (i < line.length) {
            val char = line[i]
            buffer.append(char)
            if (isSentenceEnd(char) && isBoundary(line, i, buffer.toString())) {
                var next = i + 1
                while (next < line.length && line[next] in closingChars) {
                    buffer.append(line[next])
                    next += 1
                }
                addCleanSegment(result, buffer.toString())
                buffer.clear()
                while (next < line.length && line[next].isWhitespace()) next += 1
                i = next
                continue
            }
            i += 1
        }
        addCleanSegment(result, buffer.toString())
        return result.flatMap { splitOverlongSegment(it) }
    }

    private fun isSentenceEnd(char: Char): Boolean = char == '.' || char == '?' || char == '!' || char == '。' || char == '？' || char == '！'

    private fun isBoundary(line: String, index: Int, currentText: String): Boolean {
        val char = line[index]
        if (char == '.' && isProtectedPeriod(line, index, currentText)) return false
        var next = index + 1
        while (next < line.length && line[next] in closingChars) next += 1
        if (next >= line.length) return true
        return line[next].isWhitespace()
    }

    private fun isProtectedPeriod(line: String, index: Int, currentText: String): Boolean {
        val prev = line.getOrNull(index - 1)
        val next = line.getOrNull(index + 1)
        if (prev?.isDigit() == true && next?.isDigit() == true) return true

        var nextNonSpace = index + 1
        while (nextNonSpace < line.length && line[nextNonSpace].isWhitespace()) nextNonSpace += 1
        val nextSignificant = line.getOrNull(nextNonSpace)

        val token = Regex("""([A-Za-z](?:\.[A-Za-z])*|[A-Za-z]+)\.$""")
            .find(currentText.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            ?: return false
        if (token in titleAbbreviations) return true
        if (token in inlineAbbreviations) return true
        if (token in terminalAbbreviations) return nextSignificant?.let { it.isLowerCase() || it.isDigit() } == true
        val compactInitialism = Regex("""(?:\b[A-Za-z]\.){2,}$""")
        if (compactInitialism.containsMatchIn(currentText.takeLast(10))) {
            return nextSignificant?.let { it.isLowerCase() || it.isDigit() } == true
        }
        if (token.length == 1 && nextSignificant?.isUpperCase() == true) return true
        return token == "no" && nextSignificant?.isDigit() == true
    }

    private fun addCleanSegment(result: MutableList<String>, raw: String) {
        val cleaned = raw.replace(Regex("""\s+"""), " ").trim()
        if (cleaned.isNotBlank()) result += cleaned
    }

    private fun splitOverlongSegment(segment: String): List<String> {
        if (segment.length <= 260) return listOf(segment)
        val semicolonParts = segment.split(Regex("""(?<=[;；])\s+""")).map { it.trim() }.filter { it.isNotBlank() }
        if (semicolonParts.size > 1) return semicolonParts

        val commaParts = segment.split(Regex("""(?<=[,，])\s+""")).map { it.trim() }.filter { it.isNotBlank() }
        if (commaParts.size <= 1) return listOf(segment)

        val merged = mutableListOf<String>()
        val buffer = StringBuilder()
        commaParts.forEach { part ->
            if (buffer.isNotEmpty() && buffer.length + part.length + 1 > 220) {
                merged += buffer.toString().trim()
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(part)
        }
        if (buffer.isNotEmpty()) merged += buffer.toString().trim()
        return merged.ifEmpty { listOf(segment) }
    }
}

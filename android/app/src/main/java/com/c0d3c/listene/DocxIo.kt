package com.c0d3c.listene

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 生成 Word / WPS 可识别的最小 DOCX（OOXML），无第三方库。 */
object DocxIo {
    fun write(paragraphs: List<String>, output: OutputStream) {
        val documentXml = buildDocumentXml(paragraphs)
        ZipOutputStream(output).use { zip ->
            putEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            putEntry(zip, "_rels/.rels", ROOT_RELS)
            putEntry(zip, "docProps/core.xml", buildCoreProps())
            putEntry(zip, "docProps/app.xml", APP_PROPS)
            putEntry(zip, "word/document.xml", documentXml)
            putEntry(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS)
            putEntry(zip, "word/styles.xml", STYLES)
        }
    }

    fun writeToBytes(paragraphs: List<String>): ByteArray {
        val bos = ByteArrayOutputStream()
        write(paragraphs, bos)
        return bos.toByteArray()
    }

    fun readText(input: InputStream): String {
        val bytes = input.readBytes()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/').trim('/')
                if (entryName == "word/document.xml" || entryName.endsWith("/word/document.xml")) {
                    val xmlBytes = zip.readBytes()
                    val parsed = runCatching { parseDocumentText(xmlBytes) }.getOrDefault("")
                    if (parsed.isNotBlank()) return parsed
                    return parseDocumentTextRegex(xmlBytes)
                }
                entry = zip.nextEntry
            }
        }
        error("无效的 DOCX 文件：缺少 word/document.xml")
    }

    private fun parseDocumentTextRegex(xmlBytes: ByteArray): String {
        val xml = xmlBytes.toString(Charsets.UTF_8)
        val texts = Regex("""<w:t[^>]*>(.*?)</w:t>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { unescapeXmlText(it.groupValues[1]) }
            .toList()
        return texts.joinToString("\n").trim()
    }

    private fun unescapeXmlText(raw: String): String = buildString(raw.length) {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '&') {
                when {
                    raw.startsWith("&amp;", i) -> { append('&'); i += 5 }
                    raw.startsWith("&lt;", i) -> { append('<'); i += 4 }
                    raw.startsWith("&gt;", i) -> { append('>'); i += 4 }
                    raw.startsWith("&quot;", i) -> { append('"'); i += 6 }
                    raw.startsWith("&apos;", i) -> { append('\''); i += 6 }
                    else -> { append(raw[i]); i++ }
                }
            } else {
                append(raw[i]); i++
            }
        }
    }

    private fun tagLocalName(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val idx = name.indexOf(':')
        return if (idx >= 0 && idx < name.length - 1) name.substring(idx + 1) else name
    }

    private fun putEntry(zip: ZipOutputStream, name: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val entry = ZipEntry(name)
        entry.method = ZipEntry.DEFLATED
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun buildCoreProps(): String {
        val now = Instant.now().toString()
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <dc:title>ListenE Export</dc:title>
              <dc:creator>ListenE</dc:creator>
              <cp:lastModifiedBy>ListenE</cp:lastModifiedBy>
              <dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>
              <dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified>
            </cp:coreProperties>
        """.trimIndent().trimEnd() + "\n"
    }

    private fun parseDocumentText(xmlBytes: ByteArray): String {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")
        val paragraphs = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (tagLocalName(parser.name) == "t") inText = true
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (tagLocalName(parser.name)) {
                    "t" -> inText = false
                    "p" -> {
                        paragraphs.add(current)
                        current = StringBuilder()
                    }
                }
            }
            event = parser.next()
        }
        if (current.isNotEmpty()) paragraphs.add(current)
        return paragraphs.joinToString("\n") { it.toString() }.trim()
    }

    private fun buildDocumentXml(paragraphs: List<String>): String {
        val paragraphXml = buildString {
            val source = paragraphs.ifEmpty { listOf("") }
            source.forEach { paragraph ->
                paragraph.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
                    append(buildParagraphXml(line))
                }
            }
            append(SECTION_PROPS)
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n" +
            "  <w:body>$paragraphXml</w:body>\n" +
            "</w:document>\n"
    }

    private fun buildParagraphXml(line: String): String {
        val sanitized = sanitizeXmlText(line)
        if (sanitized.isEmpty()) {
            return "<w:p/>"
        }
        val escaped = escapeXml(sanitized)
        val preserve = if (sanitized.first().isWhitespace() || sanitized.last().isWhitespace()) {
            " xml:space=\"preserve\""
        } else {
            ""
        }
        return "<w:p><w:r><w:t$preserve>$escaped</w:t></w:r></w:p>"
    }

    /** 去掉 XML 1.0 不允许的控制字符，避免 Word 解析失败显示乱码。 */
    private fun sanitizeXmlText(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch.code) {
                0x9, 0xA, 0xD -> append(ch)
                in 0x20..0xD7FF, in 0xE000..0xFFFD -> append(ch)
            }
        }
    }

    private fun escapeXml(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private const val SECTION_PROPS = """
<w:sectPr>
  <w:pgSz w:w="11906" w:h="16838"/>
  <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>
</w:sectPr>
"""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>
"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>
"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>
"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Calibri" w:eastAsia="宋体" w:hAnsi="Calibri" w:cs="Times New Roman"/>
        <w:lang w:val="en-US" w:eastAsia="zh-CN"/>
      </w:rPr>
    </w:rPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
    <w:qFormat/>
  </w:style>
</w:styles>
"""

    private const val APP_PROPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
  <Application>ListenE</Application>
</Properties>
"""
}

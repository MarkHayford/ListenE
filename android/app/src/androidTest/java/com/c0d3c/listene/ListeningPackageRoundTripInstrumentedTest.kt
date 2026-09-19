package com.c0d3c.listene

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.ZipInputStream

/**
 * 听力 ZIP 练习包的真机端到端往返测试：build -> 解包校验 -> import 回读校验。
 *
 * 单元测试（AgentWorkspaceRecordBindingTest）只覆盖 manifest 的解析/序列化，没有覆盖含真实音频
 * 字节的完整链路。这里覆盖两条取音频分支：
 *  1) 本地文件音频：resolveListeningAudioZipEntry 直接读本地文件字节；
 *  2) HTTP 下载音频：起一个 127.0.0.1 环回小服务喂音频字节，走 HttpURLConnection 下载分支。
 * 两条都断言 zip 内含齐 manifest/docx/transcript/questions/audio，且 import 后题目、原文、音频字节一致。
 *
 * 不依赖后端与登录：build/import 只用 Context（filesDir）与本机环回网络，因此快速且确定。
 */
@RunWith(AndroidJUnit4::class)
class ListeningPackageRoundTripInstrumentedTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { runCatching { it.delete() } }
        // import 会把音频落到 filesDir/audio_import_*.<ext>，清掉避免堆积。
        ctx.filesDir.listFiles()
            ?.filter { it.name.startsWith("audio_import_") }
            ?.forEach { runCatching { it.delete() } }
    }

    @Test
    fun localAudioRoundTripPreservesContentAndAudio() {
        val audioBytes = fakeWavBytes(seed = 7, dataLen = 4096)
        val audioFile = File(ctx.cacheDir, "listening_local_${System.nanoTime()}.wav").apply {
            writeBytes(audioBytes)
            tempFiles += this
        }
        val record = sampleRecord(audioUrl = audioFile.absolutePath)

        val zip = buildListeningZipBytes(ctx, record)

        assertTrue("产物应为 zip", isZipBytes(zip))
        val entries = unzip(zip)
        assertEntriesComplete(entries, expectedAudioName = "audio.wav")
        assertArrayEquals("zip 内音频字节应与源文件一致", audioBytes, entries["audio.wav"])

        assertRoundTrip(record, zip, expectedAudioBytes = audioBytes)
    }

    @Test
    fun httpAudioRoundTripDownloadsAudioIntoPackage() {
        val audioBytes = fakeWavBytes(seed = 19, dataLen = 8192)
        LoopbackAudioServer(audioBytes, "audio/wav").use { server ->
            server.start()
            val record = sampleRecord(audioUrl = "http://127.0.0.1:${server.port}/clip.wav")

            val zip = buildListeningZipBytes(ctx, record)

            assertTrue("产物应为 zip", isZipBytes(zip))
            val entries = unzip(zip)
            assertEntriesComplete(entries, expectedAudioName = "audio.wav")
            assertArrayEquals("zip 内音频字节应与 HTTP 下载一致", audioBytes, entries["audio.wav"])

            assertRoundTrip(record, zip, expectedAudioBytes = audioBytes)
        }
    }

    @Test
    fun exportWithoutUsableAudioFailsFast() {
        val record = sampleRecord(audioUrl = File(ctx.cacheDir, "does_not_exist.wav").absolutePath)
        try {
            buildListeningZipBytes(ctx, record)
            fail("缺少可用音频时应抛出异常")
        } catch (error: IllegalStateException) {
            assertTrue(
                "异常信息应提示需要音频：${error.message}",
                error.message?.contains("音频") == true
            )
        }
    }

    private fun assertRoundTrip(source: HistoryRecord, zip: ByteArray, expectedAudioBytes: ByteArray) {
        val imported = importListeningPackage(ctx, zip)

        assertEquals(source.scene, imported.scene)
        assertEquals(source.contentType, imported.contentType)
        assertEquals(source.content.title, imported.content.title)
        assertEquals(source.content.script, imported.content.script)

        assertEquals(source.content.questions.size, imported.content.questions.size)
        source.content.questions.forEachIndexed { index, expected ->
            val actual = imported.content.questions[index]
            assertEquals("第${index + 1}题题干", expected.questionText, actual.questionText)
            assertEquals("第${index + 1}题选项", expected.options, actual.options)
            assertEquals("第${index + 1}题正确答案下标", expected.correctAnswer, actual.correctAnswer)
            assertEquals("第${index + 1}题解析", expected.explanation, actual.explanation)
        }

        assertEquals(source.content.speakers.map { it.speakerName }, imported.content.speakers.map { it.speakerName })
        assertNotNull("学习报告应保留", imported.analysisResult)
        assertEquals(source.analysisResult?.summary, imported.analysisResult?.summary)
        assertEquals(source.analysisResult?.weakPoints, imported.analysisResult?.weakPoints)

        val importedAudio = imported.content.audioUrl
        assertNotNull("导入后应写出本地音频路径", importedAudio)
        val importedAudioFile = File(importedAudio!!)
        tempFiles += importedAudioFile
        assertTrue("导入音频文件应存在", importedAudioFile.exists())
        assertArrayEquals("导入音频字节应与源一致", expectedAudioBytes, importedAudioFile.readBytes())
    }

    private fun assertEntriesComplete(entries: Map<String, ByteArray>, expectedAudioName: String) {
        listOf("manifest.json", "content.docx", "transcript.txt", "questions.txt", expectedAudioName).forEach { name ->
            assertTrue("zip 应包含 $name，实际：${entries.keys}", entries.containsKey(name))
        }
        val transcript = entries["transcript.txt"]!!.toString(Charsets.UTF_8)
        assertTrue("transcript 应含原文", transcript.contains("Alice: A latte, please."))
        val questions = entries["questions.txt"]!!.toString(Charsets.UTF_8)
        assertTrue("questions 应含题干", questions.contains("What does the customer order?"))
    }

    private fun sampleRecord(audioUrl: String): HistoryRecord = HistoryRecord(
        id = "listening_pkg_rt",
        scene = "Cafe ordering",
        createdAt = 100L,
        contentType = "dialogue",
        content = ListeningContent(
            title = "Ordering Coffee",
            script = "Alice: A latte, please.\n\nBen: Sure, small or large?",
            questions = listOf(
                Question(
                    questionText = "What does the customer order?",
                    options = listOf("A latte", "A tea", "A cake"),
                    correctAnswer = 0,
                    explanation = "She asks for a latte."
                ),
                Question(
                    questionText = "What does the clerk ask about?",
                    options = listOf("The price", "The size", "The name"),
                    correctAnswer = 1,
                    explanation = "He asks small or large."
                )
            ),
            audioUrl = audioUrl,
            speakers = listOf(
                ListeningSpeaker(speakerId = "P1", speakerName = "Alice", speakerGender = "female"),
                ListeningSpeaker(speakerId = "P2", speakerName = "Ben", speakerGender = "male")
            )
        ),
        analysisResult = AnalysisResult(
            summary = "Good grasp of ordering phrases.",
            weakPoints = listOf("size vocabulary"),
            suggestions = listOf("Review small/medium/large.")
        )
    )

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    map[entry.name.replace('\\', '/').trim('/')] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return map
    }

    private fun fakeWavBytes(seed: Int, dataLen: Int): ByteArray {
        val data = ByteArray(dataLen) { ((it * 31 + seed) and 0xFF).toByte() }
        val sampleRate = 8000
        val bos = ByteArrayOutputStream()
        fun le32(v: Int) { bos.write(v and 0xFF); bos.write((v shr 8) and 0xFF); bos.write((v shr 16) and 0xFF); bos.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { bos.write(v and 0xFF); bos.write((v shr 8) and 0xFF) }
        bos.write("RIFF".toByteArray(Charsets.US_ASCII))
        le32(36 + data.size)
        bos.write("WAVE".toByteArray(Charsets.US_ASCII))
        bos.write("fmt ".toByteArray(Charsets.US_ASCII))
        le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate); le16(1); le16(8)
        bos.write("data".toByteArray(Charsets.US_ASCII))
        le32(data.size)
        bos.write(data)
        return bos.toByteArray()
    }

    /** 环回 HTTP 小服务：接受连接、读掉请求头、回一个带 Content-Length 的 200 响应体（音频字节）。 */
    private class LoopbackAudioServer(
        private val payload: ByteArray,
        private val contentType: String
    ) : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        fun start() {
            Thread {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use { s ->
                        val input = s.getInputStream()
                        val header = StringBuilder()
                        while (true) {
                            val b = input.read()
                            if (b == -1) break
                            header.append(b.toChar())
                            if (header.length >= 4 && header.endsWith("\r\n\r\n")) break
                        }
                        val response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        s.getOutputStream().apply {
                            write(response.toByteArray(Charsets.US_ASCII))
                            write(payload)
                            flush()
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            runCatching { server.close() }
        }
    }
}

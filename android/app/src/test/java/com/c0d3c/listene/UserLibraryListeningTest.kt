package com.c0d3c.listene

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 听力练习进卡片库：验证「保存形态 → 还原为可练习/可导出的 HistoryRecord」这条 round-trip，
 * 以及听力卡（无 microCard）能被识别并放行展示（否则会被卡片库过滤掉）。
 */
class UserLibraryListeningTest {
    private fun sampleRecord(): HistoryRecord = HistoryRecord(
        id = "rec-1",
        scene = "咖啡店点单",
        createdAt = 1_000L,
        content = ListeningContent(
            title = "Ordering Coffee",
            script = "A: Hi, what can I get you?\nB: A flat white, please.",
            questions = listOf(
                Question(
                    questionText = "What did the customer order?",
                    options = listOf("Tea", "A flat white", "Juice"),
                    correctAnswer = 1,
                    explanation = "B 明确说 a flat white。"
                )
            ),
            audioUrl = "/data/user/0/com.c0d3c.listene/files/audio/coffee.wav"
        ),
        contentType = "dialogue"
    )

    // 复刻 UserLibraryStore.saveGeneratedListeningCard 的 data 形态（不触达 Android Context）。
    private fun listeningItemFrom(record: HistoryRecord): UserLibraryItem {
        val manifest = parseJsonObjectOrNull(listeningManifestJson(record))!!
        val data = buildJsonObject {
            put("workspaceId", "ws-1")
            put("recordId", record.id)
            put("contentType", record.contentType)
            record.content.audioUrl?.let { put("audioUrl", it) }
            put("listening", manifest)
        }
        return UserLibraryItem(
            id = "local-1",
            kind = "cards",
            title = record.content.title,
            summary = "听力练习 · 1 题",
            data = data,
            createdAt = record.createdAt,
            updatedAt = record.createdAt
        )
    }

    @Test
    fun listeningItemIsDetectedAndShownInCardLibrary() {
        val item = listeningItemFrom(sampleRecord())
        assertTrue(isListeningLibraryItem(item))
        // 没有 microCard 也必须能展示（否则卡片库会把听力卡过滤掉）。
        assertTrue(userLibraryItemCanBeShown(item))
    }

    @Test
    fun listeningItemRoundTripsToPlayableRecord() {
        val item = listeningItemFrom(sampleRecord())
        val rebuilt = userLibraryListeningRecord(item)
        assertNotNull(rebuilt)
        requireNotNull(rebuilt)
        assertEquals("rec-1", rebuilt.id)
        assertEquals("dialogue", rebuilt.contentType)
        assertEquals("Ordering Coffee", rebuilt.content.title)
        // 音频路径回带 → 详情里可播放、导出 ZIP 可取到录音。
        assertEquals("/data/user/0/com.c0d3c.listene/files/audio/coffee.wav", rebuilt.content.audioUrl)
        assertEquals(1, rebuilt.content.questions.size)
        assertEquals(1, rebuilt.content.questions[0].correctAnswer)
        assertEquals("B 明确说 a flat white。", rebuilt.content.questions[0].explanation)
        assertTrue(rebuilt.content.script.contains("flat white"))
    }

    @Test
    fun nonListeningCardIsNotTreatedAsListening() {
        val data = buildJsonObject {
            put("workspaceId", "ws-1")
            put("microCard", buildJsonObject { put("title", "练习卡") })
        }
        val item = UserLibraryItem(
            id = "local-2",
            kind = "cards",
            title = "练习卡",
            summary = "",
            data = data,
            createdAt = 1L,
            updatedAt = 1L
        )
        assertFalse(isListeningLibraryItem(item))
        assertEquals(null, userLibraryListeningRecord(item))
    }
}

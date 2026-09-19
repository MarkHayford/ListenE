package com.c0d3c.listene

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案 B（客户端练习生成走微元）验证：
 * 1) 聊天消息携带 microCardJson 能完整 落库→读回→再解析 成 MicroCard（新增持久化路径）。
 * 2) 真接口：debug 包 API_BASE_URL=http://10.0.2.2:8001/api/v1 → 宿主 prod，活体打 /agent/micro
 *    （AI 实时拼卡），证明客户端→prod 微元生成链路通且可解析渲染。
 */
class MicroGenerateRoutingInstrumentedTest {
    @Test
    fun runnerKeepsProductionDefaultFlags() {
        // 旧渲染器退役 #5：Runner 不再钉死微元开关，E2E 按生产默认（开）跑。
        // 渲染侧灰度开关已随旧渲染宿主（AgentCardHost）一并退役，只剩生成开关。
        assertTrue("生成开关应保持生产默认（开），实际=${AgentMicroGenerateFlag.enabled}", AgentMicroGenerateFlag.enabled)
    }

    @Test
    fun microCardJsonSurvivesChatMessageSaveLoad() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val wsId = "ws_micro_persist_${System.currentTimeMillis()}"
        val microJson =
            """{"title":"完形(微元)","nodes":[{"type":"passage","text":"I ___ home."},{"type":"choice","prompt":"空1","options":["go","went"],"answer":"went"}]}"""
        val message = AgentChatMessage(
            id = 1L,
            role = AgentChatRole.Agent,
            text = "已生成微元练习卡（AI 实时拼装）。",
            microCardJson = microJson
        )
        try {
            AgentChatMessageStore.save(ctx, wsId, listOf(message))
            val loaded = AgentChatMessageStore.load(ctx, wsId)
            assertEquals(1, loaded.size)
            val restored = loaded.first()
            assertTrue("microCardJson 应在落库后保留", restored.microCardJson.isNotBlank())
            val card = MicroCardParser.parse(restored.microCardJson)
            assertNotNull(card)
            requireNotNull(card)
            assertEquals("完形(微元)", card.title)
            assertTrue(card.nodes.any { it is MicroNode.Choice })
        } finally {
            AgentChatMessageStore.delete(ctx, wsId)
        }
    }

    @Test
    fun liveMicroGenerationAgainstProdReturnsRenderableCard() {
        val result = runBlocking { MicroCardStore.generate("出一道一般现在时的句子改错练习") }
        assertTrue("活体 /agent/micro 应返回 ok（HTTP/解析错误：${result.error}）", result.ok)
        assertTrue("应带原始 card JSON 以便落库", result.cardJson.isNotBlank())
        val card = result.card
        assertNotNull("应解析出 MicroCard", card)
        requireNotNull(card)
        assertTrue("微元卡应至少有一个节点", card.nodes.isNotEmpty())
    }

    // ②流式端到端：decideStream 消费 prod /agent/chat/stream 的 SSE —— 应收到阶段进度 + done 带原生微元卡。
    @Test
    fun liveChatStreamCollectsStagesAndMicroCard() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val previous = AgentMicroGenerateFlag.enabled
        AgentMicroGenerateFlag.enabled = true
        val stages = mutableListOf<String>()
        try {
            val decision = runBlocking {
                AgentConversationService.decideStream(
                    ctx = ctx,
                    message = "出一道一般过去时的句子改错题",
                    workspace = null,
                    recentMessages = emptyList(),
                    onStage = { _, text -> synchronized(stages) { stages.add(text) } }
                )
            }
            assertEquals(AgentConversationIntent.PracticeCard, decision.intent)
            assertTrue("应至少收到一个阶段进度事件", stages.isNotEmpty())
            assertTrue("done 应带原生微元卡 JSON", decision.microCardJson.isNotBlank())
            val card = MicroCardParser.parse(decision.microCardJson)
            assertNotNull("microCardJson 应可解析", card)
            requireNotNull(card)
            assertTrue("微元卡应至少一个节点", card.nodes.isNotEmpty())
        } finally {
            AgentMicroGenerateFlag.enabled = previous
        }
    }

    // ②阶段1+2 端到端：生成开关开时，客户端 decide() 请求带 withMicro，prod /agent/chat 应原生附带微元卡，
    // 客户端解析进 decision.microCardJson（省去单独 /agent/micro 往返）。flag 用后即恢复，保持其它测试确定性。
    @Test
    fun liveChatCarriesNativeMicroCardWhenGenerateFlagOn() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val previous = AgentMicroGenerateFlag.enabled
        AgentMicroGenerateFlag.enabled = true
        try {
            val decision = runBlocking {
                AgentConversationService.decide(
                    ctx = ctx,
                    message = "出一道一般过去时的句子改错题",
                    workspace = null,
                    recentMessages = emptyList()
                )
            }
            assertEquals(AgentConversationIntent.PracticeCard, decision.intent)
            assertTrue("/agent/chat 应原生附带微元卡 JSON", decision.microCardJson.isNotBlank())
            val card = MicroCardParser.parse(decision.microCardJson)
            assertNotNull("microCardJson 应可解析为 MicroCard", card)
            requireNotNull(card)
            assertTrue("微元卡应至少有一个节点", card.nodes.isNotEmpty())
        } finally {
            AgentMicroGenerateFlag.enabled = previous
        }
    }
}

package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拍照答疑解析单测：验证服务端返回的结构化解题 JSON 能在端上稳健解析，
 * 空词过滤、缺字段 graceful 降级。
 */
class SolveStoreTest {
    @Test
    fun parsesStructuredSolveResult() {
        val json = """
            {"questionText":"She ___ to school.","questionType":"语法单选","skill":"语法",
             "points":["第三人称单数","一般现在时"],
             "steps":["主语 She 是第三人称单数","谓语加 -es → goes"],
             "answer":"goes","pitfalls":["别写成 go"],
             "vocab":[
               {"word":"school","phonetic":"/skuːl/","meaning":"学校","example":"I go to school."},
               {"word":"","meaning":"空词应过滤"}
             ],
             "comment":"很好，继续加油"}
        """.trimIndent()
        val o = parseJsonObjectOrNull(json)
        assertNotNull(o)
        requireNotNull(o)
        val r = parseSolveResult(o)
        assertEquals("She ___ to school.", r.questionText)
        assertEquals("语法单选", r.questionType)
        assertEquals("语法", r.skill)
        assertEquals(2, r.points.size)
        assertEquals(2, r.steps.size)
        assertEquals("goes", r.answer)
        assertEquals(1, r.pitfalls.size)
        assertEquals(1, r.vocab.size) // 空 word 的项被过滤
        assertEquals("school", r.vocab[0].word)
        assertEquals("学校", r.vocab[0].meaning)
        assertFalse(r.isEmpty)
    }

    @Test
    fun handlesMissingFieldsGracefully() {
        val o = parseJsonObjectOrNull("{}")
        assertNotNull(o)
        requireNotNull(o)
        val r = parseSolveResult(o)
        assertTrue(r.questionText.isEmpty())
        assertTrue(r.answer.isEmpty())
        assertTrue(r.steps.isEmpty())
        assertTrue(r.points.isEmpty())
        assertTrue(r.vocab.isEmpty())
        assertTrue(r.isEmpty)
    }
}

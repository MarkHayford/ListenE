package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微元卡 spike 单测：验证“AI 把扁平微元实时组合成卡”这条路在端上能解析 + 判分，
 * 完形/句子改错都只是微元组合（非固定题型），未知微元 graceful 忽略。
 */
class MicroCardTest {
    @Test
    fun parsesClozeCompositionFromFlatPrimitivesAndDropsUnknown() {
        val json = """
            {"title":"完形","nodes":[
              {"type":"text","role":"hint","text":"选词"},
              {"type":"passage","text":"I ___ to school."},
              {"type":"choice","prompt":"空1","options":["go","went","gone"],"answer":"went"},
              {"type":"unknown_widget","x":1}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        assertNotNull(card)
        requireNotNull(card)
        assertEquals("完形", card.title)
        // 未知微元被忽略（graceful 降级）→ 只剩 3 个已知微元
        assertEquals(3, card.nodes.size)
        assertTrue(card.nodes[0] is MicroNode.Text)
        assertTrue(card.nodes[1] is MicroNode.Passage)
        assertTrue(card.nodes[2] is MicroNode.Choice)
        val choice = card.nodes[2] as MicroNode.Choice
        assertTrue(microChoiceCorrect(1, choice.options, choice.answer))
        assertFalse(microChoiceCorrect(0, choice.options, choice.answer))
    }

    @Test
    fun parsesCorrectionAsTokensMicroAndGradesByDiff() {
        val json = """
            {"title":"改错","nodes":[
              {"type":"tokens","text":"She go to school yesterday.","correct":"She went to school yesterday.","explanation":"过去时"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        assertEquals(1, card.nodes.size)
        val tokens = card.nodes[0] as MicroNode.Tokens
        // "go"（下标 1）是唯一错误位置
        assertEquals(setOf(1), microTokensErrorIndices(tokens))
    }

    @Test
    fun parsesAndGradesOrderMicro() {
        val json = """
            {"title":"排序","nodes":[
              {"type":"order","prompt":"排成正确顺序","items":["world","Hello"],"answer":"Hello | world"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val order = card.nodes.filterIsInstance<MicroNode.Order>().first()
        assertEquals(listOf("world", "Hello"), order.items)
        assertTrue(microOrderCorrect(listOf("Hello", "world"), order.answer))
        assertFalse(microOrderCorrect(listOf("world", "Hello"), order.answer))
        // 句子形式（answer 为完整句子）：拼接后归一化比对
        assertTrue(microOrderCorrect(listOf("I", "went", "home"), "I went home."))
    }

    @Test
    fun parsesAndGradesMatchMicro() {
        val json = """
            {"title":"连线","nodes":[
              {"type":"match","prompt":"配对词义","pairs":[{"left":"apple","right":"苹果"},{"left":"banana","right":"香蕉"}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val match = card.nodes.filterIsInstance<MicroNode.Match>().first()
        assertEquals(2, match.pairs.size)
        assertEquals("apple", match.pairs[0].left)
        assertEquals("苹果", match.pairs[0].right)
        assertTrue(microMatchCorrect(mapOf(0 to "苹果", 1 to "香蕉"), match.pairs))
        assertFalse(microMatchCorrect(mapOf(0 to "香蕉", 1 to "香蕉"), match.pairs))
        assertFalse(microMatchCorrect(mapOf(0 to "苹果"), match.pairs))
    }

    @Test
    fun parsesAndGradesCategorizeMicro() {
        val json = """
            {"title":"归类","nodes":[
              {"type":"categorize","prompt":"按词性归类","categories":[{"name":"动词","items":["run","eat"]},{"name":"名词","items":["cat"]}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val cat = card.nodes.filterIsInstance<MicroNode.Categorize>().first()
        assertEquals(2, cat.categories.size)
        assertTrue(microCategorizeCorrect(mapOf("run" to "动词", "eat" to "动词", "cat" to "名词"), cat.categories))
        assertFalse(microCategorizeCorrect(mapOf("run" to "名词", "eat" to "动词", "cat" to "名词"), cat.categories))
        assertFalse(microCategorizeCorrect(mapOf("run" to "动词"), cat.categories))
    }

    @Test
    fun parsesFlashcardMicro() {
        val json = """
            {"title":"翻卡","nodes":[
              {"type":"flashcard","prompt":"记单词","cards":[{"front":"apple","back":"苹果"},{"front":"cat","back":"猫"}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val fc = card.nodes.filterIsInstance<MicroNode.Flashcard>().first()
        assertEquals(2, fc.cards.size)
        assertEquals("apple", fc.cards[0].front)
        assertEquals("苹果", fc.cards[0].back)
    }

    @Test
    fun parsesAndGradesAudioMicros() {
        val json = """
            {"title":"听力","nodes":[
              {"type":"dictation","text":"I have finished my homework.","hint":"现在完成时"},
              {"type":"audio_choice","audioText":"sheep","prompt":"你听到的是？","options":["ship","sheep"],"answer":"sheep"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val d = card.nodes.filterIsInstance<MicroNode.Dictation>().first()
        assertEquals("I have finished my homework.", d.text)
        assertEquals("现在完成时", d.hint)
        assertTrue(microInputCorrect("i have finished my homework", d.text))
        val ac = card.nodes.filterIsInstance<MicroNode.AudioChoice>().first()
        assertEquals(listOf("ship", "sheep"), ac.options)
        assertTrue(microChoiceCorrect(1, ac.options, ac.answer))
        assertFalse(microChoiceCorrect(0, ac.options, ac.answer))
    }

    @Test
    fun parsesSpeakScoreMicro() {
        val json = """
            {"title":"跟读","nodes":[
              {"type":"speak_score","text":"How are you doing today?"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val s = card.nodes.filterIsInstance<MicroNode.SpeakScore>().first()
        assertEquals("How are you doing today?", s.text)
    }

    @Test
    fun parsesAndGradesClozeDragMicro() {
        val json = """
            {"title":"填空","nodes":[
              {"type":"cloze_drag","text":"I ___ to school and ___ homework.","bank":["went","did","go"],"answers":["went","did"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val cd = card.nodes.filterIsInstance<MicroNode.ClozeDrag>().first()
        assertEquals(listOf("went", "did"), cd.answers)
        assertEquals(listOf("went", "did", "go"), cd.bank)
        assertTrue(microClozeDragCorrect(mapOf(0 to "went", 1 to "did"), cd.answers))
        assertFalse(microClozeDragCorrect(mapOf(0 to "go", 1 to "did"), cd.answers))
        assertFalse(microClozeDragCorrect(mapOf(0 to "went"), cd.answers))
    }

    @Test
    fun parsesRoleplayTurnMicro() {
        val json = """
            {"title":"情景对话","nodes":[
              {"type":"roleplay_turn","scenario":"你是咖啡店店员，我来点单。","opening":"Hi! What can I get for you?","goal":"点一杯咖啡","turns":4}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val rp = card.nodes.filterIsInstance<MicroNode.RoleplayTurn>().first()
        assertEquals("你是咖啡店店员，我来点单。", rp.scenario)
        assertEquals("Hi! What can I get for you?", rp.opening)
        assertEquals("点一杯咖啡", rp.goal)
        assertEquals(4, rp.turns)
    }

    @Test
    fun parsesAiHintMicro() {
        val json = """
            {"title":"渐进提示","nodes":[
              {"type":"ai_hint","prompt":"翻译：我昨天去了学校。","hints":["用一般过去时","go 的过去式是 went"],"answer":"I went to school yesterday."}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val h = card.nodes.filterIsInstance<MicroNode.AiHint>().first()
        assertEquals("翻译：我昨天去了学校。", h.prompt)
        assertEquals(listOf("用一般过去时", "go 的过去式是 went"), h.hints)
        assertEquals("I went to school yesterday.", h.answer)
    }

    @Test
    fun parsesAndGradesHighlightSpanMicro() {
        val json = """
            {"title":"框选","nodes":[
              {"type":"highlight_span","prompt":"选出所有动词","text":"I run and she eats.","answers":["run","eats"],"explanation":"run/eats 是动词"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val hs = card.nodes.filterIsInstance<MicroNode.HighlightSpan>().first()
        assertEquals("选出所有动词", hs.prompt)
        assertEquals(listOf("run", "eats"), hs.answers)
        val target = microHighlightTargetIndices(hs)
        assertTrue(target.isNotEmpty())
        // 恰好选中全部目标 → 正确
        assertTrue(microHighlightCorrect(target, hs))
        // 少选（漏一个）→ 错
        assertFalse(microHighlightCorrect(target.take(1).toSet(), hs))
        // 多选（额外选一个非目标位置）→ 错
        assertFalse(microHighlightCorrect(target + 999, hs))
    }

    @Test
    fun parsesTimedChallengeMicroAndGradesByChoice() {
        val json = """
            {"title":"限时","nodes":[
              {"type":"timed_challenge","prompt":"2 + 2 = ?","options":["3","4","5"],"answer":"4","seconds":20}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val tc = card.nodes.filterIsInstance<MicroNode.TimedChallenge>().first()
        assertEquals("2 + 2 = ?", tc.prompt)
        assertEquals(listOf("3", "4", "5"), tc.options)
        assertEquals("4", tc.answer)
        assertEquals(20, tc.seconds)
        // 判分复用 microChoiceCorrect
        assertTrue(microChoiceCorrect(1, tc.options, tc.answer))
        assertFalse(microChoiceCorrect(0, tc.options, tc.answer))
        // seconds 缺省 → 15
        val d = MicroCardParser.parse("""{"nodes":[{"type":"timed_challenge","prompt":"q","options":["a","b"],"answer":"a"}]}""")
        val tc2 = requireNotNull(d).nodes.filterIsInstance<MicroNode.TimedChallenge>().first()
        assertEquals(15, tc2.seconds)
    }

    @Test
    fun parsesTableMicro() {
        val json = """
            {"title":"表","nodes":[
              {"type":"table","title":"动词变化","headers":["原形","过去式","过去分词"],"rows":[["go","went","gone"],["eat","ate","eaten"]]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val t = card.nodes.filterIsInstance<MicroNode.Table>().first()
        assertEquals("动词变化", t.title)
        assertEquals(listOf("原形", "过去式", "过去分词"), t.headers)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("go", "went", "gone"), t.rows[0])
        assertEquals(listOf("eat", "ate", "eaten"), t.rows[1])
    }

    @Test
    fun parsesAndGradesSentenceDiagramMicro() {
        val json = """
            {"title":"成分","nodes":[
              {"type":"sentence_diagram","sentence":"She reads books.","labels":["主语","谓语","宾语"],"items":[{"text":"She","label":"主语"},{"text":"reads","label":"谓语"},{"text":"books","label":"宾语"}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val sd = card.nodes.filterIsInstance<MicroNode.SentenceDiagram>().first()
        assertEquals(3, sd.items.size)
        assertEquals("She", sd.items[0].text)
        assertEquals("主语", sd.items[0].label)
        assertTrue(microDiagramCorrect(mapOf(0 to "主语", 1 to "谓语", 2 to "宾语"), sd.items))
        assertFalse(microDiagramCorrect(mapOf(0 to "谓语", 1 to "谓语", 2 to "宾语"), sd.items))
        assertFalse(microDiagramCorrect(mapOf(0 to "主语"), sd.items))
    }

    @Test
    fun parsesAndGradesWordScrambleMicro() {
        val json = """
            {"title":"重组","nodes":[
              {"type":"word_scramble","word":"cat","hint":"一种宠物","scrambled":["t","a","c"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val ws = card.nodes.filterIsInstance<MicroNode.WordScramble>().first()
        assertEquals("cat", ws.word)
        assertEquals("一种宠物", ws.hint)
        assertEquals(listOf("t", "a", "c"), ws.scrambled)
        // scrambled 提供 → 字母池即 scrambled 原序
        assertEquals(listOf("t", "a", "c"), microScrambleTiles(ws))
        // 拼成 c-a-t → 正确；乱序拼 → 错；空 → 错
        assertTrue(microWordScrambleCorrect(listOf("c", "a", "t"), ws.word))
        assertFalse(microWordScrambleCorrect(listOf("a", "c", "t"), ws.word))
        assertFalse(microWordScrambleCorrect(emptyList(), ws.word))
        // scrambled 缺省 → 端上按 word 拆单字母；判分忽略大小写/标点
        val ws2 = requireNotNull(MicroCardParser.parse("""{"nodes":[{"type":"word_scramble","word":"dog"}]}"""))
            .nodes.filterIsInstance<MicroNode.WordScramble>().first()
        assertEquals(listOf("d", "o", "g"), microScrambleTiles(ws2))
        assertTrue(microWordScrambleCorrect(listOf("D", "O", "G"), ws2.word))
    }

    @Test
    fun parsesAndGradesTrueFalseMicro() {
        val json = """
            {"title":"判断","nodes":[
              {"type":"true_false","prompt":"判断对错","statements":[{"text":"The sun rises in the east.","answer":true},{"text":"Cats can fly.","answer":false}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val tf = card.nodes.filterIsInstance<MicroNode.TrueFalse>().first()
        assertEquals(2, tf.statements.size)
        assertTrue(tf.statements[0].answer)
        assertFalse(tf.statements[1].answer)
        // 全部判对 → 正确；有一处判错 → 错；漏判一句 → 错
        assertTrue(microTrueFalseCorrect(mapOf(0 to true, 1 to false), tf.statements))
        assertFalse(microTrueFalseCorrect(mapOf(0 to true, 1 to true), tf.statements))
        assertFalse(microTrueFalseCorrect(mapOf(0 to true), tf.statements))
        // 宽松真值解析："对" → true，"no" → false
        val tf2 = requireNotNull(MicroCardParser.parse("""{"nodes":[{"type":"true_false","statements":[{"text":"a","answer":"对"},{"text":"b","answer":"no"}]}]}"""))
            .nodes.filterIsInstance<MicroNode.TrueFalse>().first()
        assertTrue(tf2.statements[0].answer)
        assertFalse(tf2.statements[1].answer)
    }

    @Test
    fun parsesAndGradesFillTableMicro() {
        val json = """
            {"title":"表格填空","nodes":[
              {"type":"fill_table","title":"动词变化","headers":["原形","过去式","过去分词"],"rows":[["go","went","gone"],["eat","ate","eaten"]],"blanks":[[0,1],[1,2]],"bank":["went","eaten","ate","gone"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val ft = card.nodes.filterIsInstance<MicroNode.FillTable>().first()
        assertEquals(listOf("原形", "过去式", "过去分词"), ft.headers)
        assertEquals(2, ft.rows.size)
        assertEquals(2, ft.blanks.size)
        assertEquals(MicroCellCoord(0, 1), ft.blanks[0])
        assertEquals(MicroCellCoord(1, 2), ft.blanks[1])
        // 期望答案取自完整表 rows[coord]
        assertEquals("went", microFillTableExpected(ft, ft.blanks[0]))
        assertEquals("eaten", microFillTableExpected(ft, ft.blanks[1]))
        // 全填对 → 正确（含大小写归一）；填错/漏填 → 错
        assertTrue(microFillTableCorrect(mapOf(0 to "went", 1 to "eaten"), ft))
        assertTrue(microFillTableCorrect(mapOf(0 to "WENT", 1 to "Eaten"), ft))
        assertFalse(microFillTableCorrect(mapOf(0 to "gone", 1 to "eaten"), ft))
        assertFalse(microFillTableCorrect(mapOf(0 to "went"), ft))
    }

    @Test
    fun parsesPairsMemoryMicro() {
        val json = """
            {"title":"翻牌","nodes":[
              {"type":"pairs_memory","prompt":"翻牌配对","pairs":[{"left":"apple","right":"苹果"},{"left":"banana","right":"香蕉"}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val pm = card.nodes.filterIsInstance<MicroNode.PairsMemory>().first()
        assertEquals("翻牌配对", pm.prompt)
        assertEquals(2, pm.pairs.size)
        assertEquals("apple", pm.pairs[0].left)
        assertEquals("苹果", pm.pairs[0].right)
        // 不足 2 对（右项空）→ 被丢弃后为空（配对游戏无法进行）
        val bad = requireNotNull(MicroCardParser.parse("""{"nodes":[{"type":"pairs_memory","pairs":[{"left":"a","right":""}]}]}"""))
        assertTrue(bad.nodes.filterIsInstance<MicroNode.PairsMemory>().first().pairs.isEmpty())
    }

    @Test
    fun parsesAndGradesClozeSelectMicro() {
        val json = """
            {"title":"完形","nodes":[
              {"type":"cloze_select","text":"I ___ to school and ___ homework.","blanks":[{"options":["go","went","gone"],"answer":"went"},{"options":["do","did","done"],"answer":"did"}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val cs = card.nodes.filterIsInstance<MicroNode.ClozeSelect>().first()
        assertEquals(2, cs.blanks.size)
        assertEquals(listOf("go", "went", "gone"), cs.blanks[0].options)
        assertEquals("went", cs.blanks[0].answer)
        // 选 went(下标1)+did(下标1) → 正确；选错/漏选 → 错
        assertTrue(microClozeSelectCorrect(mapOf(0 to 1, 1 to 1), cs.blanks))
        assertFalse(microClozeSelectCorrect(mapOf(0 to 0, 1 to 1), cs.blanks))
        assertFalse(microClozeSelectCorrect(mapOf(0 to 1), cs.blanks))
    }

    @Test
    fun parsesAndGradesListenFillMicro() {
        val json = """
            {"title":"听力填空","nodes":[
              {"type":"listen_fill","audioText":"I went to school by bus.","text":"I ___ to school by ___.","bank":["went","bus","go","car"],"answers":["went","bus"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val lf = card.nodes.filterIsInstance<MicroNode.ListenFill>().first()
        assertEquals("I went to school by bus.", lf.audioText)
        assertEquals(listOf("went", "bus"), lf.answers)
        assertEquals(listOf("went", "bus", "go", "car"), lf.bank)
        // 判分复用 microClozeDragCorrect：全填对→对；填错/漏填→错
        assertTrue(microClozeDragCorrect(mapOf(0 to "went", 1 to "bus"), lf.answers))
        assertFalse(microClozeDragCorrect(mapOf(0 to "go", 1 to "bus"), lf.answers))
        assertFalse(microClozeDragCorrect(mapOf(0 to "went"), lf.answers))
    }

    @Test
    fun parsesAndGradesWordFormationMicro() {
        val json = """
            {"title":"词形","nodes":[
              {"type":"word_formation","prompt":"写出词形","items":[{"base":"happy","target":"名词","answer":"happiness"},{"base":"care","target":"形容词","answer":"careful"}],"bank":["happiness","careful","happily","careless"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val wf = card.nodes.filterIsInstance<MicroNode.WordFormation>().first()
        assertEquals(2, wf.items.size)
        assertEquals("happy", wf.items[0].base)
        assertEquals("名词", wf.items[0].target)
        assertEquals("happiness", wf.items[0].answer)
        // 全填对 → 对（含大小写归一）；填错/漏填 → 错
        assertTrue(microWordFormationCorrect(mapOf(0 to "happiness", 1 to "careful"), wf.items))
        assertTrue(microWordFormationCorrect(mapOf(0 to "Happiness", 1 to "careful"), wf.items))
        assertFalse(microWordFormationCorrect(mapOf(0 to "happily", 1 to "careful"), wf.items))
        assertFalse(microWordFormationCorrect(mapOf(0 to "happiness"), wf.items))
    }

    @Test
    fun parsesTimelineMicro() {
        val json = """
            {"title":"时间线","nodes":[
              {"type":"timeline","title":"学习路线","events":[{"time":"第1周","title":"入门基础","detail":"学音标"},{"time":"第2周","title":"扩展词汇"}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val tl = card.nodes.filterIsInstance<MicroNode.Timeline>().first()
        assertEquals("学习路线", tl.title)
        assertEquals(2, tl.events.size)
        assertEquals("第1周", tl.events[0].time)
        assertEquals("入门基础", tl.events[0].title)
        assertEquals("学音标", tl.events[0].detail)
        assertEquals("", tl.events[1].detail)
        // 缺 title 的事件被丢弃
        val bad = requireNotNull(MicroCardParser.parse("""{"nodes":[{"type":"timeline","events":[{"time":"x"},{"title":"y"}]}]}"""))
        assertEquals(1, bad.nodes.filterIsInstance<MicroNode.Timeline>().first().events.size)
    }

    @Test
    fun parsesAndGradesStressMarkMicro() {
        val json = """
            {"title":"重音","nodes":[
              {"type":"stress_mark","word":"computer","syllables":["com","pu","ter"],"stress":2}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val sm = card.nodes.filterIsInstance<MicroNode.StressMark>().first()
        assertEquals("computer", sm.word)
        assertEquals(listOf("com", "pu", "ter"), sm.syllables)
        assertEquals(2, sm.stress)
        // 选中第 2 个音节(下标 1) → 对；选错/未选 → 错
        assertTrue(microStressMarkCorrect(1, sm))
        assertFalse(microStressMarkCorrect(0, sm))
        assertFalse(microStressMarkCorrect(null, sm))
        // stress 越界 → 错
        assertFalse(microStressMarkCorrect(0, MicroNode.StressMark("x", listOf("a", "b"), 9)))
    }

    @Test
    fun parsesAndGradesReorderParagraphMicro() {
        val json = """
            {"title":"语篇排序","nodes":[
              {"type":"reorder_paragraph","prompt":"排成连贯段落","sentences":["First, I woke up early.","Then I had breakfast.","Finally I went to school."]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val rp = card.nodes.filterIsInstance<MicroNode.ReorderParagraph>().first()
        assertEquals("排成连贯段落", rp.prompt)
        assertEquals(3, rp.sentences.size)
        assertEquals("First, I woke up early.", rp.sentences[0])
        // 全序对 → 对（含大小写归一）；错序/未排满 → 错
        assertTrue(microReorderParagraphCorrect(rp.sentences, rp.sentences))
        assertTrue(microReorderParagraphCorrect(rp.sentences.map { it.uppercase() }, rp.sentences))
        assertFalse(microReorderParagraphCorrect(listOf(rp.sentences[1], rp.sentences[0], rp.sentences[2]), rp.sentences))
        assertFalse(microReorderParagraphCorrect(rp.sentences.take(2), rp.sentences))
        // 少于 2 句 → 无法排序 → 错
        assertFalse(microReorderParagraphCorrect(listOf("only"), listOf("only")))
    }

    @Test
    fun parsesAndGradesOddOneOutMicro() {
        val json = """
            {"title":"选异类","nodes":[
              {"type":"odd_one_out","prompt":"选出不同类的","items":["apple","banana","car","orange"],"answer":"car","explanation":"其余都是水果"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val oo = card.nodes.filterIsInstance<MicroNode.OddOneOut>().first()
        assertEquals(listOf("apple", "banana", "car", "orange"), oo.items)
        assertEquals("car", oo.answer)
        assertEquals("其余都是水果", oo.explanation)
        // 复用 choice 判分：选中异类(下标2 car) → 对；选同类 → 错；未选 → 错
        assertTrue(microChoiceCorrect(2, oo.items, oo.answer))
        assertFalse(microChoiceCorrect(0, oo.items, oo.answer))
        assertFalse(microChoiceCorrect(null, oo.items, oo.answer))
    }

    @Test
    fun parsesAndGradesRankOrderMicro() {
        val json = """
            {"title":"程度排序","nodes":[
              {"type":"rank_order","prompt":"按从冷到热排列","items":["freezing","cold","warm","hot"],"from":"最冷","to":"最热","explanation":"温度由低到高"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val ro = card.nodes.filterIsInstance<MicroNode.RankOrder>().first()
        assertEquals(listOf("freezing", "cold", "warm", "hot"), ro.items)
        assertEquals("最冷", ro.from)
        assertEquals("最热", ro.to)
        // 全序对 → 对（含大小写归一）；错序/未排满/少于3项 → 错
        assertTrue(microRankOrderCorrect(ro.items, ro.items))
        assertTrue(microRankOrderCorrect(ro.items.map { it.uppercase() }, ro.items))
        assertFalse(microRankOrderCorrect(listOf("cold", "freezing", "warm", "hot"), ro.items))
        assertFalse(microRankOrderCorrect(ro.items.take(3), ro.items))
        assertFalse(microRankOrderCorrect(listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun parsesAndGradesSpellingBeeMicro() {
        val json = """
            {"title":"听音拼写","nodes":[
              {"type":"spelling_bee","word":"necessary","hint":"必要的","example":"It is ___ to rest."}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val sb = card.nodes.filterIsInstance<MicroNode.SpellingBee>().first()
        assertEquals("necessary", sb.word)
        assertEquals("必要的", sb.hint)
        assertEquals("It is ___ to rest.", sb.example)
        // 拼写归一（小写 + 去非字母数字）：正确/大小写/前后空格 → 对；错拼/空 → 错
        assertTrue(microSpellingCorrect("necessary", sb.word))
        assertTrue(microSpellingCorrect("  Necessary ", sb.word))
        assertFalse(microSpellingCorrect("neccessary", sb.word))
        assertFalse(microSpellingCorrect("", sb.word))
    }

    @Test
    fun parsesAndGradesSentenceTransformMicro() {
        val json = """
            {"title":"句型转换","nodes":[
              {"type":"sentence_transform","prompt":"改为被动语态","source":"He wrote the book.","answer":"The book was written by him.","accept":["The book was authored by him."]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val st = card.nodes.filterIsInstance<MicroNode.SentenceTransform>().first()
        assertEquals("改为被动语态", st.prompt)
        assertEquals("He wrote the book.", st.source)
        assertEquals("The book was written by him.", st.answer)
        assertEquals(listOf("The book was authored by him."), st.accept)
        // 标准答案对；大小写/句末标点归一后对；accept 备选写法对；原句/空 → 错
        assertTrue(microSentenceTransformCorrect("The book was written by him.", st.answer, st.accept))
        assertTrue(microSentenceTransformCorrect("the book was written by him", st.answer, st.accept))
        assertTrue(microSentenceTransformCorrect("The book was authored by him", st.answer, st.accept))
        assertFalse(microSentenceTransformCorrect("He wrote the book.", st.answer, st.accept))
        assertFalse(microSentenceTransformCorrect("", st.answer, st.accept))
    }

    @Test
    fun parsesAndGradesOpenClozeMicro() {
        val json = """
            {"title":"开放式填空","nodes":[
              {"type":"open_cloze","prompt":"用适当的词填空","text":"I am good ___ English and I have ___ apple.","answers":["at","a/an"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val oc = card.nodes.filterIsInstance<MicroNode.OpenCloze>().first()
        assertEquals(listOf("at", "a/an"), oc.answers)
        // 全空对（含大小写、某空多写法 a/an 命中任一）→ 对；某空错/未填满/空 → 错
        assertTrue(microOpenClozeCorrect(mapOf(0 to "at", 1 to "an"), oc.answers))
        assertTrue(microOpenClozeCorrect(mapOf(0 to "AT", 1 to "a"), oc.answers))
        assertFalse(microOpenClozeCorrect(mapOf(0 to "in", 1 to "an"), oc.answers))
        assertFalse(microOpenClozeCorrect(mapOf(0 to "at"), oc.answers))
        assertFalse(microOpenClozeCorrect(emptyMap(), oc.answers))
    }

    @Test
    fun parsesAndGradesTranslateMicro() {
        val json = """
            {"title":"翻译","nodes":[
              {"type":"translate","direction":"zh2en","source":"我每天步行去上学。","answer":"I walk to school every day.","accept":["I go to school on foot every day."]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val tr = card.nodes.filterIsInstance<MicroNode.Translate>().first()
        assertEquals("zh2en", tr.direction)
        assertEquals("我每天步行去上学。", tr.source)
        assertEquals("I walk to school every day.", tr.answer)
        // 参考译文对；大小写/句末标点归一后对；accept 备选译文对；错译/空 → 错
        assertTrue(microTranslateCorrect("I walk to school every day.", tr.answer, tr.accept))
        assertTrue(microTranslateCorrect("i walk to school every day", tr.answer, tr.accept))
        assertTrue(microTranslateCorrect("I go to school on foot every day.", tr.answer, tr.accept))
        assertFalse(microTranslateCorrect("I drive to school.", tr.answer, tr.accept))
        assertFalse(microTranslateCorrect("", tr.answer, tr.accept))
    }

    @Test
    fun typedAnswerUnifiedNormHandlesPunctuationAndAccept() {
        // 共用判分 microTypedAnswerCorrect：大小写/首尾及中英文句末标点归一；answer 或任一 accept 命中即对；空 → 错。
        assertTrue(microTypedAnswerCorrect("I am happy.", "I am happy", emptyList()))
        assertTrue(microTypedAnswerCorrect("我很高兴。", "我很高兴", emptyList()))
        assertTrue(microTypedAnswerCorrect("plan B", "plan A", listOf("plan B")))
        assertFalse(microTypedAnswerCorrect("something else", "I am happy", emptyList()))
        assertFalse(microTypedAnswerCorrect("", "x", emptyList()))
    }

    @Test
    fun parsesAndGradesTfngMicro() {
        val json = """
            {"title":"判断三态","nodes":[
              {"type":"tfng","prompt":"根据短文判断","statements":[
                {"text":"The author lives in Paris.","answer":"true"},
                {"text":"The author hates coffee.","answer":"not_given"},
                {"text":"Cats can fly.","answer":"false"}
              ]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val tf = card.nodes.filterIsInstance<MicroNode.Tfng>().first()
        assertEquals(3, tf.statements.size)
        assertEquals("true", tf.statements[0].answer)
        assertEquals("not_given", tf.statements[1].answer)
        assertEquals("false", tf.statements[2].answer)
        // 三态全判对 → 对；有一句错/未判满/空 → 错
        assertTrue(microTfngCorrect(mapOf(0 to "true", 1 to "not_given", 2 to "false"), tf.statements))
        assertFalse(microTfngCorrect(mapOf(0 to "true", 1 to "false", 2 to "false"), tf.statements))
        assertFalse(microTfngCorrect(mapOf(0 to "true", 1 to "not_given"), tf.statements))
        assertFalse(microTfngCorrect(emptyMap(), tf.statements))
        // answer 宽松解析（中文/别名）
        val tf2 = MicroCardParser.parse(
            """{"nodes":[{"type":"tfng","statements":[{"text":"a","answer":"未提及"},{"text":"b","answer":"错误"},{"text":"c","answer":"对"}]}]}"""
        )!!.nodes.filterIsInstance<MicroNode.Tfng>().first()
        assertEquals(listOf("not_given", "false", "true"), tf2.statements.map { it.answer })
    }

    @Test
    fun parsesAndGradesListenClozeMicro() {
        val json = """
            {"title":"听力填空","nodes":[
              {"type":"listen_cloze","audioText":"I went to school by bus.","text":"I ___ to school by ___.","answers":["went","bus"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val lc = card.nodes.filterIsInstance<MicroNode.ListenCloze>().first()
        assertEquals("I went to school by bus.", lc.audioText)
        assertEquals(listOf("went", "bus"), lc.answers)
        // 逐空判分复用 microOpenClozeCorrect：全对（含大小写归一）→ 对；某空错/未填满/空 → 错
        assertTrue(microOpenClozeCorrect(mapOf(0 to "went", 1 to "bus"), lc.answers))
        assertTrue(microOpenClozeCorrect(mapOf(0 to "Went", 1 to "BUS"), lc.answers))
        assertFalse(microOpenClozeCorrect(mapOf(0 to "go", 1 to "bus"), lc.answers))
        assertFalse(microOpenClozeCorrect(mapOf(0 to "went"), lc.answers))
        assertFalse(microOpenClozeCorrect(emptyMap(), lc.answers))
    }

    @Test
    fun parsesAndGradesErrorCorrectionMicro() {
        val json = """
            {"title":"改错","nodes":[
              {"type":"error_correction","prompt":"时态错误","sentence":"She go to school yesterday.","answer":"She went to school yesterday.","accept":["She went to school yesterday"],"explanation":"过去时间状语用过去式"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val ec = card.nodes.filterIsInstance<MicroNode.ErrorCorrection>().first()
        assertEquals("She go to school yesterday.", ec.sentence)
        assertEquals("She went to school yesterday.", ec.answer)
        assertEquals("过去时间状语用过去式", ec.explanation)
        // 判分复用 microSentenceTransformCorrect：标准答案/大小写与句末标点归一 → 对；原句(未改)/空 → 错
        assertTrue(microSentenceTransformCorrect("She went to school yesterday.", ec.answer, ec.accept))
        assertTrue(microSentenceTransformCorrect("she went to school yesterday", ec.answer, ec.accept))
        assertFalse(microSentenceTransformCorrect("She go to school yesterday.", ec.answer, ec.accept))
        assertFalse(microSentenceTransformCorrect("", ec.answer, ec.accept))
    }

    @Test
    fun parsesAndGradesDialogueCompleteMicro() {
        val json = """
            {"title":"补全对话","nodes":[
              {"type":"dialogue_complete","prompt":"在咖啡店","turns":[{"speaker":"Clerk","text":"What can I get for you?"},{"speaker":"You","text":"___"}],"options":["A latte, please.","See you tomorrow.","I am fifteen."],"answer":"A latte, please.","explanation":"点单场景应回应饮品"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val dc = card.nodes.filterIsInstance<MicroNode.DialogueComplete>().first()
        assertEquals(2, dc.turns.size)
        assertEquals("Clerk", dc.turns[0].speaker)
        assertEquals(listOf("A latte, please.", "See you tomorrow.", "I am fifteen."), dc.options)
        // 复用 choice 判分：选中正确回应(下标0) → 对；选干扰 → 错；未选 → 错
        assertTrue(microChoiceCorrect(0, dc.options, dc.answer))
        assertFalse(microChoiceCorrect(1, dc.options, dc.answer))
        assertFalse(microChoiceCorrect(null, dc.options, dc.answer))
    }

    @Test
    fun parsesAndGradesWordSearchMicro() {
        val json = """
            {"title":"找词","nodes":[
              {"type":"word_search","prompt":"找出动物","grid":[["C","A","T","X"],["D","Y","Z","Q"],["O","M","N","P"],["G","H","I","J"]],"words":["CAT","DOG"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val ws = card.nodes.filterIsInstance<MicroNode.WordSearch>().first()
        assertEquals(4, ws.grid.size)
        assertEquals(listOf("CAT", "DOG"), ws.words)
        // 路径：CAT 横排 row0 (0,0)-(0,2)；DOG 竖排 col0 (1,0)-(3,0)；不存在的返回 null
        assertEquals(listOf(0 to 0, 0 to 1, 0 to 2), microWordSearchPath(ws.grid, "CAT"))
        assertEquals(listOf(1 to 0, 2 to 0, 3 to 0), microWordSearchPath(ws.grid, "DOG"))
        assertTrue(microWordSearchPath(ws.grid, "FOX") == null)
        // 连线：直线返回沿线格；非直线返回 null
        assertEquals(listOf(0 to 0, 0 to 1, 0 to 2), microWordSearchLineCells(0, 0, 0, 2))
        assertTrue(microWordSearchLineCells(0, 0, 1, 2) == null)
        // 判分：找齐 → 对（含大小写归一）；缺一 / 空 → 错
        assertTrue(microWordSearchCorrect(setOf("CAT", "DOG"), ws.words))
        assertTrue(microWordSearchCorrect(setOf("cat", "dog"), ws.words))
        assertFalse(microWordSearchCorrect(setOf("CAT"), ws.words))
        assertFalse(microWordSearchCorrect(emptySet(), ws.words))
    }

    @Test
    fun parsesAndGradesHangmanMicro() {
        val json = """
            {"title":"猜词","nodes":[
              {"type":"hangman","word":"banana","hint":"香蕉","maxWrong":6}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val hm = card.nodes.filterIsInstance<MicroNode.Hangman>().first()
        assertEquals("banana", hm.word)
        assertEquals(6, hm.maxWrong)
        // banana 字母={B,A,N}：猜齐且错误<6 → 赢；含少量错误仍<6 → 赢；缺字母 → 输；错误达上限 → 输
        assertTrue(microHangmanCorrect(setOf('B', 'A', 'N'), hm.word, hm.maxWrong))
        assertTrue(microHangmanCorrect(setOf('B', 'A', 'N', 'X', 'Y'), hm.word, hm.maxWrong))
        assertFalse(microHangmanCorrect(setOf('B', 'A'), hm.word, hm.maxWrong))
        assertFalse(microHangmanCorrect(setOf('B', 'A', 'N', 'Q', 'W', 'E', 'R', 'T', 'Y'), hm.word, hm.maxWrong))
    }

    @Test
    fun parsesAndGradesProofParagraphMicro() {
        val json = """
            {"title":"短文改错","nodes":[
              {"type":"proof_paragraph","prompt":"改正每行错误","lines":[
                {"text":"She go to school every day.","answer":"She goes to school every day.","note":"第三人称单数"},
                {"text":"I like apples.","answer":"I like apples."},
                {"text":"He can sings well.","answer":"He can sing well.","note":"情态动词后用原形"}
              ]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val pp = card.nodes.filterIsInstance<MicroNode.ProofParagraph>().first()
        assertEquals(3, pp.lines.size)
        assertEquals("She goes to school every day.", pp.lines[0].answer)
        // 第1、3行改对 + 第2行本就对(未编辑=原文) → 全对
        assertTrue(microProofParagraphCorrect(mapOf(0 to "She goes to school every day.", 2 to "He can sing well."), pp.lines))
        // 只改第3行、第1行仍是错误原文 → 错
        assertFalse(microProofParagraphCorrect(mapOf(2 to "He can sing well."), pp.lines))
        // 全未编辑（第1/3行有错） → 错
        assertFalse(microProofParagraphCorrect(emptyMap(), pp.lines))
    }

    @Test
    fun parsesChartAndWritingMicroFromFlatPrimitives() {
        val json = """
            {"title":"图表作文","nodes":[
              {"type":"chart","chartType":"bar","title":"Sales","categories":["2019","2020"],"series":[{"name":"A","values":[10,20]},{"name":"B","values":[5,15]}]},
              {"type":"writing","prompt":"Describe the chart.","reference":"2019: 10, 5"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        assertEquals(2, card.nodes.size)
        val chart = card.nodes.filterIsInstance<MicroNode.Chart>().first()
        assertEquals("bar", chart.chartType)
        assertEquals(listOf("2019", "2020"), chart.categories)
        assertEquals(listOf("A", "B"), chart.seriesNames)
        // series 转置为「按类目 → 各系列值」：2019 → [A=10, B=5]
        assertEquals(listOf(10f, 5f), chart.seriesValues[0])
        assertEquals(listOf(20f, 15f), chart.seriesValues[1])
        assertTrue(chart.toChartModel().hasData)
        val writing = card.nodes.filterIsInstance<MicroNode.Writing>().first()
        assertEquals("Describe the chart.", writing.prompt)
        assertEquals("2019: 10, 5", writing.reference)
    }

    @Test
    fun inputGradingNormalizesAndAllUnknownCardStaysEmpty() {
        assertTrue(microInputCorrect("  Went ", "went"))
        assertFalse(microInputCorrect("", "went"))
        // 全是未知微元 → 空 nodes（端上 graceful：能渲染多少渲染多少，不套固定兜底卡）
        val card = MicroCardParser.parse("""{"title":"x","nodes":[{"type":"nope"}]}""")
        requireNotNull(card)
        assertEquals(0, card.nodes.size)
    }

    @Test
    fun parsesAndGradesMinimalPairMicro() {
        val json = """
            {"title":"最小对立对","nodes":[
              {"type":"minimal_pair","prompt":"听到的是哪个？","audioText":"sheep","options":["ship","sheep"],"answer":"sheep","ipa":["/ʃɪp/","/ʃiːp/"],"explanation":"长短元音对立"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val mp = card.nodes.filterIsInstance<MicroNode.MinimalPair>().first()
        assertEquals("sheep", mp.audioText)
        assertEquals(listOf("ship", "sheep"), mp.options)
        assertEquals(listOf("/ʃɪp/", "/ʃiːp/"), mp.ipa)
        // 复用 choice 判分：选中 answer(下标1) → 对；选干扰 → 错；未选 → 错
        assertTrue(microChoiceCorrect(1, mp.options, mp.answer))
        assertFalse(microChoiceCorrect(0, mp.options, mp.answer))
        assertFalse(microChoiceCorrect(null, mp.options, mp.answer))
        assertEquals("听力", microNodeSkill(mp))
    }

    @Test
    fun parsesAndGradesIpaReadMicro() {
        val json = """
            {"title":"音标认读","nodes":[
              {"type":"ipa_read","prompt":"含 /iː/ 的词","symbol":"/iː/","example":"see","options":["sit","see","set"],"answer":"see","explanation":"长音 /iː/"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val ir = card.nodes.filterIsInstance<MicroNode.IpaRead>().first()
        assertEquals("/iː/", ir.symbol)
        assertEquals("see", ir.example)
        assertTrue(microChoiceCorrect(1, ir.options, ir.answer))
        assertFalse(microChoiceCorrect(0, ir.options, ir.answer))
        assertEquals("口语", microNodeSkill(ir))
    }

    @Test
    fun parsesSoundLinkAndSpeakingMicrosNonGraded() {
        val json = """
            {"title":"发音口语","nodes":[
              {"type":"sound_link","text":"an apple a day","marks":["an_apple","a_day"],"note":"连读：辅音+元音"},
              {"type":"monologue","prompt":"描述你的家乡","scene":"一张城市照片","points":["位置","特色","推荐"]},
              {"type":"shadowing","text":"Practice makes perfect.","translation":"熟能生巧"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        assertEquals(3, card.nodes.size)
        val sl = card.nodes.filterIsInstance<MicroNode.SoundLink>().first()
        assertEquals(listOf("an_apple", "a_day"), sl.marks)
        assertEquals("连读：辅音+元音", sl.note)
        val mono = card.nodes.filterIsInstance<MicroNode.Monologue>().first()
        assertEquals("一张城市照片", mono.scene)
        assertEquals(listOf("位置", "特色", "推荐"), mono.points)
        val shadow = card.nodes.filterIsInstance<MicroNode.Shadowing>().first()
        assertEquals("Practice makes perfect.", shadow.text)
        assertEquals("熟能生巧", shadow.translation)
        // 非统一判分节点：不参与判分（nodeCorrect 默认 true），此处仅确认技能归类
        assertEquals("口语", microNodeSkill(sl))
        assertEquals("口语", microNodeSkill(mono))
        assertEquals("口语", microNodeSkill(shadow))
    }

    @Test
    fun parsesAndGradesMapLabelMicro() {
        val json = """
            {"title":"位置标注","nodes":[
              {"type":"map_label","prompt":"听录音标注地点","audioText":"The cafe is on the left of the entrance.","layout":"入口在南侧，主路两侧是店铺","options":["A","B","C"],"items":[{"text":"Cafe","answer":"A"},{"text":"Library","answer":"C"}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val ml = card.nodes.filterIsInstance<MicroNode.MapLabel>().first()
        assertEquals(2, ml.items.size)
        assertEquals("Cafe", ml.items[0].text)
        assertEquals(listOf("A", "B", "C"), ml.options)
        // 全部标对 → 对；缺一 → 错；标错 → 错
        assertTrue(microMapLabelCorrect(mapOf(0 to "A", 1 to "C"), ml.items))
        assertFalse(microMapLabelCorrect(mapOf(0 to "A"), ml.items))
        assertFalse(microMapLabelCorrect(mapOf(0 to "B", 1 to "C"), ml.items))
        assertEquals("听力", microNodeSkill(ml))
    }

    @Test
    fun parsesAndGradesNoteCompleteMicro() {
        val json = """
            {"title":"笔记填空","nodes":[
              {"type":"note_complete","audioText":"The tour starts at nine and costs ten pounds.","title":"City Tour Notes","text":"Start time: ___\nPrice: ___ pounds","answers":["nine","ten"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val nc = card.nodes.filterIsInstance<MicroNode.NoteComplete>().first()
        assertEquals("City Tour Notes", nc.title)
        assertEquals(listOf("nine", "ten"), nc.answers)
        // 复用 openCloze 判分：逐空填对 → 对（含大小写归一）；缺一 / 填错 → 错
        assertTrue(microOpenClozeCorrect(mapOf(0 to "Nine", 1 to "ten"), nc.answers))
        assertFalse(microOpenClozeCorrect(mapOf(0 to "nine"), nc.answers))
        assertFalse(microOpenClozeCorrect(mapOf(0 to "nine", 1 to "five"), nc.answers))
        assertEquals("听力", microNodeSkill(nc))
    }

    @Test
    fun parsesAndGradesMatchSentenceEndingsMicro() {
        val json = """
            {"title":"句尾配对","nodes":[
              {"type":"match_sentence_endings","prompt":"配出正确句尾","endings":["to save money.","because it rained.","than ever before."],"stems":[{"text":"They cancelled the trip","answer":"because it rained."},{"text":"She works two jobs","answer":"to save money."}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val mse = card.nodes.filterIsInstance<MicroNode.MatchSentenceEndings>().first()
        assertEquals(3, mse.endings.size)
        assertEquals(2, mse.stems.size)
        assertEquals("because it rained.", mse.stems[0].answer)
        // 全部配对 → 对；缺一 → 错；配错 → 错
        assertTrue(microSentenceEndingsCorrect(mapOf(0 to "because it rained.", 1 to "to save money."), mse.stems))
        assertFalse(microSentenceEndingsCorrect(mapOf(0 to "because it rained."), mse.stems))
        assertFalse(microSentenceEndingsCorrect(mapOf(0 to "to save money.", 1 to "because it rained."), mse.stems))
        assertEquals("阅读", microNodeSkill(mse))
    }

    @Test
    fun parsesAndGradesSummaryCompleteMicro() {
        val json = """
            {"title":"摘要填空","nodes":[
              {"type":"summary_complete","prompt":"用词库补全摘要","text":"The study found that ___ improves ___ significantly.","bank":["exercise","memory","sleep","stress"],"answers":["exercise","memory"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val sc = card.nodes.filterIsInstance<MicroNode.SummaryComplete>().first()
        assertEquals(listOf("exercise", "memory", "sleep", "stress"), sc.bank)
        assertEquals(listOf("exercise", "memory"), sc.answers)
        // 复用 clozeDrag 判分：逐空填对 → 对；缺一 / 填错 → 错
        assertTrue(microClozeDragCorrect(mapOf(0 to "exercise", 1 to "memory"), sc.answers))
        assertFalse(microClozeDragCorrect(mapOf(0 to "exercise"), sc.answers))
        assertFalse(microClozeDragCorrect(mapOf(0 to "sleep", 1 to "memory"), sc.answers))
        assertEquals("阅读", microNodeSkill(sc))
    }

    @Test
    fun parsesAndGradesShortAnswerMicro() {
        val json = """
            {"title":"篇章简答","nodes":[
              {"type":"short_answer","prompt":"根据短文简答","questions":[{"q":"Who wrote the letter?","answer":"Tom","accept":["Tom.","tom"]},{"q":"When did it arrive?","answer":"on Monday","accept":["Monday"]}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val sa = card.nodes.filterIsInstance<MicroNode.ShortAnswer>().first()
        assertEquals(2, sa.questions.size)
        assertEquals("Tom", sa.questions[0].answer)
        assertEquals(listOf("Tom.", "tom"), sa.questions[0].accept)
        // 全部答对 → 对（含 accept 容错/大小写归一）；缺一 → 错；答错 → 错
        assertTrue(microShortAnswerCorrect(mapOf(0 to "tom", 1 to "Monday"), sa.questions))
        assertFalse(microShortAnswerCorrect(mapOf(0 to "Tom"), sa.questions))
        assertFalse(microShortAnswerCorrect(mapOf(0 to "Jerry", 1 to "on Monday"), sa.questions))
        assertEquals("阅读", microNodeSkill(sa))
    }

    @Test
    fun parsesGuidedWritingMicro() {
        val json = """
            {"title":"引导写作","nodes":[
              {"type":"guided_writing","prompt":"写一封投诉信","reference":"Dear Sir, I am writing to complain...","steps":[{"label":"提纲","hint":"列出3个要点"},{"label":"主体段","hint":"每个要点一段"},{"label":"成文","hint":"连接成完整信件"}]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val gw = card.nodes.filterIsInstance<MicroNode.GuidedWriting>().first()
        assertEquals("写一封投诉信", gw.prompt)
        assertEquals(3, gw.steps.size)
        assertEquals("提纲", gw.steps[0].label)
        assertEquals("列出3个要点", gw.steps[0].hint)
        assertEquals("写作", microNodeSkill(gw))
    }

    @Test
    fun parsesAllNewMicrosTogetherAndDropsUnknown() {
        val json = """
            {"title":"新微元合卷","nodes":[
              {"type":"minimal_pair","audioText":"sheep","options":["ship","sheep"],"answer":"sheep"},
              {"type":"ipa_read","symbol":"/iː/","example":"see","options":["sit","see"],"answer":"see"},
              {"type":"sound_link","text":"an apple","marks":["an_apple"],"note":"连读"},
              {"type":"map_label","audioText":"a","options":["A","B"],"items":[{"text":"Cafe","answer":"A"}]},
              {"type":"note_complete","audioText":"a","title":"N","text":"x ___","answers":["nine"]},
              {"type":"match_sentence_endings","endings":["e1","e2"],"stems":[{"text":"s1","answer":"e1"}]},
              {"type":"summary_complete","text":"x ___","bank":["a","b"],"answers":["a"]},
              {"type":"short_answer","questions":[{"q":"q1","answer":"a1"}]},
              {"type":"monologue","prompt":"p","scene":"s","points":["x"]},
              {"type":"shadowing","text":"t","translation":"tr"},
              {"type":"guided_writing","prompt":"p","reference":"r","steps":[{"label":"l","hint":"h"}]},
              {"type":"totally_unknown_widget","x":1}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        // 11 个新微元全部解析成功，未知微元 graceful 丢弃
        assertEquals(11, card.nodes.size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.MinimalPair>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.IpaRead>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.SoundLink>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.MapLabel>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.NoteComplete>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.MatchSentenceEndings>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.SummaryComplete>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.ShortAnswer>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.Monologue>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.Shadowing>().size)
        assertEquals(1, card.nodes.filterIsInstance<MicroNode.GuidedWriting>().size)
    }

    @Test
    fun answersKeepBlankSlotsToPreserveBlankAlignment() {
        // answers 混入空串时须按位保留占位：空位2 仍无答案（判永错），空位3 仍对 "b"——
        // 而不是整体左移把 "b" 错配到空位2。ipa 数组同理按位对齐 options。
        val json = """
            {"title":"对齐","nodes":[
              {"type":"open_cloze","text":"x ___ y ___ z ___","answers":["a","","b"]},
              {"type":"cloze_drag","text":"x ___ y ___","bank":["a","b"],"answers":["","b"]},
              {"type":"listen_cloze","audioText":"t","text":"x ___ y ___","answers":[" ","b"]},
              {"type":"minimal_pair","audioText":"sheep","options":["ship","sheep"],"answer":"sheep","ipa":["","/ʃiːp/"]}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val oc = card.nodes.filterIsInstance<MicroNode.OpenCloze>().first()
        assertEquals(listOf("a", "", "b"), oc.answers)
        // 空位2 答案为空占位 → 该空判永错（不可全对），但空位3 的 "b" 不会被左移错配
        assertFalse(microOpenClozeCorrect(mapOf(0 to "a", 1 to "anything", 2 to "b"), oc.answers))
        val cd = card.nodes.filterIsInstance<MicroNode.ClozeDrag>().first()
        assertEquals(listOf("", "b"), cd.answers)
        assertFalse(microClozeDragCorrect(mapOf(0 to "b"), cd.answers)) // "b" 不再左移到空1
        val lc = card.nodes.filterIsInstance<MicroNode.ListenCloze>().first()
        assertEquals(listOf("", "b"), lc.answers)
        val mp = card.nodes.filterIsInstance<MicroNode.MinimalPair>().first()
        assertEquals(listOf("", "/ʃiːp/"), mp.ipa) // 音标仍对齐第2个选项，不会错标到 ship
    }

    @Test
    fun mapLabelGradingNormalizesCase() {
        val items = listOf(MicroLabelItem("Cafe", "a"), MicroLabelItem("Library", "C"))
        // 判分与高亮同口径：忽略大小写归一比对
        assertTrue(microMapLabelCorrect(mapOf(0 to "A", 1 to "c"), items))
        assertFalse(microMapLabelCorrect(mapOf(0 to "B", 1 to "C"), items))
    }

    @Test
    fun dominantSkillCountsSelfScoredSpeakingAndWritingNodes() {
        // 主导技能按整卡节点投票：口语跟读/引导写作等自带流程节点也计入维度倾向
        val speakingCard = listOf<MicroNode>(
            MicroNode.Shadowing(text = "t", translation = ""),
            MicroNode.Monologue(prompt = "p", scene = "", points = emptyList()),
            MicroNode.MinimalPair(prompt = "", audioText = "sheep", options = listOf("ship", "sheep"), answer = "sheep", ipa = emptyList(), explanation = "")
        )
        assertEquals("口语", microCardDominantSkill(speakingCard))
        val writingCard = listOf<MicroNode>(
            MicroNode.GuidedWriting(prompt = "p", steps = emptyList(), reference = ""),
            MicroNode.Translate(direction = "zh2en", source = "s", answer = "a", accept = emptyList(), hint = "")
        )
        assertEquals("写作", microCardDominantSkill(writingCard))
    }

    @Test
    fun revealExplanationPrefersModelText() {
        // 模型给了 explanation：核对后直接用它。
        val choice = MicroNode.Choice(
            prompt = "空1",
            options = listOf("go", "went"),
            answer = "went",
            multi = false,
            explanation = "过去时间状语 yesterday 要求一般过去时。"
        )
        assertEquals("过去时间状语 yesterday 要求一般过去时。", microRevealExplanationText(choice))
    }

    @Test
    fun revealExplanationFallsBackForGradableWhenModelOmitsIt() {
        // 模型没给 explanation 的可判分题（含无 explanation 字段的填空类）：核对后必须仍有一段非空解析（修复「核对答案没有解析」）。
        val choiceNoExpl = MicroNode.Choice(
            prompt = "空1",
            options = listOf("go", "went"),
            answer = "went",
            multi = false,
            explanation = ""
        )
        assertTrue(microRevealExplanationText(choiceNoExpl).isNotBlank())
        val openCloze = MicroNode.OpenCloze(prompt = "", text = "I ___ home.", answers = listOf("go"))
        assertTrue(microRevealExplanationText(openCloze).isNotBlank())
        val translate = MicroNode.Translate(direction = "zh2en", source = "我回家。", answer = "I go home.", accept = emptyList(), hint = "")
        assertTrue(microRevealExplanationText(translate).isNotBlank())
        // OddOneOut 此前漏在兜底之外：模型没给 explanation 时核对后没有解析。
        val oddOneOut = MicroNode.OddOneOut(prompt = "选出不同类", items = listOf("cat", "dog", "car"), answer = "car", explanation = "")
        assertTrue(microRevealExplanationText(oddOneOut).isNotBlank())
    }

    @Test
    fun reviewInfoKeepsModelExplanationForInputAndSpellingFamily() {
        // 修「错题本条目丢解析」：这些题型的 explanation 字段此前没被抽进错题本。
        val input = MicroNode.Input(prompt = "填空", answer = "went", multiline = false, explanation = "过去时。")
        assertEquals("过去时。", microNodeReviewInfo(input)?.explanation)
        val dictation = MicroNode.Dictation(text = "I go home.", hint = "", explanation = "注意连读。")
        assertEquals("注意连读。", microNodeReviewInfo(dictation)?.explanation)
        val spelling = MicroNode.SpellingBee(word = "banana", hint = "水果", example = "", explanation = "双 n。")
        assertEquals("双 n。", microNodeReviewInfo(spelling)?.explanation)
        val scramble = MicroNode.WordScramble(word = "cat", hint = "动物", scrambled = listOf("t", "a", "c"), explanation = "c-a-t。")
        assertEquals("c-a-t。", microNodeReviewInfo(scramble)?.explanation)
        val hangman = MicroNode.Hangman(word = "dog", hint = "动物", maxWrong = 6, explanation = "常见词。")
        assertEquals("常见词。", microNodeReviewInfo(hangman)?.explanation)
    }

    @Test
    fun parsesExplanationForFillAndListeningTypesAndSurfacesIt() {
        // 填空/听力类现在也带 explanation 字段：解析后核对能显示模型给的真·解析。
        val json = """
            {"title":"听力填空","nodes":[
              {"type":"open_cloze","text":"I ___ home.","answers":["go"],"explanation":"一般现在时，主语 I 用动词原形 go。"},
              {"type":"listen_cloze","audioText":"I go home.","text":"I ___ home.","answers":["go"],"explanation":"听到的实义动词是 go。"}
            ]}
        """.trimIndent()
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val oc = card.nodes.filterIsInstance<MicroNode.OpenCloze>().first()
        assertEquals("一般现在时，主语 I 用动词原形 go。", oc.explanation)
        assertEquals("一般现在时，主语 I 用动词原形 go。", microRevealExplanationText(oc))
        val lc = card.nodes.filterIsInstance<MicroNode.ListenCloze>().first()
        assertEquals("听到的实义动词是 go。", microRevealExplanationText(lc))
    }

    @Test
    fun revealExplanationBlankForNonGradableNodes() {
        // 纯展示/自带流程微元：核对后不展示统一解析。
        assertEquals("", microRevealExplanationText(MicroNode.Text(text = "标题", role = "title")))
        assertEquals("", microRevealExplanationText(MicroNode.Flashcard(prompt = "", cards = emptyList())))
    }

    @Test
    fun clozeFamilyWrongAnswersEnterReviewBook() {
        // 修复「完形填空核对答案后错题没进错题本」：完形/填空家族必须能抽成错题本条目。
        val clozeSelect = MicroNode.ClozeSelect(
            text = "I ___ to school ___ .",
            blanks = listOf(
                MicroClozeSelectBlank(options = listOf("go", "went"), answer = "went"),
                MicroClozeSelectBlank(options = listOf("yesterday", "tomorrow"), answer = "yesterday")
            ),
            explanation = "过去时。"
        )
        val cs = microNodeReviewInfo(clozeSelect)
        assertNotNull(cs)
        requireNotNull(cs)
        assertEquals("cloze_select", cs.componentType)
        assertEquals("went | yesterday", cs.answer)
        assertTrue(cs.options.isEmpty()) // 多空不给选项池，复习走看答案自评
        assertEquals("过去时。", cs.explanation)

        val clozeDrag = MicroNode.ClozeDrag(text = "She ___ happy.", bank = listOf("is", "are"), answers = listOf("is"))
        val cd = microNodeReviewInfo(clozeDrag)
        requireNotNull(cd)
        assertEquals("cloze_drag", cd.componentType)
        assertEquals("is", cd.answer)
        assertEquals(listOf("is", "are"), cd.options) // 单空保留选项池，复习可点选

        val openCloze = MicroNode.OpenCloze(prompt = "填空", text = "I ___ home.", answers = listOf("go"))
        val oc = microNodeReviewInfo(openCloze)
        requireNotNull(oc)
        assertEquals("open_cloze", oc.componentType)
        assertEquals("go", oc.answer)
        assertTrue(oc.prompt.contains("I ___ home."))

        val listenFill = MicroNode.ListenFill(audioText = "I go home.", text = "I ___ home.", bank = listOf("go", "went"), answers = listOf("go"))
        val lf = microNodeReviewInfo(listenFill)
        requireNotNull(lf)
        assertEquals("listen_fill", lf.componentType)
        assertEquals("go", lf.answer)

        val listenCloze = MicroNode.ListenCloze(audioText = "I go home.", text = "I ___ home.", answers = listOf("go"))
        val lc = microNodeReviewInfo(listenCloze)
        requireNotNull(lc)
        assertEquals("listen_cloze", lc.componentType)
        assertEquals("go", lc.answer)
    }

    @Test
    fun structuredGradableTypesAlsoEnterReviewBook() {
        // 其余此前被跳过的可判分题型也要能进错题本（降解成题面+文字正解）。
        val fillTable = MicroNode.FillTable(
            title = "词形表",
            headers = listOf("原形", "过去式"),
            rows = listOf(listOf("go", "went"), listOf("eat", "ate")),
            blanks = listOf(MicroCellCoord(0, 1), MicroCellCoord(1, 1)),
            bank = listOf("went", "ate")
        )
        val ft = microNodeReviewInfo(fillTable)
        requireNotNull(ft)
        assertEquals("fill_table", ft.componentType)
        assertEquals("went；ate", ft.answer)

        val reorder = MicroNode.ReorderParagraph(prompt = "", sentences = listOf("First.", "Then."))
        val ro = microNodeReviewInfo(reorder)
        requireNotNull(ro)
        assertEquals("First. | Then.", ro.answer)

        val proof = MicroNode.ProofParagraph(
            prompt = "",
            lines = listOf(
                MicroProofLine(text = "She go home.", answer = "She goes home.", note = "三单"),
                MicroProofLine(text = "I am fine.", answer = "I am fine.", note = "")
            )
        )
        val pp = microNodeReviewInfo(proof)
        requireNotNull(pp)
        assertEquals("She go home. → She goes home.", pp.answer)
        assertTrue(pp.prompt.contains("She go home."))

        val stress = MicroNode.StressMark(word = "banana", syllables = listOf("ba", "na", "na"), stress = 2)
        val sm = microNodeReviewInfo(stress)
        requireNotNull(sm)
        assertEquals("na", sm.answer)
        assertEquals(listOf("ba", "na", "na"), sm.options) // 音节做选项池，复习可点选

        val match = MicroNode.Match(prompt = "", pairs = listOf(MicroMatchPair("cat", "猫"), MicroMatchPair("dog", "狗")))
        val mt = microNodeReviewInfo(match)
        requireNotNull(mt)
        assertEquals("cat → 猫；dog → 狗", mt.answer)
    }
}

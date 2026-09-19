package com.c0d3c.listene

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test

/**
 * 真机渲染验证：微元引擎 MicroCardView 能把“AI 组合出的微元卡”渲染出来并完成 点选→核对 流程。
 * 覆盖 choice（完形式）与 tokens（点选找错）两类核心交互。
 */
class MicroCardRenderInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun microChoiceCardRendersAndChecks() {
        val card = MicroCard(
            title = "完形(微元)",
            nodes = listOf(
                MicroNode.Text("选出最合适的词", "hint"),
                MicroNode.Passage("I ___ to school yesterday."),
                MicroNode.Choice(prompt = "空1", options = listOf("go", "went", "gone"), answer = "went", multi = false)
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_choice") } }
        composeRule.onNodeWithText("完形(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("went").assertIsDisplayed()
        composeRule.onNodeWithText("went").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microMatchCardRendersAndChecks() {
        val card = MicroCard(
            title = "连线(微元)",
            nodes = listOf(
                MicroNode.Match(
                    prompt = "把单词和词义配对",
                    pairs = listOf(
                        MicroMatchPair("apple", "苹果"),
                        MicroMatchPair("banana", "香蕉")
                    )
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_match") } }
        composeRule.onNodeWithText("连线(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("apple").assertIsDisplayed()
        // 右项池每行共享 → "苹果" 出现多次，点首个即可；作答后核对。
        composeRule.onAllNodesWithText("苹果")[0].performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microCategorizeCardRendersAndChecks() {
        val card = MicroCard(
            title = "归类(微元)",
            nodes = listOf(
                MicroNode.Categorize(
                    prompt = "按词性归类",
                    categories = listOf(
                        MicroCategory("动词", listOf("run", "eat")),
                        MicroCategory("名词", listOf("cat"))
                    )
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_categorize") } }
        composeRule.onNodeWithText("归类(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("run").assertIsDisplayed()
        composeRule.onAllNodesWithText("动词")[0].performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microFlashcardCardRendersAndFlips() {
        val card = MicroCard(
            title = "翻卡(微元)",
            nodes = listOf(
                MicroNode.Flashcard(prompt = "记单词", cards = listOf(MicroFlashItem("apple", "苹果")))
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_flash") } }
        composeRule.onNodeWithText("翻卡(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("apple").assertIsDisplayed()
        composeRule.onNodeWithText("apple").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("苹果").assertIsDisplayed()
    }

    @Test
    fun microAudioCardRendersPlayAndChecks() {
        val card = MicroCard(
            title = "听力(微元)",
            nodes = listOf(
                MicroNode.Dictation(text = "hello world", hint = "问候"),
                MicroNode.AudioChoice(audioText = "sheep", prompt = "你听到的是？", options = listOf("ship", "sheep"), answer = "sheep")
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_audio") } }
        composeRule.onNodeWithText("听力(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("播放").assertIsDisplayed()
        composeRule.onNodeWithText("播放并选择").assertIsDisplayed()
        composeRule.onNodeWithText("sheep").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microSpeakScoreCardRenders() {
        val card = MicroCard(
            title = "跟读(微元)",
            nodes = listOf(MicroNode.SpeakScore(text = "Read this aloud please.", prompts = emptyList()))
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_speak") } }
        composeRule.onNodeWithText("跟读(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("Read this aloud please.").assertIsDisplayed()
    }

    @Test
    fun microRoleplayTurnCardRendersSceneAndOpening() {
        val card = MicroCard(
            title = "情景对话(微元)",
            nodes = listOf(
                MicroNode.RoleplayTurn(
                    scenario = "你是咖啡店店员，我来点单。",
                    opening = "Hi! What can I get for you today?",
                    goal = "点一杯拿铁",
                    turns = 4
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_roleplay") } }
        composeRule.onNodeWithText("情景对话(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("Hi! What can I get for you today?").assertIsDisplayed()
        composeRule.onNodeWithText("发送").assertIsDisplayed()
    }

    @Test
    fun microAiHintCardRevealsHintsThenAnswer() {
        val card = MicroCard(
            title = "渐进提示(微元)",
            nodes = listOf(
                MicroNode.AiHint(
                    prompt = "翻译：我昨天去了学校。",
                    hints = listOf("用一般过去时", "go 的过去式是 went"),
                    answer = "I went to school yesterday."
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_hint") } }
        composeRule.onNodeWithText("渐进提示(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("看提示（1/2）").performClick()
        composeRule.onNodeWithText("💡 提示 1：用一般过去时").assertIsDisplayed()
        composeRule.onNodeWithText("看提示（2/2）").performClick()
        composeRule.onNodeWithText("💡 提示 2：go 的过去式是 went").assertIsDisplayed()
        composeRule.onNodeWithText("看答案").performClick()
        composeRule.onNodeWithText("I went to school yesterday.").assertIsDisplayed()
    }

    @Test
    fun microHighlightSpanCardRendersAndChecks() {
        val card = MicroCard(
            title = "框选(微元)",
            nodes = listOf(
                MicroNode.HighlightSpan(
                    prompt = "选出所有动词",
                    text = "I run and she eats.",
                    answers = listOf("run", "eats"),
                    explanation = ""
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_highlight") } }
        composeRule.onNodeWithText("框选(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("选出所有动词").assertIsDisplayed()
        composeRule.onNodeWithText("run").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microTimedChallengeCardRenders() {
        // 冻结虚拟时钟，避免倒计时在测试中自动跑到超时。
        composeRule.mainClock.autoAdvance = false
        val card = MicroCard(
            title = "限时(微元)",
            nodes = listOf(
                MicroNode.TimedChallenge(prompt = "2 + 2 = ?", options = listOf("3", "4", "5"), answer = "4", seconds = 30)
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_timed") } }
        composeRule.onNodeWithText("限时(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("2 + 2 = ?").assertIsDisplayed()
        composeRule.onNodeWithText("4").assertIsDisplayed()
    }

    @Test
    fun microTableCardRenders() {
        val card = MicroCard(
            title = "表格(微元)",
            nodes = listOf(
                MicroNode.Table(
                    title = "动词变化",
                    headers = listOf("原形", "过去式"),
                    rows = listOf(listOf("go", "went"), listOf("eat", "ate"))
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_table") } }
        composeRule.onNodeWithText("表格(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("动词变化").assertIsDisplayed()
        composeRule.onNodeWithText("原形").assertIsDisplayed()
        composeRule.onNodeWithText("went").assertIsDisplayed()
    }

    @Test
    fun microSentenceDiagramCardRendersAndChecks() {
        val card = MicroCard(
            title = "成分(微元)",
            nodes = listOf(
                MicroNode.SentenceDiagram(
                    prompt = "给每块选成分",
                    sentence = "She reads books.",
                    labels = listOf("主语", "谓语", "宾语"),
                    items = listOf(
                        MicroDiagramItem("She", "主语"),
                        MicroDiagramItem("reads", "谓语"),
                        MicroDiagramItem("books", "宾语")
                    )
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_diagram") } }
        composeRule.onNodeWithText("成分(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("She").assertIsDisplayed()
        // 标签池每块共享 → "主语" 出现多次，点首个给第一块作答；再核对。
        composeRule.onAllNodesWithText("主语")[0].performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microWordScrambleCardRendersAndChecks() {
        val card = MicroCard(
            title = "重组(微元)",
            nodes = listOf(
                MicroNode.WordScramble(word = "cat", hint = "一种宠物", scrambled = listOf("t", "a", "c"))
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_scramble") } }
        composeRule.onNodeWithText("重组(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("提示：一种宠物").assertIsDisplayed()
        // 按 c→a→t 顺序点字母拼出目标词，再核对。
        composeRule.onNodeWithText("c").performClick()
        composeRule.onNodeWithText("a").performClick()
        composeRule.onNodeWithText("t").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microTrueFalseCardRendersAndChecks() {
        val card = MicroCard(
            title = "判断(微元)",
            nodes = listOf(
                MicroNode.TrueFalse(
                    prompt = "判断对错",
                    statements = listOf(
                        MicroTrueFalseItem("The sun rises in the east.", true),
                        MicroTrueFalseItem("Cats can fly.", false)
                    )
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_tf") } }
        composeRule.onNodeWithText("判断(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("1. The sun rises in the east.").assertIsDisplayed()
        // 每句共享「对/错」→ "对" 出现多次，点首个给第一句作答；再核对。
        composeRule.onAllNodesWithText("对")[0].performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microFillTableCardRendersAndChecks() {
        val card = MicroCard(
            title = "填表(微元)",
            nodes = listOf(
                MicroNode.FillTable(
                    title = "动词变化",
                    headers = listOf("原形", "过去式"),
                    rows = listOf(listOf("go", "went"), listOf("eat", "ate")),
                    blanks = listOf(MicroCellCoord(0, 1), MicroCellCoord(1, 1)),
                    bank = listOf("went", "ate")
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_filltable") } }
        composeRule.onNodeWithText("填表(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("动词变化").assertIsDisplayed()
        composeRule.onNodeWithText("原形").assertIsDisplayed()
        composeRule.onNodeWithText("go").assertIsDisplayed()
        // 从词库点词填入下一个空位（按 blanks 顺序），再核对。
        composeRule.onNodeWithText("went").performClick()
        composeRule.onNodeWithText("ate").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microPairsMemoryCardRendersAndFlips() {
        val card = MicroCard(
            title = "翻牌(微元)",
            nodes = listOf(
                MicroNode.PairsMemory(
                    prompt = "翻牌配对",
                    pairs = listOf(MicroMatchPair("apple", "苹果"), MicroMatchPair("banana", "香蕉"))
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_pm") } }
        composeRule.onNodeWithText("翻牌(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("翻牌配对").assertIsDisplayed()
        // 4 张盖牌（2 对 × 2）
        composeRule.onAllNodesWithText("?").assertCountEquals(4)
        // 翻开一张 → 盖牌减少到 3（翻开的牌显示其文本）
        composeRule.onAllNodesWithText("?")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("?").assertCountEquals(3)
    }

    @Test
    fun microClozeSelectCardRendersAndChecks() {
        val card = MicroCard(
            title = "完形(微元)",
            nodes = listOf(
                MicroNode.ClozeSelect(
                    text = "Yesterday I ___ to school and ___ my homework.",
                    blanks = listOf(
                        MicroClozeSelectBlank(options = listOf("go", "went", "gone"), answer = "went"),
                        MicroClozeSelectBlank(options = listOf("do", "did", "done"), answer = "did")
                    )
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_clozesel") } }
        composeRule.onNodeWithText("完形(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("空 1").assertIsDisplayed()
        // 逐空点选独立选项（各空选项互不相同，唯一可点），再核对。
        composeRule.onNodeWithText("went").performClick()
        composeRule.onNodeWithText("did").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microListenFillCardRendersPlayAndChecks() {
        val card = MicroCard(
            title = "听力填空(微元)",
            nodes = listOf(
                MicroNode.ListenFill(
                    audioText = "I went home.",
                    text = "I ___ home.",
                    bank = listOf("went", "go"),
                    answers = listOf("went")
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_listenfill") } }
        composeRule.onNodeWithText("听力填空(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("播放句子").assertIsDisplayed()
        // 点词库填缺词，再核对。
        composeRule.onNodeWithText("went").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microWordFormationCardRendersAndChecks() {
        val card = MicroCard(
            title = "词形(微元)",
            nodes = listOf(
                MicroNode.WordFormation(
                    prompt = "写出词形",
                    items = listOf(
                        MicroWordFormItem("happy", "名词", "happiness"),
                        MicroWordFormItem("care", "形容词", "careful")
                    ),
                    bank = listOf("happiness", "careful", "happily", "careless")
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_wordform") } }
        composeRule.onNodeWithText("词形(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("写出词形").assertIsDisplayed()
        // 从词库点填每项派生词，再核对。
        composeRule.onNodeWithText("happiness").performClick()
        composeRule.onNodeWithText("careful").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microTimelineCardRenders() {
        val card = MicroCard(
            title = "时间线(微元)",
            nodes = listOf(
                MicroNode.Timeline(
                    title = "学习路线",
                    events = listOf(
                        MicroTimelineEvent("第1周", "入门基础", "学音标"),
                        MicroTimelineEvent("第2周", "扩展词汇", "")
                    )
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_timeline") } }
        composeRule.onNodeWithText("时间线(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("学习路线").assertIsDisplayed()
        composeRule.onNodeWithText("第1周").assertIsDisplayed()
        composeRule.onNodeWithText("入门基础").assertIsDisplayed()
        composeRule.onNodeWithText("学音标").assertIsDisplayed()
    }

    @Test
    fun microStressMarkCardRendersAndChecks() {
        val card = MicroCard(
            title = "重音(微元)",
            nodes = listOf(
                MicroNode.StressMark(word = "computer", syllables = listOf("com", "pu", "ter"), stress = 2)
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_stress") } }
        composeRule.onNodeWithText("重音(微元)").assertIsDisplayed()
        // 点出重读音节（第 2 个 pu），再核对。
        composeRule.onNodeWithText("pu").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microClozeDragRendersAndChecks() {
        val card = MicroCard(
            title = "填空(微元)",
            nodes = listOf(
                MicroNode.ClozeDrag(text = "I ___ home.", bank = listOf("went", "go"), answers = listOf("went"))
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_cloze") } }
        composeRule.onNodeWithText("填空(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("went").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microChartWritingCardRendersChartAndWritingBox() {
        val card = MicroCard(
            title = "图表作文(微元)",
            nodes = listOf(
                MicroNode.Chart(
                    title = "Sales",
                    chartType = "bar",
                    categories = listOf("2019", "2020"),
                    seriesNames = listOf("A"),
                    seriesValues = listOf(listOf(10f), listOf(20f))
                ),
                MicroNode.Writing(prompt = "Describe the chart.", reference = "2019: 10")
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_chart_writing") } }
        composeRule.onNodeWithText("图表作文(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("2019").assertIsDisplayed()
        composeRule.onNodeWithText("Describe the chart.").assertIsDisplayed()
        // writing 微元自带 AI 评分按钮（非统一“核对答案”流程）。
        composeRule.onNodeWithText("AI 评分").assertIsDisplayed()
    }

    @Test
    fun microMaterialNodesRenderProgressBarAndHiddenTranscript() {
        // 批2：材料类下沉到微元 —— 进度条微元 + 原文默认隐藏（reveal），点开才显示。
        val card = MicroCard(
            title = "材料(微元)",
            nodes = listOf(
                MicroNode.Progress(label = "1 / 2", value = 0.5f),
                MicroNode.Reveal(label = "查看原文", content = "Secret transcript body.")
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_material") } }
        composeRule.onNodeWithText("进度").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
        composeRule.onNodeWithText("查看原文").assertIsDisplayed()
        // 作答前不剧透原文。
        composeRule.onNodeWithText("Secret transcript body.").assertDoesNotExist()
        composeRule.onNodeWithText("查看原文").performClick()
        composeRule.onNodeWithText("Secret transcript body.").assertIsDisplayed()
    }

    @Test
    fun microSentenceTranscriptRendersPlayerAndClickableSentences() {
        // 批3：逐句点播下沉到微元 —— 渲染片段播放面 + 可点句子原文（不点播放，避免依赖真实音频）。
        val groups = listOf(
            AgentParagraphSentenceGroup(
                id = "p0",
                title = "Speaker A",
                text = "Hello there.",
                sentences = listOf(
                    AgentTranscriptClip(
                        id = "c0",
                        scope = AgentTranscriptClipScope.Sentences,
                        speakerName = "Speaker A",
                        text = "Hello there.",
                        startRatio = 0f,
                        endRatio = 1f,
                        turnIndex = 0,
                        sentenceIndex = 0,
                        precise = false
                    )
                )
            )
        )
        val card = MicroCard(
            title = "逐句点播(微元)",
            nodes = listOf(MicroNode.SentenceTranscript(audioUrl = "", groups = groups))
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_sentence") } }
        composeRule.onNodeWithText("逐句点播").assertIsDisplayed()
        composeRule.onNodeWithText("完整原文").assertIsDisplayed()
        composeRule.onNodeWithText("Hello there.").assertIsDisplayed()
    }

    @Test
    fun microQuestionPreviewRendersWithInjectedRuntimeContext() {
        // 批3：题目控件下沉到微元 —— record/viewModel/回调经 CompositionLocal 注入后，题目+选项可渲染。
        val record = HistoryRecord(
            id = "qp_it",
            scene = "cafe",
            createdAt = 0L,
            content = ListeningContent(
                title = "Cafe",
                script = "A: Hi.",
                questions = listOf(Question("What did A say?", listOf("Hi", "Bye"), 0, "greeting")),
                audioUrl = null
            )
        )
        val card = MicroCard(
            title = "题目(微元)",
            nodes = listOf(MicroNode.QuestionPreview(fallbackText = ""))
        )
        val viewModel = ListeningViewModel()
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAgentMicroRecord provides record,
                    LocalAgentMicroViewModel provides viewModel,
                    LocalAgentMicroOnRequestAiReview provides {}
                ) {
                    MicroCardView(card, instanceKey = "it_micro_qp")
                }
            }
        }
        composeRule.onNodeWithText("1. What did A say?").assertIsDisplayed()
        composeRule.onNodeWithText("Hi").assertIsDisplayed()
        composeRule.onNodeWithText("Bye").assertIsDisplayed()
    }

    @Test
    fun microFeedbackRendersWithInjectedRuntimeContext() {
        // 批3：反馈控件下沉到微元 —— 已批改且无分析结果时，显示「交给 AI 分析」（依赖注入的 record + 回调）。
        val record = HistoryRecord(
            id = "fb_it",
            scene = "cafe",
            createdAt = 0L,
            content = ListeningContent(
                title = "Cafe",
                script = "A: Hi.",
                questions = listOf(Question("Q1", listOf("a", "b"), 0)),
                audioUrl = null
            ),
            selectedAnswers = mapOf(0 to 0),
            answersRevealed = true
        )
        val card = MicroCard(
            title = "反馈(微元)",
            nodes = listOf(MicroNode.Feedback(fallbackText = ""))
        )
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAgentMicroRecord provides record,
                    LocalAgentMicroOnRequestAiReview provides {}
                ) {
                    MicroCardView(card, instanceKey = "it_micro_fb")
                }
            }
        }
        composeRule.onNodeWithText("反馈控件").assertIsDisplayed()
        composeRule.onNodeWithText("交给 AI 分析").assertIsDisplayed()
    }

    @Test
    fun microReorderParagraphCardRendersAndChecks() {
        val card = MicroCard(
            title = "语篇排序(微元)",
            nodes = listOf(
                MicroNode.ReorderParagraph(
                    prompt = "排成连贯段落",
                    sentences = listOf(
                        "First, I woke up early.",
                        "Then I had breakfast.",
                        "Finally I went to school."
                    )
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_reorder") } }
        composeRule.onNodeWithText("语篇排序(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("排成连贯段落").assertIsDisplayed()
        // 句块打乱展示，按正确顺序点选（点后移入「已排」区、文本变为带序号，故按原句文本唯一可点）。
        composeRule.onNodeWithText("First, I woke up early.").performClick()
        composeRule.onNodeWithText("Then I had breakfast.").performClick()
        composeRule.onNodeWithText("Finally I went to school.").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microOddOneOutCardRendersAndChecks() {
        val card = MicroCard(
            title = "选异类(微元)",
            nodes = listOf(
                MicroNode.OddOneOut(
                    prompt = "选出不属于同类的一个",
                    items = listOf("apple", "banana", "car", "orange"),
                    answer = "car",
                    explanation = "其余都是水果"
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_oddoneout") } }
        composeRule.onNodeWithText("选异类(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("选出不属于同类的一个").assertIsDisplayed()
        // 点出异类 car，再核对。
        composeRule.onNodeWithText("car").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microRankOrderCardRendersAndChecks() {
        val card = MicroCard(
            title = "程度排序(微元)",
            nodes = listOf(
                MicroNode.RankOrder(
                    prompt = "按从冷到热排列",
                    items = listOf("freezing", "cold", "warm", "hot"),
                    from = "最冷",
                    to = "最热",
                    explanation = "温度由低到高"
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_rankorder") } }
        composeRule.onNodeWithText("程度排序(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("按从冷到热排列").assertIsDisplayed()
        // 词块打乱展示，按 低→高 正确顺序点选（点后移入「已排」区、文本变为带序号，故按原词唯一可点）。
        composeRule.onNodeWithText("freezing").performClick()
        composeRule.onNodeWithText("cold").performClick()
        composeRule.onNodeWithText("warm").performClick()
        composeRule.onNodeWithText("hot").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microSpellingBeeCardRendersAndChecks() {
        val card = MicroCard(
            title = "听音拼写(微元)",
            nodes = listOf(
                MicroNode.SpellingBee(word = "cat", hint = "一种宠物：猫", example = "The ___ is sleeping.")
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_spellingbee") } }
        composeRule.onNodeWithText("听音拼写(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("播放单词").assertIsDisplayed()
        composeRule.onNodeWithText("逐字母").assertIsDisplayed()
        // 在拼写输入框输入正确单词，再核对 → 应进入已揭示态。
        composeRule.onNode(hasSetTextAction()).performTextInput("cat")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microSentenceTransformCardRendersAndChecks() {
        val card = MicroCard(
            title = "句型转换(微元)",
            nodes = listOf(
                MicroNode.SentenceTransform(
                    prompt = "改为被动语态",
                    source = "He wrote the book.",
                    answer = "The book was written by him.",
                    accept = emptyList(),
                    hint = "把宾语提前作主语"
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_sentransform") } }
        composeRule.onNodeWithText("句型转换(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("原句：He wrote the book.").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("The book was written by him.")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microOpenClozeCardRendersAndChecks() {
        val card = MicroCard(
            title = "开放式填空(微元)",
            nodes = listOf(
                MicroNode.OpenCloze(prompt = "用适当的介词填空", text = "I am good ___ English.", answers = listOf("at"))
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_opencloze") } }
        composeRule.onNodeWithText("开放式填空(微元)").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("at")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microTranslateCardRendersAndChecks() {
        val card = MicroCard(
            title = "翻译(微元)",
            nodes = listOf(
                MicroNode.Translate(
                    direction = "zh2en",
                    source = "我每天步行去上学。",
                    answer = "I walk to school every day.",
                    accept = emptyList(),
                    hint = "walk to ..."
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_translate") } }
        composeRule.onNodeWithText("翻译(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("我每天步行去上学。").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("I walk to school every day.")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microTfngCardRendersAndChecks() {
        val card = MicroCard(
            title = "判断三态(微元)",
            nodes = listOf(
                MicroNode.Tfng(
                    prompt = "根据短文判断",
                    statements = listOf(
                        MicroTfngItem("The sun rises in the east.", "true"),
                        MicroTfngItem("The author has three cats.", "not_given")
                    )
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_tfng") } }
        composeRule.onNodeWithText("判断三态(微元)").assertIsDisplayed()
        // 每句三个选项 → "未提及" 应出现 2 次（两句各一）。
        composeRule.onAllNodesWithText("未提及").assertCountEquals(2)
        composeRule.onAllNodesWithText("正确")[0].performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microListenClozeCardRendersAndChecks() {
        val card = MicroCard(
            title = "听力填空(微元)",
            nodes = listOf(
                MicroNode.ListenCloze(audioText = "I go to school by bus.", text = "I go to school by ___.", answers = listOf("bus"))
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_listencloze") } }
        composeRule.onNodeWithText("听力填空(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("播放句子").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("bus")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microErrorCorrectionCardRendersAndChecks() {
        val card = MicroCard(
            title = "句子改错(微元)",
            nodes = listOf(
                MicroNode.ErrorCorrection(
                    prompt = "时态错误",
                    sentence = "She go to school yesterday.",
                    answer = "She went to school yesterday.",
                    accept = emptyList(),
                    explanation = "过去时间状语用过去式 went"
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_errcorrect") } }
        composeRule.onNodeWithText("句子改错(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("原句：She go to school yesterday.").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("She went to school yesterday.")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microDialogueCompleteCardRendersAndChecks() {
        val card = MicroCard(
            title = "补全对话(微元)",
            nodes = listOf(
                MicroNode.DialogueComplete(
                    prompt = "在咖啡店",
                    turns = listOf(MicroDialogueTurn("Clerk", "What can I get for you?")),
                    options = listOf("A latte, please.", "See you tomorrow."),
                    answer = "A latte, please.",
                    explanation = "点单场景"
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_dialogue") } }
        composeRule.onNodeWithText("补全对话(微元)").assertIsDisplayed()
        composeRule.onNodeWithText("What can I get for you?").assertIsDisplayed()
        composeRule.onNodeWithText("A latte, please.").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microWordSearchCardRendersAndChecks() {
        val card = MicroCard(
            title = "单词找词(微元)",
            nodes = listOf(
                MicroNode.WordSearch(
                    prompt = "找出 CAT",
                    grid = listOf(
                        listOf("C", "A", "T"),
                        listOf("X", "Y", "Z"),
                        listOf("P", "Q", "R")
                    ),
                    words = listOf("CAT")
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_wordsearch") } }
        composeRule.onNodeWithText("单词找词(微元)").assertIsDisplayed()
        // 点首字母格(0,0)=C，再点尾字母格(0,2)=T → 命中 CAT。
        composeRule.onNodeWithTag("ws_0_0").performClick()
        composeRule.onNodeWithTag("ws_0_2").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microHangmanCardRendersAndChecks() {
        val card = MicroCard(
            title = "猜词游戏(微元)",
            nodes = listOf(
                MicroNode.Hangman(word = "GO", hint = "去", maxWrong = 6)
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_hangman") } }
        composeRule.onNodeWithText("猜词游戏(微元)").assertIsDisplayed()
        // 猜出 G、O 两个字母 → 拼出 GO。
        composeRule.onNodeWithTag("hm_G").performClick()
        composeRule.onNodeWithTag("hm_O").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microProofParagraphCardRendersAndChecks() {
        val card = MicroCard(
            title = "短文改错(微元)",
            nodes = listOf(
                MicroNode.ProofParagraph(
                    prompt = "改正错误",
                    lines = listOf(MicroProofLine("She go home.", "She goes home.", "第三人称单数"))
                )
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_proof") } }
        composeRule.onNodeWithText("短文改错(微元)").assertIsDisplayed()
        // 单行改错框预填原句「She go home.」，替换为正确句。
        composeRule.onNode(hasSetTextAction()).performTextReplacement("She goes home.")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }

    @Test
    fun microTokensFindErrorRendersAndChecks() {
        val card = MicroCard(
            title = "改错(微元)",
            nodes = listOf(
                MicroNode.Tokens(text = "She go to school.", correct = "She goes to school.", explanation = "第三人称单数", errors = emptyList())
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_micro_tokens") } }
        composeRule.onNodeWithText("go").assertIsDisplayed()
        composeRule.onNodeWithText("go").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("重新作答").assertIsDisplayed()
    }
}

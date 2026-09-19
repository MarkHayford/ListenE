package com.c0d3c.listene

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class AgentCardSpecJsonTest {


    @Test
    fun filePreviewActionsUseVisibleTwoByTwoLayout() {
        assertEquals(
            listOf(
                listOf("打开", "下载"),
                listOf("导出", "分享")
            ),
            agentFilePreviewActionLayoutLabels()
        )
    }







    @Test
    fun questionSetDisplayPayloadDropsQuestionsWithOutOfRangeCorrectAnswer() {
        val component = agentCardComponent(
            AgentCardComponent.QuestionSet,
            questions = listOf(
                Question(
                    questionText = "What does gate mean?",
                    options = listOf("A place to board", "A bag"),
                    correctAnswer = 4
                ),
                Question(
                    questionText = "What does luggage mean?",
                    options = listOf("A ticket", "Bags for travel"),
                    correctAnswer = 1
                )
            )
        )

        val questions = AgentCardDisplayPayload.questions(component)

        assertEquals(1, questions.size)
        assertEquals("What does luggage mean?", questions.single().questionText)
    }









    @Test
    fun appendedChatScrollsTextButKeepsGeneratedCardsAtTopByDefault() {
        assertTrue(
            agentShouldScrollForAppendedChat(
                role = AgentChatRole.User,
                hasCardPayload = false,
                attachments = emptyList(),
                explicitScrollToBottom = false
            )
        )
        assertTrue(
            agentShouldScrollForAppendedChat(
                role = AgentChatRole.Agent,
                hasCardPayload = false,
                attachments = emptyList(),
                explicitScrollToBottom = false
            )
        )
        assertFalse(
            agentShouldScrollForAppendedChat(
                role = AgentChatRole.Agent,
                hasCardPayload = true,
                attachments = emptyList(),
                explicitScrollToBottom = false
            )
        )
        assertTrue(
            agentShouldScrollForAppendedChat(
                role = AgentChatRole.Agent,
                hasCardPayload = true,
                attachments = emptyList(),
                explicitScrollToBottom = true
            )
        )
        assertTrue(
            agentShouldAnchorAppendedCardAtTop(
                role = AgentChatRole.Agent,
                hasCardPayload = true,
                explicitScrollToBottom = false
            )
        )
        assertFalse(
            agentShouldAnchorAppendedCardAtTop(
                role = AgentChatRole.Agent,
                hasCardPayload = true,
                explicitScrollToBottom = true
            )
        )
    }

    @Test
    fun imeOnlyScrollsChatWhenComposerHasFocus() {
        assertTrue(agentShouldAutoScrollOnIme(imeVisible = true, chatInputFocused = true))
        assertFalse(agentShouldAutoScrollOnIme(imeVisible = true, chatInputFocused = false))
        assertFalse(agentShouldAutoScrollOnIme(imeVisible = false, chatInputFocused = true))
    }

    @Test
    fun chatContentUsesSmallBottomSpacerBecauseComposerOwnsLayoutSpace() {
        assertTrue(agentChatMessageBottomSpacerDp() >= 160)
    }

    @Test
    fun materialGenerationIntroDoesNotClaimReadyBeforeAsyncRecordExists() {
        assertEquals(
            "素材生成中。完成后会自动显示音频、原文和题目。",
            agentMaterialGenerationIntroText(
                materialNeed = "A1 train station listening",
                introText = "A1火车站听力素材已生成，包含音频、原文和2道题目。"
            )
        )
        assertEquals(
            "素材生成中。完成后会自动显示音频、原文和题目。",
            agentMaterialGenerationIntroText(
                materialNeed = "A1 train station listening",
                introText = "The listening material is ready."
            )
        )
        assertEquals(
            "素材生成中。完成后会自动显示音频、原文和题目。",
            agentMaterialGenerationIntroText(
                materialNeed = "A1 train station listening",
                introText = "I will create it now."
            )
        )
    }


    @Test
    fun clozeAnswerSequenceParsesMultiBlankAnswersWithoutBreakingSingleChoice() {
        val multiBlank = agentCardComponent(
            AgentCardComponent.Cloze,
            text = "Maintaining healthy habits is ___. Eating a ___ diet helps. Regular ___ reduces stress.",
            options = listOf("exercise", "balanced", "essential"),
            answer = "essential | balanced | exercise"
        )
        assertEquals(
            listOf("essential", "balanced", "exercise"),
            AgentCardDisplayPayload.clozeAnswers(multiBlank)
        )

        val singleChoice = agentCardComponent(
            AgentCardComponent.Cloze,
            text = "I ___ already finished it.",
            options = listOf("have", "has"),
            answer = "have"
        )
        assertEquals(listOf("have"), AgentCardDisplayPayload.clozeAnswers(singleChoice))
        assertEquals(0, AgentCardDisplayPayload.answerIndex(singleChoice))
    }












    @Test
    fun interactivePracticeComponentsStartExpanded() {
        assertTrue(agentCardComponentStartsExpanded(AgentCardComponent.Cloze))
        assertTrue(agentCardComponentStartsExpanded(AgentCardComponent.ShortAnswer))
        assertTrue(agentCardComponentStartsExpanded(AgentCardComponent.SentenceBuilder))
        assertTrue(agentCardComponentStartsExpanded(AgentCardComponent.Ordering))
        assertTrue(agentCardComponentStartsExpanded(AgentCardComponent.QuestionSet))
        assertFalse(agentCardComponentStartsExpanded(AgentCardComponent.Summary))
        assertFalse(agentCardComponentStartsExpanded(AgentCardComponent.Transcript))
    }

    @Test
    fun emptyInteractiveComponentsStillRenderStatusContainer() {
        val emptyQuestionSet = agentCardComponent(AgentCardComponent.QuestionSet)
        val emptySentenceBuilder = agentCardComponent(AgentCardComponent.SentenceBuilder)
        val emptyOrdering = agentCardComponent(AgentCardComponent.Ordering)
        val emptySummary = agentCardComponent(AgentCardComponent.Summary)

        assertTrue(agentCardAtomBlockShouldRender(emptyQuestionSet))
        assertTrue(agentCardAtomBlockShouldRender(emptySentenceBuilder))
        assertTrue(agentCardAtomBlockShouldRender(emptyOrdering))
        assertFalse(agentCardAtomBlockShouldRender(emptySummary))
    }



    @Test
    fun collapsedTranscriptDoesNotRenderBodyText() {
        val transcript = agentCardComponent(
            AgentCardComponent.Transcript,
            text = "A: The answer is hidden until the learner expands the original text."
        )

        assertTrue(agentCardComponentUsesHiddenCollapsedBody(transcript))
        assertFalse(agentCardComponentStartsExpanded(AgentCardComponent.Transcript))
        assertFalse(
            agentCardCollapsedBodyRendersContent(
                isLong = true,
                expanded = false,
                hideLongContentWhenCollapsed = true
            )
        )
        assertTrue(
            agentCardCollapsedBodyRendersContent(
                isLong = true,
                expanded = true,
                hideLongContentWhenCollapsed = true
            )
        )
    }

    @Test
    fun ordinaryLongSummaryCanStillRenderCollapsedPreview() {
        val summary = agentCardComponent(
            AgentCardComponent.Summary,
            text = "A compact summary can show a short preview before expansion."
        )

        assertFalse(agentCardComponentUsesHiddenCollapsedBody(summary))
    }




    @Test
    fun multiPromptScenarioUsesExpandableBlock() {
        val scenario = agentCardComponent(
            AgentCardComponent.Scenario,
            title = "Pharmacy Scenario",
            items = listOf(
                "Customer: Say what medicine or help you need in one simple sentence.",
                "Pharmacist: Ask one short follow-up question about symptoms or dosage.",
                "Friend: Suggest a safe next step and keep the language easy.",
                "Customer: Close the conversation politely after confirming the solution."
            )
        )

        assertTrue(AgentCardDisplayPayload.isLong(scenario))
        assertFalse(agentCardComponentStartsExpanded(AgentCardComponent.Scenario))
    }






    @Test
    fun sentenceBuilderKeepsProvidedShuffledWordsAndShufflesFallbackAnswerWords() {
        val component = agentCardComponent(
            AgentCardComponent.SentenceBuilder,
            items = listOf("online", "to", "book", "I", "a", "room", "decided"),
            answer = "I decided to book a room online"
        )

        assertEquals(
            listOf("online", "to", "book", "I", "a", "room", "decided"),
            AgentCardDisplayPayload.sentenceBuilderWords(component)
        )

        val missingItems = component.copy(items = emptyList())
        val fallbackWords = AgentCardDisplayPayload.sentenceBuilderWords(missingItems)
        assertNotEquals(listOf("I", "decided", "to", "book", "a", "room", "online"), fallbackWords)
        assertEquals(
            listOf("a", "book", "decided", "I", "online", "room", "to"),
            fallbackWords.sortedBy { it.lowercase() }
        )
    }

    @Test
    fun sentenceBuilderKeepsExplicitPhraseChunksWhenTheyBuildAnswer() {
        val component = agentCardComponent(
            AgentCardComponent.SentenceBuilder,
            items = listOf("usually", "I", "take", "the bus", "to work"),
            answer = "I usually take the bus to work."
        )

        assertEquals(
            listOf("usually", "I", "take", "the bus", "to work"),
            AgentCardDisplayPayload.sentenceBuilderWords(component)
        )
    }

    @Test
    fun displayPairParserSplitsEnDashRows() {
        assertEquals(
            "Could we move on to the next point?" to "smoothly transition to a new agenda item",
            splitAgentCardPair("Could we move on to the next point? – smoothly transition to a new agenda item")
        )
    }

    @Test
    fun displayPairParserSplitsEqualsAndArrowRows() {
        assertEquals(
            "say" to "speak words or content",
            splitAgentCardPair("say = speak words or content")
        )
        assertEquals(
            listOf(AgentCardPair(left = "tell", right = "give information to someone", hint = "")),
            AgentCardDisplayPayload.pairs(
                agentCardComponent(
                    AgentCardComponent.Compare,
                    items = listOf("tell → give information to someone")
                )
            )
        )
    }

    @Test
    fun grammarItemsDoNotRepeatBodyText() {
        val component = agentCardComponent(
            AgentCardComponent.Grammar,
            text = "Subject + have/has + past participle",
            items = listOf("Subject + have/has + past participle")
        )

        assertEquals(emptyList<String>(), agentCardGrammarItemsToDisplay(component))
    }

    @Test
    fun speakingPromptItemsDisplayAsSeparatePrompts() {
        val component = agentCardComponent(
            AgentCardComponent.SpeakingPrompt,
            items = listOf(
                "Greet the worker and say what you want to drink.",
                "Ask the worker for the price.",
                "Say thank you and say goodbye."
            )
        )

        assertEquals(
            listOf(
                "Greet the worker and say what you want to drink.",
                "Ask the worker for the price.",
                "Say thank you and say goodbye."
            ),
            AgentCardDisplayPayload.speakingPrompts(component)
        )
    }

    @Test
    fun grammarItemsDoNotRepeatTextAlreadyCoveredByBody() {
        val component = agentCardComponent(
            AgentCardComponent.Grammar,
            text = "Use have/has + past participle for actions that happened at an unspecified time before now or started in the past and continue to the present.",
            items = listOf("Use have/has + past participle for actions that happened at")
        )

        assertEquals(emptyList<String>(), agentCardGrammarItemsToDisplay(component))
    }

    @Test
    fun atomBlockHeaderDoesNotMirrorBodyTextByDefault() {
        val component = agentCardComponent(
            AgentCardComponent.Grammar,
            title = "Core Rule",
            text = "Use have/has + past participle to describe actions that happened at an unspecified time before now."
        )

        assertEquals("", agentCardAtomHeaderText(component))
    }

    @Test
    fun keyValueRowsDoNotDuplicateSingleColumnItems() {
        assertEquals(
            "" to "say focuses on the words spoken",
            agentCardKeyValueRowTexts("say focuses on the words spoken", "")
        )
    }

    @Test
    fun materialGeneratingCardDoesNotRepeatNeedAsSummary() {
        // 旧渲染器退役 C2：生成中是纯文本通知（需求写进文案），不再造 cardSpec。
        val reply = AgentCardEngine.materialGenerating(
            workspace = testWorkspace(),
            need = "生成一套校园租房听力素材"
        )

        assertTrue(reply.text.contains("素材生成中"))
        assertTrue(reply.text.contains("生成一套校园租房听力素材"))
    }

    @Test
    fun headerTitleAlwaysFollowsWorkspaceTitle() {
        // B3：消息不再携带 cardSpec，头部标题不再被卡片标题劫持。
        val workspace = testWorkspace().copy(title = "酒店入住表达卡")
        assertEquals("酒店入住表达卡", agentVisibleWorkspaceTitle(workspace))
        assertEquals("", agentVisibleWorkspaceTitle(null))
    }































    @Test
    fun agentOutputDocxBytesRoundTripAsWordDocument() {
        val spec = AgentOutputFileSpec(
            name = "questions",
            mimeType = "",
            format = "docx",
            content = "Online Shopping Quiz\n\n1. Why do people like online shopping?\nA. It is easy.\nCorrect answer: A"
        )

        val file = agentOutputFileBytes(spec)

        assertEquals("questions.docx", file.name)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            file.mimeType
        )
        val text = DocxIo.readText(ByteArrayInputStream(file.bytes))
        assertTrue(text.contains("Online Shopping Quiz"))
        assertTrue(text.contains("Correct answer: A"))
        assertTrue(readDocxEntry(file.bytes, "word/document.xml").startsWith("<?xml"))
    }

    @Test
    fun agentOutputDocxUsesSpecificFileNameWhenContentTitleIsGeneric() {
        val spec = AgentOutputFileSpec(
            name = "B1_Online_Shopping_Reading_Questions.docx",
            mimeType = "",
            format = "docx",
            content = "Question Set\n\nQuestions:\n1. Why do people like online shopping?\nA. It saves time.\nCorrect answer: A"
        )

        val file = agentOutputFileBytes(spec)
        val text = DocxIo.readText(ByteArrayInputStream(file.bytes))

        assertTrue(text.contains("B1 Online Shopping Reading Questions"))
        assertFalse(text.lines().firstOrNull().orEmpty() == "Question Set")
    }

    @Test
    fun agentOutputDocxKeepsQuestionOnlyAnswerKeyExport() {
        val spec = AgentOutputFileSpec(
            name = "Scenario_Practice_Questions.docx",
            mimeType = "",
            format = "docx",
            content = """
                Questions:
                1. Where does the dialogue take place?
                A. At a pharmacy
                B. At a train station
                C. At a hotel
                D. At a classroom

                Answer Key:
                1. A
            """.trimIndent()
        )

        val file = agentOutputFileBytes(spec)
        val text = DocxIo.readText(ByteArrayInputStream(file.bytes))

        assertTrue(text.contains("Where does the dialogue take place?"))
        assertTrue(text.contains("Answer Key:"))
        assertTrue(text.contains("1. A"))
        assertFalse(text.contains("Scenario:"))
    }

    @Test
    fun agentOutputFilesBuildRealZipWhenFormatIsZip() {
        val spec = AgentOutputFileSpec(
            name = "Listening_Questions.zip",
            mimeType = "application/zip",
            format = "zip",
            content = "Transcript and questions package."
        )

        val file = agentOutputFileBytes(spec)

        assertEquals("Listening_Questions.zip", file.name)
        assertEquals("application/zip", file.mimeType)
        assertTrue(isZipBytes(file.bytes))
        assertEquals(
            "Transcript and questions package.",
            readZipEntry(file.bytes, "content.txt").toString(Charsets.UTF_8)
        )
    }

    @Test
    fun currentListeningQuestionExportBuildsZipPackageWithAudio() {
        val record = HistoryRecord(
            id = "record_job_interview_export",
            scene = "A Simple Job Interview",
            createdAt = 10L,
            contentType = "dialogue",
            content = ListeningContent(
                title = "A Simple Job Interview",
                script = "Manager: Tell me about yourself.\nCandidate: I am organized.",
                audioUrl = "/tmp/job_interview.wav",
                questions = listOf(
                    Question(
                        questionText = "What is the conversation about?",
                        options = listOf("A job interview", "A birthday party", "A bus ticket", "A weather report"),
                        correctAnswer = 0,
                        explanation = "The manager is interviewing a candidate."
                    )
                )
            )
        )

        val bytes = buildListeningZipBytes(record, "audio.wav" to byteArrayOf(1, 2, 3, 4))
        val manifest = (parseJsonObjectOrNull(readDocxEntry(bytes, "manifest.json"))!!)
        val text = DocxIo.readText(ByteArrayInputStream(readZipEntry(bytes, "content.docx")))

        assertEquals("A_Simple_Job_Interview.zip", agentListeningExportZipName(record))
        assertEquals("listene_listening", manifest.str("format"))
        assertTrue(text.contains("A Simple Job Interview"))
        assertTrue(text.contains("What is the conversation about?"))
        assertEquals(listOf("manifest.json", "content.docx", "transcript.txt", "questions.txt", "audio.wav"), zipEntryNames(bytes))
        assertTrue(readDocxEntry(bytes, "transcript.txt").contains("Manager: Tell me about yourself."))
        assertTrue(readDocxEntry(bytes, "questions.txt").contains("What is the conversation about?"))
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), readZipEntry(bytes, "audio.wav").toList())
    }

    @Test
    fun listeningZipExportRequiresBundledAudio() {
        val record = HistoryRecord(
            id = "record_missing_audio_export",
            scene = "A Simple Job Interview",
            createdAt = 10L,
            contentType = "dialogue",
            content = ListeningContent(
                title = "A Simple Job Interview",
                script = "Manager: Tell me about yourself.",
                questions = listOf(
                    Question(
                        questionText = "What is the conversation about?",
                        options = listOf("A job interview", "A birthday party"),
                        correctAnswer = 0
                    )
                )
            )
        )

        val error = runCatching { buildListeningZipBytes(record, null) }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("音频"))
    }

    @Test
    fun currentListeningQuestionDocxExportContainsOnlyQuestionsAndAnswers() {
        val record = HistoryRecord(
            id = "record_job_interview_docx_export",
            scene = "A Simple Job Interview",
            createdAt = 10L,
            contentType = "dialogue",
            content = ListeningContent(
                title = "A Simple Job Interview",
                script = "Manager: Tell me about yourself.\nCandidate: I am organized.",
                audioUrl = "/tmp/job_interview.wav",
                questions = listOf(
                    Question(
                        questionText = "What is the conversation about?",
                        options = listOf("A job interview", "A birthday party", "A bus ticket", "A weather report"),
                        correctAnswer = 0,
                        explanation = "The manager is interviewing a candidate."
                    )
                )
            ),
            analysisResult = AnalysisResult(
                summary = "User missed detail questions.",
                weakPoints = listOf("detail"),
                suggestions = listOf("listen again")
            )
        )

        val text = DocxIo.readText(ByteArrayInputStream(DocxIo.writeToBytes(buildListeningQuestionDocxParagraphs(record))))

        assertTrue(text.contains("What is the conversation about?"))
        assertTrue(text.contains("正确答案：A"))
        assertFalse(text.contains("Manager: Tell me"))
        assertFalse(text.contains("【听力文案】"))
        assertFalse(text.contains("AI 学习报告"))
        assertFalse(text.contains("User missed detail questions."))
    }


    @Test
    fun currentListeningQuestionExportManifestPointsToBundledAudioPath() {
        val record = HistoryRecord(
            id = "record_job_interview_audio_path",
            scene = "A Simple Job Interview",
            createdAt = 10L,
            contentType = "dialogue",
            content = ListeningContent(
                title = "A Simple Job Interview",
                script = "Manager: Tell me about yourself.\nCandidate: I am organized.",
                audioUrl = "/tmp/job_interview.mp3",
                questions = listOf(
                    Question(
                        questionText = "What is the conversation about?",
                        options = listOf("A job interview", "A birthday party"),
                        correctAnswer = 0
                    )
                )
            )
        )

        val bytes = buildListeningZipBytes(record, "recordings/job_interview.mp3" to byteArrayOf(1, 2, 3, 4))
        val manifest = (parseJsonObjectOrNull(readDocxEntry(bytes, "manifest.json"))!!)
        val content = manifest.objOrNull("content")!!

        assertEquals("audio.mp3", content.str("audioPath"))
        assertEquals("application/zip", inferAgentMimeType(agentListeningExportZipName(record)))
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), readZipEntry(bytes, "audio.mp3").toList())
    }

    @Test
    fun importedListeningAudioKeepsManifestAudioExtension() {
        val path = listeningImportAudioFileName("audio.mp3", setOf("manifest.json", "content.docx", "audio.mp3"))

        assertTrue(path.endsWith(".mp3"))
        assertFalse(path.endsWith(".wav"))
    }

    @Test
    fun importedListeningAudioKeepsFallbackEntryExtension() {
        val path = listeningImportAudioFileName("", setOf("manifest.json", "content.docx", "media/audio.m4a"))

        assertTrue(path.endsWith(".m4a"))
        assertFalse(path.endsWith(".wav"))
    }

    @Test
    fun listeningZipAudioEntryCanBeReadFromLocalAudioFile() {
        val audio = File.createTempFile("listene_export_audio", ".mp3").apply {
            writeBytes(byteArrayOf(9, 8, 7))
            deleteOnExit()
        }

        val entry = resolveListeningAudioZipEntry(audio.absolutePath)

        assertEquals("audio.mp3", entry?.first)
        assertEquals(byteArrayOf(9, 8, 7).toList(), entry?.second?.toList())
    }

    @Test
    fun listeningZipAudioEntryCanBeDownloadedFromRemoteAudioUrl() {
        val server = ServerSocket(0)
        val audioBytes = byteArrayOf(5, 4, 3, 2)
        val worker = thread(start = true) {
            server.use { socket ->
                socket.accept().use { client ->
                    client.getInputStream().bufferedReader().readLine()
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${audioBytes.size}\r\n\r\n"
                    client.getOutputStream().use { output ->
                        output.write(response.toByteArray(Charsets.UTF_8))
                        output.write(audioBytes)
                    }
                }
            }
        }

        val entry = resolveListeningAudioZipEntry("http://127.0.0.1:${server.localPort}/lesson-audio.mp3")
        worker.join(1_000)

        assertEquals("audio.mp3", entry?.first)
        assertEquals(audioBytes.toList(), entry?.second?.toList())
    }

    @Test
    fun listeningZipAudioEntryUsesHttpContentTypeWhenRemoteUrlHasNoExtension() {
        val server = ServerSocket(0)
        val audioBytes = byteArrayOf(5, 4, 3, 2)
        val worker = thread(start = true) {
            server.use { socket ->
                socket.accept().use { client ->
                    client.getInputStream().bufferedReader().readLine()
                    val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/mpeg\r\n" +
                        "Content-Length: ${audioBytes.size}\r\n\r\n"
                    client.getOutputStream().use { output ->
                        output.write(response.toByteArray(Charsets.UTF_8))
                        output.write(audioBytes)
                    }
                }
            }
        }
        val record = HistoryRecord(
            id = "record_extensionless_remote_audio",
            scene = "Remote Audio",
            createdAt = 10L,
            contentType = "dialogue",
            content = ListeningContent(
                title = "Remote Audio",
                script = "Speaker: Listen carefully.",
                audioUrl = "http://127.0.0.1:${server.localPort}/download",
                questions = listOf(
                    Question(
                        questionText = "What should the listener do?",
                        options = listOf("Listen carefully", "Read quickly"),
                        correctAnswer = 0
                    )
                )
            )
        )

        val entry = resolveListeningAudioZipEntry(record.content.audioUrl)
        val zipBytes = buildListeningZipBytes(record, entry)
        worker.join(1_000)
        val manifest = (parseJsonObjectOrNull(readDocxEntry(zipBytes, "manifest.json"))!!)
        val content = manifest.objOrNull("content")!!

        assertEquals("audio.mp3", entry?.first)
        assertEquals("audio.mp3", content.str("audioPath"))
        assertEquals("audio/mpeg", content.objOrNull("audio")!!.str("mimeType"))
        assertEquals(audioBytes.toList(), readZipEntry(zipBytes, "audio.mp3").toList())
        assertTrue(readZipEntry(zipBytes, "audio.mp3").isNotEmpty())
    }

    @Test
    fun zipExportUsesZipMimeType() {
        assertEquals("application/zip", inferAgentMimeType("A_Simple_Job_Interview.zip"))
    }

    @Test
    fun zipAttachmentsAreSupportedSoListeningPackagesCanBeImported() {
        ensureAgentSupportedAttachment("application/zip")
    }

    @Test
    fun zipMimeOrFileNameRoutesToListeningPackageImportBeforeAgentAttachment() {
        assertTrue(agentUriLooksLikeListeningPackage(name = "Job_Interview.zip", mimeType = ""))
        assertTrue(agentUriLooksLikeListeningPackage(name = "download", mimeType = "application/zip"))
        assertFalse(agentUriLooksLikeListeningPackage(name = "notes.docx", mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }

    @Test
    fun importedListeningWorkspaceNeedUsesRecordTitle() {
        val record = HistoryRecord(
            id = "record_imported",
            scene = "A Simple Job Interview",
            createdAt = 10L,
            content = ListeningContent(
                title = "A Simple Job Interview",
                script = "Manager: Hello.",
                questions = listOf(Question("Where are they?", listOf("Office", "Park"), 0))
            )
        )

        assertEquals(
            "导入听力练习：A Simple Job Interview",
            agentImportedListeningWorkspaceNeed(record)
        )
    }

    @Test
    fun agentOutputDocxCleansMarkdownTablesForWord() {
        val spec = AgentOutputFileSpec(
            name = "B1_Travel_Translation.docx",
            mimeType = "",
            format = "docx",
            content = """
                # B1 Travel Translation
                **2 sentences: English → Chinese**
                ---
                | English | Chinese |
                |---------|--------|
                | I missed the train. | 我错过了火车。 |
                | Could you change my ticket? | 你能帮我改签车票吗？ |
                - tell — requires a personal object (*tell someone something*).
            """.trimIndent()
        )

        val file = agentOutputFileBytes(spec)
        val text = DocxIo.readText(ByteArrayInputStream(file.bytes))

        assertTrue(text.contains("B1 Travel Translation"))
        assertTrue(text.contains("2 sentences: English → Chinese"))
        assertTrue(text.contains("I missed the train. - 我错过了火车。"))
        assertTrue(text.contains("Could you change my ticket? - 你能帮我改签车票吗？"))
        assertTrue(text.contains("tell — requires a personal object (tell someone something)."))
        assertFalse(text.contains("#"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("*tell"))
        assertFalse(text.contains("|---------|"))
    }

    private fun readDocxEntry(bytes: ByteArray, entryName: String): String {
        return readZipEntry(bytes, entryName).toString(Charsets.UTF_8)
    }

    private fun readZipEntry(bytes: ByteArray, entryName: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == entryName) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return ByteArray(0)
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }















    @Test
    fun displayPolicyRepairsGenericClozeBlankLabelsFromSourceSentence() {
        val component = agentCardComponent(
            AgentCardComponent.Cloze,
            title = "Fill in the blanks",
            text = "Blank 1: ___ Blank 2: ___ Blank 3: ___",
            options = listOf("I", "booked", "window", "seat", "online"),
            answer = "booked | window | seat"
        )

        assertEquals(
            "I ___ a ___ ___ online.",
            agentCardClozeTextFromSourceSentence(
                component = component,
                sourceSentence = "I booked a window seat online."
            )
        )
    }




    @Test
    fun sentenceBuilderKeepsPhraseChunksThatBuildAnswer() {
        val component = agentCardComponent(
            AgentCardComponent.SentenceBuilder,
            text = "Put the words in the correct order.",
            items = listOf("book", "a room", "to", "decided", "I", "online"),
            answer = "I decided to book a room online"
        )

        assertEquals(
            listOf("book", "a room", "to", "decided", "I", "online"),
            AgentCardDisplayPayload.sentenceBuilderWords(component)
        )
    }
















    @Test
    fun progressLabelUsesLiveAnswersInsteadOfStaticModelText() {
        assertEquals(
            "1 / 2",
            agentCardProgressLabel(
                componentText = "0 of 2 questions answered",
                questionCount = 2,
                answeredCount = 1,
                revealed = false,
                correctCount = 0,
                fallbackProgress = 0.5f
            )
        )
        assertEquals(
            "答对 2 / 2",
            agentCardProgressLabel(
                componentText = "0 of 2 questions answered",
                questionCount = 2,
                answeredCount = 2,
                revealed = true,
                correctCount = 2,
                fallbackProgress = 1f
            )
        )
    }






    @Test
    fun materialReadyFallbackShowsPlayableCoreMaterialCard() {
        val record = HistoryRecord(
            id = "record_1",
            scene = "Coffee Shop",
            createdAt = 1L,
            content = ListeningContent(
                title = "At the Coffee Shop",
                script = "Barista: Hello.\nCustomer: A coffee, please.",
                audioUrl = "/tmp/audio.wav",
                questions = listOf(
                    Question(
                        questionText = "What does the customer order?",
                        options = listOf("Coffee", "Tea"),
                        correctAnswer = 0
                    )
                )
            )
        )

        // cardSpec 彻底退役：素材锚点经 listeningRecordId 渲染自包含听力微元。
        val reply = AgentCardEngine.materialReady(record)
        assertEquals("record_1", reply.listeningRecordId)
        assertEquals("current", AgentCardEngine.materialReady(null).listeningRecordId)
    }


    @Test
    fun truncatedTranscriptPreviewResolvesToFullMaterialScript() {
        val fullScript = "Passenger: Good morning. I want to check in for my flight.\n\n" +
            "Agent: Good morning. Can I see your passport, please?\n\n" +
            "Passenger: Here is my passport. My flight number is BA 207.\n\n" +
            "Agent: Thank you. Do you have any bags to check?\n\n" +
            "Passenger: Yes, I have one suitcase.\n\n" +
            "Agent: Here is your boarding pass. Your gate is B12."
        val preview = "Passenger: Good morning. I want to check in for my flight. " +
            "Agent: Good morning. Can I see your passport, please? " +
            "Passenger: Here is my passport. My flight number is BA 207. " +
            "Agent: Thank you. Do you have any bags..."

        val resolved = agentCardResolvedTranscriptText(
            componentText = preview,
            recordScript = fullScript,
            source = ""
        )

        assertEquals(fullScript, resolved)
    }

    @Test
    fun nonMaterialTranscriptTextDoesNotOverrideWithRecordScript() {
        val explicitText = "This transcript belongs to a pasted clip."
        val recordScript = "Agent: This is the latest generated listening material."

        val resolved = agentCardResolvedTranscriptText(
            componentText = explicitText,
            recordScript = recordScript,
            source = ""
        )

        assertEquals(explicitText, resolved)
    }

    @Test
    fun agentVoiceReplyOnlySynthesizesForPlainChatReplies() {
        assertEquals(
            true,
            shouldSynthesizeAgentReplySpeech(
                replyMode = "voice",
                text = "Here is a short explanation.",
                attachments = emptyList()
            )
        )
        assertEquals(
            false,
            shouldSynthesizeAgentReplySpeech(
                replyMode = "voice",
                text = "已生成文件。",
                attachments = listOf(
                    AgentInputAttachment(
                        name = "practice.txt",
                        mimeType = "text/plain",
                        generated = true
                    )
                )
            )
        )
    }






















    @Test
    fun questionSetFeedbackShowsCorrectnessAfterChecking() {
        val question = Question(
            questionText = "Where was Tom going?",
            options = listOf("To the bus stop", "To the park", "To the shop", "To his home"),
            correctAnswer = 0
        )

        val correct = agentQuestionSetFeedback(question, selectedAnswer = 0)
        val wrong = agentQuestionSetFeedback(question, selectedAnswer = 2)

        assertEquals(true, correct?.correct)
        assertEquals("回答正确", correct?.message)
        assertTrue(correct?.explanation.orEmpty().contains("正确答案：A（To the bus stop）。"))
        assertTrue(correct?.explanation.orEmpty().contains("题干关键词"))
        assertTrue(correct?.explanation.orEmpty().contains("排除"))
        assertEquals(false, wrong?.correct)
        assertEquals("不正确", wrong?.message)
        assertTrue(wrong?.explanation.orEmpty().contains("你选择了 C（To the shop）"))
        assertTrue(wrong?.explanation.orEmpty().contains("正确答案：A（To the bus stop）。"))
        assertTrue(wrong?.explanation.orEmpty().contains("回到原文"))
    }

    @Test
    fun basicPracticeFeedbackFallbackExplanationIsChineseAndNamesCorrectAnswer() {
        val cloze = agentPracticeFeedbackExplanation(
            type = AgentCardComponent.Cloze,
            answer = "have",
            prompt = "I ___ already finished it.",
            providedExplanation = ""
        )
        val shortAnswer = agentPracticeFeedbackExplanation(
            type = AgentCardComponent.ShortAnswer,
            answer = "I visited the museum yesterday.",
            prompt = "Type this sentence.",
            providedExplanation = ""
        )
        val sentenceBuilder = agentPracticeFeedbackExplanation(
            type = AgentCardComponent.SentenceBuilder,
            answer = "I decided to book a room online",
            prompt = "",
            providedExplanation = ""
        )

        listOf(cloze, shortAnswer, sentenceBuilder).forEach { explanation ->
            assertTrue(explanation.contains("正确答案："))
            assertTrue(explanation.contains("提示："))
            assertTrue(Regex("[\\u4e00-\\u9fff]").containsMatchIn(explanation))
            assertTrue(explanation.length > explanation.substringBefore("提示：").length + 20)
        }
        assertTrue(cloze.contains("have"))
        assertTrue(cloze.contains("空格前后"))
        assertTrue(cloze.contains("固定搭配"))
        assertTrue(shortAnswer.contains("I visited the museum yesterday."))
        assertTrue(shortAnswer.contains("关键词"))
        assertTrue(shortAnswer.contains("时态"))
        assertTrue(sentenceBuilder.contains("I decided to book a room online"))
        assertTrue(sentenceBuilder.contains("主语"))
        assertTrue(sentenceBuilder.contains("修饰信息"))
        val supplemented = agentPracticeFeedbackExplanation(
            type = AgentCardComponent.Cloze,
            answer = "have",
            prompt = "I ___ already finished it.",
            providedExplanation = "Use have with I in present perfect."
        )
        assertTrue(supplemented.contains("Use have with I in present perfect."))
        assertTrue(supplemented.contains("正确答案：have。"))
        assertTrue(supplemented.contains("提示："))
    }




    @Test
    fun displayTitlesLocalizeCommonEnglishProtocolLabels() {
        assertEquals("原文", agentCardDisplayTitle("Script", "原文"))
        assertEquals("原文", agentCardDisplayTitle("Transcript", "原文"))
        assertEquals("听力音频", agentCardDisplayTitle("Listening Audio", "音频控件"))
        assertEquals("题组", agentCardDisplayTitle("Question Set", "题目"))
        assertEquals("逐句点播", agentCardDisplayTitle("Sentence Transcript", "逐句点播"))
        assertEquals("短文", agentCardDisplayTitle("Passage", "简介"))
        assertEquals("填空题", agentCardDisplayTitle("Fill-in-the-Blank Questions", "题目"))
        assertEquals("写作提纲", agentCardDisplayTitle("Writing Outline", "写作"))
        assertEquals("输入答案", agentCardDisplayTitle("Short Answer", "题目"))
        assertEquals("组句", agentCardDisplayTitle("Sentence Builder", "题目"))
        assertEquals("排序", agentCardDisplayTitle("Ordering", "题目"))
        assertEquals("十五选三", agentCardDisplayTitle("填空 短文 十五选三", "题目"))
        assertEquals(null, agentCardSinglePracticeTitleFromMixedLabel("Cloze / Short Answer"))
    }

    @Test
    fun displayTitleUsesVisiblePracticeComponentInsteadOfMixedAiTitle() {
        val clozeComponent = agentCardComponent(
            AgentCardComponent.Cloze,
            title = "填空 / 短答 / 组句 / 十五选三",
            options = (1..15).map { "option$it" },
            answer = "option1 | option2 | option3"
        )
        assertEquals("作答区", agentCardComponentDisplayTitle(clozeComponent, "作答区"))

        val builderComponent = agentCardComponent(
            AgentCardComponent.SentenceBuilder,
            title = "Cloze / Short Answer / Sentence Builder"
        )
        assertEquals("组句", agentCardComponentDisplayTitle(builderComponent, "组句"))

        val orderingComponent = agentCardComponent(
            AgentCardComponent.Ordering,
            title = "Cloze / Short Answer / Ordering"
        )
        assertEquals("排序", agentCardComponentDisplayTitle(orderingComponent, "排序"))
    }




    @Test
    fun libraryCardTitlesLocalizeProtocolLabelsWithoutTouchingBusinessTitles() {
        assertEquals("题组", agentLibraryCardDisplayTitle("Question Set"))
        assertEquals("听力音频", agentLibraryCardDisplayTitle("Listening Audio"))
        assertEquals("Travel Vocabulary Quiz", agentLibraryCardDisplayTitle("Travel Vocabulary Quiz"))
    }



    @Test
    fun storedBlockedErrorChatMessagesAreFilteredOnLoad() {
        val blocked = AgentChatMessage(
            id = 1L,
            role = AgentChatRole.Agent,
            text = "刚才的 AI 卡片没有生成成功，请点击重试或换个说法再试。"
        )
        val user = AgentChatMessage(
            id = 2L,
            role = AgentChatRole.User,
            text = "help me practice CET4 essay writing"
        )
        val fileReply = AgentChatMessage(
            id = 3L,
            role = AgentChatRole.Agent,
            text = "已生成文件。",
            attachments = listOf(AgentInputAttachment(name = "practice.zip", mimeType = "application/zip", generated = true))
        )

        val sanitized = sanitizeStoredAgentChatMessages(listOf(blocked, user, fileReply))

        assertEquals(listOf(user, fileReply), sanitized)
    }


    @Test
    fun materialQuestionPreviewCheckRequiresAllQuestionsAnswered() {
        val noneAnswered = agentMaterialQuestionPreviewActionState(
            questionCount = 2,
            answeredCount = 0,
            revealed = false
        )
        assertEquals("核对答案", noneAnswered.text)
        assertFalse(noneAnswered.enabled)

        val partiallyAnswered = agentMaterialQuestionPreviewActionState(
            questionCount = 2,
            answeredCount = 1,
            revealed = false
        )
        assertEquals("核对答案", partiallyAnswered.text)
        assertFalse(partiallyAnswered.enabled)

        val allAnswered = agentMaterialQuestionPreviewActionState(
            questionCount = 2,
            answeredCount = 2,
            revealed = false
        )
        assertEquals("核对答案", allAnswered.text)
        assertTrue(allAnswered.enabled)

        val revealed = agentMaterialQuestionPreviewActionState(
            questionCount = 2,
            answeredCount = 2,
            revealed = true
        )
        assertEquals("交给 AI 分析", revealed.text)
        assertTrue(revealed.enabled)
    }

    @Test
    fun materialStatusTextUsesActualRecordInsteadOfStaleCardText() {
        val record = HistoryRecord(
            id = "material_status",
            scene = "Bookstore",
            createdAt = 1L,
            content = ListeningContent(
                title = "At the Bookstore",
                script = "Clerk: Can I help you?",
                questions = List(5) { index ->
                    Question(
                        questionText = "Question ${index + 1}?",
                        options = listOf("A", "B"),
                        correctAnswer = 0
                    )
                },
                audioUrl = "/tmp/bookstore.wav"
            )
        )

        assertEquals("5 题 | 有音频 | 有原文", agentMaterialStatusText(record, fallback = "5 题 | 无音频 | 无原文"))
        assertEquals("等待素材", agentMaterialStatusText(null, fallback = "等待素材"))
    }


    @Test
    fun chatInputEnterOnlySendsWhenEnabledAndNotBlank() {
        assertEquals(true, agentChatInputEnterKeyShouldSend(enabled = true, value = "练习四级听力"))
        assertEquals(false, agentChatInputEnterKeyShouldSend(enabled = true, value = "   "))
        assertEquals(false, agentChatInputEnterKeyShouldSend(enabled = false, value = "练习四级听力"))
    }



    @Test
    fun displayPayloadPrefersTypedFieldsOverFlattenedItems() {
        val soundPairComponent = agentCardComponent(
            AgentCardComponent.MinimalPair,
            items = listOf("flattened | ignored"),
            pairs = listOf(AgentCardPair("ship", "sheep", "/ɪ/ vs /iː/"))
        )
        val tokenComponent = agentCardComponent(
            AgentCardComponent.WordFamily,
            items = listOf("flattened | ignored"),
            tokens = listOf("decide", "decision", "decisive")
        )
        val outlineComponent = agentCardComponent(
            AgentCardComponent.WritingOutline,
            items = listOf("fallback"),
            steps = listOf(AgentCardLabeledText("claim", "State your opinion."))
        )
        val rubricComponent = agentCardComponent(
            AgentCardComponent.Rubric,
            items = listOf("fallback"),
            criteria = listOf(AgentCardLabeledText("Accuracy", "Use present perfect correctly."))
        )
        val clozeComponent = agentCardComponent(
            AgentCardComponent.Cloze,
            text = "I ___ already finished it.",
            items = listOf("fallback"),
            options = listOf("have", "has")
        )
        val examplesComponent = agentCardComponent(
            AgentCardComponent.Examples,
            items = listOf("fallback"),
            examples = listOf("She has visited London twice.")
        )

        assertEquals(listOf(AgentCardPair("ship", "sheep", "/ɪ/ vs /iː/")), AgentCardDisplayPayload.pairs(soundPairComponent))
        assertEquals(listOf(listOf("decide", "decision", "decisive")), AgentCardDisplayPayload.tokenRows(tokenComponent))
        assertEquals(listOf(AgentCardLabeledText("claim", "State your opinion.")), AgentCardDisplayPayload.labeledRows(outlineComponent))
        assertEquals(listOf(AgentCardLabeledText("Accuracy", "Use present perfect correctly.")), AgentCardDisplayPayload.labeledRows(rubricComponent))
        assertEquals(listOf("have", "has"), AgentCardDisplayPayload.options(clozeComponent))
        assertEquals(listOf("She has visited London twice."), AgentCardDisplayPayload.textRows(examplesComponent))
    }

    @Test
    fun longDetectionMatchesCollapsedRendererLimits() {
        val shortSummary = agentCardComponent(
            AgentCardComponent.Summary,
            text = "a".repeat(220)
        )
        val longSummary = shortSummary.copy(text = "a".repeat(221))
        val fourStepOutline = agentCardComponent(
            AgentCardComponent.WritingOutline,
            steps = listOf(
                AgentCardLabeledText("claim", "State your opinion."),
                AgentCardLabeledText("reason", "Give your main reason."),
                AgentCardLabeledText("example", "Provide one example."),
                AgentCardLabeledText("closing", "Wrap up.")
            )
        )
        val sevenStepOutline = fourStepOutline.copy(
            steps = fourStepOutline.steps + listOf(
                AgentCardLabeledText("counterpoint", "Address a concern."),
                AgentCardLabeledText("detail", "Add a detail."),
                AgentCardLabeledText("link", "Connect back.")
            )
        )
        val fourRegisterPairs = agentCardComponent(
            AgentCardComponent.Register,
            pairs = listOf(
                AgentCardPair("casual 1", "formal 1"),
                AgentCardPair("casual 2", "formal 2"),
                AgentCardPair("casual 3", "formal 3"),
                AgentCardPair("casual 4", "formal 4")
            )
        )
        val fiveRegisterPairs = fourRegisterPairs.copy(
            pairs = fourRegisterPairs.pairs + AgentCardPair("casual 5", "formal 5")
        )
        val sevenTokenFamily = agentCardComponent(
            AgentCardComponent.WordFamily,
            tokens = listOf("act", "action", "active", "actor", "activity", "activate", "activation")
        )
        val nineTokenFamily = sevenTokenFamily.copy(tokens = sevenTokenFamily.tokens + listOf("actively", "inactive"))

        assertFalse(AgentCardDisplayPayload.isLong(shortSummary))
        assertTrue(AgentCardDisplayPayload.isLong(longSummary))
        assertFalse(AgentCardDisplayPayload.isLong(fourStepOutline))
        assertTrue(AgentCardDisplayPayload.isLong(sevenStepOutline))
        assertFalse(AgentCardDisplayPayload.isLong(fourRegisterPairs))
        assertTrue(AgentCardDisplayPayload.isLong(fiveRegisterPairs))
        assertFalse(AgentCardDisplayPayload.isLong(sevenTokenFamily))
        assertTrue(AgentCardDisplayPayload.isLong(nineTokenFamily))
    }







    private fun testWorkspace() = LearningWorkspace(
        id = "ws_test",
        title = "听力工作区",
        need = "生成听力练习",
        summary = "听力训练",
        currentStep = "material",
        createdAt = 1L,
        updatedAt = 1L,
        plan = WorkspacePlan(
            title = "听力工作区",
            summary = "听力训练",
            contentType = "dialogue",
            materialPrompt = "校园对话",
            difficulty = "普通",
            speechRate = "medium",
            voiceGender = "female"
        )
    )

}

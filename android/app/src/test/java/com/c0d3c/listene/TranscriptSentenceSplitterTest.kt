package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSentenceSplitterTest {
    @Test
    fun splitTurnsPreservesSpeakersAndProtectedPeriods() {
        val turns = TranscriptSentenceSplitter.splitTurnsForListening(
            "Alex: Dr. Morgan arrived at 8.30 a.m. She met Mr. Lee. Ben: Did it work? Yes!"
        )

        assertEquals(2, turns.size)
        assertEquals("Alex", turns[0].speakerName)
        assertEquals(
            listOf(
                "Dr. Morgan arrived at 8.30 a.m.",
                "She met Mr. Lee."
            ),
            turns[0].sentences
        )
        assertEquals("Ben", turns[1].speakerName)
        assertEquals(listOf("Did it work?", "Yes!"), turns[1].sentences)
    }

    @Test
    fun splitTurnsPreservesArticleParagraphs() {
        val turns = TranscriptSentenceSplitter.splitTurnsForListening(
            "The first paragraph has one sentence. It has another sentence.\n\nThe second paragraph starts here. It ends here."
        )

        assertEquals(2, turns.size)
        assertEquals("", turns[0].speakerName)
        assertEquals(
            listOf("The first paragraph has one sentence.", "It has another sentence."),
            turns[0].sentences
        )
        assertEquals(
            listOf("The second paragraph starts here.", "It ends here."),
            turns[1].sentences
        )
    }
}

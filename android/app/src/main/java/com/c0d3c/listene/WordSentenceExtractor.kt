package com.c0d3c.listene

/** 从听力脚本 / 题目文本中抽取候选单词与句子的纯文本工具，供错因分析定位信息句使用。 */
object WordSentenceExtractor {

    private val STOP_WORDS: Set<String> = setOf(
        "a","an","the","and","or","but","so","if","then","than","of","to","in","on","at","for","with","by",
        "from","into","onto","over","under","up","down","out","off","about","as","is","am","are","was","were",
        "be","been","being","do","does","did","done","doing","have","has","had","having","i","you","he","she",
        "it","we","they","me","him","her","us","them","my","your","his","its","our","their","this","that",
        "these","those","there","here","what","which","who","whom","whose","when","where","why","how","not",
        "no","yes","also","just","very","too","only","own","same","other","another","such","each","every",
        "any","some","all","most","more","much","few","less","one","two","three","four","five","six","seven",
        "eight","nine","ten","first","next","last","also","like","get","got","getting","go","going","went",
        "gone","make","makes","made","making","take","takes","took","taken","taking","come","comes","came",
        "coming","know","knows","knew","known","knowing","think","thinks","thought","thinking","see","sees",
        "saw","seen","seeing","want","wants","wanted","wanting","will","would","shall","should","can","could",
        "may","might","must","need","needs","needed","needing","let","lets","letting","say","says","said",
        "saying","speaker","narrator","s","t","ll","ve","re","m","d"
    )

    /** 抽取候选单词（去标点，去停用词，按 lowercase 去重，保留出现顺序） */
    fun extractWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("""[A-Za-z]+(?:'[A-Za-z]+)?""")
        val seen = LinkedHashSet<String>()
        for (m in regex.findAll(text)) {
            val raw = m.value.trim()
            if (raw.length < 2) continue
            val lower = raw.lowercase()
            if (lower in STOP_WORDS) continue
            seen.add(lower)
        }
        return seen.toList()
    }

    /** 抽取候选句子（先按行、再按句末标点切分，过滤过短、过长、speaker 标签） */
    fun extractSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = text.lineSequence()
            .map { line -> line.replace(Regex("""^\s*[A-Z][A-Za-z'\- ]{0,30}[:：]\s*"""), "") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val parts = lines.flatMap { line ->
            line.split(Regex("""(?<=[.!?。！？])\s+"""))
        }

        val trimChars = setOf(
            '"', '\u201C', '\u201D',
            '\'', '\u2018', '\u2019',
            '(', ')', '\uFF08', '\uFF09',
            ' ', '\t'
        )
        val seen = LinkedHashSet<String>()
        for (raw in parts) {
            var s = raw.trim().trim { it in trimChars }
            s = s.replace(Regex("""^[A-D][.)）]\s*"""), "")
                 .replace(Regex("""^\([A-D]\)\s*"""), "")
                 .replace(Regex("""^\d+[.)）]\s*"""), "")
                 .trim()
            if (s.length < 6 || s.length > 200) continue
            val wc = Regex("""[A-Za-z]+""").findAll(s).count()
            if (wc < 3) continue
            seen.add(s)
        }
        return seen.toList()
    }
}

/** 词句去重规范化键。 */
object NormalizeKeys {
    fun forSentence(en: String): String =
        en.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim().replace(Regex("""\s+"""), " ")
}

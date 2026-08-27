package com.example.medvoicetrainer.analysis

import java.util.Locale

/**
 * Evidence-aware L1 transfer profiles for every native language offered by the Android app.
 *
 * A profile is a watchlist, never proof of an error. The evaluator must first find an unacceptable
 * form in a learner turn and pass the normal correction audit. Only then may a matching profile
 * explain a probable transfer mechanism. This distinction matters because many developmental
 * English errors occur across L1s and because bilingual speakers vary substantially.
 *
 * Pure Kotlin: prompt generation, locale normalization and conservative post-classification are
 * covered by local JVM tests.
 */
object L1InterferenceCatalog {

    data class Pattern(
        val id: String,
        val category: String,
        val title: String,
        val transferHint: String,
        val original: String,
        val corrected: String
    )

    data class Profile(
        val code: String,
        val name: String,
        val aliases: Set<String>,
        val pronunciationFocus: String,
        val patterns: List<Pattern>
    )

    private fun p(
        id: String,
        category: String,
        title: String,
        hint: String,
        original: String,
        corrected: String
    ) = Pattern(id, category, title, hint, original, corrected)

    private fun profile(
        code: String,
        name: String,
        aliases: Set<String>,
        pronunciation: String,
        vararg patterns: Pattern
    ) = Profile(code, name, aliases, pronunciation, patterns.toList())

    val PROFILES: Map<String, Profile> = listOf(
        profile(
            "ko", "Korean", setOf("korean", "한국어", "한국말"),
            "r/l, f/p, voiced/voiceless contrasts, th sounds, final consonants, consonant clusters, inserted vowels, and English word stress",
            p("ko.articles", "articles", "Missing or wrong articles", "Korean has no article system, so a/an/the may be omitted or selected from discourse context incorrectly.", "I have headache", "I have a headache"),
            p("ko.plurals", "plurals", "Plural and count marking", "Number can remain implicit in Korean nouns, so English plural -s and count/mass distinctions need checking.", "Take two tablet", "Take two tablets"),
            p("ko.tense", "verb_tense", "Tense, aspect, and auxiliaries", "Korean tense/aspect and question formation do not map one-to-one to English auxiliary and perfect forms.", "When the pain start?", "When did the pain start?"),
            p("ko.prepositions", "prepositions", "Particles versus prepositions", "Korean particles cover relations divided among several English prepositions.", "Are you allergic with penicillin?", "Are you allergic to penicillin?"),
            p("ko.word_order", "word_order", "SOV order and question inversion", "Korean is head-final/SOV and does not use English-style subject-auxiliary inversion.", "You are taking any medication?", "Are you taking any medication?"),
            p("ko.collocation", "word_choice", "Literal collocation transfer", "A Korean verb-noun pairing may be translated word for word instead of using the English collocation.", "Did you eat the medicine?", "Did you take the medicine?"),
            p("ko.konglish", "konglish", "Konglish false loans", "Korean English-derived words can have a different form or meaning in international English.", "We will remove the gips", "We will remove the cast"),
            p("ko.register", "register", "Politeness and clinical softening", "Korean honorific grammar does not map directly to English modal softeners, creating overly blunt or overly formal requests.", "Sit here", "Could you have a seat here?"),
        ),
        profile(
            "es", "Spanish", setOf("spanish", "español", "espanol", "castellano"),
            "English vowel reduction and schwa, b/v, y/j, final consonants, initial s-consonant clusters without an added vowel, th contrasts, and stress timing",
            p("es.articles", "articles", "Article overuse and specificity", "Spanish articles occur in some generic and possessive contexts where English uses no article or a possessive.", "The diabetes is common", "Diabetes is common"),
            p("es.plurals", "plurals", "Number and mass nouns", "Spanish countability can differ from English, especially with advice, information, and medication names.", "I need some advices", "I need some advice"),
            p("es.tense", "verb_tense", "Perfect, progressive, and auxiliary choice", "Spanish tense/aspect boundaries and question auxiliaries do not align exactly with English.", "Since when you have the pain?", "How long have you had the pain?"),
            p("es.prepositions", "prepositions", "Preposition mapping", "High-frequency Spanish prepositions map to several English choices and cannot be translated one-to-one.", "It depends of the result", "It depends on the result"),
            p("es.word_order", "word_order", "Adjective, subject, and question order", "Spanish permits post-nominal adjectives and more flexible subject placement; English questions require auxiliary inversion.", "You need a treatment different", "You need a different treatment"),
            p("es.collocation", "word_choice", "Literal collocations", "Common Spanish light verbs and body-state expressions require different English collocations.", "Make a question", "Ask a question"),
            p("es.false_friends", "word_choice", "False friends", "Cognates may look transparent but differ in meaning, such as constipado and constipated.", "I am constipated with a cold", "I have a cold"),
        ),
        profile(
            "zh", "Mandarin Chinese", setOf("chinese", "mandarin", "mandarin chinese", "中文", "普通话", "國語", "国语"),
            "final consonants and clusters, l/n and r/l where audibly relevant, th sounds, tense/lax vowels, plural and past-tense endings, aspiration, and English lexical and sentence stress",
            p("zh.articles", "articles", "Article selection", "Mandarin has classifiers and demonstratives but no English-equivalent article system.", "Patient has fever", "The patient has a fever"),
            p("zh.plurals", "plurals", "Plural and count marking", "Nouns generally do not inflect for number in Mandarin, so English plural and count/mass marking may be absent.", "three symptom", "three symptoms"),
            p("zh.tense", "verb_tense", "Tense versus aspect", "Mandarin relies strongly on aspect markers and context rather than English tense inflection and auxiliaries.", "Yesterday the pain start", "Yesterday the pain started"),
            p("zh.prepositions", "prepositions", "Coverbs and prepositions", "Mandarin spatial and temporal relations divide differently from English prepositions.", "Discuss about the result", "Discuss the result"),
            p("zh.word_order", "word_order", "Topic prominence and question order", "Mandarin keeps statement order in many questions and commonly places time/topic material early.", "You take which medicine?", "Which medicine do you take?"),
            p("zh.copula", "word_choice", "Copula and adjective predicates", "Mandarin adjective predicates do not require an English-style copula, while 是 does not map to every English be context.", "The pain very strong", "The pain is very strong"),
            p("zh.collocation", "word_choice", "Literal verb-object collocations", "A transparent Mandarin verb-object phrase may need a different conventional English verb.", "Drink the medicine", "Take the medicine"),
        ),
        profile(
            "ar", "Arabic", setOf("arabic", "العربية", "عربي"),
            "p/b, v/f where audibly relevant, th contrasts, consonant clusters without inserted vowels, vowel quality and length, final consonants, and English word stress",
            p("ar.articles", "articles", "Definiteness and article scope", "Arabic definiteness marking differs from English and can encourage the with generic or abstract nouns.", "The blood pressure is important for the health", "Blood pressure is important for health"),
            p("ar.plurals", "plurals", "Plural agreement and countability", "Arabic plural morphology and agreement patterns differ from regular English plural -s and mass nouns.", "many informations", "a lot of information"),
            p("ar.tense", "verb_tense", "Tense, aspect, and auxiliary be", "Arabic verbal aspect and English tense/aspect auxiliaries do not correspond one-to-one.", "He was went home", "He went home"),
            p("ar.prepositions", "prepositions", "Preposition selection", "Arabic prepositions have broader or different ranges than their nearest English equivalents.", "married from", "married to"),
            p("ar.word_order", "word_order", "VSO/SVO transfer and questions", "Arabic permits verb-initial clauses and forms questions without the same do-support pattern as English.", "What means this result?", "What does this result mean?"),
            p("ar.copula", "word_choice", "Present copula", "Arabic nominal sentences normally have no overt present-tense copula, which may lead to missing am/is/are.", "The patient stable", "The patient is stable"),
            p("ar.collocation", "word_choice", "Literal collocation transfer", "Arabic light-verb and causative expressions may be rendered with a non-English collocation.", "Open the oxygen", "Turn on the oxygen"),
        ),
        profile(
            "hi", "Hindi", setOf("hindi", "हिन्दी", "हिंदी", "hindustani"),
            "w/v, dental versus alveolar stops, retroflexion when it affects intelligibility, aspiration, th contrasts, consonant clusters, vowel length, and English stress timing",
            p("hi.articles", "articles", "Article selection", "Hindi has no direct equivalent of English a/an/the, so definiteness may be inferred without an article.", "Patient is in ward", "The patient is in the ward"),
            p("hi.plurals", "plurals", "Plural and countability", "Hindi number marking and English count/mass boundaries differ, especially for equipment and information.", "many equipment", "a lot of equipment"),
            p("hi.tense", "verb_tense", "Aspect and auxiliary sequences", "Hindi aspect-plus-auxiliary constructions can transfer into nonstandard English tense or progressive forms.", "I am having pain since Monday", "I have had pain since Monday"),
            p("hi.prepositions", "prepositions", "Postpositions versus prepositions", "Hindi uses postpositions whose semantic ranges do not map one-to-one onto English prepositions.", "discuss about the scan", "discuss the scan"),
            p("hi.word_order", "word_order", "SOV order and questions", "Hindi is predominantly SOV and allows question words without English do-support/inversion.", "Which medicine you take?", "Which medicine do you take?"),
            p("hi.collocation", "word_choice", "Indian-English and literal collocations", "Established local usage or a Hindi light-verb construction may not fit the intended international-English context.", "Give an exam", "Take an exam"),
        ),
        profile(
            "pt", "Portuguese", setOf("portuguese", "português", "portugues", "português brasileiro"),
            "English vowel reduction, h, th sounds, r quality only when clarity suffers, final consonants and clusters, -ed endings, consonant voicing, and stress timing",
            p("pt.articles", "articles", "Article overuse and possessives", "Portuguese uses articles with some generic nouns, names, and possessives where English does not.", "The medicine is important for the health", "Medicine is important for health"),
            p("pt.plurals", "plurals", "Countability and number", "Portuguese-English countability differs for words such as information, advice, and equipment.", "some informations", "some information"),
            p("pt.tense", "verb_tense", "Perfect and progressive aspect", "Portuguese perfect/progressive forms cover different time spans from their apparent English counterparts.", "I have this pain since Friday", "I have had this pain since Friday"),
            p("pt.prepositions", "prepositions", "Preposition mapping", "Portuguese contracted prepositions and verb complements do not translate one-to-one.", "depend of the test", "depend on the test"),
            p("pt.word_order", "word_order", "Questions and adjective position", "Portuguese can form questions with declarative order and commonly permits adjectives after nouns.", "You take insulin?", "Do you take insulin?"),
            p("pt.subjects", "word_order", "Null subjects and object pronouns", "Portuguese may omit a recoverable subject and places clitic pronouns differently from English.", "Is important to rest", "It is important to rest"),
            p("pt.false_friends", "word_choice", "False cognates and collocations", "Close-looking Portuguese and English words can differ in meaning or preferred collocation.", "I pretended to ask about allergies", "I intended to ask about allergies"),
        ),
        profile(
            "tl", "Tagalog/Filipino", setOf("tagalog", "filipino", "pilipino", "tagalog/filipino"),
            "f/p and v/b where audibly relevant, th sounds, z/s, tense/lax vowels, final consonant clusters, -ed and plural endings, and English stress placement",
            p("tl.articles", "articles", "English article selection", "Tagalog ang/ng/sa marking encodes a different set of grammatical and discourse distinctions from a/an/the.", "Patient has the diabetes", "The patient has diabetes"),
            p("tl.plurals", "plurals", "Plural marking and countability", "Tagalog plural marker mga is separate from the noun, so English noun inflection and mass nouns need explicit attention.", "two medicine", "two medicines"),
            p("tl.tense", "verb_tense", "Aspect rather than tense", "Tagalog verbs foreground completed, progressive, or contemplated aspect rather than English tense boundaries.", "I take it yesterday", "I took it yesterday"),
            p("tl.prepositions", "prepositions", "Multi-purpose relation markers", "Tagalog sa and related markers cover meanings divided among English in/on/at/to/for.", "I arrived in Monday", "I arrived on Monday"),
            p("tl.word_order", "word_order", "Predicate-first order and questions", "Tagalog frequently places predicates before arguments and does not require English do-support.", "What medicine you are taking?", "What medicine are you taking?"),
            p("tl.pronouns", "word_choice", "Pronoun gender and reference", "Tagalog siya does not distinguish he from she, so English gendered pronouns may be confused.", "She is my father", "He is my father"),
            p("tl.collocation", "word_choice", "Local or literal collocations", "A Tagalog expression or accepted Philippine-English phrase may need adjustment only when the target audience could misunderstand it.", "Open the light", "Turn on the light"),
        ),
        profile(
            "ja", "Japanese", setOf("japanese", "日本語", "にほんご"),
            "r/l, consonant clusters without epenthetic vowels, final consonants, f/h, th sounds, vowel length, mora timing versus English stress timing, and reduced vowels",
            p("ja.articles", "articles", "Article selection", "Japanese has no article system, so English definiteness and countability must be expressed in a new way.", "I ordered blood test", "I ordered a blood test"),
            p("ja.plurals", "plurals", "Plural and count marking", "Japanese nouns generally do not inflect obligatorily for number.", "two tablet", "two tablets"),
            p("ja.tense", "verb_tense", "Tense, aspect, and auxiliaries", "Japanese non-past/past and aspect forms do not map directly to English perfect, progressive, or do-support.", "How long you have this pain?", "How long have you had this pain?"),
            p("ja.prepositions", "prepositions", "Particles versus prepositions", "Japanese particles encode relations differently from English prepositions and verb complements.", "I explained him the result", "I explained the result to him"),
            p("ja.word_order", "word_order", "SOV order and omitted arguments", "Japanese is head-final/SOV and routinely omits recoverable subjects and objects.", "This medicine after meals take", "Take this medicine after meals"),
            p("ja.questions", "word_order", "Question formation", "Japanese question particles do not require English subject-auxiliary inversion or do-support.", "You have allergies?", "Do you have any allergies?"),
            p("ja.false_friends", "word_choice", "Wasei-eigo false friends", "Japan-made English and shifted loanwords may have another meaning in wider English.", "I live in a mansion", "I live in an apartment building"),
            p("ja.register", "register", "Honorifics and indirectness", "Japanese honorific and omission strategies can become overly vague or formal in English; patient care needs clear, gentle agency.", "It may be difficult", "I'm afraid we can't do that today"),
        ),
        profile(
            "id", "Indonesian", setOf("indonesian", "bahasa indonesia", "bahasa"),
            "th sounds, f/v and z/s where audibly relevant, final consonants and clusters, plural and past-tense endings, tense/lax vowels, schwa, and English word stress",
            p("id.articles", "articles", "Article selection", "Indonesian has no direct a/an/the system; specificity is expressed through context, demonstratives, or word order.", "Doctor ordered test", "The doctor ordered a test"),
            p("id.plurals", "plurals", "Plural and count marking", "Indonesian number is often unmarked after numerals and may use reduplication rather than English noun inflection.", "three day", "three days"),
            p("id.tense", "verb_tense", "Time words versus tense inflection", "Indonesian verbs do not inflect for tense, relying on context and aspect markers.", "Yesterday I feel dizzy", "Yesterday I felt dizzy"),
            p("id.prepositions", "prepositions", "Preposition mapping", "Indonesian di/ke/dari and English spatial or temporal prepositions divide meanings differently.", "I have worked here since two years", "I have worked here for two years"),
            p("id.word_order", "word_order", "Questions and noun modifiers", "Indonesian questions need no English do-support, and many modifiers follow the noun.", "You take what medicine?", "What medicine do you take?"),
            p("id.copula", "word_choice", "Copula omission", "Indonesian nominal and adjectival predicates generally do not require an equivalent of English be.", "The result normal", "The result is normal"),
            p("id.collocation", "word_choice", "Literal collocations", "Indonesian verb choices and reduplicated expressions may not select the conventional English collocation.", "Drink this medicine", "Take this medicine"),
        ),
        profile(
            "vi", "Vietnamese", setOf("vietnamese", "tiếng việt", "tieng viet", "việt ngữ"),
            "final consonants and clusters, final -s and -ed, th sounds, consonant voicing, tense/lax and reduced vowels, consonant clusters without inserted vowels, and English stress and intonation",
            p("vi.articles", "articles", "Article selection", "Vietnamese classifiers and demonstratives do not form an English-equivalent article system.", "I have headache", "I have a headache"),
            p("vi.plurals", "plurals", "Plural and count marking", "Vietnamese nouns do not inflect for plural after numerals or quantifiers.", "two week", "two weeks"),
            p("vi.tense", "verb_tense", "Time/aspect markers versus tense", "Vietnamese verbs do not inflect for English tense; time and aspect are often expressed separately or left to context.", "The pain start yesterday", "The pain started yesterday"),
            p("vi.prepositions", "prepositions", "Preposition and complement choice", "Vietnamese relational words and English prepositions divide location, time, and verb complements differently.", "I am afraid with needles", "I am afraid of needles"),
            p("vi.word_order", "word_order", "Questions and noun modification", "Vietnamese wh-words often remain in place, and many modifiers follow nouns.", "You take medicine what?", "What medicine do you take?"),
            p("vi.copula", "word_choice", "Copula distribution", "Vietnamese là is used with noun predicates but not all adjective predicates, unlike English am/is/are.", "The pain very severe", "The pain is very severe"),
            p("vi.collocation", "word_choice", "Literal verb-object collocations", "Vietnamese medical and everyday verb-object combinations may require a different English collocation.", "Drink the medicine", "Take the medicine"),
            p("vi.register", "register", "Kinship address and patient tone", "Vietnamese kinship pronouns encode age and respect; literal transfer can misidentify relationships or sound abrupt in English.", "Grandmother, sit here", "Ma'am, please have a seat here"),
        )
    ).associateBy { it.code }

    private val aliasToCode: Map<String, String> = buildMap {
        for ((code, profile) in PROFILES) {
            put(code, code)
            for (alias in profile.aliases) put(alias.lowercase(Locale.ROOT), code)
        }
    }

    /** Supports app codes, locale forms such as pt-BR/zh_CN, and localized language names. */
    fun normalizeLanguageCode(nativeLanguage: String?): String? {
        val normalized = nativeLanguage?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) return null
        aliasToCode[normalized]?.let { return it }
        val base = normalized.substringBefore('-').substringBefore('_')
        return base.takeIf { it in PROFILES }
    }

    fun profile(nativeLanguage: String?): Profile? =
        normalizeLanguageCode(nativeLanguage)?.let(PROFILES::get)

    fun promptNote(nativeLanguage: String?): String {
        val profile = profile(nativeLanguage) ?: return ""
        val lines = profile.patterns.joinToString("\n") { pattern ->
            "- ${pattern.id} [${pattern.category}]: ${pattern.transferHint} " +
                "(observed-form example only: \"${pattern.original}\" -> \"${pattern.corrected}\")"
        }
        return """
            ${profile.name.uppercase(Locale.ROOT)} L1 INTERFERENCE WATCHLIST
            Check these only AFTER transcript evidence establishes a real error. A listed pattern is
            not evidence by itself, and it must not lower a score or create a correction. Learners vary
            by region, proficiency, education, and other languages. When the observed form clearly
            matches a pattern, use its bracketed canonical category, use the listed id as pattern_id,
            and phrase l1_hypothesis as probable rather than certain. A pattern may also be a general
            developmental L2 error, so do not claim L1 causation from one occurrence. Accept legitimate
            regional English varieties unless the form impairs meaning in the stated target context.
            Otherwise set l1_hypothesis=null.
            $lines
        """.trimIndent()
    }

    private val ARTICLES = setOf("a", "an", "the")
    private val PREPOSITIONS = setOf(
        "in", "on", "at", "to", "of", "for", "with", "from", "by", "about", "since",
        "during", "after", "before", "into", "over", "under", "between"
    )
    private val EXPLANATION_KEYWORDS = listOf(
        Regex("\\barticle(s)?\\b") to "articles",
        Regex("\\bplural(s)?\\b|singular|count noun|uncountable|mass noun") to "plurals",
        Regex("\\btense\\b|past simple|present perfect|aspect\\b|subject.?verb agreement") to "verb_tense",
        Regex("\\bpreposition(s)?\\b") to "prepositions",
        Regex("word order|inversion|invert|do-support") to "word_order",
        Regex("\\bregister\\b|politeness|too (direct|blunt|formal)|softener") to "register",
        Regex("collocation|word choice|false friend|literal translation|natural phrasing|copula") to "word_choice",
        Regex("\\bkonglish\\b|korean loanword") to "konglish"
    )
    private val KOREAN_LOAN_MARKERS = setOf(
        "gips", "ringer", "hand phone", "handphone", "skinship", "one shot", "burberry",
        "y-shirt", "health club", "eye shopping", "self camera", "selca"
    )

    internal fun tokens(value: String): List<String> = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z\\s]"), " ")
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)

    /**
     * Refines a category from correction evidence. This classifies the edit, not its cause: the
     * learner's L1 only enables tightly scoped language-specific lexical checks.
     */
    fun classify(
        nativeLanguage: String?,
        original: String,
        corrected: String,
        explanation: String,
        category: String
    ): String? {
        if (profile(nativeLanguage) == null) return null
        L1Stats.normalizeCategory(category)?.let { if (it != "other") return it }

        val lowerExplanation = explanation.lowercase(Locale.ROOT)
        for ((regex, canonical) in EXPLANATION_KEYWORDS) {
            if (regex.containsMatchIn(lowerExplanation)) return canonical
        }

        val originalTokens = tokens(original)
        val correctedTokens = tokens(corrected)
        if (originalTokens.isEmpty() || correctedTokens.isEmpty()) return null

        if (normalizeLanguageCode(nativeLanguage) == "ko") {
            val lowerOriginal = original.lowercase(Locale.ROOT)
            if (KOREAN_LOAN_MARKERS.any { marker ->
                    if (' ' in marker) marker in lowerOriginal else marker in originalTokens
                }) return "konglish"
        }

        val added = correctedTokens.toMutableList().also { list -> originalTokens.forEach(list::remove) }
        val removed = originalTokens.toMutableList().also { list -> correctedTokens.forEach(list::remove) }
        if (added.isEmpty() && removed.isEmpty() && originalTokens != correctedTokens) return "word_order"
        // Correction evidence makes both directions useful: missing articles/plurals are common in
        // articleless L1s, while overuse and mass-noun plurals are prominent in several others.
        if (added.any(ARTICLES::contains) || removed.any(ARTICLES::contains)) return "articles"
        if (removed.any { old -> added.any { new -> new == old + "s" || new == old + "es" } } ||
            added.any { new -> removed.any { old -> old == new + "s" || old == new + "es" } }
        ) return "plurals"
        if (added.any(PREPOSITIONS::contains) && removed.any(PREPOSITIONS::contains)) return "prepositions"
        return null
    }
}

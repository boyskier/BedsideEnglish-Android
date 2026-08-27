package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.db.ErrorItemEntity

object L1InterferenceAnalyzer {
    fun analyzeMistakeGenome(errors: List<ErrorItemEntity>): Map<String, Int> {
        val genome = mutableMapOf<String, Int>()
        
        for (error in errors) {
            val rawCategory = error.category.lowercase()
            
            val mappedCategory = when {
                rawCategory.contains("article") -> "Articles (a/the)"
                rawCategory.contains("plural") -> "Pluralization"
                rawCategory.contains("verb_tense") || rawCategory.contains("tense") -> "Verb Tense"
                rawCategory.contains("preposition") -> "Prepositions"
                rawCategory.contains("word_order") -> "Word Order"
                rawCategory.contains("word_choice") || rawCategory.contains("vocabulary") -> "Vocabulary/Word Choice"
                rawCategory.contains("konglish") || rawCategory.contains("conglish") -> "L1 Direct Translation"
                rawCategory.contains("register") -> "Register/Tone"
                rawCategory.contains("pronunciation") -> "Pronunciation"
                else -> {
                    val expl = error.explanation.lowercase()
                    when {
                        expl.contains("article") || expl.contains("a/an") || expl.contains("the") -> "Articles (a/the)"
                        expl.contains("tense") || expl.contains("past") || expl.contains("future") -> "Verb Tense"
                        expl.contains("preposition") || expl.contains("in/on/at") -> "Prepositions"
                        expl.contains("plural") || expl.contains("singular") -> "Pluralization"
                        expl.contains("vocabulary") || expl.contains("word choice") -> "Vocabulary/Word Choice"
                        expl.contains("conglish") || expl.contains("konglish") || expl.contains("literal translation") -> "L1 Direct Translation"
                        expl.contains("word order") -> "Word Order"
                        expl.contains("register") || expl.contains("tone") -> "Register/Tone"
                        expl.contains("pronunciation") -> "Pronunciation"
                        else -> "Other Grammar"
                    }
                }
            }
            
            genome[mappedCategory] = (genome[mappedCategory] ?: 0) + error.seenCount + 1
        }
        
        return genome.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }
}

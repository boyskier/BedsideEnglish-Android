package com.example.medvoicetrainer.ui

/**
 * Controls which practice entry points are visible. It is deliberately a presentation choice,
 * not an account type: switching never deletes or filters the learner's history or SRS data.
 */
enum class PracticeExperience(val storageValue: String) {
    ALL_FEATURES("all_features"),
    EVERYDAY_ENGLISH("everyday_english"),

    /**
     * 한국 의사국시 CPX (한국어): the Korean-language track. Home becomes the CPX station picker
     * and History its own score history; the English tools are hidden, not removed.
     */
    KOREAN_CPX("korean_cpx");

    companion object {
        const val SETTING_KEY = "practice_experience"

        fun fromStorage(value: String?): PracticeExperience? =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() }
    }
}

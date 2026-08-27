package com.example.medvoicetrainer.ui

/**
 * Controls which practice entry points are visible. It is deliberately a presentation choice,
 * not an account type: switching never deletes or filters the learner's history or SRS data.
 */
enum class PracticeExperience(val storageValue: String) {
    ALL_FEATURES("all_features"),
    EVERYDAY_ENGLISH("everyday_english");

    companion object {
        const val SETTING_KEY = "practice_experience"

        fun fromStorage(value: String?): PracticeExperience? =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() }
    }
}

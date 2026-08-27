package com.example.medvoicetrainer.ui

/** Pure race guards shared by the session finisher and its Compose host. */
internal object SessionCompletionOwnership {
    fun stillOwnsUi(finishingGeneration: Long, currentGeneration: Long): Boolean =
        finishingGeneration == currentGeneration

    fun shouldPresentFeedback(hasEvaluation: Boolean, hasActiveSession: Boolean): Boolean =
        hasEvaluation && !hasActiveSession
}

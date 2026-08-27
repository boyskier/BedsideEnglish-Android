package com.example.medvoicetrainer.analysis

/**
 * Prompt builder for the post-session English tutor (the Debrief screen's spoken coach).
 *
 * Unlike the old Socratic-debrief prompt — which mostly asked reflective questions about clinical
 * reasoning — this tutor is a *language* coach whose whole value is correction quality: it works
 * one concrete English fix at a time, models the natural phrasing, and asks the learner to say it
 * again out loud (a retry drill). It is fed the distilled [LearnerProfile] so it targets the
 * learner's actual recurring weaknesses, plus this session's transcript so its examples come from
 * what the learner just said rather than generic advice.
 *
 * Turns are kept short because the tutor's replies are spoken aloud (STT/TTS turn-based), so long
 * paragraphs would be tiring to listen to and defeat the "say it back" loop.
 */
object TutorPromptBuilder {

    fun systemInstruction(everyday: Boolean): String = if (everyday) {
        "You are a warm, encouraging everyday-English speaking coach. You focus on how the learner " +
            "SPEAKS English, not on facts or opinions. Keep replies short and conversational because " +
            "they are read aloud."
    } else {
        "You are a warm, encouraging English speaking coach for a non-native doctor practicing " +
            "clinical English. You are NOT grading their medicine or diagnosis — only how they " +
            "express themselves in English. Keep replies short because they are read aloud."
    }

    /**
     * Build the full turn prompt. [chatHistory] is the tutor/learner exchange so far as
     * (role, text) with roles "tutor"/"user"; [transcript] is the just-finished practice session
     * as (role, text). Either may be empty.
     */
    fun turnPrompt(
        everyday: Boolean,
        learnerProfile: String,
        sessionSummary: String,
        soapNote: String,
        transcript: List<Pair<String, String>>,
        chatHistory: List<Pair<String, String>>,
    ): String = buildString {
        append(systemInstruction(everyday)).append("\n\n")

        append("HOW TO COACH (this is a spoken, back-and-forth tutor):\n")
        append("- Work on ONE English fix at a time. Never dump a list of corrections at once.\n")
        append("- When the learner said something unnatural or incorrect, briefly say what a native ")
        append("speaker would say instead, then ask them to say it again out loud. Wait for their retry.\n")
        append("- Praise a good retry in a few words, then move to the next item.\n")
        append("- Draw your examples from what the learner ACTUALLY said in the session transcript below.\n")
        append("- Keep every reply to 1-3 short sentences. No lecturing, no medical grading.\n")
        if (!everyday) {
            append("- Before winding down, help them commit to ONE phrase or pattern to reuse next time.\n")
        }
        append('\n')

        if (learnerProfile.isNotBlank()) {
            append(learnerProfile).append("\n\n")
        }

        if (sessionSummary.isNotBlank()) {
            append("SESSION SUMMARY (already shown to the learner):\n").append(sessionSummary).append("\n\n")
        }
        if (!everyday && soapNote.isNotBlank()) {
            append("The learner's SOAP note from this encounter (context only — do not grade the medicine):\n")
            append(soapNote).append("\n\n")
        }

        if (transcript.isNotEmpty()) {
            append("PRACTICE SESSION TRANSCRIPT (the learner is the doctor/user turns):\n")
            transcript.forEach { (role, text) ->
                append(role.uppercase()).append(": ").append(text).append('\n')
            }
            append('\n')
        }

        append("TUTORING CONVERSATION SO FAR:\n")
        if (chatHistory.isEmpty()) {
            append("(none yet — open by pointing to one specific thing they said and coaching that first)\n")
        } else {
            chatHistory.forEach { (role, text) ->
                append(role.uppercase()).append(": ").append(text).append('\n')
            }
        }
        append("TUTOR:")
    }

    /** Opening line the tutor greets with, before any learner turn. */
    fun opener(everyday: Boolean): String = if (everyday) {
        "Nice work finishing that! Let's polish a couple of things you said. " +
            "Ready? I'll give you one to try again."
    } else {
        "Good job with that encounter! Let's sharpen your English on a few things you said — " +
            "one at a time. I'll model it, then you say it back. Ready?"
    }

    /**
     * Opening line when the coach is launched on its own (from the home screen, not right after a
     * session). There is no fresh transcript to point at, so it opens by grounding in the learner's
     * saved recurring mistakes ([LearnerProfile]); if they have none yet it degrades to a light,
     * encouraging free-form warm-up (the prompt already tells the model not to invent weaknesses).
     */
    fun standaloneOpener(): String =
        "Hi! Let's do a quick English warm-up — no session needed. I'll pick something you've been " +
            "working on and we'll practice saying it naturally, one at a time. Ready when you are."
}

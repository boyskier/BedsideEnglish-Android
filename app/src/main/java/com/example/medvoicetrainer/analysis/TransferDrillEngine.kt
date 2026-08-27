package com.example.medvoicetrainer.analysis

/**
 * Builds the SPOKEN transfer challenge for an SRS card: instead of flipping a flashcard and
 * self-grading silently, the learner is dropped into a small, *new* situation and must produce
 * one natural sentence that uses the corrected form — the "Transfer" half of the redo→transfer
 * loop (memorising a fix and retrieving it in a fresh context are different skills).
 *
 * Scenario frames are deterministic: the frame pool is fixed per domain, and [variantIndex]
 * (callers pass the item's seenCount) rotates through it, so every review of the same mistake
 * gets a different situation without any API call or randomness. Pure Kotlin — JVM-testable.
 */
object TransferDrillEngine {

    data class TransferPrompt(
        /** Stable id of the chosen frame, for logging/tests. */
        val frameId: String,
        /** The situation the learner must respond to, read aloud to them. */
        val scenario: String,
        /** What they must do with the target phrase. */
        val instruction: String,
        val targetPhrase: String
    )

    private data class Frame(val id: String, val text: String)

    private enum class Function { QUESTION, INSTRUCTION, EMPATHY, STATEMENT }

    /**
     * Frames are deliberately generic: any corrected sentence a clinical session can produce
     * (a question to a patient, an instruction, an empathy line, a presentation phrase) must
     * plausibly fit every frame in its pool.
     */
    private val CLINICAL_FRAMES = listOf(
        Frame("worried_patient", "A worried patient looks at you and asks what is going on."),
        Frame("didnt_understand", "Your patient says: \"Sorry doctor, I didn't understand — could you say that again?\""),
        Frame("attending_one_liner", "Your attending stops you in the hallway: \"Quickly — one sentence.\""),
        Frame("nurse_phone", "A nurse calls you about this patient and waits for your instruction."),
        Frame("family_member", "The patient's daughter steps in and asks you to explain to her directly."),
        Frame("first_meeting", "You walk into the exam room and meet this patient for the first time."),
        Frame("end_of_visit", "The visit is ending; the patient reaches for the door and looks back at you."),
        Frame("anxious_call", "A patient calls the clinic after hours, clearly anxious, and asks you what to do."),
        Frame("interpreter_gone", "The interpreter just left, and now you must say it to the patient yourself, simply."),
        Frame("osce_examiner", "An OSCE examiner watches silently as the simulated patient waits for you to speak.")
    )

    private val EVERYDAY_FRAMES = listOf(
        Frame("neighbor_hallway", "Your neighbor stops you in the hallway for a quick chat."),
        Frame("coworker_lunch", "A coworker at lunch asks what you were just talking about."),
        Frame("shop_counter", "You are at a counter and it is your turn to speak."),
        Frame("phone_followup", "You are on the phone and the other person says: \"Sorry, what was that?\""),
        Frame("new_acquaintance", "Someone you just met is making friendly small talk with you."),
        Frame("plan_change", "Plans just changed, and you need to tell your friend right away.")
    )

    private fun communicativeFunction(text: String): Function {
        val normalized = text.trim().lowercase()
        if (normalized.endsWith("?")) return Function.QUESTION
        if (
            normalized.startsWith("please ") ||
            normalized.startsWith("take ") ||
            normalized.startsWith("do not ") ||
            normalized.startsWith("don't ") ||
            normalized.startsWith("you should ") ||
            normalized.startsWith("you need ")
        ) return Function.INSTRUCTION
        if (
            listOf("sorry", "understand", "worried", "difficult", "concern")
                .any { it in normalized }
        ) return Function.EMPATHY
        return Function.STATEMENT
    }

    private fun functionFrames(domain: String, function: Function): List<Frame> {
        val everyday = domain.trim().lowercase() == "everyday"
        if (everyday) {
            return when (function) {
                Function.QUESTION -> listOf(
                    Frame("everyday_information_gap", "You need this exact information to continue the conversation."),
                    Frame("everyday_clarifying_question", "The other person left out one important detail. Ask for it naturally.")
                )
                Function.INSTRUCTION -> listOf(
                    Frame("everyday_request", "The other person needs one clear request or next step."),
                    Frame("everyday_plan", "A plan has changed. State the next action clearly.")
                )
                Function.EMPATHY -> listOf(
                    Frame("everyday_support", "A friend has just shared a concern and waits for your response."),
                    Frame("everyday_reassure", "The other person looks uncertain. Respond supportively.")
                )
                Function.STATEMENT -> EVERYDAY_FRAMES
            }
        }
        return when (function) {
            Function.QUESTION -> listOf(
                Frame("clinical_history_gap", "A patient is in front of you and this information is still missing."),
                Frame("clinical_safety_check", "Before proceeding, ask the patient this safety-relevant question.")
            )
            Function.INSTRUCTION -> listOf(
                Frame("clinical_next_step", "The patient needs one unambiguous next step before leaving."),
                Frame("clinical_teach_back", "Give the instruction clearly, then imagine asking the patient to repeat it back.")
            )
            Function.EMPATHY -> listOf(
                Frame("clinical_empathy", "The patient has just expressed fear or frustration. Respond before continuing."),
                Frame("clinical_acknowledge", "Acknowledge the patient's concern in one natural sentence.")
            )
            Function.STATEMENT -> CLINICAL_FRAMES
        }
    }

    fun buildTransferPrompt(
        corrected: String,
        domain: String = "clinical",
        variantIndex: Int = 0,
        category: String = "",
        patternId: String = ""
    ): TransferPrompt {
        val function = communicativeFunction(corrected)
        val pool = functionFrames(domain, function)
        val index = ((variantIndex % pool.size) + pool.size) % pool.size
        val frame = pool[index]
        val target = corrected.trim()
        val ruleCue = patternId.ifBlank { category }.takeIf { it.isNotBlank() }
        return TransferPrompt(
            frameId = frame.id,
            scenario = frame.text,
            instruction = buildString {
                append("Respond out loud in this situation. Preserve the reusable form: \"$target\"")
                ruleCue?.let { append(" (focus: $it)") }
            },
            targetPhrase = target
        )
    }
}

package com.example.medvoicetrainer.voice

import org.json.JSONObject

/**
 * Perceived gender of a synthesized voice, used only to (a) pick a plausible voice for a case's
 * stated patient gender in Encounter/Follow-up modes and (b) let the learner pick the counterpart's gender
 * for gender-sensitive Survival scenarios (Dating & Romance). Neither Google nor OpenAI publishes
 * an official gender label for their prebuilt voices — these assignments are this app's own
 * best-effort classification from each provider's public voice-style descriptions and widely
 * reported listening comparisons, not a vendor guarantee.
 */
enum class VoiceGender { MALE, FEMALE }

data class VoiceOption(val name: String, val gender: VoiceGender)

/**
 * The full set of prebuilt voices each realtime voice provider exposes, so a session can pick a
 * different one at random instead of every conversation sounding like the same person regardless
 * of accent/delivery-style instructions (those only change *how* the fixed voice talks, not which
 * voice it is).
 */
object VoiceCatalog {

    // Gemini Live API prebuilt voices (BidiGenerateContentSetup.generationConfig.speechConfig.
    // voiceConfig.prebuiltVoiceConfig.voiceName). The original 8 (Zephyr..Zubenelgenubi's peers)
    // are guaranteed on every Live model generation; the rest of the 30-voice TTS set is exposed
    // by the native-audio Live models this app targets (see GeminiLiveClient.CANDIDATE_LIVE_MODELS
    // — both current candidates are native-audio generation).
    val GEMINI_VOICES: List<VoiceOption> = listOf(
        VoiceOption("Zephyr", VoiceGender.FEMALE),
        VoiceOption("Puck", VoiceGender.MALE),
        VoiceOption("Charon", VoiceGender.MALE),
        VoiceOption("Kore", VoiceGender.FEMALE),
        VoiceOption("Fenrir", VoiceGender.MALE),
        VoiceOption("Leda", VoiceGender.FEMALE),
        VoiceOption("Orus", VoiceGender.MALE),
        VoiceOption("Aoede", VoiceGender.FEMALE),
        VoiceOption("Callirrhoe", VoiceGender.FEMALE),
        VoiceOption("Autonoe", VoiceGender.FEMALE),
        VoiceOption("Enceladus", VoiceGender.MALE),
        VoiceOption("Iapetus", VoiceGender.MALE),
        VoiceOption("Umbriel", VoiceGender.MALE),
        VoiceOption("Algieba", VoiceGender.MALE),
        VoiceOption("Despina", VoiceGender.FEMALE),
        VoiceOption("Erinome", VoiceGender.FEMALE),
        VoiceOption("Algenib", VoiceGender.MALE),
        VoiceOption("Rasalgethi", VoiceGender.MALE),
        VoiceOption("Laomedeia", VoiceGender.FEMALE),
        VoiceOption("Achernar", VoiceGender.FEMALE),
        VoiceOption("Alnilam", VoiceGender.MALE),
        VoiceOption("Schedar", VoiceGender.MALE),
        VoiceOption("Gacrux", VoiceGender.FEMALE),
        VoiceOption("Pulcherrima", VoiceGender.FEMALE),
        VoiceOption("Achird", VoiceGender.MALE),
        VoiceOption("Zubenelgenubi", VoiceGender.MALE),
        VoiceOption("Vindemiatrix", VoiceGender.FEMALE),
        VoiceOption("Sadachbia", VoiceGender.MALE),
        VoiceOption("Sadaltager", VoiceGender.MALE),
        VoiceOption("Sulafat", VoiceGender.FEMALE),
    )

    // OpenAI Realtime API voices (session.audio.output.voice). fable/onyx/nova exist on the
    // older tts-1 endpoint but are not offered on the Realtime models, so they're excluded here.
    val OPENAI_VOICES: List<VoiceOption> = listOf(
        VoiceOption("alloy", VoiceGender.MALE),
        VoiceOption("ash", VoiceGender.MALE),
        VoiceOption("ballad", VoiceGender.MALE),
        VoiceOption("cedar", VoiceGender.MALE),
        VoiceOption("coral", VoiceGender.FEMALE),
        VoiceOption("echo", VoiceGender.MALE),
        VoiceOption("marin", VoiceGender.FEMALE),
        VoiceOption("sage", VoiceGender.FEMALE),
        VoiceOption("shimmer", VoiceGender.FEMALE),
        VoiceOption("verse", VoiceGender.MALE),
    )

    fun voicesFor(provider: String): List<VoiceOption> =
        if (provider == "openai") OPENAI_VOICES else GEMINI_VOICES

    /** Random voice for [provider], constrained to [gender] when given (falls back to the full pool if nothing matches). */
    fun randomVoice(provider: String, gender: VoiceGender? = null): VoiceOption {
        val pool = voicesFor(provider)
        val candidates = if (gender != null) pool.filter { it.gender == gender } else pool
        return candidates.ifEmpty { pool }.random()
    }

    /**
     * Deterministic voice pick for [provider], constrained to [gender] when given, indexed by a
     * hash of [seedKey] instead of [kotlin.random.Random]. Used where the *same* voice must come
     * back for the same input every time (e.g. Listening Lab narration keyed by drill+accent+
     * gender) so a cached clip and a freshly-synthesized one for the same key always agree, and
     * repeated calls don't silently re-roll the voice.
     */
    fun voiceForKey(provider: String, seedKey: String, gender: VoiceGender? = null): VoiceOption {
        val pool = voicesFor(provider)
        val candidates = (if (gender != null) pool.filter { it.gender == gender } else pool).ifEmpty { pool }
        val index = Math.floorMod(seedKey.hashCode(), candidates.size)
        return candidates[index]
    }

    fun parseGender(raw: String?): VoiceGender? = when (raw?.trim()?.lowercase()) {
        "male", "m" -> VoiceGender.MALE
        "female", "f" -> VoiceGender.FEMALE
        else -> null
    }

    /**
     * The gender the session's voice should be constrained to, if any: an Encounter or Follow-up
     * case's own `gender` field (so a female patient doesn't get a male voice), or a Survival case's
     * learner-chosen `voice_gender_preference` (see PracticeScreen's Dating & Romance picker).
     * Every other mode/field combination is unconstrained — the voice is picked fully at random.
     */
    fun genderConstraintFor(mode: String, caseJson: String): VoiceGender? {
        val json = try {
            JSONObject(caseJson)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return null
        }
        val raw = when (mode) {
            "encounter", "follow_up" -> json.optString("gender", "")
            "survival" -> json.optString("voice_gender_preference", "")
            else -> ""
        }
        return parseGender(raw)
    }
}

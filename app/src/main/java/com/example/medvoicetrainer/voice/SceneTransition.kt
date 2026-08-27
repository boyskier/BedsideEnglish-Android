package com.example.medvoicetrainer.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * Scene-transition protocol for "Survival English — Advanced Beta" (see
 * docs/plans/SURVIVAL_ADVANCED_BETA_PLAN.md).
 *
 * The model proposes a scene change (walk somewhere, skip time, a new person joins, or the learner
 * runs an errand alone) through a Gemini Live **function call**; the app turns that call into an
 * on-screen chip, and the learner's tap becomes the function *response*. Nothing here is wired into
 * an ordinary Survival session: the tool declaration is only emitted when a client was explicitly
 * opted in (see [VoiceClient.enableSceneTransitions]), and every listener callback defaults to a
 * no-op, so removing the beta tab removes the feature.
 *
 * Everything in this file is plain Kotlin + `org.json`, deliberately Android-free, so the wire
 * shapes and the app-side rate limiting are covered by ordinary JVM unit tests.
 */
/**
 * Whether a session on this voice backend can actually carry the scene-transition protocol.
 *
 * The beta system prompt's rules block instructs the model to call `propose_scene_transition`, so
 * it may only be injected where that function is genuinely declared to the provider. Gemini Live
 * declares it ([VoiceClient.enableSceneTransitions]) and the offline mock implements the whole
 * proposal loop in process; OpenAI Realtime does neither, and a model told to call a tool it does
 * not have will announce the suggestion out loud instead — the one behaviour this feature must
 * never produce. Where this returns false the caller drops the transition menu from the case, which
 * makes the prompt omit the rules entirely and the session run as ordinary Survival English.
 *
 * Takes [shouldStartLiveMicrophone]'s inputs because that is what decides which client a
 * [VoiceManager] actually builds: with no API key, or in mock mode, the session runs on
 * [MockVoiceClient] whatever the configured provider says.
 */
internal fun supportsSceneTransitions(provider: String, isMock: Boolean, apiKey: String): Boolean =
    if (!shouldStartLiveMicrophone(isMock, apiKey)) true
    else provider.trim().lowercase(java.util.Locale.ROOT) != "openai"

enum class SceneTransitionType(val wire: String) {
    /** Same person, new place — handled by injecting a stage direction, no reconnect. */
    MOVE("move"),

    /** Same person, later moment — handled by injecting a stage direction, no reconnect. */
    TIME_SKIP("time_skip"),

    /** A different person takes over the scene — handled by reconnecting with a new voice. */
    NEW_CHARACTER("new_character"),

    /** The learner goes somewhere alone and comes back to report (Stage 3's relay task). */
    SOLO_ERRAND("solo_errand"),

    /** Back to the person the learner was talking to before a [NEW_CHARACTER] switch. */
    RETURN_TO_PREVIOUS("return_to_previous");

    /** True when applying this transition requires a fresh provider session (new voice). */
    val requiresReconnect: Boolean
        get() = this == NEW_CHARACTER || this == RETURN_TO_PREVIOUS

    companion object {
        fun fromWire(value: String?): SceneTransitionType? =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * How a proposal ended, and therefore what the model is told when its function call is answered.
 *
 * The three non-accepting outcomes were once a single `declined`, which conflated things the model
 * should treat very differently: a learner who tapped "Not now" has rejected that idea, while one
 * who never looked at the chip has not rejected anything, and a proposal the app's own rate limit
 * bounced was never even offered. The beta prompt tells the model to be reluctant after a refusal,
 * so collapsing all three taught it to give up after a proposal nobody ever saw.
 */
enum class SceneTransitionOutcome(val wire: String) {
    ACCEPTED("accepted"),

    /** The learner explicitly said no. The one outcome that should make the model back off. */
    DECLINED_BY_USER("declined_by_user"),

    /** Nobody answered: the chip timed out, a newer one replaced it, or the session is ending. */
    EXPIRED_UNANSWERED("expired_unanswered"),

    /** [SceneTransitionGate] bounced it before it ever reached the screen: too soon, not too many. */
    RATE_LIMITED("rate_limited"),

    /**
     * [SceneTransitionGate.MAX_PROPOSALS_PER_SESSION] is spent — no proposal will be shown again
     * for the rest of this conversation.
     *
     * Split out of [RATE_LIMITED] because the two ask for opposite things. A rate limit is "not
     * yet", and a model told that reasonably tries again later; a spent budget is "not ever
     * again", and the same model kept calling a function that could no longer succeed — burning
     * tokens on calls the app silently swallowed, while the learner saw nothing at all.
     */
    BUDGET_EXHAUSTED("budget_exhausted"),

    /**
     * The proposal contradicted the scene the session is actually in — a return trip with nobody to
     * return to, or a character switch that never said who the new person is. See
     * [SceneTransitionProtocol.rejectionReasonFor]; the specific reason travels with the response.
     */
    NOT_APPLICABLE("not_applicable"),
    ;

    val isAccepted: Boolean get() = this == ACCEPTED

    /**
     * What the model should actually *do* about this outcome, sent alongside [wire].
     *
     * The system prompt already explains what the outcome words mean, but a prompt written at setup
     * competes with everything said since, while this arrives attached to the call it answers. It
     * costs a few dozen tokens per proposal — at most a handful per session — and is the difference
     * between a model that infers "rate_limited" means "wait" and one that is told so.
     */
    val note: String get() = when (this) {
        ACCEPTED ->
            "The user accepted. A [SCENE CHANGE] stage direction follows immediately — treat it as " +
                "real and continue in the new scene. Never mention this exchange."

        DECLINED_BY_USER ->
            "The user turned this idea down. Let it go, stay where you are, and be very reluctant " +
                "to suggest another change."

        EXPIRED_UNANSWERED ->
            "Nobody refused this — the user never answered it. Carry on exactly as you are; a " +
                "genuinely better moment later is still fair game."

        RATE_LIMITED ->
            "The app blocked this before the user saw it: it came too soon — either too early in " +
                "the conversation or too close to the last suggestion. Nobody refused anything, " +
                "and later suggestions are still welcome. Do not propose another change of your " +
                "own for at least two minutes. If the user asks for one themselves, call this " +
                "again straight away with requested_by_user=true."

        BUDGET_EXHAUSTED ->
            "This conversation has used up all the scene changes you may suggest on your own. " +
                "Nobody refused anything, but no further idea of yours can reach the user in this " +
                "conversation: stop proposing changes and stay in the scene you are in. The one " +
                "exception is a change the user asks for themselves — that is still allowed, and " +
                "still reaches them, so keep calling this with requested_by_user=true when they do."

        NOT_APPLICABLE ->
            "This does not fit the scene as it stands, so the user never saw it."
    }

    /**
     * How the model should react to *this response frame* when the tool was declared
     * [SceneTransitionProtocol.BEHAVIOR_NON_BLOCKING] (ignored otherwise).
     *
     * Every outcome is SILENT, and deliberately so: the function response is bookkeeping between
     * the app and the model, never something the character should remark on. What actually drives
     * an accepted transition is the stage direction sent straight afterwards, and a refusal is
     * supposed to leave no trace in the conversation at all. The field exists per-outcome rather
     * than as one constant because it is the natural place to tune if that ever stops being true.
     */
    val scheduling: String get() = SceneTransitionProtocol.SCHEDULING_SILENT
}

/**
 * One pending transition the model asked for. [id] is the provider's function-call id and is what
 * the accept/decline response must echo back; [name] is the declared function name (kept so the
 * response frame can mirror it verbatim rather than assuming the constant).
 */
data class SceneTransitionProposal(
    val id: String,
    val type: SceneTransitionType,
    val title: String,
    val description: String,
    val newCharacterRole: String = "",
    val place: String = "",
    /**
     * The model's claim that the learner themselves just asked for this change, in English, in the
     * conversation — "can we go inside?", "let's come back tomorrow", "I should ask at the desk".
     *
     * This is the whole point of the beta's most valuable path: the utterance that earns the scene
     * change *is* the practice, so a change the learner talked their way into must not be rationed
     * the way one the model thought of is. [SceneTransitionGate] therefore exempts it from the
     * opening lead-in and from the session budget.
     *
     * A flag the model sets is a claim, not a fact, and it unlocks a limit — so nothing should act
     * on it alone. `VoiceManager` corroborates it against a final learner turn it saw for itself
     * before passing it to the gate.
     */
    val requestedByUser: Boolean = false,
    val name: String = SceneTransitionProtocol.TOOL_NAME,
)

object SceneTransitionProtocol {

    const val TOOL_NAME = "propose_scene_transition"

    /**
     * Gemini Live's asynchronous function-call mode. With the default (blocking) behaviour the
     * model stops speaking the instant it makes a call and stays mute until the response arrives —
     * which, in this feature, is however long the learner leaves the chip on screen (up to
     * [SceneTransitionGate.PROPOSAL_TIMEOUT_MILLIS]). The prompt tells the model to propose a change
     * "silently and keep talking as if nothing happened"; under blocking calls that instruction is
     * physically impossible to obey, so every proposal froze the conversation. Declaring the tool
     * NON_BLOCKING is what makes the promised behaviour actually available.
     */
    const val BEHAVIOR_NON_BLOCKING = "NON_BLOCKING"

    /** `scheduling` value telling the model to absorb a function response without reacting aloud. */
    const val SCHEDULING_SILENT = "SILENT"

    /** Marker prefix for stage directions, both on the wire and in the saved transcript. */
    const val STAGE_PREFIX = "[SCENE CHANGE]"

    /**
     * Transcript role used for the stage-direction lines a transition writes into the session
     * record. Deliberately a third role rather than a fake patient/doctor turn: it must never be
     * scored as something the learner or their partner said, and it must never be counted as a
     * spoken turn for cost/coverage purposes.
     */
    const val NARRATOR_ROLE = "narrator"

    /**
     * The `tools` value for a Gemini Live setup message. Emitted **only** for a session that opted
     * in — an ordinary Survival session's setup message is byte-for-byte what it always was.
     *
     * [nonBlocking] adds the [BEHAVIOR_NON_BLOCKING] field. It is a parameter rather than a
     * constant because the declaration and the responses have to agree for a whole session: a
     * `scheduling` value only means anything on a call that was declared asynchronous, so whoever
     * builds the setup message owns the decision and [buildToolResponse] is told what was chosen.
     */
    fun toolsDeclaration(nonBlocking: Boolean = true): JSONArray = JSONArray().put(
        JSONObject().put(
            "functionDeclarations",
            JSONArray().put(
                JSONObject().apply {
                    put("name", TOOL_NAME)
                    if (nonBlocking) put("behavior", BEHAVIOR_NON_BLOCKING)
                    put(
                        "description",
                        "Propose a change of scene when the conversation has reached a point where " +
                            "moving somewhere, skipping ahead in time, bringing in a different person, " +
                            "or sending the other person off on a short errand would happen naturally " +
                            "in real life. The user sees the proposal as a tappable card and decides. " +
                            "Never mention the proposal out loud. Carry on with the conversation " +
                            "immediately after calling this — the user's answer arrives later, or " +
                            "not at all, and nothing about the scene changes until it does.",
                    )
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put(
                                        "type",
                                        JSONObject().apply {
                                            put("type", "STRING")
                                            put(
                                                "enum",
                                                JSONArray().apply {
                                                    SceneTransitionType.entries.forEach { put(it.wire) }
                                                },
                                            )
                                            put("description", "Which kind of scene change this is.")
                                        },
                                    )
                                    put(
                                        "title",
                                        JSONObject()
                                            .put("type", "STRING")
                                            .put(
                                                "description",
                                                "A short English phrase shown on the card, e.g. " +
                                                    "\"Walk to the information desk?\".",
                                            ),
                                    )
                                    put(
                                        "description",
                                        JSONObject()
                                            .put("type", "STRING")
                                            .put(
                                                "description",
                                                "One or two sentences describing the scene after the " +
                                                    "change, written for the actors in it.",
                                            ),
                                    )
                                    put(
                                        "new_character_role",
                                        JSONObject()
                                            .put("type", "STRING")
                                            .put(
                                                "description",
                                                "For type=new_character only: who the new person is, " +
                                                    "e.g. \"front desk staff\".",
                                            ),
                                    )
                                    put(
                                        "place",
                                        JSONObject()
                                            .put("type", "STRING")
                                            .put(
                                                "description",
                                                "For type=move or type=solo_errand: where the scene " +
                                                    "moves to, e.g. \"the pharmacy counter\".",
                                            ),
                                    )
                                    put(
                                        "requested_by_user",
                                        JSONObject()
                                            .put("type", "BOOLEAN")
                                            .put(
                                                "description",
                                                "True only when the user themselves just asked for " +
                                                    "this change in the conversation, e.g. \"can we " +
                                                    "go inside?\" or \"let's come back tomorrow\". " +
                                                    "Their own requests are not rationed. Never set " +
                                                    "it for an idea of your own.",
                                            ),
                                    )
                                },
                            )
                            put("required", JSONArray().put("type").put("title").put("description"))
                        },
                    )
                },
            ),
        ),
    )

    /**
     * Parse a `toolCall` server message into proposals. Unknown function names and unknown
     * transition types are dropped rather than guessed at — an unparseable call is answered with a
     * decline by the caller, which keeps the model unblocked.
     */
    fun parseToolCall(message: JSONObject): List<SceneTransitionProposal> {
        val toolCall = message.optJSONObject("toolCall") ?: return emptyList()
        val calls = toolCall.optJSONArray("functionCalls") ?: return emptyList()
        val parsed = mutableListOf<SceneTransitionProposal>()
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val name = call.optString("name").trim()
            if (name != TOOL_NAME) continue
            val id = call.optString("id").trim()
            if (id.isEmpty()) continue
            val args = call.optJSONObject("args") ?: JSONObject()
            val type = SceneTransitionType.fromWire(args.optString("type")) ?: continue
            val title = args.optString("title").trim()
            val description = args.optString("description").trim()
            parsed += SceneTransitionProposal(
                id = id,
                type = type,
                title = title.ifEmpty { defaultTitleFor(type) },
                description = description,
                newCharacterRole = args.optString("new_character_role").trim(),
                place = args.optString("place").trim(),
                requestedByUser = args.optBoolean("requested_by_user", false),
                name = name,
            )
        }
        return parsed
    }

    /** Ids the provider withdrew before the learner answered (`toolCallCancellation`). */
    fun parseToolCallCancellation(message: JSONObject): List<String> {
        val cancellation = message.optJSONObject("toolCallCancellation") ?: return emptyList()
        val ids = cancellation.optJSONArray("ids") ?: return emptyList()
        return (0 until ids.length()).mapNotNull { ids.optString(it).trim().takeIf(String::isNotEmpty) }
    }

    /**
     * The `toolResponse` frame answering one proposal.
     *
     * [nonBlocking] must match what [toolsDeclaration] declared for the session this frame is going
     * to: `scheduling` is only meaningful on an asynchronous call, so it is omitted entirely when
     * the tool was declared in the ordinary blocking mode.
     *
     * [note] is the plain-English instruction that rides along with the outcome word (see
     * [SceneTransitionOutcome.note]). Callers override it only where the default cannot be specific
     * enough — a [SceneTransitionOutcome.NOT_APPLICABLE] response carries the actual reason from
     * [rejectionReasonFor], which is what tells the model how to propose it correctly next time.
     */
    fun buildToolResponse(
        id: String,
        name: String,
        outcome: SceneTransitionOutcome,
        nonBlocking: Boolean,
        note: String = outcome.note,
    ): String =
        JSONObject().put(
            "toolResponse",
            JSONObject().put(
                "functionResponses",
                JSONArray().put(
                    JSONObject()
                        .put("id", id)
                        .put("name", name.ifBlank { TOOL_NAME })
                        .put(
                            "response",
                            JSONObject().apply {
                                put("result", outcome.wire)
                                note.trim().takeIf(String::isNotEmpty)?.let { put("note", it) }
                                if (nonBlocking) put("scheduling", outcome.scheduling)
                            },
                        ),
                ),
            ),
        ).toString()

    /**
     * Why this proposal cannot be applied to the scene the session is actually in, or null when it
     * can. A non-null reason means the chip is never shown and the call is answered
     * [SceneTransitionOutcome.NOT_APPLICABLE] with that reason as its note.
     *
     * Gemini Live fixes the tool declaration for the whole session, so all five types stay on offer
     * however the scene moves — the app cannot narrow the menu the way it narrows the prompt. That
     * makes two proposals reachable that describe something untrue:
     *
     * - a return trip when the learner never left. [SceneTransitionType.RETURN_TO_PREVIOUS] is the
     *   one type [SceneTransitionGate] neither charges to the session budget nor holds to the full
     *   cooldown (both deliberate — see the gate's own docs), so an unguarded one is also the one a
     *   model could repeat every few seconds. Accepting it would reconnect as the character the
     *   learner is already with: a silent, pointless teardown of a working session.
     * - a character switch that never says who to. `new_character_role` is not `required` in the
     *   declaration, because making it required for every type would force the model to invent one
     *   for a plain move; the cost is that a blank one reaches [VoiceManager.switchCharacter], which
     *   builds a stand-in prompt around an unnamed person and gives them a synthetic key.
     *
     * Both are stated back to the model rather than silently swallowed, because both are things it
     * can fix on the next attempt.
     */
    fun rejectionReasonFor(
        proposal: SceneTransitionProposal,
        isWithStandInCharacter: Boolean,
    ): String? = when {
        proposal.type == SceneTransitionType.RETURN_TO_PREVIOUS && !isWithStandInCharacter ->
            "There is nobody to go back to — the user is still with the person this scene started " +
                "with and has not been handed to anyone else. Only propose return_to_previous once " +
                "the user is talking to a different person."

        proposal.type == SceneTransitionType.NEW_CHARACTER && proposal.newCharacterRole.isBlank() ->
            "A new_character transition needs new_character_role — who the new person is, for " +
                "example \"front desk staff\". Propose it again with that field filled in."

        else -> null
    }

    /**
     * The text injected into the live conversation once a transition is accepted. Written as a
     * stage direction to the actor, not as something the learner said.
     *
     * The solo-errand wording deliberately does NOT include the facts the learner was handed: the
     * whole point of the relay task is that the counterpart was not there and has to be told.
     */
    fun stageDirectionFor(proposal: SceneTransitionProposal): String {
        val scene = proposal.description.trim().ifEmpty { proposal.title.trim() }
        val sceneSentence = if (scene.isEmpty()) "" else "$scene "
        return when (proposal.type) {
            SceneTransitionType.MOVE ->
                "$STAGE_PREFIX ${sceneSentence}You are both there now. " +
                    "Continue the conversation naturally in the new scene."

            SceneTransitionType.TIME_SKIP ->
                "$STAGE_PREFIX ${sceneSentence}Time has passed since your last exchange. " +
                    "Continue the conversation naturally in the new moment, as if that time really went by."

            SceneTransitionType.SOLO_ERRAND -> {
                val place = proposal.place.trim().ifEmpty { "the place you suggested" }
                "$STAGE_PREFIX The user went to $place alone and has just returned. " +
                    "They will now report what happened. You were not there — ask natural follow-up " +
                    "questions and react to their report. ${sceneSentence}".trim()
            }

            SceneTransitionType.NEW_CHARACTER ->
                "$STAGE_PREFIX ${sceneSentence}Continue the conversation naturally in the new scene."

            SceneTransitionType.RETURN_TO_PREVIOUS ->
                "$STAGE_PREFIX ${sceneSentence}Continue the conversation naturally from here."
        }
    }

    /**
     * The line written into the saved transcript so the session record (and therefore the analysis
     * prompt) shows where the scene changed. [relayFacts] is appended for a solo errand so the
     * grader can compare what the learner was told with what they actually reported back.
     */
    fun transcriptLineFor(
        proposal: SceneTransitionProposal,
        relayFacts: List<String> = emptyList(),
    ): String {
        val label = when (proposal.type) {
            SceneTransitionType.MOVE -> "Moved"
            SceneTransitionType.TIME_SKIP -> "Time skip"
            SceneTransitionType.NEW_CHARACTER -> "New person"
            SceneTransitionType.SOLO_ERRAND -> "Solo errand"
            SceneTransitionType.RETURN_TO_PREVIOUS -> "Back to the previous person"
        }
        val detail = proposal.description.trim().ifEmpty { proposal.title.trim() }
        val role = proposal.newCharacterRole.trim()
        val parts = mutableListOf("$STAGE_PREFIX $label")
        if (role.isNotEmpty()) parts.add("($role)")
        if (detail.isNotEmpty()) parts.add("— $detail")
        val head = parts.joinToString(" ")
        if (relayFacts.isEmpty()) return head
        return head + "\nThe learner was told: " + relayFacts.joinToString(" | ")
    }

    /**
     * A recap of the scene changes that are still true, for a reconnect whose new provider session
     * did NOT resume the old one server-side (see [VoiceClient.seedHistory]). The system prompt
     * describes where the scene *started*, and the replayed turns rarely state outright that it
     * moved, so without this the model quietly puts everyone back in the original setting.
     *
     * Only [SceneTransitionType.MOVE] and [SceneTransitionType.TIME_SKIP] belong here: they change
     * where and when the scene is and stay true for the rest of it. A solo errand is deliberately
     * excluded — "they have just returned and will report" describes a moment that has already
     * passed, and replaying it would have the model ask for the same report a second time. The two
     * reconnecting types need nothing either: a character switch rebuilds the system prompt itself.
     */
    fun sceneRecapFor(proposals: List<SceneTransitionProposal>): String {
        val scenes = proposals
            .filter { it.type == SceneTransitionType.MOVE || it.type == SceneTransitionType.TIME_SKIP }
            .mapNotNull { proposal ->
                proposal.description.trim().ifEmpty { proposal.title.trim() }.takeIf(String::isNotEmpty)
            }
        if (scenes.isEmpty()) return ""
        return "$STAGE_PREFIX Where the scene stands now, after everything above: " +
            scenes.joinToString(" ") +
            " Carry on from that point — do not restart the scene, re-introduce yourself, " +
            "or go back to where the conversation began."
    }

    /** Longest an individual old turn may be once it has been folded into a digest. */
    const val DIGEST_TURN_CHARS = 110

    /**
     * Shrink a history replay to a verbatim tail plus one narrator digest of everything older.
     *
     * Replayed context is not paid for once. Gemini Live re-bills the whole conversation prefix on
     * every subsequent turn, so each turn seeded into a fresh session is charged again for the rest
     * of that session — which makes the seed the most expensive text the app sends, and makes the
     * oldest turns the worst value in it: they are the least likely to matter and are paid for the
     * longest. Folding them into one short digest keeps what a character needs to not sound lost
     * (roughly what has been discussed) while cutting what it is charged to carry.
     *
     * Deliberately deterministic string work, not an LLM summary: this runs on the scene-switch
     * path, where a network round trip would sit between the learner's tap and the new person
     * speaking, and where an API failure would have to be handled without losing the scene.
     *
     * [modelSpeakerLabel] names whoever produced the non-user turns, because that differs by
     * caller: a reconnect replays the *current* character's own history back to it ("You"), while a
     * character switch replays it to someone who was never there and must not read it as their own
     * memory. Narrator lines keep their own wording — they are already stage directions.
     */
    fun compressSeedTurns(
        turns: List<Pair<String, String>>,
        verbatimTurns: Int,
        maxDigestChars: Int,
        modelSpeakerLabel: String,
    ): List<Pair<String, String>> {
        if (verbatimTurns < 0 || turns.size <= verbatimTurns) return turns
        val tail = turns.takeLast(verbatimTurns)
        val head = turns.subList(0, turns.size - tail.size)
        val entries = mutableListOf<String>()
        for ((role, text) in head) {
            val condensed = condense(text, DIGEST_TURN_CHARS)
            if (condensed.isEmpty()) continue
            entries += when (role) {
                SceneTransitionProtocol.NARRATOR_ROLE -> condensed
                "user", "doctor" -> "The user: $condensed"
                else -> "$modelSpeakerLabel: $condensed"
            }
        }
        var total = entries.sumOf { it.length + 1 }
        while (entries.isNotEmpty() && total > maxDigestChars) {
            total -= entries.removeAt(0).length + 1
        }
        if (entries.isEmpty()) return tail
        val digest = "$STAGE_PREFIX What was already said before this point, in brief: " +
            entries.joinToString(" ")
        return listOf(NARRATOR_ROLE to digest) + tail
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    private fun condense(text: String, maxChars: Int): String {
        val collapsed = text.replace(WHITESPACE_RUN, " ").trim()
        if (collapsed.length <= maxChars) return collapsed
        val cut = collapsed.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val kept = if (lastSpace > maxChars / 2) cut.take(lastSpace) else cut
        return kept.trimEnd() + "…"
    }

    private fun defaultTitleFor(type: SceneTransitionType): String = when (type) {
        SceneTransitionType.MOVE -> "Move somewhere else?"
        SceneTransitionType.TIME_SKIP -> "Skip ahead in time?"
        SceneTransitionType.NEW_CHARACTER -> "Talk to someone else?"
        SceneTransitionType.SOLO_ERRAND -> "Go and come back?"
        SceneTransitionType.RETURN_TO_PREVIOUS -> "Go back to the previous person?"
    }
}

/**
 * What [SceneTransitionGate.admit] decided about one proposal, and therefore what the model is
 * told. The two refusals are kept apart because they ask for opposite things — see
 * [SceneTransitionOutcome.BUDGET_EXHAUSTED].
 */
enum class SceneTransitionAdmission {
    /** Show the chip. Recorded as shown, and charged to the budget where the type is charged. */
    ADMITTED,

    /**
     * Too soon: either the session's [SceneTransitionGate.LEAD_IN_MILLIS] opening stretch has not
     * elapsed, or the previous proposal is still inside its cooldown. Later ones can still land.
     */
    TOO_SOON,

    /** [SceneTransitionGate.MAX_PROPOSALS_PER_SESSION] is spent; nothing else will be admitted. */
    BUDGET_SPENT,
    ;

    val isAdmitted: Boolean get() = this == ADMITTED

    /** How a refusal is answered back to the model. Never called for [ADMITTED]. */
    val refusalOutcome: SceneTransitionOutcome
        get() = when (this) {
            BUDGET_SPENT -> SceneTransitionOutcome.BUDGET_EXHAUSTED
            else -> SceneTransitionOutcome.RATE_LIMITED
        }
}

/**
 * App-side backstop for the three limits the beta prompt also asks the model to respect: an opening
 * stretch with no scene changes at all, a gap between them, and a ceiling per conversation. A prompt
 * is a request, not a guarantee — a model that proposes a transition every other turn would wreck
 * the session and cost real tokens, so the same caps are enforced here and anything over the limit
 * is auto-declined without ever reaching the screen.
 *
 * Refusals are not interchangeable: [SceneTransitionAdmission] says which limit was hit, because
 * "not yet" and "not again this conversation" are different instructions to the model.
 *
 * A [SceneTransitionType.RETURN_TO_PREVIOUS] proposal is treated as the closing half of a character
 * switch the learner already accepted rather than a new detour: it never counts against the budget,
 * and it waits only [RETURN_COOLDOWN_MILLIS] rather than the full between-detours cooldown. That
 * shorter wait is load-bearing, not a nicety — the app offers no manual way back, so a return
 * proposal blocked here can strand the learner with the new character for the rest of the session
 * (the model is told "declined" and has no obligation to ask again). It is still bounded, because a
 * model that spams the return trip would otherwise be unlimited.
 */
class SceneTransitionGate(
    private val maxProposals: Int = MAX_PROPOSALS_PER_SESSION,
    private val cooldownMillis: Long = COOLDOWN_MILLIS,
    // Never longer than the session's own cooldown, so a deliberately loose gate (the offline
    // demo's) stays loose in both directions.
    private val returnCooldownMillis: Long = minOf(cooldownMillis, RETURN_COOLDOWN_MILLIS),
    private val leadInMillis: Long = LEAD_IN_MILLIS,
    private val requestedCooldownMillis: Long = minOf(cooldownMillis, REQUESTED_COOLDOWN_MILLIS),
) {
    private val lock = Any()
    private var counted = 0
    private var lastAdmittedAtMillis: Long? = null
    private var startedAtMillis: Long? = null

    /**
     * Start the session's [leadInMillis] opening stretch. Idempotent: the first call wins, so a
     * reconnect part-way through a conversation cannot hand the model a fresh quiet period.
     *
     * Deliberately not the gate's construction time. Nothing between building a [VoiceManager] and
     * the provider actually being ready is conversation the learner took part in, and the whole
     * point of the lead-in is to measure how long they have been *talking*.
     *
     * A gate nobody starts has no lead-in at all, which is what keeps every existing caller — the
     * offline demo, the unit tests — behaving exactly as it did.
     */
    fun markSessionStart(nowMillis: Long) {
        synchronized(lock) { if (startedAtMillis == null) startedAtMillis = nowMillis }
    }

    /**
     * Whether this proposal may be shown, and if not, why. Records it as shown when it admits.
     * [nowMillis] must come from a monotonic clock (callers pass `SystemClock.elapsedRealtime()`),
     * never wall time, which an NTP correction mid-session can move backwards or forwards.
     *
     * The lead-in is checked for everything except the return trip. Nothing rate-limits the *first*
     * proposal of a session otherwise — `lastAdmittedAtMillis` starts unset — so a model that found
     * an excuse thirty seconds in got it waved straight through, which is exactly how the feature
     * came across as hair-trigger at the start and inert later. A return trip is exempt because it
     * closes out a character switch that already waited out the lead-in itself.
     */
    fun admit(
        type: SceneTransitionType,
        nowMillis: Long,
        requestedByUser: Boolean = false,
    ): SceneTransitionAdmission = synchronized(lock) {
        val isReturn = type == SceneTransitionType.RETURN_TO_PREVIOUS
        // Neither limit describes a change the learner asked for. The budget rations how often the
        // model may interrupt with an idea of its own, and the lead-in buys time for a scene to
        // exist before it can be changed — but a learner who has just said "can we go inside?" has
        // established that scene by talking about it, and being told nothing happens is the
        // feature failing at exactly the moment it worked. The utterance is also the practice, so
        // rationing it rations the training. Callers must corroborate the claim first.
        val exempt = isReturn || requestedByUser
        if (!exempt && counted >= maxProposals) return SceneTransitionAdmission.BUDGET_SPENT
        if (!exempt) {
            startedAtMillis?.let { started ->
                if (nowMillis - started < leadInMillis) return SceneTransitionAdmission.TOO_SOON
            }
        }
        // A cooldown still applies to every kind, including the exempt ones: back-to-back scene
        // changes are incoherent however they were arrived at, and it is the only thing bounding a
        // model that sets the flag on its own ideas.
        val applicableCooldown = when {
            isReturn -> returnCooldownMillis
            requestedByUser -> requestedCooldownMillis
            else -> cooldownMillis
        }
        lastAdmittedAtMillis?.let { last ->
            if (nowMillis - last < applicableCooldown) return SceneTransitionAdmission.TOO_SOON
        }
        if (!exempt) counted += 1
        lastAdmittedAtMillis = nowMillis
        SceneTransitionAdmission.ADMITTED
    }

    /**
     * Give back the budget slot [admit] charged for a proposal the learner never answered.
     *
     * [MAX_PROPOSALS_PER_SESSION] is a budget for *detours the learner actually took*, but it was
     * being spent at the moment a chip appeared. Two proposals the learner did not happen to look
     * at therefore switched the feature off for the rest of the session, which is precisely
     * backwards: someone who ignored both got fewer chances, not more.
     *
     * The cooldown is deliberately not rewound with it. A chip that came and went still interrupted
     * the learner once, and rewinding it would let the model re-propose the instant the first one
     * expired — turning "you didn't answer" into a licence to ask again immediately.
     */
    fun refundUnanswered(type: SceneTransitionType, requestedByUser: Boolean = false) {
        synchronized(lock) {
            // Mirrors what admit() charged: neither exempt kind ever spent a slot, so refunding
            // one would hand the session a slot it never had.
            if (type == SceneTransitionType.RETURN_TO_PREVIOUS || requestedByUser) return
            if (counted > 0) counted -= 1
        }
    }

    companion object {
        /**
         * Scene changes one conversation may offer. Was 2, which turned out to be the whole reason
         * the feature felt broken rather than limited: two answered proposals — accepted or turned
         * down, both charge a slot — switched it off for the rest of the session, and every later
         * call the model made was swallowed with the learner never seeing a thing.
         */
        const val MAX_PROPOSALS_PER_SESSION = 5

        const val COOLDOWN_MILLIS = 60_000L

        /**
         * How long a conversation runs before the first scene change may be offered.
         *
         * Without it the first proposal was the only one nothing throttled, so it landed whenever
         * the model first thought of one — often before the scene had established itself at all.
         * Forty seconds is roughly where a Survival opening has got past hello and into whatever
         * the learner actually came for, which is the earliest a change of scene can be a change
         * *from* something.
         */
        const val LEAD_IN_MILLIS = 40_000L

        /** Cooldown for the return trip only — long enough to bound a runaway model, no longer. */
        const val RETURN_COOLDOWN_MILLIS = 15_000L

        /**
         * Cooldown for a change the learner asked for themselves.
         *
         * Shorter than [COOLDOWN_MILLIS] because that gap exists to stop the model interrupting,
         * and a learner asking is not an interruption — making them wait a minute after their own
         * request is the "it's broken, not limited" complaint all over again. It is not zero
         * because `requested_by_user` is the model's word for what the learner said, and this is
         * the only limit still standing behind it: at worst a model that mislabels its own ideas
         * gets one card per quarter-minute, not one per turn.
         */
        const val REQUESTED_COOLDOWN_MILLIS = 15_000L

        /** How long a chip stays on screen before it auto-declines itself. */
        const val PROPOSAL_TIMEOUT_MILLIS = 30_000L

        /**
         * The same timeout for a session whose tool came up **blocking** — i.e. one that lost the
         * [SceneTransitionProtocol.BEHAVIOR_NON_BLOCKING] declaration to
         * `GeminiLiveClient.retryWithBlockingTools` because the server refused it.
         *
         * On such a session the model is genuinely mute from the instant it proposes until the
         * response lands, so [PROPOSAL_TIMEOUT_MILLIS] is not a chip sitting quietly on screen — it
         * is up to thirty seconds of dead air, with the character apparently having stopped
         * listening. Eight seconds is about as long as a conversational pause can be explained away
         * as thinking, and a learner who wanted the change has almost always tapped by then.
         */
        const val PROPOSAL_TIMEOUT_BLOCKING_MILLIS = 8_000L

        /**
         * How long the learner gets to read a solo errand's facts before the scene resumes without
         * them (see MainViewModel's deferred acceptance). Longer than [PROPOSAL_TIMEOUT_MILLIS]
         * because this is reading time for two or three concrete details, not a yes/no decision —
         * but still bounded, since the accepted function call stays unanswered until it elapses.
         */
        const val RELAY_READING_TIMEOUT_MILLIS = 45_000L
    }
}

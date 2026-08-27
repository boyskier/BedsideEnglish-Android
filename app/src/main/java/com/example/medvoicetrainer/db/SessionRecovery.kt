package com.example.medvoicetrainer.db

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Why a session row stopped being a live conversation.
 *
 * Before this existed, "was this session finished?" was answered by a single heuristic —
 * `summaryFeedback.isNullOrBlank()` (see `isUnanalyzedSession`) — which cannot tell four very
 * different situations apart, because all four leave the analysis columns empty:
 *
 *  - the process died mid-conversation (crash, low-memory kill, force stop),
 *  - the learner asked for analysis and the analysis call failed,
 *  - the learner explicitly pressed "Discard without analyzing",
 *  - the learner explicitly pressed "Skip & keep transcript" on the analyzing screen.
 *
 * Only the first two are accidents worth offering to recover on Home. The last two are decisions
 * the learner already made, and re-asking about them on the next app launch reads as the app
 * ignoring the button they pressed. Recording the reason at the moment the session ends is the
 * only way to keep them apart afterwards.
 */
object SessionEndReason {
    /** Set at draft insert. A row still holding this was never ended by any code path — i.e. the
     *  process died with the conversation open. This is the case the Home recovery card exists for. */
    const val IN_PROGRESS = "in_progress"

    /** Analysis ran and produced feedback. */
    const val COMPLETED = "completed"

    /** The learner asked for analysis; the provider call threw. "Analyze" is a real retry here. */
    const val ANALYSIS_FAILED = "analysis_failed"

    /** The learner chose "Discard without analyzing" (or backed out of a lab). Their decision. */
    const val DISCARDED = "discarded"

    /** The learner chose "Skip analysis, keep the transcript". Also their decision. */
    const val KEPT_UNANALYZED = "kept_unanalyzed"

    /** The only two reasons that may raise an unprompted recovery card on Home. */
    val RECOVERABLE: Set<String> = setOf(IN_PROGRESS, ANALYSIS_FAILED)
}

/**
 * Decides whether an un-analyzed session still deserves the Home recovery card.
 *
 * Pure and clock-injected so the rules are unit-testable without Room or a device.
 *
 * Beyond the end-reason check above, two bars keep the card from becoming permanent Home
 * furniture: a session must be *substantial* (an accidental start that was killed after one
 * sentence is not worth analyzing, let alone announcing), and it must be *fresh* — the card used
 * to sit at the top of Home indefinitely, so a session abandoned three weeks ago still greeted the
 * learner every launch until they dismissed it by hand. Anything that ages out of the window is
 * still in History, where the same retry-analysis action lives.
 */
object SessionRecovery {
    /** Learner turns below which a session is an abandoned start, not an interrupted attempt. */
    const val MIN_LEARNER_TURNS = 4

    /** How long an interrupted session stays worth interrupting Home about. */
    val MAX_AGE: Duration = Duration.ofHours(24)

    // Matches the "yyyy-MM-dd'T'HH:mm:ss" local-time stamp every write path formats createdAt with.
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun parseCreatedAt(createdAt: String): LocalDateTime? = try {
        LocalDateTime.parse(createdAt.trim(), TIMESTAMP)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Age of [session] at [now], or null if its timestamp cannot be read. May be negative. */
    fun ageOf(session: SessionEntity, now: LocalDateTime): Duration? =
        parseCreatedAt(session.createdAt)?.let { Duration.between(it, now) }

    fun isRecoverable(session: SessionEntity, now: LocalDateTime): Boolean {
        if (session.deletedAt != null) return false
        if (!session.summaryFeedback.isNullOrBlank()) return false
        if (session.endReason !in SessionEndReason.RECOVERABLE) return false
        if (session.learnerTurnCount < MIN_LEARNER_TURNS) return false
        val age = ageOf(session, now) ?: return false
        // A negative age (device clock moved backwards, timezone change) is treated as fresh
        // rather than as a reason to hide a session the learner may genuinely have just lost.
        return age <= MAX_AGE
    }

    /**
     * The newest session worth offering, skipping any the learner already closed on Home.
     * [sessions] is expected newest-first, as `Repository.sessions` emits it.
     */
    fun firstRecoverable(
        sessions: List<SessionEntity>,
        dismissedIds: Set<Int>,
        now: LocalDateTime,
    ): SessionEntity? = sessions.firstOrNull {
        it.id !in dismissedIds && isRecoverable(it, now)
    }
}

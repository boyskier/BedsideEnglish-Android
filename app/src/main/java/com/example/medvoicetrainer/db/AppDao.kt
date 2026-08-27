package com.example.medvoicetrainer.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM api_usage_events ORDER BY createdAt DESC")
    fun getApiUsageEvents(): Flow<List<ApiUsageEventEntity>>

    @Query("SELECT * FROM api_usage_events ORDER BY id")
    suspend fun getApiUsageEventsForBackup(): List<ApiUsageEventEntity>

    @Insert
    suspend fun insertApiUsageEvent(event: ApiUsageEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApiUsageEvents(events: List<ApiUsageEventEntity>)

    @Query("DELETE FROM api_usage_events")
    suspend fun deleteAllApiUsageEvents()

    // --- Sessions ---
    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query(
        "SELECT createdAt, voiceBackend, totalCostUsd, " +
            "CASE WHEN rawEvalJson IS NOT NULL THEN 1 ELSE 0 END AS analyzed " +
            "FROM sessions WHERE deletedAt IS NULL ORDER BY createdAt DESC LIMIT 50"
    )
    fun getRecentSessionCostSamples(): Flow<List<SessionCostSample>>

    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    suspend fun getAllSessionsList(): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY id")
    suspend fun getAllSessionsForBackup(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: Int): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Update
    suspend fun updateSession(session: SessionEntity)

    /** Persist every accepted transcript turn while the session is still running. */
    @Query(
        "UPDATE sessions SET rawTranscript = :transcriptJson, " +
            "learnerTurnCount = :learnerTurnCount WHERE id = :id"
    )
    suspend fun updateSessionTranscript(id: Int, transcriptJson: String, learnerTurnCount: Int)

    @Query("UPDATE sessions SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDeleteSession(id: Int, deletedAt: String)

    // Mirrors queries.py's save_debrief_chat/save_debrief_insights.
    @Query("UPDATE sessions SET debriefChat = :chatJson WHERE id = :id")
    suspend fun saveDebriefChat(id: Int, chatJson: String)

    @Query("UPDATE sessions SET debriefInsights = :insightsJson WHERE id = :id")
    suspend fun saveDebriefInsights(id: Int, insightsJson: String)

    @Query("UPDATE sessions SET corrections = :correctionsJson WHERE id = :id")
    suspend fun updateSessionCorrections(id: Int, correctionsJson: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun hardDeleteSessionById(id: Int)

    // Mirrors queries.py's list_sessions_by_mode(): DB-side History filter so dense practice in
    // one mode cannot hide another.
    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL AND LOWER(mode) = LOWER(:mode) ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listSessionsByMode(mode: String, limit: Int): List<SessionEntity>

    // Mirrors queries.py's list_clinical_sessions(): clinical rows without everyday practice
    // crowding them out.
    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL AND analysisDomain = 'clinical' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listClinicalSessions(limit: Int): List<SessionEntity>

    // Mirrors queries.py's list_survival_practice_cases(): compact case snapshots for Survival
    // sessions with at least one non-empty learner turn (abandoned starts don't count).
    @Query("SELECT rawCaseJson FROM sessions WHERE mode = 'survival' AND deletedAt IS NULL AND learnerTurnCount > 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listSurvivalPracticeCaseJson(limit: Int): List<String>

    // Mirrors queries.py's list_trash_sessions()/restore_session()/purge_old_trash().
    @Query("SELECT * FROM sessions WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC LIMIT :limit")
    suspend fun listTrashSessions(limit: Int): List<SessionEntity>

    @Query("UPDATE sessions SET deletedAt = NULL WHERE id = :id")
    suspend fun restoreSession(id: Int)

    @Query("DELETE FROM sessions WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeOldTrash(cutoff: String): Int

    // Mirrors queries.py's get_all_transcripts()/get_all_clinical_transcripts().
    @Query("SELECT rawTranscript FROM sessions WHERE rawTranscript IS NOT NULL AND deletedAt IS NULL")
    suspend fun getAllTranscripts(): List<String>

    @Query("SELECT rawTranscript FROM sessions WHERE rawTranscript IS NOT NULL AND deletedAt IS NULL AND analysisDomain = 'clinical'")
    suspend fun getAllClinicalTranscripts(): List<String>

    // Mirrors queries.py's get_sessions_for_case().
    @Query("SELECT * FROM sessions WHERE caseId = :caseId ORDER BY createdAt DESC")
    suspend fun getSessionsForCase(caseId: String): List<SessionEntity>

    // Mirrors queries.py's finalize_empty_session(): a session that never got analyzed (e.g. the
    // user backed out) still gets a real duration recorded.
    @Query("UPDATE sessions SET durationSeconds = :durationSeconds WHERE id = :id")
    suspend fun finalizeEmptySession(id: Int, durationSeconds: Int)

    @Query(
        """UPDATE sessions SET durationSeconds = :durationSeconds,
            voiceModel = :voiceModel, voiceCostUsd = :voiceCostUsd,
            totalCostUsd = :voiceCostUsd,
            voiceInputTextTokens = :inputText, voiceInputAudioTokens = :inputAudio,
            voiceOutputTextTokens = :outputText, voiceOutputAudioTokens = :outputAudio,
            voiceCachedInputTextTokens = :cachedText,
            voiceCachedInputAudioTokens = :cachedAudio,
            voiceThinkingTokens = :thinking, voiceUsageExact = :usageExact,
            costEstimated = :estimated, endReason = :endReason
        WHERE id = :id"""
    )
    suspend fun finalizeVoiceOnlySession(
        id: Int,
        durationSeconds: Int,
        voiceModel: String?,
        voiceCostUsd: Double,
        inputText: Int,
        inputAudio: Int,
        outputText: Int,
        outputAudio: Int,
        cachedText: Int,
        cachedAudio: Int,
        thinking: Int,
        usageExact: Boolean,
        estimated: Boolean,
        endReason: String,
    )

    /**
     * Record why a session stopped being live (see SessionEndReason) without touching anything
     * else on the row — used by the paths that end a session outside finalizeVoiceOnlySession,
     * e.g. an analysis attempt that threw.
     */
    @Query("UPDATE sessions SET endReason = :endReason WHERE id = :id")
    suspend fun updateSessionEndReason(id: Int, endReason: String)

    // Mirrors queries.py's update_session_case_snapshot(): the authoritative, analysis-facing
    // case snapshot can change mid-session (learning-assistance events).
    @Query("UPDATE sessions SET rawCaseJson = :caseJson WHERE id = :id")
    suspend fun updateSessionCaseSnapshot(id: Int, caseJson: String)

    /** Adds durable post-analysis teaching details without changing the original scores. */
    @Query("UPDATE sessions SET rawEvalJson = :rawEvalJson WHERE id = :id")
    suspend fun updateSessionRawEvaluation(id: Int, rawEvalJson: String)

    // Mirrors queries.py's save_docx_path().
    @Query("UPDATE sessions SET docxPath = :path WHERE id = :id")
    suspend fun saveDocxPath(id: Int, path: String)

    // Mirrors queries.py's save_student_soap().
    @Query("UPDATE sessions SET studentSoapNote = :soapText WHERE id = :id")
    suspend fun saveStudentSoap(id: Int, soapText: String)

    // Mirrors queries.py's save_self_scores().
    @Query(
        """UPDATE sessions SET
            selfGrammar = :selfGrammar, selfMedicalAccuracy = :selfMedicalAccuracy,
            selfClinicalReasoning = :selfClinicalReasoning, selfProfessionalism = :selfProfessionalism,
            selfFluency = :selfFluency, selfScoresJson = :selfScoresJson
        WHERE id = :id"""
    )
    suspend fun saveSelfScores(
        id: Int,
        selfGrammar: Double,
        selfMedicalAccuracy: Double,
        selfClinicalReasoning: Double,
        selfProfessionalism: Double,
        selfFluency: Double,
        selfScoresJson: String
    )

    // Mirrors queries.py's get_total_sessions_analyzed(): sessions that completed full analysis,
    // including trashed ones (matches Python — no deletedAt filter here).
    @Query("SELECT COUNT(*) FROM sessions WHERE rawClaudeResponse IS NOT NULL")
    suspend fun getTotalSessionsAnalyzed(): Int

    // Mirrors queries.py's get_weekly_cost()/get_session_cost().
    @Query("SELECT COALESCE(SUM(totalCostUsd), 0.0) FROM sessions WHERE createdAt >= :cutoff AND deletedAt IS NULL")
    suspend fun getWeeklyCost(cutoff: String): Double

    @Query("SELECT totalCostUsd FROM sessions WHERE id = :id")
    suspend fun getSessionCost(id: Int): Double?

    // Mirrors queries.py's get_days_since_last_session()/session_local_date bookkeeping —
    // returns the most recent analyzed session's createdAt for the caller to diff against today.
    @Query("SELECT createdAt FROM sessions WHERE rawClaudeResponse IS NOT NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastAnalyzedSessionCreatedAt(): String?

    // Mirrors queries.py's get_confidence_trend()'s per-period window query. Only sessions with
    // fluency metrics actually computed (userWordCount > 0) count, matching Python's
    // `user_word_count IS NOT NULL` filter — Kotlin's column defaults to 0 rather than null.
    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL AND createdAt >= :fromIso AND createdAt < :toIso AND userWordCount > 0")
    suspend fun getSessionsInWindowWithWordCount(fromIso: String, toIso: String): List<SessionEntity>

    // Mirrors queries.py's get_survival_progress_summary().
    @Query("SELECT rawClaudeResponse FROM sessions WHERE deletedAt IS NULL AND mode = 'survival' AND rawClaudeResponse IS NOT NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getSurvivalRawResponses(limit: Int): List<String>

    // --- Error Items (SRS) ---
    @Query("SELECT * FROM error_items ORDER BY lastSeen DESC")
    fun getAllErrorItems(): Flow<List<ErrorItemEntity>>

    @Query("SELECT * FROM error_items ORDER BY lastSeen DESC")
    suspend fun getAllErrorItemsList(): List<ErrorItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertErrorItems(errorItems: List<ErrorItemEntity>)

    @Query("DELETE FROM error_items")
    suspend fun deleteAllErrorItems()

    @Query("SELECT * FROM error_items WHERE `key` = :key")
    suspend fun getErrorItemByKey(key: String): ErrorItemEntity?

    // Accepted clinical and everyday-language errors share one review queue. Pronunciation
    // observations remain excluded until repeated audio evidence promotes them out of observed.
    @Query("SELECT * FROM error_items WHERE state NOT IN ('mastered', 'observed') AND (domain IN ('clinical', 'everyday') OR LOWER(category) LIKE 'pronunciation%') AND dueAt <= :nowTime ORDER BY dueAt ASC, seenCount DESC LIMIT :maxItems")
    suspend fun getDueErrorItems(nowTime: String, maxItems: Int): List<ErrorItemEntity>

    // Mirrors queries.py's get_all_active_error_items(): active items regardless of due date.
    @Query("SELECT * FROM error_items WHERE state NOT IN ('mastered', 'observed') AND (domain IN ('clinical', 'everyday') OR LOWER(category) LIKE 'pronunciation%') ORDER BY seenCount DESC, dueAt ASC LIMIT :maxItems")
    suspend fun getAllActiveErrorItems(maxItems: Int): List<ErrorItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertErrorItem(errorItem: ErrorItemEntity)

    @Update
    suspend fun updateErrorItem(errorItem: ErrorItemEntity)

    @Query("DELETE FROM error_items WHERE `key` = :key")
    suspend fun deleteErrorItemByKey(key: String)

    // Mirrors queries.py's get_due_count()/get_due_count_by(): count of error items due for SRS
    // review by a given deadline (Repository passes "now" for get_due_count's semantics).
    @Query("SELECT COUNT(*) FROM error_items WHERE state NOT IN ('mastered', 'observed') AND (domain IN ('clinical', 'everyday') OR LOWER(category) LIKE 'pronunciation%') AND dueAt <= :deadline")
    suspend fun getDueCountBy(deadline: String): Int

    // Mirrors queries.py's get_mastered_this_week(): items that transitioned to mastered
    // recently (for progress surfaces).
    @Query("SELECT COUNT(*) FROM error_items WHERE state = 'mastered' AND (domain IN ('clinical', 'everyday') OR LOWER(category) LIKE 'pronunciation%') AND lastSeen >= :cutoff")
    suspend fun getMasteredSince(cutoff: String): Int

    // --- Listening Attempts ---
    @Query("SELECT * FROM listening_attempts ORDER BY createdAt DESC")
    fun getAllListeningAttempts(): Flow<List<ListeningAttemptEntity>>

    @Query("SELECT * FROM listening_attempts ORDER BY id")
    suspend fun getAllListeningAttemptsForBackup(): List<ListeningAttemptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListeningAttempt(attempt: ListeningAttemptEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListeningAttempts(attempts: List<ListeningAttemptEntity>)

    @Query("DELETE FROM listening_attempts")
    suspend fun deleteAllListeningAttempts()

    // Mirrors listening_queries.py's _next_interval()'s lookup: the most recent unaided
    // (non-assisted) interval recorded for this drill, so review spacing only ever advances
    // off a genuinely unaided commit.
    @Query(
        "SELECT intervalDays FROM listening_attempts WHERE drillId = :drillId AND " +
            "unaidedAccuracy IS NOT NULL ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun getLastUnaidedIntervalDays(drillId: String): Int?

    @Query("SELECT repairStrategies FROM listening_attempts WHERE id = :id")
    suspend fun getRepairStrategies(id: Int): String?

    // Mirrors listening_queries.py's update_listening_assistance(): post-commit help events
    // only ever raise the counters (SQLite's 2-arg MAX() is a scalar max, not an aggregate).
    @Query(
        "UPDATE listening_attempts SET replayCount = MAX(replayCount, :replayCount), " +
            "cleanReplayCount = MAX(cleanReplayCount, :cleanReplayCount), " +
            "revealCount = MAX(revealCount, :revealCount), repairCount = :repairCount, " +
            "repairStrategies = :repairStrategies WHERE id = :id"
    )
    suspend fun applyListeningAssistanceUpdate(
        id: Int,
        replayCount: Int,
        cleanReplayCount: Int,
        revealCount: Int,
        repairCount: Int,
        repairStrategies: String
    )

    // Mirrors listening_queries.py's update_listening_ratings().
    @Query(
        "UPDATE listening_attempts SET authenticityRating = :authenticity, " +
            "accentMatchRating = :accentMatch, naturalnessRating = :naturalness WHERE id = :id"
    )
    suspend fun updateListeningRatings(id: Int, authenticity: Int?, accentMatch: Int?, naturalness: Int?)

    // Mirrors listening_queries.py's listening_validation_summary().
    @Query(
        "SELECT COUNT(*) as rated, AVG(authenticityRating) as authenticity, " +
            "AVG(accentMatchRating) as accentMatch, AVG(naturalnessRating) as naturalness " +
            "FROM listening_attempts WHERE authenticityRating IS NOT NULL OR " +
            "accentMatchRating IS NOT NULL OR naturalnessRating IS NOT NULL"
    )
    suspend fun getListeningValidationSummary(): ListeningValidationSummary

    // --- Commitments ---
    @Query("SELECT * FROM debrief_commitments ORDER BY createdAt DESC")
    fun getAllCommitments(): Flow<List<DebriefCommitmentEntity>>

    @Query("SELECT * FROM debrief_commitments ORDER BY id")
    suspend fun getAllCommitmentsForBackup(): List<DebriefCommitmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommitment(commitment: DebriefCommitmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommitments(commitments: List<DebriefCommitmentEntity>)

    @Query("DELETE FROM debrief_commitments")
    suspend fun deleteAllCommitments()

    @Query("UPDATE debrief_commitments SET status = :status WHERE id = :id")
    suspend fun updateCommitmentStatus(id: Int, status: String)

    // Mirrors queries.py's get_open_commitments(): most recent open commitments first (what the
    // next session's daily mission checks).
    @Query("SELECT * FROM debrief_commitments WHERE status = 'open' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getOpenCommitments(limit: Int): List<DebriefCommitmentEntity>

    // Mirrors queries.py's add_debrief_commitment()'s fuzzy-dedup read.
    @Query("SELECT * FROM debrief_commitments WHERE status = 'open'")
    suspend fun getAllOpenCommitments(): List<DebriefCommitmentEntity>

    @Query("SELECT * FROM debrief_commitments WHERE id = :id")
    suspend fun getCommitmentById(id: Int): DebriefCommitmentEntity?

    @Update
    suspend fun updateCommitment(commitment: DebriefCommitmentEntity)

    // Mirrors queries.py's add_debrief_commitment()'s open-set overflow eviction — oldest open
    // commitments beyond the cap get dropped rather than left to grow forever.
    @Query(
        """UPDATE debrief_commitments SET status = 'dropped' WHERE id IN (
            SELECT id FROM debrief_commitments WHERE status = 'open' ORDER BY createdAt ASC LIMIT :count
        )"""
    )
    suspend fun dropOldestOpenCommitments(count: Int)

    // Mirrors queries.py's get_commitment_stats(): counts by status for progress surfaces.
    @Query("SELECT status, COUNT(*) as cnt FROM debrief_commitments GROUP BY status")
    suspend fun getCommitmentStatusCounts(): List<CommitmentStatusCount>

    // --- App Logs ---
    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AppLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AppLogEntity)

    // --- Settings ---
    @Query("SELECT * FROM settings")
    fun getAllSettings(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings ORDER BY `key`")
    suspend fun getAllSettingsForBackup(): List<SettingEntity>

    @Query("SELECT * FROM settings WHERE `key` = :key")
    suspend fun getSettingByKey(key: String): SettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: SettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: List<SettingEntity>)

    @Query("DELETE FROM settings")
    suspend fun deleteAllSettings()
}

// Mirrors queries.py's get_commitment_stats() row shape.
data class CommitmentStatusCount(val status: String, val cnt: Int)

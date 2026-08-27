package com.example.medvoicetrainer.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "sessions",
    indices = [
        Index(name = "idx_sessions_survival_history", value = ["mode", "deletedAt", "learnerTurnCount", "createdAt"]),
        Index(name = "idx_sessions_domain_history", value = ["analysisDomain", "deletedAt", "createdAt"])
    ]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val createdAt: String,
    val mode: String,
    val analysisDomain: String = "clinical",
    val caseName: String,
    val caseId: String? = null,
    val evalTemplate: String? = null,
    val voiceBackend: String = "gemini",
    val voiceModel: String? = null,
    val analysisModel: String? = null,
    val durationSeconds: Int = 0,
    val rawTranscript: String, // stored as JSON string
    val learnerTurnCount: Int = 0,
    val rawClaudeResponse: String? = null,
    val rawCaseJson: String = "{}",
    val rawEvalJson: String? = null,
    val grammarScore: Double = 0.0,
    val medicalAccuracyScore: Double = 0.0,
    val clinicalReasoningScore: Double = 0.0,
    val professionalismScore: Double = 0.0,
    val fluencyScore: Double = 0.0,
    val selfGrammar: Double = 0.0,
    val selfMedicalAccuracy: Double = 0.0,
    val selfClinicalReasoning: Double = 0.0,
    val selfProfessionalism: Double = 0.0,
    val selfFluency: Double = 0.0,
    val selfScoresJson: String? = null,
    val checklistResults: String? = null,
    val historyCompleteness: Double = 0.0,
    val iceElicited: Int = 0,
    val empathyMarkersFound: String? = null,
    val studentSoapNote: String? = null,
    val soapNote: String? = null,
    val referenceSoap: String? = null,
    val corrections: String? = null, // JSON string of spelling/grammar corrections
    val ankiCards: String? = null,
    val summaryFeedback: String? = null,
    /**
     * How this session stopped being live — see [SessionEndReason]. Distinguishes an accident
     * (process death, failed analysis) from a decision the learner already made ("discard",
     * "skip analysis"), which the empty analysis columns alone cannot.
     */
    val endReason: String = SessionEndReason.IN_PROGRESS,
    val debriefChat: String? = null,
    val debriefInsights: String? = null,
    val docxPath: String? = null,
    
    // Token usage and cost tracking
    val claudeInputTokens: Int = 0,
    val claudeOutputTokens: Int = 0,
    val claudeCachedTokens: Int = 0,
    val claudeCostUsd: Double = 0.0,
    val voiceCostUsd: Double = 0.0,
    val totalCostUsd: Double = 0.0,
    val voiceInputTextTokens: Int = 0,
    val voiceInputAudioTokens: Int = 0,
    val voiceOutputTextTokens: Int = 0,
    val voiceOutputAudioTokens: Int = 0,
    val voiceCachedInputTextTokens: Int = 0,
    val voiceCachedInputAudioTokens: Int = 0,
    val voiceThinkingTokens: Int = 0,
    /** True only when voice token counts came from provider usage metadata. */
    val voiceUsageExact: Boolean = false,
    /** True when any component of totalCostUsd is reconstructed rather than provider-reported. */
    val costEstimated: Boolean = true,
    val costReportPath: String? = null,
    val deletedAt: String? = null,
    
    // Legacy support or extra fluency stats
    val userWordCount: Int = 0,
    val wordsPerMinute: Double = 0.0,
    val fillerRate: Double = 0.0,
    val talkTimeRatio: Double = 0.0
)

/** Compact projection for practice-screen cost/unlock decisions; excludes all large JSON text. */
data class SessionCostSample(
    val createdAt: String,
    val voiceBackend: String,
    val totalCostUsd: Double,
    val analyzed: Boolean,
)

@Serializable
@Entity(tableName = "api_usage_events")
data class ApiUsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: String,
    val provider: String,
    val model: String,
    val operation: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double,
    val estimated: Boolean,
)

@Serializable
@Entity(tableName = "app_logs")
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: String,
    val level: String,
    val sessionId: Int? = null,
    val message: String,
    val traceback: String? = null
)

@Serializable
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: String
)

@Serializable
@Entity(tableName = "error_items")
data class ErrorItemEntity(
    @PrimaryKey val key: String, // original incorrect phrase/word
    val category: String,
    val original: String,
    val corrected: String,
    val explanation: String = "",
    val state: String = "new", // "observed", "new", "learning", "review", "mastered"
    val seenCount: Int = 0,
    val correctStreak: Int = 0,
    val intervalDays: Int = 1,
    val dueAt: String, // ISO timestamp
    val firstSeen: String,
    val lastSeen: String,
    val lastSessionId: Int? = null,
    val absentStreak: Int = 0,
    val lapses: Int = 0,
    val domain: String = "clinical",
    /** Stable reusable rule id; legacy rows keep an empty value and use surface matching. */
    val patternId: String = ""
)

@Serializable
@Entity(tableName = "debrief_commitments")
data class DebriefCommitmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Int = 0,
    val createdAt: String,
    val text: String,
    val focusArea: String = "",
    val status: String = "open", // "open" or "completed"
    val keptCount: Int = 0,
    val checkedCount: Int = 0,
    val lastResult: String? = null,
    val lastCheckedAt: String? = null,
    val lastCheckedSessionId: Int? = null
)

@Serializable
@Entity(
    tableName = "listening_attempts",
    indices = [
        Index(name = "idx_listening_attempts_due", value = ["drillId", "nextDueAt"])
    ]
)
data class ListeningAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val createdAt: String,
    val drillId: String,
    val category: String? = null,
    val difficulty: Int,
    val skillTags: String = "",
    val accent: String? = null,
    val pace: String? = null,
    val noiseProfile: String? = null,
    val voiceName: String? = null,
    val provider: String? = null,
    val audioSource: String? = null,
    val audioMetadataJson: String? = null,
    val answerText: String? = null,
    val answerDetailsJson: String? = null,
    val answerPhase: String = "legacy",
    val assistanceBeforeCommit: Int = 0,
    val detailsCorrect: Int,
    val detailsTotal: Int,
    val detailResultsJson: String = "",
    val unaidedDetailsCorrect: Int? = null,
    val unaidedDetailsTotal: Int? = null,
    val unaidedAccuracy: Double? = null,
    val assistedDetailsCorrect: Int? = null,
    val assistedDetailsTotal: Int? = null,
    val assistedAccuracy: Double? = null,
    val firstPassCorrect: Int = 0,
    val replayCount: Int = 0,
    val cleanReplayCount: Int = 0,
    val revealCount: Int = 0,
    val repairCount: Int = 0,
    val repairStrategies: String = "",
    val responseLatencySeconds: Double? = null,
    val intervalDays: Int = 1,
    val nextDueAt: String = "",
    val authenticityRating: Int? = null,
    val accentMatchRating: Int? = null,
    val naturalnessRating: Int? = null
)

/** Mirrors listening_queries.py's listening_validation_summary() return shape. */
data class ListeningValidationSummary(
    val rated: Int,
    val authenticity: Double?,
    val accentMatch: Double?,
    val naturalness: Double?
)

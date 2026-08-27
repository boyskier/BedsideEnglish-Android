package com.example.medvoicetrainer.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        ApiUsageEventEntity::class,
        ErrorItemEntity::class,
        ListeningAttemptEntity::class,
        DebriefCommitmentEntity::class,
        AppLogEntity::class,
        SettingEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        // Versions 1→3 changed encrypted settings code but not the Room entity schema. Explicit
        // no-op migrations retain successful learners' history instead of deleting it.
        private val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE error_items ADD COLUMN patternId TEXT NOT NULL DEFAULT ''"
                )
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN voiceInputTextTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN voiceInputAudioTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN voiceOutputTextTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN voiceOutputAudioTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN voiceCachedInputTextTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN voiceCachedInputAudioTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN voiceThinkingTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN voiceUsageExact INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN costEstimated INTEGER NOT NULL DEFAULT 1")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS api_usage_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        createdAt TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        inputTokens INTEGER NOT NULL,
                        outputTokens INTEGER NOT NULL,
                        costUsd REAL NOT NULL,
                        estimated INTEGER NOT NULL
                    )""".trimIndent()
                )
            }
        }

        // Records why each session stopped being live (see SessionEndReason), so the Home recovery
        // card can stop re-offering sessions the learner deliberately discarded.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sessions ADD COLUMN endReason TEXT NOT NULL DEFAULT " +
                        "'${SessionEndReason.IN_PROGRESS}'"
                )
                // Anything already carrying feedback plainly finished. Existing rows without it
                // keep the pre-migration default, so their intent is unknown — they stay eligible
                // for recovery exactly as they were before, and SessionRecovery's freshness bar
                // (rather than a guess made here) is what stops them piling up on Home.
                db.execSQL(
                    "UPDATE sessions SET endReason = '${SessionEndReason.COMPLETED}' " +
                        "WHERE summaryFeedback IS NOT NULL AND TRIM(summaryFeedback) <> ''"
                )
            }
        }

        /**
         * Room's v7 entity has no SQL default for endReason, but MIGRATION_6_7 had to use a
         * DEFAULT while adding the NOT NULL column. SQLite retains that default in the table
         * schema, so Room rejects an upgraded v6 database even though every row is valid. Rebuild
         * the table once to make upgraded and freshly-created schemas identical without dropping
         * any learner history.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sessions_v8` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `createdAt` TEXT NOT NULL, `mode` TEXT NOT NULL,
                        `analysisDomain` TEXT NOT NULL, `caseName` TEXT NOT NULL,
                        `caseId` TEXT, `evalTemplate` TEXT, `voiceBackend` TEXT NOT NULL,
                        `voiceModel` TEXT, `analysisModel` TEXT,
                        `durationSeconds` INTEGER NOT NULL, `rawTranscript` TEXT NOT NULL,
                        `learnerTurnCount` INTEGER NOT NULL, `rawClaudeResponse` TEXT,
                        `rawCaseJson` TEXT NOT NULL, `rawEvalJson` TEXT,
                        `grammarScore` REAL NOT NULL, `medicalAccuracyScore` REAL NOT NULL,
                        `clinicalReasoningScore` REAL NOT NULL, `professionalismScore` REAL NOT NULL,
                        `fluencyScore` REAL NOT NULL, `selfGrammar` REAL NOT NULL,
                        `selfMedicalAccuracy` REAL NOT NULL, `selfClinicalReasoning` REAL NOT NULL,
                        `selfProfessionalism` REAL NOT NULL, `selfFluency` REAL NOT NULL,
                        `selfScoresJson` TEXT, `checklistResults` TEXT,
                        `historyCompleteness` REAL NOT NULL, `iceElicited` INTEGER NOT NULL,
                        `empathyMarkersFound` TEXT, `studentSoapNote` TEXT, `soapNote` TEXT,
                        `referenceSoap` TEXT, `corrections` TEXT, `ankiCards` TEXT,
                        `summaryFeedback` TEXT, `endReason` TEXT NOT NULL, `debriefChat` TEXT,
                        `debriefInsights` TEXT, `docxPath` TEXT,
                        `claudeInputTokens` INTEGER NOT NULL, `claudeOutputTokens` INTEGER NOT NULL,
                        `claudeCachedTokens` INTEGER NOT NULL, `claudeCostUsd` REAL NOT NULL,
                        `voiceCostUsd` REAL NOT NULL, `totalCostUsd` REAL NOT NULL,
                        `voiceInputTextTokens` INTEGER NOT NULL,
                        `voiceInputAudioTokens` INTEGER NOT NULL,
                        `voiceOutputTextTokens` INTEGER NOT NULL,
                        `voiceOutputAudioTokens` INTEGER NOT NULL,
                        `voiceCachedInputTextTokens` INTEGER NOT NULL,
                        `voiceCachedInputAudioTokens` INTEGER NOT NULL,
                        `voiceThinkingTokens` INTEGER NOT NULL, `voiceUsageExact` INTEGER NOT NULL,
                        `costEstimated` INTEGER NOT NULL, `costReportPath` TEXT, `deletedAt` TEXT,
                        `userWordCount` INTEGER NOT NULL, `wordsPerMinute` REAL NOT NULL,
                        `fillerRate` REAL NOT NULL, `talkTimeRatio` REAL NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """INSERT INTO `sessions_v8` (
                        `id`, `createdAt`, `mode`, `analysisDomain`, `caseName`, `caseId`,
                        `evalTemplate`, `voiceBackend`, `voiceModel`, `analysisModel`,
                        `durationSeconds`, `rawTranscript`, `learnerTurnCount`, `rawClaudeResponse`,
                        `rawCaseJson`, `rawEvalJson`, `grammarScore`, `medicalAccuracyScore`,
                        `clinicalReasoningScore`, `professionalismScore`, `fluencyScore`,
                        `selfGrammar`, `selfMedicalAccuracy`, `selfClinicalReasoning`,
                        `selfProfessionalism`, `selfFluency`, `selfScoresJson`, `checklistResults`,
                        `historyCompleteness`, `iceElicited`, `empathyMarkersFound`,
                        `studentSoapNote`, `soapNote`, `referenceSoap`, `corrections`, `ankiCards`,
                        `summaryFeedback`, `endReason`, `debriefChat`, `debriefInsights`, `docxPath`,
                        `claudeInputTokens`, `claudeOutputTokens`, `claudeCachedTokens`,
                        `claudeCostUsd`, `voiceCostUsd`, `totalCostUsd`, `voiceInputTextTokens`,
                        `voiceInputAudioTokens`, `voiceOutputTextTokens`, `voiceOutputAudioTokens`,
                        `voiceCachedInputTextTokens`, `voiceCachedInputAudioTokens`,
                        `voiceThinkingTokens`, `voiceUsageExact`, `costEstimated`, `costReportPath`,
                        `deletedAt`, `userWordCount`, `wordsPerMinute`, `fillerRate`, `talkTimeRatio`
                    ) SELECT
                        `id`, `createdAt`, `mode`, `analysisDomain`, `caseName`, `caseId`,
                        `evalTemplate`, `voiceBackend`, `voiceModel`, `analysisModel`,
                        `durationSeconds`, `rawTranscript`, `learnerTurnCount`, `rawClaudeResponse`,
                        `rawCaseJson`, `rawEvalJson`, `grammarScore`, `medicalAccuracyScore`,
                        `clinicalReasoningScore`, `professionalismScore`, `fluencyScore`,
                        `selfGrammar`, `selfMedicalAccuracy`, `selfClinicalReasoning`,
                        `selfProfessionalism`, `selfFluency`, `selfScoresJson`, `checklistResults`,
                        `historyCompleteness`, `iceElicited`, `empathyMarkersFound`,
                        `studentSoapNote`, `soapNote`, `referenceSoap`, `corrections`, `ankiCards`,
                        `summaryFeedback`, `endReason`, `debriefChat`, `debriefInsights`, `docxPath`,
                        `claudeInputTokens`, `claudeOutputTokens`, `claudeCachedTokens`,
                        `claudeCostUsd`, `voiceCostUsd`, `totalCostUsd`, `voiceInputTextTokens`,
                        `voiceInputAudioTokens`, `voiceOutputTextTokens`, `voiceOutputAudioTokens`,
                        `voiceCachedInputTextTokens`, `voiceCachedInputAudioTokens`,
                        `voiceThinkingTokens`, `voiceUsageExact`, `costEstimated`, `costReportPath`,
                        `deletedAt`, `userWordCount`, `wordsPerMinute`, `fillerRate`, `talkTimeRatio`
                    FROM `sessions`""".trimIndent()
                )
                db.execSQL("DROP TABLE `sessions`")
                db.execSQL("ALTER TABLE `sessions_v8` RENAME TO `sessions`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_sessions_survival_history` " +
                        "ON `sessions` (`mode`, `deletedAt`, `learnerTurnCount`, `createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_sessions_domain_history` " +
                        "ON `sessions` (`analysisDomain`, `deletedAt`, `createdAt`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "med_voice_trainer.db"
                )
                .addMigrations(
                    MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

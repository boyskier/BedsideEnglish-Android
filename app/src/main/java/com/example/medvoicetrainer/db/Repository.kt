package com.example.medvoicetrainer.db

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.room.withTransaction
import com.example.medvoicetrainer.api.DEFAULT_GEMINI_ANALYSIS_MODEL
import com.example.medvoicetrainer.api.normalizeGeminiAnalysisModel
import com.example.medvoicetrainer.export.UserBackupPayload
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val ENCRYPTED_PREFS_NAME = "med_voice_encrypted_prefs"

// Last-resort plaintext store, used only when the device cannot provide Keystore-backed prefs at
// all (see Repository.openPrefs). Excluded from cloud backup and device transfer alongside the
// encrypted file — see res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml.
private const val FALLBACK_PREFS_NAME = "med_voice_prefs_unencrypted"

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

class Repository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val appDao = database.appDao()
    private val learnerAudioStore = com.example.medvoicetrainer.voice.LearnerAudioStore(context.applicationContext)
    
    /**
     * True when [prefs] is the Keystore-backed store. False means every recovery attempt below
     * failed and settings live in a plaintext file — surfaced so the UI can warn rather than
     * silently downgrading where the learner's API keys are kept.
     */
    var isSecureStorageAvailable: Boolean = true
        private set

    private val prefs: SharedPreferences = openPrefs(context)

    /**
     * Open the encrypted settings store, recovering instead of crashing when the Keystore keyset
     * can't be read.
     *
     * `EncryptedSharedPreferences.create()` throws when the master key and the keyset disagree —
     * a documented failure on some OEM Keystore implementations and after certain device
     * migrations. This runs during Repository construction, i.e. during MainViewModel
     * construction, so an uncaught throw here made the app permanently unlaunchable with no
     * screen left to reset keys from. Recovery order:
     *
     *  1. Open normally.
     *  2. On failure, drop the unreadable keyset file and master key and build a fresh pair. The
     *     old ciphertext is unrecoverable either way, so this costs the learner their saved
     *     settings and API keys (onboarding asks again) but leaves the app usable and still
     *     encrypted.
     *  3. If even that fails, the device cannot give us Keystore-backed storage at all; fall back
     *     to a plaintext file so the app still runs, and flag it via [isSecureStorageAvailable].
     */
    private fun openPrefs(context: Context): SharedPreferences {
        try {
            return createEncryptedPrefs(context)
        } catch (first: kotlinx.coroutines.CancellationException) { throw first } catch (first: Exception) {
            // Fall through to the wipe-and-retry path below.
        }

        try {
            context.deleteSharedPreferences(ENCRYPTED_PREFS_NAME)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // Pre-N-style fallback: delete the backing file directly.
            try {
                java.io.File(context.filesDir.parentFile, "shared_prefs/$ENCRYPTED_PREFS_NAME.xml").delete()
            } catch (e2: kotlinx.coroutines.CancellationException) { throw e2 } catch (e2: Exception) {
                // Nothing more to try; the retry below will tell us whether it mattered.
            }
        }
        try {
            java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // The alias may already be gone — that is the case we are trying to reach.
        }

        return try {
            createEncryptedPrefs(context)
        } catch (second: kotlinx.coroutines.CancellationException) { throw second } catch (second: Exception) {
            isSecureStorageAvailable = false
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // --- Database Flows & Suspend Functions ---
    val sessions: Flow<List<SessionEntity>> = appDao.getAllSessions()
    val recentSessionCostSamples: Flow<List<SessionCostSample>> = appDao.getRecentSessionCostSamples()
    val errorItems: Flow<List<ErrorItemEntity>> = appDao.getAllErrorItems()
    val listeningAttempts: Flow<List<ListeningAttemptEntity>> = appDao.getAllListeningAttempts()
    val commitments: Flow<List<DebriefCommitmentEntity>> = appDao.getAllCommitments()
    val apiUsageEvents: Flow<List<ApiUsageEventEntity>> = appDao.getApiUsageEvents()

    suspend fun getSessionById(id: Int) = appDao.getSessionById(id)
    suspend fun getAllSessionsList() = appDao.getAllSessionsList()
    suspend fun insertSession(session: SessionEntity) = appDao.insertSession(session)
    suspend fun updateSession(session: SessionEntity) = appDao.updateSession(session)
    suspend fun updateSessionTranscript(id: Int, transcriptJson: String, learnerTurnCount: Int) =
        appDao.updateSessionTranscript(id, transcriptJson, learnerTurnCount)
    suspend fun deleteSessionById(id: Int) = appDao.hardDeleteSessionById(id)
    suspend fun saveDebriefChat(id: Int, chatJson: String) = appDao.saveDebriefChat(id, chatJson)
    suspend fun saveDebriefInsights(id: Int, insightsJson: String) = appDao.saveDebriefInsights(id, insightsJson)
    suspend fun updateSessionCorrections(id: Int, correctionsJson: String) =
        appDao.updateSessionCorrections(id, correctionsJson)

    suspend fun getDueErrorItems(nowTime: String, maxItems: Int = 6) = appDao.getDueErrorItems(nowTime, maxItems)
    suspend fun getAllActiveErrorItems(maxItems: Int = 6) = appDao.getAllActiveErrorItems(maxItems)
    suspend fun getAllErrorItemsList() = appDao.getAllErrorItemsList()
    suspend fun getErrorItemByKey(key: String) = appDao.getErrorItemByKey(key)
    suspend fun insertErrorItem(errorItem: ErrorItemEntity) = appDao.insertErrorItem(errorItem)
    suspend fun updateErrorItem(errorItem: ErrorItemEntity) = appDao.updateErrorItem(errorItem)
    suspend fun deleteErrorItemByKey(key: String) = appDao.deleteErrorItemByKey(key)

    /**
     * Mirrors listening_queries.py's record_listening_attempt(): detects assistance from
     * replay/clean-replay/repair counts (reveal only ever happens post-commit, so it isn't part
     * of the at-commit detection, matching Python), splits the score into unaided-vs-assisted
     * accuracy, and computes the next spaced-repetition interval (only a genuinely unaided,
     * first-pass-correct commit ever advances the ladder) before persisting. Previously this was
     * a bare insert with intervalDays always defaulting to 1 and nextDueAt left as an empty
     * string for every attempt.
     */
    suspend fun recordListeningAttempt(
        drillId: String,
        detailsCorrect: Int,
        detailsTotal: Int,
        category: String? = null,
        difficulty: Int = 1,
        skillTags: List<String> = emptyList(),
        replayCount: Int = 0,
        cleanReplayCount: Int = 0,
        revealCount: Int = 0,
        repairStrategies: List<String> = emptyList(),
        firstPassCorrectClaim: Boolean = true,
        accent: String? = null,
        voiceName: String? = null,
        provider: String? = null,
        audioSource: String? = null
    ): Int {
        val previousInterval = appDao.getLastUnaidedIntervalDays(drillId)
        val computed = com.example.medvoicetrainer.analysis.ListeningQueries.computeAttempt(
            detailsCorrect = detailsCorrect,
            detailsTotal = detailsTotal,
            replayCount = replayCount,
            cleanReplayCount = cleanReplayCount,
            revealCount = revealCount,
            repairCount = repairStrategies.size,
            firstPassCorrectClaim = firstPassCorrectClaim,
            previousIntervalDays = previousInterval
        )
        val now = Date()
        val nextDue = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, computed.intervalDays)
        }
        val entity = ListeningAttemptEntity(
            createdAt = localIsoFormat.format(now),
            drillId = drillId,
            category = category,
            difficulty = difficulty,
            skillTags = org.json.JSONArray(skillTags).toString(),
            accent = accent,
            voiceName = voiceName,
            provider = provider,
            audioSource = audioSource,
            detailsCorrect = computed.detailsCorrect,
            detailsTotal = computed.detailsTotal,
            answerPhase = computed.answerPhase,
            assistanceBeforeCommit = if (computed.assistance) 1 else 0,
            unaidedDetailsCorrect = computed.unaidedCorrect,
            unaidedDetailsTotal = computed.unaidedTotal,
            unaidedAccuracy = computed.unaidedAccuracy,
            assistedDetailsCorrect = computed.assistedCorrect,
            assistedDetailsTotal = computed.assistedTotal,
            assistedAccuracy = computed.assistedAccuracy,
            firstPassCorrect = if (computed.firstPass) 1 else 0,
            replayCount = replayCount,
            cleanReplayCount = cleanReplayCount,
            revealCount = revealCount,
            repairCount = repairStrategies.size,
            repairStrategies = org.json.JSONArray(repairStrategies).toString(),
            intervalDays = computed.intervalDays,
            nextDueAt = localIsoFormat.format(nextDue.time)
        )
        return appDao.insertListeningAttempt(entity).toInt()
    }

    /** Mirrors listening_queries.py's update_listening_assistance(). */
    suspend fun updateListeningAssistance(
        attemptId: Int,
        replayCount: Int,
        cleanReplayCount: Int,
        revealCount: Int,
        repairStrategies: List<String>
    ) {
        val previousRaw = appDao.getRepairStrategies(attemptId)
        val previous = try {
            if (previousRaw.isNullOrBlank()) emptyList() else {
                val arr = org.json.JSONArray(previousRaw)
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        val merged = (previous + repairStrategies).filter { it.isNotBlank() }.distinct()
        appDao.applyListeningAssistanceUpdate(
            id = attemptId,
            replayCount = maxOf(0, replayCount),
            cleanReplayCount = maxOf(0, cleanReplayCount),
            revealCount = maxOf(0, revealCount),
            repairCount = merged.size,
            repairStrategies = org.json.JSONArray(merged).toString()
        )
    }

    /** Mirrors listening_queries.py's update_listening_ratings(). */
    suspend fun updateListeningRatings(attemptId: Int, authenticity: Int?, accentMatch: Int?, naturalness: Int?) {
        fun clamp(v: Int?) = v?.let { minOf(5, maxOf(1, it)) }
        appDao.updateListeningRatings(attemptId, clamp(authenticity), clamp(accentMatch), clamp(naturalness))
    }

    /** Mirrors listening_queries.py's listening_validation_summary(). */
    suspend fun getListeningValidationSummary() = appDao.getListeningValidationSummary()

    suspend fun insertCommitment(commitment: DebriefCommitmentEntity) = appDao.insertCommitment(commitment)
    suspend fun updateCommitmentStatus(id: Int, status: String) = appDao.updateCommitmentStatus(id, status)

    // --- Session listing / lifecycle (queries.py parity) ---
    suspend fun listSessionsByMode(mode: String, limit: Int = 200) = appDao.listSessionsByMode(mode, limit)
    suspend fun listClinicalSessions(limit: Int = 200) = appDao.listClinicalSessions(limit)
    suspend fun listTrashSessions(limit: Int = 200) = appDao.listTrashSessions(limit)
    suspend fun restoreSession(id: Int) = appDao.restoreSession(id)

    /** Soft-delete (move to trash) — sets deletedAt to now (queries.py soft_delete_session). */
    suspend fun softDeleteSession(id: Int) {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        appDao.softDeleteSession(id, now)
    }
    suspend fun getAllTranscripts() = appDao.getAllTranscripts()
    suspend fun getAllClinicalTranscripts() = appDao.getAllClinicalTranscripts()
    suspend fun getSessionsForCase(caseId: String) = appDao.getSessionsForCase(caseId)
    suspend fun finalizeEmptySession(id: Int, durationSeconds: Int) = appDao.finalizeEmptySession(id, durationSeconds)
    suspend fun finalizeVoiceOnlySession(
        id: Int,
        durationSeconds: Int,
        voiceModel: String?,
        voiceCostUsd: Double,
        usage: com.example.medvoicetrainer.voice.VoiceApiUsage?,
        estimated: Boolean,
        endReason: String,
    ) = appDao.finalizeVoiceOnlySession(
        id = id,
        durationSeconds = durationSeconds,
        voiceModel = voiceModel,
        voiceCostUsd = voiceCostUsd,
        inputText = usage?.inputTextTokens ?: 0,
        inputAudio = usage?.inputAudioTokens ?: 0,
        outputText = usage?.outputTextTokens ?: 0,
        outputAudio = usage?.outputAudioTokens ?: 0,
        cachedText = usage?.cachedInputTextTokens ?: 0,
        cachedAudio = usage?.cachedInputAudioTokens ?: 0,
        thinking = usage?.thinkingTokens ?: 0,
        usageExact = usage != null,
        estimated = estimated,
        endReason = endReason,
    )
    suspend fun updateSessionEndReason(id: Int, endReason: String) =
        appDao.updateSessionEndReason(id, endReason)
    suspend fun updateSessionCaseSnapshot(id: Int, caseJson: String) = appDao.updateSessionCaseSnapshot(id, caseJson)
    suspend fun updateSessionRawEvaluation(id: Int, rawEvalJson: String) =
        appDao.updateSessionRawEvaluation(id, rawEvalJson)
    suspend fun saveDocxPath(id: Int, path: String) = appDao.saveDocxPath(id, path)
    suspend fun saveStudentSoap(id: Int, soapText: String) = appDao.saveStudentSoap(id, soapText)

    suspend fun saveSelfScores(id: Int, selfScores: Map<String, Any?>, everyday: Boolean) {
        fun pick(vararg keys: String): Double {
            for (k in keys) {
                val v = selfScores[k]
                if (v is Number) return v.toDouble()
            }
            return 0.0
        }
        val selfScoresObj = org.json.JSONObject(selfScores)
        appDao.saveSelfScores(
            id = id,
            selfGrammar = if (everyday) pick("naturalness", "grammar") else pick("grammar"),
            selfMedicalAccuracy = if (everyday) 0.0 else pick("medical_accuracy"),
            selfClinicalReasoning = if (everyday) 0.0 else pick("clinical_reasoning"),
            selfProfessionalism = if (everyday) 0.0 else pick("professionalism"),
            selfFluency = if (everyday) pick("fluency", "communication_fluency") else pick("communication_fluency", "fluency"),
            selfScoresJson = selfScoresObj.toString()
        )
    }

    /** Mirrors queries.py's list_survival_practice_cases(): parsed case snapshots only. */
    suspend fun listSurvivalPracticeCases(limit: Int = 5000): List<Map<String, Any?>> {
        return appDao.listSurvivalPracticeCaseJson(limit).mapNotNull { raw ->
            try {
                jsonObjectToMap(org.json.JSONObject(raw))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                null
            }
        }
    }

    /** Mirrors queries.py's purge_old_trash(): permanently deletes trash older than [days] days. */
    suspend fun purgeOldTrash(days: Int = 7): Int {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        val cutoff = localIsoFormat.format(cal.time)
        val rowsToPurge = appDao.listTrashSessions(100_000).filter {
            !it.deletedAt.isNullOrBlank() && it.deletedAt < cutoff
        }
        val purged = appDao.purgeOldTrash(cutoff)
        rowsToPurge.forEach { learnerAudioStore.deleteAudioReferencedBy(it.rawTranscript) }
        return purged
    }

    // Mirrors queries.py's get_due_count()/get_due_count_by().
    suspend fun getDueCount(): Int = appDao.getDueCountBy(nowIso())
    suspend fun getDueCountBy(deadlineIso: String): Int = appDao.getDueCountBy(deadlineIso)

    // Mirrors queries.py's get_total_sessions_analyzed()/get_weekly_cost()/get_session_cost().
    suspend fun getTotalSessionsAnalyzed(): Int = appDao.getTotalSessionsAnalyzed()

    suspend fun getWeeklyCost(): Double {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -7)
        return appDao.getWeeklyCost(localIsoFormat.format(cal.time))
    }

    suspend fun getSessionCost(id: Int): Double? = appDao.getSessionCost(id)

    // Mirrors queries.py's get_days_since_last_session(): whole calendar days (local time) since
    // the last analyzed session, or null for a brand-new user.
    suspend fun getDaysSinceLastSession(): Int? {
        val lastCreatedAt = appDao.getLastAnalyzedSessionCreatedAt() ?: return null
        val lastDate = try {
            java.time.LocalDate.parse(lastCreatedAt.take(10))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return null
        }
        val days = java.time.temporal.ChronoUnit.DAYS.between(lastDate, java.time.LocalDate.now())
        return maxOf(0, days.toInt())
    }

    // Mirrors queries.py's get_mastered_this_week().
    suspend fun getMasteredThisWeek(days: Int = 7): Int {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return appDao.getMasteredSince(localIsoFormat.format(cal.time))
    }

    // Mirrors queries.py's get_confidence_trend()'s two comparison windows; the caller (an
    // analysis engine) computes the averages, matching the "engines take fetched data" convention.
    suspend fun getConfidenceTrendWindows(days: Int = 7): Pair<List<SessionEntity>, List<SessionEntity>> {
        val now = Calendar.getInstance()
        val nowIso = localIsoFormat.format(now.time)
        val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
        val weekAgoIso = localIsoFormat.format(weekAgo.time)
        val priorAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days * 2) }
        val priorAgoIso = localIsoFormat.format(priorAgo.time)
        val thisWeek = appDao.getSessionsInWindowWithWordCount(weekAgoIso, nowIso)
        val priorWeek = appDao.getSessionsInWindowWithWordCount(priorAgoIso, weekAgoIso)
        return thisWeek to priorWeek
    }

    suspend fun getSurvivalRawResponses(limit: Int = 200) = appDao.getSurvivalRawResponses(limit)

    // --- Debrief commitments (queries.py's add_debrief_commitment/get_open_commitments/
    // record_commitment_check/get_commitment_stats) ---
    private val commitmentKeptToGraduate = 2
    private val commitmentMaxOpen = 10

    suspend fun getOpenCommitments(limit: Int = 3) = appDao.getOpenCommitments(limit)

    suspend fun getCommitmentStats(): Map<String, Int> {
        val stats = mutableMapOf("open" to 0, "kept" to 0, "dropped" to 0)
        appDao.getCommitmentStatusCounts().forEach { stats[it.status] = it.cnt }
        return stats
    }

    /**
     * Mirrors queries.py's add_debrief_commitment(): fuzzy-deduplicated against currently open
     * commitments so the tutor/student rephrasing the same intention doesn't create a sibling
     * row — a restated open commitment just refreshes its timestamp. Also caps the open set,
     * dropping the oldest overflow so the daily-mission prompt never carries a growing guilt list.
     */
    suspend fun addDebriefCommitment(sessionId: Int?, text: String, focusArea: String = ""): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val now = nowIso()
        val openRows = appDao.getAllOpenCommitments()
        for (row in openRows) {
            if (ErrorIdentity.similarity(trimmed, row.text) >= ErrorIdentity.MATCH_THRESHOLD) {
                appDao.updateCommitment(row.copy(createdAt = now, sessionId = sessionId ?: row.sessionId))
                return row.id
            }
        }
        val newId = appDao.insertCommitment(
            DebriefCommitmentEntity(
                sessionId = sessionId ?: 0,
                createdAt = now,
                text = trimmed,
                focusArea = focusArea.trim(),
                status = "open"
            )
        )
        val overflow = openRows.size + 1 - commitmentMaxOpen
        if (overflow > 0) {
            appDao.dropOldestOpenCommitments(overflow)
        }
        return newId.toInt()
    }

    /**
     * Mirrors queries.py's record_commitment_check(): a kept/missed/not_applicable verdict from
     * a session analysis. not_applicable leaves progress untouched; kept twice graduates the
     * habit; a miss resets the streak so graduation always means two consecutive keeps.
     */
    suspend fun recordCommitmentCheck(commitmentId: Int, result: String, sessionId: Int? = null) {
        val normalized = result.trim().lowercase()
        if (normalized !in setOf("kept", "missed", "not_applicable")) return
        val row = appDao.getCommitmentById(commitmentId) ?: return
        if (row.status != "open") return
        var keptCount = row.keptCount
        if (normalized == "kept") keptCount += 1 else if (normalized == "missed") keptCount = 0
        val status = if (keptCount >= commitmentKeptToGraduate) "kept" else "open"
        appDao.updateCommitment(
            row.copy(
                keptCount = keptCount,
                checkedCount = row.checkedCount + 1,
                status = status,
                lastResult = normalized,
                lastCheckedAt = nowIso(),
                lastCheckedSessionId = sessionId
            )
        )
    }

    private val localIsoFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private fun nowIso(): String = localIsoFormat.format(Date())

    private fun jsonObjectToMap(obj: org.json.JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        obj.keys().forEach { key -> map[key] = obj.opt(key) }
        return map
    }

    // --- Settings / SharedPreferences ---
    fun getSetting(key: String, defaultValue: String): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun hasSetting(key: String): Boolean = prefs.contains(key)

    fun setSetting(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * Snapshot every portable piece of learner state. A defensive name-based secret filter is
     * applied both here and during restore so a future credential cannot silently enter a backup.
     */
    suspend fun createUserBackupPayload(
        exportedAt: String,
        appVersion: String,
        includesLearnerAudio: Boolean,
    ): UserBackupPayload {
        // Values are carried as text, but their original SharedPreferences type is recorded next to
        // them: restoring an Int as a String would make the first getInt() on that key throw
        // ClassCastException, and only on devices that had restored a backup.
        val portablePreferenceEntries = prefs.all.mapNotNull { (key, value) ->
            if (!isPortableSettingKey(key)) return@mapNotNull null
            val typed = when (value) {
                is String -> value to null
                is Boolean -> value.toString() to UserBackupPayload.PREF_TYPE_BOOLEAN
                is Int -> value.toString() to UserBackupPayload.PREF_TYPE_INT
                is Long -> value.toString() to UserBackupPayload.PREF_TYPE_LONG
                is Float -> value.toString() to UserBackupPayload.PREF_TYPE_FLOAT
                else -> return@mapNotNull null
            }
            Triple(key, typed.first, typed.second)
        }
        val portablePreferences = portablePreferenceEntries.associate { it.first to it.second }
        val portablePreferenceTypes = portablePreferenceEntries
            .mapNotNull { (key, _, type) -> type?.let { key to it } }
            .toMap()
        return database.withTransaction {
            val portableSettings = appDao.getAllSettingsForBackup().filter {
                isPortableSettingKey(it.key)
            }
            UserBackupPayload(
                exportedAt = exportedAt,
                appVersion = appVersion,
                includesLearnerAudio = includesLearnerAudio,
                preferences = portablePreferences,
                preferenceTypes = portablePreferenceTypes,
                sessions = appDao.getAllSessionsForBackup().map {
                    it.copy(docxPath = null, costReportPath = null)
                },
                apiUsageEvents = appDao.getApiUsageEventsForBackup(),
                errorItems = appDao.getAllErrorItemsList(),
                listeningAttempts = appDao.getAllListeningAttemptsForBackup(),
                commitments = appDao.getAllCommitmentsForBackup(),
                settings = portableSettings,
            )
        }
    }

    /** Replace portable user state while deliberately preserving every credential on this device. */
    suspend fun restoreUserBackupPayload(payload: UserBackupPayload) {
        require(payload.formatVersion in 1..UserBackupPayload.CURRENT_FORMAT_VERSION) {
            "Unsupported backup format ${payload.formatVersion}"
        }
        val safePreferences = payload.preferences.filterKeys(::isPortableSettingKey)
        val safeSettings = payload.settings.filter { isPortableSettingKey(it.key) }
        database.withTransaction {
            appDao.deleteAllApiUsageEvents()
            appDao.deleteAllListeningAttempts()
            appDao.deleteAllCommitments()
            appDao.deleteAllErrorItems()
            appDao.deleteAllSettings()
            appDao.deleteAllSessions()

            if (payload.sessions.isNotEmpty()) appDao.insertSessions(payload.sessions)
            if (payload.apiUsageEvents.isNotEmpty()) appDao.insertApiUsageEvents(payload.apiUsageEvents)
            if (payload.errorItems.isNotEmpty()) appDao.insertErrorItems(payload.errorItems)
            if (payload.listeningAttempts.isNotEmpty()) appDao.insertListeningAttempts(payload.listeningAttempts)
            if (payload.commitments.isNotEmpty()) appDao.insertCommitments(payload.commitments)
            if (safeSettings.isNotEmpty()) appDao.insertSettings(safeSettings)
        }

        val editor = prefs.edit()
        prefs.all.keys.filter(::isPortableSettingKey).forEach(editor::remove)
        safePreferences.forEach { (key, value) ->
            // A format-1 backup has no type map, so everything in it stays a String — exactly how
            // it was written. Anything that fails to parse falls back to String rather than
            // aborting a restore over one malformed preference.
            val parsed = when (payload.preferenceTypes[key]) {
                UserBackupPayload.PREF_TYPE_BOOLEAN -> value.toBooleanStrictOrNull()?.let { editor.putBoolean(key, it) }
                UserBackupPayload.PREF_TYPE_INT -> value.toIntOrNull()?.let { editor.putInt(key, it) }
                UserBackupPayload.PREF_TYPE_LONG -> value.toLongOrNull()?.let { editor.putLong(key, it) }
                UserBackupPayload.PREF_TYPE_FLOAT -> value.toFloatOrNull()?.let { editor.putFloat(key, it) }
                else -> null
            }
            if (parsed == null) editor.putString(key, value)
        }
        // A half-finished onboarding/API setup flow is a device-local transient, never restored.
        editor.putString("api_setup_in_progress", "false")
        // Room is already committed at this point and there is no cross-store transaction. A
        // rare preference fsync failure must not make the caller roll back audio while leaving
        // the restored database in place; the imported learning history remains authoritative.
        editor.commit()
    }

    companion object {
        internal fun isPortableSettingKey(key: String): Boolean {
            val normalized = key.trim().lowercase(Locale.US)
            if (normalized.isEmpty()) return false
            val secretMarkers = listOf(
                "api_key", "apikey", "speech_key", "access_token", "refresh_token",
                "password", "passwd", "credential", "client_secret", "private_key"
            )
            // Device-local transients: a half-finished API setup is not learner state, and the
            // anonymous telemetry id identifies one install — restoring a backup onto a second
            // device would otherwise make both report as the same person. Telemetry mints a fresh
            // id whenever the key is missing, so dropping it here is safe.
            val deviceLocalKeys = setOf("api_setup_in_progress", "telemetry_anon_id")
            return secretMarkers.none(normalized::contains) && normalized !in deviceLocalKeys
        }
    }

    // API keys are pasted by hand (often on a phone keyboard), which routinely appends a trailing
    // space or newline. That whitespace survives into the Gemini Live WebSocket URL (?key=...) and
    // makes the server reject the connection with no obvious cause, so normalize on both read and
    // write — read-side trimming also repairs keys that were already saved with trailing whitespace.
    fun getGeminiApiKey(): String {
        return (prefs.getString("gemini_api_key", "") ?: "").trim()
    }

    fun saveGeminiApiKey(key: String) {
        prefs.edit().putString("gemini_api_key", key.trim()).apply()
    }

    /** Persist the first-run transition as one preference transaction. */
    fun completeDemoOnboarding(nativeLanguage: String) {
        prefs.edit()
            .putString("native_language", nativeLanguage)
            .putString("voice_backend", "demo")
            .putString("onboarding_step", "0")
            .putString("onboarding_completed", "true")
            .putString("api_setup_in_progress", "false")
            .apply()
    }

    /** Persist the verified credential and both pipelines atomically for first-run activation. */
    fun completeGeminiOnboarding(apiKey: String, nativeLanguage: String) {
        prefs.edit()
            .putString("gemini_api_key", apiKey.trim())
            .putString("native_language", nativeLanguage)
            .putString("analysis_backend", "gemini")
            .putString("voice_backend", "gemini")
            .putString("onboarding_step", "0")
            .putString("onboarding_completed", "true")
            .putString("api_setup_in_progress", "false")
            .apply()
    }

    fun getGeminiModel(): String {
        val saved = prefs.getString("gemini_model", DEFAULT_GEMINI_ANALYSIS_MODEL).orEmpty()
        val normalized = normalizeGeminiAnalysisModel(saved)
        if (normalized != saved) {
            // Repair installs that persisted the retired 2.5 model before the default changed.
            prefs.edit().putString("gemini_model", normalized).apply()
        }
        return normalized
    }

    fun saveGeminiModel(model: String) {
        prefs.edit().putString("gemini_model", normalizeGeminiAnalysisModel(model)).apply()
    }

    // --- Asset Helpers ---
    fun listAssetFiles(path: String): List<String> {
        return try {
            context.assets.list(path)?.toList() ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Reads a bundled asset in one shot. The previous line-by-line StringBuilder loop repeatedly
     * grew and copied its backing array, which is a real cost on the multi-megabyte content banks
     * (survival_situations.json alone is ~3.6 MB) and left a lot of garbage behind. Assets are
     * UTF-8 and LF-terminated, so a bulk read produces the same string.
     */
    fun loadAssetFile(path: String): String {
        return try {
            context.assets.open(path).use { it.readBytes().decodeToString() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ""
        }
    }
}

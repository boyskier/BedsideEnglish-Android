package com.example.medvoicetrainer.export

import com.example.medvoicetrainer.db.ApiUsageEventEntity
import com.example.medvoicetrainer.db.DebriefCommitmentEntity
import com.example.medvoicetrainer.db.ErrorItemEntity
import com.example.medvoicetrainer.db.ListeningAttemptEntity
import com.example.medvoicetrainer.db.SessionEntity
import com.example.medvoicetrainer.db.SettingEntity
import kotlinx.serialization.Serializable

/** Versioned, portable user state. Secrets, logs, caches, and device-only paths never enter it. */
@Serializable
data class UserBackupPayload(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAt: String,
    val appVersion: String,
    val includesLearnerAudio: Boolean,
    val preferences: Map<String, String>,
    /**
     * Original SharedPreferences value type per key, one of [PREF_TYPE_BOOLEAN], [PREF_TYPE_INT],
     * [PREF_TYPE_LONG], [PREF_TYPE_FLOAT]. A key missing here was — and is restored as — a String.
     *
     * Without this, every preference came back as a String and the first `getBoolean`/`getInt` on a
     * restored device would throw ClassCastException. Format-1 backups have no map and stay
     * String-only, which matches how they were written.
     */
    val preferenceTypes: Map<String, String> = emptyMap(),
    val sessions: List<SessionEntity>,
    val apiUsageEvents: List<ApiUsageEventEntity>,
    val errorItems: List<ErrorItemEntity>,
    val listeningAttempts: List<ListeningAttemptEntity>,
    val commitments: List<DebriefCommitmentEntity>,
    val settings: List<SettingEntity>,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 2

        /**
         * First format that ends the archive with [UserBackupManager.TRAILER_ENTRY]. Restores of
         * this version or newer require that entry, so a stream that was truncated mid-write can
         * never be mistaken for a complete backup.
         */
        const val TRAILER_FORMAT_VERSION = 2

        const val PREF_TYPE_BOOLEAN = "b"
        const val PREF_TYPE_INT = "i"
        const val PREF_TYPE_LONG = "l"
        const val PREF_TYPE_FLOAT = "f"
    }
}

data class UserBackupSummary(
    val sessionCount: Int,
    val errorItemCount: Int,
    val listeningAttemptCount: Int,
    val commitmentCount: Int,
    val audioFileCount: Int,
)

/** Coarse phase of a running backup, used only to label the progress UI. */
enum class UserBackupStage {
    PREPARING,
    WRITING_DATA,
    WRITING_AUDIO,
    READING_DATA,
    READING_AUDIO,
    APPLYING,
}

/**
 * Snapshot of a running export/restore.
 *
 * Backups of a long practice history take minutes, and an indeterminate spinner gives a learner no
 * way to tell "slow" from "stuck" — so the manager reports file and byte counters as it goes.
 * [fraction] is null whenever the total is not known ahead of time (every restore, and the data
 * entry of an export), which the UI renders as an indeterminate bar.
 */
data class UserBackupProgress(
    val stage: UserBackupStage,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * Why a backup failed, in the learner's terms.
 *
 * Every failure used to collapse into one "check the file and password" sentence, which cannot tell
 * a mistyped password from a file picked out of the wrong app. Each constant carries the i18n key
 * for the message the UI shows.
 */
enum class UserBackupFailure(val messageKey: String) {
    WRONG_PASSWORD("prefs.backup_error_password"),
    NOT_A_BACKUP("prefs.backup_error_not_backup"),
    NEWER_VERSION("prefs.backup_error_newer_version"),
    DAMAGED("prefs.backup_error_damaged"),
    TOO_MUCH_AUDIO("prefs.backup_error_too_much_audio"),
    TOO_LARGE("prefs.backup_error_too_large"),
    NOT_ENOUGH_SPACE("prefs.backup_error_no_space"),
    WEAK_PASSWORD("prefs.backup_error_weak_password"),
    SESSION_ACTIVE("prefs.backup_error_session_active"),
    UNKNOWN("prefs.backup_error_generic"),
}

/** A backup failure that already knows which message the learner should see. */
class UserBackupException(
    val failure: UserBackupFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        /**
         * Best-effort classification for throwables that did not come from our own checks — most
         * importantly the `IOException(AEADBadTagException)` that a wrong password produces once the
         * GCM stream reaches EOF.
         */
        fun failureOf(error: Throwable): UserBackupFailure {
            var current: Throwable? = error
            while (current != null) {
                when (current) {
                    is UserBackupException -> return current.failure
                    is javax.crypto.AEADBadTagException,
                    is javax.crypto.BadPaddingException,
                    is javax.crypto.IllegalBlockSizeException -> return UserBackupFailure.WRONG_PASSWORD
                    is java.util.zip.ZipException,
                    is java.io.EOFException -> return UserBackupFailure.DAMAGED
                    is OutOfMemoryError -> return UserBackupFailure.TOO_LARGE
                }
                if (current.message?.contains("ENOSPC") == true ||
                    current.message?.contains("No space left") == true
                ) {
                    return UserBackupFailure.NOT_ENOUGH_SPACE
                }
                current = current.cause
            }
            return UserBackupFailure.UNKNOWN
        }
    }
}

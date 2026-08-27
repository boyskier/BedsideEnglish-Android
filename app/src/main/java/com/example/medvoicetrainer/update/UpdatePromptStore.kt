package com.example.medvoicetrainer.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers which update version the learner declined and when, so a "not now" is honoured across
 * launches (see [UpdatePolicy.isSnoozed]).
 *
 * Deliberately its own small SharedPreferences file rather than Repository's encrypted store: these
 * two values are not secrets, and the update check runs from the Activity at cold start, before —
 * and independently of — the ViewModel/Room graph.
 */
class UpdatePromptStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readMemory(): UpdatePromptMemory = UpdatePromptMemory(
        dismissedVersionCode = prefs.getInt(KEY_DISMISSED_VERSION, 0),
        lastPromptEpochDay = prefs.getLong(KEY_LAST_PROMPT_DAY, 0L),
    )

    fun recordDismissal(versionCode: Int, todayEpochDay: Long) {
        prefs.edit()
            .putInt(KEY_DISMISSED_VERSION, versionCode)
            .putLong(KEY_LAST_PROMPT_DAY, todayEpochDay)
            .apply()
    }

    /** Cleared once an update actually installs, so the next release starts from a clean slate. */
    fun clear() {
        prefs.edit().remove(KEY_DISMISSED_VERSION).remove(KEY_LAST_PROMPT_DAY).apply()
    }

    private companion object {
        const val PREFS_NAME = "in_app_update"
        const val KEY_DISMISSED_VERSION = "dismissed_version_code"
        const val KEY_LAST_PROMPT_DAY = "last_prompt_epoch_day"
    }
}

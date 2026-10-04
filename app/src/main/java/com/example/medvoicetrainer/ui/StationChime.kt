package com.example.medvoicetrainer.ui

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The short tone a timed station plays at its announcements (Korean CPX: two minutes left, time up),
 * standing in for the exam hall's automated announcements. Best effort: a device without a tone
 * generator just shows the on-screen banner.
 */
object StationChime {
    private val scope = CoroutineScope(Dispatchers.Default)

    fun play(double: Boolean) {
        scope.launch {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                try {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
                    if (double) {
                        delay(400)
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
                    }
                    delay(400)
                } finally {
                    tone.release()
                }
            }
        }
    }
}

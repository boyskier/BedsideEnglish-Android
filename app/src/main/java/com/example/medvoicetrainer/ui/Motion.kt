package com.example.medvoicetrainer.ui

import android.provider.Settings
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * docs/design/android-ui-spec.html §14 "Motion": honor the system's "Remove animations" setting
 * — transitions become cuts, autoscroll jumps instead of smooth-scrolls. Compose's animation APIs
 * (unlike legacy View ValueAnimator/ObjectAnimator) do NOT read
 * [Settings.Global.ANIMATOR_DURATION_SCALE] automatically, so call sites that animate must check
 * this and swap in [snap] themselves — there is no framework-level opt-out to rely on.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            false
        }
    }
}

/** [tween]-or-[snap] depending on [rememberReducedMotion] — use in place of a bare `tween(millis)`. */
@Composable
fun <T> motionSpec(millis: Int) = if (rememberReducedMotion()) snap<T>() else tween<T>(millis)

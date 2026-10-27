package com.snipsnap.app.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the person has asked the phone for no animation. `App` holds it
 * live with [rememberReduceMotion] and provides it at the root, so a drawing
 * that would otherwise loop (the busy bar's sweep, the spinner's turn) can ask
 * here and stand still instead, without a parameter threaded to it.
 *
 * False by default, which is what a preview or a test that provides
 * nothing gets: the motion is on unless somebody said otherwise. A test
 * that cannot run an infinite transition against its frozen clock says
 * otherwise on purpose.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * True when the system animator duration scale is zero - "Remove
 * animations" in Accessibility, or Animator duration scale set to "off" in
 * the developer options. Any other scale, including the 0.5x and 10x a
 * developer picks to look at a transition, still animates; only off is a
 * request for stillness. A single read: [rememberReduceMotion] is what keeps
 * it current while the app is up.
 */
fun Context.animationsOff(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * [animationsOff], held in state and kept current: it changes when the system's
 * animator scale does, while the app is on screen.
 *
 * It does not wait for a restart, because the app can be on screen while the
 * setting changes - in split-screen or a pop-up window beside Settings, from
 * the quick-settings developer tile, or from a debugger's `settings put` - and
 * Compose itself reacts at once: with the scale at 0 it skips every infinite
 * transition to its end, so a flag that still said "animation is on" would
 * leave the busy bar's sweep pinned past the edge of its track. Two ways to
 * hear of a change, because a content notification is not promised to every
 * process in every state: a [ContentObserver] on the setting, on the main
 * looper, and a re-read whenever the app resumes. The observer is removed
 * when the composition leaves.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var off by remember(context) { mutableStateOf(context.animationsOff()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                off = context.animationsOff()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        val resumed = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) off = context.animationsOff()
        }
        lifecycle.addObserver(resumed)
        // It may have changed between the first read and the registration.
        off = context.animationsOff()
        onDispose {
            lifecycle.removeObserver(resumed)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return off
}

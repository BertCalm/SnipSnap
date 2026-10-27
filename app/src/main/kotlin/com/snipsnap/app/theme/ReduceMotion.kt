package com.snipsnap.app.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the person has asked the phone for no animation. `App` reads it
 * once from [animationsOff] and provides it at the root, so a drawing that
 * would otherwise loop (the busy bar's sweep, the spinner's turn) can ask
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
 * request for stillness. The scale is read once and not observed: it is a
 * setting a person changes in the system's own screen, and the app is not
 * on screen while they do.
 */
fun Context.animationsOff(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

package com.snipsnap.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snipsnap.shell.OnsetRange
import com.snipsnap.shell.PadVelocity

/**
 * How a tap on a pad becomes a velocity, as SETUP sets it: the [mode] (POS,
 * FULL LVL, AREA), the [curve], and what AREA has learned about the player's
 * touch. One for the whole app, read at the moment of each hit and remembered
 * across launches, so every screen that plays pads agrees.
 *
 * The rules are `:shell`'s [PadVelocity]; this holds only the choice. [mode]
 * and [curve] are Compose state so the SETUP chips follow them; the pad
 * gestures read them off the main composition, at hit time.
 *
 * [init] is called once at start; before it (a preview, an instrumented
 * test) the pads behave as they always did: POS, LINEAR.
 */
object PadFeelStore {
    private const val KEY_MODE = "pad.velocity.mode"
    private const val KEY_CURVE = "pad.velocity.curve"

    var mode by mutableStateOf(PadVelocity.Mode.POS)
        private set
    var curve by mutableStateOf(PadVelocity.Curve.LINEAR)
        private set

    private val range = OnsetRange()
    private var prefs: SharedPreferences? = null

    /** Read the saved choice, once. Safe to call again. */
    fun init(context: Context) {
        if (prefs != null) return
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = saved
        mode = PadVelocity.parseMode(saved.getString(KEY_MODE, null))
        curve = PadVelocity.parseCurve(saved.getString(KEY_CURVE, null))
    }

    fun setMode(next: PadVelocity.Mode) {
        mode = next
        prefs?.edit()?.putString(KEY_MODE, next.name)?.apply()
    }

    fun setCurve(next: PadVelocity.Curve) {
        curve = next
        prefs?.edit()?.putString(KEY_CURVE, next.name)?.apply()
    }

    /**
     * The velocity for one touch: [position] is what `PadHit.velocityAt` made
     * of where it landed, [pressure] the panel's reading at first contact.
     * Every onset teaches AREA, whichever mode is on, so switching to it
     * finds it already knowing the player's hand.
     */
    fun shape(position: Float, pressure: Float): Float {
        range.observe(pressure)
        return PadVelocity.shape(mode, curve, range, position, pressure)
    }
}

package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Tidal
import com.snipsnap.synth.TidalPatch

/** Duration filing does not imply looping: only the explicit HOLD switch gates the pad. */
object TidalPad {
    fun configure(pad: KitPad, patch: TidalPatch): KitPad {
        val held = Tidal.isHeld(patch.macros["HOLD"] ?: 0f)
        return pad.copy(oneShot = !held, loop = held,
            attack = if (held) 0f else pad.attack, decay = if (held) 1f else pad.decay)
    }
}

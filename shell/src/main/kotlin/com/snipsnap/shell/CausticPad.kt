package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch

/** Only explicit held playback loops a pad; the duration-based LOOP class does not. */
object CausticPad {
    fun configure(pad: KitPad, patch: CausticPatch): KitPad {
        val held = CausticInstrument.isLoop(patch.voice, patch.macros, patch.model)
        return pad.copy(oneShot = !held, loop = held,
            attack = if (held) 0f else pad.attack,
            decay = if (held) 1f else pad.decay)
    }
}

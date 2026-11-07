package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Rime
import com.snipsnap.synth.RimePatch

/** Pad HOLD repeats the resolved contour; MAKE INSTRUMENT additionally retains its opening. */
object RimePad {
    fun configure(pad: KitPad, patch: RimePatch): KitPad {
        val held = Rime.isLoop(patch.macros)
        return pad.copy(oneShot = !held, loop = held,
            attack = if (held) 0f else pad.attack, decay = if (held) 1f else pad.decay)
    }
}

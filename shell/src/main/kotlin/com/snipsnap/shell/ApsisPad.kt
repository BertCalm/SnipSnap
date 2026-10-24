package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Apsis
import com.snipsnap.synth.ApsisPatch

/** The rendered PASSAGE is either a complete finite note or a settled, gated cycle. */
object ApsisPad {
    fun configure(pad: KitPad, patch: ApsisPatch): KitPad {
        val held = Apsis.isLoop(patch.macros["HOLD"] ?: 0f)
        val drumClass = Apsis.drumClassFor(patch.voice, patch.macros)
        return pad.copy(
            drumClass = drumClass,
            colorHex = if (pad.drumClass == drumClass) pad.colorHex else AutoPlace.colorFor(drumClass),
            oneShot = !held,
            loop = held,
            attack = if (held) 0f else pad.attack,
            decay = if (held) 1f else pad.decay,
        )
    }
}

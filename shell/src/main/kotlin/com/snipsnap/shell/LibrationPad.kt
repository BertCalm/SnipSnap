package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.LibrationKeys
import com.snipsnap.synth.LibrationPatch

/** An authored orbital note plays once or gates its complete recurring cycle. */
object LibrationPad {
    fun configure(pad: KitPad, patch: LibrationPatch, resetHeldTuning: Boolean = false): KitPad {
        val held = LibrationKeys.isHeld(patch.macros["HOLD"] ?: 0f)
        val drumClass = if (held) DrumClass.LOOP else DrumClass.TONAL
        return pad.copy(
            drumClass = drumClass,
            colorHex = if (pad.drumClass == drumClass) pad.colorHex else AutoPlace.colorFor(drumClass),
            loop = held,
            oneShot = !held,
            attack = if (held) 0f else pad.attack,
            decay = if (held) 1f else pad.decay,
            tuneCoarse = if (held && resetHeldTuning) 0 else pad.tuneCoarse,
            tuneFine = if (held && resetHeldTuning) 0 else pad.tuneFine,
        )
    }
}

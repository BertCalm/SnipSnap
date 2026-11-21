package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Consort
import com.snipsnap.synth.ConsortPatch

/**
 * A CONSORT pad's playback, set from its recipe ([ApsisPad]'s shape). Below HOLD's top step a pad
 * plays its finite note once through. At the top step ([Consort.isLoop]) the render is one seamless
 * loop, so the pad is gated and repeats it while held, with no attack or decay shaping of its own.
 */
object ConsortPad {
    fun configure(pad: KitPad, patch: ConsortPatch): KitPad {
        val held = Consort.isLoop(patch.macros["HOLD"] ?: 0f)
        val drumClass = Consort.drumClassFor(patch.voice, patch.macros)
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

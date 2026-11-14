package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/** Importable one-pad instrument, keeping all nine accepted dynamic/repeat sources.
 * Source metadata stores a versioned rerender recipe; it does not pretend to be a
 * registered PadRecipe or claim that a one-pad bank completes the genre-kit shelf.
 */
object SporeSnareBank {
    const val ID = "spore-approved-snare-v1"
    val velocities: List<Double> get() = listOf(.3,.65,1.0)
    fun arranged(c:SporeSnare.Controls=SporeSnare.Controls()):List<ArrangedPad> {
        val grid=velocities.map { v -> (0..2).map { t -> SporeSnare.render(c,v,t) } }
        return listOf(ArrangedPad(grid[2][0],DrumClass.SNARE,level=1f,oneShot=true,
            displayName="Spore Approved Snare",velocityTakes=grid,
            source=mapOf("sporeSource" to ID,"modelSha256" to SporeSnare.MODEL_SHA256,
                "sourceRecipe" to SporeSnare.Recipe(c).toJsonText(),"velocities" to ".3,.65,1.0","takes" to "3",
                "ownerSoundApproval" to "2026-11-14 Python study; Kotlin parity verified separately")))
    }
}

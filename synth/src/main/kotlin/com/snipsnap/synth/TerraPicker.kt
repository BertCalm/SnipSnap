package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.random.Random

/**
 * What the SYNTH picker needs from TERRA beyond [Terra] itself: the voices it offers, a RANDOMISE near the voice's
 * own settings, the class a recipe is heard as, and short chip labels. Kept here so the JVM tests hold it.
 */
object TerraPicker {
    /** Every voice but the retired membrane, which has no factory preset and no pad (docs/TERRA_HANDS.md). */
    val voices: List<TerraVoice> = TerraVoice.entries - TerraVoice.COMPOUND_MEMBRANE

    /** RANDOMISE: the voice's defaults, moved as every other modal-drum engine's are ([Dsp.scrambleNear]). */
    fun scramble(voice: TerraVoice, random: Random, temperature: Float = .35f): Map<String, Float> =
        Dsp.scrambleNear(Terra.defaults(voice), temperature, random)

    /** The class the [Classifier] hears in the render, as SEND files it. */
    fun drumClassFor(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        Classifier.classify(Terra.render(voice, macros)).drumClass

    /** A colour role without rendering: what each voice's factory presets mostly are. */
    fun roleClass(voice: TerraVoice): DrumClass = when (voice) {
        TerraVoice.RESONANT_CAVITY, TerraVoice.ODAIKO -> DrumClass.KICK
        TerraVoice.BOX -> DrumClass.SNARE
        TerraVoice.DJEMBE, TerraVoice.DHOLAK, TerraVoice.BAYAN, TerraVoice.COMPOUND_MEMBRANE, TerraVoice.NAGADO, TerraVoice.SHIME -> DrumClass.TOM
        TerraVoice.CONICAL_BELL, TerraVoice.TUNED_BAR, TerraVoice.SLAP, TerraVoice.DAYAN, TerraVoice.DARBUKA,
        TerraVoice.DOUM, TerraVoice.TUN, TerraVoice.KA, TerraVoice.KANE, TerraVoice.CHAPPA, TerraVoice.BINZASARA,
        TerraVoice.COWBELL, TerraVoice.WATER_DRUM -> DrumClass.PERC
    }

    /** The chip's one-line label: the instrument the voice plays, with no underscores. */
    fun chipLabel(voice: TerraVoice): String = when (voice) {
        TerraVoice.RESONANT_CAVITY -> "CAVITY"
        TerraVoice.CONICAL_BELL -> "AGOGO"
        TerraVoice.TUNED_BAR -> "BALAFON"
        TerraVoice.BOX -> "CAJON"
        TerraVoice.SLAP -> "SLAP"
        TerraVoice.DAYAN -> "DAYAN"
        TerraVoice.DARBUKA -> "DARBUKA"
        TerraVoice.DOUM -> "DOUM"
        TerraVoice.DJEMBE -> "DJEMBE"
        TerraVoice.DHOLAK -> "DHOLAK"
        TerraVoice.BAYAN -> "BAYAN"
        TerraVoice.TUN -> "TUN"
        TerraVoice.COMPOUND_MEMBRANE -> "MEMBRANE"
        TerraVoice.ODAIKO -> "ODAIKO"
        TerraVoice.NAGADO -> "NAGADO"
        TerraVoice.SHIME -> "SHIME"
        TerraVoice.KA -> "KA"
        TerraVoice.KANE -> "KANE"
        TerraVoice.CHAPPA -> "CHAPPA"
        TerraVoice.BINZASARA -> "BINZASARA"
        TerraVoice.COWBELL -> "COWBELL"
        TerraVoice.WATER_DRUM -> "WATER DRUM"
    }
}

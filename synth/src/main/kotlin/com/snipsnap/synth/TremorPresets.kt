package com.snipsnap.synth

/**
 * TREMOR's factory roster. Thirteen sounds, two or three a voice, covering a clean head, four
 * strikers, a light and a heavy bead bed, a passive cage, a powered bloom, a restrained fault, a
 * dense fault, a low note, and two held settings.
 *
 * Names are plain words. Nothing here has been listened to: the audition page is where a human
 * decides which of these is a drum and which is only a measurement that looks like one.
 *
 * Every preset names all eight knobs, so a saved file is the whole sound and not a default with
 * one knob moved. TUNE is semitones over 24 from C2, so 0, 0.5 and 1 are C2, C3 and C4.
 */
object TremorPresets {

    private fun p(voice: TremorVoice, name: String, vararg macros: Pair<String, Float>) =
        TremorPatch(name, voice, macros.toMap())

    fun forVoice(voice: TremorVoice): List<TremorPatch> = when (voice) {
        TremorVoice.HIDE -> hide
        TremorVoice.UNISON -> unison
        TremorVoice.ROLL -> roll
        TremorVoice.WIRE -> wire
        TremorVoice.CHARGE -> charge
        TremorVoice.FRACTURE -> fracture
    }

    fun all(): List<TremorPatch> = TremorVoice.entries.flatMap { forVoice(it) }

    // HIDE. C3, a softer C3, and the same head held.
    private val hide = listOf(
        p(TremorVoice.HIDE, "BROAD DRUM", "TUNE" to 12 / 24f, "STRIKE" to 0.35f, "ENSEMBLE" to 0.15f, "BEADS" to 0.20f, "CAGE" to 0.20f, "CURRENT" to 0.10f, "FAULT" to 0.05f, "HOLD" to 0f),
        p(TremorVoice.HIDE, "SOFT STRIKE", "TUNE" to 12 / 24f, "STRIKE" to 0.12f, "ENSEMBLE" to 0.08f, "BEADS" to 0.15f, "CAGE" to 0.12f, "CURRENT" to 0.05f, "FAULT" to 0f, "HOLD" to 0f),
        p(TremorVoice.HIDE, "HELD DRUM", "TUNE" to 12 / 24f, "STRIKE" to 0.40f, "ENSEMBLE" to 0.30f, "BEADS" to 0.28f, "CAGE" to 0.30f, "CURRENT" to 0.18f, "FAULT" to 0.08f, "HOLD" to 1f),
    )

    // UNISON. Four hands at C3, and the same idea down at C2.
    private val unison = listOf(
        p(TremorVoice.UNISON, "FOUR HANDS", "TUNE" to 12 / 24f, "STRIKE" to 0.55f, "ENSEMBLE" to 0.90f, "BEADS" to 0.35f, "CAGE" to 0.30f, "CURRENT" to 0.20f, "FAULT" to 0.10f, "HOLD" to 0f),
        p(TremorVoice.UNISON, "DEEP ASSEMBLY", "TUNE" to 0f, "STRIKE" to 0.48f, "ENSEMBLE" to 0.70f, "BEADS" to 0.40f, "CAGE" to 0.36f, "CURRENT" to 0.22f, "FAULT" to 0.12f, "HOLD" to 0f),
    )

    // ROLL. A light bed and a dense one.
    private val roll = listOf(
        p(TremorVoice.ROLL, "LIGHT BED", "TUNE" to 12 / 24f, "STRIKE" to 0.32f, "ENSEMBLE" to 0.28f, "BEADS" to 0.18f, "CAGE" to 0.16f, "CURRENT" to 0.10f, "FAULT" to 0.04f, "HOLD" to 0f),
        p(TremorVoice.ROLL, "SETTLING BED", "TUNE" to 14 / 24f, "STRIKE" to 0.46f, "ENSEMBLE" to 0.55f, "BEADS" to 0.88f, "CAGE" to 0.28f, "CURRENT" to 0.16f, "FAULT" to 0.10f, "HOLD" to 0f),
    )

    // WIRE. Passive cage, then a cage with current.
    private val wire = listOf(
        p(TremorVoice.WIRE, "DRY CAGE", "TUNE" to 12 / 24f, "STRIKE" to 0.40f, "ENSEMBLE" to 0.30f, "BEADS" to 0.22f, "CAGE" to 0.72f, "CURRENT" to 0f, "FAULT" to 0.16f, "HOLD" to 0f),
        p(TremorVoice.WIRE, "LATE STRAND", "TUNE" to 17 / 24f, "STRIKE" to 0.42f, "ENSEMBLE" to 0.36f, "BEADS" to 0.34f, "CAGE" to 0.86f, "CURRENT" to 0.40f, "FAULT" to 0.12f, "HOLD" to 0f),
    )

    // CHARGE. The bloom, and the same bloom held.
    private val charge = listOf(
        p(TremorVoice.CHARGE, "CHARGED TAIL", "TUNE" to 12 / 24f, "STRIKE" to 0.58f, "ENSEMBLE" to 0.62f, "BEADS" to 0.42f, "CAGE" to 0.68f, "CURRENT" to 0.78f, "FAULT" to 0.18f, "HOLD" to 0f),
        p(TremorVoice.CHARGE, "HELD BLOOM", "TUNE" to 9 / 24f, "STRIKE" to 0.52f, "ENSEMBLE" to 0.58f, "BEADS" to 0.40f, "CAGE" to 0.70f, "CURRENT" to 0.82f, "FAULT" to 0.22f, "HOLD" to 1f),
    )

    // FRACTURE. A restrained interruption and a dense one.
    private val fracture = listOf(
        p(TremorVoice.FRACTURE, "SOFT FAULT", "TUNE" to 12 / 24f, "STRIKE" to 0.50f, "ENSEMBLE" to 0.45f, "BEADS" to 0.36f, "CAGE" to 0.55f, "CURRENT" to 0.48f, "FAULT" to 0.28f, "HOLD" to 0f),
        p(TremorVoice.FRACTURE, "BROKEN RETURN", "TUNE" to 7 / 24f, "STRIKE" to 0.70f, "ENSEMBLE" to 0.78f, "BEADS" to 0.66f, "CAGE" to 0.74f, "CURRENT" to 0.84f, "FAULT" to 0.82f, "HOLD" to 0f),
    )
}

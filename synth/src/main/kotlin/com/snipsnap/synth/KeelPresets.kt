package com.snipsnap.synth

/**
 * KEEL's factory roster: six per voice, authored after four listens (spec section 20) and **provisional** as a set:
 * the owner heard WEIGHT as the body, the settle as one gesture, the bowed sustain's level and the locked bow, but
 * none of these recipes. The kit on the phone ([SynthKits.keel]) is where they are heard; a name that does not
 * survive it is renamed or dropped.
 *
 * Every preset names all seven knobs, so the roster is the full knob and not a default in disguise. Every root is
 * C2 (MIDI 36), and TUNE is `(12 + k) / 24` for k semitones from C1, so each note sits in C minor pentatonic (the
 * kit's key); the comment names the note. The loops (HOLD 1) sit at C2, where the locked bow was heard. SLIDE stays
 * at or under .5: a slide from above was never heard (spec risk 5).
 *
 * Names are plain words: no maker, no model, no engine's, voice's or rack section's name.
 */
object KeelPresets {

    private fun p(voice: KeelVoice, name: String, vararg macros: Pair<String, Float>) =
        KeelPatch(name, voice, macros.toMap())

    private fun tune(semitonesFromC1: Int) = semitonesFromC1 / Keel.TUNE_SEMITONES.toFloat()

    fun forVoice(voice: KeelVoice): List<KeelPatch> = when (voice) {
        KeelVoice.REDUCE -> reducePresets
        KeelVoice.UPRIGHT -> uprightPresets
        KeelVoice.BELLY -> bellyPresets
    }

    fun all(): List<KeelPatch> = KeelVoice.entries.flatMap { forVoice(it) }

    private val reducePresets = listOf(
        p(KeelVoice.REDUCE, "ROUND FLOOR", "TUNE" to tune(12), "SETTLE" to .5f, "WEIGHT" to .45f, "GRIT" to .15f, "SLIDE" to .5f, "LENGTH" to .5f, "HOLD" to .1f), // C2
        p(KeelVoice.REDUCE, "QUICK SINK", "TUNE" to tune(7), "SETTLE" to .15f, "WEIGHT" to .55f, "GRIT" to .2f, "SLIDE" to .5f, "LENGTH" to .4f, "HOLD" to 0f), // G1
        p(KeelVoice.REDUCE, "LOW HULL", "TUNE" to tune(0), "SETTLE" to .3f, "WEIGHT" to .75f, "GRIT" to .1f, "SLIDE" to .5f, "LENGTH" to 1f, "HOLD" to .8f), // C1
        p(KeelVoice.REDUCE, "GROWL LINE", "TUNE" to tune(5), "SETTLE" to .7f, "WEIGHT" to .5f, "GRIT" to .65f, "SLIDE" to .5f, "LENGTH" to .55f, "HOLD" to 0f), // F1
        p(KeelVoice.REDUCE, "SCOOP UP", "TUNE" to tune(15), "SETTLE" to .4f, "WEIGHT" to .4f, "GRIT" to .2f, "SLIDE" to .38f, "LENGTH" to .5f, "HOLD" to 0f), // D#2
        p(KeelVoice.REDUCE, "SETTLED HUM", "TUNE" to tune(12), "SETTLE" to .5f, "WEIGHT" to .5f, "GRIT" to .15f, "SLIDE" to .5f, "LENGTH" to .5f, "HOLD" to 1f), // C2
    )

    private val uprightPresets = listOf(
        p(KeelVoice.UPRIGHT, "WARM GUT", "TUNE" to tune(12), "SETTLE" to .4f, "WEIGHT" to .4f, "GRIT" to .1f, "SLIDE" to .5f, "LENGTH" to .55f, "HOLD" to 0f), // C2
        p(KeelVoice.UPRIGHT, "MUTED THUMB", "TUNE" to tune(10), "SETTLE" to .25f, "WEIGHT" to .45f, "GRIT" to .05f, "SLIDE" to .5f, "LENGTH" to .2f, "HOLD" to 0f), // A#1
        p(KeelVoice.UPRIGHT, "LONG NECK", "TUNE" to tune(3), "SETTLE" to .6f, "WEIGHT" to .4f, "GRIT" to .1f, "SLIDE" to .5f, "LENGTH" to .85f, "HOLD" to .2f), // D#1
        p(KeelVoice.UPRIGHT, "BRIGHT PICK", "TUNE" to tune(19), "SETTLE" to .8f, "WEIGHT" to .25f, "GRIT" to .2f, "SLIDE" to .5f, "LENGTH" to .45f, "HOLD" to 0f), // G2
        p(KeelVoice.UPRIGHT, "HOT WOOD", "TUNE" to tune(12), "SETTLE" to .5f, "WEIGHT" to .45f, "GRIT" to .55f, "SLIDE" to .5f, "LENGTH" to .5f, "HOLD" to 0f), // C2
        p(KeelVoice.UPRIGHT, "BOWED NOTE", "TUNE" to tune(12), "SETTLE" to .45f, "WEIGHT" to .35f, "GRIT" to .15f, "SLIDE" to .5f, "LENGTH" to .55f, "HOLD" to 1f), // C2
    )

    private val bellyPresets = listOf(
        p(KeelVoice.BELLY, "SOFT PILLOW", "TUNE" to tune(12), "SETTLE" to .5f, "WEIGHT" to .45f, "GRIT" to .3f, "SLIDE" to .5f, "LENGTH" to .5f, "HOLD" to .1f), // C2
        p(KeelVoice.BELLY, "DEEP CUSHION", "TUNE" to tune(0), "SETTLE" to .35f, "WEIGHT" to .6f, "GRIT" to .2f, "SLIDE" to .5f, "LENGTH" to 1f, "HOLD" to .8f), // C1
        p(KeelVoice.BELLY, "TOOTH MARK", "TUNE" to tune(7), "SETTLE" to .75f, "WEIGHT" to .35f, "GRIT" to .55f, "SLIDE" to .5f, "LENGTH" to .5f, "HOLD" to 0f), // G1
        p(KeelVoice.BELLY, "SLOW MELT", "TUNE" to tune(15), "SETTLE" to 1f, "WEIGHT" to .4f, "GRIT" to .3f, "SLIDE" to .5f, "LENGTH" to .7f, "HOLD" to .2f), // D#2
        p(KeelVoice.BELLY, "CLIMB IN", "TUNE" to tune(10), "SETTLE" to .4f, "WEIGHT" to .5f, "GRIT" to .25f, "SLIDE" to .38f, "LENGTH" to .5f, "HOLD" to 0f), // A#1
        p(KeelVoice.BELLY, "WARM HUSH", "TUNE" to tune(12), "SETTLE" to .5f, "WEIGHT" to .5f, "GRIT" to .3f, "SLIDE" to .5f, "LENGTH" to .5f, "HOLD" to 1f), // C2
    )
}

package com.snipsnap.synth

/**
 * The string machine's landing: the rack chain a STRING MACHINE preset takes
 * to its pad, so the sound the owner auditioned is the sound the pad plays.
 *
 * A string machine is not a waveform. Its saws are plain, and the sound is
 * the ensemble that turns them into a section - which is why the preset is
 * only half of the sound and the recipe's ENSEMBLE is the other half
 * ([Ensemble]). The first landings carried the chorus (three taps on the same
 * two sines), which reads as one instrument swirled; these carry its SECTION,
 * six independent players ([EnsemblePlayers]), at the depth the owner heard
 * them at. The presets (`VelvetPresets` STRING MACHINE and THIN
 * STRINGS, `ResinPresets` WIDE STRINGS and DARK STRINGS) each name their chain
 * here, the way [Siren.landingChain] names ECHO for a one-shot siren; the
 * difference is the key. A siren's chain follows one macro, because HOLD
 * decides whether the sound is a loop; no macro says "string machine", so
 * these follow the *sound*: the chain belongs to the factory preset's exact
 * macro values, found by [landing]. `Presets.landingFor` is the one door to
 * the tables, and it is asked with the macros the pad is being made from. A
 * slider moved off them makes the sound the player's own, and it lands dry;
 * a player's saved copy of the unmoved factory sound lands with the chain
 * (same sound, same landing); and a player's *different* sound saved under a
 * factory name - which UserPresets allowed before the name was a factory
 * one - is a second chip with the same label, so a key on the label would
 * hand it the ENSEMBLE, and a key on the macros does not.
 *
 * The chain is ENSEMBLE alone. The design note paired two of the presets with
 * an EQ (a bass cut for THIN STRINGS, a low shelf for DARK STRINGS); the
 * pairing was measured and dropped, because the rack's EQ is three fixed
 * bands (a 100 Hz shelf, a 900 Hz bell, an 8 kHz shelf) and these voices live
 * between them: with the bass cut at 0.3 THIN STRINGS' 40-200 Hz band moved
 * -0.9 dB against the same chain without it, and with the low shelf raised to
 * 0.6 (and an air cut at 0.3 besides) DARK STRINGS' 40-200 Hz band moved
 * +0.9 dB and its top band -0.6 dB, all of it small enough that a listener
 * could not be expected to name it. An EQ that changes nothing you can hear
 * is a chip on the pad sheet that lies about the sound, so the presets differ
 * where the measurement says they differ: their source macros, and how deep
 * and how fast the players swing.
 *
 * The mono voice comes out as the stereo pair (WIDTH is 1), and every later
 * stage of the pad's life - `Cleanup.toMono` on the way to a mono consumer, the
 * preview, the audition - folds that pair with the measured cost the tests
 * hold each landing to.
 */
internal object StringMachine {

    /**
     * The chain [table] holds for the factory preset among [presets] whose every
     * macro equals the one in [macros] (extra keys in [macros] are ignored, and
     * the compare is exact - a landing is for the sound as it was authored), or
     * null when the sound is not one of them.
     */
    fun landing(presets: List<Patch>, table: Map<String, FxChain>, macros: Map<String, Float>): FxChain? =
        presets.firstOrNull { it.name in table && it.macros.all { (k, v) -> macros[k] == v } }?.let { table.getValue(it.name) }

    /** ENSEMBLE at [depth] and [rate] (0..1, 0.5 the classic centre of both), the full pair, its SECTION at [section]: the six players by default. */
    fun chain(depth: Float = 0.5f, rate: Float = 0.5f, section: Float = 1f): FxChain =
        FxChain().withSection("ensemble", mapOf("DEPTH" to depth, "RATE" to rate, "WIDTH" to 1f, "SECTION" to section))

    /** STRING MACHINE and WIDE STRINGS: [Ensemble]'s own DEPTH and RATE, with its six players. */
    val CLASSIC: FxChain = chain()

    /** THIN STRINGS: a shallower swing, a little quicker, so the section reads as shimmer and not as lushness. */
    val THIN: FxChain = chain(depth = 0.35f, rate = 0.6f)

    /** DARK STRINGS: slower and deeper, so the swell has room to be felt under a closed tone. */
    val DARK: FxChain = chain(depth = 0.6f, rate = 0.4f)
}

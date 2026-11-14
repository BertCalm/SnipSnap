package com.snipsnap.synth

/**
 * CAIRN's factory roster: four presets for each of the six voices, 24 in all.
 *
 * - **Kept SING recipes.** SING's four are the owner's Wave 3 KEEP recipes (2026-11-09), read
 *   from [CairnAcceptedSingSources] with only the name changed. Their model, root, velocity
 *   and contact cue stay exactly as saved, so they replay the accepted PCM24 anchors:
 *   - SING EARLIER is `before-60`, model 4.
 *   - SING MOVING is `after-60`, model 5.
 *   - SING SMOOTH is `groove-0`, GROOVE 0%.
 *   - SING GROOVED is `groove-100`, GROOVE 100%. Renamed because "SING GROOVE 100" is longer
 *     than the 14 letters a YOUR PRESET name may hold.
 * - **New settings.** The other twenty are new settings of the existing controls, at C4 and
 *   full touch. Each pins its saved model so a later model bump cannot move it:
 *   - KNOCK, ANSWER and TUMBLE: model 5, which renders byte-identically at 6 and 7.
 *   - GRIND: model 6.
 *   - VESSEL: model 7.
 * - **GRIND was kept at G9's scrape level** (round cairn-voices-2). Its four presets use that level.
 *
 * Promoted YOUR PRESET recipes are complete JSON constructors (see `UserPresets.rosterLine`),
 * because CAIRN keeps its root, touch, cue and model outside the macro map.
 */
object CairnPresets {
    private const val OLD_VOICE_MODEL = 5
    private const val GRIND_MODEL = 6
    private const val VESSEL_MODEL = 7

    private fun p(voice: CairnVoice, name: String, model: Int, vararg macros: Pair<String, Float>) =
        CairnPatch(name, voice, Cairn.defaults(voice) + macros, model = model)

    private fun kept(id: String, name: String): CairnPatch =
        checkNotNull(CairnAcceptedSingSources.byId(id)) { "missing accepted CAIRN SING source $id" }
            .patch().copy(name = name)

    /** Paste complete UserPresets roster lines here; the accepted SING recipes stay fixed. */
    private val promoted: List<CairnPatch> = listOf()

    private val factory: List<CairnPatch> by lazy {
        listOf(
            p(CairnVoice.KNOCK, "KNOCK", OLD_VOICE_MODEL),
            p(CairnVoice.KNOCK, "KNOCK HEAVY", OLD_VOICE_MODEL, "WEIGHT" to .70f),
            p(CairnVoice.KNOCK, "KNOCK DRY", OLD_VOICE_MODEL, "BODY" to .10f),
            p(CairnVoice.KNOCK, "KNOCK RING", OLD_VOICE_MODEL, "HOLD" to .80f),
            p(CairnVoice.ANSWER, "ANSWER", OLD_VOICE_MODEL),
            p(CairnVoice.ANSWER, "ANSWER LIGHT", OLD_VOICE_MODEL, "WEIGHT" to .20f),
            p(CairnVoice.ANSWER, "ANSWER WIDE", OLD_VOICE_MODEL, "BODY" to .95f),
            p(CairnVoice.ANSWER, "ANSWER LOOP", OLD_VOICE_MODEL, "HOLD" to 1f),
            p(CairnVoice.TUMBLE, "TUMBLE", OLD_VOICE_MODEL),
            p(CairnVoice.TUMBLE, "TUMBLE LONG", OLD_VOICE_MODEL, "SLOPE" to .80f),
            p(CairnVoice.TUMBLE, "TUMBLE PEBBLE", OLD_VOICE_MODEL, "WEIGHT" to .15f),
            p(CairnVoice.TUMBLE, "TUMBLE STEEP", OLD_VOICE_MODEL, "GROOVE" to .90f),
            kept("cairn-before-60", "SING EARLIER"),
            kept("cairn-after-60", "SING MOVING"),
            kept("cairn-groove-0", "SING SMOOTH"),
            kept("cairn-groove-100", "SING GROOVED"),
            p(CairnVoice.GRIND, "GRIND", GRIND_MODEL),
            p(CairnVoice.GRIND, "GRIND SLOW", GRIND_MODEL, "SLOPE" to .20f),
            p(CairnVoice.GRIND, "GRIND COARSE", GRIND_MODEL, "GROOVE" to .85f),
            p(CairnVoice.GRIND, "GRIND LOOP", GRIND_MODEL, "HOLD" to 1f),
            p(CairnVoice.VESSEL, "VESSEL", VESSEL_MODEL),
            p(CairnVoice.VESSEL, "VESSEL DEEP", VESSEL_MODEL, "BODY" to .90f),
            p(CairnVoice.VESSEL, "VESSEL SMALL", VESSEL_MODEL, "WEIGHT" to .15f),
            p(CairnVoice.VESSEL, "VESSEL LOOP", VESSEL_MODEL, "HOLD" to 1f),
        )
    }

    fun forVoice(voice: CairnVoice): List<CairnPatch> = all().filter { it.voice == voice }
    fun all(): List<CairnPatch> = factory + promoted
}

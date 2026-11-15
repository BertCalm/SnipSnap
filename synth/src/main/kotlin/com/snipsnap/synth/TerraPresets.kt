package com.snipsnap.synth

/**
 * TERRA's factory bank: four presets on each of its twelve playing voices (COMPOUND_MEMBRANE is retired and has none).
 *
 * The sixteen pads of [TerraKits.classic], which the owner kept in full (round terra-kit-1, 2026-11-14), are filed
 * here under their own names and macros, so each is the kept pad byte for byte. The other thirty-two are new settings
 * of the same voices, written out in full so no preset moves with a voice's defaults. The owner kept the forty presets
 * of the first ten voices in round terra-presets-1 (W1–W10, 2026-11-15; pinned in `TerraPresetsTest`); BAYAN's and
 * TUN's new three were reworked after that round and wait on terra-presets-2. See docs/TERRA_PROMOTION.md.
 *
 * Names describe the drum and the stroke; none names a pitch it does not have. Every name fits a pad label (14).
 */
object TerraPresets {
    /** Paste complete UserPresets roster lines here; the kept and the factory settings stay fixed. */
    private val promoted: List<TerraPatch> = listOf()

    fun forVoice(voice: TerraVoice): List<TerraPatch> = factory(voice) + promoted.filter { it.voice == voice }

    fun all(): List<TerraPatch> = TerraVoice.entries.flatMap(::forVoice)

    /** The presets that are pads of the kept classic kit, in the kit's pad order. */
    fun kept(): List<TerraPatch> = KEPT

    private fun p(name: String, voice: TerraVoice, vararg macros: Pair<String, Float>) = TerraPatch(name, voice, macros.toMap())

    // The kept pads, exactly as TerraKits.classic() writes them (the same partial macro maps, so equal recipes).
    private val UDU_WHOOMP = p("Udu Whoomp", TerraVoice.RESONANT_CAVITY, "TUNE" to 0.1058f, "FORCE" to 0.10f, "DROOP" to 0.0769f, "CAVITY" to 0.90f)
    private val BAYAN_DRAG = p("Bayan Drag", TerraVoice.BAYAN, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "BEND" to 1f)
    private val DJEMBE_BASS = p("Djembe Bass", TerraVoice.DJEMBE, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "STROKE" to 0f, "THUMP" to 0.5f)
    private val CAJON_LOW = p("Cajon Low", TerraVoice.RESONANT_CAVITY, "TUNE" to 0.1516f, "FORCE" to 0.20f, "CAVITY" to 0.70f, "BUZZ" to 0.15f)
    private val DHOLAK_BASS = p("Dholak Bass", TerraVoice.DHOLAK, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "DROP" to 0.5f, "TREBLE" to 0.5f)
    private val DUMBEK_DOUM = p("Dumbek Doum", TerraVoice.DOUM, "TUNE" to 0.483f, "DECAY" to 0.5f, "FORCE" to 0.4f, "AIR" to 0.5f, "SHELL" to 0.5f)
    private val CAJON_SLAP = p("Cajon Slap", TerraVoice.BOX, "TUNE" to 0.4f, "DECAY" to 0.5f, "FORCE" to 0.8f, "POS" to 0.875f, "CAVITY" to 0.3f, "BUZZ" to 0.5f)
    private val DJEMBE_OPEN = p("Djembe Open", TerraVoice.DJEMBE, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "STROKE" to 1f, "THUMP" to 0.5f)
    private val TABLA_TIN = p("Tabla Tin", TerraVoice.DAYAN, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.9f, "OPEN" to 1f, "CLICK" to 0.65f)
    private val TABLA_TUN = p("Tabla Tun", TerraVoice.TUN, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "KNOCK" to 0.5f, "LAG" to 0.5f)
    private val DUMBEK_TEK = p("Dumbek Tek", TerraVoice.DARBUKA, "TUNE" to 0.483f, "DECAY" to 0.5f, "FORCE" to 0.92f, "POS" to 0.875f, "SHELL" to 0.5f, "SNAP" to 0.5f)
    private val DJEMBE_SLAP = p("Djembe Slap", TerraVoice.SLAP, "TUNE" to 0.53f, "DECAY" to 0.5f, "FORCE" to 0.95f, "POS" to 0.875f, "BOWL" to 0.5f, "SMACK" to 0.5f)
    private val AGOGO_LOW = p("Agogo Low", TerraVoice.CONICAL_BELL, "TUNE" to 0.2508f, "FORCE" to 0.85f)
    private val AGOGO_HIGH = p("Agogo High", TerraVoice.CONICAL_BELL, "TUNE" to 0.8807f, "FORCE" to 0.90f)
    private val AGOGO_CLACK = p("Agogo Clack", TerraVoice.CONICAL_BELL, "TUNE" to 0.6108f, "FORCE" to 0.95f, "CLACK" to 0.6667f)
    private val BALAFON_KEY = p("Balafon Key", TerraVoice.TUNED_BAR, "TUNE" to 0.7573f, "FORCE" to 0.70f, "BUZZ" to 0.60f)

    private val KEPT = listOf(UDU_WHOOMP, BAYAN_DRAG, DJEMBE_BASS, CAJON_LOW, DHOLAK_BASS, DUMBEK_DOUM, CAJON_SLAP, DJEMBE_OPEN,
        TABLA_TIN, TABLA_TUN, DUMBEK_TEK, DJEMBE_SLAP, AGOGO_LOW, AGOGO_HIGH, AGOGO_CLACK, BALAFON_KEY)

    private fun factory(voice: TerraVoice): List<TerraPatch> = when (voice) {
        TerraVoice.COMPOUND_MEMBRANE -> emptyList()
        TerraVoice.RESONANT_CAVITY -> listOf(UDU_WHOOMP, CAJON_LOW,
            // The whoomp left to ring: the same deep air push with a longer tail.
            p("Udu Long", TerraVoice.RESONANT_CAVITY, "TUNE" to 0.1058f, "DECAY" to 0.8f, "FORCE" to 0.10f, "POS" to 0.25f, "DROOP" to 0.0769f, "CAVITY" to 0.90f, "BUZZ" to 0f),
            // A harder hand off the centre: more of the clay, less of the air.
            p("Udu Tok", TerraVoice.RESONANT_CAVITY, "TUNE" to 0.2f, "DECAY" to 0.35f, "FORCE" to 0.6f, "POS" to 0.6f, "DROOP" to 0.05f, "CAVITY" to 0.5f, "BUZZ" to 0f),
        )
        TerraVoice.CONICAL_BELL -> listOf(AGOGO_LOW, AGOGO_HIGH, AGOGO_CLACK,
            // The low bell choked by the hand straight after the strike.
            p("Agogo Muted", TerraVoice.CONICAL_BELL, "TUNE" to 0.2508f, "DECAY" to 0.15f, "FORCE" to 0.6f, "POS" to 0.25f, "CLACK" to 0f),
        )
        TerraVoice.TUNED_BAR -> listOf(BALAFON_KEY,
            // A lower key on the same frame, its gourd buzzing.
            p("Balafon Low", TerraVoice.TUNED_BAR, "TUNE" to 0.45f, "DECAY" to 0.5f, "FORCE" to 0.70f, "POS" to 0.25f, "BUZZ" to 0.60f),
            // The key with its gourd's membrane taped over: wood only.
            p("Balafon Dry", TerraVoice.TUNED_BAR, "TUNE" to 0.7573f, "DECAY" to 0.5f, "FORCE" to 0.70f, "POS" to 0.25f, "BUZZ" to 0f),
            // A softer mallet nearer the key's middle.
            p("Balafon Soft", TerraVoice.TUNED_BAR, "TUNE" to 0.7573f, "DECAY" to 0.5f, "FORCE" to 0.35f, "POS" to 0.5f, "BUZZ" to 0.4f),
        )
        TerraVoice.BOX -> listOf(CAJON_SLAP,
            // The palm in the plate's centre: the port's air and the low plate modes, the wires quieter.
            p("Cajon Bass", TerraVoice.BOX, "TUNE" to 0.4f, "DECAY" to 0.5f, "FORCE" to 0.5f, "POS" to 0.1f, "CAVITY" to 0.7f, "BUZZ" to 0.25f),
            // A light fingertip near the edge, between the beats.
            p("Cajon Ghost", TerraVoice.BOX, "TUNE" to 0.4f, "DECAY" to 0.5f, "FORCE" to 0.35f, "POS" to 0.875f, "CAVITY" to 0.3f, "BUZZ" to 0.5f),
            // The slap with the wires off.
            p("Cajon Dry", TerraVoice.BOX, "TUNE" to 0.4f, "DECAY" to 0.5f, "FORCE" to 0.8f, "POS" to 0.875f, "CAVITY" to 0.3f, "BUZZ" to 0f),
        )
        TerraVoice.SLAP -> listOf(DJEMBE_SLAP,
            // A shorter head and more of the hand's crack.
            p("Slap Tight", TerraVoice.SLAP, "TUNE" to 0.53f, "DECAY" to 0.3f, "FORCE" to 0.95f, "POS" to 0.875f, "BOWL" to 0.5f, "SMACK" to 0.7f),
            // A looser head over more of the bowl's air.
            p("Slap Deep", TerraVoice.SLAP, "TUNE" to 0.45f, "DECAY" to 0.5f, "FORCE" to 0.95f, "POS" to 0.875f, "BOWL" to 0.8f, "SMACK" to 0.5f),
            // The same slap played lighter.
            p("Slap Soft", TerraVoice.SLAP, "TUNE" to 0.53f, "DECAY" to 0.5f, "FORCE" to 0.6f, "POS" to 0.875f, "BOWL" to 0.5f, "SMACK" to 0.3f),
        )
        TerraVoice.DAYAN -> listOf(TABLA_TIN,
            // The ring finger mutes the fundamental: na.
            p("Tabla Na", TerraVoice.DAYAN, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.9f, "OPEN" to 0f, "CLICK" to 0.65f),
            // The open stroke left longer, with less snap.
            p("Tabla Ring", TerraVoice.DAYAN, "TUNE" to 0.5f, "DECAY" to 0.8f, "FORCE" to 0.9f, "OPEN" to 1f, "CLICK" to 0.4f),
            // A lighter finger.
            p("Tabla Soft", TerraVoice.DAYAN, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.55f, "OPEN" to 1f, "CLICK" to 0.4f),
        )
        TerraVoice.DARBUKA -> listOf(DUMBEK_TEK,
            // The other hand's rim stroke: lighter, less snap.
            p("Dumbek Ka", TerraVoice.DARBUKA, "TUNE" to 0.483f, "DECAY" to 0.5f, "FORCE" to 0.6f, "POS" to 0.8f, "SHELL" to 0.5f, "SNAP" to 0.35f),
            // The fingertip's snap pushed forward.
            p("Dumbek Snap", TerraVoice.DARBUKA, "TUNE" to 0.483f, "DECAY" to 0.5f, "FORCE" to 0.92f, "POS" to 0.875f, "SHELL" to 0.5f, "SNAP" to 0.85f),
            // The ceramic left to ring.
            p("Dumbek Ring", TerraVoice.DARBUKA, "TUNE" to 0.483f, "DECAY" to 0.7f, "FORCE" to 0.92f, "POS" to 0.875f, "SHELL" to 0.85f, "SNAP" to 0.5f),
        )
        TerraVoice.DOUM -> listOf(DUMBEK_DOUM,
            // A looser head over more of the goblet's air.
            p("Doum Deep", TerraVoice.DOUM, "TUNE" to 0.4f, "DECAY" to 0.5f, "FORCE" to 0.4f, "AIR" to 0.8f, "SHELL" to 0.5f),
            // The palm stays on the head: a short doum.
            p("Doum Short", TerraVoice.DOUM, "TUNE" to 0.483f, "DECAY" to 0.25f, "FORCE" to 0.4f, "AIR" to 0.5f, "SHELL" to 0.5f),
            // Struck harder.
            p("Doum Hard", TerraVoice.DOUM, "TUNE" to 0.483f, "DECAY" to 0.5f, "FORCE" to 0.7f, "AIR" to 0.5f, "SHELL" to 0.5f),
        )
        TerraVoice.DJEMBE -> listOf(DJEMBE_BASS, DJEMBE_OPEN,
            // The bass two semitones lower, with more of the palm's thud.
            p("Djembe Bass Lo", TerraVoice.DJEMBE, "TUNE" to 0.3f, "DECAY" to 0.5f, "FORCE" to 0.5f, "STROKE" to 0f, "THUMP" to 0.7f),
            // The open tone two semitones higher.
            p("Djembe Open Hi", TerraVoice.DJEMBE, "TUNE" to 0.7f, "DECAY" to 0.5f, "FORCE" to 0.5f, "STROKE" to 1f, "THUMP" to 0.5f),
        )
        TerraVoice.DHOLAK -> listOf(DHOLAK_BASS,
            // The bass without the fall: the hand stays off the head.
            p("Dholak Flat", TerraVoice.DHOLAK, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "DROP" to 0f, "TREBLE" to 0.5f),
            // The deepest fall.
            p("Dholak Swoop", TerraVoice.DHOLAK, "TUNE" to 0.5f, "DECAY" to 0.5f, "FORCE" to 0.5f, "DROP" to 1f, "TREBLE" to 0.5f),
            // Shorter, with the treble head quiet.
            p("Dholak Dry", TerraVoice.DHOLAK, "TUNE" to 0.5f, "DECAY" to 0.35f, "FORCE" to 0.5f, "DROP" to 0.5f, "TREBLE" to 0f),
        )
        // BAYAN and TUN were reworked after round terra-presets-1 ("All similar", "Too similar"): each new preset
        // now moves the voice to an end of its range on two or more macros, so the four differ in pitch, length,
        // contact and fall, not by nudges.
        TerraVoice.BAYAN -> listOf(BAYAN_DRAG,
            // Five semitones down, the ring twice as long, the ghe held on its note.
            p("Bayan Deep", TerraVoice.BAYAN, "TUNE" to 0f, "DECAY" to 1f, "FORCE" to 0.5f, "BEND" to 0f),
            // A hard, short-contact stroke that stops at once: the ring at half length, no fall.
            p("Bayan Slap", TerraVoice.BAYAN, "TUNE" to 0.5f, "DECAY" to 0f, "FORCE" to 1f, "BEND" to 0f),
            // Five semitones up, a soft wide palm, a short ring that falls.
            p("Bayan Fall Hi", TerraVoice.BAYAN, "TUNE" to 1f, "DECAY" to 0.2f, "FORCE" to 0f, "BEND" to 1f),
        )
        TerraVoice.TUN -> listOf(TABLA_TUN,
            // Five semitones down and twice as long, with a light knock.
            p("Tun Deep", TerraVoice.TUN, "TUNE" to 0f, "DECAY" to 1f, "FORCE" to 0.5f, "KNOCK" to 0.2f, "LAG" to 0.5f),
            // Mostly the fingertip's knock: the note at half length, the knock doubled, struck hard.
            p("Tun Muted", TerraVoice.TUN, "TUNE" to 0.5f, "DECAY" to 0f, "FORCE" to 1f, "KNOCK" to 1f, "LAG" to 0f),
            // Five semitones up, a soft finger and no knock at all: the plain ringing note.
            p("Tun Pure", TerraVoice.TUN, "TUNE" to 1f, "DECAY" to 0.5f, "FORCE" to 0f, "KNOCK" to 0f, "LAG" to 0.5f),
        )
    }
}

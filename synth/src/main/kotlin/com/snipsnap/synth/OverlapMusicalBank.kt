package com.snipsnap.synth

/** Musical roles for the OVERLAP factory bank; these are finite rendered sounds. */
enum class OverlapPresetFamily { PLUCK, TEXTURE, BREATH }

data class OverlapBankEntry(
    val id: String,
    val name: String,
    val description: String,
    val family: OverlapPresetFamily,
    val patch: OverlapPatch,
)

/**
 * The twelve approved factory recipes for the unchanged model-two instrument.
 * Earlier control-comparison recipes remain separate in [OverlapPresets].
 *
 * MIDI names the geometry reference. Full-depth families retain their documented
 * relationships; intermediate KNOT textures can have a different strongest pitch.
 * HOLD supplies finite excitation, with no seamless-loop or tempo-sync claim.
 */
object OverlapMusicalBank {
    private val entries = listOf(
        // Struck voices: no breathing or continuing excitation hides the body.
        entry(
            id = "glass-thread", name = "GLASS THREAD",
            description = "A short C4-reference pluck: two fifth-related families with a restrained harmonic glow.",
            family = OverlapPresetFamily.PLUCK, topology = OverlapTopology.TREFOIL,
            midi = 60, velocity = .85f,
            knot = 1f, pulse = 0f, lock = 0f, glow = .15f, ring = .25f, hold = 0f,
        ),
        entry(
            id = "hollow-pin", name = "HOLLOW PIN",
            description = "A hollow C4-reference strike with a single odd-harmonic family and a longer clean ring.",
            family = OverlapPresetFamily.PLUCK, topology = OverlapTopology.TORUS,
            midi = 60, velocity = .75f,
            knot = 1f, pulse = 0f, lock = 0f, glow = .02f, ring = .38f, hold = 0f,
        ),
        entry(
            id = "cup-strike", name = "CUP STRIKE",
            description = "A lower C3-reference bell chord: three reflected families with a fuller harmonic edge.",
            family = OverlapPresetFamily.PLUCK, topology = OverlapTopology.MIRROR,
            midi = 48, velocity = .90f,
            knot = 1f, pulse = 0f, lock = 0f, glow = .35f, ring = .40f, hold = 0f,
        ),
        entry(
            id = "chord-seed", name = "CHORD SEED",
            description = "A compact C3-reference chord from six independent lengths, with a dry, short decay.",
            family = OverlapPresetFamily.PLUCK, topology = OverlapTopology.SELF,
            midi = 48, velocity = .80f,
            knot = 0f, pulse = 0f, lock = 0f, glow = .05f, ring = .18f, hold = 0f,
        ),

        // Held excitation gives a finite texture; only LOW HALO deliberately swells.
        entry(
            id = "open-weave", name = "OPEN WEAVE",
            description = "A steady C3-reference chord field with a soft rubbing onset and six seconds of excitation.",
            family = OverlapPresetFamily.TEXTURE, topology = OverlapTopology.SELF,
            midi = 48, velocity = .70f,
            knot = 0f, pulse = 0f, lock = 0f, glow = .15f, ring = .62f, hold = 1f,
        ),
        entry(
            id = "fifth-cloud", name = "FIFTH CLOUD",
            description = "A glowing C3-reference root-and-fifth field, held for about five seconds before its finite tail.",
            family = OverlapPresetFamily.TEXTURE, topology = OverlapTopology.TREFOIL,
            midi = 48, velocity = .75f,
            knot = 1f, pulse = 0f, lock = 0f, glow = .55f, ring = .72f, hold = .92f,
        ),
        entry(
            id = "silver-veil", name = "SILVER VEIL",
            description = "A high C4-reference wash from mixed mirror routing; its strongest pitch can move away from the reference.",
            family = OverlapPresetFamily.TEXTURE, topology = OverlapTopology.MIRROR,
            midi = 60, velocity = .70f,
            knot = .72f, pulse = 0f, lock = 0f, glow = .45f, ring = .70f, hold = 1f,
        ),
        entry(
            id = "low-halo", name = "LOW HALO",
            description = "A C3-reference odd-harmonic body that gathers into slow shared swells during finite excitation.",
            family = OverlapPresetFamily.TEXTURE, topology = OverlapTopology.TORUS,
            midi = 48, velocity = .80f,
            knot = 1f, pulse = .08f, lock = 1f, glow = .65f, ring = .85f, hold = 1f,
        ),

        // These rhythms come from the six excitation/absorption phases themselves.
        entry(
            id = "loose-tide", name = "LOOSE TIDE",
            description = "A C3-reference chord with slow independent entrances; its six energy gates remain free to drift.",
            family = OverlapPresetFamily.BREATH, topology = OverlapTopology.SELF,
            midi = 48, velocity = .75f,
            knot = 0f, pulse = .38f, lock = 0f, glow = .15f, ring = .85f, hold = 1f,
        ),
        entry(
            id = "common-tide", name = "COMMON TIDE",
            description = "A C3-reference fifth field with slow synchronized surges and shared quiet gaps.",
            family = OverlapPresetFamily.BREATH, topology = OverlapTopology.TREFOIL,
            midi = 48, velocity = .80f,
            knot = 1f, pulse = .42f, lock = 1f, glow = .25f, ring = .75f, hold = 1f,
        ),
        entry(
            id = "pair-pulse", name = "PAIR PULSE",
            description = "A C4-reference reflected chord with a quicker locked breathing cadence and a harmonic edge.",
            family = OverlapPresetFamily.BREATH, topology = OverlapTopology.MIRROR,
            midi = 60, velocity = .75f,
            knot = 1f, pulse = .65f, lock = 1f, glow = .40f, ring = .65f, hold = 1f,
        ),
        entry(
            id = "circuit-run", name = "CIRCUIT RUN",
            description = "Fast gathered surges through a partly coupled circuit, using C3 as a geometry reference.",
            family = OverlapPresetFamily.BREATH, topology = OverlapTopology.TORUS,
            midi = 48, velocity = .85f,
            knot = .85f, pulse = .85f, lock = .90f, glow = .25f, ring = .65f, hold = 1f,
        ),
    )

    fun all(): List<OverlapBankEntry> = entries

    /** Every stored recipe names all controls, its note, velocity, and model explicitly. */
    private fun entry(
        id: String, name: String, description: String, family: OverlapPresetFamily,
        topology: OverlapTopology, midi: Int, velocity: Float,
        knot: Float, pulse: Float, lock: Float, glow: Float, ring: Float, hold: Float,
    ): OverlapBankEntry = OverlapBankEntry(
        id, name, description, family,
        OverlapPatch(
            name = name, topology = topology,
            macros = linkedMapOf(
                "KNOT" to knot, "PULSE" to pulse, "LOCK" to lock,
                "GLOW" to glow, "RING" to ring, "HOLD" to hold,
            ),
            midi = midi, velocity = velocity, model = 2,
        ),
    )
}

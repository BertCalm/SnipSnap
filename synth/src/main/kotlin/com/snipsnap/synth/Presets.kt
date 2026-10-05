package com.snipsnap.synth

/**
 * The cross-engine preset roster.
 *
 * Authored engine by engine, not all at once — THUMP was first (U1 of
 * `docs/SYNTH_UPGRADE.md`, PR #189) and SKIN was last, a whole wave after
 * the engine itself shipped. This dispatcher now covers all sixteen
 * registered engines with a roster.
 *
 * An unregistered engine name (or a future one with no roster yet)
 * returns an empty list rather than throwing, so the UI can ask before
 * checking what exists.
 */
object Presets {

    fun forVoice(engine: String, voice: String): List<Patch> = when (engine) {
        ThumpPatch.ENGINE -> {
            val v = ThumpVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            ThumpPresets.forVoice(v)
        }
        TinesPatch.ENGINE -> {
            val v = TinesVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            TinesPresets.forVoice(v)
        }
        PluckPatch.ENGINE -> {
            val v = PluckVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            PluckPresets.forVoice(v)
        }
        VelvetPatch.ENGINE -> {
            val v = VelvetVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            VelvetPresets.forVoice(v)
        }
        FathomPatch.ENGINE -> {
            val v = FathomVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            FathomPresets.forVoice(v)
        }
        ResinPatch.ENGINE -> {
            val v = ResinVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            ResinPresets.forVoice(v)
        }
        TonewheelPatch.ENGINE -> {
            val v = TonewheelVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            TonewheelPresets.forVoice(v)
        }
        VoxPatch.ENGINE -> {
            val v = VoxVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            VoxPresets.forVoice(v)
        }
        SkinPatch.ENGINE -> {
            val v = SkinVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            SkinPresets.forVoice(v)
        }
        TidePatch.ENGINE -> {
            val v = TideVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            TidePresets.forVoice(v)
        }
        SirenPatch.ENGINE -> {
            val v = SirenVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            SirenPresets.forVoice(v)
        }
        ForkPatch.ENGINE -> {
            val v = ForkVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            ForkPresets.forVoice(v)
        }
        BorePatch.ENGINE -> {
            val v = BoreVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            BorePresets.forVoice(v)
        }
        ArcoPatch.ENGINE -> {
            val v = ArcoVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            ArcoPresets.forVoice(v)
        }
        MercuryPatch.ENGINE -> {
            val v = MercuryVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            MercuryPresets.forVoice(v)
        }
        AerostatPatch.ENGINE -> {
            val v = AerostatVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            AerostatPresets.forVoice(v)
        }
        FlotillaPatch.ENGINE -> {
            val v = FlotillaVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            FlotillaPresets.forVoice(v)
        }
        TremorPatch.ENGINE -> {
            val v = TremorVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            TremorPresets.forVoice(v)
        }
        CorollaPatch.ENGINE -> {
            val v = CorollaVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            CorollaPresets.forVoice(v)
        }
        else -> emptyList()
    }

    fun byName(engine: String, voice: String, name: String): Patch? =
        forVoice(engine, voice).firstOrNull { it.name == name }

    /**
     * The rack chain a sound made from [macros] lands with when SEND TO PAD turns
     * it into a pad, or null when it lands dry - which is nearly every sound.
     * The string-machine presets ([StringMachine]) are the exception: they are
     * half a source and half an ENSEMBLE, so the pad's recipe carries both.
     *
     * Keyed by engine, voice and the *macro values*, not a label: only a factory
     * preset's exact values land with its chain, so a slider moved since the
     * preset loaded, or a player's own sound saved under a factory name, lands
     * dry, and a saved copy of the unmoved factory sound lands like the
     * original. An unregistered engine or an unknown voice is null.
     */
    fun landingFor(engine: String, voice: String, macros: Map<String, Float>): FxChain? = when (engine) {
        VelvetPatch.ENGINE -> VelvetVoice.entries.firstOrNull { it.name == voice }?.let { VelvetPresets.landingFor(it, macros) }
        ResinPatch.ENGINE -> ResinVoice.entries.firstOrNull { it.name == voice }?.let { ResinPresets.landingFor(it, macros) }
        else -> null
    }

    fun all(): List<Patch> =
        ThumpPresets.all() + TinesPresets.all() + PluckPresets.all() + VelvetPresets.all() +
            FathomPresets.all() + TonewheelPresets.all() + VoxPresets.all() + SkinPresets.all() +
            ResinPresets.all() + TidePresets.all() + SirenPresets.all() + ForkPresets.all() +
            BorePresets.all() + ArcoPresets.all() + MercuryPresets.all() + FlotillaPresets.all() +
            AerostatPresets.all() +
            TremorPresets.all() + CorollaPresets.all()
}

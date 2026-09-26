package com.snipsnap.synth

/**
 * The cross-engine preset roster.
 *
 * Authored engine by engine, not all at once — THUMP was first (U1 of
 * `docs/SYNTH_UPGRADE.md`, PR #189) and SKIN was last, a whole wave after
 * the engine itself shipped. This dispatcher now covers all ten
 * registered engines.
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
        else -> emptyList()
    }

    fun byName(engine: String, voice: String, name: String): Patch? =
        forVoice(engine, voice).firstOrNull { it.name == name }

    fun all(): List<Patch> =
        ThumpPresets.all() + TinesPresets.all() + PluckPresets.all() + VelvetPresets.all() +
            FathomPresets.all() + TonewheelPresets.all() + VoxPresets.all() + SkinPresets.all() +
            ResinPresets.all() + TidePresets.all()
}

package com.snipsnap.synth

/**
 * The cross-engine preset roster.
 *
 * Authored engine by engine, not all at once — THUMP is first (U1 of
 * `docs/SYNTH_UPGRADE.md`), the other seven join as their own preset passes
 * land. An engine with no roster yet returns an empty list rather than
 * throwing, so the UI can ask before checking what exists.
 */
object Presets {

    fun forVoice(engine: String, voice: String): List<Patch> = when (engine) {
        ThumpPatch.ENGINE -> {
            val v = ThumpVoice.entries.firstOrNull { it.name == voice } ?: return emptyList()
            ThumpPresets.forVoice(v)
        }
        else -> emptyList()
    }

    fun byName(engine: String, voice: String, name: String): Patch? =
        forVoice(engine, voice).firstOrNull { it.name == name }

    fun all(): List<Patch> = ThumpPresets.all()
}

package com.snipsnap.synth

/**
 * Phase 0's grid for TERRA's frozen guard
 * (docs/superpowers/plans/2026-09-30-chimera-phase-0-record.md, Appendix A,
 * G-P0a): the four voices at defaults, five macro probes on each voice
 * (twenty cases), and the sixteen Terra Kit pads - the three BUZZ pads A04,
 * A07 and A16 and the CLACK pad A15 among them. Forty cases.
 *
 * The pads are written out here, copied from TerraKits.kt in the same macro
 * order, rather than read back through PadRecipe and TerraPatch: the HIT
 * work rewrites that decoder, and a guard must not lean on what it guards.
 */
internal object TerraCases {

    class Case(val label: String, val voice: TerraVoice, val macros: Map<String, Float>)

    class KitPad(val slot: String, val name: String, val voice: TerraVoice, val macros: Map<String, Float>)

    val PROBES: List<Map<String, Float>> = listOf(
        mapOf("TUNE" to 0.30f, "DECAY" to 0.55f),
        mapOf("TUNE" to 0.65f, "DECAY" to 0.30f),
        mapOf("TUNE" to 0.45f, "DECAY" to 0.55f),
        mapOf("TUNE" to 0.40f, "DECAY" to 0.60f),
        mapOf("TUNE" to 0.8f, "DECAY" to 0.1f, "POS" to 0.9f),
    )

    val KIT: List<KitPad> = listOf(
        KitPad("A01", "Udu Whoomp", TerraVoice.RESONANT_CAVITY, mapOf("TUNE" to 0.1058f, "FORCE" to 0.10f, "DROOP" to 0.0769f, "CAVITY" to 0.90f)),
        KitPad("A02", "Bayan Drag", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.0803f, "FORCE" to 0.25f, "DROOP" to 0.8462f)),
        KitPad("A03", "Djembe Bass", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.1362f, "FORCE" to 0.30f, "POS" to 0.05f, "DROOP" to 0.1846f)),
        KitPad("A04", "Cajon Low", TerraVoice.RESONANT_CAVITY, mapOf("TUNE" to 0.1516f, "FORCE" to 0.20f, "CAVITY" to 0.70f, "BUZZ" to 0.15f)),
        KitPad("A05", "Dholak Bass", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.2779f, "FORCE" to 0.35f, "DROOP" to 0.5385f)),
        KitPad("A06", "Dumbek Doum", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.3333f, "FORCE" to 0.40f, "DROOP" to 0.2769f)),
        KitPad("A07", "Cajon Slap", TerraVoice.TUNED_BAR, mapOf("TUNE" to 0.2514f, "FORCE" to 0.85f, "BUZZ" to 0.75f)),
        KitPad("A08", "Djembe Open", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.7185f, "FORCE" to 0.50f, "POS" to 0.5f, "DROOP" to 0.0769f)),
        KitPad("A09", "Tabla Tin", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.8057f, "FORCE" to 0.80f, "POS" to 0.95f, "DROOP" to 0.0308f)),
        KitPad("A10", "Tabla Tun", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.8057f, "FORCE" to 0.45f, "POS" to 0.05f)),
        KitPad("A11", "Dumbek Tek", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 1.0f, "FORCE" to 0.92f, "POS" to 0.95f)),
        KitPad("A12", "Djembe Slap", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.9167f, "FORCE" to 0.95f, "POS" to 0.9f, "DROOP" to 0.3077f, "DECAY" to 0f)),
        KitPad("A13", "Agogo Low", TerraVoice.CONICAL_BELL, mapOf("TUNE" to 0.2508f, "FORCE" to 0.85f)),
        KitPad("A14", "Agogo High", TerraVoice.CONICAL_BELL, mapOf("TUNE" to 0.8807f, "FORCE" to 0.90f)),
        KitPad("A15", "Agogo Clack", TerraVoice.CONICAL_BELL, mapOf("TUNE" to 0.6108f, "FORCE" to 0.95f, "CLACK" to 0.6667f)),
        KitPad("A16", "Balafon Key", TerraVoice.TUNED_BAR, mapOf("TUNE" to 0.7573f, "FORCE" to 0.70f, "BUZZ" to 0.60f)),
    )

    val all: List<Case> =
        TerraVoice.entries.map { Case("default ${it.name}", it, emptyMap()) } +
            TerraVoice.entries.flatMap { v -> PROBES.mapIndexed { i, m -> Case("probe ${i + 1} ${v.name}", v, m) } } +
            KIT.map { Case("pad ${it.slot} ${it.name}", it.voice, it.macros) }
}

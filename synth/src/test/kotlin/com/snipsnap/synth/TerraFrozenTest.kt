package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * TERRA with no driver renders exactly as it did before HIT, BEND and TALK
 * (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md,
 * "Testing", test 1). [LegacyTerraBank] is the frozen copy and [TerraCases]
 * Phase 0's 40-case grid. Landed before any TERRA change and passing
 * against the unchanged source, which is the proof it guards something.
 */
class TerraFrozenTest {

    @Test
    fun `the frozen copy's macro table is today's`() {
        for (voice in TerraVoice.entries) assertEquals(LegacyTerraBank.macrosFor(voice), Terra.macrosFor(voice), voice.name)
    }

    @Test
    fun `Terra render is the frozen TERRA, sample for sample, on the 40 cases`() {
        var cases = 0
        for (c in TerraCases.all) {
            assertContentEquals(LegacyTerraBank.render(c.voice, c.macros).samples, Terra.render(c.voice, c.macros).samples, c.label)
            cases++
        }
        assertEquals(40, cases)
    }

    /** The audio the kit ships with, through whatever path TerraPatch.render takes - the routing Task 5 rewrites. */
    @Test
    fun `the shipped Terra Kit is the frozen TERRA, pad for pad`() {
        val kit = TerraKits.classic()
        assertEquals(TerraCases.KIT.size, kit.size)
        for ((i, pad) in TerraCases.KIT.withIndex()) {
            val shipped = requireNotNull(kit[i]) { "pad ${pad.slot} is empty" }
            assertContentEquals(LegacyTerraBank.render(pad.voice, pad.macros).samples, shipped.snip.samples, "pad ${pad.slot} ${pad.name}")
        }
    }

    /**
     * A plain recipe keeps today's bytes, so every saved TERRA pad and the
     * Terra Kit stay byte-stable (spec, "Data flow and compatibility"; test
     * 1, "pinned as text"). Three literal pins: a synthetic patch, the BUZZ
     * pad A07 and the CLACK pad A15. The numbers are the floats widened to
     * double and spelled by Double.toString, as Json.write spells them;
     * the build's JVM and a shortest round-trip spelling agree on all six. The other
     * pads are held to [todaysText], which rebuilds today's JSON without
     * the encoder under test.
     */
    @Test
    fun `a plain TERRA patch writes today's bytes`() {
        val pinned = """
            {
              "engine": "TERRA",
              "version": 1,
              "name": "Pin",
              "voice": "COMPOUND_MEMBRANE",
              "macros": {
                "TUNE": 0.5,
                "DROOP": 0.25
              }
            }
        """.trimIndent()
        assertEquals(pinned, TerraPatch("Pin", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.5f, "DROOP" to 0.25f)).toJsonText())
        val a07 = """
            {
              "engine": "TERRA",
              "version": 1,
              "name": "Cajon Slap",
              "voice": "TUNED_BAR",
              "macros": {
                "TUNE": 0.2513999938964844,
                "FORCE": 0.8500000238418579,
                "BUZZ": 0.75
              }
            }
        """.trimIndent()
        val a15 = """
            {
              "engine": "TERRA",
              "version": 1,
              "name": "Agogo Clack",
              "voice": "CONICAL_BELL",
              "macros": {
                "TUNE": 0.61080002784729,
                "FORCE": 0.949999988079071,
                "CLACK": 0.666700005531311
              }
            }
        """.trimIndent()
        val kit = TerraKits.classic()
        assertEquals(a07, Json.write(requireNotNull(kit[6]?.recipe?.entries?.get("patch"))), "A07's stored recipe")
        assertEquals(a15, Json.write(requireNotNull(kit[14]?.recipe?.entries?.get("patch"))), "A15's stored recipe")
        assertEquals(a07, TerraPatch("Cajon Slap", TerraVoice.TUNED_BAR, mapOf("TUNE" to 0.2514f, "FORCE" to 0.85f, "BUZZ" to 0.75f)).toJsonText())
        assertEquals(a15, TerraPatch("Agogo Clack", TerraVoice.CONICAL_BELL, mapOf("TUNE" to 0.6108f, "FORCE" to 0.95f, "CLACK" to 0.6667f)).toJsonText())
        for ((i, pad) in TerraCases.KIT.withIndex()) {
            val today = todaysText(pad.name, pad.voice, pad.macros)
            assertEquals(today, TerraPatch(pad.name, pad.voice, pad.macros).toJsonText(), "pad ${pad.slot} ${pad.name}")
            val stored = requireNotNull(kit[i]?.recipe?.entries?.get("patch")) { "pad ${pad.slot} has no patch in its recipe" }
            assertEquals(today, Json.write(stored), "pad ${pad.slot}'s stored recipe")
        }
    }

    /**
     * A plain TERRA patch's JSON as `Patches.toJsonValue` wrote it before HIT: engine, version 1 (the one
     * version the decoder accepts, Patches.kt :85-86), name, voice, then the macros in their own order.
     */
    private fun todaysText(name: String, voice: TerraVoice, macros: Map<String, Float>): String = Json.write(
        JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "engine" to JsonValue.Str("TERRA"),
                "version" to JsonValue.Num(1.0),
                "name" to JsonValue.Str(name),
                "voice" to JsonValue.Str(voice.name),
                "macros" to JsonValue.Obj(macros.entries.associateTo(LinkedHashMap()) { (k, v) -> k to JsonValue.Num(v.toDouble()) }),
            ),
        ),
    )
}

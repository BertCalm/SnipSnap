package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-09-29-glint-paths-design.md §4 - the old voice name is the migration marker. */
class GlintLegacyTest {

    private fun old(voice: String, macros: String) =
        """{"engine":"GLINT","version":${Patches.VERSION},"name":"Old","voice":"$voice","macros":{$macros}}"""

    /** Each name GLINT's voices were saved under before the paths rebuild, and the voice that replaced it. */
    private val REPLACED_BY = mapOf(
        "REED" to GlintVoice.SWEEP, "BOTTLE" to GlintVoice.SWEEP, "KAZOO" to GlintVoice.SWEEP,
        "CICADA" to GlintVoice.SWEEP, "PLATE" to GlintVoice.BRASS, "RATCHET" to GlintVoice.STEP,
    )

    @Test
    fun `every old voice name loads as the voice that replaced it`() {
        for ((name, voice) in REPLACED_BY) {
            val patch = GlintPatch.fromJsonText(old(name, "\"PEAK\":0.6,\"BLOOM\":0.4")) as GlintPatch
            assertEquals(voice, patch.voice, name)
            assertEquals(0.6f, patch.macros.getValue("PEAK"), name)
            // Old BLOOM 0.4 fell into PEAK on every voice but RATCHET, whose ladder climbed.
            assertEquals(if (name == "RATCHET") 0.3f else 0.7f, patch.macros.getValue("BLOOM"), 1e-6f, name)
        }
    }

    @Test
    fun `old BLOOM keeps its gesture - falling voices above centre, the ladder below`() {
        fun bloom(voice: String, b: Float) =
            (GlintPatch.fromJsonText(old(voice, "\"BLOOM\":$b")) as GlintPatch).macros.getValue("BLOOM")
        assertEquals(0.5f, bloom("REED", 0f))
        assertEquals(1f, bloom("REED", 1f))
        assertEquals(1f, bloom("PLATE", 1f))
        assertEquals(0.5f, bloom("RATCHET", 0f))
        assertEquals(0f, bloom("RATCHET", 1f))
    }

    @Test
    fun `an old patch with no BLOOM gets the old default's gesture, not today's default`() {
        fun bloom(voice: String) = (GlintPatch.fromJsonText(old(voice, "")) as GlintPatch).macros.getValue("BLOOM")
        assertEquals(0.675f, bloom("BOTTLE"), 1e-6f)
        assertEquals(0.325f, bloom("RATCHET"), 1e-6f)
    }

    @Test
    fun `today's names load untouched`() {
        val patch = GlintPatch("New", GlintVoice.STEP, mapOf("BLOOM" to 0.2f))
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
    }

    @Test
    fun `a kit pad saved before the change still opens`() {
        // A recipe as an old build wrote it: today's recipe JSON, with the
        // voice renamed back to what it was called then. (PadRecipe's JSON
        // needs a "recipe" version key, so it is not written by hand.)
        val current = PadRecipe(patch = GlintPatch("Old", GlintVoice.SWEEP, mapOf("PEAK" to 0.5f, "BLOOM" to 0.4f)))
        val saved = current.toJsonText().replace("\"SWEEP\"", "\"REED\"")
        val patch = PadRecipe.fromJsonText(saved).patch as GlintPatch
        assertEquals(GlintVoice.SWEEP, patch.voice)
        assertEquals(0.7f, patch.macros.getValue("BLOOM"), 1e-6f)
    }

    @Test
    fun `a migrated patch is saved under today's name and reloads as itself`() {
        // The old name is the only marker and a save drops it, so a second load
        // must not remap BLOOM again: RATCHET's 1 becomes 0, and remapped
        // again 0 would become 0.5.
        val once = GlintPatch.fromJsonText(old("RATCHET", "\"BLOOM\":1")) as GlintPatch
        assertEquals("STEP", once.voiceName)
        assertEquals(0f, once.macros.getValue("BLOOM"))
        assertEquals(once, Patches.fromJsonText(once.toJsonText()))
    }

    @Test
    fun `no voice of today is called by an old name`() {
        // Decode looks up today's names first, so a current voice named like an old
        // one would swallow that name's old patches, which would then load unmigrated.
        val today = GlintVoice.entries.map { it.name }
        assertTrue(today.none { it in REPLACED_BY }, "a current voice shares an old name: $today")
    }

    @Test
    fun `an old BLOOM outside 0 to 1 is refused, not remapped into range`() {
        // -0.4 would remap to 0.3 (REED) or 0.7 (RATCHET), inside 0 to 1: an
        // old build refused it, and so must this one, naming the saved value.
        for (voice in listOf("REED", "RATCHET")) {
            for (b in listOf(-0.4f, 1.5f)) {
                val refusal = assertFailsWith<IllegalArgumentException>("$voice at $b") {
                    GlintPatch.fromJsonText(old(voice, "\"BLOOM\":$b"))
                }
                assertTrue("$b" in refusal.message.orEmpty(), "$voice: the refusal should name the saved $b, said: ${refusal.message}")
            }
        }
    }

    @Test
    fun `an old patch at either BLOOM end, or without one, validates and plays`() {
        // 0.35 is the floor GlintTest uses to catch a render that is broken, not one that is merely quiet.
        for (name in REPLACED_BY.keys) {
            for (macros in listOf("\"BLOOM\":0", "\"BLOOM\":1", "")) {
                val snip = GlintPatch.fromJsonText(old(name, macros)).render()
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$name {$macros} broke range")
                assertTrue(snip.peak() > 0.35f, "$name {$macros} is too quiet")
            }
        }
    }
}

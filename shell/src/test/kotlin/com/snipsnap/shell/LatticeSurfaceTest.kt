package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Lattice
import com.snipsnap.synth.LatticePatch
import com.snipsnap.synth.LatticeVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LatticeSurfaceTest {
    @Test
    fun `saved lattice presets retain their note velocity model and held state when promoted`() {
        val directory = Files.createTempDirectory("lattice-preset-").toFile()
        try {
            val patch = LatticePatch(
                "MY ROUTE", LatticeVoice.HOLLOW,
                Lattice.defaults(LatticeVoice.HOLLOW) + mapOf("TUNE" to .625f, "COLOR" to .37f, "HOLD" to 1f),
                midi = 60, velocity = .31f,
            )
            UserPresets.save(directory, patch, 123L)
            val saved = UserPresets.read(directory).single()
            assertEquals(patch, saved.patch)
            assertEquals(listOf(saved), UserPresets.forVoice(listOf(saved), "LATTICE", "HOLLOW"))
            val line = UserPresets.rosterLine(saved.patch)
            assertTrue(line.startsWith("p(LatticeVoice.HOLLOW, \"MY ROUTE\", "), line)
            assertTrue(line.endsWith(".copy(midi = 60, velocity = 0.31f, model = ${Lattice.MODEL_VERSION}),"), line)
            assertEquals("LatticeVoice", UserPresets.voiceEnum("LATTICE"))
            assertEquals("LatticePresets.kt", UserPresets.rosterFile(patch))
            assertEquals("LatticePresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
            val edited = assertIs<LatticePatch>(Patches.edited(saved.patch, "MY SECOND PATH", patch.macros + ("TURN" to .23f)))
            assertEquals(patch.midi, edited.midi)
            assertEquals(patch.velocity, edited.velocity)
            assertEquals(patch.model, edited.model)
            assertEquals(1f, edited.macros.getValue("HOLD"))
            assertEquals(.23f, edited.macros.getValue("TURN"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `spread retains authored lattice pitch through silence halo and native recipe copies`() {
        val silence = Snip(FloatArray(4096), channels = 1, sampleRate = 44_100)
        val upperMode = Snip(FloatArray(4096) { i ->
            (.2 * kotlin.math.sin(2 * Math.PI * 880 * i / 44_100)).toFloat()
        }, channels = 1, sampleRate = 44_100)
        for (voice in LatticeVoice.entries) {
            val patch = LatticePatch("Saved Path", voice,
                Lattice.defaults(voice) + mapOf("TUNE" to .625f, "REJECT" to 1f),
                midi = 60, velocity = .35f)
            val duplicate = requireNotNull(PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            assertEquals(63f, Spread.detect(silence, duplicate))
            assertEquals(63f, Spread.detect(upperMode, duplicate), "the collector's strongest overtone must not redefine its note")
            assertEquals(36f, Spread.detect(silence, patch.copy(midi = 36, macros = mapOf("TUNE" to 0f))))
            assertEquals(84f, Spread.detect(silence, patch.copy(midi = 84, macros = mapOf("TUNE" to 1f))))
            assertEquals(60f, Spread.detect(silence, patch.copy(macros = emptyMap())), "sparse maps retain neutral TUNE")
        }
    }

    @Test
    fun `lattice starter exports a regenerable dry bank with held and finite classifications`() {
        val directory = Files.createTempDirectory("lattice-starter-").toFile()
        try {
            val starter = requireNotNull(StarterKits.byId("lattice"))
            assertFalse(starter.seeded)
            assertEquals(KeySpec.parse("Cminpent"), starter.key)
            val kit = starter.render("Lattice Test", directory)
            assertFalse(Preflight.check(kit, directory).blocked())
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            val decoded = kit.pads.map { pad ->
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = assertIs<LatticePatch>(recipe.patch)
                assertEquals(Lattice.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                assertEquals(null, recipe.fx, "the engine renders this dry starter")
                Triple(pad, recipe, patch)
            }
            assertEquals(LatticeVoice.entries.toSet(), decoded.map { it.third.voice }.toSet())
            assertTrue(decoded.any { it.first.drumClass == DrumClass.LOOP })
            assertTrue(decoded.any { it.first.drumClass == DrumClass.TONAL })
            // Rebuild representative notes and held buffers from the JSON written to the kit.
            for ((pad, recipe, _) in decoded.distinctBy { it.third.voice to it.first.drumClass }) {
                val replay = File(directory, "replay-${pad.slot}.wav")
                WavWriter.write(replay, recipe.render())
                assertContentEquals(File(directory, pad.sampleFile).readBytes(), replay.readBytes(), pad.displayName)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}

package com.snipsnap.synth

import com.snipsnap.audio.Scale
import com.snipsnap.audio.Tuner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The string machine's landing (PR-E2 of the ARCO design's ENSEMBLE track): four
 * factory presets take an ENSEMBLE chain to their pad, and nothing else does.
 *
 * The presets themselves were authored by measurement - a probe rendered
 * candidates and read their sustain, brightness drift and the fold's cost - and
 * not by ear, and their exact macro values are candidates until the owner has
 * heard them on the gate page. What these tests pin is the contract around
 * them: which sounds land with a chain, what the chain is, that the pad it makes
 * is a clean stereo pair, and what the phone's fold of that pair costs.
 */
class StringMachineTest {

    /** Engine, voice, name, and the ENSEMBLE macros the landing names: the whole table, written out once. */
    private data class Machine(val engine: String, val voice: String, val name: String, val ensemble: Map<String, Float>)

    private val classic = mapOf("DEPTH" to 0.5f, "RATE" to 0.5f, "WIDTH" to 1f, "SECTION" to 1f)
    private val machines = listOf(
        Machine(VelvetPatch.ENGINE, "BRASS", "STRING MACHINE", classic),
        Machine(VelvetPatch.ENGINE, "BRASS", "THIN STRINGS", mapOf("DEPTH" to 0.35f, "RATE" to 0.6f, "WIDTH" to 1f, "SECTION" to 1f)),
        Machine(ResinPatch.ENGINE, "BRASS", "WIDE STRINGS", classic),
        Machine(ResinPatch.ENGINE, "BRASS", "DARK STRINGS", mapOf("DEPTH" to 0.6f, "RATE" to 0.4f, "WIDTH" to 1f, "SECTION" to 1f)),
    )

    private fun patch(m: Machine): Patch = requireNotNull(Presets.byName(m.engine, m.voice, m.name)) { "${m.engine}/${m.voice}/${m.name} is not on the roster" }

    private fun landing(m: Machine): FxChain = requireNotNull(Presets.landingFor(m.engine, m.voice, patch(m).macros)) { "${m.name} lands dry" }

    @Test
    fun `exactly the four string machines land with a chain and every other factory preset lands dry`() {
        val landed = mutableListOf<String>()
        for (preset in Presets.all()) {
            if (Presets.landingFor(preset.engine, preset.voiceName, preset.macros) != null) landed += "${preset.engine}/${preset.voiceName}/${preset.name}"
        }
        assertEquals(machines.map { "${it.engine}/${it.voice}/${it.name}" }.sorted(), landed.sorted())
    }

    @Test
    fun `a landing is ENSEMBLE alone, at the swing its name promises`() {
        for (m in machines) {
            val chain = landing(m)
            for (section in FxChain.SECTION_NAMES) {
                if (section == "ensemble") continue
                assertNull(chain.section(section), "${m.name}: the landing added $section, which the measurements did not support")
            }
            assertEquals(m.ensemble, chain.section("ensemble"), "${m.name}: ENSEMBLE macros")
            assertTrue(!chain.reverse, "${m.name}: the landing reversed the pad")
        }
        // ENSEMBLE's own DEPTH, RATE and WIDTH, with its SECTION turned all the way up: the six players, not the chorus.
        assertEquals(Ensemble.defaults() + ("SECTION" to 1f), classic, "the classic landing is ENSEMBLE's own defaults with the players, or the KDoc's claim is stale")
    }

    @Test
    fun `the landing follows the sound, not the label`() {
        for (m in machines) {
            val factory = patch(m)
            // A player's copy of the unmoved sound under another name is the same sound and lands the same way.
            assertNotNull(Presets.landingFor(m.engine, m.voice, factory.macros), "${m.name}: the unmoved sound")
            // Any macro moved - even a hair - and the sound is the player's own: dry.
            for ((key, value) in factory.macros) {
                val nudged = factory.macros + (key to (if (value > 0.5f) value - 0.01f else value + 0.01f))
                assertNull(Presets.landingFor(m.engine, m.voice, nudged), "${m.name}: $key moved and it still landed with the chain")
            }
            // A macro the roster does not know about is not a difference in the sound.
            assertNotNull(Presets.landingFor(m.engine, m.voice, factory.macros + ("NOT A MACRO" to 0.3f)), "${m.name}: an extra key")
            // A macro missing is not the sound either.
            assertNull(Presets.landingFor(m.engine, m.voice, factory.macros - factory.macros.keys.first()), "${m.name}: a macro missing")
        }
    }

    @Test
    fun `a string machine's macros on the wrong voice or the wrong engine land dry`() {
        for (m in machines) {
            val macros = patch(m).macros
            for (voice in listOf("BASS", "LEAD", "SQUELCH", "CHIP", "NOT A VOICE")) {
                assertNull(Presets.landingFor(m.engine, voice, macros), "${m.name}'s macros on $voice")
            }
            assertNull(Presets.landingFor("THUMP", m.voice, macros), "${m.name}'s macros on THUMP")
            assertNull(Presets.landingFor("NOT AN ENGINE", m.voice, macros), "${m.name}'s macros on no engine")
        }
        // The two engines' BRASS voices share three macro names (TUNE, CUTOFF, DECAY) and none of the other macros, so one engine's sound never matches the other's.
        val velvetMachine = machines.first { it.engine == VelvetPatch.ENGINE }
        assertNull(Presets.landingFor(ResinPatch.ENGINE, "BRASS", patch(velvetMachine).macros))
    }

    @Test
    fun `a landed pad is a clean stereo pair the recipe regenerates bit for bit`() {
        for (m in machines) {
            val recipe = PadRecipe(patch(m), landing(m))
            val dry = patch(m).render()
            val pad = recipe.render()
            assertEquals(2, pad.channels, "${m.name}: the landing did not widen the mono voice")
            assertEquals(dry.sampleRate, pad.sampleRate, "${m.name}: rate")
            assertTrue(pad.frameCount >= dry.frameCount, "${m.name}: the pad is shorter than the voice it came from")
            assertTrue(pad.samples.all { it.isFinite() }, "${m.name}: non-finite samples")
            assertTrue(pad.samples.all { it in -1f..1f }, "${m.name}: clipped")
            assertTrue(pad.peak() > 0.5f, "${m.name}: too quiet: ${pad.peak()}")
            // The recipe is the pad: through JSON and back, same samples (the file is written next to the WAV).
            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe.fx, restored.fx, "${m.name}: the chain through JSON")
            assertTrue(pad.samples.contentEquals(restored.render().samples), "${m.name}: the regenerated pad differs")
        }
    }

    @Test
    fun `a landing's fold is measured - what the phone loses is bounded and the pair is still a pair`() {
        val rows = machines.map { m ->
            val pad = PadRecipe(patch(m), landing(m)).render()
            m to FoldMeter.report(pad)
        }
        println("STRING MACHINE landings, the fold against the channels: " + rows.joinToString(", ") { (m, r) ->
            "${m.name} ${"%.2f".format(r.lossDb)} dB (L/R %.2f, pump against the pair %.1f dB)".format(r.correlation, r.pumpDb)
        })
        for ((m, r) in rows) {
            // Measured with the six players: -0.38 / -1.09 / -0.60 / -0.45 dB, L/R 0.74 / 0.80 / 0.74 / 0.58, pump 0.6 / 0.3 / 0.5 / 1.3 dB
            // (STRING MACHINE / THIN / WIDE / DARK). With the chorus it was -0.92 / -1.08 / -1.49 / -1.32 dB, L/R 0.29 / 0.47 / 0.04 / 0.26,
            // pump 1.7 / 1.3 / 2.6 / 2.3 dB: the players fold more gently and the pair is narrower, which is what the L/R allowance now
            // watches. The allowances sit just past the worst of each, so a preset that drifts into a worse fold fails here, next to the
            // number it drifted from.
            assertTrue(r.lossDb > -1.4, "${m.name}: the fold loses ${r.lossDb} dB, more than the measured -1.1 dB allows")
            assertTrue(r.pumpDb < 1.8, "${m.name}: the fold pumps ${r.pumpDb} dB against its pair, past the measured 1.3 dB")
            assertTrue(r.correlation < 0.85, "${m.name}: the pair is barely wider than mono: L/R ${r.correlation}")
        }
    }

    @Test
    fun `the landing does not cost a string machine its in-key tuning`() {
        // KitBuilder.assign retunes a TONAL pad into the kit's key from the mono fold of what it is handed, and
        // leaves a pad it cannot pitch alone. At these landings' DEPTH and RATE the ENSEMBLE swings a tap's pitch
        // by up to about 24 cents (Ensemble.peakCents, slow and fast sines summed), which could have dropped the
        // detector under its bar; measured, the landed pair's confidence is within 0.02 of the voice's and it asks
        // for the same tune within 4 cents (fine cents, dry / landed: -4 and -4, -1 and -1, -8 and -4, -4 and -4).
        for (m in machines) {
            val dry = patch(m).render()
            val landed = landing(m).process(dry)
            val plain = assertNotNull(Tuner.inKey(dry, 9, Scale.MINOR), "${m.name}: the voice itself is not pitched")
            val tuned = assertNotNull(Tuner.inKey(landed, 9, Scale.MINOR), "${m.name}: the landing hid the pitch from the detector")
            assertEquals(plain.targetMidi, tuned.targetMidi, "${m.name}: the landed pad tunes to a different note")
            val centsApart = abs((plain.tuneCoarse * 100 + plain.tuneFine) - (tuned.tuneCoarse * 100 + tuned.tuneFine))
            assertTrue(centsApart <= 6, "${m.name}: the landed pad asks for a tune ${centsApart} cents from the voice's")
            assertTrue(abs(plain.confidence - tuned.confidence) < 0.05f, "${m.name}: the detector is ${plain.confidence} sure of the voice and ${tuned.confidence} of the landed pad")
        }
    }
}

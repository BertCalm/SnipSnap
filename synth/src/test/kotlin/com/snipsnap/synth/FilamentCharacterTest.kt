package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Character has to change the signal's spectral shape, while the original instrument stays replayable. */
class FilamentCharacterTest {
    private val controls = mapOf("INJECT" to .65f, "BEND" to .6f, "LENGTH" to .6f,
        "BUNDLE" to .6f, "COLLECT" to .5f, "HOLD" to 0f)

    private fun window(samples: FloatArray, fromSeconds: Double): FloatArray {
        val from = (fromSeconds * Dsp.RATE).toInt()
        val until = from + (.4 * Dsp.RATE).toInt()
        assertTrue(until <= samples.size, "character window falls outside the actual render")
        return samples.copyOfRange(from, until)
    }

    @Test
    fun `revised sources evolve partial balance beyond the original amplitude envelope`() {
        for (voice in FilamentVoice.entries) {
            val original = Filament.inspect(voice, controls, midi = 48, model = 1)
            val revised = Filament.inspect(voice, controls, midi = 48, model = 2)
            fun evolution(p: Filament.Probe) = TangleMeasure.spectralDistance(window(p.source, .08), window(p.source, .70))
            val before = evolution(original)
            val after = evolution(revised)
            println("FILAMENT $voice source spectral evolution model1=$before model2=$after")
            // A small normalized spectral change is a mechanism floor, not a claim of listening acceptance.
            assertTrue(after > .01 && after > before + .005,
                "$voice revised character only changes amplitude or repeats the original spectrum: $before -> $after")
        }
    }

    @Test
    fun `revised voice identities retain different spectral shapes at one shared note and macro setting`() {
        val rendered = FilamentVoice.entries.associateWith {
            Filament.inspect(it, controls, midi = 48, velocity = .7f, config = FilamentConfig(seed = 29), model = 2)
        }
        for ((index, voice) in FilamentVoice.entries.withIndex()) for (other in FilamentVoice.entries.drop(index + 1)) {
            val a = rendered.getValue(voice)
            val b = rendered.getValue(other)
            val distance = TangleMeasure.spectralDistance(window(a.raw, .08), window(b.raw, .08))
            println("FILAMENT model2 shared-control voice spectral contrast $voice/$other=$distance")
            assertTrue(distance > .01, "$voice and $other differ only by level or timing: spectrum distance $distance")
        }
    }

    @Test
    fun `longer held character evolves inside the period while preserving its actual prior propagation`() {
        for (voice in FilamentVoice.entries) {
            val p = Filament.inspect(voice, controls + ("HOLD" to 1f), midi = 48, model = 2)
            val seconds = p.samples.size.toDouble() / Dsp.RATE
            assertTrue(seconds in 2.1..2.4, "$voice revised HOLD omitted its complete evolving gesture: $seconds s")
            val motion = TangleMeasure.spectralDistance(window(p.source, .03), window(p.source, seconds * .5))
            println("FILAMENT $voice held source spectral motion $motion")
            assertTrue(motion > .005, "$voice held character is a stationary source: $motion")
            val previous = assertNotNull(p.previous)
            assertEquals(p.samples.size, previous.size)
            val reference = previous.sumOf { it.toDouble() * it }
            val difference = previous.indices.sumOf { val delta = previous[it].toDouble() - p.samples[it]; delta * delta }
            assertTrue(reference > 0.0 && difference / reference < 1e-5,
                "$voice character broke genuine predecessor recurrence: ${difference / reference}")
        }
    }

    @Test
    fun `explicit original models preserve the published finite and held native PCM bytes`() {
        // These six SHA256 fixtures were captured from the published first-listen native PCM24 WAVs,
        // before model 2 was implemented. The full audition separately checks all 366 original WAVs.
        data class Fixture(val voice: FilamentVoice, val values: List<Float>, val finite: String, val held: String)
        val fixtures = listOf(
            Fixture(FilamentVoice.THREAD, listOf(.25f, .15f, .25f, 0f, .20f),
                "a904966d2e85f8f2e872d2f60804d3e2f5c34e9c5332b74e16403fcb4c124bd9",
                "388551b7b87952e4e6e10254ca4b6c121c71ea89b1786ba069fef6cb8bfe8e83"),
            Fixture(FilamentVoice.PULSE, listOf(.65f, .30f, .65f, .20f, .35f),
                "6e188dd48fc7c91cbd3cb381342503a727250fbd7e6f19e0f018ed41f496404d",
                "05a08687debdf3d159c778fdf3287cb811d186d845488a802671a9235ef9ae4c"),
            Fixture(FilamentVoice.CURL, listOf(.45f, .65f, .45f, .15f, .50f),
                "71f490a44e41ae40b5be44fe575fedf7de871b99dc54776fa1a6c12920422f5d",
                "a8a27f515a32b06f0f328462f0660bd260a96d98019cbee7732443aa1b0f4623"),
        )
        val names = listOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT")
        for (fixture in fixtures) for (held in listOf(false, true)) {
            val macros = names.zip(fixture.values).toMap() + ("HOLD" to if (held) 1f else 0f)
            val snip = Filament.render(fixture.voice, macros, midi = 48, velocity = 1f,
                config = FilamentConfig(), model = 1)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(if (held) fixture.held else fixture.finite, hash,
                "${fixture.voice} HOLD$held changed the published original native WAV")
        }
    }
}

package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Judge EDGE on complete factory sounds after output conditioning, rather than an isolated layer. */
class ContourEdgeRangeTest {
    private val positions = listOf(0f, .25f, .5f, .75f, 1f)
    private val retained = setOf("Soft Digital", "Bright Margin")

    @Test
    fun `contrast broadens all seven complete recipe ranges including adjacent middle settings`() {
        val affected = ContourPresets.all().filter { it.name !in retained }
        assertTrue(affected.size == 7, "the range review must cover all seven affected recipes")
        for (patch in affected) for (hold in listOf(0f, 1f)) {
            fun spectrum(response: ContourEdgeResponse, edge: Float) = bands(
                ContourEngine.render(patch.voice, patch.macros + mapOf("HOLD" to hold, "EDGE" to edge),
                    patch.velocity, patch.config.copy(edgeResponse = response)).samples)
            val previous = positions.associateWith { spectrum(ContourEdgeResponse.FOREGROUND, it) }
            val revised = positions.associateWith { spectrum(ContourEdgeResponse.CONTRAST, it) }
            val oldFull = distance(previous.getValue(0f), previous.getValue(1f))
            val newFull = distance(revised.getValue(0f), revised.getValue(1f))
            val midpointChange = distance(previous.getValue(.5f), revised.getValue(.5f))
            val steps = positions.zipWithNext { from, to ->
                distance(revised.getValue(from), revised.getValue(to))
            }
            val context = "${patch.name} HOLD $hold"
            println("EDGE range $context: full $oldFull -> $newFull dB; midpoint $midpointChange dB; steps $steps")
            // Regression margins against the previously measured recipes, not hearing thresholds.
            // Normalized band powers make a scalar gain change unable to satisfy the guard.
            assertTrue(newFull > 2.5 && newFull > oldFull * 1.15,
                "$context: the full range did not substantially broaden")
            assertTrue(steps.all { it > .35 }, "$context: a quarter-range step remains ineffective: $steps")
            // A similar authored midpoint can still belong to a much wider useful sweep.
            // Fixed-setting old/new distance is diagnostic; full span and adjacent steps are the contract.
        }
    }

    @Test
    fun `contrast retains the requested root at maximum EDGE and BODY extremes`() {
        for (patch in ContourPresets.all().filter { it.name !in retained }) {
            for (body in listOf(0f, 1f)) for (hold in listOf(0f, 1f)) {
                requireRoot(patch, body, .5f, hold)
            }
        }
        // These lower and upper register cases exposed octave errors in the wider-mix trial.
        val rounded = ContourPresets.all().single { it.name == "Rounded Edge" }
        val twin = ContourPresets.all().single { it.name == "Twin Outline" }
        for (hold in listOf(0f, 1f)) {
            requireRoot(rounded, 1f, .375f, hold)
            for (tune in listOf(.25f, .75f)) requireRoot(twin, 1f, tune, hold)
        }
    }

    @Test
    fun `zero EDGE retains the previous body sound in finite and held recipes`() {
        for (patch in ContourPresets.all()) for (hold in listOf(0f, 1f)) {
            val macros = patch.macros + mapOf("EDGE" to 0f, "HOLD" to hold)
            val previous = ContourEngine.render(patch.voice, macros, patch.velocity,
                patch.config.copy(edgeResponse = ContourEdgeResponse.FOREGROUND))
            val revised = ContourEngine.render(patch.voice, macros, patch.velocity,
                patch.config.copy(edgeResponse = ContourEdgeResponse.CONTRAST))
            assertContentEquals(previous.samples, revised.samples, "${patch.name} HOLD $hold")
        }
    }

    private fun requireRoot(patch: ContourPatch, body: Float, tune: Float, hold: Float) {
        val context = "${patch.name} BODY $body TUNE $tune HOLD $hold"
        val tone = ContourEngine.render(patch.voice,
            patch.macros + mapOf("BODY" to body, "TUNE" to tune, "HOLD" to hold, "EDGE" to 1f),
            patch.velocity, patch.config.copy(edgeResponse = ContourEdgeResponse.CONTRAST))
        val pitch = assertNotNull(Pitch.detect(tone, .08f, .35f), "$context: no detected root")
        val requested = ContourEngine.frequencyFor(patch.voice, tune).toDouble()
        val cents = 1200 * ln(pitch.hz / requested) / ln(2.0)
        assertTrue(abs(cents) <= 10, "$context: detected root moved $cents cents")
        assertTrue(pitch.confidence >= .85f, "$context: root confidence fell to ${pitch.confidence}")
    }

    private fun bands(samples: FloatArray): DoubleArray {
        val count = 40
        val size = 8192
        val energies = DoubleArray(count)
        val scale = count / ln(16_000.0 / 25.0)
        for (from in 0..samples.size - size step 2048) {
            val power = ContourTestAudio.power(samples, from, size)
            for (bin in 1 until power.size) {
                val hz = bin.toDouble() * Dsp.RATE / size
                if (hz >= 25 && hz < 16_000) {
                    val band = (ln(hz / 25) * scale).toInt().coerceIn(0, count - 1)
                    energies[band] += power[bin]
                }
            }
        }
        val total = energies.sum()
        require(total > 1e-12)
        return DoubleArray(count) { energies[it] / total }
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double = sqrt(a.indices.sumOf {
        val difference = 10 * log10(b[it].coerceAtLeast(1e-10) / a[it].coerceAtLeast(1e-10))
        (a[it] + b[it]) * .5 * difference * difference
    })
}

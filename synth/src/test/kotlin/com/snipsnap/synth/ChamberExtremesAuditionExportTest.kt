package com.snipsnap.synth

import com.snipsnap.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChamberExtremesAuditionExportTest {
    @Test
    fun `wider exported diagnostic replays exact raw and matched PCM with its complete authored room`() {
        val root = createTempDirectory("chamber-wide-portable-replay").toFile()
        try {
            val patch = ChamberPatch("Portable wider room", ChamberVoice.AQUARIUM,
                Chamber.defaults(ChamberVoice.AQUARIUM) + mapOf("STRIKE" to .8f, "MATERIAL" to 0f, "PLACEMENT" to 1f),
                midi = 57, velocity = .42f, seed = Long.MIN_VALUE + 117,
                configuration = ChamberConfig.aquarium().copy(dimensions = ChamberDimensions(6f, 8f, 5f)),
                model = Chamber.WIDE_RANGE_MODEL)
            val options = Chamber.ProbeOptions(normalize = true, seconds = .4f, recordDiagnostics = true, recordStems = true)
            val output = ChamberExtremesAuditionGenerator.exportCandidate(root, "portable-wide", patch, options,
                "MATERIAL", 0f, "test-source")
            val recipe = File(root, "recipes/portable-wide.json")
            val saved = Json.parse(recipe.readText()).obj()
            assertEquals(patch, ChamberPatch.fromJsonValue(saved.getValue("patch")))
            assertEquals(4, saved.getValue("options").obj().getValue("oversample").int())
            assertTrue(output.metrics.getValue("passedHardGates") == true)
            for (mode in listOf("raw", "matched")) {
                val replay = File(root, "replayed-$mode.wav")
                ChamberDiagnosticReplay.main(arrayOf(recipe.path, replay.path, mode))
                assertContentEquals(File(root, "$mode/portable-wide.wav").readBytes(), replay.readBytes(),
                    "$mode replay lost its room, explicit model, signed seed, gain or tail treatment")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `late spectra expose a changed room answer while gain and an unchanged attack do not`() {
        val frames = (4.5 * Dsp.RATE).toInt()
        val original = FloatArray(frames) { i ->
            val t = i.toDouble() / Dsp.RATE
            (sin(2 * PI * 261.625565 * t) * exp(-t / .8)).toFloat()
        }
        val changedTail = FloatArray(frames) { i ->
            val t = i.toDouble() / Dsp.RATE
            if (t < .75) original[i]
            else (original[i] + .7 * sin(2 * PI * 3000 * t) * exp(-(t - .75) / 1.5)).toFloat()
        }
        val louder = FloatArray(frames) { original[it] * 3f }
        val changed = windowComparisons(ChamberExtremesAuditionGenerator.comparison(original, changedTail, 60))
        val gainOnly = windowComparisons(ChamberExtremesAuditionGenerator.comparison(original, louder, 60))
        assertEquals(0.0, changed.getValue("attack").getValue("shapeDistance") as Double, 1e-12)
        assertEquals(0.0, changed.getValue("body").getValue("spectralDistance") as Double, 1e-12)
        assertTrue((changed.getValue("answer").getValue("spectralDistance") as Double) > .5)
        assertTrue((changed.getValue("tail").getValue("spectralDistance") as Double) > .5)
        for (window in gainOnly.values) {
            assertTrue((window.getValue("shapeDistance") as Double) < 1e-6)
            assertTrue((window.getValue("spectralDistance") as Double) < 1e-6)
        }
        val oldTimes = ChamberExtremesAuditionGenerator.measure(original, original, 60)
        val newTimes = ChamberExtremesAuditionGenerator.measure(changedTail, changedTail, 60)
        assertTrue((newTimes.getValue("t90Seconds") as Double) > (oldTimes.getValue("t90Seconds") as Double) + .5,
            "energy timing must expose a longer answer rather than judging only the attack")
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowComparisons(value: Map<String, Any?>) = value.getValue("windows") as Map<String, Map<String, Any?>>
}

package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ChamberDiagnosticReplayTest {
    @Test
    fun `diagnostic replay preserves ablation selected stem and shared output gain in actual PCM`() {
        val directory = createTempDirectory("chamber-diagnostic-replay").toFile()
        try {
            val patch = ChamberPatch("Diagnostic", ChamberVoice.AQUARIUM, Chamber.defaults(ChamberVoice.AQUARIUM),
                midi = 57, velocity = .42f, seed = Long.MAX_VALUE)
            val options = Chamber.ProbeOptions(returnsEnabled = false, averageSurfaces = true,
                seconds = .3f, recordDiagnostics = false, recordStems = true)
            val recipe = File(directory, "recipe.json").also {
                it.writeText(Json.write(ChamberDiagnosticReplay.recipe(patch, options, "instrument-state", .7f, 1f)))
            }
            val probe = Chamber.renderProbe(patch.voice, patch.macros, patch.midi, patch.velocity,
                patch.configuration, patch.seed, patch.model, options)
            val selected = ChamberDiagnosticReplay.select(probe, "instrument-state")
            for ((mode, gain) in listOf("raw" to 1f, "matched" to .7f)) {
                val actual = File(directory, "$mode.wav")
                ChamberDiagnosticReplay.main(arrayOf(recipe.path, actual.path, mode))
                val expected = ByteArrayOutputStream().also {
                    val samples = FloatArray(selected.size) { frame -> selected[frame] * gain }
                    if (mode == "matched") Dsp.fadeTail(samples)
                    WavWriter.write(it, Snip(samples, 1, Dsp.RATE),
                        WavWriter.BitDepth.PCM_16)
                }.toByteArray()
                assertContentEquals(expected, actual.readBytes(), "$mode replay changed the saved diagnostic")
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}

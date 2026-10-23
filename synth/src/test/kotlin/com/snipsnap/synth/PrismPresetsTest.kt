package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrismPresetsTest {
    @Test
    fun `the complete bank gives each voice its approved share`() {
        val presets = PrismPresets.all()
        assertEquals(40, presets.size)
        assertEquals(mapOf(
            PrismVoice.KEY to 8, PrismVoice.PLUCK to 5, PrismVoice.ORGAN to 6,
            PrismVoice.BASS to 7, PrismVoice.VEIL to 8, PrismVoice.GLASS to 6,
        ), presets.groupingBy { it.voice }.eachCount())
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        val auditionIds = presets.map { it.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') }
        assertEquals(auditionIds.size, auditionIds.toSet().size, "every preset has an unambiguous audition filename")
        for (voice in PrismVoice.entries) {
            val group = PrismPresets.forVoice(voice)
            assertEquals(presets.filter { it.voice == voice }, group)
            assertEquals(group.size, group.map { listOf(it.macros - setOf("TUNE", "HOLD"), it.template, it.velocity, it.motion) }.toSet().size,
                "$voice must offer different timbres, not copies moved to a different note or sustain mode")
        }
    }

    @Test
    fun `the original eight recipes remain unchanged and keep their selection indices`() {
        assertEquals(originalPresets, PrismPresets.all().take(originalPresets.size),
            "expanding the bank must preserve the original macros, templates, energy and model")
        for (voice in PrismVoice.entries) {
            val originals = originalPresets.filter { it.voice == voice }
            assertEquals(originals, PrismPresets.forVoice(voice).take(originals.size),
                "$voice's existing preset numbers must still select the same recipes")
        }
    }

    @Test
    fun `every complete saved recipe regenerates clean audio exactly`() {
        for (preset in PrismPresets.all()) {
            val recipe = PadRecipe(preset)
            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            val reopened = assertIs<PrismPatch>(restored.patch)
            assertEquals(recipe, restored, preset.name)
            assertEquals(preset.template, reopened.template, "${preset.name} must retain its full partial model")
            val original = preset.render()
            assertTrue(original.frameCount > 0, "${preset.name} rendered no audio")
            assertTrue(original.samples.all { it.isFinite() && it in -1f..1f }, "${preset.name} rendered invalid audio")
            assertTrue(original.peak() > 0f, "${preset.name} is silent")
            assertContentEquals(original.samples, restored.render().samples,
                "${preset.name} must remain deterministic after its complete pad recipe is saved and reopened")
        }
    }

    @Test
    fun `held bank recipes close their genuine generated cycles throughout the register`() {
        val held = PrismPresets.all().filter { Prism.isLoop(it.macros.getValue("HOLD")) }
        assertTrue(held.isNotEmpty())
        for (preset in held) for (tune in listOf(0f, .5f, 1f)) {
            val rendered = Prism.probe(preset.voice, preset.macros + ("TUNE" to tune), preset.velocity,
                preset.template, normalize = true, keepRaw = false)
            val previous = assertNotNull(rendered.previousCycle)
            assertEquals(rendered.samples.size, rendered.loopFrames)
            assertEquals(rendered.loopFrames, previous.size)
            val seam = Keys.seamError(previous + rendered.samples, rendered.loopFrames)
            assertTrue(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR,
                "${preset.name} at TUNE $tune must close without replacing the preceding generated cycle: $seam")
            var difference = 0.0
            var energy = 0.0
            for (i in previous.indices) {
                val delta = rendered.samples[i] - previous[i].toDouble()
                difference += delta * delta
                energy += previous[i].toDouble() * previous[i]
            }
            val cycleError = difference / energy
            assertTrue(cycleError.isFinite() && cycleError < 1e-5,
                "${preset.name} at TUNE $tune must repeat its entire generated body: $cycleError")
        }
    }

    companion object {
        /** The eight recipes shipped before the expansion, independent of factory defaults. */
        internal val originalPresets: List<PrismPatch> by lazy {
            val glass = PrismTemplate(PrismSpectrum.BELL, PrismContour.RINGING,
                ratioOverrides = listOf(
                    1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f,
                    9.25f, 10.5f, 11.75f, 12f, 13.25f, 14.5f, 15.75f, 16f,
                    17.25f, 18.5f, 19.75f, 20f, 21.25f, 22.5f, 23.75f, 24f,
                    25.25f, 26.5f, 27.75f, 28f, 29.25f, 30.5f, 31.75f, 32f,
                ),
                amplitudeOverrides = listOf(
                    1f, .300176382f, .292255521f, .107195802f, .102828607f, .0985804647f, .0420557298f, .0725277439f,
                    .0253859125f, .012438288f, .0212141424f, .0124744968f, .008466959f, .013283384f, .00644746935f, .0063283206f,
                    .00828130171f, .00356076006f, .00487184804f, .00506419549f, .00218585855f, .00372321438f, .00302078319f, .00153055927f,
                    .00277133007f, .00176342437f, .00119694555f, .00199015765f, .00102390419f, .00099103956f, .00137247855f, .000612293021f,
                ),
            )
            fun p(voice: PrismVoice, name: String, grain: Float, drift: Float, shape: Float,
                  strike: Float, body: Float, hold: Float = 0f): PrismPatch {
                val template = when (voice) {
                    PrismVoice.KEY -> PrismTemplate(PrismSpectrum.ROUNDED, PrismContour.STRUCK)
                    PrismVoice.PLUCK -> PrismTemplate(PrismSpectrum.PLUCKED, PrismContour.PLUCKED)
                    PrismVoice.ORGAN -> PrismTemplate(PrismSpectrum.DRAWBAR, PrismContour.HELD)
                    PrismVoice.BASS -> PrismTemplate(PrismSpectrum.ROOTED, PrismContour.LOW)
                    PrismVoice.GLASS -> glass
                    PrismVoice.VEIL -> PrismTemplate(PrismSpectrum.VEILED, PrismContour.BLOOM)
                }
                return PrismPatch(name, voice, mapOf("TUNE" to .5f, "GRAIN" to grain, "DRIFT" to drift,
                    "SHAPE" to shape, "STRIKE" to strike, "BODY" to body, "HOLD" to hold),
                    velocity = 1f, template = template, model = 1)
            }
            listOf(
                p(PrismVoice.KEY, "Soft Facets", .22f, .28f, .40f, .25f, .68f),
                p(PrismVoice.KEY, "Wooden Spectrum", .36f, .42f, .18f, .45f, .85f),
                p(PrismVoice.PLUCK, "Partial Tines", .48f, .35f, .62f, .75f, .45f),
                p(PrismVoice.ORGAN, "Hollow Register", .28f, .25f, .42f, .30f, .70f),
                p(PrismVoice.BASS, "Root Glass", .32f, .18f, .58f, .58f, .76f),
                p(PrismVoice.VEIL, "Slow Separation", .58f, .80f, .65f, .20f, .55f),
                p(PrismVoice.VEIL, "Amber Veil", .35f, .65f, .24f, .20f, .72f, hold = 1f),
                p(PrismVoice.GLASS, "Small Prism", .70f, .48f, .72f, .62f, .30f),
            )
        }
    }
}

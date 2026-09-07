package com.snipsnap.synth

import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Similar
import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesampleTest {

    @Test
    fun `a rendered THUMP kick returns its own patch`() {
        val macros = mapOf("TUNE" to 0.5f, "SWEEP" to 0.15f, "DECAY" to 0.85f, "CLICK" to 0.5f, "DRIVE" to 0.15f)
        val rendered = Thump.render(ThumpVoice.KICK, macros)
        val match = Desample.nearest(rendered)
        assertEquals(ThumpVoice.KICK, match.patch.voice)
        assertEquals(macros, match.patch.macros, "on the grid, the grid answers exactly")
        assertEquals(0f, match.distance, 1e-5f)
        assertTrue(!match.far)
        assertTrue(match.patch.render().samples.contentEquals(rendered.samples), "and it renders back the same bytes")
    }

    @Test
    fun `an off-grid snare comes back nearer than the grid alone`() {
        val macros = mapOf("TUNE" to 0.62f, "SNAP" to 0.3f, "DECAY" to 0.7f, "TONE" to 0.44f)
        val rendered = Thump.render(ThumpVoice.SNARE, macros)
        val match = Desample.nearest(rendered)
        assertEquals(ThumpVoice.SNARE, match.patch.voice)
        assertTrue(match.distance < 0.08f, "refined close to the source: ${match.distance}")
        // The nearest grid point alone sits further away than the refinement landed.
        val target = FeatureExtractor.extract(rendered)
        val onGrid = Desample.GRID.flatMap { t -> Desample.GRID.flatMap { s -> Desample.GRID.flatMap { d -> Desample.GRID.map { o -> mapOf("TUNE" to t, "SNAP" to s, "DECAY" to d, "TONE" to o) } } } }
            .minOf { Similar.distance(target, FeatureExtractor.extract(Thump.render(ThumpVoice.SNARE, it))) }
        assertTrue(match.distance <= onGrid, "refinement never loses to the grid: ${match.distance} vs $onGrid")
    }

    @Test
    fun `a capture returns a patch within a distance bound, and says when none is near`() {
        for ((name, hit, drumClass) in listOf(
            Triple("kick", DrumSynth.kick(), com.snipsnap.audio.DrumClass.KICK),
            Triple("snare", DrumSynth.snare(), com.snipsnap.audio.DrumClass.SNARE),
            Triple("hat", DrumSynth.closedHat(), com.snipsnap.audio.DrumClass.HAT_CLOSED),
        )) {
            val any = Desample.nearest(hit)
            assertTrue(any.distance < Desample.FAR, "$name: a patch within the bound, got ${any.distance} (${any.patch.voice})")
            val kindred = Desample.nearest(hit, voices = Desample.voicesFor(drumClass))
            assertTrue(kindred.patch.voice in Desample.voicesFor(drumClass), "$name lands on a kindred voice: ${kindred.patch.voice}")
            assertTrue(kindred.distance < Desample.FAR, "$name: kindred and within the bound: ${kindred.distance}")
        }
        assertEquals(ThumpVoice.entries, Desample.voicesFor(com.snipsnap.audio.DrumClass.UNKNOWN))
        // A second of white noise is nobody's drum: the nearest is named far.
        val rnd = java.util.Random(2)
        val hiss = Snip(FloatArray(44_100) { (rnd.nextFloat() * 2f - 1f) * 0.5f }, 1, 44_100)
        val far = Desample.nearest(hiss)
        assertTrue(far.far, "hiss is far from every patch: ${far.distance}")
        assertFailsWith<IllegalArgumentException> { Desample.nearest(Snip(FloatArray(0), 1, 44_100)) }
        assertFailsWith<IllegalArgumentException> { Desample.nearest(hiss, voices = emptyList()) }
        assertTrue(Desample.gridSize > 400, "a few hundred renders: ${Desample.gridSize}")
    }
}

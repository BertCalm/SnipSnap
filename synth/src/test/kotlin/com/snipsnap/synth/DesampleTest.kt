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
        assertEquals(ThumpPatch.ENGINE, match.patch.engine)
        assertEquals(ThumpVoice.KICK.name, match.patch.voiceName)
        assertEquals(macros, match.patch.macros, "on the grid, the grid answers exactly")
        assertEquals(0f, match.distance, 1e-5f)
        assertTrue(!match.far)
        assertTrue(match.patch.render().samples.contentEquals(rendered.samples), "and it renders back the same bytes")
    }

    @Test
    fun `an off-grid snare comes back nearer than the grid alone`() {
        // Not the first combination tried: U3's PUNCH (docs/SYNTH_UPGRADE.md)
        // colours every voice's default render a little, and the original
        // values here turned out to sit on a near-tie between SNARE's and
        // CLAP's own best grid points (a 0.0006 margin - already fragile
        // before PUNCH existed, just not yet visibly so). This combination
        // keeps SNARE the clear nearest voice by a wide margin instead.
        val macros = mapOf("TUNE" to 0.43f, "SNAP" to 0.72f, "DECAY" to 0.39f, "TONE" to 0.94f)
        val rendered = Thump.render(ThumpVoice.SNARE, macros)
        val match = Desample.nearest(rendered)
        assertEquals(ThumpVoice.SNARE.name, match.patch.voiceName)
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
            assertTrue(any.distance < Desample.FAR, "$name: a patch within the bound, got ${any.distance} (${any.patch.engine}/${any.patch.voiceName})")
            val kindred = Desample.nearest(hit, voices = Desample.voicesFor(drumClass))
            val kindredVoice = Desample.Voices.of(kindred.patch.engine, kindred.patch.voiceName)
            assertTrue(
                kindredVoice in Desample.voicesFor(drumClass),
                "$name lands on a kindred voice: ${kindred.patch.engine}/${kindred.patch.voiceName}",
            )
            assertTrue(kindred.distance < Desample.FAR, "$name: kindred and within the bound: ${kindred.distance}")
        }
        assertEquals(Desample.Voices.ALL, Desample.voicesFor(com.snipsnap.audio.DrumClass.UNKNOWN))
        // A slow two-second sweep is nobody's drum: the nearest is named
        // far (measured 0.582 against FAR's 0.45).
        //
        // This used to be a second of white noise, and that stopped being
        // true the moment SKIN joined the roster - hiss now lands on
        // SKIN/RIDE at 0.391, inside the bound. That is the engine being
        // better rather than the guard being weaker: RIDE *is* noise
        // through a dense resonant bank, so a noisy capture answered by a
        // noise voice is a right answer, and it only looked like "nobody's
        // drum" while the roster had nothing made of noise. The sweep is
        // the replacement because it has the widest measured margin of the
        // candidates tried, so it survives the roster growing again.
        val sweep = Snip(
            FloatArray(88_200) { i ->
                val f = 80.0 + 3000.0 * i / 88_200.0
                kotlin.math.sin(2.0 * Math.PI * f * i / 44_100.0).toFloat() * 0.5f
            },
            1, 44_100,
        )
        val far = Desample.nearest(sweep)
        assertTrue(far.far, "a slow sweep is far from every patch: ${far.distance}")

        // And the change itself, pinned rather than remembered: if this
        // ever fails, hiss went back to matching nothing, which means a
        // noise-shaped voice left the roster.
        val rnd = java.util.Random(2)
        val hiss = Snip(FloatArray(44_100) { (rnd.nextFloat() * 2f - 1f) * 0.5f }, 1, 44_100)
        val hissMatch = Desample.nearest(hiss)
        assertTrue(
            !hissMatch.far,
            "hiss no longer matches anything (${hissMatch.distance}) - it found " +
                "${hissMatch.patch.engine}/${hissMatch.patch.voiceName} once SKIN's noise voices were searchable",
        )

        assertFailsWith<IllegalArgumentException> { Desample.nearest(Snip(FloatArray(0), 1, 44_100)) }
        assertFailsWith<IllegalArgumentException> { Desample.nearest(hiss, voices = emptyList()) }
        // Counted rather than built, so asking is free - see gridSize's KDoc.
        assertEquals(
            Desample.Voices.ALL.sumOf { it.points },
            Desample.gridSize,
            "gridSize has to be every voice's point count, or it is a number about nothing",
        )
        assertTrue(Desample.gridSize > 400, "a few hundred renders: ${Desample.gridSize}")
    }

    /**
     * The point of making the search engine-agnostic: an acoustic source
     * comes back as an acoustic voice, not as the nearest analog
     * approximation of one. Rendered from SKIN so the answer is checkable
     * rather than a matter of taste - its own grid point is exact.
     *
     * Not pinned here: that a narrowed search *builds* only the voices it
     * searches. That is the performance property the per-voice grid
     * exists for, and it is deliberately not asserted - the map is
     * private, and a timing assertion would be flaky on CI. The KDoc on
     * [Desample] carries the measurement instead.
     */
    @Test
    fun `a rendered SKIN hit comes back as a SKIN patch`() {
        val macros = mapOf("TUNE" to 0.5f, "SNAP" to 0.85f, "DECAY" to 0.15f)
        val rendered = Skin.render(SkinVoice.SNARE, macros)
        val match = Desample.nearest(rendered)
        assertEquals(SkinPatch.ENGINE, match.patch.engine, "a SKIN source answered by ${match.patch.engine}")
        assertEquals(SkinVoice.SNARE.name, match.patch.voiceName)
        assertEquals(macros, match.patch.macros, "on the grid, the grid answers exactly")
        assertEquals(0f, match.distance, 1e-5f, "its own grid point is exact")
    }
}

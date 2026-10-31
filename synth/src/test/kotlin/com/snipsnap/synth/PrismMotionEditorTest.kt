package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrismMotionEditorTest {
    @Test
    fun `opening the editor leaves all four accepted defaults byte exact`() {
        for (patch in PrismOpeningPresets.all()) {
            val text = patch.toJsonText()
            assertSame(patch, PrismMotionEditor.ensureMotion(patch), patch.name)
            assertEquals(text, PrismMotionEditor.ensureMotion(patch).toJsonText())
            assertFalse(assertNotNull(patch.motion).repeat)
            assertEquals(null, patch.motion!!.sourceId)
        }
    }

    @Test
    fun `a plain custom note gets unmasked template B body A and a finite HOLD without other edits`() {
        val template = PrismTemplate(PrismSpectrum.ROUNDED, PrismContour.HELD,
            ratioOverrides = List(Prism.PARTIALS) { it + 1f },
            amplitudeOverrides = listOf(1f, .71f, .23f),
            attackSeconds = .08f, decaySeconds = 2.1f, loopSeconds = 1.7f)
        val plain = PrismPatch("Custom held source", PrismVoice.KEY,
            Prism.defaults(PrismVoice.KEY) + mapOf("HOLD" to 1f, "TUNE" to .25f, "GRAIN" to .73f,
                "SHAPE" to .82f, "STRIKE" to .27f, "BODY" to .67f), velocity = .31f, template = template)
        val edited = PrismMotionEditor.ensureMotion(plain)
        assertEquals(plain.copy(macros = plain.macros + ("HOLD" to Math.nextDown(Prism.LOOP_THRESHOLD)),
            motion = edited.motion), edited)
        assertEquals(1f, plain.macros.getValue("HOLD"), "the original patch remains unchanged")
        val motion = assertNotNull(edited.motion)
        assertEquals(Prism.amplitudeEndpoint(template), motion.amplitudesB)
        assertEquals(listOf(1f, .71f, .23f), motion.amplitudesB.take(3),
            "GRAIN, SHAPE, STRIKE and BODY must not be baked into B and applied twice")
        assertEquals(Prism.PARTIALS, motion.amplitudesB.size)
        assertEquals(PrismOpeningPresets.bodyAmplitudes(motion.amplitudesB), motion.amplitudesA)
        assertEquals(0f, motion.morph)
        assertEquals(1f, motion.evolve)
        assertEquals(.8f, motion.seconds)
        assertEquals(0f, motion.groupLag)
        assertFalse(motion.repeat)
    }

    @Test
    fun `amount time and repeat edits retain the complete saved gesture and playing energy`() {
        val original = PrismOpeningPresets.all().last().let { it.copy(velocity = .29f,
            motion = it.motion!!.copy(morph = .17f, evolve = .63f, seconds = 1.7f, groupLag = .41f,
                sourceId = "fork-soft-keys")) }
        assertEquals(original.copy(motion = original.motion!!.copy(evolve = .37f)),
            PrismMotionEditor.withAmount(original, .37f))
        assertEquals(original.copy(motion = original.motion!!.copy(seconds = 2.3f)),
            PrismMotionEditor.withTime(original, 2.3f))
        assertEquals(original.copy(motion = original.motion!!.copy(repeat = true)),
            PrismMotionEditor.withRepeat(original, true))
        assertEquals(0f, PrismMotionEditor.withAmount(original, -1f).motion!!.evolve)
        assertEquals(1f, PrismMotionEditor.withAmount(original, 2f).motion!!.evolve)
        assertEquals(original, PrismMotionEditor.withAmount(original, Float.NaN))
        assertEquals(PrismMotionEditor.MIN_SECONDS, PrismMotionEditor.withTime(original, -1f).motion!!.seconds)
        assertEquals(PrismMotionEditor.MAX_SECONDS, PrismMotionEditor.withTime(original, 20f).motion!!.seconds)
        assertEquals(original, PrismMotionEditor.withTime(original, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `repeat preserves a held note and switching OPEN clamps only HOLD`() {
        val original = PrismOpeningPresets.all().first()
        val held = PrismMotionEditor.withRepeat(original, true).let {
            it.copy(macros = it.macros + ("HOLD" to 1f))
        }
        assertSame(held, PrismMotionEditor.ensureMotion(held))
        assertEquals(1f, PrismMotionEditor.withTime(held, 2f).macros.getValue("HOLD"))
        assertEquals(1f, PrismMotionEditor.withAmount(held, .5f).macros.getValue("HOLD"))
        val open = PrismMotionEditor.withRepeat(held, false)
        assertEquals(held.copy(macros = held.macros + ("HOLD" to Math.nextDown(Prism.LOOP_THRESHOLD)),
            motion = held.motion!!.copy(repeat = false)), open)
        assertTrue(Prism.isLoop(held.macros.getValue("HOLD")))
        assertFalse(Prism.isLoop(open.macros.getValue("HOLD")))
        val plainHeld = original.copy(motion = null, macros = original.macros + ("HOLD" to 1f))
        assertEquals(1f, PrismMotionEditor.withRepeat(plainHeld, true).macros.getValue("HOLD"),
            "choosing REPEAT directly does not silently shorten an existing held note")
    }

    @Test
    fun `static destination is a separate finite preview preserving source and carrier identity`() {
        val original = PrismMotionEditor.withRepeat(PrismOpeningPresets.all()[2], true).let {
            it.copy(macros = it.macros + ("HOLD" to 1f), velocity = .42f,
                motion = it.motion!!.copy(morph = .21f, evolve = .71f, seconds = 2.6f,
                    groupLag = .52f, sourceId = "embouchure-relaxed-bow"))
        }
        val preview = PrismMotionEditor.staticDestination(original)
        assertEquals(original.copy(macros = original.macros + ("HOLD" to Math.nextDown(Prism.LOOP_THRESHOLD)),
            motion = original.motion!!.copy(morph = 1f, evolve = 0f, repeat = false)), preview)
        assertTrue(original.motion!!.repeat)
        assertEquals(.71f, original.motion!!.evolve)
        assertEquals(preview, PrismPatch.fromJsonText(preview.toJsonText()))
    }
}

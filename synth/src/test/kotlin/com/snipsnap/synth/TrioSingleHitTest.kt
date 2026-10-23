package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Single companion excitation and saved model compatibility, without a listening verdict. */
class TrioSingleHitTest {
    private val wives = setOf(TrioActor.TRES, TrioActor.BERIMBAU, TrioActor.CHARANGO)
    private val companions = setOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)
    private val baseline = mapOf("TOUCH" to .55f, "SIDES" to (1f / 6f), "MOTION" to 0f,
        "DRAMA" to 1f, "SPACE" to .35f, "DAMP" to .48f)
    private val published = TrioConfig(seed = 47010, model = 2)
    private val singleHit = published.copy(model = 3)

    private fun scene(config: TrioConfig, macros: Map<String, Float> = baseline,
        midi: Int = 60, velocity: Float = 1f) =
        Trio.renderProbe(TrioVoice.ENSEMBLE, macros, midi, velocity, config)

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun meanSquare(samples: FloatArray, fromSeconds: Float, toSeconds: Float): Double {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceIn(0, samples.lastIndex)
        val to = (toSeconds * Dsp.RATE).toInt().coerceIn(from + 1, samples.size)
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sum / (to - from)
    }

    /** Compare late stored energy against the same player's initial attack, removing gain. */
    private fun lateRetention(probe: TrioProbe, actor: TrioActor): Double {
        val onset = probe.events.first { it.actor == actor }.timeSeconds
        val samples = probe.actors.getValue(actor)
        val attack = meanSquare(samples, onset + .03f, onset + .18f)
        assertTrue(attack > 0.0 && attack.isFinite(), "$actor has no finite attack energy")
        return meanSquare(samples, 2.2f, 2.8f) / attack
    }

    @Test
    fun `published model two scenes retain their archived float audio`() {
        // Native float hashes from the published model-2 manifest, round
        // trio-870ed66085d5-ea6c3e4579f0, same C4/velocity/seed and full controls.
        val canaries = mapOf(
            .5f to "725ea6b3a59759da661bbdeb2936f86804c2829c05343240692d1eddc4a58b11",
            .85f to "6ed77168819fe2a8e3f49f27b4112e0a7f0e6e6b0c16a09ed5bce5e5fbf47d6e",
            1f to "6d8788d1a85b3bd19966351e229ddb5a71310de16dbd880fcc8e7cd583d495ec",
        )
        for ((drama, expected) in canaries) {
            assertEquals(expected, hash(scene(published, baseline + ("DRAMA" to drama)).samples),
                "saved model 2 at DRAMA $drama changed")
        }
    }

    @Test
    fun `all wife sources and gestures are unchanged by the single companion revision`() {
        val rich = baseline + mapOf("TOUCH" to .72f, "SIDES" to .42f,
            "MOTION" to .7f, "SPACE" to .8f, "DAMP" to .3f)
        val moving = published.copy(seed = -91827, tempoBpm = 140f, beats = 3,
            press = .8f, tremoloHz = 17f, strumMs = 23f)
        for ((macros, config) in listOf(baseline to published, rich to moving)) {
            for (drama in listOf(.5f, .85f, 1f)) {
                val controls = macros + ("DRAMA" to drama)
                val old = scene(config, controls, midi = 57, velocity = .7f)
                val revised = scene(config.copy(model = 3), controls, midi = 57, velocity = .7f)
                for (actor in wives) {
                    assertContentEquals(old.actors.getValue(actor), revised.actors.getValue(actor),
                        "$actor source changed at DRAMA $drama, config $config")
                    assertEquals(old.events.filter { it.actor == actor }, revised.events.filter { it.actor == actor },
                        "$actor gesture changed at DRAMA $drama, config $config")
                }
            }
        }
    }

    @Test
    fun `each companion strikes once independently of phrase length and course strum spacing`() {
        val controls = baseline + mapOf("SIDES" to .36f, "MOTION" to .8f)
        val config = singleHit.copy(tempoBpm = 200f, beats = 1)
        val reference = scene(config, controls)
        for (beats in listOf(1, 2, 4)) {
            val probe = scene(config.copy(beats = beats), controls)
            for (actor in companions) {
                val events = probe.events.filter { it.actor == actor }
                assertEquals(1, events.size, "$actor retriggers in a $beats-beat scene")
                assertTrue(events.single().energy > 0f, "$actor has an empty strike")
                assertEquals(reference.events.filter { it.actor == actor }, events,
                    "$actor entrance depends on the later phrase length")
                assertContentEquals(reference.actors.getValue(actor), probe.actors.getValue(actor),
                    "$actor has additional excitation in a $beats-beat scene")
            }
        }
        // Source taps do not expose individual strings. Invariance under the full
        // saved strum-spacing range catches course stagger driven by that control.
        for (spacing in listOf(1f, 30f)) {
            val probe = scene(config.copy(strumMs = spacing), controls)
            for (actor in companions) {
                assertContentEquals(reference.actors.getValue(actor), probe.actors.getValue(actor),
                    "$actor single strike still depends on strum spacing $spacing ms")
            }
        }
    }

    @Test
    fun `default single strikes retain more late energy than the previous answer pattern then decay`() {
        val old = scene(published)
        val revised = scene(singleHit)
        for (actor in companions) {
            val retained = lateRetention(revised, actor)
            val previous = lateRetention(old, actor)
            assertTrue(retained.isFinite() && retained > 0.0, "$actor has no finite late ring")
            assertTrue(retained > previous,
                "$actor late attack-normalized energy did not increase: $retained versus $previous")
            assertTrue(retained < 1.0, "$actor grows after its single strike rather than decaying")
            assertEquals(1, revised.events.count { it.actor == actor }, "$actor sustains by retriggering")
        }
    }

    @Test
    fun `DAMP changes the single strike decay without scheduling another excitation`() {
        val open = scene(singleHit, baseline + ("DAMP" to 0f))
        val damped = scene(singleHit, baseline + ("DAMP" to 1f))
        for (actor in companions) {
            assertEquals(open.events.filter { it.actor == actor }, damped.events.filter { it.actor == actor },
                "$actor DAMP changes its excitation score")
            assertEquals(1, damped.events.count { it.actor == actor })
            val openTail = lateRetention(open, actor)
            val dampedTail = lateRetention(damped, actor)
            assertTrue(openTail.isFinite() && dampedTail.isFinite() && openTail > dampedTail,
                "$actor DAMP does not shorten attack-normalized decay: $openTail versus $dampedTail")
        }
    }

    @Test
    fun `single companion rings remain finite within headroom at extreme roots and dense wife gestures`() {
        val dense = singleHit.copy(tempoBpm = 200f, beats = 4, press = 1f,
            tremoloHz = 25f, strumMs = 30f)
        val controls = baseline + mapOf("TOUCH" to 1f, "DRAMA" to 1f,
            "SIDES" to .42f, "MOTION" to 1f, "SPACE" to 1f, "DAMP" to 0f)
        for (midi in listOf(Trio.MIN_MIDI, Trio.MAX_MIDI)) {
            val probe = scene(dense, controls, midi)
            assertTrue(probe.samples.all { it.isFinite() } && probe.samples.maxOf { abs(it) } < .99f,
                "dense single-hit scene at MIDI $midi exceeded raw headroom")
            for (actor in companions) {
                val tap = probe.actors.getValue(actor)
                assertTrue(tap.all { it.isFinite() } && tap.any { it != 0f },
                    "$actor ring at MIDI $midi is nonfinite or silent")
                assertTrue(tap.maxOf { abs(it) } < .99f, "$actor ring at MIDI $midi exceeded headroom")
                assertEquals(1, probe.events.count { it.actor == actor })
            }
        }
    }

    @Test
    fun `saved model two patches recipes edits and velocity variants retain model two`() {
        val config = published.copy(seed = -91827, tempoBpm = 120f, beats = 2,
            press = .7f, tremoloHz = 16f, strumMs = 4f)
        val patch = TrioPatch("Before Single Hit", TrioVoice.ENSEMBLE,
            baseline + mapOf("DRAMA" to .85f, "MOTION" to .4f),
            midi = 62, velocity = .8f, config = config)
        val restored = assertIs<TrioPatch>(Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch, restored)
        assertEquals(2, restored.model)
        val recipe = PadRecipe.fromJsonText(PadRecipe(patch).toJsonText())
        val recipePatch = assertIs<TrioPatch>(recipe.patch)
        assertEquals(config, recipePatch.config)
        assertContentEquals(patch.render().samples, recipe.render().samples)
        val edited = assertIs<TrioPatch>(Patches.edited(recipePatch, "Edited Original",
            recipePatch.macros + ("SPACE" to .7f)))
        assertEquals(2, edited.model)
        assertEquals(config, edited.config)
        assertContentEquals(patch.copy(velocity = .35f).render().samples,
            Velocity.atVelocity(recipePatch, .35f).samples,
            "native velocity changed the saved model-2 renderer")
        assertEquals(config, recipePatch.config)
        assertEquals(.8f, recipePatch.velocity)
    }
}

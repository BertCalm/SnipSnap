package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlapHeldTest {
    private fun patch(topology: OverlapTopology = OverlapTopology.TREFOIL, midi: Int = 48, velocity: Float = .8f) =
        OverlapPatch("Held network", topology, OverlapHeld.defaults(topology), midi, velocity,
            playback = OverlapPlayback.HELD)

    @Test
    fun `held settings roundtrip and edit while historical finite recipes retain their shape`() {
        val held = patch().copy(releaseSeconds = 1.2f)
        val restored = Patches.fromJsonText(held.toJsonText()) as OverlapPatch
        assertEquals(held, restored)
        val edited = Patches.edited(restored, "MY NETWORK", restored.macros + ("RING" to .3f)) as OverlapPatch
        assertEquals(held.copy(name = "MY NETWORK", macros = held.macros + ("RING" to .3f)), edited)
        assertEquals(held, PadRecipe.fromJsonText(PadRecipe(held).toJsonText()).patch)
        for (model in 1..2) {
            val finite = OverlapPatch("Finite", OverlapTopology.MIRROR, mapOf("GLOW" to .1f), 55, .61f, model)
            val obj = finite.toJsonValue().entries
            assertEquals(setOf("version", "engine", "voice", "name", "macros", "midi", "velocity", "model"), obj.keys)
            assertEquals(finite, OverlapPatch.fromJsonText(finite.toJsonText()))
            assertEquals(OverlapPlayback.FINITE, finite.playback)
            assertFalse(OverlapHeld.supports(finite))
        }
        val legacy = patch().copy(playback = OverlapPlayback.FINITE, model = 1)
        val missingModel = JsonValue.Obj(legacy.toJsonValue().entries - "model")
        val restoredLegacy = OverlapPatch.fromJsonValue(missingModel) as OverlapPatch
        assertEquals(1, restoredLegacy.model)
        assertEquals(OverlapPlayback.FINITE, restoredLegacy.playback)
        // A partial held map fills only from the supported steady defaults.
        val partial = patch().copy(macros = mapOf("RING" to .4f))
        assertTrue(OverlapHeld.supports(partial))
        assertEquals(partial, OverlapPatch.fromJsonText(partial.toJsonText()))
        assertEquals(listOf("RING"), OverlapHeld.macrosFor(OverlapTopology.SELF).map { it.name })
        for (topology in OverlapTopology.entries.filter { it != OverlapTopology.SELF })
            assertEquals(listOf("KNOT", "RING"), OverlapHeld.macrosFor(topology).map { it.name })
    }

    @Test
    fun `unsupported held controls versions models and release values are refused`() {
        val p = patch()
        assertFailsWith<IllegalArgumentException> { p.copy(model = 1) }
        assertFailsWith<IllegalArgumentException> { p.copy(heldVersion = 2) }
        for ((control, value) in listOf("PULSE" to .1f, "GLOW" to .1f, "LOCK" to .1f, "HOLD" to .9f)) {
            assertFailsWith<IllegalArgumentException> { p.copy(macros = p.macros + (control to value)) }
        }
        for (value in listOf(.09f, 1.51f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { p.copy(releaseSeconds = value) }
        }
        fun refuses(field: String, value: JsonValue) {
            assertFailsWith<JsonException> {
                OverlapPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries + (field to value)))
            }
        }
        refuses("playback", JsonValue.Str("DRIFT"))
        refuses("playback", JsonValue.Null)
        refuses("heldVersion", JsonValue.Num(2.0))
        refuses("heldVersion", JsonValue.Null)
        refuses("releaseSeconds", JsonValue.Num(.0999999999))
        refuses("releaseSeconds", JsonValue.Num(1.5000000001))
        refuses("velocity", JsonValue.Num(-1e-100))
        refuses("velocity", JsonValue.Num(1.0000000001))
        val roundedRing = JsonValue.Obj(p.toJsonValue().entries + ("macros" to JsonValue.Obj(
            p.toJsonValue().entries.getValue("macros").obj() + ("RING" to JsonValue.Num(1.0000000001)))))
        assertFailsWith<JsonException> { OverlapPatch.fromJsonValue(roundedRing) }
        val tinyGlow = JsonValue.Obj(p.toJsonValue().entries + ("macros" to JsonValue.Obj(
            p.toJsonValue().entries.getValue("macros").obj() + ("GLOW" to JsonValue.Num(1e-100)))))
        assertFailsWith<JsonException> { OverlapPatch.fromJsonValue(tinyGlow) }
    }

    @Test
    fun `root layout retains every selected note and three native roots at both register edges`() {
        for (midi in Overlap.MIN_MIDI..Overlap.MAX_MIDI) {
            val roots = OverlapHeld.midis(patch(midi = midi))
            assertEquals(3, roots.size)
            assertTrue(midi in roots)
            assertTrue(roots.all { it in Overlap.MIN_MIDI..Overlap.MAX_MIDI })
            assertTrue(roots.zipWithNext().all { (a, b) -> b - a == 12 })
        }
        assertEquals(listOf(36, 48, 60), OverlapHeld.midis(patch()))
        assertEquals(listOf(24, 36, 48), OverlapHeld.midis(patch(midi = 24)))
        assertEquals(listOf(60, 72, 84), OverlapHeld.midis(patch(midi = 84)))
        assertFailsWith<IllegalArgumentException> { OverlapHeld.midis(patch().copy(playback = OverlapPlayback.FINITE)) }
    }

    @Test
    fun `phone sample retains the approved prototype body and genuine neighboring seams`() {
        OverlapHeld.clearCache()
        try {
            val p = patch()
            val prototype = OverlapHeldPrototype.renderLoopMeasured(p.topology, p.macros, p.midi, p.velocity,
                OverlapHeld.PERIOD_SECONDS)
            val held = OverlapHeld.render(p)
            val length = prototype.snip.frameCount
            assertEquals(length, held.loopStartFrame)
            assertEquals(2 * length, held.loopEndExclusive)
            assertEquals(held.snip.frameCount, held.loopEndExclusive)
            assertEquals(1.0, held.diagnostics.gain, "The accepted velocity-.8 body needs no new gain")
            assertTrue(held.snip.samples[0] == 0f, "The sampler onset must start at silence")
            assertContentEquals(prototype.snip.samples, held.snip.samples.copyOfRange(length, 2 * length))
            val ramp = (OverlapHeld.ONSET_SECONDS * Dsp.RATE).toInt()
            assertContentEquals(prototype.snip.samples.copyOfRange(ramp, length), held.snip.samples.copyOfRange(ramp, length))
            assertTrue(Keys.seamError(prototype.seamContext.copyOfRange(0, 2 * length), length) < Keys.MAX_SEAM_ERROR)
            assertTrue(Keys.seamError(prototype.seamContext.copyOfRange(length, 3 * length), length) < Keys.MAX_SEAM_ERROR)
            assertTrue(Keys.seamError(held.snip.samples, length) < Keys.MAX_SEAM_ERROR)
            assertTrue(held.diagnostics.sourceDiagnostics.converged)
            assertTrue(held.diagnostics.outputPeak in .001..0.900001)
        } finally { OverlapHeld.clearCache() }
    }

    @Test
    fun `cache owns returned audio and diagnostics and reuses only identical source settings`() {
        OverlapHeld.clearCache()
        try {
            val p = patch()
            val first = OverlapHeld.render(p)
            val expected = first.snip.samples.copyOf()
            val expectedDelay = first.diagnostics.sourceDiagnostics.delaySamples.first()
            first.snip.samples.fill(0f)
            (first.diagnostics.sourceDiagnostics.delaySamples as MutableList<Double>)[0] = -1.0
            val renamed = OverlapHeld.render(p.copy(name = "MY BODY", releaseSeconds = 1.2f))
            assertContentEquals(expected, renamed.snip.samples, "A caller sample edit must not change the cached held capture")
            assertEquals(expectedDelay, renamed.diagnostics.sourceDiagnostics.delaySamples.first())
            assertEquals(1, OverlapHeld.cacheStats().entries)
            assertEquals(1L, OverlapHeld.cacheStats().hits)
            assertEquals(1L, OverlapHeld.cacheStats().misses)
            val changes = listOf(
                p.copy(macros = p.macros + ("KNOT" to .5f)),
                p.copy(macros = p.macros + ("RING" to .2f)),
                p.copy(velocity = .3f), p.copy(midi = 49), p.copy(topology = OverlapTopology.MIRROR),
            )
            for (changed in changes) {
                val rendered = OverlapHeld.render(changed)
                assertFalse(expected.contentEquals(rendered.snip.samples), "Cache reused a changed source: ${changed.toJsonText()}")
            }
            assertEquals(6L, OverlapHeld.cacheStats().misses)
        } finally { OverlapHeld.clearCache() }
    }

    @Test
    fun `completed captures respect memory limits and least recently used eviction`() {
        OverlapHeld.clearCache()
        try {
            // Silence converges quickly but still constructs real native captures;
            // this checks the actual retained arrays and eviction without broad timbre sweeps.
            val p = patch(OverlapTopology.SELF, velocity = 0f)
            val count = OverlapHeld.CACHE_MAX_ENTRIES + 1
            for (midi in 24 until 24 + count) {
                val held = OverlapHeld.render(p, midi)
                assertTrue(held.snip.samples.all { it == 0f })
                assertTrue(OverlapHeld.cacheStats().entries <= OverlapHeld.CACHE_MAX_ENTRIES)
                assertTrue(OverlapHeld.cacheStats().bytes <= OverlapHeld.CACHE_MAX_BYTES)
            }
            assertEquals(count.toLong(), OverlapHeld.cacheStats().misses)
            OverlapHeld.render(p, 24)
            assertEquals(count + 1L, OverlapHeld.cacheStats().misses, "The oldest source should have been evicted")
        } finally { OverlapHeld.clearCache() }
    }

    @Test
    fun `cancellation and clearing in flight cannot publish an unwanted capture`() {
        OverlapHeld.clearCache()
        try {
            val p = patch()
            assertFailsWith<CancellationException> { OverlapHeld.render(p, cancelled = { true }) }
            assertEquals(0, OverlapHeld.cacheStats().entries)
            var checks = 0
            assertFailsWith<CancellationException> { OverlapHeld.render(p, cancelled = { ++checks > 300 }) }
            assertTrue(checks > 300, "Cold synthesis must observe cancellation beyond entry validation")
            assertEquals(0, OverlapHeld.cacheStats().entries)
            // Cancel exactly when a completed entry becomes visible. The caller
            // must receive no capture, and the publication must be rolled back.
            assertFailsWith<CancellationException> {
                OverlapHeld.render(p, cancelled = { OverlapHeld.cacheStats().entries > 0 })
            }
            assertEquals(0, OverlapHeld.cacheStats().entries)
            checks = 0
            val rendered = OverlapHeld.render(p, cancelled = {
                if (++checks == 10) OverlapHeld.clearCache()
                false
            })
            assertTrue(rendered.snip.peak() > .001f)
            assertEquals(0, OverlapHeld.cacheStats().entries, "A cache clear must invalidate an already running solve")
            OverlapHeld.render(p)
            val before = OverlapHeld.cacheStats()
            assertFailsWith<CancellationException> { OverlapHeld.render(p, cancelled = { true }) }
            assertEquals(before, OverlapHeld.cacheStats(), "Cancellation is checked before touching a hit")
        } finally { OverlapHeld.clearCache() }
    }
}

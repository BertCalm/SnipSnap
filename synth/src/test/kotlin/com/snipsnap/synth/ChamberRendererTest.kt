package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ChamberRendererTest {
    private fun patch() = ChamberPatch("test", ChamberVoice.HYBRID, Chamber.defaults(ChamberVoice.HYBRID))
    private fun snip(value: Float = .25f) = Snip(floatArrayOf(value, -.5f), 1, Chamber.RATE)

    @Test fun `completed recordings stay private across preview and pad edits`() {
        var renders = 0
        val renderer = ChamberRenderer(8) { _, _ -> renders++; snip() }
        val first = renderer.render(patch())
        first.samples[0] = 99f
        val second = renderer.render(patch().copy(name = "renamed"))
        assertEquals(.25f, second.samples[0])
        second.samples[0] = -99f
        val third = renderer.render(patch())
        assertEquals(.25f, third.samples[0])
        assertNotSame(second.samples, third.samples)
        assertEquals(1, renders)
        assertEquals(ChamberRenderer.Stats(2, 1, 1, 2), renderer.stats())
    }

    @Test fun `every sonic recipe input separates cache entries`() {
        val base = patch()
        val c = base.configuration
        val variants = listOf(
            base,
            base.copy(voice = ChamberVoice.AQUARIUM),
            base.copy(macros = base.macros + ("STRIKE" to .7f)),
            base.copy(macros = base.macros + ("BALANCE" to .7f)),
            base.copy(macros = base.macros + ("MATERIAL" to .7f)),
            base.copy(macros = base.macros + ("DIVIDE" to .7f)),
            base.copy(macros = base.macros + ("PLACEMENT" to .7f)),
            base.copy(midi = 48), base.copy(velocity = .3f), base.copy(seed = Long.MAX_VALUE),
            base.copy(model = 1), base.copy(model = 2),
            base.copy(configuration = c.copy(dimensions = c.dimensions.copy(width = 9f))),
            base.copy(configuration = c.copy(listener = c.listener.copy(x = .55f))),
            base.copy(configuration = c.copy(sources = c.sources.copy(membrane = c.sources.membrane.copy(x = .16f)))),
            base.copy(configuration = c.copy(targets = c.targets.copy(ceiling = .9f))),
            base.copy(configuration = c.copy(splitHeight = .6f)),
            base.copy(configuration = c.copy(materialMode = ChamberMaterialMode.MACRO_LAYOUT)),
            base.copy(configuration = c.copy(tuningRatios = ChamberTuningRatios(bar = 2f))),
            base.copy(configuration = c.copy(instrumentEnabled = ChamberInstrumentEnabled(triangle = false))),
        )
        var renders = 0
        val renderer = ChamberRenderer(100) { _, _ -> snip((++renders).toFloat()) }
        variants.forEachIndexed { index, recipe ->
            assertEquals((index + 1).toFloat(), renderer.render(recipe).samples[0])
        }
        variants.forEachIndexed { index, recipe ->
            assertEquals((index + 1).toFloat(), renderer.render(recipe).samples[0])
        }
        assertEquals(variants.size, renders)
        assertEquals(variants.size, renderer.stats().entries)
    }

    @Test fun `partial defaults and a mutable caller map use an owned resolved recipe`() {
        val macros = linkedMapOf("STRIKE" to .5f)
        val p = patch().copy(macros = macros)
        var renders = 0
        val renderer = ChamberRenderer(8) { request, _ ->
            renders++
            macros["STRIKE"] = .9f
            snip(request.macros.getValue("STRIKE"))
        }
        assertEquals(.5f, renderer.render(p).samples[0])
        assertEquals(.5f, renderer.render(patch()).samples[0])
        assertEquals(1, renders)
        assertEquals(.9f, renderer.render(p).samples[0])
        assertEquals(2, renders)
    }

    @Test fun `sample budget evicts least recently used recordings`() {
        var renders = 0
        val renderer = ChamberRenderer(4) { _, _ -> snip((++renders).toFloat()) }
        val a = patch()
        val b = a.copy(seed = 1)
        val c = a.copy(seed = 2)
        assertEquals(1f, renderer.render(a).samples[0])
        assertEquals(2f, renderer.render(b).samples[0])
        assertEquals(1f, renderer.render(a).samples[0])
        assertEquals(3f, renderer.render(c).samples[0])
        assertEquals(1f, renderer.render(a).samples[0])
        assertEquals(4f, renderer.render(b).samples[0])
        assertEquals(4, renderer.stats().samples)
        assertEquals(2, renderer.stats().entries)
        val disabled = ChamberRenderer(0) { _, _ -> snip() }
        disabled.render(a)
        assertEquals(0, disabled.stats().entries)
        val tooSmall = ChamberRenderer(1) { _, _ -> snip() }
        tooSmall.render(a)
        assertEquals(0, tooSmall.stats().samples)
    }

    @Test fun `cancelled and failed work cannot publish a recording`() {
        var cancel = false
        var renders = 0
        val renderer = ChamberRenderer(8) { _, _ ->
            renders++
            cancel = true
            snip()
        }
        assertFailsWith<CancellationException> { renderer.render(patch()) { true } }
        assertEquals(0, renders)
        assertFailsWith<CancellationException> { renderer.render(patch()) { cancel } }
        assertEquals(1, renders)
        assertEquals(0, renderer.stats().entries)
        var fail = true
        val retry = ChamberRenderer(8) { _, _ ->
            if (fail) error("test failure") else snip()
        }
        assertFailsWith<IllegalStateException> { retry.render(patch()) }
        assertEquals(0, retry.stats().entries)
        fail = false
        retry.render(patch())
        assertEquals(1, retry.stats().entries)
    }

    @Test fun `clearing invalidates a render already in flight`() {
        lateinit var renderer: ChamberRenderer
        renderer = ChamberRenderer(8) { _, _ -> renderer.clear(); snip() }
        renderer.render(patch())
        assertEquals(0, renderer.stats().entries)
        assertEquals(0, renderer.stats().samples)
    }

    @Test fun `warm cache hits still respect cancellation`() {
        val renderer = ChamberRenderer(8) { _, _ -> snip() }
        renderer.render(patch())
        assertFailsWith<CancellationException> { renderer.render(patch()) { true } }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { renderer.render(patch()) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun `production rendering stops cooperatively during the frame loop`() {
        var checks = 0
        val failure = assertFailsWith<CancellationException> {
            Chamber.render(ChamberVoice.AQUARIUM, cancelled = { ++checks >= 4 })
        }
        assertTrue(failure.message.orEmpty().contains("CHAMBER"))
        assertEquals(4, checks)
    }

    @Test fun `cache stores the actual production recording unchanged`() {
        val p = patch()
        val renderer = ChamberRenderer()
        val direct = p.render()
        val fresh = renderer.render(p)
        val hit = renderer.render(p)
        assertContentEquals(direct.samples, fresh.samples)
        assertContentEquals(direct.samples, hit.samples)
        assertEquals(1L, renderer.stats().hits)
    }
}

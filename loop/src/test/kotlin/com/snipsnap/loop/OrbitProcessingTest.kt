package com.snipsnap.loop

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.file.Files
import kotlin.test.*

class OrbitProcessingTest {
    private val set = OrbitSet(listOf(
        Orbit("A", 16, SnipOrbit("a.wav")), Orbit("B", 16, SnipOrbit("b.wav")),
    ), 120f, 48000)
    private val source = object : SampleSource {
        override fun loop(sampleFile: String) = Snip(FloatArray(96000) { if (sampleFile == "a.wav") .75f else .5f }, 1, 48000)
        override fun pad(kit: String, slot: Int): Snip? = null
    }
    private val bank = OrbitBank.prepare(set, source)
    private class Decay : OrbitEngine.OutputProcessor {
        var history = 0f
        override fun process(block: FloatArray, bpm: Float, restart: Boolean) {
            for (i in block.indices.step(2)) {
                history = .9f * history + .1f * block[i]
                block[i] = history; block[i + 1] = -history
            }
        }
    }

    @Test fun `processor receives the whole final sum including dry overs and precedes output`() {
        val dry = OrbitEngine.render(set, bank, 4097)
        val identity = OrbitEngine.render(set, bank, 4097, processor = OrbitEngine.OutputProcessor { _, _, _ -> })
        assertContentEquals(dry.samples, identity.samples)
        assertTrue(dry.samples.max() > 1f)
        val heard = mutableListOf<Float>()
        val sink = object : AudioSink {
            override val sampleRate = 48000
            override val channels = 2
            override fun write(block: FloatArray) { heard.addAll(block.toList()) }
            override fun close() {}
        }
        OrbitEngine(set, bank, sink, 37, processor = Decay()).runFor(4)
        val bounced = OrbitEngine.render(set, bank, 37 * 4, 37, processor = Decay())
        assertContentEquals(heard.toFloatArray(), bounced.samples)
        assertTrue(bounced.samples.indices.step(2).all { bounced.samples[it] == -bounced.samples[it + 1] })
    }

    @Test fun `finite endpoint feeds zero into the processor at the exact frame inside a block`() {
        // A still-sounding pattern voice exercises the final block's cut;
        // a snip ring already bounds its own writes to the source interval.
        val held = OrbitSet(listOf(Orbit("HELD", 16, PatternOrbit("K", listOf(OrbitHit(0, 1))))), 120f, 48000)
        val heldBank = OrbitBank.prepare(held, object : SampleSource {
            override fun loop(sampleFile: String): Snip? = null
            override fun pad(kit: String, slot: Int) = Snip(FloatArray(96000) { 1.25f }, 1, 48000)
        })
        for (block in listOf(1, 37, 256, 2048)) {
            val rendered = OrbitEngine.render(held, heldBank, 1025, block, processor = Decay(), tailFrames = 123)
            assertEquals(1148 * 2, rendered.samples.size)
            var history = 0f
            for (frame in 0 until 1148) {
                history = .9f * history + if (frame < 1025) .1f * 1.25f else 0f
                assertEquals(history, rendered.samples[2 * frame], "block $block frame $frame")
                assertEquals(-history, rendered.samples[2 * frame + 1])
            }
        }
    }

    @Test fun `tempo adoption and rewind reach the processor without resetting ordinary edits`() {
        val calls = mutableListOf<Pair<Float, Boolean>>()
        val sink = object : AudioSink {
            override val sampleRate = 48000; override val channels = 2
            override fun write(block: FloatArray) {}; override fun close() {}
        }
        val engine = OrbitEngine(set, bank, sink, 37, processor = OrbitEngine.OutputProcessor { _, bpm, restart -> calls += bpm to restart })
        engine.runFor(2)
        engine.apply(OrbitEngine.Prepared(set.copy(bpm = 90f), bank))
        engine.runFor(1)
        engine.rewind(); engine.runFor(1)
        assertEquals(listOf(120f to false, 120f to false, 90f to false, 90f to true), calls)
        assertEquals(37, engine.position())
    }

    @Test fun `bounce refuses combined frame overflow and a negative tail before allocation`() {
        assertFailsWith<IllegalArgumentException> { OrbitEngine.render(set, bank, OrbitEngine.MAX_RENDER_FRAMES, tailFrames = 1) }
        assertFailsWith<IllegalArgumentException> { OrbitEngine.render(set, bank, 1, tailFrames = -1) }
    }

    @Test fun `old sets stay dry and optional routing and tail fields round trip strictly`() {
        val dir = Files.createTempDirectory("orbit-fx").toFile()
        try {
            val old = OrbitStore.save(set, dir)
            assertFalse("aquariumFromKit" in Json.parse(old.readText()).obj())
            assertFalse(OrbitStore.load(dir).aquariumFromKit)
            val on = set.copy(aquariumFromKit = true, aquariumTailSeconds = 10f)
            OrbitStore.save(on, dir)
            assertEquals(on, OrbitStore.load(dir))
            val obj = Json.parse(old.readText()).obj() + ("aquariumFromKit" to JsonValue.Num(1.0))
            old.writeText(Json.write(JsonValue.Obj(obj)))
            assertFails { OrbitStore.load(dir) }
            assertFailsWith<IllegalArgumentException> { set.copy(aquariumTailSeconds = Float.NaN) }
            assertFailsWith<IllegalArgumentException> { set.copy(aquariumTailSeconds = 31f) }
        } finally { dir.deleteRecursively() }
    }
}

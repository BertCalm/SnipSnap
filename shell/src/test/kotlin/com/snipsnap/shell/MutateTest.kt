package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MutateTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mutate").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    /** A decaying sine — pitched, distinct, deterministic. */
    private fun tone(hz: Double, seconds: Float, amp: Float = 0.5f): Snip =
        Snip(
            FloatArray((seconds * rate).toInt()) { i ->
                (amp * Math.sin(2.0 * Math.PI * hz * i / rate) *
                    Math.exp(-i / (0.4 * rate))).toFloat()
            },
            1, rate,
        )

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, tone(100.0, 0.5f), DrumClass.KICK)
        m.assign(2, tone(3000.0, 0.8f), DrumClass.SNARE)
        m.save()
        return m
    }

    private fun rms(samples: FloatArray, from: Int, to: Int): Double {
        var acc = 0.0
        var n = 0
        for (i in from until minOf(to, samples.size)) {
            acc += samples[i] * samples[i].toDouble()
            n++
        }
        return Math.sqrt(acc / n.coerceAtLeast(1))
    }

    /** Amplitude of [hz] inside the frames — a tiny Goertzel-style probe. */
    private fun toneAmp(snip: Snip, hz: Double, frames: Int): Double {
        var re = 0.0
        var im = 0.0
        val n = minOf(frames, snip.frameCount)
        for (f in 0 until n) {
            val v = snip.samples[f * 2].toDouble()
            val ph = 2.0 * Math.PI * hz * f / rate
            re += v * Math.cos(ph)
            im += v * Math.sin(ph)
        }
        return 2.0 * Math.sqrt(re * re + im * im) / n
    }

    @Test
    fun `splice - the pad's attack, the parent's body`() {
        val m = model("Splice")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val before = padFile.readBytes()

        val outcome = Mutate.apply(
            m, 1,
            listOf(Mutate.Source("Splice:A02", WavReader.read(File(m.kitDir, m.pad(2)!!.sampleFile)))),
            Mutate.Mode.SPLICE,
        )
        m.save()
        val out = WavReader.read(padFile)

        // Before the split: the 100 Hz kick owns the sound; the 3 kHz parent
        // is absent. Past the handover it flips.
        val split = Mutate.DEFAULT_SPLICE_MS * rate / 1000
        val head = Snip(out.samples.copyOfRange(0, split * 2), 2, rate)
        assertTrue(toneAmp(head, 100.0, split) > 10 * toneAmp(head, 3000.0, split), "the attack is the pad's")
        val tailStart = split + (Mutate.FADE_MS * rate / 1000) * 2
        val tail = Snip(out.samples.copyOfRange(tailStart * 2, out.samples.size), 2, rate)
        assertTrue(
            toneAmp(tail, 3000.0, 4096) > 10 * toneAmp(tail, 100.0, 4096),
            "the body is the parent's",
        )

        // Recipe + parent stamp ride the pad; undo is byte-identical.
        assertTrue(outcome.pad.recipe!!.entries.containsKey("mutate"))
        assertEquals("Splice:A02", outcome.pad.source["mutatedWith"])
        Mutate.undo(m, 1)
        m.save()
        assertTrue(padFile.readBytes().contentEquals(before), "undo restores the original")
        assertEquals(null, m.pad(1)!!.source["mutatedWith"])
    }

    @Test
    fun `split - the pad's lows, the parent's highs`() {
        val m = model("Split")
        Mutate.apply(
            m, 1,
            listOf(Mutate.Source("hat", WavReader.read(File(m.kitDir, m.pad(2)!!.sampleFile)))),
            Mutate.Mode.SPLIT,
            crossoverHz = 500f,
        )
        val out = WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))
        assertTrue(toneAmp(out, 100.0, 8192) > 0.05, "the pad's 100 Hz survives below the crossover")
        assertTrue(toneAmp(out, 3000.0, 8192) > 0.05, "the parent's 3 kHz survives above it")
    }

    @Test
    fun `stack - polarity cancellation is caught and flipped`() {
        val m = model("Stack")
        val base = WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))
        val inverted = Snip(FloatArray(base.samples.size) { -base.samples[it] }, base.channels, rate)

        val outcome = Mutate.apply(m, 1, listOf(Mutate.Source("evil twin", inverted)), Mutate.Mode.STACK)
        assertEquals(listOf("evil twin"), outcome.flipped, "the cancelling twin gets caught")
        val out = WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))
        assertTrue(
            rms(out.samples, 0, out.samples.size) > 0.5 * rms(base.samples, 0, base.samples.size),
            "flipped means summed, not silenced",
        )

        // Determinism: the same mutation on a twin kit renders the same bytes.
        val m2 = model("Stack2")
        Mutate.apply(m2, 1, listOf(Mutate.Source("evil twin", inverted)), Mutate.Mode.STACK)
        assertTrue(
            File(m.kitDir, m.pad(1)!!.sampleFile).readBytes()
                .contentEquals(File(m2.kitDir, m2.pad(1)!!.sampleFile).readBytes()),
            "same parents, same bytes",
        )
    }

    @Test
    fun `roulette - the crate deals the partner, seeded and never the pad itself`() {
        val m = model("Roul")
        model("Roul2")
        val self = File(m.kitDir, m.pad(1)!!.sampleFile).canonicalPath

        val pick = Mutate.roulette(m, 1, root = temp, seed = 5)
        assertTrue(File(pick.file.path).canonicalPath != self, "never the pad itself")
        assertEquals(pick, Mutate.roulette(m, 1, root = temp, seed = 5), "same seed, same deal")

        val wildPick = Mutate.roulette(m, 1, root = temp, seed = 5, wild = true)
        assertTrue(File(wildPick.file.path).canonicalPath != self)

        val empty = File(temp, "empty-crate").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException>("an empty crate refuses") {
            Mutate.roulette(m, 1, root = empty, seed = 0)
        }
    }

    @Test
    fun `guards hold - chained pads refused, splice takes one parent`() {
        val m = model("Guards")
        Robin.apply(m, 2, takes = 2)
        val parent = Mutate.Source("x", tone(500.0, 0.2f))
        assertFailsWith<IllegalArgumentException>("chained pad refused") {
            Mutate.apply(m, 2, listOf(parent))
        }
        assertFailsWith<IllegalArgumentException>("splice takes one parent") {
            Mutate.apply(m, 1, listOf(parent, parent), Mutate.Mode.SPLICE)
        }
        assertFailsWith<IllegalArgumentException>("no parents, no mutation") {
            Mutate.apply(m, 1, emptyList())
        }
    }
}

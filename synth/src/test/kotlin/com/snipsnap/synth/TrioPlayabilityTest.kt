package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlin.test.*

class TrioPlayabilityTest {
    private val macros = Trio.defaults(TrioVoice.ENSEMBLE) + mapOf("DRAMA" to 1f, "SIDES" to (1f / 6f))
    private val config = TrioConfig(seed = 47010)
    private fun hash(x: FloatArray): String {
        val bytes = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun distance(a: FloatArray, b: FloatArray): Double {
        var aa = 0.0; var bb = 0.0; var ab = 0.0
        for (i in a.indices) { aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i]; ab += a[i].toDouble() * b[i] }
        val gain = ab / bb
        return sqrt(a.indices.sumOf { val d = a[it] - gain * b[it]; d * d } / aa)
    }

    @Test fun `all accepted factory sounds retain their exact float audio`() {
        val expected = mapOf(
            "Tres Study" to "66158fd31804806a165b249b5ef745825e7989b1b860d88fab967c12caa0f58a",
            "Wire Study" to "cbc46c875deb774e13a932d0b15dab40b9e15f40b6c5a4359ff59f831d4c54c2",
            "Charango Study" to "ac1fd140da4b68c1d8dd3d459c1e3bd88824f42edf342c6e90ab6b245c01191f",
            "Family Root" to "21707a8ead1fc9a1cfdfd863733c57c8b595462f98af399faa43cc6401df9e29",
            "Late Reply" to "4c1ca6981c657dad5a156e83dff43079e9f65747813aa645528e557caf43979e",
            "Patient Circle" to "e59f2ab8834820e519c9e1a2407b7fd1010ae67793f3f35544327e136ad83266",
            "Wire Apart" to "a481bbe58f78bc1f93b2416ad8e419eaebba94666d04071cafa47471c6b3185e",
            "Slow Turn" to "eca7ae3640d25c04033a32526be3d26e414bc3e926498ed7d8917d3bdf5a01bb",
            "Cross Talk" to "5dd283e1723d2b4ce8ba4019daaf4ca514cdda9af933bb0c1503165d79b54b89",
            "Inner Answer" to "c3207de0113c0bff51c156b5f18b3131ab8452395f2d28471e797e180120f715",
            "Moving Pair" to "f48c7305444aac0978d2650240c919d654f75e5f11fa8894da7556358f8690a3",
            "Bright Exchange" to "c30497f3076470a6d0f739ec89900c58c32e1c6b4a4b3bd7cf6d3f87a489ad3a",
            "Early Guests" to "5e4654fe660f96fd33c022645d3b360232bb2f29186b84e86701c42b018fcc0f",
            "Close Quarters" to "97df2dc901e7c8a512e5949a3754c9fd2d4f0b8ccd20524c699831fd24b84165",
            "Gathering Heat" to "acb32723da872c2bb6a3031fa12cb3396654e2f116c9d57bd11f9a28c4992f0f",
            "Full Circle" to "33e72b586feb72645ffe0cc2348434f66098dd19c26a988bcf42048e5a0142bb"
        )
        for (patch in TrioPresets.all()) assertEquals(expected.getValue(patch.name), hash(patch.render().samples), patch.name)
    }

    @Test fun `fixed filter retains the original coefficient and sample arithmetic`() {
        for (cutoff in listOf(100f, 3800f, 5200f, 25000f, 100000f)) {
            val original = Dsp.OnePole(176400)
            val fixed = Dsp.FixedOnePole(cutoff, 176400)
            for (i in 0 until 6000) {
                val input = ((i * 37) % 101 - 50) / 50f
                assertEquals(original.lp(input, cutoff).toBits(), fixed.lp(input).toBits(), "cutoff $cutoff sample $i")
            }
        }
    }

    @Test fun `sparse contacts preserve addition order for overlapping bursts`() {
        val sparse = TrioExcitation(100)
        val dense = FloatArray(100)
        for ((at, gain) in listOf(20 to .3f, 1 to .1f, 21 to -.4f, 80 to .8f)) {
            val burst = FloatArray(25) { (it - 12) * .023f }
            sparse.add(at, burst, gain)
            for (j in burst.indices) if (at + j in dense.indices) dense[at + j] += burst[j] * gain
        }
        sparse.prepare()
        assertContentEquals(dense, FloatArray(100) { sparse[it] })
    }

    @Test fun `score reports final contact beyond the two second beat grid and caches every input`() {
        val edge = config.copy(tempoBpm = 120f, beats = 4, tremoloHz = 5f)
        val m = macros + ("SIDES" to (2f / 3f))
        val score = Trio.score(TrioVoice.ENSEMBLE, m, 60, 1f, edge)
        assertEquals(2f, score.gridSpanSeconds)
        assertTrue(score.lastGestureSeconds in 2.375f..2.380f)
        assertSame(score, Trio.score(TrioVoice.ENSEMBLE, m.toMap(), 60, 1f, edge))
        assertNotSame(score, Trio.score(TrioVoice.ENSEMBLE, m, 61, 1f, edge))
        assertNotSame(score, Trio.score(TrioVoice.ENSEMBLE, m, 60, .9f, edge))
        assertNotSame(score, Trio.score(TrioVoice.ENSEMBLE, m + ("TOUCH" to .95f), 60, 1f, edge))
        assertNotSame(score, Trio.score(TrioVoice.ENSEMBLE, m, 60, 1f, edge.copy(seed = 4)))
        assertEquals(score.events, Trio.renderProbe(TrioVoice.ENSEMBLE, m, 60, 1f, edge).events)
        assertFailsWith<UnsupportedOperationException> { (score.events as MutableList).clear() }
    }

    @Test fun `ordinary preview equals leveled diagnostic mix without retaining taps`() {
        val raw = Trio.renderProbe(TrioVoice.ENSEMBLE, macros, 60, .73f, config).samples
        Dsp.levelTo(raw, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        for (i in raw.indices) raw[i] *= .73f
        Dsp.fadeTail(raw)
        assertContentEquals(raw, Trio.render(TrioVoice.ENSEMBLE, macros, 60, .73f, config).samples)
    }

    @Test fun `upper touch changes character after output leveling and leaves accepted anchor exact`() {
        for (model in 5..6) {
            assertEquals("e3391a097e6279decd005948e0cb66ca5f2cb425085001091e0d1c37a3588c7d",
                hash(Trio.renderProbe(TrioVoice.ENSEMBLE, macros, 60, 1f, config.copy(model = model)).samples))
        }
        for (drama in listOf(.5f, 1f)) {
            fun render(touch: Float, model: Int) = Trio.render(TrioVoice.ENSEMBLE,
                macros + mapOf("TOUCH" to touch, "DRAMA" to drama), 60, 1f, config.copy(model = model)).samples
            assertContentEquals(render(.8f, 5), render(.8f, 6))
            assertTrue(distance(render(.9f, 6), render(1f, 6)) > .02, "DRAMA $drama must retain useful upper TOUCH after leveling")
        }
    }

    @Test fun `saved model five high touch recipes keep their pre-refactor audio`() {
        val cases = listOf(
            Triple(0.5f, 0.9f, "a54ea68a6da9a0df21ee9c2d89ff10a153d342f47ae7b3c5128acf80c7840546"),
            Triple(0.5f, 1.0f, "b07cc8a0edc279fdf42ec2bf3fe2d9486bc10da8708ceb0b1b46d60b2b6f8af4"),
            Triple(1.0f, 0.9f, "a03db8af50fd0a5fd575e620c1eba8ba5736ddac1ec87acb9b272266bc00ed8e"),
            Triple(1.0f, 1.0f, "ba633e1c8afe45ae5102de7edcfb062e1d3dcbbbc5d78006c2ecadf8d391a3ce")
        )
        for ((drama, touch, expected) in cases) assertEquals(expected, hash(Trio.render(TrioVoice.ENSEMBLE,
            macros + mapOf("DRAMA" to drama, "TOUCH" to touch), 60, 1f, config.copy(model = 5)).samples))
    }

    @Test fun `revised maximum touch keeps the dense extreme register scenes finite and single hit`() {
        val husbands = listOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)
        for (midi in listOf(48, 84)) for (damp in listOf(0f, 1f)) for (press in listOf(0f, 1f)) {
            val probe = Trio.renderProbe(TrioVoice.ENSEMBLE, macros + mapOf("TOUCH" to 1f, "DAMP" to damp,
                "SIDES" to (2f / 3f), "MOTION" to 1f), midi, 1f,
                config.copy(tempoBpm = 120f, beats = 4, tremoloHz = 5f, strumMs = 30f, press = press))
            assertTrue(probe.samples.all { it.isFinite() && kotlin.math.abs(it) < 1f })
            for (actor in husbands) assertEquals(1, probe.events.count { it.actor == actor })
        }
    }

    @Test fun `all historical and solo renders honor an already cancelled preview`() {
        for (model in 1..Trio.MODEL_VERSION) for (voice in TrioVoice.entries) {
            try {
                Thread.currentThread().interrupt()
                val cancelled = assertFailsWith<CancellationException> { Trio.render(voice, macros, config = config.copy(model = model)) }
                assertEquals("TRIO render cancelled", cancelled.message)
            } finally { Thread.interrupted() }
        }
    }

    @Test fun `an active obsolete render stops instead of returning audio`() {
        val started = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val worker = Thread {
            started.countDown()
            try { Trio.render(TrioVoice.ENSEMBLE, macros, config = config) }
            catch (error: Throwable) { result.set(error) }
            finally { ended.countDown() }
        }
        worker.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        worker.interrupt()
        assertTrue(ended.await(5, TimeUnit.SECONDS), "cancelled worker did not stop")
        assertIs<CancellationException>(result.get())
    }

    @Test fun `solo controls disclose the inactive ensemble values without losing recipe fields`() {
        for (voice in TrioVoice.entries) for (name in Trio.defaults(voice).keys) {
            assertEquals(voice == TrioVoice.ENSEMBLE || name in setOf("TOUCH", "SPACE", "DAMP"), Trio.isMacroActive(voice, name))
        }
    }
}

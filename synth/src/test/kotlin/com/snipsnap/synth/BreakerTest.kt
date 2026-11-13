package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BREAKER's probes (docs/BREAKER.md): the house set (finite, bounded, centred, its length to the frame,
 * every control moves it, JSON, non-finite fallback, velocity and takes) and the three claims the
 * owner heard at round 0: the spring's echoes rise and fall away, DRIP puts that spring after the hit,
 * and CRUSH past halfway swells the tail back up behind it.
 */
class BreakerTest {
    private val rate = Dsp.RATE
    private val frames = (Breaker.SECONDS * rate).toInt()

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * rate).toInt(); val b = minOf(x.size, (to * rate).toInt())
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / maxOf(1, b - a))
    }

    private fun db(x: Double) = 20 * log10(x + 1e-12)

    /** The tail (100 to 400 ms) against the hit (its first 20 ms), in dB. */
    private fun tailToHit(x: FloatArray) = db(rms(x, .1, .4)) - db(rms(x, 0.0, .02))

    /** [x] under two one-pole low-passes at 2 kHz: the drum's body and the spring's tail, without the crack. */
    private fun below2k(x: FloatArray): FloatArray {
        val a = Dsp.OnePole(rate); val b = Dsp.OnePole(rate)
        return FloatArray(x.size) { b.lp(a.lp(x[it], 2000f), 2000f) }
    }

    private fun band(x: FloatArray, hz: Float, q: Float): FloatArray {
        val bp = Dsp.Biquad().apply { bandpass(hz, q, rate) }
        val bp2 = Dsp.Biquad().apply { bandpass(hz, q, rate) }
        return FloatArray(x.size) { bp2.process(bp.process(x[it])) }
    }

    @Test
    fun `every hit is finite, bounded, centred and exactly its length`() {
        for (voice in BreakerVoice.entries) {
            val corners = listOf(emptyMap(), Breaker.macrosFor(voice).associate { it.name to 0f }, Breaker.macrosFor(voice).associate { it.name to 1f })
            val length = (Breaker.secondsFor(voice) * rate).toInt()
            for (m in corners) {
                val x = Breaker.render(voice, m).samples
                assertEquals(length, x.size, "$voice $m length")
                // Two declared exceptions ring longer: the crash and the riser.
                if (voice != BreakerVoice.CRASH && voice != BreakerVoice.RISER) assertTrue(length < (1.5 * rate).toInt(), "$voice stays under the loop threshold")
                assertTrue(x.all { it.isFinite() }, "$voice $m finite")
                val peak = x.maxOf { abs(it) }
                assertTrue(peak in .01f..1f, "$voice $m peak $peak")
                assertTrue(abs(x.average()) < .005, "$voice $m DC ${x.average()}")
            }
        }
    }

    @Test
    fun `every control changes the sound and an unknown key does not`() {
        for (voice in BreakerVoice.entries) {
            val base = Breaker.render(voice).samples
            for (spec in Breaker.macrosFor(voice)) {
                val lo = Breaker.render(voice, mapOf(spec.name to .15f)).samples
                val hi = Breaker.render(voice, mapOf(spec.name to .85f)).samples
                val diff = lo.indices.sumOf { abs(lo[it] - hi[it]).toDouble() } / lo.size
                assertTrue(diff > 1e-4, "$voice ${spec.name} moves the sound (mean |diff| $diff)")
            }
            assertContentEquals(base, Breaker.render(voice, mapOf("NOT_A_CONTROL" to .3f)).samples, "$voice ignores an unknown key")
            assertContentEquals(base, Breaker.render(voice, mapOf("DUST" to Float.NaN)).samples, "$voice falls back on a non-finite control")
        }
    }

    /** The drums the rig's tail claims are about. The cymbals ring longer than any tail window here, and the riser is all build. */
    private val struckDrums = BreakerVoice.entries - setOf(BreakerVoice.RIDE, BreakerVoice.CRASH, BreakerVoice.RISER)

    @Test
    fun `CRUSH lifts the tail, and past halfway swells the kick's and snare's back up`() {
        for (voice in struckDrums) {
            val r = listOf(.2f, .5f, .9f).map { tailToHit(Breaker.render(voice, mapOf("CRUSH" to it, "DRIP" to .6f)).samples) }
            println("BREAKER $voice tail-to-hit at CRUSH .2/.5/.9 (DRIP .6): ${r.map { "%.1f".format(it) }} dB")
            // A drum whose tail already sits within 6 dB of its hit (the tambourine's jingles) has no
            // tail for the crush to lift; every other one must come up.
            if (r[0] < -6.0) assertTrue(r[2] > r[0] + 1.0, "$voice: the crush lifts the tail ($r)")
            // The swell the owner heard at round 0, on the kick and snare. A short hat is already held
            // down hard by the first half, so its tail is up by CRUSH .5. It is the spring's tail coming
            // back up, so it is measured below 2 kHz, where the tail is: model 4's open top end makes the
            // hit's crack louder and hides it in a broadband ratio (snare .5 to .9: 0.4 dB broadband,
            // 1.3 dB below 2 kHz; kick 0.9 and 1.2 dB).
            if (voice == BreakerVoice.KICK || voice == BreakerVoice.SNARE) {
                val body = listOf(.5f, .9f).map { tailToHit(below2k(Breaker.render(voice, mapOf("CRUSH" to it, "DRIP" to .6f)).samples)) }
                println("BREAKER $voice tail-to-hit below 2 kHz at CRUSH .5/.9 (DRIP .6): ${body.map { "%.1f".format(it) }} dB")
                assertTrue(body[1] > body[0] + 1.0, "$voice: the crush after the spring swells the tail ($body)")
            }
        }
    }

    @Test
    fun `DRIP puts the spring after the hit`() {
        for (voice in struckDrums) {
            val e = listOf(.0f, .6f).map {
                val y = band(Breaker.render(voice, mapOf("DRIP" to it, "SLAP" to 0f)).samples, 2000f, .7f)
                db(rms(y, .05, .4)) - db(rms(y, 0.0, .05))
            }
            println("BREAKER $voice 2 kHz band after the hit against its first 50 ms, DRIP 0 / .6: ${e.map { "%.1f".format(it) }} dB")
            // 2 dB, not more: model 2's ride, whose own wash filled the band after the hit, measured
            // 2.6 dB at round 2; the rest are 5.6 dB and up.
            assertTrue(e[1] > e[0] + 2.0, "$voice: the spring rings after the hit ($e)")
        }
    }

    @Test
    fun `both tanks' echoes rise in pitch and fall away`() {
        for ((name, tank) in listOf("A" to BreakerChain.TANK_A, "B" to BreakerChain.TANK_B)) {
            val impulse = DoubleArray(frames).also { it[10] = 1.0 }
            val ir = BreakerChain.spring(impulse, .6, frames, rate, tank)
            val window = (.1 * rate).toInt()
            val energy = (0 until frames / window).map { w -> (w * window until (w + 1) * window).sumOf { ir[it] * ir[it] } }
            val levels = energy.map { 10 * log10(it / energy[0] + 1e-30) }
            println("BREAKER tank $name energy per 100 ms, dB re the first: ${levels.map { "%.1f".format(it) }}")
            for (w in 1 until levels.size) assertTrue(levels[w] < levels[w - 1] + .5, "tank $name never gains energy (window $w: ${levels[w]} after ${levels[w - 1]})")
            assertTrue(levels.last() < levels.first() - 30, "tank $name decays")
            // The first echo: its 2.5 kHz arrives after its 300 Hz (lows travel faster in a spring).
            // Zero-phase (forward, then backward): a narrow band-pass's own delay is longer at
            // 300 Hz than at 2.5 kHz, and would otherwise be read as the tank's.
            val first = FloatArray((.08 * rate).toInt()) { ir[it].toFloat() }
            fun arrival(hz: Float): Int {
                val y = band(band(first, hz, 2f).reversedArray(), hz, 2f).reversedArray()
                return y.indices.maxBy { abs(y[it]) }
            }
            val low = arrival(300f); val high = arrival(2500f)
            println("BREAKER tank $name first echo: 300 Hz peaks at ${"%.1f".format(low * 1000.0 / rate)} ms, 2.5 kHz at ${"%.1f".format(high * 1000.0 / rate)} ms")
            assertTrue(high > low, "tank $name's chirp rises: highs arrive later")
        }
    }

    @Test
    fun `the slap's first repeat lands at the round-0 95 ms, 90 ms on model 1`() {
        val impulse = DoubleArray(rate).also { it[0] = 1.0 }
        fun firstRepeat(base: Double) = BreakerChain.slap(impulse, .2, rate, base).withIndex().drop(1).maxBy { abs(it.value) }.index
        assertEquals((.095 * rate).toInt(), firstRepeat(BreakerChain.SLAP_SECONDS), "model 2")
        assertEquals((.09 * rate).toInt(), firstRepeat(.09), "model 1")
    }

    @Test
    fun `a model-1 save keeps round 1's kick, and model 2 is the re-fit one`() {
        val old = BreakerPatch("Round one", BreakerVoice.KICK, emptyMap(), model = 1)
        val back = BreakerPatch.fromJsonText(old.toJsonText())
        assertEquals(1, back.model)
        assertContentEquals(Breaker.render(BreakerVoice.KICK, model = 1).samples, back.render().samples, "a model-1 save replays model 1")
        assertContentEquals(back.render().samples, assertNotNull(Restrike.take(back, 1f, 7, 0)).samples, "take 0 of a model-1 save is model 1")
        assertFalse(back.render().samples.contentEquals(Breaker.render(BreakerVoice.KICK).samples), "model 2 changes the kick")
        assertEquals(Breaker.MODEL_VERSION, BreakerPatch("New", BreakerVoice.KICK, emptyMap()).model)
    }

    @Test
    fun `velocity and takes strike it again, deterministically`() {
        for (voice in BreakerVoice.entries) {
            val patch = BreakerPatch("Probe", voice, emptyMap())
            val full = patch.render(1f).samples
            // .3, not .5: the tambourine's two recorded layers split at .5, so a half-strength hit
            // is still its loud recording.
            val soft = patch.render(.3f).samples
            assertFalse(full.contentEquals(soft), "$voice: velocity changes the strike")
            assertTrue(Restrike.canRestrike(patch), "$voice re-strikes")
            val take1 = assertNotNull(Restrike.take(patch, 1f, 7, 1)).samples
            assertEquals(full.size, take1.size)
            assertFalse(full.contentEquals(take1), "$voice: a second take is a fresh strike")
            assertContentEquals(take1, assertNotNull(Restrike.take(patch, 1f, 7, 1)).samples, "$voice: takes are deterministic")
        }
    }

    /** The spectral centroid of the first 30 ms, from a plain DFT at 86 Hz spacing. */
    private fun attackCentroid(x: FloatArray): Double {
        val n = (.03 * rate).toInt()
        var num = 0.0; var den = 0.0
        for (k in 1 until 256) {
            val w = 2 * PI * k * 86.0 / rate
            var re = 0.0; var im = 0.0
            for (i in 0 until n) { re += x[i] * cos(w * i); im -= x[i] * sin(w * i) }
            val mag = sqrt(re * re + im * im)
            num += mag * k * 86.0; den += mag
        }
        return num / den
    }

    @Test
    fun `a softer hit on the hats, shaker and stick is a darker one`() {
        // SKIN does not strike the hats, shaker or stick, so BREAKER darkens the finished hit
        // (UNSTRUCK_SOFTEN). The rest take their velocity from their source: SKIN's contact law on
        // the kick, snare and toms (the owner heard the kick's and snare's as a softer hand), the
        // recorded layers on the tambourine, and on the cymbals the difference measured between a soft
        // and a hard recording (the next test).
        for (voice in listOf(BreakerVoice.HAT_CLOSED, BreakerVoice.HAT_OPEN, BreakerVoice.SHAKER, BreakerVoice.STICK)) {
            val full = attackCentroid(Breaker.render(voice).samples)
            val soft = attackCentroid(Breaker.render(voice, velocity = .4f).samples)
            println("BREAKER $voice attack centroid, full / 40%: ${"%.0f".format(full)} / ${"%.0f".format(soft)} Hz")
            assertTrue(soft < full * .95, "$voice: a 40% hit is darker, not only quieter ($full Hz, $soft Hz)")
        }
    }

    @Test
    fun `a softer cymbal is a different strike, not a quieter copy`() {
        // The law is measured (resources/breaker/cymbals.json, soft recording minus hard, each re its
        // own loudness): a softer crash carries more middle (+5 to +14 dB, 315 Hz to 1.3 kHz) and less
        // presence (-3 dB, 4 to 6 kHz); a softer ride more low ring (+20 dB at 250 Hz, +11 at 315) and
        // less at 1.3 to 2 kHz (-4 to -6 dB). Neither is simply darker by centroid, so the claim is the
        // house one: at matched loudness, the spectrum's shape moves.
        val octaves = listOf(250f, 500f, 1000f, 2000f, 4000f, 8000f)
        val moved = listOf(BreakerVoice.RIDE, BreakerVoice.CRASH).associateWith { voice ->
            fun shape(x: FloatArray): List<Double> {
                val all = db(rms(x, 0.0, .3))
                return octaves.map { db(rms(band(x, it, 1.4f), 0.0, .3)) - all }
            }
            val full = shape(Breaker.render(voice).samples); val soft = shape(Breaker.render(voice, velocity = .4f).samples)
            println("BREAKER $voice octave shape (250 Hz to 8 kHz), 40% minus full: ${full.indices.map { "%.1f".format(soft[it] - full[it]) }} dB")
            full.indices.maxOf { abs(full[it] - soft[it]) }
        }
        for ((voice, db) in moved) assertTrue(db > 3.0, "$voice: a 40% hit changes its spectrum's shape by more than 3 dB in some octave ($db)")
    }

    @Test
    fun `at their defaults the cymbals are the fitted synth with no rig at all, and AMP fades the combo in`() {
        for ((voice, kind) in listOf(BreakerVoice.RIDE to BreakerCymbal.Kind.RIDE, BreakerVoice.CRASH to BreakerCymbal.Kind.CRASH)) {
            val seconds = Breaker.secondsFor(voice)
            val dry = BreakerCymbal.render(kind, 1f, 0, seconds)
            val n = dry.size
            // The engine's own finish on the dry synth: mean and 20 Hz high-pass, the fade, the level.
            val mean = dry.average().toFloat()
            val dc = Dsp.FixedOnePole(20f, rate)
            for (i in dry.indices) { val s = dry[i] - mean; dry[i] = s - dc.lp(s) }
            val fade = (.25f * rate).toInt()
            for (k in 0 until fade) dry[n - fade + k] *= (0.5 + 0.5 * cos(PI * k / fade)).toFloat()
            Dsp.levelTo(dry, rate, Dsp.MELODIC_LOUDNESS_TARGET); Dsp.fadeTail(dry)
            val got = Breaker.render(voice).samples
            val worst = dry.indices.maxOf { abs(dry[it] - got[it]) }
            assertTrue(worst < 1e-5f, "$voice at its defaults is the dry fit (max |diff| $worst)")
            val amped = Breaker.render(voice, mapOf("AMP" to .5f)).samples
            assertTrue(dry.indices.sumOf { abs(dry[it] - amped[it]).toDouble() } / n > 1e-4, "$voice: AMP brings the combo in")
        }
    }

    @Test
    fun `the cymbals have hundreds of modes, and the crash rings past the one-shot limit`() {
        assertTrue(BreakerCymbal.modeCount(BreakerCymbal.Kind.RIDE) >= 300 && BreakerCymbal.modeCount(BreakerCymbal.Kind.CRASH) >= 300)
        val crash = Breaker.render(BreakerVoice.CRASH).samples
        val ringing = db(rms(crash, 1.6, 2.2)) - db(rms(crash, 0.0, .1))
        println("BREAKER crash 1.6-2.2 s against its first 100 ms: ${"%.1f".format(ringing)} dB")
        assertTrue(ringing > -45, "the crash is still ringing after 1.5 s ($ringing dB)")
        assertEquals(DrumClass.PERC, Breaker.drumClassFor(BreakerVoice.CRASH), "declared, so its length never files it as a LOOP")
    }

    @Test
    fun `the riser builds`() {
        val x = Breaker.render(BreakerVoice.RISER).samples
        val start = db(rms(x, 0.0, .5)); val top = db(rms(x, 1.9, 2.4))
        println("BREAKER riser: first 0.5 s ${"%.1f".format(start)} dB, its last 0.5 s of build ${"%.1f".format(top)} dB")
        assertTrue(top > start + 8, "the riser rises ($start to $top dB)")
    }

    @Test
    fun `model 3's new voices refuse model 2, and model 2's ride is its own`() {
        for (voice in listOf(BreakerVoice.CRASH, BreakerVoice.RISER)) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { BreakerPatch("Old", voice, emptyMap(), model = 2) }
        }
        val old = BreakerPatch("Round two", BreakerVoice.RIDE, emptyMap(), model = 2)
        assertContentEquals(old.render().samples, BreakerPatch.fromJsonText(old.toJsonText()).render().samples, "model 2's ride replays")
        // A save holds only the controls it set; model 2 fills the rest with round 2's ride defaults, not the dry ones.
        val round2 = mapOf("DUST" to .3f, "DRIP" to .45f, "SLAP" to 0f, "CRUSH" to .6f, "AMP" to .35f, "TUNE" to .5f)
        assertContentEquals(Breaker.render(BreakerVoice.RIDE, round2, model = 2).samples, old.render().samples, "model 2 keeps round 2's ride defaults")
        assertFalse(old.render().samples.contentEquals(Breaker.render(BreakerVoice.RIDE).samples), "model 3's ride is the fitted one")
    }

    @Test
    fun `model 4 opens the drums' top end, keeps the cymbals' and riser's rig, and model-3 saves keep theirs`() {
        // The owner heard the model-3 kit as mid heavy and picked the open rig (2026-11-12): DUST's filter
        // opened, no speaker. Measured on the hard hit, the 8 kHz octave against the whole hit's level.
        fun air(x: FloatArray) = db(rms(band(x, 8000f, 1.4f), 0.0, .3)) - db(rms(x, 0.0, .3))
        for ((voice, least) in listOf(BreakerVoice.SNARE to 6.0, BreakerVoice.HAT_CLOSED to 6.0, BreakerVoice.HAT_OPEN to 6.0, BreakerVoice.KICK to 3.0)) {
            val now = air(Breaker.render(voice).samples); val before = air(Breaker.render(voice, model = 3).samples)
            println("BREAKER $voice 8 kHz octave re the hit, model 3 / 4: ${"%.1f".format(before)} / ${"%.1f".format(now)} dB")
            assertTrue(now > before + least, "$voice: model 4 opens the top by more than $least dB (${now - before})")
        }
        for (voice in listOf(BreakerVoice.RIDE, BreakerVoice.CRASH, BreakerVoice.RISER)) {
            assertContentEquals(Breaker.render(voice, model = 3).samples, Breaker.render(voice).samples, "$voice keeps the rig it was heard with")
            val dialled = mapOf("DUST" to .3f, "CRUSH" to .4f, "AMP" to .35f)
            assertContentEquals(Breaker.render(voice, dialled, model = 3).samples, Breaker.render(voice, dialled).samples, "$voice's dialled rig too")
        }
        val old = BreakerPatch("Model three", BreakerVoice.SNARE, emptyMap(), model = 3)
        val back = BreakerPatch.fromJsonText(old.toJsonText())
        assertEquals(3, back.model)
        assertContentEquals(Breaker.render(BreakerVoice.SNARE, model = 3).samples, back.render().samples, "a model-3 save keeps its tone")
        assertFalse(back.render().samples.contentEquals(Breaker.render(BreakerVoice.SNARE).samples))
    }

    @Test
    fun `a saved patch round-trips through JSON with its model`() {
        for (voice in BreakerVoice.entries) {
            val patch = BreakerPatch("Round trip", voice, mapOf("DRIP" to .3f, "CRUSH" to .7f))
            val back = BreakerPatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, back)
            assertEquals(Breaker.MODEL_VERSION, back.model)
        }
    }

    @Test
    fun `what the classifier hears, and the render cost`() {
        for (voice in BreakerVoice.entries) {
            Breaker.render(voice)
            val t0 = System.nanoTime()
            val snip = Breaker.render(voice)
            val ms = (System.nanoTime() - t0) / 1e6
            println("BREAKER $voice: classified ${Classifier.classify(snip).drumClass}, declared ${Breaker.drumClassFor(voice)}, render ${"%.0f".format(ms)} ms (desktop JVM, warm)")
        }
    }
}

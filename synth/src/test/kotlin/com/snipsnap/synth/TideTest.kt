package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TideTest {

    /** A clean note: no fold, no WARP, no WANDER, the longest gate. */
    private val clean = mapOf("FOLD" to 0f, "WARP" to 0f, "WANDER" to 0f, "DECAY" to 1f)

    private fun cents(hz: Float, want: Float): Float = 1200f * ln(hz / want) / ln(2f)

    /** Frequency from rising zero crossings between two times, interpolated: exact on a clean tone. */
    private fun zeroCrossingHz(s: Snip, fromSec: Float, toSec: Float): Float {
        val a = (fromSec * s.sampleRate).toInt()
        val b = (toSec * s.sampleRate).toInt().coerceAtMost(s.samples.size)
        var first = -1.0
        var last = -1.0
        var cycles = 0
        for (i in a + 1 until b) {
            val p = s.samples[i - 1]
            val q = s.samples[i]
            if (p < 0f && q >= 0f) {
                val x = i - 1 + (-p / (q - p)).toDouble()
                if (first < 0) first = x else cycles++
                last = x
            }
        }
        return (cycles * s.sampleRate / (last - first)).toFloat()
    }

    private fun slice(s: Snip, fromSec: Float, toSec: Float): Snip {
        val a = (fromSec * s.sampleRate).toInt().coerceIn(0, s.samples.size)
        val b = (toSec * s.sampleRate).toInt().coerceIn(a, s.samples.size)
        return Snip(s.samples.copyOfRange(a, b), 1, s.sampleRate)
    }

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in TideVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Tide.macrosFor(voice).associate { it.name to 0f },
                Tide.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Tide.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                // Levelled by loudness: a long note meets the target under a low peak, a short hit meets the ceiling first.
                val loud = com.snipsnap.audio.Loudness.of(snip)
                assertTrue(loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f, "$voice too quiet at $macros: loudness $loud, peak ${snip.peak()}")
                assertTrue(snip.durationSeconds <= Tide.maxSecondsFor(voice) + 0.01f, "$voice must stay a one-shot: ${snip.durationSeconds} s")
                val dc = snip.samples.average().toFloat()
                assertTrue(abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
            }
        }
    }

    @Test
    fun `the struck voices add CLICK, and GONG and FLARE add RATIO`() {
        val struck = listOf("TUNE", "FOLD", "WARP", "GLOW", "CLICK", "DECAY", "WANDER")
        val held = listOf("TUNE", "FOLD", "WARP", "RATIO", "GLOW", "DECAY", "WANDER")
        assertEquals(struck, Tide.macrosFor(TideVoice.BONGO).map { it.name })
        assertEquals(struck, Tide.macrosFor(TideVoice.DRIP).map { it.name })
        assertEquals(held, Tide.macrosFor(TideVoice.GONG).map { it.name })
        assertEquals(held, Tide.macrosFor(TideVoice.FLARE).map { it.name })
    }

    @Test
    fun `the same recipe renders the same bytes, and only WANDER lets takes differ`() {
        for (voice in TideVoice.entries) {
            val a = Tide.render(voice, mapOf("WANDER" to 0.7f))
            val b = Tide.render(voice, mapOf("WANDER" to 0.7f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: same macros must render the same bytes")

            val still0 = Tide.render(voice, mapOf("WANDER" to 0f), take = 0)
            val still1 = Tide.render(voice, mapOf("WANDER" to 0f), take = 1)
            assertTrue(still0.samples.contentEquals(still1.samples), "$voice: WANDER 0 makes every take the same")

            val wild0 = Tide.render(voice, mapOf("WANDER" to 1f), take = 0)
            val wild1 = Tide.render(voice, mapOf("WANDER" to 1f), take = 1)
            assertTrue(!wild0.samples.contentEquals(wild1.samples), "$voice: WANDER 1 makes takes differ")
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in TideVoice.entries) {
            val a = Tide.scramble(voice, Random(11))
            val b = Tide.scramble(voice, Random(11))
            assertEquals(a, b, "$voice scramble should be seed-stable")
            assertEquals(Tide.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it in 0f..1f })
        }
    }

    @Test
    fun `render actually dispatches through the oversampled path, not directly at RATE`() {
        for (voice in TideVoice.entries) {
            val actual = Tide.render(voice)
            val direct = Tide.synthesize(voice, emptyMap(), Dsp.RATE)
            Dsp.levelTo(direct, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(direct)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += abs((actual.samples[i] - direct[i]).toDouble())
            assertTrue(diff / n > 0.002, "$voice: render should differ from a native-rate synthesize, avgDiff=${diff / n}")
        }
    }

    @Test
    fun `TUNE snaps to semitones across two octaves`() {
        for (voice in TideVoice.entries) {
            val notes = (0..100).map { Tide.midiFor(voice, it / 100f) }.toSet()
            assertEquals(Tide.TUNE_SEMITONES + 1, notes.size, "$voice")
            assertEquals(Tide.rootMidi(voice), notes.min())
        }
    }

    /** Seconds until [voice]'s gate, WANDER 0, first closes below [level]. */
    private fun gateBelow(voice: TideVoice, decay: Float, level: Float): Float {
        val rate = 10_000
        val gate = Tide.Gate(Tide.lengthFor(voice, decay), rate, Tide.holdFractionFor(voice, decay))
        var i = 0
        while (gate.next() >= level || i < rate / 100) i++
        return i.toFloat() / rate
    }

    @Test
    fun `a clean note sits on its pitch while the gate is open, and the sag stays small`() {
        for (voice in TideVoice.entries) {
            for (tune in listOf(0f, 0.5f, 1f)) {
                val want = Tide.frequencyFor(voice, tune)
                val s = Tide.render(voice, clean + ("TUNE" to tune))
                // From 20 ms (DRIP's chirp has landed by 15) to the gate half closed.
                val open = zeroCrossingHz(s, 0.02f, gateBelow(voice, 1f, 0.5f))
                assertTrue(abs(cents(open, want)) < 10f, "$voice TUNE $tune: ${cents(open, want)} cents off while the gate is open")
                // Down to the gate at a tenth: the level is under -26 dB by then.
                val body = zeroCrossingHz(s, 0.02f, gateBelow(voice, 1f, 0.1f))
                assertTrue(abs(cents(body, want)) < 30f, "$voice TUNE $tune: the sag reached ${cents(body, want)} cents")
            }
        }
    }

    @Test
    fun `the gate opens far above every note and closes under it`() {
        for (voice in TideVoice.entries) for (tune in listOf(0f, 1f)) {
            val hz = Tide.frequencyFor(voice, tune)
            assertEquals(hz * 0.5f, Tide.cutoffAt(0f, hz), hz * 1e-4f, "$voice closed")
            assertTrue(Tide.cutoffAt(1f, hz) in 5_999f..18_001f, "$voice open: ${Tide.cutoffAt(1f, hz)}")
            assertTrue(Tide.cutoffAt(0.5f, hz) > hz * 2f, "$voice half open should still be well above the note")
        }
    }

    @Test
    fun `FOLD, WARP, WANDER and the edge never move the pitch`() {
        // Phase modulation keeps the carrier on the note; a fold keeps the
        // period; WANDER is timbre and decay only; CROSS is held under the
        // loop gain where feedback turns a note to noise. The detector reads
        // the clean note and the wildest one the same once SWEEP's dive has
        // landed: read from 100 ms, since at 50 ms the modulator is still
        // 10% sharp of its ratio and its sidebands pull the reading a step.
        // DRIP is read at its root: the detector's range stops short of its
        // top octave. GONG is a bell, with no one pitch to hold.
        for (voice in listOf(TideVoice.BONGO, TideVoice.DRIP, TideVoice.FLARE)) {
            val tune = if (voice == TideVoice.DRIP) 0f else 0.5f
            val plain = TestPitch.estimate(Tide.render(voice, clean + ("TUNE" to tune)), fromSec = 0.1f, windowSec = 0.2f)
            for (ratio in listOf(0f, 1f)) for (take in 0..2) {
                val wild = Tide.render(
                    voice,
                    mapOf("TUNE" to tune, "FOLD" to 1f, "WARP" to 1f, "RATIO" to ratio, "WANDER" to 1f, "DECAY" to 1f).filterKeys { it in Tide.defaults(voice) },
                    take = take,
                )
                val got = com.snipsnap.audio.Pitch.detect(wild, fromSec = 0.1f, windowSec = 0.2f)
                assertTrue(got != null && got.confidence >= 0.6f, "$voice RATIO $ratio take $take: the note turned to noise (${got?.confidence})")
                // Within one step of the detector's whole-sample lag: f²/(rate − f) Hz, ~5 cents at C3, ~20 at C5.
                val step = plain * plain / (wild.sampleRate - plain)
                assertTrue(abs(got.hz - plain) <= step * 1.01f, "$voice RATIO $ratio take $take: $plain Hz clean, ${got.hz} Hz folded and warped")
            }
        }
    }

    @Test
    fun `the edge leaves a clean note clean`() {
        // SWEEP and CROSS work through WARP's index, WOBBLE through FOLD and
        // WARP, TILT through a bias that fades in over FOLD's first tenth:
        // at FOLD 0 and WARP 0 all four are silent and the note is a sine.
        for (voice in TideVoice.entries) {
            val s = Tide.render(voice, clean)
            val hz = Tide.frequencyFor(voice, Tide.defaults(voice).getValue("TUNE"))
            val w = slice(s, 0.05f, 0.25f).samples
            fun level(f: Float): Double {
                var re = 0.0
                var im = 0.0
                for (i in w.indices) {
                    val hann = 0.5 - 0.5 * cos(2 * PI * i / (w.size - 1))
                    re += w[i] * hann * cos(2 * PI * f * i / s.sampleRate)
                    im += w[i] * hann * sin(2 * PI * f * i / s.sampleRate)
                }
                return re * re + im * im
            }
            val fundamental = level(hz)
            for (k in 2..6) {
                val db = 10 * log10(level(hz * k) / fundamental)
                assertTrue(db < -50.0, "$voice: harmonic $k of a clean note is only ${"%.1f".format(db)} dB down")
            }
        }
    }

    @Test
    fun `the strike's sweep lands - every FLARE preset reads its note from 100 ms`() {
        // SWEEP starts the modulator 2.2 times its ratio and dives at 20 ms
        // a step. Auditioned at 60 ms, SNARL FLARE read 196 Hz for 131 into
        // its second hundred milliseconds; at 20 ms it reads true by 50.
        for (preset in TidePresets.forVoice(TideVoice.FLARE)) {
            val want = Tide.frequencyFor(TideVoice.FLARE, preset.macros.getValue("TUNE"))
            val got = com.snipsnap.audio.Pitch.detect(preset.render(), fromSec = 0.1f, windowSec = 0.1f)
            assertTrue(got != null && got.confidence >= 0.8f, "${preset.name}: no clear pitch at 100 ms (${got?.confidence})")
            assertTrue(abs(cents(got.hz, want)) < 10f, "${preset.name}: ${got.hz} Hz at 100 ms, want $want")
        }
    }

    @Test
    fun `the strike thumps - it starts sharp and lands on the note`() {
        // THUMP starts the strike seven semitones sharp, falling at 5 ms a
        // step. On BONGO's top note (C5, a cycle every 1.9 ms) its first
        // milliseconds read well sharp; the pitch tests above show it gone.
        val want = Tide.frequencyFor(TideVoice.BONGO, 1f)
        val s = Tide.render(TideVoice.BONGO, clean + ("TUNE" to 1f))
        val strike = zeroCrossingHz(s, 0.001f, 0.008f)
        assertTrue(strike > want * 1.1f, "the strike should start sharp: $strike Hz for a $want Hz note")
    }

    /** The fundamental's share of the first 150 ms, dB: 0 is a pure sine at [hz]. */
    private fun fundamentalShareDb(s: Snip, hz: Float): Double {
        val n = minOf(s.samples.size, (0.15f * s.sampleRate).toInt())
        var re = 0.0
        var im = 0.0
        var total = 0.0
        for (i in 0 until n) {
            val v = s.samples[i] * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
            re += v * cos(2 * PI * hz * i / s.sampleRate)
            im += v * sin(2 * PI * hz * i / s.sampleRate)
            total += v * v
        }
        // A Hann-windowed sine puts N/3 of its windowed energy in its own bin.
        return 10 * log10((re * re + im * im) / (n / 3.0) / total)
    }

    @Test
    fun `BODY keeps the note under the fold - every FLARE preset carries its fundamental`() {
        // The fold spreads FLARE's energy up the spectrum; BODY's clean sine
        // puts the note back under it, always on the note's side. Measured
        // without BODY: -2.7 to -28.3 dB (SNARL FLARE the worst); with it,
        // -1.3 to -6.6.
        for (preset in TidePresets.forVoice(TideVoice.FLARE)) {
            val hz = Tide.frequencyFor(TideVoice.FLARE, preset.macros.getValue("TUNE"))
            val share = fundamentalShareDb(preset.render(), hz)
            assertTrue(share > -8.0, "${preset.name}: the note is ${"%.1f".format(share)} dB under the whole")
        }
    }

    @Test
    fun `CLICK puts a stick on the skin, and at 0 it is nothing`() {
        // Every preset leaves CLICK at 0, so it must not move a byte there,
        // WANDER's draws included (seedFor leaves CLICK out).
        for (voice in listOf(TideVoice.BONGO, TideVoice.DRIP)) {
            val base = mapOf("WANDER" to 0.7f)
            assertTrue(
                Tide.render(voice, base).samples.contentEquals(Tide.render(voice, base + ("CLICK" to 0f)).samples),
                "$voice: CLICK 0 must render the note unchanged",
            )
        }
        // Up, the first 4 ms carry far more energy above the note, and the
        // body under it keeps its level. Measured: WOOD BONGO 3.1 times,
        // losing 0.3 dB.
        fun above(s: Snip): Double {
            var e = 0.0
            for (i in 1 until (0.004f * s.sampleRate).toInt()) {
                val d = (s.samples[i] - s.samples[i - 1]).toDouble()
                e += d * d
            }
            return e
        }
        fun rms(s: Snip, from: Float, to: Float): Double {
            val w = slice(s, from, to).samples
            return kotlin.math.sqrt(w.sumOf { (it * it).toDouble() } / w.size)
        }
        val bongo = TidePresets.all().first { it.name == "WOOD BONGO" }
        val plain = bongo.render()
        val clicked = Tide.render(bongo.voice, bongo.macros + ("CLICK" to 1f))
        assertTrue(kotlin.math.sqrt(above(clicked) / above(plain)) > 2.0, "CLICK 1 should brighten the strike well past the note's own")
        val loss = 20 * log10(rms(clicked, 0.02f, 0.2f) / rms(plain, 0.02f, 0.2f))
        assertTrue(loss > -1.0, "the body under the click should keep its level, lost ${"%.1f".format(loss)} dB")
    }

    @Test
    fun `the edge's extra reach goes to the low notes and eases off where it would alias`() {
        // Full reach wherever the brightest corner stays under REACH_LIMIT_HZ.
        assertEquals(1f, Tide.reachAt(Tide.frequencyFor(TideVoice.BONGO, 0.5f)), "BONGO's middle gets all of it")
        assertTrue(Tide.reachAt(Tide.frequencyFor(TideVoice.BONGO, 1f)) > 0.95f, "and its top note nearly all")
        assertEquals(1f, Tide.reachAt(Tide.frequencyFor(TideVoice.FLARE, 0.5f), 4f), "FLARE's middle gets all of it, even at RATIO 4")
        // Less as the note or the modulator climbs, never nothing.
        var last = 1f
        for (midi in 72..96) {
            val r = Tide.reachAt(440f * Math.pow(2.0, (midi - 69) / 12.0).toFloat())
            assertTrue(r <= last && r > 0.3f, "reach at MIDI $midi: $r after $last")
            last = r
        }
        val c4 = Tide.frequencyFor(TideVoice.FLARE, 1f)
        assertTrue(Tide.reachAt(c4, 4f) < Tide.reachAt(c4, 2f), "a higher RATIO reaches further, so it eases sooner")
    }

    @Test
    fun `the gate falls slower as it closes and reaches -60 dB on time`() {
        val rate = 10_000
        val length = 1f
        val gate = Tide.Gate(length, rate)
        val c = FloatArray((length * rate).toInt() + rate / 10) { gate.next() }
        fun db(sec: Float): Double = 20 * log10(Math.pow(c[(sec * rate).toInt()].toDouble(), 1.3).coerceAtLeast(1e-12))
        val early = (db(0.15f) - db(0.05f)) / 0.1
        val late = (db(0.8f) - db(0.4f)) / 0.4
        assertTrue(late > early, "the release should slow: early $early dB/s, late $late dB/s")
        assertTrue(db(length) in -63.0..-57.0, "-60 dB lands at the note's length, got ${db(length)} dB")
    }

    @Test
    fun `brightness closes with the level - the gate's signature`() {
        for (voice in TideVoice.entries) {
            // GLOW 0: the classic gate, brightness and level together.
            val s = Tide.render(voice, mapOf("FOLD" to 0.8f, "DECAY" to 0.5f, "GLOW" to 0f, "WANDER" to 0f))
            val head = FeatureExtractor.extract(slice(s, 0f, 0.03f)).centroidHz
            val tail = FeatureExtractor.extract(slice(s, s.durationSeconds * 0.5f, s.durationSeconds * 0.75f)).centroidHz
            // The tail closes all the way to the note itself. The strike is
            // brighter, though BODY's clean sine under it pulls its centroid
            // toward the note: measured with BODY, DRIP (C6, the least fold
            // room) 1.9 times its tail, the others 4.4-7.2; without, 2.6-10.9.
            val note = Tide.frequencyFor(voice, Tide.defaults(voice).getValue("TUNE"))
            assertTrue(tail < note * 1.1f, "$voice: the closed gate should leave only the note, $tail Hz for a $note Hz note")
            assertTrue(head > tail * 1.5f, "$voice: the strike should be brighter than the tail, $head Hz vs $tail Hz")
        }
    }

    @Test
    fun `GLOW keeps the tail's harmonics`() {
        for (voice in TideVoice.entries) {
            fun tail(glow: Float): Float {
                val s = Tide.render(voice, mapOf("FOLD" to 0.8f, "DECAY" to 0.5f, "GLOW" to glow, "WANDER" to 0f))
                return FeatureExtractor.extract(slice(s, s.durationSeconds * 0.3f, s.durationSeconds * 0.6f)).centroidHz
            }
            val dark = tail(0f)
            val bright = tail(1f)
            assertTrue(bright > dark * 1.3f, "$voice: GLOW 1 should keep the tail brighter, $dark Hz at 0 vs $bright Hz at 1")
        }
    }

    @Test
    fun `a long DECAY holds GONG and FLARE open, and the struck voices never hold`() {
        for (voice in TideVoice.entries) {
            assertEquals(0f, Tide.holdFractionFor(voice, 0.5f), "$voice holds nothing at DECAY 0.5")
        }
        assertEquals(0f, Tide.holdFractionFor(TideVoice.BONGO, 1f))
        assertEquals(0f, Tide.holdFractionFor(TideVoice.DRIP, 1f))
        assertEquals(0.6f, Tide.holdFractionFor(TideVoice.FLARE, 1f), 1e-6f)
        fun rms(s: Snip, from: Float, to: Float): Double {
            val w = slice(s, from, to).samples
            return kotlin.math.sqrt(w.sumOf { (it * it).toDouble() } / w.size)
        }
        for (voice in listOf(TideVoice.GONG, TideVoice.FLARE)) {
            val s = Tide.render(voice, mapOf("DECAY" to 1f, "WANDER" to 0f))
            val early = rms(s, 0.05f, 0.25f)
            val middle = rms(s, s.durationSeconds * 0.4f, s.durationSeconds * 0.5f)
            assertTrue(20 * log10(middle / early) > -3.0, "$voice DECAY 1 should still be held at 40%: ${20 * log10(middle / early)} dB")
        }
        val bongo = Tide.render(TideVoice.BONGO, mapOf("DECAY" to 1f, "WANDER" to 0f))
        assertTrue(20 * log10(rms(bongo, bongo.durationSeconds * 0.4f, bongo.durationSeconds * 0.5f) / rms(bongo, 0.01f, 0.05f)) < -12.0, "BONGO is struck, not held")
    }

    @Test
    fun `FOLD adds harmonics across its whole travel`() {
        for (voice in TideVoice.entries) {
            val centroids = listOf(0f, 0.5f, 1f).map { f ->
                FeatureExtractor.extract(Tide.render(voice, mapOf("FOLD" to f, "WANDER" to 0f))).centroidHz
            }
            assertTrue(centroids[0] < centroids[1] && centroids[1] < centroids[2], "$voice FOLD 0 -> 0.5 -> 1: $centroids")
        }
    }

    @Test
    fun `a soft strike folds less - velocity reaches TIDE through FOLD`() {
        for (voice in TideVoice.entries) {
            val patch = TidePatch("Velocity", voice, mapOf("FOLD" to 0.8f, "WANDER" to 0f))
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.2f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard * 0.8f, "$voice: a soft strike should be darker, $soft Hz soft vs $hard Hz hard")
        }
    }

    @Test
    fun `DECAY lengthens`() {
        for (voice in TideVoice.entries) {
            val short = Tide.render(voice, mapOf("DECAY" to 0.1f, "WANDER" to 0f))
            val long = Tide.render(voice, mapOf("DECAY" to 0.9f, "WANDER" to 0f))
            assertTrue(long.durationSeconds > short.durationSeconds * 2f, "$voice: ${short.durationSeconds} s -> ${long.durationSeconds} s")
            val sd = FeatureExtractor.extract(short).decayMs
            val ld = FeatureExtractor.extract(long).decayMs
            assertTrue(ld > sd * 1.5f, "$voice DECAY should stretch the note: $sd -> $ld ms")
        }
    }

    /** Energy on the harmonics of [f0] over energy between them, dB, below 20 kHz, DC excluded. */
    private fun harmonicClarity(samples: FloatArray, rate: Int, f0: Float): Double {
        val n = 32768
        val start = rate / 10
        // Four-term Blackman-Harris: sidelobes under -92 dB, so leakage from
        // a loud harmonic never reads as energy between harmonics.
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples[start + i] * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var on = 0.0
        var off = 0.0
        for (b in 1 until n / 2) {
            val hz = b.toFloat() * rate / n
            if (hz > 20_000f) break
            if (hz < f0 / 2) continue
            val k = Math.round(hz / f0)
            val e = (re[b] * re[b] + im[b] * im[b]).toDouble()
            if (abs(hz - k * f0) < 8f) on += e else off += e
        }
        return 10 * log10(on / off)
    }

    /** One steady second of TIDE's oscillator and folder at [rate], at their brightest for [hz]. */
    private fun steadyFold(hz: Float, ratio: Float, rate: Int): FloatArray {
        val reach = Tide.reachAt(hz, ratio)
        val drive = 1f + (Tide.MAX_DRIVE - 1f) * reach
        val index = Tide.MAX_INDEX * reach
        return FloatArray(rate) { i ->
            val t = i.toDouble() / rate
            val x = sin(2 * PI * hz * t + index * sin(2 * PI * hz * ratio * t)).toFloat()
            Tide.fold(x, drive, 0.3f)
        }
    }

    @Test
    fun `full fold and full WARP at the top note stay clean of aliasing`() {
        // GONG is inharmonic by design, so "between the harmonics" means
        // nothing there; every harmonic voice is held at its top note.
        for ((voice, ratio) in listOf(TideVoice.BONGO to 1f, TideVoice.DRIP to 1f, TideVoice.FLARE to 4f)) {
            val hz = Tide.frequencyFor(voice, 1f)
            val raw = steadyFold(hz, ratio, Dsp.RATE * Dsp.OVERSAMPLE)
            Tide.bandLimit(raw, Dsp.RATE * Dsp.OVERSAMPLE)
            val rendered = harmonicClarity(Dsp.decimate(raw, Dsp.RATE), Dsp.RATE, hz)
            val naive = harmonicClarity(steadyFold(hz, ratio, Dsp.RATE), Dsp.RATE, hz)
            assertTrue(rendered >= 45.0, "$voice at ${hz} Hz: energy between harmonics only ${"%.1f".format(rendered)} dB down")
            // The measure has to be able to see aliasing for the line above to mean anything.
            assertTrue(rendered > naive + 10.0, "$voice: oversampled ${"%.1f".format(rendered)} dB vs native ${"%.1f".format(naive)} dB")
        }
    }

    @Test
    fun `RATIO snaps to whole numbers on FLARE and to bell ratios on GONG`() {
        for (i in 0..20) {
            val r = i / 20f
            val flare = Tide.ratioFor(TideVoice.FLARE, r)
            assertEquals(Math.round(flare).toFloat(), flare, "FLARE's ratio must be whole: $flare")
            assertTrue(Tide.ratioFor(TideVoice.GONG, r) in Tide.INHARMONIC_RATIOS.toList())
            assertEquals(1f, Tide.ratioFor(TideVoice.BONGO, r))
            assertEquals(1f, Tide.ratioFor(TideVoice.DRIP, r))
        }
    }

    @Test
    fun `a TIDE patch round-trips through JSON`() {
        val patch = TidePatch("Wood Test", TideVoice.GONG, mapOf("RATIO" to 0.75f, "FOLD" to 0.4f))
        val restored = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        assertTrue(patch.render().samples.contentEquals(restored.render().samples))
    }

    @Test
    fun `a TIDE patch rejects a macro the voice does not have`() {
        assertFailsWith<IllegalArgumentException> { TidePatch("Bad", TideVoice.BONGO, mapOf("RATIO" to 0.5f)) }
    }

    @Test
    fun `scramble honors temperature and near`() {
        val voice = TideVoice.FLARE
        val near = TidePatch("X", voice, mapOf("FOLD" to 0.9f))
        assertEquals(0.9f, Tide.scramble(voice, Random(3), temperature = 0f, near = near)["FOLD"], "near seeds the roll")
    }
}

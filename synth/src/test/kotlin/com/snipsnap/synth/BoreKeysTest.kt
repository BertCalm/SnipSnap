package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BORE held ([BoreKeys]): every zone is the held note the LOOP is cut from, played from its first sample, with Round
 * 1.7's blend moved to the loop's end (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md, "R2: the held
 * keygroup, as built"). The claims, each pinned here: every zone of both voices and both layers holds Round 1.7's join;
 * the zone's loop is the LOOP's own cycle (same length, same correlation); the file's last frame runs on into the loop
 * start and everything before the blend is the held note untouched; the attack is the one-shot's; the soft layer is the
 * same note blown softer, darker at the same loudness; OVERBLOW, TUNE and HOLD do not move a zone; the render is the same
 * twice. Render times are measured and printed, not asserted.
 */
class BoreKeysTest {

    private companion object {
        /** One rendered layer of one zone, and how long it took. */
        class Zone(val voice: BoreVoice, val midi: Int, val layer: Int, val breath: Float, val held: BoreKeys.Held, val ms: Double) {
            val samples: FloatArray get() = held.note.snip.samples
            val start: Int get() = held.note.loopStartFrame.toInt()
            val label: String get() = "$voice ${Scales.nameOf(midi)} ${if (layer == 0) "soft" else "hard"} (BREATH %.2f)".format(java.util.Locale.ROOT, breath)
        }

        /** Every zone of both voices, both layers, at the defaults: 36 renders, shared by the tests below. */
        val defaults: List<Zone> by lazy {
            buildList {
                for (voice in BoreVoice.entries) for (midi in BoreKeys.padMidis(voice)) {
                    BoreKeys.layerBreaths(voice, Bore.defaults(voice)).forEachIndexed { layer, breath ->
                        val t0 = System.nanoTime()
                        val held = BoreKeys.padMeasured(voice, Bore.defaults(voice), midi, breath)
                        add(Zone(voice, midi, layer, breath, held, (System.nanoTime() - t0) / 1e6))
                    }
                }
            }
        }

        fun zone(voice: BoreVoice, midi: Int, layer: Int) = defaults.first { it.voice == voice && it.midi == midi && it.layer == layer }

        /** The join is a sample step like any other: no larger than the loop's own steepest ordinary one (a tenth over, for the 99.9th percentile), as `BoreLoopFuzzTest` holds the LOOP's. */
        const val WRAP_OVER_STEEPEST = 1.1

        fun db(x: Double) = 10 * log10(x + 1e-30)

        /** Normalised correlation of [a] and [b] over their first [n] frames, at lag 0. */
        fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
            var ab = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 0 until n) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
            return ab / sqrt(aa * bb + 1e-30)
        }

        fun loudnessOf(x: FloatArray, from: Int, to: Int) = Loudness.of(x.copyOfRange(from, to), 1, Dsp.RATE)

        /**
         * Where the power of 16,384 frames (0.37 s) of [x] from [from] sits, Hann-windowed: the 1.5 to 8 kHz share of 50 Hz to
         * 10 kHz in dB, and the spectral centroid over 50 Hz to 10 kHz in Hz.
         */
        fun brightness(x: FloatArray, from: Int): Pair<Double, Double> {
            val n = 16_384
            val re = FloatArray(n) { (x[from + it] * (0.5 - 0.5 * cos(2 * Math.PI * it / n))).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            var band = 0.0
            var all = 0.0
            var moment = 0.0
            for (k in 1 until n / 2) {
                val hz = k * Dsp.RATE.toDouble() / n
                val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                if (hz in 50.0..10_000.0) { all += p; moment += p * hz }
                if (hz in 1_500.0..8_000.0) band += p
            }
            return db(band / (all + 1e-30)) to moment / (all + 1e-30)
        }
    }

    private fun assertHolds(z: BoreKeys.Held, label: String) {
        val j = z.join
        val s = z.note.snip.samples
        val start = z.note.loopStartFrame.toInt()
        assertTrue(j.correlation >= Bore.LOOP_MIN_CORRELATION, "$label: the blended waves correlate only ${j.correlation}")
        assertTrue(j.blendDipDb >= -Bore.LOOP_MAX_DIP_DB, "$label: the loop dips ${j.blendDipDb} dB inside its blend")
        assertTrue(j.junctionStep <= WRAP_OVER_STEEPEST * j.steepestStep, "$label: the wrap's step is ${j.junctionStep / j.steepestStep} times the steepest ordinary step")
        assertTrue(s.all { it.isFinite() }, "$label: not finite")
        assertTrue(s.maxOf { abs(it) } <= BoreKeys.PEAK_CEILING + 1e-5f, "$label: peaks at ${s.maxOf { abs(it) }}, over the ${BoreKeys.PEAK_CEILING} ceiling")
        assertEquals(start, j.startFrames, "$label: the join's start is not the loop marker")
        assertEquals(s.size, start + j.lengthFrames, "$label: the file does not end where the loop does")
        assertTrue(start >= ((Bore.LOOP_START_SECONDS * Dsp.RATE).toInt() + Bore.LOOP_BLEND_FRAMES), "$label: the loop starts at $start, before the floor and the blend")
        assertTrue(j.lengthFrames in Bore.LOOP_LENGTH_LOW_FRAMES..Bore.LOOP_LENGTH_HIGH_FRAMES, "$label: the loop is ${j.lengthFrames} frames")
        assertEquals(1, z.note.snip.channels)
        assertEquals(Dsp.RATE, z.note.snip.sampleRate)
        // Loud where it is held: the loop at the melodic target, unless the attack's peak set the gain.
        val loud = loudnessOf(s, start, s.size)
        val limited = s.maxOf { abs(it) } >= BoreKeys.PEAK_CEILING - 1e-4f
        assertTrue(abs(20 * log10(loud / Dsp.MELODIC_LOUDNESS_TARGET)) < 0.05 || (limited && loud < Dsp.MELODIC_LOUDNESS_TARGET),
            "$label: the loop reads $loud against the target ${Dsp.MELODIC_LOUDNESS_TARGET} and the peak is not at the ceiling")
        val mean = (start until s.size).sumOf { s[it].toDouble() } / (s.size - start)
        assertTrue(abs(mean) < 0.01, "$label: the loop's mean is $mean")
    }

    @Test
    fun `the zones are nine minor thirds across each voice's range, and the soft layer blows 0_45 of the patch's breath`() {
        assertEquals((60..84 step 3).toList(), BoreKeys.padMidis(BoreVoice.FLUTE))
        assertEquals((48..72 step 3).toList(), BoreKeys.padMidis(BoreVoice.SAX))
        for (voice in BoreVoice.entries) {
            val midis = BoreKeys.padMidis(voice)
            assertEquals(Bore.rootMidi(voice), midis.first())
            assertEquals(Bore.rootMidi(voice) + Bore.TUNE_SEMITONES, midis.last(), "$voice: the top zone is the top of TUNE's range")
            assertEquals(listOf(0.6f * 0.45f, 0.6f), BoreKeys.layerBreaths(voice, Bore.defaults(voice)))
            assertEquals(listOf(0.45f, 1f), BoreKeys.layerBreaths(voice, mapOf("BREATH" to 1f)))
            assertEquals(listOf(0f, 0f), BoreKeys.layerBreaths(voice, mapOf("BREATH" to 0f)), "$voice: at BREATH 0 the two layers are one note")
            assertEquals(BoreKeys.layerBreaths(voice, emptyMap()), BoreKeys.layerBreaths(voice, mapOf("BREATH" to Float.NaN)))
        }
        assertEquals(listOf(1..63, 64..127), BoreKeys.VELOCITY_WINDOWS)
        assertFailsWith<IllegalArgumentException> { BoreKeys.zoneMacros(BoreVoice.FLUTE, emptyMap(), 59, 0.5f) }
        assertFailsWith<IllegalArgumentException> { BoreKeys.zoneMacros(BoreVoice.SAX, emptyMap(), 73, 0.5f) }
        assertFailsWith<IllegalArgumentException> { BoreKeys.zoneMacros(BoreVoice.SAX, emptyMap(), 60, 1.5f) }
        assertFailsWith<IllegalArgumentException> { BoreKeys.zoneMacros(BoreVoice.SAX, emptyMap(), 60, Float.NaN) }
        val m = BoreKeys.zoneMacros(BoreVoice.SAX, Bore.defaults(BoreVoice.SAX) + ("TUNE" to 1f), 51, 0.3f)
        assertEquals(51, Bore.midiFor(BoreVoice.SAX, m.getValue("TUNE")), "the zone's TUNE lands on its key")
        assertEquals(0.3f, m.getValue("BREATH"))
        assertEquals(Bore.LOOP_HOLD, m.getValue("HOLD"))
    }

    @Test
    fun `every zone of both voices, both layers, at the defaults holds its join and plays its key, clean and under the ceiling`() {
        for (z in defaults) {
            assertHolds(z.held, z.label)
            // The key: the loop's mean pitch, as the LOOP's own test reads it (the pin leaves a few cents; 7 is its bound).
            val hz = Keys.midiHz(z.midi)
            val cents = BoreMeasure.centsTrack(z.samples, hz, z.start.toFloat() / Dsp.RATE, z.samples.size.toFloat() / Dsp.RATE, Dsp.RATE).average()
            assertTrue(abs(cents) <= 7.0, "${z.label}: the loop plays $cents cents from its key")
        }
        // The report: per voice and layer, the weakest join, the deepest dip, the widest wrap step, the loop start and length,
        // the render time and the size at PCM_24 (3 bytes a frame, the package writer's depth).
        for (voice in BoreVoice.entries) {
            val all = defaults.filter { it.voice == voice }
            for (layer in 0..1) {
                val zs = all.filter { it.layer == layer }
                println("BORE held $voice ${if (layer == 0) "soft" else "hard"}: weakest correlation %.4f (%s), deepest dip %.2f dB (%s), widest wrap step %.2fx, loop start %.2f to %.2f s, loop %.3f to %.3f s, ceiling-limited %d of %d".format(
                    java.util.Locale.ROOT,
                    zs.minOf { it.held.join.correlation }, Scales.nameOf(zs.minBy { it.held.join.correlation }.midi),
                    zs.minOf { it.held.join.blendDipDb }, Scales.nameOf(zs.minBy { it.held.join.blendDipDb }.midi),
                    zs.maxOf { it.held.join.junctionStep / it.held.join.steepestStep },
                    zs.minOf { it.start } / Dsp.RATE.toDouble(), zs.maxOf { it.start } / Dsp.RATE.toDouble(),
                    zs.minOf { it.held.join.lengthFrames } / Dsp.RATE.toDouble(), zs.maxOf { it.held.join.lengthFrames } / Dsp.RATE.toDouble(),
                    zs.count { it.samples.maxOf { s -> abs(s) } >= BoreKeys.PEAK_CEILING - 1e-4f }, zs.size,
                ))
            }
            val ms = all.map { it.ms }.sorted()
            val frames = all.sumOf { it.samples.size.toLong() }
            println("BORE held $voice: %d renders, %.0f ms median, %.0f ms max, %.0f ms min a layer, %.1f s for the instrument; %.2f to %.2f s a sample, %.1f MB at PCM_24".format(
                java.util.Locale.ROOT, ms.size, ms[ms.size / 2], ms.last(), ms.first(), ms.sum() / 1000,
                all.minOf { it.samples.size } / Dsp.RATE.toDouble(), all.maxOf { it.samples.size } / Dsp.RATE.toDouble(), frames * 3 / 1e6))
            for (z in all) println("  ${z.label}: %.0f ms, loop start %.3f s, loop %.3f s, correlation %.4f, dip %.2f dB, wrap %.2fx, loop level %+.2f dB against the target, peak %.3f".format(
                java.util.Locale.ROOT, z.ms, z.start / Dsp.RATE.toDouble(), z.held.join.lengthFrames / Dsp.RATE.toDouble(),
                z.held.join.correlation, z.held.join.blendDipDb, z.held.join.junctionStep / z.held.join.steepestStep,
                20 * log10(loudnessOf(z.samples, z.start, z.samples.size) / Dsp.MELODIC_LOUDNESS_TARGET), z.samples.maxOf { abs(it) }))
        }
    }

    @Test
    fun `a zone's loop is the LOOP's own cycle - the same length and correlation, cut from the same note, one blend later`() {
        // The length search is the LOOP's call with the LOOP's arguments, so the zone's loop is the LOOP preset's cycle rotated
        // to start where the attack arrives. Round 1.7's 1,071 measured corners therefore speak for the zones too.
        for ((voice, midi) in listOf(BoreVoice.SAX to 48, BoreVoice.SAX to 66, BoreVoice.FLUTE to 60, BoreVoice.FLUTE to 84)) {
            val z = zone(voice, midi, 1)
            val tune = (midi - Bore.rootMidi(voice)) / Bore.TUNE_SEMITONES.toFloat()
            val loop = Bore.renderLoopMeasured(voice, Bore.defaults(voice) + mapOf("TUNE" to tune, "HOLD" to 1f)).join
            val held = z.held.join
            assertEquals(loop.lengthFrames, held.lengthFrames, "${z.label}: the zone's loop is not the LOOP's length")
            assertEquals(loop.correlation, held.correlation, 0.0, "${z.label}: the zone's join is not the LOOP's correlation")
            assertEquals(loop.settledFrames, held.settledFrames, "${z.label}: the zone's note settles elsewhere than the LOOP's")
            assertEquals(loop.startFrames + Bore.LOOP_BLEND_FRAMES, held.startFrames, "${z.label}: the zone's loop does not start one blend after the LOOP's stretch")
            assertEquals(loop.blendDipDb, held.blendDipDb, 0.05, "${z.label}: the zone's blend dips ${held.blendDipDb} dB, the LOOP's ${loop.blendDipDb}")
        }
    }

    @Test
    fun `the wrap - the file's last frame runs on into the loop start, and everything before the blend is the held note untouched`() {
        for ((voice, midi, layer) in listOf(Triple(BoreVoice.SAX, 48, 0), Triple(BoreVoice.SAX, 72, 1), Triple(BoreVoice.FLUTE, 72, 1))) {
            val z = zone(voice, midi, layer)
            val x = Bore.heldNote(voice, BoreKeys.zoneMacros(voice, Bore.defaults(voice), midi, z.breath))
            val s = z.samples
            val g = z.held.gain
            val tail = s.size - Bore.LOOP_BLEND_FRAMES
            for (i in 0 until tail) if (s[i] != x[i] * g) error("${z.label}: frame $i is not the held note's (${s[i]} against ${x[i] * g})")
            // The last frame is the loop start's predecessor in the note, so the player's wrap from the end to the loop start
            // is two consecutive frames of the note. Exactly: at the last frame the loop's own wave weighs cos(pi/2), 6e-17,
            // far under a float's step. A tolerance would hide a weight one index early (1.2e-4 of the loop's wave there):
            // the two frames either side of a real wrap match too closely for a loose check to see it.
            assertEquals(x[z.start - 1] * g, s[s.size - 1], 0f, "${z.label}: the last frame is not the frame before the loop start")
            assertEquals(x[z.start] * g, s[z.start], 0f, "${z.label}: the loop start is not the note's own frame")
        }
        // On a synthetic note with known answers: the tail runs into the frame before the loop start, the loop's own wave comes
        // first, the frames before the blend are untouched, and the power holds for two uncorrelated waves where a linear
        // fade dips.
        val rng = Random(7)
        val n = 40_000
        val blend = 8_000
        val a = FloatArray(n) { rng.nextFloat() - 0.5f }
        val start = 10_000
        val length = 20_000
        val out = BoreKeys.assembleTail(a, start, length, blend, correlation = 0.0)
        assertEquals(start + length, out.size)
        assertEquals(a[start - 1], out[out.size - 1], 1e-6f, "the tail does not end on the frame before the loop start")
        assertContentEquals(a.copyOf(start + length - blend), out.copyOf(start + length - blend), "a frame before the blend moved")
        assertEquals(a[start + length - blend], out[start + length - blend], 1e-3f, "the blend does not start on the loop's own wave")
        fun power(x: FloatArray, from: Int, k: Int) = (from until from + k).sumOf { x[it].toDouble() * x[it] } / k
        val outside = power(out, start, 8_000)
        val middle = power(out, start + length - blend / 2 - 400, 800)
        assertTrue(abs(db(middle / outside)) < 0.7, "uncorrelated waves dip ${db(middle / outside)} dB at the middle of the blend")
        val linear = FloatArray(blend) { val t = (it + 1f) / blend; (1f - t) * a[start + length - blend + it] + t * a[start - blend + it] }
        assertTrue(db(power(linear, blend / 2 - 400, 800) / outside) < -2.0, "a linear fade of uncorrelated waves does not dip, so the check cannot fail")
        val c = FloatArray(n) { 0.3f }
        assertTrue(BoreKeys.assembleTail(c, start, length, blend, correlation = 1.0).all { abs(it - 0.3f) < 1e-5f }, "the same wave in both passes did not come through at its own level")
        assertFailsWith<IllegalArgumentException> { BoreKeys.assembleTail(a, blend - 1, length, blend, 0.5) }
        assertFailsWith<IllegalArgumentException> { BoreKeys.assembleTail(a, start, n, blend, 0.5) }
    }

    @Test
    fun `the attack is the note's own - the zone opens as the one-shot of the same key opens`() {
        // The zone is the one-shot's own blow with the gate stretched: the raw samples are the same over the attack, and the
        // conditioning differs only by what it reads over the whole note. Compared with the one-shot the pad plays (the patch's
        // own HOLD, 0.45 at the defaults, whose vibrato is 9 cents to the zone's 10) over the opening 0.3 s. The control: the
        // one-shot three semitones up does not match.
        val n = (0.3 * Dsp.RATE).toInt()
        var weakest = 1.0
        var strongestControl = -1.0
        for (voice in BoreVoice.entries) {
            val midis = BoreKeys.padMidis(voice)
            for (midi in listOf(midis.first(), midis[midis.size / 2], midis.last())) for (layer in 0..1) {
                val z = zone(voice, midi, layer)
                fun shot(key: Int) = Bore.render(voice, Bore.defaults(voice) + mapOf("TUNE" to (key - Bore.rootMidi(voice)) / Bore.TUNE_SEMITONES.toFloat(), "BREATH" to z.breath)).samples
                val r = correlation(z.samples, shot(midi), n)
                val control = correlation(z.samples, shot(if (midi + 3 <= midis.last()) midi + 3 else midi - 3), n)
                println("BORE held ${z.label}: the opening 0.3 s correlates %.4f with its one-shot, %.4f with the one-shot three semitones off".format(java.util.Locale.ROOT, r, control))
                weakest = minOf(weakest, r)
                strongestControl = maxOf(strongestControl, control)
                assertTrue(r >= 0.98, "${z.label}: the zone's opening correlates only $r with the one-shot of its key")
                assertTrue(control < 0.3, "${z.label}: the zone's opening correlates $control with a different key's one-shot, so the check cannot fail")
            }
        }
        println("BORE held attack: weakest %.4f against its one-shot, strongest control %.4f".format(java.util.Locale.ROOT, weakest, strongestControl))
    }

    @Test
    fun `the soft layer is the same note blown softer - a different sound, never brighter, at the same loudness on its loop`() {
        // Velocity is timbre: the player applies the key's gain, so both layers are levelled on their loops and the soft one is
        // told apart by its colour: where its power sits ([brightness], the loop's first 0.37 s) and how much breath lies
        // between its harmonics. Per zone the soft layer is never brighter; per voice it is darker on average. How far apart
        // the two are is the audition's question (SAX's layers are much closer on this measure than FLUTE's).
        for (voice in BoreVoice.entries) {
            val gaps = ArrayList<Double>()
            for (midi in BoreKeys.padMidis(voice)) {
                val soft = zone(voice, midi, 0)
                val hard = zone(voice, midi, 1)
                val where = "$voice ${Scales.nameOf(midi)}"
                assertFalse(soft.samples.contentEquals(hard.samples), "$where: the soft layer is the hard one")
                val ls = loudnessOf(soft.samples, soft.start, soft.samples.size)
                val lh = loudnessOf(hard.samples, hard.start, hard.samples.size)
                val limited = listOf(soft, hard).any { it.samples.maxOf { s -> abs(s) } >= BoreKeys.PEAK_CEILING - 1e-4f }
                assertTrue(limited || abs(20 * log10(ls / lh)) < 0.1, "$where: the layers' loops differ by ${20 * log10(ls / lh)} dB")
                val (bs, cs) = brightness(soft.samples, soft.start)
                val (bh, ch) = brightness(hard.samples, hard.start)
                val hz = Keys.midiHz(midi)
                val ns = BoreMeasure.betweenHarmonicsDb(soft.samples, hz, soft.start.toFloat() / Dsp.RATE, Dsp.RATE)
                val nh = BoreMeasure.betweenHarmonicsDb(hard.samples, hz, hard.start.toFloat() / Dsp.RATE, Dsp.RATE)
                println("BORE held $where: 1.5-8 kHz share soft %.2f dB, hard %.2f dB; centroid soft %.0f Hz, hard %.0f Hz; between the harmonics soft %.1f dB, hard %.1f dB; loop loudness soft %.4f, hard %.4f".format(
                    java.util.Locale.ROOT, bs, bh, cs, ch, ns, nh, ls, lh))
                assertTrue(bs <= bh + 0.1, "$where: the soft layer is brighter than the hard ($bs dB against $bh)")
                assertTrue(cs < ch, "$where: the soft layer's centroid ($cs Hz) is not under the hard's ($ch Hz)")
                // The breath: BREATH's turbulence is under the soft note, 2.5 to 5.5 dB less between its harmonics as measured.
                assertTrue(ns <= nh - 1.5, "$where: the soft layer has $ns dB between its harmonics, the hard $nh: not a softer breath")
                gaps.add(bh - bs)
            }
            println("BORE held $voice: the soft layer sits %.2f dB under the hard in 1.5-8 kHz on average (%.2f to %.2f)".format(java.util.Locale.ROOT, gaps.average(), gaps.min(), gaps.max()))
            assertTrue(gaps.average() >= 0.3, "$voice: the soft layer is only ${gaps.average()} dB darker on average")
        }
    }

    @Test
    fun `the corners hold their join - BREATH, LIP and CHIFF at their ends, at the lowest and highest zone of each voice`() {
        val corners = listOf(
            mapOf("BREATH" to 0f), mapOf("BREATH" to 1f), mapOf("CHIFF" to 0f, "BREATH" to 0f), mapOf("CHIFF" to 1f),
            mapOf("LIP" to 0f, "BREATH" to 1f), mapOf("LIP" to 1f, "BREATH" to 0f), mapOf("LIP" to 0f, "BREATH" to 0f), mapOf("LIP" to 1f, "BREATH" to 1f),
        )
        for (voice in BoreVoice.entries) {
            var weakest = 1.0
            var deepest = 0.0
            var latest = 0
            var renders = 0
            val t0 = System.nanoTime()
            val midis = BoreKeys.padMidis(voice)
            for (midi in listOf(midis.first(), midis.last())) for (corner in corners) {
                val macros = Bore.defaults(voice) + corner
                for (breath in BoreKeys.layerBreaths(voice, macros).distinct()) {
                    val h = BoreKeys.padMeasured(voice, macros, midi, breath)
                    val label = "$voice ${Scales.nameOf(midi)} $corner at BREATH $breath"
                    assertHolds(h, label)
                    weakest = minOf(weakest, h.join.correlation)
                    deepest = minOf(deepest, h.join.blendDipDb)
                    latest = maxOf(latest, h.join.startFrames)
                    renders++
                }
            }
            println("BORE held $voice corners: %d renders in %.1f s, weakest correlation %.4f, deepest dip %.2f dB, latest loop start %.2f s".format(
                java.util.Locale.ROOT, renders, (System.nanoTime() - t0) / 1e9, weakest, deepest, latest / Dsp.RATE.toDouble()))
        }
    }

    @Test
    fun `a zone ignores OVERBLOW, TUNE and HOLD, and renders the same twice`() {
        // OVERBLOW is a one-shot's gesture: a held note never cracks, so every zone plays its key. TUNE is set by the zone and
        // HOLD by the LOOP's vibrato, so the patch's own do not move it. Each render is compared with the zone at the defaults.
        val flute = zone(BoreVoice.FLUTE, 72, 1)
        val cracked = BoreKeys.padLayer(BoreVoice.FLUTE, Bore.defaults(BoreVoice.FLUTE) + mapOf("OVERBLOW" to 1f, "TUNE" to 0f, "HOLD" to 0.98f), 72, flute.breath)
        assertContentEquals(flute.samples, cracked.snip.samples, "a cracked FLUTE patch at another TUNE and HOLD makes another zone")
        val sax = zone(BoreVoice.SAX, 48, 0)
        val again = BoreKeys.padLayer(BoreVoice.SAX, Bore.defaults(BoreVoice.SAX), 48, sax.breath)
        assertContentEquals(sax.samples, again.snip.samples, "two renders of the same zone differ")
        assertEquals(sax.held.note.loopStartFrame, again.loopStartFrame)
        val moved = BoreKeys.padLayer(BoreVoice.SAX, Bore.defaults(BoreVoice.SAX) + mapOf("TUNE" to 1f, "HOLD" to 0f, "OVERBLOW" to 1f), 48, sax.breath)
        assertContentEquals(sax.samples, moved.snip.samples, "a SAX patch at another TUNE and HOLD makes another zone")
        // The control: a different CHIFF makes a different zone, so the comparison can fail.
        val chiff = BoreKeys.padLayer(BoreVoice.SAX, Bore.defaults(BoreVoice.SAX) + ("CHIFF" to 0.9f), 48, sax.breath)
        assertFalse(sax.samples.contentEquals(chiff.snip.samples), "CHIFF does not change the zone, so the comparison cannot fail")
    }

    @Test
    fun `a held note whose join does not hold is refused by name, and a cancelled render stops`() {
        // A steady noise has settled and has no wave to match: the two waves of the blend barely correlate.
        val rng = Random(3)
        val noise = FloatArray((4.6 * Dsp.RATE).toInt()) { (rng.nextFloat() - 0.5f) * 0.2f }
        val refused = assertFailsWith<IllegalArgumentException> { BoreKeys.cutHeld(noise, noise.size - 100, "BORE SAX C3 at BREATH 0.27") }
        assertTrue(refused.message!!.contains("BORE SAX C3 at BREATH 0.27") && refused.message!!.contains("does not hold"), "the refusal does not name the zone and the fault: ${refused.message}")
        assertFailsWith<CancellationException> { BoreKeys.padLayer(BoreVoice.SAX, Bore.defaults(BoreVoice.SAX), 48, 0.6f) { true } }
    }
}

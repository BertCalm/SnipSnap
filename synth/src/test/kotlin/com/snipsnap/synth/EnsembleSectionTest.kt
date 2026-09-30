package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ENSEMBLE's SECTION macro: the chorus at 0, six independent players at 1.
 *
 * Two promises are pinned here and one property is measured. The promises: SECTION at its default is the chorus
 * byte for byte (the bytes themselves are [EnsembleBytesTest]'s), and every other macro keeps meaning what it meant.
 * The property: the players are independent where the chorus is locked, and their cost (the mono fold's hollows, the
 * stereo image, the onset) is printed and bounded just past what was measured, never reused from the chorus's own
 * bounds in FxTest, which the players are not built to meet.
 */
class EnsembleSectionTest {

    private fun tone(hz: Float, seconds: Float, rate: Int = 44_100): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n) { i -> ((2.0 * ((i.toDouble() * hz / rate) % 1.0) - 1.0) * 0.5).toFloat() }, 1, rate)
    }

    private fun doubled(s: Snip): Snip = Snip(FloatArray(s.frameCount * 2) { s.samples[it / 2] }, 2, s.sampleRate)

    private fun hardLeft(s: Snip): Snip = Snip(FloatArray(s.frameCount * 2) { if (it % 2 == 0) s.samples[it / 2] else 0f }, 2, s.sampleRate)

    private fun channel(s: Snip, ch: Int): FloatArray = FloatArray(s.frameCount) { s.samples[it * s.channels + ch] }

    private fun section(value: Float, extra: Map<String, Float> = emptyMap()): (Snip) -> Snip = { Ensemble.process(it, extra + ("SECTION" to value)) }

    private val saw = tone(130.81f, 1.5f)
    private val macroSets = listOf(
        emptyMap(),
        mapOf("DEPTH" to 1f, "RATE" to 0f, "WIDTH" to 0f),
        mapOf("DEPTH" to 0.25f, "RATE" to 1f, "WIDTH" to 0.5f),
    )

    // ---------------------------------------------------------------- the promises

    @Test
    fun `SECTION absent, zero and at the off point are the chorus, bit for bit`() {
        for (signal in listOf(saw, doubled(saw), hardLeft(saw))) for (macros in macroSets) {
            val shipped = Ensemble.process(signal, macros)
            for (off in listOf(0f, 0.0005f, Ensemble.SECTION_OFF)) {
                val out = Ensemble.process(signal, macros + ("SECTION" to off))
                assertEquals(shipped.channels, out.channels)
                assertContentEquals(shipped.samples, out.samples, "SECTION $off on ${signal.channels} channels with $macros is not the chorus")
            }
        }
    }

    @Test
    fun `a recipe written before SECTION existed reads back without it and is the chorus`() {
        val text = """{"fx":1,"reverse":false,"ensemble":{"DEPTH":0.6,"WIDTH":1.0}}"""
        val chain = FxChain.fromJsonText(text)
        assertEquals(null, chain.section("ensemble")?.get("SECTION"))
        assertTrue("SECTION" !in chain.toJsonText(), "reading an old recipe and writing it back added the key")
        assertContentEquals(Ensemble.process(saw, mapOf("DEPTH" to 0.6f, "WIDTH" to 1f)).samples, chain.process(saw).samples)
    }

    @Test
    fun `SECTION is the last macro, off by default, and leaves the first three random draws where they were`() {
        assertEquals(listOf("DEPTH", "RATE", "WIDTH", "SECTION"), Ensemble.MACROS.map { it.name })
        assertEquals(0f, Ensemble.defaults().getValue("SECTION"))
        assertEquals(0f, Ensemble.MACROS.last().neutral)
        val rolled = Ensemble.scramble(Random(0))
        val r = Random(0)
        for (name in listOf("DEPTH", "RATE", "WIDTH")) assertEquals(r.nextFloat(), rolled.getValue(name), "the $name draw moved")
        assertEquals(r.nextFloat(), rolled.getValue("SECTION"))
    }

    @Test
    fun `the crossfade is equal power and exactly the players at one`() {
        var s = 0.01f
        while (s < 1f) {
            val w = Ensemble.sectionWeights(s)
            assertEquals(1f, w[0] * w[0] + w[1] * w[1], 1e-6f, "power at SECTION $s")
            s += 0.07f
        }
        assertContentEquals(floatArrayOf(0f, 1f), Ensemble.sectionWeights(1f))
        assertEquals(0.7071f, Ensemble.sectionWeights(0.5f)[0], 1e-4f)
        assertEquals(0.7071f, Ensemble.sectionWeights(0.5f)[1], 1e-4f)
    }

    // ---------------------------------------------------------------- the contract of a section

    @Test
    fun `above the off point it is a section - finite, the input's length, its peak, deterministic, and no state leaks between renders`() {
        val shipped = Ensemble.process(saw, emptyMap())
        for (value in listOf(0.25f, 0.5f, 1f)) {
            val out = section(value)(saw)
            assertEquals(saw.frameCount, out.frameCount, "SECTION $value changed the length")
            assertEquals(2, out.channels, "a mono pad widens at WIDTH 1, as the chorus does")
            assertTrue(out.samples.all { it.isFinite() })
            assertEquals(saw.peak(), out.peak(), 1e-5f, "SECTION $value is not peak-matched")
            assertNotEquals(shipped.samples.toList(), out.samples.toList(), "SECTION $value is still the chorus")
            assertContentEquals(out.samples, section(value)(saw).samples, "SECTION $value is not deterministic")
        }
        section(1f)(saw)
        assertContentEquals(shipped.samples, Ensemble.process(saw, emptyMap()).samples, "a SECTION 1 render left state behind")
    }

    @Test
    fun `the end of a render is faded, so a cut note does not click`() {
        val out = section(1f)(saw)
        assertEquals(0f, out.samples[out.samples.size - 1])
        assertEquals(0f, out.samples[out.samples.size - 2])
    }

    @Test
    fun `every sample of a short render is the same sample of a longer one`() {
        val short = tone(196f, 2f)
        val long = tone(196f, 4f)
        val a = EnsemblePlayers.render(short, 0.5f, 1f, 1f)
        val b = EnsemblePlayers.render(long, 0.5f, 1f, 1f)
        // The render is causal and keeps no state from the input's length, so every sample of the short render is the same sample of the long one.
        for (i in 0 until a.size) assertEquals(a[i], b[i], "sample $i moved with the snip's length")
    }

    @Test
    fun `DEPTH is the players' depth, linearly, and zero is six static taps`() {
        val base = EnsemblePlayers.delayTracks(0f, 1f, 4, 1000)
        for (track in base) assertTrue(track.all { it == track[0] }, "at DEPTH 0 a player still moves")
        val quarter = EnsemblePlayers.delayTracks(0.25f, 1f, 4, 1000)
        val half = EnsemblePlayers.delayTracks(0.5f, 1f, 4, 1000)
        for (p in 0 until EnsemblePlayers.PLAYERS) for (n in quarter[p].indices) {
            val a = quarter[p][n] - base[p][n]
            val b = half[p][n] - base[p][n]
            assertEquals(2.0 * a, b, 1e-12, "player $p at $n: DEPTH is not linear in the players' swing")
        }
        // The onset table: the earliest player at the chorus's own 7.5 ms, the last 25.5 ms later.
        assertEquals(7.5, base.first()[0] * 1000.0, 1e-9)
        assertEquals(33.0, base.last()[0] * 1000.0, 1e-9)
    }

    @Test
    fun `DEPTH reaches the render - at zero a steady tone holds a steady level, at the default it does not`() {
        // [delayTracks] is what the tests measure and [render] is what is heard; they share [EnsemblePlayers]'s one law, and this is the check on the render's side of it.
        val sine = Snip(FloatArray(88_200) { (0.5 * sin(2 * PI * 440.0 * it / 44_100)).toFloat() }, 1, 44_100)
        fun levelSpreadDb(depth: Float): Double {
            val out = EnsemblePlayers.render(sine, depth, 1f, 0f)
            val levels = (22_050 until out.size - 441 step 441).map { start ->
                var e = 0.0
                for (n in start until start + 441) e += out[n].toDouble() * out[n]
                10.0 * kotlin.math.log10(e / 441 + 1e-12)
            }
            return levels.max() - levels.min()
        }
        val still = levelSpreadDb(0f)
        val moving = levelSpreadDb(0.5f)
        println("SECTION level spread of a steady 440 Hz tone, dB: DEPTH 0 ${"%.3f".format(java.util.Locale.ROOT, still)}, DEPTH 0.5 ${"%.2f".format(java.util.Locale.ROOT, moving)}")
        assertTrue(still < 0.4, "at DEPTH 0 a steady tone's level still moves: $still dB")
        assertTrue(moving > 3.0, "at DEPTH 0.5 a steady tone's level barely moves: $moving dB")
    }

    @Test
    fun `RATE and WIDTH mean what they meant, for the players`() {
        val renders = listOf(0f, 0.5f, 1f).map { rate -> section(1f, mapOf("RATE" to rate))(saw).samples.toList() }
        assertEquals(3, renders.toSet().size, "RATE left the players' sound alone")
        // Direction: a faster RATE is more turns of every player's wobble in the same time.
        fun turns(rate: Float): Int {
            val tracks = EnsemblePlayers.delayTracks(1f, Ensemble.rateMultiplier(rate), 30, 1000)
            var count = 0
            for (track in tracks) {
                val mean = track.average()
                for (n in 1 until track.size) if ((track[n] - mean) * (track[n - 1] - mean) < 0) count++
            }
            return count
        }
        val slow = turns(0f)
        val fast = turns(1f)
        println("SECTION zero crossings of the six delay tracks over 30 s: RATE 0 $slow, RATE 1 $fast")
        assertTrue(fast > 2 * slow, "RATE 1 is not clearly faster than RATE 0: $fast against $slow")
        fun correlation(width: Float): Double {
            val out = section(1f, mapOf("WIDTH" to width))(saw)
            val l = channel(out, 0); val r = channel(out, 1)
            var sl = 0.0; var sr = 0.0; var slr = 0.0
            for (i in l.indices) { sl += l[i].toDouble() * l[i]; sr += r[i].toDouble() * r[i]; slr += l[i].toDouble() * r[i] }
            return slr / kotlin.math.sqrt(sl * sr)
        }
        assertTrue(correlation(0.25f) > correlation(0.5f) && correlation(0.5f) > correlation(1f), "a wider pair should be less alike")
        assertEquals(1, section(1f, mapOf("WIDTH" to 0f))(saw).channels, "WIDTH 0 keeps a mono pad mono")
    }

    @Test
    fun `the stereo clauses hold for the players`() {
        val widened = section(1f)(saw)
        assertContentEquals(widened.samples, section(1f)(doubled(saw)).samples, "a doubled pair should come out as the widened mono")
        val flat = section(1f, mapOf("WIDTH" to 0f))(saw)
        val twoCopies = section(1f, mapOf("WIDTH" to 0f))(doubled(saw))
        assertEquals(2, twoCopies.channels)
        assertContentEquals(flat.samples, channel(twoCopies, 0), "WIDTH 0 on a doubled pair is the mono render, per channel")
        for (width in listOf(0f, 0.5f, 1f)) {
            val left = section(1f, mapOf("WIDTH" to width))(hardLeft(saw))
            assertTrue(channel(left, 1).all { it == 0f }, "a hard-left input leaked into the right channel at WIDTH $width")
            assertTrue(channel(left, 0).any { it != 0f })
        }
    }

    @Test
    fun `the stereo clauses hold across the crossfade too`() {
        for (z in listOf(0.3f, 0.7f)) {
            assertContentEquals(section(z)(saw).samples, section(z)(doubled(saw)).samples, "a doubled pair should come out as the widened mono at SECTION $z")
            for (width in listOf(0f, 0.5f, 1f)) {
                val left = section(z, mapOf("WIDTH" to width))(hardLeft(saw))
                assertTrue(channel(left, 1).all { it == 0f }, "a hard-left input leaked into the right channel at SECTION $z, WIDTH $width")
                assertTrue(channel(left, 0).any { it != 0f })
            }
        }
    }

    @Test
    fun `the players never outrun their line at the extremes of DEPTH and RATE`() {
        for (rate in listOf(0f, 0.5f, 1f)) {
            val tracks = EnsemblePlayers.delayTracks(1f, Ensemble.rateMultiplier(rate), 60, 1000)
            val low = tracks.minOf { it.min() }
            val high = tracks.maxOf { it.max() }
            println("SECTION delay range at DEPTH 1, RATE $rate: ${"%.2f".format(java.util.Locale.ROOT, low * 1000)} .. ${"%.2f".format(java.util.Locale.ROOT, high * 1000)} ms")
            assertTrue(low > 0.001, "a player's delay fell under 1 ms at RATE $rate: $low")
            assertTrue(high < 0.05, "a player's delay passed 50 ms (the line is 60) at RATE $rate: $high")
        }
    }

    @Test
    fun `at the extremes of DEPTH and RATE a render stays finite, the input's length and its peak`() {
        // The delay lines are held by the read clamp in [EnsemblePlayers.render] (2 samples to the line's length less 2), not by the noise clamp
        // alone: the clamp bounds the wander, and the low side of a delay can still fall near the floor at DEPTH 1.
        val long = tone(130.81f, 8f)
        for (depth in listOf(0f, 1f)) for (rate in listOf(0f, 1f)) {
            val out = section(1f, mapOf("DEPTH" to depth, "RATE" to rate))(long)
            assertEquals(long.frameCount, out.frameCount)
            assertTrue(out.samples.all { it.isFinite() }, "DEPTH $depth RATE $rate is not finite")
            assertEquals(long.peak(), out.peak(), 1e-5f, "DEPTH $depth RATE $rate is not peak-matched")
        }
    }

    // ---------------------------------------------------------------- the door from the pad sheet

    @Test
    fun `the sectioned character is the section at its own depth, and AMT fades it toward the chorus and then nothing`() {
        assertEquals(mapOf("DEPTH" to 0.5f, "WIDTH" to 1f, "SECTION" to 1f), Treatments.chain("sectioned", 1f).section("ensemble"))
        val half = Treatments.chain("sectioned", 0.5f).section("ensemble")!!
        assertEquals(0.25f, half.getValue("DEPTH"), 1e-6f)
        assertEquals(0.5f, half.getValue("WIDTH"), 1e-6f)
        assertEquals(0.5f, half.getValue("SECTION"), 1e-6f)
        assertTrue(Treatments.chain("sectioned", 0f).isBypass, "AMT 0 is the section off altogether")
        // The fade never invents the key for the chorus character: an ensembled pad stays the chorus at every AMT.
        for (amount in listOf(0.2f, 0.5f, 0.7f)) {
            assertTrue("SECTION" !in Treatments.chain("ensembled", amount).section("ensemble")!!, "AMT $amount gave the chorus a SECTION")
        }
    }

    @Test
    fun `sectioning a pad widens it, differs from ensembling it, and records the treatment`() {
        val sectioned = Treatments.apply("sectioned", saw)
        val ensembled = Treatments.apply("ensembled", saw)
        assertEquals(2, sectioned.snip.channels)
        assertNotEquals(ensembled.snip.samples.toList(), sectioned.snip.samples.toList(), "the two characters are the same sound")
        assertTrue("sectioned" in sectioned.recipe.toString(), "the recipe does not name the treatment")
    }

    // ---------------------------------------------------------------- the property, measured

    @Test
    fun `the pad sheet's default tap is the blend of chorus and players, and its fold is recorded`() {
        // A chip tap at its default AMT is not SECTION 1: it is DEPTH .35, WIDTH .7, SECTION .7, the chorus still in it at cos(63 deg) of the level.
        val macros = Treatments.chain("sectioned", 0.7f).section("ensemble")!!
        assertEquals(mapOf("DEPTH" to 0.35f, "WIDTH" to 0.7f, "SECTION" to 0.7f), macros.mapValues { (it.value * 1000).toInt() / 1000f })
        val folds = listOf(130.81f, 261.63f).map { FoldMeter.report(Ensemble.process(tone(it, 2f), macros)) }
        println("SECTION chip AMT 0.7 fold C3 loss ${"%.2f".format(java.util.Locale.ROOT, folds[0].lossDb)} L/R ${"%.2f".format(java.util.Locale.ROOT, folds[0].correlation)} pump ${"%.2f".format(java.util.Locale.ROOT, folds[0].pumpDb)}; C4 loss ${"%.2f".format(java.util.Locale.ROOT, folds[1].lossDb)} L/R ${"%.2f".format(java.util.Locale.ROOT, folds[1].correlation)} pump ${"%.2f".format(java.util.Locale.ROOT, folds[1].pumpDb)}")
        // Measured: -0.53 / -0.18 dB, L/R 0.72 / 0.89, pump 0.42 / 0.42 dB. The upper saw's pair is nearly mono at this tap (WIDTH 0.7 on a voice with few low partials).
        for (f in folds) assertTrue(f.lossDb > -0.8 && f.pumpDb < 0.8 && f.correlation < 0.95, "the chip's default tap folds worse than measured: loss ${f.lossDb} pump ${f.pumpDb} L/R ${f.correlation}")
    }

    @Test
    fun `the bass stays one steady voice across the crossfade - the chorus bed and the anchor do not cancel`() {
        // The chorus bed's bass is a copy at 7.5 ms and the anchor's is a copy at 17.7: two coherent copies with a 10 ms offset cancel
        // at 49 Hz and its odd multiples (-10.6 dB at 50 Hz, -15.6 at 130 Hz before the bed gave up its bass and the anchor kept its delay).
        val tones = listOf(50.0, 100.0, 130.0, 165.0, 200.0, 260.0)
        for (z in listOf(0.05f, 0.3f, 0.5f, 0.7f, 0.85f, 0.97f)) {
            fun blend(snip: Snip): Snip = Ensemble.process(snip, mapOf("DEPTH" to 0.35f, "WIDTH" to 0.7f, "SECTION" to z))
            val rows = tones.map { hz -> hz to SectionMeter.bassFold(hz, ::blend) }
            println("SECTION crossfade $z bass fold dB vs dry, mean(worst 250 ms window): " + rows.joinToString("  ") { (hz, r) -> "${hz.toInt()} Hz ${"%.1f".format(java.util.Locale.ROOT, r.first)}(${"%.1f".format(java.util.Locale.ROOT, r.second)})" })
            // Measured: within -5.0 mean and -7.7 worst from 0.05 to 0.85; at 0.97 the anchor is half way through its move from the bed's delay to its own, and 165 Hz reaches -5.4 (-10.8).
            val (meanFloor, worstFloor) = if (z < 0.9f) -5.3 to -8.2 else -5.7 to -11.3
            for ((hz, r) in rows) assertTrue(r.first >= meanFloor && r.second >= worstFloor, "SECTION $z folds a $hz Hz sine to mean ${r.first} dB, worst ${r.second} dB")
        }
    }

    @Test
    fun `the players are independent where the chorus is locked`() {
        val chorus = SectionMeter.pitchCorrelation(SectionMeter.chorusTracks(60, 1000), 1000)
        val players = SectionMeter.pitchCorrelation(EnsemblePlayers.delayTracks(0.5f, 1f, 60, 1000), 1000)
        println("SECTION pitch-deviation |rho| mean/max over 60 s: chorus ${"%.3f".format(java.util.Locale.ROOT, chorus.first)}/${"%.3f".format(java.util.Locale.ROOT, chorus.second)}, players ${"%.3f".format(java.util.Locale.ROOT, players.first)}/${"%.3f".format(java.util.Locale.ROOT, players.second)}")
        assertEquals(0.5, chorus.first, 0.01, "the measure no longer sees the chorus's locked wobbles")
        assertTrue(players.first <= 0.10 && players.second <= 0.25, "two players' wobbles are alike: mean ${players.first} max ${players.second}")
    }

    @Test
    fun `the players' level does not repeat where the chorus's swell does`() {
        val chorus = SectionMeter.envelopePeriodicity(1000.0) { Ensemble.process(it, emptyMap()) }
        assertTrue(chorus > 100, "the measure no longer sees the chorus's repeating swell: $chorus")
        val rows = listOf(100.0, 200.0, 300.0, 500.0, 1000.0).map { hz -> hz to SectionMeter.envelopePeriodicity(hz, section(1f)) }
        println("SECTION envelope line prominence, players: " + rows.joinToString("  ") { (hz, v) -> "${hz.toInt()} Hz ${"%.1f".format(java.util.Locale.ROOT, v)}" } + "  (chorus at 1 kHz ${"%.0f".format(java.util.Locale.ROOT, chorus)})")
        for ((hz, v) in rows) assertTrue(v <= 8.0, "the players' level repeats at $hz Hz: line prominence $v")
    }

    @Test
    fun `the players' level does not repeat at the extremes of RATE either, and the lines there are recorded`() {
        val rows = listOf(0f, 1f).flatMap { rate -> listOf(200.0, 1000.0).map { hz -> Triple(rate, hz, SectionMeter.envelopePeriodicity(hz, section(1f, mapOf("RATE" to rate)))) } }
        println("SECTION envelope line prominence at the RATE extremes: " + rows.joinToString("  ") { (rate, hz, v) -> "RATE ${rate.toInt()} ${hz.toInt()} Hz ${"%.1f".format(java.util.Locale.ROOT, v)}" })
        // Measured: 3.8 / 4.4 at RATE 0 and 8.7 / 3.4 at RATE 1 (200 / 1000 Hz), against 3.0-5.1 at the default RATE: the beating lines stand out a little more at the extremes.
        for ((rate, hz, v) in rows) assertTrue(v <= 9.2, "the players' level repeats at RATE $rate, $hz Hz: line prominence $v")
    }

    @Test
    fun `the players come in over a spread of time, not together`() {
        val chorus = SectionMeter.clickSpread { Ensemble.process(it, emptyMap()) }
        val players = SectionMeter.clickSpread(section(1f))
        println("SECTION click energy 5/50/95 % in ms after the click: chorus ${"%.1f".format(java.util.Locale.ROOT, chorus.first)}/${"%.1f".format(java.util.Locale.ROOT, chorus.second)}/${"%.1f".format(java.util.Locale.ROOT, chorus.third)}, players ${"%.1f".format(java.util.Locale.ROOT, players.first)}/${"%.1f".format(java.util.Locale.ROOT, players.second)}/${"%.1f".format(java.util.Locale.ROOT, players.third)}")
        assertTrue(players.third - players.first >= 18.0, "the players' onset spans only ${players.third - players.first} ms")
        assertTrue(players.first <= 9.0, "the first player comes in late: ${players.first} ms")
        assertTrue(chorus.third - chorus.first < 5.0, "the measure no longer sees the chorus's tight onset")
    }

    @Test
    fun `the players' bass folds near the dry sine, where six equal players alone fold hollow`() {
        // Before the peak match, from the players alone: the level the phone's fold of a low sine keeps against the dry sine.
        fun players(snip: Snip): Snip = Snip(EnsemblePlayers.render(snip, 0.5f, 1f, 1f), 2, snip.sampleRate)
        val rows = listOf(40.0, 50.0, 65.0, 82.0, 100.0, 130.0, 165.0, 200.0, 260.0, 330.0).map { hz -> hz to SectionMeter.bassFold(hz, ::players) }
        println("SECTION bass fold dB vs dry, mean(worst 250 ms window): " + rows.joinToString("  ") { (hz, r) -> "${hz.toInt()} Hz ${"%.1f".format(java.util.Locale.ROOT, r.first)}(${"%.1f".format(java.util.Locale.ROOT, r.second)})" })
        for ((hz, r) in rows) {
            assertTrue(r.first in -1.5..3.5 && r.second >= -4.2, "the fold of a $hz Hz sine is off the dry sine: mean ${r.first} dB, worst window ${r.second} dB")
        }
    }

    @Test
    fun `above the anchor the mid-range is a comb, recorded so it is not mistaken for solved`() {
        // The anchor is a 290 Hz low-pass and does nothing above it. A steady sine here folds hollower than the bass tones do; the rows
        // are printed, and bounded just past what was measured, so a change that makes them worse fails and one that makes them better can say so.
        fun players(snip: Snip): Snip = Snip(EnsemblePlayers.render(snip, 0.5f, 1f, 1f), 2, snip.sampleRate)
        val rows = listOf(175.0, 240.0, 305.0, 380.0).map { hz -> hz to SectionMeter.bassFold(hz, ::players) }
        println("SECTION mid-range fold dB vs dry, mean(worst 250 ms window): " + rows.joinToString("  ") { (hz, r) -> "${hz.toInt()} Hz ${"%.1f".format(java.util.Locale.ROOT, r.first)}(${"%.1f".format(java.util.Locale.ROOT, r.second)})" })
        for ((hz, r) in rows) assertTrue(r.first >= -5.0 && r.second >= -12.4, "the fold of a $hz Hz sine: mean ${r.first} dB, worst window ${r.second} dB")
    }

    @Test
    fun `the players' fold is recorded, and bounded just past what was measured`() {
        val c3 = FoldMeter.report(section(1f)(tone(130.81f, 2f)))
        val c4 = FoldMeter.report(section(1f)(tone(261.63f, 2f)))
        val notes = listOf(110f, 130.81f, 164.81f, 196f, 220f, 261.63f, 329.63f, 440f)
        val rows = notes.map { FoldMeter.report(section(1f)(tone(it, 2f))) }
        val loss = rows.map { it.lossDb }.average()
        val correlation = rows.map { it.correlation }.average()
        val chorusCorrelation = notes.map { FoldMeter.report(Ensemble.process(tone(it, 2f), emptyMap())).correlation }.average()
        val ripple = rows.map { it.rippleDb }.average()
        val pump = rows.map { it.pumpDb }.average()
        fun f(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)
        println("SECTION fold C3 saw ${f(c3.lossDb)} dB L/R ${f(c3.correlation)} ripple ${f(c3.rippleDb)} pump ${f(c3.pumpDb)}; C4 saw ${f(c4.lossDb)} dB L/R ${f(c4.correlation)} ripple ${f(c4.rippleDb)} pump ${f(c4.pumpDb)}; eight saws mean loss ${f(loss)} (worst ${f(rows.minOf { it.lossDb })}) L/R ${f(correlation)} ripple ${f(ripple)} pump ${f(pump)} (worst ${f(rows.maxOf { it.pumpDb })}); the chorus's eight saws L/R ${f(chorusCorrelation)}")
        assertTrue(c3.lossDb > -1.2 && c4.lossDb > -1.2, "a saw's fold loses more than the players' measured -0.9 dB: ${c3.lossDb} / ${c4.lossDb}")
        assertTrue(loss > -0.8 && rows.minOf { it.lossDb } > -1.3, "the eight saws' fold loses more than measured: mean $loss worst ${rows.minOf { it.lossDb }}")
        assertTrue(c3.correlation < 0.75 && c4.correlation < 0.85 && correlation < 0.8, "the pair is not as wide as measured: ${c3.correlation} / ${c4.correlation} / $correlation")
        assertEquals(0.43, chorusCorrelation, 0.02, "the chorus's own eight-saw pair moved")
        assertTrue(chorusCorrelation < correlation, "the players' pair is meant to be the narrower one (the owned cost): chorus $chorusCorrelation, players $correlation")
        assertTrue(c3.pumpDb < 1.2 && c4.pumpDb < 2.4 && pump < 1.8 && rows.maxOf { it.pumpDb } < 2.5, "the fold pumps more than measured: ${c3.pumpDb} / ${c4.pumpDb} / $pump")
        assertTrue(c3.rippleDb < 2.5 && c4.rippleDb < 6.8 && ripple < 4.6, "the fold's level ripples more than measured: ${c3.rippleDb} / ${c4.rippleDb} / $ripple")
    }
}

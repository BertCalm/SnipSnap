package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class TempestGenreRoundRobinKitsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** Float-sample hashes of every accepted TEMPEST render, taken before takes existed: (kind, name) to (frames, sha). */
    private val pins: Map<Pair<String, String>, Pair<Int, String>> by lazy {
        javaClass.getResourceAsStream("/tempest/accepted-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .associate { line -> line.split(' ').let { (k, n, f, s) -> (k to n) to (f.toInt() to s) } }
    }

    private val kits = TempestGenreRoundRobinKits.Kit.entries
    private val built by lazy { kits.associateWith { TempestGenreRoundRobinKits.build(it) } }
    private fun patches(kit: TempestGenreRoundRobinKits.Kit) = TempestGenreKits.patches(kit.source)
    private fun takes(kit: TempestGenreRoundRobinKits.Kit, i: Int) = built.getValue(kit)[i].let { listOf(it.snip) + it.takes }

    @Test fun `take zero of every pad is the kept genre pad, byte for byte, and its accepted pin`() {
        for (kit in kits) {
            val kept = TempestGenreKits.build(kit.source).map { it!! }
            val pads = built.getValue(kit)
            assertEquals(16, pads.size)
            for ((i, pad) in pads.withIndex()) {
                val patch = patches(kit)[i]
                val slot = "A%02d".format(i + 1)
                assertEquals(patch.name, pad.displayName)
                assertEquals(kept[i].drumClass, pad.drumClass, "${patch.name}")
                assertEquals(kept[i].muteGroup, pad.muteGroup, "${patch.name}")
                assertEquals(if (patch.voice == TempestVoice.HAT) 1 else 0, pad.muteGroup, "${patch.name}: hats choke in group 1")
                assertTrue(pad.oneShot)
                assertNull(pad.recipe, "a single-hit recipe cannot regenerate the takes")
                assertEquals(patch, TempestPatch.fromJsonText(pad.source.getValue("recipe")))
                assertEquals(kit.source.displayName, pad.source["kit"])
                assertEquals(TempestGenreRoundRobinKits.strikesFor(patch).joinToString(","), pad.source["rendererTakes"])
                assertTrue(pad.softVariants.isEmpty() && pad.velocityTakes.isEmpty(), "TEMPEST has one touch")
                assertEquals(TempestGenreRoundRobinKits.TAKES - 1, pad.takes.size)
                assertContentEquals(kept[i].snip.samples, pad.snip.samples, "${kit.name} ${patch.name} take 0")
                val (frames, s) = pins.getValue("kit" to "${kit.source.id}/$slot")
                assertEquals(frames, pad.snip.frameCount)
                assertEquals(s, sha(pad.snip.samples), "${kit.name} $slot take 0 is the accepted pin")
            }
        }
    }

    @Test fun `the pads these kits share with the main kit deal the takes the owner kept in tempest-rr-1`() {
        val main = TempestPresets.fullKitPatches()
        var shared = 0
        for (kit in kits) for ((i, patch) in patches(kit).withIndex()) {
            if (patch !in main) continue
            shared++
            assertEquals(TempestRoundRobinKits.STRIKES, TempestGenreRoundRobinKits.strikesFor(patch), "${patch.name} keeps the kept takes")
            for ((t, snip) in takes(kit, i).withIndex())
                assertContentEquals(TempestRoundRobinKits.render(patch, t).samples, snip.samples, "${kit.name} ${patch.name} take $t")
        }
        // CLAP START, HAT CLOSED, HAT OPEN (CIRCUIT and STEP), HAT NOISE, BURR, TRAIL and CLAP SPREAD.
        assertEquals(8, shared)
    }

    @Test fun `takes replay exactly, share the pad's length and stay bounded`() {
        assertEquals(listOf(0, 2, 5, 8), TempestGenreRoundRobinKits.STRIKES)
        for ((name, strikes) in TempestGenreRoundRobinKits.OVERRIDES) {
            assertEquals(0, strikes.first(), name)
            assertEquals(TempestGenreRoundRobinKits.TAKES, strikes.toSet().size, name)
            assertTrue(kits.any { k -> patches(k).any { it.name == name } }, "$name is a genre pad")
        }
        for (kit in kits) for ((i, patch) in patches(kit).withIndex()) for ((take, snip) in takes(kit, i).withIndex()) {
            assertContentEquals(TempestGenreRoundRobinKits.render(patch, take).samples, snip.samples, "${patch.name} take $take replays")
            assertEquals(Tempest.OUTPUT_FRAMES, snip.frameCount)
            assertTrue(snip.samples.all { it.isFinite() && abs(it) <= 1f }, "${patch.name} take $take bounded")
        }
        assertFailsWith<IllegalArgumentException> { TempestGenreRoundRobinKits.render(patches(kits[0])[0], TempestGenreRoundRobinKits.TAKES) }
    }

    /**
     * WIRE and STEP SUB sit at SOURCE 0: no noise and no free-running partial reaches their sound, so
     * every renderer take is take 0's bytes, which is why their takes drift the oscillator instead.
     * Every other pad's takes come from its renderer takes alone, with no drift.
     */
    @Test fun `the drift pads cannot vary any other way, and only they drift`() {
        val drift = kits.flatMap { k -> patches(k).filter { it.name in TempestGenreRoundRobinKits.DRIFT_PADS }.map { k to it } }
        assertEquals(listOf("WIRE", "STEP SUB"), drift.map { it.second.name })
        assertEquals(listOf(TempestGenreRoundRobinKits.Kit.CIRCUIT, TempestGenreRoundRobinKits.Kit.STEP), drift.map { it.first })
        assertEquals(0.0, TempestGenreRoundRobinKits.DRIFT_CENTS[0])
        assertTrue(TempestGenreRoundRobinKits.DRIFT_CENTS.drop(1).all { it != 0.0 && abs(it) <= 5.0 }, "a few cents")
        for ((_, p) in drift) {
            assertEquals(0f, p.macros.getValue("SOURCE"))
            val x0 = Tempest.render(p.voice, p.macros, p.model, 0).samples
            for (s in TempestGenreRoundRobinKits.strikesFor(p).drop(1))
                assertContentEquals(x0, Tempest.render(p.voice, p.macros, p.model, s).samples, "${p.name} renderer take $s is take 0")
        }
        for (kit in kits) for (p in patches(kit)) if (p.name !in TempestGenreRoundRobinKits.DRIFT_PADS)
            assertTrue((0 until TempestGenreRoundRobinKits.TAKES).all { TempestGenreRoundRobinKits.centsFor(p, it) == 0.0 }, p.name)
        assertFailsWith<IllegalArgumentException> { Tempest.drifted(TempestVoice.SNARE, emptyMap(), 3, 1, 4.0) }
        assertFailsWith<IllegalArgumentException> { Tempest.drifted(TempestVoice.KICK, emptyMap(), 2, 0, 4.0) }
        assertFailsWith<IllegalArgumentException> { Tempest.drifted(TempestVoice.KICK, emptyMap(), 2, 1, 11.0) }
    }

    /** Strongest peak in [lo, hi] Hz of the first half second, Hann-windowed and zero-padded to 2^18, log-parabolic. */
    private fun peakHz(x: FloatArray, lo: Double, hi: Double): Double {
        val n = 1 shl 18
        val w = min(x.size, Dsp.RATE / 2)
        val re = FloatArray(n) { i -> if (i < w) x[i] * (.5f - .5f * cos(2 * PI * i / (w - 1)).toFloat()) else 0f }
        val im = FloatArray(n)
        Fft.forward(re, im)
        val a = (lo * n / Dsp.RATE).toInt(); val b = (hi * n / Dsp.RATE).toInt()
        val mag = { k: Int -> ln(hypot(re[k].toDouble(), im[k].toDouble()) + 1e-12) }
        val k = (a..b).maxBy { mag(it) }
        val (l, c, r) = Triple(mag(k - 1), mag(k), mag(k + 1))
        return (k + .5 * (l - r) / (l - 2 * c + r)) * Dsp.RATE / n
    }

    /**
     * The drift is what it says. STEP SUB's strongest partial (46 Hz) sits its [TempestGenreRoundRobinKits.DRIFT_CENTS]
     * from take 0's: measured +4.04, -4.03 and +2.02 cents. WIRE's oscillator drifts as much, but its strongest peak
     * (277 Hz, the second harmonic) moves about half that, +2.06, -2.05 and +1.03 cents, because WIRE rings through
     * SPACE's repeats, whose fixed delay holds the spectrum's comb in place. Either way, each take drifts the way it is
     * told, and onset, shape and energy time stay as the screen below asks (shape 0.05 to 0.27 dB).
     */
    @Test fun `the drift pads' takes sound their drift, measured`() {
        for (kit in kits) for ((i, p) in patches(kit).withIndex()) if (p.name in TempestGenreRoundRobinKits.DRIFT_PADS) {
            val row = takes(kit, i).map { it.samples }
            val f0 = peakHz(row[0], 30.0, 3000.0)
            for (t in 1 until row.size) {
                val c = 1200 * log2(peakHz(row[t], f0 * .9, f0 * 1.1) / f0)
                val target = TempestGenreRoundRobinKits.DRIFT_CENTS[t]
                if (p.name == "WIRE") assertTrue(c / target in .4..1.05, "${p.name} take $t drifts $c cents, told $target")
                else assertEquals(target, c, DRIFT_TOLERANCE_CENTS, "${p.name} take $t drifts $c cents")
            }
        }
    }

    /**
     * Whether the takes vary where they should, by a phase-blind measure ([TempestRoundRobinMeasure.texture]),
     * per measured pad rather than per voice: FOUNDRY's driven kicks and the high-SOURCE toms move more than the
     * main kit's. No take is a copy of another (a phase-sensitive residual with gain removed). Measured pairwise:
     * kicks 0.001 to 0.030 dB (FOUNDRY's driven kicks included), toms 0.007 to 0.036, CIRCUIT BELL and BELL 5 and STEP
     * BLIP and BLIP 3 (SOURCE .05 and .10) 0.012 to 0.038, STEP SUB 0.012 to 0.048; WIRE 0.14 to 0.46; every other
     * metal 0.34 to 1.08, hats 0.58 to 1.99, snares 0.97 to 2.67 and claps 2.04 to 2.74 dB.
     */
    @Test fun `every take differs from every other, and the steady pads stay almost the same hit`() {
        val m = TempestRoundRobinMeasure
        for (kit in kits) for ((i, patch) in patches(kit).withIndex()) {
            val row = takes(kit, i).map { it.samples }
            for (a in row.indices) for (b in a + 1 until row.size) {
                assertFalse(row[a].contentEquals(row[b]), "${patch.name} takes $a/$b are a copy")
                assertTrue(m.residual(row[a], row[b]) > 1e-12, "${patch.name} takes $a/$b differ only in gain")
                val tex = m.texture(row[a], row[b])
                when (patch.name) {
                    in STEADY -> assertTrue(tex < STEADY_TEXTURE, "${patch.name} takes $a/$b: a tuned body should barely move, $tex dB")
                    "WIRE" -> assertTrue(tex > DRIFTED_TEXTURE, "${patch.name} takes $a/$b should drift, $tex dB")
                    else -> assertTrue(tex > VARIED_TEXTURE, "${patch.name} takes $a/$b should vary, $tex dB")
                }
            }
        }
    }

    /**
     * The closeness screen the takes were chosen by, TEMPEST ROUND ROBIN's: every later take keeps the kept onset
     * (within a quarter millisecond), stays within 2 dB of the kept hit's spectral shape over the bands within 30 dB of
     * its strongest ([TempestRoundRobinMeasure.audibleDistance]), and keeps its 90%-energy time within 0.7 to 1.4
     * times the kept one. Four pads failed it on renderer takes 2, 5 or 8 (CIRCUIT BODY 2.24 and 2.49 dB, CIRCUIT TIGHT
     * 2.02 to 3.25 dB, FOUNDRY CRACK 2.54 and 3.44 dB, STEP PEDAL's onset 0.27 ms early) and deal their own first three
     * passing takes ([TempestGenreRoundRobinKits.OVERRIDES]). Measured on the kits' takes: onsets within 0.21 ms, shape
     * 0.0 to 1.99 dB (snares and claps highest) and energy time 0.895 to 1.038 times.
     */
    @Test fun `takes keep the kept onset spectral shape and energy arrival`() {
        val m = TempestRoundRobinMeasure
        for (kit in kits) for ((i, patch) in patches(kit).withIndex()) {
            val kept = takes(kit, i)[0].samples
            for ((take, snip) in takes(kit, i).withIndex().drop(1)) {
                val x = snip.samples
                assertEquals(m.onsetMs(kept), m.onsetMs(x), ONSET_MS, "${patch.name} take $take onset")
                val d = m.audibleDistance(kept, x)
                assertTrue(d < 2.0, "${patch.name} take $take shape $d dB from its own hit")
                val ratio = m.t90Ms(x) / m.t90Ms(kept)
                assertTrue(ratio in .7..1.4, "${patch.name} take $take energy time ratio $ratio")
            }
        }
    }

    private companion object {
        const val ONSET_MS = .25
        const val DRIFT_TOLERANCE_CENTS = .25
        /** The pads measured nearly identical take to take: kicks, toms, the low-SOURCE bells and blips, and STEP SUB. */
        val STEADY = setOf("CIRCUIT KICK", "CIRCUIT LOW", "CIRCUIT ZAP HI", "CIRCUIT ZAP LO", "CIRCUIT BELL", "CIRCUIT BELL 5",
            "FOUNDRY HAMMER", "FOUNDRY SLAB", "FOUNDRY ECHO", "FOUNDRY PIPE LO", "FOUNDRY PIPE MID", "FOUNDRY PIPE HI",
            "STEP KICK", "STEP SUB", "STEP BLIP", "STEP BLIP 3")
        /** WIRE's drift moves it between the two classes (0.14 to 0.46 dB). */
        const val DRIFTED_TEXTURE = .1
        const val STEADY_TEXTURE = .1
        const val VARIED_TEXTURE = .25
    }
}

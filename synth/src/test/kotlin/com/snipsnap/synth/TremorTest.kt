package com.snipsnap.synth

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.json.Json
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TREMOR's acceptance checks. The numbers are the built engine's, from the same renders the kit
 * and the presets use. Nothing here is a listen: the audition page is where a human says which
 * of these is a drum.
 */
class TremorTest {

    @Test
    fun `the same patch renders the same audio twice`() {
        val a = Tremor.play(TremorVoice.ROLL, mapOf("BEADS" to 1f, "FAULT" to 0.4f))
        val b = Tremor.play(TremorVoice.ROLL, mapOf("BEADS" to 1f, "FAULT" to 0.4f))
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.contactCount, b.contactCount)
        assertEquals(a.faults.size, b.faults.size)
        assertEquals(a.circuitWork, b.circuitWork)
    }

    @Test
    fun `a one-shot decays, stays off the rail, and carries no dc`() {
        val r = Tremor.play(TremorVoice.HIDE)
        assertTrue(r.railHits == 0, "the internal mix hit the rail ${r.railHits} times")
        assertTrue(r.rawPeak < 4f, "raw peak ${r.rawPeak}")
        assertTrue(abs(r.rawDc) < 1e-3, "raw dc ${r.rawDc}")
        assertTrue(r.maxModalEnergy > 0.0 && r.endModalEnergy < r.maxModalEnergy * 1e-2, "energy ${r.maxModalEnergy} -> ${r.endModalEnergy}")
        assertTrue(r.energy.last() < r.energy.max() * 1e-2, "the trace did not settle")
        val peakAt = r.energy.indices.maxBy { r.energy[it] }
        for (i in peakAt + 1 until r.energy.size) {
            assertTrue(r.energy[i] <= r.energy[i - 1] * 1.25 + 1e-8, "energy rose after the hit at $i")
        }
        assertClean(r, "HIDE")
        assertTrue(r.snip.frameCount / Dsp.RATE.toDouble() < Tremor.ONESHOT_CAP_SECONDS + 0.05)
    }

    @Test
    fun `current zero does no work and a fault still loads the strings`() {
        val off = Tremor.play(TremorVoice.WIRE, mapOf("CURRENT" to 0f, "FAULT" to 1f))
        assertEquals(0.0, off.circuitWork)
        assertEquals(0.0, off.actuatorPeak)
        assertTrue(off.faults.isNotEmpty(), "FAULT at CURRENT 0 changed nothing")
        val quiet = Tremor.play(TremorVoice.WIRE, mapOf("CURRENT" to 0f, "FAULT" to 0f))
        assertEquals(0.0, quiet.circuitWork)
        assertTrue(quiet.faults.isEmpty())
        assertTrue(off.stringEnergy < quiet.stringEnergy, "CURRENT 0 logged faults without loading the passive cage")
    }

    @Test
    fun `a powered note does work and then settles`() {
        val r = Tremor.play(TremorVoice.CHARGE)
        assertTrue(r.circuitWork > 0.0 && r.circuitWork.isFinite(), "work ${r.circuitWork}")
        assertTrue(r.actuatorPeak > 0.0 && r.actuatorPeak.isFinite())
        assertTrue(r.endModalEnergy < r.maxModalEnergy * 1e-2)
        assertEquals(0, r.railHits)
        assertClean(r, "CHARGE")
    }

    @Test
    fun `beads and the cage are consequences of the drum`() {
        val beads = Tremor.play(TremorVoice.ROLL, mapOf("BEADS" to 1f))
        val noTray = Tremor.play(TremorVoice.ROLL, mapOf("BEADS" to 1f), probe = TremorProbe(headToTray = false))
        assertTrue(beads.contactCount > 0, "the beads never left the tray")
        assertEquals(0, noTray.contactCount, "beads hit a tray the head was not driving")
        val cage = Tremor.play(TremorVoice.WIRE)
        val cageOff = Tremor.play(TremorVoice.WIRE, probe = TremorProbe(headToCage = false, trayToCage = false))
        assertTrue(cage.stringEnergy > 0.0)
        assertEquals(0.0, cageOff.stringEnergy, "the cage rang with both drives off")
        val floor = Tremor.play(TremorVoice.WIRE, mapOf("CAGE" to 0f))
        assertTrue(floor.stringEnergy > 0.0, "CAGE 0 went silent")
    }

    @Test
    fun `each control moves its own trace, including across five steps`() {
        val strike = (0..4).map { step -> Tremor.play(TremorVoice.HIDE, mapOf("STRIKE" to step / 4f)).strikes.first().impulse }
        assertTrue(strike.last() > strike.first() * 1.2, "STRIKE impulses $strike")
        val hands = (0..4).map { step -> Tremor.play(TremorVoice.HIDE, mapOf("ENSEMBLE" to step / 4f)).strikes.size }
        assertTrue(hands.first() == 1 && hands.last() == 4, "ensemble strikes $hands")
        for (i in 1 until hands.size) assertTrue(hands[i] >= hands[i - 1], "ensemble went backwards: $hands")
        val beads = (0..4).map { step -> Tremor.play(TremorVoice.ROLL, mapOf("BEADS" to step / 4f)).contactCount }
        assertTrue(beads.last() > beads.first(), "BEADS contacts $beads")
        val cage = (0..4).map { step -> Tremor.play(TremorVoice.WIRE, mapOf("CAGE" to step / 4f, "CURRENT" to 0f)).stringEnergy }
        assertIncreasing(cage, "CAGE")
        val current = (0..4).map { step -> Tremor.play(TremorVoice.CHARGE, mapOf("CURRENT" to step / 4f, "FAULT" to 0f)) }
        assertEquals(0.0, current.first().circuitWork)
        assertTrue(current.last().circuitWork > 0.0)
        current.forEach { assertEquals(0, it.railHits) }
        val faults = (0..4).map { step ->
            Tremor.play(TremorVoice.FRACTURE, mapOf("FAULT" to step / 4f, "CURRENT" to 0.8f)).faults.size
        }
        assertEquals(0, faults.first())
        assertTrue(faults.last() > faults[1], "FAULT counts $faults")
        for (i in 1 until faults.size) assertTrue(faults[i] >= faults[i - 1], "FAULT went backwards: $faults")
    }

    @Test
    fun `interaction corners stay finite and the axes still do their jobs`() {
        for (ensemble in listOf(0f, 0.5f, 1f)) for (beads in listOf(0f, 0.5f, 1f)) {
            assertClean(Tremor.play(TremorVoice.UNISON, mapOf("ENSEMBLE" to ensemble, "BEADS" to beads)), "ensemble $ensemble beads $beads")
        }
        val soft = Tremor.play(TremorVoice.ROLL, mapOf("STRIKE" to 0f, "BEADS" to 1f))
        val hard = Tremor.play(TremorVoice.ROLL, mapOf("STRIKE" to 1f, "BEADS" to 1f))
        assertTrue(hard.strikes.first().impulse > soft.strikes.first().impulse)
        for (cage in listOf(0f, 0.5f, 1f)) {
            val dead = Tremor.play(TremorVoice.WIRE, mapOf("CAGE" to cage, "CURRENT" to 0f))
            val live = Tremor.play(TremorVoice.WIRE, mapOf("CAGE" to cage, "CURRENT" to 1f, "FAULT" to 0f))
            assertEquals(0.0, dead.circuitWork)
            assertTrue(live.circuitWork > 0.0)
        }
        val calm = Tremor.play(TremorVoice.FRACTURE, mapOf("CURRENT" to 0.5f, "FAULT" to 0f))
        val broken = Tremor.play(TremorVoice.FRACTURE, mapOf("CURRENT" to 0f, "FAULT" to 1f))
        val dense = Tremor.play(TremorVoice.FRACTURE, mapOf("CURRENT" to 0.5f, "FAULT" to 1f))
        assertEquals(0.0, broken.circuitWork)
        assertEquals(0.0, broken.actuatorPeak)
        assertTrue(dense.faults.size > calm.faults.size)
        val bed = Tremor.play(TremorVoice.ROLL, mapOf("BEADS" to 1f, "CAGE" to 0f))
        val wired = Tremor.play(TremorVoice.ROLL, mapOf("BEADS" to 1f, "CAGE" to 1f))
        assertTrue(bed.contactCount > 0)
        assertTrue(wired.stringEnergy > bed.stringEnergy)
    }

    @Test
    fun `tune moves the anchor and the filed class matches the classifier`() {
        val tunes = listOf(0f to DrumClass.KICK, 0.5f to DrumClass.TOM, 1f to DrumClass.PERC)
        var previous = 0f
        for ((tune, filed) in tunes) {
            val r = Tremor.play(TremorVoice.HIDE, mapOf("TUNE" to tune))
            val f = FeatureExtractor.extract(r.snip)
            val hz = Tremor.frequencyFor(TremorVoice.HIDE, tune)
            assertEquals(filed, Tremor.drumClassFor(TremorVoice.HIDE, mapOf("TUNE" to tune)))
            assertEquals(filed, Classifier.classify(f).drumClass, "TUNE $tune heard ${Classifier.classify(f).drumClass}")
            assertTrue(f.centroidHz > previous, "centroid did not rise at TUNE $tune (${f.centroidHz} after $previous)")
            val ratio = f.centroidHz / hz
            assertTrue(ratio in 0.8f..1.2f, "centroid ${f.centroidHz} is far from the anchor $hz")
            previous = f.centroidHz
            assertEquals(36 + (tune * 24).toInt(), Tremor.midiFor(TremorVoice.HIDE, tune))
        }
    }

    @Test
    fun `a high faulted note does not pile energy above 18 kHz`() {
        val r = Tremor.play(TremorVoice.FRACTURE, mapOf("TUNE" to 1f, "FAULT" to 1f, "CURRENT" to 1f))
        assertClean(r, "alias")
        val share = highShare(r.snip.samples, Dsp.RATE, 18_000.0)
        assertTrue(share < 0.02, "share above 18 kHz is $share")
    }

    @Test
    fun `powered presets keep the struck register instead of selecting a high feedback tone`() {
        for (name in listOf("CHARGED TAIL", "BROKEN RETURN")) {
            val p = TremorPresets.all().first { it.name == name }
            val r = Tremor.play(p.voice, p.macros + ("TUNE" to 0.5f))
            val at = (0.08 * Dsp.RATE).toInt()
            val tail = r.snip.samples.copyOfRange(at, minOf(at + 8192, r.snip.frameCount))
            // The previous powered path put 88–95% of this C3 tail above 800 Hz. A broad
            // drum/cage tail may carry upper partials, but they cannot become the whole note.
            val upper = highShare(tail, Dsp.RATE, 800.0)
            assertTrue(upper < 0.15, "$name has an upper-register tail share $upper")
        }
    }

    @Test
    fun `returning beads and passive cage change exported audio after levelling`() {
        val sparse = Tremor.render(TremorVoice.ROLL, mapOf("BEADS" to 0f))
        val full = Tremor.render(TremorVoice.ROLL, mapOf("BEADS" to 1f))
        // Contact counts alone passed while the old export differed by less than 0.1% RMS.
        assertTrue(rms(sparse.samples, full.samples) > 0.02, "the bead bed is still buried")
        val closed = Tremor.render(TremorVoice.WIRE, mapOf("CAGE" to 0f, "CURRENT" to 0f, "FAULT" to 0f))
        val open = Tremor.render(TremorVoice.WIRE, mapOf("CAGE" to 1f, "CURRENT" to 0f, "FAULT" to 0f))
        assertTrue(rms(closed.samples, open.samples) > 0.05, "passive cage radiation is still buried")
    }

    @Test
    fun `the clean hide has a resonant body beyond its first damped knock`() {
        val s = Tremor.render(TremorVoice.HIDE, mapOf("CURRENT" to 0f, "FAULT" to 0f)).samples
        fun moment(from: Double, to: Double): Double {
            val a = (from * Dsp.RATE).toInt()
            val b = minOf((to * Dsp.RATE).toInt(), s.size)
            return sqrt((a until b).sumOf { s[it].toDouble() * s[it] } / (b - a))
        }
        val ratio = moment(0.12, 0.20) / moment(0.0, 0.08)
        assertTrue(ratio > 0.15, "the hide tail dies too early: ratio $ratio")
    }

    @Test
    fun `louder cage tails close and every textured held voice keeps its seam`() {
        for (voice in listOf(TremorVoice.WIRE, TremorVoice.CHARGE, TremorVoice.FRACTURE)) {
            val r = Tremor.play(voice, mapOf("CAGE" to 1f, "CURRENT" to 1f, "FAULT" to 1f))
            assertTrue(abs(r.snip.samples.last()) < 1e-8f, "$voice tail is cut at a nonzero sample")
            assertTrue(r.snip.frameCount / Dsp.RATE.toDouble() <= Tremor.ONESHOT_CAP_SECONDS)
        }
        for (voice in TremorVoice.entries) {
            val r = Tremor.play(voice, mapOf("BEADS" to 1f, "CAGE" to 1f, "CURRENT" to 1f, "FAULT" to 1f, "HOLD" to 1f))
            assertClean(r, "$voice held corner")
            assertTrue(Keys.seamError(r.snip.samples, r.loopStart) < Keys.MAX_SEAM_ERROR, "$voice held seam")
        }
    }

    @Test
    fun `hold closes, files loop, and strike still changes the loop`() {
        val soft = Tremor.play(TremorVoice.HIDE, mapOf("HOLD" to 1f, "STRIKE" to 0f))
        val hard = Tremor.play(TremorVoice.HIDE, mapOf("HOLD" to 1f, "STRIKE" to 1f))
        for (r in listOf(soft, hard)) {
            assertTrue(r.loopStart >= 256)
            assertTrue(r.snip.frameCount / Dsp.RATE.toDouble() > 1.5)
            assertTrue(Keys.seamError(r.snip.samples, r.loopStart) < Keys.MAX_SEAM_ERROR, "seam ${Keys.seamError(r.snip.samples, r.loopStart)}")
            assertEquals(DrumClass.LOOP, Classifier.classify(r.snip).drumClass)
            assertEquals(DrumClass.LOOP, Tremor.drumClassFor(TremorVoice.HIDE, mapOf("HOLD" to 1f)))
            assertEquals(0, r.railHits)
        }
        val loopSoft = soft.snip.samples.copyOfRange(soft.loopStart, soft.snip.frameCount)
        val loopHard = hard.snip.samples.copyOfRange(hard.loopStart, hard.snip.frameCount)
        assertTrue(rms(loopSoft, loopHard) > 0.02, "STRIKE did not move the held loop")
        val tiled = FloatArray(loopSoft.size * 8)
        for (k in 0 until 8) loopSoft.copyInto(tiled, k * loopSoft.size)
        val join = Keys.seamError(soft.snip.samples, soft.loopStart)
        for (k in 1 until 8) {
            var diff = 0.0
            var level = 0.0
            val at = k * loopSoft.size
            for (i in 0 until 256) {
                val d = tiled[at - 256 + i] - soft.snip.samples[soft.loopStart - 256 + i]
                diff += d * d
                level += soft.snip.samples[soft.loopStart - 256 + i].toDouble().let { it * it }
            }
            assertTrue(diff / level <= join + 1e-6, "wrap $k opened the seam")
        }
    }

    @Test
    fun `held endpoints of every control still close`() {
        val knobs = listOf("STRIKE", "ENSEMBLE", "BEADS", "CAGE", "CURRENT", "FAULT")
        for (name in knobs) for (end in listOf(0f, 1f)) {
            val r = Tremor.play(TremorVoice.HIDE, mapOf("HOLD" to 1f, name to end))
            assertTrue(Keys.seamError(r.snip.samples, r.loopStart) < Keys.MAX_SEAM_ERROR, "$name $end seam")
            assertEquals(0, r.railHits, "$name $end")
            assertTrue(r.snip.samples.all { it.isFinite() })
        }
    }

    @Test
    fun `ensemble spreads a fixed budget and velocity is the blow`() {
        for (e in listOf(0f, 0.35f, 0.7f, 1f)) {
            val w = Tremor.ensembleWeights(e.toDouble())
            val sum = w.sumOf { it * it }
            assertTrue(abs(sum - 1.0) < 1e-9, "weights at $e sum to $sum")
        }
        val one = Tremor.play(TremorVoice.UNISON, mapOf("ENSEMBLE" to 0f))
        val four = Tremor.play(TremorVoice.UNISON, mapOf("ENSEMBLE" to 1f))
        assertEquals(1, one.strikes.size)
        assertEquals(4, four.strikes.size)
        assertTrue(one.strikes.first().impulse > four.strikes.maxOf { it.impulse })
        val patch = TremorPatch("Hit", TremorVoice.HIDE, Tremor.defaults(TremorVoice.HIDE))
        assertContentEquals(patch.render().samples, Velocity.atVelocity(patch, 1f).samples)
        val hard = Tremor.play(TremorVoice.HIDE, velocity = 1f)
        val soft = Tremor.play(TremorVoice.HIDE, velocity = 0.3f)
        assertTrue(hard.strikes.first().impulse > soft.strikes.first().impulse)
        assertTrue(hard.contactImpulse >= soft.contactImpulse)
        assertClean(soft, "soft")
    }

    @Test
    fun `a render is cheap and a patch round-trips`() {
        val t0 = System.nanoTime()
        val r = Tremor.play(TremorVoice.HIDE)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(ms < 8_000, "a one-shot took ${ms}ms")
        assertTrue(r.snip.frameCount < Dsp.RATE * 2)
        val patch = TremorPatch("Round", TremorVoice.ROLL, Tremor.defaults(TremorVoice.ROLL) + mapOf("BEADS" to 0.9f))
        val restored = TremorPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        assertContentEquals(patch.render().samples, restored.render().samples)
        val viaDispatcher = Patches.fromJsonValue(Json.parse(patch.toJsonText()))
        assertEquals(patch, viaDispatcher)
        assertFailsWith<IllegalArgumentException> {
            TremorPatch("Bad", TremorVoice.HIDE, mapOf("NOPE" to 0.2f))
        }
        assertFailsWith<IllegalArgumentException> {
            TremorPatch("Bad", TremorVoice.HIDE, mapOf("STRIKE" to 1.2f))
        }
        assertFailsWith<IllegalArgumentException> {
            TremorPatch(" ", TremorVoice.HIDE, emptyMap())
        }
    }

    @Test
    fun `the kit is sixteen dry pads and the classifier agrees`() {
        val kit = SynthKits.tremor()
        assertEquals(16, kit.size)
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        for ((i, pad) in kit.withIndex()) {
            val arranged = requireNotNull(pad)
            assertTrue(arranged.oneShot)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe))
            assertEquals(null, recipe.fx, "pad ${i + 1} is not dry")
            val patch = recipe.patch as TremorPatch
            assertEquals(arranged.drumClass, Tremor.drumClassFor(patch.voice, patch.macros))
            // These two recipe roles stay fixed while their deliberately corrected timbres
            // cross the generic import classifier's centroid/bass thresholds.
            when (patch.name) {
                "SETTLING BED" -> assertEquals(DrumClass.TOM, arranged.drumClass)
                "CHARGED TAIL" -> assertEquals(DrumClass.PERC, arranged.drumClass)
                else -> assertEquals(arranged.drumClass, Classifier.classify(arranged.snip).drumClass, "pad ${i + 1} ${patch.name}")
            }
            assertEquals(0, AutoPlace.muteGroupFor(arranged.drumClass), "pad ${i + 1} would choke")
            assertContentEquals(arranged.snip.samples, patch.render().samples)
            if (i < 8) {
                assertEquals(TremorVoice.HIDE, patch.voice)
                assertEquals(36 + walk[i], Tremor.midiFor(patch.voice, patch.macros.getValue("TUNE")))
            }
        }
        val names = kit.drop(8).map { (PadRecipe.fromJsonValue(it!!.recipe!!).patch as TremorPatch).name }
        assertEquals(
            listOf("FOUR HANDS", "SETTLING BED", "DRY CAGE", "CHARGED TAIL", "LATE STRAND", "BROKEN RETURN", "HELD DRUM", "HELD BLOOM"),
            names,
        )
    }

    private fun assertClean(r: TremorRender, label: String) {
        assertTrue(r.snip.frameCount > 0, "$label rendered nothing")
        assertTrue(r.snip.samples.all { it.isFinite() }, "$label was not finite")
        assertTrue(r.snip.samples.all { it in -1f..1f }, "$label clipped")
        assertEquals(0, r.railHits, "$label hit the rail")
        val loud = Loudness.of(r.snip)
        assertTrue(
            loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || r.snip.peak() >= 0.95f,
            "$label is quiet: loudness $loud peak ${r.snip.peak()}",
        )
    }

    private fun assertIncreasing(values: List<Double>, label: String) {
        for (i in 1 until values.size) {
            assertTrue(values[i] > values[i - 1], "$label did not rise: $values")
        }
    }

    private fun rms(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var diff = 0.0
        var level = 0.0
        for (i in 0 until n) {
            val d = a[i] - b[i]
            diff += d * d
            level += a[i] * a[i]
        }
        return sqrt(diff / level.coerceAtLeast(1e-12))
    }

    private fun highShare(samples: FloatArray, rate: Int, fromHz: Double): Double {
        val n = 4096
        val re = FloatArray(n)
        val im = FloatArray(n)
        val take = minOf(n, samples.size)
        for (i in 0 until take) re[i] = samples[i]
        Fft.forward(re, im)
        var hi = 0.0
        var all = 0.0
        for (k in 1 until n / 2) {
            val mag = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            all += mag
            if (k * rate.toDouble() / n >= fromHz) hi += mag
        }
        return if (all == 0.0) 0.0 else hi / all
    }
}

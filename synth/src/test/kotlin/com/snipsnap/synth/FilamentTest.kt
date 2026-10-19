package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Check the actual propagation and exported audio, before loudness can disguise lost energy. */
class FilamentTest {
    private val timbral = listOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT")
    private val gesture = mapOf("INJECT" to .55f, "BEND" to .55f, "LENGTH" to .65f,
        "BUNDLE" to .65f, "COLLECT" to .5f)

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    @Test
    fun `finite and held renders reproduce all branches independent of macro map order`() {
        for (voice in FilamentVoice.entries) for (hold in listOf(0f, 1f)) {
            val macros = gesture + ("HOLD" to hold)
            val config = FilamentConfig(seed = 719)
            val a = Filament.inspect(voice, macros, midi = 57, velocity = .7f, config = config)
            val b = Filament.inspect(voice, macros.entries.reversed().associate { it.key to it.value },
                midi = 57, velocity = .7f, config = config)
            for ((name, pair) in mapOf("raw" to (a.raw to b.raw), "export" to (a.samples to b.samples),
                "source" to (a.source to b.source), "through" to (a.through to b.through),
                "escaped" to (a.escaped to b.escaped), "spill" to (a.spill to b.spill))) {
                assertContentEquals(pair.first, pair.second, "$voice HOLD$hold $name replay")
            }
            assertEquals(a.diagnostics.branches, b.diagnostics.branches, "$voice HOLD$hold branch replay")
            assertEquals(a.diagnostics.sites, b.diagnostics.sites, "$voice HOLD$hold allocation replay")
            assertEquals(a.diagnostics.seamError, b.diagnostics.seamError, "$voice HOLD$hold seam replay")
        }
    }

    @Test
    fun `ordered splits conserve one launch budget without donating escaped input twice`() {
        val p = Filament.inspect(FilamentVoice.CURL, gesture)
        val d = p.diagnostics
        assertEquals(1.0, d.launch, 1e-9, "source allocation must not grow with the bundle")
        assertEquals(d.launch, d.retained + d.escaped + d.absorbed, 1e-9)
        assertEquals(d.launch, d.branches.sumOf { it.launch }, 1e-9)
        assertTrue(d.branches.isNotEmpty() && d.sites.isNotEmpty())
        assertEquals(d.branches.size, d.branches.map { it.id }.distinct().size)
        assertEquals(d.sites.size, d.sites.map { it.id }.distinct().size)
        for (branch in d.branches) {
            val sites = d.sites.filter { it.strandId == branch.strandId && it.pathId == branch.pathId }.sortedBy { it.sectionId }
            assertTrue(sites.size >= 2, "${branch.id} has no ordered propagation")
            var remaining = branch.launch
            var escaped = 0.0
            var absorbed = 0.0
            var delay = -1.0
            for (site in sites) {
                val fractions = listOf(site.retainedFraction, site.escapedFraction, site.absorbedFraction)
                assertTrue(fractions.all { it.isFinite() && it in 0.0..1.0 }, "${site.id} invalid fraction")
                assertEquals(1.0, fractions.sum(), 1e-9, "${site.id} split budget")
                assertEquals(remaining, site.incomingPower, 1e-9, "${site.id} repeated an earlier donation")
                assertEquals(site.incomingPower, site.retainedPower + site.escapedPower + site.absorbedPower, 1e-9)
                assertEquals(site.incomingPower * site.retainedFraction, site.retainedPower, 1e-9)
                assertEquals(site.incomingPower * site.escapedFraction, site.escapedPower, 1e-9)
                assertEquals(site.incomingPower * site.absorbedFraction, site.absorbedPower, 1e-9)
                assertTrue(site.delaySeconds > delay && site.delaySeconds <= branch.endpointSeconds,
                    "${site.id} violates spatial arrival order")
                delay = site.delaySeconds
                remaining = site.retainedPower
                escaped += site.escapedPower
                absorbed += site.absorbedPower
            }
            assertEquals(branch.retained, remaining, 1e-9)
            assertEquals(branch.escaped, escaped, 1e-9)
            assertEquals(branch.absorbed, absorbed, 1e-9)
            assertEquals(branch.launch, remaining + escaped + absorbed, 1e-9)
        }
    }

    @Test
    fun `activating routes preserves existing identities geometry and the shared launch budget`() {
        val config = FilamentConfig(seed = 718)
        val one = Filament.inspect(FilamentVoice.CURL, gesture + ("BUNDLE" to 0f), config = config).diagnostics
        val four = Filament.inspect(FilamentVoice.CURL, gesture + ("BUNDLE" to 1f), config = config).diagnostics
        assertEquals(1.0, one.launch, 1e-9)
        assertEquals(1.0, four.launch, 1e-9)
        assertTrue(four.branches.map { it.strandId }.distinct().size > one.branches.filter { it.launch > 0 }.map { it.strandId }.distinct().size)
        for (before in one.branches.filter { it.launch > 0 }) {
            val after = assertNotNull(four.branches.singleOrNull { it.id == before.id }, "existing route disappeared")
            assertEquals(before.strandId, after.strandId)
            assertEquals(before.pathId, after.pathId)
            assertEquals(before.endpointSeconds, after.endpointSeconds, 1e-12, "bundle reseeded existing route")
            val originalSites = one.sites.filter { it.strandId == before.strandId && it.pathId == before.pathId }
            for (site in originalSites) {
                val same = assertNotNull(four.sites.singleOrNull { it.id == site.id })
                assertEquals(site.delaySeconds, same.delaySeconds, 1e-12)
                assertEquals(site.retainedFraction, same.retainedFraction, 1e-12)
                assertEquals(site.escapedFraction, same.escapedFraction, 1e-12)
            }
        }
    }

    @Test
    fun `supported note edges and demanding finite settings remain bounded before leveling`() {
        for (voice in FilamentVoice.entries) for (midi in listOf(36, 60, 84)) for (allHigh in listOf(false, true)) {
            val macros = if (allHigh) timbral.associateWith { 1f } else Filament.defaults(voice)
            val p = Filament.inspect(voice, macros, midi = midi)
            val label = "$voice MIDI$midi allHigh$allHigh"
            val branches = listOf(p.raw, p.samples, p.source, p.through, p.escaped, p.spill)
            assertTrue(branches.all { it.size == p.samples.size }, "$label branch alignment")
            assertTrue(branches.all { it.all { sample -> sample.isFinite() } }, "$label nonfinite branch")
            assertTrue(branches.all { it.maxOf { sample -> abs(sample) } <= 4f }, "$label unbounded raw branch")
            assertTrue(p.diagnostics.maxState.isFinite() && p.diagnostics.maxState <= 4.0, "$label unbounded filter state")
            assertTrue(p.samples.maxOf { abs(it) } <= .991f, "$label exported peak")
            assertTrue(rms(p.raw) > 1e-7, "$label lost the note")
            assertTrue(abs(p.samples.average()) < .015, "$label DC")
            assertTrue(p.samples.size.toDouble() / Dsp.RATE in 1.0..8.0, "$label duration")
        }
    }

    @Test
    fun `zero velocity cannot manufacture a source or halo in finite and held renders`() {
        for (voice in FilamentVoice.entries) for (hold in listOf(0f, 1f)) {
            val p = Filament.inspect(voice, gesture + ("HOLD" to hold), velocity = 0f)
            assertTrue(listOf(p.samples, p.raw, p.source, p.through, p.escaped, p.spill).all { a -> a.all { it == 0f } },
                "$voice HOLD$hold creates sound without excitation")
        }
    }

    @Test
    fun `perfect retention produces exact spill silence including isolated spill export`() {
        for (voice in FilamentVoice.entries) for (hold in listOf(0f, 1f)) {
            val p = Filament.inspect(voice, gesture + mapOf("BEND" to 1f, "COLLECT" to 1f, "HOLD" to hold),
                config = FilamentConfig(perfectRetention = true))
            assertTrue(p.source.any { it != 0f } && p.through.any { it != 0f }, "$voice retention silenced the guided note")
            assertTrue(p.escaped.all { it == 0f } && p.spill.all { it == 0f }, "$voice phantom halo")
            assertTrue(p.raw.all { it == 0f } && p.samples.all { it == 0f }, "$voice spill-only hidden bypass")
        }
    }

    @Test
    fun `bending transfers the same excitation from through to escaped sound`() {
        val controls = gesture + mapOf("BUNDLE" to 0f, "COLLECT" to .5f)
        val straight = Filament.inspect(FilamentVoice.THREAD, controls + ("BEND" to 0f))
        val bent = Filament.inspect(FilamentVoice.THREAD, controls + ("BEND" to 1f))
        assertContentEquals(straight.source, bent.source, "bend changed injected effort")
        assertTrue(rms(bent.through) < rms(straight.through) * .8,
            "bend did not deplete endpoint: ${rms(straight.through)} -> ${rms(bent.through)}")
        assertTrue(rms(bent.escaped) > rms(straight.escaped) * 1.2,
            "bend did not feed escaped collector: ${rms(straight.escaped)} -> ${rms(bent.escaped)}")
        assertTrue(rms(bent.spill) > 1e-6, "actual escaped sound never reached the halo")
    }

    @Test
    fun `length grows transit and relative arrival spread while increasing path loss`() {
        val controls = gesture + mapOf("BUNDLE" to 0f, "INJECT" to .8f)
        val short = Filament.inspect(FilamentVoice.PULSE, controls + ("LENGTH" to 0f)).diagnostics
        val long = Filament.inspect(FilamentVoice.PULSE, controls + ("LENGTH" to 1f)).diagnostics
        val a = short.branches.filter { it.launch > 0 }
        val b = long.branches.filter { it.launch > 0 }
        assertEquals(a.map { it.id }, b.map { it.id })
        for (i in a.indices) assertTrue(b[i].endpointSeconds > a[i].endpointSeconds,
            "${a[i].id} long path did not travel longer")
        fun spread(branches: List<Filament.BranchTrace>) = branches.maxOf { it.endpointSeconds } - branches.minOf { it.endpointSeconds }
        assertTrue(spread(b) > spread(a), "length did not separate path arrivals")
        assertTrue(long.absorbed > short.absorbed, "longer travel has no accumulated absorption")
    }

    @Test
    fun `leakage location changes escape timing before the common endpoint arrival`() {
        val controls = gesture + mapOf("BUNDLE" to 0f, "BEND" to .75f, "LENGTH" to .85f)
        val early = Filament.inspect(FilamentVoice.PULSE, controls, config = FilamentConfig(profile = FilamentProfile.EARLY))
        val late = Filament.inspect(FilamentVoice.PULSE, controls, config = FilamentConfig(profile = FilamentProfile.LATE))
        assertContentEquals(early.source, late.source, "leakage profile changed excitation")
        assertEquals(early.diagnostics.branches.map { it.endpointSeconds }, late.diagnostics.branches.map { it.endpointSeconds },
            "moving a bend changed total route travel")
        fun escapedArrival(p: Filament.Probe): Double {
            val sites = p.diagnostics.sites
            val total = sites.sumOf { it.escapedPower }
            assertTrue(total > 0.0)
            return sites.sumOf { it.escapedPower * it.delaySeconds } / total
        }
        assertTrue(escapedArrival(early) < escapedArrival(late), "early and late bends donated at the same time")
        assertTrue(TangleMeasure.distance(early.escaped, late.escaped) > .01, "site timing only changes metadata")
    }

    @Test
    fun `path collapse and generic halo sends lose the routed gesture beyond output gain`() {
        val controls = gesture + mapOf("LENGTH" to .9f, "BEND" to .75f, "BUNDLE" to .8f)
        val normal = Filament.inspect(FilamentVoice.PULSE, controls)
        for ((label, options) in listOf("one arrival" to Filament.Options(collapsePaths = true),
            "generic spill send" to Filament.Options(genericSpill = true),
            "bend bypass" to Filament.Options(disableBend = true))) {
            val simplified = Filament.inspect(FilamentVoice.PULSE, controls, options = options)
            assertContentEquals(normal.source, simplified.source, "$label changed the comparison source")
            val distance = TangleMeasure.distance(normal.raw, simplified.raw)
            assertTrue(distance > .01, "$label preserved the entire gesture after gain matching: $distance")
        }
    }

    @Test
    fun `every timbral macro has low to middle activity beyond a change of gain`() {
        for (voice in FilamentVoice.entries) for (macro in timbral) {
            val low = Filament.inspect(voice, gesture + (macro to 0f)).raw
            val middle = Filament.inspect(voice, gesture + (macro to .5f)).raw
            val distance = TangleMeasure.distance(low, middle)
            println("FILAMENT $voice $macro low-middle shape distance $distance")
            assertTrue(distance > .005, "$voice $macro is inactive or only changes gain: $distance")
        }
    }

    @Test
    fun `the moderate voice roster retains the requested root and pitched export class`() {
        for (voice in FilamentVoice.entries) for (midi in listOf(36, 60, 84)) {
            val p = Filament.inspect(voice, midi = midi, velocity = .7f)
            val snip = Snip(p.samples, 1, Dsp.RATE)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(snip, wanted, .12f, .5f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            assertTrue(abs(cents) < 25.0, "$voice MIDI$midi requested root differs by $cents cents")
            assertTrue(TangleMeasure.toneShare(p.samples, wanted.toDouble()) > .025,
                "$voice MIDI$midi requested root is only a spectral trace")
            assertTrue(Classifier.classify(snip).drumClass !in TangleMeasure.drumClasses,
                "$voice MIDI$midi complete export entered a drum choke class")
            val onset = Snip(p.samples.copyOfRange(0, minOf(p.samples.size, (1.2 * Dsp.RATE).toInt())), 1, Dsp.RATE)
            val onsetClass = Classifier.classify(onset).drumClass
            assertTrue(onsetClass !in TangleMeasure.drumClasses,
                "$voice MIDI$midi onset entered $onsetClass despite the full-file loop classification")
            assertTrue(Filament.drumClassFor(voice) !in TangleMeasure.drumClasses)
        }
    }

    @Test
    fun `held propagation recurs in an independently synthesized predecessor including exported PCM`() {
        for ((voice, midi) in listOf(FilamentVoice.THREAD to 36, FilamentVoice.PULSE to 60, FilamentVoice.CURL to 84)) {
            val p = Filament.inspect(voice, gesture + ("HOLD" to 1f), midi = midi)
            val d = p.diagnostics
            val previous = assertNotNull(p.previous, "$voice lost genuine predecessor")
            assertTrue(d.loop && d.loopConverged, "$voice returned unconverged held audio")
            assertTrue(d.loopAttempts in 1..64, "$voice unbounded convergence search")
            assertEquals(p.samples.size, previous.size)
            assertEquals(p.samples.size, d.periodFrames)
            assertTrue(d.seamError != null && d.seamError!! < Keys.MAX_SEAM_ERROR)
            val energy = previous.sumOf { it.toDouble() * it }
            val difference = previous.indices.sumOf { val delta = previous[it].toDouble() - p.samples[it]; delta * delta }
            assertTrue(energy > 0.0 && difference / energy < 1e-5,
                "$voice full independent cycle did not recur: ${difference / energy}")
            assertTrue(rms(p.spill) > 1e-6, "$voice obtained a loop by removing leakage")
            val context = previous + p.samples
            assertTrue(Keys.seamError(context, previous.size) < Keys.MAX_SEAM_ERROR)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, Snip(context, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16) }
            val decoded = WavReader.read(bytes.toByteArray()).samples
            val reference = (previous.size - 256 until previous.size).sumOf { decoded[it].toDouble() * decoded[it] }
            if (reference == 0.0) {
                assertTrue((previous.size - 256 until previous.size).all { decoded[it] == 0f && decoded[it + previous.size] == 0f },
                    "$voice changed a silent predecessor boundary")
                assertEquals(0f, decoded[previous.size], "$voice begins after silence with a click")
                assertEquals(0f, decoded.last(), "$voice ends before silence with a click")
            } else assertTrue(Keys.seamError(decoded, previous.size) < Keys.MAX_SEAM_ERROR, "$voice PCM seam")
        }
    }
}

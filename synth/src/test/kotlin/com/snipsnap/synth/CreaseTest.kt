package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Listening-prototype checks. Distances independently remove gain and compare several
 * time windows: different level, initial phase or one changed attack cannot alone prove
 * the pressure history, layer load and unfolding gestures work.
 */
class CreaseTest {
    private val timbral = listOf("FOLD", "PRESSURE", "DRAG", "SPLIT", "RELAX")
    private val bodyWindows = listOf(.04, .12, .25, .5, .8)

    @Test
    fun `published model one recipes retain their exact original sample hashes`() {
        data class Original(val voice: CreaseVoice, val macros: Map<String, Float>, val history: CreaseHistory,
            val normalized: String, val prelevel: String)
        val expressive = mapOf("FOLD" to .6f, "PRESSURE" to .85f, "DRAG" to .85f, "SPLIT" to .7f, "RELAX" to .8f, "HOLD" to 0f)
        val originals = listOf(
            Original(CreaseVoice.ROUND, mapOf("FOLD" to .2f, "PRESSURE" to .25f, "DRAG" to .15f, "SPLIT" to .15f, "RELAX" to .3f, "HOLD" to 0f),
                CreaseHistory.STATEFUL, "59ae9d4d7c8156e35df33ffad07c543b7877e0dcc8be54e42a2e7f4460957fdb", "aa9b6cbe90584372c8cf89d58c6d6efa638ecee0726b40ad476ad56b5734b2a1"),
            Original(CreaseVoice.PINCH, expressive, CreaseHistory.INSTANTANEOUS,
                "a76ba325fd2b58d853f550b255a16a52f91d776bff798ee55cde5a6483dc497f", "de6f8bc278bacf0bf4a572586e3d6538af418d013f61dd04f40dd9aed023e7c4"),
            Original(CreaseVoice.RUBBER, expressive + ("HOLD" to 1f), CreaseHistory.STATEFUL,
                "26e13098d6f571797b3461a356561d5466d3a346fc8420028817c5ee2f94183c", "bb55f3f44a8afcc2e36e75cca86d33d155505439de7b5f7c7c884998d28417d5"),
        )
        for (original in originals) {
            val p = Crease.probe(original.voice, original.macros, midi = 60, velocity = .8f, history = original.history, model = 1)
            assertEquals(original.normalized, hash(p.samples), "${original.voice} published normalized audio changed")
            assertEquals(original.prelevel, hash(p.prelevel), "${original.voice} published prelevel audio changed")
            assertEquals(1, p.diagnostics.modelVersion)
        }
    }

    @Test
    fun `default voices have substantial gain independent harmonic separation that model one lacks`() {
        val issues = mutableListOf<String>()
        val profiles = CreaseVoice.entries.associateWith { voice ->
            val probe = Crease.probe(voice, midi = 60, velocity = .8f)
            assertEquals(2, probe.diagnostics.modelVersion, "new auditions must select model two explicitly or by default")
            bodyWindows.map { harmonicProfile(probe.prelevel, Keys.midiHz(60).toDouble(), it) }
        }
        for (voice in CreaseVoice.entries) {
            val overtones = profiles.getValue(voice).map { 1.0 - it[0] }
            println("CREASE identity $voice overtoneMean=${overtones.average()} overtonePeak=${overtones.max()} windows=$overtones")
            if (voice == CreaseVoice.ROUND) continue
            val meanFloor = if (voice == CreaseVoice.PINCH) .20 else .15
            val peakFloor = if (voice == CreaseVoice.PINCH) .25 else .20
            issues.expect(overtones.average() >= meanFloor, "$voice still spends nearly all its power on the fundamental: mean overtone power ${overtones.average()} < $meanFloor")
            issues.expect(overtones.max() >= peakFloor, "$voice never develops substantive upper harmonics: peak overtone power ${overtones.max()} < $peakFloor")
        }
        val voices = CreaseVoice.entries
        for (i in voices.indices) for (j in i + 1 until voices.size) {
            val a = averageProfile(profiles.getValue(voices[i]))
            val b = averageProfile(profiles.getValue(voices[j]))
            val separation = powerDistance(a, b)
            println("CREASE voice pair ${voices[i]}/${voices[j]} harmonicPowerDistance=$separation")
            issues.expect(separation >= .12, "${voices[i]}/${voices[j]} remain spectrally similar: only $separation of harmonic power moves")
        }
        // The rejected build is a negative control: its original published controls put
        // 98.9–99.4% of harmonic power into the root, with pair distances below .006.
        // Large distances between inaudible residual harmonics cannot pass the new gates.
        val legacy = CreaseVoice.entries.associateWith { voice ->
            val p = Crease.probe(voice, originalDefaults(voice), midi = 60, velocity = .8f, model = 1)
            averageProfile(bodyWindows.map { harmonicProfile(p.prelevel, Keys.midiHz(60).toDouble(), it) })
        }
        for (i in voices.indices) for (j in i + 1 until voices.size) {
            val oldDistance = powerDistance(legacy.getValue(voices[i]), legacy.getValue(voices[j]))
            println("CREASE rejected model one ${voices[i]}/${voices[j]} harmonicPowerDistance=$oldDistance")
            assertTrue(oldDistance < .02, "the frozen rejected build unexpectedly passes the separation control")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `expressive pressure moves audible harmonic power through the note instead of only moving phase`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) {
            val macros = Crease.defaults(voice) + mapOf("FOLD" to .7f, "PRESSURE" to .85f, "DRAG" to .7f, "SPLIT" to .65f, "RELAX" to .7f)
            val p = Crease.probe(voice, macros, midi = 60, velocity = .8f)
            val profiles = bodyWindows.map { harmonicProfile(p.prelevel, Keys.midiHz(60).toDouble(), it) }
            val overtones = profiles.map { 1.0 - it[0] }
            val ratiosDb = overtones.map { 10.0 * ln(it.coerceAtLeast(1e-12) / (1.0 - it).coerceAtLeast(1e-12)) / ln(10.0) }
            val motion = profiles.indices.maxOf { i -> profiles.indices.maxOf { j -> powerDistance(profiles[i], profiles[j]) } }
            val spanDb = ratiosDb.max() - ratiosDb.min()
            // Formant/notch movement can redistribute upper harmonics while preserving
            // their combined power. Require a 4dB movement in a useful individual band,
            // with at least 3% peak and 1.5% mean signal power, rather than counting a
            // large ratio change in the rejected model's tiny residual harmonics.
            val usefulBands = (1..15).filter { harmonic ->
                val powers = profiles.map { it[harmonic] }
                powers.max() >= .03 && powers.average() >= .015
            }.map { harmonic ->
                val db = profiles.map { 10.0 * ln(it[harmonic].coerceAtLeast(1e-12) / it[0].coerceAtLeast(1e-12)) / ln(10.0) }
                (harmonic + 1) to (db.max() - db.min())
            }
            val usefulBand = usefulBands.maxByOrNull { it.second }
            val usefulSpan = usefulBand?.second ?: 0.0
            println("CREASE harmonic motion $voice maximumPowerDistance=$motion overtoneRootDbSpan=$spanDb usefulHarmonic=${usefulBand?.first} usefulBandRootDbSpan=$usefulSpan overtonePower=$overtones ratioDb=$ratiosDb")
            issues.expect(motion >= .08, "$voice has little actual harmonic motion: $motion < .08")
            issues.expect(overtones.max() >= .10, "$voice motion occurs only in weak residual harmonics: ${overtones.max()}")
            issues.expect(usefulSpan >= 4.0, "$voice has no 4dB movement in a substantial harmonic/root band: $usefulSpan dB")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `repeat renders and reordered macro maps are sample identical`() {
        for (voice in CreaseVoice.entries) {
            val macros = Crease.defaults(voice)
            val a = Crease.probe(voice, macros, midi = 55, velocity = .63f)
            val b = Crease.probe(voice, macros.entries.reversed().associate { it.key to it.value }, midi = 55, velocity = .63f)
            assertContentEquals(a.prelevel, b.prelevel, "$voice prelevel determinism")
            assertContentEquals(a.samples, b.samples, "$voice output determinism")
            assertContentEquals(a.trace.deformationA, b.trace.deformationA, "$voice history determinism")
            assertContentEquals(a.trace.loadB, b.trace.loadB, "$voice load determinism")
            assertEquals(44_100, a.sampleRate)
            assertFalse(a.diagnostics.held)
        }
    }

    @Test
    fun `default and all high notes retain numerical headroom before leveling`() {
        for (voice in CreaseVoice.entries) for (midi in listOf(24, 54, 84)) for (high in listOf(false, true)) {
            val macros = Crease.defaults(voice) + if (high) timbral.associateWith { 1f } else emptyMap()
            val p = Crease.probe(voice, macros, midi = midi, velocity = 1f)
            val label = "$voice MIDI$midi allHigh=$high"
            val d = p.diagnostics
            assertTrue(p.prelevel.isNotEmpty() && p.samples.isNotEmpty(), "$label empty audio")
            assertTrue(p.prelevel.all { it.isFinite() } && p.samples.all { it.isFinite() }, "$label non-finite audio")
            for (trace in listOf(p.trace.timeSeconds, p.trace.pressure, p.trace.deformationA, p.trace.deformationB, p.trace.loadA, p.trace.loadB)) {
                assertTrue(trace.isNotEmpty() && trace.all { it.isFinite() }, "$label non-finite or absent trace")
            }
            assertTrue(d.rawPeak.isFinite() && d.rawPeak < 1.1, "$label raw peak ${d.rawPeak}")
            assertTrue(d.maxAbsState.isFinite() && d.maxAbsState < 1.01, "$label runaway state ${d.maxAbsState}")
            assertEquals(2, d.modelVersion, "$label unexpectedly rendered the rejected model")
            assertEquals(null, d.maxCoefficientBudget, "$label reported a Fourier budget for a different map family")
            // Model two bounds the positive derivative directly. Its maximum low-register
            // log-compression is 3.6, so importing model one's .1 derivative floor would
            // reject useful compression for the wrong mathematical reason.
            val maximumDerivative = kotlin.math.exp(3.6)
            assertTrue(d.minMapDerivative >= 1.0 / maximumDerivative - 1e-6, "$label phase reversal or excess compression ${d.minMapDerivative}")
            assertTrue(d.maxMapDerivative.isFinite() && d.maxMapDerivative <= maximumDerivative + 1e-4,
                "$label unbounded phase rate ${d.maxMapDerivative}")
            assertTrue(d.maxMapCurvature.isFinite() && d.maxMapCurvature <= Crease.MAX_MAP_CURVATURE,
                "$label unbounded map curvature ${d.maxMapCurvature}")
            assertTrue(rms(p.prelevel) > 1e-4, "$label silent raw signal")
            assertTrue(abs(p.prelevel.average()) < .01, "$label prelevel DC ${p.prelevel.average()}")
            assertTrue(abs(p.samples.average()) < .01, "$label exported DC ${p.samples.average()}")
            assertTrue(p.samples.all { it in -1f..1f }, "$label clipped final output")
            println("CREASE bounds $label rawPeak=${d.rawPeak} state=${d.maxAbsState} derivative=${d.minMapDerivative}..${d.maxMapDerivative} curvature=${d.maxMapCurvature} dc=${p.prelevel.average()}")
        }
    }

    @Test
    fun `each macro changes both low and middle steps after gain matching`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) for (macro in timbral) {
            val base = Crease.defaults(voice) + ("HOLD" to 0f)
            val low = Crease.probe(voice, base + (macro to 0f)).prelevel
            val quarter = Crease.probe(voice, base + (macro to .25f)).prelevel
            val middle = Crease.probe(voice, base + (macro to .5f)).prelevel
            val first = distance(low, quarter)
            val next = distance(quarter, middle)
            val firstSpectrum = trajectoryDistance(low, quarter)
            val nextSpectrum = trajectoryDistance(quarter, middle)
            println("CREASE macro $voice $macro first=$first middle=$next firstSpectrum=$firstSpectrum middleSpectrum=$nextSpectrum")
            issues.expect(first > .001, "$voice/$macro low quarter is inactive after gain matching: $first")
            issues.expect(next > .001, "$voice/$macro middle quarter is inactive after gain matching: $next")
            issues.expect(firstSpectrum > .0001, "$voice/$macro low quarter changes no measured spectral trajectory: $firstSpectrum")
            issues.expect(nextSpectrum > .0001, "$voice/$macro middle quarter changes no measured spectral trajectory: $nextSpectrum")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `history and reciprocal load change spectral evolution with the same excitation envelope`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) {
            val macros = Crease.defaults(voice) + mapOf("PRESSURE" to .8f, "DRAG" to .65f, "SPLIT" to .65f, "RELAX" to .7f)
            val memory = Crease.probe(voice, macros)
            val instantaneous = Crease.probe(voice, macros, history = CreaseHistory.INSTANTANEOUS)
            val independent = Crease.probe(voice, macros, coupling = false)
            assertEquals(memory.samples.size, instantaneous.samples.size, "$voice history changed the render envelope length")
            assertEquals(memory.samples.size, independent.samples.size, "$voice coupling changed render duration")
            assertContentEquals(memory.trace.pressure, instantaneous.trace.pressure, "$voice history also changed excitation")
            assertContentEquals(memory.trace.pressure, independent.trace.pressure, "$voice coupling also changed excitation")
            val wholeHistory = trajectoryDistance(memory.prelevel, instantaneous.prelevel)
            val historyWindows = listOf(.04, .12, .25, .5, .8).map { seconds ->
                spectralDistanceAt(memory.prelevel, instantaneous.prelevel, (seconds * Dsp.RATE).toInt())
            }
            val historyMean = historyWindows.average()
            val historyMaximum = historyWindows.max()
            val coupling = trajectoryDistance(memory.prelevel, independent.prelevel)
            println("CREASE mechanism $voice historyBodyMean=$historyMean historyBodyMaximum=$historyMaximum historyWholeNote=$wholeHistory bodyWindows=$historyWindows coupling=$coupling")
            // First prototype probes: minimum mean .001568, minimum maximum .002938.
            // History must change the evolving body, and may finish unfolding before the tail.
            // These regression floors measure operation; listening determines audibility.
            issues.expect(historyMean > .001, "$voice history has no sustained spectral difference during the body: $historyMean")
            issues.expect(historyMaximum > .002, "$voice history has no distinct spectral gesture during the body: $historyMaximum")
            issues.expect(coupling > .0001, "$voice layer load has no independent measured spectral change: $coupling")
            issues.expect(distance(memory.prelevel, instantaneous.prelevel) > .01, "$voice history only changes gain")
            issues.expect(distance(memory.prelevel, independent.prelevel) > .001, "$voice coupling only changes gain")
            val afterimage = memory.trace.pressure.indices.filter {
                memory.trace.timeSeconds[it] > .2f && memory.trace.pressure[it] < .08f
            }.maxOfOrNull {
                maxOf(memory.trace.deformationA[it] - instantaneous.trace.deformationA[it],
                    memory.trace.deformationB[it] - instantaneous.trace.deformationB[it]).toDouble()
            } ?: 0.0
            println("CREASE history $voice deformationAfterPressureFalls=$afterimage")
            issues.expect(afterimage > .01, "$voice deformation forgets pressure immediately: $afterimage")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `velocity changes excitation shape rather than only normalized level`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) {
            val soft = Crease.probe(voice, velocity = .25f)
            val hard = Crease.probe(voice, velocity = 1f)
            val spectral = trajectoryDistance(soft.prelevel, hard.prelevel)
            val shape = distance(soft.prelevel, hard.prelevel)
            println("CREASE velocity $voice normalizedDistance=$shape spectralDistance=$spectral")
            issues.expect(shape > .01, "$voice velocity changes only gain: $shape")
            issues.expect(spectral > .002, "$voice velocity has no independent spectral effect: $spectral")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `moderate voices preserve a measurable requested root at low middle and high velocity`() {
        for (voice in CreaseVoice.entries) for (midi in listOf(24, 36, 48, 60, 72, 84)) for (velocity in listOf(.3f, .65f, 1f)) {
            val p = Crease.probe(voice, midi = midi, velocity = velocity)
            val snip = Snip(p.samples, 1, p.sampleRate)
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(snip, wanted, .12f, .6f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            val share = toneShare(p.samples, wanted.toDouble(), p.sampleRate)
            println("CREASE pitch $voice MIDI$midi velocity=$velocity cents=$cents rootShare=$share")
            assertTrue(abs(cents) < 10.0, "$voice MIDI$midi velocity$velocity root error $cents cents")
            assertTrue(share > .08, "$voice MIDI$midi velocity$velocity has only a weak root component: $share")
            if (midi in 36..72) {
                val detected = Pitch.detect(snip, .12f, .35f)
                assertTrue(detected != null && detected.confidence > .3f, "$voice MIDI$midi velocity$velocity weak periodic body: $detected")
                assertTrue(abs(FineTuning.cents(detected!!.hz.toDouble(), wanted.toDouble())) < 50.0,
                    "$voice MIDI$midi velocity$velocity detector chose ${detected.hz}Hz instead of $wanted")
            }
        }
    }

    @Test
    fun `finite HOLD steps extend the articulation before the loop endpoint`() {
        for (voice in CreaseVoice.entries) {
            val steps = listOf(0f, .25f, .5f).map { hold ->
                Crease.probe(voice, Crease.defaults(voice) + ("HOLD" to hold))
            }
            for (p in steps) assertFalse(p.diagnostics.held, "$voice finite HOLD unexpectedly selected a loop")
            for ((short, long) in steps.zipWithNext()) {
                assertTrue(long.samples.size > short.samples.size + Dsp.RATE / 20,
                    "$voice finite HOLD has a dead quarter step: ${short.samples.size} / ${long.samples.size} frames")
                val release = short.samples.size - Dsp.RATE / 4
                val shortTail = rms(short.samples.copyOfRange(release, short.samples.size))
                val longBody = rms(long.samples.copyOfRange(release, short.samples.size))
                assertTrue(longBody > shortTail * 1.2,
                    "$voice HOLD pads silence rather than extending articulation: $shortTail / $longBody")
            }
        }
    }

    @Test
    fun `zero velocity produces finite silence for notes and converged loops`() {
        for (voice in CreaseVoice.entries) for (hold in listOf(0f, 1f)) {
            val p = Crease.probe(voice, Crease.defaults(voice) + ("HOLD" to hold), velocity = 0f)
            assertTrue(p.samples.all { it == 0f } && p.prelevel.all { it == 0f }, "$voice HOLD$hold zero velocity is not silent")
            assertTrue(p.diagnostics.seamError.isFinite(), "$voice HOLD$hold zero-velocity seam is NaN")
            assertTrue(p.diagnostics.maxAbsState.isFinite(), "$voice HOLD$hold zero-velocity state is non-finite")
        }
    }

    @Test
    fun `unprobed pitches outside the supported prototype range are refused`() {
        for (midi in listOf(23, 85)) {
            val error = assertFailsWith<IllegalArgumentException> { Crease.render(CreaseVoice.ROUND, midi = midi) }
            assertTrue(error.message.orEmpty().contains("MIDI 24..84"), "unsupported range refusal lacks context: ${error.message}")
        }
    }

    @Test
    fun `held loops recur through independent prior audio and survive PCM export with active deformation`() {
        for (voice in CreaseVoice.entries) {
            val p = Crease.probe(voice, Crease.defaults(voice) + ("HOLD" to 1f))
            val d = p.diagnostics
            assertTrue(d.held && d.converged, "$voice did not converge a held trajectory")
            assertEquals(d.loopFrames, p.samples.size, "$voice loop period does not match exported audio")
            assertEquals(p.samples.size, p.previousCycle.size, "$voice missing independent previous period")
            assertTrue(d.prerollCycles in 2..64, "$voice unbounded or unmeasured preroll ${d.prerollCycles}")
            assertTrue(d.stateError.isFinite() && d.stateError < 1e-3, "$voice synthesis state recurrence ${d.stateError}")
            assertTrue(p.loopStateStart.isNotEmpty(), "$voice missing full-state boundary snapshots")
            assertEquals(p.loopStateStart.size, p.loopStateEnd.size, "$voice incompatible boundary snapshots")
            val independentStateError = p.loopStateStart.indices.maxOf { abs(p.loopStateStart[it] - p.loopStateEnd[it]) }
            assertEquals(independentStateError, d.stateError, 1e-12, "$voice state-error diagnostic disagrees with snapshots")
            assertTrue(independentStateError < 1e-3, "$voice independently measured state recurrence $independentStateError")
            assertTrue(d.rootCycles > 0, "$voice lacks a closed oscillator clock")
            assertEquals(d.rootCycles * p.sampleRate.toDouble() / p.samples.size, d.renderedHz, 1e-12,
                "$voice oscillator did not complete an integer number of cycles")
            val independentContext = p.previousCycle + p.samples
            val measuredSeam = Keys.seamError(independentContext, p.previousCycle.size)
            assertTrue(measuredSeam < 1e-3, "$voice independent-period seam $measuredSeam")
            assertTrue(distance(p.previousCycle, p.samples) < .003, "$voice periods differ outside the seam window")
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, Snip(independentContext, 1, p.sampleRate), WavWriter.BitDepth.PCM_24) }
            val pcm = WavReader.read(bytes.toByteArray()).samples
            val exportedSeam = Keys.seamError(pcm, p.previousCycle.size)
            assertTrue(exportedSeam < 1e-3, "$voice exported PCM24 independent-period seam $exportedSeam")
            val pressureSpan = span(p.trace.pressure)
            val deformationSpan = maxOf(span(p.trace.deformationA), span(p.trace.deformationB))
            println("CREASE hold $voice cycles=${d.prerollCycles} state=${d.stateError} seam=$measuredSeam pcm=$exportedSeam pressureSpan=$pressureSpan deformationSpan=$deformationSpan")
            assertTrue(pressureSpan > .01, "$voice held pressure became static")
            assertTrue(deformationSpan > .005, "$voice held deformation became static")
            val wanted = Keys.midiHz(48)
            val measured = FineTuning.measuredHz(p.samples, p.sampleRate, wanted, .12f, .6f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 10.0, "$voice HOLD moved requested root")
        }
    }

    @Test
    fun `held RELAX and the other memory controls still reshape a constant amplitude sustain`() {
        val cases = CreaseVoice.entries.map { it to "RELAX" } +
            listOf("PRESSURE", "DRAG", "SPLIT").map { CreaseVoice.RUBBER to it }
        val issues = mutableListOf<String>()
        for ((voice, macro) in cases) {
            val base = Crease.defaults(voice) + ("HOLD" to 1f)
            val low = Crease.probe(voice, base + (macro to 0f))
            val high = Crease.probe(voice, base + (macro to 1f))
            assertEquals(low.samples.size, high.samples.size, "$voice/$macro changed the held pressure period")
            assertContentEquals(low.trace.pressure, high.trace.pressure, "$voice/$macro changed the held excitation schedule")
            val shape = distance(low.prelevel, high.prelevel)
            val spectral = trajectoryDistance(low.prelevel, high.prelevel)
            println("CREASE held control $voice $macro normalizedDistance=$shape spectralDistance=$spectral")
            issues.expect(shape > .005, "$voice/$macro has no held motion after gain matching: $shape")
            issues.expect(spectral > .0001, "$voice/$macro does not reshape held spectral evolution: $spectral")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `DRAG delays the same held pressure rise rather than only changing a static colour`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) {
            val base = Crease.defaults(voice) + mapOf("HOLD" to 1f, "PRESSURE" to .85f, "SPLIT" to .5f)
            val fast = Crease.probe(voice, base + ("DRAG" to 0f))
            val slow = Crease.probe(voice, base + ("DRAG" to 1f))
            assertContentEquals(fast.trace.pressure, slow.trace.pressure, "$voice DRAG also changed the held player gesture")
            val fastTime = risingHalfTime(fast.trace.timeSeconds, fast.trace.deformationA)
            val slowTime = risingHalfTime(slow.trace.timeSeconds, slow.trace.deformationA)
            val delay = slowTime - fastTime
            println("CREASE DRAG $voice fastHalfTime=$fastTime slowHalfTime=$slowTime responseDelay=$delay")
            issues.expect(delay >= .015, "$voice DRAG does not add a measured body response lag: $delay seconds")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    @Test
    fun `four times oversampling remains close to a sixteen times high register reference`() {
        val issues = mutableListOf<String>()
        for (voice in CreaseVoice.entries) {
            val macros = Crease.defaults(voice) + timbral.associateWith { 1f }
            val normal = Crease.probe(voice, macros, midi = 84, velocity = 1f, oversample = 4)
            val reference = Crease.probe(voice, macros, midi = 84, velocity = 1f, oversample = 16)
            val spectral = trajectoryDistance(normal.prelevel, reference.prelevel)
            val off4 = offHarmonicPower(normal.prelevel, Keys.midiHz(84).toDouble())
            val off16 = offHarmonicPower(reference.prelevel, Keys.midiHz(84).toDouble())
            val excess = (off4 - off16).coerceAtLeast(0.0)
            println("CREASE reference $voice MIDI84 all-high spectralDistance4x16x=$spectral offHarmonic4x=$off4 offHarmonic16x=$off16 excessOffHarmonicPower=$excess")
            issues.expect(spectral < 1e-4, "$voice four-times output differs materially from sixteen-times reference: $spectral")
            issues.expect(excess < 1e-4, "$voice adds non-harmonic power above its high-rate reference: $excess")
        }
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    /** Whole-signal unit-RMS distance; pure gain changes return zero. */
    private fun distance(a: FloatArray, b: FloatArray): Double {
        val size = minOf(a.size, b.size)
        val ar = rms(a).coerceAtLeast(1e-12)
        val br = rms(b).coerceAtLeast(1e-12)
        return sqrt((0 until size).sumOf { i -> val d = a[i] / ar - b[i] / br; d * d } / size.coerceAtLeast(1))
    }

    /** Mean total variation of normalized spectral power at attack, body and release. */
    private fun trajectoryDistance(a: FloatArray, b: FloatArray): Double {
        val length = minOf(a.size, b.size)
        val starts = listOf(.02, .14, .35, .65).map { (it * length).toInt() }
        return starts.sumOf { start -> spectralDistanceAt(a, b, start) } / starts.size
    }

    private fun spectralDistanceAt(a: FloatArray, b: FloatArray, start: Int): Double {
        val x = spectrum(a, start)
        val y = spectrum(b, start)
        return x.indices.sumOf { abs(x[it] - y[it]) } / 2.0
    }

    private fun spectrum(samples: FloatArray, start: Int): DoubleArray {
        val size = 8192
        val magnitude = Fft.magnitudeSpectrum(samples.copyOfRange(start, minOf(samples.size, start + size)), size)
        val bands = DoubleArray(48)
        for (bin in 1 until magnitude.size) {
            val hz = bin * Dsp.RATE.toDouble() / size
            val band = (ln(hz.coerceAtLeast(25.0) / 25.0) / ln(2.0) * 4.5).toInt().coerceIn(0, bands.lastIndex)
            bands[band] += magnitude[bin].toDouble() * magnitude[bin]
        }
        val total = bands.sum().coerceAtLeast(1e-30)
        return DoubleArray(bands.size) { bands[it] / total }
    }

    /** Actual overtone power, rather than coherent-root loss caused by a moving amplitude
     * envelope. Hann-windowed bands include the main lobe around each of 32 harmonics. */
    private fun harmonicProfile(samples: FloatArray, fundamental: Double, fromSeconds: Double): DoubleArray {
        val from = (fromSeconds * Dsp.RATE).toInt()
        val count = minOf((.25 * Dsp.RATE).toInt(), samples.size - from)
        require(count > 1024) { "no body window after $fromSeconds seconds" }
        val size = 65536
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        val mean = (from until from + count).sumOf { samples[it].toDouble() } / count
        for (i in 0 until count) {
            val window = .5 - .5 * cos(2.0 * PI * i / (count - 1))
            real[i] = ((samples[from + i] - mean) * window).toFloat()
        }
        Fft.forward(real, imaginary)
        val powers = DoubleArray(32)
        for (harmonic in 1..32) {
            val center = fundamental * harmonic
            if (center >= Dsp.RATE / 2.0 - 12.0) continue
            val low = ((center - 12.0) * size / Dsp.RATE).toInt().coerceAtLeast(1)
            val high = ((center + 12.0) * size / Dsp.RATE).toInt().coerceAtMost(size / 2)
            for (bin in low..high) powers[harmonic - 1] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
        }
        val total = powers.sum().coerceAtLeast(1e-30)
        return DoubleArray(powers.size) { powers[it] / total }
    }

    private fun averageProfile(profiles: List<DoubleArray>): DoubleArray =
        DoubleArray(profiles.first().size) { i -> profiles.sumOf { it[i] } / profiles.size }

    private fun powerDistance(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) } / 2.0

    /** Harmonic/control sidebands receive a 30Hz guard. Excess against the identical
     * higher-rate render is a practical alias-spur diagnostic, not total alias energy. */
    private fun offHarmonicPower(samples: FloatArray, fundamental: Double): Double {
        val size = 16384
        val start = (.12 * Dsp.RATE).toInt()
        val magnitude = Fft.magnitudeSpectrum(samples.copyOfRange(start, start + size), size)
        var total = 0.0
        var outside = 0.0
        for (bin in 1 until magnitude.size) {
            val hz = bin * Dsp.RATE.toDouble() / size
            if (hz < 30.0 || hz > 20000.0) continue
            val power = magnitude[bin].toDouble() * magnitude[bin]
            total += power
            val harmonic = kotlin.math.round(hz / fundamental)
            if (harmonic < 1.0 || abs(hz - harmonic * fundamental) > 30.0) outside += power
        }
        return outside / total.coerceAtLeast(1e-30)
    }

    private fun originalDefaults(voice: CreaseVoice): Map<String, Float> {
        val values = when (voice) {
            CreaseVoice.ROUND -> floatArrayOf(.20f, .25f, .15f, .15f, .30f)
            CreaseVoice.PINCH -> floatArrayOf(.70f, .55f, .20f, .45f, .35f)
            CreaseVoice.RUBBER -> floatArrayOf(.50f, .70f, .75f, .55f, .65f)
        }
        return timbral.mapIndexed { i, name -> name to values[i] }.toMap() + ("HOLD" to 0f)
    }

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun toneShare(samples: FloatArray, hz: Double, rate: Int): Double {
        val from = (.12 * rate).toInt()
        val to = minOf(samples.size, from + (.6 * rate).toInt())
        var re = 0.0
        var im = 0.0
        var power = 0.0
        for (i in from until to) {
            val x = samples[i].toDouble()
            re += x * cos(2.0 * PI * hz * i / rate)
            im += x * sin(2.0 * PI * hz * i / rate)
            power += x * x
        }
        return (2.0 * (re * re + im * im) / ((to - from) * power).coerceAtLeast(1e-30)).coerceIn(0.0, 1.0)
    }

    private fun span(values: FloatArray): Double = (values.maxOrNull()!! - values.minOrNull()!!).toDouble()

    private fun risingHalfTime(time: FloatArray, values: FloatArray): Double {
        val peak = values.indices.maxBy { values[it] }
        val trough = (0..peak).minBy { values[it] }
        val threshold = (values[trough] + values[peak]) / 2f
        return time[(trough..peak).first { values[it] >= threshold }].toDouble()
    }

    private fun MutableList<String>.expect(condition: Boolean, message: String) {
        if (!condition) add(message)
    }
}

package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** R9 is a bass-register balance correction; all older motion and touch bars remain separate. */
class SkitterBassTest {
    @Test
    fun `common C2 and C3 bass bodies put more power in the fundamental through the powered note`() {
        for (midi in listOf(36, 48)) for (velocity in listOf(.6f, 1f)) {
            val take = SkitterBassMeasure.take("common", midi, velocity)
            SkitterBassMeasure.print(take)
            // Frozen before the candidate: half the third-to-eighth/root power ratio,
            // and at least one percentage point more actual fundamental power. Lowering
            // gain or moving only an onset cannot satisfy a normalized sustained measure.
            assertTrue(take.upperDeltaDb <= -3.0, "${take.label} upper/root balance changes only ${take.upperDeltaDb}dB")
            assertTrue(take.rootShareDelta >= .01, "${take.label} adds only ${take.rootShareDelta} fundamental power share")
        }
    }

    @Test
    fun `the bass foundation keeps C1 to C3 roots deterministic finite and genuinely held`() {
        for (midi in listOf(24, 36, 48)) for (velocity in listOf(.25f, .6f, 1f)) {
            val take = SkitterBassMeasure.take("common", midi, velocity)
            val audio = take.full
            val pitch = assertNotNull(SkitterBassMeasure.lowPitch(audio.raw), "${take.label} has no low-register periodic root")
            val cents = 1200.0 * ln(pitch.first / SkitterBassMeasure.hz(midi)) / ln(2.0)
            assertTrue(abs(cents) < 5.0, "${take.label} low-register root error $cents cents")
            assertTrue(pitch.second >= .75, "${take.label} low-register periodic confidence ${pitch.second}")
            assertTrue(audio.raw.all { it.isFinite() && abs(it) < .8f }, "${take.label} exceeds raw headroom")
            assertEquals(0, audio.diagnostics.boundHits, "${take.label} reaches an emergency bound")
            assertTrue(abs(audio.diagnostics.rawDc) / audio.diagnostics.rawRms < .01, "${take.label} leaks DC")
        }
        val original = SkitterBassMeasure.take("common", 36, .6f).full
        val repeat = SkitterBassMeasure.render("common", 36, .6f)
        assertContentEquals(original.raw, repeat.raw, "bass foundation is not raw deterministic")
        assertContentEquals(original.snip.samples, repeat.snip.samples, "bass foundation is not export deterministic")
        for (midi in listOf(24, 36, 48)) {
            val held = Skitter.renderProbe(SkitterVoice.BASS, SkitterBassMeasure.common + ("HOLD" to 1f),
                midi = midi, velocity = .6f, seed = 17)
            assertTrue(held.diagnostics.loopConverged, "BASS MIDI$midi does not reach its full held state")
            assertTrue(held.diagnostics.loopStateError < Skitter.LOOP_STATE_TOLERANCE)
            assertTrue(Keys.seamError(held.raw, held.loopStart) < Keys.MAX_SEAM_ERROR, "BASS MIDI$midi real raw periods do not close")
            assertEquals(0, held.diagnostics.boundHits)
        }
        for (hold in listOf(0f, 1f)) {
            val zero = Skitter.renderProbe(SkitterVoice.BASS, SkitterBassMeasure.common + ("HOLD" to hold), midi = 24, velocity = 0f)
            assertTrue(zero.raw.all { it == 0f } && zero.snip.samples.all { it == 0f }, "BASS HOLD$hold has sound at zero velocity")
        }
    }

    @Test
    fun `the bass readout preserves every recorded field of the original feedback circuit`() {
        for (midi in listOf(24, 36, 48)) for (velocity in listOf(.25f, .6f, 1f)) {
            val take = SkitterBassMeasure.take("common", midi, velocity)
            val old = take.old
            val full = take.full
            // The readout may change audio and its DC follower, but must not become
            // another feedback input. These traces include every circuit descriptor
            // and all four bridge states, with no tolerance for hidden modulation.
            assertTrue(old.trace.isNotEmpty(), "${take.label} has no circuit evidence")
            assertEquals(old.trace.size, full.trace.size, "${take.label} changes trace timing")
            for (i in old.trace.indices) {
                // Report only the first differing point, not two complete trace arrays.
                assertEquals(old.trace[i], full.trace[i], "${take.label} changes feedback at trace point $i")
            }
            // Peaks cover every internal frame, including changes between trace points.
            val a = old.diagnostics
            val b = full.diagnostics
            assertEquals(a.filterStatePeak, b.filterStatePeak, "${take.label} changes filter state")
            assertEquals(a.historyPeak, b.historyPeak, "${take.label} changes energy memory")
            assertEquals(a.signedHistoryPeak, b.signedHistoryPeak, "${take.label} changes signed memory")
            assertEquals(a.depthStatePeak, b.depthStatePeak, "${take.label} changes bridge states")
            assertEquals(a.depthReturnPeak, b.depthReturnPeak, "${take.label} changes bridge return")
            assertEquals(a.depthExchangePeak, b.depthExchangePeak, "${take.label} changes bridge exchange")
        }
    }
}

/** Gain-independent harmonic evidence at the real common patch, plus diagnostic-only defaults. */
internal object SkitterBassMeasure {
    val common = mapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val starts = listOf(.50, .70, .90)
    private const val size = 16384
    private const val harmonicHalfWidth = .35
    private val cache = mutableMapOf<Triple<String, Int, Float>, Take>()

    data class Window(val fundamentalShare: Double, val upperOverRootDb: Double, val secondOverRootDb: Double)
    data class Take(val profile: String, val midi: Int, val velocity: Float, val full: SkitterRender,
                    val old: SkitterRender, val windows: List<Window>, val oldWindows: List<Window>) {
        val label get() = "BASS $profile MIDI$midi velocity$velocity"
        val upperDeltaDb get() = windows.zip(oldWindows).sumOf { (a, b) -> a.upperOverRootDb - b.upperOverRootDb } / windows.size
        val rootShareDelta get() = windows.zip(oldWindows).sumOf { (a, b) -> a.fundamentalShare - b.fundamentalShare } / windows.size
    }

    fun hz(midi: Int) = Keys.midiHz(midi).toDouble()
    fun render(profile: String, midi: Int, velocity: Float, old: Boolean = false) = Skitter.renderProbe(
        SkitterVoice.BASS, if (profile == "common") common else Skitter.defaults(SkitterVoice.BASS),
        midi = midi, velocity = velocity, seed = 0, seconds = 1.3f,
        probe = SkitterProbe(bassFoundationDisabled = old))

    fun take(profile: String, midi: Int, velocity: Float): Take = cache.getOrPut(Triple(profile, midi, velocity)) {
        val full = render(profile, midi, velocity)
        val old = render(profile, midi, velocity, old = true)
        Take(profile, midi, velocity, full, old, starts.map { window(full.raw, it, hz(midi)) },
            starts.map { window(old.raw, it, hz(midi)) })
    }

    private fun window(audio: FloatArray, seconds: Double, root: Double): Window {
        val start = (seconds * Dsp.RATE).roundToInt()
        // At C1 this window has twelve cycles, enough to separate root/second/third;
        // the existing short character ruler measures a different, broader property.
        val mean = (start until start + size).sumOf { audio[it].toDouble() } / size
        val re = FloatArray(size) { i -> ((audio[start + i] - mean) * (.5 - .5 * cos(2 * PI * i / (size - 1)))).toFloat() }
        val im = FloatArray(size)
        Fft.forward(re, im)
        var total = 0.0; var first = 0.0; var second = 0.0; var upper = 0.0
        for (bin in 1..size / 2) {
            val frequency = bin.toDouble() * Dsp.RATE / size
            val power = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            if (frequency >= 20.0) total += power
            val ratio = frequency / root
            val partial = ratio.roundToInt()
            if (abs(ratio - partial) <= harmonicHalfWidth) when (partial) {
                1 -> first += power
                2 -> second += power
                in 3..8 -> upper += power
            }
        }
        require(total > 1e-20 && first > 1e-20) { "bass analysis is silent or has no root power" }
        return Window(first / total, 10 * log10(upper.coerceAtLeast(1e-30) / first),
            10 * log10(second.coerceAtLeast(1e-30) / first))
    }

    /** Unconstrained by the requested note: search 20–300Hz, including competing octaves. */
    fun lowPitch(audio: FloatArray): Pair<Double, Double>? {
        val stride = 4
        val rate = Dsp.RATE.toDouble() / stride
        val start = (.55 * Dsp.RATE).roundToInt()
        val values = DoubleArray((.4 * rate).roundToInt()) { audio[start + it * stride].toDouble() }
        val mean = values.average()
        for (i in values.indices) values[i] -= mean
        val low = (rate / 300).toInt(); val high = (rate / 20).toInt()
        val correlation = DoubleArray(high + 1)
        for (lag in low..high) {
            var xy = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 0 until values.size - lag) {
                val a = values[i]; val b = values[i + lag]
                xy += a * b; aa += a * a; bb += b * b
            }
            correlation[lag] = xy / sqrt(aa * bb).coerceAtLeast(1e-30)
        }
        val best = correlation.slice(low..high).max()
        val lag = (low + 1 until high).firstOrNull {
            correlation[it] >= .85 * best && correlation[it] >= correlation[it - 1] && correlation[it] >= correlation[it + 1]
        } ?: return null
        val curvature = correlation[lag - 1] - 2 * correlation[lag] + correlation[lag + 1]
        val offset = if (abs(curvature) < 1e-15) 0.0 else
            (.5 * (correlation[lag - 1] - correlation[lag + 1]) / curvature).coerceIn(-.5, .5)
        return rate / (lag + offset) to correlation[lag]
    }

    fun print(take: Take) {
        val pitch = lowPitch(take.full.raw)
        val cents = pitch?.let { 1200 * ln(it.first / hz(take.midi)) / ln(2.0) }
        println("BASS_FOUNDATION,${take.profile},midi=${take.midi},velocity=${take.velocity},upperDeltaDb=${take.upperDeltaDb},rootShareDelta=${take.rootShareDelta},rootShare=${take.windows.map { it.fundamentalShare }.average()},oldRootShare=${take.oldWindows.map { it.fundamentalShare }.average()},secondOverRootDb=${take.windows.map { it.secondOverRootDb }.average()},rawRms=${take.full.diagnostics.rawRms},rawPeak=${take.full.diagnostics.rawPeak},pitchCents=$cents,pitchConfidence=${pitch?.second},boundHits=${take.full.diagnostics.boundHits}")
    }

    @JvmStatic fun main(args: Array<String>) {
        val profiles = if ("--common-only" in args) listOf("common") else listOf("defaults", "common")
        for (profile in profiles) for (midi in listOf(24, 36, 48)) for (velocity in listOf(.25f, .6f, 1f))
            print(take(profile, midi, velocity))
    }
}

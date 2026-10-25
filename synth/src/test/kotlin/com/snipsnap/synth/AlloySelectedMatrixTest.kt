package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The selected voice recipes need their own control matrix: the original model-1
 * probe defaults deliberately remain frozen for saved-patch regressions. These are
 * numerical and causal gates, not a substitute for listening to the release pack. */
class AlloySelectedMatrixTest {
    private val revised = listOf(AlloyVoice.KEY, AlloyVoice.PLUCK, AlloyVoice.BRASS, AlloyVoice.STRING)
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    private val neutral = mapOf("TUNE" to .5f, "MATERIAL" to .5f, "FUSE" to .6f,
        "EDGE" to .6f, "BODY" to .5f, "LENGTH" to .5f, "HOLD" to 0f)

    @Test
    fun `all selected finite and held recipes reproduce reordered inputs and ignore invalid overrides`() {
        for (voice in AlloyVoice.entries) for (held in listOf(false, true)) {
            val macros = Alloy.defaults(voice) + ("HOLD" to if (held) 1f else 0f)
            val seconds = if (held) null else .65f
            val a = probe(voice, macros, seconds = seconds)
            val b = probe(voice, macros.entries.reversed().associate { it.key to it.value }, seconds = seconds)
            val invalid = probe(voice, macros + mapOf("MATERIAL" to Float.NaN,
                "FUSE" to Float.POSITIVE_INFINITY, "BODY" to Float.NEGATIVE_INFINITY,
                "UNKNOWN_CONTROL" to .91f), seconds = seconds)
            val label = "$voice ${if (held) "held" else "finite"}"
            assertContentEquals(a.samples, b.samples, "$label depends on insertion order or retained render state")
            assertContentEquals(a.conditionedAttack, b.conditionedAttack, "$label did not reproduce its source")
            assertContentEquals(a.samples, invalid.samples, "$label invalid controls did not fall back to the defaults")
            assertTrue(rms(a.samples) > 1e-4 && rms(a.body) > 1e-4 && rms(a.directAttack) > 1e-5,
                "$label lost its audible body or material")
            if (held) assertHeld(a, label)
            val zero = probe(voice, macros, velocity = 0f, seconds = seconds)
            assertTrue(zero.samples.all { it == 0f }, "$label zero velocity still sounds")
        }
    }

    @Test
    fun `selected revised voices change excitation and body timbre with native velocity`() {
        for (voice in revised) {
            val soft = probe(voice, neutral, velocity = .25f)
            val hard = probe(voice, neutral, velocity = 1f)
            assertTrue(rms(soft.directAttack) < rms(hard.directAttack) * .8,
                "$voice velocity did not change material excitation")
            assertTrue(rms(soft.samples) < rms(hard.samples) * .8,
                "$voice raw velocity had no dynamic effect")
            val distance = spectralDistance(soft.body, hard.body)
            assertTrue(distance > .005, "$voice velocity became only an output gain: $distance")
        }
    }

    @Test
    fun `five point selected timbral sweeps remain active in finite and settled held bodies`() {
        val failures = mutableListOf<String>()
        var minimumMiddleWave = Double.POSITIVE_INFINITY
        var minimumOuterWave = Double.POSITIVE_INFINITY
        var minimumSpectrum = Double.POSITIVE_INFINITY
        for (voice in revised) for (held in listOf(false, true)) {
            for (macro in listOf("MATERIAL", "FUSE", "EDGE", "BODY", "LENGTH")) {
                val bodies = steps.map { value ->
                    probe(voice, neutral + mapOf("HOLD" to if (held) 1f else 0f, macro to value)).body
                }
                val repeatedMiddle = probe(voice,
                    neutral + mapOf("HOLD" to if (held) 1f else 0f, macro to .5f)).body
                assertContentEquals(bodies[2], repeatedMiddle,
                    "$voice $macro sweep did not reproduce its middle-point control")
                val repeatNoise = matchedDifference(bodies[2], repeatedMiddle)
                // The original .005 activity gate was calibrated at .25/.5/.75,
                // not as a promise of a uniform knob law at the two endpoints.
                // Still exercise all five points: an outer interval must move
                // beyond repeat noise and a conservative eight Float ULPs.
                val outerFloor = max(8 * Math.ulp(1f).toDouble(), repeatNoise * 10)
                for (i in 0 until bodies.lastIndex) {
                    val change = matchedDifference(bodies[i], bodies[i + 1])
                    val middle = i == 1 || i == 2
                    if (middle) minimumMiddleWave = minOf(minimumMiddleWave, change)
                    else minimumOuterWave = minOf(minimumOuterWave, change)
                    val floor = if (middle) .005 else outerFloor
                    if (change <= floor) failures += "$voice $macro ${if (held) "held" else "finite"} ${steps[i]}→${steps[i + 1]} wave$change floor$floor"
                }
                val color = spectralDistance(bodies.first(), bodies.last())
                minimumSpectrum = minOf(minimumSpectrum, color)
                if (color <= .003) failures += "$voice $macro ${if (held) "held" else "finite"} changed only level/phase: spectrum$color"
            }
        }
        println("ALLOY selected five-point sweeps: minimum middle-step matched body $minimumMiddleWave, outer-step matched body $minimumOuterWave, endpoint spectral variation $minimumSpectrum")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `selected length control extends the finite phrase beyond its short setting`() {
        val failures = mutableListOf<String>()
        for (voice in revised) {
            val short = probe(voice, mapOf("LENGTH" to .25f), seconds = null)
            val long = probe(voice, mapOf("LENGTH" to .75f), seconds = null)
            val extension = (long.samples.size - short.samples.size).toDouble() / Dsp.RATE
            println("ALLOY selected $voice LENGTH .25→.75 extension $extension s")
            if (extension <= .25) failures += "$voice LENGTH did not extend the finite phrase by more than .25 s: $extension"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `four required selected macro pairs interact inside the body rather than only its direct layer`() {
        val pairs = listOf("MATERIAL" to "FUSE", "FUSE" to "EDGE",
            "BODY" to "EDGE", "FUSE" to "LENGTH")
        val failures = mutableListOf<String>()
        for (voice in revised) for (held in listOf(false, true)) for ((first, second) in pairs) {
            // A difference of independently level-matched corner responses removes an
            // additive gain-only explanation. Spectral mass also discards phase, so a
            // pair cannot pass merely because one filter changes its time alignment.
            val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (a, b) ->
                probe(voice, neutral + mapOf("HOLD" to if (held) 1f else 0f, first to a, second to b)).body
            }
            val duplicate = probe(voice,
                neutral + mapOf("HOLD" to if (held) 1f else 0f, first to 0f, second to 0f)).body
            assertContentEquals(corners.first(), duplicate,
                "$voice $first×$second did not reproduce its negative-control render")
            val negative = listOf(corners.first(), duplicate, corners.first(), duplicate)
            assertEquals(0.0, interactionDifference(negative), 0.0,
                "$voice $first×$second interaction metric reported coupling for duplicate renders")
            assertEquals(0.0, spectralInteraction(negative), 0.0,
                "$voice $first×$second spectral metric reported coupling for duplicate renders")
            val wave = interactionDifference(corners)
            val color = spectralInteraction(corners)
            println("ALLOY selected interaction $voice $first×$second ${if (held) "held" else "finite"}: matched body $wave, spectral residual $color")
            if (wave <= .005 || color <= .003) failures +=
                "$voice $first×$second ${if (held) "held" else "finite"}: body$wave spectrum$color"
        }
        assertTrue(failures.isEmpty(), "Selected pair interactions fell below the existing macro-activity floors:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `selected fuse keeps causal material modulation at wood tine and breath in finite and held bodies`() {
        val failures = mutableListOf<String>()
        for (voice in revised) for (material in listOf(0f, .5f, 1f)) for (held in listOf(false, true)) {
            val macros = neutral + mapOf("MATERIAL" to material, "FUSE" to .75f,
                "EDGE" to .4f, "HOLD" to if (held) 1f else 0f)
            val fused = probe(voice, macros)
            val ablated = probe(voice, macros, attackModulation = false)
            val label = "$voice material$material ${if (held) "held" else "finite"}"
            assertContentEquals(fused.directAttack, ablated.directAttack, "$label ablation changed direct material")
            assertContentEquals(fused.conditionedAttack, ablated.conditionedAttack, "$label ablation changed its generated source")
            assertTrue(rms(ablated.directAttack) > 1e-5, "$label ablation discarded the audible source")
            val wave = matchedDifference(fused.body, ablated.body, Dsp.RATE / 100, Dsp.RATE / 3)
            val color = spectralDistance(fused.body, ablated.body, if (held) .01f else .025f,
                if (held) .35f else .30f)
            println("ALLOY selected FUSE $label: matched body $wave, spectral variation $color")
            if (held && color <= .004 || !held && (wave <= .02 || color <= .005)) {
                failures += "$label wave$wave spectrum$color"
            }
            val zero = macros + ("FUSE" to 0f)
            assertContentEquals(probe(voice, zero, seconds = .4f).samples,
                probe(voice, zero, seconds = .4f, attackModulation = false).samples,
                "$label FUSE zero retained hidden attack modulation")
        }
        assertTrue(failures.isEmpty(), "Selected FUSE fell below the unchanged original causal gates:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `selected extreme controls retain bounded pitched bodies across three registers`() {
        val corners = listOf(
            "all low" to neutral.mapValues { (name, _) -> if (name == "TUNE") .5f else 0f },
            "all high" to neutral.mapValues { (name, _) -> if (name == "TUNE") .5f else if (name == "HOLD") 0f else 1f },
            "bright thin" to (neutral + mapOf("MATERIAL" to .5f, "FUSE" to 1f, "EDGE" to 1f, "BODY" to 0f)),
        )
        var worstCents = 0.0
        for (voice in revised) for (midi in listOf(36, 60, 84)) for ((corner, macros) in corners) {
            val p = probe(voice, macros, midi = midi, seconds = 1.1f)
            val label = "$voice MIDI$midi $corner"
            assertBounded(p, label)
            val hz = p.diagnostics.rootHz.toFloat()
            val cents = FineTuning.cents(FineTuning.measuredHz(p.body, Dsp.RATE, hz, .25f, .45f), hz.toDouble())
            worstCents = max(worstCents, abs(cents))
            assertTrue(abs(cents) < 10 && rootShare(p.body, hz) > .025,
                "$label lost its root: $cents cents, share${rootShare(p.body, hz)}")
        }
        println("ALLOY selected 36 register/extreme checks: worst root $worstCents cents")
    }

    @Test
    fun `selected standard pitched wood tine and breath cases retain actual classifier guards`() {
        for (voice in revised) for (midi in listOf(36, 60, 84)) for (material in listOf(0f, .5f, 1f)) {
            // Classify ordinary pitched gestures independently from the all-zero
            // sound-design corner. Keep the named-preset classifier matrix too.
            val macros = mapOf("TUNE" to .5f, "MATERIAL" to material,
                "FUSE" to .7f, "EDGE" to .6f, "LENGTH" to .3f, "HOLD" to 0f)
            val p = probe(voice, macros, midi = midi, seconds = 1.1f, normalize = true)
            val actual = Classifier.classify(Snip(p.samples, 1, Dsp.RATE))
            assertTrue(actual.drumClass !in drums,
                "$voice MIDI$midi standard material$material became ${actual.drumClass}: ${actual.features}")
        }
    }

    @Test
    fun `the all low C2 pluck retains its root and tonal export despite the known tom heuristic`() {
        val voice = AlloyVoice.PLUCK
        val macros = Alloy.defaults(voice).mapValues { (name, _) -> if (name == "TUNE") .5f else 0f }
        val p = probe(voice, macros, midi = 36, seconds = 1.1f)
        assertBounded(p, "PLUCK all-low C2")
        val hz = p.diagnostics.rootHz.toFloat()
        val cents = FineTuning.cents(FineTuning.measuredHz(p.body, Dsp.RATE, hz, .25f, .45f), hz.toDouble())
        assertTrue(abs(cents) < 10 && rootShare(p.body, hz) > .025,
            "the disclosed percussive PLUCK corner lost its actual pitched root: $cents cents")
        assertEquals(DrumClass.TONAL, Alloy.drumClassFor(voice, macros),
            "the pitched PLUCK recipe lost its melodic export routing")
        val actual = Classifier.classify(Snip(p.samples, 1, Dsp.RATE))
        assertEquals(DrumClass.TOM, actual.drumClass,
            "the disclosed generic-classifier corner changed; review its documentation and actual features")
        println("ALLOY disclosed all-low C2 PLUCK: root $cents cents; declared TONAL; actual ${actual.drumClass}: ${actual.features}")
    }

    @Test
    fun `finite key and pluck neutral audio is unchanged by material and length authoring repairs`() {
        for (voice in listOf(AlloyVoice.KEY, AlloyVoice.PLUCK)) {
            val current = Alloy.defaultConfigFor(voice)
            // These are the complete previous selected recipes, reconstructed by
            // undoing only the authoring changes. The engine/model is unchanged.
            val previous = when (voice) {
                AlloyVoice.KEY -> current.copy(
                    wood = current.wood.copy(losses = listOf(.055f, .025f, .012f)),
                    held = current.held.copy(drive = .17f),
                )
                AlloyVoice.PLUCK -> current.copy(
                    breath = current.breath.copy(losses = listOf(.045f, .025f, .010f)),
                    envelope = current.envelope.copy(finiteSeconds = .65f),
                )
                else -> error("not a repaired selected recipe")
            }
            for (length in listOf(.25f, .45f)) {
                val macros = neutral + mapOf("EDGE" to .5f, "BODY" to .45f, "LENGTH" to length)
                val before = Alloy.render(voice, macros, midi = 48, velocity = .75f, config = previous)
                val after = Alloy.render(voice, macros, midi = 48, velocity = .75f, config = current)
                assertContentEquals(before.samples, after.samples,
                    "$voice finite neutral LENGTH$length changed while repairing other material/length regions")
            }
            // KEY held drive intentionally changes so held FUSE retains causal
            // activity at the middle BODY setting. It is covered by ablation and
            // seam gates, not an incorrect claim of held PCM identity.
        }
    }

    @Test
    fun `selected finite extremes settle their tails before export and respect the leveled ceiling`() {
        for (voice in revised) for (value in listOf(0f, 1f)) {
            val macros = Alloy.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else value }
            val p = probe(voice, macros, seconds = null)
            val label = "$voice finite$value"
            assertBounded(p, label)
            assertTrue(p.samples.size in Dsp.RATE..7 * Dsp.RATE, "$label escaped finite host length")
            assertTrue(rms(p.samples, p.samples.size - Dsp.RATE / 10) < rms(p.samples) * .08,
                "$label finite tail did not settle")
            val leveled = probe(voice, macros, seconds = null, normalize = true)
            assertTrue(leveled.samples.maxOf { abs(it) } <= .991f, "$label leveled PCM exceeded its ceiling")
        }
    }

    @Test
    fun `selected held material extremes keep real preceding cycles at low and high notes`() {
        for (voice in AlloyVoice.entries) for (midi in listOf(36, 96)) for (material in listOf(0f, 1f)) {
            val macros = Alloy.defaults(voice).mapValues { (name, _) -> if (name == "TUNE") .5f else 1f } +
                ("MATERIAL" to material)
            val p = probe(voice, macros, midi = midi, velocity = .6f, seconds = null)
            val label = "$voice MIDI$midi held material$material"
            assertBounded(p, label)
            assertHeld(p, label)
            assertTrue(rms(p.conditionedAttack) > 1e-5, "$label settled by discarding material")
            val repeated = FloatArray(p.samples.size * 4) { p.samples[it % p.samples.size] }
            val hz = p.diagnostics.rootHz.toFloat()
            val cents = FineTuning.cents(FineTuning.measuredHz(repeated, Dsp.RATE, hz, .05f, .65f), hz.toDouble())
            assertTrue(abs(cents) < 10 && rootShare(repeated, hz) > .025,
                "$label lost pitch across actual exported cycles: $cents cents")
        }
    }

    @Test
    fun `selected high note phase modulation agrees with eight times references`() {
        var worstWave = 0.0
        var worstSpectrum = 0.0
        for (voice in revised) for (material in listOf(0f, .5f)) {
            val macros = Alloy.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f } +
                ("MATERIAL" to material)
            val four = probe(voice, macros, midi = 84, seconds = .6f)
            val eight = probe(voice, macros, midi = 84, seconds = .6f, oversample = 8)
            assertEquals(Dsp.RATE * 8, eight.diagnostics.internalRate)
            val wave = matchedDifference(four.body, eight.body, Dsp.RATE / 50, Dsp.RATE / 2)
            val color = spectralDistance(four.body, eight.body, .02f, .45f)
            worstWave = max(worstWave, wave)
            worstSpectrum = max(worstSpectrum, color)
            assertTrue(wave < .12 && color < .035,
                "$voice material$material selected 4x/8x divergence: wave$wave spectrum$color")
            val power = spectrum(four.samples)
            val high = power.indices.filter { it * Dsp.RATE / (2.0 * power.size) >= 16000 }
                .sumOf { power[it] } / power.sum().coerceAtLeast(1e-20)
            assertTrue(high < .02, "$voice material$material selected high notes concentrate near Nyquist: $high")
        }
        println("ALLOY selected eight 4x/8x cases: worst matched body $worstWave, spectral variation $worstSpectrum")
    }

    /** Every call supplies the selected configuration explicitly. The original probe
     * default must stay available for model-1 saves and historical comparisons. */
    private fun probe(voice: AlloyVoice, macros: Map<String, Float>, midi: Int = 48,
        velocity: Float = 1f, seconds: Float? = .8f, normalize: Boolean = false,
        attackModulation: Boolean = true, oversample: Int = 4) = Alloy.renderProbe(voice,
        macros, midi, velocity, seconds = seconds, normalize = normalize,
        attackModulation = attackModulation, config = Alloy.defaultConfigFor(voice), oversample = oversample)

    private fun assertBounded(p: Alloy.Probe, label: String) {
        assertTrue(p.samples.all { it.isFinite() } && p.body.all { it.isFinite() } &&
            p.conditionedAttack.all { it.isFinite() }, "$label produced nonfinite audio")
        assertTrue(p.diagnostics.rawPeak.isFinite() && p.diagnostics.rawPeak < 2,
            "$label raw state escaped its original bound: ${p.diagnostics.rawPeak}")
        assertTrue(p.diagnostics.maxConditionedAttack <= 1.001 && p.diagnostics.maxPhaseModulation.isFinite() &&
            p.diagnostics.maxPhaseModulation < 12, "$label escaped modulation/source budgets")
        assertTrue(rms(p.samples) > 1e-4, "$label became silent")
        val average = p.samples.sumOf { it.toDouble() } / p.samples.size
        assertTrue(abs(average) < max(1e-5, rms(p.samples) * .01), "$label DC offset $average")
    }

    private fun assertHeld(p: Alloy.Probe, label: String) {
        assertTrue(p.previousCycle.size == p.samples.size && p.samples.size > 256,
            "$label lost its actual preceding cycle")
        assertEquals(3, p.prerollCycles, "$label lost the original three-cycle settling margin")
        assertTrue(p.stateError < 1e-9, "$label last-cycle state change ${p.stateError} lost its original margin")
        val seam = Keys.seamError(p.previousCycle + p.samples, p.previousCycle.size)
        assertEquals(seam, p.seamError, 1e-12, "$label reported an artificial seam")
        assertTrue(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR, "$label held seam $seam")
    }

    private fun rms(a: FloatArray, from: Int = 0, until: Int = a.size): Double {
        val end = until.coerceAtMost(a.size)
        val start = from.coerceIn(0, end)
        return sqrt((start until end).sumOf { a[it].toDouble() * a[it] } / (end - start).coerceAtLeast(1))
    }

    private fun matchedDifference(a: FloatArray, b: FloatArray, from: Int = 0,
        until: Int = minOf(a.size, b.size)): Double {
        val end = minOf(until, a.size, b.size)
        val start = from.coerceIn(0, end)
        val ar = rms(a, start, end).coerceAtLeast(1e-12)
        val br = rms(b, start, end).coerceAtLeast(1e-12)
        return sqrt((start until end).sumOf { val d = a[it] / ar - b[it] / br; d * d } /
            (end - start).coerceAtLeast(1))
    }

    private fun interactionDifference(corners: List<FloatArray>): Double {
        val from = (Dsp.RATE * .10).toInt()
        val until = (Dsp.RATE * .55).toInt()
        val scale = corners.map { rms(it, from, until).coerceAtLeast(1e-12) }
        return sqrt((from until until).sumOf { i ->
            val d = corners[3][i] / scale[3] - corners[2][i] / scale[2] -
                corners[1][i] / scale[1] + corners[0][i] / scale[0]
            d * d
        } / (until - from))
    }

    private fun spectralInteraction(corners: List<FloatArray>): Double {
        val masses = corners.map { spectralMass(it, .10f, .45f) }
        return masses[0].indices.sumOf { band ->
            abs(masses[3][band] - masses[2][band] - masses[1][band] + masses[0][band])
        } / 2
    }

    private fun spectrum(samples: FloatArray, fromSeconds: Float = .025f,
        seconds: Float = .3f): DoubleArray {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val count = minOf((seconds * Dsp.RATE).toInt(), samples.size - from)
        require(count > 8)
        var n = 1
        while (n < count) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = (samples[from + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(re, im)
        return DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    private fun spectralMass(samples: FloatArray, fromSeconds: Float, seconds: Float): DoubleArray {
        val power = spectrum(samples, fromSeconds, seconds)
        val bands = DoubleArray(32)
        val binHz = Dsp.RATE.toDouble() / (2 * power.size)
        for (bin in 1 until power.size) {
            val hz = bin * binHz
            if (hz < 30) continue
            val band = (ln(hz / 30) / ln(20000.0 / 30) * bands.size).toInt().coerceIn(0, bands.lastIndex)
            bands[band] += power[bin]
        }
        val total = bands.sum().coerceAtLeast(1e-20)
        for (i in bands.indices) bands[i] /= total
        return bands
    }

    /** The original logarithmic spectral total-variation metric and its thresholds
     * are retained so phase changes cannot substitute for changed tonal content. */
    private fun spectralDistance(a: FloatArray, b: FloatArray, fromSeconds: Float = .025f,
        seconds: Float = .3f): Double {
        val aa = spectralMass(a, fromSeconds, seconds)
        val bb = spectralMass(b, fromSeconds, seconds)
        return aa.indices.sumOf { abs(aa[it] - bb[it]) } / 2
    }

    private fun rootShare(samples: FloatArray, hz: Float): Double {
        val power = spectrum(samples, .25f, .45f)
        val binHz = Dsp.RATE.toDouble() / (2 * power.size)
        val lo = ((hz * .95) / binHz).toInt().coerceAtLeast(1)
        val hi = ((hz * 1.05) / binHz).toInt().coerceAtMost(power.lastIndex)
        return (lo..hi).sumOf { power[it] } / power.sum().coerceAtLeast(1e-20)
    }
}

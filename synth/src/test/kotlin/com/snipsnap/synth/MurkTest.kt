package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.json.JsonException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Behavioral and numerical evidence; the generated audition supplies the listening verdict. */
class MurkTest {
    private val drums = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
    )

    @Test
    fun `the same grove renders sample identical audio and event order`() {
        val macros = Murk.defaults(MurkVoice.GROVE) + mapOf("GROVE" to 0.75f, "AGITATION" to 0.75f)
        val options = Murk.ProbeOptions(durationSeconds = 3.5f, seedContext = 41)
        val a = Murk.probe(MurkVoice.GROVE, macros, 0.8f, options)
        val b = Murk.probe(MurkVoice.GROVE, macros, 0.8f, options)
        assertContentEquals(a.samples, b.samples)
        assertContentEquals(a.directTrunks, b.directTrunks)
        assertContentEquals(a.owls, b.owls)
        assertEquals(a.arrivals, b.arrivals)
        assertEquals(a.calls, b.calls)
        assertEquals(a.gestures, b.gestures)
        assertTrue(a.arrivals.isNotEmpty(), "determinism case did not exercise propagation")
        assertTrue(a.calls.isNotEmpty(), "determinism case did not exercise behavior")
        val otherSeed = Murk.probe(MurkVoice.GROVE, macros, 0.8f, options.copy(seedContext = 42))
        assertFalse(a.samples.contentEquals(otherSeed.samples), "the seed context did not reach synthesis")
    }

    @Test
    fun `a grove at rest creates neither sound nor behavioral events`() {
        val rest = Murk.probe(
            MurkVoice.ALARM,
            Murk.defaults(MurkVoice.ALARM) + mapOf("FOG" to 1f, "AGITATION" to 1f, "GROVE" to 1f),
            options = Murk.ProbeOptions(primaryStrikeEnabled = false, durationSeconds = 2f),
        )
        assertEquals(0.0, rms(rest.samples))
        assertEquals(0.0, rms(rest.owlFogInput))
        assertTrue(rest.calls.isEmpty())
        assertTrue(rest.arrivals.isEmpty())
        assertTrue(rest.gestures.isEmpty())
        assertEquals(0.0, rest.finalPassiveEnergy)
    }

    @Test
    fun `fog changes the original wood before any delayed answer exists`() {
        val options = Murk.ProbeOptions(linksEnabled = false, owlsEnabled = false, durationSeconds = 0.5f)
        val thin = Murk.probe(MurkVoice.CLUNK, mapOf("FOG" to 0f), options = options)
        val thick = Murk.probe(MurkVoice.CLUNK, mapOf("FOG" to 1f), options = options)
        val end = (0.05f * Dsp.RATE).roundToInt()
        val difference = relativeDifference(thin.directTrunks, thick.directTrunks, end)
        println("MURK source loading, first 50 ms: difference $difference")
        assertTrue(difference > 0.05, "FOG changed the source by only $difference before travel")
        assertEquals(0.0, rms(thin.reexcitedTrunks))
        assertEquals(0.0, rms(thick.reexcitedTrunks))
        assertTrue(thin.arrivals.isEmpty() && thick.arrivals.isEmpty())
    }

    @Test
    fun `the compensated direct trunk holds the requested root across material and loading`() {
        var worst = 0.0
        for (tune in floatArrayOf(0f, 0.5f, 1f)) {
            val hz = Keys.midiHz(48 + (tune * 24).roundToInt())
            for ((trunk, fog) in listOf(0f to 0f, 0f to 1f, 0.5f to 0.5f, 1f to 0f, 1f to 1f)) {
                val probe = Murk.probe(
                    MurkVoice.CLUNK, mapOf("TUNE" to tune, "TRUNK" to trunk, "FOG" to fog),
                    options = Murk.ProbeOptions(linksEnabled = false, owlsEnabled = false, durationSeconds = 0.7f),
                )
                val cents = FineTuning.cents(
                    FineTuning.measuredHz(probe.directTrunks, Dsp.RATE, hz, 0.04f, 0.3f), hz.toDouble(),
                )
                worst = max(worst, abs(cents))
                assertTrue(abs(cents) <= 10.0, "TUNE $tune TRUNK $trunk FOG $fog: direct trunk $cents cents")
            }
        }
        println("MURK compensated direct trunk: worst $worst cents")
    }

    @Test
    fun `fronts travel in order and removing links removes reexcitation and remote replies`() {
        val macros = mapOf("FOG" to 0.65f, "GROVE" to 0.8f, "AGITATION" to 0.8f)
        val on = Murk.probe(MurkVoice.GROVE, macros, options = Murk.ProbeOptions(durationSeconds = 3f))
        val off = Murk.probe(
            MurkVoice.GROVE, macros,
            options = Murk.ProbeOptions(linksEnabled = false, durationSeconds = 3f),
        )
        assertTrue(on.arrivals.isNotEmpty())
        assertTrue(on.arrivals.zipWithNext().all { (a, b) -> a.timeSeconds <= b.timeSeconds })
        val first = (1..3).map { tree ->
            on.arrivals.firstOrNull { it.destination == tree && !it.fromCall }?.timeSeconds
                ?: error("tree $tree never received the strike front")
        }
        assertTrue(first[0] > 0.04f, "front arrived before finite travel: ${first[0]}")
        assertTrue(first.zipWithNext().all { (a, b) -> b > a }, "farther trees did not answer later: $first")
        assertTrue(on.arrivals.all { it.strength.isFinite() && it.strength > 0f })
        assertTrue(rms(on.reexcitedTrunks) > 1e-6, "travel did not excite wood")
        assertTrue(on.calls.any { it.tree != 0 }, "travel did not disturb a remote owl")
        assertTrue(off.arrivals.isEmpty())
        assertEquals(0.0, rms(off.reexcitedTrunks))
        assertTrue(off.calls.all { it.tree == 0 }, "an isolated neighboring owl answered")
    }

    @Test
    fun `a passive extreme rings down without owl energy or repeated performers`() {
        val passive = Murk.probe(
            MurkVoice.FRONT, mapOf("FOG" to 1f, "GROVE" to 1f, "TRUNK" to 1f, "HOLD" to 0f),
            options = Murk.ProbeOptions(owlsEnabled = false, durationSeconds = 8f),
        )
        assertEquals(1, passive.gestures.size)
        assertTrue(passive.calls.isEmpty())
        assertEquals(0.0, rms(passive.owls))
        assertEquals(0.0, rms(passive.owlFogInput))
        assertTrue(passive.samples.all { it.isFinite() })
        assertTrue(passive.rawPeak < 1f, "passive raw peak ${passive.rawPeak} escaped its headroom")
        val directPeak = passive.directTrunks.maxOf { abs(it) }
        val returnRatio = passive.reexcitedTrunks.maxOf { abs(it) } / directPeak
        val atmosphereRatio = passive.atmosphere.maxOf { abs(it) } / directPeak
        println("MURK passive radiation: return/direct $returnRatio, atmosphere/direct $atmosphereRatio")
        assertTrue(returnRatio > 0.01f, "responding wood was inaudible against the direct strike: $returnRatio")
        assertTrue(atmosphereRatio > 0.01f, "traveling atmosphere was inaudible against the direct strike: $atmosphereRatio")
        val window = (0.05f * Dsp.RATE).roundToInt()
        val loudest = (0 until passive.samples.size / window).maxOf {
            rms(passive.samples, it * window, (it + 1) * window)
        }
        val end = rms(passive.samples, passive.samples.size - window, passive.samples.size)
        val decayDb = 20 * log10(loudest / end.coerceAtLeast(1e-30))
        println("MURK passive extreme: end $decayDb dB under loudest, raw peak ${passive.rawPeak}")
        assertTrue(decayDb >= 50.0, "passive network still sounded after 8 s: $decayDb dB decay")
        val peakState = passive.stateSnapshots.maxOf { it.passiveEnergy }
        assertTrue(passive.finalPassiveEnergy < peakState * 1e-5, "passive state did not relax")
    }

    @Test
    fun `owl responses follow stimuli and exhaust a finite count and energy budget`() {
        val active = Murk.probe(
            MurkVoice.ALARM, mapOf("STRIKE" to 1f, "FOG" to 1f, "AGITATION" to 1f, "GROVE" to 1f),
            options = Murk.ProbeOptions(durationSeconds = 8f),
        )
        assertTrue(active.calls.isNotEmpty())
        assertTrue(active.calls.size <= Murk.MAX_CALLS, "${active.calls.size} calls escaped the count budget")
        for (call in active.calls) {
            assertTrue(call.timeSeconds > call.stimulusTimeSeconds, "an owl answered before its stimulus: $call")
            assertTrue(call.generation in 0..Murk.MAX_CALL_GENERATION)
            assertTrue(call.remainingBudget >= 0)
            assertTrue(call.strength.isFinite() && call.strength > 0f)
            if (call.tree != 0) {
                assertTrue(active.arrivals.any {
                    it.destination == call.tree && abs(it.timeSeconds - call.stimulusTimeSeconds) <= 1e-4f &&
                        (call.generation == 0 || it.fromCall)
                }, "tree ${call.tree} answered without an arriving stimulus: $call")
            }
        }
        for (tree in 0..3) {
            val calls = active.calls.filter { it.tree == tree }
            for ((a, b) in calls.zipWithNext()) {
                assertTrue(
                    b.timeSeconds - a.timeSeconds >= Murk.MIN_REFRACTORY_SECONDS - 1e-3f,
                    "owl $tree retriggered inside its refractory period: $a then $b",
                )
            }
        }
        assertTrue(active.calls.zipWithNext().all { (a, b) -> b.remainingBudget < a.remainingBudget })
        val budget = active.stateSnapshots.map { it.remainingVocalEnergy }
        assertTrue(budget.all { it.isFinite() && it >= 0f })
        assertTrue(budget.zipWithNext().all { (a, b) -> b <= a + 1e-6f }, "one-shot vocal energy replenished")
        assertTrue(active.samples.all { it.isFinite() })
        assertTrue(active.rawPeak < 1f, "active raw peak ${active.rawPeak} escaped its headroom")
        assertTrue(active.stateSnapshots.all { it.passiveEnergy.isFinite() })
        println("MURK alarm: ${active.calls.size} calls; final vocal budget ${budget.last()}")
    }

    @Test
    fun `muting calls preserves their fog excitation while disabling injection removes it`() {
        val macros = mapOf("AGITATION" to 1f, "GROVE" to 0.75f, "FOG" to 0.7f)
        val options = Murk.ProbeOptions(durationSeconds = 3.5f)
        val full = Murk.probe(MurkVoice.GROVE, macros, options = options)
        val muted = Murk.probe(MurkVoice.GROVE, macros, options = options.copy(owlAudioEnabled = false))
        val noInjection = Murk.probe(MurkVoice.GROVE, macros, options = options.copy(owlToFogEnabled = false))
        assertTrue(full.calls.isNotEmpty())
        assertEquals(full.calls, muted.calls, "an audio mute changed behavior")
        assertEquals(full.arrivals, muted.arrivals, "an audio mute changed propagation")
        assertContentEquals(full.owlFogInput, muted.owlFogInput)
        assertFalse(full.samples.contentEquals(muted.samples), "owl audio mute was inaudible")
        assertTrue(rms(full.owlFogInput) > 1e-6)
        assertTrue(full.arrivals.any { it.fromCall }, "calls did not enter the traveling atmosphere")
        assertEquals(0.0, rms(noInjection.owlFogInput))
        assertTrue(noInjection.arrivals.none { it.fromCall })
        assertEquals(full.calls.first(), noInjection.calls.first(), "injection changed the initiating call")
        assertTrue(rms(noInjection.owls) > 1e-6, "disabling injection also removed the direct calls")
        assertFalse(full.atmosphere.contentEquals(noInjection.atmosphere))
        assertTrue(full.calls.any { it.generation > 0 }, "call propagation never provoked a secondary answer")
        assertFalse(full.calls == noInjection.calls, "call propagation did not change secondary behavior")
    }

    @Test
    fun `event energy changes the front strength and each timbral control moves every voice`() {
        val options = Murk.ProbeOptions(durationSeconds = 1.5f)
        val quiet = Murk.probe(MurkVoice.FRONT, energy = 0.25f, options = options)
        val strong = Murk.probe(MurkVoice.FRONT, energy = 1f, options = options)
        assertTrue(strong.arrivals.first().strength > quiet.arrivals.first().strength)
        assertTrue(rms(strong.directTrunks) > rms(quiet.directTrunks))
        for (voice in MurkVoice.entries) {
            val base = Murk.defaults(voice)
            val a = Murk.probe(voice, base, options = options).samples
            for (name in listOf("STRIKE", "TRUNK", "FOG", "AGITATION", "GROVE")) {
                val end = if (base.getValue(name) < 0.5f) 1f else 0f
                val b = Murk.probe(voice, base + (name to end), options = options).samples
                val change = relativeDifference(a, b)
                assertTrue(change > 0.01, "$voice $name moved the sound by only $change")
            }
        }
    }

    @Test
    fun `host velocity reaches event energy without moving the contact control`() {
        val patch = MurkPatch("Quiet Axe", MurkVoice.THWACK, mapOf("STRIKE" to 0.8f, "FOG" to 0.6f))
        val viaHost = Velocity.atVelocity(patch, 0.35f)
        val direct = Murk.render(patch.voice, patch.macros, velocity = 0.35f)
        assertContentEquals(direct.samples, viaHost.samples)
        assertEquals(0.8f, patch.macros.getValue("STRIKE"))
    }

    @Test
    fun `thwack prepares upper wood before its distinct impact`() {
        // Listening requested "thhh wackk" after the original THWACK was accepted as the bat.
        // Measure the rendered profile, not the exciter's timing or coefficient table.
        val options = Murk.ProbeOptions(linksEnabled = false, owlsEnabled = false, durationSeconds = 0.45f)
        val bat = Murk.probe(MurkVoice.CLUNK, options = options).directTrunks
        val thwack = Murk.probe(MurkVoice.THWACK, options = options).directTrunks
        val attackEnd = (0.08f * Dsp.RATE).roundToInt()
        fun peakTime(samples: FloatArray) =
            (0 until attackEnd).maxBy { abs(samples[it]) }.toDouble() / Dsp.RATE
        val batPeak = peakTime(bat)
        val thwackPeak = peakTime(thwack)
        val preparationRatio = rms(thwack, (0.008f * Dsp.RATE).roundToInt(), (0.030f * Dsp.RATE).roundToInt()) /
            rms(thwack, (0.045f * Dsp.RATE).roundToInt(), (0.075f * Dsp.RATE).roundToInt())
        val root = Keys.midiHz(60)
        val upperShare = upperBandShare(thwack, 0.008f, 0.030f, 3f * root, 10f * root)
        println("MURK attack: bat peak ${batPeak * 1000} ms, THWACK peak ${thwackPeak * 1000} ms, preparation/impact $preparationRatio, upper share $upperShare")
        // Measured C4: 10.14 / 51.45 ms, 0.0657 preparation ratio, 0.4687 upper share.
        // A delayed silent strike or a single bat impulse cannot satisfy this complete profile.
        assertTrue(
            thwackPeak > batPeak + 0.025 && preparationRatio >= 0.05 && upperShare > 0.20,
            "THWACK lost its prepared impact: bat peak $batPeak, THWACK peak $thwackPeak, preparation/impact $preparationRatio, upper share $upperShare",
        )
    }

    @Test
    fun `the low agitation range changes quiet calls and all required macro pairs interact`() {
        val options = Murk.ProbeOptions(durationSeconds = 1.5f)
        val quiet = Murk.probe(MurkVoice.CLUNK, mapOf("AGITATION" to 0.1f), options = options)
        val alert = Murk.probe(MurkVoice.CLUNK, mapOf("AGITATION" to 0.25f), options = options)
        assertTrue(quiet.calls.isNotEmpty(), "the useful low agitation range was silent")
        assertTrue(relativeDifference(quiet.owls, alert.owls) > 0.05)
        val neutral = Murk.macrosFor(MurkVoice.GROVE).associate { it.name to it.neutral }
        val pairs = listOf("STRIKE" to "FOG", "TRUNK" to "FOG", "FOG" to "GROVE", "GROVE" to "AGITATION", "STRIKE" to "AGITATION")
        for ((a, b) in pairs) {
            val corners = listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (x, y) ->
                Murk.probe(MurkVoice.GROVE, neutral + mapOf(a to x, b to y), options = options).samples
            }
            var mixed = 0.0
            var signal = 0.0
            for (i in corners[0].indices) {
                val delta = corners[3][i] - corners[2][i] - corners[1][i] + corners[0][i].toDouble()
                mixed += delta * delta
                signal += corners.maxOf { it[i].toDouble() * it[i] }
            }
            val interaction = sqrt(mixed / signal.coerceAtLeast(1e-30))
            println("MURK $a × $b interaction: $interaction")
            assertTrue(interaction > 0.01, "$a × $b behaved as independent additive controls: $interaction")
        }
    }

    @Test
    fun `normal voices and selected extremes remain mono finite and outside drum guards`() {
        val normal = MurkVoice.entries.map { it to Murk.defaults(it) } +
            (MurkVoice.CLUNK to (Murk.defaults(MurkVoice.CLUNK) + ("TUNE" to 1f)))
        val cases = normal + listOf(
            MurkVoice.CLUNK to mapOf("TUNE" to 0f, "TRUNK" to 1f, "FOG" to 1f),
            MurkVoice.THWACK to mapOf("TUNE" to 1f, "STRIKE" to 1f, "FOG" to 0f),
            MurkVoice.ALARM to mapOf("STRIKE" to 1f, "TRUNK" to 1f, "FOG" to 1f, "AGITATION" to 1f, "GROVE" to 1f),
        )
        for ((voice, macros) in cases) {
            val snip = Murk.render(voice, macros)
            assertEquals(1, snip.channels)
            assertEquals(Dsp.RATE, snip.sampleRate)
            assertTrue(snip.samples.all { it.isFinite() }, "$voice $macros")
            assertTrue(snip.peak() in 0.01f..0.991f, "$voice peak ${snip.peak()}")
            assertTrue(abs(snip.samples.average()) < 0.005, "$voice DC ${snip.samples.average()}")
            assertTrue(snip.durationSeconds in 1f..12f, "$voice duration ${snip.durationSeconds}")
            val heard = Classifier.classify(snip).drumClass
            assertTrue(heard !in drums, "$voice $macros classified as $heard")
            val filed = Murk.drumClassFor(voice, macros)
            assertTrue(filed !in drums, "$voice filed as $filed")
            if (heard == DrumClass.LOOP || filed == DrumClass.LOOP) assertEquals(heard, filed)
            if ((voice to macros) in normal) {
                val pitch = Pitch.detect(snip) ?: error("$voice default has no detectable root")
                val midi = 48 + (macros.getValue("TUNE") * 24).roundToInt()
                val cents = FineTuning.cents(pitch.hz.toDouble(), Keys.midiHz(midi).toDouble())
                assertTrue(abs(cents) < 50.0, "$voice full mix root is $cents cents from MIDI $midi")
            }
        }
    }

    @Test
    fun `patch editing and recipes preserve all controls and regenerate kit pads`() {
        for (voice in MurkVoice.entries) {
            val patch = MurkPatch("Test ${voice.name}", voice, Murk.defaults(voice))
            assertEquals(
                setOf("TUNE", "STRIKE", "TRUNK", "FOG", "AGITATION", "GROVE", "HOLD"),
                Murk.macrosFor(voice).map { it.name }.toSet(),
            )
            assertTrue(Presets.forVoice("MURK", voice.name).isNotEmpty(), "$voice is absent from the preset dispatcher")
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val edited = Patches.edited(patch, "Edited", patch.macros + ("FOG" to 0.73f))
            assertEquals(patch.copy(name = "Edited", macros = patch.macros + ("FOG" to 0.73f)), edited)
            assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
        }
        val kit = SynthKits.murk()
        assertEquals(16, kit.size)
        for ((i, nullablePad) in kit.withIndex()) {
            val pad = requireNotNull(nullablePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            assertTrue(recipe.patch is MurkPatch, "pad $i lost its typed MURK recipe")
            assertEquals(null, recipe.fx, "pad $i needs dry audition evidence")
            val patch = recipe.patch as MurkPatch
            assertEquals(Murk.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            if (i == 0 || i == kit.lastIndex) assertContentEquals(pad.snip.samples, recipe.render().samples)
        }
        assertFailsWith<IllegalArgumentException> { MurkPatch("Bad", MurkVoice.CLUNK, mapOf("FOG" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { MurkPatch("Bad", MurkVoice.CLUNK, mapOf("UNKNOWN" to 0.5f)) }
        assertFailsWith<JsonException> {
            MurkPatch.fromJsonText("""{"engine":"MURK","version":99,"name":"Bad","voice":"CLUNK","macros":{}}""")
        }
    }

    @Test
    fun `held performers and animals settle across a genuine preceding cycle`() {
        val cases = listOf(
            MurkVoice.CLUNK to mapOf("TUNE" to 0f, "HOLD" to 1f),
            MurkVoice.HOOT to mapOf("AGITATION" to 0.8f, "GROVE" to 0.9f, "HOLD" to 1f),
            MurkVoice.ALARM to mapOf("STRIKE" to 1f, "TRUNK" to 1f, "FOG" to 1f, "AGITATION" to 1f, "GROVE" to 1f, "HOLD" to 1f),
        )
        for ((voice, macros) in cases) {
            val held = Murk.probe(voice, macros)
            val previous = held.previousCycle
            assertEquals(held.samples.size, previous.size, "$voice did not retain a preceding settled cycle")
            val continuous = previous + held.samples
            val seam = Keys.seamError(continuous, previous.size)
            println("MURK $voice genuine held seam: $seam")
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "$voice did not close: $seam")
            assertEquals(seam, held.seamError, 1e-10)
            assertTrue(held.gestures.isNotEmpty(), "$voice held without a powered performer")
            assertTrue(held.calls.isNotEmpty(), "$voice held case did not exercise the animals")
            assertTrue(held.samples.all { it.isFinite() })
            assertTrue(held.rawPeak < 1f, "$voice held raw peak ${held.rawPeak}")
            assertTrue(held.calls.size <= Murk.MAX_CALLS, "$voice held call plan escaped its count cap")
            assertTrue(held.stateSnapshots.all { it.passiveEnergy.isFinite() && it.remainingVocalEnergy in 0f..4.8f })
            // The natural predecessor is evidence independent of copying a loop twice.
            val wrap = abs(held.samples.first() - held.samples.last())
            val natural = abs(held.samples.first() - previous.last())
            val peak = held.samples.maxOf { abs(it) }
            assertTrue(abs(wrap - natural) <= 0.03f * peak, "$voice changed the boundary step: wrap $wrap natural $natural")
            for (tree in 0..3) {
                val calls = held.calls.filter { it.tree == tree }
                for ((a, b) in calls.zipWithNext()) {
                    assertTrue(b.timeSeconds - a.timeSeconds >= Murk.MIN_REFRACTORY_SECONDS - 1e-3f)
                }
                if (calls.isNotEmpty()) {
                    val seconds = held.samples.size.toFloat() / Dsp.RATE
                    val acrossWrap = seconds - calls.last().timeSeconds + calls.first().timeSeconds
                    assertTrue(acrossWrap >= Murk.MIN_REFRACTORY_SECONDS - 1e-3f, "$voice owl $tree violated refractory time across wrap: $acrossWrap")
                }
            }
        }
    }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var energy = 0.0
        for (i in from until to) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (to - from).coerceAtLeast(1))
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray, end: Int = minOf(a.size, b.size)): Double {
        var difference = 0.0
        var energy = 0.0
        for (i in 0 until end) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            difference += (x - y) * (x - y)
            energy += max(x * x, y * y)
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    private fun upperBandShare(samples: FloatArray, fromSeconds: Float, toSeconds: Float, lowHz: Float, highHz: Float): Double {
        val from = (fromSeconds * Dsp.RATE).roundToInt()
        val count = (toSeconds * Dsp.RATE).roundToInt() - from
        val n = 2048
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) {
            re[i] = (samples[from + i] * (0.5 - 0.5 * cos(2 * PI * i / (count - 1)))).toFloat()
        }
        Fft.forward(re, im)
        var upper = 0.0
        var total = 0.0
        for (k in 1 until n / 2) {
            val energy = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            val hz = k.toDouble() * Dsp.RATE / n
            total += energy
            if (hz >= lowHz && hz < highHz) upper += energy
        }
        return upper / total.coerceAtLeast(1e-30)
    }
}

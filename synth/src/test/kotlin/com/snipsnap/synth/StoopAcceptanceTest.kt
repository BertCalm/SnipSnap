package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent output gates: these accompany, rather than replace, the listening audition. */
class StoopAcceptanceTest {
    @Test
    fun `every factory recipe avoids drum classification at its authored root`() {
        val failures = mutableListOf<String>()
        for (preset in StoopPresets.all()) {
            val actual = Classifier.classify(preset.render())
            println("STOOP preset ${preset.name}: ${actual.drumClass}, decay ${actual.features.decayMs}, centroid ${actual.features.centroidHz}")
            if (actual.drumClass in drums) failures += "${preset.name} (${preset.voice}, MIDI ${preset.midi}) classified ${actual.drumClass}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `dry defaults retain root energy and avoid drum classification in their intended registers`() {
        val failures = mutableListOf<String>()
        for (voice in StoopVoice.entries) for (midi in registers.getValue(voice)) {
            val patch = patch(voice, midi)
            val snip = Stoop.render(patch)
            val classified = Classifier.classify(snip)
            val hz = Keys.midiHz(midi)
            val rootCents = FineTuning.cents(FineTuning.measuredHz(snip, hz, .06f, .35f), hz.toDouble())
            val rootShare = spectralShare(snip, .06f, .35f) { frequency -> abs(frequency - hz) <= max(5.0, hz * .04) }
            println("STOOP default $voice MIDI $midi: class ${classified.drumClass}, cents $rootCents, root share $rootShare, centroid ${classified.features.centroidHz}, decay ${classified.features.decayMs}")
            if (classified.drumClass in drums) failures += "$voice MIDI $midi classified ${classified.drumClass}"
            if (abs(rootCents) > 10.0) failures += "$voice MIDI $midi root drifted $rootCents cents"
            if (rootShare < .005) failures += "$voice MIDI $midi has only $rootShare requested-root energy"
            if (voice == StoopVoice.BASS) {
                val upperShare = spectralShare(snip, .03f, .25f) { frequency -> frequency > hz * 1.7 && frequency < 8_000 }
                if (upperShare < .005) failures += "BASS MIDI $midi has only $upperShare audible upper harmonic energy"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `raw velocity carries energy while hard articulation remains playable at low velocity`() {
        for (voice in StoopVoice.entries) {
            val p = patch(voice).copy(macros = Stoop.defaults(voice) + ("TOUCH" to 1f))
            val soft = Stoop.renderRaw(p.copy(velocity = .2f))
            val firm = Stoop.renderRaw(p.copy(velocity = 1f))
            val ratio = rms(firm.samples) / rms(soft.samples).coerceAtLeast(1e-30)
            assertTrue(rms(soft.samples) > 1e-7, "$voice soft high-TOUCH note is silent")
            assertTrue(ratio > 2.0, "$voice normalized synthesis concealed raw velocity: firm/soft RMS $ratio")
            val silent = Stoop.render(p.copy(velocity = 0f))
            assertTrue(silent.samples.all { it == 0f }, "$voice zero velocity produced audio")
        }
    }

    @Test
    fun `every timbral macro changes each family over five sweep points`() {
        val failures = mutableListOf<String>()
        for (voice in StoopVoice.entries) for (macro in timbralMacros) {
            val base = patch(voice)
            val sweep = listOf(0f, .25f, .5f, .75f, 1f).map { value ->
                Stoop.renderRaw(base.copy(macros = base.macros + (macro to value))).samples
            }
            val endpoints = shapeDistance(sweep.first(), sweep.last())
            val changes = sweep.zipWithNext().map { (a, b) -> shapeDistance(a, b) }
            println("STOOP $voice $macro sweep: endpoint $endpoints, adjacent $changes")
            if (endpoints <= .02) failures += "$voice $macro is inactive after gain matching ($endpoints)"
            if (changes.count { it > .001 } < 3) failures += "$voice $macro has fewer than three active sweep intervals: $changes"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `touch is locally continuous and keeps the principal root at both articulation ends`() {
        for (voice in StoopVoice.entries) {
            val base = patch(voice).copy(macros = Stoop.defaults(voice) + mapOf("SHADOW" to 0f, "WEAR" to 0f))
            fun render(touch: Float) = Stoop.renderRaw(base.copy(macros = base.macros + ("TOUCH" to touch)))
            val soft = render(0f)
            val hard = render(1f)
            val local = shapeDistance(render(.495f).samples, render(.505f).samples)
            val complete = shapeDistance(soft.samples, hard.samples)
            assertTrue(local < complete * .1 + .02, "$voice TOUCH changes discontinuously at midpoint: local $local, full $complete")
            val hz = Keys.midiHz(base.midi)
            for ((label, snip) in listOf("soft" to soft, "hard" to hard)) {
                val cents = FineTuning.cents(FineTuning.measuredHz(snip, hz, .06f, .3f), hz.toDouble())
                assertTrue(abs(cents) < 10, "$voice $label TOUCH changed root by $cents cents")
            }
            val ratio = rms(hard.samples) / rms(soft.samples).coerceAtLeast(1e-30)
            assertTrue(ratio in .35..2.8, "$voice TOUCH caused an excessive raw loudness swing: $ratio")
        }
    }

    @Test
    fun `all-high controls stay finite with bounded peaks and DC at register boundaries`() {
        for (voice in StoopVoice.entries) for (midi in registers.getValue(voice).let { listOf(it.first(), it.last()) }) {
            val p = patch(voice, midi).copy(macros = Stoop.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f })
            val raw = Stoop.renderRaw(p)
            val output = Stoop.render(p)
            assertEquals(44_100, output.sampleRate)
            assertEquals(1, output.channels)
            assertTrue(raw.samples.all { it.isFinite() && abs(it) < 4f }, "$voice MIDI $midi raw synthesis is unbounded")
            assertTrue(output.samples.all { it.isFinite() && abs(it) <= 1f }, "$voice MIDI $midi output is unbounded")
            assertTrue(abs(output.samples.average()) < .005, "$voice MIDI $midi excessive DC ${output.samples.average()}")
            assertTrue(output.peak() > .01f, "$voice MIDI $midi collapsed at all-high settings")
        }
    }

    @Test
    fun `long-delay answers survive the shortest gestures and begin at their own scheduled time`() {
        for (voice in StoopVoice.entries) {
            val p = patch(voice).copy(
                macros = Stoop.defaults(voice) + mapOf("SHADOW" to 1f, "LENGTH" to 0f, "WEAR" to 0f),
                shadowDelayMs = 250f, shadowReplies = 2, shadowInterval = 12,
            )
            val probe = Stoop.renderProbe(p)
            assertEquals(2, probe.events.size, "$voice lost a deliberate answer")
            val first = probe.events.first()
            assertTrue(abs(first.frame - .25 * Stoop.RATE) <= 1, "$voice answer began at ${first.frame} instead of 250 ms")
            // The symmetric anti-alias kernel can pre-ring within its short support.
            assertTrue(probe.shadow.take(first.frame - 64).all { abs(it) < 1e-7 }, "$voice shadow sounded before its scheduled onset")
            assertTrue(probe.events.all { it.frame + it.durationFrames <= probe.samples.size }, "$voice truncated a scheduled reply")
            assertTrue(probe.events.all { it.midi == p.midi + 12 }, "$voice reply lost its explicit octave interval")
            assertTrue(rms(probe.shadow) > 1e-6 && rms(probe.shadow) < rms(probe.main), "$voice answer is silent or dominates the main voice")
            val direct = Stoop.renderProbe(p.copy(macros = p.macros + ("SHADOW" to 0f)))
            assertTrue(direct.events.isEmpty() && direct.shadow.all { it == 0f }, "$voice SHADOW zero left scheduled or synthesized replies")
        }
    }

    @Test
    fun `difficult held patches close against genuine preroll and preserve oscillator tuning`() {
        val cases = listOf(
            patch(StoopVoice.BASS, 28),
            patch(StoopVoice.TINE, 84),
            patch(StoopVoice.ORGAN, 60),
            patch(StoopVoice.CHORD, 48).copy(chordIntervals = listOf(0, 3, 7)),
        )
        for (p in cases) {
            val held = Stoop.renderProbe(p.copy(macros = p.macros + mapOf("HOLD" to 1f, "WEAR" to 1f, "SHADOW" to 1f, "LENGTH" to 0f)))
            assertTrue(held.prerollFrames > 0 && held.previousCycle.size >= 256, "${p.voice} HOLD has no genuine preceding audio")
            val seam = Keys.seamError(held.previousCycle + held.samples, held.previousCycle.size)
            println("STOOP HOLD ${p.voice}: measured seam $seam, reported ${held.seamError}, maximum pitch movement ${held.maxPitchAdjustmentCents} cents")
            assertEquals(seam, held.seamError, 1e-10, "${p.voice} reported seam is not measured on continuous preceding audio")
            assertTrue(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR, "${p.voice} held state fails the loop seam: $seam")
            assertTrue(held.maxPitchAdjustmentCents.isFinite() && held.maxPitchAdjustmentCents <= 5.0, "${p.voice} HOLD coarsely detuned an oscillator")
            assertTrue(held.events.all { it.frame < held.prerollFrames }, "${p.voice} default HOLD restarts answer gestures inside the exported loop")
        }
    }

    @Test
    fun `clean bright high bass and organ avoid substantial folded energy between harmonics`() {
        for (voice in listOf(StoopVoice.BASS, StoopVoice.ORGAN)) {
            val p = patch(voice, 84).copy(
                macros = Stoop.defaults(voice) + mapOf("COLOR" to 1f, "TOUCH" to .5f, "SHADOW" to 0f, "WEAR" to 0f, "LENGTH" to 1f),
                source = Stoop.defaultSource(voice).copy(detuneCents = 0f),
            )
            val snip = Stoop.renderRaw(p)
            // Any enabled sub shares the periodic root/2 grid, including organ registrations.
            val fundamental = Keys.midiHz(p.midi) / if (p.source.sub > 0f) 2 else 1
            val on = spectralShare(snip, .2f, .6f) { frequency ->
                frequency >= fundamental / 2 && frequency <= 20_000 && abs(frequency - kotlin.math.round(frequency / fundamental) * fundamental) < 18.0
            }
            val clarityDb = 10 * log10(on / (1 - on).coerceAtLeast(1e-30))
            println("STOOP clean $voice MIDI 84 harmonic clarity $clarityDb dB")
            assertTrue(clarityDb > 35, "$voice clean high-note interharmonic energy is only $clarityDb dB below pitched energy")
        }
    }

    private fun patch(voice: StoopVoice, midi: Int = registers.getValue(voice)[1]) =
        StoopPatch("Acceptance ${voice.name}", voice, Stoop.defaults(voice), midi = midi)

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    /** Compare whole finite envelopes with unity RMS; zero padding retains LENGTH's audible tail. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val scaleA = rms(a).coerceAtLeast(1e-30)
        val scaleB = rms(b).coerceAtLeast(1e-30)
        val count = max(a.size, b.size)
        var difference = 0.0
        for (i in 0 until count) {
            val delta = a.getOrElse(i) { 0f } / scaleA - b.getOrElse(i) { 0f } / scaleB
            difference += delta * delta
        }
        return sqrt(difference / count)
    }

    /** A broad root-energy check complements the deliberately narrow frequency estimator. */
    private fun spectralShare(snip: Snip, fromSeconds: Float, seconds: Float, selected: (Double) -> Boolean): Double {
        val from = (fromSeconds * snip.sampleRate).toInt()
        val length = minOf((seconds * snip.sampleRate).toInt(), snip.samples.size - from)
        require(length > 8)
        var size = 32768
        while (size < length) size *= 2
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        for (i in 0 until length) {
            val x = 2 * PI * i / (length - 1)
            val window = .35875 - .48829 * cos(x) + .14128 * cos(2 * x) - .01168 * cos(3 * x)
            real[i] = (snip.samples[from + i] * window).toFloat()
        }
        Fft.forward(real, imaginary)
        var total = 0.0
        var desired = 0.0
        for (bin in 1 until size / 2) {
            val energy = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
            total += energy
            if (selected(bin.toDouble() * snip.sampleRate / size)) desired += energy
        }
        return desired / total.coerceAtLeast(1e-30)
    }

    private companion object {
        val timbralMacros = listOf("TOUCH", "COLOR", "SHADOW", "WEAR", "LENGTH")
        val registers = mapOf(
            StoopVoice.BASS to listOf(28, 36, 55), StoopVoice.TINE to listOf(48, 60, 84),
            StoopVoice.CLAV to listOf(40, 48, 76), StoopVoice.ORGAN to listOf(48, 60, 84),
            StoopVoice.LEAD to listOf(48, 60, 84), StoopVoice.CHORD to listOf(36, 48, 72),
        )
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}

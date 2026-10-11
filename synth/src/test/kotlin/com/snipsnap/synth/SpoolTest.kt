package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Dry machine acceptance: shared output leveling cannot conceal a failed transport or write path. */
class SpoolTest {
    private val timbral = listOf("HEAD", "STRAIN", "RETURN", "IMPRINT", "SPLICE")
    private val drumClasses = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

    private fun rms(s: FloatArray, from: Int = 0, until: Int = s.size): Double {
        val end = until.coerceIn(from, s.size)
        return sqrt((from until end).sumOf { s[it].toDouble() * s[it] } / (end - from).coerceAtLeast(1))
    }

    /** Matching RMS rejects a control that changes only output gain. */
    private fun shapeDistance(a: FloatArray, b: FloatArray, fromSeconds: Float = 0f): Double {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceAtMost(minOf(a.size, b.size))
        val end = minOf(a.size, b.size)
        val ra = rms(a, from, end).coerceAtLeast(1e-20)
        val rb = rms(b, from, end).coerceAtLeast(1e-20)
        return sqrt((from until end).sumOf {
            val d = a[it] / ra - b[it] / rb
            d * d
        } / (end - from).coerceAtLeast(1))
    }

    private data class Pitch(val hz: Double, val share: Double)

    private fun boundaryError(s: FloatArray): Double {
        val step = s.first() - s.last().toDouble()
        val slope = (s[1] - s[0]) - (s.last() - s[s.lastIndex - 1]).toDouble()
        return (step * step + slope * slope) / rms(s).let { it * it }.coerceAtLeast(1e-30)
    }

    /** Search a broad fundamental region so an out-of-tune peak cannot hide outside a narrow bin. */
    private fun forwardPitch(s: FloatArray, wanted: Float): Pitch {
        val first = (.25f * Dsp.RATE).toInt()
        val count = minOf((.70f * Dsp.RATE).toInt(), s.size - first)
        require(count > 1000)
        val n = 65536
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = s[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val binHz = Dsp.RATE.toDouble() / n
        val lo = (wanted * .65 / binHz).toInt().coerceAtLeast(2)
        val hi = (wanted * 1.4 / binHz).toInt().coerceAtMost(power.lastIndex - 1)
        val peak = (lo..hi).maxBy { power[it] }
        val left = sqrt(power[peak - 1])
        val center = sqrt(power[peak])
        val right = sqrt(power[peak + 1])
        val curvature = left - 2 * center + right
        val offset = if (abs(curvature) < 1e-20) 0.0 else (.5 * (left - right) / curvature).coerceIn(-1.0, 1.0)
        val root = ((wanted * .96 / binHz).toInt()..(wanted * 1.04 / binHz).toInt()).sumOf { power[it] }
        val total = ((40 / binHz).toInt()..(15000 / binHz).toInt()).sumOf { power[it] }
        return Pitch((peak + offset) * binHz, root / total.coerceAtLeast(1e-30))
    }

    @Test
    fun `six voices expose five distinct timbral controls plus tuning and hold`() {
        assertEquals(listOf("STRING", "CHOIR", "FLUTE", "BRASS", "PALIMPSEST", "CUT"), SpoolVoice.entries.map { it.name })
        for (voice in SpoolVoice.entries) {
            assertEquals(listOf("TUNE") + timbral + "HOLD", Spool.macrosFor(voice).map { it.name })
            assertTrue(Spool.macrosFor(voice).all { it.default.isFinite() && it.default in 0f..1f && it.neutral.isFinite() && it.neutral in 0f..1f })
            assertEquals(0f, Spool.defaults(voice).getValue("HOLD"))
        }
    }

    @Test
    fun `fresh machines reproduce source transport writes audio and exported samples`() {
        val macros = mapOf("HEAD" to .6f, "STRAIN" to .4f, "RETURN" to .8f, "IMPRINT" to .8f, "SPLICE" to .5f)
        val machine = SpoolMachine(seed = 1907, spliceSchedule = listOf(SpoolSplice(.25f, .12f), SpoolSplice(.55f, -.16f)))
        val first = Spool.renderProbe(SpoolVoice.PALIMPSEST, macros, machine, velocity = .7f)
        val second = Spool.renderProbe(SpoolVoice.PALIMPSEST, macros, machine, velocity = .7f)
        assertContentEquals(first.samples, second.samples, "dry machine is nondeterministic")
        assertEquals(first.diagnostics, second.diagnostics, "machine history is nondeterministic")
        assertContentEquals(Spool.render(SpoolVoice.PALIMPSEST, macros, machine, .7f).samples,
            Spool.render(SpoolVoice.PALIMPSEST, macros, machine, .7f).samples)
        val quiet = Spool.renderProbe(SpoolVoice.BRASS, velocity = .25f)
        val strong = Spool.renderProbe(SpoolVoice.BRASS, velocity = 1f)
        assertTrue(rms(strong.samples, until = Dsp.RATE / 5) > rms(quiet.samples, until = Dsp.RATE / 5) * 1.3,
            "velocity failed to articulate the original performance before leveling")
    }

    @Test
    fun `all voices and all high corners remain audible finite bounded and end quietly`() {
        for (voice in SpoolVoice.entries) for (macros in listOf(emptyMap(), timbral.associateWith { 1f })) {
            val played = Spool.renderProbe(voice, macros)
            val s = played.samples
            val d = played.diagnostics
            val label = "$voice $macros"
            assertTrue(s.isNotEmpty() && s.all { it.isFinite() }, "$label nonfinite output")
            assertTrue(s.size <= Dsp.RATE * 10, "$label exceeded finite host duration")
            assertTrue(s.maxOf { abs(it) } < 4f && rms(s) > 1e-5, "$label runaway or silent source")
            assertTrue(abs(s.average()) < .01, "$label DC offset")
            assertTrue(s.takeLast(Dsp.RATE / 100).maxOf { abs(it) } < 1e-3, "$label failed to terminate quietly")
            assertTrue(d.sourcePeak.isFinite() && d.overlayPeak.isFinite() && d.overlayPeak <= 1f, "$label unbounded recording")
            assertTrue(d.feedbackBound in 0f..0.550001f, "$label full write operator exceeds contraction bound")
            assertTrue(d.allocatedBytes > 0 && d.allocatedBytes < 64L * 1024 * 1024, "$label unbounded render allocation")
            assertTrue(d.spliceCount in 0..4, "$label exceeded four splice boundaries")
            assertTrue(d.trace.all { frame ->
                frame.motorRate.isFinite() && frame.positions.all { it.isFinite() && it >= 0f } &&
                    frame.rates.all { it.isFinite() } && frame.headCoordinates.all { it.isFinite() && it >= 0f && it <= 3.001f } &&
                    frame.springEnergy.isFinite() && frame.springEnergy >= 0f && frame.motorWork.isFinite() && frame.motorWork >= 0f &&
                    frame.frictionLoss.isFinite() && frame.frictionLoss >= 0f
            }, "$label transport state left its valid support")
        }
    }

    @Test
    fun `main forward material retains C3 C4 and C5 separately from its slowing reverse answer`() {
        for (voice in SpoolVoice.entries) for (midi in listOf(48, 60, 72)) {
            val macros = mapOf("TUNE" to (midi - 48) / 24f, "HEAD" to .2f, "STRAIN" to .1f, "SPLICE" to 0f)
            val s = Spool.renderProbe(voice, macros).samples
            val wanted = Keys.midiHz(midi)
            val measured = forwardPitch(s, wanted)
            val cents = 1200 * ln(measured.hz / wanted) / ln(2.0)
            assertTrue(abs(cents) <= 10.0, "$voice MIDI $midi forward root ${measured.hz}Hz ($cents cents)")
            assertTrue(measured.share > .03, "$voice MIDI $midi root has only ${measured.share} of audible power")
        }
    }

    @Test
    fun `minimum gate and extreme head geometry stay inside the actual return support`() {
        val machine = SpoolMachine(sourceSeconds = 2f, gateSeconds = .15f,
            headOffsets = listOf(-.6f, .6f, .6f), spliceSchedule = emptyList())
        val p = Spool.renderProbe(SpoolVoice.CUT, timbral.associateWith { 1f }, machine)
        assertTrue(p.samples.all { it.isFinite() } && rms(p.samples) > 1e-5)
        assertEquals(0, p.diagnostics.spliceCount)
        val d = p.diagnostics
        assertTrue(d.maxForwardRate <= Spool.MAX_FORWARD_RATE + .0001f)
        assertTrue(d.maxReverseRate <= Spool.MAX_REVERSE_RATE + .0001f)
        val returning = d.trace.filter { it.timeSeconds in d.gateSeconds..d.returnEndSeconds }
        assertTrue(returning.all { frame -> frame.headCoordinates.indices.all { k ->
            frame.headCoordinates[k] in 0f..(d.returnTravel[k] + .0001f)
        } }, "a short gate and extreme head offset revealed future material")
        assertTrue(p.samples.takeLast(Dsp.RATE / 100).maxOf { abs(it) } < 1e-3)
    }

    @Test
    fun `return reverses the portion travelled and longer gates produce broader answers`() {
        val macros = mapOf("RETURN" to .9f, "HEAD" to 0f, "STRAIN" to .2f, "IMPRINT" to 0f, "SPLICE" to 0f)
        val short = Spool.renderProbe(SpoolVoice.STRING, macros, SpoolMachine(gateSeconds = .45f))
        val long = Spool.renderProbe(SpoolVoice.STRING, macros, SpoolMachine(gateSeconds = 1.7f))
        assertTrue(long.diagnostics.returnTravel.average() > short.diagnostics.returnTravel.average() * 2.5,
            "reverse answer does not depend on actual forward travel")
        for (played in listOf(short, long)) {
            val d = played.diagnostics
            val atGate = d.trace.minBy { abs(it.timeSeconds - d.gateSeconds) }
            assertTrue(d.returnTravel.indices.all { abs(d.returnTravel[it] - atGate.positions[it]) < .025f },
                "return displacement was invented instead of inherited from the tape")
            val returning = d.trace.filter { it.timeSeconds > d.gateSeconds + .02f && it.timeSeconds < d.returnEndSeconds - .02f }
            assertTrue(returning.isNotEmpty() && returning.any { frame -> frame.rates.any { it < -.01f } }, "no actual reverse coordinate motion")
            assertTrue(returning.all { frame -> frame.rates.all { it <= .001f } }, "return head replayed material forward")
            assertTrue(returning.all { frame -> frame.headCoordinates.indices.all { frame.headCoordinates[it] <= d.returnTravel[it] + .001f } },
                "return head revealed future recording material")
            val early = returning.take(returning.size / 3).sumOf { frame -> frame.rates.sumOf { abs(it).toDouble() } }
            val late = returning.takeLast(returning.size / 3).sumOf { frame -> frame.rates.sumOf { abs(it).toDouble() } }
            assertTrue(early > late * 1.3, "reverse contour did not decelerate toward its rest")
        }
        val muted = Spool.renderProbe(SpoolVoice.STRING, macros + ("RETURN" to 0f), writes = false)
        val audible = Spool.renderProbe(SpoolVoice.STRING, macros, writes = false)
        val from = ((audible.diagnostics.gateSeconds + .1f) * Dsp.RATE).toInt()
        val until = ((audible.diagnostics.returnEndSeconds - .05f) * Dsp.RATE).toInt()
        assertTrue(rms(audible.samples, from, until) > rms(muted.samples, from, until) * 3,
            "RETURN did not reveal the reset journey")
    }

    @Test
    fun `imprint is causal bounded and heard in one finite reread with originals intact`() {
        val macros = mapOf("RETURN" to .8f, "IMPRINT" to 1f, "HEAD" to .55f, "SPLICE" to .2f)
        val written = Spool.play(SpoolVoice.PALIMPSEST, macros, record = true)
        val clean = Spool.play(SpoolVoice.PALIMPSEST, macros, writes = false, record = true)
        val d = written.diagnostics
        assertEquals(clean.diagnostics.sourceHashes, d.sourceHashes, "imprint changed immutable first-generation performances")
        assertTrue(d.writeCount > 0 && d.overlayPeak > 1e-5f, "no local recording was written")
        assertEquals(0L, clean.diagnostics.writeCount)
        val firstWrite = requireNotNull(d.firstWriteSeconds)
        val firstRead = requireNotNull(d.firstOverlayReadSeconds)
        assertTrue(firstRead > firstWrite, "current output read its own just-written sample")
        assertTrue(firstRead < d.durationSeconds, "inherited material became available after the render ended")
        val before = ((firstWrite - .002f).coerceAtLeast(0f) * Dsp.RATE * Dsp.OVERSAMPLE).toInt()
        assertContentEquals(clean.raw.copyOf(before), written.raw.copyOf(before), "writing changed the first encounter before causal exposure")
        assertTrue(shapeDistance(Spool.finish(written.raw, normalize = false), Spool.finish(clean.raw, normalize = false), firstRead) > .005,
            "second encounter failed to hear the inherited trace")
        val zero = Spool.renderProbe(SpoolVoice.PALIMPSEST, macros + ("IMPRINT" to 0f))
        assertEquals(0L, zero.diagnostics.writeCount)
        assertEquals(0f, zero.diagnostics.overlayPeak)
    }

    @Test
    fun `every timbral macro changes normal string shape and diagonal heads differ from frozen pickup`() {
        val baseline = mapOf("HEAD" to .25f, "STRAIN" to .2f, "RETURN" to .6f, "IMPRINT" to .4f, "SPLICE" to .2f)
        for (macro in timbral) {
            val low = Spool.renderProbe(SpoolVoice.STRING, baseline + (macro to 0f))
            val high = Spool.renderProbe(SpoolVoice.STRING, baseline + (macro to 1f))
            assertTrue(shapeDistance(low.samples, high.samples) > .01, "$macro is only gain or has no finite audible encounter")
        }
        val moving = Spool.renderProbe(SpoolVoice.STRING, baseline + ("HEAD" to .9f))
        val frozen = Spool.renderProbe(SpoolVoice.STRING, baseline + ("HEAD" to .9f), frozenHead = true)
        assertEquals(moving.diagnostics.sourceHashes, frozen.diagnostics.sourceHashes)
        assertTrue(shapeDistance(moving.samples, frozen.samples) > .05, "HEAD collapsed to static mixing of completed exports")
        assertEquals(moving.diagnostics.returnTravel, frozen.diagnostics.returnTravel, "freezing the head changed the transport")
    }

    @Test
    fun `one companion load changes the shared motor and the unchanged dominant strip`() {
        val machine = SpoolMachine.forVoice(SpoolVoice.STRING).copy(motorTorque = .4f)
        val heavier = machine.copy(roster = machine.roster.mapIndexed { i, strip ->
            if (i == 2) strip.copy(tension = 2f, drag = 1f) else strip
        })
        val macros = mapOf("STRAIN" to .8f, "HEAD" to .2f, "IMPRINT" to 0f)
        val base = Spool.renderProbe(SpoolVoice.STRING, macros, machine)
        val loaded = Spool.renderProbe(SpoolVoice.STRING, macros, heavier)
        assertEquals(base.diagnostics.sourceHashes, loaded.diagnostics.sourceHashes, "loading changed the original source performances")
        val a = base.diagnostics.trace.filter { it.timeSeconds < machine.gateSeconds }
        val b = loaded.diagnostics.trace.filter { it.timeSeconds < machine.gateSeconds }
        assertEquals(a.size, b.size)
        val motorDifference = a.indices.sumOf { abs(a[it].motorRate - b[it].motorRate).toDouble() } / a.size
        val dominantDifference = a.indices.sumOf { abs(a[it].rates[0] - b[it].rates[0]).toDouble() } / a.size
        assertTrue(motorDifference > 1e-4, "companion load never affected shared powered drive: $motorDifference")
        assertTrue(dominantDifference > 1e-4, "dominant strip only heard its independent slip gesture: $dominantDifference")
    }

    @Test
    fun `a genuine delayed write pass contracts the full tape including cells the write head never visits`() {
        val macros = timbral.associateWith { 1f }
        for (voice in listOf(SpoolVoice.PALIMPSEST, SpoolVoice.CUT)) {
            val p = Spool.imprintContractionProbe(voice, macros, SpoolMachine(gateSeconds = .6f, sourceSeconds = 2f), delta = .01f)
            assertTrue(p.initialDistance > .005f, "$voice contraction probe omitted its seeded perturbation")
            assertTrue(p.finalDistance <= p.initialDistance * .4001f,
                "$voice complete overlay operator failed its .40 infinity-norm bound: ${p.initialDistance} -> ${p.finalDistance}")
            assertTrue(p.unvisitedDistance <= p.initialDistance * .4001f,
                "$voice untouched cells retained unity-gain feedback: ${p.unvisitedDistance}")
        }
    }

    @Test
    fun `broadband inherited traces are filtered before fast forward and reverse coordinate reads`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val high = FloatArray(rate / 3) { (.2 * sin(2 * PI * 60_000 * it / rate)).toFloat() }
        val low = FloatArray(rate / 3) { (.2 * sin(2 * PI * 1_000 * it / rate)).toFloat() }
        for (speed in listOf(3.2f, -3.2f)) {
            fun rendered(input: FloatArray, filtered: Boolean) =
                Spool.finish(Spool.resampleOverlayProbe(input, speed, filtered), normalize = false)
            val unfiltered = rendered(high, false)
            val filtered = rendered(high, true)
            val margin = Dsp.RATE / 100
            val alias = rms(unfiltered, margin, unfiltered.size - margin)
            val safe = rms(filtered, margin, filtered.size - margin)
            assertTrue(alias > .01, "alias probe did not place an audible folded component: $speed $alias")
            assertTrue(safe < alias * .02, "inherited broadband material folded into the audible band: $speed $alias -> $safe")
            val lowPlain = rendered(low, false)
            val lowFiltered = rendered(low, true)
            assertTrue(rms(lowFiltered, margin, lowFiltered.size - margin) > rms(lowPlain, margin, lowPlain.size - margin) * .8,
                "overlay anti-alias path suppressed its useful low recorded tone")
        }
    }

    @Test
    fun `frozen snapshot hold remains deterministic audible and seamless at difficult corners`() {
        for (voice in SpoolVoice.entries) {
            val macros = Spool.defaults(voice) + ("HOLD" to 1f) + ("IMPRINT" to .85f) + ("RETURN" to .8f)
            val probe = Spool.renderProbe(voice, macros, normalize = true)
            val loop = Spool.renderLoop(voice, macros)
            assertContentEquals(probe.samples, loop, "$voice measured and exported loops diverge")
            assertTrue(loop.all { it.isFinite() } && rms(loop) > 1e-5, "$voice silent/nonfinite HOLD")
            assertTrue(probe.diagnostics.writesFrozen, "$voice loop metadata does not disclose frozen writes")
            assertTrue(probe.diagnostics.writeCount > 0, "$voice IMPRINT failed to prepare the held snapshot")
            val previous = requireNotNull(probe.previousCycle)
            val seam = Keys.seamError(previous + loop, previous.size)
            assertTrue(seam < 1e-3 && requireNotNull(probe.seamError) < 1e-3, "$voice HOLD seam $seam")
            assertTrue(boundaryError(loop) < 1e-3, "$voice HOLD boundary step/slope ${boundaryError(loop)}")
        }
        val extreme = Spool.renderLoop(SpoolVoice.CUT, timbral.associateWith { 1f } + ("HOLD" to 1f))
        assertTrue(extreme.all { it.isFinite() } && boundaryError(extreme) < 1e-3, "all-high CUT held loop failed")
    }

    @Test
    fun `complete recipes roundtrip routing timing velocity and exact sound through the shared dispatcher`() {
        val machine = SpoolMachine.forVoice(SpoolVoice.PALIMPSEST).copy(sourceSeconds = 2.7f, gateSeconds = .8f, seed = 411,
            routes = listOf(SpoolRoute(0, 2, .3f), SpoolRoute(2, 1, .4f), SpoolRoute(1, 0, .25f)),
            headOffsets = listOf(-.08f, .18f, .02f), spliceSchedule = listOf(SpoolSplice(.3f, .12f)))
        val patch = SpoolPatch("Saved Spool", SpoolVoice.PALIMPSEST, Spool.defaults(SpoolVoice.PALIMPSEST), machine, .6f)
        val restored = Patches.fromJsonText(patch.toJsonText()) as SpoolPatch
        assertEquals(patch, restored)
        assertEquals(patch.toJsonText(), restored.toJsonText())
        assertContentEquals(patch.render().samples, restored.render().samples)
        val changed = patch.macros + ("HEAD" to .75f)
        assertEquals(patch.copy(name = "Edited Spool", macros = changed), Patches.edited(patch, "Edited Spool", changed))
        assertFailsWith<IllegalArgumentException> { patch.copy(velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("IMPRINT" to 1.1f)) }
        val wrongVoice = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { it["voice"] = JsonValue.Str("KICK") })
        assertFailsWith<JsonException> { Patches.fromJsonValue(wrongVoice) }
        assertTrue(SpoolPresets.all().isNotEmpty())
        assertTrue(Presets.all().containsAll(SpoolPresets.all()))
    }

    @Test
    fun `machine validation refuses instantaneous feedback excessive routing and ambiguous splice geometry`() {
        assertFailsWith<IllegalArgumentException> { SpoolMachine(writeDelaySamples = 0) }
        assertFailsWith<IllegalArgumentException> {
            SpoolMachine(routes = listOf(SpoolRoute(0, 1, .4f), SpoolRoute(2, 1, .4f)))
        }
        assertFailsWith<IllegalArgumentException> { SpoolRoute(1, 1) }
        assertFailsWith<IllegalArgumentException> { SpoolMachine(sourceSeconds = 2f, gateSeconds = 2f) }
        assertFailsWith<IllegalArgumentException> {
            SpoolMachine(spliceSchedule = listOf(SpoolSplice(.3f, .1f), SpoolSplice(.32f, -.1f)))
        }
        assertFailsWith<IllegalArgumentException> { SpoolMachine(roster = listOf(SpoolStrip(SpoolSourceFamily.STRING))) }
    }

    @Test
    fun `standard pitched exports avoid drum classification and held routing remains loop`() {
        for (voice in SpoolVoice.entries) {
            val macros = mapOf("HEAD" to .2f, "STRAIN" to .1f, "RETURN" to .25f, "IMPRINT" to .2f, "SPLICE" to 0f)
            val snip = Spool.render(voice, macros)
            val classified = Classifier.classify(Snip(snip.samples, 1, Dsp.RATE)).drumClass
            assertTrue(classified !in drumClasses, "$voice classified as $classified")
            assertEquals(DrumClass.TONAL, Spool.drumClassFor(voice, macros))
            assertEquals(DrumClass.LOOP, Spool.drumClassFor(voice, macros + ("HOLD" to 1f)))
        }
    }
}

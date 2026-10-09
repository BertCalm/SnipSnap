package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Acceptance probes observe the dry mechanism before loudness targeting or a final fade. */
class PitchwheelTest {
    private val timbral = listOf("PUSH", "TOOTH", "ADHESION", "HEAT", "BODY")
    private val names = listOf("TUNE") + timbral + "HOLD"

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double {
        var energy = 0.0
        for (i in from until until) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (until - from).coerceAtLeast(1))
    }

    private fun loudestWindow(samples: FloatArray, frames: Int): Double {
        if (samples.size <= frames) return rms(samples)
        var energy = 0.0
        for (i in 0 until frames) energy += samples[i].toDouble() * samples[i]
        var maximum = energy
        for (i in frames until samples.size) {
            energy += samples[i].toDouble() * samples[i] - samples[i - frames].toDouble() * samples[i - frames]
            maximum = maxOf(maximum, energy)
        }
        return sqrt(maximum / frames)
    }

    /** Matching energy prevents a control from passing by altering only final gain. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size, Dsp.RATE * 2)
        val ra = rms(a, until = n).coerceAtLeast(1e-20)
        val rb = rms(b, until = n).coerceAtLeast(1e-20)
        var error = 0.0
        for (i in 0 until n) {
            val delta = a[i] / ra - b[i] / rb
            error += delta * delta
        }
        return sqrt(error / n.coerceAtLeast(1))
    }

    /** The root must receive audible energy, not merely exist as a weak tuning reference. */
    private fun rootShare(samples: FloatArray, wanted: Float, from: Float): Double {
        val first = (from * Dsp.RATE).toInt().coerceAtMost(samples.size)
        val count = minOf((.75f * Dsp.RATE).toInt(), samples.size - first)
        require(count > 1000)
        val n = 65536
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        var root = 0.0
        var total = 0.0
        for (k in 1 until n / 2) {
            val hz = k.toDouble() * Dsp.RATE / n
            if (hz < wanted * .5 || hz > minOf(15000.0, wanted * 16.0)) continue
            val power = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            total += power
            if (hz in wanted * .96..wanted * 1.04) root += power
        }
        return root / total.coerceAtLeast(1e-30)
    }

    @Test
    fun `six voices expose the documented controls and pitches independently of wheel speed`() {
        assertEquals(listOf("CLUNK", "PLUCK", "DRAW", "RECOIL", "THAWED", "TURN"),
            PitchwheelVoice.entries.map { it.name })
        assertEquals(48, Pitchwheel.DEFAULT_MIDI)
        assertEquals(24, Pitchwheel.MIDI_MIN)
        assertEquals(96, Pitchwheel.MIDI_MAX)
        for (voice in PitchwheelVoice.entries) {
            assertEquals(names, Pitchwheel.macrosFor(voice).map { it.name })
            assertEquals(.5f, Pitchwheel.defaults(voice).getValue("TUNE"))
            assertEquals(0f, Pitchwheel.defaults(voice).getValue("HOLD"))
            assertTrue(Pitchwheel.macrosFor(voice).all {
                it.default.isFinite() && it.default in 0f..1f && it.neutral.isFinite() && it.neutral in 0f..1f
            })
            for (tune in listOf(0f, .5f, 1f)) {
                val midi = Pitchwheel.DEFAULT_MIDI + (tune * 24f).roundToInt() - 12
                assertEquals(midi, Pitchwheel.midiFor(voice, tune))
                assertEquals(Keys.midiHz(midi), Pitchwheel.frequencyFor(voice, tune))
            }
            val a = Pitchwheel.scramble(voice, Random(91))
            assertEquals(a, Pitchwheel.scramble(voice, Random(91)))
            assertEquals(Pitchwheel.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it.isFinite() && it in 0f..1f })
            assertTrue(!Pitchwheel.isLoop(a.getValue("HOLD")), "scramble unexpectedly creates a powered loop")
        }
        assertTrue(!Pitchwheel.isLoop(.98f))
        assertTrue(Pitchwheel.isLoop(Pitchwheel.HOLD_LOOP))
    }

    @Test
    fun `fresh objects reproduce audio events and thermal history and velocity pays for the gesture`() {
        val macros = mapOf("PUSH" to .65f, "ADHESION" to .65f, "HEAT" to .2f)
        val a = Pitchwheel.renderProbe(PitchwheelVoice.DRAW, macros, midi = 48, velocity = .7f, seconds = 2f)
        // An intervening hot powered object must not leak phase, attachments or heat into this note.
        Pitchwheel.renderProbe(PitchwheelVoice.TURN, mapOf("HEAT" to 1f, "HOLD" to 1f), seconds = 1f)
        val b = Pitchwheel.renderProbe(PitchwheelVoice.DRAW, macros, midi = 48, velocity = .7f, seconds = 2f)
        assertContentEquals(a.samples, b.samples)
        assertEquals(a.diagnostics.events, b.diagnostics.events)
        assertEquals(a.diagnostics.trace, b.diagnostics.trace)
        val soft = Pitchwheel.renderProbe(PitchwheelVoice.PLUCK, velocity = .25f, seconds = .3f)
        val firm = Pitchwheel.renderProbe(PitchwheelVoice.PLUCK, velocity = 1f, seconds = .3f)
        assertTrue(firm.diagnostics.initialEnergy > soft.diagnostics.initialEnergy * 2,
            "velocity does not scale finite mechanical energy")
        for (hold in listOf(0f, 1f)) {
            val zero = Pitchwheel.renderProbe(PitchwheelVoice.RECOIL, mapOf("HOLD" to hold),
                velocity = 0f, seconds = .4f)
            assertTrue(zero.samples.all { it == 0f })
            assertEquals(0.0, zero.diagnostics.initialEnergy)
            assertEquals(0.0, zero.diagnostics.inputWork)
            assertTrue(zero.diagnostics.events.isEmpty(), "a zero-energy object makes contact events")
        }
    }

    @Test
    fun `passive finite objects close their total energy ledger at every trace point`() {
        for (voice in PitchwheelVoice.entries) {
            val p = Pitchwheel.renderProbe(voice, seconds = 4f)
            val d = p.diagnostics
            println("PITCHWHEEL PASSIVE $voice: initial ${d.initialEnergy}, final ${d.finalEnergy}, " +
                "loss ${d.dissipated}, correction ${d.passiveCorrection}, ledger error ${d.maxEnergyError}")
            assertTrue(d.initialEnergy > 0)
            assertEquals(0.0, d.inputWork, "$voice finite gesture has a hidden powered source")
            assertTrue(d.maxEnergyError <= d.initialEnergy * 1e-5 + 1e-9,
                "$voice has unaccounted work ${d.maxEnergyError}")
            assertTrue(d.passiveCorrection <= d.initialEnergy * .1 + 1e-9,
                "$voice numerical damping substitutes for physical dynamics")
            var previousEnergy = Double.POSITIVE_INFINITY
            var previousLoss = 0.0
            for (t in d.trace) {
                val energy = t.mechanicalEnergy + t.acousticEnergy
                assertTrue(listOf(t.angle, t.speed, t.temperature, t.mechanicalEnergy, t.acousticEnergy,
                    t.inputWork, t.dissipated).all { it.isFinite() }, "$voice nonfinite state at ${t.time}")
                assertTrue(energy >= 0 && t.dissipated >= previousLoss - 1e-10)
                assertTrue(energy <= previousEnergy + d.initialEnergy * 1e-5 + 1e-9,
                    "$voice passive energy grows at ${t.time}")
                assertTrue(abs(energy + t.dissipated - d.initialEnergy - t.inputWork) <=
                    d.initialEnergy * 2e-4 + 1e-8, "$voice ledger does not close at ${t.time}")
                previousEnergy = energy
                previousLoss = t.dissipated
            }
            assertTrue(d.finalEnergy < d.initialEnergy, "$voice never loses initial energy")
            assertTrue(d.maxAttachments in 0..4)
        }
    }

    @Test
    fun `pluck and snap events follow real bounded contact and attachment lifecycles`() {
        var releases = 0
        var snaps = 0
        for (voice in PitchwheelVoice.entries) {
            val d = Pitchwheel.renderProbe(voice, seconds = 4f).diagnostics
            println("PITCHWHEEL EVENTS $voice: releases ${d.events.count { it.kind.name == "RELEASE" }}, " +
                "snaps ${d.events.count { it.kind.name == "SNAP" }}, maximum attachments ${d.maxAttachments}")
            val contacts = mutableMapOf<Int, Int>()
            val attachments = mutableMapOf<Int, Int>()
            for (event in d.events) {
                assertTrue(event.time.isFinite() && event.time >= 0 && event.energy.isFinite() && event.energy >= 0)
                assertTrue(event.direction == -1 || event.direction == 1)
                when (event.kind.name) {
                    "CONTACT" -> contacts[event.tooth] = (contacts[event.tooth] ?: 0) + 1
                    "RELEASE" -> {
                        assertTrue((contacts[event.tooth] ?: 0) > 0, "$voice release has no caught tooth")
                        contacts[event.tooth] = contacts.getValue(event.tooth) - 1
                        releases++
                    }
                    "ATTACH" -> attachments[event.tooth] = (attachments[event.tooth] ?: 0) + 1
                    "SNAP", "DETACH" -> {
                        assertTrue((attachments[event.tooth] ?: 0) > 0, "$voice filament ends without an attachment")
                        attachments[event.tooth] = attachments.getValue(event.tooth) - 1
                        if (event.kind.name == "SNAP") snaps++
                    }
                    else -> error("undocumented event ${event.kind}")
                }
                assertTrue(attachments.values.sum() in 0..4, "$voice exceeds its filament budget")
            }
        }
        assertTrue(releases > 0, "the wheel never releases a finger")
        assertTrue(snaps > 0, "the documented voices never release resin through a snap")
    }

    @Test
    fun `resin resistance bow and snap are causal parts of the mechanism`() {
        val sticky = mapOf("PUSH" to .7f, "ADHESION" to .8f, "HEAT" to .35f)
        val active = Pitchwheel.renderProbe(PitchwheelVoice.DRAW, sticky, seconds = 3f)
        val clear = Pitchwheel.renderProbe(PitchwheelVoice.DRAW, sticky, seconds = 3f, resin = false)
        println("PITCHWHEEL RESIN: bowed work ${active.diagnostics.bowedEnergy}, " +
            "snap work ${active.diagnostics.snappedEnergy}, attachments ${active.diagnostics.maxAttachments}")
        assertTrue(active.diagnostics.events.any { it.kind.name == "ATTACH" })
        assertTrue(clear.diagnostics.events.none { it.kind.name in setOf("ATTACH", "SNAP", "DETACH") })
        assertEquals(0.0, clear.diagnostics.bowedEnergy)
        assertEquals(0.0, clear.diagnostics.snappedEnergy)
        val clearTravel = clear.diagnostics.trace.last().angle - clear.diagnostics.trace.first().angle
        val stickyTravel = active.diagnostics.trace.last().angle - active.diagnostics.trace.first().angle
        assertTrue(clearTravel > stickyTravel + .01, "silent resin resistance does not slow wheel travel")
        assertTrue(active.diagnostics.bowedEnergy > 0, "DRAW never supplies slip work to the bowed layer")
        val noBow = Pitchwheel.renderProbe(PitchwheelVoice.DRAW, sticky, seconds = 3f, bowSound = false)
        assertEquals(0.0, noBow.diagnostics.bowedEnergy)
        assertTrue(shapeDistance(active.samples, noBow.samples) > .01, "removing bowed energy changes no dry sound")
        // A medium-adhesion strong gesture pulls through its short strands. The high-
        // adhesion bow patch above may return elastically before any snap threshold.
        val snapping = mapOf("PUSH" to .9f, "ADHESION" to .3f, "HEAT" to .7f)
        val withSnap = Pitchwheel.renderProbe(PitchwheelVoice.PLUCK, snapping, seconds = 3f)
        val noSnap = Pitchwheel.renderProbe(PitchwheelVoice.PLUCK, snapping, seconds = 3f, snapSound = false)
        assertTrue(withSnap.diagnostics.snappedEnergy > 0, "pull-through probe generates no acoustic snap work")
        assertEquals(0.0, noSnap.diagnostics.snappedEnergy)
        assertTrue(noSnap.diagnostics.events.any { it.kind.name == "SNAP" }, "muting snap acoustics removes physical snaps")
        assertTrue(shapeDistance(withSnap.samples, noSnap.samples) > .005, "snap work changes no dry sound")
        val silent = Pitchwheel.renderProbe(PitchwheelVoice.DRAW, sticky, seconds = 1f, resin = false, contacts = false)
        assertTrue(silent.samples.all { it == 0f }, "a wheel without acoustic contacts manufactures sound")
        assertEquals(0.0, silent.diagnostics.releasedEnergy)
    }

    @Test
    fun `recoil disengages before a tooth returns audibly from the reverse side`() {
        val p = Pitchwheel.renderProbe(PitchwheelVoice.RECOIL, seconds = 5f)
        val releases = p.diagnostics.events.filter { it.kind.name == "RELEASE" }
        val returning = releases.firstOrNull { reverse -> reverse.direction == -1 &&
            releases.any { forward -> forward.direction == 1 && forward.tooth == reverse.tooth && forward.time < reverse.time } }
        assertTrue(returning != null, "RECOIL has no reverse release of a previously heard tooth")
        assertTrue(p.diagnostics.trace.any { it.speed < -.01 }, "the wheel never physically reverses")
        val reverse = requireNotNull(returning)
        val forward = releases.last { it.direction == 1 && it.tooth == reverse.tooth && it.time < reverse.time }
        assertTrue(reverse.time - forward.time > .015, "the repeat is boundary chatter")
        val interval = p.diagnostics.trace.filter { it.time >= forward.time && it.time <= reverse.time }
        assertTrue(interval.isNotEmpty() && interval.maxOf { it.angle } - interval.minOf { it.angle } > .005,
            "a reverse release does not follow geometric travel")
        val first = (reverse.time * Dsp.RATE).toInt().coerceIn(0, p.samples.lastIndex)
        assertTrue(rms(p.samples, first, minOf(p.samples.size, first + Dsp.RATE / 10)) > 1e-7,
            "the reverse release is acoustically silent")
        for (tooth in releases.map { it.tooth }.distinct()) {
            for ((a, b) in releases.filter { it.tooth == tooth }.zipWithNext()) {
                assertTrue(b.time - a.time > .001, "tooth $tooth releases repeatedly at a stationary boundary")
            }
        }
    }

    @Test
    fun `heat changes early resistance and dissipated work warms then cools the gesture`() {
        val base = mapOf("PUSH" to .7f, "ADHESION" to .65f)
        val cold = Pitchwheel.renderProbe(PitchwheelVoice.THAWED, base + ("HEAT" to .05f), seconds = 6f)
        val warm = Pitchwheel.renderProbe(PitchwheelVoice.THAWED, base + ("HEAT" to .9f), seconds = 1f)
        val coldTrace = cold.diagnostics.trace
        assertTrue(coldTrace.all { it.temperature in 0.0..1.0 })
        val peak = coldTrace.maxOf { it.temperature }
        assertTrue(peak > coldTrace.first().temperature + .005, "resin friction never warms the gesture")
        assertTrue(coldTrace.last().temperature < peak - .001, "resting resin never cools")
        val coldEarly = coldTrace.first { it.time >= .3 }.angle
        val warmEarly = warm.diagnostics.trace.first { it.time >= .3 }.angle
        assertTrue(warmEarly > coldEarly + .001, "HEAT waits through a long warmup before affecting resistance")
        assertTrue(shapeDistance(cold.samples, warm.samples) > .02, "cold and warm starts have identical dry sound")
    }

    @Test
    fun `requested roots survive plucked bowed and hollow character across the note range`() {
        for (voice in listOf(PitchwheelVoice.PLUCK, PitchwheelVoice.DRAW, PitchwheelVoice.CLUNK)) {
            for (midi in listOf(Pitchwheel.MIDI_MIN, Pitchwheel.DEFAULT_MIDI, Pitchwheel.MIDI_MAX)) {
                val p = Pitchwheel.renderProbe(voice, midi = midi, seconds = 2f)
                val release = p.diagnostics.events.first { it.kind.name == "RELEASE" }
                val from = release.time.toFloat() + .025f
                val wanted = Keys.midiHz(midi)
                val measured = FineTuning.measuredHz(p.samples, Dsp.RATE, wanted, from, .75f)
                val cents = FineTuning.cents(measured, wanted.toDouble())
                println("PITCHWHEEL ROOT $voice MIDI $midi: wanted $wanted Hz, measured $measured Hz, " +
                    "$cents cents, root share ${rootShare(p.samples, wanted, from)}")
                assertTrue(abs(cents) <= 15.0, "$voice MIDI $midi: $cents cents from requested root")
                assertTrue(rootShare(p.samples, wanted, from) > .08, "$voice MIDI $midi hides the root under upper modes")
            }
        }
        val fixed = mapOf("ADHESION" to .2f, "HEAT" to .7f)
        val wanted = Keys.midiHz(Pitchwheel.DEFAULT_MIDI)
        val roots = listOf(.15f, .9f).map { push ->
            val p = Pitchwheel.renderProbe(PitchwheelVoice.PLUCK, fixed + ("PUSH" to push), seconds = 2f)
            val from = p.diagnostics.events.first { it.kind.name == "RELEASE" }.time.toFloat() + .025f
            val measured = FineTuning.measuredHz(p.samples, Dsp.RATE, wanted, from, .75f)
            measured to p.diagnostics.events.count { it.kind.name == "RELEASE" }
        }
        assertTrue(abs(FineTuning.cents(roots[0].first, roots[1].first)) < 10.0, "PUSH transposes oscillator pitch")
        assertTrue(roots[1].second > roots[0].second, "PUSH does not change encounter density")
    }

    @Test
    fun `minimum push still clears a finger and produces a pitched finite gesture`() {
        for (voice in PitchwheelVoice.entries) {
            val p = Pitchwheel.renderProbe(voice, mapOf("PUSH" to 0f), seconds = 2f)
            assertTrue(p.diagnostics.events.any { it.kind.name == "RELEASE" && it.energy > 0 },
                "$voice minimum PUSH strains without ever releasing a finger")
            assertTrue(p.samples.maxOf { abs(it) } > 1e-6f,
                "$voice minimum PUSH is an off control")
            assertEquals(0.0, p.diagnostics.inputWork, "$voice low PUSH receives hidden powered assistance")
        }
    }

    @Test
    fun `timbral controls change dry character after matching signal energy`() {
        val base = mapOf("PUSH" to .65f, "TOOTH" to .45f, "ADHESION" to .45f, "HEAT" to .5f, "BODY" to .5f)
        for (macro in timbral) {
            val low = Pitchwheel.renderProbe(PitchwheelVoice.TURN, base + (macro to .1f), seconds = 2f)
            val high = Pitchwheel.renderProbe(PitchwheelVoice.TURN, base + (macro to .9f), seconds = 2f)
            assertTrue(shapeDistance(low.samples, high.samples) > .02, "$macro changes only output gain or is inactive")
        }
    }

    @Test
    fun `mechanical solver changes preserve encounters recoil and thermal trajectories`() {
        for (voice in listOf(PitchwheelVoice.PLUCK, PitchwheelVoice.RECOIL)) {
            val a = Pitchwheel.renderProbe(voice, seconds = 3f, solverRate = Dsp.RATE * 2)
            val b = Pitchwheel.renderProbe(voice, seconds = 3f, solverRate = Dsp.RATE * 4)
            for (kind in listOf("RELEASE", "SNAP")) {
                val ea = a.diagnostics.events.filter { it.kind.name == kind }
                val eb = b.diagnostics.events.filter { it.kind.name == kind }
                assertTrue(abs(ea.size - eb.size) <= 1, "$voice $kind event count depends on solver clock")
                for ((x, y) in ea.zip(eb)) {
                    assertEquals(x.tooth, y.tooth, "$voice $kind tooth ordering changes with rate")
                    assertEquals(x.direction, y.direction, "$voice $kind reverse timing changes with rate")
                    assertTrue(abs(x.time - y.time) < .015, "$voice $kind encounter time changes with rate")
                }
            }
            val ta = a.diagnostics.trace.last()
            val tb = b.diagnostics.trace.last()
            assertTrue(abs(ta.angle - tb.angle) < .05, "$voice wheel travel depends on solver rate")
            assertTrue(abs(ta.temperature - tb.temperature) < .015, "$voice heat depends on solver rate")
        }
    }

    @Test
    fun `finite tails settle physically before the host fades them and remain quiet`() {
        for (voice in listOf(PitchwheelVoice.PLUCK, PitchwheelVoice.RECOIL, PitchwheelVoice.DRAW)) {
            val natural = Pitchwheel.renderProbe(voice)
            val d = natural.diagnostics
            assertTrue(natural.samples.size.toDouble() / Dsp.RATE <= 8.01, "$voice exceeds its finite render budget")
            // Static resistance may retain elastic potential at rest. A quiet pickup alone
            // is insufficient: the wheel must stop, acoustic energy must decay, and the
            // continued object below must remain quiet with that potential still accounted.
            val rest = d.trace.last()
            println("PITCHWHEEL TAIL $voice: ${natural.samples.size.toDouble() / Dsp.RATE} s, " +
                "speed ${rest.speed}, acoustic energy ${rest.acousticEnergy}, total remaining ${d.finalEnergy}")
            assertTrue(abs(rest.speed) < .005, "$voice cuts off a moving wheel")
            assertTrue(rest.acousticEnergy <= d.initialEnergy * 1e-6 + 1e-10,
                "$voice ends with hidden acoustic energy")
            val window = Dsp.RATE / 5
            val body = loudestWindow(natural.samples, window)
            val tail = rms(natural.samples, natural.samples.size - window)
            assertTrue(body > 1e-7 && tail < body * .001, "$voice relies on final fading to hide a live tail")
            val seconds = natural.samples.size.toFloat() / Dsp.RATE
            val continued = Pitchwheel.renderProbe(voice, seconds = seconds + 1f)
            assertEquals(d.trace, continued.diagnostics.trace.take(d.trace.size),
                "$voice changes mechanical trajectory when capture duration changes")
            assertEquals(d.events, continued.diagnostics.events.take(d.events.size),
                "$voice changes physical event ordering when capture duration changes")
            // Whole-buffer DC estimation and sinc end padding may differ with capture
            // length. The interior acoustic trajectory should still be the same.
            val firstInterior = Dsp.RATE / 5
            val endInterior = natural.samples.size - Dsp.RATE / 20
            var acousticDifference = 0.0
            for (i in firstInterior until endInterior) acousticDifference = maxOf(acousticDifference,
                abs(natural.samples[i] - continued.samples[i]).toDouble())
            assertTrue(acousticDifference < 1e-6, "$voice interior audio changes with capture length")
            assertTrue(loudestWindow(continued.samples.copyOfRange(natural.samples.size, continued.samples.size), window)
                <= body * .001, "$voice resumes after its supposed natural ending")
        }
    }

    @Test
    fun `supported finite corners remain bounded before output processing`() {
        val corners = listOf(
            timbral.associateWith { 0f },
            timbral.associateWith { 1f },
            mapOf("PUSH" to .1f, "ADHESION" to 1f, "HEAT" to 0f),
            mapOf("PUSH" to 1f, "TOOTH" to 1f, "ADHESION" to 1f, "HEAT" to 0f, "BODY" to 1f),
        )
        for (macros in corners) {
            val p = Pitchwheel.renderProbe(PitchwheelVoice.RECOIL, macros, seconds = 2f)
            assertTrue(p.samples.isNotEmpty() && p.samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(abs(p.samples.average()) < .02, "dry corners introduce DC")
            assertTrue(p.diagnostics.maxAttachments <= 4)
        }
        for (voice in PitchwheelVoice.entries) {
            val out = Pitchwheel.render(voice)
            assertEquals(Dsp.RATE, out.sampleRate)
            assertEquals(1, out.channels)
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f })
            assertTrue(out.peak() > .1f, "$voice exports an inaudible default")
        }
    }

    @Test
    fun `powered loops settle their full state and preserve pitch over repeated wraps`() {
        val cases = PitchwheelVoice.entries.map { it to emptyMap<String, Float>() } + listOf(
            PitchwheelVoice.RECOIL to mapOf("PUSH" to .1f, "ADHESION" to 1f, "HEAT" to 0f),
            PitchwheelVoice.TURN to mapOf("PUSH" to 1f, "ADHESION" to 1f, "HEAT" to 1f),
        )
        for ((voice, extra) in cases) {
            val p = Pitchwheel.renderProbe(voice, extra + ("HOLD" to 1f))
            assertTrue(p.prerollCycles in 1..64, "$voice has no bounded mechanical preroll")
            assertTrue(p.stateError.isFinite() && p.stateError < 1e-3, "$voice full state does not converge: ${p.stateError}")
            assertTrue(p.seamError.isFinite() && p.seamError < Keys.MAX_SEAM_ERROR, "$voice held seam ${p.seamError}")
            assertEquals(p.samples.size, p.previousCycle.size, "$voice provides no complete previous cycle")
            assertTrue(rms(p.samples) > 1e-7 && p.samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(p.diagnostics.inputWork > 0, "$voice HOLD has no accounted powered source")
            assertTrue(p.diagnostics.events.any { it.kind.name == "RELEASE" }, "$voice HOLD no longer catches teeth")
            var largestStep = 0.0
            var largestKink = 0.0
            for (i in 1 until p.samples.size) largestStep = maxOf(largestStep, abs(p.samples[i] - p.samples[i - 1]).toDouble())
            for (i in 2 until p.samples.size) largestKink = maxOf(largestKink,
                abs((p.samples[i] - p.samples[i - 1]) - (p.samples[i - 1] - p.samples[i - 2])).toDouble())
            val wrap = (p.samples.first() - p.samples.last()).toDouble()
            val before = (p.samples.last() - p.samples[p.samples.lastIndex - 1]).toDouble()
            val after = (p.samples[1] - p.samples.first()).toDouble()
            assertTrue(abs(wrap) <= largestStep * 1.05 + 1e-7, "$voice loop wraps with an exceptional jump")
            assertTrue(maxOf(abs(wrap - before), abs(after - wrap)) <= largestKink * 1.1 + 1e-7,
                "$voice loop wraps with an exceptional slope change")
            val wanted = Keys.midiHz(Pitchwheel.DEFAULT_MIDI)
            val cents = FineTuning.cents(FineTuning.measuredHz(p.samples, Dsp.RATE, wanted, .05f, .75f), wanted.toDouble())
            println("PITCHWHEEL HOLD $voice $extra: ${p.prerollCycles} preroll cycles, " +
                "state error ${p.stateError}, seam ${p.seamError}, root $cents cents")
            assertTrue(abs(cents) <= 15.0, "$voice held loop loses requested root: $cents cents")
        }
    }
}

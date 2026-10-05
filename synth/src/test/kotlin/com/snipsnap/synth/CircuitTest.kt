package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mechanical acceptance for the invented ensemble; musical acceptance belongs to its auditions. */
class CircuitTest {

    @Test
    fun `walking changes paths without changing the scheduled performance`() {
        val voice = CircuitVoice.PROCESSION
        val m = Circuit.defaults(voice)
        val still = Circuit.inspect(voice, m + ("ORBIT" to 0f), probe = Circuit.Probe(recordPaths = true))
        val walking = Circuit.inspect(voice, m + ("ORBIT" to 1f), probe = Circuit.Probe(recordPaths = true))
        assertEquals(still.events.filterNot { it.reply }, walking.events.filterNot { it.reply })
        assertEquals(0.0, still.rates.orbitHz)
        assertTrue(walking.rates.orbitHz > 0.0)
        for (source in 3..6) {
            val a = still.paths.filter { it.source == source }
            val b = walking.paths.filter { it.source == source }
            assertTrue(a.size > 1 && b.size > 1, "missing path history for player $source")
            assertTrue(a.all { it.x == a.first().x && it.y == a.first().y }, "stationary player $source moved")
            assertTrue(b.any { hypot(it.x - b.first().x, it.y - b.first().y) > 0.001 }, "player $source did not walk")
            assertTrue(b.any { abs(it.directMeters - b.first().directMeters) > 0.001 }, "direct path $source did not move")
            assertTrue(b.any { p -> p.reflectedMeters.indices.any { abs(p.reflectedMeters[it] - b.first().reflectedMeters[it]) > 0.001 } }, "reflected paths $source did not move")
        }
        assertTrue(relativeDifference(still.raw, walking.raw) > 1e-4, "motion is absent from mono audio")
    }

    @Test
    fun `playing faster leaves the movement clock alone`() {
        val voice = CircuitVoice.PROCESSION
        val m = Circuit.defaults(voice)
        val sparse = Circuit.inspect(voice, m + ("PACE" to 0f))
        val busy = Circuit.inspect(voice, m + ("PACE" to 1f))
        assertEquals(sparse.rates.orbitHz, busy.rates.orbitHz)
        assertEquals(sparse.rates.requestedOrbitHz, busy.rates.requestedOrbitHz)
        assertTrue(busy.rates.paceHz > sparse.rates.paceHz)
        val sparseBase = sparse.events.count { !it.reply && it.source >= 3 }
        val busyBase = busy.events.count { !it.reply && it.source >= 3 }
        assertTrue(sparseBase >= 4, "the slow playing range lost its initial ensemble accent")
        assertTrue(sparse.events.any { !it.reply && it.source >= 3 && it.timeSeconds > 0.5 }, "low PACE has no later gesture")
        assertTrue(busyBase > sparseBase, "PACE did not increase playing ($sparseBase to $busyBase)")
    }

    @Test
    fun `formation and canyon control separate valid acoustic paths`() {
        val voice = CircuitVoice.EXPANSE
        val m = Circuit.defaults(voice) + ("ORBIT" to 0f)
        val close = Circuit.inspect(voice, m + mapOf("DIAMETER" to 0f, "CANYON" to 0f), probe = Circuit.Probe(recordPaths = true))
        val wide = Circuit.inspect(voice, m + mapOf("DIAMETER" to 1f, "CANYON" to 0f), probe = Circuit.Probe(recordPaths = true))
        val canyon = Circuit.inspect(voice, m + mapOf("DIAMETER" to 1f, "CANYON" to 1f), probe = Circuit.Probe(recordPaths = true))
        for (r in listOf(close, wide, canyon)) for (p in r.paths) {
            assertTrue(p.x.isFinite() && p.y.isFinite() && p.directMeters.isFinite())
            assertTrue(p.directMeters > 0.0, "source ${p.source} reaches the observer singularity")
            assertEquals(2, p.reflectedMeters.size)
            assertTrue(p.reflectedMeters.all { it.isFinite() && it > p.directMeters }, "invalid reflected path $p")
        }
        for (source in 3..6) {
            val a = close.paths.first { it.source == source }
            val b = wide.paths.first { it.source == source }
            val c = canyon.paths.first { it.source == source }
            assertTrue(hypot(b.x, b.y) > hypot(a.x, a.y), "DIAMETER did not widen player $source")
            assertEquals(b.x, c.x, "CANYON changed the formation")
            assertEquals(b.y, c.y, "CANYON changed the formation")
            assertTrue(b.reflectedMeters != c.reflectedMeters, "CANYON did not move reflected arrivals")
        }
    }

    @Test
    fun `replies have bounded energy and follow their own listening arrival`() {
        val voice = CircuitVoice.ANSWER
        val m = Circuit.defaults(voice) + mapOf("CANYON" to 1f, "PACE" to 0.15f)
        val r = Circuit.inspect(voice, m)
        val replies = r.events.filter { it.reply }
        val base = r.events.filterNot { it.reply }
        assertTrue(replies.isNotEmpty(), "ANSWER never answers its canyon")
        assertTrue(replies.size <= Circuit.MAX_REPLIES, "unbounded reply count ${replies.size}")
        assertTrue(replies.sumOf { it.energy } <= Circuit.MAX_REPLY_ENERGY, "reply energy exceeds its finite budget")
        assertTrue(replies.sumOf { it.energy } < base.sumOf { it.energy }, "replies exceed scheduled performer energy")
        for (e in replies) {
            val cue = assertNotNull(e.cueSeconds, "reply has no listening arrival")
            val emitted = assertNotNull(e.emittedSeconds, "reply has no outgoing accent time")
            val observer = assertNotNull(e.observerCueSeconds, "reply has no observer comparison")
            val cueSource = assertNotNull(e.cueSource, "reply has no triggering source")
            val reflection = assertNotNull(e.reflection, "reply has no reflected path")
            assertTrue(e.source in 3..6 && cueSource in 0..6 && cueSource != e.source)
            assertTrue(reflection in 0..1)
            assertEquals(1, e.depth, "a reply recursively cued another reply")
            assertTrue(e.timeSeconds > cue, "reply precedes its listening arrival: $e")
            assertTrue(cue > emitted && observer > emitted, "reflected accent has no travel time: $e")
            assertTrue(base.any { it.source == cueSource && it.timeSeconds == emitted }, "cue has no originating accent: $e")
            assertEquals(if (cueSource == 1) "reflected-tube-accent" else "reflected-clapper", e.reason)
            assertTrue(e.energy.isFinite() && e.energy > 0.0)
        }
        assertTrue(replies.any { abs(it.cueSeconds!! - it.observerCueSeconds!!) > 1.0 / Dsp.RATE }, "performers listened at the observer position")
        for (player in 3..6) {
            val times = replies.filter { it.source == player }.map { it.timeSeconds }
            for ((a, b) in times.zipWithNext()) {
                assertTrue(b - a >= Circuit.refractorySeconds(r.rates.paceHz), "player $player ignored its refractory interval")
            }
        }
    }

    @Test
    fun `disabling replies preserves the base performance and canyon paths`() {
        val voice = CircuitVoice.ANSWER
        val m = Circuit.defaults(voice) + ("CANYON" to 1f)
        val enabled = Circuit.inspect(voice, m, probe = Circuit.Probe(recordPaths = true))
        val disabled = Circuit.inspect(voice, m, probe = Circuit.Probe(responses = false, recordPaths = true))
        assertEquals(enabled.events.filterNot { it.reply }, disabled.events)
        assertEquals(enabled.paths, disabled.paths)
        assertTrue(enabled.events.any { it.reply })
        val responseDelta = relativeDifference(enabled.raw, disabled.raw)
        println("CIRCUIT REPLIES: normalized difference=$responseDelta events=${enabled.events.filter { it.reply }}")
        assertTrue(responseDelta > 1e-4, "behavioral response has no sound: difference=$responseDelta")
        val dry = Circuit.inspect(voice, m + ("CANYON" to 0f), probe = Circuit.Probe(responses = false))
        assertTrue(relativeDifference(disabled.raw, dry.raw) > 1e-4, "disabling replies also disabled acoustic returns")
    }

    @Test
    fun `the trio and all surrounding roles have distinct finite source sound`() {
        val voice = CircuitVoice.VOICED
        val m = Circuit.defaults(voice)
        val sources = (0..6).map { source ->
            Circuit.inspect(voice, m, probe = Circuit.Probe(solo = source, responses = false)).raw.also {
                assertTrue(it.all(Float::isFinite), "source $source is not finite")
                assertTrue(rms(it) > 1e-5, "source $source is silent")
            }
        }
        for (a in 0..2) for (b in a + 1..2) {
            assertTrue(shapeDifference(sources[a], sources[b]) > 1e-4, "tube $b is a scaled copy of tube $a")
        }
        val vocal = Circuit.inspect(voice, m).events.filter { it.source == 6 }
        assertTrue(vocal.isNotEmpty(), "the voiced configuration lost its gestures")
        assertTrue(vocal.map { it.kind }.distinct().size >= 2, "vocal diagnostics lost grunt or two-part gesture")
    }

    @Test
    fun `local tube coupling changes mechanics beyond output gain`() {
        val voice = CircuitVoice.ROOT
        val m = Circuit.defaults(voice) + mapOf("CANYON" to 0f, "BREATH" to 0.7f)
        val uncoupled = Circuit.inspect(voice, m, probe = Circuit.Probe(solo = 0, coupling = 0.0, responses = false))
        val coupled = Circuit.inspect(voice, m, probe = Circuit.Probe(solo = 0, coupling = 1.0, responses = false))
        val residual = shapeDifference(uncoupled.raw, coupled.raw)
        println("CIRCUIT COUPLING: residual=${scientific(residual)}")
        assertTrue(residual > 1e-6, "coupling only changed amplitude")
    }

    @Test
    fun `moderate central tones retain the root at bottom middle and top notes`() {
        val evidence = mutableListOf<String>()
        for (voice in listOf(CircuitVoice.ROOT, CircuitVoice.VOICED)) for (tune in listOf(0f, 0.5f, 1f)) {
            val m = Circuit.defaults(voice) + mapOf("TUNE" to tune, "ORBIT" to 0f, "BREATH" to 0.45f, "CANYON" to 0.2f)
            val root = Circuit.render(voice, m, probe = Circuit.Probe(solo = 0, responses = false))
            val hz = Keys.midiHz(Circuit.ROOT_MIDI + (tune * Circuit.TUNE_SEMITONES).toInt())
            val cents = FineTuning.cents(FineTuning.measuredHz(root, hz, 0.35f, 0.7f), hz.toDouble())
            evidence.add("$voice/$tune=${decimal(cents)}")
            assertTrue(abs(cents) < 10.0, "$voice TUNE $tune: root moved $cents cents")
        }
        println("CIRCUIT ROOT CENTS: ${evidence.joinToString(", ")}")
    }

    @Test
    fun `PACE preserves the pitched root in the full ensemble`() {
        val voice = CircuitVoice.ROOT
        val hz = Keys.midiHz(Circuit.ROOT_MIDI + Circuit.TUNE_SEMITONES / 2)
        val evidence = mutableListOf<String>()
        for (pace in listOf(0f, 1f)) {
            val m = Circuit.defaults(voice) + mapOf("PACE" to pace, "ORBIT" to 0f, "BREATH" to 0.45f)
            val snip = Circuit.render(voice, m)
            val cents = FineTuning.cents(FineTuning.measuredHz(snip, hz, 0.4f, 0.7f), hz.toDouble())
            evidence.add("$pace=${decimal(cents)}")
            assertTrue(abs(cents) < 10.0, "PACE $pace moved the ensemble root $cents cents")
        }
        println("CIRCUIT PACE CENTS: ${evidence.joinToString(", ")}")
    }

    @Test
    fun `the full ensemble keeps its root in every normal voice`() {
        val hz = Keys.midiHz(Circuit.ROOT_MIDI + Circuit.TUNE_SEMITONES / 2)
        val evidence = mutableListOf<String>()
        for (voice in CircuitVoice.entries) {
            val snip = Circuit.render(voice, Circuit.defaults(voice))
            val cents = FineTuning.cents(FineTuning.measuredHz(snip, hz, 0.4f, 0.7f), hz.toDouble())
            evidence.add("$voice=${decimal(cents)}")
            assertTrue(abs(cents) < 10.0, "$voice full mix moved the root $cents cents")
        }
        println("CIRCUIT FULL-MIX CENTS: ${evidence.joinToString(", ")}")
    }

    @Test
    fun `fresh renders reproduce audio paths and event order exactly`() {
        val voice = CircuitVoice.CONFLUENCE
        val m = Circuit.defaults(voice) + mapOf("CANYON" to 0.85f, "DIAMETER" to 0.7f)
        val probe = Circuit.Probe(recordPaths = true)
        val a = Circuit.inspect(voice, m, velocity = 0.65f, probe = probe)
        val b = Circuit.inspect(voice, m, velocity = 0.65f, probe = probe)
        assertContentEquals(a.raw, b.raw)
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.events, b.events)
        assertEquals(a.paths, b.paths)
        assertEquals(a.rates, b.rates)
        assertEquals(a.events.sortedWith(compareBy({ it.timeSeconds }, { it.source })), a.events)
    }

    @Test
    fun `velocity supplies performer energy and zero velocity is silent`() {
        val voice = CircuitVoice.PROCESSION
        val m = Circuit.defaults(voice)
        val zero = Circuit.inspect(voice, m, velocity = 0f)
        val soft = Circuit.inspect(voice, m, velocity = 0.2f)
        val hard = Circuit.inspect(voice, m, velocity = 1f)
        assertTrue(zero.raw.all { it == 0f } && zero.snip.samples.all { it == 0f })
        assertTrue(zero.events.all { it.energy == 0.0 })
        assertTrue(hard.events.sumOf { it.energy } > soft.events.sumOf { it.energy })
        assertTrue(rms(hard.raw) > rms(soft.raw), "velocity has no raw energy effect")
        assertContentEquals(hard.snip.samples, Circuit.render(voice, m).samples)
    }

    @Test
    fun `all macro endpoints stay finite and every normal voice responds`() {
        val names = listOf("BREATH", "DIAMETER", "ORBIT", "PACE", "CANYON")
        var minimumDifference = Double.POSITIVE_INFINITY
        var weakest = ""
        for (voice in CircuitVoice.entries) {
            val m = Circuit.defaults(voice)
            for (name in names) {
                val low = Circuit.inspect(voice, m + (name to 0f))
                val high = Circuit.inspect(voice, m + (name to 1f))
                assertHealthy(low, "$voice $name 0")
                assertHealthy(high, "$voice $name 1")
                val difference = relativeDifference(low.snip.samples, high.snip.samples)
                if (difference < minimumDifference) {
                    minimumDifference = difference
                    weakest = "$voice/$name"
                }
                assertTrue(difference > 1e-4, "$voice $name is inactive after shared loudness matching")
            }
        }
        println("CIRCUIT MACROS: minimum difference=${scientific(minimumDifference)} ($weakest)")
    }

    @Test
    fun `combined extreme phrases terminate without numerical recovery or DC`() {
        var peak = 0.0
        var dcFraction = 0.0
        var tailFraction = 0.0
        for (voice in CircuitVoice.entries) for (tune in listOf(0f, 1f)) {
            val m = Circuit.defaults(voice) + mapOf("TUNE" to tune, "BREATH" to 1f, "DIAMETER" to 1f, "ORBIT" to 1f, "PACE" to 1f, "CANYON" to 1f)
            val r = Circuit.inspect(voice, m)
            assertHealthy(r, "$voice extreme TUNE $tune")
            assertTrue(r.snip.durationSeconds in 3f..8f, "phrase length ${r.snip.durationSeconds}")
            val dc = abs(r.raw.average()) / rms(r.raw)
            val tail = rms(r.raw, r.raw.size - Dsp.RATE / 2, r.raw.size) / rms(r.raw, 0, Dsp.RATE)
            peak = maxOf(peak, r.rawPeak)
            dcFraction = maxOf(dcFraction, dc)
            tailFraction = maxOf(tailFraction, tail)
            assertTrue(dc < 0.03, "$voice extreme accumulated DC")
            assertTrue(tail < 1.0, "$voice extreme did not release")
        }
        println("CIRCUIT EXTREMES: worst raw peak=${scientific(peak)}, DC/RMS=${scientific(dcFraction)}, tail/first-second RMS=${scientific(tailFraction)}")
    }

    @Test
    fun `passive tubes and canyon decay after performer input is turned off`() {
        val voice = CircuitVoice.EXPANSE
        val m = Circuit.defaults(voice) + mapOf("BREATH" to 1f, "CANYON" to 1f)
        val r = Circuit.inspect(voice, m, probe = Circuit.Probe(inputOffSeconds = 0.5))
        assertHealthy(r, "passive decay")
        val early = rms(r.raw, (0.5 * Dsp.RATE).toInt(), (1.0 * Dsp.RATE).toInt())
        val late = rms(r.raw, r.raw.size - Dsp.RATE / 2, r.raw.size)
        println("CIRCUIT PASSIVE: early RMS=${scientific(early)}, late RMS=${scientific(late)}, ratio=${scientific(late / early)}")
        assertTrue(early > 1e-5, "no passive sound remains to measure")
        assertTrue(late < early * 0.01, "passive network did not decay: $early to $late")
    }

    @Test
    fun `held loops sound and close while slow nonzero movement survives`() {
        val cases = listOf(
            CircuitVoice.ROOT to mapOf("ORBIT" to 0f, "PACE" to 0.3f),
            CircuitVoice.EXPANSE to mapOf("ORBIT" to 0.02f, "PACE" to 0.07f),
            CircuitVoice.EXPANSE to mapOf("ORBIT" to 1f, "PACE" to 0.07f),
            CircuitVoice.CONFLUENCE to mapOf("ORBIT" to 1f, "PACE" to 1f, "CANYON" to 1f),
        )
        val expanseMotion = mutableListOf<Circuit.Report>()
        for ((voice, changes) in cases) {
            val r = Circuit.inspect(voice, Circuit.defaults(voice) + changes + ("HOLD" to 1f))
            if (voice == CircuitVoice.EXPANSE) expanseMotion.add(r)
            assertHealthy(r, "$voice held")
            val loop = assertNotNull(r.loop)
            println("CIRCUIT HOLD $voice ORBIT ${changes.getValue("ORBIT")}: seam=${scientific(loop.seamError)}, convergence=${scientific(loop.convergenceError)}, preroll=${loop.prerollCycles}, seconds=${decimal(r.rates.loopSeconds)}, orbit=${decimal(r.rates.orbitHz)}Hz (requested ${decimal(r.rates.requestedOrbitHz)}), pace=${decimal(r.rates.paceHz)}Hz (requested ${decimal(r.rates.requestedPaceHz)}), raw peak=${scientific(r.rawPeak)}")
            assertTrue(loop.seamError.isFinite() && loop.seamError < Keys.MAX_SEAM_ERROR, "$voice seam ${loop.seamError}")
            assertTrue(loop.convergenceError.isFinite() && loop.convergenceError < Keys.MAX_SEAM_ERROR, "$voice unsettled state ${loop.convergenceError}")
            assertTrue(loop.prerollCycles >= 1)
            assertTrue(rms(r.raw) > 1e-4, "$voice passes the seam by being silent")
            assertEquals(r.snip.samples.size.toDouble() / Dsp.RATE, r.rates.loopSeconds)
            assertTrue(r.rates.paceHz > 0.0)
            val phraseCycles = r.rates.paceHz * r.rates.loopSeconds / 4.0
            assertTrue(abs(phraseCycles - kotlin.math.round(phraseCycles)) < 1e-9, "$voice playing pattern does not repeat at the wrap")
            if (changes.getValue("ORBIT") > 0f) {
                assertTrue(r.rates.requestedOrbitHz > 0.0 && r.rates.orbitHz > 0.0, "$voice rounded a slow walk to stationary")
                val orbitCycles = r.rates.orbitHz * r.rates.loopSeconds
                assertTrue(abs(orbitCycles - kotlin.math.round(orbitCycles)) < 1e-9, "$voice formation does not repeat at the wrap")
            } else {
                assertEquals(0.0, r.rates.orbitHz)
            }
            val seamStep = abs(r.snip.samples.last() - r.snip.samples.first())
            var maxStep = 0f
            for (i in 1 until r.snip.samples.size) maxStep = maxOf(maxStep, abs(r.snip.samples[i] - r.snip.samples[i - 1]))
            assertTrue(seamStep <= maxStep, "$voice wrap jumps farther than any interior sample")
        }
        assertTrue(expanseMotion[1].rates.orbitHz > expanseMotion[0].rates.orbitHz, "HOLD flattened the ORBIT control")
        assertTrue(relativeDifference(expanseMotion[0].snip.samples, expanseMotion[1].snip.samples) > 1e-4, "HOLD motion rates have the same audio")
    }

    private fun assertHealthy(r: Circuit.Report, label: String) {
        assertTrue(r.raw.isNotEmpty() && r.raw.all(Float::isFinite), "$label raw is nonfinite")
        assertTrue(r.snip.samples.all { it.isFinite() && abs(it) <= 1f }, "$label output exceeds the audio contract")
        assertEquals(0, r.recoveries, "$label needed numerical recovery")
        assertTrue(r.rawPeak.isFinite() && r.rawPeak > 0.0 && r.rawPeak <= 1.0, "$label raw peak ${r.rawPeak} exceeds calibrated source headroom")
        assertEquals(Dsp.RATE, r.snip.sampleRate)
        assertEquals(1, r.snip.channels)
    }

    private fun scientific(value: Double) = "%.3e".format(java.util.Locale.ROOT, value)
    private fun decimal(value: Double) = "%.3f".format(java.util.Locale.ROOT, value)

    private fun rms(s: FloatArray, from: Int = 0, until: Int = s.size): Double {
        val a = from.coerceIn(0, s.size)
        val b = until.coerceIn(a, s.size)
        return if (b == a) 0.0 else sqrt((a until b).sumOf { s[it].toDouble() * s[it] } / (b - a))
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var difference = 0.0
        var energy = 0.0
        for (i in 0 until n) {
            val d = a[i].toDouble() - b[i]
            difference += d * d
            energy += a[i].toDouble() * a[i] + b[i].toDouble() * b[i]
        }
        return difference / energy.coerceAtLeast(1e-20)
    }

    /** Remove the best-fit scalar so an amplitude-only change cannot pass. */
    private fun shapeDifference(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var aa = 0.0
        var ab = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            aa += a[i].toDouble() * a[i]
            ab += a[i].toDouble() * b[i]
            bb += b[i].toDouble() * b[i]
        }
        val scale = ab / aa.coerceAtLeast(1e-20)
        var residual = 0.0
        for (i in 0 until n) {
            val d = b[i] - scale * a[i]
            residual += d * d
        }
        return residual / bb.coerceAtLeast(1e-20)
    }
}

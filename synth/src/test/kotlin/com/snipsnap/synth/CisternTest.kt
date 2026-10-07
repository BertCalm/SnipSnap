package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Causal and material acceptance checks; listening still decides the instrument's character. */
class CisternTest {

    @Test
    fun `release events have causes, finite travel and a slot that is spent once`() {
        val rendered = Cistern.renderInternal(
            CisternVoice.CASCADE,
            mapOf("STRIKE" to 0.75f, "SUSPENSION" to 0.85f, "DROP" to 0.65f),
            seconds = 4f,
        )
        val diagnostics = rendered.diagnostics
        val events = diagnostics.events
        val byId = events.associateBy { it.id }
        assertEquals(events.size, byId.size, "event ids repeat")
        val releases = events.filter { it.kind.name == "RELEASE" }
        val landings = events.filter { it.kind.name == "LANDING" }
        assertTrue(releases.isNotEmpty(), "the strike released no drops")
        assertTrue(landings.isNotEmpty(), "no released drop reached the surface")
        assertTrue(releases.map { it.region }.distinct().size > 1, "release influence never left its initial region")
        assertEquals(releases.size, releases.map { it.slot }.distinct().size, "a one-shot slot released twice")
        assertTrue(releases.size <= diagnostics.slotCount)
        for (release in releases) {
            assertTrue(release.slot in 0 until diagnostics.slotCount)
            val cause = byId[release.cause] ?: error("release ${release.id} has no cause ${release.cause}")
            assertTrue(cause.kind.name in setOf("STRIKE", "LANDING", "RELEASE"), "unexpected release cause ${cause.kind}")
            assertTrue(cause.time <= release.time, "release ${release.id} preceded its cause")
            assertTrue(release.mass > 0.0 && release.travel in 0.04..0.50)
        }
        for (landing in landings) {
            val release = byId[landing.cause] ?: error("landing ${landing.id} has no release")
            assertEquals("RELEASE", release.kind.name)
            assertEquals(release.slot, landing.slot)
            assertTrue(abs(landing.time - release.time - release.travel) < 1e-7, "arrival does not follow release + travel")
            assertTrue(abs(landing.mass - release.mass) < 1e-10, "mass changed in flight")
        }
        assertTrue(releases.any { byId[it.cause]?.kind?.name == "LANDING" }, "no landing caused a secondary release")
        assertTrue(events.zipWithNext().all { (a, b) -> a.time <= b.time }, "event order is not chronological")
        assertMaterial(rendered)
    }

    @Test
    fun `restrained suspension still answers and stronger strikes change branching`() {
        for (voice in CisternVoice.entries) {
            val quiet = Cistern.renderInternal(voice, mapOf("SUSPENSION" to 0f), seconds = 2f)
            assertTrue(quiet.diagnostics.events.any { it.kind.name == "RELEASE" }, "$voice has a dead SUSPENSION minimum")
        }
        val companions = mapOf("SUSPENSION" to 0.6f, "DROP" to 0.6f)
        val soft = Cistern.renderInternal(CisternVoice.CASCADE, companions + ("STRIKE" to 0f), seconds = 3f)
        val hard = Cistern.renderInternal(CisternVoice.CASCADE, companions + ("STRIKE" to 1f), seconds = 3f)
        val softReleases = soft.diagnostics.events.filter { it.kind.name == "RELEASE" }
        val hardReleases = hard.diagnostics.events.filter { it.kind.name == "RELEASE" }
        // Both cascades can eventually spend the entire inventory. Parent
        // topology still distinguishes wider initial reach from drop-led growth.
        fun lineage(rendered: Cistern.Rendered): Map<Int, Pair<String, Int>> {
            val events = rendered.diagnostics.events.associateBy { it.id }
            return rendered.diagnostics.events.filter { it.kind.name == "RELEASE" }.associate { release ->
                val parent = events.getValue(release.cause)
                release.slot to (parent.kind.name to parent.slot)
            }
        }
        assertTrue(
            lineage(hard) != lineage(soft),
            "STRIKE did not change released slots or their parents: ${softReleases.size} -> ${hardReleases.size}",
        )
    }

    @Test
    fun `quiet notes retain real liquid answers across the supported registers`() {
        for (voice in CisternVoice.entries) {
            for (midi in listOf(36, 60, 84)) {
                val quiet = Cistern.renderInternal(voice, midi = midi, velocity = 0.25f, seconds = 2f)
                assertTrue(quiet.diagnostics.events.any { it.kind.name == "LANDING" },
                    "$voice MIDI $midi quiet note has no delayed liquid answer")
                assertMaterial(quiet)
            }
        }
        for (midi in listOf(36, 60, 84)) {
            val soft = Cistern.renderInternal(CisternVoice.DRIP, mapOf("STRIKE" to 0f),
                midi = midi, velocity = 0.25f, seconds = 2f)
            assertTrue(soft.diagnostics.events.any { it.kind.name == "LANDING" },
                "DRIP MIDI $midi loses its liquid answer at minimum STRIKE")
            assertMaterial(soft)
        }
    }

    @Test
    fun `liquid remains nonnegative and conserved with retained and fast-draining extremes`() {
        for (drain in listOf(0f, 1f)) {
            val rendered = Cistern.renderInternal(
                CisternVoice.POOL,
                mapOf("STRIKE" to 1f, "SUSPENSION" to 1f, "DROP" to 1f, "DRAIN" to drain),
                seconds = 6f,
            )
            assertMaterial(rendered)
            assertTrue(rendered.diagnostics.maxLoad > 0.0)
            assertEquals(0, rendered.diagnostics.nonFiniteRecoveries, "a state reset concealed instability")
        }
    }

    @Test
    fun `frozen loading replays the same drops but changes their shared surface`() {
        val macros = mapOf("STRIKE" to 0.65f, "SUSPENSION" to 0.75f, "DROP" to 0.9f, "SKIN" to 0.4f, "DRAIN" to 0.25f)
        val loaded = Cistern.renderInternal(CisternVoice.POOL, macros, seconds = 3f)
        val frozen = Cistern.renderInternal(CisternVoice.POOL, macros, seconds = 3f, frozenLoad = true)
        assertEquals(loaded.diagnostics.events, frozen.diagnostics.events, "the comparison changed the scheduled drops")
        assertTrue(loaded.diagnostics.maxLoad > 0.0)
        val change = relativeDifference(loaded.raw, frozen.raw)
        println("CISTERN live/frozen loading relative audio change $change")
        assertTrue(change > 0.01, "loading barely changes the raw resonating surface: $change")
        assertTrue(abs(brightness(loaded.raw, 0.5f, 1.5f) - brightness(frozen.raw, 0.5f, 1.5f)) > 1e-7)
        assertTrue(loaded.diagnostics.finalEnergy != frozen.diagnostics.finalEnergy, "frozen replay reported the original acoustic state")
        assertMaterial(loaded)
        assertMaterial(frozen)
    }

    @Test
    fun `drain changes wet contact during a gesture and removes available liquid`() {
        val macros = mapOf("SUSPENSION" to 0.75f, "DROP" to 0.9f, "SKIN" to 0.4f)
        val retained = Cistern.renderInternal(CisternVoice.POOL, macros + ("DRAIN" to 0f), seconds = 3f)
        val drained = Cistern.renderInternal(CisternVoice.POOL, macros + ("DRAIN" to 1f), seconds = 3f)
        val retainedWetness = wetnessIntegral(retained)
        val drainedWetness = wetnessIntegral(drained)
        println("CISTERN retained/fast wetness integrals $retainedWetness / $drainedWetness")
        assertTrue(drainedWetness < retainedWetness, "faster drainage did not lighten the surface")
        assertTrue(drained.diagnostics.traces.last().drained > retained.diagnostics.traces.last().drained)
        assertTrue(relativeDifference(retained.raw, drained.raw, toSeconds = 1.5f) > 0.005, "DRAIN only changed an inaudible late tail")
        assertMaterial(retained)
        assertMaterial(drained)
        val dry = Cistern.renderInternal(CisternVoice.FIRST, seconds = 1f, initialStrike = false, maintenance = false)
        assertTrue(dry.raw.all { it == 0f })
        assertTrue(dry.diagnostics.traces.all { it.drainFlow == 0.0 }, "a dry surface generated drain flow")
        assertTrue(dry.diagnostics.events.none { it.kind.name in setOf("RELEASE", "LANDING") }, "the suspended field released without a stimulus")
    }

    @Test
    fun `a passive surface decays and retained liquid cannot render endlessly`() {
        val passive = Cistern.renderInternal(CisternVoice.RECOVERY, mapOf("DRAIN" to 1f), seconds = 6f, noDrops = true)
        assertTrue(passive.diagnostics.maxEnergy > 0.0)
        assertTrue(passive.diagnostics.finalEnergy < passive.diagnostics.maxEnergy * 1e-4, "passive modes retained their excitation")
        val afterStrike = passive.diagnostics.traces.filter { it.time >= 0.05 }
        assertTrue(afterStrike.isNotEmpty())
        for ((a, b) in afterStrike.zipWithNext()) {
            assertTrue(b.energy <= a.energy * 1.00001 + 1e-12, "unforced acoustic energy grew at ${b.time}s")
        }
        val retained = Cistern.renderInternal(CisternVoice.POOL, mapOf("DRAIN" to 0f, "DROP" to 1f, "SUSPENSION" to 1f))
        assertTrue(retained.snip.durationSeconds <= 8f)
        assertTrue(retained.diagnostics.events.count { it.kind.name == "RELEASE" } <= retained.diagnostics.slotCount)
        assertEquals(0, retained.diagnostics.nonFiniteRecoveries)
        val draining = Cistern.renderInternal(
            CisternVoice.RECOVERY,
            mapOf("DRAIN" to 1f, "DROP" to 0.85f, "SUSPENSION" to 0.65f),
            seconds = 6f,
        )
        val finalLanding = draining.diagnostics.events.filter { it.kind.name == "LANDING" }.maxOfOrNull { it.time }
            ?: error("recovery gesture had no liquid impacts")
        val wetTail = draining.diagnostics.traces.filter { it.time > finalLanding + 0.05 }
        assertTrue(wetTail.size > 2, "no passive wet tail was observed")
        for ((a, b) in wetTail.zipWithNext()) {
            assertTrue(b.energy <= a.energy * 1.00001 + 1e-12, "drainage added acoustic energy at ${b.time}s")
        }
    }

    @Test
    fun `identical inputs and reordered macro maps reproduce samples and event order`() {
        val macros = linkedMapOf("STRIKE" to 0.7f, "SUSPENSION" to 0.8f, "DROP" to 0.6f, "SKIN" to 0.4f, "DRAIN" to 0.7f)
        val reordered = macros.entries.reversed().associate { it.key to it.value }
        val a = Cistern.renderInternal(CisternVoice.CASCADE, macros, midi = 67, velocity = 0.7f, seconds = 2f)
        val b = Cistern.renderInternal(CisternVoice.CASCADE, reordered, midi = 67, velocity = 0.7f, seconds = 2f)
        assertTrue(a.raw.contentEquals(b.raw))
        assertTrue(a.snip.samples.contentEquals(b.snip.samples))
        assertEquals(a.diagnostics.events, b.diagnostics.events)
        for (hold in listOf(0f, 1f)) {
            val silent = Cistern.renderInternal(CisternVoice.CASCADE, mapOf("HOLD" to hold), velocity = 0f)
            assertTrue(silent.raw.all { it == 0f }, "zero velocity HOLD $hold produced raw energy")
            assertTrue(silent.snip.samples.all { it == 0f }, "zero velocity HOLD $hold was normalized into a note")
            assertTrue(silent.diagnostics.events.none { it.kind.name in setOf("RELEASE", "LANDING") }, "zero velocity released stored liquid")
        }
    }

    @Test
    fun `each normal voice gives all five macros authority before the long tail`() {
        for (voice in CisternVoice.entries) {
            for (macro in listOf("STRIKE", "SUSPENSION", "DROP", "SKIN", "DRAIN")) {
                val low = Cistern.renderInternal(voice, mapOf(macro to 0f), seconds = 1.6f)
                val high = Cistern.renderInternal(voice, mapOf(macro to 1f), seconds = 1.6f)
                val change = relativeDifference(low.snip.samples, high.snip.samples)
                println("CISTERN $voice $macro 0 -> 1 relative audio change $change")
                assertTrue(change > 0.005, "$voice $macro has negligible normalized authority: $change")
            }
        }
    }

    @Test
    fun `skin does not transpose the dry surface and moderate wet mixes retain the root`() {
        for (voice in CisternVoice.entries) {
            for (skin in listOf(0f, 1f)) {
                val dry = Cistern.renderInternal(voice, mapOf("SKIN" to skin), seconds = 0.8f, noDrops = true)
                assertRoot(dry.snip, Cistern.DEFAULT_MIDI, 10.0, "$voice dry SKIN $skin")
            }
            val wet = Cistern.renderInternal(voice, seconds = 4f)
            assertRoot(wet.snip, Cistern.DEFAULT_MIDI, 10.0, "$voice default wet", fromSeconds = steadyFrom(wet))
        }
        for (midi in listOf(Cistern.MIDI_MIN, Cistern.MIDI_MAX)) {
            val rendered = Cistern.renderInternal(CisternVoice.FIRST, midi = midi, seconds = 4f)
            assertRoot(rendered.snip, midi, 10.0, "FIRST MIDI $midi", fromSeconds = steadyFrom(rendered))
        }
        val extreme = Cistern.renderInternal(
            CisternVoice.POOL,
            mapOf("DROP" to 1f, "SUSPENSION" to 1f, "DRAIN" to 0f, "SKIN" to 0f),
            seconds = 4f,
        )
        assertRoot(extreme.snip, Cistern.DEFAULT_MIDI, 30.0, "POOL retained extreme", fromSeconds = steadyFrom(extreme))
    }

    @Test
    fun `standard voices stay mono finite and pitched at supported range edges`() {
        for (voice in CisternVoice.entries) for (midi in listOf(Cistern.MIDI_MIN, Cistern.MIDI_MAX)) {
            val rendered = Cistern.renderInternal(voice, midi = midi, velocity = 0.7f, seconds = 2f)
            val snip = rendered.snip
            assertEquals(1, snip.channels)
            assertEquals(44100, snip.sampleRate)
            assertTrue(snip.samples.all { it.isFinite() })
            assertTrue(rendered.raw.all { it.isFinite() })
            assertTrue(snip.peak() in 0.01f..0.991f, "$voice MIDI $midi peak ${snip.peak()}")
            assertTrue(abs(snip.samples.average()) < 0.02, "$voice MIDI $midi DC ${snip.samples.average()}")
            assertEquals(0, rendered.diagnostics.nonFiniteRecoveries)
            val classified = Classifier.classify(snip).drumClass
            assertTrue(classified !in DRUMS, "$voice MIDI $midi classified as $classified")
            assertEquals(Cistern.drumClassFor(voice), classified)
        }
    }

    @Test
    fun `held circulation converges its material and the actual preceding audio cycle`() {
        for ((voice, macros) in listOf(
            CisternVoice.FIRST to mapOf("HOLD" to 1f),
            CisternVoice.CASCADE to mapOf("HOLD" to 1f, "SUSPENSION" to 1f, "DROP" to 0.85f),
            CisternVoice.POOL to mapOf("HOLD" to 1f, "DROP" to 1f, "DRAIN" to 0f),
        )) {
            val rendered = Cistern.renderInternal(voice, macros)
            val loop = requireNotNull(rendered.loop)
            println("CISTERN HOLD $voice seam ${loop.seam}, state ${loop.stateDiff}, material ${loop.materialDiff}, cycles ${loop.iterations}")
            assertTrue(loop.converged, "$voice did not converge")
            assertTrue(loop.seam.isFinite() && loop.seam < Keys.MAX_SEAM_ERROR, "$voice seam ${loop.seam}")
            assertTrue(loop.stateDiff < 1e-3 && loop.materialDiff < 1e-3, "$voice material changes at the wrap")
            assertTrue(loop.pumpCapacity > 0.0 && loop.pumpDelay > 0.0)
            assertTrue(loop.eventsPerPeriod > 0)
            assertTrue(loop.iterations <= Cistern.MAX_HOLD_CYCLES)
            assertEquals(loop.period.size, loop.previous.size)
            assertTrue(relativeDifference(loop.period, loop.previous) < 0.05, "$voice actual preceding cycle differs")
            assertRoot(rendered.snip, Cistern.DEFAULT_MIDI, 30.0, "$voice held circulation")
            assertMaterial(rendered)
            assertEquals(0, rendered.diagnostics.nonFiniteRecoveries)
        }
    }

    @Test
    fun `sparse and rounded held circulation closes across the supported registers`() {
        val sparse = mapOf("STRIKE" to 0f, "SUSPENSION" to 0f, "DROP" to 0f,
            "SKIN" to 0.65f, "DRAIN" to 1f, "HOLD" to 1f)
        val heavy = mapOf("STRIKE" to 0f, "SUSPENSION" to 0f, "DROP" to 1f,
            "DRAIN" to 0f, "HOLD" to 1f)
        val cases = listOf(36, 60, 84).map { Triple(CisternVoice.FIRST, it, 0.25f) } +
            listOf(36, 84).flatMap { midi -> listOf(0.25f, 1f).map { Triple(CisternVoice.DRIP, midi, it) } }
        for ((voice, midi, velocity) in cases) {
            val rendered = Cistern.renderInternal(voice, if (voice == CisternVoice.FIRST) sparse else heavy,
                midi = midi, velocity = velocity)
            val loop = requireNotNull(rendered.loop)
            assertTrue(loop.converged && loop.iterations <= Cistern.MAX_HOLD_CYCLES,
                "$voice MIDI $midi velocity $velocity did not settle")
            assertTrue(loop.stateDiff < 2e-5 && loop.materialDiff < 2e-5 && loop.eventDiff < 2e-5)
            assertTrue(loop.relativePeriodDiff < 1e-4)
            assertTrue(loop.seam < Keys.MAX_SEAM_ERROR)
            assertTrue(loop.boundaryStepError < 1e-3 && loop.boundarySlopeError < 1e-3)
            assertTrue(loop.eventsPerPeriod > 0)
            assertEquals(0, loop.crossfadeSamples)
            assertTrue(rendered.raw.all { it.isFinite() && abs(it) < 1f })
            assertEquals(0, rendered.diagnostics.nonFiniteRecoveries)
            assertMaterial(rendered)
        }
    }

    @Test
    fun `raw gestures preserve PCM headroom across neutral sweeps and dense range edges`() {
        var greatestPeak = 0f
        var greatestLabel = ""
        fun check(label: String, rendered: Cistern.Rendered) {
            val peak = rendered.raw.maxOf { abs(it) }
            assertTrue(rendered.raw.all { it.isFinite() }, "$label raw samples are nonfinite")
            assertTrue(peak < 1f, "$label raw peak $peak would clip an unconditioned PCM audition")
            assertEquals(0, rendered.diagnostics.nonFiniteRecoveries, "$label concealed an unstable state")
            if (peak > greatestPeak) { greatestPeak = peak; greatestLabel = label }
        }
        for (voice in CisternVoice.entries) {
            val neutral = Cistern.macrosFor(voice).associate { it.name to it.neutral }
            for (macro in listOf("STRIKE", "SUSPENSION", "DROP", "SKIN", "DRAIN")) {
                for (value in listOf(0f, .25f, .5f, .75f, 1f)) {
                    check("$voice $macro=$value", Cistern.renderInternal(voice, neutral + (macro to value)))
                }
            }
        }
        val dense = mapOf("STRIKE" to 1f, "SUSPENSION" to 1f, "DROP" to 1f, "SKIN" to 1f, "DRAIN" to 0f)
        for (midi in listOf(Cistern.MIDI_MIN, Cistern.MIDI_MAX)) {
            check("dense RIPPLE MIDI $midi", Cistern.renderInternal(CisternVoice.RIPPLE, dense, midi = midi))
        }
        check("held dense CASCADE MIDI ${Cistern.MIDI_MIN}",
            Cistern.renderInternal(CisternVoice.CASCADE, dense + ("HOLD" to 1f), midi = Cistern.MIDI_MIN))
        println("CISTERN raw neutral-sweep/dense maximum $greatestPeak at $greatestLabel")
    }

    @Test
    fun `a long dense render stays within the existing offline time and memory budget`() {
        val rendered = Cistern.renderInternal(
            CisternVoice.RIPPLE,
            mapOf("STRIKE" to 1f, "SUSPENSION" to 1f, "DROP" to 1f, "SKIN" to 1f, "DRAIN" to 0f),
            midi = Cistern.MIDI_MAX,
            seconds = 8f,
        )
        val seconds = rendered.renderNanos / 1e9
        println("CISTERN dense eight-second render ${seconds}s, accounted bytes ${rendered.bytes}")
        // The existing FlotillaTest budget is reused rather than claiming a real-time engine.
        assertTrue(seconds < 20.0, "render took ${seconds}s")
        assertTrue(rendered.bytes < 192L * 1024 * 1024, "accounted ${rendered.bytes} bytes")
        assertTrue(rendered.snip.durationSeconds > 7.9f)
        assertEquals(0, rendered.diagnostics.nonFiniteRecoveries)
        assertMaterial(rendered)
    }

    private fun assertMaterial(rendered: Cistern.Rendered) {
        val diagnostics = rendered.diagnostics
        val tolerance = maxOf(1e-7, diagnostics.initialInventory * 1e-6)
        assertTrue(diagnostics.maxInventoryError <= tolerance, "inventory error ${diagnostics.maxInventoryError}")
        assertTrue(diagnostics.traces.isNotEmpty())
        for (trace in diagnostics.traces) {
            assertTrue(trace.surface.all { it.isFinite() && it >= 0f }, "negative/nonfinite surface at ${trace.time}")
            assertTrue(trace.surface.all { it <= Cistern.REGION_CAPACITY + tolerance }, "regional load exceeds capacity")
            val quantities = listOf(trace.suspended, trace.airborne, trace.drained, trace.pump)
            assertTrue(quantities.all { it.isFinite() && it >= -tolerance })
            val total = quantities.sum() + trace.surface.sumOf { it.toDouble() }
            assertTrue(abs(total - diagnostics.initialInventory) <= tolerance, "material lost at ${trace.time}: $total vs ${diagnostics.initialInventory}")
            assertTrue(trace.energy.isFinite() && trace.energy >= 0.0)
            assertTrue(trace.inputEnergy.isFinite() && trace.dissipated.isFinite() && trace.dissipated >= 0.0)
            val energyTolerance = maxOf(1e-9, diagnostics.maxEnergy * 1e-6)
            assertTrue(
                abs(trace.energy + trace.dissipated - trace.inputEnergy) <= energyTolerance,
                "acoustic energy accounting does not close at ${trace.time}: ${trace.energy} + ${trace.dissipated} vs ${trace.inputEnergy}",
            )
            assertTrue(trace.drainFlow.isFinite() && trace.drainFlow >= 0.0)
        }
    }

    private fun wetnessIntegral(rendered: Cistern.Rendered): Double =
        rendered.diagnostics.traces.zipWithNext().sumOf { (a, b) ->
            (a.surface.sumOf { it.toDouble() } + b.surface.sumOf { it.toDouble() }) * 0.5 * (b.time - a.time)
        }

    /** Compare equally leveled audio, or raw excitation, without changing the event schedule. */
    private fun relativeDifference(a: FloatArray, b: FloatArray, toSeconds: Float? = null): Double {
        val n = minOf(maxOf(a.size, b.size), toSeconds?.let { (it * 44100).toInt() } ?: Int.MAX_VALUE)
        var difference = 0.0
        var energy = 0.0
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0f }.toDouble()
            val y = b.getOrElse(i) { 0f }.toDouble()
            difference += (x - y) * (x - y)
            energy += maxOf(x * x, y * y)
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    private fun brightness(samples: FloatArray, fromSeconds: Float, toSeconds: Float): Double {
        val from = maxOf(1, (fromSeconds * 44100).toInt())
        val to = minOf(samples.size, (toSeconds * 44100).toInt())
        var energy = 0.0
        var difference = 0.0
        for (i in from until to) {
            val x = samples[i].toDouble()
            val d = x - samples[i - 1]
            energy += x * x
            difference += d * d
        }
        return difference / energy.coerceAtLeast(1e-30)
    }

    private fun assertRoot(snip: Snip, midi: Int, centsLimit: Double, label: String, fromSeconds: Float = 0.05f) {
        val hz = Keys.midiHz(midi)
        val measured = FineTuning.measuredHz(snip, hz, fromSeconds, 0.4f)
        val cents = FineTuning.cents(measured, hz.toDouble())
        val share = rootShare(snip, hz, fromSeconds)
        println("CISTERN $label: root $cents cents, spectral share $share")
        assertTrue(abs(cents) <= centsLimit, "$label root is $cents cents off")
        assertTrue(share > 0.1, "$label expected root has too little spectral energy: $share")
    }

    /**
     * During overlapping impacts the peak includes interference between force
     * pulses. Probes at C2 read -19 cents there, +1.8 after the final landing;
     * the spec's steady-tone tuning policy needs the latter window.
     */
    private fun steadyFrom(rendered: Cistern.Rendered): Float {
        assertEquals(0, rendered.diagnostics.pendingLandings, "pitch probe ended with drops still in flight")
        val lastImpact = rendered.diagnostics.events.filter { it.kind.name in setOf("STRIKE", "LANDING") }
            .maxOfOrNull { it.time } ?: 0.0
        val start = (lastImpact + 0.05).toFloat()
        assertTrue(rendered.snip.durationSeconds >= start + 0.4f, "no full steady-tone window after $lastImpact")
        return start
    }

    /** A narrow estimator needs an independent energy check: an absent root can still have a peak. */
    private fun rootShare(snip: Snip, root: Float, fromSeconds: Float): Double {
        val n = 32768
        val from = (fromSeconds * snip.sampleRate).toInt()
        val length = minOf((0.4f * snip.sampleRate).toInt(), snip.samples.size - from)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until length) re[i] = snip.samples[from + i] * (0.5 - 0.5 * cos(2 * PI * i / (length - 1))).toFloat()
        Fft.forward(re, im)
        var fundamental = 0.0
        var total = 0.0
        val halfWidth = maxOf(5.0, root * 0.04)
        for (i in 1 until n / 2) {
            val hz = i.toDouble() * snip.sampleRate / n
            val energy = re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            total += energy
            if (abs(hz - root) < halfWidth) fundamental += energy
        }
        return fundamental / total.coerceAtLeast(1e-30)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}

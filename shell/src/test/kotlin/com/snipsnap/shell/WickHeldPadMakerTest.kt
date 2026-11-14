package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WickHeldPadMakerTest {
    private fun spec() = WickHeldPadMaker.Spec(WickPresets.forVoice(WickVoice.PLUCK).single { it.name == "Held Glow" })
    private fun bits(samples: FloatArray) = IntArray(samples.size) { samples[it].toRawBits() }
    private fun synthetic(): Wick.Probe {
        val body = FloatArray(1024) { .125f }
        val prefix = body.copyOf().also { it[17] = .25f }
        val tail = body.copyOf().also { it[20] = Float.fromBits(it[20].toRawBits() + 1) }
        val loop = Wick.LoopDiagnostics(body.size, body.size.toDouble() / WavWriter.MPC_SAMPLE_RATE, 3, 0.0, 0.0, true, "synthetic-only")
        return Wick.Probe(body.copyOf(), body, Wick.Diagnostics(.125, .125, .125, emptyList(), 0, 0, 0.0, 1.0, 0.0, 0.0, 0.0, loop), prefix + tail)
    }

    @Test fun syntheticPrefixAndNativeBodyKeepDistinctExactRepresentations() {
        val probe = synthetic(); val before = listOf(bits(probe.raw), bits(probe.samples), bits(probe.seamContext!!))
        val spec = spec(); val capture = WickHeldPadMaker.capture(spec, probe); val p = probe.samples.size
        assertContentEquals(bits(probe.samples), bits(capture.note.snip.samples.copyOfRange(p, 2 * p)))
        assertContentEquals(bits(probe.seamContext!!.copyOfRange(0, p)), bits(capture.note.snip.samples.copyOfRange(0, p)))
        assertContentEquals(bits(probe.samples), bits(capture.acceptedCycle))
        assertContentEquals(bits(probe.seamContext!!), bits(capture.originalSeamContext))
        assertTrue(capture.contextTailMaxAbsError > 0.0 && capture.contextTailMaxAbsError < 1e-6)
        assertTrue(capture.originalContextSha256 != capture.assembledSha256)
        assertEquals(p.toLong(), capture.note.loopStartFrame)
        assertFalse(capture.naturalAttack); assertFalse(capture.naturalRelease)
        assertEquals("SETTLED_NATIVE_PREDECESSOR", capture.onsetPolicy)
        assertContentEquals(before[0], bits(probe.raw)); assertContentEquals(before[1], bits(probe.samples)); assertContentEquals(before[2], bits(probe.seamContext!!))
        capture.note.snip.samples[p] = .3f
        assertFailsWith<IllegalArgumentException> { WickHeldPadMaker.assemble("Wick Held", spec, capture) }
        assertContentEquals(before[1], bits(probe.samples)); assertContentEquals(before[2], bits(probe.seamContext!!))
    }

    @Test fun malformedOrUnconvergedSourcesRefuse() {
        val source = spec(); val p = synthetic(); val loop = p.diagnostics.loop!!
        assertFailsWith<IllegalArgumentException> { WickHeldPadMaker.Spec(source.patch.copy(macros = source.patch.macros + ("HOLD" to 0f))) }
        assertFailsWith<IllegalArgumentException> { WickHeldPadMaker.Spec(source.patch.copy(velocity = 0f)) }
        val invalid = listOf(
            p.copy(seamContext = null), p.copy(seamContext = p.seamContext!!.copyOf(1000)),
            p.copy(raw = FloatArray(1)), p.copy(samples = p.samples.copyOf().also { it[0] = Float.NaN }),
            p.copy(seamContext = p.seamContext!!.copyOf().also { it[0] = Float.POSITIVE_INFINITY }),
            p.copy(seamContext = p.seamContext!!.copyOf().also { it[p.samples.size + 20] += .01f }),
            p.copy(diagnostics = p.diagnostics.copy(loop = null)),
            p.copy(diagnostics = p.diagnostics.copy(loop = loop.copy(converged = false))),
            p.copy(diagnostics = p.diagnostics.copy(loop = loop.copy(periodFrames = 1025))),
            p.copy(diagnostics = p.diagnostics.copy(loop = loop.copy(seam = Keys.MAX_SEAM_ERROR))))
        for (bad in invalid) assertFailsWith<IllegalArgumentException> { WickHeldPadMaker.capture(source, bad) }
    }

    @Test fun joinedSeamRejectsNearEqualTailThatBreaksQuietJoin() {
        val fixture = synthetic(); val n = fixture.samples.size
        val prefix = FloatArray(n) { .125f }.also { for (i in n - 256 until n) it[i] = 1e-7f }
        val tail = prefix.copyOf()
        val cycle = tail.copyOf().also { for (i in n - 256 until n) it[i] += 2e-7f }
        val context = prefix + tail
        assertEquals(0.0, Keys.seamError(context, n))
        assertTrue(cycle.indices.maxOf { kotlin.math.abs(cycle[it] - tail[it]) } < 1e-6f)
        assertTrue(Keys.seamError(prefix + cycle, n) > Keys.MAX_SEAM_ERROR)
        assertFailsWith<IllegalArgumentException> { WickHeldPadMaker.capture(spec(), fixture.copy(raw = cycle.copyOf(), samples = cycle, seamContext = context)) }
    }

    @Test fun actualHeldGlowProbeReachesTypedRootOnlyConsumerWithoutChangingSource() {
        val spec = spec(); val patch = spec.patch; val patchBefore = patch.toJsonText()
        assertEquals(WickVoice.PLUCK, patch.voice); assertEquals(48, patch.midi)
        // The only actual native inspect in this test class. No source edits/overrides.
        val probe = Wick.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, rattleSource = patch.rattleSource)
        val rawBefore = bits(probe.raw); val cycleBefore = bits(probe.samples); val contextBefore = bits(assertNotNull(probe.seamContext))
        val capture = WickHeldPadMaker.capture(spec, probe); val p = probe.diagnostics.loop!!.periodFrames
        val (program, samples) = WickHeldPadMaker.assemble("Wick Held", spec, capture)
        val group = program.keygroups.single(); val layer = group.layers.single(); val file = layer.sampleName + ".wav"
        assertEquals(48, group.lowNote); assertEquals(48, group.highNote); assertEquals(48, group.rootNote)
        assertEquals(2L * p, layer.frameCount); assertEquals(p.toLong(), layer.loopStartFrame)
        // Genuine public consumer types, projected in memory. Not a saved reopen/playback claim.
        val instrument = InstrumentStore.Instrument(program.name, program.volumeRelease, listOf(InstrumentStore.Zone(
            group.lowNote, group.highNote, group.rootNote, file, layer.frameCount, layer.loopStartFrame,
            listOf(InstrumentStore.Layer(file, layer.frameCount, layer.loopStartFrame, layer.velStart, layer.velEnd)))),
            voiceMode = program.voiceMode, explicitNoteOn = program.explicitNoteOn)
        val framesOf: (String) -> Long? = { if (it == file) samples.getValue(layer.sampleName).frameCount.toLong() else null }
        val hit = assertNotNull(KeyHit.resolve(instrument, 48, .65f, framesOf))
        assertTrue(hit.loops); assertEquals(p.toLong(), hit.loopStartFrame); assertEquals(2L * p, hit.frames); assertEquals(1.0, hit.pitchRatio)
        assertEquals(.65f * InstrumentEngine.VOICE_LEVEL, hit.gain)
        assertNull(KeyHit.resolve(instrument, 47, .65f, framesOf)); assertNull(KeyHit.resolve(instrument, 49, .65f, framesOf))
        assertContentEquals(cycleBefore, bits(capture.note.snip.samples.copyOfRange(p, 2 * p)))
        assertContentEquals(contextBefore.copyOfRange(0, p), bits(capture.note.snip.samples.copyOfRange(0, p)))
        assertContentEquals(rawBefore, bits(probe.raw)); assertContentEquals(cycleBefore, bits(probe.samples)); assertContentEquals(contextBefore, bits(probe.seamContext!!))
        assertEquals(patchBefore, patch.toJsonText())
        assertTrue(capture.originalContextSeam < Keys.MAX_SEAM_ERROR && capture.joinedSeam < Keys.MAX_SEAM_ERROR)
        val report = JsonValue.Obj(linkedMapOf(
            "sourcePatch" to patch.toJsonValue(), "periodFrames" to JsonValue.Num(p.toDouble()),
            "acceptedCycleSha256" to JsonValue.Str(capture.acceptedCycleSha256), "originalContextSha256" to JsonValue.Str(capture.originalContextSha256),
            "assembledSha256" to JsonValue.Str(capture.assembledSha256), "sourceSha256" to JsonValue.Str(capture.sourceSha256),
            "contextTailMaxAbsError" to JsonValue.Num(capture.contextTailMaxAbsError), "originalContextSeam" to JsonValue.Num(capture.originalContextSeam),
            "joinedSeam" to JsonValue.Num(capture.joinedSeam), "onsetPolicy" to JsonValue.Str(capture.onsetPolicy),
            "nativeInspectCallsSourceAudited" to JsonValue.Num(1.0), "runtimeRenderCounterInstrumented" to JsonValue.Bool(false),
            "rootMeaning" to JsonValue.Str("authored root only; no measured physical pitch claim"),
            "fileImport" to JsonValue.Bool(false), "savedReopen" to JsonValue.Bool(false), "playback" to JsonValue.Bool(false),
            "naturalAttack" to JsonValue.Bool(false), "naturalRelease" to JsonValue.Bool(false)))
        println("WICK_HELD_CONTEXT_V1 " + Json.write(report))
    }
}

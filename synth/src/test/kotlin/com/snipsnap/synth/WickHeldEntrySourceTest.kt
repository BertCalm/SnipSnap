package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WickHeldEntrySourceTest {
    @Test fun invalidEntryRequestsRefuseBeforeSynthesis() {
        assertFailsWith<IllegalArgumentException> { Wick.inspectHeldSource(WickVoice.PLUCK) }
        assertFailsWith<IllegalArgumentException> { Wick.inspectHeldSource(WickVoice.PLUCK, midi = 23) }
        assertFailsWith<IllegalArgumentException> { Wick.inspectHeldSource(WickVoice.PLUCK, velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Wick.inspectHeldSource(WickVoice.PLUCK, mapOf("HOLD" to 1f), velocity = 0f) }
        assertFailsWith<IllegalArgumentException> {
            Wick.inspectHeldSource(WickVoice.PLUCK, mapOf("HOLD" to 1f), options = Wick.Options(oversample = 3))
        }
    }

    @Test fun heldSourceKeepsAcceptedCycleAndRealSelectedTimeline() {
        val patch = WickPresets.forVoice(WickVoice.PLUCK).single { it.name == "Held Glow" }
        val before = patch.toJsonText()
        assertEquals("7eee1bd4428780df01ecd8552f5e6fe3d88728d0713562d23947f056e003ca26", hash(before.toByteArray()))
        assertEquals(48, patch.midi)
        // Exactly one NEW native feature call, no baseline inspect/render comparison.
        val source = Wick.inspectHeldSource(patch.voice, patch.macros, patch.midi, patch.velocity,
            rattleSource = patch.rattleSource)
        val probe = source.probe
        val diagnostics = assertNotNull(probe.diagnostics.loop)
        val p = diagnostics.periodFrames
        assertEquals(105857, p)
        assertEquals(p, probe.raw.size)
        assertEquals(p, probe.samples.size)
        assertEquals("6bdf6693ef15f24ccc737d550496a28846693d8b1f8c567e506e6aa8a2b13504", floatHash(probe.samples))
        assertEquals("090b5aa69e47b7ab92c8d5d524b887ec67fdb9b0b0293bb12e9d64551fd51600", floatHash(assertNotNull(probe.seamContext)))
        assertTrue(diagnostics.converged)
        assertTrue(diagnostics.seam.isFinite() && diagnostics.seam < Keys.MAX_SEAM_ERROR)
        when (diagnostics.strategy) {
            "causal" -> {
                assertEquals("CAUSAL_COLD_HELD_PULSE", source.origin)
                assertEquals(diagnostics.attempts + 1, source.selectedPeriods)
                assertTrue(probe.diagnostics.replaySourceEvents.isEmpty())
            }
            "causal-schedule-replay" -> {
                assertEquals("REPLAY_COLD_HELD_SCHEDULE", source.origin)
                assertTrue(source.selectedPeriods < diagnostics.attempts + 1, "abandoned causal periods must not enter the prefix")
                assertTrue(probe.diagnostics.replaySourceEvents.isNotEmpty())
                assertNotNull(probe.diagnostics.replaySourcePeriodStartSeconds)
            }
            else -> throw AssertionError("unknown held origin ${diagnostics.strategy}")
        }
        val oversample = Wick.Options().oversample
        assertEquals(0L, source.selectedStartNativeFrame)
        assertEquals(WavWriter.MPC_SAMPLE_RATE * oversample, source.nativeSampleRate)
        assertEquals(source.selectedPeriods.toLong() * p * oversample, source.selectedNativeFrames)
        assertTrue(source.rightGuardNativeFrames > 0 && source.rightGuardNativeFrames <= p * oversample)
        assertTrue(source.selectedPeriods >= 2)
        val start = source.note.loopStartFrame.toInt()
        val joined = source.note.snip.samples
        assertEquals((source.selectedPeriods - 1) * p, start)
        assertEquals(start + p, joined.size)
        assertEquals(1, source.note.snip.channels)
        assertEquals(WavWriter.MPC_SAMPLE_RATE, source.note.snip.sampleRate)
        assertTrue(joined.all { it.isFinite() })
        for (i in 0 until p) assertEquals(probe.samples[i].toRawBits(), joined[start + i].toRawBits(), "accepted body frame $i")
        val prefix = joined.copyOfRange(0, start)
        val differsFromRepeatedSustain = prefix.indices.count { prefix[it].toRawBits() != probe.samples[it % p].toRawBits() }
        assertTrue(differsFromRepeatedSustain > 0, "the true opening is not a repeated accepted cycle")
        val prefixPeak = prefix.maxOf { abs(it.toDouble()) }
        val prefixDc = prefix.fold(0.0) { sum, value -> sum + value } / prefix.size
        assertEquals(prefixPeak, source.prefixPeak)
        assertEquals(prefixDc, source.prefixDc)
        assertTrue(source.prefixPeak.isFinite() && source.prefixDc.isFinite())
        assertEquals(Keys.seamError(joined, start), source.joinedSeam)
        assertTrue(source.joinedSeam.isFinite() && source.joinedSeam < Keys.MAX_SEAM_ERROR)
        assertFalse(source.naturalAttack)
        assertFalse(source.nativeRelease)
        assertEquals(before, patch.toJsonText())
        val samplerHash = floatHash(joined)
        val previews = System.getProperty("snipsnap.wick.entryPreviewDir")?.let { path ->
            comparisons(source, hash(before.toByteArray()), samplerHash, File(path))
        }
        assertEquals(samplerHash, floatHash(joined), "presentation crops never alter the full source")
        val report = JsonValue.Obj(linkedMapOf(
            "kind" to JsonValue.Str("WICK_REAL_HELD_HISTORY_SOURCE"),
            "sourcePatch" to patch.toJsonValue(),
            "origin" to JsonValue.Str(source.origin),
            "strategy" to JsonValue.Str(diagnostics.strategy),
            "attemptsAllEngines" to n(diagnostics.attempts),
            "selectedPeriodsIncludingAccepted" to n(source.selectedPeriods),
            "selectedStartNativeFrame" to n(source.selectedStartNativeFrame),
            "selectedNativeFramesBeforeRightGuard" to n(source.selectedNativeFrames),
            "nativeSampleRate" to n(source.nativeSampleRate),
            "rightGuardNativeFrames" to n(source.rightGuardNativeFrames),
            "loopStartOutputFrame" to n(start), "periodOutputFrames" to n(p),
            "samplerOutputFrames" to n(joined.size),
            "prefixPeak" to n(source.prefixPeak), "prefixDc" to n(source.prefixDc),
            "prefixAllFinite" to JsonValue.Bool(true),
            "prefixFramesDifferentFromRepeatedSustain" to n(differsFromRepeatedSustain),
            "joinedSeam" to n(source.joinedSeam), "existingSeamBar" to n(Keys.MAX_SEAM_ERROR),
            "acceptedCycleSha256" to JsonValue.Str(floatHash(probe.samples)),
            "originalContextSha256" to JsonValue.Str(floatHash(probe.seamContext!!)),
            "rawCycleSha256" to JsonValue.Str(floatHash(probe.raw)),
            "prefixSha256" to JsonValue.Str(floatHash(prefix)),
            "samplerBufferSha256" to JsonValue.Str(samplerHash),
            "nativeFeatureCallsSourceAudited" to n(1), "runtimeDspCounterInstrumented" to JsonValue.Bool(false),
            "finiteInitialArticulation" to JsonValue.Bool(false), "naturalAttack" to JsonValue.Bool(false),
            "nativeRelease" to JsonValue.Bool(false), "uiOrExportAdmission" to JsonValue.Bool(false),
            "audioFilesWritten" to n(if (previews == null) 0 else 2), "playbackOrDeviceAcceptance" to JsonValue.Bool(false),
            "comparisonCropsAreProgramExports" to JsonValue.Bool(false),
            "comparisonCrops" to (previews ?: JsonValue.Str("not requested")),
        ))
        System.getProperty("snipsnap.wick.entryReport")?.let { path ->
            val file = File(path)
            check(!file.exists()) { "refuse to overwrite an entry report" }
            file.writeText(Json.write(report) + "\n")
        }
        println("WICK_REAL_HELD_HISTORY_SOURCE_OK")
    }

    /** Optional, owner-budgeted listening aids on private copies, never a source edit. */
    private fun comparisons(source: Wick.HeldSource, recipeHash: String, samplerHash: String, directory: File): JsonValue.Obj {
        val full = source.note.snip.samples
        val start = source.note.loopStartFrame.toInt()
        val count = 33075
        val fade = 176 // 4 ms at 44.1 kHz, rounded to a whole frame.
        val products = ArrayList<Pair<String, ByteArray>>()
        val crops = listOf(Triple("opening.wav", 0, false), Triple("join.wav", start - 16537, true)).map { (name, from, fadeIn) ->
            require(from >= 0 && from + count <= full.size)
            val raw = full.copyOfRange(from, from + count)
            val audition = raw.copyOf()
            for (i in 0 until fade) {
                val weight = i / (fade - 1f)
                if (fadeIn) audition[i] *= weight
                audition[audition.lastIndex - i] *= weight
            }
            // The join at offset16537 is far outside either endpoint fade.
            if (fadeIn) for (i in 16537 - 256..16537 + 256)
                assertEquals(raw[i].toRawBits(), audition[i].toRawBits())
            else for (i in 0 until count - fade) assertEquals(raw[i].toRawBits(), audition[i].toRawBits())
            val out = ByteArrayOutputStream()
            WavWriter.write(out, Snip(audition, 1, WavWriter.MPC_SAMPLE_RATE), WavWriter.BitDepth.PCM_16)
            val wav = out.toByteArray()
            assertEquals(66194, wav.size)
            val pcm = MessageDigest.getInstance("SHA-256")
            val code = ByteArray(2)
            for (sample in audition) {
                val value = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
                code[0] = value.toByte(); code[1] = (value ushr 8).toByte(); pcm.update(code)
            }
            val pcmHash = pcm.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(pcmHash, hash(wav.copyOfRange(44, wav.size)))
            products += name to wav
            JsonValue.Obj(linkedMapOf(
                "file" to JsonValue.Str(name), "fullBufferFromInclusive" to n(from), "fullBufferToExclusive" to n(from + count),
                "joinCropOffset" to n(if (fadeIn) 16537 else -1),
                "fadeInFrames" to n(if (fadeIn) fade else 0), "fadeOutFrames" to n(fade),
                "rawFloatSha256" to JsonValue.Str(floatHash(raw)), "auditionFloatSha256" to JsonValue.Str(floatHash(audition)),
                "pcm16DataSha256" to JsonValue.Str(pcmHash), "wavSha256" to JsonValue.Str(hash(wav)),
                "bytes" to n(wav.size), "clippedSamples" to n(audition.count { abs(it) > 1f }),
            ))
        }
        val manifest = JsonValue.Obj(linkedMapOf(
            "role" to JsonValue.Str("cropped listening aids; NOT native-program exports"),
            "recipeSha256" to JsonValue.Str(recipeHash), "acceptedCycleSha256" to JsonValue.Str(floatHash(source.probe.samples)),
            "samplerFloatSha256" to JsonValue.Str(samplerHash), "fullLoopStart" to n(start),
            "sampleRate" to n(WavWriter.MPC_SAMPLE_RATE), "gain" to n(1),
            "fadeCurve" to JsonValue.Str("linear, endpoint-inclusive; no full-source fade"),
            "crops" to JsonValue.Arr(crops),
        ))
        val text = Json.write(manifest) + "\n"
        require(products.sumOf { it.second.size } + text.toByteArray().size <= 135168) { "comparison crop logical budget exceeded" }
        require(!directory.exists() && directory.mkdir()) { "comparison crop directory must be fresh" }
        for ((name, bytes) in products) File(directory, name).writeBytes(bytes)
        File(directory, "manifest.json").writeText(text)
        return manifest
    }

    private fun n(value: Number) = JsonValue.Num(value.toDouble())
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun floatHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (i in bytes.indices) bytes[i] = (bits ushr (8 * i)).toByte()
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

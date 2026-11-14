package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Keys
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sampler package save/reopen qualification; no new production source-recall format. */
class WickHeldSavedSourceTest {
    @Test fun produceSavedHeldGlowFixture() {
        // Ordinary test runs need neither a property nor another test's output.
        val retained = System.getProperty("snipsnap.wick.saved.root")
        val output = retained?.let(::File) ?: createTempDirectory("wick-held-saved-").toFile()
        try {
            val root = File(output, "fixture")
            check(!root.exists()) { "refuse to overwrite a retained fixture" }
            val patch = WickPresets.forVoice(WickVoice.PLUCK).single { it.name == "Held Glow" }
            val patchText = patch.toJsonText()
            assertEquals(48, patch.midi)
            assertEquals(SOURCE, hash(patchText.toByteArray(Charsets.UTF_8)))
            val spec = WickHeldPadMaker.Spec(patch)
            // The sole new native capture, needed by this new persistence contract.
            val captured = WickHeldPadMaker.renderZone(spec)
            assertEquals(P, captured.acceptedCycle.size)
            assertEquals(SOURCE, captured.sourceSha256)
            assertEquals(CYCLE, captured.acceptedCycleSha256)
            assertEquals(CONTEXT, captured.originalContextSha256)
            assertEquals(ASSEMBLED, captured.assembledSha256)
            assertEquals("SETTLED_NATIVE_PREDECESSOR", captured.onsetPolicy)
            assertFalse(captured.naturalAttack)
            assertFalse(captured.naturalRelease)
            val (program, samples) = WickHeldPadMaker.assemble(NAME, spec, captured)
            assertEquals(expectedProgram(), program)
            val native = samples.getValue(STEM)
            assertEquals(2 * P, native.frameCount)
            for (i in 0 until P) {
                assertEquals(captured.originalSeamContext[i].toRawBits(), native.samples[i].toRawBits())
                assertEquals(captured.acceptedCycle[i].toRawBits(), native.samples[P + i].toRawBits())
            }
            OneNote.writePackage(NAME, program, samples, root, overwrite = false, spanKeyboard = false)
            // Proof provenance only: OneNote does NOT embed an editable WickPatch recipe.
            File(root, "source-patch.json").writeText(patchText)
            val reopened = reopen(root)
            val bytes = File(root, samplePath()).readBytes()
            val pcm = pcmPayload(bytes)
            var maxError = 0.0
            for (i in native.samples.indices) {
                val code = signed24(bytes, pcm.first + 3 * i)
                val expected = (native.samples[i].coerceIn(-1f, 1f).toDouble() * 8_388_607.0)
                    .roundToLong().coerceIn(-8_388_608L, 8_388_607L).toInt()
                assertEquals(expected, code, "PCM24 frame $i")
                assertEquals((code / 8_388_607f).coerceAtLeast(-1f), reopened.samples[i])
                maxError = maxOf(maxError, abs(native.samples[i].toDouble() - reopened.samples[i]))
            }
            assertTrue(maxError <= 1.2e-7, "PCM24 roundoff is not Float bit identity")
            assertEquals(CYCLE, floatHash(captured.acceptedCycle))
            assertEquals(CONTEXT, floatHash(captured.originalSeamContext))
            assertEquals(ASSEMBLED, floatHash(native.samples))
            assertEquals(patchText, patch.toJsonText())
            val provenance = JsonValue.Obj(linkedMapOf(
                "schema" to num(1), "qualification" to str("sampler package save/reopen"),
                "priorSealSha256" to str(PRIOR_SEAL), "sourceSha256" to str(SOURCE),
                "acceptedCycleSha256" to str(CYCLE), "originalContextSha256" to str(CONTEXT),
                "assembledFloatSha256" to str(ASSEMBLED), "periodFrames" to num(P),
                "onsetPolicy" to str("SETTLED_NATIVE_PREDECESSOR"),
                "naturalAttack" to JsonValue.Bool(false), "naturalRelease" to JsonValue.Bool(false), "nativeRelease" to JsonValue.Bool(false),
                "productionEditableSourceRecall" to JsonValue.Bool(false), "audioPlayback" to JsonValue.Bool(false),
                "nativeLegacyFormatImport" to JsonValue.Bool(false),
                "recipeMeaning" to str("separate proof provenance; OneNote does not embed editable WickPatch recipes"),
                "nativeCaptureCallsSourceAudited" to num(1), "runtimeDspCallCounterInstrumented" to JsonValue.Bool(false),
                "pcm24IntegerChecks" to num(2 * P), "decodedFloatMaxAbsError" to num(maxError),
                "decodedFloatSha256" to str(floatHash(reopened.samples)),
                "decodedJoinedSeam" to num(Keys.seamError(reopened.samples, P)),
                "files" to fileHashes(root, payloadPaths()),
            ))
            File(root, "proof-provenance.json").writeText(Json.write(provenance) + "\n")
            assertEquals((payloadPaths() + "proof-provenance.json").toSet(), relativeFiles(root))
            assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 1_048_576)
            println("WICK_HELD_SAVED_PRODUCER_OK")
        } finally {
            if (retained == null) output.deleteRecursively()
        }
    }

    companion object {
        private const val NAME = "WickHeld48"
        private const val STEM = "WickHeld_48"
        private const val P = 105857
        private const val SOURCE = "7eee1bd4428780df01ecd8552f5e6fe3d88728d0713562d23947f056e003ca26"
        private const val CYCLE = "6bdf6693ef15f24ccc737d550496a28846693d8b1f8c567e506e6aa8a2b13504"
        private const val CONTEXT = "090b5aa69e47b7ab92c8d5d524b887ec67fdb9b0b0293bb12e9d64551fd51600"
        private const val ASSEMBLED = CONTEXT
        private const val PRIOR_SEAL = "e7a1d34114fd92f8d7efc2f5704ba3cde532ef70870e91e1c2684df9df818ab6"

        /** Fresh JVM entrypoint: reads saved files only; no render/inspect/capture invocation. */
        @JvmStatic fun main(args: Array<String>) {
            require(args.size == 1) { "one retained proof-output root is required" }
            val output = File(args.single())
            val root = File(output, "fixture")
            val before = fileHashes(root, payloadPaths() + "proof-provenance.json")
            val provenance = Json.parse(File(root, "proof-provenance.json").readText()).obj()
            assertEquals(1, provenance.getValue("schema").int())
            assertEquals(PRIOR_SEAL, provenance.getValue("priorSealSha256").str())
            assertEquals(SOURCE, provenance.getValue("sourceSha256").str())
            assertEquals(CYCLE, provenance.getValue("acceptedCycleSha256").str())
            assertEquals(CONTEXT, provenance.getValue("originalContextSha256").str())
            assertEquals(ASSEMBLED, provenance.getValue("assembledFloatSha256").str())
            assertEquals(P, provenance.getValue("periodFrames").int())
            assertEquals("SETTLED_NATIVE_PREDECESSOR", provenance.getValue("onsetPolicy").str())
            for (key in listOf("naturalAttack", "naturalRelease", "nativeRelease", "productionEditableSourceRecall", "audioPlayback", "nativeLegacyFormatImport"))
                assertFalse(provenance.getValue(key).bool())
            assertEquals(fileHashes(root, payloadPaths()), provenance.getValue("files"))
            val reopened = reopen(root)
            assertEquals(provenance.getValue("decodedFloatSha256").str(), floatHash(reopened.samples))
            assertEquals((payloadPaths() + "proof-provenance.json").toSet(), relativeFiles(root))
            assertEquals(before, fileHashes(root, payloadPaths() + "proof-provenance.json"))
            val report = JsonValue.Obj(linkedMapOf(
                "qualification" to str("sampler package save/reopen"), "coldJvmNativeCaptureCalls" to num(0),
                "fileImport" to JsonValue.Bool(true), "savedInstrumentReopen" to JsonValue.Bool(true),
                "productionEditableSourceRecall" to JsonValue.Bool(false), "audioPlayback" to JsonValue.Bool(false),
                "naturalAttack" to JsonValue.Bool(false), "naturalRelease" to JsonValue.Bool(false), "nativeRelease" to JsonValue.Bool(false),
                "nativeLegacyFormatImport" to JsonValue.Bool(false),
                "zeroVelocityMeaning" to str("zero-gain KeyHit resolution only; no noteOff/playback claim"),
                "recipeMeaning" to provenance.getValue("recipeMeaning"),
                "rootMeaning" to str("authored MIDI48 only; no physical pitch detection or keyboard-range qualification"),
                "frames" to num(2 * P), "loopStartFrame" to num(P), "loopEndExclusive" to num(2 * P),
                "releaseSeconds" to num(.35f), "keyHitVelocityChecks" to num(4), "filesUnchanged" to before,
            ))
            val reportFile = File(output, "cold-reopen-report.json")
            check(!reportFile.exists()) { "refuse to overwrite a retained cold report" }
            reportFile.writeText(Json.write(report) + "\n")
            println("WICK_HELD_SAVED_COLD_REOPEN_OK")
        }

        private fun expectedProgram() = KeygroupProgram(NAME,
            listOf(Keygroup(48, 48, 48, listOf(VelocityLayer(STEM, 2L * P, 0, 127, P.toLong())))),
            volumeRelease = .35f, explicitNoteOn = true)

        private fun reopen(root: File): Snip {
            for (path in payloadPaths()) assertTrue(File(root, path).isFile, "real saved file $path")
            val patchText = File(root, "source-patch.json").readText()
            assertEquals(SOURCE, hash(patchText.toByteArray(Charsets.UTF_8)))
            val patch = WickPatch.fromJsonText(patchText)
            assertEquals(Json.parse(patchText), patch.toJsonValue())
            assertEquals(patchText, patch.toJsonText())
            assertEquals(WickVoice.PLUCK, patch.voice)
            assertEquals(48, patch.midi)
            val instrument = InstrumentStore.read(InstrumentStore.sidecar(root, NAME))
            assertEquals(expectedProgram(), instrument.toProgram())
            assertEquals(listOf(samplePath()), instrument.sampleFiles)
            val wav = File(root, samplePath())
            val bytes = wav.readBytes()
            pcmPayload(bytes)
            val snip = WavReader.read(wav)
            assertEquals(1, snip.channels)
            assertEquals(WavWriter.MPC_SAMPLE_RATE, snip.sampleRate)
            assertEquals(2 * P, snip.frameCount)
            assertTrue(snip.samples.all { it.isFinite() })
            val sheet = assertNotNull(WavReader.readSmplStrict(wav))
            assertEquals(48, sheet.rootNote)
            assertEquals(P.toLong(), assertNotNull(sheet.loop).startFrame)
            assertEquals(2L * P, sheet.loop!!.endFrameExclusive)
            assertTrue(Keys.seamError(snip.samples, P) < Keys.MAX_SEAM_ERROR)
            val framesOf: (String) -> Long? = { if (it == samplePath()) snip.frameCount.toLong() else null }
            for (velocity in listOf(0f, .30f, .65f, 1f)) {
                val hit = assertNotNull(KeyHit.resolve(instrument, 48, velocity, framesOf))
                assertEquals(samplePath(), hit.sampleFile)
                assertEquals(2L * P, hit.frames)
                assertEquals(P.toLong(), hit.loopStartFrame)
                assertTrue(hit.loops)
                assertEquals(1.0, hit.pitchRatio)
                assertEquals(velocity * InstrumentEngine.VOICE_LEVEL, hit.gain)
            }
            assertNull(KeyHit.resolve(instrument, 47, .65f, framesOf))
            assertNull(KeyHit.resolve(instrument, 49, .65f, framesOf))
            assertNull(KeyHit.resolve(instrument, 48, .65f) { null })
            assertEquals(.35f, instrument.playableRelease)
            assertEquals(350f, KeyHit.releaseMs(instrument))
            assertTrue(instrument.explicitNoteOn)
            assertFalse(instrument.stackZones)
            assertContentEquals(Mpc3TrackWriter().writeKeygroup(expectedProgram()), File(root, "$NAME.xty").readBytes())
            assertEquals(KeygroupWriter().write(expectedProgram()), File(root, "${dataDir()}/$NAME.xpm").readText())
            return snip
        }

        /** Independent RIFF walk checks complete PCM24 data and inclusive on-disk smpl coordinates. */
        private fun pcmPayload(bytes: ByteArray): Pair<Int, Int> {
            fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
            fun int(at: Int): Int = (0..3).fold(0) { n, i -> n or ((bytes[at + i].toInt() and 255) shl (8 * i)) }
            fun short(at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
            assertEquals("RIFF", tag(0)); assertEquals(bytes.size - 8, int(4)); assertEquals("WAVE", tag(8))
            var at = 12
            var dataAt = -1
            val chunks = mutableListOf<String>()
            while (at + 8 <= bytes.size) {
                val id = tag(at); val size = int(at + 4); val body = at + 8
                assertTrue(size >= 0 && body.toLong() + size <= bytes.size)
                chunks += id
                when (id) {
                    "fmt " -> {
                        assertEquals(16, size); assertEquals(1, short(body)); assertEquals(1, short(body + 2))
                        assertEquals(WavWriter.MPC_SAMPLE_RATE, int(body + 4)); assertEquals(3, short(body + 12)); assertEquals(24, short(body + 14))
                    }
                    "smpl" -> {
                        assertEquals(60, size); assertEquals(48, int(body + 12)); assertEquals(1, int(body + 28))
                        assertEquals(P, int(body + 44)); assertEquals(2 * P - 1, int(body + 48))
                    }
                    "data" -> { assertEquals(2 * P * 3, size); dataAt = body }
                }
                at = body + size + (size and 1)
            }
            assertEquals(bytes.size, at)
            assertEquals(listOf("fmt ", "smpl", "data"), chunks)
            assertTrue(dataAt > 0)
            return dataAt to 2 * P
        }

        private fun signed24(bytes: ByteArray, at: Int): Int {
            val n = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or ((bytes[at + 2].toInt() and 255) shl 16)
            return if (n and 0x800000 == 0) n else n or -0x1000000
        }
        private fun dataDir() = Mpc3TrackWriter.trackDataDirName(NAME)
        private fun samplePath() = "${dataDir()}/$STEM.wav"
        private fun payloadPaths() = listOf("$NAME.xty", "$NAME${InstrumentStore.SUFFIX}", "${dataDir()}/$NAME.xpm", samplePath(), "source-patch.json")
        private fun relativeFiles(root: File) = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        private fun fileHashes(root: File, paths: List<String>) = JsonValue.Obj(linkedMapOf<String, JsonValue>().apply {
            for (path in paths) put(path, str(hash(File(root, path).readBytes())))
        })
        private fun str(value: String) = JsonValue.Str(value)
        private fun num(value: Number) = JsonValue.Num(value.toDouble())
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun floatHash(samples: FloatArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(4)
            for (sample in samples) {
                val bits = sample.toRawBits()
                for (i in bytes.indices) bytes[i] = (bits ushr (8 * i)).toByte()
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

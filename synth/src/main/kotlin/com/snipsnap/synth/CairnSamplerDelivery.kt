package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.OneNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Source-preserving sampler prototype. It never calls an engine renderer or decodes a source WAV. */
object CairnSamplerDelivery {
    const val RATE = WavWriter.MPC_SAMPLE_RATE
    const val FULL_FRAMES = 176_400
    const val LOOP_START = 88_200
    const val RELEASE_SECONDS = .08f
    const val CAPTURE_BYTES = FULL_FRAMES * 4
    const val MAX_CAPTURE_METADATA_BYTES = 65_536
    const val POLICY_JSON = """{"schema":1,"id":"cairn-sampler-delivery-candidate-v1","status":"pending listening","source":"retained new native full Float proof capture","earlierMovingCoverage":"sampler transposition48..72 only;48..53root48,54..65root60,66..72root72; upper tie;max6semitones;pending listening","grooveCoverage":"fixed C4 study only; partial register","introPolicySha256":"853c18236fa2c64400ac1815d5194e9aa7da345167c855034254d2c6d7454470","samplerReleaseSeconds":0.08,"releaseMeaning":"JVM linear sample-player level after note-off; same numeric MPC envelope value; physical timing unmeasured","nativeVelocity":1,"samplerVelocity":"shared gain only; no new native touch layers","naturalAttack":false,"naturalRelease":false,"factoryOrPickerAdmission":false}"""
    val policySha256: String get() = sha(POLICY_JSON.toByteArray(Charsets.UTF_8))

    data class Spec(val id: String, val name: String, val sourceIds: List<String>)
    val specs: List<Spec> = listOf(
        Spec("earlier-c3-c5", "CAIRN Earlier C3 C5", listOf("cairn-before-48", "cairn-before-60", "cairn-before-72")),
        Spec("moving-c3-c5", "CAIRN Moving C3 C5", listOf("cairn-after-48", "cairn-after-60", "cairn-after-72")),
        Spec("groove-0", "CAIRN Groove 0 C4", listOf("cairn-groove-0")),
        Spec("groove-50", "CAIRN Groove 50 C4", listOf("cairn-groove-50")),
        Spec("groove-100", "CAIRN Groove 100 C4", listOf("cairn-groove-100")),
    )

    class Capture internal constructor(
        val source: CairnAcceptedSingSources.Source,
        val captureSha256: String,
        val metadataSha256: String,
        private val context: CairnAcceptedSingContext.Context,
    ) {
        val rootMidi: Int get() = context.effectiveMidi
        fun originalFull(): Snip = context.fullNativeContext()
        fun originalCycle(): Snip = context.acceptedCycle()
        fun delivered(): Snip = CairnAcceptedSingDelivery.withSettledPrefixIntro(context).samples()
    }

    /** Only the pinned new f32le capture is decoded here; source PCM/WAV/FLAC is never an input. */
    fun readCapture(sourceId: String, capture: File, metadata: File,
        expectedCaptureSha256: String, expectedMetadataSha256: String): Capture {
        val source = requireNotNull(CairnAcceptedSingSources.byId(sourceId)) { "Unknown accepted CAIRN source" }
        require(capture.isFile && metadata.isFile && capture.length() == FULL_FRAMES * 4L)
        val capturedBytes = capture.readBytes()
        val metadataBytes = metadata.readBytes()
        return checkedCapture(source, capturedBytes, metadataBytes, expectedCaptureSha256, expectedMetadataSha256)
    }

    /**
     * Bounded stream counterpart of the File API with the same source, recipe,
     * full/cycle PCM and metadata checks. Streams are borrowed; their opener
     * owns closure, including when opening the second stream fails.
     * No renderer, source WAV decode or workspace fallback is used.
     */
    fun readCapture(sourceId: String, capture: InputStream, metadata: InputStream,
        expectedCaptureSha256: String, expectedMetadataSha256: String,
        cancelled: () -> Boolean = { false }): Capture {
        val source = requireNotNull(CairnAcceptedSingSources.byId(sourceId)) { "Unknown accepted CAIRN source" }
        stopCapture(cancelled)
        val capturedBytes = readCaptureBytes(capture, CAPTURE_BYTES, cancelled)
        require(capturedBytes.size == CAPTURE_BYTES) { "Truncated CAIRN native Float capture" }
        val metadataBytes = readCaptureBytes(metadata, MAX_CAPTURE_METADATA_BYTES, cancelled)
        stopCapture(cancelled)
        val result = checkedCapture(source, capturedBytes, metadataBytes,
            expectedCaptureSha256, expectedMetadataSha256)
        stopCapture(cancelled)
        return result
    }

    private fun checkedCapture(source: CairnAcceptedSingSources.Source,
        capturedBytes: ByteArray, metadataBytes: ByteArray,
        expectedCaptureSha256: String, expectedMetadataSha256: String): Capture {
        require(sha(capturedBytes) == expectedCaptureSha256 && sha(metadataBytes) == expectedMetadataSha256) {
            "Retained native Float capture or metadata changed"
        }
        val m = Json.parse(metadataBytes.toString(Charsets.UTF_8)).obj()
        val patch = source.patch()
        require(m.getValue("sourceId").str() == source.id && m.getValue("sourceModel").int() == patch.model)
        require(m.getValue("midi").int() == patch.midi && m.getValue("effectiveMidi").int() == patch.midi)
        require(m.getValue("velocity").num().toFloat() == patch.velocity && m.getValue("startAtContact").bool() == patch.startAtContact)
        require(m.getValue("originalRecipeSha256").str() == source.originalRecipe.sha256)
        require(m.getValue("savedPatch") == Json.parse(source.originalRecipeJson())) { "Capture lost its full model-aware source recipe" }
        require(m.getValue("deliveryIntroApplied").bool().not() && m.getValue("historicalOriginalFloatClaimed").bool().not())
        require(m.getValue("captureSha256").str() == expectedCaptureSha256 &&
            !m.getValue("naturalAttack").bool() && !m.getValue("naturalRelease").bool())
        require(m.getValue("frames").int() == FULL_FRAMES && m.getValue("fullLoopStartFrame").int() == LOOP_START &&
            m.getValue("fullOutputStartFrame").int() == LOOP_START && m.getValue("croppedCycleLoopStartFrame").int() == 0)
        val raw = ByteBuffer.wrap(capturedBytes).order(ByteOrder.LITTLE_ENDIAN)
        val full = FloatArray(FULL_FRAMES) { raw.float }
        require(full.all { it.isFinite() && it in -1f..1f })
        val cycle = full.copyOfRange(LOOP_START, full.size)
        require(sha(pcm24(full)) == source.fullPcmS24leSha256 && sha(pcm24(cycle)) == source.cyclePcmS24leSha256) {
            "Retained Float capture no longer encodes both accepted native PCM anchors"
        }
        val context = CairnAcceptedSingContext.mapBuffers(source, patch.midi, full, cycle, LOOP_START, LOOP_START)
        return Capture(source, expectedCaptureSha256, expectedMetadataSha256, context)
    }

    private fun readCaptureBytes(stream: InputStream, limit: Int, cancelled: () -> Boolean): ByteArray {
        val output = ByteArrayOutputStream(limit)
        val buffer = ByteArray(8192)
        while (true) {
            stopCapture(cancelled)
            // At most one byte beyond the limit distinguishes a complete input
            // from an otherwise valid capture with a suffix.
            val requested = minOf(buffer.size, limit - output.size() + 1)
            val count = stream.read(buffer, 0, requested)
            if (count < 0) break
            if (count == 0) {
                // A custom zero-returning stream must not spin or bypass the cap.
                stopCapture(cancelled)
                val one = stream.read()
                if (one < 0) break
                require(output.size() < limit) { "CAIRN capture input exceeds its byte limit" }
                output.write(one)
            } else {
                require(count <= limit - output.size()) { "CAIRN capture input exceeds its byte limit" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun stopCapture(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("CAIRN retained capture read cancelled")
    }

    fun stem(sourceId: String): String = "sing_" + sourceId.removePrefix("cairn-").replace('-', '_')

    data class NoteZone(val low: Int, val high: Int, val root: Int)
    fun noteZone(spec: Spec, root: Int): NoteZone = if (spec.id in listOf("earlier-c3-c5", "moving-c3-c5")) when (root) {
        48 -> NoteZone(48, 53, 48)
        60 -> NoteZone(54, 65, 60)
        72 -> NoteZone(66, 72, 72)
        else -> error("Unsupported source root")
    } else {
        require(root == 60)
        NoteZone(60, 60, 60)
    }
    fun coverageLabel(spec: Spec): String = if (spec.sourceIds.size == 3)
        "chromatic sampler transposition48..72; three native captured roots; pending listening"
        else "fixed C4 study only; partial register"

    /** Root-approved nearest-source mapping: upper tie, maximum six semitones, no outside notes. */
    fun assemble(spec: Spec, captures: Map<String, Capture>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(specs.any { it == spec }) { "Choose one of the five fixed sampler candidates" }
        val chosen = spec.sourceIds.map { requireNotNull(captures[it]) }
        require(chosen.map { it.rootMidi }.distinct().size == chosen.size)
        val samples = chosen.associate { stem(it.source.id) to it.delivered() }
        val keygroups = chosen.map {
            val zone = noteZone(spec, it.rootMidi)
            Keygroup(zone.low, zone.high, zone.root,
                listOf(VelocityLayer(stem(it.source.id), FULL_FRAMES.toLong(), 0, 127, LOOP_START.toLong())))
        }
        return KeygroupProgram(spec.name, keygroups, volumeRelease = RELEASE_SECONDS,
            explicitNoteOn = true, stackZones = false) to samples
    }

    /** A new isolated proof output only. No shelf/picker registration or overwrite is provided. */
    fun writePackage(spec: Spec, captures: Map<String, Capture>, destination: File): KeygroupProgram {
        val (program, samples) = assemble(spec, captures)
        OneNote.writePackage(spec.name, program, samples, destination, overwrite = false, spanKeyboard = false)
        val originalRecipes = File(destination, spec.name + " Sources").apply { mkdirs() }
        val sourceRows = spec.sourceIds.map { id ->
            val c = captures.getValue(id)
            val recipeFile = File(originalRecipes, c.source.recordingId + ".patch.json")
            AtomicFile.writeText(recipeFile, c.source.originalRecipeJson())
            mapOf("sourceId" to id, "model" to c.source.model, "rootMidi" to c.rootMidi,
                "lowNote" to noteZone(spec, c.rootMidi).low, "highNote" to noteZone(spec, c.rootMidi).high,
                "originalRecipeSha256" to c.source.originalRecipe.sha256,
                "originalRecipeFile" to (originalRecipes.name + "/" + recipeFile.name),
                "savedPatch" to Json.parse(c.source.originalRecipeJson()),
                "newNativeFullFloatSha256" to c.captureSha256, "captureMetadataSha256" to c.metadataSha256,
                "sourceCyclePcmS24leSha256" to c.source.cyclePcmS24leSha256,
                "sourceFullPcmS24leSha256" to c.source.fullPcmS24leSha256,
                "sampleStem" to stem(id), "fullFrames" to FULL_FRAMES, "loopStartFrame" to LOOP_START)
        }
        AtomicFile.writeText(File(destination, spec.name + ".source.json"), Json.write(json(mapOf(
            "schema" to 1, "kind" to "CAIRN SING sampler-delivery candidate; pending listening",
            "programId" to spec.id, "coverage" to coverageLabel(spec), "sources" to sourceRows,
            "sourcePatchAndDeliveryPolicySeparate" to true, "samplerReleaseFloatSeconds" to RELEASE_SECONDS,
            "deliveryPolicySha256" to policySha256,
            "introPolicySha256" to CairnAcceptedSingDelivery.policySha256,
            "naturalAttack" to false, "naturalRelease" to false))))
        AtomicFile.writeText(File(destination, spec.name + ".delivery-policy.json"), POLICY_JSON)
        return program
    }

    /** RIFF data walker, so smpl metadata never turns into a wrong fixed byte offset. */
    fun wavPcmData(bytes: ByteArray): ByteArray {
        require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.size) {
            val size = b.getInt(at + 4)
            require(size >= 0 && at.toLong() + 8 + size <= bytes.size)
            if (String(bytes, at, 4, Charsets.US_ASCII) == "data") return bytes.copyOfRange(at + 8, at + 8 + size)
            at += 8 + size + (size and 1)
        }
        error("Missing WAV data chunk")
    }

    fun pcm24(samples: FloatArray): ByteArray = wavPcmData(ByteArrayOutputStream().also {
        WavWriter.write(it, Snip(samples, 1, RATE))
    }.toByteArray())
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun floatSha(samples: FloatArray): String = sha(ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .also { out -> samples.forEach { out.putInt(it.toRawBits()) } }.array())
    fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is List<*> -> JsonValue.Arr(value.map { json(it) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        else -> error("Unsupported sampler JSON value")
    }
}

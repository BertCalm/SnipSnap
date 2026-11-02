package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ConfluencePersonalFiles
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Confluence
import com.snipsnap.synth.ConfluencePersonalVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A personal recording becomes an immutable voice, then a separate ordinary rendered pad.
 * Frame coordinates always belong to the untreated mono 44.1 kHz take. The decoded raw
 * input is retained separately so downmixing cannot erase evidence of clipped channels.
 * CPU preparation happens before the caller takes its kit-write mutex; makePad only
 * validates the saved source and empty destination, carries its source closure and assigns.
 */
object ConfluencePersonalWorkshop {
    const val LIBRARY_DIR = ConfluencePersonalLibrary.DIR
    const val ENGINE = "CONFLUENCE_PERSONAL"
    private const val RECIPE_VERSION = 1
    val previewLevel: Float = KitPad(1, "preview.wav").level

    enum class Mode { ORIGINAL, SOLO, ENSEMBLE }

    /** Only memory copies are returned: audition clients cannot change what SAVE will use. */
    class Prepared internal constructor(
        private val raw: Snip,
        private val canonical: Snip,
        val startFrame: Int,
        val endFrame: Int,
        internal val voice: ConfluencePersonalVoice.PreparedVoice,
    ) {
        val assessment: ConfluencePersonalVoice.Assessment get() = voice.assessment
        val seconds: Float get() = (endFrame - startFrame).toFloat() / Confluence.RATE
        val original: Snip get() = Snip(canonical.samples.copyOfRange(startFrame, endFrame), 1, Confluence.RATE)
        internal fun rawCopy(): Snip = raw.snapshot()
    }

    class PreparedPad internal constructor(
        internal val voiceId: String,
        internal val manifestHash: String,
        internal val pcmHash: String,
        internal val sourceFileHash: String,
        internal val metadata: Map<String, String>,
        private val rendered: Snip,
        val mode: Mode,
    ) {
        val snip: Snip get() = rendered.snapshot()
        val previewLevel: Float get() = ConfluencePersonalWorkshop.previewLevel
    }

    /** Downmix/resample only. Cleanup, gain normalization and repair must not precede assessment. */
    fun canonicalTake(raw: Snip): Snip {
        require(raw.sampleRate in 8_000..192_000 && raw.channels in 1..2) {
            "Choose a mono or stereo recording at a supported sample rate."
        }
        require(raw.frameCount > 0 &&
            raw.frameCount.toLong() <= raw.sampleRate.toLong() * ConfluencePersonalVoice.MAX_SOURCE_SECONDS) {
            "Choose a recording of thirty seconds or less."
        }
        require(raw.samples.size.toLong() * 4 + 44 <= ConfluencePersonalLibrary.MAX_RAW_BYTES) {
            "This recording is too large for a personal voice. Choose a shorter take or a lower sample rate."
        }
        require(raw.samples.all { it.isFinite() }) { "The recording contains invalid audio samples." }
        val mono = if (raw.channels == 1) raw.snapshot() else Cleanup.toMono(raw.snapshot())
        val canonical = if (mono.sampleRate == Confluence.RATE) mono else Resampler.resample(mono, Confluence.RATE)
        require(canonical.frameCount <= Confluence.RATE * ConfluencePersonalVoice.MAX_SOURCE_SECONDS) {
            "Choose a recording of thirty seconds or less."
        }
        return canonical
    }

    fun assess(raw: Snip, startFrame: Int = 0, endFrame: Int? = null): ConfluencePersonalVoice.Assessment {
        val canonical = canonicalTake(raw)
        return assessment(raw, canonical, startFrame, endFrame ?: canonical.frameCount)
    }

    /**
     * Quantize the selected processor source exactly as the durable WAV will be written.
     * A nonzero crop consequently has the same zero-based source identity and performer seed
     * before SAVE and after reopening. The original preview still uses the untreated selection.
     */
    fun prepare(raw: Snip, startFrame: Int = 0, endFrame: Int? = null): Prepared {
        val rawCopy = raw.snapshot()
        val canonical = canonicalTake(rawCopy)
        val end = endFrame ?: canonical.frameCount
        val checked = assessment(rawCopy, canonical, startFrame, end)
        require(checked.usable) { checked.message }
        val selected = Snip(canonical.samples.copyOfRange(startFrame, end), 1, Confluence.RATE)
        val persistedEquivalent = pcm24(selected)
        val voice = ConfluencePersonalVoice.prepare(persistedEquivalent)
        return Prepared(rawCopy, canonical, startFrame, end, voice)
    }

    fun preview(prepared: Prepared, mode: Mode): Snip = when (mode) {
        Mode.ORIGINAL -> prepared.original
        Mode.SOLO -> ConfluencePersonalVoice.render(prepared.voice, performers = 1, room = 0f).snip
        Mode.ENSEMBLE -> ConfluencePersonalVoice.render(prepared.voice, performers = 4, room = .15f).snip
    }

    fun saveVoice(
        libraryDir: File,
        prepared: Prepared,
        name: String,
        origin: ConfluencePersonalLibrary.Origin = ConfluencePersonalLibrary.Origin.RECORDING,
    ): ConfluencePersonalLibrary.StoredVoice = ConfluencePersonalLibrary.save(
        libraryDir, name, prepared.rawCopy(), prepared.startFrame, prepared.endFrame,
        prepared.voice.referenceMidi, origin,
    )

    fun listVoices(libraryDir: File): List<ConfluencePersonalLibrary.Summary> = ConfluencePersonalLibrary.list(libraryDir)

    fun loadVoice(libraryDir: File, id: String): ConfluencePersonalLibrary.StoredVoice =
        ConfluencePersonalLibrary.load(libraryDir, id)

    fun openVoice(libraryDir: File, id: String): Prepared = preparedFromStored(loadVoice(libraryDir, id))

    /** A restored kit's own source is editable even when this phone has no separate library copy. */
    fun openVoice(voice: ConfluencePersonalLibrary.StoredVoice): Prepared = preparedFromStored(voice)

    /** A returned voice resolves only from the kit's own exact personal closure, never a factory fallback. */
    fun openPad(kitDir: File, pad: KitPad): ConfluencePersonalLibrary.StoredVoice {
        require(pad.source["engine"] == ENGINE) { "Choose a saved personal voice pad." }
        val id = requireNotNull(pad.source["personalVoiceId"]) { "The personal voice reference is missing." }
        val voice = ConfluencePersonalLibrary.loadFromKit(kitDir, id)
        verifyBinding(voice, pad.source)
        savedMode(pad)
        return voice
    }

    fun savedMode(pad: KitPad): Mode {
        require(pad.source["engine"] == ENGINE) { "Choose a saved personal voice pad." }
        val text = requireNotNull(pad.source["personalRecipe"]) { "The personal voice recipe is missing." }
        require(text.length <= 4096) { "The personal voice recipe is too large." }
        try {
            val o = Json.parse(text).obj()
            require(o.getValue("schemaVersion").int() == RECIPE_VERSION &&
                o.getValue("analysisVersion").int() == ConfluencePersonalVoice.ANALYSIS_VERSION) {
                "This personal voice recipe uses a different preparation version."
            }
            val mode = Mode.valueOf(o.getValue("mode").str())
            require(text == recipeText(mode)) { "The personal voice recipe settings have changed. Open the source again." }
            return mode
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("The personal voice recipe cannot be reopened: ${e.message.orEmpty()}", e)
        }
    }

    /** Read/qualify and render off the UI thread, before entering the kit write lock. */
    fun preparePad(voice: ConfluencePersonalLibrary.StoredVoice, mode: Mode = Mode.ENSEMBLE): PreparedPad {
        // A stale StoredVoice object may point to files replaced since it was opened.
        val current = ConfluencePersonalLibrary.load(voice.directory.parentFile, voice.id)
        require(current.manifestText == voice.manifestText) { "The saved voice changed. Open it again before previewing." }
        val prepared = preparedFromStored(current)
        val rendered = preview(prepared, mode)
        val manifestHash = fileHash(current.manifestFile)
        val fileHash = fileHash(current.sourceFile)
        val metadata = linkedMapOf(
            "engine" to ENGINE,
            "personalVoiceId" to current.id,
            "personalVoiceName" to current.name,
            "personalVoiceManifestSha256" to manifestHash,
            "personalSourceSha256" to current.source.hash,
            "personalSourceFileSha256" to fileHash,
            "sourceHashes" to current.source.hash,
            "personalRecipe" to recipeText(mode),
            "referenceMidi" to current.region.referenceMidi.toString(),
            "rootMidi" to prepared.voice.rootMidi.toString(),
            "origin" to current.origin.name,
        )
        return PreparedPad(current.id, manifestHash, current.source.hash, fileHash, metadata, rendered.snapshot(), mode)
    }

    /** Caller owns the kit-write mutex and saves the fresh model after this returns. */
    fun makePad(
        fresh: KitBuilderModel,
        voice: ConfluencePersonalLibrary.StoredVoice,
        prepared: PreparedPad,
        targetSlot: Int,
    ): KitPad {
        require(targetSlot in 1..128 && fresh.pad(targetSlot) == null) {
            "That pad is now occupied. Choose another empty pad."
        }
        require(voice.id == prepared.voiceId) { "Preview this personal voice again before making a pad." }
        val current = ConfluencePersonalLibrary.load(voice.directory.parentFile, voice.id)
        require(fileHash(current.manifestFile) == prepared.manifestHash && current.source.hash == prepared.pcmHash &&
            fileHash(current.sourceFile) == prepared.sourceFileHash) {
            "The saved voice changed. Preview it again before making a pad."
        }
        verifyBinding(current, prepared.metadata)
        ConfluencePersonalFiles.requireCapacity(
            fresh.kit, fresh.kitDir, current.id,
            current.manifestFile.length() + current.rawFile.length() + current.sourceFile.length(),
        )
        ConfluencePersonalLibrary.copyToKit(current, fresh.kitDir)
        val rendered = prepared.snip
        val kind = if (rendered.frameCount > Confluence.RATE * 1.5) DrumClass.LOOP else DrumClass.PERC
        val title = when (prepared.mode) { Mode.ORIGINAL -> "ORIGINAL"; Mode.SOLO -> "SOLO"; Mode.ENSEMBLE -> "ENSEMBLE" }
        fresh.assign(targetSlot, rendered, kind, "${current.name} $title", prepared.metadata)
        return fresh.update(targetSlot) { it.copy(level = prepared.previewLevel, oneShot = true) }
    }

    private fun preparedFromStored(voice: ConfluencePersonalLibrary.StoredVoice): Prepared {
        val raw = voice.rawSnip
        val canonical = canonicalTake(raw)
        val prepared = voice.prepared
        require(prepared.sourceHash == voice.source.hash && prepared.startFrame == 0 && prepared.endFrame == voice.sourceSnip.frameCount) {
            "The saved personal voice region changed. Prepare it again."
        }
        return Prepared(raw, canonical, voice.selectionStartFrame, voice.selectionEndFrame, prepared)
    }

    private fun verifyBinding(voice: ConfluencePersonalLibrary.StoredVoice, source: Map<String, String>) {
        require(source["personalVoiceId"] == voice.id && source["personalVoiceManifestSha256"] == fileHash(voice.manifestFile) &&
            source["personalSourceSha256"] == voice.source.hash && source["sourceHashes"] == voice.source.hash &&
            source["personalSourceFileSha256"] == fileHash(voice.sourceFile) &&
            source["referenceMidi"] == voice.region.referenceMidi.toString()) {
            "The saved personal voice does not match this pad. Restore its original source files."
        }
    }

    private fun assessment(raw: Snip, canonical: Snip, start: Int, end: Int): ConfluencePersonalVoice.Assessment {
        val result = ConfluencePersonalVoice.assess(canonical, start, end)
        if (start < 0 || end <= start || end > canonical.frameCount) return result
        val from = floor(start.toDouble() * raw.sampleRate / Confluence.RATE).toInt().coerceAtLeast(0)
        val to = ceil(end.toDouble() * raw.sampleRate / Confluence.RATE).toInt().coerceAtMost(raw.frameCount)
        val clipped = raw.samples.copyOfRange(from * raw.channels, to * raw.channels).count {
            kotlin.math.abs(it) >= ConfluencePersonalVoice.CLIP_LEVEL
        }
        if (clipped == 0 || result.issues.any { it.code == ConfluencePersonalVoice.Problem.CLIPPED }) return result
        val issue = ConfluencePersonalVoice.Issue(ConfluencePersonalVoice.Problem.CLIPPED,
            "The selected recording reaches clipping level. Record again a little farther from the microphone.")
        return result.copy(issues = listOf(issue) + result.issues, clippedSamples = clipped)
    }

    private fun recipeText(mode: Mode): String {
        fun n(value: Number) = JsonValue.Num(value.toDouble())
        return Json.write(JsonValue.Obj(linkedMapOf(
            "schemaVersion" to n(RECIPE_VERSION),
            "analysisVersion" to n(ConfluencePersonalVoice.ANALYSIS_VERSION),
            "mode" to JsonValue.Str(mode.name),
            "performers" to n(if (mode == Mode.ENSEMBLE) 4 else 1),
            "semitones" to n(0),
            "individuality" to n(.2f),
            "room" to n(if (mode == Mode.ENSEMBLE) .15f else 0f),
        )))
    }

    private fun pcm24(snip: Snip): Snip = WavReader.read(ByteArrayOutputStream().apply { WavWriter.write(this, snip) }.toByteArray())
    private fun Snip.snapshot() = Snip(samples.copyOf(), channels, sampleRate)
    private fun fileHash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

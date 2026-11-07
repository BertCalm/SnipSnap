package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainPresets
import com.snipsnap.synth.ScannedChainVoice
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.VoiceMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * A finite Short Pluck keyboard bank. Each touch changes the existing physical source;
 * no sustain renderer, loop or new source model is introduced. The source owns its
 * complete attack and ending fade. Key-up adds only the player's short release.
 */
object ScannedChainKeysMaker {
    const val VERSION = 1
    const val DEFAULT_NAME = "SCANNED SHORT PLUCK"
    const val RELEASE_SECONDS = .05f
    const val PATCH_FILE = "scanned-chain.patch.json"
    const val MANIFEST_FILE = "scanned-chain-keys.json"
    const val RECIPE_DIRECTORY = "recipes"
    val TOUCHES: List<Float> = listOf(.30f, .65f, 1f)
    val VELOCITY_WINDOWS: List<IntRange> = listOf(0..42, 43..84, 85..127)
    private val ROOTS = (36..72 step 3).toList()

    /** Source edits stay editable PLUCK recipes; pitch and physical touch are supplied by the bank. */
    data class Spec(val patch: ScannedChainPatch = shortPluck(), val releaseSeconds: Float = RELEASE_SECONDS) {
        init {
            require(patch.voice == ScannedChainVoice.PLUCK) { "Scanned Chain Short Pluck keys require a PLUCK source" }
            require(releaseSeconds.isFinite() && releaseSeconds in 0f..0.25f) {
                "Scanned Chain keys note-off release must be 0..0.25 seconds"
            }
        }
    }

    /** A recipe and its host-ready finite capture, in native root and soft-to-hard order. */
    data class Capture(val patch: ScannedChainPatch, val snip: Snip)

    fun shortPluck(): ScannedChainPatch = ScannedChainPresets.forVoice(ScannedChainVoice.PLUCK)
        .single { it.name == "Short Pluck" }

    fun zoneMidis(spec: Spec): List<Int> = ROOTS

    fun sourcePatch(spec: Spec, midi: Int, touch: Float): ScannedChainPatch {
        require(midi in ROOTS) { "Scanned Chain keys root must be one of $ROOTS" }
        require(touch in TOUCHES) { "Scanned Chain keys physical touch must be one of $TOUCHES" }
        return spec.patch.copy(midi = midi, velocity = touch)
    }

    /**
     * Cancellation is checked between finite captures; source rendering also responds to
     * thread interruption throughout its solver. Never retain a partial pitch zone.
     */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<Capture> {
        require(midi in ROOTS) { "Scanned Chain keys root must be one of $ROOTS" }
        return TOUCHES.map { touch ->
            checkCancelled(cancelled)
            val patch = sourcePatch(spec, midi, touch)
            val source = patch.render()
            checkCancelled(cancelled)
            Capture(patch, hostSample(source, touch))
        }.also { checkCancelled(cancelled) }
    }

    /**
     * ScannedChain.render finishes with both its loudness target and ceiling multiplied
     * by physical velocity. Remove that scalar once, leaving all physical differences
     * intact, because the phone and MPC apply the player's note-velocity gain themselves.
     * This is not another peak or loudness match.
     */
    internal fun hostSample(source: Snip, physicalVelocity: Float): Snip {
        require(physicalVelocity in TOUCHES) { "unsupported physical touch $physicalVelocity" }
        require(source.channels == 1 && source.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
            "Scanned Chain keys captures must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
        }
        require(source.frameCount > 1 && source.samples.all { it.isFinite() && it in -1f..1f }) {
            "Scanned Chain keys source is empty, nonfinite or out of range"
        }
        val samples = FloatArray(source.samples.size) { source.samples[it] / physicalVelocity }
        require(samples.all { it.isFinite() && it in -1f..1f }) {
            "Scanned Chain keys velocity removal exceeds sample headroom"
        }
        return Snip(samples, source.channels, source.sampleRate)
    }

    fun assemble(name: String, spec: Spec, notes: List<List<Capture>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name must be MPC-safe and at most ${OneNote.MAX_NAME_LENGTH} characters: '$name'"
        }
        require(notes.size == ROOTS.size) { "Scanned Chain keys need ${ROOTS.size} pitch zones, got ${notes.size}" }
        val expectedFrames = (spec.patch.config.seconds * WavWriter.MPC_SAMPLE_RATE).roundToInt()
        val samples = linkedMapOf<String, Snip>()
        val prefix = name.filterNot(Char::isWhitespace)
        val groups = ROOTS.mapIndexed { index, midi ->
            require(notes[index].size == TOUCHES.size) { "Scanned Chain root $midi needs three physical touches" }
            val layers = notes[index].mapIndexed { touchIndex, capture ->
                require(capture.patch == sourcePatch(spec, midi, TOUCHES[touchIndex])) {
                    "Scanned Chain root $midi touch ${touchIndex + 1} does not match its exact source recipe"
                }
                val snip = capture.snip
                require(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) {
                    "Scanned Chain root $midi must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz"
                }
                require(snip.frameCount == expectedFrames && snip.frameCount > 1) {
                    "Scanned Chain root $midi needs the complete finite source ($expectedFrames frames)"
                }
                require(snip.samples.all { it.isFinite() && it in -1f..1f }) {
                    "Scanned Chain root $midi has nonfinite or out-of-range audio"
                }
                val stem = Names.sanitizeStem("${prefix}_${midi}_v${touchIndex + 1}")
                require(samples.put(stem, snip) == null) { "duplicate Scanned Chain sample name '$stem'" }
                val window = VELOCITY_WINDOWS[touchIndex]
                VelocityLayer(stem, snip.frameCount.toLong(), window.first, window.last, loopStartFrame = 0)
            }
            Keygroup(maxOf(ROOTS.first(), midi - 1), minOf(ROOTS.last(), midi + 1), midi, layers)
        }
        return KeygroupProgram(name, groups, volumeRelease = spec.releaseSeconds,
            explicitNoteOn = true, voiceMode = VoiceMode.POLY) to samples
    }

    fun export(name: String, spec: Spec, notes: List<List<Capture>>, destRoot: File,
        overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        require(destRoot.isDirectory || destRoot.mkdirs()) { "cannot create instrument destination ${destRoot.path}" }
        val dataName = Mpc3TrackWriter.trackDataDirName(name)
        // The sidecar becomes visible last, after the complete data and MPC descriptors.
        val entries = listOf(dataName, "$name.xty", "$name${InstrumentStore.SUFFIX}")
        fun guardDestination() {
            if (!overwrite) entries.map { File(destRoot, it) }.firstOrNull { it.exists() }
                ?.let { throw DestinationExists(it) }
        }
        guardDestination()
        val staging = Files.createTempDirectory(destRoot.toPath(), ".scanned-chain-keys-").toFile()
        val prepared = File(staging, "prepared")
        val previous = File(staging, "previous").apply { mkdirs() }
        val promoted = mutableListOf<String>()
        var retainRecovery = false
        try {
            OneNote.writePackage(name, program, samples, prepared, overwrite = false, spanKeyboard = false)
            val dataDir = File(prepared, dataName)
            AtomicFile.writeText(File(dataDir, PATCH_FILE), spec.patch.toJsonText())
            program.keygroups.forEachIndexed { zoneIndex, group ->
                group.layers.forEachIndexed { touchIndex, layer ->
                    AtomicFile.writeText(File(dataDir, recipePath(layer.sampleName)), notes[zoneIndex][touchIndex].patch.toJsonText())
                }
            }
            AtomicFile.writeText(File(dataDir, MANIFEST_FILE), manifest(spec, program))
            guardDestination()
            for (entry in entries.asReversed()) {
                val existing = File(destRoot, entry)
                if (existing.exists()) Files.move(existing.toPath(), File(previous, entry).toPath())
            }
            for (entry in entries) {
                Files.move(File(prepared, entry).toPath(), File(destRoot, entry).toPath())
                promoted += entry
            }
        } catch (failure: Throwable) {
            for (entry in promoted.asReversed()) File(destRoot, entry).deleteRecursively()
            for (entry in entries) {
                val backup = File(previous, entry)
                if (backup.exists()) try {
                    Files.move(backup.toPath(), File(destRoot, entry).toPath())
                } catch (rollbackFailure: Throwable) {
                    retainRecovery = true
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        } finally {
            // Keep the originals for manual recovery if even rollback could not write them.
            if (!retainRecovery) staging.deleteRecursively()
        }
        return program
    }

    /** Delivery settings are separate from the source-patch schema and the phone's playback sidecar. */
    private fun manifest(spec: Spec, program: KeygroupProgram): String = Json.write(JsonValue.Obj(linkedMapOf(
        "version" to JsonValue.Num(VERSION.toDouble()),
        "name" to JsonValue.Str(program.name),
        "sourcePatch" to JsonValue.Str(PATCH_FILE),
        "renderer" to JsonValue.Str("ScannedChainPatch.render"),
        "sourceModel" to JsonValue.Num(spec.patch.model.toDouble()),
        "sampleRate" to JsonValue.Num(WavWriter.MPC_SAMPLE_RATE.toDouble()),
        "channels" to JsonValue.Num(1.0),
        "finite" to JsonValue.Bool(true),
        "loopStartFrame" to JsonValue.Num(0.0),
        "explicitNoteOn" to JsonValue.Bool(program.explicitNoteOn),
        "voiceMode" to JsonValue.Str(program.voiceMode.name),
        "releaseSeconds" to JsonValue.Num(program.volumeRelease.toDouble()),
        "spanKeyboard" to JsonValue.Bool(false),
        "gainRule" to JsonValue.Str("Divide the finished source by its physical velocity exactly once; the host supplies note-velocity gain. No peak or loudness matching."),
        "zones" to JsonValue.Arr(program.keygroups.map { group -> JsonValue.Obj(linkedMapOf(
            "rootMidi" to JsonValue.Num(group.rootNote.toDouble()),
            "lowMidi" to JsonValue.Num(group.lowNote.toDouble()),
            "highMidi" to JsonValue.Num(group.highNote.toDouble()),
            "layers" to JsonValue.Arr(group.layers.mapIndexed { index, layer -> JsonValue.Obj(linkedMapOf(
                "sample" to JsonValue.Str("${layer.sampleName}.wav"),
                "sourceRecipe" to JsonValue.Str(recipePath(layer.sampleName)),
                "physicalVelocity" to JsonValue.Num(TOUCHES[index].toDouble()),
                "sampleScale" to JsonValue.Num((1.0 / TOUCHES[index]).toDouble()),
                "velStart" to JsonValue.Num(layer.velStart.toDouble()),
                "velEnd" to JsonValue.Num(layer.velEnd.toDouble()),
                "frames" to JsonValue.Num(layer.frameCount.toDouble()),
                "loopStartFrame" to JsonValue.Num(0.0),
            )) }),
        )) }),
    )))

    private fun recipePath(stem: String): String = "$RECIPE_DIRECTORY/$stem.patch.json"

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("Scanned Chain keys render no longer wanted")
    }
}

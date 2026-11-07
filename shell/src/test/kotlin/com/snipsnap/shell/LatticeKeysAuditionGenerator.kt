package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.LatticeKeys
import com.snipsnap.synth.LatticePresets
import com.snipsnap.synth.LatticeVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Actual TRACE keygroup delivery, read back through the normal sample player. */
object LatticeKeysAuditionGenerator {
    private const val RATE = 44_100
    private const val NAME = "Lattice Trace Keys"
    private const val RECIPE_VERSION = 1
    private const val CONTENT = "keys2"
    private const val HELD_MODEL = 2
    private val SOURCE_FILES = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Lattice.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/LatticeKeys.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/LatticeMotion.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/LatticePatch.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/LatticePadMaker.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
        "shell/src/test/kotlin/com/snipsnap/shell/LatticeKeysAuditionGenerator.kt",
    )

    private data class Event(val midi: Int, val on: Int, val off: Int, val velocity: Float = .75f)
    private data class LegacyDependency(
        val spec: LatticePadMaker.Spec,
        val sidecar: File,
        val instrument: InstrumentStore.Instrument,
        val samples: Map<String, Snip>,
        val source: Any,
        val hashes: Map<String, String>,
        val identityScope: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size == 3) { "usage: --replay recipe.json output.wav" }
            WavWriter.write(File(args[2]), replay(File(args[1])), WavWriter.BitDepth.PCM_16)
            println("Replayed held TRACE performance to ${args[2]}")
            return
        }
        require(args.size <= 2) { "supply an optional fresh output directory and optional published-v1 baseline directory" }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
        val output = File(args.firstOrNull() ?: File(repo, "testkit/lattice-keys-audition").path).canonicalFile
        val baseline = args.getOrNull(1)?.let { File(it).canonicalFile.also { folder ->
            require(folder.isDirectory && File(folder, "keys-manifest.json").isFile && File(folder, "keys-measurements.json").isFile) {
                "Published-v1 baseline needs keys/, keys-manifest.json and keys-measurements.json: $folder"
            }
        } }
        require(!output.exists() || output.listFiles()?.isEmpty() == true) { "Use a fresh output directory: $output" }
        val revision = git(repo, "rev-parse", "HEAD")
        val dirty = git(repo, "status", "--porcelain", "--untracked-files=normal").isNotEmpty()
        val hashes = SOURCE_FILES.associateWith { sha(File(repo, it)) }
        val started = System.nanoTime()
        check(output.mkdirs() || output.isDirectory)
        val bundle = File(output, "$CONTENT/instruments/$NAME").apply { check(mkdirs()) }
        val spec = LatticePadMaker.Spec(LatticePresets.forVoice(LatticeVoice.TRACE).first(), heldModel = HELD_MODEL)
        val roots = LatticePadMaker.zoneMidis(spec)
        val times = mutableListOf<Double>()
        val notes = roots.map { midi ->
            val before = System.nanoTime()
            LatticePadMaker.renderZone(spec, midi).also { note ->
                times += (System.nanoTime() - before) / 1e6
                println("TRACE ${Scales.nameOf(midi)}: ${note.snip.frameCount} frames, loop ${note.loopStartFrame}")
            }
        }
        val program = LatticePadMaker.export(NAME, spec, notes, bundle)
        val (sidecar, instrument) = InstrumentStore.list(bundle).single()
        check(instrument.zones.size == 9 && instrument.zones.first().lowNote == 48 && instrument.zones.last().highNote == 72)
        check(instrument.release == spec.releaseSeconds)
        check(LatticePadMaker.Spec.fromJsonText(LatticePadMaker.recipeFile(bundle, NAME).readText()) == spec)
        val decoded = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)) }
        val loaded = InstrumentEngine.Loaded(instrument, RATE, decoded.mapValues { it.value.samples })
        val zoneRows = instrument.zones.mapIndexed { index, zone ->
            val file = File(sidecar.parentFile, zone.sample)
            val pcm = decoded.getValue(zone.sample)
            val sampler = requireNotNull(WavReader.readSmpl(file))
            val loop = requireNotNull(sampler.loop)
            val source = notes[index]
            check(sampler.rootNote == roots[index] && zone.rootNote == roots[index])
            check(pcm.channels == 1 && pcm.sampleRate == RATE && pcm.frameCount == source.snip.frameCount)
            check(loop.startFrame == source.loopStartFrame && loop.endFrameExclusive == pcm.frameCount.toLong())
            check(zone.loopStartFrame == source.loopStartFrame)
            val quantizationError = pcm.samples.indices.maxOf { abs(pcm.samples[it] - source.snip.samples[it]) }
            check(quantizationError < 2e-7f)
            linkedMapOf<String, Any?>(
                "rootMidi" to zone.rootNote, "rootName" to Scales.nameOf(zone.rootNote),
                "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                "file" to relative(output, file), "sha256" to sha(file), "pcmBits" to 24,
                "frames" to pcm.frameCount, "loopStartFrame" to loop.startFrame,
                "loopEndFrameExclusive" to loop.endFrameExclusive,
                "maxPcm24Error" to quantizationError, "renderMillis" to times[index],
                "bodyAndHandoff" to bodyAndHandoff(pcm.samples, loop.startFrame.toInt(), spec.patch.macros.getValue("SPEED")),
            )
        }
        val nativeTrack = File(bundle, "$NAME.xty")
        check(Acvs.read(nativeTrack).payloadText == Mpc3TrackWriter().keygroupPayloadText(program))
        val compatibility = File(bundle, "${Mpc3TrackWriter.trackDataDirName(NAME)}/$NAME.xpm")
        val xpm = compatibility.readText()
        notes.forEach { check("<SliceLoopStart>${it.loopStartFrame}</SliceLoopStart>" in xpm) }

        // These three proofs come from real independent synthesis cycles before any playback repeats.
        val proofRows = listOf(48, 60, 72).map { midi ->
            val proof = LatticeKeys.probe(spec.patch, midi, heldModel = spec.heldModel)
            val folder = File(output, "$CONTENT/diagnostics/${Scales.nameOf(midi).lowercase(Locale.ROOT)}").apply { check(mkdirs()) }
            val samples = proof.note.snip.samples
            val prefix = WavWriter.write(File(folder, "attack-prefix.wav"), Snip(samples.copyOf(proof.prefixFrames), 1, RATE))
            val previous = WavWriter.write(File(folder, "actual-previous-cycle.wav"), Snip(proof.previousCycle, 1, RATE))
            val loop = WavWriter.write(File(folder, "actual-loop-cycle.wav"), Snip(samples.copyOfRange(proof.prefixFrames, samples.size), 1, RATE))
            linkedMapOf<String, Any?>(
                "midi" to midi, "prefixFrames" to proof.prefixFrames, "loopFrames" to proof.loopFrames,
                "prefixSeconds" to proof.prefixSeconds, "transitionEndSeconds" to proof.transitionEndSeconds,
                "requestedHz" to proof.requestedHz, "fittedHz" to proof.fittedHz,
                "pitchErrorCents" to proof.pitchErrorCents, "seamError" to proof.seamError,
                "cycleError" to proof.cycleError, "pcm16SeamError" to proof.pcm16SeamError,
                "pcm16MaxCycleDifference" to proof.pcm16MaxCycleDifference,
                "pcm24MaxCycleDifference" to proof.pcm24MaxCycleDifference,
                "stateError" to proof.stateError, "sourceGain" to proof.sourceGain,
                "heldModel" to spec.heldModel,
                "loopStateStart" to proof.loopStateStart.toList(), "loopStateEnd" to proof.loopStateEnd.toList(),
                "directSustainPeak" to proof.directSustainPeak, "renderMillis" to proof.renderMillis,
                "files" to listOf(prefix, previous, loop).map { mapOf("file" to relative(output, it), "sha256" to sha(it)) },
                "scope" to "Native held source with its fixed velocity gain; package layers remove that gain once for host playback.",
            )
        }
        val source = linkedMapOf<String, Any?>(
            "engine" to "LATTICE", "voice" to "TRACE", "patchModel" to spec.patch.model,
            "heldModel" to spec.heldModel, "sourceCommit" to revision,
            "workingTreeDirty" to dirty, "sourceFilesSha256" to hashes,
            "sampleRate" to RATE, "oversample" to 4,
        )
        val fileHashes = (listOf(sidecar) + instrument.sampleFiles.map { File(sidecar.parentFile, it) })
            .associate { relative(output, it) to sha(it) }
        val releaseFrames = ceil(spec.releaseSeconds * RATE.toDouble()).toInt()
        fun hit(midi: Int, velocity: Float = .75f): KeyHit.Hit = requireNotNull(
            KeyHit.resolve(instrument, midi, velocity) { decoded[it]?.frameCount?.toLong() },
        )
        fun heldFrames(midi: Int): Int {
            val resolved = hit(midi)
            return ceil(resolved.frames / resolved.pitchRatio).toInt() +
                ceil((resolved.frames - resolved.loopStartFrame) / resolved.pitchRatio).toInt() + RATE / 8
        }
        fun clip(id: String, title: String, description: String, events: List<Event>): Map<String, Any?> {
            val frames = events.maxOf { it.off } + releaseFrames + RATE / 5
            val recipeFile = File(output, "$CONTENT/recipes/$id.json").apply { parentFile.mkdirs() }
            val recipe = linkedMapOf<String, Any?>(
                "version" to RECIPE_VERSION, "kind" to "lattice-keygroup-performance", "source" to source,
                "instrumentSidecar" to relative(output, sidecar), "exportedFilesSha256" to fileHashes,
                "sampleRate" to RATE, "frames" to frames, "releaseSeconds" to spec.releaseSeconds,
                "hostVoiceGain" to InstrumentEngine.VOICE_LEVEL, "sourceRecipe" to Json.parse(spec.toJsonText()),
                "events" to events.map { mapOf("midi" to it.midi, "velocity" to it.velocity, "noteOnFrame" to it.on, "noteOffFrame" to it.off) },
            )
            recipeFile.writeText(Json.write(json(recipe)) + "\n")
            val rendered = performance(loaded, events, frames)
            val replayed = replay(recipeFile)
            check(rendered.samples.contentEquals(replayed.samples)) { "$id recipe Float replay differs" }
            val audioFile = WavWriter.write(File(output, "$CONTENT/clips/$id.wav"), rendered, WavWriter.BitDepth.PCM_16)
            val replayBytes = ByteArrayOutputStream().also { WavWriter.write(it, replayed, WavWriter.BitDepth.PCM_16) }.toByteArray()
            check(audioFile.readBytes().contentEquals(replayBytes)) { "$id recipe PCM16 replay differs" }
            val pcm = WavReader.read(audioFile)
            val eventRows = events.map { event ->
                val zone = requireNotNull(instrument.zoneFor(event.midi))
                val resolved = hit(event.midi, event.velocity)
                val wrap = event.on + ceil(resolved.frames / resolved.pitchRatio).toInt()
                check(event.off > wrap + RATE / 10)
                val afterWrap = rms(pcm.samples, wrap + RATE / 40, wrap + RATE / 10)
                check(afterWrap > 1e-7) { "$id stopped instead of sustaining at ${event.midi}" }
                mapOf(
                    "midi" to event.midi, "velocity" to event.velocity, "noteOnFrame" to event.on,
                    "firstWrapFrame" to wrap, "noteOffFrame" to event.off,
                    "releaseFinishedFrame" to event.off + releaseFrames,
                    "heldPastFirstWrapFrames" to event.off - wrap, "postWrapRms" to afterWrap,
                    "zoneRootMidi" to zone.rootNote, "pitchRatio" to resolved.pitchRatio,
                )
            }
            check(pcm.samples.takeLast(RATE / 10).all { it == 0f }) { "$id did not release to silence" }
            val first = events.minBy { it.on }
            val resolved = hit(first.midi, first.velocity)
            val cues = listOf(
                mapOf("time" to first.on / RATE.toDouble(), "label" to "Attack and movement"),
                mapOf("time" to (first.on + ceil(resolved.loopStartFrame / resolved.pitchRatio)) / RATE.toDouble(), "label" to "Held sustain"),
                mapOf("time" to first.off / RATE.toDouble(), "label" to "Release the key"),
            )
            return linkedMapOf(
                "id" to id, "title" to title, "description" to description, "section" to "keys",
                "file" to relative(output, audioFile), "recipeFile" to relative(output, recipeFile),
                "sha256" to sha(audioFile), "duration" to frames / RATE.toDouble(),
                "waveform" to waveform(pcm.samples), "events" to eventRows, "cues" to cues,
                "metrics" to statistics(pcm.samples), "commonGain" to true,
                "heldModel" to spec.heldModel,
            )
        }
        val chordNotes = listOf(48, 51, 55)
        val chordOff = chordNotes.mapIndexed { index, midi -> heldFrames(midi) + index * RATE / 3 }.max()
        val heldClips = listOf(
            clip("keys2-c3-hold", "C3: keep the body through the handoff", "Held model 2 keeps the source driving the collector and changes its fixed pickup polarity. The key stays down through a full loop, then releases over 0.6 seconds. No level matching is applied.",
                listOf(Event(48, 0, heldFrames(48)))),
            clip("keys2-c4-hold", "C4: the revised handoff one octave up", "C4 uses its own held-model-2 rendered root. Listen across the marked sustain and the key release; no new strike happens at the loop.",
                listOf(Event(60, 0, heldFrames(60)))),
            clip("keys2-chord", "A held C minor chord", "Three staggered key presses settle into the revised held collector together. Every note wraps before all three keys release.",
                chordNotes.mapIndexed { index, midi -> Event(midi, index * RATE / 3, chordOff, .65f) }),
        )
        val comparison = handoffComparison(output, spec, sidecar, instrument, decoded, source, fileHashes, baseline)
        val clips = heldClips + comparison
        val validation = linkedMapOf<String, Any?>(
            "exportReadback" to true, "samplerMarkersReadback" to true, "mpc3ContainerReadback" to true,
            "recipesSampleIdentical" to true, "recipeReplayCount" to clips.size,
            "heldPerformancesWrapAndRelease" to true, "heldPerformanceCount" to heldClips.size,
            "comparisonIsShortHandoffOnly" to true, "comparisonWrapRequired" to false,
            "independentCycleProofCount" to proofRows.size, "exportedZoneBodyChecks" to zoneRows.size,
            "physicalPhoneValidated" to false, "mpcHardwareValidated" to false, "ownerListeningAcceptance" to false,
        )
        val measurements = linkedMapOf<String, Any?>(
            "generatedAt" to Instant.now().toString(), "keysEngine" to source, "sourceRecipe" to Json.parse(spec.toJsonText()),
            "instrument" to mapOf("name" to NAME, "releaseSeconds" to instrument.release, "range" to listOf(48, 72),
                "rootCount" to roots.size, "velocityLayers" to 1, "sourceVelocity" to spec.patch.velocity,
                "mpc3" to relative(output, nativeTrack), "mpc2" to relative(output, compatibility), "sidecar" to relative(output, sidecar)),
            "zones" to zoneRows, "independentCycleProofs" to proofRows, "clips" to clips, "keysValidation" to validation,
            "gainNote" to "One captured velocity-shaped spectrum. Export layers remove velocity^.72 once; the host applies event velocity and its fixed voice gain. No clip normalization or external effects.",
            "releaseNote" to "Note-off fades the looping sample through the existing host amplitude envelope; it does not synthesize a separate physical free-decay tail.",
            "renderSeconds" to (System.nanoTime() - started) / 1e9,
        )
        File(output, "$CONTENT/measurements.json").writeText(Json.write(json(measurements)) + "\n")
        File(bundle, "README.txt").writeText(readme(spec.releaseSeconds))
        val zip = archive(output, bundle)
        val manifest = linkedMapOf<String, Any?>(
            "revision" to 7, "schemaVersion" to 1, "title" to "Lattice · a steadier handoff",
            "subtitle" to "A plucked attack, a steadier ringing body, and a fade when you let go.",
            "revisionTitle" to "Keep the body.\nHold the ringing tone.",
            "revisionSummary" to "The held tone now keeps its body as the pluck turns into a ring. Start with C3, then compare the original and revised versions on the same short key press.",
            "revisionNote" to "The accepted B one-shot stays unchanged. The original held take remains below for comparison.",
            "heroClipId" to "keys2-c3-hold", "heroTitle" to "Hold C3 through the revised handoff",
            "heroEyebrow" to "TRACE · STEADIER SUSTAIN",
            "heroDescription" to "Listen as the strong opening becomes a held ringing tone. The key stays down through a loop, then releases into a fade.",
            "heroFootnote" to "These are recordings of the exported instrument. The full examples repeat their sustain loop; the short comparison plays the same two-second key press twice.",
            "generatedAt" to Instant.now().toString(), "keysEngine" to source, "keysValidation" to validation,
            "clips" to clips, "instrument" to (measurements.getValue("instrument")),
            "downloads" to mapOf("keygroups" to zip.name, "keysMeasurements" to "$CONTENT/measurements.json"),
            "archive" to mapOf("file" to zip.name, "sha256" to sha(zip), "bytes" to zip.length()),
        )
        File(output, "keys-manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(output, "$CONTENT/REPLAY.txt").writeText("""
            LATTICE TRACE KEYS, HELD MODEL ${spec.heldModel}

            Generate: ./gradlew --no-daemon :shell:generateLatticeKeysAudition -PlatticeKeysOutput=/fresh/output
            Replay: ./gradlew --no-daemon :shell:generateLatticeKeysAudition -PlatticeKeysRecipe=/path/keys2/recipes/keys2-c3-hold.json -PlatticeKeysReplayOutput=/path/replay.wav

            Keep recipes under keys2/recipes and the exported instruments under keys2/instruments
            and keys2/before-v1. Original model-1 keys/ recipes still use the same --replay entry point.
            Replay checks the exported sidecar and each WAV SHA-256, then drives InstrumentEngine
            with the saved exact note-on/off frames. The three held performances and short before/after
            comparison replay byte-identically. The comparison plays model 1 then model 2 at C3,
            note-on 0 / note-off 2 seconds / release 0.6 seconds, with a nominal 0.2-second gap.
            The recipe records exact frames; measurements record the Float release's actual silence.
            That comparison is deliberately shorter than the first loop; the other three performances wrap.
            keys2/diagnostics contains independent synthesis cycles at C3/C4/C5 before playback repeats.
            keys2/measurements.json records source fingerprints, cycle errors, raw body/handoff windows
            across all nine exported roots and package marker readback.
            Native package WAVs are PCM24; clips are PCM16. No loudness matching is applied.
            Physical phone/MPC listening and acceptance of the new held sound remain open.
            Source revision: $revision
        """.trimIndent() + "\n")
        println("Wrote 9 held-model-2 TRACE keygroups, 4 replay-checked recordings and 3 independent cycle proofs in ${(System.nanoTime() - started) / 1e9}s")
    }

    private fun handoffComparison(
        output: File,
        spec: LatticePadMaker.Spec,
        sidecar: File,
        instrument: InstrumentStore.Instrument,
        decoded: Map<String, Snip>,
        source: Map<String, Any?>,
        currentHashes: Map<String, String>,
        baseline: File?,
    ): Map<String, Any?> {
        val legacy = legacyDependency(output, spec, source, baseline)
        val legacySpec = legacy.spec
        val legacySidecar = legacy.sidecar
        val legacyInstrument = legacy.instrument
        val legacyDecoded = legacy.samples

        val event = Event(48, 0, 2 * RATE)
        val releaseFrames = ceil(spec.releaseSeconds * RATE.toDouble()).toInt()
        val gapFrames = RATE / 5
        // The gap is a nominal .2 seconds after the .6-second host release. The normal
        // Float envelope can finish a few frames beyond that nominal boundary; record
        // the actual last nonzero frame rather than silently editing its fade.
        val partFrames = event.off + releaseFrames + gapFrames
        val dependencies = listOf(
            mapOf("heldModel" to 1, "label" to "A · Original held model 1", "instrumentSidecar" to relative(output, legacySidecar),
                "exportedFilesSha256" to legacy.hashes, "source" to legacy.source,
                "identityScope" to legacy.identityScope, "sourceRecipe" to Json.parse(legacySpec.toJsonText())),
            mapOf("heldModel" to spec.heldModel, "label" to "B · Revised held model 2", "instrumentSidecar" to relative(output, sidecar),
                "exportedFilesSha256" to currentHashes, "source" to source, "sourceRecipe" to Json.parse(spec.toJsonText())),
        )
        val parts = listOf(
            performance(InstrumentEngine.Loaded(legacyInstrument, RATE, legacyDecoded.mapValues { it.value.samples }), listOf(event), partFrames),
            performance(InstrumentEngine.Loaded(instrument, RATE, decoded.mapValues { it.value.samples }), listOf(event), partFrames),
        )
        val recipeFile = File(output, "$CONTENT/recipes/keys2-handoff-before-after.json").apply { parentFile.mkdirs() }
        val recipe = linkedMapOf<String, Any?>(
            "version" to RECIPE_VERSION, "kind" to "lattice-keygroup-handoff-comparison", "sampleRate" to RATE,
            "frames" to 2 * partFrames, "nominalReleaseSeconds" to spec.releaseSeconds,
            "nominalGapFrames" to gapFrames, "normalization" to "none", "hostVoiceGain" to InstrumentEngine.VOICE_LEVEL,
            "parts" to dependencies.mapIndexed { index, dependency -> dependency + mapOf(
                "offsetFrame" to index * partFrames, "frames" to partFrames,
                "events" to listOf(mapOf("midi" to event.midi, "velocity" to event.velocity,
                    "noteOnFrame" to event.on, "noteOffFrame" to event.off)),
            ) },
        )
        recipeFile.writeText(Json.write(json(recipe)) + "\n")
        val joined = FloatArray(2 * partFrames)
        parts.forEachIndexed { index, part -> part.samples.copyInto(joined, index * partFrames) }
        val rendered = Snip(joined, 1, RATE)
        val replayed = replay(recipeFile)
        check(rendered.samples.contentEquals(replayed.samples)) { "Same-event comparison Float replay differs" }
        val audioFile = WavWriter.write(File(output, "$CONTENT/clips/keys2-handoff-before-after.wav"), rendered, WavWriter.BitDepth.PCM_16)
        val replayBytes = ByteArrayOutputStream().also { WavWriter.write(it, replayed, WavWriter.BitDepth.PCM_16) }.toByteArray()
        check(audioFile.readBytes().contentEquals(replayBytes)) { "Same-event comparison PCM16 replay differs" }
        val pcm = WavReader.read(audioFile)
        val partMetrics = parts.mapIndexed { index, part ->
            val last = part.samples.indexOfLast { it != 0f }
            val partPcm = pcm.samples.copyOfRange(index * partFrames, (index + 1) * partFrames)
            val zone = if (index == 0) legacyInstrument.zoneFor(48) else instrument.zoneFor(48)
            val sourceFrames = requireNotNull(zone).let { if (index == 0) legacyDecoded.getValue(it.sample).frameCount else decoded.getValue(it.sample).frameCount }
            check(event.off < sourceFrames) { "The short handoff comparison unexpectedly reaches a loop" }
            mapOf("heldModel" to if (index == 0) 1 else spec.heldModel,
                "offsetFrame" to index * partFrames, "noteOnFrame" to event.on, "noteOffFrame" to event.off,
                "lastNonzeroFloatFrame" to last, "trailingSilentFloatFrames" to partFrames - last - 1,
                "lastNonzeroPcm16Frame" to partPcm.indexOfLast { it != 0f },
                "firstWrapFrame" to sourceFrames, "wrapsBeforeNoteOff" to false,
                "statistics" to statistics(partPcm),
                "bodyAndHandoff" to bodyAndHandoff(partPcm, null, spec.patch.macros.getValue("SPEED")))
        }
        val comparison = linkedMapOf<String, Any?>(
            "id" to "keys2-handoff-before-after", "title" to "Same C3 press: original, then revised handoff",
            "description" to "A plays held model 1; B plays held model 2 from their actual exported WAVs. Both press C3 at 0 and release at 2 seconds through the same 0.6-second host fade, with a nominal 0.2-second gap. No loudness matching. This short comparison does not reach the sustain loop.",
            "section" to "keys", "comparison" to true, "tags" to listOf("RAW BEFORE / AFTER", "SAME NOTE ON / OFF"),
            "file" to relative(output, audioFile), "recipeFile" to relative(output, recipeFile),
            "sha256" to sha(audioFile), "duration" to joined.size / RATE.toDouble(),
            "waveform" to waveform(pcm.samples), "commonGain" to true,
            "cues" to listOf(mapOf("time" to 0.0, "label" to "A · Original held model 1"),
                mapOf("time" to partFrames / RATE.toDouble(), "label" to "B · Revised held model 2")),
            "metrics" to statistics(pcm.samples), "parts" to partMetrics,
            "legacyIdentityScope" to legacy.identityScope,
            "first35msMaxFloatDifference" to (0 until (RATE * .035).toInt()).maxOf { abs(parts[0].samples[it] - parts[1].samples[it]).toDouble() },
        )
        File(output, "$CONTENT/handoff-comparison.json").writeText(Json.write(json(comparison)) + "\n")
        return comparison
    }

    private fun legacyDependency(output: File, spec: LatticePadMaker.Spec, source: Map<String, Any?>, baseline: File?): LegacyDependency {
        val destination = File(output, "$CONTENT/before-v1").apply { check(mkdirs()) }
        val legacySpec: LatticePadMaker.Spec
        val legacySidecar: File
        val legacyInstrument: InstrumentStore.Instrument
        val legacySource: Any
        val identityScope: String
        if (baseline != null) {
            val measurements = Json.parse(File(baseline, "keys-measurements.json").readText()).obj()
            legacySpec = LatticePadMaker.Spec.fromJsonText(Json.write(measurements.getValue("sourceRecipe")))
            require(legacySpec.heldModel == 1) { "The comparison baseline must be held model 1" }
            require(legacySpec.patch == spec.patch && legacySpec.releaseSeconds == spec.releaseSeconds) {
                "Before/after comparison requires the same source patch and release in both models"
            }
            val oldKeys = File(baseline, "keys")
            require(oldKeys.isDirectory) { "The comparison baseline has no keys/ dependencies" }
            oldKeys.walkTopDown().filter { it.isFile }.forEach { file ->
                val copy = File(destination, "keys/${relative(oldKeys, file)}")
                check(copy.parentFile.mkdirs() || copy.parentFile.isDirectory)
                file.copyTo(copy)
                check(sha(file) == sha(copy)) { "Published held-model-1 dependency changed while copying" }
            }
            for (name in listOf("keys-manifest.json", "keys-measurements.json")) {
                val original = File(baseline, name)
                original.copyTo(File(destination, name))
                check(sha(original) == sha(File(destination, name)))
            }
            val oldSidecarPath = measurements.getValue("instrument").obj().getValue("sidecar").str()
            legacySidecar = File(destination, oldSidecarPath).canonicalFile
            require(legacySidecar.toPath().startsWith(destination.canonicalFile.toPath()))
            legacyInstrument = InstrumentStore.read(legacySidecar)
            legacySource = measurements.getValue("keysEngine")
            identityScope = "Byte-identical published held-model-1 package copied with its original keys-manifest.json, keys-measurements.json and source fingerprints. Comparison recipe paths are scoped to keys2/before-v1; original keys/ artifacts remain unchanged."
        } else {
            legacySpec = spec.copy(heldModel = 1)
            val bundle = File(destination, "instruments/$NAME").apply { check(mkdirs()) }
            val notes = LatticePadMaker.zoneMidis(legacySpec).map { LatticePadMaker.renderZone(legacySpec, it) }
            LatticePadMaker.export(NAME, legacySpec, notes, bundle)
            val stored = InstrumentStore.list(bundle).single()
            legacySidecar = stored.first
            legacyInstrument = stored.second
            legacySource = source + mapOf("heldModel" to 1)
            identityScope = "Legacy held-model-1 renderer exported by this source snapshot. No published-v1 baseline was supplied; this is a fresh export, not a claim about the hashes or source snapshot of the original publication."
        }
        val samples = legacyInstrument.sampleFiles.associateWith { WavReader.read(File(legacySidecar.parentFile, it)) }
        val hashes = (listOf(legacySidecar) + legacyInstrument.sampleFiles.map { File(legacySidecar.parentFile, it) })
            .associate { relative(output, it) to sha(it) }
        File(destination, "ORIGIN.txt").writeText(identityScope + "\n")
        return LegacyDependency(legacySpec, legacySidecar, legacyInstrument, samples, legacySource, hashes, identityScope)
    }

    private fun replay(recipeFile: File): Snip {
        val recipe = Json.parse(recipeFile.readText()).obj()
        require(recipe.getValue("version").int() == RECIPE_VERSION)
        if (recipe.getValue("kind").str() == "lattice-keygroup-handoff-comparison") return replayComparison(recipeFile, recipe)
        require(recipe.getValue("kind").str() == "lattice-keygroup-performance")
        require(recipe.getValue("sampleRate").int() == RATE)
        val root = recipeFile.canonicalFile.parentFile.parentFile.parentFile
        fun inside(path: String): File = File(root, path).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) }
        recipe.getValue("exportedFilesSha256").obj().forEach { (path, hash) ->
            require(sha(inside(path)) == hash.str()) { "Exported dependency changed: $path" }
        }
        val sidecar = inside(recipe.getValue("instrumentSidecar").str())
        val instrument = InstrumentStore.read(sidecar)
        val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
        val events = recipe.getValue("events").arr().map { value ->
            val row = value.obj()
            Event(row.getValue("midi").int(), row.getValue("noteOnFrame").int(), row.getValue("noteOffFrame").int(), row.getValue("velocity").num().toFloat())
        }
        return performance(InstrumentEngine.Loaded(instrument, RATE, samples), events, recipe.getValue("frames").int())
    }

    private fun replayComparison(recipeFile: File, recipe: Map<String, JsonValue>): Snip {
        require(recipe.getValue("sampleRate").int() == RATE)
        require(recipe.getValue("normalization").str() == "none")
        val root = recipeFile.canonicalFile.parentFile.parentFile.parentFile
        fun inside(path: String): File = File(root, path).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) }
        val out = FloatArray(recipe.getValue("frames").int())
        val occupied = BooleanArray(out.size)
        recipe.getValue("parts").arr().forEach { value ->
            val part = value.obj()
            part.getValue("exportedFilesSha256").obj().forEach { (path, hash) ->
                require(sha(inside(path)) == hash.str()) { "Exported comparison dependency changed: $path" }
            }
            val sidecar = inside(part.getValue("instrumentSidecar").str())
            val instrument = InstrumentStore.read(sidecar)
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            val events = part.getValue("events").arr().map { row -> row.obj().let {
                Event(it.getValue("midi").int(), it.getValue("noteOnFrame").int(), it.getValue("noteOffFrame").int(), it.getValue("velocity").num().toFloat())
            } }
            val rendered = performance(InstrumentEngine.Loaded(instrument, RATE, samples), events, part.getValue("frames").int())
            val offset = part.getValue("offsetFrame").int()
            require(offset >= 0 && offset <= out.size - rendered.frameCount)
            for (i in rendered.samples.indices) {
                require(!occupied[offset + i]) { "Comparison recipe contains overlapping parts" }
                occupied[offset + i] = true
                out[offset + i] = rendered.samples[i]
            }
        }
        return Snip(out, 1, RATE)
    }

    private fun performance(loaded: InstrumentEngine.Loaded, events: List<Event>, frames: Int): Snip {
        require(events.all { it.on >= 0 && it.off > it.on && it.off < frames })
        val player = InstrumentEngine(loaded, RATE)
        val starts = events.groupBy { it.on }
        val stops = events.groupBy { it.off }
        val boundaries = (starts.keys + stops.keys + listOf(0, frames)).distinct().sorted()
        val out = FloatArray(frames)
        val block = FloatArray(512 * 2)
        for ((begin, end) in boundaries.zipWithNext()) {
            stops[begin]?.forEach { player.noteOff(it.midi) }
            starts[begin]?.forEach { check(player.noteOn(it.midi, it.velocity)) }
            var position = begin
            while (position < end) {
                val count = minOf(512, end - position)
                player.render(block, count)
                for (i in 0 until count) {
                    check(block[2 * i] == block[2 * i + 1])
                    out[position + i] = block[2 * i]
                }
                position += count
            }
        }
        check(player.activeNotes.isEmpty()) { "Performance ended before release completed" }
        return Snip(out, 1, RATE)
    }

    private fun archive(output: File, bundle: File): File {
        val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.toList()
        val archive = File(output, "lattice-trace-keys-v2.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry("${bundle.name}/${relative(bundle, file)}").apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        ZipFile(archive).use { zip ->
            check(zip.size() == files.size)
            files.forEach { file -> check(zip.getInputStream(zip.getEntry("${bundle.name}/${relative(bundle, file)}")).use { it.readBytes() }.contentEquals(file.readBytes())) }
        }
        return archive
    }

    private fun readme(release: Float) = """
        LATTICE TRACE KEYS, HELD MODEL 2

        Nine native roots C3–C5, every minor third. The program covers MIDI 48–72.
        Each mono PCM24 sample contains its real onset followed by one settled loop.
        Keep the .xty with its TrackData folder for MPC 3. The MPC 2 .xpm twin is
        inside TrackData. The .instrument.json sidecar is for SnipSnap's player.
        $NAME.lattice-keys.json preserves the complete finite source patch and
        separately versioned held recipe. The finite B rendering stays unchanged.

        Held model 2 continues the pluck's source effort after reference time .035
        and reaches .80 pressure by .08 seconds. A fixed -2 collector pickup changes
        the signed readout before the direct/halo mix. There is no output makeup gain,
        automatic level following or loop splice. These choices address the original
        handoff loss; the owner's listening verdict on this revision remains open.

        Keyboard mode keeps the source driving the collector after the opening
        movement. SPEED changes movement timing. The saved finite HOLD value does
        not determine keyboard note length: the key stays down until note-off.
        Release is the host amplitude fade, $release seconds; no separate physical
        free-decay tail is synthesized. One captured velocity-shaped spectrum is
        shared across velocities; the host supplies note-velocity gain.

        A normal WAV player plays the marked sustain once and stops. Load the
        keygroup program to hold a key. Exported-file reference playback, loop
        markers and source-cycle recurrence are measured. Physical phone/MPC
        import and owner acceptance of this new held sound remain open.
    """.trimIndent() + "\n"

    private fun waveform(samples: FloatArray): List<Double> {
        val peak = samples.maxOf { abs(it).toDouble() }.coerceAtLeast(1e-12)
        return (0 until 160).map { bin ->
            val begin = bin * samples.size / 160
            val end = ((bin + 1) * samples.size / 160).coerceAtLeast(begin + 1)
            (begin until end).maxOf { abs(samples[it]).toDouble() } / peak
        }
    }
    private fun rms(samples: FloatArray, begin: Int, end: Int): Double =
        sqrt((begin until end).sumOf { samples[it].toDouble() * samples[it] } / (end - begin))
    private fun bodyAndHandoff(samples: FloatArray, loopStart: Int?, speed: Float): Map<String, Any?> {
        fun at(seconds: Double) = (seconds * RATE).toInt().coerceIn(0, samples.size)
        fun window(begin: Int, end: Int): Map<String, Any?> = mapOf(
            "startFrame" to begin, "endFrameExclusive" to end,
            "startSeconds" to begin / RATE.toDouble(), "endSeconds" to end / RATE.toDouble(),
            "rms" to rms(samples, begin, end),
        )
        val bodyBegin = at(.05)
        val bodyEnd = at(.15)
        val body = rms(samples, bodyBegin, bodyEnd)
        val initialBody = rms(samples, at(.02), at(.08))
        val attack = rms(samples, at(.01), at(.035))
        val steadyBegin = loopStart ?: at(1.0)
        val steadyEnd = if (loopStart != null) samples.size else at(1.8)
        val steady = rms(samples, steadyBegin, steadyEnd)
        val width = RATE / 20
        val hop = RATE / 200
        fun scan(begin: Int, end: Int): Map<String, Any?> {
            require(end - begin >= 2 * width)
            var worstStart = begin
            var largestSignedDrop = Double.NEGATIVE_INFINITY
            for (start in begin..(end - 2 * width) step hop) {
                val before = rms(samples, start, start + width)
                val after = rms(samples, start + width, start + 2 * width)
                val drop = 20.0 * log10(before.coerceAtLeast(1e-20) / after.coerceAtLeast(1e-20))
                if (drop > largestSignedDrop) {
                    largestSignedDrop = drop; worstStart = start
                }
            }
            return mapOf(
                "startFrame" to begin, "endFrameExclusive" to end,
                "startSeconds" to begin / RATE.toDouble(), "endSeconds" to end / RATE.toDouble(),
                "windowFrames" to width, "stepFrames" to hop,
                "largestDownwardDb" to largestSignedDrop.coerceAtLeast(0.0),
                "largestSignedBeforeAfterDb" to largestSignedDrop,
                "before" to window(worstStart, worstStart + width),
                "after" to window(worstStart + width, worstStart + 2 * width),
                "definition" to "Largest downward dB change between adjacent 50ms RMS windows, scanned every approximately 5ms. A negative signed change is a rise; largestDownwardDb is zero when there is no drop.",
            )
        }
        // This is the authored TRACE clock from LatticeMotion. Only the measurement
        // window is warped; the exported samples are never time-warped or modified.
        val scale = if (speed == .5f) 1.0 else 2.0.pow(1.0 - 2.0 * speed.toDouble())
        fun physicalTime(reference: Double) = if (scale == 1.0 || reference <= .035) reference else .035 + (reference - .035) * scale
        val handoffBegin = at(physicalTime(.20))
        val handoffEnd = at(physicalTime(.50))
        return linkedMapOf(
            "measurementScope" to if (loopStart != null) "Exported PCM24 keygroup layer at its native root; fixed source gain, no host gain or normalization." else "Exported PCM16 host-performance segment, fixed event velocity and host voice gain; no output normalization.",
            "initialBody" to window(at(.02), at(.08)), "initialBodyRms" to initialBody,
            "fixedAttack" to window(at(.01), at(.035)), "fixedAttackRms" to attack,
            "body" to window(bodyBegin, bodyEnd),
            "steady" to window(steadyBegin, steadyEnd),
            "steadyToInitialBodyDb" to 20.0 * log10(steady.coerceAtLeast(1e-20) / initialBody.coerceAtLeast(1e-20)),
            "steadyToFixedAttackDb" to 20.0 * log10(steady.coerceAtLeast(1e-20) / attack.coerceAtLeast(1e-20)),
            "steadyToBodyDb" to 20.0 * log10(steady.coerceAtLeast(1e-20) / body.coerceAtLeast(1e-20)),
            "early50msWindows" to (1..19).map { index -> window(index * width, (index + 1) * width) },
            "earlyPassageScan" to scan(at(.05), at(1.0)),
            "earlyPassageNote" to "Includes the accepted early gate closure; this global .05–1.0s scan is not the isolated collector handoff result.",
            "handoffScan" to (scan(handoffBegin, handoffEnd) + mapOf(
                "referenceStartSeconds" to .20, "referenceEndSeconds" to .50,
                "speed" to speed, "movementTimeScale" to scale,
                "clock" to "Physical seconds = .035 + (reference seconds - .035) × 2^(1 - 2 × SPEED), preserving times through .035s.")),
        )
    }
    private fun statistics(samples: FloatArray): Map<String, Double> = mapOf(
        "peak" to samples.maxOf { abs(it).toDouble() }, "rms" to rms(samples, 0, samples.size),
        "dc" to samples.sumOf { it.toDouble() } / samples.size,
    )
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.path) + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Cannot read source provenance" }
        return result.trim()
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(linkedMapOf()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest type ${value.javaClass.name}")
    }
}

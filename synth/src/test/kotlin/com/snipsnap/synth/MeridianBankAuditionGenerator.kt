package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Eight new saved recipes, the supported sixteen-one-shot landing, and genuine
 * held keyboard instruments. Native samples retain engine gain; listening matches
 * are separate files. The short phrase uses the exported pad WAVs and one mix gain.
 */
object MeridianBankAuditionGenerator {
    private const val MODEL = 2

    private data class PadAudio(
        val label: String, val patch: MeridianPatch, val sample: Snip,
        val sampleFile: String, val recipeFile: String, val wavSha256: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.getOrNull(0) ?: "../../meridian-bank-audition").absoluteFile
        val previous = File(args.getOrNull(1) ?: "../../meridian-round2-audition").absoluteFile
        check(root.mkdirs() || root.isDirectory)
        check(root.canonicalFile != previous.canonicalFile) { "The candidate bank must not overwrite an accepted round" }
        val began = System.nanoTime()
        val repository = File(git("rev-parse", "--show-toplevel"))
        val prior = Json.parse(File(previous, "manifest.json").readText()).obj()
        val sourcePaths = prior.getValue("retainedSourceFilesSha256").obj().keys + listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/MeridianPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/MeridianKits.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/MeridianKitGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/MeridianBankAuditionGenerator.kt",
        )
        val source = retainSource(root, repository, sourcePaths)
        val reference = copyReference(root, previous, prior)
        val presets = MeridianPresets.all()
        check(presets.size == 8 && presets.map { it.name }.distinct().size == 8)
        check(presets.all { it.model == MODEL })
        val cards = presets.mapIndexed { index, patch -> renderPreset(root, index, patch) }
        @Suppress("UNCHECKED_CAST") val firstFiles = cards.first().getValue("files") as Map<String, String>
        val first = WavReader.read(File(root, firstFiles.getValue("native")))
        val accepted = WavReader.read(File(root, "reference/round2-default-native.wav"))
        check(sameBits(first, accepted)) { "FIRST ARC changed the accepted finite starting sound" }

        val kitRoot = File(root, "exports")
        val kitArchive = MeridianKitGenerator.generate(kitRoot)
        val kitDirectory = File(kitRoot, MeridianKits.NAME)
        val kitManifestFile = File(kitDirectory, "kit-manifest.json")
        val kit = Json.parse(kitManifestFile.readText()).obj()
        val pads = readPads(root, kitDirectory, kit)
        val downloads = copyDownloads(root, kitDirectory, kitArchive, kit)
        File(root, "kit-manifest.json").writeBytes(kitManifestFile.readBytes())
        val phrase = renderPhrase(root, pads)
        val padCards = pads.mapIndexed { index, pad ->
            val midi = Meridian.midiFor(pad.patch.voice, pad.patch.macros.getValue("TUNE"), pad.patch.midi)
            linkedMapOf<String, Any?>(
                "slot" to index + 1, "label" to pad.label, "name" to pad.patch.name,
                "midi" to midi, "note" to noteName(midi), "velocity" to pad.patch.velocity,
                "model" to pad.patch.model, "seedContext" to pad.patch.seedContext,
                "playback" to if (Meridian.isLoop(pad.patch.macros, pad.patch.model)) "FINITE_HELD_CAPTURE" else "FINITE_ONE_SHOT",
                "oneShot" to true, "sampleFile" to pad.sampleFile, "recipeFile" to pad.recipeFile,
                "sampleWavSha256" to pad.wavSha256, "durationSeconds" to pad.sample.durationSeconds,
                "metrics" to metrics(pad.sample),
            )
        }
        val engineMatchesPrior = prior.getValue("retainedSourceFilesSha256").obj().filterKeys {
            it.startsWith("audio/src/main/") || it.startsWith("synth/src/main/")
        }.mapValues { (path, hash) -> source[path] == hash.str() }
        val provenance = linkedMapOf<String, Any?>(
            "sourceCommit" to git("rev-parse", "HEAD"), "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "generatedAt" to Instant.now().toString(), "retainedSourceFilesSha256" to source,
            "engineSourcesMatchAcceptedRound2" to engineMatchesPrior,
            "sourceSnapshotNote" to "These bytes were retained before any bank or kit render and checked unchanged afterwards. Preset and kit sources are part of the snapshot.",
        )
        for ((path, hash) in source) check(sha(File(repository, path).readBytes()) == hash) { "Source changed while rendering: $path" }
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "MERIDIAN", "model" to MODEL, "presetCount" to cards.size,
            "padCount" to pads.size, "sampleRate" to Dsp.RATE, "channels" to 1,
            "reference" to reference, "presets" to cards, "pads" to padCards, "phrase" to phrase,
            "kitManifestEvidenceFile" to "kit-manifest.json",
            "rawKitManifestArtifactPath" to relative(root, kitManifestFile),
            "rawKitManifestPathScope" to "The raw kit manifest's sample/program paths are relative to the generated export destination and archive contents, not web URLs. Use pads.sampleFile and downloads.webUrl for this page.",
            "downloads" to downloads,
            "rawExportManifest" to JsonValue.Obj(kit),
            "notes" to listOf(
                "The earlier engine auditions received positive owner feedback. FIRST ARC retains the accepted finite default; the seven other presets are new listening candidates.",
                "The sixteen-pad program plays every pad once. Its last two pads are finite captures of held presets with a terminal fade; they do not claim drum-program looping.",
                "ROUND THREAD and FINE STRAND also have separate sustained keyboard packages. Their supported sampler loop metadata is retained in those exports.",
                "Held preset previews repeat the decoded exported loop eight times. Seam evidence is measured on the genuine preceding and exported cycles before that repetition.",
                "The musical phrase is mixed from the exact staged kit WAVs, with explicit event accents and one shared output gain. There is no per-hit loudness normalization.",
                "Pitch.detect reports a local full-mix estimate. The disclosed finite pitch and octave-estimator limits have not been reshaped for this preset round.",
            ),
        ) + provenance
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        File(root, "diagnostics.json").writeText(Json.write(json(linkedMapOf(
            "presetCount" to 8, "padCount" to 16, "finiteAndUnclipped" to true,
            "firstArcMatchesAcceptedDefaultPcm" to true, "recipeRoundtrips" to true,
            "newPresetListening" to "Awaiting owner judgment", "generationSeconds" to (System.nanoTime() - began) / 1e9,
            "heldPresetEvidence" to cards.filter { it["held"] == true }.map { linkedMapOf("name" to it["name"], "loop" to it["loop"]) },
        ) + provenance)))
        File(root, "index.html").writeText(page(reference, cards, padCards, phrase, downloads))
        // The final source patch, test evidence, bank.zip and separate instrument
        // archives are published after validation; this task never packages stale evidence.
        println("MERIDIAN bank wrote eight preset cards, sixteen native pads and a saved musical phrase under ${root.path}")
    }

    private fun renderPreset(root: File, index: Int, patch: MeridianPatch): Map<String, Any?> {
        val id = "${(index + 1).toString().padStart(2, '0')}-${slug(patch.name)}"
        val held = Meridian.isLoop(patch.macros, patch.model)
        val began = System.nanoTime()
        val result = if (held) Meridian.renderHeld(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seedContext) else null
        val native = result?.snip ?: patch.render()
        val renderMillis = (System.nanoTime() - began) / 1e6
        checkAudio(patch.name, native)
        val recipe = PadRecipe(patch = patch)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        check(restored.patch == patch && sameBits(native, restored.render())) { "${patch.name}: recipe replay differs" }
        val matched = AuditionLevel.level(native)
        val midi = Meridian.midiFor(patch.voice, patch.macros.getValue("TUNE"), patch.midi)
        val sheet = SmplChunk(midi, result?.let { SmplChunk.Loop(it.loopStartFrame.toLong(), it.loopEndFrameExclusive.toLong()) })
        val files = linkedMapOf(
            "native" to "presets/$id-native.wav", "matched" to "presets/$id-matched.wav", "recipe" to "recipes/$id.json",
        )
        WavWriter.write(File(root, files.getValue("native")), native, WavWriter.BitDepth.PCM_16, smpl = sheet)
        WavWriter.write(File(root, files.getValue("matched")), matched, WavWriter.BitDepth.PCM_16, smpl = sheet)
        File(root, files.getValue("recipe")).also { it.parentFile.mkdirs() }.writeText(recipe.toJsonText())
        val decoded = WavReader.read(File(root, files.getValue("native")))
        val decodedMatched = WavReader.read(File(root, files.getValue("matched")))
        check(WavReader.readSmpl(File(root, files.getValue("native"))) == sheet)
        check(WavReader.readSmpl(File(root, files.getValue("matched"))) == sheet)
        var loop: Map<String, Any?>? = null
        if (result != null) {
            val nativeSeam = Keys.seamError(decoded.samples, result.loopStartFrame)
            val matchedSeam = Keys.seamError(decodedMatched.samples, result.loopStartFrame)
            check(listOf(result.seamError, nativeSeam, matchedSeam).all { it.isFinite() && it < Keys.MAX_SEAM_ERROR })
            files["nativeListening"] = "previews/$id-native-eight-loops.wav"
            files["matchedListening"] = "previews/$id-matched-eight-loops.wav"
            WavWriter.write(File(root, files.getValue("nativeListening")), repeatLoop(decoded, result.loopStartFrame, result.loopEndFrameExclusive), WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, files.getValue("matchedListening")), repeatLoop(decodedMatched, result.loopStartFrame, result.loopEndFrameExclusive), WavWriter.BitDepth.PCM_16)
            loop = heldEvidence(result) + linkedMapOf(
                "exportedNativePcm16SeamError" to nativeSeam,
                "exportedMatchedPcm16SeamError" to matchedSeam,
                "evidence" to "The seam compares independent preceding and exported cycles. The eight-repeat preview is listening material, not seam evidence.",
            )
        }
        val classification = Classifier.classify(decoded)
        println("MERIDIAN bank ${index + 1}/8 ${patch.name}: ${number(renderMillis, 1)} ms")
        return linkedMapOf(
            "id" to id, "name" to patch.name, "description" to MeridianPresets.roleDescription(patch.name),
            "model" to patch.model, "voice" to patch.voice.name, "midi" to midi, "note" to noteName(midi),
            "velocity" to patch.velocity, "seedContext" to patch.seedContext, "macros" to patch.macros,
            "held" to held, "files" to files, "durationSeconds" to native.durationSeconds,
            "native" to metrics(native), "matched" to metrics(matched), "decodedNativePcm16" to metrics(decoded),
            "nativeSampleSha256" to samplesHash(native.samples),
            "nativeWavSha256" to sha(File(root, files.getValue("native")).readBytes()),
            "matchedWavSha256" to sha(File(root, files.getValue("matched")).readBytes()),
            "renderMillis" to renderMillis, "renderScope" to "One measured pass including setup and normal output conditioning; no phone performance claim.",
            "recipeReplayIdentical" to true, "loop" to loop,
            "pitch" to pitch(decoded, midi, result?.loopStartFrame),
            "classifier" to linkedMapOf("class" to classification.drumClass.name, "confidence" to classification.confidence),
            "waveform" to waveform(native.samples),
        )
    }

    private fun heldEvidence(result: Meridian.HeldResult): Map<String, Any?> = linkedMapOf(
        "loopStartFrame" to result.loopStartFrame, "loopEndFrameExclusive" to result.loopEndFrameExclusive,
        "realPreviousCycleSeamError" to result.seamError, "cycleError" to result.cycleError,
        "stateError" to result.stateError, "noiseStateError" to result.noiseStateError,
        "loopSeconds" to result.loopFrames.toDouble() / Dsp.RATE,
        "nominalSwingPeriodSeconds" to result.nominalSwingPeriodSeconds,
        "effectiveSwingPeriodSeconds" to result.effectiveSwingPeriodSeconds,
        "swingPeriodChangePercent" to 100 * (result.effectiveSwingPeriodSeconds / result.nominalSwingPeriodSeconds - 1),
        "requestedRootHz" to result.rootHz, "forcingGridRootHz" to result.rootGridHz,
        "forcingGridErrorCents" to result.rootCents, "fixedOrientationRadians" to result.fixedOrientation,
        "activeSurfaces" to result.activeSurfaces, "rawPeak" to result.rawPeak, "peakModalEnergy" to result.peakEnergy,
        "inputHashes" to result.inputHashes.map { java.lang.Long.toUnsignedString(it, 16).padStart(16, '0') },
        "roughnessHashes" to result.roughnessHashes.map { java.lang.Long.toUnsignedString(it, 16).padStart(16, '0') },
    )

    private fun readPads(root: File, kitDirectory: File, kit: Map<String, JsonValue>): List<PadAudio> {
        val patches = MeridianKits.patches()
        val pads = kit.getValue("pads").arr()
        check(patches.size == 16 && pads.size == 16)
        return pads.mapIndexed { index, value ->
            val pad = value.obj()
            val label = "A${(index + 1).toString().padStart(2, '0')}"
            val wav = File(kitDirectory, pad.getValue("sampleFile").str())
            val recipeFile = File(kitDirectory, pad.getValue("recipeFile").str())
            val recipe = PadRecipe.fromJsonText(recipeFile.readText())
            check(recipe == MeridianKits.sourceRecipe(patches[index])) { "$label: kit recipe differs from its saved source" }
            val snip = WavReader.read(wav)
            checkAudio(label, snip)
            check(WavReader.readSmpl(wav)?.loop == null) { "$label: the drum pad must be a finite capture" }
            val webSample = File(root, "pads/$label.wav").also { it.parentFile.mkdirs() }
            val webRecipe = File(root, "pad-recipes/$label.json").also { it.parentFile.mkdirs() }
            val sampleBytes = wav.readBytes()
            val recipeBytes = recipeFile.readBytes()
            webSample.writeBytes(sampleBytes)
            webRecipe.writeBytes(recipeBytes)
            check(sha(webSample.readBytes()) == sha(sampleBytes) && sha(webRecipe.readBytes()) == sha(recipeBytes))
            PadAudio(label, patches[index], snip, relative(root, webSample), relative(root, webRecipe), sha(sampleBytes))
        }
    }

    /** Public downloads are complete packages; raw program trees remain local export evidence. */
    private fun copyDownloads(root: File, kitDirectory: File, kitArchive: File, kit: Map<String, JsonValue>): List<Map<String, Any?>> {
        val sources = listOf(Triple("pads", "Meridian Kit", kitArchive)) + kit.getValue("heldInstruments").arr().map { value ->
            val instrument = value.obj()
            Triple("keyboard", instrument.getValue("preset").str(), File(kitDirectory, instrument.getValue("archiveFile").str()).canonicalFile)
        }
        return sources.map { (kind, name, archive) ->
            check(archive.isFile && archive.length() < 25_000_000) { "$name package exceeds the initial 25 MB download limit" }
            val webFile = File(root, "downloads/${archive.name}").also { it.parentFile.mkdirs() }
            val bytes = archive.readBytes()
            webFile.writeBytes(bytes)
            check(sha(webFile.readBytes()) == sha(bytes))
            linkedMapOf<String, Any?>(
                "kind" to kind, "name" to name, "webUrl" to relative(root, webFile),
                "archiveArtifactPath" to relative(root, archive), "bytes" to bytes.size, "sha256" to sha(bytes),
                "contents" to if (kind == "pads") "Sixteen finite one-shot pads, recipes, native MPC 3 track, MPC 2 program and samples" else "Thirteen native zones C2 to C5, three physical velocity layers, real sustain loops, native MPC 3 and MPC 2 programs with samples",
            )
        }
    }

    private fun renderPhrase(root: File, pads: List<PadAudio>): Map<String, Any?> {
        val bpm = 100.0
        val beatsToSeconds = 60 / bpm
        val walk = listOf(0, 1, 2, 3, 4, 6, 7, 5)
        val slots = walk + (8..15).toList()
        val accents = listOf(.90, .70, .82, .74, .86, .68, .80, .72, .86, .74, .80, .90, .74, .76, .80, .72)
        val events = slots.mapIndexed { index, slot ->
            linkedMapOf<String, Any?>(
                "beat" to index, "seconds" to index * beatsToSeconds,
                "pad" to pads[slot].label, "slot" to slot + 1, "sampleIndex" to slot, "sampleFile" to pads[slot].sampleFile,
                "sampleWavSha256" to pads[slot].wavSha256, "eventGain" to accents[index],
                "nativeVelocity" to pads[slot].patch.velocity,
                "playback" to "One complete exported pad sample, no retuning or per-hit normalization",
            )
        }
        val frames = slots.indices.maxOf { index ->
            (index * beatsToSeconds * Dsp.RATE).roundToInt() + pads[slots[index]].sample.frameCount
        }
        val mix = DoubleArray(frames)
        for (i in slots.indices) {
            val from = (i * beatsToSeconds * Dsp.RATE).roundToInt()
            val sample = pads[slots[i]].sample.samples
            for (j in sample.indices) mix[from + j] += sample[j] * accents[i]
        }
        val peak = mix.maxOf { abs(it) }
        val gain = minOf(1.0, .85 / peak.coerceAtLeast(1e-12))
        val native = Snip(FloatArray(mix.size) { (mix[it] * gain).toFloat() }, 1, Dsp.RATE)
        val matched = AuditionLevel.level(native)
        checkAudio("musical phrase", native)
        val files = linkedMapOf(
            "native" to "phrase/meridian-phrase-native.wav", "matched" to "phrase/meridian-phrase-matched.wav",
            "events" to "phrase/events.json",
        )
        WavWriter.write(File(root, files.getValue("native")), native, WavWriter.BitDepth.PCM_16)
        WavWriter.write(File(root, files.getValue("matched")), matched, WavWriter.BitDepth.PCM_16)
        val schedule = linkedMapOf<String, Any?>(
            "bpm" to bpm, "beatsPerBar" to 4, "events" to events,
            "sharedMixGain" to gain, "preGainMixPeak" to peak,
            "source" to "The exact staged PCM24 kit WAVs identified by per-event hashes",
            "mixing" to "The first two bars walk the native C-minor pads; the next two answer with eight A3 material presets. Every source plays once, including the finite held captures. Explicit event gains are musical accents; one common final gain preserves their relative levels.",
        )
        File(root, files.getValue("events")).writeText(Json.write(json(schedule)))
        return linkedMapOf(
            "title" to "A walk, then material replies", "detail" to "Four bars at 100 BPM, followed by the actual remaining tails. First the native note pads, then all eight material presets.",
            "files" to files, "durationSeconds" to native.durationSeconds,
            "native" to metrics(native), "matched" to metrics(matched),
            "sharedMixGain" to gain, "preGainMixPeak" to peak,
            "nativeWavSha256" to sha(File(root, files.getValue("native")).readBytes()),
            "schedule" to schedule,
        )
    }

    private fun copyReference(root: File, previous: File, prior: Map<String, JsonValue>): Map<String, Any?> {
        val original = prior.getValue("clips").arr().single { it.obj().getValue("id").str() == "finite-default" }.obj()
        val oldFiles = original.getValue("files").obj()
        val files = linkedMapOf(
            "native" to "reference/round2-default-native.wav", "matched" to "reference/round2-default-matched.wav",
            "recipe" to "reference/round2-default.json", "evidence" to "reference/round2-evidence.json",
        )
        val hashes = linkedMapOf<String, String>()
        for (kind in listOf("native", "matched", "recipe")) {
            val bytes = File(previous, oldFiles.getValue(kind).str()).readBytes()
            val to = File(root, files.getValue(kind)).also { it.parentFile.mkdirs() }
            to.writeBytes(bytes)
            hashes[kind] = sha(bytes)
            check(sha(to.readBytes()) == hashes[kind])
        }
        File(root, files.getValue("evidence")).writeText(Json.write(json(linkedMapOf(
            "originalClip" to JsonValue.Obj(original), "originalSourceCommit" to prior["sourceCommit"],
            "originalRetainedSourceFilesSha256" to prior["retainedSourceFilesSha256"], "copySha256" to hashes,
            "note" to "Copied unchanged from the accepted round-two audition; not regenerated.",
        ))))
        return linkedMapOf(
            "title" to "The accepted starting swing", "files" to files, "copySha256" to hashes,
            "copiedUnchanged" to true, "model" to 2,
            "detail" to "The unchanged round-two default, kept beside FIRST ARC so the sound already heard remains available.",
        )
    }

    private fun retainSource(root: File, repository: File, paths: Collection<String>): Map<String, String> =
        paths.distinct().sorted().associateWith { path ->
            val bytes = File(repository, path).readBytes()
            File(root, "source/$path").also { it.parentFile.mkdirs() }.writeBytes(bytes)
            sha(bytes)
        }

    private fun repeatLoop(source: Snip, start: Int, end: Int): Snip {
        val n = end - start
        check(n > 0 && start >= 0 && end <= source.frameCount)
        return Snip(FloatArray(n * 8) { source.samples[start + it % n] }, 1, Dsp.RATE)
    }

    private fun pitch(snip: Snip, midi: Int, loopStart: Int?): Map<String, Any?> {
        val from = if (loopStart == null) .18f else loopStart.toFloat() / Dsp.RATE
        val window = minOf(.35f, snip.durationSeconds - from)
        val estimate = Pitch.detect(snip, from, window)
        val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
        return linkedMapOf(
            "method" to "Pitch.detect on decoded native PCM16", "fromSeconds" to from, "windowSeconds" to window,
            "targetHz" to expected, "hz" to estimate?.hz, "confidence" to estimate?.confidence,
            "errorCents" to estimate?.let { 1200 * ln(it.hz / expected) / ln(2.0) },
            "scope" to "Local full-mix observation, separate from natural modal tuning and held forcing-grid fit. Finite estimator and octave limitations remain disclosed.",
        )
    }

    private fun checkAudio(id: String, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE)
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id: invalid audio" }
        check(snip.peak() > 1e-7f && snip.peak() < 1f) { "$id: silent or clipping audio" }
    }

    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf(
        "peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "loudness" to Loudness.of(snip),
    )

    private fun waveform(samples: FloatArray): List<Double> {
        val peak = samples.maxOf { abs(it) }.coerceAtLeast(1e-9f)
        return (0 until 96).map { bin ->
            val from = bin * samples.size / 96
            val end = (bin + 1) * samples.size / 96
            (from until end).maxOf { abs(samples[it]) }.toDouble() / peak
        }
    }

    private fun sameBits(a: Snip, b: Snip) = a.channels == b.channels && a.sampleRate == b.sampleRate &&
        a.samples.size == b.samples.size && a.samples.indices.all { a.samples[it].toRawBits() == b.samples[it].toRawBits() }
    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return sha(bytes.array())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun relative(root: File, file: File) = file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
    private fun slug(name: String) = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[Math.floorMod(midi, 12)] + (midi / 12 - 1)
    private fun number(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun url(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

    private fun page(reference: Map<String, Any?>, cards: List<Map<String, Any?>>, pads: List<Map<String, Any?>>, phrase: Map<String, Any?>,
        downloads: List<Map<String, Any?>>): String = buildString {
        append("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Meridian · Candidate bank</title><style>:root{color-scheme:dark;--bg:#101b25;--panel:#182936;--line:#344b58;--text:#eee7d9;--muted:#afc1cc;--accent:#9bd4bd}*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:radial-gradient(ellipse at 80% 0,#234139 0,transparent 45%),var(--bg);color:var(--text);font:16px/1.55 system-ui}main{max-width:1160px;margin:auto;padding:34px 25px 70px}nav{display:flex;gap:22px;flex-wrap:wrap;padding-bottom:28px;font-size:14px}a{color:var(--accent);text-underline-offset:4px}h1{font-size:clamp(36px,7vw,68px);line-height:1.05;margin:15px 0 20px;letter-spacing:-.04em}h2{font-size:29px;letter-spacing:-.02em;margin:45px 0 10px}h3{font-size:21px;margin:10px 0}p{color:var(--muted);max-width:850px}.eyebrow{font-size:13px;letter-spacing:.16em;text-transform:uppercase;color:var(--accent)}.lead{font-size:20px;max-width:780px}.cards{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.card,.reference,.phrase,.download{background:linear-gradient(145deg,#1b2f3d,#14232e);border:1px solid var(--line);border-radius:16px;padding:23px;min-width:0}.card p{min-height:50px}.badge{display:inline-block;border:1px solid #4c6b64;border-radius:20px;padding:3px 10px;font-size:12px;color:#c2e1d0}.meta,.links{display:flex;gap:12px;flex-wrap:wrap;font-size:13px;color:var(--muted)}.wave{display:block;width:100%;height:45px;margin:12px 0 17px}.audio-row{display:flex;align-items:center;gap:10px;margin:10px 0}.audio-row>span{width:65px;font-size:12px;color:var(--muted)}audio{width:100%;min-width:0;height:38px}.audio-row audio{flex:1}.macros{font:12px/1.8 ui-monospace,monospace;color:#a7bdc9;padding:10px 0}.pad-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:22px 0}.pad{display:flex;flex-direction:column;align-items:flex-start;justify-content:space-between;gap:8px;min-height:136px;padding:16px;border:1px solid #45675e;border-radius:13px;background:linear-gradient(155deg,#24443d,#1a3030);color:var(--text);text-align:left;cursor:pointer;transition:transform .08s,background .08s}.pad:hover{background:#2d5549}.pad.active{transform:translateY(2px);background:#42816a;box-shadow:inset 0 0 20px #a5eac124}.pad small{color:#bbd3cc}.pad strong{font-size:14px}.pad .pad-index{font:12px ui-monospace,monospace;color:#a8d6bf}.tools{display:flex;align-items:center;gap:16px;flex-wrap:wrap;color:var(--muted);font-size:14px}button.stop{border:1px solid var(--line);background:var(--panel);color:var(--text);padding:9px 15px;border-radius:8px;cursor:pointer}.downloads{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:15px}.button{display:inline-block;border:1px solid #5c8874;border-radius:9px;padding:9px 13px;text-decoration:none;background:#28453c}.fine{font-size:13px}details{margin-top:15px;font-size:13px;color:var(--muted)}summary{cursor:pointer}pre{overflow:auto;background:#0c1720;padding:15px;font:11px/1.45 ui-monospace,monospace;border-radius:9px;max-height:480px}.rule{border-top:1px solid var(--line);margin-top:34px;padding-top:15px}kbd{font:11px ui-monospace,monospace}#pad-status{min-height:24px}@media(max-width:700px){main{padding:24px 15px 50px}.cards,.downloads{grid-template-columns:1fr}.pad-grid{gap:7px}.pad{padding:10px;min-height:124px}.pad strong{font-size:12px}.pad small{font-size:11px}.card,.reference,.phrase,.download{padding:18px}.audio-row{flex-wrap:wrap}.audio-row>span{width:100%}.audio-row audio{flex-basis:100%}nav{gap:16px}}</style><main><nav><a href="#bank">Eight sounds</a><a href="#pads">Play the pads</a><a href="#phrase">A short phrase</a><a href="#downloads">Downloads</a><a href="#evidence">Evidence</a></nav><header><div class="eyebrow">Meridian / Candidate bank</div><h1>One swing.<br>Eight material journeys.</h1><p class="lead">The changing contacts and retained ringing now have eight saved starting points, a sixteen-pad kit, and two sustained keyboard instruments.</p><p>The earlier engine auditions received positive feedback. FIRST ARC preserves that starting sound; the seven new recipes await listening. The instrument itself is unchanged in this round.</p></header>""")
        @Suppress("UNCHECKED_CAST") val refFiles = reference.getValue("files") as Map<String, String>
        append("<section class=\"reference\"><span class=\"badge\">Accepted reference · copied unchanged</span><h3>The starting swing</h3><p>${escape(reference.getValue("detail").toString())}</p>")
        audioRows(refFiles)
        append("<div class=\"links\"><a href=\"${url(refFiles.getValue("recipe"))}\">Original recipe</a><a href=\"${url(refFiles.getValue("evidence"))}\">Copy evidence</a></div></section>")
        append("<section id=\"bank\"><h2>Eight saved sounds</h2><p>Matched previews make character easier to compare. Native previews keep the normal engine level. The two sustained sounds play their exported loop eight times; their sampler downloads retain real loop markers.</p><div class=\"cards\">")
        for (card in cards) {
            @Suppress("UNCHECKED_CAST") val files = card.getValue("files") as Map<String, String>
            @Suppress("UNCHECKED_CAST") val macros = card.getValue("macros") as Map<String, Float>
            append("<article class=\"card\" id=\"${card["id"]}\"><div class=\"meta\"><span class=\"badge\">${if (card["held"] == true) "Sustain · 8-loop preview" else "Finite journey"}</span><span>${card["note"]} · velocity ${card["velocity"]}</span></div><h3>${escape(card.getValue("name").toString())}</h3><p>${escape(card.getValue("description").toString())}</p>")
            @Suppress("UNCHECKED_CAST") val wave = card.getValue("waveform") as List<Double>
            append(waveSvg(wave))
            audioRows(files)
            append("<div class=\"macros\">${listOf("LATITUDE", "LENGTH", "REACH", "SURFACE", "MEMORY").joinToString(" · ") { "$it ${number(macros.getValue(it).toDouble(), 2)}" }}</div>")
            append("<div class=\"links\"><a href=\"${url(files.getValue("native"))}\">Native sampler WAV</a><a href=\"${url(files.getValue("matched"))}\">Matched WAV</a><a href=\"${url(files.getValue("recipe"))}\">Saved recipe</a></div>")
            append("<details><summary>Settings and measured evidence</summary><pre>${escape(Json.write(json(card.filterKeys { it !in setOf("waveform", "files") })))}</pre></details></article>")
        }
        append("</div></section><section id=\"pads\"><h2>Play the sixteen pads</h2><p>These buttons play the exact kit samples at one shared playback volume. Every pad plays once. The last two are short captures of the sustained sounds; the separate keyboard downloads below keep sustaining while a note is held.</p><div class=\"tools\"><button class=\"stop\" id=\"stop-all\">Stop all</button><label>Pad volume <input id=\"pad-volume\" type=\"range\" min=\"0\" max=\"1\" step=\"0.01\" value=\"0.35\"></label><span>Keys: 1–4 / QWER / ASDF / ZXCV</span></div><div class=\"pad-grid\">")
        val keys = "1234qwerasdfzxcv"
        val displayOrder = (12..15).toList() + (8..11).toList() + (4..7).toList() + (0..3).toList()
        for ((position, slot) in displayOrder.withIndex()) {
            val pad = pads[slot]
            append("<button class=\"pad\" data-src=\"${url(pad.getValue("sampleFile").toString())}\" data-key=\"${keys[position]}\" data-label=\"${pad["label"]}\"><span class=\"pad-index\">${pad["label"]} <kbd>[${keys[position].uppercaseChar()}]</kbd></span><strong>${escape(pad.getValue("name").toString())}</strong><small>${pad["note"]} · velocity ${pad["velocity"]}<br>${if (pad["playback"] == "FINITE_HELD_CAPTURE") "Finite sustain capture" else "One-shot"}</small></button>")
        }
        append("</div><p class=\"fine\" id=\"pad-status\" aria-live=\"polite\">Choose a pad or use the keyboard.</p></section><section id=\"phrase\"><h2>A walk, then replies</h2><div class=\"phrase\"><p>${escape(phrase.getValue("detail").toString())} The exact kit samples supply every note. One shared mix gain preserves their saved touch and the event accents.</p>")
        @Suppress("UNCHECKED_CAST") val phraseFiles = phrase.getValue("files") as Map<String, String>
        audioRows(phraseFiles)
        append("<div class=\"links\"><a href=\"${url(phraseFiles.getValue("events"))}\">Replayable event schedule</a><a href=\"${url(phraseFiles.getValue("native"))}\">Native phrase WAV</a></div></div></section>")
        append("<section id=\"downloads\"><h2>Take it to the sampler</h2><p>The pad kit plays sixteen finite samples. The two keyboard instruments sustain with genuine sample loops and respond with separately rendered soft, medium and hard touches. Every download is a complete package with its samples.</p><div class=\"downloads\">")
        for (download in downloads) {
            val isPads = download["kind"] == "pads"
            append("<div class=\"download\"><span class=\"eyebrow\">${if (isPads) "16 pads" else "Sustained keyboard"}</span><h3>${escape(download.getValue("name").toString())}</h3><p>${if (isPads) "A note walk followed by all eight saved sounds. Short sustain captures occupy the last two pads." else "Native notes from C2 to C5, with three contact strengths. Use this keyboard package for held notes."}</p><p class=\"fine\">MPC 3 + MPC 2 included · ${number((download.getValue("bytes") as Number).toDouble() / 1_000_000, 1)} MB</p><a class=\"button\" href=\"${url(download.getValue("webUrl").toString())}\">${if (isPads) "Download pad kit" else "Download instrument"}</a></div>")
        }
        append("</div><p><a href=\"bank.zip\">Download the audition bank</a> · <a href=\"meridian-bank.patch\">Source patch</a></p></section><section id=\"evidence\" class=\"rule\"><h2>What was checked</h2><p>The accepted reference is copied unchanged. Recipes reproduce their samples, held sampler metadata is decoded and checked, and loop seams compare real preceding and exported cycles. Repeating an already exported loop is used for listening only. Source snapshots identify the code actually used for this bank.</p><p>These new presets still need a listening verdict. The earlier finite pitch and octave-estimator limits remain disclosed; this round does not retune the instrument to improve a number.</p><div class=\"links\"><a href=\"manifest.json\">Complete bank, pad and source evidence</a><a href=\"diagnostics.json\">Numerical summary</a></div><h3>Why it is built this way</h3><p>The eight recipes keep one native note and noise context so the contact behavior carries their differences. The kit's first eight notes are rendered at their own pitches so swing timing stays fixed. Separate held keyboard packages use supported sustain metadata, while the pad program stays an ordinary one-shot bank.</p></section></main><script>const livePads=new Set();const volume=document.getElementById('pad-volume');const status=document.getElementById('pad-status');function playPad(button){const audio=new Audio(button.dataset.src);audio.volume=Number(volume.value);livePads.add(audio);button.classList.add('active');status.textContent=button.dataset.label+' · '+button.querySelector('strong').textContent;const done=()=>{livePads.delete(audio);button.classList.remove('active')};audio.addEventListener('ended',done,{once:true});audio.addEventListener('error',done,{once:true});audio.play().catch(()=>{done();status.textContent='Playback is unavailable. Use the downloadable samples.'})}document.querySelectorAll('.pad').forEach(button=>button.addEventListener('click',()=>playPad(button)));document.addEventListener('keydown',event=>{if(event.repeat||event.ctrlKey||event.metaKey||event.altKey||['INPUT','TEXTAREA','SELECT'].includes(document.activeElement.tagName))return;const key=event.key.toLowerCase();const button=[...document.querySelectorAll('.pad')].find(b=>b.dataset.key===key);if(button){event.preventDefault();playPad(button)}});volume.addEventListener('input',()=>livePads.forEach(a=>a.volume=Number(volume.value)));document.getElementById('stop-all').addEventListener('click',()=>{livePads.forEach(a=>{a.pause();a.currentTime=0});livePads.clear();document.querySelectorAll('audio').forEach(a=>a.pause());document.querySelectorAll('.pad').forEach(b=>b.classList.remove('active'));status.textContent='Stopped.'});document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})));</script></html>")
    }

    private fun StringBuilder.audioRows(files: Map<String, String>) {
        for ((label, kind) in listOf("Matched" to "matched", "Native" to "native")) {
            val file = files[kind + "Listening"] ?: files.getValue(kind)
            append("<div class=\"audio-row\"><span>$label</span><audio controls preload=\"none\" src=\"${url(file)}\"></audio></div>")
        }
    }

    private fun waveSvg(wave: List<Double>) = buildString {
        append("<svg class=\"wave\" viewBox=\"0 0 480 45\" role=\"img\" aria-label=\"Amplitude across the sample\">")
        for ((index, amplitude) in wave.withIndex()) {
            val height = maxOf(1.0, amplitude * 40)
            append("<rect x=\"${index * 5}\" y=\"${number((45 - height) / 2, 2)}\" width=\"3\" height=\"${number(height, 2)}\" rx=\"1\" fill=\"#78baa0\"/>")
        }
        append("</svg>")
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Cannot record source provenance: $result" }
        return result
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> { check(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported bank evidence ${value.javaClass.name}")
    }
}

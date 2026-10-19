package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A small, reproducible ONSET listening experiment, rendered by the actual JVM engine. */
object OnsetAuditionGenerator {
    private val blendSteps = listOf(0f, .25f, .5f, .75f, 1f)
    private const val BPM = 104.0
    private const val BARS = 2
    private const val STEPS_PER_BAR = 16
    private const val PACK_NAME = "onset-audition.zip"
    private const val KIT_PACK_NAME = "onset-mpc-kit.zip"
    private const val HAT_CHOKE_SECONDS = .005
    private val voices = listOf(OnsetVoice.KICK, OnsetVoice.SNARE, OnsetVoice.HAT_CLOSED, OnsetVoice.HAT_OPEN)

    private data class BeatHit(val voice: OnsetVoice, val step: Int, val gain: Float)
    private data class References(val clips: List<Map<String, Any?>>, val source: JsonValue)

    // These events and gains are identical in all three beat examples. No external backing or FX.
    private val beatHits: List<BeatHit> = buildList {
        val kicks = listOf(0 to .78f, 6 to .61f, 8 to .75f, 14 to .65f,
            16 to .78f, 23 to .59f, 24 to .75f, 26 to .57f, 30 to .65f)
        kicks.forEach { (step, gain) -> add(BeatHit(OnsetVoice.KICK, step, gain)) }
        listOf(4 to .5f, 12 to .54f, 20 to .5f, 28 to .56f, 27 to .2f)
            .forEach { (step, gain) -> add(BeatHit(OnsetVoice.SNARE, step, gain)) }
        val openSteps = setOf(6, 14, 22, 30)
        openSteps.forEach { step -> add(BeatHit(OnsetVoice.HAT_OPEN, step, .24f)) }
        for (step in 0 until BARS * STEPS_PER_BAR) {
            if (step in openSteps || step == 7 || step == 23) continue
            val gain = when (step % 4) { 0 -> .24f; 2 -> .21f; else -> .14f }
            add(BeatHit(OnsetVoice.HAT_CLOSED, step, gain))
        }
    }.sortedWith(compareBy<BeatHit> { it.step }.thenBy { it.voice.ordinal })

    @JvmStatic
    fun main(args: Array<String>) {
        val directories = args.filterNot { it.startsWith("--") }
        require(directories.size <= 1 && args.all { !it.startsWith("--") || it.startsWith("--reference-dir=") }) {
            "usage: OnsetAuditionGenerator [output-directory] [--reference-dir=first-pass-directory]"
        }
        require(args.count { it.startsWith("--reference-dir=") } <= 1) { "pass one reference directory" }
        check(Onset.MODEL_VERSION >= 3) { "four-voice audition needs an ONSET model 3 renderer" }
        val root = File(directories.firstOrNull() ?: "../testkit/onset-audition")
        require(root.mkdirs() || root.isDirectory) { "could not create ${root.absolutePath}" }
        // Read and preserve the first pass before replacing any current audition metadata.
        val referenceDir = args.singleOrNull { it.startsWith("--reference-dir=") }
            ?.substringAfter('=')?.let(::File) ?: root
        val references = preserveReferences(root, referenceDir)
        val preservation = acceptedBaseline(referenceDir)
        val clips = mutableListOf<Map<String, Any?>>()
        val renders = linkedMapOf<Pair<OnsetVoice, Float>, Snip>()

        for (voice in voices) {
            for (blend in blendSteps) {
                val macros = Onset.defaults(voice) + ("BLEND" to blend)
                val snip = Onset.render(voice, macros)
                validateAudio(snip, "${voice.name} at blend $blend")
                renders[voice to blend] = snip
                val id = "${voiceId(voice)}-${blendId(blend)}"
                val fileId = when (voice) {
                    OnsetVoice.KICK -> "kick-r2-${blendId(blend)}"
                    OnsetVoice.HAT_CLOSED -> id
                    else -> "${voiceId(voice)}-r${Onset.MODEL_VERSION}-${blendId(blend)}"
                }
                clips += writeClip(root, id, "${voiceTitle(voice)} · ${blendTitle(blend, voice)}", voice.name,
                    blend, "hit", voiceDescription(voice), snip, settings(voice, macros), fileId)
            }
            val row = blendSteps.map { renders.getValue(voice to it) }
            check(row.map { it.frameCount }.distinct().size == 1) { "blend changed ${voice.name} render duration" }
            check(row.first().samples.indices.any { abs(row.first().samples[it] - row.last().samples[it]) > 1e-5f }) {
                "${voice.name} endpoints are indistinguishable"
            }
        }

        val beats = listOf(0f, .5f, 1f).associateWith { blend ->
            renderBeat(voices.associateWith { renders.getValue(it to blend) })
        }
        // One common PCM headroom gain across the three raw bounces preserves their mix balance.
        val beatExportGain = minOf(1f, .99f / beats.values.maxOf { it.peak() }.coerceAtLeast(1e-9f))
        for ((blend, beat) in beats) {
            val id = "beat-r${Onset.MODEL_VERSION}-${blendId(blend)}"
            clips += writeClip(root, id, "Four-voice beat · ${blendTitle(blend)}", "KIT",
                blend, "beat", "The same four-voice pattern at 104 BPM. Timing, hit gains and the raw bus gain stay fixed; closed hats choke open hats with a 5 ms fade.",
                beat, linkedMapOf("voices" to voices.associate { voice ->
                    voice.name to settings(voice, Onset.defaults(voice) + ("BLEND" to blend))
                }, "bpm" to BPM, "bars" to BARS, "events" to beatEvents(),
                    "hatChoke" to chokeSettings(), "rawBusGain" to beatExportGain), id, beatExportGain)
        }

        check(clips.size == 23 && clips.map { it.getValue("id") }.distinct().size == 23)
        verifyUnchangedHats(clips, references.source)
        verifyAcceptedVoices(clips, preservation)
        val sourceIdentity = rendererIdentity()
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "rendererVersion" to Onset.MODEL_VERSION,
            "title" to "ONSET · four-voice audition",
            "subtitle" to "The accepted kick and closed hat, plus a new FM snare and open hat. Real dry SnipSnap renders.",
            "engine" to linkedMapOf(
                "id" to "ONSET", "version" to Onset.MODEL_VERSION,
                "voices" to voices.map { it.name },
                "controls" to listOf("TUNE", "DECAY", "BLEND", "SNAP", "BODY", "EDGE"),
                "settings" to voices.associate { it.name to settings(it, Onset.defaults(it)) },
                "sampleRate" to Dsp.RATE, "oversample" to Dsp.OVERSAMPLE,
                "identity" to sourceIdentity,
                "rawDefinition" to "Engine output before audition matching; any PCM export attenuation is recorded per clip.",
            ),
            "comparison" to linkedMapOf("blendSteps" to blendSteps, "bpm" to BPM, "bars" to BARS,
                "fixedBacking" to false, "beatVoices" to voices.map { it.name },
                "acceptedKickVersion" to 2, "stableClosedHatVersion" to 1,
                "voicesPendingOwnerListen" to listOf("SNARE", "HAT_OPEN"),
                "acceptedVoices" to listOf("KICK"), "stableVoices" to listOf("HAT_CLOSED"),
                "musicalDurationSeconds" to BARS * 4 * 60.0 / BPM,
                "tailDescription" to "The same full-hit ring-out buffer follows each two-bar pattern.",
                "events" to beatEvents(), "withinHitMotion" to false, "voiceCoupling" to false,
                "hatChoke" to chokeSettings(), "rawBeatBusGain" to beatExportGain,
                "effects" to emptyList<String>()),
            "audition" to linkedMapOf("method" to "AuditionLevel", "targetLoudest200msRms" to .03,
                "peakGuard" to .99, "defaultVariant" to "matched",
                "description" to "A single gain per complete clip matches its loudest 200 ms window. Raw files preserve engine balance."),
            "download" to PACK_NAME,
            "kitDownload" to linkedMapOf("href" to KIT_PACK_NAME, "title" to "16-pad MPC kit",
                "description" to "Four voices at BLEND 0, .25, .5 and 1. MPC 3 XTD plus MPC 2 XPM, with editable SnipSnap recipes. Hardware listening check pending."),
            "clips" to clips,
            "referenceClips" to references.clips,
            "referenceSource" to references.source,
            "preservation" to preservation,
        )
        val manifestText = Json.write(json(manifest))
        File(root, "manifest.json").writeText(manifestText + "\n")
        File(root, "settings.json").writeText(Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to manifest.getValue("engine"),
            "comparison" to manifest.getValue("comparison"),
            "clips" to clips.map { mapOf("id" to it.getValue("id"), "settings" to it.getValue("settings")) },
            "referenceClips" to references.clips.map { mapOf("id" to it.getValue("id"),
                "rendererVersion" to 1, "settings" to it.getValue("settings")) },
            "referenceSource" to references.source,
            "preservation" to preservation,
        ))) + "\n")
        File(root, "README.md").writeText(readme())
        copyPage(root, manifestText)
        val deliveredRaw = clips.filter { it["kind"] == "hit" }.associate { clip ->
            (OnsetVoice.valueOf(clip.getValue("voice").toString()) to (clip.getValue("blend") as Float)) to
                WavReader.read(File(root, clip.getValue("raw").toString()))
        }
        val kitPack = OnsetKitExport.write(root, deliveredRaw, File(root, "settings.json"))
        check(kitPack.name == KIT_PACK_NAME && kitPack.isFile) { "MPC helper did not write $KIT_PACK_NAME" }
        bundle(root, clips + references.clips, listOf(KIT_PACK_NAME))
        println("Wrote 20 dry hit comparisons, 3 four-voice beats, 3 preserved first-pass kicks, 52 audition WAVs, settings, manifest and MPC kit under ${root.absolutePath}")
    }

    private fun writeClip(root: File, id: String, title: String, voice: String, blend: Float,
                          kind: String, description: String, source: Snip,
                          settings: Map<String, Any?>, fileId: String = id,
                          rawExportGain: Float? = null): Map<String, Any?> {
        validateAudio(source, id)
        val exportGain = rawExportGain ?: minOf(1f, .99f / source.peak().coerceAtLeast(1e-9f))
        check(exportGain > 0f && source.peak() * exportGain <= .990001f) { "invalid raw PCM gain for $id" }
        val raw = Snip(FloatArray(source.samples.size) { source.samples[it] * exportGain }, source.channels, source.sampleRate)
        val matched = AuditionLevel.level(source)
        val rawPath = "audio/$fileId-raw.wav"
        val matchedPath = "audio/$fileId-matched.wav"
        val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
        val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
        // Measure the delivered PCM, so the listening evidence describes the actual downloads.
        val rawPcm = WavReader.read(rawFile)
        val matchedPcm = WavReader.read(matchedFile)
        validateAudio(rawPcm, "$id raw PCM")
        validateAudio(matchedPcm, "$id matched PCM")
        check(rawPcm.frameCount == matchedPcm.frameCount)
        val matchedGain = minOf(.03f / Loudness.of(source).coerceAtLeast(1e-9f),
            .99f / source.peak().coerceAtLeast(1e-9f))
        return linkedMapOf(
            "id" to id, "title" to title, "voice" to voice, "blend" to blend,
            "rendererVersion" to if (voice == "KIT") Onset.MODEL_VERSION else voiceModelVersion(OnsetVoice.valueOf(voice)),
            "renderEngineVersion" to Onset.MODEL_VERSION,
            "blendLabel" to blendTitle(blend, if (voice == "KIT") null else OnsetVoice.valueOf(voice)),
            "endpointLabels" to linkedMapOf("electronic" to "Electronic", "algorithmic" to
                if (voice == "KIT") "Algorithmic" else algorithmLabel(OnsetVoice.valueOf(voice))),
            "kind" to kind, "description" to description,
            "raw" to rawPath, "matched" to matchedPath,
            "durationSeconds" to rawPcm.durationSeconds,
            "settings" to settings,
            "measures" to linkedMapOf("raw" to measures(rawPcm), "matched" to measures(matchedPcm)),
            "audition" to linkedMapOf("method" to "AuditionLevel", "rawExportGain" to exportGain,
                "matchedGain" to matchedGain, "sourcePeak" to source.peak(),
                "sourceLoudestWindowRms" to Loudness.of(source),
                "targetLoudest200msRms" to .03, "peakGuard" to .99),
            "sha256" to linkedMapOf("raw" to sha256(rawFile), "matched" to sha256(matchedFile)),
        )
    }

    private fun settings(voice: OnsetVoice, macros: Map<String, Float>): Map<String, Any?> {
        val s = Onset.settingsFor(voice, macros)
        return linkedMapOf(
            "voice" to s.voice.name, "macros" to s.macros, "modelVersion" to s.modelVersion,
            "voiceModelVersion" to voiceModelVersion(voice),
            "electronicSource" to s.electronicSource, "physicalSource" to s.physicalSource,
            "algorithmicSource" to s.physicalSource,
            "electronicModel" to s.electronicModel, "physicalModel" to s.physicalModel,
            "algorithmicModel" to s.physicalModel,
            "rootHz" to s.rootHz, "transpositionSemitones" to s.transpositionSemitones,
            "electronicRootHz" to s.electronicRootHz, "physicalRootHz" to s.physicalRootHz,
            "t60Seconds" to s.t60Seconds, "electronicGain" to s.electronicGain,
            "physicalGain" to s.physicalGain, "outputGain" to s.outputGain,
            "dry" to true,
        )
    }

    private fun renderBeat(voices: Map<OnsetVoice, Snip>): Snip {
        val framesPerStep = Dsp.RATE * 60.0 / BPM / 4.0
        val musicalFrames = (BARS * STEPS_PER_BAR * framesPerStep).roundToInt()
        // The identical buffer includes complete tails rather than cutting the final offbeat.
        val tailFrames = voices.values.maxOf { it.frameCount }
        val out = FloatArray(musicalFrames + tailFrames)
        data class Placed(val hit: BeatHit, val snip: Snip, val start: Int, var end: Int,
                          var fadeStart: Int? = null)
        val placed = mutableListOf<Placed>()
        val fadeFrames = (HAT_CHOKE_SECONDS * Dsp.RATE).roundToInt().coerceAtLeast(1)
        for (hit in beatHits) {
            val snip = voices.getValue(hit.voice)
            check(snip.channels == 1 && snip.sampleRate == Dsp.RATE)
            val start = (hit.step * framesPerStep).roundToInt()
            if (hit.voice == OnsetVoice.HAT_CLOSED) {
                for (open in placed.filter { it.hit.voice == OnsetVoice.HAT_OPEN && it.end > start && it.fadeStart == null }) {
                    open.fadeStart = start
                    open.end = minOf(open.end, start + fadeFrames)
                }
            }
            placed += Placed(hit, snip, start, start + snip.frameCount)
        }
        for (voice in placed) {
            for (i in 0 until voice.end - voice.start) {
                val at = voice.start + i
                val fadeStart = voice.fadeStart
                val gain = if (fadeStart != null && at >= fadeStart)
                    (voice.end - at).toFloat() / (voice.end - fadeStart) else 1f
                out[at] += voice.snip.samples[i] * voice.hit.gain * gain
            }
        }
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }

    private fun chokeSettings(): Map<String, Any> = linkedMapOf(
        "source" to "HAT_CLOSED", "target" to "HAT_OPEN", "fadeSeconds" to HAT_CHOKE_SECONDS,
        "implementation" to "baked sample fade", "crossVoiceDspMatrix" to false,
    )

    private fun beatEvents(): List<Map<String, Any>> = beatHits.map {
        linkedMapOf("voice" to it.voice.name, "step" to it.step, "gain" to it.gain)
    }

    private fun acceptedBaseline(referenceDir: File): JsonValue {
        val file = File(referenceDir, "manifest.json")
        require(file.isFile) { "accepted model-2 audition is missing; pass -PonsetReferenceDir=/path/to/model-2-audition" }
        val previous = Json.parse(file.readText()).obj()
        val engineVersion = previous.getValue("engine").obj().getValue("version").int()
        val baseline = if (engineVersion == 2) {
            val stable = previous.getValue("clips").arr().filter {
                it.obj()["voice"]?.str() in listOf("KICK", "HAT_CLOSED") && it.obj()["kind"]?.str() == "hit"
            }
            check(stable.size == 10 && stable.all { it.obj().getValue("settings").obj().getValue("modelVersion").int() == 2 }) {
                "accepted model-2 snapshot must contain the ten original kick and closed-hat comparisons"
            }
            json(linkedMapOf("acceptedKickVersion" to 2, "stableClosedHatVersion" to 1,
                "sourceEngineVersion" to 2, "sourceManifestSha256" to sha256(file),
                "sourceIdentity" to previous.getValue("engine").obj().getValue("identity"), "clips" to stable))
        } else requireNotNull(previous["preservation"]) { "no accepted model-2 baseline in this audition" }
        val b = baseline.obj()
        check(b.getValue("acceptedKickVersion").int() == 2 && b.getValue("sourceEngineVersion").int() == 2)
        val stable = b.getValue("clips").arr()
        check(stable.size == 10)
        for (clip in stable) {
            val c = clip.obj()
            for (variant in listOf("raw", "matched")) {
                val path = c.getValue(variant).str()
                require(Regex("audio/[A-Za-z0-9._-]+[.]wav").matches(path)) { "invalid accepted audio path" }
                val source = File(referenceDir, path)
                check(source.isFile && sha256(source) == c.getValue("sha256").obj().getValue(variant).str()) {
                    "accepted audio does not match model-2 snapshot: ${c.getValue("id").str()} $variant"
                }
            }
        }
        return baseline
    }

    private fun verifyAcceptedVoices(clips: List<Map<String, Any?>>, baseline: JsonValue) {
        val stable = baseline.obj().getValue("clips").arr()
        val fields = listOf("voice", "macros", "voiceModelVersion", "electronicSource", "physicalSource",
            "electronicModel", "physicalModel", "rootHz", "transpositionSemitones", "electronicRootHz",
            "physicalRootHz", "t60Seconds", "electronicGain", "physicalGain", "outputGain", "dry")
        for (original in stable) {
            val old = original.obj()
            val id = old.getValue("id").str()
            val current = clips.single { it["id"] == id }
            check(json(current.getValue("sha256")) == old.getValue("sha256")) { "accepted audio changed: $id" }
            check(current.getValue("raw") == old.getValue("raw").str() && current.getValue("matched") == old.getValue("matched").str()) {
                "accepted audio URL changed: $id"
            }
            val before = old.getValue("settings").obj()
            val after = json(current.getValue("settings")).obj()
            for (field in fields) {
                val expected = if (field == "voiceModelVersion") before[field] ?:
                    JsonValue.Num(if (old.getValue("voice").str() == "KICK") 2.0 else 1.0)
                else before[field]
                check(expected == after[field]) { "accepted $id setting changed: $field" }
            }
        }
    }

    private fun preserveReferences(root: File, referenceDir: File): References {
        val manifestFile = File(referenceDir, "manifest.json")
        require(manifestFile.isFile) {
            "first-pass kick references are missing; pass -PonsetReferenceDir=/path/to/model-1-audition"
        }
        val previous = Json.parse(manifestFile.readText()).obj()
        val previousVersion = previous.getValue("engine").obj().getValue("version").int()
        val isFirstPass = previousVersion == 1
        val shelf = previous[if (isFirstPass) "clips" else "referenceClips"]?.arr().orEmpty()
        val selected = listOf(0f, .5f, 1f).map { blend ->
            val matches = shelf.filter { value ->
                val c = value.obj()
                c["voice"]?.str() == "KICK" && c["blend"]?.num()?.toFloat() == blend &&
                    c["kind"]?.str() == (if (isFirstPass) "hit" else "reference")
            }
            require(matches.size == 1) { "expected one model-1 kick reference at blend $blend" }
            val original = matches.single().obj()
            require(original.getValue("settings").obj().getValue("modelVersion").int() == 1) {
                "first-pass kick reference at blend $blend is not model 1"
            }
            if (!isFirstPass) require(original["rendererVersion"]?.int() == 1) {
                "retained kick reference at blend $blend has no model-1 renderer identity"
            }
            val retained = LinkedHashMap<String, Any?>(original)
            for (variant in listOf("raw", "matched")) {
                val oldPath = original.getValue(variant).str()
                require(Regex("audio/[A-Za-z0-9._-]+[.]wav").matches(oldPath)) { "invalid reference audio path" }
                val source = File(referenceDir, oldPath)
                val expectedHash = original.getValue("sha256").obj().getValue(variant).str()
                require(source.isFile && sha256(source) == expectedHash) {
                    "first-pass $variant kick at blend $blend does not match its saved hash"
                }
                val targetPath = "audio/previous-kick-r1-${blendId(blend)}-$variant.wav"
                val target = File(root, targetPath)
                target.parentFile.mkdirs()
                if (source.canonicalFile != target.canonicalFile) source.copyTo(target, overwrite = true)
                check(sha256(target) == expectedHash)
                validateAudio(WavReader.read(target), "preserved first-pass kick $blend $variant")
                retained[variant] = targetPath
            }
            retained["id"] = "previous-kick-${blendId(blend)}"
            retained["title"] = if (isFirstPass) "Previous kick · ${referenceBlendTitle(blend)}" else original.getValue("title")
            retained["originalId"] = original["originalId"] ?: original.getValue("id")
            retained["originalTitle"] = original["originalTitle"] ?: original.getValue("title")
            retained["originalDescription"] = original["originalDescription"] ?: original.getValue("description")
            retained["kind"] = "reference"
            retained["rendererVersion"] = 1
            retained["description"] = "The unchanged first-pass kick at this BLEND, retained for comparison with the revised kick."
            retained
        }
        val source = if (isFirstPass) json(linkedMapOf(
            "rendererVersion" to 1, "engine" to previous.getValue("engine"),
            "manifestSha256" to sha256(manifestFile),
            "unchangedHatSha256" to shelf.filter { it.obj()["voice"]?.str() == "HAT_CLOSED" }
                .associate { it.obj().getValue("id").str() to it.obj().getValue("sha256") },
            "description" to "First-pass kick WAVs copied without rerendering; settings, measures and hashes retained.",
        )) else requireNotNull(previous["referenceSource"]) { "retained references have no first-pass source metadata" }
        return References(selected, source)
    }

    private fun verifyUnchangedHats(clips: List<Map<String, Any?>>, source: JsonValue) {
        val expected = source.obj().getValue("unchangedHatSha256").obj()
        val hats = clips.filter { it["voice"] == "HAT_CLOSED" }
        check(hats.size == 5 && expected.size == 5) { "need five first-pass hat hashes to verify unchanged audio" }
        for (hat in hats) {
            check(json(hat.getValue("sha256")) == expected.getValue(hat.getValue("id").toString())) {
                "closed-hat audio changed from the first pass: ${hat.getValue("id")}"
            }
        }
    }

    private fun measures(snip: Snip): Map<String, Any?> {
        var power = 0.0
        var sum = 0.0
        for (sample in snip.samples) { power += sample.toDouble() * sample; sum += sample }
        val rms = sqrt(power / snip.samples.size)
        val peak = snip.peak().toDouble()
        return linkedMapOf("peak" to peak, "rms" to rms, "crestDb" to 20.0 * log10(peak / rms),
            "dc" to sum / snip.samples.size, "loudestWindowRms" to Loudness.of(snip),
            "durationSeconds" to snip.durationSeconds, "sampleRate" to snip.sampleRate,
            "channels" to snip.channels, "frames" to snip.frameCount, "finite" to true)
    }

    private fun validateAudio(snip: Snip, label: String) {
        check(snip.sampleRate == Dsp.RATE && snip.channels == 1) { "$label has an unexpected audio format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$label is empty or non-finite" }
        check(snip.peak() > 1e-5f) { "$label is silent" }
    }

    private fun copyPage(root: File, manifest: String) {
        for (name in listOf("index.html", "app.js", "styles.css")) {
            val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("onset-audition/$name")) {
                "missing onset-audition/$name resource"
            }
            val text = resource.bufferedReader().use { it.readText() }
            val contents = if (name == "index.html") {
                check("</body>" in text) { "audition template has no closing body" }
                val inline = "<script id=\"onset-manifest\" type=\"application/json\">${manifest.replace("<", "\\u003c")}</script>\n"
                text.replace("</body>", inline + "</body>")
            } else text
            File(root, name).writeText(contents)
        }
    }

    private fun bundle(root: File, clips: List<Map<String, Any?>>, extraFiles: List<String> = emptyList()) {
        val names = (listOf("index.html", "app.js", "styles.css", "manifest.json", "settings.json", "README.md") + extraFiles +
            clips.flatMap { listOf(it.getValue("raw").toString(), it.getValue("matched").toString()) }).sorted()
        ZipOutputStream(File(root, PACK_NAME).outputStream().buffered()).use { zip ->
            for (name in names) {
                val entry = ZipEntry(name).apply { time = 0L }
                zip.putNextEntry(entry)
                File(root, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun rendererIdentity(): Map<String, Any?> {
        val paths = listOf("src/main/kotlin/com/snipsnap/synth/Onset.kt",
            "src/main/kotlin/com/snipsnap/synth/OnsetPatch.kt",
            "src/main/kotlin/com/snipsnap/synth/OnsetKits.kt",
            "src/main/kotlin/com/snipsnap/synth/Thump.kt", "src/main/kotlin/com/snipsnap/synth/Skin.kt",
            "src/main/kotlin/com/snipsnap/synth/Tines.kt",
            "src/main/kotlin/com/snipsnap/synth/Modes.kt",
            "src/main/kotlin/com/snipsnap/synth/Dsp.kt", "src/main/kotlin/com/snipsnap/synth/Punch.kt",
            "src/test/kotlin/com/snipsnap/synth/OnsetAuditionGenerator.kt",
            "src/test/kotlin/com/snipsnap/synth/OnsetKitExport.kt",
            "src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt")
        return linkedMapOf("name" to "ONSET audition model ${Onset.MODEL_VERSION}", "implementation" to "SnipSnap JVM renderer",
            "sources" to paths.associateWith { path -> File(path).takeIf { it.isFile }?.let(::sha256) })
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun voiceId(voice: OnsetVoice) = when (voice) {
        OnsetVoice.KICK -> "kick"; OnsetVoice.SNARE -> "snare"
        OnsetVoice.HAT_CLOSED -> "hat"; OnsetVoice.HAT_OPEN -> "open-hat"
    }
    private fun voiceTitle(voice: OnsetVoice) = when (voice) {
        OnsetVoice.KICK -> "Kick"; OnsetVoice.SNARE -> "Snare"
        OnsetVoice.HAT_CLOSED -> "Closed hat"; OnsetVoice.HAT_OPEN -> "Open hat"
    }
    private fun voiceModelVersion(voice: OnsetVoice) = when (voice) {
        OnsetVoice.KICK -> 2; OnsetVoice.HAT_CLOSED -> 1; else -> Onset.MODEL_VERSION
    }
    private fun algorithmLabel(voice: OnsetVoice) = when (voice) {
        OnsetVoice.KICK -> "Modal membrane"; OnsetVoice.SNARE -> "FM"
        OnsetVoice.HAT_CLOSED, OnsetVoice.HAT_OPEN -> "Resonant noise"
    }
    private fun voiceDescription(voice: OnsetVoice): String {
        val identity = when (voice) {
            OnsetVoice.KICK -> "The accepted model-2 kick with independent membrane modes."
            OnsetVoice.HAT_CLOSED -> "The unchanged first-pass closed hat: square cluster to resonant noise."
            OnsetVoice.SNARE -> "New snare: THUMP membrane body to two-operator FM, under shared wires. Awaiting owner listening."
            OnsetVoice.HAT_OPEN -> "New open hat: electronic square cluster to resonant noise, with a shared longer decay. Awaiting owner listening."
        }
        return "$identity Only BLEND changes across this row; the other five controls stay fixed."
    }
    private fun blendId(blend: Float) = String.format(Locale.ROOT, "%03d", (blend * 100).roundToInt())
    private fun blendTitle(blend: Float, voice: OnsetVoice? = null): String {
        val label = voice?.let(::algorithmLabel) ?: "Algorithmic"
        return when (blend) { 0f -> "Electronic"; .5f -> "Hybrid"; 1f -> label; else -> "${(blend * 100).roundToInt()}% ${label.lowercase()}" }
    }
    private fun referenceBlendTitle(blend: Float) = when (blend) { 0f -> "Electronic"; .5f -> "Hybrid"; else -> "Physical" }

    private fun readme() = """
        ONSET four-voice audition, model ${Onset.MODEL_VERSION}
        =====================================

        Twenty dry single-hit comparisons: kick, snare, closed hat and open hat at BLEND
        0, .25, .5, .75 and 1. Three two-bar examples at 104 BPM use exactly the same four-voice
        events, hit gains and raw bus gain. Closed hats choke open hats with a 5 ms sample fade.
        Three previous-kick references preserve the original raw and matched first-pass files
        at BLEND 0, .5 and 1. Their settings, measurements and hashes are retained unchanged.
        The accepted model-2 kick and original closed-hat WAVs keep their exact bytes and URLs.
        New snare and open-hat clips await owner listening. New voice/beat files are versioned.
        There is no fixed backing, reverb, delay, within-hit blend motion or cross-voice DSP matrix.
        SNARE's algorithmic endpoint is FM; the kick uses modal membrane synthesis and hats use
        resonant noise. "Physical" is not a shared description of these three different models.

        These WAVs are rendered by the actual SnipSnap JVM ONSET engine. The browser plays the
        exported audio. Raw means engine output before audition matching, with PCM attenuation
        only if necessary to prevent clipping; the manifest records that gain. Matched uses
        the repository's AuditionLevel: loudest 200 ms RMS .03, with a .99 peak guard.

        WAV format: mono 24-bit PCM, 44.1 kHz. Measurements describe the delivered PCM files.
        settings.json records the complete controls, source identities and beat events.
        manifest.json adds per-clip metadata, measurements and SHA-256 hashes.

        Open index.html from this folder. Its embedded manifest also supports offline listening.
        Reproduce the pack from the repository with: ./gradlew :synth:generateOnsetAudition
        An initial four-voice build also needs -PonsetReferenceDir=/path/to/model-2-audition.
        Later runs reuse the retained first-pass references already present in this folder.
        onset-mpc-kit.zip contains a separate 16-pad XTD/XPM sample kit (BLEND rows 0/.25/.5/1;
        columns kick/snare/closed hat/open hat), exact raw PCM, source settings and typed SnipSnap
        recipes. Import the kit in SnipSnap, use SYNTH > REOPEN PAD, edit and UPDATE PAD, then
        export again. Saved recipes retain all six controls and the renderer version. Hardware
        listening is still pending. MPC plays the rendered samples rather than live ONSET DSP.
    """.trimIndent() + "\n"

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported ONSET manifest value: ${value.javaClass}")
    }
}

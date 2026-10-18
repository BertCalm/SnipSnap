package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Extend the published R3 audition without rerendering or reidentifying any accepted sound. */
object RegistersTransistorAuditionGenerator {
    private const val REVISION = "r4-transistor"
    private const val R3_REVISION = "r3-airflow"
    private const val R3_AUDIO_HASH = "a79d2f2f9e35a7427bad6a6742aba88b8a7751797d5789bee4b9abe0540ea48f"
    private const val R3_MANIFEST_HASH = "3fc116d6d2db36947b90caef0c773f02f7b4e755dc1d90e1330e1fede58284b5"
    private const val TARGET = .03
    private const val SEAM_LIMIT = .001
    private const val CYCLE_LIMIT = .00001
    private val controls = listOf("COLOR", "AIR", "EDGE", "SPREAD", "MOTION")
    private val voice = RegistersVoice.TRANSISTOR

    private data class Case(
        val id: String,
        val section: String,
        val label: String,
        val description: String,
        val note: Int = 60,
        val velocity: Float = .8f,
        val overrides: Map<String, Float> = emptyMap(),
        val gesture: String? = null,
        val macro: String? = null,
        val setting: String? = null,
        val value: Float? = null,
        val preset: RegistersPatch? = null,
    )

    private data class Baseline(
        val root: File,
        val manifest: JsonValue,
        val clips: List<Map<String, Any?>>,
        val recipeHashes: Map<String, String>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--baseline=") } <= 1 &&
            args.filter { it.startsWith("--") }.all { it.startsWith("--baseline=") }) {
            "Supply one output directory and optional --baseline=<published R3 archive>."
        }
        val root = File(args.firstOrNull { !it.startsWith("--") }
            ?: "../testkit/registers-audition-r4").canonicalFile
        val baseline = loadBaseline(File(args.firstOrNull { it.startsWith("--baseline=") }?.substringAfter('=')
            ?: "/workspace/scratch/registers-r3-published"))
        check(root != baseline.root && !root.toPath().startsWith(baseline.root.toPath()) &&
            !baseline.root.toPath().startsWith(root.toPath())) {
            "The published R3 archive and R4 output must be separate directories."
        }
        check(root.mkdirs() || root.isDirectory)
        preserveArchive(root, baseline)
        val clips = baseline.clips.map { archived ->
            LinkedHashMap(archived).also { clip ->
                // Explicit R1/R2 scopes already describe their original sound identity. R3 overlays
                // remain available through the added R3 baseline even for these unchanged sounds.
                if (clip["reviewAudioHash"] == null) {
                    clip["reviewClipId"] = clip.getValue("id")
                    clip["reviewRevision"] = R3_REVISION
                    clip["reviewAudioHash"] = R3_AUDIO_HASH
                }
                clip["recipeSha256"] = baseline.recipeHashes.getValue(clip.getValue("id") as String)
                clip["retainedFromR3"] = true
            }
        }.toMutableList<Map<String, Any?>>()
        val cases = cases()
        check(cases.size == 31 && cases.map { it.id }.distinct().size == cases.size)
        for ((index, case) in cases.withIndex()) {
            check(clips.none { it["id"] == case.id })
            clips += render(root, case)
            println("REGISTERS R4 ${index + 1}/${cases.size}: ${case.id}")
        }
        check(clips.size == 116 && clips.map { it.getValue("id") }.distinct().size == clips.size)
        validateRetainedAssets(root, baseline)
        validateComparisonReferences(baseline.manifest, clips)
        validateTouch(clips)

        val repository = repositoryRoot()
        val sources = sourceFingerprints(repository, root)
        val audioHash = audioHash(clips)
        val old = baseline.manifest.obj()
        val r3Metadata = r3Metadata(baseline)
        val manifest = LinkedHashMap(plain(baseline.manifest) as Map<String, Any?>)
        manifest.putAll(linkedMapOf(
            "title" to "REGISTERS · Pipe + Musette + Transistor",
            "revision" to REVISION, "model" to Registers.MODEL_VERSION,
            "status" to "PIPE and MUSETTE working audition baseline; TRANSISTOR listening pending; physical phone and MPC listening pending",
            "voiceStatus" to mapOf("PIPE" to "working-audition-baseline", "MUSETTE" to "working-audition-baseline",
                "TRANSISTOR" to "listening-pending"),
            "clipCount" to clips.size, "audioHash" to audioHash,
            "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceFingerprints" to sources,
            "sourceFingerprint" to sha256(Json.write(json(sources)).toByteArray(Charsets.UTF_8)),
            "previous" to r3Metadata,
            "baselines" to old.getValue("baselines").arr().map(::plain) + r3Metadata,
            "metricNotes" to "All 85 published R3 recordings and recipes are copied byte-identical, including both historical comparison sets. The 31 new TRANSISTOR cases are dry mono 44.1 kHz PCM24 from model-4 Kotlin DSP. Raw preserves conditioned pre-level engine samples, with any PCM peak guard recorded as rawExportGain. Matched playback uses AuditionLevel: loudest 200 ms RMS 0.03 after SnipSnap's gentle low cut. Playback metrics are measured from decoded exported WAVs; sourceMetrics describe the unscaled float render. First-difference RMS is a brightness proxy, not spectral centroid. Held files contain three exact copies of a settled engine region only after independently synthesized adjacent cycles have passed recurrence checks. No effects, reverb, external samples or browser synthesis. PIPE and MUSETTE retain their accepted working audition baseline; TRANSISTOR awaits listening feedback. Physical phone and MPC listening remain pending.",
            "sections" to sections(old.getValue("sections")),
            "voices" to voices(old.getValue("voices")), "clips" to clips,
            "summary" to mapOf(
                "wavCount" to clips.size * 2, "heldCases" to clips.count { it["loop"] != null },
                "allDeterministic" to clips.all { (it.getValue("diagnostics") as Map<*, *>)["deterministic"] == true },
                "allLoopsPassed" to clips.filter { it["loop"] != null }
                    .all { (it["loop"] as Map<*, *>)["seamPass"] == true },
                "allAirflowLoopsPassed" to clips.filter { it["loop"] != null && it["airflow"] != null }
                    .all { (it["loop"] as Map<*, *>)["airflowCyclePass"] == true },
                "maxRawPeak" to clips.maxOf { number(nested(it, "metrics", "raw", "peak")) },
                "totalAudioSeconds" to clips.sumOf { number(it.getValue("seconds")) },
                "airflowPairs" to old.getValue("comparisonPairs").arr().size,
                "preservedChiffHistoryPairs" to old.getValue("comparisonHistoryPairs").arr().size,
                "preservedR1Clips" to 12, "preservedR2Clips" to 17,
                "preservedR3Clips" to baseline.clips.size, "newTransistorClips" to cases.size,
                "allRetainedWavsByteIdentical" to true, "allRetainedRecipesByteIdentical" to true,
            ),
        ))
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        // Frontend resources always come from this repository so UI revisions and regenerated
        // evidence are published together; the historical site's frontend is never authoritative.
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val resource = RegistersTransistorAuditionGenerator::class.java
                .getResourceAsStream("/audition/registers-audition/$asset")
                ?: error("Missing REGISTERS listening page resource $asset")
            File(root, asset).outputStream().use { output -> resource.use { it.copyTo(output) } }
        }
        File(root, "README.md").writeText("""
            # REGISTERS audition · $REVISION

            85 published R3 cases are retained byte-identical, plus 31 new TRANSISTOR cases:
            six identity notes, fifteen control checks, three velocity cases, two held loops,
            three factory presets and two control interactions. 232 dry mono PCM24 WAVs at
            44.1 kHz. The browser only plays the exported audio.

            PIPE and MUSETTE are the working audition baseline. TRANSISTOR awaits listening
            feedback. Physical phone and MPC listening remain pending. AIR is the transistor
            keyer transient, EDGE adds drive, and MOTION adds periodic vibrato. These are
            original instrument approximations rather than historical circuit emulations.

            Raw preserves conditioned engine level with any PCM peak guard recorded.
            Matched uses AuditionLevel's loudest 200 ms RMS target of 0.03. Metrics are
            measured after PCM decoding. All recipes are complete model-versioned patches.
            Held recurrence is measured across independently synthesized adjacent cycles
            before the settled region is copied three times for audition playback.

            R1/R2 comparison pairs, archived source snapshots and all published R3 sounds
            retain their exact paths and hashes. The complete R3 manifest is archived at
            baseline/r3-manifest.json, with its source snapshots at baseline/source/r3/.
            Existing explicit review scopes remain intact; implicit R3 sound scopes become
            explicit. R4 notes save separately and can inherit matching R1/R2/R3 reviews.

            Regenerate: ./gradlew :synth:generateRegistersTransistorAudition -PregistersR3Baseline=<published R3 archive>
            Published R3 manifest SHA-256: $R3_MANIFEST_HASH
            Published R3 audio fingerprint: $R3_AUDIO_HASH
            R4 audio fingerprint: $audioHash
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} cases / ${clips.size * 2} WAVs and the $REVISION audition under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        val presets = RegistersPresets.forVoice(voice)
        check(presets.map { it.name } == listOf("Compact Keys", "Clean Combo", "Garage Buzz"))
        check(presets.all { it.model == 4 && it.midi == 60 && it.velocity == 1f })
        for (note in listOf(48, 60, 69)) for (gesture in listOf("restrained", "expressive")) {
            val preset = presets[if (gesture == "restrained") 1 else 2]
            add(Case("transistor_${note}_$gesture", "identities", "TRANSISTOR · ${noteName(note)} · $gesture",
                "Compare immediate combo-organ speech, divided square-wave color and vibrato across the register.",
                note = note, overrides = preset.macros, gesture = gesture))
        }
        for (velocity in listOf(.25f, .6f, 1f)) add(Case(
            "transistor_velocity_${(velocity * 100).toInt()}", "velocity", "TRANSISTOR · velocity ${(velocity * 100).toInt()}%",
            "The same C4 patch at three excitation strengths. Compare raw dynamics and matched keyer/drive character.",
            velocity = velocity, overrides = mapOf("HOLD" to .35f)))
        for (macro in controls) for (setting in listOf("low", "default", "high")) {
            val value = when (setting) { "low" -> 0f; "high" -> 1f; else -> Registers.defaults(voice).getValue(macro) }
            add(Case("transistor_${macro.lowercase()}_$setting", "sweeps", "TRANSISTOR · $macro · $setting",
                controlDescription(macro), overrides = mapOf("HOLD" to .35f, macro to value),
                macro = macro, setting = setting, value = value))
        }
        for (setting in listOf("low", "high")) {
            val value = if (setting == "low") .05f else .9f
            add(Case("transistor_held_$setting", "loops", "TRANSISTOR · held · $setting motion + spread",
                "Three exact passes of the onset-free held region. Listen through every wrap with vibrato and rank spread active.",
                overrides = mapOf("HOLD" to 1f, "SPREAD" to value, "MOTION" to value), setting = setting))
        }
        for ((index, tag) in listOf("default", "clean", "garage").withIndex()) {
            val preset = presets[index]
            add(Case("transistor_preset_$tag", "presets", "TRANSISTOR · ${preset.name}",
                "The exact saved ${preset.name} factory patch at C4 and full velocity, with a complete replayable recipe.",
                velocity = 1f, preset = preset, setting = tag))
        }
        add(Case("transistor_interaction_color_edge", "interactions", "TRANSISTOR · bright registration + drive",
            "High COLOR and EDGE together: listen for useful bite, alias-like harshness or an overly flattened envelope.",
            overrides = mapOf("COLOR" to .85f, "EDGE" to .85f, "HOLD" to .35f)))
        add(Case("transistor_interaction_spread_motion", "interactions", "TRANSISTOR · rank spread + vibrato",
            "High SPREAD and MOTION together on a finite note: listen for coherent pitch and useful movement.",
            overrides = mapOf("SPREAD" to .85f, "MOTION" to .85f, "HOLD" to .35f)))
    }

    private fun render(root: File, case: Case): Map<String, Any?> {
        val macros = Registers.defaults(voice) + case.overrides + ("TUNE" to (case.note - 48) / 24f)
        val patch = case.preset ?: RegistersPatch(case.label, voice, macros, case.note, case.velocity, model = 4)
        val recipe = Json.parse(patch.toJsonText())
        val probe = patch.probe()
        val repeated = RegistersPatch.fromJsonText(Json.write(recipe)).probe()
        check(probe.samples.contentEquals(repeated.samples)) { "${case.id}: recipe roundtrip was not deterministic" }
        check(probe.rawPeak.isFinite() && probe.rawPeak <= Registers.RAW_PEAK_CEILING)
        val loop = if (probe.isLoop) loopEvidence(case.id, probe) else null
        val samples = if (probe.isLoop) FloatArray(probe.samples.size * 3) { probe.samples[it % probe.samples.size] } else probe.samples
        val source = Snip(samples, 1, Dsp.RATE)
        validateSnip(source, case.id)
        val exportGain = minOf(1f, .99f / source.peak().coerceAtLeast(1e-9f))
        val raw = Snip(FloatArray(source.frameCount) { source.samples[it] * exportGain }, 1, Dsp.RATE)
        val matched = AuditionLevel.level(source)
        val rawPath = "raw/${case.id}.wav"
        val matchedPath = "matched/${case.id}.wav"
        val recipePath = "recipes/${case.id}.json"
        val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
        val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
        val rawMetrics = metrics(validateExport(rawFile, raw))
        val matchedMetrics = metrics(validateExport(matchedFile, matched))
        check(abs(number(matchedMetrics.getValue("loudestWindowRms")) - TARGET) < .00001) {
            "${case.id}: listening target was peak-limited or not reached"
        }
        val recipeFile = File(root, recipePath).also { it.parentFile.mkdirs() }
        recipeFile.writeText(Json.write(recipe) + "\n")
        check(RegistersPatch.fromJsonText(recipeFile.readText()) == patch)
        return linkedMapOf(
            "id" to case.id, "label" to case.label, "description" to case.description,
            "section" to case.section, "voice" to voice.name,
            "noteName" to noteName(patch.midi), "note" to patch.midi, "velocity" to patch.velocity,
            "macros" to patch.macros, "gesture" to case.gesture,
            "macro" to case.macro, "setting" to case.setting, "value" to case.value,
            "raw" to rawPath, "matched" to matchedPath, "recipeUrl" to recipePath,
            "frames" to raw.frameCount, "seconds" to raw.frameCount.toDouble() / Dsp.RATE,
            "metrics" to mapOf("raw" to rawMetrics, "matched" to matchedMetrics),
            "sourceMetrics" to metrics(source), "rawExportGain" to exportGain,
            "sha256" to mapOf("raw" to sha256(rawFile), "matched" to sha256(matchedFile)),
            "recipe" to recipe, "recipeSha256" to sha256(recipeFile),
            "diagnostics" to mapOf(
                "model" to patch.model, "requestedHz" to probe.requestedHz, "effectiveHz" to probe.effectiveHz,
                "tuningErrorCents" to 1200.0 * ln(probe.effectiveHz / probe.requestedHz) / ln(2.0),
                "rawPeak" to probe.rawPeak, "effectiveSpreadHz" to probe.effectiveSpreadHz,
                "motionHz" to probe.motionHz, "deterministic" to true,
                "recipeRoundtripSamplesExact" to true,
            ),
            "loop" to loop, "auditionRevision" to REVISION, "listeningStatus" to "pending",
            "factoryPreset" to case.preset?.name,
        )
    }

    private fun loopEvidence(id: String, probe: RegistersProbe): Map<String, Any?> {
        val frames = requireNotNull(probe.loopFrames)
        val previous = requireNotNull(probe.previousCycle)
        check(frames == probe.samples.size && previous.size == frames && previous !== probe.samples)
        val measuredCycle = relativeError(previous, probe.samples)
        val measuredSeam = relativeError(previous, probe.samples, (frames - 256).coerceAtLeast(0))
        check(measuredCycle == probe.cycleError && measuredSeam == probe.seamError)
        check(measuredCycle.isFinite() && measuredCycle < CYCLE_LIMIT && measuredSeam.isFinite() && measuredSeam < SEAM_LIMIT) {
            "$id: independently synthesized cycles failed (seam=$measuredSeam, cycle=$measuredCycle)"
        }
        return mapOf(
            "regionFrames" to frames, "regionSeconds" to frames.toDouble() / Dsp.RATE,
            "playbackRepetitions" to 3, "seamError" to measuredSeam, "cycleError" to measuredCycle,
            "seamPass" to true, "cyclePass" to true,
            "independentPreviousCycle" to true,
            "independentCycleNotes" to "Measured from adjacent regions of continuous synthesis after preroll, before any playback copying.",
            "previousCycleFloatSha256" to floatHash(previous), "currentCycleFloatSha256" to floatHash(probe.samples),
            "wrapStep" to abs(probe.samples.first().toDouble() - probe.samples.last()),
            "wrapSlopeDifference" to abs((probe.samples[1].toDouble() - probe.samples[0]) -
                (probe.samples.last().toDouble() - probe.samples[probe.samples.lastIndex - 1])),
        )
    }

    private fun relativeError(a: FloatArray, b: FloatArray, start: Int = 0): Double {
        var error = 0.0
        var energy = 0.0
        for (i in start until a.size) {
            val difference = a[i] - b[i].toDouble()
            error += difference * difference
            energy += a[i].toDouble() * a[i]
        }
        return error / energy.coerceAtLeast(1e-30)
    }

    private fun loadBaseline(directory: File): Baseline {
        val root = directory.canonicalFile
        val file = archiveAsset(root, "manifest.json")
        check(sha256(file) == R3_MANIFEST_HASH) { "The R3 manifest differs from the published accepted revision." }
        val manifest = Json.parse(file.readText())
        val obj = manifest.obj()
        check(obj.getValue("audioHash").str() == R3_AUDIO_HASH && obj.getValue("revision").str() == R3_REVISION &&
            obj.getValue("model").int() == 3 && obj.getValue("clipCount").int() == 85)
        val recipes = linkedMapOf<String, String>()
        val clips = obj.getValue("clips").arr().map { entry ->
            val clip = entry.obj()
            val id = clip.getValue("id").str()
            check(id !in recipes)
            for (mode in listOf("raw", "matched")) {
                check(sha256(archiveAsset(root, clip.getValue(mode).str())) == clip.getValue("sha256").obj().getValue(mode).str()) {
                    "R3 $id $mode no longer matches its published WAV hash"
                }
            }
            val recipe = archiveAsset(root, clip.getValue("recipeUrl").str())
            check(Json.parse(recipe.readText()) == clip.getValue("recipe")) { "R3 $id recipe differs from its published manifest" }
            recipes[id] = sha256(recipe)
            @Suppress("UNCHECKED_CAST")
            (plain(entry) as Map<String, Any?>)
        }
        check(clips.size == 85 && audioHash(clips) == R3_AUDIO_HASH)
        for (source in obj.getValue("sourceFingerprints").arr()) {
            val fingerprint = source.obj()
            check(sha256(archiveAsset(root, "source/${fingerprint.getValue("path").str()}")) == fingerprint.getValue("sha256").str())
        }
        for (metadata in obj.getValue("baselines").arr()) {
            val baseline = metadata.obj()
            check(sha256(archiveAsset(root, baseline.getValue("manifest").str())) == baseline.getValue("manifestSha256").str())
        }
        println("Verified published R3 manifest, 170 WAV hashes, 85 recipes and archived source fingerprints.")
        return Baseline(root, manifest, clips, recipes)
    }

    private fun preserveArchive(root: File, baseline: Baseline) {
        baseline.root.walkTopDown().forEach { file ->
            val relative = file.relativeTo(baseline.root).path
            val target = File(root, relative)
            if (file.isDirectory) check(target.mkdirs() || target.isDirectory)
            else if (file.isFile) {
                check(file.canonicalFile.toPath().startsWith(baseline.root.toPath())) { "Archive link escapes R3 root: $relative" }
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
                check(sha256(file) == sha256(target)) { "R3 $relative changed during copy" }
            }
        }
        File(baseline.root, "manifest.json").copyTo(File(root, "baseline/r3-manifest.json"), overwrite = true)
        check(sha256(File(root, "baseline/r3-manifest.json")) == R3_MANIFEST_HASH)
        for (source in baseline.manifest.obj().getValue("sourceFingerprints").arr()) {
            val fingerprint = source.obj()
            val path = fingerprint.getValue("path").str()
            val target = File(root, "baseline/source/r3/$path")
            target.parentFile.mkdirs()
            archiveAsset(baseline.root, "source/$path").copyTo(target, overwrite = true)
            check(sha256(target) == fingerprint.getValue("sha256").str())
        }
    }

    private fun validateRetainedAssets(root: File, baseline: Baseline) {
        for (clip in baseline.clips) {
            val id = clip.getValue("id") as String
            val hashes = clip.getValue("sha256") as Map<*, *>
            for (mode in listOf("raw", "matched")) {
                check(sha256(archiveAsset(root, clip.getValue(mode) as String)) == hashes[mode]) { "$id $mode changed in R4" }
            }
            check(sha256(archiveAsset(root, clip.getValue("recipeUrl") as String)) == baseline.recipeHashes.getValue(id)) {
                "$id recipe changed in R4"
            }
        }
    }

    private fun validateComparisonReferences(manifest: JsonValue, clips: List<Map<String, Any?>>) {
        val ids = clips.map { it.getValue("id") as String }.toSet()
        for (key in listOf("comparisonPairs", "comparisonHistoryPairs")) {
            val pairs = manifest.obj().getValue(key).arr()
            check(pairs.size == 12)
            for (pair in pairs) for (side in listOf("originalId", "revisedId")) {
                check(pair.obj().getValue(side).str() in ids)
            }
        }
    }

    private fun validateTouch(clips: List<Map<String, Any?>>) {
        val touch = listOf(25, 60, 100).map { velocity -> clips.single { it["id"] == "transistor_velocity_$velocity" } }
        val rms = touch.map { number(nested(it, "metrics", "raw", "loudestWindowRms")) }
        check(rms.zipWithNext().all { (low, high) -> high > low }) { "TRANSISTOR raw velocity levels are not increasing: $rms" }
        check(touch.map { nested(it, "sha256", "raw") }.distinct().size == 3)
    }

    private fun r3Metadata(baseline: Baseline): Map<String, Any?> = mapOf(
        "revision" to R3_REVISION, "audioHash" to R3_AUDIO_HASH,
        "manifest" to "baseline/r3-manifest.json", "manifestSha256" to R3_MANIFEST_HASH,
        "sourceRoot" to "baseline/source/r3",
        "sourceFingerprint" to baseline.manifest.obj().getValue("sourceFingerprint"),
        "verifiedWavCount" to 170, "verifiedRecipeCount" to 85, "copiedOriginalCases" to 85,
        "recipeFingerprints" to baseline.recipeHashes.map { (id, hash) -> mapOf("id" to id, "sha256" to hash) },
    )

    private fun sections(original: JsonValue): List<Any?> = original.arr().map { value ->
        @Suppress("UNCHECKED_CAST")
        LinkedHashMap(plain(value) as Map<String, Any?>).also { section ->
            when (section["id"]) {
                "identities" -> section["description"] = "PIPE, MUSETTE and TRANSISTOR at C3, C4 and A4, each restrained and expressive. Start with matched playback, then compare raw energy."
                "loops" -> section["description"] = "Six onset-free held cases with low or high spread and motion. Each file contains three wraps after independent engine recurrence checks."
            }
        }
    } + listOf(
        mapOf("id" to "presets", "label" to "Transistor presets", "description" to "Compact Keys, Clean Combo and Garage Buzz: exact model-4 factory patches at C4 and full velocity."),
        mapOf("id" to "interactions", "label" to "Controls together", "description" to "High COLOR with EDGE, then high SPREAD with MOTION. Listen for useful character and coherent pitch when controls interact."),
    )

    private fun voices(original: JsonValue): List<Any?> = original.arr().map(::plain).toMutableList().also {
        it.add(2, mapOf("id" to "TRANSISTOR", "label" to "TRANSISTOR",
            "description" to "Divided square-wave combo-organ registrations, immediate keyer speech, drive and vibrato. Listening pending."))
    }

    private fun controlDescription(macro: String): String = when (macro) {
        "COLOR" -> "Registration balance changes the hollow divided square-wave body and upper-register brightness."
        "AIR" -> "Keyer transient amount changes the short electronic speaking gesture. This voice does not use pipe airflow."
        "EDGE" -> "Transistor-style drive adds harmonic bite before level matching."
        "SPREAD" -> "Rank detuning adds beating while the requested note remains fixed."
        "MOTION" -> "Periodic vibrato adds pitch movement to the sustained combo-organ body."
        else -> error("Unknown TRANSISTOR control $macro")
    }

    private fun validateSnip(snip: Snip, id: String) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.frameCount > 2)
        check(snip.samples.all { it.isFinite() } && snip.peak() > 1e-6f) { "$id: invalid or silent render" }
    }

    private fun validateExport(file: File, expected: Snip): Snip {
        val bytes = file.readBytes()
        check((bytes[20].toInt() and 255) == 1 && (bytes[21].toInt() and 255) == 0 &&
            (bytes[34].toInt() and 255) == 24 && (bytes[35].toInt() and 255) == 0)
        val decoded = WavReader.read(bytes)
        validateSnip(decoded, file.name)
        check(decoded.frameCount == expected.frameCount && decoded.peak() <= .990001f)
        check(decoded.samples.indices.all { abs(decoded.samples[it] - expected.samples[it]) <= 1.3e-7f }) {
            "${file.name}: PCM roundtrip exceeded quantization tolerance"
        }
        return decoded
    }

    private fun metrics(snip: Snip): Map<String, Any> {
        var sum = 0.0
        var energy = 0.0
        var differences = 0.0
        for (i in snip.samples.indices) {
            val sample = snip.samples[i].toDouble()
            sum += sample
            energy += sample * sample
            if (i > 0) { val difference = sample - snip.samples[i - 1]; differences += difference * difference }
        }
        val rms = sqrt(energy / snip.frameCount)
        return mapOf("peak" to snip.peak().toDouble(), "rms" to rms,
            "loudestWindowRms" to Loudness.of(snip).toDouble(), "dcMean" to sum / snip.frameCount,
            "firstDifferenceRms" to sqrt(differences / (snip.frameCount - 1)),
            "crestDb" to 20 * log10(snip.peak().toDouble() / rms.coerceAtLeast(1e-20)))
    }

    private fun sourceFingerprints(repository: File, output: File): List<Map<String, String>> = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Registers.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/RegistersTransistor.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/RegistersPatch.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/RegistersPresets.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/RegistersTransistorAuditionGenerator.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
        "json/src/main/kotlin/com/snipsnap/json/Json.kt",
    ).map { path ->
        val source = File(repository, path)
        val target = File(output, "source/$path")
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        check(sha256(source) == sha256(target))
        mapOf("path" to path, "sha256" to sha256(source))
    }

    private fun archiveAsset(root: File, path: String): File {
        val file = File(root, path).canonicalFile
        check(file.toPath().startsWith(root.toPath()) && file.isFile) { "Invalid archived asset $path" }
        return file
    }
    private fun audioHash(clips: List<Map<String, Any?>>): String = sha256(clips.sortedBy { it.getValue("id") as String }.joinToString("\n") {
        val hashes = it.getValue("sha256") as Map<*, *>
        "${it.getValue("id")}:${hashes["raw"]}:${hashes["matched"]}"
    }.toByteArray(Charsets.UTF_8))
    private fun floatHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return sha256(bytes.array())
    }
    private fun nested(value: Map<String, Any?>, vararg keys: String): Any? = keys.fold(value as Any?) { node, key ->
        val map = node as Map<*, *>
        (map.entries.firstOrNull { it.key == key } ?: error("Missing manifest field $key")).value
    }
    private fun number(value: Any?): Double = (value as Number).toDouble()
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun repositoryRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun plain(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { plain(it.value) }
        is JsonValue.Arr -> value.items.map(::plain)
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported REGISTERS manifest value ${value.javaClass}")
    }
}

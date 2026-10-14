package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Real Kotlin DSP evidence for a provisional organ/reed family, before application integration. */
object RegistersAuditionGenerator {
    private const val REVISION = "r3-airflow"
    private const val BASELINE_AUDIO_HASH = "56aa7372cb7bd4e84b7ac9fe8321acfdec17d32c2393fa5d63a3749a1510c513"
    private const val BASELINE_MANIFEST_HASH = "77ee227bf2855c327008c86c57b9046b8d66dca310357d8c2c561b6314316b5d"
    private const val PREVIOUS_AUDIO_HASH = "a1856a35995acd5dc151193cede4133ebcf896b17606d6205e9a4d49c81941df"
    private const val PREVIOUS_MANIFEST_HASH = "3d650ce13378569558a00275c141362fb48ee298dcc62ead149fd927a91364e1"
    private const val TARGET = .03
    private const val SEAM_LIMIT = .001
    private const val CYCLE_LIMIT = .00001
    private val controls = listOf("COLOR", "AIR", "EDGE", "SPREAD", "MOTION")
    private val pitches = listOf(48, 60, 69)

    private data class Case(
        val id: String,
        val section: String,
        val voice: String,
        val label: String,
        val description: String,
        val note: Int = 60,
        val velocity: Float = .8f,
        val overrides: Map<String, Float> = emptyMap(),
        val gesture: String? = null,
        val macro: String? = null,
        val setting: String? = null,
        val value: Float? = null,
    )

    private data class Render(
        val snip: Snip,
        val macros: Map<String, Float>,
        val recipe: JsonValue,
        val diagnostics: Map<String, Any?>,
        val loop: Map<String, Any?>? = null,
        val airflow: Map<String, Any?>? = null,
    )

    private data class Baseline(
        val root: File,
        val manifest: JsonValue,
        val clips: Map<String, Map<String, Any?>>,
        val recipeHashes: Map<String, String>,
        val manifestHash: String,
        val key: String,
        val revision: Any,
        val audioHash: String,
        val model: Int,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--baseline=") } <= 1 &&
            args.count { it.startsWith("--previous=") } <= 1 &&
            args.filter { it.startsWith("--") }.all { it.startsWith("--baseline=") || it.startsWith("--previous=") }) {
            "Supply one output directory and optional --baseline=<R1 archive> --previous=<R2 archive>."
        }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/registers-audition-r3").canonicalFile
        val baseline = loadBaseline(File(args.firstOrNull { it.startsWith("--baseline=") }?.substringAfter('=')
            ?: "/workspace/scratch/registers-r1"), "r1", 1, BASELINE_AUDIO_HASH, BASELINE_MANIFEST_HASH, 1, 56)
        val previous = loadBaseline(File(args.firstOrNull { it.startsWith("--previous=") }?.substringAfter('=')
            ?: "/workspace/scratch/registers-r2"), "r2", "r2-chiff", PREVIOUS_AUDIO_HASH, PREVIOUS_MANIFEST_HASH, 2, 68)
        check(root != baseline.root && root != previous.root) { "Published archives must remain separate from the output directory." }
        check(root.mkdirs() || root.isDirectory)
        val cases = cases()
        check(cases.size == 56 && cases.map { it.id }.distinct().size == cases.size)
        val pairedCases = cases.filter { it.voice == "PIPE" &&
            (it.section in listOf("identities", "loops") || it.macro == "AIR" || it.id == "pipe_velocity_60") }
        check(pairedCases.size == 12)
        val pairedIds = pairedCases.map { it.id }.toSet()
        val clips = ArrayList<Map<String, Any?>>(cases.size)
        var unchangedRows = 0
        for ((index, case) in cases.withIndex()) {
            val rendered = render(case)
            validateSnip(rendered.snip, case.id)
            val exportGain = minOf(1f, .99f / rendered.snip.peak().coerceAtLeast(1e-9f))
            val raw = Snip(FloatArray(rendered.snip.frameCount) { rendered.snip.samples[it] * exportGain }, 1, Dsp.RATE)
            val matched = AuditionLevel.level(rendered.snip)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            val recipePath = "recipes/${case.id}.json"
            val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val decodedRaw = validateExport(rawFile, raw)
            val decodedMatched = validateExport(matchedFile, matched)
            val rawMetrics = metrics(decodedRaw)
            val matchedMetrics = metrics(decodedMatched)
            check(abs((matchedMetrics.getValue("loudestWindowRms") as Number).toDouble() - TARGET) < .00001) {
                "${case.id}: shared listening target was peak-limited or not reached"
            }
            File(root, recipePath).also { it.parentFile.mkdirs() }.writeText(Json.write(rendered.recipe) + "\n")
            val clip = linkedMapOf<String, Any?>(
                "id" to case.id, "label" to case.label, "description" to case.description,
                "section" to case.section, "voice" to case.voice,
                "noteName" to noteName(case.note), "note" to case.note, "velocity" to case.velocity,
                "macros" to rendered.macros, "gesture" to case.gesture,
                "macro" to case.macro, "setting" to case.setting, "value" to case.value,
                "raw" to rawPath, "matched" to matchedPath, "recipeUrl" to recipePath,
                "frames" to raw.frameCount, "seconds" to raw.frameCount.toDouble() / Dsp.RATE,
                "metrics" to mapOf("raw" to rawMetrics, "matched" to matchedMetrics),
                "sourceMetrics" to metrics(rendered.snip), "rawExportGain" to exportGain,
                "sha256" to mapOf("raw" to sha256(rawFile), "matched" to sha256(matchedFile)),
                "recipe" to rendered.recipe, "diagnostics" to rendered.diagnostics, "loop" to rendered.loop,
                "auditionRevision" to REVISION,
                "airflow" to rendered.airflow?.let { scaleAirflow(it, exportGain.toDouble(), matchingGain(rendered.snip, matched)) },
            )
            if (case.id in pairedIds) {
                clip["comparisonPair"] = "airflow_${case.id}"
                clip["comparisonLabel"] = "With airflow R3"
                clip["comparisonOriginalId"] = "r2_${case.id}"
            }
            if (case.voice != "PIPE" || (case.macro == "AIR" && case.setting == "low")) {
                val originalHashes = previous.clips.getValue(case.id).getValue("sha256") as Map<*, *>
                val newHashes = clip.getValue("sha256") as Map<*, *>
                check(originalHashes["raw"] == newHashes["raw"] && originalHashes["matched"] == newHashes["matched"]) {
                    "${case.id}: R3 changed a protected MUSETTE/reference or AIR 0 case"
                }
                clip["unchangedFromR2"] = true
                addReviewScope(clip, case.id, previous)
                if (case.voice != "PIPE") unchangedRows++
            }
            if (case.voice == "PIPE" && rendered.loop == null) {
                val oldRaw = WavReader.read(archiveAsset(previous.root, previous.clips.getValue(case.id).getValue("raw") as String))
                val headFrames = (.080 * Dsp.RATE).roundToInt()
                check(decodedRaw.samples.copyOfRange(0, headFrames).contentEquals(oldRaw.samples.copyOfRange(0, headFrames))) {
                    "${case.id}: R3 changed the accepted first 80 ms of the R2 attack"
                }
                clip["first80msRawUnchangedFromR2"] = true
            }
            clips += clip
            println("REGISTERS ${index + 1}/${cases.size}: ${case.id} · ${fmt(raw.durationSeconds.toDouble())} s · raw peak ${fmt(raw.peak().toDouble())} · matched ${fmt((matchedMetrics.getValue("loudestWindowRms") as Number).toDouble())}")
        }
        check(unchangedRows == 30)
        preserveBaseline(root, baseline)
        preserveBaseline(root, previous)
        val history = preserveChiffHistory(root, baseline, previous, pairedCases, clips)
        val comparisons = airflowComparisons(root, pairedCases, clips)
        check(clips.size == 85 && clips.map { it.getValue("id") }.distinct().size == clips.size)
        val repository = repositoryRoot()
        val sources = sourceFingerprints(repository)
        val audioHash = audioHash(clips)
        val manifest = Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "title" to "REGISTERS · Pipe + Musette", "revision" to REVISION,
            "engine" to "REGISTERS", "model" to Registers.MODEL_VERSION,
            "status" to "Prototype audition — listening approval pending",
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24,
            "clipCount" to clips.size, "listeningTarget" to TARGET, "listeningPeakCeiling" to .99,
            "audioHash" to audioHash, "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceFingerprints" to sources,
            "sourceFingerprint" to sha256(Json.write(json(sources)).toByteArray(Charsets.UTF_8)),
            "inspiration" to "https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/octave-engine-architecture.md",
            "repository" to "https://github.com/BertCalm/SnipSnap",
            "metricNotes" to "All WAVs are dry mono 44.1 kHz PCM24 from Kotlin DSP. Raw playback preserves conditioned pre-level engine samples, with any PCM peak guard recorded as rawExportGain. Matched playback uses AuditionLevel: loudest 200 ms RMS 0.03 after SnipSnap's gentle low cut. Playback metrics are measured after reading the exported WAVs; sourceMetrics describe the unscaled float render. First-difference RMS is a brightness proxy, not spectral centroid. Held WAVs contain three exact copies of the actual onset-free engine region. Independent generated cycleError and seamError are checked before export; wrapStep is a normal waveform step, not a continuity test by itself. Baselines use existing FULL TONEWHEEL and ORGAN PRISM DSP before normalization, at exact MIDI 48 and 60. No effects, reverb, browser synthesis, application registry integration or factory presets.",
            "baseline" to baselineMetadata(baseline), "previous" to baselineMetadata(previous),
            "baselines" to listOf(baselineMetadata(baseline), baselineMetadata(previous)),
            "comparisonPairs" to comparisons,
            "comparisonHistoryPairs" to history,
            "transientMetricNotes" to "First80ms RMS is measured on the exported PCM samples from 0–80 ms. BandMeanSquare uses a Hann window over those samples, zero-padded to a 4096-point FFT, and sums the two-sided Parseval power of bins from 1.5–8 kHz, divided by FFT size and window squared sum. It describes measured band energy, not a listening verdict. Raw and matched versions are measured independently; Original R1 files are copied byte-identical from the published manifest.",
            "airflowMetricNotes" to "Airflow is the new model-3 layer alone, conditioned like the main waveform. Settled analysis begins at 300 ms and ends before release, or spans the complete first held cycle. Raw and matched airflow/body RMS use the common playback gain; the tap is never independently normalized. Spectral evidence measures the tap within 120–12000 Hz and outside ±2 Hz of integer carrier harmonics using a Hann-windowed FFT. Legacy sparse wind remains in the body. All finite PIPE first-80ms raw samples remain identical to R2; history pairs continue to compare preserved R1 with preserved R2.",
            "sections" to sections(), "voices" to voices(), "clips" to clips,
            "summary" to mapOf(
                "wavCount" to clips.size * 2, "heldCases" to clips.count { it["loop"] != null },
                "allDeterministic" to clips.all { (it.getValue("diagnostics") as Map<*, *>)["deterministic"] == true },
                "allLoopsPassed" to clips.filter { it["loop"] != null }.all { (it["loop"] as Map<*, *>)["seamPass"] == true },
                "allAirflowLoopsPassed" to clips.filter { it["loop"] != null && it["airflow"] != null }
                    .all { (it["loop"] as Map<*, *>)["airflowCyclePass"] == true },
                "maxRawPeak" to clips.maxOf { ((it.getValue("metrics") as Map<*, *>)["raw"] as Map<*, *>)["peak"] as Double },
                "totalAudioSeconds" to clips.sumOf { it.getValue("seconds") as Double },
                "airflowPairs" to comparisons.size, "preservedChiffHistoryPairs" to history.size,
                "preservedR1Clips" to 12, "preservedR2Clips" to 17, "unchangedMainCases" to unchangedRows,
            ),
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val resource = RegistersAuditionGenerator::class.java.getResourceAsStream("/audition/registers-audition/$asset")
                ?: error("Missing REGISTERS listening page resource $asset")
            File(root, asset).outputStream().use { output -> resource.use { it.copyTo(output) } }
        }
        File(root, "README.md").writeText("""
            # REGISTERS audition · revision $REVISION

            56 current cases plus 12 preserved R1 and 17 preserved R2 recordings;
            170 mono PCM24 WAVs at 44.1 kHz. R3 adds soft sustained PIPE airflow.
            All 30 MUSETTE/reference cases and PIPE AIR 0 remain byte-identical to R2.
            Every finite PIPE first 80 ms remains byte-identical to the accepted R2 attack.
            Serve this directory as a static site. index.html reads manifest.json.
            Audio is rendered by Kotlin DSP; the browser only plays exported WAVs.

            PIPE and MUSETTE are provisional standalone RegistersPatch configurations.
            Identity notes are C3, C4 and A4. Existing TONEWHEEL FULL and PRISM ORGAN
            references use their shared supported C3/C4 register without pitch clamping.
            Five controls receive low/default/high comparisons at C4. Velocity cases
            preserve level changes in raw playback; matched playback exposes timbre.
            Four HOLD cases retain periodic motion; low spread can fit to zero detuning.
            Each file begins with
            the settled region and contains three exact copies, with no fade or crossfade.

            Raw files preserve conditioned engine level except a recorded peak guard.
            Matched files use AuditionLevel's loudest 200 ms RMS target of 0.03.
            Metrics come from decoded exported WAVs. Recipes, independent loop evidence,
            deterministic checks and source/audio SHA-256 fingerprints are in manifest.json.
            Listening approval and SnipSnap application integration remain pending.

            Original R1 WAVs and recipes are copied byte-identical from the verified
            published archives; model 1 and 2 replay match all retained original WAV hashes.
            Twelve default pairs compare R2/R3 airflow. Twelve history pairs still compare
            R1/R2 chiff using preserved recordings. Settled airflow/body RMS and spectral
            evidence are measured from the engine tap with the common playback gain.
            All three review revisions retain their source identities.

            Regenerate: ./gradlew :synth:generateRegistersAudition -PregistersBaseline=<R1 archive> -PregistersR2Baseline=<R2 archive>
            Published R1 manifest SHA-256: ${baseline.manifestHash}
            Published R2 manifest SHA-256: ${previous.manifestHash}
            Audio fingerprint: $audioHash
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} cases / ${clips.size * 2} WAVs, recipes, manifest and audition page under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        for (voice in RegistersVoice.entries) {
            val tag = voice.name.lowercase()
            for (midi in pitches) for (gesture in listOf("restrained", "expressive")) {
                add(Case("${tag}_${midi}_$gesture", "identities", voice.name,
                    "${voice.name} · ${noteName(midi)} · $gesture",
                    if (voice == RegistersVoice.PIPE) "Compare gradual pipe speech, wind and chiff across the register."
                    else "Compare the three beating reeds, bellows contour and pressure-dependent buzz across the register.",
                    note = midi, overrides = gestureMacros(voice, gesture), gesture = gesture))
            }
            for (velocity in listOf(.25f, .6f, 1f)) {
                add(Case("${tag}_velocity_${(velocity * 100).toInt()}", "velocity", voice.name,
                    "${voice.name} · velocity ${(velocity * 100).toInt()}%",
                    "The same C4 patch at three excitation strengths. Compare raw dynamics and matched articulation.",
                    velocity = velocity, overrides = mapOf("HOLD" to .35f)))
            }
            for (macro in controls) for (setting in listOf("low", "default", "high")) {
                val value = when (setting) { "low" -> 0f; "high" -> 1f; else -> Registers.defaults(voice).getValue(macro) }
                add(Case("${tag}_${macro.lowercase()}_$setting", "sweeps", voice.name,
                    "${voice.name} · $macro · $setting", controlDescription(voice, macro),
                    overrides = mapOf("HOLD" to .35f, macro to value), macro = macro, setting = setting, value = value))
            }
            for (setting in listOf("low", "high")) {
                val value = if (setting == "low") .05f else .9f
                add(Case("${tag}_held_$setting", "loops", voice.name,
                    "${voice.name} · held · $setting motion + spread",
                    "Three exact passes of the onset-free held region. Listen through every wrap; periodic motion remains active." +
                        if (setting == "low") " Low spread can fit to zero detuning on the loop clock." else " Strong fitted detuning remains active.",
                    overrides = mapOf("HOLD" to 1f, "SPREAD" to value, "MOTION" to value), setting = setting))
            }
        }
        for (voice in listOf("TONEWHEEL", "PRISM")) for (midi in listOf(48, 60)) {
            add(Case("${voice.lowercase()}_reference_$midi", "references", voice,
                "$voice ${if (voice == "TONEWHEEL") "FULL" else "ORGAN"} · ${noteName(midi)}",
                "Existing SnipSnap organ DSP at its genuine supported pitch. Compare against PIPE and MUSETTE at the same note and matched level.",
                note = midi, velocity = 1f))
        }
    }

    private fun gestureMacros(voice: RegistersVoice, gesture: String): Map<String, Float> {
        val values = when {
            voice == RegistersVoice.PIPE && gesture == "restrained" -> listOf(.18f, .20f, .08f, .06f, .05f)
            voice == RegistersVoice.PIPE -> listOf(.55f, .72f, .62f, .62f, .55f)
            gesture == "restrained" -> listOf(.32f, .24f, .12f, .20f, .08f)
            else -> listOf(.64f, .78f, .52f, .78f, .50f)
        }
        return controls.zip(values).toMap() + ("HOLD" to .35f)
    }

    private fun render(case: Case): Render = if (case.voice in RegistersVoice.entries.map { it.name }) {
        val voice = RegistersVoice.valueOf(case.voice)
        val macros = Registers.defaults(voice) + case.overrides + ("TUNE" to (case.note - 48) / 24f)
        val patch = RegistersPatch(case.label, voice, macros, case.note, case.velocity)
        val probe = patch.probe()
        val recipe = Json.parse(patch.toJsonText())
        val repeated = RegistersPatch.fromJsonText(Json.write(recipe)).probe()
        check(probe.samples.contentEquals(repeated.samples)) { "${case.id}: patch roundtrip was not deterministic" }
        val loop = if (probe.isLoop) {
            val frames = requireNotNull(probe.loopFrames)
            check(frames == probe.samples.size)
            val seam = requireNotNull(probe.seamError)
            val cycle = requireNotNull(probe.cycleError)
            check(seam.isFinite() && seam < SEAM_LIMIT && cycle.isFinite() && cycle < CYCLE_LIMIT) {
                "${case.id}: independent held cycles failed (seam=$seam, cycle=$cycle)"
            }
            mapOf(
                "regionFrames" to frames, "regionSeconds" to frames.toDouble() / Dsp.RATE,
                "playbackRepetitions" to 3, "seamError" to seam, "cycleError" to cycle, "seamPass" to true,
                "airflowCycleError" to probe.airflowCycleError, "airflowSeamError" to probe.airflowSeamError,
                "airflowCyclePass" to probe.airflowSamples?.let {
                    requireNotNull(probe.airflowCycleError) < CYCLE_LIMIT && requireNotNull(probe.airflowSeamError) < SEAM_LIMIT
                },
                "wrapStep" to abs(probe.samples.first().toDouble() - probe.samples.last()),
                "wrapSlopeDifference" to abs((probe.samples[1].toDouble() - probe.samples[0]) -
                    (probe.samples.last().toDouble() - probe.samples[probe.samples.lastIndex - 1])),
            )
        } else null
        val output = if (probe.isLoop) FloatArray(probe.samples.size * 3) { probe.samples[it % probe.samples.size] } else probe.samples
        Render(Snip(output, 1, Dsp.RATE), macros, recipe, mapOf(
            "model" to patch.model,
            "requestedHz" to probe.requestedHz, "effectiveHz" to probe.effectiveHz,
            "tuningErrorCents" to 1200.0 * ln(probe.effectiveHz / probe.requestedHz) / ln(2.0),
            "rawPeak" to probe.rawPeak, "effectiveSpreadHz" to probe.effectiveSpreadHz,
            "motionHz" to probe.motionHz, "deterministic" to true,
        ), loop, if (voice == RegistersVoice.PIPE) airflowEvidence(probe) else null)
    } else renderReference(case)

    private fun loadBaseline(
        directory: File, key: String, revision: Any, expectedAudioHash: String,
        expectedManifestHash: String, model: Int, count: Int,
    ): Baseline {
        val root = directory.canonicalFile
        val manifestFile = File(root, "manifest.json")
        check(manifestFile.isFile) { "Published $key archive is missing: ${root.path}. Supply the archive path explicitly." }
        val manifestHash = sha256(manifestFile)
        check(manifestHash == expectedManifestHash) { "The $key manifest differs from the preserved published revision: $manifestHash" }
        val manifest = Json.parse(manifestFile.readText())
        val obj = manifest.obj()
        check(obj.getValue("audioHash").str() == expectedAudioHash && obj.getValue("revision") == json(revision) &&
            obj.getValue("model").int() == model && obj.getValue("clipCount").int() == count)
        val clips = linkedMapOf<String, Map<String, Any?>>()
        val recipeHashes = linkedMapOf<String, String>()
        for (entry in obj.getValue("clips").arr()) {
            val clip = entry.obj()
            val id = clip.getValue("id").str()
            check(id !in clips)
            val hashes = clip.getValue("sha256").obj()
            for (mode in listOf("raw", "matched")) {
                val file = archiveAsset(root, clip.getValue(mode).str())
                check(sha256(file) == hashes.getValue(mode).str()) { "$key $id $mode WAV no longer matches its published hash" }
            }
            val recipe = archiveAsset(root, clip.getValue("recipeUrl").str())
            check(Json.parse(recipe.readText()) == clip.getValue("recipe")) { "$key $id recipe differs from its published manifest" }
            recipeHashes[id] = sha256(recipe)
            @Suppress("UNCHECKED_CAST")
            val values = plain(entry) as Map<String, Any?>
            clips[id] = values
        }
        check(clips.size == count && audioHash(clips.values.toList()) == expectedAudioHash)
        for (source in obj.getValue("sourceFingerprints").arr()) {
            val fingerprint = source.obj()
            val file = archiveAsset(root, "source/${fingerprint.getValue("path").str()}")
            check(sha256(file) == fingerprint.getValue("sha256").str()) { "$key archived source changed: ${file.path}" }
        }
        println("Verified published $key manifest, ${count * 2} WAV hashes, $count recipes and archived source fingerprints.")
        return Baseline(root, manifest, clips, recipeHashes, manifestHash, key, revision, expectedAudioHash, model)
    }

    private fun preserveBaseline(root: File, baseline: Baseline) {
        val manifestCopy = File(root, "baseline/${baseline.key}-manifest.json")
        manifestCopy.parentFile.mkdirs()
        File(baseline.root, "manifest.json").copyTo(manifestCopy, overwrite = true)
        check(sha256(manifestCopy) == baseline.manifestHash)
        for (source in baseline.manifest.obj().getValue("sourceFingerprints").arr()) {
            val fingerprint = source.obj()
            val path = fingerprint.getValue("path").str()
            val target = File(root, "baseline/source/${baseline.key}/$path")
            target.parentFile.mkdirs()
            archiveAsset(baseline.root, "source/$path").copyTo(target, overwrite = true)
            check(sha256(target) == fingerprint.getValue("sha256").str())
        }
    }

    private fun baselineMetadata(baseline: Baseline) = mapOf(
        "revision" to baseline.revision, "audioHash" to baseline.audioHash,
        "manifest" to "baseline/${baseline.key}-manifest.json", "manifestSha256" to baseline.manifestHash,
        "sourceRoot" to "baseline/source/${baseline.key}",
        "sourceFingerprint" to baseline.manifest.obj().getValue("sourceFingerprint"),
        "verifiedWavCount" to baseline.clips.size * 2, "verifiedRecipeCount" to baseline.clips.size,
        "copiedOriginalCases" to if (baseline.model == 1) 12 else 17,
        "recipeFingerprints" to baseline.recipeHashes.map { (id, hash) -> mapOf("id" to id, "sha256" to hash) },
    )

    private fun preserveChiffHistory(
        root: File, baseline: Baseline, previous: Baseline,
        airflowCases: List<Case>, clips: MutableList<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val originalPairs = previous.manifest.obj().getValue("comparisonPairs").arr().map { plain(it) as Map<String, Any?> }
        check(originalPairs.size == 12)
        val historyIds = originalPairs.map { it.getValue("id") as String }.toSet()
        val airflowIds = airflowCases.map { it.id }.toSet()
        val previousIds = historyIds + airflowIds
        check(previousIds.size == 17)
        for (id in historyIds.sorted()) clips += preserveClip(root, baseline, id, "chiff-history", "Original R1", "chiff_$id")
        for (id in previousIds.sorted()) clips += preserveClip(root, previous, id,
            if (id in airflowIds) "comparisons" else "chiff-history", "Current R2",
            if (id in airflowIds) "airflow_$id" else "chiff_$id")
        return originalPairs.map { pair ->
            val id = pair.getValue("id") as String
            LinkedHashMap(pair).also {
                it["id"] = "chiff_$id"
                it["group"] = "chiff"
                it["originalId"] = "r1_$id"
                it["revisedId"] = "r2_$id"
                it["originalLabel"] = "Original R1"
                it["revisedLabel"] = "More chiff R2"
                it["provenance"] = mapOf("sourceManifest" to "baseline/r2-manifest.json",
                    "sourcePairId" to id, "sourceManifestSha256" to previous.manifestHash)
            }
        }
    }

    private fun preserveClip(
        root: File, archive: Baseline, sourceId: String,
        section: String, versionLabel: String, pairId: String,
    ): Map<String, Any?> {
        val archived = archive.clips.getValue(sourceId)
        val original = LinkedHashMap(archived)
        val id = "${archive.key}_$sourceId"
        val hashes = archived.getValue("sha256") as Map<*, *>
        val paths = mapOf("raw" to "raw/$id.wav", "matched" to "matched/$id.wav", "recipeUrl" to "recipes/$id.json")
        for ((key, path) in paths) {
            val target = File(root, path)
            target.parentFile.mkdirs()
            archiveAsset(archive.root, archived.getValue(key) as String).copyTo(target, overwrite = true)
            val expected = if (key == "recipeUrl") archive.recipeHashes.getValue(sourceId) else hashes[key] as String
            check(sha256(target) == expected) { "$id $key changed while copying the published asset" }
            original[key] = path
        }
        val patch = RegistersPatch.fromJsonText(File(root, paths.getValue("recipeUrl")).readText())
        check(patch.model == archive.model && patch.voice == RegistersVoice.PIPE)
        val probe = patch.probe()
        val samples = if (probe.isLoop) FloatArray(probe.samples.size * 3) { probe.samples[it % probe.samples.size] } else probe.samples
        val legacy = Snip(samples, 1, Dsp.RATE)
        val exportGain = minOf(1f, .99f / legacy.peak().coerceAtLeast(1e-9f))
        val raw = Snip(FloatArray(legacy.frameCount) { legacy.samples[it] * exportGain }, 1, Dsp.RATE)
        check(sha256(wavBytes(raw)) == hashes["raw"] && sha256(wavBytes(AuditionLevel.level(legacy))) == hashes["matched"]) {
            "$id model ${archive.model} no longer replays the published original WAVs"
        }
        original["id"] = id
        original["label"] = "$versionLabel · ${archived.getValue("label")}"
        original["description"] = "Preserved published ${archive.key.uppercase()} recording, recipe and source identity."
        original["section"] = section
        original["comparisonPair"] = pairId
        original["comparisonLabel"] = versionLabel
        original.remove("comparisonOriginalId")
        original.remove("comparisonRevisedId")
        original["auditionRevision"] = archive.revision
        original["recipeSha256"] = archive.recipeHashes.getValue(sourceId)
        original["provenance"] = mapOf(
            "kind" to "preserved-published-recording", "sourceClipId" to sourceId,
            "sourceAudioHash" to archive.audioHash, "sourceManifest" to "baseline/${archive.key}-manifest.json",
            "sourceManifestSha256" to archive.manifestHash, "modelReplayMatched" to true, "model" to archive.model,
        )
        addReviewScope(original, sourceId, archive)
        println("Preserved $id · explicit model ${archive.model} replay matched both published WAV hashes.")
        return original
    }

    private fun airflowComparisons(root: File, cases: List<Case>, clips: List<Map<String, Any?>>): List<Map<String, Any?>> = cases.map { case ->
        val revised = clips.single { it["id"] == case.id }
        val original = clips.single { it["id"] == "r2_${case.id}" }
        check(json(revised.getValue("macros")) == json(original.getValue("macros")) &&
            (revised.getValue("note") as Number).toInt() == (original.getValue("note") as Number).toInt() &&
            (revised.getValue("velocity") as Number).toFloat() == (original.getValue("velocity") as Number).toFloat()) {
            "${case.id}: R2/R3 pair changed pitch, velocity or controls"
        }
        val flow = revised.getValue("airflow") as Map<*, *>
        val from = (flow["analysisStartFrame"] as Number).toInt()
        val until = (flow["analysisEndFrame"] as Number).toInt()
        val oldLevels = linkedMapOf<String, Any?>()
        val newLevels = linkedMapOf<String, Any?>()
        for (mode in listOf("raw", "matched")) {
            val old = WavReader.read(File(root, original.getValue(mode) as String))
            val current = WavReader.read(File(root, revised.getValue(mode) as String))
            check(old.frameCount == current.frameCount)
            oldLevels[mode] = mapOf("outputRms" to windowRms(old.samples, from, until),
                "airflowRms" to 0.0, "bodyRms" to windowRms(old.samples, from, until), "flowToBodyDb" to null)
            @Suppress("UNCHECKED_CAST")
            val tap = flow[mode] as Map<String, Any?>
            newLevels[mode] = tap + ("outputRms" to windowRms(current.samples, from, until))
        }
        val unchanged = case.macro == "AIR" && case.setting == "low"
        if (unchanged) check(revised.getValue("sha256") == original.getValue("sha256"))
        mapOf(
            "id" to "airflow_${case.id}", "group" to "airflow", "label" to case.label,
            "originalId" to "r2_${case.id}", "revisedId" to case.id,
            "originalLabel" to "Current R2", "revisedLabel" to "With airflow R3",
            "description" to if (unchanged) "AIR 0 control: added sustained airflow is disabled, so both recordings remain identical."
                else "Same accepted attack, note, velocity and controls. R3 adds soft airflow beneath the sustained PIPE body.",
            "expectedUnchanged" to unchanged,
            "first80msRawUnchanged" to if (revised["loop"] == null) true else null,
            "settled" to mapOf("window" to mapOf("fromFrame" to from, "untilFrame" to until,
                "fromSeconds" to from.toDouble() / Dsp.RATE, "untilSeconds" to until.toDouble() / Dsp.RATE),
                "original" to oldLevels, "revised" to newLevels),
            "airflowSpectrum" to flow["spectrum"],
        )
    }

    private fun addReviewScope(clip: MutableMap<String, Any?>, id: String, archive: Baseline) {
        clip["reviewRevision"] = archive.revision
        clip["reviewAudioHash"] = archive.audioHash
        clip["reviewClipId"] = id
    }

    private fun airflowEvidence(probe: RegistersProbe): Map<String, Any?> {
        val from = probe.airflowAnalysisStartFrame
        val until = probe.airflowAnalysisEndFrame
        check(from >= 0 && until > from && until <= probe.samples.size)
        val flowRms = probe.airflowSamples?.let { windowRms(it, from, until) } ?: 0.0
        check(abs(flowRms - probe.airflowRms) < 1e-10)
        return mapOf(
            "analysisStartFrame" to from, "analysisEndFrame" to until,
            "analysisStartSeconds" to from.toDouble() / Dsp.RATE, "analysisEndSeconds" to until.toDouble() / Dsp.RATE,
            "airflowRms" to probe.airflowRms, "bodyRms" to probe.bodyRms,
            "flowToBodyDb" to if (probe.airflowRms > 0.0 && probe.bodyRms > 0.0)
                20.0 * log10(probe.airflowRms / probe.bodyRms) else null,
            "enabled" to (probe.airflowSamples != null),
            "spectrum" to flowSpectrum(probe.airflowSamples, from, until, probe.effectiveHz),
            "cycleError" to probe.airflowCycleError, "seamError" to probe.airflowSeamError,
            "source" to "conditioned pre-level model-3 airflow tap; legacy sparse wind remains in body",
        )
    }

    private fun scaleAirflow(evidence: Map<String, Any?>, rawGain: Double, matchedGain: Double): Map<String, Any?> {
        fun scaled(gain: Double) = mapOf(
            "airflowRms" to (evidence.getValue("airflowRms") as Double) * gain,
            "bodyRms" to (evidence.getValue("bodyRms") as Double) * gain,
            "flowToBodyDb" to evidence["flowToBodyDb"], "playbackGain" to gain,
        )
        return evidence + mapOf("raw" to scaled(rawGain), "matched" to scaled(matchedGain))
    }

    private fun matchingGain(source: Snip, matched: Snip): Double {
        val index = source.samples.indices.maxBy { abs(source.samples[it]) }
        return matched.samples[index].toDouble() / source.samples[index]
    }

    private fun windowRms(samples: FloatArray, from: Int, until: Int): Double {
        check(from >= 0 && until > from && until <= samples.size)
        var energy = 0.0
        for (i in from until until) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (until - from))
    }

    private fun flowSpectrum(samples: FloatArray?, from: Int, until: Int, rootHz: Double): Map<String, Any> {
        val count = until - from
        var size = 1
        while (size < count) size *= 2
        val re = FloatArray(size)
        val im = FloatArray(size)
        var windowSquared = 0.0
        for (i in 0 until count) {
            val window = .5 - .5 * cos(2.0 * PI * i / (count - 1))
            windowSquared += window * window
            re[i] = ((samples?.get(from + i) ?: 0f) * window).toFloat()
        }
        Fft.forward(re, im)
        var energy = 0.0
        var nonharmonic = 0.0
        for (bin in 1 until size / 2) {
            val hz = bin * Dsp.RATE.toDouble() / size
            if (hz !in 120.0..12000.0) continue
            val power = 2.0 * (re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin])
            energy += power
            val harmonic = (hz / rootHz).roundToInt()
            if (harmonic < 1 || abs(hz - harmonic * rootHz) > 2.0) nonharmonic += power
        }
        val divisor = size * windowSquared
        return mapOf(
            "windowFrames" to count, "fftSize" to size, "binHz" to Dsp.RATE.toDouble() / size,
            "bandLowHz" to 120, "bandHighHz" to 12000, "harmonicMaskHz" to 2.0,
            "bandMeanSquare" to energy / divisor, "nonharmonicMeanSquare" to nonharmonic / divisor,
            "nonharmonicFraction" to if (energy > 0.0) nonharmonic / energy else 0.0,
        )
    }

    private fun archiveAsset(root: File, path: String): File {
        val file = File(root, path).canonicalFile
        check(file.toPath().startsWith(root.toPath()) && file.isFile) { "Invalid archived asset $path" }
        return file
    }

    private fun wavBytes(snip: Snip): ByteArray = ByteArrayOutputStream().use { stream ->
        WavWriter.write(stream, snip, WavWriter.BitDepth.PCM_24)
        stream.toByteArray()
    }

    private fun transientMetrics(snip: Snip): Map<String, Any> {
        val frames = minOf(snip.frameCount, (.08 * snip.sampleRate).roundToInt())
        val fftSize = 4096
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        var energy = 0.0
        var windowSquaredSum = 0.0
        for (i in 0 until frames) {
            val sample = snip.samples[i].toDouble()
            energy += sample * sample
            val window = .5 - .5 * cos(2.0 * PI * i / (frames - 1))
            windowSquaredSum += window * window
            re[i] = (sample * window).toFloat()
        }
        Fft.forward(re, im)
        var bandEnergy = 0.0
        for (bin in 1 until fftSize / 2) {
            val hz = bin * snip.sampleRate.toDouble() / fftSize
            if (hz in 1500.0..8000.0) bandEnergy += 2.0 * (re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin])
        }
        val bandMeanSquare = bandEnergy / (fftSize * windowSquaredSum)
        return mapOf(
            "frames" to frames, "seconds" to frames.toDouble() / snip.sampleRate,
            "rms" to sqrt(energy / frames), "bandLowHz" to 1500, "bandHighHz" to 8000,
            "bandMeanSquare" to bandMeanSquare, "bandRms" to sqrt(bandMeanSquare),
            "method" to "Hann-windowed first 80 ms, 4096-point FFT; two-sided Parseval band power / window squared sum",
        )
    }

    private fun transientChange(original: Map<String, Any>, revised: Map<String, Any>) = mapOf(
        "rms" to 20.0 * log10((revised.getValue("rms") as Number).toDouble().coerceAtLeast(1e-20) /
            (original.getValue("rms") as Number).toDouble().coerceAtLeast(1e-20)),
        "bandEnergy" to 10.0 * log10((revised.getValue("bandMeanSquare") as Number).toDouble().coerceAtLeast(1e-30) /
            (original.getValue("bandMeanSquare") as Number).toDouble().coerceAtLeast(1e-30)),
    )

    private fun audioHash(clips: List<Map<String, Any?>>): String = sha256(clips.sortedBy { it.getValue("id") as String }.joinToString("\n") {
        val hashes = it.getValue("sha256") as Map<*, *>
        "${it.getValue("id")}:${hashes["raw"]}:${hashes["matched"]}"
    }.toByteArray(Charsets.UTF_8))

    private fun renderReference(case: Case): Render {
        val hz = Keys.midiHz(case.note).toDouble()
        if (case.voice == "TONEWHEEL") {
            val macros = Tonewheel.defaults(TonewheelVoice.FULL) + ("TUNE" to (case.note - 45) / 24f)
            check(abs(Tonewheel.frequencyFor(macros.getValue("TUNE")).toDouble() - hz) < .001)
            fun samples(): FloatArray {
                val raw = Tonewheel.synthesize(TonewheelVoice.FULL, macros, 2.1f, Dsp.RATE * Dsp.OVERSAMPLE, motion = true)
                val out = Dsp.decimate(raw, Dsp.RATE)
                Dsp.fadeTail(out)
                return out
            }
            val output = samples()
            check(output.contentEquals(samples())) { "${case.id}: TONEWHEEL reference was not deterministic" }
            return Render(Snip(output, 1, Dsp.RATE), macros, json(mapOf(
                "engine" to "TONEWHEEL", "voice" to "FULL", "note" to case.note,
                "macros" to macros, "renderConfiguration" to mapOf(
                    "gateSeconds" to 2.1, "motion" to true, "normalize" to false,
                    "velocitySupported" to false,
                ),
            )), mapOf("requestedHz" to hz, "effectiveHz" to hz, "tuningErrorCents" to 0.0,
                "deterministic" to true, "velocitySupported" to false))
        }
        val macros = Prism.defaults(PrismVoice.ORGAN) + ("TUNE" to (case.note - Prism.rootMidi(PrismVoice.ORGAN)) / 24f)
        check(abs(Prism.frequencyFor(PrismVoice.ORGAN, macros.getValue("TUNE")).toDouble() - hz) < .001)
        val patch = PrismPatch(case.label, PrismVoice.ORGAN, macros, case.velocity)
        val probe = Prism.probe(PrismVoice.ORGAN, macros, case.velocity, patch.template, normalize = false)
        val repeated = Prism.probe(PrismVoice.ORGAN, macros, case.velocity, patch.template, normalize = false)
        check(probe.samples.contentEquals(repeated.samples)) { "${case.id}: PRISM reference was not deterministic" }
        val recipe = JsonValue.Obj(patch.toJsonValue().entries + ("renderConfiguration" to json(mapOf("normalize" to false))))
        return Render(Snip(probe.samples, 1, Dsp.RATE), macros, recipe,
            mapOf("requestedHz" to hz, "effectiveHz" to probe.effectiveHz, "tuningErrorCents" to 0.0,
                "rawPeak" to probe.raw.maxOf { abs(it).toDouble() }, "deterministic" to true))
    }

    private fun validateSnip(snip: Snip, id: String) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.frameCount > 2)
        check(snip.samples.all { it.isFinite() } && snip.peak() > 1e-6f) { "$id: invalid or silent render" }
    }

    private fun validateExport(file: File, expected: Snip): Snip {
        val bytes = file.readBytes()
        // WavWriter emits a fixed PCM fmt chunk before data; verify the actual exported format.
        check((bytes[20].toInt() and 255) == 1 && (bytes[34].toInt() and 255) == 24)
        val decoded = WavReader.read(bytes)
        validateSnip(decoded, file.name)
        check(decoded.frameCount == expected.frameCount && decoded.peak() <= .990001f)
        check(decoded.samples.indices.all { abs(decoded.samples[it] - expected.samples[it]) <= 1.3e-7f }) {
            "${file.name}: PCM roundtrip exceeded quantization tolerance"
        }
        return decoded
    }

    private fun metrics(snip: Snip): Map<String, Any> {
        val samples = snip.samples
        var sum = 0.0
        var energy = 0.0
        var differenceEnergy = 0.0
        for (i in samples.indices) {
            val sample = samples[i].toDouble()
            sum += sample
            energy += sample * sample
            if (i > 0) { val difference = sample - samples[i - 1]; differenceEnergy += difference * difference }
        }
        val rms = sqrt(energy / samples.size)
        return mapOf(
            "peak" to snip.peak().toDouble(), "rms" to rms,
            "loudestWindowRms" to Loudness.of(snip).toDouble(), "dcMean" to sum / samples.size,
            "firstDifferenceRms" to sqrt(differenceEnergy / (samples.size - 1)),
            "crestDb" to 20 * log10(snip.peak().toDouble() / rms.coerceAtLeast(1e-20)),
        )
    }

    private fun controlDescription(voice: RegistersVoice, macro: String): String = when (macro) {
        "COLOR" -> if (voice == RegistersVoice.PIPE) "Registration-inspired harmonic weighting moves from dark foundation to brighter principal color."
            else "Reed harmonic balance moves from rounded body to brighter accordion detail."
        "AIR" -> if (voice == RegistersVoice.PIPE) "Wind changes pipe speech and adds soft sustained airflow beneath the body. AIR 0 disables the added layer."
            else "Bellows pressure changes the swell and reed excitation; use matched playback to hear articulation."
        "EDGE" -> if (voice == RegistersVoice.PIPE) "The wind-driven chiff becomes more pronounced at the pipe entrance."
            else "Pressure-dependent reed buzz becomes more pronounced."
        "SPREAD" -> "Symmetric detuning changes beating between ranks or reeds while the requested note stays fixed."
        "MOTION" -> if (voice == RegistersVoice.PIPE) "Tremulant introduces periodic movement in the sustained pipe body."
            else "Periodic vibrato moves the sustained reed body."
        else -> error("Unknown control $macro")
    }

    private fun sections() = listOf(
        section("comparisons", "Soft pipe airflow", "Twelve direct pairs compare the accepted R2 attack with R3's soft sustained airflow at the same note, velocity and controls. AIR 0 is the unchanged control."),
        section("chiff-history", "Chiff history", "The twelve original R1/R2 chiff comparisons remain available with their preserved recordings and measurements."),
        section("identities", "First listen", "PIPE and MUSETTE at C3, C4 and A4, each with restrained and expressive articulation. Start with matched playback, then compare raw energy."),
        section("velocity", "Touch", "The same C4 patch at 25%, 60% and full velocity. Raw reveals dynamics; matched reveals changes in excitation and articulation."),
        section("sweeps", "One control at a time", "Low, default and high COLOR, AIR, EDGE, SPREAD and MOTION. Every other control stays at the voice defaults, with the same finite HOLD."),
        section("loops", "Held motion", "Four onset-free held cases with low or high spread and motion. Files contain three exact wraps of the engine's loop, with independent seam evidence."),
        section("references", "Existing SnipSnap organs", "TONEWHEEL FULL and PRISM ORGAN at their shared C3/C4 notes. Compare matched identity and attack against the new prototypes."),
    )

    private fun voices() = listOf(
        mapOf("id" to "PIPE", "label" to "PIPE", "description" to "Twelve harmonic partials with registration-inspired weighting, gradual speech, wind and chiff."),
        mapOf("id" to "MUSETTE", "label" to "MUSETTE", "description" to "Three beating reeds, bellows swells and pressure-dependent buzz."),
        mapOf("id" to "TONEWHEEL", "label" to "TONEWHEEL FULL", "description" to "Existing drawbar organ with scanner chorus, percussion and rotary movement."),
        mapOf("id" to "PRISM", "label" to "PRISM ORGAN", "description" to "Existing additive organ template with developing partial groups."),
    )

    private fun section(id: String, label: String, description: String) = mapOf("id" to id, "label" to label, "description" to description)
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.5f", value)
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun repositoryRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun sourceFingerprints(repository: File): List<Map<String, String>> {
        val paths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Registers.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/RegistersPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tonewheel.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Prism.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/RegistersAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt",
        )
        return paths.map { path -> mapOf("path" to path, "sha256" to sha256(File(repository, path))) }
    }

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

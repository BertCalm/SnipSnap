package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Actual JVM renderings and reproducible evidence for the finite first-listen milestone. */
object ChamberAuditionGenerator {
    private const val RAW_GAIN = 1f
    private val controls = listOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val instruments = listOf("Membrane", "Bar", "Cowbell", "Triangle")

    private data class Case(
        val id: String,
        val title: String,
        val section: String,
        val patch: ChamberPatch,
        val description: String,
        val question: String,
        val options: Chamber.ProbeOptions = Chamber.ProbeOptions(normalize = true, recordStems = false),
        val stem: String = "mix",
        val reference: String? = null,
        val compareGroup: String? = null,
        val variant: String? = null,
        val tags: List<String> = emptyList(),
        val sweep: String? = null,
        val step: Float? = null,
    ) {
        val diagnostic get() = stem != "mix" || !options.roomEnabled || !options.returnsEnabled ||
            options.isolatedInstrument != null || options.averageSurfaces || !options.submergedModulationEnabled || reference != null
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            ChamberDiagnosticReplay.main(args.drop(1).toTypedArray())
            return
        }
        require(args.all { !it.startsWith("--") || it == "--timpani" || it == "--timpani-boost" || it.startsWith("--previous=") }) {
            "supply the output directory, --timpani or --timpani-boost and optionally --previous=/absolute/previous-audition"
        }
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size <= 1) { "supply only one output directory" }
        val timpaniBoost = "--timpani-boost" in args
        require(!timpaniBoost || "--timpani" !in args) { "choose one timpani revision" }
        val timpaniRevision = "--timpani" in args || timpaniBoost
        val previous = args.firstOrNull { it.startsWith("--previous=") }?.substringAfter('=')?.let(::File)
        require(previous == null || timpaniRevision) { "--previous requires a timpani revision" }
        val root = File(positional.firstOrNull() ?: "../testkit/chamber-audition").canonicalFile
        root.mkdirs()
        File(root, "manifest.json").delete()
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, ".git").exists() } ?: error("run from the SnipSnap repository")
        val source = provenance(repo, root)
        for (name in listOf("index.html", "app.js", "styles.css")) {
            val resource = ChamberAuditionGenerator::class.java.getResourceAsStream("/chamber-audition/$name")
                ?: error("missing retained CHAMBER audition page resource $name")
            resource.use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
        }
        val sourceRevision = source.getValue("revision")
        val previousAudition = previous?.let { archivePrevious(it, root, if (timpaniBoost) 2 else 1) }
        val cases = if (timpaniRevision) timpaniCases(timpaniBoost) else cases()
        require(cases.map { it.id }.distinct().size == cases.size) { "duplicate CHAMBER clip ID" }
        val clips = mutableListOf<Map<String, Any?>>()
        val failures = mutableListOf<Map<String, Any?>>()
        val defaultChecks = mutableListOf<Map<String, Any?>>()
        val gains = mutableMapOf<String, Float>()
        val references = mutableMapOf<String, FloatArray>()
        val sweepPrevious = mutableMapOf<String, FloatArray>()
        val referencedIds = cases.mapNotNull { it.reference }.toSet()
        val started = System.nanoTime()

        for ((index, case) in cases.withIndex()) {
            val began = System.nanoTime()
            try {
                val p = case.patch
                val renderOptions = case.options.copy(recordStems = case.options.recordStems || case.stem != "mix")
                val probe = Chamber.renderProbe(p.voice, p.macros, p.midi, p.velocity, p.configuration, p.seed, p.model, renderOptions)
                val selected = ChamberDiagnosticReplay.select(probe, case.stem)
                val native = Snip(if (case.stem == "mix") probe.samples else selected, 1, Dsp.RATE)
                val gain = case.reference?.let { gains.getValue(it) }
                    ?: matchingGain(selected, AuditionLevel.level(Snip(selected, 1, Dsp.RATE)).samples)
                // Derive a single gain before the tail envelope. The reference and every
                // diagnostic branch then receive that identical gain and identical fade.
                val matchedSamples = scaled(selected, gain).also { Dsp.fadeTail(it) }
                val raw = Snip(scaled(selected, RAW_GAIN), 1, Dsp.RATE)
                val matched = Snip(matchedSamples, 1, Dsp.RATE)
                gains[case.id] = gain
                val localFailures = mutableListOf<String>()
                if (selected.isEmpty() || !selected.all { it.isFinite() } || !matched.samples.all { it.isFinite() })
                    localFailures += "empty or non-finite audio"
                if (!probe.diagnostics.finiteStates) localFailures += "non-finite internal states"
                if (raw.peak() >= 1f) localFailures += "raw PCM export clips at common gain $RAW_GAIN"
                if (matched.peak() > .991f) localFailures += "matched PCM exceeds peak guard"
                val d = probe.diagnostics
                if (d.maxEnergy > d.primaryWork * 1.0001 + 1e-10)
                    localFailures += "complete network energy exceeds primary work"
                if (d.primaryWork > d.primaryWorkBudget * 1.0001 + 1e-10)
                    localFailures += "primary strike work exceeds the declared budget"
                if (d.maxUnforcedEnergyRise > d.maxEnergy * 1e-4 + 1e-10)
                    localFailures += "unforced network energy grows"
                if (d.passiveCouplingNorm > 1.0000001 || d.maxDelayGain > 1.0)
                    localFailures += "network component exceeds passive bound"
                val expectedStrikes = (0..3).count { sourceIndex -> p.velocity > 0f && p.configuration.instrumentEnabled[sourceIndex] &&
                    (renderOptions.isolatedInstrument == null || renderOptions.isolatedInstrument == sourceIndex) }
                if (d.sharedStrikeTimes.size != expectedStrikes || d.sharedStrikeTimes.any { it != 0.0 })
                    localFailures += "quartet strikes do not share the source timestamp"

                val renderMs = (System.nanoTime() - began) / 1e6
                val metrics = LinkedHashMap(TangleMeasure.metrics(selected, matched, p.midi, renderMs))
                val onset = Classifier.classify(Snip(matched.samples.copyOfRange(0,
                    minOf(matched.samples.size, (1.2f * Dsp.RATE).toInt())), 1, Dsp.RATE))
                metrics["onsetClassifier"] = onset.drumClass.name
                metrics["onsetClassifierConfidence"] = onset.confidence
                metrics["onsetClassifierWindowSeconds"] = 1.2
                metrics["matchedLoudness"] = Loudness.of(matched)
                metrics["nativeLoudness"] = Loudness.of(native)
                metrics["rawExportGain"] = RAW_GAIN
                metrics["matchedGainFromPrelevel"] = gain
                metrics["matchingPolicy"] = if (case.reference == null) "full-mix loudest 200 ms RMS 0.03 with 0.99 peak guard"
                    else "same fixed gain as ${case.reference}; diagnostic energy is not independently normalized"
                metrics["gainReference"] = case.reference
                metrics["primaryWork"] = d.primaryWork
                metrics["primaryWorkBudget"] = d.primaryWorkBudget
                metrics["primaryEndSeconds"] = d.primaryEndSeconds
                metrics["energyAtPrimaryEnd"] = d.energyAtPrimaryEnd
                metrics["maxNetworkEnergy"] = d.maxEnergy
                metrics["finalNetworkEnergy"] = d.finalEnergy
                metrics["maxUnforcedEnergyRise"] = d.maxUnforcedEnergyRise
                metrics["passiveCouplingNorm"] = d.passiveCouplingNorm
                metrics["maxDelayGain"] = d.maxDelayGain
                metrics["finiteStates"] = d.finiteStates
                metrics["peakModalState"] = d.peakModalState
                metrics["modalCount"] = d.modalCount
                metrics["travelingSamples"] = d.travelingSamples
                metrics["sharedStrikeTimes"] = d.sharedStrikeTimes
                metrics["preparationMs"] = d.preparationNanos / 1e6
                metrics["synthesisMs"] = d.synthesisNanos / 1e6
                metrics["outputMs"] = d.outputNanos / 1e6
                metrics["materialPositions"] = probe.geometry.regionMaterialPositions
                if (renderOptions.recordStems) {
                    metrics["instrumentLocalRms"] = probe.instrumentStems.map { TangleMeasure.rms(it) }
                    metrics["surfaceLocalRms"] = probe.surfaceStems.map { TangleMeasure.rms(it) }
                    metrics["stemRms"] = linkedMapOf("direct" to TangleMeasure.rms(probe.direct),
                        "early" to TangleMeasure.rms(probe.early), "surface" to TangleMeasure.rms(probe.surface),
                        "late" to TangleMeasure.rms(probe.late))
                }
                case.reference?.let { reference ->
                    val samples = references.getValue(reference)
                    metrics["referenceShapeDistance"] = TangleMeasure.distance(samples, selected)
                    metrics["referenceSpectralDistance"] = TangleMeasure.spectralDistance(samples, selected)
                    metrics["referenceRelativeRmsDifference"] = relativeDifference(samples, selected)
                }
                case.sweep?.let { macro ->
                    val key = "${p.voice}:$macro"
                    sweepPrevious[key]?.let { previous ->
                        metrics["previousStepShapeDistance"] = TangleMeasure.distance(previous, selected)
                        metrics["previousStepSpectralDistance"] = TangleMeasure.spectralDistance(previous, selected)
                    }
                    sweepPrevious[key] = selected
                    metrics["control"] = macro
                    metrics["controlValue"] = case.step
                }

                if (case.section == "first-listen") {
                    val replay = ChamberPatch.fromJsonText(p.toJsonText())
                    val second = Chamber.renderProbe(p.voice, p.macros, p.midi, p.velocity, p.configuration,
                        p.seed, p.model, renderOptions)
                    val exactRaw = probe.rawMix.contentEquals(second.rawMix)
                    val exactExport = probe.samples.contentEquals(replay.render().samples)
                    val exactPatch = p == replay && p == PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).patch
                    defaultChecks += linkedMapOf("clip" to case.id, "deterministicRaw" to exactRaw,
                        "recipeRoundTrip" to exactPatch, "productionRecipeSampleReplay" to exactExport)
                    if (!exactRaw || !exactExport || !exactPatch) localFailures += "default deterministic or recipe replay failed"
                }
                // Isolated metal instruments are intentionally inharmonic. The source quartet
                // and production recipes must retain a safe pitched classification.
                if (case.stem == "mix" && renderOptions.roomEnabled && renderOptions.isolatedInstrument == null &&
                    TangleMeasure.drumClasses.any { it.name == metrics["classifier"] })
                    localFailures += "production quartet classified as ${metrics["classifier"]}"

                val rawPath = "raw/${case.id}.wav"
                val matchedPath = "matched/${case.id}.wav"
                val recipePath = "recipes/${case.id}.json"
                val tracePath = "diagnostics/${case.id}.json"
                WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_16)
                WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
                val recipe = if (case.diagnostic) ChamberDiagnosticReplay.recipe(p, renderOptions, case.stem, gain, RAW_GAIN)
                    else PadRecipe(p).toJsonValue()
                writeJson(File(root, recipePath), recipe)
                writeJson(File(root, tracePath), linkedMapOf(
                    "clip" to case.id,
                    "options" to ChamberDiagnosticReplay.optionsJson(renderOptions),
                    "stem" to case.stem,
                    "energy" to d.energy.map { linkedMapOf("seconds" to it.timeSeconds,
                        "instruments" to it.instrumentEnergy, "surfaces" to it.surfaceEnergy,
                        "traveling" to it.travelingEnergy, "total" to it.totalEnergy, "primaryWork" to it.primaryWork) },
                    "arrivals" to probe.arrivals.map { linkedMapOf("source" to it.sourceIndex,
                        "kind" to it.kind, "seconds" to it.timeSeconds, "face" to it.face,
                        "materialWeights" to it.materialWeights) },
                    "regions" to ChamberRegion.entries.mapIndexed { i, r -> linkedMapOf("id" to r.name,
                        "weights" to probe.geometry.regionMaterialWeights[i], "area" to probe.geometry.regionAreas[i]) },
                    "failures" to localFailures,
                ))
                metrics["audioSha256"] = linkedMapOf("raw" to sha(File(root, rawPath)), "matched" to sha(File(root, matchedPath)))
                metrics["recipeSha256"] = sha(File(root, recipePath))
                metrics["diagnostics"] = tracePath
                metrics["passedHardGates"] = localFailures.isEmpty()
                clips += linkedMapOf("id" to case.id, "title" to case.title, "section" to case.section,
                    "model" to p.model, "sourceRevision" to sourceRevision,
                    "voice" to p.voice.name, "midi" to p.midi, "velocity" to p.velocity,
                    "description" to case.description, "question" to case.question,
                    "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                    "metrics" to metrics, "tags" to (case.tags + if (case.diagnostic) listOf("diagnostic") else listOf("production-recipe")),
                    "compareGroup" to case.compareGroup, "variant" to case.variant)
                if (case.id in referencedIds) references[case.id] = selected
                if (localFailures.isNotEmpty()) failures += linkedMapOf("clip" to case.id, "errors" to localFailures)
                println("${index + 1}/${cases.size} ${case.id}: ${"%.0f".format(Locale.ROOT, renderMs)} ms; peak ${probe.rawPeak}; ${localFailures.ifEmpty { listOf("pass") }.joinToString()}")
            } catch (e: Exception) {
                failures += linkedMapOf("clip" to case.id, "errors" to listOf(e.message ?: e.javaClass.simpleName))
                println("${index + 1}/${cases.size} ${case.id}: FAILED ${e.message}")
            }
        }

        val starterIds = clips.filter { clip ->
            timpaniRevision || clip["section"] == "first-listen" || clip["section"] == "mechanisms" || clip["section"] == "materials"
        }.map { it.getValue("id") as String }.toSet()
        writeJson(File(root, "source-provenance.json"), source)
        writeJson(File(root, "checks.json"), linkedMapOf("passed" to failures.isEmpty(), "defaultReplays" to defaultChecks,
            "rendered" to clips.size, "requested" to cases.size, "failures" to failures))
        val manifest = linkedMapOf<String, Any?>(
            "title" to if (timpaniBoost) "CHAMBER — Timpani Mix Lift" else if (timpaniRevision) "CHAMBER — Timpani Revision" else "CHAMBER — First Listen",
            "model" to cases.maxOf { it.patch.model }, "sourceRevision" to sourceRevision, "createdAt" to Instant.now().toString(),
            "models" to cases.map { it.patch.model }.distinct().sorted(),
            "auditionRevision" to if (timpaniBoost) "timpani-boost" else if (timpaniRevision) "timpani" else null,
            "membraneMixLiftDb" to if (timpaniBoost) 3.0 else null,
            "previousAudition" to previousAudition,
            "sampleRate" to Dsp.RATE,
            "summary" to if (timpaniBoost)
                "Timpani mix lift: the accepted model-2 membrane sound with 3 dB more listener pickup in model 3. Before/after quartets and dry membrane solos at C3 and C4 share one gain per room and note from the boosted full quartet. Voicing, strike, tuning, room and feedback are unchanged."
                else if (timpaniRevision)
                "Timpani revision: previous and revised quartet strikes at C3 and C4, with the membrane heard alone at its actual mix level. Each four-clip group shares one gain from the revised full quartet. The note, velocity, macros, seed and room remain identical across models."
                else "One simultaneous membrane, bar, cowbell and triangle strike. Two rooms with independently addressed material regions return energy into those instruments. Actual finite Kotlin engine renders, with direct, surface, position and return comparisons.",
            "limitations" to listOf("First listening prototype: HYBRID and AQUARIUM only; HOLD is unsupported.",
                "Designed acoustic materials and modal instruments; no measured-room or orchestral realism claim.",
                "Each rendered note contains its own mono room. Chords mix separate room recordings; sample transposition shifts room resonances too.",
                "Submerged-glass modulation is a listener-only branch, excluded from the passive feedback network.",
                "Near-root spectral measurements locate energy near the requested pitch; they do not replace the independent pitch detector or listening.",
                "The factory roster, phone picker and starter menu are unchanged pending listening review."),
            "checks" to linkedMapOf("passed" to failures.isEmpty(), "rendered" to clips.size, "requested" to cases.size,
                "defaults" to defaultChecks, "failures" to failures,
                "report" to "checks.json", "provenance" to "source-provenance.json",
                "renderSeconds" to (System.nanoTime() - started) / 1e9),
            "normalization" to linkedMapOf("format" to "PCM_16 mono 44100 Hz", "rawExportGain" to RAW_GAIN,
                "description" to if (timpaniBoost)
                    "Accepted/boosted quartets and their dry membrane solos share one gain per room and note, derived from the boosted full quartet. Solos are never boosted separately. Raw uses common gain 1.0."
                    else if (timpaniRevision)
                    "Previous/revised quartets and their dry membrane solos share one gain per room and note, derived from the revised full quartet. Solos are never boosted separately. Raw uses common gain 1.0."
                    else "Matched full mixes use loudest 200 ms RMS 0.03; mechanism comparisons and stems share their full-reference gain. Raw uses common gain 1.0.",
                "fullMix" to "AuditionLevel: loudest 200 ms RMS 0.03, 0.99 peak guard on the prelevel mix, followed by the production tail fade.",
                "diagnostics" to "Causal comparisons and stems share one gain derived from their full reference and the same tail fade. Weak returns and surface stems are never independently boosted.",
                "instrumentStatePickup" to "Sum of local radiation stems times the corresponding direct-distance gain and fixed direct pickup 0.46; no propagation delay. Identical pickup and gain in return on/off.",
                "raw" to "Raw WAVs contain unscaled prelevel samples at common gain 1.0; peaks are checked before PCM conversion."),
            "clips" to clips,
            "downloads" to linkedMapOf("zip" to "starter-pack.zip", "manifest" to "manifest.json",
                "checks" to "checks.json", "provenance" to "source-provenance.json"),
        )
        writeJson(File(root, "manifest.json"), manifest)
        File(root, "README.txt").writeText(
            "CHAMBER ${if (timpaniBoost) "timpani mix lift" else if (timpaniRevision) "timpani revision" else "first-listen prototype"}\nActual Kotlin 4x renders, 44.1 kHz mono PCM16.\n" +
                "Raw WAVs use common gain $RAW_GAIN; full mixes use AuditionLevel 0.03, diagnostic groups share the full reference gain.\n" +
                "Production recipes are PadRecipe JSON. Diagnostic recipes explicitly record probe options and output selection.\n" +
                "Replay a diagnostic: ./gradlew :synth:replayChamberDiagnostic -PchamberRecipe=/absolute/recipe.json -PchamberReplayOutput=/absolute/output.wav\n" +
                "Use -PchamberReplayMode=raw for common-gain prelevel audio; matched is the default.\n" +
                "Source revision: $sourceRevision; exact source hashes and copies: source-provenance.json and source/.\n" +
                "Serve this directory over HTTP (for example python3 -m http.server) to open the included page and JSON manifest.\n" +
                (if (timpaniBoost) "Accepted model 2 and boosted model 3 differ only by 3 dB membrane listener pickup. Each quartet/solo group shares the boosted full quartet gain. Previous pages are archived on the hosted page when supplied with --previous; archives are excluded from this compact starter ZIP.\n" else if (timpaniRevision) "Previous and revised quartet/solo comparisons share one gain from the revised full quartet for each room and note. The original 114-clip study is archived on the hosted page when supplied with --previous. It is not included in this compact starter ZIP.\n" else "") +
                "HOLD, seamless looping, six-voice factory integration and the room editor are future work.\n",
        )
        starterZip(root, clips.filter { it["id"] in starterIds }, manifest)
        check(File(root, "starter-pack.zip").length() < 20L * 1024 * 1024) { "starter ZIP exceeds hosting file limit" }
        println("CHAMBER: ${clips.size}/${cases.size} clips, ${failures.size} failed cases, ${(System.nanoTime() - started) / 1e9} seconds; ${root.path}")
        check(failures.isEmpty() && clips.size == cases.size) { "CHAMBER hard gates failed; see checks.json" }
    }

    /** A bounded listening response; the complete original matrix remains available via cases(). */
    private fun timpaniCases(boost: Boolean = false): List<Case> = buildList {
        val previousModel = if (boost) 2 else 1
        val revisedModel = if (boost) 3 else 2
        val study = if (boost) "timpani-boost" else "timpani"
        val revisedLabel = if (boost) "boosted" else "revised"
        for (voice in ChamberVoice.entries) for (midi in listOf(48, 60)) {
            val slug = voice.name.lowercase(Locale.ROOT)
            val note = if (midi == 48) "C3" else "C4"
            val base = ChamberPresets.forVoice(voice).first().copy(midi = midi, model = revisedModel)
            val revisedFull = "$slug-$study-r$revisedModel-${note.lowercase(Locale.ROOT)}-full"
            val group = "$slug-$study-${note.lowercase(Locale.ROOT)}"
            add(Case(revisedFull, "${voice.name}: $revisedLabel quartet · $note", if (midi == 48) "first-listen" else "timpani",
                base.copy(name = "${voice.name} $revisedLabel quartet $note"),
                if (boost) "The accepted timpani sound with 3 dB more membrane listener pickup. The bar, cowbell, triangle, room and physical strike are unchanged. All following comparisons keep this quartet's gain."
                else "Model 2 full quartet at $note. The timpani-like membrane, bar, cowbell and triangle share one strike. The following previous/revised clips retain this quartet's gain.",
                if (boost) "Does the timpani now have the right presence beside the other three instruments?" else "Can you hear the rounded membrane body beneath the bar and metal voices?",
                compareGroup = group, variant = "$revisedLabel quartet", tags = listOf("timpani", revisedLabel, "model-$revisedModel", "quartet", note)))
            add(Case("$slug-$study-r$previousModel-${note.lowercase(Locale.ROOT)}-full", "${voice.name}: previous quartet · $note", "timpani",
                base.copy(name = "${voice.name} previous quartet $note", model = previousModel),
                if (boost) "Accepted model-2 voicing before the mix lift, with the same note, velocity, controls, seed and saved room. Its level is tied to the boosted quartet."
                else "Model 1 replay of the identical note, velocity, macros, seed and saved room. Its level is tied to the revised quartet, rather than independently matched.",
                if (boost) "Compare the timpani's level while its voicing stays the same." else "Which part of the membrane's attack and pitched decay was missing?", reference = revisedFull,
                compareGroup = group, variant = "previous quartet", tags = listOf("timpani", "previous", "model-$previousModel", "quartet", note)))
            for (model in listOf(previousModel, revisedModel)) {
                val label = if (model == previousModel) "previous" else revisedLabel
                add(Case("$slug-$study-r$model-${note.lowercase(Locale.ROOT)}-solo", "${voice.name}: $label timpani alone · $note", "timpani",
                    base.copy(name = "${voice.name} $label timpani alone $note", model = model),
                    "Dry membrane source from model $model at its unchanged quartet strike strength and balance. Room paths and returns are disabled. It uses the $revisedLabel quartet's full-mix gain, with no solo boost.",
                    if (boost) "The same membrane shape and decay, with only the listener level lifted." else "Does the membrane retain a pitched body after the mallet attack?",
                    options = Chamber.ProbeOptions(normalize = true, roomEnabled = false, returnsEnabled = false, isolatedInstrument = 0),
                    reference = revisedFull, compareGroup = group, variant = "$label timpani solo",
                    tags = listOf("timpani", "solo", label, "model-$model", note)))
            }
        }
    }

    /** Keep historical PCM, recipes and source intact while making the older archive a sibling. */
    private fun archivePrevious(previous: File, root: File, expectedModel: Int): Map<String, Any?> {
        val source = previous.canonicalFile
        require(source.isDirectory && File(source, "index.html").isFile && File(source, "source-provenance.json").isFile) {
            "--previous must be a complete previous CHAMBER audition directory"
        }
        require(!source.toPath().startsWith(root.toPath()) && !root.toPath().startsWith(source.toPath())) {
            "previous audition and output directories must not contain each other"
        }
        val manifestFile = File(source, "manifest.json")
        val manifest = Json.parse(manifestFile.readText()).obj()
        require(manifest.getValue("model").int() == expectedModel) { "the previous audition must be model $expectedModel" }
        val target = File(root, "archive/model-$expectedModel")
        if (target.exists()) check(target.deleteRecursively()) { "could not replace previous audition archive" }
        if (expectedModel == 1) {
            check(source.copyRecursively(target, overwrite = false)) { "could not archive previous audition" }
        } else {
            check(target.mkdirs()) { "could not create previous audition archive" }
            source.listFiles().orEmpty().filter { it.name != "archive" }.forEach { file ->
                check(file.copyRecursively(File(target, file.name), overwrite = false)) { "could not archive ${file.name}" }
            }
            val original = File(target, "manifest-original.json")
            manifestFile.copyTo(original, overwrite = true)
            val previousLink = manifest["previousAudition"]?.takeUnless { it is JsonValue.Null }?.obj()
            if (previousLink != null) {
                val oldArchive = File(source, "archive/model-1")
                require(File(oldArchive, "manifest.json").isFile && File(oldArchive, "index.html").isFile) {
                    "the previous audition's model-1 archive is missing"
                }
                val sibling = File(root, "archive/model-1")
                if (sibling.exists()) check(sibling.deleteRecursively()) { "could not replace original audition archive" }
                check(oldArchive.copyRecursively(sibling, overwrite = false)) { "could not preserve original audition archive" }
                val updated = LinkedHashMap(manifest)
                updated["previousAudition"] = JsonValue.Obj(LinkedHashMap(previousLink).also {
                    it["url"] = JsonValue.Str("../model-1/index.html")
                })
                updated["navigationRelocation"] = JsonValue.Obj(linkedMapOf(
                    "description" to JsonValue.Str("Only the archived page's previous-audition URL moved to its sibling. Audio, recipes and source snapshots are unchanged."),
                    "originalManifest" to JsonValue.Str("manifest-original.json"),
                    "originalManifestSha256" to JsonValue.Str(sha(original)),
                    "previousUrl" to previousLink.getValue("url"),
                    "relocatedUrl" to JsonValue.Str("../model-1/index.html"),
                ))
                writeJson(File(target, "manifest.json"), JsonValue.Obj(updated))
            }
        }
        return linkedMapOf("url" to "archive/model-$expectedModel/index.html",
            "title" to if (expectedModel == 1) "Original 114-clip audition · model 1" else "Previous timpani audition · model 2",
            "model" to expectedModel, "sourceRevision" to manifest["sourceRevision"]?.str())
    }

    private fun cases(): List<Case> = buildList {
        for (voice in ChamberVoice.entries) {
            val slug = voice.name.lowercase(Locale.ROOT)
            val defaults = ChamberPresets.forVoice(voice).first()
            val room = if (voice == ChamberVoice.HYBRID) "two wood and two glass walls, with independent floor and ceiling"
                else "concrete lower regions and floor, with a submerged-glass canopy"
            add(Case("$slug-default", voice.name, "first-listen", defaults,
                "A single simultaneous quartet strike in $room. C4, event velocity 0.75, no external effects.",
                "Do four distinct struck sources and the room answer feel like one playable instrument?",
                tags = listOf("default", "preset")))
            for ((index, preset) in ChamberPresets.forVoice(voice).drop(1).withIndex()) {
                add(Case("$slug-preset-${index + 2}", preset.name, "presets", preset,
                    "Provisional ${voice.name} recipe with the complete saved room configuration.",
                    "Does this control combination suggest a useful musical role?", tags = listOf("preset")))
            }
            val required = defaults.copy(name = "${voice.name} exact split", macros = defaults.macros +
                mapOf("DIVIDE" to 1f, "MATERIAL" to defaults.configuration.materialReference))
            val full = "$slug-required-full"
            add(Case(full, "${voice.name}: exact mixed boundaries", "mechanisms", required,
                "Full DIVIDE at the saved material reference: $room. All quartet, source, and return settings stay fixed in the following ablations.",
                "Can you hear distinct regional answers rather than one averaged material?", compareGroup = "$slug-boundaries", variant = "regional",
                tags = listOf("required-layout", "full-reference")))
            add(Case("$slug-averaged", "${voice.name}: averaged surfaces", "mechanisms", required,
                "Every boundary is replaced by the area-weighted mean of the same ten parallel material-family responses, preserving aggregate room surface composition. Geometry, note, strike and reference gain are retained.",
                "What changes when position-specific boundary contrast is removed?",
                options = Chamber.ProbeOptions(normalize = true, averageSurfaces = true), reference = full,
                compareGroup = "$slug-boundaries", variant = "average", tags = listOf("average-ablation")))
            add(Case("$slug-no-returns", "${voice.name}: instrument returns off", "mechanisms", required,
                "Instrument receiving ports are disconnected while audible listener reflections, surface response and late field remain. The complete output uses the full-reference gain.",
                "Does the source decay change while the listener room is still present?",
                options = Chamber.ProbeOptions(normalize = true, returnsEnabled = false), reference = full,
                compareGroup = "$slug-returns", variant = "off", tags = listOf("return-ablation")))
            add(Case("$slug-state-on", "${voice.name}: source states, returns on", "mechanisms", required,
                "Only instrument-state radiation, picked up with the same distance weights and fixed gain; room output is omitted from this diagnostic stem.",
                "Do instrument states keep a different decay after the room sends energy back?", stem = "instrument-state", reference = full,
                compareGroup = "$slug-instrument-state", variant = "on", tags = listOf("state-stem", "returns-on")))
            add(Case("$slug-state-off", "${voice.name}: source states, returns off", "mechanisms", required,
                "The same source-state pickup with receiving ports disabled. It uses exactly the on-state pickup weights and full-reference gain.",
                "Which late source detail disappears when returning waves cannot reach the modal states?",
                options = Chamber.ProbeOptions(normalize = true, returnsEnabled = false), stem = "instrument-state", reference = full,
                compareGroup = "$slug-instrument-state", variant = "off", tags = listOf("state-stem", "returns-off")))
            add(Case("$slug-direct-quartet", "${voice.name}: direct quartet", "stems", required,
                "The four aligned source strikes with room paths disabled. Direct geometric propagation remains, at the complete-room reference gain.",
                "Can you recognize the four instruments before any surface answer?",
                options = Chamber.ProbeOptions(normalize = true, roomEnabled = false, returnsEnabled = false), reference = full,
                compareGroup = "$slug-sources", variant = "quartet", tags = listOf("direct", "quartet")))
            for ((index, instrument) in instruments.withIndex()) {
                add(Case("$slug-solo-$index", "${voice.name}: $instrument alone", "stems", required,
                    "Isolated $instrument from the same strike and balance, with room disabled. No solo normalization or excitation compensation.",
                    "Does this source have its own attack and decay?",
                    options = Chamber.ProbeOptions(normalize = true, roomEnabled = false, returnsEnabled = false, isolatedInstrument = index),
                    reference = full, compareGroup = "$slug-sources", variant = instrument.lowercase(Locale.ROOT), tags = listOf("solo", instrument.lowercase(Locale.ROOT))))
            }
            for (stem in listOf("direct", "early", "surface", "late")) {
                add(Case("$slug-stem-$stem", "${voice.name}: $stem pickup", "stems", required,
                    "The $stem component from the full room rendering, with its actual mix level retained. Quiet branches are not boosted.",
                    "Which part of the answer belongs to this pickup?", stem = stem, reference = full,
                    compareGroup = "$slug-room-stems", variant = stem, tags = listOf("room-stem", stem)))
            }
            for (position in listOf(0f, .5f, 1f)) {
                add(Case("$slug-placement-${(position * 100).toInt()}", "${voice.name}: placement $position", "placement",
                    required.copy(macros = required.macros + ("PLACEMENT" to position)),
                    "Same quartet and exact mixed boundary targets. Only the saved spatial path position changes; gain stays at the full reference.",
                    "Does moving the instruments change the material answer, even in mono?", reference = full,
                    compareGroup = "$slug-placement", variant = position.toString(), tags = listOf("placement", position.toString())))
            }
            for (material in listOf(.75f, 1f)) {
                val label = if (material == .75f) "glass" else "submerged glass"
                val uniform = required.copy(name = "${voice.name} $label", macros = required.macros +
                    mapOf("MATERIAL" to material, "DIVIDE" to 0f))
                add(Case("$slug-${if (material == .75f) "glass" else "submerged"}", "${voice.name}: $label", "materials", uniform,
                    "A uniform $label boundary family, with fixed geometry, strike and the same full-room reference gain. Glass and submerged families have comparable designed decay times.",
                    "Does the submerged boundary add a wavering secondary return without hiding the air-like instruments?", reference = full,
                    compareGroup = "$slug-glass", variant = label, tags = listOf("glass-comparison", label)))
            }
            for (control in controls) for (step in steps) {
                add(Case("$slug-sweep-${control.lowercase(Locale.ROOT)}-${(step * 100).toInt()}", "${voice.name}: $control $step", "controls",
                    defaults.copy(name = "${voice.name} $control $step", macros = defaults.macros + (control to step)),
                    "Five-step $control sweep. Other macros, note, event velocity, seed and saved room stay at the voice default. Full mixes are matched by the common audition rule.",
                    "Is the low-to-middle step useful, and does the high end retain the quartet?",
                    compareGroup = "$slug-${control.lowercase(Locale.ROOT)}", variant = step.toString(), tags = listOf("control", control, step.toString()),
                    sweep = control, step = step))
            }
            for (midi in listOf(48, 60, 72)) for (velocity in listOf(.3f, .65f, 1f)) {
                add(Case("$slug-midi-$midi-v-${(velocity * 100).toInt()}", "${voice.name}: MIDI $midi, velocity $velocity", "registers",
                    defaults.copy(name = "${voice.name} note $midi effort $velocity", midi = midi, velocity = velocity),
                    "Native note and event-velocity rendering. STRIKE stays at the saved macro value, so touch changes the excitation without rewriting the sound.",
                    "Does the quartet stay pitched and recognizable across low, middle and high registers?",
                    compareGroup = "$slug-register-$midi", variant = velocity.toString(), tags = listOf("register", "midi-$midi", "velocity-$velocity")))
            }
            add(Case("$slug-all-high", "${voice.name}: all controls high", "extremes",
                defaults.copy(name = "${voice.name} all high", macros = defaults.macros + controls.associateWith { 1f }, velocity = 1f),
                "The five timbral controls and event velocity at their maximum. HOLD stays zero. Raw energy and passive-network checks remain active.",
                "Does the demanding corner remain musical and bounded?", tags = listOf("extreme")))
        }
    }

    private fun matchingGain(raw: FloatArray, matched: FloatArray): Float =
        (TangleMeasure.rms(matched) / TangleMeasure.rms(raw).coerceAtLeast(1e-30)).toFloat()
    private fun scaled(samples: FloatArray, gain: Float) = FloatArray(samples.size) { samples[it] * gain }
    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        var energy = 0.0
        val count = minOf(a.size, b.size)
        for (i in 0 until count) { val delta = a[i].toDouble() - b[i]; energy += delta * delta }
        return sqrt(energy / count.coerceAtLeast(1)) / TangleMeasure.rms(a).coerceAtLeast(1e-30)
    }
    private fun provenance(repo: File, root: File): Map<String, Any?> {
        val paths = listOf("synth/src/main/kotlin/com/snipsnap/synth/Chamber.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ChamberConfig.kt", "synth/src/main/kotlin/com/snipsnap/synth/ChamberRoom.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ChamberPatch.kt", "synth/src/main/kotlin/com/snipsnap/synth/ChamberPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt", "synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt", "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt", "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt", "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt", "synth/src/test/kotlin/com/snipsnap/synth/FineTuning.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt", "synth/src/test/kotlin/com/snipsnap/synth/TangleMeasure.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ChamberAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ChamberDiagnosticReplayTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ChamberTimpaniTest.kt",
            "synth/src/test/resources/chamber-model-1/hybrid-default.json",
            "synth/src/test/resources/chamber-model-1/aquarium-default.json",
            "synth/src/test/resources/chamber-model-2/hybrid-timpani-r2-c3-full.json",
            "synth/src/test/resources/chamber-model-2/aquarium-timpani-r2-c3-full.json", "synth/build.gradle.kts",
            "synth/src/test/resources/chamber-audition/index.html", "synth/src/test/resources/chamber-audition/app.js",
            "synth/src/test/resources/chamber-audition/styles.css")
        val files = paths.map { path ->
            val file = File(repo, path)
            require(file.isFile) { "missing provenance source $path" }
            File(root, "source/$path").also { it.parentFile.mkdirs(); file.copyTo(it, overwrite = true) }
            linkedMapOf("path" to path, "sha256" to sha(file))
        }
        val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(repo).start()
        val revision = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && revision.isNotEmpty()) { "could not read source revision" }
        val statusProcess = ProcessBuilder("git", "status", "--porcelain=v1", "--untracked-files=normal").directory(repo).start()
        val changedPaths = statusProcess.inputStream.bufferedReader().readLines()
        check(statusProcess.waitFor() == 0) { "could not inspect source worktree" }
        return linkedMapOf("revision" to revision, "worktreeSourceHashesAuthoritative" to true,
            "dirtyWorktree" to changedPaths.isNotEmpty(), "worktreeChanges" to changedPaths,
            "engine" to "CHAMBER", "model" to Chamber.MODEL_VERSION,
            "javaRuntime" to System.getProperty("java.runtime.version"), "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
            "oversample" to Dsp.OVERSAMPLE, "sampleRate" to Dsp.RATE, "files" to files)
    }
    private fun starterZip(root: File, clips: List<Map<String, Any?>>, manifest: Map<String, Any?>) {
        val paths = linkedSetOf("index.html", "app.js", "styles.css", "checks.json", "source-provenance.json", "README.txt")
        for (clip in clips) {
            for (key in listOf("raw", "matched", "recipe")) paths += clip.getValue(key) as String
            @Suppress("UNCHECKED_CAST")
            val metrics = clip.getValue("metrics") as Map<String, Any?>
            paths += metrics.getValue("diagnostics") as String
        }
        File(root, "source").walkTopDown().filter { it.isFile }.forEach { paths += it.relativeTo(root).invariantSeparatorsPath }
        ZipOutputStream(File(root, "starter-pack.zip").outputStream().buffered()).use { zip ->
            @Suppress("UNCHECKED_CAST")
            val fullChecks = manifest.getValue("checks") as Map<String, Any?>
            val subsetChecks = fullChecks + mapOf("rendered" to clips.size, "requested" to clips.size,
                "fullRunRendered" to fullChecks["rendered"], "fullRunRequested" to fullChecks["requested"],
                "fullRunReport" to "checks.json")
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(Json.write(json(manifest + mapOf("clips" to clips,
                "checks" to subsetChecks,
                "summary" to if (manifest["auditionRevision"] in setOf("timpani", "timpani-boost")) manifest["summary"] else
                    "Starter selection: both first-listen voices and their causal room comparisons. The hosted page contains the complete control and register grids.",
                "previousAudition" to null,
                "downloads" to mapOf("manifest" to "manifest.json", "checks" to "checks.json", "provenance" to "source-provenance.json")))).toByteArray())
            zip.closeEntry()
            for (path in paths) {
                zip.putNextEntry(ZipEntry(path)); File(root, path).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    internal fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)))
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> if (value.toDouble().isFinite()) JsonValue.Num(value.toDouble()) else JsonValue.Null
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(linkedMapOf()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported JSON ${value.javaClass.simpleName}")
    }
}

/** Diagnostic-only replay is explicit: options and stem selection are part of its saved recipe. */
object ChamberDiagnosticReplay {
    internal fun select(probe: Chamber.Probe, stem: String): FloatArray = when (stem) {
        "mix" -> probe.rawMix
        "direct" -> probe.direct
        "early" -> probe.early
        "surface" -> probe.surface
        "late" -> probe.late
        "instrument-state" -> FloatArray(probe.rawMix.size) { frame ->
            (0..3).sumOf { i -> probe.instrumentStems[i][frame].toDouble() * probe.geometry.directPaths[i].gain * .46 }.toFloat()
        }
        else -> error("unknown CHAMBER diagnostic stem $stem")
    }

    internal fun optionsJson(options: Chamber.ProbeOptions): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "normalize" to JsonValue.Bool(options.normalize), "roomEnabled" to JsonValue.Bool(options.roomEnabled),
        "returnsEnabled" to JsonValue.Bool(options.returnsEnabled),
        "isolatedInstrument" to (options.isolatedInstrument?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
        "averageSurfaces" to JsonValue.Bool(options.averageSurfaces),
        "submergedModulationEnabled" to JsonValue.Bool(options.submergedModulationEnabled),
        "seconds" to JsonValue.Num(options.seconds.toDouble()), "recordDiagnostics" to JsonValue.Bool(options.recordDiagnostics),
        "recordStems" to JsonValue.Bool(options.recordStems),
        "oversample" to JsonValue.Num(options.oversample.toDouble()),
    ))

    internal fun recipe(patch: ChamberPatch, options: Chamber.ProbeOptions, stem: String,
        matchedGain: Float, rawGain: Float): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "diagnosticRecipe" to JsonValue.Num(1.0), "engine" to JsonValue.Str(ChamberPatch.ENGINE),
        "patch" to patch.toJsonValue(), "options" to optionsJson(options), "stem" to JsonValue.Str(stem),
        "matchedGain" to JsonValue.Num(matchedGain.toDouble()), "rawGain" to JsonValue.Num(rawGain.toDouble()),
        "sampleRate" to JsonValue.Num(Dsp.RATE.toDouble()), "format" to JsonValue.Str("PCM_16"),
        "matchedTailTreatment" to JsonValue.Str("Dsp.fadeTail"),
    ))

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 2..3) { "diagnostic replay wants <recipe.json> <output.wav> [raw|matched]" }
        val mode = args.getOrNull(2) ?: "matched"
        require(mode in setOf("raw", "matched")) { "replay mode must be raw or matched" }
        val obj = Json.parse(File(args[0]).readText()).obj()
        require(obj.getValue("diagnosticRecipe").int() == 1) { "unsupported CHAMBER diagnostic recipe" }
        require(obj.getValue("engine").str() == ChamberPatch.ENGINE)
        val patch = ChamberPatch.fromJsonValue(obj.getValue("patch"))
        val o = obj.getValue("options").obj()
        val options = Chamber.ProbeOptions(
            normalize = o.getValue("normalize").bool(), roomEnabled = o.getValue("roomEnabled").bool(),
            returnsEnabled = o.getValue("returnsEnabled").bool(),
            isolatedInstrument = o["isolatedInstrument"]?.takeUnless { it is JsonValue.Null }?.int(),
            averageSurfaces = o.getValue("averageSurfaces").bool(),
            submergedModulationEnabled = o.getValue("submergedModulationEnabled").bool(),
            seconds = o.getValue("seconds").num().toFloat(), recordDiagnostics = o.getValue("recordDiagnostics").bool(),
            recordStems = o.getValue("recordStems").bool(),
            oversample = o.getValue("oversample").int(),
        )
        val probe = Chamber.renderProbe(patch.voice, patch.macros, patch.midi, patch.velocity,
            patch.configuration, patch.seed, patch.model, options)
        val samples = select(probe, obj.getValue("stem").str())
        val gain = obj.getValue(if (mode == "raw") "rawGain" else "matchedGain").num().toFloat()
        val output = FloatArray(samples.size) { samples[it] * gain }
        if (mode == "matched") {
            require(obj.getValue("matchedTailTreatment").str() == "Dsp.fadeTail")
            Dsp.fadeTail(output)
        }
        WavWriter.write(File(args[1]), Snip(output, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
        println("Replayed CHAMBER $mode diagnostic to ${args[1]}")
    }
}

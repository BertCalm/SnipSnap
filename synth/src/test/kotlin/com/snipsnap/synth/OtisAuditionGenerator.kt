package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** A compact finite-note first listen, isolated from production patches, presets and export APIs. */
object OtisAuditionGenerator {
    // These three published studies remain reproducible independently of the newer workshop.
    private const val STUDY_MODEL = 3
    private data class Case(
        val id: String, val title: String, val family: String, val group: String, val description: String,
        val provenance: JsonValue.Obj, val request: OtisPrototypeRequest? = null,
        val register: String? = null, val presetId: String? = null, val presetName: String? = null,
        val comparison: OtisPrototypeRequest? = null, val render: () -> Snip,
    )
    private data class Baseline(val directory: File, val manifest: Map<String, JsonValue>,
        val clips: Map<String, Map<String, JsonValue>>, val manifestSha256: String)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--baseline=") } <= 1 &&
            args.all { !it.startsWith("--") || it in listOf("--embed-audio", "--range", "--presets") || it.startsWith("--baseline=") }) {
            "Usage: OtisAuditionGenerator [output-directory] [--embed-audio] [--range | --presets | --baseline=previous-audition-directory]"
        }
        val rangeStudy = "--range" in args
        val presetStudy = "--presets" in args
        require(!(rangeStudy && presetStudy)) { "choose one Otis study" }
        require(!(rangeStudy || presetStudy) || args.none { it.startsWith("--baseline=") }) { "range and preset studies use current model sources, without a previous-model comparison" }
        val repository = repositoryRoot()
        val defaultDirectory = when { presetStudy -> "../testkit/otis-preset-audition"; rangeStudy -> "../testkit/otis-range-audition"; else -> "../testkit/otis-audition" }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: defaultDirectory).canonicalFile
        val baselineDirectory = if (rangeStudy || presetStudy) null else args.firstOrNull { it.startsWith("--baseline=") }?.substringAfter('=')?.let {
            require(it.isNotBlank()) { "--baseline needs a directory" }; File(it).canonicalFile
        } ?: (STUDY_MODEL - 1 downTo 1).asSequence()
            .map { File(repository, "testkit/otis-audition-model$it").canonicalFile }.firstOrNull { it.isDirectory }
        if (baselineDirectory != null) require(!root.toPath().startsWith(baselineDirectory.toPath()) && !baselineDirectory.toPath().startsWith(root.toPath())) {
            "output and baseline directories must be separate; the original audition is immutable"
        }
        val cases = if (presetStudy) OtisPresetStudy.cases().map { case ->
            Case(case.id, case.title, case.family, case.group, case.description,
                OtisPrototypeRecipe.toJsonValue(case.request), case.request,
                presetId = case.presetId, presetName = case.presetName, comparison = case.comparison) { case.request.render() }
        } else if (rangeStudy) OtisRangeStudy.cases().map { case ->
            Case(case.id, case.title, case.family, case.group, case.description,
                OtisPrototypeRecipe.toJsonValue(case.request), case.request, case.register) { case.request.render() }
        } else cases()
        check(cases.map { it.id }.distinct().size == cases.size)
        val baseline = baselineDirectory?.let { loadBaseline(it, cases) }
        val audio = File(root, "audio").also { it.mkdirs() }
        val recipes = File(root, "recipes").also { it.mkdirs() }
        val revision = git(repository, "rev-parse", "HEAD").trim()
        val dirty = git(repository, "status", "--porcelain", "--untracked-files=normal").isNotBlank()
        val rendered = cases.filter { it.request != null || baseline == null }.associate { case ->
            println("OTIS ${case.id}")
            case.id to case.render().also { snip ->
                check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty()) { "${case.id}: wrong output format" }
                check(snip.samples.all { it.isFinite() }) { "${case.id}: nonfinite audio" }
            }
        }
        fun comparisonId(case: Case) = "${case.family.lowercase()}_${case.group.lowercase()}"
        for ((id, group) in cases.filter { it.comparison != null }.groupBy { comparisonId(it) }) {
            check(group.map { it.comparison }.distinct().size == 1) { "default comparison $id has inconsistent requests" }
        }
        val comparisonRequests = cases.filter { it.comparison != null }.associate { comparisonId(it) to it.comparison!! }
        val comparisonRendered = comparisonRequests.mapValues { it.value.render() }
        // Every current source and default reference shares one disclosed raw PCM headroom gain.
        val maximum = (rendered.values + comparisonRendered.values).maxOf { peak(it) }
        val baselineRawGain = baseline?.manifest?.getValue("rawPcmGain")?.num()?.toFloat() ?: 1f
        require(baselineRawGain.isFinite() && baselineRawGain > 0f && baselineRawGain <= 1f) { "baseline PCM gain must be in 0..1" }
        val rawGain = minOf(baselineRawGain, if (maximum > .98f) .98f / maximum else 1f)
        val defaultFields = comparisonRendered.mapValues { (id, source) ->
            val request = comparisonRequests.getValue(id)
            val raw = Snip(FloatArray(source.samples.size) { source.samples[it] * rawGain }, source.channels, source.sampleRate)
            val matched = AuditionLevel.level(source)
            val prefix = "defaults/audio/$id.model${request.model}"
            val rawFile = WavWriter.write(File(root, "$prefix.raw.wav").also { it.parentFile.mkdirs() }, raw)
            val matchedFile = WavWriter.write(File(root, "$prefix.matched.wav"), matched)
            val recipePath = "defaults/recipes/$id.model${request.model}.json"
            File(root, recipePath).also { it.parentFile.mkdirs() }.writeText(OtisPrototypeRecipe.toJsonText(request) + "\n")
            obj("originalRaw" to "$prefix.raw.wav", "originalMatched" to "$prefix.matched.wav",
                "originalRawSha256" to sha256(rawFile), "originalMatchedSha256" to sha256(matchedFile),
                "originalSourceMeasurements" to measurements(source), "originalRawMeasurements" to measurements(raw),
                "originalMatchedMeasurements" to measurements(matched), "originalProvenance" to OtisPrototypeRecipe.toJsonValue(request),
                "originalRecipe" to recipePath, "originalModel" to request.model,
                "originalRevision" to revision, "originalRawPcmGain" to rawGain)
        }
        val clips = cases.map { case ->
            val original = baseline?.clips?.getValue(case.id)
            if (case.request == null && baseline != null && original != null) {
                // Existing engines are references, not new revisions; both files remain byte-identical.
                val fields = LinkedHashMap(original)
                for (key in listOf("raw", "matched")) {
                    val sourceFile = baselineFile(baseline.directory, original.getValue(key).str())
                    val target = File(audio, "${case.id}.$key.wav")
                    sourceFile.copyTo(target, overwrite = true)
                    fields[key] = JsonValue.Str("audio/${target.name}")
                }
                fields["hasOriginal"] = JsonValue.Bool(false)
                fields["rawPcmGain"] = baseline.manifest.getValue("rawPcmGain")
                fields["sourceRevision"] = baseline.manifest.getValue("sourceRevision")
                fields["changes"] = JsonValue.Str("Unchanged reference copied from the previous audition; the revision control does not change this clip.")
                return@map JsonValue.Obj(fields)
            }
            val source = rendered.getValue(case.id)
            val raw = Snip(FloatArray(source.samples.size) { source.samples[it] * rawGain }, source.channels, source.sampleRate)
            val matched = AuditionLevel.level(source)
            val stem = case.request?.let { "${case.id}.model${it.model}" } ?: case.id
            val rawFile = WavWriter.write(File(audio, "$stem.raw.wav"), raw)
            val matchedFile = WavWriter.write(File(audio, "$stem.matched.wav"), matched)
            val recipe = case.request?.let { request ->
                // Re-decoding also validates every request before it enters the listening manifest.
                val text = OtisPrototypeRecipe.toJsonText(request)
                check(OtisPrototypeRecipe.fromJsonText(text) == request)
                File(recipes, "${case.id}.json").writeText(text + "\n")
                "recipes/${case.id}.json"
            }
            val fields = LinkedHashMap(obj(
                "id" to case.id, "title" to case.title, "family" to case.family, "group" to case.group,
                "register" to case.register,
                "presetId" to case.presetId, "presetName" to case.presetName,
                "description" to case.description, "raw" to "audio/${rawFile.name}", "matched" to "audio/${matchedFile.name}",
                "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                "sourceMeasurements" to measurements(source), "rawMeasurements" to measurements(raw),
                "matchedMeasurements" to measurements(matched), "provenance" to case.provenance,
                "recipe" to recipe, "model" to case.request?.model, "sourceRevision" to revision,
                "hasOriginal" to (case.comparison != null || baseline != null && case.request != null), "rawPcmGain" to rawGain,
                "changes" to if (presetStudy) case.description
                    else if (rangeStudy) "Current model 3 source and controls are fixed; this study changes only notes, playing strength and note overlap."
                    else if (case.request != null) changes(case.family, baseline?.manifest?.getValue("model")?.int()) else "Unchanged existing-engine reference; no previous/revised comparison.",
            ).entries)
            if (case.comparison != null) fields.putAll(defaultFields.getValue(comparisonId(case)).entries)
            if (baseline != null && original != null && case.request != null) {
                val originalModel = original.getValue("provenance").obj().getValue("model").int()
                val originalAudio = listOf("raw", "matched").associateWith { key ->
                    val destination = File(root, "original/audio/${case.id}.model$originalModel.$key.wav").also { it.parentFile.mkdirs() }
                    baselineFile(baseline.directory, original.getValue(key).str()).copyTo(destination, overwrite = true)
                    "original/audio/${destination.name}"
                }
                val originalRecipePath = original.getValue("recipe").str()
                val originalRecipe = File(root, "original/recipes/${case.id}.model$originalModel.json").also { it.parentFile.mkdirs() }
                baselineFile(baseline.directory, originalRecipePath).copyTo(originalRecipe, overwrite = true)
                fields.putAll(obj(
                "originalRaw" to originalAudio.getValue("raw"), "originalMatched" to originalAudio.getValue("matched"),
                "originalRawSha256" to original.getValue("rawSha256"), "originalMatchedSha256" to original.getValue("matchedSha256"),
                "originalSourceMeasurements" to original.getValue("sourceMeasurements"),
                "originalRawMeasurements" to original.getValue("rawMeasurements"),
                "originalMatchedMeasurements" to original.getValue("matchedMeasurements"),
                "originalProvenance" to original.getValue("provenance"), "originalRecipe" to "original/recipes/${originalRecipe.name}",
                "originalModel" to originalModel, "originalRevision" to baseline.manifest.getValue("sourceRevision"),
                "originalRawPcmGain" to baseline.manifest.getValue("rawPcmGain"),
                ).entries)
            }
            JsonValue.Obj(fields)
        }
        val comparisonCount = if (presetStudy) cases.count { it.comparison != null }
            else if (baseline != null) cases.count { it.request != null } else 0
        val baselineInfo = baseline?.let {
            val destination = File(root, "original/baselineManifest.json").also { it.parentFile.mkdirs() }
            File(it.directory, "manifest.json").copyTo(destination, overwrite = true)
            obj("model" to it.manifest.getValue("model"), "manifest" to "original/baselineManifest.json", "manifestSha256" to it.manifestSha256,
                "sourceRevision" to it.manifest.getValue("sourceRevision"),
                "sourceDirty" to it.manifest.getValue("sourceDirty"),
                "sourceHashes" to it.manifest.getValue("sourceHashes"),
                "rawPcmGain" to it.manifest.getValue("rawPcmGain"))
        }
        val manifest = obj(
            "format" to "snipsnap-otis-audition", "model" to STUDY_MODEL,
            "study" to when { presetStudy -> "presets"; rangeStudy -> "range"; else -> "character" },
            "presetCount" to if (presetStudy) OtisPresetStudy.presets().size else 0,
            "caseCount" to cases.size, "comparisonCount" to comparisonCount,
            "wavCount" to if (presetStudy) (cases.size + comparisonRendered.size) * 2 else (cases.size * 2 + comparisonCount * 2),
            "sampleRate" to Dsp.RATE, "channels" to 1, "wavBits" to 24,
            "sourceRevision" to revision, "sourceDirty" to dirty,
            "sourceHashes" to sourceHashes(), "rawPcmGain" to rawGain,
            "rawHeadroomPolicy" to if (baseline == null) "One shared PCM headroom gain across this audition."
                else if (rawGain == baselineRawGain) "Revised raw audio uses exactly the previous pack's PCM gain, preserving a direct comparison of source levels."
                else "Revised sources require more PCM headroom than the previous pack. Previous and revised gains are disclosed separately; compare tone in matched playback.",
            "baseline" to baselineInfo,
            "improvements" to OtisPrototypeVoice.entries.associate { it.name to if (presetStudy) "Three authored settings using existing model 3 controls; current defaults and source renderers are unchanged."
                else if (rangeStudy) "Unchanged model 3 source; musical range study only."
                else changes(it.name, baseline?.manifest?.getValue("model")?.int()) },
            "matching" to "AuditionLevel: RMS of loudest 200 ms window to 0.03, with 0.99 peak guard; no EQ or FX.",
            "limitations" to listOf(
                "Finite generated audio for a listening decision; prototype presets are not registered in the production engine.",
                "No sustain-loop export, phone UI, live MIDI or aftertouch in this prototype.",
                if (presetStudy) "Twelve authored settings compare against the same model 3 default with identical notes, timing, velocity and seed. Raw files share one disclosed gain; matched playback compares character."
                else if (rangeStudy) "The four model 3 sources are unchanged. C3/C4/C5 requests vary only the notes and playing gestures. Raw audio shares one disclosed headroom gain across the complete study."
                else if (baseline != null) "Previous WAVs are byte-identical to the selected model ${baseline.manifest.getValue("model").int()} audition. Revised prototypes use one disclosed shared PCM headroom gain; use matched playback to compare character."
                else "No previous pack was supplied. This page contains revised clips only, with one disclosed shared PCM headroom gain.",
                if (presetStudy) "Preset recipes pin model 3 and reuse its existing source controls. No source calibration or current default is changed."
                else "Comparators retain their native source mechanisms and stated level/gesture policies.",
                "Numerical checks and reproducibility are not a listening approval.",
            ),
            "clips" to clips,
        )
        File(root, "manifest.json").writeText(Json.write(manifest) + "\n")
        if (presetStudy) {
            val bank = obj("format" to "snipsnap-otis-prototype-preset-bank", "model" to OtisPrototype.MODEL_VERSION,
                "presets" to cases.filter { it.group == "Note" }.map {
                    obj("id" to it.presetId, "name" to it.presetName, "family" to it.family,
                        "description" to it.description, "recipe" to it.provenance)
                })
            File(root, "presets.json").writeText(Json.write(bank) + "\n")
        }
        val template = requireNotNull(javaClass.getResource("/audition/otis-audition.html")) { "missing Otis audition page" }.readText()
        check(template.split("__OTIS_MANIFEST__").size == 2) { "Otis audition template needs exactly one manifest placeholder" }
        File(root, "index.html").writeText(page(template, manifest))
        if ("--embed-audio" in args) {
            val embedded = JsonValue.Obj(manifest.entries + ("clips" to JsonValue.Arr(clips.map { clip ->
                JsonValue.Obj(clip.entries + listOf("raw", "matched", "originalRaw", "originalMatched").filter { it in clip.entries }.associateWith { key ->
                    val file = File(root, clip.entries.getValue(key).str())
                    JsonValue.Str("data:audio/wav;base64," + Base64.getEncoder().encodeToString(file.readBytes()))
                })
            })))
            File(root, "standalone.html").writeText(page(template, embedded))
        }
        println("Wrote ${cases.size} cases, $comparisonCount previous/revised comparisons, ${manifest.entries.getValue("wavCount").int()} WAVs and index.html to ${root.canonicalPath}; revised raw PCM gain=$rawGain")
    }

    private fun loadBaseline(directory: File, cases: List<Case>): Baseline {
        val file = File(directory, "manifest.json")
        val manifest = Json.parse(file.readText()).obj()
        val model = manifest.getValue("model").int()
        require(manifest.getValue("format").str() == "snipsnap-otis-audition" && model in 1 until OtisPrototype.MODEL_VERSION) {
            "baseline must be a frozen Otis audition from an earlier model than ${OtisPrototype.MODEL_VERSION}"
        }
        val rows = manifest.getValue("clips").arr().map { it.obj() }
        val clips = rows.associateBy { it.getValue("id").str() }
        require(clips.size == rows.size && clips.keys == cases.map { it.id }.toSet()) { "baseline case IDs do not match this audition" }
        for (case in cases) {
            val clip = clips.getValue(case.id)
            for (key in listOf("raw", "matched")) {
                val audio = baselineFile(directory, clip.getValue(key).str())
                require(sha256(audio) == clip.getValue("${key}Sha256").str()) { "baseline ${case.id} $key hash mismatch" }
            }
            if (case.request != null) {
                val recipe = Json.parse(baselineFile(directory, clip.getValue("recipe").str()).readText())
                require(recipe == clip.getValue("provenance")) { "baseline ${case.id} recipe differs from provenance" }
                val original = OtisPrototypeRecipe.fromJsonValue(recipe)
                require(original.model == model && original.voice == case.request.voice && original.notes == case.request.notes) {
                    "baseline ${case.id} must match its manifest model, family and note gesture"
                }
            }
        }
        return Baseline(directory, manifest, clips, sha256(file))
    }
    private fun baselineFile(directory: File, path: String): File {
        val file = File(directory, path).canonicalFile
        require(!File(path).isAbsolute && file.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile) {
            "baseline asset must be an existing relative file: $path"
        }
        return file
    }
    private fun changes(family: String, baselineModel: Int?): String = when (family) {
        "ORGAN" -> if (baselineModel == 1) "Model 2's fuller drawbars, contact clack and preamp bite are retained. This revision makes no further organ changes."
            else "Unchanged in this revision: fuller drawbars, contact clack, harmonic percussion and the optional rotary cabinet."
        "STEAM" -> "Fuller sustained pipe body, with pressure feeding stronger coherent harmonics through the fifth. Fundamental level, wobble and opening chuff are unchanged."
        "HARP" -> if (baselineModel == 1) "Model 2's deeper blues-harmonica entrance bend, reed harmonics and breath rasp are retained. This revision makes no further harmonica changes."
            else "Unchanged in this revision: a blues-harmonica single-reed sketch, with an entrance bend, breath rasp and delayed vibrato."
        "SQUEEZE" -> if (baselineModel == 1) "Model 2's wider beating between unequal reeds and firmer bellows gesture are retained. This revision makes no further squeeze changes."
            else "Unchanged in this revision: unequal reed ranks, musette beating and a finite bellows gesture."
        else -> error("unknown prototype family $family")
    }

    private fun cases(): List<Case> {
        val cases = mutableListOf<Case>()
        fun add(id: String, title: String, group: String, description: String, request: OtisPrototypeRequest) {
            cases += Case(id, title, request.voice.name, group, description, OtisPrototypeRecipe.toJsonValue(request), request) { request.render() }
        }
        for (voice in OtisPrototypeVoice.entries) {
            val family = voice.name.lowercase()
            val label = when (voice) {
                OtisPrototypeVoice.ORGAN -> "Organ"
                OtisPrototypeVoice.STEAM -> "Steam"
                OtisPrototypeVoice.HARP -> "Blues harmonica"
                OtisPrototypeVoice.SQUEEZE -> "Squeeze"
            }
            val request = OtisPrototypeRequest(voice, model = STUDY_MODEL)
            val identity = if (voice == OtisPrototypeVoice.HARP) " Breath-driven single-reed harmonica sketch." else ""
            add("${family}_default", "$label · default", "Defaults", "C4 / MIDI 60, 1.5-second gate, velocity .80; dry source.$identity", request)
            for ((velocity, strength) in listOf(.35f to "soft", .9f to "hard")) {
                add("${family}_$strength", "$label · $strength", "Velocity", "Same C4 and gate; velocity $velocity. Compare matched to hear changes beyond level.$identity",
                    request.copy(notes = listOf(OtisPrototypeNote(velocity = velocity))))
            }
            val phrase = listOf(60, 63, 67, 65).mapIndexed { index, midi ->
                OtisPrototypeNote(midi, startSeconds = index * .52, gateSeconds = .40, velocity = .8f)
            }
            add("${family}_phrase", "$label · C–E♭–G–F", "Phrase", "Identical notes, .52-second spacing and .40-second gates in every family. One continuous request.$identity", request.copy(notes = phrase))
        }
        val held = OtisPrototypeRequest(OtisPrototypeVoice.ORGAN, listOf(OtisPrototypeNote(gateSeconds = 4.0)), model = STUDY_MODEL)
        add("organ_hold_dry", "Organ · dry 4-second hold", "Rotary", "Same registration and 4-second key gate as the rotor example; rotary bypassed.", held)
        add("organ_hold_rotary", "Organ · slow → fast", "Rotary", "One cabinet on the voice mix. Slow at onset; accelerate toward fast after .75 seconds, with finite inertia.",
            held.copy(organ = held.organ.copy(rotary = true, rotaryStart = OtisOrganRotary.SLOW,
                rotaryTarget = OtisOrganRotary.FAST, rotaryChangeSeconds = .75)))

        val tonewheelMacros = Tonewheel.defaults(TonewheelVoice.SOUL) + ("TUNE" to (15f / Tonewheel.TUNE_SEMITONES))
        val tonewheelPatch = TonewheelPatch("Current SOUL comparator", TonewheelVoice.SOUL, tonewheelMacros)
        cases += Case("reference_tonewheel", "Current TONEWHEEL · SOUL", "ORGAN", "Comparators",
            "Existing engine at C4 and the same 1.5-second gate. Native scanner and rotor remain on; the public render applies its own melodic level. No velocity argument on this renderer.",
            obj("kind" to "shipped-renderer", "patch" to tonewheelPatch.toJsonValue(), "gateSeconds" to 1.5,
                "motion" to true, "levelPolicy" to "Tonewheel.render applies native melodic loudness before audition matching")) {
            Tonewheel.render(TonewheelVoice.SOUL, tonewheelMacros, gateSeconds = 1.5f)
        }
        val aerostatMacros = Aerostat.defaults(AerostatVoice.FLOAT) + mapOf("TUNE" to .5f, "LIFT" to 0f, "HOLD" to 0f)
        cases += Case("reference_aerostat", "Current AEROSTAT · FLOAT", "STEAM", "Comparators",
            "C4 / MIDI 60, velocity .80, LIFT 0. Its own finite pressure/catch gesture and duration remain; this is not the prototype's 1.5-second key gate. Native loudness normalization is disabled.",
            obj("kind" to "shipped-renderer", "engine" to "AEROSTAT", "voice" to "FLOAT", "macros" to aerostatMacros,
                "midi" to 60, "velocity" to .8f, "normalize" to false, "tap" to "FULL", "carry" to null,
                "controlHz" to Aerostat.CONTROL_RATE, "forcedPressure" to null)) {
            Aerostat.render(AerostatVoice.FLOAT, aerostatMacros, velocity = .8f, normalize = false)
        }
        val reed = EmbouchurePatch("Current REED comparator", EmbouchureVoice.REED,
            Embouchure.defaults(EmbouchureVoice.REED) + mapOf("TUNE" to 0f, "HOLD" to 0f), midi = 60, velocity = .8f)
        cases += Case("reference_embouchure", "Current EMBOUCHURE · REED", "HARP", "Comparators",
            "C4 / MIDI 60, velocity .80. Existing REED driver and ${reed.gesture.name} gesture retain their own duration and native melodic level. A neighboring breath-driven instrument, without a harmonica claim.",
            obj("kind" to "shipped-renderer", "patch" to reed.toJsonValue(),
                "levelPolicy" to "Embouchure.render applies native melodic loudness and host velocity before audition matching")) { reed.render() }
        return cases
    }

    private fun peak(snip: Snip) = snip.samples.maxOf { abs(it) }
    private fun measurements(snip: Snip) = obj(
        "frames" to snip.frameCount, "seconds" to snip.frameCount.toDouble() / snip.sampleRate,
        "peak" to peak(snip), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "loudest200msRms" to Loudness.of(snip),
    )
    private fun page(template: String, manifest: JsonValue.Obj) =
        template.replace("__OTIS_MANIFEST__", Json.write(manifest).replace("<", "\\u003c"))
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun sourceHashes(): Map<String, String> {
        val repository = repositoryRoot()
        val paths = listOf(
            "synth/src/test/kotlin/com/snipsnap/synth/OtisPrototype.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisPrototypeLegacy.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisOrganPrototype.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisAirPrototype.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisOrganPrototypeV1.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisAirPrototypeV1.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisAirPrototypeV2.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisPrototypeRecipe.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisRangeStudy.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OtisPresetStudy.kt",
            "synth/src/test/resources/audition/otis-audition.html",
            "synth/src/main/kotlin/com/snipsnap/synth/Tonewheel.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Aerostat.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Embouchure.kt",
        )
        return paths.associateWith { sha256(File(repository, it)) }
    }
    private fun repositoryRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
        ?: error("Otis audition generator must run inside the source checkout")

    private fun git(repository: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + arguments).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("git ${arguments.first()} timed out while recording audition provenance")
        }
        check(process.exitValue() == 0) { "git ${arguments.first()} failed while recording audition provenance" }
        return process.inputStream.bufferedReader().readText()
    }
    private fun obj(vararg fields: Pair<String, Any?>) = JsonValue.Obj(fields.associateTo(linkedMapOf()) { it.first to value(it.second) })
    private fun value(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is List<*> -> JsonValue.Arr(value.map { value(it) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(linkedMapOf()) { it.key.toString() to value(it.value) })
        else -> error("unsupported manifest value ${value.javaClass}")
    }
}

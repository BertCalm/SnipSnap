package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Actual finite TIDAL renders, shared-gain comparisons and complete diagnostic replay. */
object TidalAuditionGenerator {
    private const val RATE = 44_100
    private const val SEED = 41L
    private const val MIDI = 48
    private const val CEILING = .95f
    private val controls = listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL")

    private data class Request(
        val id: String, val title: String, val section: String,
        val description: String, val question: String,
        val voice: TidalVoice = TidalVoice.STREAM,
        val macros: Map<String, Float> = Tidal.defaults(voice),
        val midi: Int = MIDI, val velocity: Float = .75f,
        val options: Tidal.Options = Tidal.Options(), val component: String = "mix",
        val comparisonGroup: String = id, val referenceId: String = id,
        val sweep: String? = null, val step: Float? = null,
    )

    private data class Rendered(
        val request: Request, val samples: FloatArray,
        val diagnostics: Map<String, Any?>, val trace: List<Map<String, Any?>>,
        val renderMilliseconds: Double, val nativeSamplesSha256: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            replay(args.drop(1))
            return
        }
        require(args.size <= 1) { "Supply an output directory, or --replay <diagnostic.json> <out.wav> [raw|matched]." }
        val repo = repositoryRoot()
        val root = File(args.firstOrNull() ?: "../testkit/tidal-audition").canonicalFile
        require(root != repo) { "Use a dedicated audition output directory." }
        root.mkdirs()
        File(root, "manifest.json").delete()
        val requests = requests()
        require(requests.map { it.id }.distinct().size == requests.size)
        val started = System.nanoTime()
        val rendered = mutableListOf<Rendered>()
        val baselines = mutableMapOf<TidalVoice, Tidal.Probe>()
        val replayChecks = mutableMapOf<String, Boolean>()
        for ((index, request) in requests.withIndex()) {
            val begin = System.nanoTime()
            val useBaseline = request.options == Tidal.Options() && request.macros == Tidal.defaults(request.voice) &&
                request.midi == MIDI && request.velocity == .75f
            val probe = if (useBaseline && request.voice in baselines) baselines.getValue(request.voice) else
                Tidal.probe(request.voice, request.macros, request.midi, request.velocity, SEED,
                    Tidal.MODEL_VERSION, request.options)
            if (request.id.endsWith("-default")) {
                baselines[request.voice] = probe
                val patch = patch(request)
                val restored = TidalPatch.fromJsonText(patch.toJsonText())
                check(restored == patch) { "${request.id}: patch round-trip changes the recipe." }
                val production = patch.render().samples
                val repeated = restored.render().samples
                check(sameSamples(production, repeated)) { "${request.id}: serialized production replay changes samples." }
                check(probe.rawMix.indices.all { i ->
                    abs(probe.rawMix[i] - (probe.core[i] + probe.tail[i])) < 2e-6f &&
                        abs(probe.rawMix[i] - (probe.populationA[i] + probe.populationB[i])) < 2e-6f
                }) { "${request.id}: the prelevel stems do not sum to the full mix." }
                replayChecks[request.id] = true
            }
            val selected = select(probe, request.component)
            check(selected.isNotEmpty() && selected.all { it.isFinite() }) { "${request.id}: invalid audio." }
            val d = probe.diagnostics
            val dynamics = probe.dynamics.diagnostics
            val diagnostics = linkedMapOf<String, Any?>(
                "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
                "tailRms" to d.tailRms, "coreRms" to d.coreRms, "pitchHz" to d.pitchHz,
                "durationSeconds" to d.durationSeconds, "maxCents" to d.maxCents,
                "preparationMillis" to d.preparationMillis, "synthesisMillis" to d.synthesisMillis,
                "sourceCutoffSeconds" to d.sourceCutoffSeconds, "maxSourceAllocation" to d.maxSourceAllocation,
                "maxModalAmplitude" to d.maxModalAmplitude, "maxFrequencyHz" to d.maxFrequencyHz,
                "targetPericenter" to dynamics.targetPericenter, "minimumSeparation" to dynamics.minimumSeparation,
                "encounterTimeSeconds" to dynamics.encounterTimeSeconds,
                "maxCenterEnergyRelativeError" to dynamics.maxCenterEnergyRelativeError,
                "maxCenterMomentum" to dynamics.maxCenterMomentum,
                "maxCenterPositionDrift" to dynamics.maxCenterPositionDrift,
                "maxParticleRadius" to dynamics.maxParticleRadius, "maxParticleHeight" to dynamics.maxParticleHeight,
                "maxRadialRatioDeviation" to dynamics.maxRadialRatioDeviation, "outlierCount" to dynamics.outlierCount,
                "maxExtension" to dynamics.maxExtension, "finalExtension" to dynamics.finalExtension,
                "particleCount" to dynamics.particleCount, "step" to dynamics.step,
                "finalParticleFingerprint" to dynamics.finalParticleFingerprint.toString(),
            )
            check(diagnostics.values.filterIsInstance<Number>().all { it.toDouble().isFinite() })
            check(dynamics.outlierCount == 0) { "${request.id}: particle left the supported preparation domain." }
            val stride = maxOf(1, probe.trace.size / 240)
            val trace = probe.trace.filterIndexed { i, _ -> i % stride == 0 || i == probe.trace.lastIndex }.map { frame ->
                linkedMapOf<String, Any?>("timeSeconds" to frame.timeSeconds,
                    "extension" to frame.extension, "extensionMean" to frame.extension.average(),
                    "coreAllocation" to frame.coreAllocation, "tailAllocation" to frame.tailAllocation,
                    "modeAmplitudes" to frame.modeAmplitudes)
            }
            rendered += Rendered(request, selected, diagnostics, trace, (System.nanoTime() - begin) / 1e6,
                samplesHash(probe.samples))
            println("TIDAL ${index + 1}/${requests.size}: ${request.id}")
        }
        baselines.clear()

        val byId = rendered.associateBy { it.request.id }
        val rawGain = minOf(1f, CEILING / rendered.maxOf { peak(it.samples) }.coerceAtLeast(1e-9f))
        val gains = rendered.groupBy { it.request.comparisonGroup }.mapValues { (_, family) ->
            val reference = byId.getValue(family.first().request.referenceId)
            require(family.all { it.request.referenceId == reference.request.id }) { "Inconsistent comparison reference." }
            val referenceLoudness = Loudness.of(snip(reference.samples))
            val targetGain = if (referenceLoudness <= 1e-6f) 1f else Dsp.MELODIC_LOUDNESS_TARGET / referenceLoudness
            val familyPeak = maxOf(peak(reference.samples), family.maxOf { peak(it.samples) })
            minOf(targetGain, CEILING / familyPeak.coerceAtLeast(1e-9f))
        }
        val clips = rendered.map { writeClip(root, it, gains.getValue(it.request.comparisonGroup), rawGain) }
        val source = sourceSnapshot(repo, root)
        for (file in listOf("index.html", "app.js", "styles.css")) {
            val resource = TidalAuditionGenerator::class.java.getResourceAsStream("/tidal-audition/$file")
                ?: error("Missing retained TIDAL audition frontend resource: $file")
            resource.use { input -> File(root, file).outputStream().use { input.copyTo(it) } }
        }
        val manifest = linkedMapOf<String, Any?>(
            "format" to "snipsnap-tidal-audition", "version" to 1,
            "title" to "Tidal / First audition", "engine" to "TIDAL", "model" to Tidal.MODEL_VERSION,
            "description" to "Two pitched populations gather, encounter and leave evolving harmonic strands.",
            "seed" to SEED.toString(), "sampleRate" to RATE, "channels" to 1, "bitDepth" to 16,
            "clipCount" to clips.size, "generatedAt" to Instant.now().toString(), "hold" to false,
            "scope" to listOf("Finite PAIR and STREAM voices", "2,048 particles reduced to 32 persistent cohorts",
                "Dry mono synthesis; no samples or finishing effects", "HOLD is zero; repeat replays the complete encounter",
                "Provisional recipes; factory roster and phone editor are not integrated", "Owner listening verdict remains open"),
            "gainPolicy" to linkedMapOf("rawGain" to rawGain,
                "raw" to "One common safe gain across every prelevel clip; original amplitude differences are retained.",
                "matched" to "Each comparison family shares its declared reference gain. The whole family sets peak headroom. Stems and no-tail controls are never normalized independently.",
                "targetLoudness" to Dsp.MELODIC_LOUDNESS_TARGET, "peakCeiling" to CEILING,
                "native" to "Audition exports use prelevel engine taps. A production TidalPatch uses the normal shared output leveler."),
            "sections" to sections(), "clips" to clips, "source" to source,
            "downloads" to listOf(
                mapOf("path" to "starter.zip", "title" to "Starter listening pack", "description" to "Ten matched WAVs and their complete recipes"),
                mapOf("path" to "source-recipes.zip", "title" to "Source and recipes", "description" to "Source snapshots, every production/diagnostic recipe and measurements"),
                mapOf("path" to "metrics.csv", "title" to "Measurements", "description" to "Per-clip audio and encounter diagnostics"),
            ),
            "verification" to linkedMapOf("baselineRecipeReplayVerified" to replayChecks,
                "finiteAudio" to true, "additiveStemsVerified" to true,
                "generationSeconds" to (System.nanoTime() - started) / 1e9,
                "scopeNote" to "These are real JVM renderer outputs. Numeric checks do not establish musical acceptance, phone latency or HOLD behavior.",
                "classifierNote" to "The generic classifier calls sounds longer than 1.5 seconds LOOP by duration. Every clip here is a finite one-shot. Onset classification measures a separate 750 ms window.",
                "pitchNote" to "Pitch is measured on a 250 ms pre-encounter window and can be ambiguous for isolated upper-population or tail stems.",
                "dynamicsNote" to "Dimensionless softened restricted trajectories; no astrophysical clock or observed-galaxy reconstruction is claimed."),
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        writeMetrics(root, clips)
        File(root, "README.txt").writeText(readme(rawGain))
        val starterIds = setOf("pair-default", "stream-default", "pair-population-a", "pair-population-b", "stream-core",
            "stream-tail", "encounter-close-diffuse", "encounter-distant-compact", "mechanism-no-tides", "mechanism-fixed-envelope")
        val starterFiles = clips.filter { it["id"] in starterIds }.flatMap { clip ->
            val paths = clip.getValue("paths") as Map<*, *>
            listOf(paths["matched"].toString(), paths["recipe"].toString(), paths["diagnosticRecipe"].toString())
        }.toSet() + setOf("README.txt")
        zip(root, "starter.zip") { it in starterFiles }
        zip(root, "source-recipes.zip") { path -> path.startsWith("source/") || path.startsWith("recipes/") ||
            path.startsWith("diagnostic-recipes/") || path.startsWith("diagnostics/") || path in setOf("manifest.json", "metrics.csv", "README.txt") }
        for (archive in listOf("starter.zip", "source-recipes.zip")) check(File(root, archive).length() < 25L * 1024 * 1024)
        println("TIDAL wrote ${clips.size} finite PCM16 raw/matched clips, recipes, traces and two download packs to $root")
    }

    private fun requests(): List<Request> = buildList {
        val stream = Tidal.defaults(TidalVoice.STREAM)
        fun addCase(id: String, title: String, section: String, description: String, question: String,
            voice: TidalVoice = TidalVoice.STREAM, macros: Map<String, Float> = Tidal.defaults(voice),
            midi: Int = MIDI, velocity: Float = .75f, options: Tidal.Options = Tidal.Options(), component: String = "mix",
            group: String = id, reference: String = id, sweep: String? = null, step: Float? = null) {
            add(Request(id, title, section, description, question, voice, macros, midi, velocity, options, component, group, reference, sweep, step))
        }
        for (voice in TidalVoice.entries) {
            val slug = voice.name.lowercase(Locale.ROOT)
            val baseline = "$slug-default"
            addCase(baseline, if (voice == TidalVoice.PAIR) "Pair / two bodies" else "Stream / exposed strands", "defaults",
                "${voice.name} defaults at C3, velocity .75, seed $SEED. The complete finite encounter and passive release.",
                "Can you hear two source identities and an evolving afterstructure?", voice = voice, group = slug, reference = baseline)
            for ((component, label) in listOf("population-a" to "Population A / rounded", "population-b" to "Population B / brighter",
                "core" to "Surviving cores", "tail" to "Extended streams")) {
                addCase("$slug-$component", "${voice.name.lowercase().replaceFirstChar { it.uppercase() }} / $label", "anatomy",
                    "The $component tap from the complete $slug performance. Original full-mix gain is retained; this stem may be quiet.",
                    "Does this component remain recognizable inside the complete gesture?", voice = voice,
                    component = component, group = slug, reference = baseline)
            }
        }
        val roles = mapOf("APPROACH" to "Initial relative encounter speed; the simulation clock stays common.",
            "PASSAGE" to "Target closest-approach distance; larger values bring the centers closer.",
            "TILT" to "Actual disk inclination and spin orientation, changing the trajectories.",
            "BIND" to "Disk compactness with compatible circular speeds in the same potential.",
            "TAIL" to "Pickup allocation from compact cores toward actual extended structure.")
        for (macro in controls) for (value in listOf(0f, .25f, .5f, .75f, 1f)) {
            val slug = macro.lowercase(Locale.ROOT)
            addCase("sweep-$slug-${tag(value)}", "$macro / ${fmt(value)}", "controls",
                "${roles.getValue(macro)} Other STREAM controls, pitch and seed stay fixed. All five values share the .50 reference gain.",
                "Is the low-to-middle change audible, and does the high end remain useful?", macros = stream + (macro to value),
                group = "sweep-$slug", reference = "sweep-$slug-050", sweep = macro, step = value)
        }
        val encounterCases = listOf(
            Triple("close-diffuse", "Close / diffuse disks", mapOf("PASSAGE" to .9f, "BIND" to .15f, "TILT" to .1f)),
            Triple("close-compact", "Close / compact disks", mapOf("PASSAGE" to .9f, "BIND" to .9f, "TILT" to .1f)),
            Triple("distant-diffuse", "Distant / diffuse disks", mapOf("PASSAGE" to .05f, "BIND" to .15f, "TILT" to .1f)),
            Triple("distant-compact", "Distant / compact disks", mapOf("PASSAGE" to .05f, "BIND" to .9f, "TILT" to .1f)),
            Triple("close-inclined", "Close / inclined disks", mapOf("PASSAGE" to .9f, "BIND" to .15f, "TILT" to .65f)),
            Triple("close-retrograde", "Close / contrasting spin", mapOf("PASSAGE" to .9f, "BIND" to .15f, "TILT" to 1f)),
        )
        for ((id, title, overrides) in encounterCases) addCase("encounter-$id", title, "encounters",
            "PASSAGE ${fmt(overrides.getValue("PASSAGE"))}, BIND ${fmt(overrides.getValue("BIND"))}, TILT ${fmt(overrides.getValue("TILT"))}. The same clock, source profiles and high tail pickup are retained.",
            "Does close passage and disk structure change the material left ringing?", macros = stream + overrides + ("TAIL" to .8f),
            group = "encounters", reference = "encounter-close-diffuse")
        val mechanismOptions = listOf(
            Triple("no-tides", "Differential tidal force removed", Tidal.Options(noTidalGradient = true)),
            Triple("fixed-envelope", "Fixed decay replaces evolving tails", Tidal.Options(fixedTailEnvelope = true)),
            Triple("single-descriptor", "Cohorts collapsed to one descriptor", Tidal.Options(singleDescriptor = true)),
            Triple("reassigned-identity", "Identity follows current center", Tidal.Options(reassignedIdentity = true)),
        )
        for ((id, title, options) in mechanismOptions) addCase("mechanism-$id", title, "mechanisms",
            when (id) {
                "no-tides" -> "The centers follow the same encounter. Each disk receives its original center's uniform companion acceleration, while the spatial tidal gradient is removed. This avoids artificial stripping from a center accelerating away."
                "fixed-envelope" -> "A fixed envelope replaces evolving tail controls. Source phase, encounter schedule and common gain remain comparable. This is a deliberate simplified musical control."
                "single-descriptor" -> "Cohort control differences are collapsed. Original source profiles remain, but the disk no longer reveals independent strands."
                else -> "A deliberate incorrect-identity control assigns the current center's fingerprint rather than retaining the original population identity."
            }, "Does the complete STREAM default reveal a structural difference from this simplification?",
            options = options, group = "stream", reference = "stream-default")
        addCase("mechanism-no-tides-tail", "No tidal gradient / tail tap", "mechanisms",
            "The quiet extended tap from the compensated no-tidal-gradient control. It retains the complete STREAM reference gain.",
            "Does the tail remain quiet when the model supplies no extension?", options = Tidal.Options(noTidalGradient = true),
            component = "tail", group = "stream", reference = "stream-default")
        addCase("mechanism-fixed-envelope-tail", "Fixed envelope / tail tap", "mechanisms",
            "The tail tap with fixed decay replacing evolving controls, at the complete STREAM reference gain.",
            "Does the genuine tail carry detail beyond a generic decay?", options = Tidal.Options(fixedTailEnvelope = true),
            component = "tail", group = "stream", reference = "stream-default")
        addCase("mechanism-no-tides-population-b", "No tidal gradient / bright population", "mechanisms",
            "Population B from the no-tidal-gradient run, at the complete STREAM reference gain.",
            "Is the pitched source still present when deformation is removed?", options = Tidal.Options(noTidalGradient = true),
            component = "population-b", group = "stream", reference = "stream-default")
        for (voice in TidalVoice.entries) {
            val slug = voice.name.lowercase(Locale.ROOT)
            for (midi in listOf(36, 72)) addCase("$slug-note-$midi", "${voice.name} / ${if (midi == 36) "C2" else "C5"}", "registers",
                "The same dimensionless encounter at MIDI $midi, velocity .75. Pitch changes the synthesis while encounter preparation is reused.",
                "Does the pitched body and population contrast survive this register?", voice = voice, midi = midi)
            for (velocity in listOf(.25f, 1f)) addCase("$slug-velocity-${tag(velocity)}", "${voice.name} / velocity ${fmt(velocity)}", "registers",
                "Velocity changes excitation and spectral effort while the encounter stays fixed. These clips share the default's gain, preserving playing-energy differences.",
                "Does the softer or harder performance change articulation as well as level?", voice = voice, velocity = velocity,
                group = slug, reference = "$slug-default")
        }
        addCase("all-high", "All five controls high", "extremes",
            "APPROACH, PASSAGE, TILT, BIND and TAIL are one; HOLD remains zero. This combines a fast close pass, compact contrasting-spin disks and extended pickup.",
            "Does the extreme remain finite and pitched without inventing a stream?", macros = stream + controls.associateWith { 1f })
        addCase("weak-tail", "Distant compact disks / tail pickup", "extremes",
            "A distant compact encounter with TAIL one. This structural quiet-tail fixture uses the close encounter's gain and is not independently normalized.",
            "Does the instrument preserve the truth of a weak afterstructure?", macros = stream + mapOf("PASSAGE" to 0f, "BIND" to 1f, "TAIL" to 1f),
            group = "encounters", reference = "encounter-close-diffuse")
    }

    private fun writeClip(root: File, rendered: Rendered, matchedGain: Float, rawGain: Float): Map<String, Any?> {
        val request = rendered.request
        val raw = FloatArray(rendered.samples.size) { rendered.samples[it] * rawGain }
        val matched = FloatArray(rendered.samples.size) { rendered.samples[it] * matchedGain }
        check(peak(raw) <= CEILING + 1e-6 && peak(matched) <= CEILING + 1e-6) { "${request.id}: output would clip." }
        val paths = linkedMapOf("raw" to "audio/raw/${request.id}.wav", "matched" to "audio/matched/${request.id}.wav",
            "recipe" to "recipes/${request.id}.json", "diagnosticRecipe" to "diagnostic-recipes/${request.id}.json",
            "trace" to "traces/${request.id}.json", "diagnostics" to "diagnostics/${request.id}.json")
        val rawFile = WavWriter.write(File(root, paths.getValue("raw")), snip(raw), WavWriter.BitDepth.PCM_16)
        val matchedFile = WavWriter.write(File(root, paths.getValue("matched")), snip(matched), WavWriter.BitDepth.PCM_16)
        val recipe = patch(request)
        File(root, paths.getValue("recipe")).apply { parentFile.mkdirs(); writeText(recipe.toJsonText() + "\n") }
        writeJson(File(root, paths.getValue("diagnosticRecipe")), linkedMapOf(
            "format" to "snipsnap-tidal-diagnostic", "version" to 1, "patch" to recipe.toJsonValue(),
            "options" to optionsMap(request.options), "component" to request.component,
            "rawGain" to rawGain, "matchedGain" to matchedGain, "prelevelSamplesSha256" to samplesHash(rendered.samples),
            "note" to "A production patch replays the full ordinary gesture; this diagnostic recipe also selects the listed ablation and tap."))
        writeJson(File(root, paths.getValue("trace")), linkedMapOf("frames" to rendered.trace,
            "note" to "Actual synthesis controls reduced from the prepared trajectory; no display-only particle model is used."))
        val metrics = metrics(matched, request.midi)
        writeJson(File(root, paths.getValue("diagnostics")), rendered.diagnostics + linkedMapOf(
            "id" to request.id, "component" to request.component, "options" to optionsMap(request.options),
            "metrics" to metrics, "rawMetrics" to meters(rendered.samples),
            "matchedGain" to matchedGain, "rawGain" to rawGain))
        return linkedMapOf("id" to request.id, "title" to request.title, "section" to request.section,
            "description" to request.description, "question" to request.question, "voice" to request.voice.name,
            "midi" to request.midi, "velocity" to request.velocity, "macros" to request.macros,
            "component" to request.component, "comparisonGroup" to request.comparisonGroup, "referenceId" to request.referenceId,
            "sweep" to request.sweep, "step" to request.step, "paths" to paths,
            "durationSeconds" to matched.size.toDouble() / RATE, "matchedGain" to matchedGain, "rawGain" to rawGain,
            "gains" to mapOf("matched" to matchedGain, "raw" to rawGain, "referenceId" to request.referenceId),
            "metrics" to metrics, "rawMetrics" to meters(rendered.samples), "diagnostics" to rendered.diagnostics,
            "renderMilliseconds" to rendered.renderMilliseconds, "options" to optionsMap(request.options),
            "waveform" to waveform(matched), "hashes" to linkedMapOf("raw" to sha256(rawFile), "matched" to sha256(matchedFile),
                "prelevelSamples" to samplesHash(rendered.samples), "probeSamples" to rendered.nativeSamplesSha256))
    }

    private fun replay(args: List<String>) {
        require(args.size in 2..3) { "--replay <diagnostic.json> <out.wav> [raw|matched]" }
        val mode = args.getOrNull(2) ?: "matched"
        require(mode in setOf("raw", "matched")) { "Replay mode must be raw or matched." }
        val obj = Json.parse(File(args[0]).readText()).obj()
        require(obj.getValue("format").str() == "snipsnap-tidal-diagnostic" && obj.getValue("version").int() == 1)
        val patch = TidalPatch.fromJsonValue(obj.getValue("patch"))
        val option = obj.getValue("options").obj()
        val options = Tidal.Options(noTidalGradient = option.getValue("noTidalGradient").bool(),
            fixedTailEnvelope = option.getValue("fixedTailEnvelope").bool(), singleDescriptor = option.getValue("singleDescriptor").bool(),
            reassignedIdentity = option.getValue("reassignedIdentity").bool(), normalize = false,
            oversample = option.getValue("oversample").int(), particleCount = option.getValue("particleCount").int(),
            step = option.getValue("step").num())
        val probe = Tidal.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed, patch.model, options)
        val selected = select(probe, obj.getValue("component").str())
        check(samplesHash(selected) == obj.getValue("prelevelSamplesSha256").str()) { "Diagnostic replay differs from saved sample identity." }
        val gain = obj.getValue("${mode}Gain").num()
        val out = FloatArray(selected.size) { (selected[it] * gain).toFloat() }
        check(peak(out) < 1f)
        WavWriter.write(File(args[1]), snip(out), WavWriter.BitDepth.PCM_16)
        println("TIDAL replayed $mode ${obj.getValue("component").str()} to ${args[1]}")
    }

    private fun patch(request: Request) = TidalPatch(request.title, request.voice, request.macros, request.midi, request.velocity, SEED, Tidal.MODEL_VERSION)
    private fun select(probe: Tidal.Probe, component: String) = when (component) {
        "mix" -> probe.rawMix; "core" -> probe.core; "tail" -> probe.tail
        "population-a" -> probe.populationA; "population-b" -> probe.populationB
        else -> error("Unknown TIDAL component $component")
    }
    private fun optionsMap(options: Tidal.Options): Map<String, Any?> = linkedMapOf(
        "noTidalGradient" to options.noTidalGradient, "fixedTailEnvelope" to options.fixedTailEnvelope,
        "singleDescriptor" to options.singleDescriptor, "reassignedIdentity" to options.reassignedIdentity,
        "normalize" to false, "oversample" to options.oversample, "particleCount" to options.particleCount, "step" to options.step)
    private fun sections() = listOf(
        Triple("defaults", "Two first voices", "Start with the complete finite PAIR and STREAM gestures."),
        Triple("anatomy", "Inside the encounter", "Population and core/tail taps retain the full performance's gain. Quiet components stay quiet."),
        Triple("controls", "Five controls", "Five positions per macro, with one shared midpoint-reference gain per sweep. HOLD stays zero."),
        Triple("encounters", "What survives the pass?", "Close and distant encounters, compact and diffuse disks, and real inclination/spin differences."),
        Triple("mechanisms", "What does the model contribute?", "Compensated force removal and simplified tail, cohort and identity comparisons against STREAM defaults."),
        Triple("registers", "Pitch and playing effort", "The same prepared movement at lower/higher notes and softer/harder excitation."),
        Triple("extremes", "Boundary conditions", "All-high controls and an intentionally weak-tail encounter."),
    ).map { (id, title, description) -> mapOf("id" to id, "title" to title, "description" to description) }
    private fun meters(samples: FloatArray): Map<String, Any?> = linkedMapOf("peak" to peak(samples),
        "rms" to sqrt(samples.sumOf { it.toDouble() * it } / samples.size),
        "dc" to samples.sumOf { it.toDouble() } / samples.size, "loudness" to Loudness.of(snip(samples)),
        "finite" to samples.all { it.isFinite() })
    private fun metrics(samples: FloatArray, midi: Int): Map<String, Any?> {
        val audio = snip(samples)
        val pitch = Pitch.detect(audio, .35f, .25f)
        val requestedHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val onset = Classifier.classify(snip(samples.copyOfRange(0, minOf(samples.size, (RATE * .75).toInt()))))
        val tail = samples.takeLast(RATE / 5)
        return meters(samples) + linkedMapOf("classifier" to Classifier.classify(audio).drumClass.name,
            "onsetClassifier" to onset.drumClass.name, "onsetClassifierConfidence" to onset.confidence,
            "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence, "requestedRootHz" to requestedHz,
            "pitchErrorCents" to pitch?.let { 1200 * ln(it.hz / requestedHz) / ln(2.0) },
            "tailRms" to sqrt(tail.sumOf { it.toDouble() * it } / tail.size))
    }
    private fun sourceSnapshot(repo: File, root: File): Map<String, Any?> {
        val paths = listOf("synth/src/main/kotlin/com/snipsnap/synth/Tidal.kt", "synth/src/main/kotlin/com/snipsnap/synth/TidalDynamics.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/TidalPatch.kt", "synth/src/main/kotlin/com/snipsnap/synth/TidalPresets.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/TidalAuditionGenerator.kt", "synth/src/test/kotlin/com/snipsnap/synth/TidalTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/TidalDynamicsTest.kt", "synth/src/test/kotlin/com/snipsnap/synth/TidalPatchTest.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt", "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt", "synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt", "audio/src/main/kotlin/com/snipsnap/audio/Snip.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt", "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt", "audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt", "synth/build.gradle.kts", "docs/TIDAL.md",
            "synth/src/test/resources/tidal-audition/index.html", "synth/src/test/resources/tidal-audition/app.js",
            "synth/src/test/resources/tidal-audition/styles.css")
        val files = paths.filter { File(repo, it).isFile }.map { path ->
            val source = File(repo, path)
            val target = File(root, "source/$path").apply { parentFile.mkdirs() }
            source.copyTo(target, overwrite = true)
            mapOf("repositoryPath" to path, "path" to "source/$path", "sha256" to sha256(source))
        }
        return linkedMapOf("repository" to "https://github.com/BertCalm/SnipSnap", "revision" to git(repo, "rev-parse", "HEAD"),
            "dirty" to git(repo, "status", "--porcelain").isNotBlank(), "files" to files,
            "note" to "The source snapshot hashes identify the rendered implementation. The parent commit alone does not identify uncommitted prototype changes.")
    }
    private fun writeMetrics(root: File, clips: List<Map<String, Any?>>) {
        val keys = listOf("id", "section", "voice", "midi", "velocity", "durationSeconds", "component", "peak", "rms", "dc", "loudness",
            "classifier", "onsetClassifier", "pitchHz", "pitchConfidence", "pitchErrorCents", "rawPeak", "rawRms", "tailRms", "coreRms",
            "minimumSeparation", "encounterTimeSeconds", "maxCenterEnergyRelativeError", "maxExtension", "finalExtension", "matchedGain", "rawGain")
        File(root, "metrics.csv").writeText(buildString {
            appendLine(keys.joinToString(","))
            for (clip in clips) {
                @Suppress("UNCHECKED_CAST")
                val row = clip + (clip.getValue("metrics") as Map<String, Any?>) + (clip.getValue("diagnostics") as Map<String, Any?>)
                appendLine(keys.joinToString(",") { csv(row[it]) })
            }
        })
    }
    private fun readme(rawGain: Float) = """
        TIDAL / First audition
        ======================
        Finite PAIR and STREAM, mono 44.1 kHz PCM16. Two restricted particle disks
        drive 32 persistent source cohorts, two harmonic profiles and stable tail modes.
        HOLD stays zero. A Repeat button replays the full finite encounter.
        Factory expansion, phone picker, sustained HOLD and hardware acceptance are later work.
        No owner listening verdict or astrophysical accuracy claim is asserted.

        Matched audio uses one reference gain per comparison family and a family peak guard.
        Stems, ablations, velocity comparisons and weak-tail fixtures retain that reference gain.
        Raw audio uses one common gain of $rawGain throughout. Quiet diagnostic tails remain quiet.
        Production patch playback applies its normal shared melodic leveler.

        recipes/*.json reconstruct ordinary full TidalPatch performances.
        diagnostic-recipes/*.json also store the selected component, numerical settings,
        deliberate mechanism ablations and raw/matched gains. Replay the exact exported clip:
        ./gradlew :synth:generateTidalAudition -PtidalReplay=/absolute/diagnostic.json -PtidalReplayOutput=/absolute/out.wav
        Add -PtidalReplayMode=raw for the common-gain raw variant.

        manifest.json records the per-clip files, hashes, comparison references and diagnostics.
        traces/*.json contain sampled actual synthesis controls. metrics.csv has audio and dynamics measurements.
        The generic classifier calls >1.5-second clips LOOP by duration; every clip here is finite.
        A separate onsetClassifier measures the first .75 seconds. Pitch estimates are local and
        can report a different harmonic in isolated tail/upper-population taps.

        No-tidal-gradient controls preserve uniform companion acceleration at each original center
        while removing spatial differential force. Cohort spreading alone is not treated as tails:
        ordinary disk differential rotation can spread an undisturbed cohort.

        source/ snapshots the renderer, patch, numerical solver, tests, generator, frontend and local dependencies.
        SHA-256 hashes identify the exact files at generation; this source snapshot is not a full standalone checkout.
        Recreate from the SnipSnap repository with ./gradlew --no-daemon :synth:generateTidalAudition.
        starter.zip contains ten representative matched WAVs, recipes and this readme.
        source-recipes.zip contains the source snapshot, all recipes and summary measurements.
        Every raw/matched clip is also individually downloadable from the listening page.
    """.trimIndent() + "\n"
    private fun zip(root: File, name: String, include: (String) -> Boolean) {
        val files = root.walkTopDown().filter { it.isFile && it.extension != "zip" }
            .map { it to it.relativeTo(root).invariantSeparatorsPath }.filter { include(it.second) }.sortedBy { it.second }.toList()
        ZipOutputStream(File(root, name).outputStream().buffered()).use { zip ->
            for ((file, path) in files) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
    private fun waveform(samples: FloatArray) = (0 until 96).map { bin ->
        var p = 0f
        for (i in bin * samples.size / 96 until (bin + 1) * samples.size / 96) p = maxOf(p, abs(samples[i]))
        p
    }
    private fun sameSamples(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
    private fun snip(samples: FloatArray) = Snip(samples, channels = 1, sampleRate = RATE)
    private fun peak(samples: FloatArray) = samples.maxOfOrNull { abs(it) } ?: 0f
    private fun tag(value: Float) = (value * 100).toInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value)
    private fun csv(value: Any?) = value?.toString()?.let { if (it.any { c -> c in ",\"\n\r" }) "\"${it.replace("\"", "\"\"")}\"" else it } ?: ""
    private fun repositoryRoot() = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, ".git").exists() } ?: error("Run inside the SnipSnap checkout.")
    private fun git(repo: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).directory(repo).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        return if (process.waitFor() == 0) out else "unavailable"
    }
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex()
    private fun samplesHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            if (buffer.remaining() < 4) { digest.update(buffer.array(), 0, buffer.position()); buffer.clear() }
            buffer.putInt(sample.toRawBits())
        }
        digest.update(buffer.array(), 0, buffer.position())
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun writeJson(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n") }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported TIDAL manifest value ${value.javaClass.name}")
    }
}

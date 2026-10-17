package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dry Embouchure revision and frozen original references, rendered by the host patches.
 * Complete recipes travel beside every file. Numerical evidence does not supply a
 * listener's sonic approval; --quick deliberately omits the complete acceptance matrix.
 */
object EmbouchureAuditionGenerator {
    // One declared PCM headroom gain preserves relative raw amplitudes across every diagnostic.
    // Acoustic pressure units are not PCM units: the finite BRASS forte source peaks at 1.002.
    private const val RAW_EXPORT_GAIN = .5f
    private val controls = listOf("EFFORT", "REED", "SHAPE", "GRAIN", "LENGTH")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val interactionPairs = listOf(
        "EFFORT" to "REED", "EFFORT" to "GRAIN", "REED" to "SHAPE", "EFFORT" to "LENGTH",
    )

    private data class Spec(
        val id: String,
        val title: String,
        val group: String,
        val description: String,
        val patch: EmbouchurePatch,
        val preset: String? = null,
        val feedback: Boolean = true,
        val comparisonFiles: Boolean = false,
        val sweep: Map<String, Any?>? = null,
        val grid: Map<String, Any?>? = null,
        val comparison: Map<String, String>? = null,
        val referenceId: String? = null,
    )

    private data class ReferencePack(val directory: File, val manifest: Map<String, JsonValue>,
                                     val clips: Map<String, Map<String, JsonValue>>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.all {
            !it.startsWith("--") || it == "--quick" || it.startsWith("--reference-dir=")
        } && args.count { it.startsWith("--reference-dir=") } <= 1) {
            "Usage: EmbouchureAuditionGenerator [output-directory] [--quick] [--reference-dir=verified-model1-pack]"
        }
        val quick = "--quick" in args
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/embouchure-audition-model2")
        val reference = args.firstOrNull { it.startsWith("--reference-dir=") }?.substringAfter('=')?.let {
            val directory = File(it)
            require(directory.canonicalFile != root.canonicalFile) { "reference pack must differ from output directory" }
            val manifest = Json.parse(File(directory, "manifest.json").readText()).obj()
            require(manifest.getValue("modelVersion").int() == 1) { "reference pack must use model1" }
            ReferencePack(directory, manifest, manifest.getValue("clips").arr().associate { clip ->
                val row = clip.obj()
                row.getValue("id").str() to row
            })
        }
        val audio = File(root, "audio").also { it.mkdirs() }
        // Only this generator's WAV namespace is replaced; the listening page is copied last.
        audio.listFiles()?.filter { it.isFile && it.extension == "wav" }?.forEach { it.delete() }
        val groups = mutableListOf<Map<String, Any?>>()
        val specs = mutableListOf<Spec>()
        fun group(id: String, title: String, description: String) {
            groups += linkedMapOf("id" to id, "title" to title, "description" to description)
        }
        fun patch(name: String, voice: EmbouchureVoice, overrides: Map<String, Float> = emptyMap(),
                  midi: Int = Embouchure.rootMidi(voice), velocity: Float = 1f,
                  gesture: EmbouchureGesture = Embouchure.gestureFor(voice)) =
            EmbouchurePatch(name, voice, Embouchure.defaults(voice) + overrides,
                midi = midi, velocity = velocity, gesture = gesture)
        fun add(group: String, id: String, title: String, voice: EmbouchureVoice,
                overrides: Map<String, Float> = emptyMap(), description: String = "Dry voice defaults; no rack effects.",
                midi: Int = Embouchure.rootMidi(voice), velocity: Float = 1f,
                gesture: EmbouchureGesture = Embouchure.gestureFor(voice), feedback: Boolean = true,
                comparisonFiles: Boolean = false, sweep: Map<String, Any?>? = null,
                grid: Map<String, Any?>? = null) {
            specs += Spec(id, title, group, description, patch(title, voice, overrides, midi, velocity, gesture),
                feedback = feedback, comparisonFiles = comparisonFiles, sweep = sweep, grid = grid)
        }

        group("PRESETS", "Eight starting gestures", "Eight dry starting points for owner listening. Held Effort is a settled loop; the other presets play a finite gesture.")
        EmbouchurePresets.legacy().forEach { p ->
            specs += Spec("preset_${slug(p.name)}", p.name, "PRESETS",
                presetDescription(p.name), p, preset = p.name)
        }
        group("VOICES", "Six dry voices", "One root-bearing modeled instrument and a virtual player. WHISTLE is a clear-tone air/reed approximation.")
        EmbouchureVoice.entries.forEach { voice ->
            add("VOICES", "voice_${voice.name.lowercase()}", voiceTitle(voice), voice,
                description = voiceDescription(voice))
        }

        group("RANGE", "Register and velocity", "Stored MIDI names the base root; these clips set TUNE to zero. Velocity changes excitation before shared melodic loudness targeting.")
        val rangeVoices = if (quick) listOf(EmbouchureVoice.REED) else EmbouchureVoice.entries
        rangeVoices.forEach { voice ->
            val offsets = if (quick) listOf(0, 12) else listOf(0, 12, 24)
            offsets.forEach { offset ->
                val midi = Embouchure.rootMidi(voice) + offset
                listOf(.25f, .6f, 1f).forEach { velocity ->
                    add("RANGE", "range_${voice.name.lowercase()}_m${midi}_v${tag(velocity)}",
                        "${voiceTitle(voice)} · ${noteName(midi)} · ${fmt(velocity)} velocity", voice,
                        mapOf("TUNE" to 0f), "Three input energies at the same note and voice defaults; finite gesture.",
                        midi = midi, velocity = velocity)
                }
            }
        }

        val sweepVoices = if (quick) listOf(EmbouchureVoice.REED) else EmbouchureVoice.entries
        sweepVoices.forEach { voice ->
            val groupId = "SWEEPS_${voice.name}"
            group(groupId, "${voiceTitle(voice)} controls", "Five controls, one at a time. Companions exactly match this voice's default clip.")
            controls.forEach { control ->
                (if (quick) listOf(0f, 1f) else steps).forEach { value ->
                    add(groupId, "sweep_${voice.name.lowercase()}_${control.lowercase()}_${tag(value)}",
                        "${voiceTitle(voice)} · $control ${fmt(value)}", voice, mapOf(control to value),
                        "$control alone changes; every other macro uses ${voice.name} defaults.",
                        sweep = linkedMapOf("macro" to control, "value" to value,
                            "baseId" to "voice_${voice.name.lowercase()}"))
                }
            }
        }

        if (!quick) {
            group("GRIDS", "Controls in conversation", "Four 5 × 5 grids use REED defaults. Lip and bowed contact also have 3 × 3 EFFORT × REED comparisons.")
            fun grid(voice: EmbouchureVoice, x: String, y: String, values: List<Float>) {
                values.forEach { xv -> values.forEach { yv ->
                    add("GRIDS", "grid_${voice.name.lowercase()}_${x.lowercase()}_${tag(xv)}_${y.lowercase()}_${tag(yv)}",
                        "${voiceTitle(voice)} · $x ${fmt(xv)} / $y ${fmt(yv)}", voice,
                        mapOf(x to xv, y to yv), "Named controls change together; other companions use voice defaults.",
                        grid = linkedMapOf("xMacro" to x, "xValue" to xv, "yMacro" to y, "yValue" to yv))
                } }
            }
            interactionPairs.forEach { (x, y) -> grid(EmbouchureVoice.REED, x, y, steps) }
            listOf(EmbouchureVoice.BRASS, EmbouchureVoice.BOW).forEach {
                grid(it, "EFFORT", "REED", listOf(0f, .5f, 1f))
            }
        }

        group("EXTREMES", "All five controls high", "Finite upper-register stress cases. Bounds and decay are engineering checks; these settings are not preset recommendations.")
        (if (quick) listOf(EmbouchureVoice.REED, EmbouchureVoice.BOW) else EmbouchureVoice.entries).forEach { voice ->
            add("EXTREMES", "extreme_${voice.name.lowercase()}", "${voiceTitle(voice)} · all high", voice,
                controls.associateWith { 1f } + mapOf("TUNE" to 0f, "HOLD" to 0f),
                "All five timbral macros at one; explicit finite gesture, one octave above the voice base.",
                midi = Embouchure.rootMidi(voice) + 12)
        }

        group("HOLD", "Settled notes on repeat", "HOLD supplies continuing player energy. These buffers omit the finite attack and contain one selected settled loop; enable repeat to hear the join.")
        val heldVoices = if (quick) listOf(EmbouchureVoice.REED, EmbouchureVoice.BOW) else EmbouchureVoice.entries
        heldVoices.forEach { voice ->
            add("HOLD", "hold_${voice.name.lowercase()}_rough", "${voiceTitle(voice)} · rough held effort", voice,
                mapOf("HOLD" to 1f, "EFFORT" to .88f, "REED" to .79f, "GRAIN" to .92f, "LENGTH" to .73f),
                "High effort and seeded roughness; production HOLD calibration keeps the driver bounded.")
        }
        if (!quick) {
            add("HOLD", "hold_reed_pressure_cycle", "Reed · long pressure contour", EmbouchureVoice.REED,
                mapOf("HOLD" to 1f, "EFFORT" to 1f, "REED" to 1f, "SHAPE" to .83f, "GRAIN" to .67f, "LENGTH" to 1f),
                "Maximum effort/contact with a long held contour; finite entry is absent.")
            add("HOLD", "hold_bow_tight_contact", "Bow · tight rough contact", EmbouchureVoice.BOW,
                mapOf("HOLD" to 1f, "EFFORT" to .97f, "REED" to 1f, "SHAPE" to .93f, "GRAIN" to 1f, "LENGTH" to .19f),
                "A difficult bowed contact state and short repeating effort contour; finite entry is absent.")
        }

        group("FEEDBACK", "The driver listens to the resonator", "Ablation holds note, controls, gesture and roughness seed fixed. Feedback-off is a diagnostic render, not a saved production patch.")
        (if (quick) listOf(EmbouchureVoice.REED) else listOf(EmbouchureVoice.REED, EmbouchureVoice.BRASS, EmbouchureVoice.BOW)).forEach { voice ->
            listOf(true, false).forEach { feedback ->
                add("FEEDBACK", "feedback_${voice.name.lowercase()}_${if (feedback) "on" else "off"}",
                    "${voiceTitle(voice)} · feedback ${if (feedback) "on" else "off"}", voice,
                    mapOf("EFFORT" to .73f, "REED" to .67f, "GRAIN" to .2f, "HOLD" to 0f),
                    "Only the driver's resonator feedback changes; output conditioning and loudness rule remain the same.",
                    feedback = feedback, comparisonFiles = true)
            }
        }

        group("VELOCITY", "Energy before and after leveling", "Raw WAVs preserve relative generated amplitudes through one fixed −6.02 dB PCM export gain. Production-normalized WAVs use the host melodic target; matched WAVs use the quieter audition comparison target.")
        (if (quick) listOf(EmbouchureVoice.REED) else listOf(EmbouchureVoice.REED, EmbouchureVoice.BRASS, EmbouchureVoice.BOW)).forEach { voice ->
            listOf(.25f, .6f, 1f).forEach { velocity ->
                add("VELOCITY", "velocity_${voice.name.lowercase()}_v${tag(velocity)}",
                    "${voiceTitle(voice)} · ${fmt(velocity)} input energy", voice,
                    description = "Identical voice/note/gesture; only velocity changes. Compare raw and normalized files separately.",
                    velocity = velocity, comparisonFiles = true)
            }
        }

        // Pair the exact same stored controls, player and note across the frozen and revised models.
        val referenceIndices = specs.indices.filter {
            specs[it].group in setOf("PRESETS", "VOICES") || specs[it].sweep != null
        }
        val originals = referenceIndices.map { index ->
            val revised = specs[index]
            val originalId = "original_${revised.id}"
            val comparison = linkedMapOf("originalId" to originalId, "revisedId" to revised.id)
            specs[index] = revised.copy(comparison = comparison)
            revised.copy(id = originalId, title = "Original · ${revised.title}", group = "ORIGINAL",
                description = "Model1 reference at the same macros, note, velocity and gesture as the revised clip. ${revised.description}",
                patch = revised.patch.copy(model = 1), preset = null, comparison = comparison,
                referenceId = revised.id,
                sweep = revised.sweep?.let { it + ("baseId" to "original_voice_${revised.patch.voice.name.lowercase()}") })
        }
        group("ORIGINAL", "Original model1 references",
            "The original eight presets, six voice defaults and control sweeps preserve model1. Original and revised pairs use identical stored inputs and excitation seeds.")
        specs += originals
        if (!quick) require(specs.size == 529 && originals.size == 164) { "unexpected full model comparison matrix size" }
        if (reference != null) originals.forEach { verifyOriginalInputs(it, reference) }

        require(specs.map { it.id }.toSet().size == specs.size) { "duplicate audition clip id" }
        val renderer = rendererFingerprint()
        val started = System.nanoTime()
        val rows = specs.mapIndexed { index, spec ->
            val row = render(root, spec, renderer.getValue("sha256") as String)
            if (spec.referenceId != null && reference != null) verifyOriginal(root, row, spec, reference)
            println("[${index + 1}/${specs.size}] ${spec.id}")
            row
        }
        val kit = exportKit(root)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "Embouchure", "modelVersion" to Embouchure.MODEL_VERSION,
            "models" to listOf(linkedMapOf("version" to 1, "label" to "Original"),
                linkedMapOf("version" to Embouchure.MODEL_VERSION, "label" to "Revised")),
            "originalReferences" to linkedMapOf("modelVersion" to 1, "clips" to originals.size,
                "sameInputs" to true, "sameExcitationSeed" to true,
                "verifiedWavHashes" to if (reference == null) 0 else originals.size,
                "publishedRendererFingerprint" to reference?.manifest?.get("rendererFingerprint")),
            "rendererFingerprint" to renderer,
            "sampleRate" to Dsp.RATE, "channels" to 1, "format" to "PCM16", "quick" to quick,
            "completeMatrix" to !quick, "acceptance" to "Owner listening verdict pending",
            "sourceUrl" to "https://github.com/BertCalm/SnipSnap/tree/codex/embouchure-engine", "bundlePath" to "embouchure-audition.zip",
            "bundleDescription" to "Curated starter pack: eight revised presets, six revised voices, their fourteen original references, selected held/velocity/feedback clips and the sixteen-pad MPC 2/3 kit",
            "groups" to groups, "clips" to rows, "kit" to kit,
            "normalization" to linkedMapOf("default" to "shared-melodic", "sharedLoudnessTarget" to Dsp.MELODIC_LOUDNESS_TARGET,
                "matchedLoudnessTarget" to .03f, "velocityGain" to "Production notes and held loops apply velocity^0.60 after forte-reference leveling",
                "rawExportGain" to RAW_EXPORT_GAIN, "rawExportGainDb" to -6.020599913279624,
                "rawExport" to "Every raw diagnostic uses one fixed gain of 0.5 for PCM headroom, without per-clip leveling; rawSourceMetrics retain the unscaled conditioned source",
                "measure" to "Loudest 200 ms RMS with gentle 120 Hz low cut; peak guard applied"),
            "caveats" to listOf(
                "Original simplified drivers and gestures; no Yamaha algorithms, factory data or sampled instruments.",
                "WHISTLE is a clear-tone air/reed-derived approximation, not a calibrated jet-edge flute model.",
                "Each note starts fresh; there is no cross-note legato or persistent player state.",
                "HOLD WAVs contain a settled repeating region without the finite onset.",
                "Pitch estimates are numerical probes with a 40–1200 Hz detector range, not listening verdicts.",
                "The existing classifier labels clips longer than 1.5 seconds LOOP independent of pitched identity.",
                "Preset expansion awaits owner sonic approval.",
            ),
        )
        val manifestText = Json.write(json(manifest))
        File(root, "manifest.json").writeText(manifestText + "\n")
        File(root, "measurements.json").writeText(Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to "EMBOUCHURE", "modelVersion" to Embouchure.MODEL_VERSION,
            "completeMatrix" to !quick,
            "clips" to rows.size, "wavFiles" to audio.listFiles().orEmpty().count { it.extension == "wav" },
            "totalRenderMs" to (System.nanoTime() - started) / 1_000_000.0,
            "audioMeasure" to "Output-rate floats before PCM16 quantization; rawSourceMetrics precede the fixed raw export gain; mean and RMS use the whole buffer",
            "listeningVerdict" to "Owner listening verdict pending",
            "rows" to rows.map(::measurementRow),
        ))) + "\n")
        copyPage(root, manifestText)
        bundle(root, manifest, rows)
        println("Wrote ${rows.size} clips, manifest, measurements and download bundle under ${root.absolutePath}")
        if (quick) println("Development pack only: run without --quick for the complete matrix.")
    }

    private fun render(root: File, spec: Spec, fingerprint: String): MutableMap<String, Any?> {
        val p = spec.patch
        val held = Embouchure.isLoop(p.macros.getValue("HOLD"))
        require(spec.feedback || !held) { "feedback ablation is a finite diagnostic" }
        val start = System.nanoTime()
        val played = if (spec.comparisonFiles || !spec.feedback) {
            Embouchure.play(p.voice, p.macros, Embouchure.Probe(
                midi = p.midi, velocity = p.velocity, gesture = p.gesture, feedback = spec.feedback, record = false,
                model = p.model))
        } else null
        val raw = played?.let { Snip(Embouchure.finish(it.raw, normalize = false, rate = it.rate), 1, Dsp.RATE) }
        val snip = if (spec.feedback) p.render() else {
            val source = requireNotNull(played)
            Snip(Embouchure.finish(source.raw, normalize = true, rate = source.rate), 1, Dsp.RATE)
        }
        require(snip.channels == 1 && snip.sampleRate == 44100 && snip.samples.isNotEmpty()) { "${spec.id}: invalid audio format" }
        require(snip.samples.all { it.isFinite() }) { "${spec.id}: non-finite output" }
        require(snip.peak() <= 1f) { "${spec.id}: output would clip PCM16" }
        val path = "audio/${spec.id}.wav"
        WavWriter.write(File(root, path), snip, WavWriter.BitDepth.PCM_16)
        val effectiveMidi = p.midi + (p.macros.getValue("TUNE") * Embouchure.TUNE_SEMITONES).roundToInt()
        val recipe = p.toJsonValue()
        val row = linkedMapOf<String, Any?>(
            "id" to spec.id, "title" to spec.title, "name" to spec.title, "group" to spec.group,
            "description" to spec.description, "voice" to p.voice.name, "modelVersion" to p.model,
            "path" to path, "frames" to snip.frameCount, "durationSeconds" to snip.durationSeconds,
            "midi" to effectiveMidi, "baseMidi" to p.midi, "note" to noteName(effectiveMidi), "velocity" to p.velocity,
            "loop" to held, "macros" to p.macros, "gesture" to p.gesture.name,
            "driver" to recipe.entries["driver"], "recipe" to recipe,
            "seed" to Embouchure.seedFor(p.voice, effectiveMidi),
            "render" to linkedMapOf("normalization" to "shared-melodic", "feedbackEnabled" to spec.feedback,
                "oversampling" to Dsp.OVERSAMPLE, "effects" to emptyList<String>(), "rendererFingerprint" to fingerprint),
            "metrics" to metrics(snip, effectiveMidi), "waveform" to waveform(snip.samples),
            "renderMs" to (System.nanoTime() - start) / 1_000_000.0,
        )
        spec.preset?.let { row["preset"] = it }
        spec.comparison?.let { row["comparison"] = it }
        spec.sweep?.let { row["sweep"] = it }
        spec.grid?.let { row["grid"] = it }
        played?.let {
            row["driverDiagnostics"] = linkedMapOf("maxEnergy" to it.maxEnergy, "maxAbs" to it.maxAbs,
                "sourceEndSeconds" to it.sourceEndSeconds, "internalRate" to it.rate, "seed" to it.seed)
        }
        if (held) {
            val measured = Embouchure.renderLoopMeasured(p.voice, p.macros,
                midi = p.midi, velocity = p.velocity, gesture = p.gesture, normalize = true, model = p.model)
            row["loopDiagnostics"] = linkedMapOf("seamError" to measured.seam,
                "seamPass" to (measured.seam.isFinite() && measured.seam < 1e-3),
                "measurementLoopStartFrame" to measured.loopStart, "settleSeconds" to measured.settleSeconds,
                "converged" to measured.converged, "stateError" to measured.stateError,
                "rawRms" to measured.rawRms, "driverFrequencyHz" to measured.hz,
                "seed" to measured.seed, "keptFrames" to snip.frameCount,
                "format" to "One settled loop, finite attack omitted")
        }
        if (spec.comparisonFiles) {
            val rawSource = requireNotNull(raw)
            require(rawSource.samples.all { it.isFinite() }) { "${spec.id}: non-finite raw source" }
            val rawSnip = Snip(FloatArray(rawSource.samples.size) { rawSource.samples[it] * RAW_EXPORT_GAIN }, 1, Dsp.RATE)
            require(rawSnip.peak() <= 1f) { "${spec.id}: raw export would clip PCM16 at the declared fixed gain" }
            val matched = AuditionLevel.level(snip)
            require(matched.samples.all { it.isFinite() } && matched.peak() <= 1f) { "${spec.id}: matched output would clip PCM16" }
            val rawPath = "audio/${spec.id}-raw.wav"
            val matchedPath = "audio/${spec.id}-matched.wav"
            WavWriter.write(File(root, rawPath), rawSnip, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            row["rawPath"] = rawPath
            row["matchedPath"] = matchedPath
            row["rawExportGain"] = RAW_EXPORT_GAIN
            row["rawSourceMetrics"] = amplitudeStats(rawSource)
            row["rawMetrics"] = amplitudeStats(rawSnip)
            row["matchedMetrics"] = amplitudeStats(matched)
        }
        return row
    }

    /** Optional historical pack evidence; frozen model1 remains renderable without these files. */
    private fun verifyOriginalInputs(spec: Spec, reference: ReferencePack) {
        val id = requireNotNull(spec.referenceId)
        val old = requireNotNull(reference.clips[id]) { "missing original reference $id" }
        val recipe = spec.patch.toJsonValue().entries
        val oldRecipe = old.getValue("recipe").obj()
        listOf("voice", "macros", "model", "midi", "velocity", "gesture").forEach { key ->
            require(recipe[key] == oldRecipe[key]) { "$id: original reference input $key differs" }
        }
    }

    private fun verifyOriginal(root: File, row: MutableMap<String, Any?>, spec: Spec, reference: ReferencePack) {
        val id = requireNotNull(spec.referenceId)
        val old = reference.clips.getValue(id)
        val previous = File(reference.directory, old.getValue("path").str())
        val generated = File(root, row.getValue("path") as String)
        require(previous.isFile && sha256(previous) == sha256(generated)) { "$id: frozen model1 WAV differs from verified original" }
        row["referenceVerification"] = linkedMapOf(
            "id" to id, "audioSha256" to sha256(generated), "matchesPublishedModel1" to true,
            "publishedRendererFingerprint" to reference.manifest.getValue("rendererFingerprint").obj().getValue("sha256"))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    private fun measurementRow(row: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "id" to row["id"], "modelVersion" to row["modelVersion"], "comparison" to row["comparison"],
        "referenceVerification" to row["referenceVerification"],
        "metrics" to row["metrics"], "loopDiagnostics" to row["loopDiagnostics"],
        "rawExportGain" to row["rawExportGain"], "rawSourceMetrics" to row["rawSourceMetrics"],
        "rawMetrics" to row["rawMetrics"], "matchedMetrics" to row["matchedMetrics"],
    )

    private fun amplitudeStats(snip: Snip): Map<String, Any?> {
        var peak = 0.0
        var sum = 0.0
        var energy = 0.0
        var nonFinite = 0
        snip.samples.forEach { sample ->
            if (!sample.isFinite()) nonFinite++ else {
                val value = sample.toDouble()
                peak = maxOf(peak, abs(value)); sum += value; energy += value * value
            }
        }
        val count = snip.samples.size.coerceAtLeast(1)
        return linkedMapOf("peak" to peak, "rms" to sqrt(energy / count), "mean" to sum / count,
            "dc" to sum / count, "nonFiniteCount" to nonFinite, "finite" to (nonFinite == 0),
            "loudness" to Loudness.of(snip))
    }

    private fun metrics(snip: Snip, midi: Int): Map<String, Any?> {
        val metrics = LinkedHashMap(amplitudeStats(snip))
        val bodyStart = minOf(.4f, snip.durationSeconds * .2f)
        val from = (bodyStart * snip.sampleRate).toInt().coerceIn(0, snip.frameCount - 1)
        val to = minOf(snip.frameCount, from + (snip.sampleRate * .3f).roundToInt())
        val body = Snip(snip.samples.copyOfRange(from, to), 1, snip.sampleRate)
        val feature = FeatureExtractor.extract(body)
        val pitch = if (Keys.midiHz(midi) in 40f..1200f) Pitch.detect(snip, bodyStart, .25f) else null
        val classification = Classifier.classify(snip)
        metrics["bodyStartSeconds"] = bodyStart
        metrics["bodyCentroidHz"] = feature.centroidHz
        metrics["bodyFlatness"] = feature.flatness
        metrics["bodyHighRatio"] = feature.highRatio
        metrics["pitchHz"] = pitch?.hz
        metrics["pitchConfidence"] = pitch?.confidence
        metrics["pitchCents"] = pitch?.let { 1200 * ln(it.hz.toDouble() / Keys.midiHz(midi)) / ln(2.0) }
        metrics["classifier"] = classification.drumClass.name
        metrics["classifierConfidence"] = classification.confidence
        return metrics
    }

    private fun waveform(samples: FloatArray): List<Float> = (0 until 160).map { bin ->
        val from = bin * samples.size / 160
        val to = ((bin + 1) * samples.size / 160).coerceAtLeast(from + 1).coerceAtMost(samples.size)
        var peak = 0f
        for (i in from until to) peak = maxOf(peak, abs(samples[i]))
        peak
    }

    /** Distinguish prototype calibrations even while they share the initial model number. */
    private fun rendererFingerprint(): Map<String, Any?> {
        val classes = linkedSetOf<Class<*>>()
        fun collect(type: Class<*>) {
            if (!classes.add(type)) return
            type.declaredClasses.forEach(::collect)
        }
        listOf(Embouchure::class.java, EmbouchureV1::class.java, EmbouchureVoice::class.java, EmbouchureGesture::class.java,
            EmbouchureDriver::class.java, Dsp::class.java, Keys::class.java, Bore::class.java,
            Strings::class.java, Siren::class.java, Tide::class.java,
            Resampler::class.java, Loudness::class.java).forEach(::collect)
        val digest = MessageDigest.getInstance("SHA-256")
        val names = classes.sortedBy { it.name }.map { type ->
            digest.update(type.name.toByteArray(Charsets.UTF_8))
            val resource = "/" + type.name.replace('.', '/') + ".class"
            val bytes = type.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: error("cannot fingerprint renderer class ${type.name}")
            digest.update(bytes)
            type.name
        }
        return linkedMapOf("algorithm" to "SHA-256 of sorted compiled renderer/support class names and bytes",
            "sha256" to digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }, "classes" to names)
    }

    private fun copyPage(root: File, manifestText: String) {
        listOf("index.html", "styles.css", "app.js").forEach { name ->
            val resource = EmbouchureAuditionGenerator::class.java.getResourceAsStream("/embouchure-audition/$name")
                ?: error("missing Embouchure listening-page resource $name")
            val content = resource.use { it.readBytes() }
            if (name == "index.html") {
                val html = content.toString(Charsets.UTF_8)
                require("</body>" in html) { "Embouchure page has no closing body" }
                val inline = "<script id=\"embouchure-manifest\" type=\"application/json\">" +
                    manifestText.replace("<", "\\u003c") + "</script>\n"
                File(root, name).writeText(html.replace("</body>", inline + "</body>"))
            } else File(root, name).writeBytes(content)
        }
    }

    private fun exportKit(root: File): Map<String, Any?> {
        val work = File(root, ".kit-work")
        work.deleteRecursively()
        try {
            val arranged = SynthKits.embouchure()
            val kit = KitAssembler.assembleArranged("SnipSnap Embouchure Kit", arranged, work)
            val phrase = Mpc3Clip("Reed pentatonic", bars = 2,
                notes = listOf(1, 2, 3, 5, 4, 3, 2, 1).mapIndexed { i, slot ->
                    Mpc3Note(Mpc3Note.noteFor(slot), i * 4 * Mpc3Clip.PULSES_PER_16TH,
                        velocity = listOf(.7f, .8f, 1f, .9f)[i % 4],
                        lengthPulses = 3 * Mpc3Clip.PULSES_PER_16TH)
                })
            val exported = Mpc3Exporter.exportTrack(kit, work, File(root, "kit"),
                overwrite = true, clip = phrase, mpc2Twin = true)
            val samples = exported.samples.first().parentFile
            val sidecar = File(work, KitStore.FILE_NAME).copyTo(File(samples, KitStore.FILE_NAME), overwrite = true)
            File(exported.directory, "README.txt").writeText("""
                SnipSnap Embouchure Kit

                MPC 3: load SnipSnap Embouchure Kit.xtd with its adjacent _[TrackData] folder.
                MPC 2: open that data folder and load SnipSnap Embouchure Kit.xpm.
                Keep the program files and sample folder together.

                A01-A08: independent C minor pentatonic reed notes.
                A09-A16: the eight original dry starting presets.
                MPC 3 includes a two-bar reed phrase. kit.json records every patch recipe.

                A16 Held Effort contains one settled loop with no finite attack. The existing
                drum exporter does not enable sample-loop flags automatically; enable looping
                from the start to the end of this WAV in the sampler for continuous sustain.
                The web audition's Repeat control also repeats this same buffer.

                These original simplified drivers and gestures use no samples or Yamaha data.
                Hardware loading and the owner's sonic verdict remain acceptance checks.
            """.trimIndent() + "\n")
            return linkedMapOf("name" to kit.name, "pads" to kit.pads.size,
                "mpc3Path" to exported.program.relativeTo(root).invariantSeparatorsPath,
                "mpc2Path" to File(samples, "${kit.name}.xpm").relativeTo(root).invariantSeparatorsPath,
                "recipePath" to sidecar.relativeTo(root).invariantSeparatorsPath,
                "format" to "Native MPC 3 track and MPC 2 XPM twin with shared PCM24 samples",
                "description" to "A01–A08 are independent C minor pentatonic reed notes; A09–A16 are the eight dry starting presets.",
                "pattern" to "MPC 3 carries a two-bar pentatonic clip; MPC 2 loads the same sixteen pads.",
                "holdPlayback" to "Held Effort imports one settled buffer; enable sampler looping for continuous hardware sustain.",
                "findings" to exported.findings.map { it.message })
        } finally {
            work.deleteRecursively()
        }
    }

    /** A curated offline pack small enough for the hosting provider's per-asset limit. */
    private fun bundle(root: File, manifest: Map<String, Any?>, rows: List<Map<String, Any?>>) {
        val selectedIds = setOf("hold_reed_rough", "hold_bow_rough", "velocity_reed_v025",
            "velocity_reed_v060", "velocity_reed_v100", "feedback_reed_on", "feedback_reed_off")
        val identityIds = rows.filter { it["group"] in setOf("PRESETS", "VOICES") }.map { "original_${it["id"]}" }.toSet()
        val selected = rows.filter { it["group"] in setOf("PRESETS", "VOICES") || it["id"] in selectedIds || it["id"] in identityIds }
        val selectedGroups = selected.map { it["group"] }.toSet()
        val curated = LinkedHashMap(manifest).apply {
            this["clips"] = selected
            this["groups"] = (manifest.getValue("groups") as List<*>).filter {
                (it as Map<*, *>)["id"] in selectedGroups
            }.map {
                if ((it as Map<*, *>)["id"] == "ORIGINAL") linkedMapOf(
                    "id" to "ORIGINAL", "title" to "Original model1 references",
                    "description" to "The original eight presets and six voice defaults preserve model1. The full original control sweep collection remains on the online page.",
                ) else it
            }
            @Suppress("UNCHECKED_CAST")
            val referenceSummary = LinkedHashMap(manifest.getValue("originalReferences") as Map<String, Any?>)
            referenceSummary["clips"] = selected.count { it["modelVersion"] == 1 }
            referenceSummary["verifiedWavHashes"] = selected.count { it["referenceVerification"] != null }
            this["originalReferences"] = referenceSummary
            this["quick"] = false
            this["completeMatrix"] = false
            this["curated"] = true
            this["packType"] = "curated-starter"
            this["bundlePath"] = null
            this["packDescription"] = manifest["bundleDescription"]
        }
        val curatedText = Json.write(json(curated))
        val includedAudio = selected.flatMap { row -> listOfNotNull(row["path"], row["rawPath"], row["matchedPath"]) }.toSet()
        val measurements = Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to "EMBOUCHURE", "modelVersion" to Embouchure.MODEL_VERSION,
            "curated" to true, "completeMatrix" to false,
            "clips" to selected.size, "auditionWavFiles" to includedAudio.size,
            "audioMeasure" to "Output-rate floats before PCM16 quantization; rawSourceMetrics precede the fixed raw export gain",
            "listeningVerdict" to "Owner listening verdict pending",
            "rows" to selected.map(::measurementRow),
        )))
        val destination = File(root, "embouchure-audition.zip")
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            val paths = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }
                .filter { it in includedAudio || it.startsWith("kit/") || it in setOf("index.html", "styles.css", "app.js", "manifest.json", "measurements.json") }
                .sorted().toList()
            paths.forEach { path ->
                val entry = ZipEntry(path)
                entry.time = 0
                zip.putNextEntry(entry)
                when (path) {
                    "manifest.json" -> zip.write((curatedText + "\n").toByteArray())
                    "measurements.json" -> zip.write((measurements + "\n").toByteArray())
                    "index.html" -> {
                        val html = File(root, path).readText()
                        val pattern = Regex("<script id=\"embouchure-manifest\" type=\"application/json\">[\\s\\S]*?</script>")
                        require(pattern.containsMatchIn(html)) { "generated page has no embedded manifest" }
                        val replacement = "<script id=\"embouchure-manifest\" type=\"application/json\">" + curatedText.replace("<", "\\u003c") + "</script>"
                        zip.write(pattern.replace(html) { replacement }.toByteArray())
                    }
                    else -> File(root, path).inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
        }
        require(destination.length() < 25L * 1024 * 1024) { "curated pack exceeds the hosting provider's 25 MiB per-asset limit" }
    }

    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value)
    private fun slug(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun voiceTitle(voice: EmbouchureVoice) = voice.name.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }
    private fun voiceDescription(voice: EmbouchureVoice) = when (voice) {
        EmbouchureVoice.REED -> "An articulate reed-like lead with a leaning pressure gesture."
        EmbouchureVoice.BRASS -> "A lip-like contact driver gives a muted brass-like lead."
        EmbouchureVoice.BASS -> "A root-bearing low note with controlled airflow roughness."
        EmbouchureVoice.WHISTLE -> "A clear high-register air/reed approximation; no separate jet-edge model."
        EmbouchureVoice.BOW -> "A restrained bowed role driven by friction and a smooth capture gesture."
        EmbouchureVoice.SOFT -> "A gentle pressure-driven body with a soft entry and relaxed release."
    }
    private fun presetDescription(name: String) = when (name) {
        "Leaning Reed" -> "A reed lead that leans into its note before releasing."
        "Muted Bell" -> "A compact, muted brass-like note with compliant lip contact."
        "Breath Pocket" -> "A low, root-bearing pocket of tone and restrained breath."
        "Soft Entry" -> "A gentle speaking gesture for a rounded pressure-driven tone."
        "Short Overblow" -> "A brief overpressure gesture, returning to the requested root."
        "Relaxed Bow" -> "A restrained bowed capture with a smooth finite sustain."
        "Narrow Air" -> "A clear whistle-like air/reed approximation with a narrow body."
        "Held Effort" -> "A settled sustained effort loop; the finite onset is omitted."
        else -> "Original dry Embouchure preset."
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> value.toDouble().let { if (it.isFinite()) JsonValue.Num(it) else JsonValue.Null }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { (k, v) -> k.toString() to json(v) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported audition JSON value ${value::class}")
    }
}

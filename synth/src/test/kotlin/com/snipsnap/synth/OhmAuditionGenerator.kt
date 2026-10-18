package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
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
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Revision-2 listening evidence, middle-range comparisons and the preserved model-1 audition. */
object OhmAuditionGenerator {
    private const val SEED = 731
    private const val VELOCITY = .8f
    private const val RATE = 44_100
    private const val PHRASE_GAIN = .5f
    private const val REVISION_MODEL = 2

    private data class Case(
        val id: String,
        val title: String,
        val description: String,
        val group: String,
        val changes: Map<String, Float> = emptyMap(),
        val midi: Int = 60,
        val velocity: Float = VELOCITY,
        val gesture: OhmGesture = OhmGesture.STEADY,
        val sweepMacro: String? = null,
        val sweepValue: Float? = null,
        val context: String? = null,
    )

    private data class Rendered(
        val spec: Case,
        val raw: Snip,
        val recipe: JsonValue,
        val midi: Int?,
        val events: List<Map<String, Any?>>? = null,
        val modelVersion: Int = 2,
        val matchedOverride: Snip? = null,
        val comparison: Map<String, Any?>? = null,
    )

    private fun baseline(): Map<String, Float> = Ohm.defaults() + mapOf(
        "TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f,
        "MEDDLING" to 0f, "COMMUNE" to 0f,
    )

    private fun cases(): List<Case> = buildList {
        add(Case("solo", "Solo", "The unaccompanied string: MEDDLING 0, COMMUNE 0.", "identities"))
        add(Case("argument", "Argument", "The same string with independent companions: MEDDLING .9, COMMUNE 0.",
            "identities", mapOf("MEDDLING" to .9f)))
        add(Case("communion", "Communion", "The same companions absorbed into the string: MEDDLING .9, COMMUNE 1.",
            "identities", mapOf("MEDDLING" to .9f, "COMMUNE" to 1f)))
        add(Case("quiet-influence", "Quiet influence", "A restrained absorbed influence: MEDDLING .25, COMMUNE 1.",
            "identities", mapOf("MEDDLING" to .25f, "COMMUNE" to 1f)))
        add(Case("gather", "Gather", "A saved finite gesture brings the companions in and gathers them into the string. This is rendered motion, not a live control recording.",
            "identities", mapOf("MEDDLING" to .9f, "COMMUNE" to 1f), gesture = OhmGesture.GATHER))

        for ((m, meddling) in listOf(0f, .45f, .9f).withIndex()) {
            for ((c, commune) in listOf(0f, .5f, 1f).withIndex()) {
                add(Case("matrix-m$m-c$c", "M ${short(meddling)} / C ${short(commune)}",
                    "MEDDLING ${short(meddling)}, COMMUNE ${short(commune)}. Note, touch, body, length, velocity and seed stay fixed.",
                    "matrix", mapOf("MEDDLING" to meddling, "COMMUNE" to commune)))
            }
        }
        for (macro in listOf("TOUCH", "BODY", "LENGTH", "MEDDLING", "COMMUNE"))
            for (step in 0..4) add(controlCase(macro, step, "active"))
        for (macro in listOf("TOUCH", "BODY", "LENGTH"))
            for (step in 0..4) add(controlCase(macro, step, "solo"))
        for (midi in listOf(48, 60, 72)) {
            for ((i, velocity) in listOf(.3f, .65f, 1f).withIndex()) {
                add(Case("note-$midi-v$i", "${noteName(midi)} / velocity ${short(velocity)}",
                    "One explicit note and excitation velocity. Raw playback preserves the level response; matched playback helps compare its color.",
                    "register", mapOf("MEDDLING" to .65f, "COMMUNE" to .5f), midi, velocity))
            }
        }
    }

    private fun controlCase(macro: String, step: Int, context: String): Case {
        val value = step / 4f
        val legacy = context == "active" && macro in listOf("TOUCH", "BODY", "LENGTH") && step % 2 == 0
        val id = if (legacy) "${macro.lowercase(Locale.ROOT)}-${step / 2}" else "r2-control-$macro-$context-$step"
        val relationship = if (context == "solo") mapOf("MEDDLING" to 0f, "COMMUNE" to 0f)
            else mapOf("MEDDLING" to .65f, "COMMUNE" to .5f)
        return Case(id, "$macro / ${short(value)} / $context",
            "Only $macro changes; ${if (context == "solo") "the companions are absent" else "the other relationship setting remains at MEDDLING .65 or COMMUNE .5"}.",
            "controls", relationship + (macro to value), sweepMacro = macro, sweepValue = value, context = context)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only an output directory" }
        val root = File(args.firstOrNull() ?: "../testkit/ohm-audition").canonicalFile
        require(!root.exists() || root.isDirectory) { "output must be a directory" }
        root.mkdirs()
        val assets = File(root, "assets").also { it.mkdirs() }
        File(assets, "ohm-first-listen.zip").delete() // Model 1 has its own complete original/ archive.
        val rendererSources = sourceHashes()
        OhmAuditionV1Generator.main(arrayOf(File(root, "original").path))
        val rendered = cases().map { render(it) }.toMutableList()
        rendered += renderPhrases()
        require(rendered.size == 66)
        rendered += renderComparisons(rendered)
        require(rendered.size == 79 && rendered.map { it.spec.id }.distinct().size == rendered.size)

        // One fixed headroom gain for the entire raw collection retains relative engine levels.
        // The internal peak is reported separately, so this cannot conceal an unstable source.
        val highestRawPeak = rendered.maxOf { peak(it.raw) }
        val rawExportGain = minOf(1.0, .98 / highestRawPeak.coerceAtLeast(1e-12))
        val clips = rendered.map { writeClip(root, it, rawExportGain) }
        check(sourceHashes() == rendererSources) { "OHM renderer sources changed while generating the audition" }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "OHM", "subtitle" to "SnipSnap · Revision 2",
            "description" to "Compare the control ranges with the original model, then hear the revised string alone and with its companions.",
            "modelVersion" to REVISION_MODEL, "renderedAt" to Instant.now().toString(),
            "sourceRevision" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "rendererSources" to rendererSources,
            "renderSource" to "Ohm.renderRaw",
            "sampleRate" to RATE, "channels" to 1, "format" to "PCM_16",
            "originalPage" to "original/index.html",
            "bundle" to "assets/ohm-focused-comparisons.zip", "rawExportGain" to rawExportGain,
            "bundleScope" to linkedMapOf("kind" to "focused", "includesAllClips" to false, "includesUi" to false,
                "clipIds" to focusedIds(rendered), "description" to "Five identities, eight revised range comparisons and five original/revised comparisons; full manifest and diagnostics document all 79 clips."),
            "notes" to listOf(
                "79 finite dry clips from the actual versioned OHM Kotlin renderers. No effects rack, external recordings or browser synthesis. The original model-1 audition is preserved separately.",
                "The first five comparisons use C4, velocity .8, seed 731, TOUCH .5, BODY .5 and LENGTH .65. Only the documented relationship or gesture changes.",
                "Raw WAVs precede the engine's output loudness processing and share one fixed PCM headroom gain of $rawExportGain. Metrics retain the unscaled raw-engine measurements.",
                "Matched WAVs use the repository AuditionLevel rule: loudest-200-ms mono RMS .03, with a .99 peak guard. This removes level as an easy preference cue; use raw playback to judge excitation dynamics.",
                "Joined comparisons match each sounding part separately before concatenating, with .30 seconds of silence between parts. They receive no second whole-file normalization. Their raw versions retain the common export gain.",
                "Each note starts fresh; exported samples do not share a live ensemble. GATHER saves a deterministic finite trajectory. None of these files claims a seamless sustain loop.",
                "The three phrases use the same six notes, onset times, per-note velocity and seeds. Their JSON scores preserve each constituent OHM patch and the .5 mixing gain.",
                "The focused ZIP includes the five identities, eight revised 25/75 range comparisons and five original/revised comparisons. Its full manifest also documents the separate sweep files available on the published page.",
                "Pitch, band-energy and envelope measurements document the rendered signal; they do not prove audible differences or listening acceptance.",
            ),
            "groups" to listOf(
                group("identities", "Start with the relationship", "Solo, independent companions, absorbed companions, quiet influence and the saved gathering gesture."),
                group("matrix", "MEDDLING × COMMUNE", "Nine static relationships. All other settings remain fixed."),
                group("controls", "Five controls, five steps", "All five controls at 0, .25, .5, .75 and 1 with active companions; TOUCH, BODY and LENGTH also have solo sweeps."),
                group("comparisons", "Hear the middle of the range", "25 then 75; or original 25/75 followed by revised 25/75. Every part has its own matched level and .30 seconds of separation."),
                group("register", "Notes and velocity", "C3, C4 and C5 at three excitation velocities. Raw files retain their dynamics."),
                group("phrases", "The same small melody", "Identical notes and timings under solo, argument and communion settings. Tails overlap naturally."),
            ),
            "clips" to clips,
        )
        writeJson(File(assets, "manifest.json"), manifest)
        writeJson(File(assets, "diagnostics.json"), linkedMapOf(
            "modelVersion" to REVISION_MODEL,
            "clipCount" to clips.size,
            "allFinite" to true,
            "rawExportGain" to rawExportGain,
            "highestUnscaledRawPeak" to highestRawPeak,
            "clips" to clips.map { linkedMapOf("id" to it["id"], "metrics" to it["metrics"], "hashes" to it["hashes"]) },
        ))
        File(root, "README.txt").writeText(readme(rawExportGain))
        copyPage(root)
        zip(root, File(assets, "ohm-focused-comparisons.zip"), clips.filter { it["id"].toString() in focusedIds(rendered) })
        println("OHM: wrote ${clips.size} raw/matched clips, recipes, manifest and focused comparison ZIP under ${root.path}")
    }

    private fun render(c: Case, model: Int = 2): Rendered {
        val macros = baseline() + c.changes
        val patch = OhmPatch(c.title, macros, c.midi, c.velocity, SEED, c.gesture, model)
        val recipe = PadRecipe(patch = patch)
        check(PadRecipe.fromJsonText(recipe.toJsonText()) == recipe) { "${c.id}: recipe fields changed on replay" }
        val raw = Ohm.renderRaw(macros, c.midi, c.velocity, SEED, c.gesture, model = model)
        checkRaw(c.id, raw)
        println("OHM render ${c.id}: ${short(raw.durationSeconds)} s")
        return Rendered(c, raw, recipe.toJsonValue(), c.midi, modelVersion = model)
    }

    private fun renderPhrases(): List<Rendered> {
        val onsets = listOf(0.0, .64, 1.28, 2.08, 2.72, 3.36)
        val notes = listOf(60, 63, 67, 65, 63, 60)
        return listOf(
            Triple("solo", 0f, 0f), Triple("argument", .9f, 0f), Triple("communion", .9f, 1f),
        ).map { (relationship, meddling, commune) ->
            val title = "${relationship.replaceFirstChar { it.uppercase() }} / melody"
            val c = Case("phrase-$relationship", title,
                "C4–E♭4–G4–F4–E♭4–C4, with identical note onsets and natural overlapping tails. MEDDLING ${short(meddling)}, COMMUNE ${short(commune)}.",
                "phrases", mapOf("MEDDLING" to meddling, "COMMUNE" to commune, "LENGTH" to .45f))
            val macros = baseline() + c.changes
            val patches = notes.mapIndexed { i, note ->
                OhmPatch("$title / note ${i + 1}", macros, note, VELOCITY, SEED,
                    OhmGesture.STEADY, REVISION_MODEL)
            }
            val sounds = patches.map { p -> Ohm.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture, model = p.model) }
            sounds.forEachIndexed { i, snip -> checkRaw("${c.id}/$i", snip) }
            val frames = sounds.indices.maxOf { (onsets[it] * RATE).roundToInt() + sounds[it].frameCount }
            val out = FloatArray(frames)
            for (i in sounds.indices) {
                val offset = (onsets[i] * RATE).roundToInt()
                for (j in sounds[i].samples.indices) out[offset + j] += sounds[i].samples[j] * PHRASE_GAIN
            }
            val events = patches.indices.map { i -> linkedMapOf<String, Any?>(
                "onsetSeconds" to onsets[i], "onsetFrames" to (onsets[i] * RATE).roundToInt(),
                "gain" to PHRASE_GAIN, "patch" to patches[i].toJsonValue(),
                "durationSeconds" to sounds[i].durationSeconds,
            ) }
            val recipe = json(linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "type" to "OHM_PHRASE", "modelVersion" to REVISION_MODEL,
                "name" to title, "sampleRate" to RATE, "channels" to 1,
                "source" to "Ohm.renderRaw", "events" to events,
                "frames" to frames, "loop" to false,
                "note" to "A score of independent complete OHM patches, not a single pad patch. Mix full raw renders at the saved frame offsets and gains; audition leveling follows the mix.",
            ))
            val raw = Snip(out, 1, RATE)
            checkRaw(c.id, raw)
            println("OHM render ${c.id}: ${short(raw.durationSeconds)} s")
            Rendered(c, raw, recipe, null, events)
        }
    }

    private fun renderComparisons(singles: List<Rendered>): List<Rendered> {
        val byId = singles.associateBy { it.spec.id }
        val macros = listOf("TOUCH", "BODY", "LENGTH", "MEDDLING", "COMMUNE")
        val ranges = mutableListOf<Rendered>()
        val revisions = mutableListOf<Rendered>()
        for (macro in macros) {
            val lowCase = controlCase(macro, 1, "active")
            val highCase = controlCase(macro, 3, "active")
            val low = byId.getValue(lowCase.id)
            val high = byId.getValue(highCase.id)
            ranges += joinComparison(macro, "active", "range", listOf(
                ComparisonPart("Revised 25", low, low.spec.id),
                ComparisonPart("Revised 75", high, high.spec.id),
            ))
            val originalLow = render(lowCase.copy(id = "original-${lowCase.id}"), model = 1)
            val originalHigh = render(highCase.copy(id = "original-${highCase.id}"), model = 1)
            revisions += joinComparison(macro, "active", "revision", listOf(
                ComparisonPart("Original 25", originalLow), ComparisonPart("Original 75", originalHigh),
                ComparisonPart("Revised 25", low, low.spec.id), ComparisonPart("Revised 75", high, high.spec.id),
            ))
        }
        for (macro in listOf("TOUCH", "BODY", "LENGTH")) {
            val low = byId.getValue(controlCase(macro, 1, "solo").id)
            val high = byId.getValue(controlCase(macro, 3, "solo").id)
            ranges += joinComparison(macro, "solo", "range", listOf(
                ComparisonPart("Revised 25", low, low.spec.id), ComparisonPart("Revised 75", high, high.spec.id),
            ))
        }
        return ranges + revisions
    }

    private data class ComparisonPart(val label: String, val rendered: Rendered, val clipId: String? = null)

    private fun joinComparison(control: String, context: String, kind: String, parts: List<ComparisonPart>): Rendered {
        val gapFrames = (RATE * .30).roundToInt()
        val frames = parts.sumOf { it.rendered.raw.frameCount } + gapFrames * (parts.size - 1)
        val raw = FloatArray(frames)
        val matched = FloatArray(frames)
        val events = mutableListOf<Map<String, Any?>>()
        val descriptions = mutableListOf<Map<String, Any?>>()
        var offset = 0
        for ((index, part) in parts.withIndex()) {
            val value = part.rendered
            val leveled = AuditionLevel.level(value.raw)
            value.raw.samples.copyInto(raw, offset)
            leveled.samples.copyInto(matched, offset)
            val patch = requireNotNull(value.recipe.obj()["patch"])
            val start = offset.toDouble() / RATE
            val end = (offset + value.raw.frameCount).toDouble() / RATE
            val parameters = baseline() + value.spec.changes
            events += linkedMapOf(
                "label" to part.label, "onsetFrames" to offset, "onsetSeconds" to start,
                "gain" to 1.0, "patch" to patch, "sourceRenderModel" to value.modelVersion,
                "durationFrames" to value.raw.frameCount,
                "matchedGain" to Loudness.of(leveled).toDouble() / Loudness.of(value.raw),
            )
            descriptions += linkedMapOf(
                "label" to part.label, "clipId" to part.clipId,
                "startSeconds" to start, "endSeconds" to end,
                "modelVersion" to value.modelVersion, "macros" to parameters,
                "metrics" to OhmAuditionMetrics.analyze(value.raw, 440.0 * 2.0.pow((60 - 69) / 12.0)),
                "matchedLoudness" to Loudness.of(leveled),
            )
            offset += value.raw.frameCount + if (index < parts.lastIndex) gapFrames else 0
        }
        check(offset == frames)
        val id = "r2-$kind-$control-$context"
        val c = Case(id, "$control / ${if (kind == "revision") "original and revised" else "25 then 75"} / $context",
            if (kind == "revision") "Original 25, original 75, revised 25, revised 75. Every part uses the same note, seed, velocity and unchanged controls; each is matched separately."
            else "Revised 25 followed by revised 75, with every other control unchanged. Each part is matched separately.",
            "comparisons", if (context == "solo") mapOf("MEDDLING" to 0f, "COMMUNE" to 0f)
                else mapOf("MEDDLING" to .65f, "COMMUNE" to .5f),
            sweepMacro = control, context = context)
        val comparison = linkedMapOf<String, Any?>(
            "kind" to kind, "control" to control, "context" to context,
            "gapSeconds" to .30, "parts" to descriptions,
        )
        val recipe = json(linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "type" to "OHM_COMPARISON", "name" to c.title,
            "source" to "Ohm.renderRaw", "sampleRate" to RATE, "channels" to 1,
            "frames" to frames, "gapFrames" to gapFrames, "events" to events,
            "rawPolicy" to "Render each patch with its saved sourceRenderModel, concatenate at onsetFrames, then apply the manifest's one common rawExportGain.",
            "matchedPolicy" to "AuditionLevel.level each unscaled raw part independently, concatenate at onsetFrames, and do not normalize the joined result again.",
            "loop" to false,
        ))
        val snip = Snip(raw, 1, RATE)
        checkRaw(c.id, snip)
        return Rendered(c, snip, recipe, 60, events, REVISION_MODEL, Snip(matched, 1, RATE), comparison)
    }

    private fun focusedIds(rendered: List<Rendered>): List<String> =
        listOf("solo", "argument", "communion", "quiet-influence", "gather") +
            rendered.filter { it.comparison != null }.map { it.spec.id }

    private fun writeClip(root: File, rendered: Rendered, rawExportGain: Double): Map<String, Any?> {
        val (c, raw, recipe, midi, events) = rendered
        val rawFile = gain(raw, rawExportGain)
        val matched = rendered.matchedOverride ?: AuditionLevel.level(raw)
        check(peak(rawFile) <= .980001 && peak(matched) <= .990001) { "${c.id}: exported PCM would clip" }
        val rawPath = "assets/audio/${c.id}-raw.wav"
        val matchedPath = "assets/audio/${c.id}-matched.wav"
        val recipePath = "assets/recipes/${c.id}.json"
        WavWriter.write(File(root, rawPath), rawFile, WavWriter.BitDepth.PCM_16)
        WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
        val decodedRaw = validateWav(File(root, rawPath), rawFile)
        val decodedMatched = validateWav(File(root, matchedPath), matched)
        File(root, recipePath).also { it.parentFile.mkdirs() }.writeText(Json.write(recipe))
        val target = midi?.let { 440.0 * 2.0.pow((it - 69) / 12.0) }
        val pitch = if (midi != null) Pitch.detect(raw) else null
        val metrics = linkedMapOf<String, Any?>(
            "finite" to raw.samples.all { it.isFinite() }, "frames" to raw.frameCount,
            "durationSeconds" to raw.durationSeconds,
            "rawPeak" to peak(raw), "rawRms" to rms(raw), "rawLoudness" to Loudness.of(raw),
            "rawDc" to raw.samples.sumOf { it.toDouble() } / raw.samples.size,
            "rawExportGain" to rawExportGain, "rawExportPeak" to peak(rawFile),
            "rawExportRms" to rms(rawFile), "rawExportLoudness" to Loudness.of(rawFile),
            "matchedPeak" to peak(matched), "matchedRms" to rms(matched),
            "matchedLoudness" to Loudness.of(matched),
            "matchedGain" to if (rendered.matchedOverride == null) Loudness.of(matched).toDouble() / Loudness.of(raw) else null,
            "decodedRawPeak" to peak(decodedRaw), "decodedMatchedPeak" to peak(decodedMatched),
            "pcmValidated" to true,
            "targetHz" to target, "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
            "pitchCents" to if (target != null && pitch != null) 1200.0 * ln(pitch.hz / target) / ln(2.0) else null,
            "features" to OhmAuditionMetrics.analyze(raw, target),
        )
        val hashes = linkedMapOf("rawWavSha256" to sha(File(root, rawPath)),
            "matchedWavSha256" to sha(File(root, matchedPath)), "recipeSha256" to sha(File(root, recipePath)))
        return linkedMapOf(
            "id" to c.id, "title" to c.title, "description" to c.description, "group" to c.group,
            "macros" to (baseline() + c.changes), "midi" to midi, "noteLabel" to midi?.let(::noteName),
            "velocity" to c.velocity, "seed" to SEED, "gesture" to c.gesture.name,
            "modelVersion" to rendered.modelVersion, "context" to c.context,
            "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
            "recipeType" to when { rendered.comparison != null -> "OHM_COMPARISON"; events == null -> "PadRecipe"; else -> "OHM_PHRASE" },
            "seconds" to raw.durationSeconds, "metrics" to metrics, "hashes" to hashes,
            "renderSource" to "Ohm.renderRaw",
            "sweepMacro" to c.sweepMacro, "sweepValue" to c.sweepValue,
            "events" to events, "waveform" to waveform(matched.samples),
            "comparison" to rendered.comparison,
            "matchedLevelPolicy" to if (rendered.matchedOverride != null) "AuditionLevel per part before concatenation" else "AuditionLevel once for the complete clip",
        )
    }

    private fun checkRaw(id: String, raw: Snip) {
        check(raw.channels == 1 && raw.sampleRate == RATE && raw.samples.isNotEmpty()) { "$id: not mono 44.1 kHz" }
        check(raw.samples.all { it.isFinite() }) { "$id: non-finite raw samples" }
        check(Loudness.of(raw) > 1e-8f) { "$id: silent raw render" }
    }

    private fun validateWav(file: File, expected: Snip): Snip {
        val decoded = WavReader.read(file)
        check(decoded.channels == 1 && decoded.sampleRate == RATE && decoded.frameCount == expected.frameCount) {
            "${file.name}: PCM format or length changed on export"
        }
        check(decoded.samples.all { it.isFinite() } && peak(decoded) < 1.0) { "${file.name}: invalid or clipped PCM" }
        check(Loudness.of(decoded) > 1e-8f) { "${file.name}: silent PCM" }
        // Writer/reader use their existing signed-PCM conventions; allow their scale difference
        // plus one quantization step, while catching truncation, wrong gain and damaged samples.
        check(expected.samples.indices.all { abs(decoded.samples[it] - expected.samples[it]) < 2f / 32768f }) {
            "${file.name}: decoded samples differ by more than PCM16 quantization"
        }
        return decoded
    }

    private fun group(id: String, title: String, description: String) =
        linkedMapOf("id" to id, "title" to title, "description" to description)

    private fun copyPage(root: File) {
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val stream = OhmAuditionGenerator::class.java.getResourceAsStream("/ohm-audition/$name") ?: continue
            File(root, name).outputStream().use { output -> stream.use { it.copyTo(output) } }
        }
    }

    private fun zip(root: File, output: File, clips: List<Map<String, Any?>>) {
        // Enumerate inputs before opening the archive. Never walk an existing archive into itself.
        val paths = mutableSetOf("assets/manifest.json", "assets/diagnostics.json", "README.txt")
        for (clip in clips) for (field in listOf("raw", "matched", "recipe")) paths += clip.getValue(field).toString()
        ZipOutputStream(output.outputStream().buffered()).use { archive ->
            for (path in paths.sorted()) {
                require(path != output.relativeTo(root).invariantSeparatorsPath)
                archive.putNextEntry(ZipEntry(path).apply { time = 0L })
                File(root, path).inputStream().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }
    }

    private fun readme(rawExportGain: Double) = """
        OHM / SnipSnap revision 2

        79 finite dry mono 44.1 kHz PCM16 comparisons from the versioned Kotlin renderers.
        Begin with the joined original 25 / original 75 / revised 25 / revised 75 comparisons.
        Five-step active sweeps cover every control. TOUCH, BODY and LENGTH also have solo sweeps.
        The original 35-clip model-1 audition is preserved under original/index.html.
        No seamless loop, shared live ensemble, phone or MPC acceptance is claimed.

        This focused ZIP includes 18 clips: five identities, eight revised range comparisons and
        five original/revised comparisons. The full manifest documents all 79 clips, including
        separate sweep audio available on the published page; those extra audio files are not
        included in the focused ZIP. It contains audio, recipes and provenance, without an offline
        player. The original model-1 page has its own complete download.

        assets/manifest.json gives every clip's settings, paths, measured levels and SHA-256 hashes.
        assets/recipes/ stores ordinary PadRecipe JSON for the single notes.
        Standard PadRecipe.render() returns the engine's normal leveled pad output; the audition
        deliberately records an earlier point. To reproduce a downloaded single-note WAV:
          1. Read the JSON with PadRecipe.fromJsonText and take recipe.patch as OhmPatch.
          2. Call Ohm.renderRaw(patch.macros, patch.midi, patch.velocity, patch.seed, patch.gesture,
             model = patch.model). Never substitute the current default model for the saved model.
          3. For the raw WAV, multiply every sample by rawExportGain from the manifest.
             For the matched WAV, pass that unscaled raw Snip to AuditionLevel.level instead.
          4. Write mono 44.1 kHz PCM16 with WavWriter, using the recorded model and renderer sources.
        The three phrase JSON files store explicit frame offsets, gains and each complete OHM patch.
        Their source is Ohm.renderRaw; PHRASE_GAIN is .5, then matching follows the summed audio.

        Joined OHM_COMPARISON recipes contain each complete patch, sourceRenderModel and exact
        onsetFrames. Raw parts concatenate unchanged with .30-second gaps, then use rawExportGain.
        Matched parts each pass through AuditionLevel before concatenation; the joined file is
        never normalized again. Part boundaries exclude the silent gaps and remain in the manifest.

        Raw audio precedes output loudness processing. One common raw PCM gain: $rawExportGain.
        Matched files use AuditionLevel: loudest 200 ms RMS .03, with a .99 peak guard.
        In joined comparisons, that rule applies independently to each sounding part.
        Raw-engine peaks and dynamics remain in the manifest before that common export gain.

        Play these WAVs in an audio player, or use the hosted page for all 79 comparisons and
        controls. This focused bundle intentionally omits the hosted page's UI and extra audio.
        Listening approval remains open.
    """.trimIndent() + "\n"

    private fun peak(snip: Snip): Double = snip.samples.maxOf { abs(it).toDouble() }
    private fun rms(snip: Snip): Double = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
    private fun gain(snip: Snip, gain: Double) = Snip(FloatArray(snip.samples.size) { (snip.samples[it] * gain).toFloat() }, snip.channels, snip.sampleRate)
    private fun short(value: Number): String = "%.2f".format(Locale.ROOT, value.toDouble()).trimEnd('0').trimEnd('.')
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")[midi % 12] + (midi / 12 - 1)
    private fun waveform(samples: FloatArray): List<Double> = (0 until 96).map { bin ->
        val start = bin * samples.size / 96
        val end = ((bin + 1) * samples.size / 96).coerceAtLeast(start + 1)
        (start until end).maxOf { abs(samples[it]).toDouble() }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun sourceHashes(): Map<String, String> {
        val fromGit = File(git("rev-parse", "--show-toplevel"))
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        val repo = fromGit.takeIf { File(it, "synth/src").isDirectory }
            ?: cwd.parentFile.takeIf { File(it, "synth/src").isDirectory }
            ?: cwd.takeIf { File(it, "synth/src").isDirectory }
            ?: error("cannot locate the SnipSnap renderer sources")
        return listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Ohm.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmV1.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmV2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OhmPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OhmAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OhmAuditionV1Generator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OhmAuditionMetrics.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        ).associateWith { path -> sha(File(repo, path)) }
    }
    private fun git(vararg args: String): String = runCatching {
        ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start().let { process ->
            val out = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0)
            out
        }
    }.getOrDefault("unavailable")
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)))
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported OHM audition manifest value ${value::class.simpleName}")
    }
}

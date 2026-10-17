package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Actual dry STOOP engine renders, not a browser synthesizer. Factory recipes have a note and a
 * short independently articulated phrase; focused comparisons expose controls, velocity and loops.
 * All listening judgments remain provisional. Run :synth:generateStoopAudition; --quick is a smoke
 * pack, and --refresh-page rebuilds the local page without changing existing WAV evidence.
 */
object StoopAuditionGenerator {
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val timbral = listOf("TOUCH", "COLOR", "SHADOW", "WEAR", "LENGTH")
    private val sections = linkedMapOf(
        "presets" to ("The patch shelf" to "Every saved factory recipe, dry. Hear a single note, then a four-note pocket phrase with independent note articulations."),
        "register" to ("Across the keys" to "Six families at low, middle and high notes; quiet and medium root gestures beside full velocity. All clips use the same listening level."),
        "sweeps" to ("Move one control" to "Bass and tine representatives at 0, .25, .5, .75 and 1. Other controls stay at the saved defaults; timing and chord recipes stay fixed."),
        "interactions" to ("Two controls together" to "TOUCH × COLOR, TOUCH × WEAR, SHADOW × LENGTH and COLOR × WEAR. Four corners per pair, on bass and tine."),
        "shadow" to ("A quieter answer" to "Main, isolated answer and combined, before recording character. Parts retain the combined reference gain so the answer's actual balance can be heard."),
        "wear" to ("Direct / worn" to "WEAR 0 and .85 at matched level for every family. Compare body, pitch and attack; no added rack effect or noise bed."),
        "velocity" to ("Gesture and level" to "The same quiet, medium and strong excitation twice: raw preserves energy, matched exposes articulation. A common raw-group gain prevents PCM clipping without normalizing each note."),
        "chords" to ("Know the voicing" to "Explicit interval recipes transpose with the root. No inferred key, scale or automatic progression."),
        "hold" to ("Let it run" to "Settled loop-only exports: stress WAVs contain four exact wraps, while focused held-articulation clips contain one cycle with Repeat on. Compare main TOUCH and isolated companion articulation across all six families; attack and delayed onset are absent."),
        "edges" to ("At the edges" to "All five timbral controls at maximum, HOLD off, one render per family. These are stress cases rather than factory defaults."),
    )
    private val familyCopy = mapOf(
        StoopVoice.BASS to "Round body, root weight and a brighter bite.",
        StoopVoice.TINE to "Warm struck keys and a restrained bell edge.",
        StoopVoice.CLAV to "A compact picked gesture with a pitched body.",
        StoopVoice.ORGAN to "Smooth harmonic registration and clipped stabs.",
        StoopVoice.LEAD to "Rounded, nasal and whistle-like melodic voices.",
        StoopVoice.CHORD to "Small explicit stacks that follow your root.",
    )

    private data class ReferenceBank(
        val root: File, val manifest: Map<String, JsonValue>, val inputBytes: ByteArray,
        val publishedBytes: ByteArray, val factory: List<Map<String, Any?>>,
    ) {
        fun example(voice: String, name: String, phrase: Boolean) = factory.single {
            it["voice"] == voice && it["preset"] == name && (it["phrase"] != null) == phrase
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val positional = args.filterNot { it.startsWith("--") }
        val baselineArgs = args.filter { it.startsWith("--baseline-dir=") }
        require(positional.size <= 1 && baselineArgs.size <= 1 && args.filter { it.startsWith("--") }.all {
            it in listOf("--quick", "--refresh-page") || it.startsWith("--baseline-dir=")
        }) {
            "supply one output directory, optionally --quick, --refresh-page or --baseline-dir=<directory>"
        }
        require(!(("--quick" in args) && ("--refresh-page" in args)))
        val root = File(positional.firstOrNull() ?: "../testkit/stoop-audition")
        if ("--refresh-page" in args) {
            val manifest = File(root, "manifest.json").readText()
            val parsed = Json.parse(manifest).obj()
            require(parsed.getValue("engine").str() == "STOOP")
            for (entry in parsed.getValue("clips").arr()) {
                val file = File(root, entry.obj().getValue("path").str()).canonicalFile
                require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile)
            }
            writePage(root, manifest)
            println("refreshed STOOP page; rendered evidence unchanged")
            return
        }
        val quick = "--quick" in args
        require(!quick || baselineArgs.isEmpty()) { "--quick and --baseline-dir are separate modes" }
        val reference = baselineArgs.singleOrNull()?.substringAfter('=')?.also { require(it.isNotBlank()) }
            ?.let { readReferenceBank(File(it), root) }
        root.mkdirs()
        val clips = mutableListOf<Map<String, Any?>>()
        val started = System.nanoTime()
        val initialProvenance = provenance()
        val factory = StoopPresets.all()
        require(factory.isNotEmpty())
        if (reference != null) {
            require(factory.size == 48)
            for (patch in factory) {
                val saved = reference.example(patch.voice.name, patch.name, false)
                val recipe = StoopPatch.fromJsonValue(json(saved.getValue("patchRecipe"))) as StoopPatch
                require(patch.midi == recipe.midi && patch.velocity == recipe.velocity) {
                    "${patch.name}: before/after requires the same saved root and velocity"
                }
            }
        }
        val representatives = StoopVoice.entries.associateWith { voice -> StoopPresets.forVoice(voice).first() }
        var renderNanos = 0L
        val loopEvidence = mutableMapOf<StoopPatch, Map<String, Any?>>()

        fun raw(patch: StoopPatch): Snip {
            val t = System.nanoTime()
            val result = if (Stoop.isLoop(patch.macros["HOLD"] ?: 0f)) {
                val probe = Stoop.renderProbe(patch, normalize = false)
                require(probe.seamError.isFinite() && probe.seamError < 1e-3) { "${patch.name}: HOLD seam ${probe.seamError} failed" }
                loopEvidence[patch] = mapOf("seamError" to probe.seamError, "prerollFrames" to probe.prerollFrames,
                    "sourceFrequencies" to probe.sourceFrequencies, "maxPitchAdjustmentCents" to probe.maxPitchAdjustmentCents,
                    "seamMethod" to "Keys.seamError on actual 256-frame continuous pre-cut history and kept loop")
                Snip(probe.samples, 1, 44_100)
            } else Stoop.renderRaw(patch)
            renderNanos += System.nanoTime() - t
            require(result.channels == 1 && result.sampleRate == 44_100)
            require(result.samples.isNotEmpty() && result.samples.all { it.isFinite() }) { "invalid ${patch.name} render" }
            return result
        }
        fun save(
            id: String, title: String, description: String, category: String, group: String,
            patch: StoopPatch, rendered: Snip = raw(patch), level: String = "matched",
            referenceGain: Float? = null, extra: Map<String, Any?> = emptyMap(),
        ) {
            require(clips.none { it["id"] == id }) { "duplicate audition id $id" }
            val output = if (referenceGain == null) AuditionLevel.level(rendered) else
                Snip(FloatArray(rendered.samples.size) { rendered.samples[it] * referenceGain }, 1, 44_100)
            require(output.samples.all { it.isFinite() } && output.peak() <= 1f) { "$id cannot be exported safely" }
            val path = "clips/$id.wav"
            val file = WavWriter.write(File(root, path), output, WavWriter.BitDepth.PCM_16)
            val evidence = linkedMapOf<String, Any?>(
                "id" to id, "title" to title, "description" to description, "category" to category,
                "group" to group, "voice" to patch.voice.name, "preset" to patch.name, "path" to path,
                "midi" to patch.midi, "note" to noteName(patch.midi), "velocity" to patch.velocity,
                "macros" to patch.macros, "patchRecipe" to patch.toJsonValue(), "source" to patch.toJsonValue().obj()["source"],
                "shadowInterval" to patch.shadowInterval, "shadowDelayMs" to patch.shadowDelayMs,
                "shadowArticulation" to patch.shadowArticulation, "shadowReplies" to patch.shadowReplies,
                "chordIntervals" to patch.chordIntervals, "level" to level,
                "loop" to false, "durationSeconds" to output.durationSeconds,
                "rawPeak" to rendered.peak(), "rawLoudness" to Loudness.of(rendered),
                "rawRms" to rms(rendered.samples), "rawDc" to rendered.samples.average(),
                "exportPeak" to output.peak(), "exportLoudness" to Loudness.of(output),
                "sha256" to sha256(file.readBytes()), "bytes" to file.length(),
                "bankVersion" to "revised-candidate", "feedbackId" to "revised-candidate:${sha256(file.readBytes())}",
            )
            StoopPresets.metadataFor(patch)?.let { metadata -> evidence.putAll(mapOf(
                "presetCategory" to metadata.category, "intendedRegister" to listOf(metadata.intendedRegister.first, metadata.intendedRegister.last),
                "articulationTags" to metadata.articulationTags.toList(), "intervalDescription" to metadata.intervalDescription,
                "presetDescription" to metadata.description,
            )) }
            evidence.putAll(extra)
            loopEvidence[patch]?.let { evidence.putAll(it) }
            clips += evidence
            if (clips.size % 24 == 0) println("STOOP: ${clips.size} clips rendered")
        }

        // Full factory defaults are preserved, including deliberate shadow and wear. Empty FX.
        for ((index, original) in factory.withIndex()) {
            if (quick && original != representatives[original.voice]) continue
            val patch = original
            val id = "preset_${(index + 1).toString().padStart(2, '0')}_${slug(patch.name)}"
            val held = Stoop.isLoop(patch.macros["HOLD"] ?: 0f)
            save("${id}_note", patch.name, "Saved dry recipe · ${noteName(patch.midi)} · velocity 1." + if (held) " Settled held region, no attack." else "",
                "presets", patch.name, patch, extra = mapOf("loop" to held))
            if (!quick) {
                val originalPhrase = reference?.example(patch.voice.name, patch.name, true)?.get("phrase") as? List<*>
                val phraseEvents = originalPhrase?.map { it as Map<*, *> }
                val offsets = phraseEvents?.map { (it["midi"] as Number).toInt() - patch.midi } ?: listOf(0, 3, 7, 0)
                val energies = phraseEvents?.map { (it["velocity"] as Number).toFloat() } ?: listOf(1f, .65f, .82f, .9f)
                val onsets = phraseEvents?.map { (it["onsetSeconds"] as Number).toFloat() } ?: listOf(0f, .34f, .68f, 1.02f)
                val finite = patch.copy(macros = patch.macros + ("HOLD" to 0f))
                val notes = offsets.mapIndexed { i, offset -> raw(finite.copy(midi = patch.midi + offset, velocity = energies[i])) }
                val n = notes.indices.maxOf { (onsets[it] * 44_100).roundToInt() + notes[it].samples.size }
                val mix = FloatArray(n)
                for (i in notes.indices) {
                    val onset = (onsets[i] * 44_100).roundToInt()
                    for (frame in notes[i].samples.indices) mix[onset + frame] += notes[i].samples[frame]
                }
                save("${id}_phrase", "${patch.name} · pocket phrase", "Four independently rendered notes, root / +3 / +7 / root. HOLD off for finite gestures; no external effects or persistent delay state.",
                    "presets", patch.name, finite, Snip(mix, 1, 44_100), extra = mapOf("phrase" to offsets.indices.map {
                        mapOf("midi" to patch.midi + offsets[it], "velocity" to energies[it], "onsetSeconds" to onsets[it])
                    }))
            }
        }

        for ((voice, preset) in representatives) {
            val base = preset.copy(macros = preset.macros + ("HOLD" to 0f))
            val tag = voice.name.lowercase()
            if (!quick) for (offset in listOf(-12, 0, 12)) {
                val patch = base.copy(midi = (base.midi + offset).coerceIn(24, 96))
                save("${tag}_note_${patch.midi}", "${voice.name} · ${noteName(patch.midi)}", "Saved default at full velocity; compare register without adding effects.", "register", voice.name, patch)
            }
            if (!quick) for (velocity in listOf(.25f, .6f)) {
                save("${tag}_gesture_${percent(velocity)}", "${voice.name} · velocity ${fmt(velocity)}", "Same root and articulation; matched listening level exposes the gesture's spectrum.", "register", voice.name, base.copy(velocity = velocity))
            }
            for (wear in if (quick) listOf(0f) else listOf(0f, .85f)) {
                save("${tag}_wear_${percent(wear)}", "${voice.name} · ${if (wear == 0f) "direct" else "worn"}", "WEAR ${fmt(wear)}; companions retain the saved recipe. Matched level.", "wear", voice.name, base.copy(macros = base.macros + ("WEAR" to wear)))
            }
            val hold = base.copy(macros = base.macros + mapOf("HOLD" to 1f, "SHADOW" to .7f, "WEAR" to .8f, "COLOR" to .8f))
            val cycle = raw(hold)
            val repeated = Snip(FloatArray(cycle.samples.size * 4) { cycle.samples[it % cycle.samples.size] }, 1, 44_100)
            save("${tag}_hold", "${voice.name} · four wraps", "Settled companion, bright COLOR and substantial WEAR. Four exact wraps in the WAV; enable Repeat to keep listening.",
                "hold", voice.name, hold, repeated, extra = mapOf("loop" to true, "cycleFrames" to cycle.samples.size, "cyclesInFile" to 4,
                    "cycleSeconds" to cycle.durationSeconds, "boundaryStep" to abs(cycle.samples.first() - cycle.samples.last())))
            if (!quick) save("${tag}_all_high", "${voice.name} · all high", "TOUCH, COLOR, SHADOW, WEAR and LENGTH 1; finite articulation, HOLD off.", "edges", voice.name,
                base.copy(macros = base.macros + timbral.associateWith { 1f }))
        }

        if (!quick) {
            for ((voice, preset) in representatives) {
                val tag = voice.name.lowercase()
                val held = preset.copy(shadowInterval = 0, shadowReplies = 1, macros = preset.macros + mapOf(
                    "HOLD" to 1f, "TOUCH" to .5f, "COLOR" to .5f, "LENGTH" to .5f, "SHADOW" to 0f, "WEAR" to 0f))
                for (touch in listOf(0f, .5f, 1f)) {
                    save("${tag}_hold_touch_${percent(touch)}", "${voice.name} · held TOUCH ${fmt(touch)}",
                        "Settled main voice, TOUCH ${fmt(touch)}, no shadow and no wear. COLOR and LENGTH stay at .5. Matched listening level; compare the sustained articulation, with the attack excluded.",
                        "hold", "${voice.name} · held main TOUCH", held.copy(macros = held.macros + ("TOUCH" to touch)),
                        extra = mapOf("loop" to true, "branch" to "main", "cyclesInFile" to 1))
                }
                val companions = listOf(0f, .5f, 1f).associateWith { articulation ->
                    val patch = held.copy(shadowArticulation = articulation, macros = held.macros + ("SHADOW" to 1f))
                    val t = System.nanoTime()
                    val probe = Stoop.renderProbe(patch, normalize = false)
                    renderNanos += System.nanoTime() - t
                    require(probe.seamError.isFinite() && probe.seamError < 1e-3) { "${voice.name} held companion reference seam failed" }
                    patch to probe
                }
                val reference = Snip(companions.getValue(.5f).second.samples, 1, 44_100)
                val commonGain = Loudness.of(AuditionLevel.level(reference)) / Loudness.of(reference).coerceAtLeast(1e-9f)
                for ((articulation, pair) in companions) {
                    val (patch, probe) = pair
                    require(probe.previousShadow.size >= 256) { "held shadow history is missing" }
                    val shadowSeam = Keys.seamError(probe.previousShadow + probe.shadow, probe.previousShadow.size)
                    require(shadowSeam.isFinite() && shadowSeam < 1e-3) { "${voice.name} isolated held companion seam $shadowSeam failed" }
                    save("${tag}_hold_shadow_articulation_${percent(articulation)}", "${voice.name} · held answer ${fmt(articulation)}",
                        "Isolated settled companion, saved shadow articulation ${fmt(articulation)}. SHADOW 1, main TOUCH .5, COLOR .5, LENGTH .5 and WEAR 0. All three companion clips retain the same full-voice reference gain from articulation .5, preserving their quieter actual balance.",
                        "hold", "${voice.name} · held companion articulation", patch, Snip(probe.shadow, 1, 44_100),
                        level = "reference", referenceGain = commonGain, extra = mapOf(
                            "loop" to true, "branch" to "shadow", "cyclesInFile" to 1,
                            "commonReferenceGain" to commonGain, "referenceShadowArticulation" to .5f,
                            "seamError" to shadowSeam, "seamMethod" to "Keys.seamError on actual 256-frame continuous pre-cut shadow history and isolated kept shadow loop",
                            "referenceCombinedSeamError" to probe.seamError, "prerollFrames" to probe.prerollFrames,
                            "sourceFrequencies" to probe.sourceFrequencies, "maxPitchAdjustmentCents" to probe.maxPitchAdjustmentCents,
                        ) + shadowEvidence(probe))
                }
            }
            for (voice in listOf(StoopVoice.BASS, StoopVoice.TINE)) {
                val base = representatives.getValue(voice).copy(macros = representatives.getValue(voice).macros + ("HOLD" to 0f))
                val tag = voice.name.lowercase()
                for (macro in timbral) for (value in steps) {
                    save("${tag}_sweep_${macro.lowercase()}_${percent(value)}", "$macro ${fmt(value)}", "${voice.name}: only $macro changes; all companions use ${base.name}'s saved defaults.",
                        "sweeps", "${voice.name} · $macro", base.copy(macros = base.macros + (macro to value)))
                }
                for ((a, b) in listOf("TOUCH" to "COLOR", "TOUCH" to "WEAR", "SHADOW" to "LENGTH", "COLOR" to "WEAR")) {
                    for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                        save("${tag}_${a.lowercase()}${percent(x)}_${b.lowercase()}${percent(y)}", "$a ${fmt(x)} · $b ${fmt(y)}", "${voice.name}: pair corners, saved companions unchanged.",
                            "interactions", "${voice.name} · $a × $b", base.copy(macros = base.macros + mapOf(a to x, b to y)))
                    }
                }
                val rendered = listOf(.25f, .6f, 1f).associateWith { raw(base.copy(velocity = it)) }
                val rawGain = minOf(1f, .9f / rendered.values.maxOf { it.peak() }.coerceAtLeast(1e-9f))
                for ((velocity, snip) in rendered) {
                    val patch = base.copy(velocity = velocity)
                    save("${tag}_velocity_raw_${percent(velocity)}", "Raw · velocity ${fmt(velocity)}", "Common group gain ${fmt(rawGain)}; note energy is preserved between velocities.", "velocity", "${voice.name} · raw / matched", patch, snip,
                        level = "raw", referenceGain = rawGain, extra = mapOf("commonRawGain" to rawGain))
                    save("${tag}_velocity_matched_${percent(velocity)}", "Matched · velocity ${fmt(velocity)}", "Exactly the same synthesis, matched by the shared audition level rule.", "velocity", "${voice.name} · raw / matched", patch, snip)
                }
            }
            for (voice in listOf(StoopVoice.BASS, StoopVoice.TINE, StoopVoice.CLAV)) {
                val patch = representatives.getValue(voice).let { it.copy(macros = it.macros + mapOf("WEAR" to 0f, "SHADOW" to 1f, "HOLD" to 0f)) }
                val t = System.nanoTime()
                val probe = Stoop.renderProbe(patch, normalize = false)
                renderNanos += System.nanoTime() - t
                val combined = Snip(probe.samples, 1, 44_100)
                val main = Snip(probe.main.copyOf().also { Dsp.fadeTail(it) }, 1, 44_100)
                val answer = Snip(probe.shadow.copyOf().also { Dsp.fadeTail(it) }, 1, 44_100)
                val commonGain = Loudness.of(AuditionLevel.level(combined)) / Loudness.of(combined).coerceAtLeast(1e-9f)
                for ((branch, rendered) in listOf("main" to main, "answer" to answer, "combined" to combined)) {
                    save("${voice.name.lowercase()}_shadow_$branch", "${voice.name} · $branch", when (branch) {
                        "main" -> "Isolated main source, before recording character; combined-reference listening gain."
                        "answer" -> "Isolated newly articulated answering source, before recording character. Combined-reference gain preserves its quieter actual balance."
                        else -> "Main plus newly articulated answer; WEAR 0. Same reference gain as both isolated parts."
                    }, "shadow", "${voice.name} · main / answer / combined", patch, rendered, level = "reference", referenceGain = commonGain,
                        extra = mapOf("branch" to branch, "commonReferenceGain" to commonGain) + shadowEvidence(probe))
                }
            }
            val answering = representatives.getValue(StoopVoice.BASS)
            for (interval in listOf(0, 7, 12)) for (delay in listOf(25f, 250f)) {
                val patch = answering.copy(shadowInterval = interval, shadowDelayMs = delay,
                    macros = answering.macros + mapOf("SHADOW" to 1f, "LENGTH" to 0f, "WEAR" to 0f, "HOLD" to 0f))
                val t = System.nanoTime()
                val probe = Stoop.renderProbe(patch, normalize = false)
                renderNanos += System.nanoTime() - t
                require(probe.events.all { it.frame + it.durationFrames <= probe.samples.size }) { "scheduled answer was truncated" }
                save("shadow_interval_${interval}_delay_${delay.roundToInt()}", "Answer +$interval st · ${delay.roundToInt()} ms", "Root, fifth or octave answer at the delay endpoints. LENGTH 0; the actual reply and its decay are retained.",
                    "shadow", "Intervals / timing", patch, Snip(probe.samples, 1, 44_100), extra = shadowEvidence(probe))
            }
            val double = representatives.getValue(StoopVoice.CLAV).let { it.copy(shadowReplies = 2, shadowDelayMs = 250f,
                macros = it.macros + mapOf("SHADOW" to 1f, "LENGTH" to 0f, "WEAR" to 0f, "HOLD" to 0f)) }
            val doubleStarted = System.nanoTime()
            val doubleProbe = Stoop.renderProbe(double, normalize = false)
            renderNanos += System.nanoTime() - doubleStarted
            require(doubleProbe.events.size == 2 && doubleProbe.events.all { it.frame + it.durationFrames <= doubleProbe.samples.size }) { "second answer was truncated" }
            save("shadow_two_late_replies", "Short clav · two late replies", "Main LENGTH 0, deliberate replies at 250 and 500 ms. The short main ends before the second answer; both finite reply tails remain in the sample.",
                "shadow", "Intervals / timing", double, Snip(doubleProbe.samples, 1, 44_100), extra = shadowEvidence(doubleProbe))
            val chord = representatives.getValue(StoopVoice.CHORD)
            for ((name, intervals) in listOf("Root only" to listOf(0), "Root / fifth / octave" to listOf(0, 7, 12), "Major" to listOf(0, 4, 7), "Minor" to listOf(0, 3, 7))) {
                save("chord_${slug(name)}", "$name · ${noteName(chord.midi)}", "Semitone recipe [${intervals.joinToString(", ")}], transposed from the requested root.", "chords", "Explicit recipes",
                    chord.copy(chordIntervals = intervals, macros = chord.macros + ("HOLD" to 0f)))
            }
        }

        val factoryComparisons = linkedMapOf<String, Any?>()
        var referenceMetadata: Map<String, Any?>? = null
        if (reference != null) {
            val originalRoot = File(root, "original")
            originalRoot.mkdirs()
            File(originalRoot, "published-manifest.json").writeBytes(reference.publishedBytes)
            val originalRows = mutableMapOf<String, Map<String, Any?>>()
            for (saved in reference.factory) {
                val oldId = saved.getValue("id") as String
                val relative = saved.getValue("path") as String
                val bytes = safeAsset(reference.root, relative).readBytes()
                require(sha256(bytes) == saved.getValue("sha256")) { "$oldId reference changed during rendering" }
                val destination = safeAsset(originalRoot, relative)
                destination.parentFile.mkdirs()
                destination.writeBytes(bytes)
                val id = "original_$oldId"
                val evidence = LinkedHashMap(saved).also { row -> row.putAll(mapOf(
                    "id" to id, "title" to "${saved.getValue("title")} · original", "category" to "original",
                    "path" to "original/$relative", "bankVersion" to "published-original",
                    "feedbackId" to "published-original:${saved.getValue("sha256")}",
                    "legacyFeedbackId" to oldId, "legacyAudioSha256" to saved.getValue("sha256"),
                    "description" to "Published original. ${saved.getValue("description")}",
                )) }
                require(clips.none { it["id"] == id })
                clips += evidence
                originalRows[oldId] = evidence
            }
            val filtered = LinkedHashMap(reference.manifest).also { m ->
                m["clips"] = json(reference.factory)
                m["clipCount"] = json(reference.factory.size)
                m["totalAudioBytes"] = json(reference.factory.sumOf { (it.getValue("bytes") as Number).toLong() })
                m["mode"] = json("published original factory reference")
                m["sections"] = json(listOf(mapOf("id" to "presets", "title" to "Published original factory bank")))
                m["publishedManifestPath"] = json("published-manifest.json")
                m["publishedManifestSha256"] = json(sha256(reference.publishedBytes))
            }
            File(originalRoot, "manifest.json").writeText(Json.write(JsonValue.Obj(filtered)) + "\n")
            for (patch in factory) {
                val examples = listOf(false to "note", true to "phrase").associate { (phrase, kind) ->
                    val old = reference.example(patch.voice.name, patch.name, phrase)
                    val candidate = clips.single { it["category"] == "presets" && it["voice"] == patch.voice.name &&
                        it["preset"] == patch.name && (it["phrase"] != null) == phrase }
                    require(candidate["midi"] == (old.getValue("midi") as Number).toInt() &&
                        candidate["velocity"] == (old.getValue("velocity") as Number).toFloat())
                    if (phrase) require(json(candidate.getValue("phrase")) == json(old.getValue("phrase"))) { "phrase events must match the original" }
                    kind to mapOf("publishedOriginalId" to originalRows.getValue(old.getValue("id") as String).getValue("id"),
                        "revisedCandidateId" to candidate.getValue("id"), "audioChanged" to (candidate.getValue("sha256") != old.getValue("sha256")))
                }
                factoryComparisons["${patch.voice.name}:${patch.name}"] = mapOf(
                    "voice" to patch.voice.name, "preset" to patch.name, "midi" to patch.midi, "velocity" to patch.velocity,
                    "publishedOriginalDescription" to reference.example(patch.voice.name, patch.name, false)["presetDescription"],
                    "revisedDescription" to StoopPresets.metadataFor(patch)?.description, "examples" to examples,
                )
            }
            referenceMetadata = mapOf(
                "label" to "Published original", "manifestPath" to "original/manifest.json", "clipCount" to 96,
                "inputManifestSha256" to sha256(reference.inputBytes),
                "publishedManifestPath" to "original/published-manifest.json", "publishedManifestSha256" to sha256(reference.publishedBytes),
                "provenance" to reference.manifest["provenance"], "audioPolicy" to "96 original factory WAVs copied byte-for-byte; original audio is never regenerated",
                "comparisonRule" to "Identical saved root, velocity and phrase events. Both versions use the shared loudest-200-ms RMS 0.03 listening target.",
            )
        }
        require(clips.size == if (quick) 18 else if (reference == null) 300 else 396) { "unexpected audition roster size ${clips.size}" }
        require(initialProvenance["sourceSha256"] == provenance()["sourceSha256"]) { "STOOP source changed during generation; rerun after compiling the final source" }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 2, "engine" to "STOOP", "title" to "Stoop · dry audition", "mode" to if (quick) "quick smoke pack" else "complete audition",
            "status" to "Candidate implementation. Awaiting the owner's listening verdict; presets and numeric settings remain provisional.",
            "sampleRate" to 44_100, "channels" to 1, "bitDepth" to 16, "effects" to "none",
            "levelRule" to "Shared AuditionLevel: loudest 200-ms RMS 0.03, peak guard 0.99. Raw groups and decomposed parts explicitly identify a common reference gain.",
            "holdRule" to "Loop-only settled material. Stress HOLD WAVs contain four exact cycle copies; held-articulation comparisons contain one settled cycle with page Repeat on. Delayed attack onset is absent, with a sustained shadow companion.",
            "exportTiming" to "Offline samples retain their embedded reply timing. External sampler transposition changes reply delay in seconds with playback rate; answers are not tempo-locked.",
            "clipCount" to clips.size, "totalAudioBytes" to clips.sumOf { (it.getValue("bytes") as Number).toLong() },
            "renderSeconds" to renderNanos / 1e9, "generationSeconds" to (System.nanoTime() - started) / 1e9,
            "provenance" to initialProvenance,
            "referenceBank" to referenceMetadata, "factoryComparisons" to factoryComparisons,
            "voices" to StoopVoice.entries.map { mapOf("id" to it.name, "description" to familyCopy.getValue(it)) },
            "sections" to sections.map { (id, copy) -> mapOf("id" to id, "title" to copy.first, "description" to copy.second) } +
                if (reference == null) emptyList() else listOf(mapOf("id" to "original", "title" to "Original bank", "description" to
                    "The 96 published original factory notes and phrases, preserved byte-for-byte. Compare original on a revised factory row fills A with this reference and B with the candidate.")),
            "clips" to clips,
        )
        val text = Json.write(json(manifest)) + "\n"
        File(root, "manifest.json").writeText(text)
        writePage(root, text)
        println("wrote ${clips.size} STOOP WAVs (${manifest["totalAudioBytes"]} bytes), manifest.json and index.html under ${root.absolutePath}")
    }

    private fun writePage(root: File, manifest: String) {
        val template = javaClass.getResourceAsStream("/audition/stoop-audition.html")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("STOOP audition resource missing")
        require(template.contains("<!-- STOOP_MANIFEST -->"))
        File(root, "index.html").writeText(template.replace("<!-- STOOP_MANIFEST -->", "<script type=\"application/json\" id=\"stoop-manifest\">${manifest.replace("<", "\\u003c")}</script>"))
    }

    private fun safeAsset(root: File, relative: String): File {
        val file = File(root, relative).canonicalFile
        require(!File(relative).isAbsolute && file.toPath().startsWith(root.canonicalFile.toPath())) { "unsafe reference path $relative" }
        return file
    }

    private fun readReferenceBank(root: File, output: File): ReferenceBank {
        require(root.isDirectory)
        require(!root.canonicalFile.toPath().startsWith(output.canonicalFile.toPath()) &&
            !output.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "reference and output directories must be separate" }
        val inputBytes = File(root, "manifest.json").readBytes()
        val manifest = Json.parse(inputBytes.toString(Charsets.UTF_8)).obj()
        require(manifest.getValue("engine").str() == "STOOP")
        val rows = manifest.getValue("clips").arr().filter { it.obj().getValue("category").str() == "presets" }
            .map { it.obj().mapValues { (_, value) -> fromJson(value) } }
        require(rows.size == 96 && rows.map { it["id"] }.distinct().size == 96 && rows.map { it["path"] }.distinct().size == 96) {
            "a reference bank needs exactly 96 distinct factory note/phrase assets"
        }
        for (row in rows) {
            val file = safeAsset(root, row.getValue("path") as String)
            require(file.isFile && file.length() == (row.getValue("bytes") as Number).toLong() &&
                sha256(file.readBytes()) == row.getValue("sha256")) { "missing or changed original ${row["id"]}" }
            val audio = WavReader.read(file)
            require(audio.channels == 1 && audio.sampleRate == 44_100 && abs(Loudness.of(audio) - .03f) < .0002f) {
                "${row["id"]} does not share the original mono 44.1-kHz listening level"
            }
            require(row["patchRecipe"] is Map<*, *>) { "original ${row["id"]} has no saved recipe" }
        }
        val publishedBytes = manifest["publishedManifestPath"]?.let { path ->
            safeAsset(root, path.str()).readBytes().also { bytes ->
                require(sha256(bytes) == manifest.getValue("publishedManifestSha256").str()) { "published provenance manifest changed" }
            }
        } ?: inputBytes
        return ReferenceBank(root, manifest, inputBytes, publishedBytes, rows)
    }

    private fun provenance(): Map<String, Any?> {
        val project = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        val sources = listOf("synth/src/main/kotlin/com/snipsnap/synth/Stoop.kt", "synth/src/main/kotlin/com/snipsnap/synth/StoopPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/StoopPresets.kt", "synth/src/test/kotlin/com/snipsnap/synth/StoopAuditionGenerator.kt").associateWith { name ->
            val file = project?.let { File(it, name) }
            if (file?.isFile == true) sha256(file.readBytes()) else null
        }
        fun nested(type: Class<*>): List<Class<*>> = listOf(type) + type.declaredClasses.flatMap { nested(it) }
        val compiledTypes = listOf(Stoop::class.java, StoopPatch::class.java, StoopPresets::class.java,
            StoopSource::class.java, StoopVoice::class.java, StoopPresetMetadata::class.java, javaClass).flatMap { nested(it) }
        val classes = compiledTypes.distinctBy { it.name }.associate { type ->
            type.name to type.getResourceAsStream("/${type.name.replace('.', '/')}.class")?.use { sha256(it.readBytes()) }
        }
        return mapOf("sourceSha256" to sources, "compiledClassSha256" to classes,
            "generator" to "com.snipsnap.synth.StoopAuditionGenerator", "audio" to "Kotlin engine PCM render; no browser synthesis")
    }
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun slug(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun percent(v: Float) = (v * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(v: Float) = "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun shadowEvidence(probe: Stoop.Probe): Map<String, Any?> = mapOf("actualShadowEvents" to probe.events.map { event ->
        mapOf("frame" to event.frame, "onsetSeconds" to event.frame / 44_100.0, "midi" to event.midi,
            "articulation" to event.articulation, "durationFrames" to event.durationFrames)
    })
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported evidence ${value.javaClass}")
    }
    private fun fromJson(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { fromJson(it.value) }
        is JsonValue.Arr -> value.items.map { fromJson(it) }
    }
}

package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * OSTERIA's note-centric audition, rendered by the actual JVM engine. Native files keep
 * the engine's gain; matched files use [AuditionLevel]. Model 2 has one fixed root and one
 * shared attack and release. The four layer files are taps from that complete note, with
 * its interaction and history still active. One model 1 phrase is retained for comparison.
 * This pack contains finite notes; no seamless HOLD loop is claimed.
 *
 * Run `./gradlew :synth:generateOsteriaAudition` from the repository root.
 */
object OsteriaAuditionGenerator {
    private val roles = listOf("BASS", "HARMONY", "MELODY", "RHYTHM")
    private val layers = listOf("ROOT", "BODY", "AIR", "DETAIL")
    private const val STEM_REFERENCE = "note-memory"

    private data class Case(
        val id: String,
        val group: String,
        val title: String,
        val description: String,
        val patch: OsteriaPatch,
        val options: Osteria.ProbeOptions = Osteria.ProbeOptions(),
        val stem: Int? = null,
        val variant: String? = null,
        val referenceId: String? = null,
    )

    private val groups = listOf(
        group("first-listen", "One press, evolving color",
            "Model 2: one C3 press, velocity 0.8, seed 173 and ROOM 0. All four layers share the same fixed pitch, attack and release. Compare fixed shore colors, moving colors without memory, and the identical motion with remembered color. Each note holds for 5.2 seconds, then releases for 0.8 seconds."),
        group("duration", "Tap and hold",
            "The same Lingering Color recipe with two press lengths: a 0.2-second tap or a 7.2-second hold. Both have one attack, one fixed C3 root and a 0.8-second release; no repeating notes or loop."),
        group("touch", "Touch across the keyboard",
            "The same dry Evolving Note recipe at C2, C3 and C5, each at velocities 0.25, 0.60 and 1.00. Every note has one fixed root, one shared 0.035-second attack, a 2.2-second gate and a 0.8-second release. Use raw playback to hear the full velocity response; matched playback helps compare the change in color."),
        group("articulation", "Attack and release",
            "Dry Evolving Note at C3 and velocity 0.8. Compare a 0.005-second attack with a 0.25-second attack, then a 0.05-second release with a 1.5-second release. Every example keeps the same 2.2-second gate, fixed root and shared articulation across all four layers."),
        group("parts", "Four layers of the same note",
            "Root, body, air and detail are isolated stereo contributions from the complete Lingering Color note. They share its root pitch and envelope, and retain their native relative levels, coupled motion and history. Their raw samples sum to the complete dry note; matched playback raises each contribution for inspection."),
        group("history", "Different journeys, one destination",
            "Forward and reverse timbral motion reach exactly the same current shore before release. The pitch remains C3 throughout. Compare memory on and off: earlier colors can remain after the current positions reunite."),
        group("reference", "Previous phrase",
            "An explicit model 1 render of the previous Lingering Memory recipe. This comparison retains its scheduled attacks and changing pitches. It is the earlier phrase interpretation, rather than the new single-note behavior."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "supply at most one output directory"
        }
        val root = File(args.firstOrNull() ?: "../testkit/osteria-audition")
        val cases = cases()
        check(cases.size == 27 && cases.map { it.id }.distinct().size == cases.size)
        check(cases.all { it.patch.macros.getValue("ROOM") == 0f }) { "audition comparisons must remain dry" }
        root.mkdirs()
        val clips = ArrayList<Map<String, Any?>>(cases.size)
        val probes = mutableMapOf<String, Osteria.Probe>()
        val started = System.nanoTime()
        val sourceHashes = sourceHashes()
        var stemSumError = 0.0
        val expectedPaths = mutableSetOf<String>()

        for ((index, case) in cases.withIndex()) {
            val renderStart = System.nanoTime()
            val probe = if (case.stem != null) {
                probes.getValue(STEM_REFERENCE)
            } else {
                Osteria.probe(
                    case.patch.voice, case.patch.macros, case.patch.midi,
                    case.patch.velocity, case.patch.config, case.options,
                )
            }
            if (case.id == STEM_REFERENCE) {
                probes[case.id] = probe
                stemSumError = stemSumError(probe)
                check(stemSumError < 1e-6) { "note layers do not reconstruct their full dry render: $stemSumError" }
            }
            if (case.group == "history") probes[case.id] = probe
            check(probe.channels == 2 && probe.sampleRate == Dsp.RATE)
            check(probe.stems.size == roles.size)
            val samples = case.stem?.let { probe.stems[it] } ?: probe.samples
            val native = Snip(samples, probe.channels, probe.sampleRate)
            check(native.samples.isNotEmpty() && native.samples.all { it.isFinite() }) {
                "OSTERIA ${case.id} produced empty or nonfinite samples"
            }
            check(native.peak() < 1f) {
                "OSTERIA ${case.id} native peak ${native.peak()} exceeds PCM headroom; fix the DSP before exporting"
            }
            check(probe.shoreTrace.isNotEmpty() && probe.shoreTrace.all { frame ->
                frame.timeSeconds.isFinite() && frame.positions.size == 4 && frame.remembered.size == 4 &&
                    (frame.positions + frame.remembered).all { it.isFinite() && it in 0.0..4.0 }
            }) { "invalid shore trace in ${case.id}" }
            val matched = AuditionLevel.level(native)
            check(matched.samples.all { it.isFinite() } && matched.peak() < 1f)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            val recipePath = "recipes/${case.id}.json"
            val tracePath = "traces/${case.id}.csv"
            val rawFile = WavWriter.write(File(root, rawPath), native, WavWriter.BitDepth.PCM_24)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            writeRecipe(File(root, recipePath), case)
            writeTrace(File(root, tracePath), probe)
            expectedPaths += listOf(rawPath, matchedPath, recipePath, tracePath)
            val renderMs = (System.nanoTime() - renderStart) / 1e6
            clips += linkedMapOf(
                "id" to case.id, "group" to case.group,
                "title" to case.title, "description" to case.description,
                "voice" to case.patch.voice.name, "model" to case.patch.config.model,
                "config" to case.patch.toJsonValue().entries.getValue("config"),
                "midi" to case.patch.midi, "note" to noteName(case.patch.midi),
                "velocity" to case.patch.velocity, "macros" to case.patch.macros,
                "raw" to rawPath, "matched" to matchedPath,
                "recipe" to recipePath, "trace" to tracePath,
                "durationSeconds" to native.durationSeconds, "frames" to native.frameCount,
                "gateSeconds" to native.durationSeconds - case.patch.config.releaseSeconds,
                "attackSeconds" to case.patch.config.attackSeconds,
                "releaseSeconds" to case.patch.config.releaseSeconds,
                "oneAttack" to (case.patch.config.model == 2),
                "fixedRootPitch" to (case.patch.config.model == 2),
                "peak" to native.peak(), "rms" to rms(native.samples),
                "loudestWindowRms" to Loudness.of(native),
                "matchedPeak" to matched.peak(), "matchedRms" to rms(matched.samples),
                "matchedLoudestWindowRms" to Loudness.of(matched),
                "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                "role" to case.stem?.let { layers[it] },
                "sourceRole" to case.stem?.let { roles[it] },
                "variant" to case.variant, "referenceId" to case.referenceId,
                "probeOptions" to optionsMap(case.options),
                "seed" to case.patch.config.seed, "direction" to case.patch.config.direction,
                "tavernShore" to case.patch.config.tavernShore,
                "renderMs" to renderMs,
                // Browser graphs use actual sampled engine state, with every fifth frame retained.
                // The companion CSV retains the full trace, including its final frame.
                "shoreTrace" to probe.shoreTrace.filterIndexed { i, _ ->
                    i % 5 == 0 || i == probe.shoreTrace.lastIndex
                }.map { frame -> mapOf(
                    "timeSeconds" to frame.timeSeconds,
                    "positions" to frame.positions,
                    "remembered" to frame.remembered,
                ) },
            )
            println("Osteria audition ${index + 1}/${cases.size}: ${case.id} · ${fmt(native.durationSeconds.toDouble())} s · peak ${fmt(native.peak().toDouble())}")
        }

        val history = historyEvidence(probes)
        val manifest = Json.write(Json.fromAny(linkedMapOf(
            "schemaVersion" to 2,
            "title" to "OSTERIA · One note, evolving color",
            "description" to "A revised SnipSnap adaptation of the XOsteria concept: one press has a fixed root, one shared attack and release, and four layers whose spectra move between five shore colors. Memory retains earlier timbral color. A clearly marked model 1 phrase provides the previous interpretation for comparison.",
            "engine" to "OSTERIA", "model" to 2,
            "status" to "Approved single-note direction; touch and register audition",
            "sourceSha256" to sourceHashes,
            "sampleRate" to Dsp.RATE, "channels" to 2, "bitDepth" to 24,
            "referenceMidi" to 48, "referenceVelocity" to .8f,
            "listeningTarget" to .03, "listeningPeakCeiling" to .99,
            "finiteGestures" to true, "finiteNotes" to true, "looping" to false,
            "pressBehavior" to "Model 2 holds one fixed root for gateSeconds, followed by releaseSeconds. All four layers share attackSeconds and releaseSeconds. The accepted baseline holds 5.2 seconds and releases for 0.8 seconds; the tap holds 0.2 seconds and the long hold holds 7.2 seconds. Touch and articulation comparisons keep a 2.2-second gate, with their actual attack and release saved in each recipe and clip. These finite files simulate press and release timing. Model 1 is the explicitly marked previous phrase reference.",
            "metricNotes" to "Raw WAVs preserve native engine output without gain reduction. Matched WAVs apply one stereo gain using AuditionLevel: loudest 200 ms folded RMS 0.03, peak guard 0.99. peak and rms describe raw interleaved stereo samples; rms is whole-file RMS. Layer taps are captured from the same model 2 dry note and sum to its native output. CSV column names retain the engine's internal role order; model 2 layer labels are root, body, air and detail. CSVs retain actual current and remembered shores at the engine trace cadence; embedded browser traces retain every fifth frame plus the last. No browser synthesis or rack FX.",
            "groups" to groups, "clips" to clips,
            "summary" to linkedMapOf(
                "clipCount" to clips.size, "wavCount" to clips.size * 2,
                "maxRawPeak" to clips.maxOf { (it.getValue("peak") as Number).toDouble() },
                "allSamplesFinite" to true, "nativeHeadroomPassed" to true,
                "stemSumMaxError" to stemSumError,
                "history" to history,
                "generationSeconds" to (System.nanoTime() - started) / 1e9,
            ),
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        copyPage(root, manifest)
        prunePrevious(root, expectedPaths)
        File(root, "README.md").writeText("""
            # Osteria single-note audition

            ${clips.size} finite DSP clips, each as native and matched 24-bit stereo WAVs at ${Dsp.RATE} Hz.
            Open index.html locally or serve this directory as a static site. The page embeds its
            manifest and numerical shore traces; audio, recipes and full CSV traces are local assets.

            Start with fixed-color Anchored Note, Evolving Note and Lingering Color: C3,
            velocity 0.8, seed 173, ROOM 0 and HOLD 0. The fixed-color probe disables motion.
            Evolving Note and Lingering Color differ only in MEMORY. Model 2 shares one root
            pitch, one attack and one release across all four layers, with no scheduled notes.
            Normal notes hold 5.2 seconds and release for 0.8 seconds. The tap holds 0.2 seconds;
            the long hold holds 7.2 seconds. The four layer taps come from the same complete
            Lingering Color simulation. Their raw samples sum to the complete dry note.

            Touch across the keyboard uses the identical dry Evolving Note recipe at C2, C3
            and C5 (MIDI 36, 48 and 72), each at velocities 0.25, 0.60 and 1.00. All nine notes
            have a 2.2-second gate, a 0.035-second attack and a 0.8-second release. Attack
            comparisons use 0.005 and 0.25 seconds; release comparisons use 0.05 and 1.5
            seconds. These four articulation examples use C3, velocity 0.8 and the same
            2.2-second gate. Each model 2 note keeps one fixed root and shares articulation
            across its four layers. The files simulate press and release timing; they do not
            respond to the listener lifting a pad. Actual gate, attack and release times are
            recorded in the manifest, with attack and release also saved in the patch config.

            Probe recipes preserve the full patch plus mechanism flags and an optional stem tap.
            To reproduce a probe, decode the patch, call Osteria.probe with audition.probeOptions,
            then select audition.stemIndex if present. Patch.render() plays the normal full note.
            The Previous phrase comparison explicitly saves model 1 and the original Lingering
            Memory recipe, retaining its scheduled attacks and changing pitches.

            Raw WAVs preserve the engine gain. Matched WAVs use the shared AuditionLevel rule
            (loudest folded 200 ms RMS 0.03, peak ceiling 0.99); use raw playback to assess touch.
            ROOM is zero in every clip. No external effects or seamless loops.
            Macro HOLD extends the finite note; it does not select repeating audio.

            Regenerate: ./gradlew :synth:generateOsteriaAudition
        """.trimIndent() + "\n")
        println("Wrote ${clips.size * 2} WAVs, ${clips.size} recipes, ${clips.size} CSV traces and the audition page under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        val presets = OsteriaPresets.original()
        check(presets.size == 3 && presets.all { it.config.model == 2 })
        val anchored = presets.single { it.name == "Anchored Note" }
        val evolving = presets.single { it.name == "Evolving Note" }
        val lingering = presets.single { it.name == "Lingering Color" }
        check(evolving.copy(name = lingering.name, macros = evolving.macros + ("MEMORY" to .88f)) == lingering)
        add(Case("note-still", "first-listen", "Fixed color · Anchored Note",
            "One fixed C3 root with one shared attack and release. Motion is disabled to provide a fixed-color reference. Hold 5.2 seconds, release 0.8 seconds.",
            anchored, Osteria.ProbeOptions(motionEnabled = false), variant = "motion-off"))
        add(Case("note-evolving", "first-listen", "Evolving Note",
            "One C3 press: the four layers change spectral color while pitch stays fixed. MEMORY is zero. Hold 5.2 seconds, release 0.8 seconds.", evolving))
        add(Case("note-memory", "first-listen", "Lingering Color",
            "The identical evolving note with MEMORY 0.88. Earlier spectral colors remain in the sustained note; there are no new attacks or pitch changes. Hold 5.2 seconds, release 0.8 seconds.", lingering,
            referenceId = "note-evolving"))
        add(Case("note-tap", "duration", "Short tap",
            "Lingering Color triggered once and released after 0.2 seconds, followed by a 0.8-second release. Total length 1 second; the root stays C3.",
            lingering.copy(config = lingering.config.copy(seconds = 1.0)),
            variant = "short-tap", referenceId = "note-memory"))
        add(Case("note-long-hold", "duration", "Long hold",
            "Lingering Color with HOLD 1: one C3 press held for 7.2 seconds, followed by a 0.8-second release. Total length 8 seconds; no loop or repeated attacks.",
            lingering.copy(macros = lingering.macros + ("HOLD" to 1f)),
            variant = "long-hold", referenceId = "note-memory"))

        val touchGateSeconds = 2.2
        val touchConfig = evolving.config.copy(seconds = touchGateSeconds + .8)
        for ((midi, register) in listOf(36 to "low", 48 to "middle", 72 to "high")) {
            for ((velocity, touch) in listOf(.25f to "soft", .60f to "medium", 1f to "strong")) {
                add(Case("touch-${noteName(midi).lowercase()}-$touch", "touch",
                    "${noteName(midi)} · $touch touch",
                    "The same dry Evolving Note in the $register register at velocity ${fmt(velocity.toDouble())}. One fixed ${noteName(midi)} root, shared 0.035-second attack, 2.2-second gate and 0.8-second release. Use raw playback to assess the level and color response together.",
                    evolving.copy(midi = midi, velocity = velocity, config = touchConfig),
                    variant = "velocity-$touch",
                    referenceId = if (touch == "medium") null else "touch-${noteName(midi).lowercase()}-medium"))
            }
        }
        for ((attack, articulation) in listOf(.005 to "fast", .25 to "slow")) {
            add(Case("touch-attack-$articulation", "articulation",
                "${articulation.replaceFirstChar { it.uppercase() }} attack · ${fmt(attack)} s",
                "Dry Evolving Note at C3 and velocity 0.8, with one shared ${fmt(attack)}-second attack. The fixed root, 2.2-second gate and 0.8-second release are unchanged.",
                evolving.copy(config = touchConfig.copy(attackSeconds = attack)),
                variant = "attack-$articulation", referenceId = "touch-attack-${if (articulation == "fast") "slow" else "fast"}"))
        }
        for ((release, articulation) in listOf(.05 to "short", 1.5 to "long")) {
            add(Case("touch-release-$articulation", "articulation",
                "${articulation.replaceFirstChar { it.uppercase() }} release · ${fmt(release)} s",
                "Dry Evolving Note at C3 and velocity 0.8. A 0.035-second shared attack begins one fixed-root note; the shared ${fmt(release)}-second release follows the same 2.2-second gate.",
                evolving.copy(config = touchConfig.copy(seconds = touchGateSeconds + release, releaseSeconds = release)),
                variant = "release-$articulation", referenceId = "touch-release-${if (articulation == "short") "long" else "short"}"))
        }

        val layerDescriptions = listOf(
            "The lower spectral contribution to the same C3 note.",
            "The central spectral body of the same C3 note.",
            "The upper partials and air of the same C3 note.",
            "The quieter spectral detail of the same C3 note.",
        )
        for ((index, layer) in layers.withIndex()) add(Case(
            "note-layer-${layer.lowercase()}", "parts", layer.lowercase().replaceFirstChar { it.uppercase() },
            "${layerDescriptions[index]} Captured from Lingering Color, with its shared attack, fixed root, coupled color and release intact. This contribution is part of one note, rather than a separate player.",
            lingering, stem = index, variant = "stem", referenceId = STEM_REFERENCE,
        ))

        for (direction in listOf(1, -1)) for (memory in listOf(true, false)) {
            val travel = if (direction == 1) "forward" else "reverse"
            val suffix = if (memory) "memory" else "memory-off"
            add(Case("note-history-$travel-$suffix", "history",
                "${travel.replaceFirstChar { it.uppercase() }} color · ${if (memory) "memory on" else "memory off"}",
                "${if (direction == 1) "Forward" else "Reverse"} spectral motion reaches the same current shore. ${if (memory) "Earlier timbral colors persist at that common destination." else "History is disabled, leaving the current destination alone."} One fixed C3 root, held 5.2 seconds and released over 0.8 seconds.",
                lingering.copy(config = lingering.config.copy(direction = direction)),
                Osteria.ProbeOptions(memoryEnabled = memory),
                variant = "history-$travel-$suffix",
                referenceId = "note-history-${if (direction == 1) "reverse" else "forward"}-$suffix",
            ))
        }
        add(Case("previous-phrase-model1", "reference", "Previous phrase · model 1",
            "The original Lingering Memory recipe: scheduled attacks and changing pitches make this sound like a played phrase. This explicit model 1 reference is retained unchanged for comparison with the new single-note model 2.",
            lingering.copy(name = "Lingering Memory", config = lingering.config.copy(model = 1)),
            variant = "previous-phrase", referenceId = "note-memory"))
    }

    private fun writeRecipe(file: File, case: Case) {
        val recipe = LinkedHashMap(case.patch.toJsonValue().entries)
        recipe["audition"] = Json.fromAny(linkedMapOf(
            "id" to case.id,
            "probeOptions" to optionsMap(case.options),
            "stemIndex" to case.stem,
            "stemRole" to case.stem?.let { roles[it] },
            "stemLayer" to case.stem?.let { layers[it] },
            "sourceReference" to if (case.stem != null) STEM_REFERENCE else null,
            "nativeGain" to 1,
        ))
        file.parentFile.mkdirs()
        file.writeText(Json.write(JsonValue.Obj(recipe)) + "\n")
    }

    private fun writeTrace(file: File, probe: Osteria.Probe) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.append("timeSeconds,bassPosition,harmonyPosition,melodyPosition,rhythmPosition,bassRemembered,harmonyRemembered,melodyRemembered,rhythmRemembered\n")
            for (frame in probe.shoreTrace) {
                out.append((listOf(frame.timeSeconds) + frame.positions + frame.remembered)
                    .joinToString(",") { String.format(Locale.ROOT, "%.9f", it) }).append('\n')
            }
        }
    }

    private fun historyEvidence(probes: Map<String, Osteria.Probe>): Map<String, Any> {
        val forward = probes.getValue("note-history-forward-memory")
        val reverse = probes.getValue("note-history-reverse-memory")
        val currentGap = forward.shoreTrace.last().positions.zip(reverse.shoreTrace.last().positions)
            .maxOf { (a, b) -> abs(a - b) }
        val rememberedGap = forward.shoreTrace.last().remembered.zip(reverse.shoreTrace.last().remembered)
            .maxOf { (a, b) -> abs(a - b) }
        check(currentGap < 1e-9) { "history probes did not return to the same current shore: $currentGap" }
        val offForward = probes.getValue("note-history-forward-memory-off")
        val offReverse = probes.getValue("note-history-reverse-memory-off")
        val onDifference = differenceRms(forward.samples, reverse.samples, forward.sampleRate * 2)
        val offDifference = differenceRms(offForward.samples, offReverse.samples, offForward.sampleRate * 2)
        check(rememberedGap > 1e-6 && onDifference > 1e-6) {
            "opposite journeys did not leave measurable remembered color: $rememberedGap / $onDifference"
        }
        check(offDifference < 1e-8) { "memory-off tails differ at the same current shore: $offDifference" }
        return mapOf(
            "finalCurrentShoreMaxDifference" to currentGap,
            "finalRememberedShoreMaxDifference" to rememberedGap,
            "memoryOnTailDifferenceRms" to onDifference,
            "memoryOffTailDifferenceRms" to offDifference,
            "tailComparisonSeconds" to 1,
        )
    }

    private fun stemSumError(probe: Osteria.Probe): Double {
        check(probe.stems.all { it.size == probe.samples.size && it.all(Float::isFinite) })
        var error = 0.0
        for (i in probe.samples.indices) {
            val sum = probe.stems.sumOf { it[i].toDouble() }
            error = maxOf(error, abs(sum - probe.samples[i]))
        }
        return error
    }

    private fun differenceRms(a: FloatArray, b: FloatArray, tailSamples: Int): Double {
        check(a.size == b.size)
        val start = (a.size - tailSamples).coerceAtLeast(0)
        var sum = 0.0
        for (i in start until a.size) {
            val difference = a[i].toDouble() - b[i]
            sum += difference * difference
        }
        return sqrt(sum / (a.size - start))
    }

    private fun optionsMap(options: Osteria.ProbeOptions): Map<String, Boolean> = linkedMapOf(
        "couplingEnabled" to options.couplingEnabled, "memoryEnabled" to options.memoryEnabled,
        "motionEnabled" to options.motionEnabled, "roomEnabled" to options.roomEnabled,
    )

    private fun copyPage(root: File, manifest: String) {
        for ((resource, destination) in listOf(
            "osteria-audition.html" to "index.html",
            "osteria-audition.css" to "osteria-audition.css",
            "osteria-audition.js" to "osteria-audition.js",
        )) {
            val template = OsteriaAuditionGenerator::class.java.getResourceAsStream("/audition/$resource")
                ?.bufferedReader()?.use { it.readText() } ?: error("missing audition resource $resource")
            if (destination == "index.html") check(template.contains("__MANIFEST__"))
            File(root, destination).writeText(template.replace("__MANIFEST__", manifest.replace("<", "\\u003c")))
        }
    }

    private fun prunePrevious(root: File, expected: Set<String>) {
        for ((directory, extension) in listOf("raw" to "wav", "matched" to "wav", "recipes" to "json", "traces" to "csv")) {
            File(root, directory).listFiles()?.filter { it.isFile && it.extension == extension }
                ?.filter { "$directory/${it.name}" !in expected }?.forEach { check(it.delete()) }
        }
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun sourceHashes(): Map<String, String> {
        val paths = listOf(
            "src/main/kotlin/com/snipsnap/synth/Osteria.kt",
            "src/main/kotlin/com/snipsnap/synth/OsteriaPatch.kt",
            "src/main/kotlin/com/snipsnap/synth/OsteriaPresets.kt",
            "src/test/kotlin/com/snipsnap/synth/OsteriaAuditionGenerator.kt",
        )
        val module = listOf(File("."), File("synth")).firstOrNull { File(it, paths.first()).isFile }
            ?: error("run the audition generator from the synth module or repository root to record source provenance")
        return paths.associate { "synth/$it" to sha256(File(module, it)) }
    }
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun noteName(midi: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
    private fun group(id: String, title: String, description: String) = mapOf("id" to id, "title" to title, "description" to description)

    /** Local conversion keeps the standalone evidence schema on SnipSnap's existing JSON writer. */
    private fun Json.fromAny(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Boolean -> JsonValue.Bool(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { (key, entry) -> key.toString() to fromAny(entry) })
        is Iterable<*> -> JsonValue.Arr(value.map { fromAny(it) })
        else -> error("unsupported audition JSON type ${value::class.simpleName}")
    }
}

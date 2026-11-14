package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Eight dry preset proposals, followed by practical register, touch and causal
 * memory comparisons. Every file is one fixed-root model-two note. These recipes
 * remain separate from the factory presets and the original QUARTET bank.
 *
 * Run `./gradlew :synth:generateOsteriaPresetAudition` from the repository root.
 */
object OsteriaPresetAuditionGenerator {
    private const val SHORT_GATE = 3.0

    private data class Case(
        val id: String,
        val group: String,
        val title: String,
        val description: String,
        val candidateId: String,
        val patch: OsteriaPatch,
        val expectedGate: Double,
        val options: Osteria.ProbeOptions = Osteria.ProbeOptions(),
        val referenceId: String? = null,
        val variant: String,
    )

    private val groups = listOf(
        group("first-listen", "Eight preset proposals",
            "Compare each pair at the same C3, velocity 0.8, seed 173 and 5.2-second press. Roaming Bass now opens from a hollow edge into its fuller body. Both Reeds develop more breath-led body and air. Anchor Bass, both Plucks and both Pads are retained for comparison. Reed keeps its -1.5 dB listening balance and Pad +1.5 dB. These are provisional recipes, not promoted factory presets."),
        group("register", "Try their registers",
            "Each preset rendered directly at a useful root: Bass C2; Pluck, Reed and Pad C4. Bass, Pluck and Reed have a 3-second press. Pad keeps 5.2 seconds so its slow onset and remembered color can develop. Every file has one fixed root and one trigger; these are separate renders, not transposed samples."),
        group("touch", "Soft and hard touch",
            "The second option from each voice at velocity 0.35 and 1.0 in its useful register. Pitch, recipe, seed and press stay the same within each pair. Matched helps compare excitation and articulation; Raw retains the original level difference. Pad again keeps a 5.2-second press; the other voices use 3 seconds."),
        group("memory", "Take memory away",
            "The three memory-bearing proposals at C3 with the memory mechanism disabled. Each retains its exact first-listen recipe, including the saved MEMORY value, seed, pitch, articulation and 5.2-second press. Only the memory probe is off. Compare each with its named first-listen reference to judge what remembered color adds."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) { "supply at most one output directory" }
        val root = File(args.firstOrNull() ?: "../testkit/osteria-presets-audition")
        val proposals = OsteriaPresetCandidates.candidates()
        val revisions = proposals.associate { it.id to it.revision }
        val cases = cases(proposals)
        check(proposals.size == 8 && cases.size == 27 && cases.map { it.id }.distinct().size == cases.size)
        check(cases.all { it.patch.config.model == 2 && it.patch.macros.getValue("ROOM") == 0f && it.patch.macros.getValue("HOLD") == 0f })
        // Leave metadata headroom even before lossless compression. The original bank
        // is a separate deliverable; adding its 29 MB ZIP would consume this margin.
        val estimatedWavBytes = cases.sumOf { ((it.patch.config.seconds * Dsp.RATE).toLong() + 1) * 2 * 3 * 2 + 88 }
        val estimatedPreviewBytes = cases.sumOf { (it.patch.config.seconds * 192_000 / 8 * 2).toLong() + 4096 }
        check(estimatedWavBytes + estimatedPreviewBytes + 8L * 1024 * 1024 < 100L * 1024 * 1024) { "preset audition exceeds the conservative hosting budget" }
        root.mkdirs()
        val hashes = sourceHashes()
        val started = System.nanoTime()
        val clips = ArrayList<Map<String, Any?>>(cases.size)
        val mainRaw = mutableMapOf<String, FloatArray>()
        val mainMatched = mutableMapOf<String, FloatArray>()
        val memoryEvidence = linkedMapOf<String, Map<String, Double>>()
        val expectedPaths = mutableSetOf<String>()

        for ((index, case) in cases.withIndex()) {
            val renderStarted = System.nanoTime()
            val patch = case.patch
            val probe = Osteria.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, case.options)
            check(probe.channels == 2 && probe.sampleRate == Dsp.RATE && probe.stems.size == 4)
            val native = Snip(probe.samples, probe.channels, probe.sampleRate)
            check(native.samples.isNotEmpty() && native.samples.all(Float::isFinite)) { "empty or nonfinite preset ${case.id}" }
            check(native.peak() >= .000001f && native.peak() < 1f) { "preset ${case.id} is silent or exceeds native headroom: ${native.peak()}" }
            check(probe.shoreTrace.isNotEmpty() && probe.shoreTrace.all { frame ->
                frame.timeSeconds.isFinite() && frame.positions.size == 4 && frame.remembered.size == 4 &&
                    (frame.positions + frame.remembered).all { it.isFinite() && it in 0.0..4.0 }
            }) { "invalid shore trace in ${case.id}" }
            val gate = native.durationSeconds.toDouble() - patch.config.releaseSeconds
            check(abs(gate - case.expectedGate) < 1.0 / Dsp.RATE) { "incorrect simulated press in ${case.id}: $gate" }
            val pitches = listOf(.85f, 1.8f).map { from ->
                val pitch = checkNotNull(Pitch.detect(native, from, .30f)) { "${case.id} has no detected root at $from seconds" }
                val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(patch.midi).toDouble()) / ln(2.0)
                check(abs(cents) < 25.0 && pitch.confidence >= .3f) { "${case.id} lost its requested root at $from: $cents cents, confidence ${pitch.confidence}" }
                linkedMapOf("fromSeconds" to from, "hz" to pitch.hz, "confidence" to pitch.confidence, "centsFromRequestedRoot" to cents)
            }
            val matched = AuditionLevel.level(native)
            Osteria.applyOutputBalance(patch.voice, matched.samples)
            check(matched.samples.all(Float::isFinite) && matched.peak() <= .990001f)
            if (case.group == "first-listen") {
                mainRaw[case.id] = native.samples
                mainMatched[case.id] = matched.samples
            }
            if (case.group == "memory") {
                val reference = mainRaw.getValue(checkNotNull(case.referenceId))
                val difference = differenceRms(reference, native.samples)
                val colorDifference = normalizedDifferenceRms(reference, native.samples)
                check(difference > 1e-6 && colorDifference > 1e-5) { "memory has no measurable effect in ${case.id}: $difference / $colorDifference" }
                memoryEvidence[case.candidateId] = mapOf("rawDifferenceRms" to difference, "unitEnergyDifferenceRms" to colorDifference)
            }

            val trimDb = Osteria.balanceTrimDbFor(patch.voice)
            val balanceSuffix = if (trimDb == 0.0) "" else "-balance${trimDb}dB"
            val revision = revisions.getValue(case.candidateId)
            val revisionSuffix = if (revision > 1) "-v$revision" else ""
            val rawPath = "raw/${case.id}$revisionSuffix.wav"
            val matchedPath = "matched/${case.id}$revisionSuffix$balanceSuffix.wav"
            val recipePath = "recipes/${case.id}.json"
            val tracePath = "traces/${case.id}.csv"
            val rawFile = WavWriter.write(File(root, rawPath), native, WavWriter.BitDepth.PCM_24)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            writeRecipe(File(root, recipePath), case, gate, revision)
            writeTrace(File(root, tracePath), probe)
            expectedPaths += listOf(rawPath, matchedPath, recipePath, tracePath)
            clips += linkedMapOf(
                "id" to case.id, "group" to case.group, "title" to case.title, "description" to case.description,
                "candidateId" to case.candidateId, "presetName" to patch.name,
                "presetRevision" to revision,
                "voice" to patch.voice.name, "model" to patch.config.model, "candidate" to true,
                "status" to "Provisional preset proposal; awaiting listening verdict",
                "config" to patch.toJsonValue().entries.getValue("config"),
                "midi" to patch.midi, "note" to noteName(patch.midi), "velocity" to patch.velocity, "macros" to patch.macros,
                "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath, "trace" to tracePath,
                "durationSeconds" to native.durationSeconds, "frames" to native.frameCount,
                "gateSeconds" to gate, "attackSeconds" to patch.config.attackSeconds, "releaseSeconds" to patch.config.releaseSeconds,
                "oneAttack" to true, "fixedRootPitch" to true, "pitchWindows" to pitches,
                "peak" to native.peak(), "rms" to rms(native.samples), "loudestWindowRms" to Loudness.of(native),
                "matchedPeak" to matched.peak(), "matchedRms" to rms(matched.samples), "matchedLoudestWindowRms" to Loudness.of(matched),
                "balanceTrimDb" to trimDb, "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                "variant" to case.variant, "referenceId" to case.referenceId, "probeOptions" to optionsMap(case.options),
                "seed" to patch.config.seed, "direction" to patch.config.direction, "tavernShore" to patch.config.tavernShore,
                "renderMs" to (System.nanoTime() - renderStarted) / 1e6,
                "shoreTrace" to probe.shoreTrace.filterIndexed { i, _ -> i % 5 == 0 || i == probe.shoreTrace.lastIndex }
                    .map { frame -> mapOf("timeSeconds" to frame.timeSeconds, "positions" to frame.positions, "remembered" to frame.remembered) },
            )
            println("Osteria preset ${index + 1}/${cases.size}: ${case.id} · ${fmt(native.durationSeconds.toDouble())} s · peak ${fmt(native.peak().toDouble())}")
        }
        val pairEvidence = proposals.chunked(2).associate { pair ->
            // Pairs deliberately have different releases. Compare their common
            // note-on/held interval rather than padding one tail with silence.
            val heldSamples = (OsteriaPresetCandidates.GATE_SECONDS * Dsp.RATE).toInt() * 2
            val difference = normalizedDifferenceRms(mainMatched.getValue(pair[0].id).copyOf(heldSamples),
                mainMatched.getValue(pair[1].id).copyOf(heldSamples))
            check(difference > 1e-4) { "${pair[0].patch.voice} preset pair differs only in level" }
            pair[0].patch.voice.name to difference
        }
        check(hashes == sourceHashes()) { "preset sources changed while rendering; regenerate from one settled source state" }
        val manifest = Json.write(Json.fromAny(linkedMapOf(
            "schemaVersion" to 2, "auditionKind" to "preset-candidates", "auditionRevision" to 4, "title" to "OSTERIA · Eight preset proposals",
            "description" to "Two dry proposals each for Bass, Pluck, Reed and Pad. First compare the eight at C3, then try their useful registers, soft and hard touch, and three memory-disabled controls. Every clip is one fixed-root note with one attack and one authored release. These recipes await listening decisions before a new bank is built.",
            "engine" to "OSTERIA", "model" to 2,
            "status" to "Eight provisional preset recipes; factory presets and the original Note bank remain unchanged",
            "candidateVoices" to listOf("BASS", "PLUCK", "REED", "PAD"),
            "presetCandidates" to proposals.map { mapOf("id" to it.id, "name" to it.patch.name, "voice" to it.patch.voice.name, "description" to it.description, "revision" to it.revision) },
            "sourceSha256" to hashes, "sampleRate" to Dsp.RATE, "channels" to 2, "bitDepth" to 24,
            "referenceMidi" to 48, "referenceVelocity" to .8f, "listeningTarget" to .03, "listeningPeakCeiling" to .99,
            "finiteGestures" to true, "finiteNotes" to true, "looping" to false,
            "pressBehavior" to "All first-listen and memory-off notes simulate a 5.2-second press. Register and touch examples use a 3-second press for Bass, Pluck and Reed; Pad keeps 5.2 seconds. Each follows its preset's saved release. Exact press, attack and release values are recorded per clip. These finite files do not respond to a live key release, do not loop and never schedule later note attacks or pitch changes.",
            "metricNotes" to "Raw WAVs preserve native engine output without voice balance. Matched first uses AuditionLevel's loudest folded 200 ms RMS target 0.03 and peak guard 0.99, then the same production balance: Reed -1.5 dB, Pad +1.5 dB, others 0 dB, preserving headroom. balanceTrimDb records this adjustment. Pitch is detected in early and held windows. Unit-energy pair and memory differences remove overall level as an explanation. Memory-off controls keep the complete saved patch and only disable ProbeOptions.memoryEnabled; reproduce those with Osteria.probe and the recipe's probe flags. CSVs contain every motion trace frame; browser traces retain every fifth plus the last. ROOM and HOLD are zero in all 27 clips.",
            "groups" to groups, "clips" to clips,
            "summary" to linkedMapOf(
                "clipCount" to clips.size, "presetCount" to proposals.size, "wavCount" to clips.size * 2,
                "estimatedUncompressedWavBytes" to estimatedWavBytes, "estimatedPreviewBytes" to estimatedPreviewBytes,
                "maxRawPeak" to clips.maxOf { (it.getValue("peak") as Number).toDouble() },
                "allSamplesFinite" to true, "nativeHeadroomPassed" to true, "allOneAttack" to true, "allFixedRootPitch" to true,
                "presetPairUnitEnergyDifferenceRms" to pairEvidence, "memoryOnOffDifferences" to memoryEvidence,
                "generationSeconds" to (System.nanoTime() - started) / 1e9,
            ),
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        copyPage(root, manifest)
        prunePrevious(root, expectedPaths)
        File(root, "README.md").writeText("""
            # Osteria preset proposals

            Eight dry proposals: two each for Bass, Pluck, Reed and Pad. The 27
            finite clips contain those eight at C3, eight register examples,
            eight soft/hard touches on the second proposal of each voice, and
            three memory-disabled controls. All start from seed 173. ROOM and
            HOLD stay zero. Main and memory-control notes have a 5.2-second
            press. Register and touch examples use Bass C2, other voices C4;
            Pad keeps a 5.2-second press, the others use 3 seconds.

            Each file is one fixed-root note with one attack and one authored
            release. Pluck retains its existing natural held decay. These files
            do not follow a live key release and are not loops. The eight recipes
            are provisional and do not modify the three factory Note presets or
            the original sixteen-pad bank. Listening verdicts determine the next bank.

            Raw and matched 24-bit stereo WAVs are generated at ${Dsp.RATE} Hz.
            Raw preserves native output. Matched uses the common loudest folded
            200 ms RMS target 0.03, then Reed -1.5 dB / Pad +1.5 dB balance.
            Complete patch state and probe flags are in recipes; full motion is
            in CSV traces. To reproduce a memory-off file, decode its patch and
            call Osteria.probe with audition.probeOptions rather than Patch.render.

            Regenerate: ./gradlew :synth:generateOsteriaPresetAudition
        """.trimIndent() + "\n")
        println("Wrote ${clips.size * 2} WAVs, ${clips.size} recipes, ${clips.size} CSV traces and the audition page under ${root.absolutePath}")
    }

    private fun cases(proposals: List<OsteriaPresetCandidates.Candidate>): List<Case> = buildList {
        for (proposal in proposals) {
            add(Case(proposal.id, "first-listen", "${proposal.patch.name} · C3",
                "${proposal.description} ${articulation(proposal.patch, OsteriaPresetCandidates.GATE_SECONDS)}",
                proposal.id, proposal.patch, OsteriaPresetCandidates.GATE_SECONDS, variant = "preset"))
        }
        for (proposal in proposals) {
            val gate = practicalGate(proposal.patch.voice)
            val midi = practicalMidi(proposal.patch.voice)
            val patch = proposal.patch.copy(midi = midi, config = proposal.patch.config.copy(seconds = gate + proposal.patch.config.releaseSeconds))
            add(Case("${proposal.id}-register-${noteName(midi).lowercase()}", "register", "${patch.name} · ${noteName(midi)}",
                "The same proposal rendered directly at ${noteName(midi)}. ${articulation(patch, gate)} No sample transposition or repeating sequence.",
                proposal.id, patch, gate, referenceId = proposal.id, variant = "register"))
        }
        val moving = proposals.chunked(2).map { it.last() }
        for (proposal in moving) {
            val gate = practicalGate(proposal.patch.voice)
            val midi = practicalMidi(proposal.patch.voice)
            for ((touch, velocity) in listOf("soft" to .35f, "hard" to 1f)) {
                val patch = proposal.patch.copy(midi = midi, velocity = velocity,
                    config = proposal.patch.config.copy(seconds = gate + proposal.patch.config.releaseSeconds))
                add(Case("${proposal.id}-$touch-${noteName(midi).lowercase()}", "touch", "${patch.name} · ${touch.replaceFirstChar(Char::uppercase)} ${noteName(midi)}",
                    "The ${patch.name} proposal at excitation velocity ${fmt(velocity.toDouble())}; compare its other touch at the same pitch and press. ${articulation(patch, gate)}",
                    proposal.id, patch, gate, referenceId = "${proposal.id}-register-${noteName(midi).lowercase()}", variant = "touch-$touch"))
            }
        }
        val remembered = moving.filter { it.patch.macros.getValue("MEMORY") > 0f }
        check(remembered.size == 3)
        for (proposal in remembered) {
            add(Case("${proposal.id}-memory-off-c3", "memory", "${proposal.patch.name} · Memory off",
                "The exact first-listen ${proposal.patch.name} recipe with the memory mechanism disabled. Saved MEMORY remains ${fmt(proposal.patch.macros.getValue("MEMORY").toDouble())}; only the probe flag changes. ${articulation(proposal.patch, OsteriaPresetCandidates.GATE_SECONDS)} Compare with the named first-listen reference.",
                proposal.id, proposal.patch, OsteriaPresetCandidates.GATE_SECONDS,
                options = Osteria.ProbeOptions(memoryEnabled = false), referenceId = proposal.id, variant = "memory-off"))
        }
    }

    private fun practicalGate(voice: OsteriaVoice) = if (voice == OsteriaVoice.PAD) OsteriaPresetCandidates.GATE_SECONDS else SHORT_GATE
    private fun practicalMidi(voice: OsteriaVoice) = if (voice == OsteriaVoice.BASS) 36 else 60
    private fun articulation(patch: OsteriaPatch, gate: Double) =
        "Attack ${number(patch.config.attackSeconds)} seconds, press ${number(gate)} seconds, release ${number(patch.config.releaseSeconds)} seconds."

    private fun writeRecipe(file: File, case: Case, gate: Double, revision: Int) {
        val recipe = LinkedHashMap(case.patch.toJsonValue().entries)
        recipe["audition"] = Json.fromAny(linkedMapOf(
            "id" to case.id, "auditionKind" to "preset-candidates", "candidateId" to case.candidateId, "candidate" to true,
            "presetRevision" to revision,
            "probeOptions" to optionsMap(case.options), "nativeGain" to 1,
            "balanceTrimDb" to Osteria.balanceTrimDbFor(case.patch.voice),
            "oneAttack" to true, "fixedRootPitch" to true, "gateSeconds" to gate,
            "attackSeconds" to case.patch.config.attackSeconds, "releaseSeconds" to case.patch.config.releaseSeconds,
            "referenceId" to case.referenceId, "variant" to case.variant,
        ))
        file.parentFile.mkdirs()
        file.writeText(Json.write(JsonValue.Obj(recipe)) + "\n")
    }

    private fun writeTrace(file: File, probe: Osteria.Probe) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.append("timeSeconds,bassPosition,harmonyPosition,melodyPosition,rhythmPosition,bassRemembered,harmonyRemembered,melodyRemembered,rhythmRemembered\n")
            for (frame in probe.shoreTrace) out.append((listOf(frame.timeSeconds) + frame.positions + frame.remembered)
                .joinToString(",") { String.format(Locale.ROOT, "%.9f", it) }).append('\n')
        }
    }

    private fun optionsMap(options: Osteria.ProbeOptions) = linkedMapOf(
        "couplingEnabled" to options.couplingEnabled, "memoryEnabled" to options.memoryEnabled,
        "motionEnabled" to options.motionEnabled, "roomEnabled" to options.roomEnabled,
        "reedPressureEnabled" to options.reedPressureEnabled,
    )

    private fun copyPage(root: File, manifest: String) {
        for ((resource, destination) in listOf(
            "osteria-audition.html" to "index.html", "osteria-audition.css" to "osteria-audition.css", "osteria-audition.js" to "osteria-audition.js",
        )) {
            val template = OsteriaPresetAuditionGenerator::class.java.getResourceAsStream("/audition/$resource")
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

    private fun sourceHashes(): Map<String, String> {
        val paths = listOf(
            "src/main/kotlin/com/snipsnap/synth/Osteria.kt", "src/main/kotlin/com/snipsnap/synth/OsteriaPatch.kt",
            "src/main/kotlin/com/snipsnap/synth/OsteriaPresets.kt", "src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "src/main/kotlin/com/snipsnap/synth/OsteriaPresetCandidates.kt", "src/test/kotlin/com/snipsnap/synth/OsteriaPresetAuditionGenerator.kt",
        )
        val module = listOf(File("."), File("synth")).firstOrNull { File(it, paths.first()).isFile }
            ?: error("run from the synth module or repository root to record source provenance")
        return paths.associate { "synth/$it" to sha256(File(module, it)) }
    }

    private fun differenceRms(a: FloatArray, b: FloatArray): Double {
        check(a.size == b.size)
        return sqrt(a.indices.sumOf { i -> val d = a[i].toDouble() - b[i]; d * d } / a.size)
    }

    private fun normalizedDifferenceRms(a: FloatArray, b: FloatArray): Double {
        check(a.size == b.size)
        val ra = rms(a).coerceAtLeast(1e-12)
        val rb = rms(b).coerceAtLeast(1e-12)
        return sqrt(a.indices.sumOf { i -> val d = a[i] / ra - b[i] / rb; d * d } / a.size)
    }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.4f", value)
    private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    private fun group(id: String, title: String, description: String) = mapOf("id" to id, "title" to title, "description" to description)

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

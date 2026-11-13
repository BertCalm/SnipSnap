package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

/**
 * A focused listening gate for provisional Osteria source families. Every file is one
 * fixed-root model-two note, not a phrase or live-key instrument. The approved QUARTET
 * Evolving Note remains a reference; these candidates do not expand the preset roster.
 *
 * Run `./gradlew :synth:generateOsteriaVoiceAudition` from the repository root.
 */
object OsteriaVoiceAuditionGenerator {
    private val candidates = listOf(OsteriaVoice.BASS, OsteriaVoice.PLUCK, OsteriaVoice.REED, OsteriaVoice.PAD)
    private const val FIRST_GATE = 5.2
    private const val REGISTER_GATE = 3.0

    private data class Case(
        val id: String,
        val group: String,
        val title: String,
        val description: String,
        val patch: OsteriaPatch,
        val options: Osteria.ProbeOptions = Osteria.ProbeOptions(),
        val referenceId: String? = null,
        val variant: String? = null,
    )

    private val groups = listOf(
        group("first-listen", "Four candidate voices, one note",
            "Bass, Pluck, Reed and Pad at C3, velocity 0.8, seed 173 and ROOM 0. Each has one fixed root and one attack, holds for 5.2 seconds, then follows its family's authored release. Source character and articulation differ; the six macros are identical. The established QUARTET Evolving Note is the fifth sound for reference. These four families are listening candidates, not accepted presets."),
        group("register", "Hear a useful register",
            "The same candidate recipes in a practical register: Bass at C2; Pluck, Reed and Pad at C4. Each has a 3-second press followed by its family's release, with velocity 0.8, seed 173 and ROOM 0. These are separately rendered roots, not transposed samples."),
        group("motion", "Hold the color still",
            "Motion-disabled C3 versions of the first four candidates. Each keeps its first-listen source, articulation, 5.2-second press and six macro values; only the motion probe is disabled. Compare with the named moving reference in the first group. Neither version contains scheduled attacks or pitch changes."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) { "supply at most one output directory" }
        val root = File(args.firstOrNull() ?: "../testkit/osteria-voices-audition")
        val cases = cases()
        check(cases.size == 13 && cases.map { it.id }.distinct().size == cases.size)
        check(cases.all { it.patch.config.model == 2 && it.patch.macros.getValue("ROOM") == 0f && it.patch.macros.getValue("HOLD") == 0f })
        root.mkdirs()
        val hashes = sourceHashes()
        val started = System.nanoTime()
        val clips = ArrayList<Map<String, Any?>>(cases.size)
        val moving = mutableMapOf<OsteriaVoice, FloatArray>()
        val motionEvidence = linkedMapOf<String, Double>()
        val expectedPaths = mutableSetOf<String>()

        for ((index, case) in cases.withIndex()) {
            val renderStarted = System.nanoTime()
            val patch = case.patch
            val probe = Osteria.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, case.options)
            check(probe.channels == 2 && probe.sampleRate == Dsp.RATE && probe.stems.size == 4)
            val native = Snip(probe.samples, probe.channels, probe.sampleRate)
            check(native.samples.isNotEmpty() && native.samples.all(Float::isFinite)) { "empty or nonfinite candidate ${case.id}" }
            check(native.peak() >= .000001f && native.peak() < 1f) { "candidate ${case.id} is silent or exceeds native PCM headroom: ${native.peak()}" }
            check(probe.shoreTrace.isNotEmpty() && probe.shoreTrace.all { frame ->
                frame.timeSeconds.isFinite() && frame.positions.size == 4 && frame.remembered.size == 4 &&
                    (frame.positions + frame.remembered).all { it.isFinite() && it in 0.0..4.0 }
            }) { "invalid shore trace in ${case.id}" }
            val gate = native.durationSeconds.toDouble() - patch.config.releaseSeconds
            val expectedGate = if (case.group == "register") REGISTER_GATE else FIRST_GATE
            check(kotlin.math.abs(gate - expectedGate) < 1.0 / Dsp.RATE) { "incorrect simulated press in ${case.id}: $gate" }
            if (case.group == "first-listen" && patch.voice != OsteriaVoice.QUARTET) moving[patch.voice] = native.samples
            if (case.group == "motion") {
                val difference = differenceRms(moving.getValue(patch.voice), native.samples)
                check(difference > 1e-6) { "motion has no measurable effect in ${case.id}: $difference" }
                motionEvidence[patch.voice.name] = difference
            }
            val matched = AuditionLevel.level(native)
            Osteria.applyOutputBalance(patch.voice, matched.samples)
            check(matched.samples.all(Float::isFinite) && matched.peak() < 1f)
            val rawPath = "raw/${case.id}.wav"
            // A changed balance gets a new URL, so a returning listener cannot hear
            // a cached preview of the previous, louder reed after refreshing the page.
            val trimDb = Osteria.balanceTrimDbFor(patch.voice)
            val balanceSuffix = if (trimDb == 0.0) "" else "-balance${trimDb}dB"
            val matchedPath = "matched/${case.id}$balanceSuffix.wav"
            val recipePath = "recipes/${case.id}.json"
            val tracePath = "traces/${case.id}.csv"
            val rawFile = WavWriter.write(File(root, rawPath), native, WavWriter.BitDepth.PCM_24)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            writeRecipe(File(root, recipePath), case, gate)
            writeTrace(File(root, tracePath), probe)
            expectedPaths += listOf(rawPath, matchedPath, recipePath, tracePath)
            clips += linkedMapOf(
                "id" to case.id, "group" to case.group,
                "title" to case.title, "description" to case.description,
                "voice" to patch.voice.name, "model" to patch.config.model,
                "candidate" to (patch.voice != OsteriaVoice.QUARTET),
                "status" to if (patch.voice == OsteriaVoice.QUARTET) "Established reference" else "Provisional voice candidate; awaiting listening verdict",
                "config" to patch.toJsonValue().entries.getValue("config"),
                "midi" to patch.midi, "note" to noteName(patch.midi),
                "velocity" to patch.velocity, "macros" to patch.macros,
                "raw" to rawPath, "matched" to matchedPath,
                "recipe" to recipePath, "trace" to tracePath,
                "durationSeconds" to native.durationSeconds, "frames" to native.frameCount,
                "gateSeconds" to gate, "attackSeconds" to patch.config.attackSeconds,
                "releaseSeconds" to patch.config.releaseSeconds,
                "oneAttack" to true, "fixedRootPitch" to true,
                "peak" to native.peak(), "rms" to rms(native.samples),
                "loudestWindowRms" to Loudness.of(native),
                "matchedPeak" to matched.peak(), "matchedRms" to rms(matched.samples),
                "matchedLoudestWindowRms" to Loudness.of(matched),
                "balanceTrimDb" to Osteria.balanceTrimDbFor(patch.voice),
                "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                "variant" to case.variant, "referenceId" to case.referenceId,
                "probeOptions" to optionsMap(case.options),
                "seed" to patch.config.seed, "direction" to patch.config.direction,
                "tavernShore" to patch.config.tavernShore,
                "renderMs" to (System.nanoTime() - renderStarted) / 1e6,
                "shoreTrace" to probe.shoreTrace.filterIndexed { i, _ -> i % 5 == 0 || i == probe.shoreTrace.lastIndex }
                    .map { frame -> mapOf("timeSeconds" to frame.timeSeconds, "positions" to frame.positions, "remembered" to frame.remembered) },
            )
            println("Osteria candidate ${index + 1}/${cases.size}: ${case.id} · ${fmt(native.durationSeconds.toDouble())} s · peak ${fmt(native.peak().toDouble())}")
        }
        check(hashes == sourceHashes()) { "candidate source changed while rendering; regenerate from one settled source state" }
        val manifest = Json.write(Json.fromAny(linkedMapOf(
            "schemaVersion" to 2, "auditionKind" to "voice-candidates",
            "title" to "OSTERIA · Four voice candidates",
            "description" to "Audition provisional Bass, Pluck, Reed and Pad source families within the accepted single-note direction. Each render has one fixed root, one attack and one authored release. First compare the four families at C3 against the established QUARTET Evolving Note, then hear their registers and motion-disabled counterparts.",
            "engine" to "OSTERIA", "model" to 2,
            "status" to "Provisional voice candidates; no preset or pad-bank promotion before listening verdict",
            "candidateVoices" to candidates.map { it.name }, "referenceVoice" to OsteriaVoice.QUARTET.name,
            "sourceSha256" to hashes, "sampleRate" to Dsp.RATE, "channels" to 2, "bitDepth" to 24,
            "referenceMidi" to 48, "referenceVelocity" to .8f,
            "listeningTarget" to .03, "listeningPeakCeiling" to .99,
            "finiteGestures" to true, "finiteNotes" to true, "looping" to false,
            "pressBehavior" to "Every clip is one model-two note with a fixed root, one attack and one release. First-listen and motion-disabled notes simulate a 5.2-second press; register examples simulate a 3-second press. Family defaults set Bass attack 0.008/release 0.3 seconds, Pluck 0.005/0.35, Reed 0.07/0.5 and Pad 0.65/1.2. The established QUARTET reference retains attack 0.035/release 0.8 seconds. Exact values are saved per clip and recipe. These finite files do not follow a live key or pad release.",
            "metricNotes" to "Raw WAVs preserve native engine output without voice balance. Matched WAVs first apply AuditionLevel's common stereo gain rule: loudest 200 ms folded RMS target 0.03, peak guard 0.99. They then apply the same voice balance as production rendering: Reed -1.5 dB, Pad +1.5 dB, all other voices 0 dB. balanceTrimDb records that final adjustment; matched metrics describe the adjusted samples. Peak and RMS describe original interleaved stereo PCM, with RMS measured over the whole file. CSV traces retain all current and remembered shore positions; browser traces retain every fifth frame plus the last. First-listen families use identical Evolving Note macro values with MEMORY 0, WANDER 0.85 and TENSION 0.20; family source and articulation differ. ROOM and HOLD are zero throughout. No browser synthesis, external effects or seamless loops.",
            "groups" to groups, "clips" to clips,
            "summary" to linkedMapOf(
                "clipCount" to clips.size, "wavCount" to clips.size * 2,
                "maxRawPeak" to clips.maxOf { (it.getValue("peak") as Number).toDouble() },
                "allSamplesFinite" to true, "nativeHeadroomPassed" to true,
                "allOneAttack" to true, "allFixedRootPitch" to true,
                "motionOnOffDifferenceRms" to motionEvidence,
                "generationSeconds" to (System.nanoTime() - started) / 1e9,
            ),
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        copyPage(root, manifest)
        prunePrevious(root, expectedPaths)
        File(root, "README.md").writeText("""
            # Osteria voice candidates

            Thirteen finite, dry single-note clips: Bass, Pluck, Reed and Pad candidates
            at C3; the established QUARTET Evolving Note as reference; four separately
            rendered register examples; and four motion-disabled comparisons at C3.
            All use velocity 0.8, seed 173 and the same Evolving Note macro values.
            ROOM, HOLD and MEMORY are zero. Source families and their default
            articulation differ; exact values are saved in every clip and recipe.

            First-listen and still-color clips simulate a 5.2-second press followed
            by the authored family release. Register clips simulate a 3-second press.
            Each has one attack and one fixed root, with no retrigger or sequenced
            pitch. These files do not respond to a live key release and are not loops.

            Bass, Pluck, Reed and Pad are provisional. They have not been promoted
            into the accepted preset roster or existing 16-pad bank. Listening
            verdicts determine which source families should be developed further.

            Raw and matched 24-bit stereo WAVs are generated at ${Dsp.RATE} Hz.
            Raw preserves native gain without voice balance. Matched first uses
            AuditionLevel's common loudest folded 200 ms RMS target of 0.03 and
            peak ceiling 0.99, then applies the production voice balance: Reed
            -1.5 dB, Pad +1.5 dB; every other voice 0 dB. Clip and recipe metadata record
            balanceTrimDb, and matched metrics describe the adjusted samples.
            Recipes preserve complete patch state and probe flags; CSVs preserve
            numerical motion traces.
            To reproduce a still-color file, decode its patch and call Osteria.probe
            with the recipe's audition.probeOptions rather than Patch.render().

            Regenerate: ./gradlew :synth:generateOsteriaVoiceAudition
        """.trimIndent() + "\n")
        println("Wrote ${clips.size * 2} WAVs, ${clips.size} recipes, ${clips.size} CSV traces and the audition page under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        val evolving = OsteriaPresets.all().single { it.name == "Evolving Note" }
        check(evolving.voice == OsteriaVoice.QUARTET && evolving.config.model == 2 && evolving.midi == 48 && evolving.velocity == .8f)
        val first = candidates.associateWith { voice ->
            OsteriaPatch(
                name = "${familyName(voice)} candidate", voice = voice, macros = evolving.macros,
                midi = 48, velocity = .8f,
                config = Osteria.configurationFor(voice).let { it.copy(seed = 173, seconds = FIRST_GATE + it.releaseSeconds) },
            )
        }
        for (voice in candidates) {
            val patch = first.getValue(voice)
            add(Case(movingId(voice), "first-listen", "${familyName(voice)} · C3",
                "Provisional ${familyName(voice).lowercase()} source, one C3 trigger. ${articulation(patch, FIRST_GATE)} The timbre moves while root pitch stays fixed; MEMORY is zero.", patch,
                variant = "moving"))
        }
        add(Case("quartet-reference-c3", "first-listen", "Evolving Note · established reference",
            "The established QUARTET recipe, unchanged: one C3 trigger with a 0.035-second attack, 5.2-second press and 0.8-second release. Compare the new source families against this accepted single-note character.",
            evolving, variant = "established-reference"))
        for (voice in candidates) {
            val midi = if (voice == OsteriaVoice.BASS) 36 else 60
            val patch = first.getValue(voice).let { it.copy(midi = midi, config = it.config.copy(seconds = REGISTER_GATE + it.config.releaseSeconds)) }
            add(Case("${voice.name.lowercase()}-register-${noteName(midi).lowercase()}", "register", "${familyName(voice)} · ${noteName(midi)}",
                "The same provisional ${familyName(voice).lowercase()} recipe rendered directly at ${noteName(midi)}. ${articulation(patch, REGISTER_GATE)} No sample transposition or repeating note sequence.",
                patch, referenceId = movingId(voice), variant = "register"))
        }
        for (voice in candidates) {
            val patch = first.getValue(voice)
            add(Case("${voice.name.lowercase()}-still-c3", "motion", "${familyName(voice)} · fixed color",
                "The first-listen ${familyName(voice).lowercase()} C3 recipe with motion disabled. ${articulation(patch, FIRST_GATE)} Source, macro values, pitch and articulation match ${familyName(voice)} · C3; compare that moving reference in the first group.",
                patch, Osteria.ProbeOptions(motionEnabled = false), referenceId = movingId(voice), variant = "motion-off"))
        }
    }

    private fun articulation(patch: OsteriaPatch, gate: Double): String =
        "Attack ${number(patch.config.attackSeconds)} seconds, press ${number(gate)} seconds, release ${number(patch.config.releaseSeconds)} seconds."

    private fun writeRecipe(file: File, case: Case, gate: Double) {
        val recipe = LinkedHashMap(case.patch.toJsonValue().entries)
        recipe["audition"] = Json.fromAny(linkedMapOf(
            "id" to case.id, "auditionKind" to "voice-candidates",
            "candidate" to (case.patch.voice != OsteriaVoice.QUARTET),
            "probeOptions" to optionsMap(case.options), "nativeGain" to 1,
            "balanceTrimDb" to Osteria.balanceTrimDbFor(case.patch.voice),
            "oneAttack" to true, "fixedRootPitch" to true,
            "gateSeconds" to gate, "attackSeconds" to case.patch.config.attackSeconds,
            "releaseSeconds" to case.patch.config.releaseSeconds, "referenceId" to case.referenceId,
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

    private fun optionsMap(options: Osteria.ProbeOptions): Map<String, Boolean> = linkedMapOf(
        "couplingEnabled" to options.couplingEnabled, "memoryEnabled" to options.memoryEnabled,
        "motionEnabled" to options.motionEnabled, "roomEnabled" to options.roomEnabled,
    )

    private fun copyPage(root: File, manifest: String) {
        for ((resource, destination) in listOf(
            "osteria-audition.html" to "index.html", "osteria-audition.css" to "osteria-audition.css", "osteria-audition.js" to "osteria-audition.js",
        )) {
            val template = OsteriaVoiceAuditionGenerator::class.java.getResourceAsStream("/audition/$resource")
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
            "src/test/kotlin/com/snipsnap/synth/OsteriaVoiceAuditionGenerator.kt",
        )
        val module = listOf(File("."), File("synth")).firstOrNull { File(it, paths.first()).isFile }
            ?: error("run the generator from the synth module or repository root to record source provenance")
        return paths.associate { "synth/$it" to sha256(File(module, it)) }
    }

    private fun differenceRms(a: FloatArray, b: FloatArray): Double {
        check(a.size == b.size)
        return sqrt(a.indices.sumOf { i -> val d = a[i].toDouble() - b[i]; d * d } / a.size)
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun movingId(voice: OsteriaVoice) = "${voice.name.lowercase()}-moving-c3"
    private fun familyName(voice: OsteriaVoice) = voice.name.lowercase().replaceFirstChar(Char::uppercase)
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

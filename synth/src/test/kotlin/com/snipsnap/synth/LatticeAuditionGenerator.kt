package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Mpc3Exporter
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The fourth LATTICE audition: one moving pluck beside its plain source, plus preserved earlier studies.
 * Every primary clip retains the engine's common
 * source gain. Optional matched files are separate listening aids, never the restoration proof.
 *
 * Generate: LatticeAuditionGenerator [output-directory]
 * Replay:   LatticeAuditionGenerator --replay recipe.json output.wav
 *
 * Audition wrappers replay a performance; separate patch downloads use the native Patch format.
 */
object LatticeAuditionGenerator {
    private const val MODEL = 2
    private const val RATE = 44_100
    private const val MATCHED_RMS = .04
    private const val PHRASE_GAIN = .85
    private val SOURCE_FILES = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Lattice.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/LatticePatch.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/LatticePresets.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Presets.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/UserPresets.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/Spread.kt",
        "cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt",
        "cli/src/main/kotlin/com/snipsnap/cli/Main.kt",
        "app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/LatticeTest.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/LatticePatchTest.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/LatticeHeldTest.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/PresetsTest.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt",
        "shell/src/test/kotlin/com/snipsnap/shell/StarterKitsTest.kt",
        "shell/src/test/kotlin/com/snipsnap/shell/LatticeSurfaceTest.kt",
        "cli/src/test/kotlin/com/snipsnap/cli/LatticeSynthCommandTest.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/LatticeAuditionGenerator.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/LatticeGestureStudy.kt",
        "docs/lattice-audition/index.html",
        "docs/lattice-audition/styles.css",
        "docs/lattice-audition/app.js",
    )

    private data class Rendered(
        val snip: Snip,
        val diagnostics: Map<String, Any?>,
        val renderMillis: Double,
        val held: Lattice.HeldProbe? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size == 3) { "usage: --replay recipe.json output.wav" }
            val recipe = Json.parse(File(args[1]).readText()).obj()
            val replay = renderRecipe(recipe)
            WavWriter.write(File(args[2]), replay.snip, WavWriter.BitDepth.PCM_16)
            println("Replayed ${recipe.getValue("kind").str()} recipe to ${args[2]}")
            return
        }
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "usage: LatticeAuditionGenerator [output-directory]"
        }
        val output = File(args.firstOrNull() ?: "../testkit/lattice-audition").absoluteFile
        require(output.isDirectory || output.mkdirs()) { "cannot create $output" }
        val repo = repositoryRoot()
        val revision = git(repo, "rev-parse", "HEAD")
        val sourceDirty = git(repo, "status", "--porcelain", "--untracked-files=normal")?.isNotEmpty() ?: true
        val latticeTests = listOf("synth", "shell", "cli").flatMap { module ->
            File(repo, "$module/src/test/kotlin/com/snipsnap/$module").listFiles().orEmpty()
                .filter { it.name.startsWith("Lattice") && it.name.endsWith("Test.kt") }
                .map { it.relativeTo(repo).invariantSeparatorsPath }
        }
        val sourceFiles = (SOURCE_FILES + latticeTests).distinct().filter { File(repo, it).isFile }
        val sourceHashes = sourceFiles.associateWith { sha256(File(repo, it)) }
        val clips = mutableListOf<Map<String, Any?>>()
        val evidence = mutableListOf<Map<String, Any?>>()
        val started = System.nanoTime()
        var replayCount = 0
        var matchedCount = 0

        fun add(
            id: String,
            title: String,
            description: String,
            section: String,
            tags: List<String>,
            recipe: Map<String, Any?>,
            matchedRecipe: Map<String, Any?>? = null,
            cues: List<Map<String, Any?>> = emptyList(),
            patchFile: String? = null,
        ) {
            fun document(body: Map<String, Any?>) = linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "engine" to "LATTICE_FINITE_PROTOTYPE",
                "model" to MODEL,
                "sourceCommit" to revision,
                "sourceFilesSha256" to sourceHashes,
                "notes" to if (body["kind"] in listOf("native-phrase", "native-hold"))
                    "Audition performance wrapper. Replay with LatticeAuditionGenerator --replay recipe.json output.wav; separate patches/ files use the native Patch format."
                else "Finite prototype harness recipe. Replay with LatticeAuditionGenerator --replay recipe.json output.wav; not an app Patch recipe.",
            ).apply { putAll(body) }
            val recipeDocument = document(recipe)
            val recipeText = Json.write(json(recipeDocument))
            val parsedRecipe = Json.parse(recipeText).obj()
            val rendered = renderRecipe(parsedRecipe)
            requireAudio(id, rendered.snip)
            val file = "assets/$id.wav"
            val recipeFile = "recipes/$id.json"
            val audioFile = File(output, file)
            WavWriter.write(audioFile, rendered.snip, WavWriter.BitDepth.PCM_16)
            File(output, recipeFile).apply { parentFile.mkdirs(); writeText(recipeText) }
            // Browser metrics describe the quantized, exported WAV, not a pre-export float buffer.
            val exported = WavReader.read(audioFile)
            val stats = statistics(exported)
            val replay = renderRecipe(parsedRecipe)
            check(rendered.snip.samples.contentEquals(replay.snip.samples)) { "$id recipe replay differs" }
            replayCount++
            val pitch = if (stats.getValue("peak") > 1e-5 && parsedRecipe.getValue("kind").str() == "static") {
                Pitch.detect(exported, fromSec = .12f, windowSec = .25f)
            } else null
            val midi = if (parsedRecipe.getValue("kind").str() in listOf("static", "restoration")) parsedRecipe["settings"]?.obj()?.get("midi")?.int() else null
            val expectedHz = midi?.let { 440.0 * 2.0.pow((it - 69) / 12.0) }
            val metrics = LinkedHashMap<String, Any?>(stats).apply {
                put("renderMs", rendered.renderMillis)
                if (expectedHz != null) put("expectedHz", expectedHz)
                if (pitch != null) {
                    put("pitchHz", pitch.hz)
                    put("pitchConfidence", pitch.confidence)
                    if (expectedHz != null) put("pitchErrorCents", 1200 * ln(pitch.hz / expectedHz) / ln(2.0))
                }
                rendered.held?.let { held ->
                    put("expectedHz", held.requestedHz)
                    put("requestedHz", held.requestedHz)
                    put("fittedHz", held.fittedHz)
                    put("pitchErrorCents", 1200 * ln(held.fittedHz / held.requestedHz) / ln(2.0))
                    put("seamError", held.seamError)
                    put("loopFrames", held.loopFrames)
                    put("repeatCount", parsedRecipe.getValue("repeatCount").int())
                    put("heldRenderMs", held.renderMillis)
                }
            }
            var matchedFile: String? = null
            var matchedRecipeFile: String? = null
            var matchedMetrics: Map<String, Double>? = null
            if (matchedRecipe != null) {
                val text = Json.write(json(document(matchedRecipe)))
                val decoded = Json.parse(text).obj()
                val matchedRendered = renderRecipe(decoded)
                requireAudio("$id-matched", matchedRendered.snip)
                check(matchedRendered.snip.samples.contentEquals(renderRecipe(decoded).snip.samples)) { "$id matched recipe replay differs" }
                replayCount++
                matchedCount++
                matchedFile = "assets/$id-matched.wav"
                matchedRecipeFile = "recipes/$id-matched.json"
                WavWriter.write(File(output, matchedFile), matchedRendered.snip, WavWriter.BitDepth.PCM_16)
                File(output, matchedRecipeFile).writeText(text)
                matchedMetrics = statistics(WavReader.read(File(output, matchedFile)))
            }
            val row = linkedMapOf<String, Any?>(
                "id" to id, "title" to title, "description" to description,
                "section" to section, "tags" to tags, "file" to file,
                "recipeFile" to recipeFile, "duration" to exported.durationSeconds,
                "metrics" to metrics, "waveform" to waveform(exported.samples),
                "settings" to (recipe["settings"] ?: recipe),
                "renderSettings" to recipe["renderSettings"],
                "preferredComparisonMode" to if (matchedFile != null) "matched" else "common",
                "recipeRoundtripExact" to true, "wavSha256" to sha256(audioFile),
                "recipeSha256" to sha256(File(output, recipeFile)),
            )
            if (matchedFile != null) {
                row["matchedFile"] = matchedFile
                row["matchedRecipeFile"] = matchedRecipeFile
                row["matchedMetrics"] = matchedMetrics
            }
            if (patchFile != null) {
                row["patchFile"] = patchFile
                row["patchSha256"] = sha256(File(output, patchFile))
            }
            rendered.held?.let { held ->
                val previousFile = "diagnostics/$id-previous.wav"
                val cycleFile = "diagnostics/$id-cycle.wav"
                WavWriter.write(File(output, previousFile), Snip(held.previous, channels = 1, sampleRate = RATE), WavWriter.BitDepth.PCM_16)
                WavWriter.write(File(output, cycleFile), held.snip, WavWriter.BitDepth.PCM_16)
                row["heldEvidence"] = mapOf("previousFile" to previousFile, "cycleFile" to cycleFile,
                    "previousSha256" to sha256(File(output, previousFile)), "cycleSha256" to sha256(File(output, cycleFile)))
            }
            if (cues.isNotEmpty()) row["cues"] = cues
            clips += row
            evidence += linkedMapOf(
                "id" to id, "metrics" to metrics, "diagnostics" to rendered.diagnostics,
                "matchedMetrics" to matchedMetrics,
                "recipeRoundtripExact" to true, "wavSha256" to sha256(audioFile),
            )
            println("LATTICE ${clips.size}: $id, peak=${stats.getValue("peak")}, rms=${stats.getValue("rms")}")
        }

        val focused = sequenceRecipe(listOf(
            "A · Plain pluck" to gesturePhraseRecipe(moving = false),
            "B · Body into ringing tail" to gesturePhraseRecipe(moving = true),
        ))
        add(
            "pluck-to-outline", "A plain pluck. A body that becomes a ringing tail.",
            "A plays the plain three-note phrase. B plays the same notes while the body thins and gives way to a tuned ringing tail. Compare the change of body and the space after each note. This moving version is an audition prototype; native patches remain static.",
            "focus", listOf("START HERE", "SAME THREE NOTES", "COMMON GAIN"), focused,
            cues = sequenceCues(focused),
        )

        val sourceBase = Lattice.Settings(
            midi = 48, velocity = .62f, turn = 0.0, gateCount = 2.0,
            color = 0.0, reject = 0.0, haloEnabled = false,
            durationSeconds = .8, excitationSeconds = .26,
            sourceProfile = Lattice.SourceProfile.WARM, contour = Lattice.Contour.PLUCK,
        )
        for ((profile, title, description) in listOf(
            Triple(Lattice.SourceProfile.WARM, "Warm · three notes", "A new rounded source candidate, played as C3, G3 and C4. All gates are aligned; this is the input tone before directional shaping."),
            Triple(Lattice.SourceProfile.PLUCK, "Pluck · the same notes", "The plucked source candidate on exactly the same notes and velocities. All gates remain aligned."),
            Triple(Lattice.SourceProfile.HOLLOW, "Hollow · the same notes", "The hollow source candidate on exactly the same short phrase. Choose its input character before judging the geometry."),
        )) {
            add(
                "source-${profile.name.lowercase()}", title, description, "source",
                listOf("SOURCE CHOICE", "C3 → C4"), phraseRecipe(sourceBase.copy(sourceProfile = profile)),
            )
        }

        val heroKeyframes = listOf(
            keyframe(0.0, 0.0, 2.0), keyframe(1.0, 0.0, 2.0),
            keyframe(1.6, 1.0, 2.0), keyframe(2.3, 1.0, 2.0),
            keyframe(3.1, 1.0, 3.0), keyframe(4.1, 1.0, 3.0),
            keyframe(5.0, 1.0, 4.0), keyframe(6.0, 1.0, 4.0),
            keyframe(7.0, 1.0, 16.0), keyframe(8.5, 1.0, 16.0),
        )
        add(
            "restoration", "The passage closes and returns",
            "A shorter C3 proof with the WARM source held at steady effort: aligned, crossed silence, then 3, 4 and 16 gates. No COLOR, halo, bypass oscillator or makeup gain.",
            "gesture", listOf("9.6 SECOND PROOF", "COMMON GAIN"),
            mapOf(
                "kind" to "restoration", "settings" to settingsMap(sourceBase.copy(
                    velocity = .62f, contour = Lattice.Contour.SUSTAIN,
                    durationSeconds = 9.6, excitationSeconds = 8.5,
                )),
                "trajectory" to heroKeyframes, "interpolation" to "smoothstep",
            ), cues = listOf(
                cue(0.0, "Aligned"), cue(1.0, "Crossing"), cue(1.6, "Silence"),
                cue(2.3, "Insert"), cue(3.1, "3 gates"), cue(5.0, "4 gates"),
                cue(7.0, "16 gates"), cue(8.5, "Release"),
            ),
        )

        fun addComparison(
            id: String, title: String, description: String, section: String,
            a: Lattice.Settings, b: Lattice.Settings, labelA: String, labelB: String,
            levelMatched: Boolean,
        ) {
            val comparison = comparisonRecipes(a, b, labelA, labelB, levelMatched)
            add(
                id, title, description, section, listOf("SAME PHRASE", "A / B / A / B"),
                comparison.common, matchedRecipe = comparison.matched,
                cues = sequenceCues(comparison.common),
            )
        }

        val crossed = sourceBase.copy(turn = 1.0)
        addComparison(
            "two-vs-three", "A silent path. B a third gate.",
            "The same three-note performance repeats A/B/A/B. A has crossed endpoints and two gates, so its transmission is intentionally silent. B inserts the third gate. One common gain preserves the restoration.",
            "comparison", crossed.copy(gateCount = 2.0), crossed.copy(gateCount = 3.0),
            "A · 2 gates", "B · 3 gates", levelMatched = false,
        )
        addComparison(
            "three-vs-sixteen", "Small steps restore more passage",
            "A uses three gates; B uses sixteen. Notes, phases, source effort and gain are identical across the repeated phrase. This comparison demonstrates transmission level, rather than an unrelated new sound.",
            "comparison", crossed.copy(gateCount = 3.0), crossed.copy(gateCount = 16.0),
            "A · 3 gates", "B · 16 gates", levelMatched = false,
        )

        for (profile in listOf(Lattice.SourceProfile.WARM, Lattice.SourceProfile.HOLLOW)) {
            val base = sourceBase.copy(sourceProfile = profile, turn = .90, gateCount = 2.0)
            addComparison(
                "color-${profile.name.lowercase()}", "${profile.name.lowercase().replaceFirstChar { it.uppercase() }} · plain versus colored",
                "A keeps all harmonic groups on the same path. B changes their directional relationships, including the low harmonics. The same phrase repeats A/B/A/B. The optional diagnostic copy matches A and B separately before repeating them; common-gain audio remains available.",
                "color", base.copy(color = 0.0), base.copy(color = 1.0),
                "A · COLOR 0", "B · COLOR 1", levelMatched = true,
            )
        }

        val rejected = sourceBase.copy(turn = .85, gateCount = 4.0, color = .6, reject = 1.0, haloEnabled = true)
        val rejectionComparison = rejected.copy(sourceProfile = Lattice.SourceProfile.PLUCK, turn = 1.0, gateCount = 3.0)
        addComparison(
            "twist-early-late", "Early rejection versus late rejection",
            "The PLUCK source repeats A/B/A/B with REJECT isolating its outline. A concentrates the turn near the entrance; B near the exit. RMS matching exposes a subtler change than COLOR; common-gain audio also retains the natural level difference.",
            "rejection", rejectionComparison.copy(twist = 0.0), rejectionComparison.copy(twist = 1.0),
            "A · Early turn", "B · Late turn", levelMatched = true,
        )
        val branches = sequenceRecipe(listOf(
            "A · Passing tone" to phraseRecipe(rejected.copy(twist = .5, reject = 0.0)),
            "B · Tone + outline" to phraseRecipe(rejected.copy(twist = .5, reject = .4)),
            "C · Outline only" to phraseRecipe(rejected.copy(twist = .5, reject = 1.0)),
        ))
        add(
            "dry-mixed-outline", "Passing tone → mixed → outline",
            "Three presentations of exactly the same notes and geometry: transmitted tone, REJECT 40%, then the rejected outline alone. These branches retain common source gain, so their level differences remain part of the mechanism.",
            "rejection", listOf("SAME PHRASE", "COMMON GAIN"), branches, cues = sequenceCues(branches),
        )

        val musicalNotes = listOf(48, 51, 55, 60, 58, 55)
        for ((id, title, settings) in listOf(
            Triple("warm-passage", "Warm passage · a small melody", sourceBase.copy(turn = 1.0, gateCount = 8.0, color = .45)),
            Triple("plucked-outline", "Plucked outline · the same melody", sourceBase.copy(
                sourceProfile = Lattice.SourceProfile.PLUCK, turn = .85, gateCount = 4.0,
                color = .7, twist = .78, reject = .35, haloEnabled = true,
            )),
        )) {
            add(
                id, title, "A short C-minor candidate, played through the new source and directional chain. This is a listening proposal, with no external effects or loudness makeup.",
                "musical", listOf("MUSICAL CANDIDATE", "C MINOR"),
                phraseRecipe(settings, notes = musicalNotes, spacing = .26, noteSeconds = .9),
            )
        }

        val nativePresets = LatticePresets.all()
        check(nativePresets.size == 12) { "the native listening bank must contain twelve patches" }
        val patchFiles = nativePresets.associateWith { patch ->
            val file = "patches/${patch.voice.name.lowercase()}-${slug(patch.name)}.json"
            File(output, file).apply { parentFile.mkdirs(); writeText(patch.toJsonText()) }
            check(Patches.fromJsonText(File(output, file).readText()) == patch) { "native patch did not round-trip: ${patch.name}" }
            file
        }
        archive(File(output, "lattice-patches.zip"), patchFiles.values.map { it to File(output, it) })
        for (voice in LatticeVoice.entries) {
            val preset = nativePresets.first { it.voice == voice && !Lattice.isLoop(it.macros.getValue("HOLD")) }
            add(
                "native-${voice.name.lowercase()}", "${preset.name} · native patch",
                "${preset.name} plays C3, G3 and C4 with the ${voice.name.lowercase()} source. Compare its short notes with the sustained HOLD example below. Use Patch to keep this instrument in SnipSnap.",
                "instrument", listOf("NATIVE PATCH", "C3 → C4"), nativePhraseRecipe(preset),
                patchFile = patchFiles.getValue(preset),
            )
            val held = nativePresets.first { it.voice == voice && Lattice.isLoop(it.macros.getValue("HOLD")) }
            add(
                "hold-${voice.name.lowercase()}", "${held.name} · four seamless cycles",
                "Four uninterrupted repeats of ${held.name}. Listen across the joins: the held tone continues without being struck again. This held voice is a new listening proposal.",
                "hold", listOf("NATIVE HOLD", "FOUR CYCLES"), nativeHoldRecipe(held),
                patchFile = patchFiles.getValue(held),
            )
        }
        val starterKit = exportNativeKit(output)

        // Silence is measured as evidence, rather than occupying another source-choice card.
        val nullProbe = Lattice.probe(sourceBase.copy(
            turn = 1.0, gateCount = 2.0, contour = Lattice.Contour.SUSTAIN,
            durationSeconds = .8, excitationSeconds = .55,
        ))
        val nullFile = File(output, "diagnostics/crossed-null.wav")
        WavWriter.write(nullFile, nullProbe.snip, WavWriter.BitDepth.PCM_16)
        val silentPeak = statistics(WavReader.read(nullFile)).getValue("peak")
        val referenceChecks = referenceChecks()
        val peak = clips.maxOf { ((it.getValue("metrics") as Map<*, *>)["peak"] as Number).toDouble() }
        val maxResidual = evidence.maxOf { ((it.getValue("diagnostics") as Map<*, *>)["allocationResidual"] as? Number)?.toDouble() ?: 0.0 }
        check(clips.size == 19 && replayCount == 22 && matchedCount == 3) { "incomplete Lattice R4 listening pack" }
        check(silentPeak == 0.0) { "crossed fixture is not digital silence after PCM16 export: $silentPeak" }
        check(referenceChecks.all { abs((it.getValue("error") as Number).toDouble()) < 1e-12 }) { "analytic reference failed" }
        val validation = linkedMapOf<String, Any?>(
            "clipCount" to clips.size, "recipeReplayCount" to replayCount,
            "matchedRecipeCount" to matchedCount,
            "allRecipesSampleIdentical" to (replayCount == clips.size + matchedCount),
            "crossedPcm16Peak" to silentPeak, "maxPrimaryExportedPeak" to peak,
            "maxAllocationResidual" to maxResidual,
            "finiteOnly" to false, "externalEffects" to false,
            "commonGainReference" to true, "phraseAssemblyGain" to PHRASE_GAIN,
            "holdImplemented" to true, "nativePresetCount" to nativePresets.size,
            "heldClipCount" to clips.count { it["section"] == "hold" },
            "maxHeldSeamError" to clips.mapNotNull { (it["metrics"] as? Map<*, *>)?.get("seamError") as? Number }.maxOf { it.toDouble() },
            "starterKitPadCount" to starterKit.getValue("padCount"),
            "listeningVerdict" to "The owner accepted the R2 source improvement and clearer comparisons. Moving pluck character, native factory phrases, HOLD and MPC playback await listening review.",
        )
        val engine = linkedMapOf<String, Any?>(
            "name" to "Lattice", "model" to MODEL, "sourceCommit" to revision,
            "uncommittedPrototype" to sourceDirty, "sourceFilesSha256" to sourceHashes,
            "sampleRate" to RATE, "oversample" to 4, "channels" to 1, "bitDepth" to 16,
            "notes" to listOf(
                "Kotlin directional-gate instrument rendered through SnipSnap's standard 4×/44.1 kHz mono path.",
                "Primary audio preserves the same fixed source gain across gate settings. Exact silence is intentional.",
                "The accepted R2 WARM, PLUCK and HOLLOW sources and all twelve existing clips retain their original render settings. Native patches save voice, model, MIDI root, velocity and seven controls explicitly.",
                "Matched COLOR/TWIST variants scale each A/B condition separately before concatenation. Repeated A/B segments reuse those exact gains. Source and restoration recordings remain common-gain.",
                "R3 adds twelve native patches, three factory phrases, three periodic HOLD studies and a dry sixteen-pad MPC starter. Held studies repeat one settled source cycle four times without seam repair or makeup gain.",
                "Standard patch, preset, velocity, CLI and phone-picker registration is present in source. Phone behavior and hardware MPC playback require their own device checks.",
                "R4 adds one authored moving-pluck A/B phrase using the unchanged projection and reject collector. The curve is saved in its audition recipe; native patches and HOLD remain static.",
            ),
        )
        val measurements = linkedMapOf<String, Any?>(
            "generatedAt" to Instant.now().toString(), "engine" to engine,
            "metricDefinition" to "Peak/RMS/DC and pitch are measured from exported PCM16 WAVs; gate allocation diagnostics are calculated inside the actual Kotlin engine. Waveform display alone is normalized per clip.",
            "matchedDefinition" to "Diagnostic A/B condition scalar, applied before sequence assembly: min(0.04 / condition RMS, 0.55 / condition sample peak). Each repeated condition retains the same scalar. Authoritative matched recipes save every gain; this is not engine output leveling.",
            "referenceChecks" to referenceChecks, "validation" to validation, "starterKit" to starterKit, "clips" to evidence,
        )
        File(output, "measurements.json").writeText(Json.write(json(measurements)))
        val manifest = linkedMapOf<String, Any?>(
            "revision" to 4,
            "title" to "Lattice · from pluck to ringing tail",
            "subtitle" to "One plain pluck beside the same notes losing their body into a tuned ringing tail.",
            "revisionTitle" to "A pluck.\nThen its ringing trace.",
            "revisionSummary" to "Start with one short A/B phrase. A is the plain source; B changes its harmonic balance and closes the direct path, leaving a resonant tail. Earlier source, native instrument and HOLD studies remain below.",
            "revisionNote" to "The moving example is an audition prototype using the existing engine. It has not added automatic motion to native patches, and its musical character still needs your listening verdict.",
            "heroClipId" to "pluck-to-outline",
            "preferredComparisonMode" to "matched",
            "generatedAt" to Instant.now().toString(), "engine" to engine,
            "clips" to clips, "referenceChecks" to referenceChecks, "validation" to validation,
            "downloads" to mapOf("zip" to "lattice-audition.zip", "measurements" to "measurements.json", "source" to "lattice-source.zip",
                "patches" to "lattice-patches.zip", "kit" to "lattice-mpc-kit.zip"),
            "starterKit" to starterKit,
        )
        File(output, "manifest.json").writeText(Json.write(json(manifest)))
        // Retain the same listening interface for local regeneration and hosted publication.
        val page = File(repo, "docs/lattice-audition")
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val template = File(page, name)
            if (template.isFile) template.copyTo(File(output, name), overwrite = true)
        }
        File(output, "REPLAY.txt").writeText("""
            LATTICE — moving pluck, native instrument and HOLD audition, model $MODEL

            Generate from the SnipSnap checkout:
              ./gradlew --no-daemon :synth:generateLatticeAudition

            Replay a recipe through LatticeAuditionGenerator:
              ./gradlew --no-daemon :synth:generateLatticeAudition \
                -PlatticeRecipe=/absolute/path/recipes/two-vs-three.json \
                -PlatticeReplayOutput=/absolute/path/replay.wav

            recipes/pluck-to-outline.json compares identical PLUCK source notes with authored gate motion.
            Its motion-phrase events save every TURN/STAGES-equivalent gate count/TWIST/COLOR/REJECT
            keyframe and smoothstep interpolation. The curve is an audition proposal, not a native macro.
            The direct path closes at .44 s, source effort ends at .46 s, and the later tail comes
            from the existing signed-rejection resonators. No extra oscillator or effect is added.
            recipes/ contains audition-performance wrappers, not app Patch/PadRecipe files.
            patches/ and lattice-patches.zip contain all twelve standard native LATTICE patches.
            native-phrase wrappers save a full native patch for every note. native-hold wrappers
            save a full held patch and repeatCount4, repeating its one native loop without fades.
            Primary WAVs retain common source gain. Matched COLOR/TWIST WAVs have their own recipes,
            with condition gains applied before A/B/A/B assembly. Replay the matching -matched.json
            recipe to reproduce a matched WAV. No whole-comparison normalization is performed.
            Source profiles and contours are saved explicitly. The two-gate crossed reference is
            intentionally silent; its standalone evidence is diagnostics/crossed-null.wav.
            diagnostics/ includes the actual preceding cycle and a single exported held cycle
            for each HOLD study; measurements.json records the engine's measured seam and pitch fit.
            lattice-mpc-kit.zip contains the sixteen-pad native MPC3 track, MPC2 twin and source kit.
            Enable whole-sample looping manually on MPC pads A14–A16 for indefinite HOLD.
            The accepted R2 source choices and comparisons remain; new presets and HOLD still
            need listening review, and phone/MPC behavior needs device checks. No external effects.
            Source revision: $revision
            Exact working-tree source fingerprints are in manifest.json and each recipe.
        """.trimIndent() + "\n")
        val sourcePaths = (sourceFiles + listOf(
            "synth/build.gradle.kts", "docs/LATTICE.md", "docs/SYNTH_ROADMAP.md",
            "docs/lattice-audition/index.html", "docs/lattice-audition/styles.css", "docs/lattice-audition/app.js",
        )).distinct()
        archive(File(output, "lattice-source.zip"), sourcePaths.map { it to File(repo, it) }.filter { it.second.isFile })
        val pack = File(output, "lattice-audition.zip")
        archive(pack, output.walkTopDown().filter { it.isFile && it != pack }.map {
            it.relativeTo(output).invariantSeparatorsPath to it
        }.toList())
        println("Wrote ${clips.size} LATTICE clips and sample-identical recipes to $output in ${(System.nanoTime() - started) / 1e9}s")
    }

    private fun archive(destination: File, files: List<Pair<String, File>>) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            for ((name, file) in files.sortedBy { it.first }) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun renderRecipe(recipe: Map<String, JsonValue>, depth: Int = 0): Rendered {
        require(depth <= 4) { "audition recipe nesting is too deep" }
        recipe["model"]?.let { require(it.int() == MODEL) { "unsupported Lattice audition model" } }
        val started = System.nanoTime()
        return when (recipe.getValue("kind").str()) {
            "native-phrase" -> {
                val events = recipe.getValue("events").arr()
                val duration = recipe.getValue("durationSeconds").num()
                val gain = recipe.getValue("assemblyGain").num()
                require(events.size in 1..16 && duration.isFinite() && duration in .1..30.0) { "native phrase exceeds audition bounds" }
                require(gain.isFinite() && gain in 0.0..1.0) { "native phrase gain must be attenuation-only" }
                val samples = DoubleArray((duration * RATE).roundToInt())
                var residual = 0.0
                for (event in events) {
                    val obj = event.obj()
                    val patch = nativePatch(obj.getValue("patch"))
                    require(!Lattice.isLoop(patch.macros.getValue("HOLD"))) { "a native phrase note must be finite" }
                    val time = obj.getValue("timeSeconds").num()
                    require(time.isFinite() && time in 0.0..duration) { "native phrase event starts outside render" }
                    val note = patch.render()
                    val offset = (time * RATE).roundToInt()
                    require(offset + note.samples.size <= samples.size) { "native phrase truncates a note" }
                    for (i in note.samples.indices) samples[offset + i] += note.samples[i]
                    val settings = Lattice.settingsFor(patch.voice, patch.macros, patch.midi, patch.velocity)
                    residual = max(residual, Lattice.compile(settings.controls()).groups.maxOf { abs(it.allocationResidual) })
                }
                Rendered(Snip(FloatArray(samples.size) { (samples[it] * gain).toFloat() }, channels = 1, sampleRate = RATE),
                    mapOf("allocationResidual" to residual, "assemblyGain" to gain, "events" to events.size,
                        "nativePatchRender" to true, "externalEffects" to false), (System.nanoTime() - started) / 1e6)
            }
            "native-hold" -> {
                val patch = nativePatch(recipe.getValue("patch"))
                require(Lattice.isLoop(patch.macros.getValue("HOLD"))) { "a held wrapper requires a native HOLD patch" }
                val repeat = recipe.getValue("repeatCount").int()
                require(repeat in 1..8) { "HOLD repeat count must be 1..8" }
                val settings = Lattice.settingsFor(patch.voice, patch.macros, patch.midi, patch.velocity)
                // This is the native HOLD render path. One settled source cycle supplies every repeat.
                val held = Lattice.heldProbe(settings)
                val repeated = FloatArray(held.loopFrames * repeat)
                for (cycle in 0 until repeat) held.snip.samples.copyInto(repeated, cycle * held.loopFrames)
                val residual = Lattice.compile(settings.controls()).groups.maxOf { abs(it.allocationResidual) }
                Rendered(Snip(repeated, channels = 1, sampleRate = RATE), mapOf(
                    "allocationResidual" to residual, "seamError" to held.seamError,
                    "requestedHz" to held.requestedHz, "fittedHz" to held.fittedHz,
                    "loopFrames" to held.loopFrames, "repeatCount" to repeat, "heldRenderMs" to held.renderMillis,
                    "previousCycleMeasured" to true, "nativePatchRender" to true,
                    "seamRepair" to false, "externalEffects" to false,
                ), (System.nanoTime() - started) / 1e6, held)
            }
            "static", "restoration" -> {
                val settings = settingsFrom(recipe.getValue("settings").obj())
                val probe = if (recipe.getValue("kind").str() == "restoration") {
                    Lattice.probe(settings, trajectoryControls(recipe, settings))
                } else Lattice.probe(settings)
                Rendered(probe.snip, diagnostics(probe.diagnostics), (System.nanoTime() - started) / 1e6)
            }
            "phrase", "motion-phrase" -> {
                val events = recipe.getValue("events").arr()
                val duration = recipe.getValue("durationSeconds").num()
                val gain = recipe["assemblyGain"]?.num() ?: 1.0
                require(events.size in 1..64 && duration.isFinite() && duration in .1..60.0) { "phrase exceeds finite audition bounds" }
                require(gain.isFinite() && gain in 0.0..1.0) { "phrase assembly gain must be attenuation-only" }
                val samples = DoubleArray((duration * RATE).roundToInt())
                var residual = 0.0
                var sourceGain: Double? = null
                val stageEnergy = DoubleArray(16)
                val motion = recipe.getValue("kind").str() == "motion-phrase"
                val noteStudies = mutableListOf<Map<String, Any?>>()
                for (event in events) {
                    val obj = event.obj()
                    val settings = settingsFrom(obj.getValue("settings").obj())
                    val probe = if (motion) {
                        require(settings.durationSeconds >= 1.2) { "moving study notes must include the 1.2 second evidence window" }
                        require(recipe.getValue("interpolation").str() == "smoothstep") { "unsupported motion interpolation" }
                        val frames = obj.getValue("trajectory").arr().map { value ->
                            val row = value.obj()
                            LatticeGestureStudy.Frame(row.getValue("time").num(), row.getValue("turn").num(),
                                row.getValue("gateCount").num(), row.getValue("twist").num(),
                                row.getValue("color").num(), row.getValue("reject").num())
                        }
                        Lattice.probe(settings, LatticeGestureStudy.controls(frames, settings.durationSeconds))
                    } else Lattice.probe(settings)
                    if (motion) noteStudies += LatticeGestureStudy.evidence(probe, settings) + mapOf("midi" to settings.midi)
                    val eventTime = obj.getValue("timeSeconds").num()
                    require(eventTime.isFinite() && eventTime in 0.0..duration) { "phrase event starts outside the render" }
                    val offset = (eventTime * RATE).roundToInt()
                    if (motion) require(offset + probe.snip.samples.size <= samples.size) { "motion phrase truncates a note" }
                    for (i in probe.snip.samples.indices) if (offset + i in samples.indices) samples[offset + i] += probe.snip.samples[i]
                    residual = max(residual, probe.diagnostics.allocationResidual)
                    sourceGain = probe.diagnostics.sourceGain
                    for (stage in stageEnergy.indices) stageEnergy[stage] += probe.diagnostics.stageRejectedEnergy[stage]
                }
                Rendered(
                    Snip(FloatArray(samples.size) { (samples[it] * gain).toFloat() }, channels = 1, sampleRate = RATE),
                    linkedMapOf<String, Any?>("allocationResidual" to residual, "sourceGain" to sourceGain, "stageRejectedEnergy" to stageEnergy.toList(), "assemblyGain" to gain, "events" to events.size).apply {
                        if (motion) {
                            put("authoredMotion", true)
                            put("nativePatchControl", false)
                            put("noteStudies", noteStudies)
                            put("externalEffects", false)
                            put("outputNormalization", false)
                        }
                    },
                    (System.nanoTime() - started) / 1e6,
                )
            }
            "sequence" -> {
                val components = recipe.getValue("components").arr()
                val duration = recipe.getValue("durationSeconds").num()
                val diagnostic = recipe.getValue("levelPolicy").str() == "diagnostic-condition-rms"
                require(components.size in 1..16 && duration.isFinite() && duration in .1..60.0) { "sequence exceeds finite audition bounds" }
                val samples = DoubleArray((duration * RATE).roundToInt())
                val cache = mutableMapOf<String, Rendered>()
                val rows = mutableListOf<Map<String, Any?>>()
                var residual = 0.0
                for (component in components) {
                    val obj = component.obj()
                    val time = obj.getValue("offsetSeconds").num()
                    val gain = obj.getValue("outputGain").num()
                    require(time.isFinite() && time in 0.0..duration) { "sequence component starts outside render" }
                    require(gain.isFinite() && if (diagnostic) gain in 0.0..64.0 else gain == 1.0) { "invalid comparison output gain" }
                    val text = Json.write(obj.getValue("recipe"))
                    val child = cache.getOrPut(text) { renderRecipe(Json.parse(text).obj(), depth + 1) }
                    val offset = (time * RATE).roundToInt()
                    require(offset + child.snip.samples.size <= samples.size) { "comparison component exceeds render" }
                    for (i in child.snip.samples.indices) samples[offset + i] += child.snip.samples[i] * gain
                    residual = max(residual, (child.diagnostics["allocationResidual"] as? Number)?.toDouble() ?: 0.0)
                    rows += mapOf(
                        "label" to obj.getValue("label").str(), "offsetSeconds" to time,
                        "outputGain" to gain, "diagnostics" to child.diagnostics,
                    )
                }
                Rendered(
                    Snip(FloatArray(samples.size) { samples[it].toFloat() }, channels = 1, sampleRate = RATE),
                    mapOf("allocationResidual" to residual, "levelPolicy" to recipe.getValue("levelPolicy"),
                        "components" to rows, "comparisonMeasurements" to recipe["comparisonMeasurements"]),
                    (System.nanoTime() - started) / 1e6,
                )
            }
            else -> error("unknown Lattice audition recipe kind")
        }
    }

    private fun nativePatch(value: JsonValue): LatticePatch =
        Patches.fromJsonValue(value) as? LatticePatch ?: error("audition native recipe must contain a LATTICE patch")

    private fun gesturePhraseRecipe(moving: Boolean): Map<String, Any?> {
        val notes = listOf(48, 55, 51)
        val settings = LatticeGestureStudy.settings()
        val events = notes.mapIndexed { index, midi ->
            linkedMapOf<String, Any?>("timeSeconds" to index * .85,
                "settings" to settingsMap(settings.copy(midi = midi))).apply {
                if (moving) put("trajectory", LatticeGestureStudy.frames.map { frame ->
                    mapOf("time" to frame.time, "turn" to frame.turn, "gateCount" to frame.gates,
                        "twist" to frame.twist, "color" to frame.color, "reject" to frame.reject)
                })
            }
        }
        return linkedMapOf<String, Any?>(
            "kind" to if (moving) "motion-phrase" else "phrase", "settings" to settingsMap(settings),
            "durationSeconds" to 3.7, "assemblyGain" to PHRASE_GAIN, "events" to events,
            "renderSettings" to events.map { it["settings"] },
        ).apply { if (moving) put("interpolation", "smoothstep") }
    }

    private fun nativePhraseRecipe(patch: LatticePatch): Map<String, Any?> {
        val notes = listOf(48, 55, 60)
        val spacingFrames = (.32 * RATE).roundToInt()
        val events = notes.mapIndexed { index, midi ->
            mapOf("timeSeconds" to index * spacingFrames.toDouble() / RATE, "patch" to patch.copy(midi = midi).toJsonValue())
        }
        val noteFrames = notes.maxOf { midi ->
            (Lattice.settingsFor(patch.voice, patch.macros, midi, patch.velocity).durationSeconds * RATE).roundToInt()
        }
        return linkedMapOf(
            "kind" to "native-phrase", "assemblyGain" to PHRASE_GAIN,
            "durationSeconds" to ((notes.lastIndex * spacingFrames + noteFrames).toDouble() / RATE),
            "settings" to mapOf("sourceProfile" to patch.voice.name, "midi" to patch.midi,
                "velocity" to patch.velocity, "macros" to patch.macros),
            "events" to events,
        )
    }

    private fun nativeHoldRecipe(patch: LatticePatch): Map<String, Any?> = linkedMapOf(
        "kind" to "native-hold", "patch" to patch.toJsonValue(), "repeatCount" to 4,
        "settings" to mapOf("sourceProfile" to patch.voice.name, "midi" to patch.midi,
            "velocity" to patch.velocity, "macros" to patch.macros),
    )

    private fun exportNativeKit(output: File): Map<String, Any?> {
        val arranged = SynthKits.lattice()
        check(arranged.size == 16 && arranged.all { it != null }) { "Lattice starter must have sixteen occupied pads" }
        arranged.filterNotNull().forEach { pad ->
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            check(recipe.patch is LatticePatch && recipe.fx == null && recipe.board == null && recipe.terminalFadeMs == null) {
                "Lattice starter must retain dry native instrument recipes"
            }
        }
        val root = File(output, "kit")
        val source = File(root, "source-kit")
        val kit = KitAssembler.assembleArranged("SnipSnap Lattice Starter", arranged, source)
        val exported = Mpc3Exporter.exportTrack(kit, source, File(root, "mpc"), overwrite = true, mpc2Twin = true)
        val heldPads = kit.pads.filter { it.drumClass == com.snipsnap.audio.DrumClass.LOOP }
        check(heldPads.map { it.slot } == listOf(14, 15, 16)) { "Lattice held pads must occupy A14–A16" }
        File(root, "pad-recipes.json").writeText(Json.write(json(kit.pads.map { pad ->
            mapOf("slot" to pad.slot, "name" to pad.displayName, "sampleFile" to pad.sampleFile,
                "source" to pad.source, "recipe" to pad.recipe)
        })))
        File(root, "README.txt").writeText("""
            SnipSnap Lattice starter — proposed native instrument bank

            Open mpc/SnipSnap Lattice Starter.xtd on MPC 3, keeping its _[TrackData] folder beside it.
            The same data folder contains the MPC 2 compatibility .xpm and PCM24 WAVs.
            source-kit/ retains kit.json, all native pad recipes and the original rendered WAVs.
            No effect rack, converter treatment, seam repair or loudness makeup is applied.

            A01–A08: Warm Passage in C minor pentatonic from C3.
            A09–A11: finite WARM, PLUCK and HOLLOW source voices.
            A12–A13: alternate WARM and PLUCK factory positions.
            A14–A16: one settled loop-only HOLD patch per source voice.

            The standard drum-program exporter leaves sample looping disabled.
            On A14–A16, enable looping across the whole sample on the MPC for indefinite HOLD;
            choose note-on/gate playback if release of the pad should stop the sound.
            The supplied held WAVs contain one settled cycle, with no new attack at the wrap.
            New presets and phone/MPC hardware behavior await listening/device review.
        """.trimIndent() + "\n")
        val zip = File(output, "lattice-mpc-kit.zip")
        archive(zip, root.walkTopDown().filter { it.isFile }.map { "lattice-starter/${it.relativeTo(root).invariantSeparatorsPath}" to it }.toList())
        return mapOf("padCount" to kit.pads.size, "heldPads" to heldPads.map { it.slot },
            "sourceKit" to "kit/source-kit/kit.json", "recipes" to "kit/pad-recipes.json",
            "program" to exported.program.relativeTo(output).invariantSeparatorsPath,
            "mpc2Twin" to true, "externalEffects" to false, "sampleLoopingNeedsManualSetup" to true,
            "download" to zip.name, "zipSha256" to sha256(zip))
    }

    private fun phraseRecipe(
        base: Lattice.Settings,
        notes: List<Int> = listOf(48, 55, 60),
        spacing: Double = .27,
        noteSeconds: Double = .8,
    ): Map<String, Any?> = linkedMapOf(
        "kind" to "phrase", "settings" to settingsMap(base),
        "durationSeconds" to ((notes.size - 1) * spacing + noteSeconds),
        "assemblyGain" to PHRASE_GAIN,
        "renderSettings" to notes.map { midi -> settingsMap(base.copy(
            midi = midi, durationSeconds = noteSeconds, excitationSeconds = .26,
        )) },
        "events" to notes.mapIndexed { i, midi ->
            mapOf("timeSeconds" to i * spacing, "settings" to settingsMap(base.copy(
                midi = midi, durationSeconds = noteSeconds, excitationSeconds = .26,
            )))
        },
    )

    private data class ComparisonRecipes(val common: Map<String, Any?>, val matched: Map<String, Any?>?)

    private fun comparisonRecipes(
        a: Lattice.Settings, b: Lattice.Settings, labelA: String, labelB: String,
        matched: Boolean,
    ): ComparisonRecipes {
        val recipes = listOf(phraseRecipe(a), phraseRecipe(b))
        val rendered = recipes.map { renderRecipe(json(it).obj()) }
        val stats = rendered.map { statistics(it.snip) }
        val conditionGains = if (matched) stats.map {
            minOf(MATCHED_RMS / it.getValue("rms").coerceAtLeast(1e-12), .55 / it.getValue("peak").coerceAtLeast(1e-12), 64.0)
        } else listOf(1.0, 1.0)
        val order = listOf(0, 1, 0, 1)
        val labels = listOf(labelA, labelB)
        val parts = order.map { labels[it] to recipes[it] }
        val comparison = linkedMapOf<String, Any?>(
            "conditionA" to stats[0], "conditionB" to stats[1],
            "gainIndependentWaveformResidual" to shapeResidual(rendered[0].snip.samples, rendered[1].snip.samples),
            "matchedConditionGains" to if (matched) conditionGains else null,
            "note" to "Same note sequence, velocities, source profile, contour and phases. Residual removes one fitted scalar gain; it is a measured difference, not a listening verdict.",
        )
        val common = LinkedHashMap(sequenceRecipe(parts)).apply { put("comparisonMeasurements", comparison) }
        val matchedRecipe = if (matched) LinkedHashMap(sequenceRecipe(parts, order.map { conditionGains[it] })).apply {
            put("comparisonMeasurements", comparison)
        } else null
        return ComparisonRecipes(common, matchedRecipe)
    }

    private fun sequenceRecipe(
        parts: List<Pair<String, Map<String, Any?>>>,
        conditionGains: List<Double>? = null,
    ): Map<String, Any?> {
        require(parts.isNotEmpty() && (conditionGains == null || conditionGains.size == parts.size))
        var frameOffset = 0
        val gapFrames = (.12 * RATE).roundToInt()
        val cache = mutableMapOf<String, Rendered>()
        val components = parts.mapIndexed { index, (label, recipe) ->
            val text = Json.write(json(recipe))
            val rendered = cache.getOrPut(text) { renderRecipe(Json.parse(text).obj()) }
            val component = linkedMapOf<String, Any?>(
                "offsetSeconds" to frameOffset.toDouble() / RATE, "label" to label,
                "outputGain" to (conditionGains?.get(index) ?: 1.0), "recipe" to recipe,
            )
            frameOffset += rendered.snip.samples.size + if (index < parts.lastIndex) gapFrames else 0
            component
        }
        return linkedMapOf(
            "kind" to "sequence", "settings" to parts.first().second["settings"],
            "durationSeconds" to frameOffset.toDouble() / RATE,
            "levelPolicy" to if (conditionGains == null) "common-source" else "diagnostic-condition-rms",
            "components" to components,
            "renderSettings" to parts.map { (label, recipe) -> mapOf("label" to label, "settings" to recipe["settings"], "events" to recipe["renderSettings"]) },
        )
    }

    private fun sequenceCues(recipe: Map<String, Any?>): List<Map<String, Any?>> =
        json(recipe).obj().getValue("components").arr().map {
            val component = it.obj()
            cue(component.getValue("offsetSeconds").num(), component.getValue("label").str())
        }

    private fun shapeResidual(a: FloatArray, b: FloatArray): Double? {
        require(a.size == b.size)
        var aa = 0.0
        var bb = 0.0
        var ab = 0.0
        for (i in a.indices) {
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
            ab += a[i].toDouble() * b[i]
        }
        if (aa < 1e-20 || bb < 1e-20) return null
        return sqrt((1.0 - ab * ab / (aa * bb)).coerceIn(0.0, 1.0))
    }

    private data class Keyframe(val time: Double, val turn: Double, val gateCount: Double)

    /** The saved trajectory is authoritative: replay never substitutes the current demo helper. */
    private fun trajectoryControls(recipe: Map<String, JsonValue>, settings: Lattice.Settings): (Double) -> Lattice.Controls {
        require(recipe.getValue("interpolation").str() == "smoothstep") { "unsupported trajectory interpolation" }
        val frames = recipe.getValue("trajectory").arr().map { value ->
            val obj = value.obj()
            Keyframe(obj.getValue("time").num(), obj.getValue("turn").num(), obj.getValue("gateCount").num())
        }
        require(frames.size in 2..64 && frames.first().time == 0.0) { "trajectory needs 2..64 keyframes beginning at zero" }
        require(frames.all {
            it.time.isFinite() && it.time in 0.0..settings.durationSeconds &&
                it.turn.isFinite() && it.turn in 0.0..1.0 &&
                it.gateCount.isFinite() && it.gateCount in 2.0..16.0
        }) { "trajectory keyframe is outside finite prototype bounds" }
        require(frames.zipWithNext().all { (a, b) -> a.time < b.time }) { "trajectory keyframe times must increase" }
        return { time ->
            val end = frames.indexOfFirst { it.time >= time }.let { if (it < 0) frames.lastIndex else it }
            val b = frames[end]
            val a = frames[(end - 1).coerceAtLeast(0)]
            val position = if (a.time == b.time) 0.0 else ((time - a.time) / (b.time - a.time)).coerceIn(0.0, 1.0)
            val smooth = position * position * (3.0 - 2.0 * position)
            Lattice.Controls(
                turn = a.turn + (b.turn - a.turn) * smooth,
                stages = settings.stages, twist = settings.twist, color = settings.color, reject = settings.reject,
                gateCount = a.gateCount + (b.gateCount - a.gateCount) * smooth,
            )
        }
    }

    private fun settingsMap(s: Lattice.Settings): Map<String, Any?> = linkedMapOf(
        "midi" to s.midi, "velocity" to s.velocity, "turn" to s.turn, "stages" to s.stages,
        "twist" to s.twist, "color" to s.color, "reject" to s.reject,
        "durationSeconds" to s.durationSeconds, "excitationSeconds" to s.excitationSeconds,
        "gateCount" to s.gateCount, "haloEnabled" to s.haloEnabled,
        "sourceProfile" to s.sourceProfile.name, "contour" to s.contour.name,
    )

    private fun settingsFrom(obj: Map<String, JsonValue>): Lattice.Settings = Lattice.Settings(
        midi = obj.getValue("midi").int(), velocity = obj.getValue("velocity").num().toFloat(),
        turn = obj.getValue("turn").num(), stages = obj.getValue("stages").num(),
        twist = obj.getValue("twist").num(), color = obj.getValue("color").num(), reject = obj.getValue("reject").num(),
        durationSeconds = obj.getValue("durationSeconds").num(), excitationSeconds = obj.getValue("excitationSeconds").num(),
        gateCount = obj["gateCount"]?.takeUnless { it is JsonValue.Null }?.num(),
        haloEnabled = obj.getValue("haloEnabled").bool(),
        sourceProfile = Lattice.SourceProfile.valueOf(obj.getValue("sourceProfile").str()),
        contour = Lattice.Contour.valueOf(obj.getValue("contour").str()),
    )

    private fun diagnostics(d: Lattice.Diagnostics): Map<String, Any?> = linkedMapOf(
        "allocationResidual" to d.allocationResidual, "sourceGain" to d.sourceGain,
        "rawPeak" to d.rawPeak, "peak" to d.peak, "rms" to d.rms, "dc" to d.dc,
        "dryPeak" to d.dryPeak, "dryRms" to d.dryRms, "haloPeak" to d.haloPeak, "haloRms" to d.haloRms,
        "branchCorrelation" to d.branchCorrelation,
        "groupTransmission" to d.groupTransmission.toList(), "stageRejectedEnergy" to d.stageRejectedEnergy.toList(),
        "renderMillis" to d.renderMillis,
    )

    private fun referenceChecks(): List<Map<String, Any?>> = listOf(2, 3, 4, 16).map { count ->
        val expected = cos(PI / (2.0 * (count - 1))).pow(count - 1)
        fun trial(input: Lattice.Vector): Lattice.Vector {
            var state = input
            for (j in 0 until count) state = Lattice.project(state, j * PI / (2.0 * (count - 1))).forward
            return state
        }
        val aligned = trial(Lattice.Vector(1.0, 0.0))
        val orthogonal = trial(Lattice.Vector(0.0, 1.0))
        // Read the final gate's own basis (90 degrees), not a fixed x coordinate.
        val amplitude = Lattice.compile(Lattice.Controls(turn = 1.0, gateCount = count.toDouble(), color = 0.0)).groups.first().transmission
        val opticalPower = .5 * (aligned.x * aligned.x + aligned.y * aligned.y + orthogonal.x * orthogonal.x + orthogonal.y * orthogonal.y)
        linkedMapOf(
            "gates" to count, "amplitude" to amplitude, "expected" to expected,
            "error" to (amplitude - expected), "primitiveAmplitude" to aligned.y, "alignedPower" to amplitude * amplitude,
            "unpolarizedPower" to opticalPower, "unpolarizedExpected" to .5 * expected * expected,
        )
    }

    private fun statistics(snip: Snip): Map<String, Double> {
        var peak = 0.0
        var sum = 0.0
        var squares = 0.0
        for (sample in snip.samples) {
            val d = sample.toDouble()
            peak = max(peak, abs(d)); sum += d; squares += d * d
        }
        return linkedMapOf("peak" to peak, "rms" to sqrt(squares / snip.samples.size), "dc" to sum / snip.samples.size)
    }

    private fun waveform(samples: FloatArray): List<Double> {
        val peak = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        return (0 until 128).map { bucket ->
            val from = bucket * samples.size / 128
            val to = ((bucket + 1) * samples.size / 128).coerceAtLeast(from + 1).coerceAtMost(samples.size)
            var local = 0.0
            for (i in from until to) local = max(local, abs(samples[i].toDouble()))
            if (peak > 1e-12) local / peak else 0.0
        }
    }

    private fun requireAudio(id: String, snip: Snip) {
        require(snip.channels == 1 && snip.sampleRate == RATE && snip.samples.isNotEmpty()) { "$id is not 44.1 kHz mono" }
        require(snip.samples.all { it.isFinite() && abs(it) < .99f }) { "$id exceeds finite prototype headroom" }
    }

    private fun cue(time: Double, label: String): Map<String, Any?> = mapOf("time" to time, "label" to label)
    private fun slug(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun keyframe(time: Double, turn: Double, count: Double): Map<String, Any?> = mapOf("time" to time, "turn" to turn, "gateCount" to count)
    private fun percent(value: Double): String = "${(value * 100).roundToInt()}%"
    private fun noteName(midi: Int): String = "${listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12]}${midi / 12 - 1}"

    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, ".git").exists() } ?: error("run the generator from the SnipSnap checkout")

    private fun git(root: File, vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "non-finite JSON measurement" } })
        is Boolean -> JsonValue.Bool(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported JSON value ${value.javaClass.name}")
    }
}

package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** JVM-rendered audio only: the web page neither synthesizes nor invents trajectory data. */
object LibrationAuditionGenerator {
    private data class Case(val id: String, val title: String, val section: String, val patch: LibrationPatch,
        val description: String, val ablation: Libration.Ablation = Libration.Ablation.FULL,
        val control: String? = null, val value: Float? = null, val sourceSeconds: Double = 6.0)
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported manifest value ${value::class}")
    }
    private fun writeJson(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value))) }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun scaled(s: Snip, gain: Double) = s.copy(samples = FloatArray(s.samples.size) { (s.samples[it] * gain).toFloat() })
    private fun cases(): List<Case> {
        val cases = mutableListOf<Case>()
        for (voice in LibrationVoice.entries) {
            val id = voice.name.lowercase()
            val title = id.replaceFirstChar(Char::uppercase)
            val patch = LibrationPresets.references().single { it.voice == voice }
            val text = when (voice) {
                LibrationVoice.GATHER -> "Rounded excursions around a steady root. Listen for changing weight inside the tone."
                LibrationVoice.TWINS -> "A fine upper cloud and a quieter rounded counterpart. Listen for two different populations."
                LibrationVoice.DEPARTURE -> "Voices leave the L2 neighborhood and reduce their excitation; the modal bank keeps ringing."
            }
            cases += Case(id, title, "voices", patch, text)
            for ((ablation, label, description) in listOf(
                Triple(Libration.Ablation.FROZEN, "Motion frozen", "Keep each particle at its initial descriptors; oscillator phase and excitation pulses still run."),
                Triple(Libration.Ablation.PITCH_ONLY, "Pitch movement only", "Keep trajectory-driven pitch while freezing the other movement descriptors."),
                Triple(Libration.Ablation.NO_PITCH, "Pitch movement off", "Keep spectral redistribution and changing excitation while holding all carriers at harmonic pitch."),
                Triple(Libration.Ablation.NO_MEMORY, "Memory off", "Use the identical direct signal and disable the shared sympathetic bank."))) {
                cases += Case("$id-${ablation.name.lowercase()}", label, "comparison", patch, description, ablation)
            }
        }
        for ((control, voice) in listOf("WANDER" to LibrationVoice.GATHER, "NUDGE" to LibrationVoice.DEPARTURE,
            "SWARM" to LibrationVoice.TWINS, "RESONANCE" to LibrationVoice.DEPARTURE)) {
            val steps = if (control in listOf("NUDGE", "RESONANCE")) listOf(0f, .25f, .5f, .75f, 1f) else listOf(0f, .5f, 1f)
            for (value in steps) {
                val fixed = when (control) {
                    "RESONANCE" -> mapOf("NUDGE" to 0f)
                    "NUDGE" -> mapOf("RESONANCE" to .25f)
                    else -> emptyMap()
                }
                val patch = LibrationPatch("$control $value", voice, Libration.defaults(voice) + fixed + (control to value), model = LibrationPresets.REFERENCE_MODEL)
                cases += Case("${control.lowercase()}-${(value * 100).toInt()}", "$control ${(value * 100).toInt()}%", "controls", patch,
                    if (control == "RESONANCE") "Two-second excitation, then the resonant tail alone. NUDGE stays at zero; body levels are matched."
                    else if (control == "NUDGE") "The cloud leaves earlier as the impulse grows. RESONANCE stays at 25%; body levels are matched."
                    else "Only $control changes from the ${voice.name.lowercase()} recipe. Body levels are matched.",
                    control = control, value = value, sourceSeconds = if (control == "RESONANCE") 2.0 else 6.0)
            }
        }
        for (i in 0..8) {
            val value = i / 8f
            val patch = LibrationPatch("Region $value", LibrationVoice.GATHER,
                Libration.defaults(LibrationVoice.GATHER) + mapOf("REGION" to value, "WANDER" to .45f, "NUDGE" to .35f, "SWARM" to .75f, "RESONANCE" to .5f), model = LibrationPresets.REFERENCE_MODEL)
            val label = listOf("L4 · rounded", "L4 + L1", "L1 · poised", "L1 + L2", "L2 · outward", "L2 + L3", "L3 · broad", "L3 + L5", "L5 · glassy")[i]
            cases += Case("region-$i", label, "regions", patch, "Adjacent families retain separate initial states and blend in audio.", control = "REGION", value = value)
        }
        for (midi in listOf(48, 72)) cases += Case("twins-midi-$midi", if (midi == 48) "Twins · C3" else "Twins · C5", "roots",
            LibrationPatch("Twins MIDI $midi", LibrationVoice.TWINS, midi = midi, model = LibrationPresets.REFERENCE_MODEL), "The same physical motion voiced at another root.")
        return cases
    }

    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") { replay(args); return }
        val root = File(args.firstOrNull() ?: "../testkit/libration-audition").canonicalFile
        root.mkdirs()
        for (name in listOf("index.html", "styles.css", "app.js", "analyze.py")) {
            val input = LibrationAuditionGenerator::class.java.getResourceAsStream("/audition/libration/$name")
                ?: error("missing Libration page $name")
            input.use { source -> File(root, name).outputStream().use { source.copyTo(it) } }
        }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, ".git").exists() }
        val revision = ProcessBuilder("git", "rev-parse", "HEAD").directory(repo).start().inputStream.bufferedReader().readText().trim()
        val changes = ProcessBuilder("git", "status", "--porcelain").directory(repo).start().inputStream.bufferedReader().readText().trim()
        val sourceNames = listOf("synth/src/main/kotlin/com/snipsnap/synth/Libration.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/LibrationTrajectory.kt", "synth/src/main/kotlin/com/snipsnap/synth/LibrationPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/LibrationPresets.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/LibrationAuditionGenerator.kt", "synth/src/test/kotlin/com/snipsnap/synth/LibrationTest.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Modes.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt", "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Presets.kt", "synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt",
            "synth/build.gradle.kts", "docs/LIBRATION.md") +
            listOf("index.html", "app.js", "styles.css", "analyze.py").map { "synth/src/test/resources/audition/libration/$it" }
        val sourceHashes = linkedMapOf<String, String>()
        for (path in sourceNames) {
            val file = File(repo, path)
            if (file.isFile) {
                val out = File(root, "source/$path"); out.parentFile.mkdirs(); file.copyTo(out, overwrite = true)
                sourceHashes[path] = hash(out)
            }
        }
        val clips = mutableListOf<Map<String, Any?>>()
        val gainForVoice = mutableMapOf<LibrationVoice, Double>()
        val referenceRaw = mutableMapOf<LibrationVoice, FloatArray>()
        val physicsCache = mutableMapOf<Libration.PopulationKey, Libration.Population>()
        val allCases = cases()
        val started = System.nanoTime()
        for ((index, case) in allCases.withIndex()) {
            val options = Libration.Options(seconds = case.sourceSeconds, ablation = case.ablation)
            val patch = case.patch
            val key = Libration.populationKey(patch.voice, patch.macros, options, patch.model)
            val population = physicsCache.getOrPut(key) { Libration.population(patch.voice, patch.macros, options, patch.model) }
            val probe = Libration.probe(patch.voice, patch.macros, patch.midi, patch.velocity, options, population, patch.model)
            val raw = probe.raw
            require(raw.samples.all { it.isFinite() } && raw.peak() < .99f) { "${case.id}: raw clipping or invalid audio ${raw.peak()}" }
            val referenceGain = gainForVoice.getOrPut(patch.voice) { .03 / Loudness.of(raw).coerceAtLeast(1e-9f) }
            val gain = if (case.section == "controls") .03 / Loudness.of(raw).coerceAtLeast(1e-9f) else referenceGain
            val matched = scaled(raw, gain)
            require(matched.peak() < .99f)
            val rawFile = File(root, "audio/raw/${case.id}.wav")
            val matchedFile = File(root, "audio/matched/${case.id}.wav")
            WavWriter.write(rawFile, raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(matchedFile, matched, WavWriter.BitDepth.PCM_16)
            val recipe = linkedMapOf("patch" to patch.toJsonValue(), "audition" to mapOf(
                "ablation" to case.ablation.name, "sourceSeconds" to options.seconds, "controlRate" to options.rate, "referenceGain" to gain))
            writeJson(File(root, "recipes/${case.id}.json"), recipe)
            val p = probe.population
            val particles = p.particles.map { particle ->
                val report = particle.path.report
                val stride = max(1, p.rate / 25)
                mapOf("family" to particle.family.name, "id" to particle.id, "weight" to particle.weight, "glass" to particle.glass,
                    "jacobiDrift" to report.jacobiDrift, "maxExcursion" to report.maxExcursion, "minPrimaryDistance" to report.minPrimaryDistance,
                    "departureSeconds" to report.departureSeconds, "termination" to report.termination,
                    "trace" to particle.path.states.indices.filter { it % stride == 0 || it == particle.path.states.lastIndex }.map { i ->
                        val s = particle.path.states[i]; val e = LibrationTrajectory.equilibrium(particle.family)
                        listOf(i.toDouble() / p.rate, s.x - e.x, s.y - e.y, particle.descriptors[i][4], particle.descriptors[i][2])
                    })
            }
            writeJson(File(root, "trajectories/${case.id}.json"), mapOf("particles" to particles))
            val estimate = requireNotNull(Pitch.detect(raw, .4f, .4f)) { "${case.id}: root was not detected" }
            val cents = 1200 * log2(estimate.hz / Keys.midiHz(patch.midi))
            val d = probe.diagnostics
            require(d.maxJacobiDrift < 1e-6) { "${case.id}: Jacobi drift ${d.maxJacobiDrift}" }
            require(abs(cents) < 15) { "${case.id}: root tracking $cents cents" }
            if (case.section == "voices") referenceRaw[patch.voice] = raw.samples
            val reference = referenceRaw[patch.voice]
            val relativeDifference = if (reference != null && reference.size == raw.samples.size)
                sqrt(raw.samples.indices.sumOf { (raw.samples[it] - reference[it]).toDouble().pow(2) } /
                    reference.sumOf { it.toDouble() * it }.coerceAtLeast(1e-20)) else null
            clips += linkedMapOf("id" to case.id, "title" to case.title, "section" to case.section, "voice" to patch.voice.name,
                "description" to case.description, "ablation" to case.ablation.name, "control" to case.control, "value" to case.value,
                "midi" to patch.midi, "model" to patch.model, "sourceSeconds" to options.seconds,
                "levelPolicy" to if (case.section == "controls") "matched body" else "common voice gain",
                "macros" to patch.macros, "seconds" to raw.durationSeconds,
                "audio" to "audio/matched/${case.id}.wav", "rawAudio" to "audio/raw/${case.id}.wav", "recipe" to "recipes/${case.id}.json",
                "trajectories" to "trajectories/${case.id}.json", "sha256" to hash(matchedFile), "rawSha256" to hash(rawFile),
                "diagnostics" to mapOf("rawPeak" to raw.peak(), "rawRms" to d.rawRms, "loudness" to Loudness.of(raw), "referenceGain" to gain,
                    "pitchCents" to cents, "pitchConfidence" to estimate.confidence, "jacobiDrift" to d.maxJacobiDrift,
                    "voices" to d.activeVoices, "terminated" to d.terminatedParticles, "solverWork" to d.solverWork,
                    "renderMilliseconds" to d.renderMilliseconds, "relativeDifference" to relativeDifference,
                    "rawClassifier" to Classifier.classify(raw).drumClass.name,
                    "departed" to particles.count { it["departureSeconds"] != null }))
            println("[${index + 1}/${allCases.size}] ${case.id}: drift=${d.maxJacobiDrift}, pitch=$cents cents, ${d.renderMilliseconds.roundToInt()} ms")
        }
        val equilibrium = LibrationTrajectory.Family.entries.map { f ->
            val e = LibrationTrajectory.equilibrium(f); val a = LibrationTrajectory.acceleration(e)
            mapOf("family" to f.name, "x" to e.x, "y" to e.y, "forceResidual" to hypot(a.first, a.second))
        }
        val manifest = mapOf("title" to "Libration", "model" to LibrationPresets.REFERENCE_MODEL, "generatedAt" to Instant.now().toString(),
            "sampleRate" to Dsp.RATE, "oversample" to 4, "controlRate" to 500, "mu" to LibrationTrajectory.MU,
            "status" to "Finite listening prototype; owner listening verdict pending. HOLD and full factory bank are not implemented.",
            "source" to mapOf("revision" to revision, "workingTreeChanges" to changes, "files" to sourceHashes),
            "equilibria" to equilibrium, "clips" to clips, "generationSeconds" to (System.nanoTime() - started) / 1e9)
        writeJson(File(root, "manifest.json"), manifest)
        File(root, "README.txt").writeText("LIBRATION MODEL ${LibrationPresets.REFERENCE_MODEL} — CONTROL REVISION\n\nAll audio was rendered by the Kotlin engine through SnipSnap's 4x DSP path. No reverb.\nAblations share their voice's single reference gain; macro sweeps match the body level separately. Native PCM24 files preserve pre-normalization gain.\nRESONANCE comparisons use a two-second source so the after-ring is exposed. Other sources last six seconds.\nThe source snapshot and SHA-256 hashes identify the exact working-tree implementation. Saved model 1 recipes retain the original rendering.\nNormal patches reproduce their underlying finite note. Diagnostic recipes additionally record the source duration, ablation and audition gain.\n\nGenerate: ./gradlew --no-daemon :synth:generateLibrationAudition\nReplay a matched WAV: ./gradlew --no-daemon :synth:replayLibrationAudition -PlibrationRecipe=/absolute/recipe.json -PlibrationReplayOutput=/absolute/replay.wav\n\nHOLD, the full six-design roster, phone controls and hardware acceptance remain future work.\n")
        val archive = File(root, "libration-listening-pack.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            root.walkTopDown().filter { it.isFile && it != archive && !it.relativeTo(root).invariantSeparatorsPath.startsWith("audio/raw/") }.forEach { file ->
                val name = file.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(name).apply { time = 0L }); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("Generated ${clips.size} clips at $root")
    }

    private fun replay(args: Array<String>) {
        require(args.size == 3) { "--replay <recipe.json> <output.wav>" }
        val obj = Json.parse(File(args[1]).readText()).obj()
        val patch = Patches.fromJsonValue(obj.getValue("patch")) as LibrationPatch
        val a = obj.getValue("audition").obj()
        val options = Libration.Options(a.getValue("sourceSeconds").num(), a.getValue("controlRate").int(),
            Libration.Ablation.valueOf(a.getValue("ablation").str()))
        val probe = Libration.probe(patch.voice, patch.macros, patch.midi, patch.velocity, options, model = patch.model)
        WavWriter.write(File(args[2]), scaled(probe.raw, a.getValue("referenceGain").num()), WavWriter.BitDepth.PCM_16)
    }
}

package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Real finite Kotlin notes, complete tails and native MPC exports; no browser synthesis. */
object LibrationMusicalGenerator {
    private const val BPM = 96f
    private data class Event(val beat: Double, val patch: LibrationPatch)
    private data class Hit(val beat: Double, val slot: Int, val velocity: Float)
    private fun j(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to j(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::j))
        else -> error("unsupported JSON ${value::class}")
    }
    private fun write(root: File, path: String, value: String) = File(root, path).also {
        it.parentFile.mkdirs(); it.writeText(value)
    }
    private fun json(root: File, path: String, value: Any?) = write(root, path, Json.write(j(value)) + "\n")
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun scaled(s: Snip, gain: Double) = s.copy(samples = FloatArray(s.samples.size) { (s.samples[it] * gain).toFloat() })
    private fun metrics(s: Snip) = mapOf("peak" to s.peak(), "loudness" to Loudness.of(s),
        "dc" to s.samples.sumOf { it.toDouble() } / s.samples.size, "seconds" to s.durationSeconds)
    private fun frame(beat: Double) = (beat * 60.0 / BPM * Dsp.RATE).roundToInt()
    private fun safe(s: Snip) { check(s.samples.all { it.isFinite() } && s.peak() < .99f) { "non-finite or clipped audio" } }
    private fun mix(parts: List<Pair<Double, Snip>>, gain: Double): Snip {
        val out = FloatArray(parts.maxOf { frame(it.first) + it.second.frameCount })
        for ((beat, snip) in parts) for (i in snip.samples.indices) out[frame(beat) + i] += (snip.samples[i] * gain).toFloat()
        return Snip(out, 1, Dsp.RATE).also(::safe)
    }
    private fun zip(root: File, name: String, paths: List<String>) {
        ZipOutputStream(File(root, name).outputStream().buffered()).use { zip ->
            for (path in paths.distinct().sorted()) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                File(root, path).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        check(File(root, name).length() < 25L * 1024 * 1024) { "$name exceeds the 25 MiB asset budget" }
    }

    /** Cache bounds describe this generator session, not a production phone cache. */
    private class Session {
        private val populations = object : LinkedHashMap<Libration.PopulationKey, Libration.Population>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Libration.PopulationKey, Libration.Population>?) = size > 16
        }
        // Audio only is retained. Probes would retain evicted trajectory tables as well.
        private val notes = mutableMapOf<String, Snip>()
        var coldPhysicsMs = 0.0
        var renders = 0
        fun probe(p: LibrationPatch): Libration.Probe {
            val options = Libration.Options(seconds = p.sourceSeconds)
            val key = Libration.populationKey(p.voice, p.macros, options, p.model)
            val population = populations.getOrPut(key) {
                val t = System.nanoTime()
                Libration.population(p.voice, p.macros, options, p.model).also { coldPhysicsMs += (System.nanoTime() - t) / 1e6 }
            }
            renders++
            return Libration.probe(p.voice, p.macros, p.midi, p.velocity, options, population, p.model)
        }
        fun note(p: LibrationPatch) = notes.getOrPut(p.toJsonText()) { probe(p).samples.also(::safe) }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1)
        val root = File(args.firstOrNull() ?: "../testkit/libration-musical").canonicalFile.also { it.mkdirs() }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, ".git").exists() }
        val start = System.nanoTime()
        val session = Session()
        val clips = mutableListOf<Map<String, Any?>>()
        val bank = LibrationPresets.all()
        val descriptions = listOf(
            "A short low wooden pulse, with a small halo after the source closes.",
            "A rounded middle-register key for chords and small repeated figures.",
            "Two adjacent families share a longer, changing wooden tone.",
            "A low sustained excursion with room for the orbit to develop.",
            "A short high glass note, followed by sympathetic memory.",
            "A fine upper cloud shaped for shorter melodic lines.",
            "A longer glass tone with two physical families in the cloud.",
            "A high, slowly changing canopy with a longer after-ring.",
            "A strong initial impulse sends the cloud outward early.",
            "A middle-length departure with a clear root underneath.",
            "L1 and L2 contribute separate paths to one departing note.",
            "A lower, longer departure with the deepest memory in the bank.",
        )
        fun clip(id: String, title: String, group: String, description: String, native: Snip,
            patch: LibrationPatch? = null, score: Map<String, Any?>? = null, gain: Double? = null,
            extra: Map<String, Any?> = emptyMap()) {
            safe(native)
            val matched = if (gain == null) AuditionLevel.level(native) else scaled(native, gain)
            safe(matched)
            val nativePath = "music/audio/native/$id.wav"
            val matchedPath = "music/audio/matched/$id.wav"
            WavWriter.write(File(root, nativePath), native, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            val recipePath = "music/recipes/$id.json"
            json(root, recipePath, if (patch != null) PadRecipe(patch = patch).toJsonValue() else score)
            clips += linkedMapOf("id" to id, "title" to title, "group" to group, "description" to description,
                "voice" to patch?.voice?.name, "midi" to patch?.midi, "velocity" to patch?.velocity,
                "sourceSeconds" to patch?.sourceSeconds, "audio" to matchedPath, "nativeAudio" to nativePath,
                "recipe" to recipePath, "score" to if (score != null) recipePath else null,
                "nativeMetrics" to metrics(native), "matchedMetrics" to metrics(matched),
                "sha256" to hash(File(root, matchedPath)), "nativeSha256" to hash(File(root, nativePath))) + extra
            println("Rendered $id: ${native.durationSeconds}s")
        }
        for ((i, p) in bank.withIndex()) {
            val probe = session.probe(p)
            check(probe.diagnostics.maxJacobiDrift < 1e-6)
            val pitch = checkNotNull(Pitch.detect(probe.samples, .06f, min(.22, p.sourceSeconds * .6).toFloat()))
            val cents = 1200 * log2(pitch.hz / Keys.midiHz(p.midi))
            check(abs(cents) < 15) { "${p.name}: $cents cents" }
            clip("bank-${i + 1}", p.name, "bank", descriptions[i], probe.samples, p,
                extra = mapOf("pitchCents" to cents, "rawPeak" to probe.raw.peak(),
                    "jacobiDrift" to probe.diagnostics.maxJacobiDrift,
                    "solverWork" to probe.diagnostics.solverWork))
        }
        val anchors = listOf("ROUND KEYS", "FINE KEYS", "OPEN EXIT").map { name -> bank.single { it.name == name } }
        for (anchor in anchors) {
            val voice = anchor.voice.name.lowercase()
            val fixed = anchor.copy(midi = 60, sourceSeconds = 1.2)
            val hard = session.note(fixed.copy(velocity = 1f))
            val commonGain = .03 / Loudness.of(hard).coerceAtLeast(1e-9f)
            for (velocity in listOf(.30f, .65f, 1f)) {
                val patch = fixed.copy(velocity = velocity)
                clip("touch-$voice-${(velocity * 100).roundToInt()}", "${(velocity * 100).roundToInt()}% touch", "touch",
                    "${anchor.voice}: same note and motion. A shared gain preserves the touch dynamics.",
                    session.note(patch), patch, gain = commonGain)
            }
            for ((label, seconds) in listOf("Short" to .28, "Medium" to 1.2, "Long" to 4.0)) {
                val patch = fixed.copy(sourceSeconds = seconds, velocity = .8f)
                clip("length-$voice-${label.lowercase()}", "$label · ${seconds}s", "length",
                    "The source closes at ${seconds}s; the sympathetic tail continues afterwards.", session.note(patch), patch)
            }
            for (midi in listOf(36, 60, 84)) {
                val patch = fixed.copy(midi = midi, velocity = .8f)
                val snip = session.note(patch)
                val pitch = checkNotNull(Pitch.detect(snip, .08f, .4f))
                val cents = 1200 * log2(pitch.hz / Keys.midiHz(midi))
                check(abs(cents) < 15) { "${anchor.voice} MIDI $midi: $cents cents" }
                clip("root-$voice-$midi", "C${midi / 12 - 1}", "root", "Same physical motion across four octaves.", snip,
                    patch, extra = mapOf("pitchCents" to cents))
            }
            val events = (0..5).map { n -> Event(n * .5, fixed.copy(sourceSeconds = .28,
                velocity = if (n % 2 == 0) 1f else .45f)) }
            phrase(root, session, events, "repeat-$voice", "${anchor.voice} · repeated notes", "repeat",
                "Six short notes, alternating hard and soft touch. Every previous tail is allowed to finish.", ::clip)
        }
        fun at(name: String, midi: Int, velocity: Float, seconds: Double) = bank.single { it.name == name }
            .copy(midi = midi, velocity = velocity, sourceSeconds = seconds)
        val bassNotes = listOf(36, 36, 43, 39, 41, 43, 36, 46, 43, 39, 36, 36, 41, 43, 39, 36)
        phrase(root, session, bassNotes.mapIndexed { i, midi -> Event(i.toDouble(), at("ROOT WOOD", midi,
            if (i % 4 == 0) 1f else if (i % 2 == 0) .75f else .5f, if (i % 4 == 3) .52 else .28)) },
            "music-bass", "Wood / a bassline", "music", "Four bars in C minor pentatonic. Short gates leave space; touch supplies the accents.", ::clip)
        val chords = listOf(listOf(48, 55, 63), listOf(46, 53, 60), listOf(41, 48, 56), listOf(43, 50, 58))
        phrase(root, session, chords.flatMapIndexed { i, notes -> notes.mapIndexed { n, midi -> Event(i * 4.0 + n * .04,
            at(if (i % 2 == 0) "ROUND KEYS" else "TWIN GROVE", midi, .65f + n * .12f, 1.4)) } },
            "music-chords", "Groves / four chords", "music", "Four spread voicings. Slightly staggered attacks let the moving clouds meet through their complete tails.", ::clip)
        val melody = listOf(60, 67, 70, 72, 67, 63, 65, 67, 60, 63, 67, 75, 72, 70, 67, 60)
        phrase(root, session, melody.mapIndexed { i, midi -> Event(i.toDouble(), at(if (i % 4 == 3) "OPEN EXIT" else "FINE KEYS",
            midi, if (i % 4 == 0) 1f else .55f + .1f * (i % 3), if (i % 4 == 3) 1.7 else .4)) },
            "music-melody", "Glass / a way out", "music", "A glass melody with a departing answer at the end of each bar. Accents and tails remain inside the performance.", ::clip)

        val kitRoot = File(root, "music/kit").also { it.mkdirs() }
        val work = Files.createTempDirectory("snipsnap-libration-starter-").toFile()
        val hits = listOf(Hit(0.0, 1, .95f), Hit(1.0, 5, .45f), Hit(2.0, 13, .9f), Hit(3.0, 14, .75f),
            Hit(4.0, 2, .9f), Hit(4.0, 7, .6f), Hit(6.0, 9, .8f), Hit(8.0, 1, .8f), Hit(9.0, 15, .4f),
            Hit(10.0, 13, .85f), Hit(11.0, 6, .7f), Hit(12.0, 16, .9f), Hit(14.0, 8, .65f))
        val groove = Mpc3Clip("Libration 96 BPM", 4, hits.map {
            Mpc3Note(Mpc3Note.noteFor(it.slot), (it.beat * Mpc3Clip.PULSES_PER_BEAT).roundToLong(), it.velocity)
        })
        val pads = mutableListOf<Map<String, Any?>>()
        try {
            val kit = KitAssembler.assembleArranged(LibrationKits.NAME, LibrationKits.arranged(session::note), work,
                KeySpec.parse("Cminpent"), BPM)
            val exported = Mpc3Exporter.exportTrack(kit, work, kitRoot, overwrite = true, mpc2Twin = true, clip = groove)
            val data = File(kitRoot, Mpc3TrackWriter.trackDataDirName(kit.name))
            val twin = File(data, "${kit.name}.xpm")
            val saved = KitStore.load(data)
            check(saved.pads.size == 16 && saved.pads.all { it.recipe != null && it.source["rawSha256"] != null })
            val findings = listOf(ExportOutcome(ExportFormat.MPC3_TRACK, exported.program, data, exported.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, exported.findings)).flatMap { ExportReadBack.verify(kit, it) }
            check(!findings.blocked()) { findings.joinToString("\n") { "${it.severity}: ${it.message}" } }
            write(root, "music/kit/read-back.txt", findings.joinToString("\n", postfix = "\n") { "${it.severity}: ${it.message}" })
            for ((i, pad) in saved.pads.withIndex()) {
                val p = LibrationKits.patches()[i]
                check(PadRecipe.fromJsonValue(checkNotNull(pad.recipe)).patch == p)
                check(pad.tuneCoarse == 0 && pad.tuneFine == 0 && pad.oneShot && pad.muteGroup == 0)
                check(pad.velocityLayers.map { it.velStart to it.velEnd } == listOf(1 to 63, 64 to 127))
                val layers = pad.velocityLayers.mapIndexed { n, layer ->
                    val patch = p.copy(velocity = if (n == 0) LibrationKits.SOFT_VELOCITY else LibrationKits.HARD_VELOCITY)
                    val file = File(data, layer.sampleFile)
                    check(file.isFile && file.readBytes().contentEquals(File(work, layer.sampleFile).readBytes()))
                    val replay = File(work, "layer-replay.wav")
                    // Decode the saved layer recipe and render afresh. Reusing the
                    // audio cache here would only prove that a WAV can copy itself.
                    val recipe = PadRecipe(patch = patch).toJsonValue()
                    val recalled = PadRecipe.fromJsonText(Json.write(recipe)).patch as LibrationPatch
                    WavWriter.write(replay, recalled.render(), WavWriter.BitDepth.PCM_24)
                    check(hash(replay) == hash(file)) { "layer recipe does not reproduce ${file.name}" }
                    mapOf("sampleFile" to layer.sampleFile, "audio" to file.relativeTo(root).invariantSeparatorsPath,
                        "velocityStart" to layer.velStart, "velocityEnd" to layer.velEnd, "sourceVelocity" to patch.velocity,
                        "sha256" to hash(file), "recipe" to recipe)
                }
                pads += mapOf("slot" to pad.slot, "title" to p.name, "voice" to p.voice.name, "midi" to p.midi,
                    "sourceSeconds" to p.sourceSeconds, "level" to pad.level, "layers" to layers)
            }
            json(root, data.relativeTo(root).invariantSeparatorsPath + "/libration-layers.json", mapOf("engine" to "LIBRATION",
                "model" to 3, "sampleRate" to Dsp.RATE, "layersAreNative" to true, "pads" to pads))
            json(root, "music/kit/layer-replay.json", mapOf("freshRenders" to 32, "allByteIdentical" to true,
                "method" to "Serialize and decode every layer PadRecipe as JSON text, call its public render with fresh physics/audio, write PCM24 and compare SHA-256 with the exported WAV.",
                "sampleHashes" to data.listFiles().orEmpty().filter { it.extension == "wav" }.sortedBy { it.name }
                    .map { mapOf("sampleFile" to it.name, "sha256" to hash(it)) }))
            write(root, "music/kit/pads.tsv", "pad\tname\tvoice\trootMidi\tsourceSeconds\tlayers\n" + pads.joinToString("\n", postfix = "\n") {
                "${it["slot"]}\t${it["title"]}\t${it["voice"]}\t${it["midi"]}\t${it["sourceSeconds"]}\t1-63 soft / 64-127 hard"
            })
            val previewParts = hits.map { hit ->
                val pad = saved.pads.single { it.slot == hit.slot }
                val v = (hit.velocity * 127).roundToInt().coerceIn(1, 127)
                val layer = pad.velocityLayers.single { v in it.velStart..it.velEnd }
                hit.beat to scaled(WavReader.read(File(data, layer.sampleFile)), .25 * sqrt(.5) * pad.level * (.35 + .65 * hit.velocity))
            }
            val score = mapOf("tempoBpm" to BPM, "bars" to 4, "ppq" to Mpc3Clip.PULSES_PER_BEAT,
                "previewLaw" to ".25 * sqrt(.5) * padLevel * (.35 + .65 * hitVelocity); zone selected at MIDI velocity 64",
                "events" to hits.map { mapOf("beat" to it.beat, "onsetFrame" to frame(it.beat), "slot" to it.slot,
                    "note" to Mpc3Note.noteFor(it.slot), "velocity" to it.velocity) })
            clip("music-kit", "Sixteen / a starter", "kit-music", "A computer preview of the exported samples and embedded four-bar MPC3 pattern. Hardware playback still needs your check.",
                mix(previewParts, 1.0), score = score)
            write(root, "music/kit/README.txt", "SNIPSNAP LIBRATION STARTER\n\nUnzip the complete folder. MPC3: open ${exported.program.name} with its adjacent ${data.name} folder. Set the project to 96 BPM for the embedded four-bar clip.\nMPC2: open ${twin.name} inside ${data.name}; keep the WAVs beside it. MPC2 does not receive the embedded pattern.\n\n16 finite one-shot pads; 32 mono 44.1 kHz PCM24 WAVs. Soft zone 1-63 was rendered at touch .35; hard zone 64-127 at touch 1. Each has native energy and articulation. MPC playback velocity can add its own gain. No loops, no mute groups, no per-pad tuning. Sounding roots are baked into the WAVs.\nkit.json is the editable SnipSnap sidecar, with hard-layer recipes and audio fingerprints. libration-layers.json saves complete soft/hard PadRecipes, roots, durations, zones and WAV hashes. Both MPC formats share the same WAVs.\nread-back.txt verifies program fields with SnipSnap's reader. This is not a hardware audition. Load both formats on your MPC before accepting the kit. The web pad buttons play the exact source WAVs at a conservative monitor level, without emulating MPC playback.\n")
        } finally { work.deleteRecursively() }

        // Measured on this Linux JVM. Warm means trajectory precomputation was reused, never audio playback.
        val profile = anchors.map { patch ->
            val cold = mutableListOf<Double>(); val warm = mutableListOf<Double>(); val physics = mutableListOf<Double>()
            var particleCount = 0; var samples = 0
            repeat(3) {
                val options = Libration.Options(seconds = patch.sourceSeconds)
                val t = System.nanoTime()
                val population = Libration.population(patch.voice, patch.macros, options, patch.model)
                physics += (System.nanoTime() - t) / 1e6
                particleCount = population.particles.size
                samples = population.particles.sumOf { it.path.states.size }
                val note = Libration.probe(patch.voice, patch.macros, patch.midi, patch.velocity, options, population, patch.model)
                warm += note.diagnostics.renderMilliseconds
                cold += physics.last() + warm.last()
            }
            mapOf("preset" to patch.name, "sourceSeconds" to patch.sourceSeconds, "particles" to particleCount,
                "controlStates" to samples, "physicsMs" to physics.sorted(), "warmRenderMs" to warm.sorted(), "coldTotalMs" to cold.sorted(),
                "approximateNumericTableBytes" to samples.toLong() * 10 * 8)
        }
        json(root, "music/profile.json", mapOf("runtime" to System.getProperty("java.runtime.version"),
            "os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"), "runsPerPreset" to 3,
            "method" to "Sequential JVM render after bank generation. Warm reuses physics only; cold adds separately timed physics. Numeric table estimate excludes object and array overhead. No phone measurement.",
            "generatorPhysicsCacheLimit" to 16, "profiles" to profile))
        val sourcePaths = listOf("Libration.kt", "LibrationTrajectory.kt", "LibrationPatch.kt", "LibrationPresets.kt", "LibrationKits.kt",
            "Patches.kt", "PadRecipe.kt", "Velocity.kt", "Dsp.kt", "Modes.kt", "Keys.kt").map { "synth/src/main/kotlin/com/snipsnap/synth/$it" } +
            listOf("LibrationMusicalGenerator.kt", "LibrationPerformanceTest.kt", "LibrationTest.kt", "LibrationAuditionGenerator.kt", "AuditionLevel.kt", "DeterminismTest.kt",
                "PatchTypes.kt", "PatchesTest.kt", "PadRecipeTest.kt").map { "synth/src/test/kotlin/com/snipsnap/synth/$it" } +
            listOf("synth/build.gradle.kts", "docs/LIBRATION.md", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
                "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
                "kit/src/main/kotlin/com/snipsnap/kit/KitAssembler.kt", "kit/src/main/kotlin/com/snipsnap/kit/Mpc3Exporter.kt",
                "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt", "xpm/src/main/kotlin/com/snipsnap/xpm/XpmWriter.kt")
        val sources = sourcePaths.associateWith { path ->
            val copy = File(root, "music/source/$path"); copy.parentFile.mkdirs()
            File(repo, path).copyTo(copy, overwrite = true); hash(copy)
        }
        val revision = ProcessBuilder("git", "rev-parse", "HEAD").directory(repo).start().inputStream.bufferedReader().readText().trim()
        val manifest = mapOf("title" to "Libration / in play", "model" to 3, "sampleRate" to Dsp.RATE,
            "tempoBpm" to BPM, "generatedAt" to Instant.now().toString(), "clips" to clips, "pads" to pads,
            "source" to mapOf("baseRevision" to revision, "files" to sources), "profile" to "music/profile.json",
            "levelPolicy" to "Matched uses SnipSnap's loudest 200 ms low-cut RMS proxy, target .03, peak guard .99. Touch groups share the hard note's gain. Native is engine-conditioned output at saved velocity; the study retains its original pre-normalization raw files. Musical phrases have one fixed conservative mix gain and then one whole-clip listening gain.",
            "downloads" to mapOf("bank" to "libration-musical-bank.zip", "kit" to "libration-mpc-starter.zip"),
            "generationSeconds" to (System.nanoTime() - start) / 1e9, "noteRenders" to session.renders + 32 + 9,
            "sessionNoteRenders" to session.renders, "freshLayerReplays" to 32, "profileRenders" to 9,
            "sessionPhysicsMilliseconds" to session.coldPhysicsMs,
            "status" to "Finite audition bank; MPC hardware and phone acceptance pending. Sustained HOLD is unavailable.")
        json(root, "musical-manifest.json", manifest)
        write(root, "README.txt", "LIBRATION / IN PLAY\n\n12 finite presets, 27 touch/length/root probes, 3 repeat examples, bass/chord/melody scores, and a 16-pad two-layer MPC starter.\nRebuild: ./gradlew --no-daemon :synth:generateLibrationMusicalBank\nEvery individual recipe saves model, macros, sourceSeconds, root and touch. Musical score recipes save every full patch, beat, onsetFrame and one fixed mix gain. Tails are complete; no per-note peak matching.\nUse the website or a WAV player for the matched listening pack. The website's Native switch plays engine-conditioned output. Touch comparisons use a shared gain, preserving dynamics in both modes.\nSource snapshots are in music/source. The source reconstruction patch and base revision are added during publication assembly.\nThe previous accepted model 2 study remains separately available on the website. This pass does not add sustained HOLD or phone controls. Hardware acceptance and musical preference need owner listening.\n")
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val path = "synth/src/test/resources/audition/libration-musical/$name"
            File(repo, path).copyTo(File(root, name), overwrite = true)
        }
        val files = root.walkTopDown().filter { it.isFile && !it.name.endsWith(".zip") && it.name !in listOf("CHECKSUMS.sha256", "BANK-CHECKSUMS.sha256") }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        write(root, "CHECKSUMS.sha256", files.joinToString("\n", postfix = "\n") { "${hash(File(root, it))}  $it" })
        zip(root, "libration-mpc-starter.zip", files.filter { it.startsWith("music/kit/") })
        val bankFiles = files.filter { !it.startsWith("music/kit/") && !it.startsWith("music/audio/native/") }
        write(root, "BANK-CHECKSUMS.sha256", bankFiles.joinToString("\n", postfix = "\n") { "${hash(File(root, it))}  $it" })
        zip(root, "libration-musical-bank.zip", bankFiles + "BANK-CHECKSUMS.sha256")
        println("Completed ${clips.size} clips and ${pads.size} two-layer pads in ${(System.nanoTime() - start) / 1e9}s at $root")
    }

    private fun phrase(root: File, session: Session, events: List<Event>, id: String, title: String, group: String, description: String,
        emit: (String, String, String, String, Snip, LibrationPatch?, Map<String, Any?>?, Double?, Map<String, Any?>) -> Unit) {
        val parts = events.map { it.beat to session.note(it.patch) }
        // Conservative sum of all peaks proves a bound even when every tail overlaps.
        val gain = min(.25, .8 / parts.sumOf { it.second.peak().toDouble() }.coerceAtLeast(1e-9))
        val score = mapOf("tempoBpm" to BPM, "mixGain" to gain, "completeTails" to true,
            "events" to events.map { mapOf("beat" to it.beat, "onsetFrame" to frame(it.beat), "patch" to it.patch.toJsonValue()) })
        json(root, "music/scores/$id.json", score)
        emit(id, title, group, description, mix(parts, gain), null, score, null,
            mapOf("score" to "music/scores/$id.json", "eventCount" to events.size, "mixGain" to gain))
    }
}

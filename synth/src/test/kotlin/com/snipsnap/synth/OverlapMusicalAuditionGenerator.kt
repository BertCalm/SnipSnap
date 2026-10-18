package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** The unchanged model-two instrument in musical roles, with replayable dry studies. */
object OverlapMusicalAuditionGenerator {
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val STUDY_GAIN = .25f

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--score") {
            require(args.size == 3) { "Use --score score.json output.wav" }
            val rendered = renderScore(Json.parse(File(args[1]).readText()))
            valid("score", rendered)
            WavWriter.write(File(args[2]), rendered, WavWriter.BitDepth.PCM_24)
            return
        }
        val root = File(args.firstOrNull() ?: "../testkit/overlap-musical-audition").absoluteFile
        val assets = File(root, "assets").also { it.deleteRecursively(); it.mkdirs() }
        val bank = OverlapMusicalBank.all()
        check(bank.size == 12 && bank.map { it.id }.toSet().size == 12)
        check(bank.groupingBy { it.family }.eachCount().values.all { it == 4 })
        val clips = mutableListOf<Map<String, Any?>>()
        val diagnostics = mutableListOf<Map<String, Any?>>()
        val bundled = mutableListOf<File>()

        fun save(
            id: String, title: String, description: String, group: String,
            native: Snip, recipe: JsonValue, metadata: Map<String, Any?>,
        ) {
            valid(id, native)
            val matched = AuditionLevel.level(native)
            valid("$id matched", matched)
            val prefix = "bank1-$id"
            val nativePath = "assets/audio/$prefix-native.wav"
            val matchedPath = "assets/audio/$prefix-matched.wav"
            val recipePath = "assets/recipes/$prefix.json"
            val nativeFile = File(root, nativePath)
            val recipeFile = File(root, recipePath)
            WavWriter.write(nativeFile, native, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            recipeFile.also { it.parentFile.mkdirs() }.writeText(Json.write(recipe))
            bundled += listOf(nativeFile, recipeFile)
            clips += linkedMapOf<String, Any?>(
                "id" to id, "title" to title, "description" to description, "group" to group,
                "native" to nativePath, "matched" to matchedPath, "recipe" to recipePath,
                "seconds" to native.durationSeconds,
                "metrics" to mapOf("nativePeak" to native.peak(), "nativeLoudness" to Loudness.of(native),
                    "matchedPeak" to matched.peak(), "matchedLoudness" to Loudness.of(matched)),
                "hashes" to mapOf("nativeWavSha256" to sha(nativeFile),
                    "matchedWavSha256" to sha(File(root, matchedPath)), "recipeSha256" to sha(recipeFile)),
                "waveform" to waveform(matched.samples),
            ).apply { putAll(metadata) }
            println("OVERLAP musical ${clips.size}/15 $id: ${native.durationSeconds}s, native peak ${native.peak()}")
        }

        for (entry in bank) {
            val patch = entry.patch
            check(patch.model == 2 && patch.macros.keys == setOf("KNOT", "PULSE", "LOCK", "GLOW", "RING", "HOLD"))
            val native = patch.render()
            val recipe = PadRecipe(patch = patch)
            val replay = PadRecipe.fromJsonText(recipe.toJsonText()).render()
            check(native.samples.contentEquals(replay.samples)) { "${entry.id}: recipe replay differs" }
            val probe = Overlap.probe(patch.topology, patch.macros, patch.midi, patch.velocity, model = patch.model)
            val d = probe.diagnostics
            check(probe.raw.all { it.isFinite() } && d.rawPeak > 0 && d.rawPeak < 1)
            check(d.maxLoopGain < 1 && d.maxBufferAbs < 1) { "${entry.id}: invalid feedback state" }
            diagnostics += mapOf("id" to entry.id, "recipeReplayExact" to true,
                "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
                "maxLoopGain" to d.maxLoopGain, "maxBufferAbs" to d.maxBufferAbs,
                "excitationSeconds" to d.excitationSeconds, "targetHz" to d.targetHz,
                "phaseOrderHistory" to d.phaseOrderHistory)
            save(entry.id, entry.name, entry.description, when (entry.family) {
                OverlapPresetFamily.PLUCK -> "plucks"
                OverlapPresetFamily.TEXTURE -> "textures"
                OverlapPresetFamily.BREATH -> "breaths"
            }, native, recipe.toJsonValue(), mapOf(
                "topology" to patch.topology.name, "note" to note(patch.midi),
                "midi" to patch.midi, "velocity" to patch.velocity, "macros" to patch.macros,
                "recipeKind" to "pad", "modelVersion" to patch.model))
        }

        fun event(id: String, seconds: Double, midi: Int, velocity: Float): JsonValue = json(mapOf(
            "atFrame" to (seconds * RATE).roundToInt(),
            "recipe" to PadRecipe(bank.first { it.id == id }.patch.copy(midi = midi, velocity = velocity)).toJsonValue(),
        ))
        fun study(id: String, title: String, description: String, seconds: Double, events: List<JsonValue>) {
            val score = json(mapOf("overlapStudy" to 1, "modelVersion" to 2,
                "sampleRate" to RATE, "frames" to (seconds * RATE).roundToInt(), "mixGain" to STUDY_GAIN,
                "tailPolicy" to "Every event renders its full native finite tail; no truncation or effects.",
                "events" to events))
            val native = renderScore(score)
            val replay = renderScore(Json.parse(Json.write(score)))
            check(native.samples.contentEquals(replay.samples)) { "$id: score replay differs" }
            diagnostics += mapOf("id" to id, "scoreReplayExact" to true, "mixGain" to STUDY_GAIN)
            save(id, title, description, "phrases", native, score,
                mapOf("recipeKind" to "score", "mixGain" to STUDY_GAIN, "modelVersion" to 2))
        }
        val notes = listOf(60, 67, 72, 67, 60, 55, 60, 67)
        study("glass-steps", "GLASS STEPS", "Eight GLASS THREAD plucks across open intervals, with changing velocity. Dry overlapping native tails; fixed 0.25 mix gain.", 8.3,
            notes.mapIndexed { i, midi -> event("glass-thread", i * .6, midi, listOf(.65f, .8f, .95f, .7f)[i % 4]) })
        study("cloud-duet", "CLOUD DUET", "FIFTH CLOUD at C3, answered at a G3 reference four seconds later. Two complete finite textures overlap; fixed 0.25 mix gain.", 12.0,
            listOf(event("fifth-cloud", 0.0, 48, .75f), event("fifth-cloud", 4.0, 55, .6f)))
        study("pulse-answer", "PULSE ANSWER", "PAIR PULSE at C3, answered at a G3 reference after 3.5 seconds. These are free-running triggered breaths, with no tempo synchronization; fixed 0.25 mix gain.", 11.5,
            listOf(event("pair-pulse", 0.0, 48, .75f), event("pair-pulse", 3.5, 55, .85f)))

        val bankFile = File(assets, "bank.json").also { it.writeText(Json.write(json(mapOf(
            "overlapBank" to 1, "modelVersion" to 2, "entries" to bank.map {
                mapOf("id" to it.id, "name" to it.name, "description" to it.description,
                    "family" to it.family.name, "recipe" to PadRecipe(it.patch).toJsonValue())
            })))) }
        val readme = File(assets, "README.txt").also { it.writeText("""
            OVERLAP provisional musical bank 1 / engine model 2 / audition revision 3
            Twelve native mono 44.1-kHz PCM24 sounds plus three scheduled dry studies.
            Native solos use the regular patch renderer, house level, velocity and end fade.
            Studies sum full native event renders at a common linear gain of 0.25, without effects.
            Matched online playback uses the loudest-200-ms target of 0.03 and is not in this ZIP.
            Each solo JSON is a normal SnipSnap PadRecipe. bank.json includes all twelve recipes.
            Each study JSON is an overlapStudy score with exact frame offsets and full PadRecipes.
            Replay a study with OverlapMusicalAuditionGenerator --score score.json output.wav.
            All sounds are finite; textures are not seamless loops or tempo-synchronized voices.
            MIDI is a geometry reference; dominant pitch may follow another resonant family.
            The bank remains provisional and outside the phone picker/factory roster.
        """.trimIndent() + "\n") }
        bundled += listOf(bankFile, readme)
        val manifest = linkedMapOf<String, Any?>(
            "title" to "OVERLAP", "subtitle" to "SnipSnap · Musical bank", "modelVersion" to 2,
            "auditionRevision" to 3, "bankVersion" to 1,
            "bundle" to "assets/overlap-musical-bank.zip", "baseline" to "r2/index.html",
            "renderedAt" to Instant.now().toString(), "sourceRevision" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "sampleRate" to RATE, "channels" to 1,
            "notes" to listOf("Twelve provisional musical patches and three dry scheduled studies, using the unchanged model-two engine.",
                "Native playback preserves regular renderer level and velocity. Matched playback uses the loudest-200-ms target of .03 with a .99 peak guard.",
                "Each study sums complete native event tails with a common .25 linear mix gain; scores preserve frame offsets and full recipes.",
                "MIDI is a geometry reference. Strongest pitch can follow another family, especially at intermediate KNOT.",
                "HOLD gives finite excitation; no seamless loop, tempo sync, or phone-picker integration is claimed."),
            "clips" to clips,
        )
        File(assets, "manifest.json").writeText(Json.write(json(manifest)))
        File(assets, "diagnostics.json").writeText(Json.write(json(mapOf("modelVersion" to 2, "clips" to diagnostics))))
        val template = File(root.parentFile.parentFile, "prototype/overlap-musical-audition")
        check(template.isDirectory) { "missing musical audition template" }
        template.copyRecursively(root, overwrite = true)
        ZipOutputStream(File(assets, "overlap-musical-bank.zip").outputStream()).use { zip ->
            bundled.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("OVERLAP musical audition: ${root.path}")
    }

    /** Offline study replay. No per-event gain changes beyond each patch's native velocity. */
    private fun renderScore(value: JsonValue): Snip {
        val score = value.obj()
        require(score.getValue("overlapStudy").int() == 1 && score.getValue("modelVersion").int() == 2)
        require(score.getValue("sampleRate").int() == RATE)
        val frames = score.getValue("frames").int().also { require(it in 1..RATE * 30) }
        val gain = score.getValue("mixGain").num().toFloat().also { require(it.isFinite() && it > 0 && it <= 1) }
        val out = FloatArray(frames)
        val events = score.getValue("events").arr().also { require(it.isNotEmpty() && it.size <= 128) }
        for (event in events) {
            val e = event.obj()
            val at = e.getValue("atFrame").int().also { require(it in 0 until frames) }
            val recipe = PadRecipe.fromJsonValue(e.getValue("recipe"))
            require(recipe.fx == null && (recipe.patch as? OverlapPatch)?.model == 2)
            val snip = recipe.render()
            valid("score event", snip)
            require(snip.samples.size <= frames - at) { "score would truncate a native tail" }
            for (i in snip.samples.indices) out[at + i] += snip.samples[i] * gain
        }
        return Snip(out, 1, RATE)
    }

    private fun valid(id: String, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == RATE && snip.samples.all { it.isFinite() }) { "$id: invalid audio" }
        check(snip.peak() in .000001f..<1f) { "$id: silent or clipped audio (${snip.peak()})" }
    }
    private fun note(midi: Int) = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12] + (midi / 12 - 1)
    private fun waveform(samples: FloatArray) = (0 until 100).map { b ->
        val a = b * samples.size / 100
        val end = ((b + 1) * samples.size / 100).coerceAtLeast(a + 1)
        (a until end).maxOf { abs(samples[it]).toDouble() }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(vararg args: String): String = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start().let { p ->
        val result = p.inputStream.bufferedReader().readText().trim(); check(p.waitFor() == 0); result
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported manifest value $value")
    }
}

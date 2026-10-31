package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Listening evidence from saved production recipes, with no separate synthesis model. */
object UprightAuditionGenerator {
    private const val RATE = 44_100
    private const val MODEL = "upright-kotlin-1"
    private val noteNames = mapOf(28 to "E1", 33 to "A1", 40 to "E2")

    private data class Case(
        val id: String, val title: String, val group: String,
        val articulation: String, val midi: Int, val velocity: Float,
        val changes: Map<String, Float> = emptyMap(),
    )
    private data class Event(val onset: Double, val recipe: PadRecipe)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size in 3..4) { "--replay recipe.json output.wav [native|matched]" }
            val value = Json.parse(File(args[1]).readText())
            val native = replay(value)
            val mode = args.getOrNull(3) ?: "native"
            require(mode == "native" || mode == "matched")
            WavWriter.write(File(args[2]), if (mode == "matched") match(native) else native)
            println("Replayed ${File(args[1]).name} as $mode")
            return
        }
        require(args.size <= 1) { "supply only the output directory" }
        generate(File(args.firstOrNull() ?: "../testkit/upright-audition"))
    }

    fun generate(destination: File) {
        val root = destination.canonicalFile
        require(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val clips = mutableListOf<Map<String, Any?>>()
        val measurements = mutableListOf<Map<String, Any?>>()
        val cases = cases()
        for (case in cases) {
            val recipe = recipe(case)
            val encoded = recipe.toJsonText()
            val decoded = PadRecipe.fromJsonText(encoded)
            require(decoded == recipe) { "${case.id}: recipe state was not retained" }
            val before = System.nanoTime()
            val native = decoded.render()
            val elapsed = (System.nanoTime() - before) / 1_000_000.0
            // This is an independent render after parsing the exported recipe, not a
            // buffer compared with itself. Production normalization cache may be warm.
            require(native.samples.contentEquals(PadRecipe.fromJsonText(encoded).render().samples)) {
                "${case.id}: saved recipe does not reproduce its audio"
            }
            val description = when (case.group) {
                "body" -> "The same A1 string and playing strength, with only WOOD changed."
                "control" -> "A single production control setting; all companions remain at their defaults."
                "pair" -> "Two production controls varied together, at A1 and the same playing strength."
                else -> "A finite production pluck. Playing dynamics retains excitation strength; Even level uses an RMS proxy."
            }
            clips += writeClip(root, case.id, case.title, case.group, case.articulation,
                native, recipe.toJsonValue(), description, case.midi, case.velocity, recipe.patch!!.macros)
            measurements += linkedMapOf("id" to case.id, "renderMs" to elapsed,
                "recipeReplayExact" to true, "metrics" to metrics(native, case.midi, case.articulation))
        }

        val phraseEvents = listOf(Triple(0.0, 33, .65f), Triple(.50, 33, .40f),
            Triple(.75, 36, .75f), Triple(1.25, 40, .90f), Triple(1.75, 38, .55f),
            Triple(2.25, 36, .70f), Triple(2.75, 31, .90f), Triple(3.50, 33, .65f))
        val performances = listOf("ROUND", "MUTED").associateWith { articulation ->
            phraseEvents.map { (onset, midi, velocity) ->
                Event(onset, recipe(Case("", "", "phrase", articulation, midi, velocity)))
            }
        }
        val phraseBuffers = performances.mapValues { (_, events) -> perform(events, 1f) }
        val maximum = phraseBuffers.values.maxOf { peak(it.samples) }
        val sharedGain = if (maximum > .95f) .95f / maximum else 1f
        for ((articulation, events) in performances) {
            val id = "phrase-${articulation.lowercase(Locale.ROOT)}"
            val performance = json(linkedMapOf(
                "auditionPerformance" to 1, "model" to MODEL,
                "articulation" to articulation, "gain" to sharedGain,
                "scope" to "A finite rendered reference performance; not a single saved pad or held instrument.",
                "events" to events.map { linkedMapOf("onsetSeconds" to it.onset, "recipe" to it.recipe.toJsonValue()) },
            ))
            val native = perform(events, sharedGain)
            require(native.samples.contentEquals(replay(Json.parse(Json.write(performance))).samples)) {
                "$id: saved performance does not replay exactly"
            }
            clips += writeClip(root, id, "${articulation.lowercase().replaceFirstChar { it.titlecase() }} · bass phrase",
                "phrase", articulation, native, performance,
                "The same eight notes, timings and playing strengths. Both phrases share one recorded headroom gain.")
            measurements += linkedMapOf("id" to id, "recipeReplayExact" to true,
                "sharedPhraseGain" to sharedGain, "metrics" to metrics(native, null, articulation))
        }

        val repo = repository()
        val sourcePaths = listOf("synth/src/main/kotlin/com/snipsnap/synth/UprightBass.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt", "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt", "synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/UprightAuditionGenerator.kt")
        val source = linkedMapOf<String, Any?>("commit" to git(repo, "rev-parse", "HEAD"),
            "dirty" to git(repo, "status", "--porcelain").isNotEmpty(),
            "files" to sourcePaths.associateWith { sha256(File(repo, it)) })
        val default = recipe(Case("", "", "note", "ROUND", 33, .65f))
        default.render()
        val repeatedMs = (0 until 5).map {
            val before = System.nanoTime(); default.render(); (System.nanoTime() - before) / 1_000_000.0
        }
        val report = linkedMapOf<String, Any?>("model" to MODEL, "implementation" to "SnipSnap Kotlin",
            "source" to source, "clipCount" to clips.size, "wavCount" to clips.size * 2,
            "allSavedRecipesReplayExactly" to true,
            "timingScope" to "Host JVM production renders; not Android phone latency. Per-case timings may include a cold reference-level cache.",
            "warmReferenceRenderMs" to repeatedMs, "measurements" to measurements,
            "limitations" to listOf("Python noise and output filters differ; this is a fresh Kotlin listening comparison.",
                "One-way body coloration, not measured upright acoustics, fingerboard contact, bow or bridge backreaction.",
                "Finite notes only; no HOLD, keygroup sustain or note-off release is claimed.",
                "Even level matches whole-file RMS with a peak guard; perceived loudness can differ.",
                "Pitch metrics use the existing production Pitch detector in independent early and late windows; they are not hearing."))
        File(root, "report.json").writeText(Json.write(json(report)) + "\n")
        val manifest = linkedMapOf<String, Any?>("title" to "Upright bass: Kotlin audition", "model" to MODEL,
            "modelVersion" to 1, "implementation" to "SnipSnap Kotlin", "sampleRate" to RATE,
            "channels" to 1, "pcm_bits" to 24, "source" to source,
            "matched_level" to "Whole-file RMS proxy per clip with a 0.90 peak guard; not perceived loudness matching.",
            "native_level" to "Production PluckPatch render; source excitation velocity is saved. Both phrases share one extra headroom factor.",
            "note_selection" to "TUNE spans E1–E3 in semitones; MIDI labels describe the selected production pitch.",
            "controls" to listOf(linkedMapOf("name" to "TUNE", "default" to 5f / 24f),
                linkedMapOf("name" to "TOUCH", "default" to .5f), linkedMapOf("name" to "DAMP", "default" to .18f),
                linkedMapOf("name" to "WOOD", "default" to .55f)),
            "hold" to "not offered: finite plucked voice", "clips" to clips)
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        val html = requireNotNull(javaClass.getResourceAsStream("/audition/upright-audition.html"))
            .bufferedReader().use { it.readText() }
        File(root, "index.html").writeText(html)
        File(root, "README.txt").writeText("""
            UPRIGHT — finite PLUCK voice, Kotlin listening evidence

            Open index.html through a static HTTP server, or play audio/ WAVs directly.
            Native clips are actual saved production PadRecipe renders. Matched clips
            use a whole-file RMS proxy, not a claim of equal perceived loudness.
            The two bass phrases share the same events and one overlap-headroom gain.
            A phrase is a saved audition performance with one PadRecipe per event.

            Rebuild: ./gradlew :synth:generateUprightAudition
            Replay a downloaded recipe: ./gradlew :synth:generateUprightAudition \
              -PuprightReplay=/absolute/recipe.json -PuprightReplayOutput=/absolute/note.wav
            Add -PuprightReplayMode=matched for the matched WAV.

            This is the Kotlin port of an owner-approved Python sound study. Kotlin
            source noise, filters and normalization differ; its musical acceptance
            still belongs to listening. No physical phone or MPC check is implied.
        """.trimIndent() + "\n")
        val checksums = root.walkTopDown().filter { it.isFile && it.name !in setOf("checksums.json", "upright-bass-listening-pack.zip") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .associate { it.relativeTo(root).invariantSeparatorsPath to sha256(it) }
        File(root, "checksums.json").writeText(Json.write(json(checksums)) + "\n")
        val archive = File(root, "upright-bass-listening-pack.zip")
        val files = root.walkTopDown().filter { it.isFile && it != archive }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("Wrote ${clips.size} native/matched Kotlin cases and ${archive.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        for (articulation in listOf("ROUND", "MUTED")) for (midi in listOf(28, 33, 40)) {
            for (velocity in listOf(.35f, .65f, 1f)) {
                add(Case("${articulation.lowercase()}-m$midi-v${(velocity * 100).roundToInt().toString().padStart(3, '0')}",
                    "${articulation.lowercase().replaceFirstChar { it.titlecase() }} · ${noteNames.getValue(midi)} · ${(velocity * 100).roundToInt()}% strength",
                    "note", articulation, midi, velocity))
            }
        }
        add(Case("round-m33-body-off", "Body off · A1", "body", "ROUND", 33, .65f, mapOf("WOOD" to 0f)))
        add(Case("round-m33-body-full", "Body full · A1", "body", "ROUND", 33, .65f, mapOf("WOOD" to 1f)))
        for (macro in listOf("TUNE", "TOUCH", "DAMP", "WOOD")) for (step in 0..4) {
            val value = step / 4f
            val midi = if (macro == "TUNE") 28 + (value * 24).roundToInt() else 33
            add(Case("control-${macro.lowercase()}-$step", "$macro · ${(value * 100).roundToInt()}%",
                "control", "ROUND", midi, .65f, mapOf(macro to value)))
        }
        for ((a, b) in listOf("DAMP" to "WOOD", "TOUCH" to "DAMP")) for (i in 0..2) for (j in 0..2) {
            add(Case("pair-${a.lowercase()}-${b.lowercase()}-$i-$j", "$a ${i * 50}% · $b ${j * 50}%",
                "pair", "ROUND", 33, .65f, mapOf(a to i / 2f, b to j / 2f)))
        }
    }

    private fun recipe(case: Case): PadRecipe {
        val macros = Pluck.defaults(PluckVoice.UPRIGHT) + mapOf("TUNE" to (case.midi - 28) / 24f,
            "DAMP" to if (case.articulation == "MUTED") 1f else .18f) + case.changes
        return PadRecipe(PluckPatch(case.title.ifEmpty { "UPRIGHT ${case.articulation}" }, PluckVoice.UPRIGHT, macros, velocity = case.velocity))
    }

    private fun writeClip(root: File, id: String, title: String, group: String, articulation: String,
        native: Snip, recipe: JsonValue, description: String, midi: Int? = null,
        velocity: Float? = null, macros: Map<String, Float>? = null): Map<String, Any?> {
        validate(native)
        val nativePath = "audio/native/$id.wav"; val matchedPath = "audio/matched/$id.wav"
        val recipePath = "recipes/$id.json"
        WavWriter.write(File(root, nativePath), native, WavWriter.BitDepth.PCM_24)
        WavWriter.write(File(root, matchedPath), match(native), WavWriter.BitDepth.PCM_24)
        File(root, recipePath).also { it.parentFile.mkdirs(); it.writeText(Json.write(recipe) + "\n") }
        return linkedMapOf("id" to id, "title" to title, "group" to group, "kind" to if (group == "phrase") "phrase" else "note",
            "articulation" to articulation, "midi" to midi, "velocity" to velocity, "macros" to macros,
            "description" to description, "native" to nativePath, "matched" to matchedPath, "recipe" to recipePath,
            "native_sha256" to sha256(File(root, nativePath)), "matched_sha256" to sha256(File(root, matchedPath)),
            "seconds" to native.samples.size.toDouble() / RATE)
    }

    private fun perform(events: List<Event>, gain: Float): Snip {
        require(events.isNotEmpty() && gain.isFinite() && gain > 0f && gain <= 1f)
        val rendered = events.map { event ->
            require(event.onset.isFinite() && event.onset >= 0.0)
            event.onset.times(RATE).roundToInt() to event.recipe.render()
        }
        val samples = FloatArray(rendered.maxOf { (start, snip) -> start + snip.samples.size })
        for ((start, snip) in rendered) {
            require(snip.channels == 1 && snip.sampleRate == RATE)
            snip.samples.forEachIndexed { i, value -> samples[start + i] += value }
        }
        for (i in samples.indices) samples[i] *= gain
        Dsp.fadeTail(samples)
        return Snip(samples, channels = 1, sampleRate = RATE)
    }

    private fun replay(value: JsonValue): Snip {
        val fields = value.obj()
        if (fields["auditionPerformance"] == null) return PadRecipe.fromJsonValue(value).render()
        require(fields.getValue("auditionPerformance").int() == 1)
        val gain = fields.getValue("gain").num().toFloat()
        val events = fields.getValue("events").arr().map { event ->
            val e = event.obj(); Event(e.getValue("onsetSeconds").num(), PadRecipe.fromJsonValue(e.getValue("recipe")))
        }
        return perform(events, gain)
    }

    private fun match(snip: Snip): Snip {
        val samples = snip.samples.copyOf()
        val rms = rms(samples)
        val gain = minOf(.10 / rms.coerceAtLeast(1e-12), .90 / peak(samples).coerceAtLeast(1e-12f))
        for (i in samples.indices) samples[i] = (samples[i] * gain).toFloat()
        return snip.copy(samples = samples)
    }

    private fun metrics(snip: Snip, midi: Int?, articulation: String): Map<String, Any?> {
        validate(snip)
        val pitches = if (midi == null) emptyList() else {
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val late = if (articulation == "MUTED") .42f else .80f
            listOf(.08f, late).map { from ->
                val pitch = Pitch.detect(snip, fromSec = from, windowSec = .35f)
                linkedMapOf("fromSeconds" to from, "windowSeconds" to .35f, "hz" to pitch?.hz,
                    "confidence" to pitch?.confidence, "centsError" to pitch?.let { 1200 * ln(it.hz / hz) / ln(2.0) })
            }
        }
        return linkedMapOf("frames" to snip.samples.size, "finite" to true, "peak" to peak(snip.samples),
            "dc" to snip.samples.map { it.toDouble() }.average(), "rms" to rms(snip.samples), "pitchWindows" to pitches)
    }

    private fun validate(snip: Snip) {
        require(snip.channels == 1 && snip.sampleRate == RATE && snip.samples.isNotEmpty())
        require(snip.samples.all { it.isFinite() }) { "non-finite production render" }
        require(peak(snip.samples) <= 1f) { "production render exceeds PCM range" }
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun peak(samples: FloatArray): Float = samples.maxOf { abs(it) }
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun repository(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, ".git").exists() } ?: error("run inside the SnipSnap checkout")
    private fun git(repo: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor() == 0) { "git failed" }; return text
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported JSON value ${value.javaClass.name}")
    }
}

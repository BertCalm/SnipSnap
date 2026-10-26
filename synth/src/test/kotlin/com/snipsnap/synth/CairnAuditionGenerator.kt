package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.*

/** Rebuildable finite listening proof. Measurements describe this JVM, never a phone. */
object CairnAuditionGenerator {
    private val middle = mapOf("TUNE" to .5f, "WEIGHT" to .5f, "SLOPE" to .4f,
        "GROOVE" to .35f, "FRICTION" to .3f, "BODY" to .4f, "HOLD" to 0f)
    private data class Case(val id: String, val section: String, val patch: CairnPatch,
                            val description: String, val uncoupled: Boolean = false)

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/cairn-reconstruction").canonicalFile
        root.mkdirs()
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, ".git").exists() }
        val revision = ProcessBuilder("git", "rev-parse", "HEAD").directory(repo).start().let {
            val s = it.inputStream.bufferedReader().readText().trim(); check(it.waitFor() == 0); s
        }
        val sourceFiles = listOf("Cairn.kt", "CairnPatch.kt").associateWith { name ->
            sha(File(repo, "synth/src/main/kotlin/com/snipsnap/synth/$name").readBytes())
        }
        // Time the first call before touching the engine object. This includes class
        // initialisation, cold mechanics and the complete output stage, not Gradle startup.
        val firstStart = System.nanoTime()
        val first = Cairn.inspect(CairnVoice.ANSWER)
        val firstMs = (System.nanoTime() - firstStart) / 1e6
        if ("--cold-only" in args) {
            File(root, "cold.json").writeText(Json.write(json(mapOf("firstAnswerMs" to firstMs,
                "mechanicsMs" to first.diagnostics.mechanicsMs, "audioMs" to first.diagnostics.audioMs,
                "seconds" to first.samples.size.toDouble() / Cairn.RATE,
                "revision" to revision, "sourceSha256" to sourceFiles))))
            println("CAIRN cold first ANSWER $firstMs ms")
            return
        }
        for (file in listOf("index.html", "styles.css", "app.js")) {
            val stream = requireNotNull(javaClass.getResourceAsStream("/cairn-audition/$file"))
            stream.use { input -> File(root, file).outputStream().use { input.copyTo(it) } }
        }
        var cubePoints = 0
        var minimumReplies = Int.MAX_VALUE
        for (weight in listOf(.25f, .5f, .75f)) for (slope in listOf(.25f, .5f, .75f))
            for (groove in listOf(.25f, .5f, .75f)) for (friction in listOf(.25f, .5f, .75f))
                for (body in listOf(.25f, .5f, .75f)) {
                    val motion = Cairn.motion(CairnVoice.ANSWER, middle + mapOf("WEIGHT" to weight,
                        "SLOPE" to slope, "GROOVE" to groove, "FRICTION" to friction, "BODY" to body))
                    minimumReplies = min(minimumReplies, motion.events.count { it.stone > 0 })
                    cubePoints++
                }
        check(minimumReplies >= 3) { "ANSWER lost its reply inside the middle control cube" }
        val cases = mutableListOf<Case>()
        fun add(id: String, section: String, voice: CairnVoice, m: Map<String, Float>, description: String,
                midi: Int = 60, velocity: Float = 1f, uncoupled: Boolean = false) {
            cases += Case(id, section, CairnPatch(id, voice, m, midi, velocity), description, uncoupled)
        }
        for (v in CairnVoice.entries) {
            val stem = v.name.lowercase()
            for (note in listOf(48, 60, 72)) add("$stem-default-$note", "Voices", v, Cairn.defaults(v),
                if (v == CairnVoice.KNOCK) "Compact direct contacts on a lossy mount." else "The shared mount wakes higher stones.", note)
            add("$stem-middle", "Equal controls", v, middle, "Both voices use exactly the same seven controls.")
            for (velocity in listOf(.35f, .65f)) add("$stem-velocity-${(velocity * 100).roundToInt()}",
                "Velocity", v, Cairn.defaults(v), "Velocity changes launch work, contact times and pulse width.", velocity = velocity)
            for (key in listOf("TUNE", "WEIGHT", "SLOPE", "GROOVE", "FRICTION", "BODY", "HOLD"))
                for ((index, step) in listOf(0f, .25f, .5f, .75f, if (key == "HOLD") .95f else 1f).withIndex())
                    add("$stem-${key.lowercase()}-$index", "${v.name} / $key", v, middle + (key to step),
                        "$key = $step; all other controls fixed at the common vector. HOLD's loop step is reserved.")
            for (body in listOf(.25f, .75f)) for (friction in listOf(.25f, .75f))
                add("$stem-pair-${(body*100).roundToInt()}-${(friction*100).roundToInt()}", "BODY × FRICTION", v,
                    middle + mapOf("BODY" to body, "FRICTION" to friction), "Mount mobility and resistance move together.")
        }
        add("answer-no-coupling", "Cause and effect", CairnVoice.ANSWER, Cairn.defaults(CairnVoice.ANSWER),
            "Same setup, with only mechanical coupling removed. No higher stone is directly launched.", uncoupled = true)
        add("answer-middle-no-coupling", "Cause and effect", CairnVoice.ANSWER, middle,
            "Common controls, with only mechanical coupling removed.", uncoupled = true)
        val clips = mutableListOf<Map<String, Any?>>()
        for ((index, case) in cases.withIndex()) {
            val p = case.patch
            val start = System.nanoTime()
            val r = Cairn.inspect(p.voice, p.macros, p.midi, p.velocity, p.startAtContact,
                Cairn.Options(disableCoupling = case.uncoupled))
            val elapsed = (System.nanoTime() - start) / 1e6
            val d = r.diagnostics
            val file = File(root, "audio/${case.id}.wav")
            WavWriter.write(file, Snip(r.samples, 1, Cairn.RATE))
            WavWriter.write(File(root, "full/${case.id}.wav"), Snip(r.full, 1, Cairn.RATE))
            // Raw 4x audio remains at its actual rate, rather than being mislabeled as MPC PCM.
            WavWriter.write(File(root, "raw/${case.id}.wav"), Snip(r.raw, 1, d.internalRate), allowNonMpcRate = true)
            File(root, "recipes/${case.id}.json").apply { parentFile.mkdirs(); writeText(p.toJsonText()) }
            File(root, "traces/${case.id}.csv").apply {
                parentFile.mkdirs()
                writeText("seconds,energy,withdrawnWork,primaryPosition,secondPosition,thirdPosition,fourthPosition,mountPosition\n" +
                    d.motion.states.joinToString("\n") { s -> "${s.seconds},${s.energy},${s.withdrawnWork},${s.positions.joinToString(",")}" } + "\n")
            }
            File(root, "events/${case.id}.csv").apply {
                parentFile.mkdirs()
                writeText("seconds,stone,groove,work,speedBefore,speedAfter\n" + d.motion.events.joinToString("\n") {
                    "${it.seconds},${it.stone},${it.groove},${it.work},${it.speedBefore},${it.speedAfter}"
                } + "\n")
            }
            val pitch = Pitch.detect(Snip(r.samples, 1, Cairn.RATE), fromSec = .20f, windowSec = .25f)
            val peak = r.samples.maxOf { abs(it) }
            val onset = r.samples.indexOfFirst { abs(it) > peak * .01f }
            clips += mapOf("id" to case.id, "section" to case.section, "voice" to p.voice.name,
                "description" to case.description, "midi" to p.midi, "velocity" to p.velocity,
                "macros" to Cairn.settled(p.voice, p.macros), "uncoupled" to case.uncoupled,
                "audio" to "audio/${case.id}.wav", "full" to "full/${case.id}.wav",
                "recipe" to "recipes/${case.id}.json", "trace" to "traces/${case.id}.csv", "events" to "events/${case.id}.csv",
                "sha256" to sha(file.readBytes()), "seconds" to r.samples.size.toDouble() / Cairn.RATE,
                "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "peak" to peak,
                "dc" to r.samples.average(), "loudness" to Loudness.of(Snip(r.samples, 1, Cairn.RATE)),
                "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
                "requestedHz" to d.requestedHz, "onsetMs" to if (onset >= 0) onset * 1000.0 / Cairn.RATE else null,
                "firstContactMs" to d.firstContactFrame * 1000.0 / Cairn.RATE,
                "outputStartMs" to d.outputStartFrame * 1000.0 / Cairn.RATE,
                "primaryContacts" to d.motion.events.count { it.stone == 0 },
                "higherContacts" to d.motion.events.count { it.stone > 0 },
                "firstAnswerMs" to d.motion.events.firstOrNull { it.stone > 0 }?.seconds?.times(1000),
                "fullSeconds" to r.full.size.toDouble() / Cairn.RATE,
                "contactEvents" to d.motion.events.map { mapOf("seconds" to it.seconds, "stone" to it.stone, "work" to it.work) },
                "launchEnergy" to d.motion.initialEnergy, "withdrawnWork" to d.motion.withdrawnWork,
                "dissipatedWork" to d.motion.dissipatedWork, "terminalBrakeWork" to d.motion.terminalBrakeWork,
                "finalEnergy" to d.motion.finalEnergy, "largestEnergyIncrease" to d.motion.largestEnergyIncrease,
                "tailEnergyIncrease" to d.tailEnergyIncrease, "maxModalEnergy" to d.maxModalEnergy,
                "renderMs" to elapsed, "mechanicsMs" to d.mechanicsMs, "audioMs" to d.audioMs,
                "cacheHit" to d.mechanicsCacheHit)
            println("CAIRN ${index + 1}/${cases.size}: ${case.id}, ${d.motion.events.count { it.stone > 0 }} higher contacts, ${"%.1f".format(java.util.Locale.ROOT, elapsed)} ms")
        }
        // Separate uncached controls from a cached-motion pitch turn. Warm the JIT
        // first, but report the untouched first call as a separate cold number.
        repeat(8) { Cairn.render(CairnVoice.ANSWER, middle) }
        val bench = mutableListOf<Map<String, Any>>()
        for (voice in CairnVoice.entries) for (i in 0..11) {
            val m = middle + ("SLOPE" to (.35f + i * .007f))
            val a = Cairn.inspect(voice, m, options = Cairn.Options(bypassCache = true))
            Cairn.inspect(voice, m) // populate exactly this motion key for the pitch-only trial
            val b = Cairn.inspect(voice, m + ("TUNE" to .75f))
            bench += mapOf("voice" to voice.name, "trial" to i,
                "uncachedMs" to (a.diagnostics.mechanicsMs + a.diagnostics.audioMs),
                "cachedMotionPitchMs" to (b.diagnostics.mechanicsMs + b.diagnostics.audioMs),
                "mechanicsMs" to a.diagnostics.mechanicsMs, "cacheHit" to b.diagnostics.mechanicsCacheHit)
        }
        File(root, "manifest.json").writeText(Json.write(json(mapOf(
            "title" to "Cairn / KNOCK and ANSWER reconstruction", "generatedAt" to Instant.now().toString(),
            "sourceRevision" to revision, "sourceSha256" to sourceFiles, "model" to Cairn.MODEL_VERSION,
            "engine" to "Kotlin JVM, 4x internal, mono 44.1 kHz",
            "scope" to "Finite two-voice reconstruction. Historical recordings remain separate. No HOLD loop or phone benchmark.",
            "proof" to mapOf("middleCubePoints" to cubePoints, "minimumHigherContacts" to minimumReplies),
            "coldFirstAnswerMs" to firstMs, "benchmark" to bench, "clips" to clips))))
        println("CAIRN wrote ${cases.size} clips to $root; cold first ANSWER $firstMs ms")
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported CAIRN manifest type ${value.javaClass.name}")
    }
}

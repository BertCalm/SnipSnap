package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A small, reproducible ONSET listening experiment, rendered by the actual JVM engine. */
object OnsetAuditionGenerator {
    private val blendSteps = listOf(0f, .25f, .5f, .75f, 1f)
    private const val BPM = 104.0
    private const val BARS = 2
    private const val STEPS_PER_BAR = 16
    private const val PACK_NAME = "onset-audition.zip"

    private data class BeatHit(val voice: OnsetVoice, val step: Int, val gain: Float)

    // These events and gains are identical in all three beat examples. No snare or external FX.
    private val beatHits: List<BeatHit> = buildList {
        val kicks = listOf(0 to .78f, 6 to .61f, 8 to .75f, 14 to .65f,
            16 to .78f, 23 to .59f, 24 to .75f, 26 to .57f, 30 to .65f)
        kicks.forEach { (step, gain) -> add(BeatHit(OnsetVoice.KICK, step, gain)) }
        for (step in 0 until BARS * STEPS_PER_BAR) {
            val gain = when (step % 4) { 0 -> .24f; 2 -> .21f; else -> .14f }
            add(BeatHit(OnsetVoice.HAT_CLOSED, step, gain))
        }
    }.sortedWith(compareBy<BeatHit> { it.step }.thenBy { it.voice.ordinal })

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "usage: OnsetAuditionGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/onset-audition")
        require(root.mkdirs() || root.isDirectory) { "could not create ${root.absolutePath}" }
        val clips = mutableListOf<Map<String, Any?>>()
        val renders = linkedMapOf<Pair<OnsetVoice, Float>, Snip>()

        for (voice in OnsetVoice.entries) {
            for (blend in blendSteps) {
                val macros = Onset.defaults(voice) + ("BLEND" to blend)
                val snip = Onset.render(voice, macros)
                validateAudio(snip, "${voice.name} at blend $blend")
                renders[voice to blend] = snip
                val id = "${voiceId(voice)}-${blendId(blend)}"
                clips += writeClip(root, id, "${voiceTitle(voice)} · ${blendTitle(blend)}", voice.name,
                    blend, "hit", "One dry hit. Only BLEND changes across this row; the other five controls stay fixed.",
                    snip, settings(voice, macros))
            }
            val row = blendSteps.map { renders.getValue(voice to it) }
            check(row.map { it.frameCount }.distinct().size == 1) { "blend changed ${voice.name} render duration" }
            check(row.first().samples.indices.any { abs(row.first().samples[it] - row.last().samples[it]) > 1e-5f }) {
                "${voice.name} endpoints are indistinguishable"
            }
        }

        for (blend in listOf(0f, .5f, 1f)) {
            val voices = OnsetVoice.entries.associateWith { renders.getValue(it to blend) }
            val beat = renderBeat(voices)
            clips += writeClip(root, "beat-${blendId(blend)}", "Two-bar beat · ${blendTitle(blend)}", "KIT",
                blend, "beat", "The same kick and closed-hat pattern at 104 BPM. Both voices use this BLEND; timing and hit gains are fixed.",
                beat, linkedMapOf("voices" to OnsetVoice.entries.associate { voice ->
                    voice.name to settings(voice, Onset.defaults(voice) + ("BLEND" to blend))
                }, "bpm" to BPM, "bars" to BARS, "events" to beatEvents()))
        }

        check(clips.size == 13 && clips.map { it.getValue("id") }.distinct().size == 13)
        val sourceIdentity = rendererIdentity()
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "title" to "ONSET · first audition",
            "subtitle" to "Two drum voices, five blend positions, and one unchanged beat. Real dry SnipSnap renders.",
            "engine" to linkedMapOf(
                "id" to "ONSET", "version" to Onset.MODEL_VERSION,
                "voices" to OnsetVoice.entries.map { it.name },
                "controls" to listOf("TUNE", "DECAY", "BLEND", "SNAP", "BODY", "EDGE"),
                "settings" to OnsetVoice.entries.associate { it.name to settings(it, Onset.defaults(it)) },
                "sampleRate" to Dsp.RATE, "oversample" to Dsp.OVERSAMPLE,
                "identity" to sourceIdentity,
                "rawDefinition" to "Engine output before audition matching; any PCM export attenuation is recorded per clip.",
            ),
            "comparison" to linkedMapOf("blendSteps" to blendSteps, "bpm" to BPM, "bars" to BARS,
                "fixedBacking" to false, "beatVoices" to OnsetVoice.entries.map { it.name },
                "musicalDurationSeconds" to BARS * 4 * 60.0 / BPM,
                "tailDescription" to "The same full-hit ring-out buffer follows each two-bar pattern.",
                "events" to beatEvents(), "withinHitMotion" to false, "voiceCoupling" to false,
                "effects" to emptyList<String>()),
            "audition" to linkedMapOf("method" to "AuditionLevel", "targetLoudest200msRms" to .03,
                "peakGuard" to .99, "defaultVariant" to "matched",
                "description" to "A single gain per complete clip matches its loudest 200 ms window. Raw files preserve engine balance."),
            "download" to PACK_NAME,
            "clips" to clips,
        )
        val manifestText = Json.write(json(manifest))
        File(root, "manifest.json").writeText(manifestText + "\n")
        File(root, "settings.json").writeText(Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to manifest.getValue("engine"),
            "comparison" to manifest.getValue("comparison"),
            "clips" to clips.map { mapOf("id" to it.getValue("id"), "settings" to it.getValue("settings")) },
        ))) + "\n")
        File(root, "README.md").writeText(readme())
        copyPage(root, manifestText)
        bundle(root, clips)
        println("Wrote 10 dry hit comparisons, 3 identical-pattern beats, 26 WAVs, settings, manifest and $PACK_NAME under ${root.absolutePath}")
    }

    private fun writeClip(root: File, id: String, title: String, voice: String, blend: Float,
                          kind: String, description: String, source: Snip,
                          settings: Map<String, Any?>): Map<String, Any?> {
        validateAudio(source, id)
        val exportGain = minOf(1f, .99f / source.peak().coerceAtLeast(1e-9f))
        val raw = Snip(FloatArray(source.samples.size) { source.samples[it] * exportGain }, source.channels, source.sampleRate)
        val matched = AuditionLevel.level(source)
        val rawPath = "audio/$id-raw.wav"
        val matchedPath = "audio/$id-matched.wav"
        val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
        val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
        // Measure the delivered PCM, so the listening evidence describes the actual downloads.
        val rawPcm = WavReader.read(rawFile)
        val matchedPcm = WavReader.read(matchedFile)
        validateAudio(rawPcm, "$id raw PCM")
        validateAudio(matchedPcm, "$id matched PCM")
        check(rawPcm.frameCount == matchedPcm.frameCount)
        val matchedGain = minOf(.03f / Loudness.of(source).coerceAtLeast(1e-9f),
            .99f / source.peak().coerceAtLeast(1e-9f))
        return linkedMapOf(
            "id" to id, "title" to title, "voice" to voice, "blend" to blend,
            "kind" to kind, "description" to description,
            "raw" to rawPath, "matched" to matchedPath,
            "durationSeconds" to rawPcm.durationSeconds,
            "settings" to settings,
            "measures" to linkedMapOf("raw" to measures(rawPcm), "matched" to measures(matchedPcm)),
            "audition" to linkedMapOf("method" to "AuditionLevel", "rawExportGain" to exportGain,
                "matchedGain" to matchedGain, "sourcePeak" to source.peak(),
                "sourceLoudestWindowRms" to Loudness.of(source),
                "targetLoudest200msRms" to .03, "peakGuard" to .99),
            "sha256" to linkedMapOf("raw" to sha256(rawFile), "matched" to sha256(matchedFile)),
        )
    }

    private fun settings(voice: OnsetVoice, macros: Map<String, Float>): Map<String, Any?> {
        val s = Onset.settingsFor(voice, macros)
        return linkedMapOf(
            "voice" to s.voice.name, "macros" to s.macros, "modelVersion" to s.modelVersion,
            "electronicSource" to "THUMP", "physicalSource" to "SKIN",
            "electronicModel" to s.electronicModel, "physicalModel" to s.physicalModel,
            "rootHz" to s.rootHz, "transpositionSemitones" to s.transpositionSemitones,
            "electronicRootHz" to s.electronicRootHz, "physicalRootHz" to s.physicalRootHz,
            "t60Seconds" to s.t60Seconds, "electronicGain" to s.electronicGain,
            "physicalGain" to s.physicalGain, "outputGain" to s.outputGain,
            "dry" to true,
        )
    }

    private fun renderBeat(voices: Map<OnsetVoice, Snip>): Snip {
        val framesPerStep = Dsp.RATE * 60.0 / BPM / 4.0
        val musicalFrames = (BARS * STEPS_PER_BAR * framesPerStep).roundToInt()
        // The identical buffer includes complete tails rather than cutting the final offbeat.
        val tailFrames = voices.values.maxOf { it.frameCount }
        val out = FloatArray(musicalFrames + tailFrames)
        for (hit in beatHits) {
            val snip = voices.getValue(hit.voice)
            check(snip.channels == 1 && snip.sampleRate == Dsp.RATE)
            val start = (hit.step * framesPerStep).roundToInt()
            for (i in snip.samples.indices) out[start + i] += snip.samples[i] * hit.gain
        }
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }

    private fun beatEvents(): List<Map<String, Any>> = beatHits.map {
        linkedMapOf("voice" to it.voice.name, "step" to it.step, "gain" to it.gain)
    }

    private fun measures(snip: Snip): Map<String, Any?> {
        var power = 0.0
        var sum = 0.0
        for (sample in snip.samples) { power += sample.toDouble() * sample; sum += sample }
        val rms = sqrt(power / snip.samples.size)
        val peak = snip.peak().toDouble()
        return linkedMapOf("peak" to peak, "rms" to rms, "crestDb" to 20.0 * log10(peak / rms),
            "dc" to sum / snip.samples.size, "loudestWindowRms" to Loudness.of(snip),
            "durationSeconds" to snip.durationSeconds, "sampleRate" to snip.sampleRate,
            "channels" to snip.channels, "frames" to snip.frameCount, "finite" to true)
    }

    private fun validateAudio(snip: Snip, label: String) {
        check(snip.sampleRate == Dsp.RATE && snip.channels == 1) { "$label has an unexpected audio format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$label is empty or non-finite" }
        check(snip.peak() > 1e-5f) { "$label is silent" }
    }

    private fun copyPage(root: File, manifest: String) {
        for (name in listOf("index.html", "app.js", "styles.css")) {
            val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("onset-audition/$name")) {
                "missing onset-audition/$name resource"
            }
            val text = resource.bufferedReader().use { it.readText() }
            val contents = if (name == "index.html") {
                check("</body>" in text) { "audition template has no closing body" }
                val inline = "<script id=\"onset-manifest\" type=\"application/json\">${manifest.replace("<", "\\u003c")}</script>\n"
                text.replace("</body>", inline + "</body>")
            } else text
            File(root, name).writeText(contents)
        }
    }

    private fun bundle(root: File, clips: List<Map<String, Any?>>) {
        val names = (listOf("index.html", "app.js", "styles.css", "manifest.json", "settings.json", "README.md") +
            clips.flatMap { listOf(it.getValue("raw").toString(), it.getValue("matched").toString()) }).sorted()
        ZipOutputStream(File(root, PACK_NAME).outputStream().buffered()).use { zip ->
            for (name in names) {
                val entry = ZipEntry(name).apply { time = 0L }
                zip.putNextEntry(entry)
                File(root, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun rendererIdentity(): Map<String, Any?> {
        val paths = listOf("src/main/kotlin/com/snipsnap/synth/Onset.kt",
            "src/main/kotlin/com/snipsnap/synth/Thump.kt", "src/main/kotlin/com/snipsnap/synth/Skin.kt",
            "src/main/kotlin/com/snipsnap/synth/Dsp.kt", "src/main/kotlin/com/snipsnap/synth/Punch.kt",
            "src/test/kotlin/com/snipsnap/synth/OnsetAuditionGenerator.kt",
            "src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt")
        return linkedMapOf("name" to "ONSET first-audition model ${Onset.MODEL_VERSION}", "implementation" to "SnipSnap JVM renderer",
            "sources" to paths.associateWith { path -> File(path).takeIf { it.isFile }?.let(::sha256) })
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun voiceId(voice: OnsetVoice) = when (voice) { OnsetVoice.KICK -> "kick"; OnsetVoice.HAT_CLOSED -> "hat" }
    private fun voiceTitle(voice: OnsetVoice) = when (voice) { OnsetVoice.KICK -> "Kick"; OnsetVoice.HAT_CLOSED -> "Closed hat" }
    private fun blendId(blend: Float) = String.format(Locale.ROOT, "%03d", (blend * 100).roundToInt())
    private fun blendTitle(blend: Float) = when (blend) { 0f -> "Electronic"; .5f -> "Hybrid"; 1f -> "Physical"; else -> "${(blend * 100).roundToInt()}% physical" }

    private fun readme() = """
        ONSET first audition
        ====================

        Ten dry single-hit comparisons: kick and closed hat at BLEND 0, .25, .5, .75 and 1.
        Three dry two-bar examples at 104 BPM use exactly the same kick/hat events and gains.
        There is no fixed backing, snare, reverb, delay, within-hit blend motion or voice coupling.

        These WAVs are rendered by the actual SnipSnap JVM ONSET engine. The browser plays the
        exported audio. Raw means engine output before audition matching, with PCM attenuation
        only if necessary to prevent clipping; the manifest records that gain. Matched uses
        the repository's AuditionLevel: loudest 200 ms RMS .03, with a .99 peak guard.

        WAV format: mono 24-bit PCM, 44.1 kHz. Measurements describe the delivered PCM files.
        settings.json records the complete controls, source identities and beat events.
        manifest.json adds per-clip metadata, measurements and SHA-256 hashes.

        Open index.html from this folder. Its embedded manifest also supports offline listening.
        Reproduce the pack from the repository with: ./gradlew :synth:generateOnsetAudition
    """.trimIndent() + "\n"

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported ONSET manifest value: ${value.javaClass}")
    }
}

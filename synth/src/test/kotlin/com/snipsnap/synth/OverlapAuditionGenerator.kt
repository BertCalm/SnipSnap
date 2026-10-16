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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Twelve real JVM renders for the finite OVERLAP proof. No browser synthesis or rack FX. */
object OverlapAuditionGenerator {
    private data class Case(
        val id: String, val title: String, val description: String, val group: String,
        val topology: OverlapTopology, val changes: Map<String, Float> = emptyMap(), val midi: Int = 48,
    )

    private val neutral = mapOf("KNOT" to 1f, "PULSE" to 0f, "LOCK" to 0f,
        "GLOW" to 0f, "RING" to .8f, "HOLD" to 0f)
    private val breathing = mapOf("KNOT" to 1f, "PULSE" to .65f, "RING" to .8f, "HOLD" to 1f)

    private fun cases() = listOf(
        Case("routing-self", "Self", "Six independent lengths form a spread of resonant families. Shared segment proportions; pulse and glow are off.", "routing", OverlapTopology.SELF),
        Case("routing-trefoil", "Trefoil", "Two three-line circuits form different families: the reference root and a fifth. Pulse and glow are off.", "routing", OverlapTopology.TREFOIL),
        Case("routing-mirror", "Mirror", "Three reflected pairs form contrasting odd-harmonic families. One polarity inversion per pair preserves passive scattering. Pulse and glow are off.", "routing", OverlapTopology.MIRROR),
        Case("routing-torus", "Torus", "One long reflected circuit produces an odd-harmonic family. The root reference sets its scale; pulse and glow are off.", "routing", OverlapTopology.TORUS),
        Case("depth-zero", "Knot / zero", "Trefoil selected, with routing depth at zero. Every topology gives this same self-resonance at zero.", "depth", OverlapTopology.TREFOIL, mapOf("KNOT" to 0f)),
        Case("depth-half", "Knot / halfway", "Halfway scattering rotates the resonant energy instead of averaging it away. Listen for the retained ring; pitch and color can move.", "depth", OverlapTopology.TREFOIL, mapOf("KNOT" to .5f)),
        Case("breathing-free", "Breathing / independent", "Six independent phases feed and load their own resonators for six seconds. Listen for interleaved entrances after the soft opening.", "breathing", OverlapTopology.TREFOIL, breathing + ("LOCK" to 0f)),
        Case("breathing-locked", "Breathing / together", "Only LOCK changes: those same phases gather into a shared breathing cadence. Listen after the opening second; there is no separate master volume pulse.", "breathing", OverlapTopology.TREFOIL, breathing + ("LOCK" to 1f)),
        Case("glow-off", "Glow / off", "The clean resonator, with locked breathing and extended finite excitation.", "glow", OverlapTopology.MIRROR, breathing + ("LOCK" to 1f)),
        Case("glow-on", "Glow / on", "Only GLOW changes: bounded upper harmonics derived from the ringing output, outside the feedback network.", "glow", OverlapTopology.MIRROR, breathing + mapOf("LOCK" to 1f, "GLOW" to .7f)),
        Case("musical-pluck", "Glass thread", "A shorter trefoil pluck at a C4 reference: two resonant families with a little output-derived glow.", "musical", OverlapTopology.TREFOIL, mapOf("RING" to .35f, "GLOW" to .2f), midi = 60),
        Case("musical-tail", "Breathing circuit", "A finite torus tail at C3, with extended excitation, gathering pulse phases and moderate glow. It ends; it is not a held loop.", "musical", OverlapTopology.TORUS, breathing + mapOf("LOCK" to .75f, "GLOW" to .4f, "RING" to .8f)),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/overlap-audition").absoluteFile
        val assets = File(root, "assets").also { it.deleteRecursively(); it.mkdirs() }
        val clips = mutableListOf<Map<String, Any?>>()
        val reports = mutableListOf<Map<String, Any?>>()
        val sourceRevision = git("rev-parse", "HEAD")
        for (c in cases()) {
            val patch = OverlapPresets.all().firstOrNull { it.name == c.title }
                ?: OverlapPatch(c.title, c.topology, neutral + c.changes, c.midi, .8f)
            val macros = patch.macros
            val probe = Overlap.probe(patch.topology, macros, patch.midi, patch.velocity, model = patch.model)
            val raw = Snip(probe.samples.copyOf(), 1, probe.sampleRate)
            check(raw.sampleRate == 44_100 && raw.samples.all { it.isFinite() }) { "${c.id}: invalid raw audio" }
            check(raw.peak() in .000001f..<1f) { "${c.id}: raw signal is silent or clips (${raw.peak()})" }
            check(probe.diagnostics.maxLoopGain < 1.0) { "${c.id}: unbounded loop gain" }
            val native = patch.render()
            val recipe = PadRecipe(patch = patch)
            val text = recipe.toJsonText()
            val replay = PadRecipe.fromJsonText(text).render()
            check(native.samples.contentEquals(replay.samples)) { "${c.id}: recipe replay differs" }
            val matched = AuditionLevel.level(raw)
            val rawPath = "assets/audio/r2-${c.id}-raw.wav"
            val matchedPath = "assets/audio/r2-${c.id}-matched.wav"
            val recipePath = "assets/recipes/r2-${c.id}.json"
            WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            File(root, recipePath).also { it.parentFile.mkdirs() }.writeText(text)
            val pitch = Pitch.detect(raw, .10f, .25f)
            val d = probe.diagnostics
            val metrics = linkedMapOf<String, Any?>(
                "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
                "earlyRms" to d.earlyRms, "lateRms" to d.lateRms,
                "phaseOrderStart" to d.phaseOrderStart, "phaseOrderEnd" to d.phaseOrderEnd,
                "maxLoopGain" to d.maxLoopGain, "maxBufferAbs" to d.maxBufferAbs,
                "targetHz" to d.targetHz, "excitationSeconds" to d.excitationSeconds,
                "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
                "pitchCents" to pitch?.let { 1200.0 * ln(it.hz / d.targetHz) / ln(2.0) },
                "matchedPeak" to matched.peak(), "matchedLoudness" to Loudness.of(matched),
                "bodyRetentionDb" to if (macros.getValue("HOLD") < .5f) retentionDb(probe.raw, probe.sampleRate) else null,
                "breathingModulationDb" to if (macros.getValue("PULSE") > 0f && macros.getValue("HOLD") >= .5f) modulationDb(probe.raw, probe.sampleRate) else null,
            )
            val hashes = linkedMapOf("rawWavSha256" to sha(File(root, rawPath)),
                "matchedWavSha256" to sha(File(root, matchedPath)), "recipeSha256" to sha(File(root, recipePath)))
            clips += linkedMapOf(
                "id" to c.id, "title" to c.title, "description" to c.description, "group" to c.group,
                "topology" to c.topology.name, "midi" to c.midi, "velocity" to .8,
                "macros" to macros, "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "seconds" to raw.durationSeconds, "metrics" to metrics, "hashes" to hashes,
                "waveform" to waveform(matched.samples),
            )
            reports += linkedMapOf("id" to c.id, "metrics" to metrics, "delaySamples" to d.delaySamples,
                "phaseOrderHistory" to d.phaseOrderHistory, "recipeReplayExact" to true, "hashes" to hashes)
            println("OVERLAP ${clips.size}/12 ${c.id}: raw peak ${d.rawPeak}, root estimate ${pitch?.hz ?: "unresolved"} Hz")
        }
        val manifest = linkedMapOf<String, Any?>(
            "title" to "OVERLAP", "subtitle" to "SnipSnap · Second listen", "modelVersion" to 2,
            "bundle" to "assets/overlap-second-listen.zip", "baseline" to "r1/index.html",
            "renderedAt" to Instant.now().toString(), "sourceRevision" to sourceRevision,
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "sampleRate" to 44_100, "channels" to 1,
            "notes" to listOf(
                "Twelve finite dry renders from the actual Kotlin engine, with no effects rack.",
                "Raw files retain one common fixed engine gain and the regular end fade; matched files use SnipSnap's loudest-200-ms target of .03 with a .99 peak guard.",
                "Revision 2 retains different cycle families and uses passive rotations at intermediate depth. The reference root is present at full routing, while dominant pitch can follow another family; intermediate pitch can move.",
                "HOLD extends finite excitation; at one it supplies a six-second passage followed by decay. No seamless sustained loop, phone-picker integration, or captured-snip input is claimed.",
                "Listening acceptance remains with the owner.",
            ), "clips" to clips,
        )
        File(assets, "manifest.json").writeText(Json.write(json(manifest)))
        File(assets, "diagnostics.json").writeText(Json.write(json(mapOf("modelVersion" to 2, "clips" to reports))))
        val template = File(root.parentFile.parentFile, "prototype/overlap-audition")
        if (template.isDirectory) template.copyRecursively(root, overwrite = true)
        ZipOutputStream(File(assets, "overlap-second-listen.zip").outputStream()).use { zip ->
            assets.walkTopDown().filter { it.isFile && it.extension != "zip" }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("OVERLAP audition: ${root.path}")
    }

    private fun windowRms(samples: FloatArray, rate: Int, start: Double, end: Double): Double {
        val a = (start * rate).toInt().coerceIn(0, samples.size)
        val b = (end * rate).toInt().coerceIn(a, samples.size)
        return sqrt((a until b).sumOf { samples[it].toDouble() * samples[it] } / maxOf(1, b - a))
    }
    private fun retentionDb(samples: FloatArray, rate: Int): Double =
        20.0 * log10(windowRms(samples, rate, .4, 1.0).coerceAtLeast(1e-12) /
            windowRms(samples, rate, .03, .15).coerceAtLeast(1e-12))
    private fun modulationDb(samples: FloatArray, rate: Int): Double {
        val end = minOf(5.5, samples.size.toDouble() / rate - .05)
        if (end < 1.7) return 0.0
        val levels = generateSequence(1.5) { it + .025 }.takeWhile { it + .05 < end }
            .map { windowRms(samples, rate, it, it + .05) }.sorted().toList()
        return 20.0 * log10(levels[(levels.size * .9).toInt()].coerceAtLeast(1e-12) /
            levels[(levels.size * .1).toInt()].coerceAtLeast(1e-12))
    }

    private fun waveform(samples: FloatArray): List<Double> {
        val size = 100
        return (0 until size).map { b ->
            val start = b * samples.size / size
            val end = ((b + 1) * samples.size / size).coerceAtLeast(start + 1)
            (start until end).maxOf { abs(samples[it]).toDouble() }
        }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(vararg args: String): String = runCatching {
        ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start().let { p ->
            val out = p.inputStream.bufferedReader().readText().trim(); check(p.waitFor() == 0); out
        }
    }.getOrDefault("unavailable")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported manifest value $value")
    }
}

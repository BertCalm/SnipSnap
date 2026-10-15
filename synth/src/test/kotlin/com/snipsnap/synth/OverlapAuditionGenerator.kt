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

/** Twelve real JVM renders for the finite OVERLAP proof. No browser synthesis or rack FX. */
object OverlapAuditionGenerator {
    private data class Case(
        val id: String, val title: String, val description: String, val group: String,
        val topology: OverlapTopology, val changes: Map<String, Float> = emptyMap(), val midi: Int = 48,
    )

    private val neutral = mapOf("KNOT" to 1f, "PULSE" to 0f, "LOCK" to 0f,
        "GLOW" to 0f, "RING" to .65f, "HOLD" to 0f)
    private val breathing = mapOf("KNOT" to 1f, "PULSE" to .55f, "RING" to .85f, "HOLD" to .8f)

    private fun cases() = listOf(
        Case("routing-self", "Self", "Six self-resonating lines. The uncoupled reference; pulse and glow are off.", "routing", OverlapTopology.SELF),
        Case("routing-trefoil", "Trefoil", "Two independent three-line circuits, each calibrated to the requested root. Pulse and glow are off.", "routing", OverlapTopology.TREFOIL),
        Case("routing-mirror", "Mirror", "Three mirrored pairs exchange their delayed sound. Pulse and glow are off.", "routing", OverlapTopology.MIRROR),
        Case("routing-torus", "Torus", "One six-line circuit. This is a routing metaphor, with no claimed automatic 3:2 harmonic lock.", "routing", OverlapTopology.TORUS),
        Case("depth-zero", "Knot / zero", "Trefoil selected, with routing depth at zero. Every topology gives this same self-resonance at zero.", "depth", OverlapTopology.TREFOIL, mapOf("KNOT" to 0f)),
        Case("depth-half", "Knot / halfway", "Trefoil halfway between self-routing and the full circuit. Network color and pitch can shift between calibrated endpoints.", "depth", OverlapTopology.TREFOIL, mapOf("KNOT" to .5f)),
        Case("breathing-free", "Breathing / independent", "Six seeded slow phases drift at slightly different rates. Extended finite excitation lets their motion be heard.", "breathing", OverlapTopology.TREFOIL, breathing + ("LOCK" to 0f)),
        Case("breathing-locked", "Breathing / together", "Only LOCK changes: the slow phases entrain toward shared breathing. Their motion never changes the delay pitch.", "breathing", OverlapTopology.TREFOIL, breathing + ("LOCK" to 1f)),
        Case("glow-off", "Glow / off", "The clean resonator, with locked breathing and extended finite excitation.", "glow", OverlapTopology.MIRROR, breathing + ("LOCK" to 1f)),
        Case("glow-on", "Glow / on", "Only GLOW changes: bounded upper harmonics derived from the ringing output, outside the feedback network.", "glow", OverlapTopology.MIRROR, breathing + mapOf("LOCK" to 1f, "GLOW" to .7f)),
        Case("musical-pluck", "Glass thread", "A shorter trefoil pluck at C4: restrained ring, with a little output-derived glow.", "musical", OverlapTopology.TREFOIL, mapOf("RING" to .25f, "GLOW" to .2f), midi = 60),
        Case("musical-tail", "Breathing circuit", "A finite torus tail at C3, with extended excitation, gathering pulse phases and moderate glow. It ends; it is not a held loop.", "musical", OverlapTopology.TORUS, breathing + mapOf("LOCK" to .75f, "GLOW" to .4f, "RING" to .9f)),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/overlap-audition").absoluteFile
        val assets = File(root, "assets").also { it.mkdirs() }
        val clips = mutableListOf<Map<String, Any?>>()
        val reports = mutableListOf<Map<String, Any?>>()
        val sourceRevision = git("rev-parse", "HEAD")
        for (c in cases()) {
            val patch = OverlapPresets.all().firstOrNull { it.name == c.title }
                ?: OverlapPatch(c.title, c.topology, neutral + c.changes, c.midi, .8f)
            val macros = patch.macros
            val probe = Overlap.probe(patch.topology, macros, patch.midi, patch.velocity)
            val raw = Snip(probe.raw.copyOf(), 1, probe.sampleRate)
            check(raw.sampleRate == 44_100 && raw.samples.all { it.isFinite() }) { "${c.id}: invalid raw audio" }
            check(raw.peak() in .000001f..<1f) { "${c.id}: raw signal is silent or clips (${raw.peak()})" }
            check(probe.diagnostics.maxLoopGain < 1.0) { "${c.id}: unbounded loop gain" }
            val native = patch.render()
            val recipe = PadRecipe(patch = patch)
            val text = recipe.toJsonText()
            val replay = PadRecipe.fromJsonText(text).render()
            check(native.samples.contentEquals(replay.samples)) { "${c.id}: recipe replay differs" }
            val matched = AuditionLevel.level(raw)
            val rawPath = "assets/audio/${c.id}-raw.wav"
            val matchedPath = "assets/audio/${c.id}-matched.wav"
            val recipePath = "assets/recipes/${c.id}.json"
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
            "title" to "OVERLAP", "subtitle" to "SnipSnap · First listen", "modelVersion" to 1,
            "renderedAt" to Instant.now().toString(), "sourceRevision" to sourceRevision,
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "sampleRate" to 44_100, "channels" to 1,
            "notes" to listOf(
                "Twelve finite dry renders from the actual Kotlin engine, with no effects rack.",
                "Raw files retain one common fixed engine gain; matched files use SnipSnap's loudest-200-ms target of .03 with a .99 peak guard.",
                "Topology endpoints are root-calibrated. Intermediate KNOT settings can change pitch as well as color; pitch estimates are observations, not acceptance claims.",
                "HOLD extends finite excitation in this first proof. No seamless sustained loop, phone-picker integration, or captured-snip input is claimed.",
                "Listening acceptance remains with the owner.",
            ), "clips" to clips,
        )
        File(assets, "manifest.json").writeText(Json.write(json(manifest)))
        File(assets, "diagnostics.json").writeText(Json.write(json(mapOf("modelVersion" to 1, "clips" to reports))))
        val template = File(root.parentFile.parentFile, "prototype/overlap-audition")
        if (template.isDirectory) template.copyRecursively(root, overwrite = true)
        ZipOutputStream(File(assets, "overlap-first-listen.zip").outputStream()).use { zip ->
            assets.walkTopDown().filter { it.isFile && it.extension != "zip" }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("OVERLAP audition: ${root.path}")
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

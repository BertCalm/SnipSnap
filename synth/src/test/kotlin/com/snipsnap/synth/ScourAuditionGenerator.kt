package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * SCOUR's R1 listening gate, written to gitignored testkit/scour-audition/.
 * Run `./gradlew :synth:generateScourAudition`, then open index.html directly.
 *
 * The gate is the SHOT × HARDEN section: can the hardening be heard? RING asks the owner's open
 * question (should a hardened plate ring shorter, as R1 ships, or longer) with the same render in
 * both directions; the longer one exists only here, through [Scour.RingLaw.OPENS]. Every clip has the
 * engine's unlevelled output and an [AuditionLevel] copy from the same render. No rack effects.
 * HOLD's top step is reserved for R2, so there is no loop section yet.
 */
object ScourAuditionGenerator {

    private data class Case(
        val section: String,
        val group: String,
        val id: String,
        val name: String,
        val description: String,
        val voice: ScourVoice,
        val macros: Map<String, Float>,
        val velocity: Float = 1f,
        val ring: Scour.RingLaw = Scour.RingLaw.TIGHTENS,
    )

    private val sections = linkedMapOf(
        "GATE" to ("SHOT × HARDEN (the gate)" to
            "Does the plate audibly harden? Each pair is the same blast with HARDEN 0 and HARDEN 1. The SHOT .7 pair is the corner you heard from the Python prototype, now in Kotlin with the R1 voicing. Listen for the upper ring rising and tightening after the blast."),
        "RING" to ("Which way should hardening move the ring?" to
            "Your open question. The same blast at SHOT .8 and HARDEN 1, once with the ring getting shorter as the plate hardens (what R1 ships) and once with it getting longer. HARDEN 0 is the reference. Pick the one that sounds more like metal being worked."),
        "VOICES" to ("Voices at three notes" to
            "PLATE (dense metal that keeps its hardening) and PANE (glass that gives it back) at C3, C4 and C5, every control at its default."),
        "SWEEPS" to ("Each control swept" to
            "RPM, SHOT, HARDEN, TRAVERSE and HOLD at 0, .5 and 1 (HOLD's top step is reserved, so its last step is .98), the others at their defaults; then velocity at .3, .65 and 1."),
        "EDGES" to ("Edges" to
            "Every control at its low end, at its high end, and the brightest and darkest corners. These are where an engine falls apart if it is going to."),
    )

    private fun fmt(v: Float) = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun tag(v: Float) = (v * 100).toInt().toString().padStart(3, '0')
    private fun title(voice: ScourVoice) = voice.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun cases(): List<Case> = buildList {
        for (voice in ScourVoice.entries) {
            val v = title(voice)
            for (shot in listOf(0f, .7f, 1f)) for (harden in listOf(0f, 1f)) add(Case("GATE", "$v", "${voice.name.lowercase()}_shot${tag(shot)}_harden${tag(harden)}",
                "$v · SHOT ${fmt(shot)} · HARDEN ${fmt(harden)}",
                if (shot == 0f) "Almost no shots: HARDEN should change little here." else "The blast at SHOT ${fmt(shot)}; compare HARDEN 0 with HARDEN 1.",
                voice, mapOf("SHOT" to shot, "HARDEN" to harden)))
            add(Case("RING", v, "${voice.name.lowercase()}_ring_reference", "$v · HARDEN 0 (reference)",
                "The same blast with no hardening.", voice, mapOf("SHOT" to .8f, "HARDEN" to 0f)))
            add(Case("RING", v, "${voice.name.lowercase()}_ring_shorter", "$v · hardened, ring shorter (R1)",
                "HARDEN 1: as coverage builds, every mode rings shorter by 1 + damp·HARDEN·coverage.", voice, mapOf("SHOT" to .8f, "HARDEN" to 1f)))
            add(Case("RING", v, "${voice.name.lowercase()}_ring_longer", "$v · hardened, ring longer (alternative)",
                "HARDEN 1 with the ring law flipped: every mode rings longer by the same factor.", voice, mapOf("SHOT" to .8f, "HARDEN" to 1f),
                ring = Scour.RingLaw.OPENS))
            for ((tune, note) in listOf(0f to "C3", .5f to "C4", 1f to "C5")) add(Case("VOICES", v, "${voice.name.lowercase()}_$note",
                "$v · $note", "Defaults at $note.", voice, mapOf("TUNE" to tune)))
            for (macro in listOf("RPM", "SHOT", "HARDEN", "TRAVERSE", "HOLD")) for (x in listOf(0f, .5f, 1f)) {
                val value = if (macro == "HOLD" && x == 1f) .98f else x
                add(Case("SWEEPS", "$v · $macro", "${voice.name.lowercase()}_${macro.lowercase()}_${tag(value)}",
                    "$v · $macro ${fmt(value)}", "$macro at ${fmt(value)}, the rest at defaults.", voice, mapOf(macro to value)))
            }
            for (vel in listOf(.3f, .65f, 1f)) add(Case("SWEEPS", "$v · velocity", "${voice.name.lowercase()}_velocity_${tag(vel)}",
                "$v · velocity ${fmt(vel)}", "Shot speed: softer hits land quieter and leave less coverage.", voice, emptyMap(), velocity = vel))
            val low = mapOf("RPM" to 0f, "SHOT" to 0f, "HARDEN" to 0f, "TRAVERSE" to 0f)
            val high = mapOf("RPM" to 1f, "SHOT" to 1f, "HARDEN" to 1f, "TRAVERSE" to 1f, "HOLD" to .98f)
            add(Case("EDGES", v, "${voice.name.lowercase()}_all_low", "$v · everything low", "RPM, SHOT, HARDEN and TRAVERSE at 0.", voice, low))
            add(Case("EDGES", v, "${voice.name.lowercase()}_all_high", "$v · everything high", "Every control at its top (HOLD .98).", voice, high))
            add(Case("EDGES", v, "${voice.name.lowercase()}_bright", "$v · bright corner", "C5 with everything high.", voice, high + ("TUNE" to 1f)))
            add(Case("EDGES", v, "${voice.name.lowercase()}_dark", "$v · dark corner", "C3 with everything low.", voice, low + ("TUNE" to 0f)))
        }
    }

    private fun levels(snip: Snip): Map<String, Double> {
        var peak = 0.0
        var sum = 0.0
        var energy = 0.0
        for (s in snip.samples) {
            val x = s.toDouble()
            peak = max(peak, abs(x)); sum += x; energy += x * x
        }
        return mapOf(
            "peak" to peak,
            "rms" to sqrt(energy / snip.samples.size.coerceAtLeast(1)),
            "dc" to sum / snip.samples.size.coerceAtLeast(1),
            "loudness" to Loudness.of(snip).toDouble(),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/scour-audition")
        require(root.mkdirs() || root.isDirectory) { "cannot create ${root.absolutePath}" }
        val grouped = sections.keys.associateWith { linkedMapOf<String, MutableList<Map<String, Any?>>>() }
        val started = System.nanoTime()
        var wavs = 0
        val all = cases()
        for ((n, c) in all.withIndex()) {
            val began = System.nanoTime()
            val played = Scour.play(c.voice, c.macros, c.velocity, record = true, ring = c.ring)
            val raw = Snip(Scour.finish(played.raw, normalize = false), channels = 1, sampleRate = Dsp.RATE)
            val millis = (System.nanoTime() - began) / 1e6
            require(raw.samples.all { it.isFinite() && abs(it) <= 1f }) { "${c.id}: raw audio is not finite or exceeds PCM range" }
            val matched = AuditionLevel.level(raw)
            val stem = "${c.section.lowercase()}/${c.id}"
            WavWriter.write(File(root, "${stem}_raw.wav"), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, "${stem}_matched.wav"), matched, WavWriter.BitDepth.PCM_24)
            wavs += 2
            val trace = played.trace
            val close = ((trace.closeSeconds * Scour.INTERNAL_RATE).toInt() / Scour.CTRL).coerceAtMost(trace.smoothed.lastIndex)
            val modes = requireNotNull(played.modeHz)
            val settled = Scour.settled(c.voice, c.macros)
            grouped.getValue(c.section).getOrPut(c.group) { mutableListOf() } += linkedMapOf(
                "id" to c.id,
                "name" to c.name,
                "description" to c.description,
                "voice" to c.voice.name,
                "macros" to settled,
                "midi" to Scour.midiFor(c.voice, settled.getValue("TUNE")),
                "velocity" to c.velocity,
                "ring" to c.ring.name,
                "raw" to "${stem}_raw.wav",
                "matched" to "${stem}_matched.wav",
                "metrics" to linkedMapOf(
                    "durationSeconds" to raw.samples.size.toDouble() / Dsp.RATE,
                    "raw" to levels(raw),
                    "matched" to levels(matched),
                    "shots" to trace.shotFrames.size,
                    "coverageAtClose" to trace.coverage[close],
                    "topModeRisePercent" to 100.0 * (modes.last()[close] / modes.last()[0] - 1),
                    "renderMillis" to millis,
                ),
            )
            if ((n + 1) % 10 == 0 || n == all.lastIndex) println("SCOUR audition: ${n + 1}/${all.size} renders, $wavs WAVs")
        }
        val manifest = Json.write(value(linkedMapOf(
            "engine" to "SCOUR",
            "model" to Scour.MODEL_VERSION,
            "auditionRevision" to "R1",
            "gestures" to all.size,
            "wavFiles" to wavs,
            "sampleRate" to Dsp.RATE,
            "elapsedSeconds" to (System.nanoTime() - started) / 1e9,
            "rawMeaning" to "Band-limited, DC-cleaned engine audio before melodic loudness targeting; the fixed radiation trim only.",
            "matchedMeaning" to "The same render matched to AuditionLevel's shared loudest-200 ms target, with its peak guard.",
            "sections" to sections.map { (id, text) ->
                mapOf("id" to id, "display" to text.first, "body" to text.second,
                    "groups" to grouped.getValue(id).map { (label, clips) -> mapOf("label" to label, "clips" to clips) })
            },
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        val template = ScourAuditionGenerator::class.java.getResourceAsStream("/audition/scour-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("missing /audition/scour-audition.html")
        File(root, "index.html").writeText(template.replace("__SCOUR_MANIFEST__", manifest.replace("</", "<\\/")))
        println("SCOUR audition: ${all.size} gestures, $wavs WAVs in ${root.absolutePath}")
    }

    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> v.toDouble().let { require(it.isFinite()) { "non-finite audition value" }; JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map { value(it) })
        else -> error("cannot write ${v::class.simpleName} to the SCOUR manifest")
    }
}

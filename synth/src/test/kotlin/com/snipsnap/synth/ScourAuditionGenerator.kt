package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * SCOUR's fourth listen (R3, the kit), written to gitignored testkit/scour-audition/.
 * Run `./gradlew :synth:generateScourAudition`, then open index.html directly.
 *
 * The third listen said yes to the impact into a drop and to the short hits, so R3 makes SCOUR a kit:
 * this page leads with its sixteen pads in pad order (builds cut for 120 and 90 BPM, then the eight
 * presets), then the drops over a beat, each build alone, PLATE beside PANE, every control swept, and
 * the edges. Single gestures keep the engine's unlevelled output beside an
 * [AuditionLevel] copy of the same render; a phrase is a mix of the engine's own pad renders and THUMP's
 * drums, so it has only the matched copy. No rack effects.
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
    )

    /** One SCOUR pad hit inside a phrase: the frame its slam lands on, and the macros it plays. */
    private class Hit(val slamFrame: Int, val macros: Map<String, Float>)

    /**
     * A phrase at [bpm]: [bars] bars of THUMP's beat, the full beat from bar [drop] on (bars before it carry
     * only a kick on each bar's one and closed hats), and SCOUR's [hits].
     */
    private class Phrase(
        val id: String, val name: String, val description: String, val voice: ScourVoice,
        val bpm: Int, val bars: Int, val drop: Int, val hits: List<Hit>,
    )

    private val sections = linkedMapOf(
        "KIT" to ("The kit, pad by pad" to
            "SynthKits.scour() in pad order, as the MPC program lays it out. A01-A04 are PLATE builds cut for 120 BPM and A05-A08 PANE builds cut for 90 BPM, one beat to two bars: hit one that long before a downbeat and its slam lands on it. A09-A16 are the eight presets."),
        "DROPS" to ("SCOUR into a drop" to
            "The build runs up to the downbeat and the slam lands on it, where the full beat comes in. Before the drop the beat is only a kick and hats. One bar of build at 120 BPM, two bars at 90, and short hits on the bar at 120. Each is the engine's own pad render placed so the slam is on the beat; nothing is added."),
        "BUILDS" to ("Each build alone" to
            "The one-bar build at 120 BPM on its own, with HARDEN 0 (the plate does not climb) and HARDEN 1 (the most climb)."),
        "VOICES" to ("PLATE beside PANE" to
            "The same impact on both voices at C3, C4 and C5, every control at its default."),
        "SWEEPS" to ("Each control swept" to
            "BUILD, RPM, SHOT, HARDEN and HOLD at 0, .5 and 1, the others at their defaults; then velocity at .3, .65 and 1."),
        "EDGES" to ("Edges" to
            "Every control at its low end, at its high end, and the brightest and darkest corners."),
    )

    private fun fmt(v: Float) = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun tag(v: Float) = (v * 100).toInt().toString().padStart(3, '0')
    private fun title(voice: ScourVoice) = voice.name.lowercase().replaceFirstChar { it.uppercase() }

    /** Seconds of [bars] bars of 4/4 at [bpm]. */
    private fun barSeconds(bpm: Int, bars: Int) = bars * 4 * 60.0 / bpm

    private fun cases(): List<Case> = buildList {
        for ((i, pad) in SynthKits.scour().withIndex()) {
            val patch = PadRecipe.fromJsonValue(requireNotNull(requireNotNull(pad).recipe)).patch as ScourPatch
            val slot = "A%02d".format(i + 1)
            add(Case("KIT", if (i < 8) "Builds (A01-A08)" else "Presets (A09-A16)", slot.lowercase(),
                "$slot · ${patch.name}", "${title(patch.voice)} · ${patch.name}", patch.voice, patch.macros))
        }
        val oneBar = Scour.buildFor(barSeconds(120, 1))
        for (voice in ScourVoice.entries) for (harden in listOf(0f, 1f)) add(Case("BUILDS", title(voice),
            "${voice.name.lowercase()}_build_harden${tag(harden)}", "${title(voice)} · one bar, HARDEN ${fmt(harden)}",
            if (harden == 0f) "The build without the climb: the rain accelerates and swells, the plate stays put." else "The build with the most climb: the upper ring rises as the dents spread.",
            voice, mapOf("BUILD" to oneBar, "HARDEN" to harden, "TUNE" to 0f)))
        for ((tune, note) in listOf(0f to "C3", .5f to "C4", 1f to "C5")) for (voice in ScourVoice.entries) add(Case("VOICES", note,
            "${voice.name.lowercase()}_$note", "${title(voice)} · $note", "Defaults at $note.", voice, mapOf("TUNE" to tune)))
        for (voice in ScourVoice.entries) {
            val v = title(voice)
            val low = voice.name.lowercase()
            for (macro in listOf("BUILD", "RPM", "SHOT", "HARDEN", "HOLD")) for (x in listOf(0f, .5f, 1f)) {
                val value = x
                add(Case("SWEEPS", "$v · $macro", "${low}_${macro.lowercase()}_${tag(value)}",
                    "$v · $macro ${fmt(value)}", "$macro at ${fmt(value)}, the rest at defaults.", voice, mapOf(macro to value)))
            }
            for (vel in listOf(.3f, .65f, 1f)) add(Case("SWEEPS", "$v · velocity", "${low}_velocity_${tag(vel)}",
                "$v · velocity ${fmt(vel)}", "How hard the slam and the shots land: softer leaves less coverage and a gentler hit.", voice, emptyMap(), velocity = vel))
            val lowEnd = mapOf("BUILD" to 0f, "RPM" to 0f, "SHOT" to 0f, "HARDEN" to 0f)
            val highEnd = mapOf("BUILD" to 1f, "RPM" to 1f, "SHOT" to 1f, "HARDEN" to 1f, "HOLD" to 1f)
            add(Case("EDGES", v, "${low}_all_low", "$v · everything low", "BUILD, RPM, SHOT and HARDEN at 0: a bare hit.", voice, lowEnd))
            add(Case("EDGES", v, "${low}_all_high", "$v · everything high", "Every control at its top: an eight-second build and the longest ring.", voice, highEnd))
            add(Case("EDGES", v, "${low}_bright", "$v · bright corner", "C5 with everything high.", voice, highEnd + ("TUNE" to 1f)))
            add(Case("EDGES", v, "${low}_dark", "$v · dark corner", "C3 with everything low.", voice, lowEnd + ("TUNE" to 0f)))
        }
    }

    // ---- phrases ------------------------------------------------------------------------------------

    /** THUMP's beat under SCOUR, levelled on its own and set at these shares of SCOUR's level. */
    private const val DRUM_SHARE = 0.5f
    private const val HAT_SHARE = 0.35f

    private fun barFrames(bpm: Int) = (barSeconds(bpm, 1) * Dsp.RATE).roundToInt()

    private fun phrases(): List<Phrase> = ScourVoice.entries.flatMap { voice ->
        val v = title(voice)
        val low = voice.name.lowercase()
        listOf(
            Phrase("${low}_drop_120", "$v · one bar into the drop, 120 BPM",
                "A one-bar build (2.0 s) at C3 over a kick and hats, the slam on the downbeat of bar 3 where the full beat comes in.",
                voice, 120, 4, 3, listOf(Hit(2 * barFrames(120), mapOf("TUNE" to 0f, "BUILD" to Scour.buildFor(barSeconds(120, 1)))))),
            Phrase("${low}_drop_90", "$v · two bars into the drop, 90 BPM",
                "A two-bar build (5.3 s) at C3, the slam on the downbeat of bar 3.",
                voice, 90, 4, 3, listOf(Hit(2 * barFrames(90), mapOf("TUNE" to 0f, "BUILD" to Scour.buildFor(barSeconds(90, 2)))))),
            Phrase("${low}_hits_120", "$v · short hits on the bar, 120 BPM",
                "BUILD 0 (a quarter-second run-up) at C3, a hit on the downbeat of bars 2 and 4 over the full beat.",
                voice, 120, 4, 1, listOf(1, 3).map { bar -> Hit(bar * barFrames(120), mapOf("TUNE" to 0f, "BUILD" to 0f)) }),
        )
    }

    /** Adds [hit] into [into] from frame [at]. */
    private fun place(into: FloatArray, hit: FloatArray, at: Int, gain: Float) {
        for (i in hit.indices) if (at + i in into.indices) into[at + i] += gain * hit[i]
    }

    /** The gain [AuditionLevel.level] gives [samples], asked once so stems can be matched before they are mixed. */
    private fun levelGain(samples: FloatArray): Float {
        val levelled = AuditionLevel.level(Snip(samples, channels = 1, sampleRate = Dsp.RATE)).samples
        var at = 0
        for (i in samples.indices) if (abs(samples[i]) > abs(samples[at])) at = i
        return levelled[at] / samples[at]
    }

    private fun mix(p: Phrase): FloatArray {
        val bar = barFrames(p.bpm)
        val eighth = bar / 8
        val renders = p.hits.map { h ->
            val start = h.slamFrame - (Scour.buildSeconds(Scour.settled(p.voice, h.macros).getValue("BUILD")) * Dsp.RATE).roundToInt()
            // A frame or two of rounding may start a build before the phrase; place() drops what falls outside.
            check(start >= -Dsp.RATE / 100) { "${p.id}: a build starts before the phrase" }
            h to (start to Scour.render(p.voice, h.macros).samples)
        }
        val frames = max(p.bars * bar, renders.maxOf { (_, r) -> r.first + r.second.size })
        val scour = FloatArray(frames)
        for ((_, r) in renders) place(scour, r.second, r.first, 1f)
        val kick = Thump.render(ThumpVoice.KICK).samples
        val snare = Thump.render(ThumpVoice.SNARE).samples
        val hat = Thump.render(ThumpVoice.HAT_CLOSED).samples
        val beat = FloatArray(frames)
        val hats = FloatArray(frames)
        for (b in 0 until p.bars) {
            val full = b + 1 >= p.drop
            for (step in 0 until 8) {
                val at = b * bar + step * eighth
                if (step == 0 || (full && step == 4)) place(beat, kick, at, 1f)
                if (full && (step == 2 || step == 6)) place(beat, snare, at, 1f)
                place(hats, hat, at, 1f)
            }
        }
        // The drums are levelled once on their own and set under SCOUR's level, so the mix, not the beat, is matched.
        val s = levelGain(scour)
        val kb = levelGain(beat) * DRUM_SHARE / s
        val hb = levelGain(hats) * HAT_SHARE / s
        return FloatArray(frames) { scour[it] + kb * beat[it] + hb * hats[it] }
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
        for (p in phrases()) {
            val began = System.nanoTime()
            val mixed = Snip(mix(p), channels = 1, sampleRate = Dsp.RATE)
            val millis = (System.nanoTime() - began) / 1e6
            require(mixed.samples.all { it.isFinite() }) { "${p.id}: the mix is not finite" }
            val matched = AuditionLevel.level(mixed)
            val stem = "drops/${p.id}"
            WavWriter.write(File(root, "${stem}_matched.wav"), matched, WavWriter.BitDepth.PCM_24)
            wavs++
            grouped.getValue("DROPS").getOrPut(p.name.substringAfter(" · ")) { mutableListOf() } += linkedMapOf(
                "id" to p.id,
                "name" to p.name,
                "description" to p.description,
                "voice" to p.voice.name,
                "kind" to "PHRASE",
                "tempoBpm" to p.bpm,
                "hits" to p.hits.map { h -> mapOf("slamBeat" to h.slamFrame.toDouble() / (barFrames(p.bpm) / 4)) },
                "macros" to Scour.settled(p.voice, p.hits.first().macros),
                "drums" to true,
                "raw" to null,
                "matched" to "${stem}_matched.wav",
                "metrics" to linkedMapOf(
                    "durationSeconds" to mixed.samples.size.toDouble() / Dsp.RATE,
                    "matched" to levels(matched),
                    "renderMillis" to millis,
                ),
            )
        }
        val all = cases()
        for ((n, c) in all.withIndex()) {
            val began = System.nanoTime()
            val played = Scour.play(c.voice, c.macros, c.velocity, record = true)
            val raw = Snip(Scour.finish(played.raw, normalize = false), channels = 1, sampleRate = Dsp.RATE)
            val millis = (System.nanoTime() - began) / 1e6
            require(raw.samples.all { it.isFinite() && abs(it) <= 1f }) { "${c.id}: raw audio is not finite or exceeds PCM range" }
            val matched = AuditionLevel.level(raw)
            val stem = "${c.section.lowercase()}/${c.id}"
            WavWriter.write(File(root, "${stem}_raw.wav"), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, "${stem}_matched.wav"), matched, WavWriter.BitDepth.PCM_24)
            wavs += 2
            val trace = played.trace
            val slam = (trace.slamFrame / Scour.CTRL).coerceAtMost(trace.smoothed.lastIndex)
            val modes = requireNotNull(played.modeHz)
            val settled = Scour.settled(c.voice, c.macros)
            grouped.getValue(c.section).getOrPut(c.group) { mutableListOf() } += linkedMapOf(
                "id" to c.id,
                "name" to c.name,
                "description" to c.description,
                "voice" to c.voice.name,
                "kind" to "GESTURE",
                "macros" to settled,
                "midi" to Scour.midiFor(c.voice, settled.getValue("TUNE")),
                "velocity" to c.velocity,
                "raw" to "${stem}_raw.wav",
                "matched" to "${stem}_matched.wav",
                "metrics" to linkedMapOf(
                    "durationSeconds" to raw.samples.size.toDouble() / Dsp.RATE,
                    "buildSeconds" to trace.slamSeconds,
                    "raw" to levels(raw),
                    "matched" to levels(matched),
                    "shots" to trace.shotFrames.size,
                    "coverageAtClose" to trace.coverage[slam],
                    "topModeRisePercent" to 100.0 * (modes.last()[slam] / modes.last()[0] - 1),
                    "renderMillis" to millis,
                ),
            )
            if ((n + 1) % 10 == 0 || n == all.lastIndex) println("SCOUR audition: ${n + 1}/${all.size} renders, $wavs WAVs")
        }
        val manifest = Json.write(value(linkedMapOf(
            "engine" to "SCOUR",
            "model" to Scour.MODEL_VERSION,
            "auditionRevision" to "R2",
            "gestures" to all.size,
            "phrases" to phrases().size,
            "wavFiles" to wavs,
            "sampleRate" to Dsp.RATE,
            "elapsedSeconds" to (System.nanoTime() - started) / 1e9,
            "rawMeaning" to "Band-limited, DC-cleaned engine audio before melodic loudness targeting; the fixed radiation trim only.",
            "matchedMeaning" to "The same render matched to AuditionLevel's shared loudest-200 ms target, with its peak guard. A phrase is matched as a whole mix.",
            "sections" to sections.map { (id, text) ->
                mapOf("id" to id, "display" to text.first, "body" to text.second,
                    "groups" to grouped.getValue(id).map { (label, clips) -> mapOf("label" to label, "clips" to clips) })
            },
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        val template = ScourAuditionGenerator::class.java.getResourceAsStream("/audition/scour-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("missing /audition/scour-audition.html")
        File(root, "index.html").writeText(template.replace("__SCOUR_MANIFEST__", manifest.replace("</", "<\\/")))
        println("SCOUR audition: ${phrases().size} phrases and ${all.size} gestures, $wavs WAVs in ${root.absolutePath}")
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

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
 * SCOUR's second listen (R1.1), written to gitignored testkit/scour-audition/.
 * Run `./gradlew :synth:generateScourAudition`, then open index.html directly.
 *
 * The owner's first listen asked for three things, and the page leads with them: hear SCOUR in a
 * phrase ("we may need to see what a phrase would sound like"), hear PLATE and PANE as different
 * instruments ("sounds the same as plate though"), and set how far hardening lengthens the ring
 * ("probably would be adjustable"). Then the gate, the sweeps and the edges again, with RING where
 * TRAVERSE was. Single gestures keep the engine's unlevelled output beside an [AuditionLevel] copy
 * of the same render. A phrase is a mix of the engine's own pad renders (and THUMP's drums under the
 * impact), so it has only the matched copy. No rack effects. HOLD's top step is reserved for R2.
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

    /** One SCOUR pad hit inside a phrase: when, which note, how hard, with what macros. */
    private class Hit(val frame: Int, val semitones: Int, val velocity: Float, val macros: Map<String, Float>)

    private class Phrase(val id: String, val name: String, val description: String, val voice: ScourVoice, val hits: List<Hit>, val drums: Boolean, val bars: Int)

    private val sections = linkedMapOf(
        "PHRASES" to ("SCOUR in a phrase" to
            "Three ways a producer could play it from pads, at 90 BPM: a riff on a scale (every note the default sound, played the way a keygroup plays it), an impact that hardens over a plain THUMP beat, and a slow chord bed. Each is the engine's own renders summed at their trigger times; nothing is added."),
        "VOICES" to ("PLATE beside PANE" to
            "The same settings on both voices, note by note. PLATE is a round metal plate; PANE is now a square glass pane, heard at one point on its diagonal, with its own overtones (1, 1.69, 2.55, 4.35...) instead of PLATE's (1, 1.73, 2.33, 3.91...)."),
        "RING" to ("How far should hardening lengthen the ring?" to
            "RING, the new knob where TRAVERSE was, at five settings on the same blast (SHOT .8, HARDEN 1). .25 is R1's shorter ring, .5 leaves the ring alone, .75 is the longer ring you picked last time and the new default, and 1 lengthens it twice as far."),
        "GATE" to ("SHOT × HARDEN (the gate, again)" to
            "The first listen's gate at the new default ring. Each pair is the same blast with HARDEN 0 and HARDEN 1."),
        "SWEEPS" to ("Each control swept" to
            "RPM, SHOT, HARDEN, RING and HOLD at 0, .5 and 1 (HOLD's top step is reserved, so its last step is .98), the others at their defaults; then velocity at .3, .65 and 1."),
        "EDGES" to ("Edges" to
            "Every control at its low end, at its high end, and the brightest and darkest corners."),
    )

    private fun fmt(v: Float) = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun tag(v: Float) = (v * 100).toInt().toString().padStart(3, '0')
    private fun title(voice: ScourVoice) = voice.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun cases(): List<Case> = buildList {
        for ((tune, note) in listOf(0f to "C3", .5f to "C4", 1f to "C5")) for (voice in ScourVoice.entries) add(Case("VOICES", note,
            "${voice.name.lowercase()}_$note", "${title(voice)} · $note", "Defaults at $note.", voice, mapOf("TUNE" to tune)))
        for (voice in ScourVoice.entries) add(Case("VOICES", "C4 hardened", "${voice.name.lowercase()}_C4_hardened",
            "${title(voice)} · C4 hardened", "SHOT .8 and HARDEN 1 at C4: the blast at full hardening.", voice, mapOf("SHOT" to .8f, "HARDEN" to 1f)))
        for (voice in ScourVoice.entries) {
            val v = title(voice)
            val low = voice.name.lowercase()
            for (ring in listOf(0f, .25f, .5f, .75f, 1f)) add(Case("RING", v, "${low}_ring${tag(ring)}", "$v · RING ${fmt(ring)}",
                when (ring) {
                    0f -> "Hardening shortens the ring twice as far as R1 did."
                    .25f -> "R1's ring: hardening shortens every mode by 1 + damp·HARDEN·coverage (the shorter clip)."
                    .5f -> "Hardening leaves the ring alone; only the stretch of the upper modes is left."
                    .75f -> "The new default: hardening lengthens every mode by the same factor (the longer clip you picked)."
                    else -> "Hardening lengthens the ring twice as far as the default."
                }, voice, mapOf("SHOT" to .8f, "HARDEN" to 1f, "RING" to ring)))
            for (shot in listOf(0f, .7f, 1f)) for (harden in listOf(0f, 1f)) add(Case("GATE", v, "${low}_shot${tag(shot)}_harden${tag(harden)}",
                "$v · SHOT ${fmt(shot)} · HARDEN ${fmt(harden)}",
                if (shot == 0f) "Almost no shots: HARDEN should change little here." else "The blast at SHOT ${fmt(shot)}; compare HARDEN 0 with HARDEN 1.",
                voice, mapOf("SHOT" to shot, "HARDEN" to harden)))
            for (macro in listOf("RPM", "SHOT", "HARDEN", "RING", "HOLD")) for (x in listOf(0f, .5f, 1f)) {
                val value = if (macro == "HOLD" && x == 1f) .98f else x
                add(Case("SWEEPS", "$v · $macro", "${low}_${macro.lowercase()}_${tag(value)}",
                    "$v · $macro ${fmt(value)}", "$macro at ${fmt(value)}, the rest at defaults.", voice, mapOf(macro to value)))
            }
            for (vel in listOf(.3f, .65f, 1f)) add(Case("SWEEPS", "$v · velocity", "${low}_velocity_${tag(vel)}",
                "$v · velocity ${fmt(vel)}", "Shot speed: softer hits land quieter and leave less coverage.", voice, emptyMap(), velocity = vel))
            val lowEnd = mapOf("RPM" to 0f, "SHOT" to 0f, "HARDEN" to 0f, "RING" to 0f)
            val highEnd = mapOf("RPM" to 1f, "SHOT" to 1f, "HARDEN" to 1f, "RING" to 1f, "HOLD" to .98f)
            add(Case("EDGES", v, "${low}_all_low", "$v · everything low", "RPM, SHOT, HARDEN and RING at 0.", voice, lowEnd))
            add(Case("EDGES", v, "${low}_all_high", "$v · everything high", "Every control at its top (HOLD .98).", voice, highEnd))
            add(Case("EDGES", v, "${low}_bright", "$v · bright corner", "C5 with everything high.", voice, highEnd + ("TUNE" to 1f)))
            add(Case("EDGES", v, "${low}_dark", "$v · dark corner", "C3 with everything low.", voice, lowEnd + ("TUNE" to 0f)))
        }
    }

    // ---- phrases ------------------------------------------------------------------------------------

    private const val BPM = 90

    /** An eighth note at 90 BPM: 14,700 frames, a whole number, so every step lands on a frame. */
    private val EIGHTH = (60.0 / BPM / 2 * Dsp.RATE).roundToInt()
    private val BAR = 8 * EIGHTH

    /** THUMP's beat under the impact: kick on 1 and 3, snare on 2 and 4, a closed hat on every eighth. */
    private const val DRUM_SHARE = 0.5f
    private const val HAT_SHARE = 0.35f

    private fun phrases(): List<Phrase> = ScourVoice.entries.flatMap { voice ->
        val v = title(voice)
        val low = voice.name.lowercase()
        // C minor pentatonic up and back on quarter notes: C3 Eb3 F3 G3 Bb3 C4 Bb3 G3, accented on the beat.
        val riff = listOf(0, 3, 5, 7, 10, 12, 10, 7).mapIndexed { i, s -> Hit(i * 2 * EIGHTH, s, if (i % 2 == 0) 1f else .8f, emptyMap()) }
        val impact = listOf(0, 2).map { bar -> Hit(bar * BAR, 0, 1f, mapOf("SHOT" to .8f, "HARDEN" to 1f)) }
        val bed = mapOf("SHOT" to .3f, "RPM" to .6f, "HOLD" to .6f, "RING" to 1f)
        val chords = listOf(0, 3, 7).map { Hit(0, it, .9f, bed) } + listOf(8, 12, 15).map { Hit(2 * BAR, it, .9f, bed) }
        listOf(
            Phrase("${low}_riff", "$v · riff", "Eight pad hits on C minor pentatonic, quarter notes at 90 BPM, every note the default sound. Each note's blast overlaps the next note's roll, as it would from pads.", voice, riff, drums = false, bars = 3),
            Phrase("${low}_impact", "$v · impact over a beat", "One hardening hit at C3 (SHOT .8, HARDEN 1) on bars 1 and 3, over THUMP's kick, snare and closed hat at 90 BPM.", voice, impact, drums = true, bars = 4),
            Phrase("${low}_bed", "$v · chord bed", "C minor, then A-flat major, three pads struck together each time: sparse shot (SHOT .3), a long blast (HOLD .6), the longest ring (RING 1).", voice, chords, drums = false, bars = 4),
        )
    }

    /** Adds [hit] into [into] from frame [at], growing nothing: [into] is sized for every tail. */
    private fun place(into: FloatArray, hit: FloatArray, at: Int, gain: Float) {
        for (i in hit.indices) into[at + i] += gain * hit[i]
    }

    /** The gain [AuditionLevel.level] gives [samples], asked once so stems can be matched before they are mixed. */
    private fun levelGain(samples: FloatArray): Float {
        val levelled = AuditionLevel.level(Snip(samples, channels = 1, sampleRate = Dsp.RATE)).samples
        var at = 0
        for (i in samples.indices) if (abs(samples[i]) > abs(samples[at])) at = i
        return levelled[at] / samples[at]
    }

    private fun mix(p: Phrase): FloatArray {
        val notes = p.hits.map { h ->
            val tune = h.semitones / Scour.TUNE_SEMITONES.toFloat()
            check(Scour.midiFor(p.voice, tune) == Scour.ROOT_MIDI + h.semitones) { "${p.id}: note ${h.semitones} is off the TUNE grid" }
            h to Scour.render(p.voice, h.macros + ("TUNE" to tune), h.velocity).samples
        }
        val frames = max(p.bars * BAR, notes.maxOf { (h, s) -> h.frame + s.size })
        val scour = FloatArray(frames)
        for ((h, s) in notes) place(scour, s, h.frame, 1f)
        val out = scour.copyOf()
        if (p.drums) {
            // The drums are levelled once on their own and set under SCOUR's level, so the mix, not the beat, is matched.
            val kick = Thump.render(ThumpVoice.KICK).samples
            val snare = Thump.render(ThumpVoice.SNARE).samples
            val hat = Thump.render(ThumpVoice.HAT_CLOSED).samples
            val beat = FloatArray(frames)
            val hats = FloatArray(frames)
            for (bar in 0 until p.bars) for (step in 0 until 8) {
                val at = bar * BAR + step * EIGHTH
                if (step == 0 || step == 4) place(beat, kick, at, 1f)
                if (step == 2 || step == 6) place(beat, snare, at, 1f)
                place(hats, hat, at, 1f)
            }
            val s = levelGain(scour)
            val b = levelGain(beat) * DRUM_SHARE / s
            val h = levelGain(hats) * HAT_SHARE / s
            for (i in out.indices) out[i] += b * beat[i] + h * hats[i]
        }
        return out
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
            val stem = "phrases/${p.id}"
            WavWriter.write(File(root, "${stem}_matched.wav"), matched, WavWriter.BitDepth.PCM_24)
            wavs++
            grouped.getValue("PHRASES").getOrPut(p.name.substringAfter(" · ")) { mutableListOf() } += linkedMapOf(
                "id" to p.id,
                "name" to p.name,
                "description" to p.description,
                "voice" to p.voice.name,
                "kind" to "PHRASE",
                "tempoBpm" to BPM,
                "hits" to p.hits.map { h -> mapOf("beat" to h.frame.toDouble() / EIGHTH / 2, "midi" to Scour.ROOT_MIDI + h.semitones, "velocity" to h.velocity) },
                "macros" to Scour.settled(p.voice, p.hits.first().macros),
                "drums" to p.drums,
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
            val close = ((trace.closeSeconds * Scour.INTERNAL_RATE).toInt() / Scour.CTRL).coerceAtMost(trace.smoothed.lastIndex)
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
            "auditionRevision" to "R1.1",
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

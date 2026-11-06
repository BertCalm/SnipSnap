package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * FILL's listen (R1.1, SNARE and TOMS, with HUMANIZE), written to gitignored testkit/fill-audition/.
 * Run `./gradlew :synth:generateFillAudition`, then open index.html directly.
 *
 * The page leads with each fill in a phrase: two bars of THUMP's beat, the beat stops for the fill, and the
 * fill's landing falls on the downbeat where the beat comes back. Then each LENGTH alone, and every control
 * swept with the others at their defaults. Each clip is the engine's own render; a phrase is a mix of the
 * fill and THUMP's drums, matched as a whole by [AuditionLevel]. No rack effects.
 */
object FillAuditionGenerator {

    private class Case(val section: String, val group: String, val id: String, val name: String, val description: String,
                       val voice: FillVoice, val macros: Map<String, Float>, val velocity: Float = 1f)

    private class Phrase(val id: String, val name: String, val description: String, val voice: FillVoice, val bpm: Int, val beats: Int, val humanize: Float = 0f)

    private val sections = linkedMapOf(
        "PHRASES" to ("A fill in the beat" to
            "Two bars of THUMP's kick, snare and hats; the beat stops for the fill, and the fill's landing (a kick and an accent) falls on the downbeat where the beat comes back for a bar. Each fill is one pad hit, placed LENGTH before that downbeat, with TEMPO set to the phrase's tempo."),
        "LENGTHS" to ("Each length" to
            "One beat, two beats, one bar and two bars at 120 BPM, every other control at its default."),
        "SWEEPS" to ("Each control swept" to
            "TUNE, DENSITY, RISE, LAND and HUMANIZE at 0, .5 and 1, one bar at 120 BPM, the others at their defaults; then the pad hit at velocity .3, .65 and 1."),
    )

    private fun fmt(v: Float) = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun tag(v: Float) = (v * 100).roundToInt().toString().padStart(3, '0')
    private fun title(voice: FillVoice) = voice.name.lowercase().replaceFirstChar { it.uppercase() }
    private fun beatsName(beats: Int) = when (beats) { 1 -> "one beat"; 2 -> "two beats"; 4 -> "one bar"; else -> "two bars" }

    private fun cases(): List<Case> = buildList {
        for (voice in FillVoice.entries) {
            val v = title(voice)
            val low = voice.name.lowercase()
            for (beats in Fill.LENGTH_BEATS) add(Case("LENGTHS", v, "${low}_len_$beats", "$v · ${beatsName(beats)}",
                "LENGTH ${beatsName(beats)} at 120 BPM; the landing is $beats beat${if (beats > 1) "s" else ""} in.",
                voice, Fill.defaults(voice) + ("LENGTH" to Fill.lengthFor(beats))))
            for (name in listOf("TUNE", "DENSITY", "RISE", "LAND", "HUMANIZE")) for (x in listOf(0f, .5f, 1f)) add(Case("SWEEPS", "$v $name",
                "${low}_${name.lowercase()}_${tag(x)}", "$v · $name ${fmt(x)}", "$name at ${fmt(x)}, the others at their defaults.",
                voice, Fill.defaults(voice) + (name to x)))
            for (vel in listOf(.3f, .65f, 1f)) add(Case("SWEEPS", "$v velocity", "${low}_vel_${tag(vel)}", "$v · velocity ${fmt(vel)}",
                "The default fill hit at velocity ${fmt(vel)}.", voice, Fill.defaults(voice), vel))
        }
    }

    private fun phrases(): List<Phrase> = FillVoice.entries.flatMap { voice ->
        val v = title(voice)
        val low = voice.name.lowercase()
        listOf(
            Phrase("${low}_bar_120", "$v · a bar's fill, 120 BPM", "A one-bar fill in place of bar 3, landing on bar 4.", voice, 120, 4),
            Phrase("${low}_two_90", "$v · two beats' fill, 90 BPM", "A two-beat fill over the second half of bar 3, landing on bar 4.", voice, 90, 2),
            Phrase("${low}_bar_120_human", "$v · a bar's fill, 120 BPM, HUMANIZE .6",
                "The same one-bar fill with HUMANIZE at .6: the run drifts and leans on the beats, and the landing stays on bar 4.", voice, 120, 4, .6f),
        )
    }

    private fun barFrames(bpm: Int) = (4 * 60.0 / bpm * Dsp.RATE).roundToInt()

    private fun place(into: FloatArray, hit: FloatArray, at: Int, gain: Float) {
        for (i in hit.indices) if (at + i in into.indices) into[at + i] += gain * hit[i]
    }

    /** The gain [AuditionLevel.level] gives [samples], so stems are matched before they are mixed. */
    private fun levelGain(samples: FloatArray): Float {
        val levelled = AuditionLevel.level(Snip(samples, channels = 1, sampleRate = Dsp.RATE)).samples
        var at = 0
        for (i in samples.indices) if (abs(samples[i]) > abs(samples[at])) at = i
        return levelled[at] / samples[at]
    }

    /** Four bars: the beat in bars 1 and 2, the fill ending on bar 4's downbeat (the beat stops under it), the beat in bar 4. */
    private fun mix(p: Phrase): FloatArray {
        val bar = barFrames(p.bpm)
        val sixteenth = bar / 16.0
        val landing = 3 * bar
        val fill = Fill.render(p.voice, Fill.defaults(p.voice) + ("TEMPO" to Fill.tempoFor(p.bpm)) + ("LENGTH" to Fill.lengthFor(p.beats)) + ("HUMANIZE" to p.humanize)).samples
        val start = landing - (p.beats * 60.0 / p.bpm * Dsp.RATE).roundToInt()
        val frames = max(4 * bar + Dsp.RATE, start + fill.size)
        val fillStem = FloatArray(frames).also { place(it, fill, start, 1f) }
        val kick = Thump.render(ThumpVoice.KICK).samples
        val snare = Thump.render(ThumpVoice.SNARE).samples
        val hat = Thump.render(ThumpVoice.HAT_CLOSED).samples
        val beat = FloatArray(frames)
        for (b in 0 until 4) for (step in 0 until 16 step 2) {
            val at = b * bar + (step * sixteenth).roundToInt()
            if (at >= start && at < landing) continue
            if (step == 0 || step == 10) place(beat, kick, at, 1f)
            if (step == 4 || step == 12) place(beat, snare, at, 1f)
            place(beat, hat, at, .5f)
        }
        val g = levelGain(beat) * BEAT_SHARE / levelGain(fillStem)
        return FloatArray(frames) { fillStem[it] + g * beat[it] }
    }

    private const val BEAT_SHARE = .8f

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/fill-audition")
        require(root.mkdirs() || root.isDirectory) { "cannot create ${root.absolutePath}" }
        val grouped = sections.keys.associateWith { linkedMapOf<String, MutableList<Map<String, Any?>>>() }
        var wavs = 0
        for (p in phrases()) {
            val matched = AuditionLevel.level(Snip(mix(p), channels = 1, sampleRate = Dsp.RATE))
            require(matched.samples.all { it.isFinite() }) { "${p.id}: the mix is not finite" }
            val stem = "phrases/${p.id}"
            WavWriter.write(File(root, "$stem.wav"), matched, WavWriter.BitDepth.PCM_24)
            wavs++
            grouped.getValue("PHRASES").getOrPut(title(p.voice)) { mutableListOf() } += linkedMapOf(
                "id" to p.id, "name" to p.name, "description" to p.description, "voice" to p.voice.name,
                "tempoBpm" to p.bpm, "beats" to p.beats, "file" to "$stem.wav",
                "durationSeconds" to matched.samples.size.toDouble() / Dsp.RATE,
            )
        }
        val all = cases()
        for (c in all) {
            val raw = Fill.render(c.voice, c.macros, c.velocity)
            require(raw.samples.all { it.isFinite() && abs(it) <= 1f }) { "${c.id}: audio is not finite or exceeds PCM range" }
            val stem = "${c.section.lowercase()}/${c.id}"
            WavWriter.write(File(root, "$stem.wav"), raw, WavWriter.BitDepth.PCM_24)
            wavs++
            val plan = Fill.schedule(c.voice, c.macros, c.velocity)
            grouped.getValue(c.section).getOrPut(c.group) { mutableListOf() } += linkedMapOf(
                "id" to c.id, "name" to c.name, "description" to c.description, "voice" to c.voice.name,
                "macros" to Fill.settled(c.voice, c.macros), "velocity" to c.velocity, "file" to "$stem.wav",
                "tempoBpm" to plan.bpm, "hits" to plan.hits.size, "landingSeconds" to plan.lengthSeconds,
                "durationSeconds" to raw.samples.size.toDouble() / Dsp.RATE,
            )
        }
        val manifest = Json.write(value(linkedMapOf(
            "engine" to "FILL", "model" to Fill.MODEL_VERSION, "auditionRevision" to "R1.1",
            "phrases" to phrases().size, "clips" to all.size, "wavFiles" to wavs, "sampleRate" to Dsp.RATE,
            "sections" to sections.map { (id, text) ->
                mapOf("id" to id, "display" to text.first, "body" to text.second,
                    "groups" to grouped.getValue(id).map { (label, clips) -> mapOf("label" to label, "clips" to clips) })
            },
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        val template = FillAuditionGenerator::class.java.getResourceAsStream("/audition/fill-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("missing /audition/fill-audition.html")
        File(root, "index.html").writeText(template.replace("__FILL_MANIFEST__", manifest.replace("</", "<\\/")))
        println("FILL audition: ${phrases().size} phrases and ${all.size} clips, $wavs WAVs in ${root.absolutePath}")
    }

    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> v.toDouble().let { require(it.isFinite()) { "non-finite audition value" }; JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map { value(it) })
        else -> error("cannot write ${v::class.simpleName} to the FILL manifest")
    }
}

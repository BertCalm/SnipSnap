package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.ln

/**
 * CONSORT's first Kotlin audition: the real engine's renders, laid out as the owner heard the Python
 * prototype, so the two can be compared block for block. Writes section folders of WAVs, each clip's
 * recipe, `metrics.csv`, `manifest.json` and `index.html` under the folder given (testkit/consort-audition/).
 * Nothing here has been heard; the page says so.
 */
object ConsortAuditionGenerator {

    private class Clip(val section: String, val group: String, val label: String, val file: String, val patch: ConsortPatch)

    private val sections = linkedMapOf(
        "sax_fifths" to (ConsortVoice.SAX to listOf(ConsortChair(ConsortVoice.SAX, ConsortInterval.FIFTH), ConsortChair(ConsortVoice.SAX, ConsortInterval.OCT_DOWN))),
        "reed_trio_min7" to (ConsortVoice.OBOE to listOf(ConsortChair(ConsortVoice.CLARINET, ConsortInterval.FIFTH), ConsortChair(ConsortVoice.BASSOON, ConsortInterval.MIN7))),
        "flute_octaves" to (ConsortVoice.FLUTE to listOf(ConsortChair(ConsortVoice.FLUTE, ConsortInterval.OCT_UP), ConsortChair(ConsortVoice.CLARINET, ConsortInterval.OCT_DOWN))),
        "duduk_drone" to (ConsortVoice.DUDUK to listOf(ConsortChair(ConsortVoice.DUDUK, ConsortInterval.OCT_DOWN), ConsortChair(ConsortVoice.DUDUK, ConsortInterval.FIFTH))),
        "full_quartet" to (ConsortVoice.CLARINET to listOf(ConsortChair(ConsortVoice.FLUTE, ConsortInterval.OCT_UP), ConsortChair(ConsortVoice.OBOE, ConsortInterval.FIFTH), ConsortChair(ConsortVoice.BASSOON, ConsortInterval.OCT_DOWN))),
        "sax_maj7_stack" to (ConsortVoice.SAX to listOf(ConsortChair(ConsortVoice.CLARINET, ConsortInterval.MAJ7), ConsortChair(ConsortVoice.BASSOON, ConsortInterval.OCT_DOWN), ConsortChair(ConsortVoice.FLUTE, ConsortInterval.FIFTH))),
    )

    private fun describe(lead: ConsortVoice, chairs: List<ConsortChair>): String =
        (listOf("${lead.name.lowercase()} lead") + chairs.map { "${it.voice.name.lowercase()} ${it.interval.name.lowercase().replace('_', ' ')}" }).joinToString(", ")

    private fun clips(): List<Clip> {
        val out = mutableListOf<Clip>()
        fun add(section: String, group: String, label: String, file: String, voice: ConsortVoice, macros: Map<String, Float> = emptyMap(),
                chairs: List<ConsortChair> = emptyList(), velocity: Float = Consort.DEFAULT_VELOCITY, take: Int = 0) {
            out += Clip(section, group, label, "$section/$file.wav", ConsortPatch(file, voice, macros, chairs, velocity, take))
        }
        for (voice in ConsortVoice.entries) {
            val name = voice.name.lowercase()
            for (tune in listOf(0f, 0.5f, 1f)) add("1_voices", name, "midi ${Consort.midiFor(voice, tune)}", "${name}_midi${Consort.midiFor(voice, tune)}", voice, mapOf("TUNE" to tune))
            for ((label, v) in listOf("soft" to 0.3f, "medium" to 0.7f, "hard" to 1f)) add("2_velocity", name, label, "${name}_$label", voice, velocity = v)
        }
        for (voice in listOf(ConsortVoice.FLUTE, ConsortVoice.SAX, ConsortVoice.DUDUK)) for (g in ConsortGesture.entries)
            add("3_gestures", voice.name.lowercase(), g.name.lowercase(), "${voice.name.lowercase()}_${g.name.lowercase()}", voice,
                mapOf("GESTURE" to Consort.gestureValue(g), "MOTION" to 0.7f, "HOLD" to 0.75f))
        for ((id, s) in sections) {
            val (lead, chairs) = s
            val group = describe(lead, chairs)
            add("4_sections", group, "plain", id, lead, mapOf("HOLD" to 0.75f), chairs)
            add("4_sections", group, "lead trills", "${id}_lead_trill", lead, mapOf("HOLD" to 0.75f, "GESTURE" to Consort.gestureValue(ConsortGesture.TRILL)), chairs)
            add("4_sections", group, "hard hit, fall", "${id}_hard_fall", lead, mapOf("HOLD" to 0.6f, "GESTURE" to Consort.gestureValue(ConsortGesture.FALL)), chairs, velocity = 1f)
        }
        for ((label, room) in listOf("dry" to 0f, "default" to 0.4f, "big" to 1f)) {
            add("5_room", "oboe alone", label, "oboe_solo_$label", ConsortVoice.OBOE, mapOf("HOLD" to 0.6f, "ROOM" to room))
            for (id in listOf("sax_fifths", "full_quartet")) { val (lead, chairs) = sections.getValue(id); add("5_room", describe(lead, chairs), label, "${id}_$label", lead, mapOf("HOLD" to 0.6f, "ROOM" to room), chairs) }
        }
        for ((label, loose) in listOf("stiff" to 0f, "default" to 0.5f, "loose" to 1f)) {
            add("6_human", "duduk alone", label, "duduk_solo_$label", ConsortVoice.DUDUK, mapOf("HOLD" to 0.75f, "LOOSE" to loose))
            val (lead, chairs) = sections.getValue("reed_trio_min7")
            add("6_human", describe(lead, chairs), label, "reed_trio_$label", lead, mapOf("HOLD" to 0.75f, "LOOSE" to loose), chairs)
        }
        val takes = listOf<Pair<String, Pair<ConsortVoice, List<ConsortChair>>>>("sax_solo" to (ConsortVoice.SAX to emptyList()), "oboe_solo" to (ConsortVoice.OBOE to emptyList()),
            "duduk_solo" to (ConsortVoice.DUDUK to emptyList()), "reed_trio_min7" to sections.getValue("reed_trio_min7"))
        for ((id, s) in takes) for (k in 0..3)
            add("7_takes", describe(s.first, s.second), "take ${k + 1}: ${Consort.TAKES[k].name}", "${id}_take${k + 1}_${Consort.TAKES[k].name}", s.first, mapOf("HOLD" to 0.7f), s.second, take = k)
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/consort-audition")
        root.mkdirs()
        val csv = StringBuilder("file,lead,chairs,velocity,take,seconds,peak,filed,classified,pitchHz,pitchCentsFromLead,confidence,centroidHz,renderMs\n")
        val manifest = mutableListOf<String>()
        val html = StringBuilder("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>CONSORT Kotlin audition</title>" +
            "<style>body{font:15px system-ui;max-width:760px;margin:24px auto;padding:0 16px;background:#131816;color:#e8eeea}h2{margin-top:32px}h3{font-size:12px;letter-spacing:.07em;text-transform:uppercase;color:#6fc9b0}" +
            ".r{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:6px 0}.r span{flex:0 0 150px}audio{flex:1 1 220px;height:36px}p{color:#98a69f}</style>" +
            "<h1>CONSORT Kotlin audition</h1><p>The real engine's renders. The owner approved the Python prototype's sound; nobody has heard these.</p>")
        var section = ""
        var group = ""
        // one warm-up so the first clip's render time is the engine's, not the JVM's
        Consort.render(ConsortVoice.OBOE)
        for (clip in clips()) {
            File(root, clip.file).parentFile.mkdirs()
            val t0 = System.nanoTime()
            val snip: Snip = clip.patch.render()
            val ms = (System.nanoTime() - t0) / 1e6
            WavWriter.write(File(root, clip.file), snip)
            File(root, clip.file.removeSuffix(".wav") + ".json").writeText(clip.patch.toJsonText())
            var peak = 0f
            for (v in snip.samples) peak = maxOf(peak, abs(v))
            val c = Classifier.classify(snip)
            val pitch = Pitch.detect(snip, fromSec = 0.6f)
            val want = Consort.frequencyFor(clip.patch.voice, clip.patch.macros["TUNE"] ?: 0.5f)
            val cents = pitch?.let { 1200.0 * ln(it.hz / want.toDouble()) / ln(2.0) }
            csv.append("${clip.file},${clip.patch.voice},${clip.patch.chairs.joinToString(" ") { "${it.voice}:${it.interval}" }},${clip.patch.velocity},${clip.patch.take},")
            csv.append("%.3f,%.3f,${Consort.drumClassFor(clip.patch.voice, clip.patch.macros)},${c.drumClass},%.2f,%.1f,%.2f,%.0f,%.0f\n".format(
                snip.frameCount.toFloat() / snip.sampleRate, peak, pitch?.hz ?: 0f, cents ?: 0.0, pitch?.confidence ?: 0f, c.features.centroidHz, ms))
            manifest += "{\"file\":\"${clip.file}\",\"section\":\"${clip.section}\",\"group\":\"${clip.group}\",\"label\":\"${clip.label}\"}"
            if (clip.section != section) { section = clip.section; group = ""; html.append("<h2>${section.substring(2).replaceFirstChar { it.uppercase() }}</h2>") }
            if (clip.group != group) { group = clip.group; html.append("<h3>$group</h3>") }
            html.append("<div class=r><span>${clip.label}</span><audio controls preload=none src='${clip.file}'></audio></div>")
        }
        File(root, "metrics.csv").writeText(csv.toString())
        File(root, "manifest.json").writeText("[${manifest.joinToString(",\n")}]")
        File(root, "index.html").writeText(html.toString())
        println("wrote ${manifest.size} clips to $root")
    }
}

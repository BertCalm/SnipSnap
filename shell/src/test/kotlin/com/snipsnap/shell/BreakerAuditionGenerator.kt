package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.Breaker
import com.snipsnap.synth.BreakerKits
import com.snipsnap.synth.BreakerPatch
import com.snipsnap.synth.BreakerPresets
import com.snipsnap.synth.BreakerVoice
import com.snipsnap.synth.Restrike
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10

/**
 * BREAKER's final audition (docs/BREAKER.md, "The audition"): the whole engine on one page, for the owner's
 * closing listen. Run `./gradlew :shell:generateBreakerAudition` (output `testkit/breaker-audition`, or
 * `-PbreakerAuditionOutput=<dir>`). It writes section folders of WAVs, `manifest.json`, `index.html`
 * (`/audition/breaker-audition.html`), `recipes/`, `metrics.csv` and `diagnostics.json`.
 *
 * Every clip is levelled to the house audition loudness ([AuditionLevel]), and every strike inside a strip
 * is levelled first, so only the sound differs:
 * - **VOICES:** each voice at its defaults, a hard hit, on the current renderer model.
 * - **TOUCH:** each voice soft (.3), medium (.6) and hard, three strikes each, dealt as a kit pad deals them:
 *   each softness's own strike, then two re-strikes (`Restrike`).
 * - **CONTROLS:** each control swept 0, .25, .5, .75, 1 on the kick and the snare, and the rig on the ride
 *   and Bump's kick and tension.
 * - **CORNERS:** the gesture's pairs at their corners: the snare's DRIP × CRUSH and SLAP × DRIP, the kick's
 *   DUST × AMP.
 * - **EDGES:** every voice with every control at 0, then at 1. `diagnostics.json` records each render's peak
 *   and that it is finite.
 * - **PRESETS:** every factory preset, three per voice.
 * - **KITS:** the three kits' grooves through the app's own preview mixer, from the kits as the shelf deals
 *   them (as `BreakerKitGenerator` renders them).
 */
object BreakerAuditionGenerator {
    private const val RATE = 44100

    private class Clip(val section: String, val id: String, val label: String, val seconds: Double, val crest: Double)

    private val clips = mutableListOf<Clip>()
    private val metrics = StringBuilder("section,id,seconds,crest_db\n")

    private fun name(voice: BreakerVoice) = voice.name.lowercase(Locale.ROOT).replace('_', ' ')

    private fun defaults(voice: BreakerVoice) = BreakerPatch(name(voice), voice, Breaker.defaults(voice))

    private fun peak(x: FloatArray): Float { var p = 0f; for (v in x) p = maxOf(p, abs(v)); return p }

    /** [x] cut to [seconds] with a 30 ms raised-cosine fade where it is cut, so a long cymbal does not click off. */
    private fun cut(x: FloatArray, seconds: Double): FloatArray {
        val n = (seconds * RATE).toInt()
        if (x.size <= n) return x
        val fade = (.03 * RATE).toInt()
        return FloatArray(n) { i -> if (i < n - fade) x[i] else x[i] * (.5 + .5 * cos(PI * (i - (n - fade)) / fade)).toFloat() }
    }

    /**
     * [x] ([channels] interleaved) at the audition loudness. A silent render stays silent rather than having its
     * noise floor raised.
     */
    private fun levelled(x: FloatArray, channels: Int = 1): FloatArray =
        if (peak(x) < 1e-5f) x else AuditionLevel.level(Snip(x, channels, RATE)).samples

    /** [parts] end to end, [gap] seconds of silence after each. */
    private fun strip(parts: List<FloatArray>, gap: Double): FloatArray {
        val g = (gap * RATE).toInt()
        val out = FloatArray(parts.sumOf { it.size + g })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size + g }
        return out
    }

    /** How long a strike of [voice] plays in a strip: a drum's whole hit, a long voice's first two seconds. */
    private fun window(voice: BreakerVoice) = when (voice) {
        BreakerVoice.CRASH, BreakerVoice.RISER, BreakerVoice.BUMP, BreakerVoice.RIDE -> 2.0
        BreakerVoice.FILL -> Breaker.FILL_SECONDS.toDouble()
        else -> 1.0
    }

    /** Writes [x] ([channels] interleaved: the kits' grooves are stereo, everything else mono) at the audition loudness. */
    private fun emit(out: File, section: String, id: String, label: String, x: FloatArray, channels: Int = 1) {
        val y = levelled(x, channels)
        check(y.all { it.isFinite() }) { "$section/$id is not finite" }
        WavWriter.write(File(out, "$section/$id.wav"), Snip(y, channels, RATE), WavWriter.BitDepth.PCM_16)
        val loud = Loudness.of(y, channels, RATE).coerceAtLeast(1e-9f)
        val crest = 20 * log10((peak(y).coerceAtLeast(1e-9f) / loud).toDouble())
        val seconds = y.size.toDouble() / channels / RATE
        clips += Clip(section, id, label, seconds, crest)
        metrics.append(String.format(Locale.ROOT, "%s,%s,%.3f,%.2f\n", section, id, seconds, crest))
    }

    private fun touch(voice: BreakerVoice): FloatArray {
        val p = defaults(voice)
        val zones = listOf(.3f, .6f, 1f).mapIndexed { z, v ->
            strip((0..2).map { t ->
                val take = if (t == 0) 0 else z * 3 + t
                levelled(cut(requireNotNull(Restrike.take(p, v, 0, take)).samples, window(voice)))
            }, .12)
        }
        return strip(zones, .35)
    }

    private fun sweep(voice: BreakerVoice, control: String): FloatArray =
        strip(listOf(0f, .25f, .5f, .75f, 1f).map { v ->
            levelled(cut(Breaker.render(voice, Breaker.defaults(voice) + (control to v)).samples, window(voice) + .5))
        }, .25)

    private fun corners(voice: BreakerVoice, a: String, b: String): FloatArray =
        strip(listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (x, y) ->
            levelled(Breaker.render(voice, Breaker.defaults(voice) + (a to x) + (b to y)).samples)
        }, .3)

    private fun json(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "testkit/breaker-audition" })
        out.deleteRecursively(); out.mkdirs()
        val began = System.nanoTime()

        for (voice in BreakerVoice.entries) {
            emit(out, "VOICES", name(voice).replace(' ', '-'), "${name(voice).uppercase(Locale.ROOT)} AT ITS DEFAULTS", defaults(voice).render().samples)
        }
        for (voice in BreakerVoice.entries) {
            emit(out, "TOUCH", name(voice).replace(' ', '-'), "${name(voice).uppercase(Locale.ROOT)}: SOFT, MEDIUM, HARD, THREE STRIKES EACH", touch(voice))
        }
        val sweeps = BreakerVoice.entries.filter { it == BreakerVoice.KICK || it == BreakerVoice.SNARE }
            .flatMap { v -> Breaker.macrosFor(v).map { v to it.name } } +
            listOf(BreakerVoice.RIDE to "AMP", BreakerVoice.RIDE to "CRUSH", BreakerVoice.BUMP to "DRIP", BreakerVoice.BUMP to "TUNE")
        for ((voice, control) in sweeps) {
            emit(out, "CONTROLS", "${name(voice).replace(' ', '-')}-${control.lowercase(Locale.ROOT)}",
                "${name(voice).uppercase(Locale.ROOT)}: $control 0, .25, .5, .75, 1", sweep(voice, control))
        }
        for ((voice, a, b) in listOf(Triple(BreakerVoice.SNARE, "DRIP", "CRUSH"), Triple(BreakerVoice.SNARE, "SLAP", "DRIP"), Triple(BreakerVoice.KICK, "DUST", "AMP"))) {
            emit(out, "CORNERS", "${name(voice)}-${a.lowercase(Locale.ROOT)}-${b.lowercase(Locale.ROOT)}",
                "${name(voice).uppercase(Locale.ROOT)}: $a × $b AT 0/0, 0/1, 1/0, 1/1", corners(voice, a, b))
        }

        // The edges: every control at 0, then at 1, on every voice, with each render's health recorded.
        val diagnostics = StringBuilder("{\n  \"edges\": [\n")
        val edgeRows = mutableListOf<String>()
        for (edge in listOf(0f, 1f)) {
            val parts = BreakerVoice.entries.map { voice ->
                val x = Breaker.render(voice, Breaker.macrosFor(voice).associate { it.name to edge }).samples
                val finite = x.all { it.isFinite() }
                val p = peak(x)
                check(finite && p <= 1f) { "$voice at every control $edge: finite=$finite peak=$p" }
                edgeRows += String.format(Locale.ROOT, "    {\"voice\": \"%s\", \"controls\": %.0f, \"finite\": %b, \"peak\": %.4f, \"silent\": %b}",
                    voice.name, edge, finite, p, p < 1e-5f)
                levelled(cut(x, window(voice) + .5))
            }
            emit(out, "EDGES", if (edge == 0f) "all-zero" else "all-one",
                "EVERY VOICE WITH EVERY CONTROL AT ${if (edge == 0f) "0" else "1"}", strip(parts, .2))
        }
        diagnostics.append(edgeRows.joinToString(",\n")).append("\n  ]\n}\n")

        for (voice in BreakerVoice.entries) {
            val presets = BreakerPresets.forVoice(voice)
            emit(out, "PRESETS", name(voice).replace(' ', '-'),
                "${name(voice).uppercase(Locale.ROOT)}: ${presets.joinToString(", ") { it.name.uppercase(Locale.ROOT) }}",
                strip(presets.map { levelled(it.render().samples) }, .3))
        }

        for (kit in BreakerKits.Kit.entries) {
            val dir = Files.createTempDirectory("breaker-audition-${kit.id}").toFile()
            try {
                requireNotNull(StarterKits.byId(kit.id)).render(BreakerKitGenerator.nameOf(kit), dir)
                val groove = KitPreview.render(KitStore.load(dir), dir, BreakerKitGenerator.grooveFor(kit), kit.bpm)
                emit(out, "KITS", kit.id, "${kit.title}: FIVE BARS AT ${kit.bpm.toInt()} BPM, THE APP'S OWN PREVIEW MIXER", groove.samples, groove.channels)
            } finally {
                dir.deleteRecursively()
            }
        }

        // Each voice's default recipe and every preset's, as the app saves them.
        File(out, "recipes").mkdirs()
        for (voice in BreakerVoice.entries) File(out, "recipes/${name(voice).replace(' ', '-')}.json").writeText(defaults(voice).toJsonText())
        for (p in BreakerPresets.all())
            File(out, "recipes/${name(p.voice).replace(' ', '-')}-${p.name.lowercase(Locale.ROOT).replace(' ', '-')}.json").writeText(p.toJsonText())
        File(out, "metrics.csv").writeText(metrics.toString())
        File(out, "diagnostics.json").writeText(diagnostics.toString())

        val sections = listOf(
            Triple("VOICES", "Every voice", "Each voice at its defaults, hit hard, on renderer model ${Breaker.MODEL_VERSION}."),
            Triple("TOUCH", "Soft to hard", "Each voice soft, medium and hard, three strikes at each, in the order a pad deals them. Each strike is levelled, so a softer hit sounds softer in character only."),
            Triple("CONTROLS", "Every control", "Each control from 0 to 1 in quarters on the kick and the snare; the ride's crush and amp; Bump's kick (DRIP) and tension (TUNE)."),
            Triple("CORNERS", "Corners", "Pairs of controls at their corners, in the order 0/0, 0/1, 1/0, 1/1: the snare's spring against its crush and its echo against its spring, and the kick's dust against its amp."),
            Triple("EDGES", "Edges", "Every voice with every control at 0, then every control at 1. Nothing should click, blow up or vanish."),
            Triple("PRESETS", "Presets", "Every factory preset, three per voice, in order."),
            Triple("KITS", "The three kits", "BREAKER, Dub Tank and Trip hop as the shelf deals them, each in a short groove through the app's own preview mixer."),
        )
        val gate = """
            {"id": "breaker-final", "name": "BREAKER, the final audition", "task": "generateBreakerAudition",
             "title": "Breaker Audition", "badge": "BREAKER.AUDITION", "heading": "BREAKER",
             "intro": "The whole engine on one page: every voice, how each plays soft to hard, every control across its range, the presets, and the three kits. Every clip plays at the same loudness, so you judge the sound, not the volume.",
             "stages": [["VOICES", "Fourteen drums, cymbals, the riser and Bump, as they ship."], ["CONTROLS", "TUNE, DUST, DRIP, SLAP, CRUSH, AMP and DAMP, each from 0 to 1."], ["KITS", "BREAKER, Dub Tank and Trip hop, each in a groove."]],
             "questionsTitle": "Five questions",
             "questions": [
              {"id": "voices", "title": "Every voice as it ships: is this BREAKER?", "clips": [${BreakerVoice.entries.joinToString(", ") { "\"VOICES/${name(it).replace(' ', '-')}\"" }}], "options": ["Yes", "Some voices are off"]},
              {"id": "controls", "title": "Each control from 0 to 1: does it do what its name says, all the way along?", "clips": [${sweeps.joinToString(", ") { (v, c) -> "\"CONTROLS/${name(v).replace(' ', '-')}-${c.lowercase(Locale.ROOT)}\"" }}], "options": ["Yes", "Some don't", "Some go too far"]},
              {"id": "edges", "title": "At the edges (every control at 0, then at 1): does anything break?", "clips": ["EDGES/all-zero", "EDGES/all-one"], "options": ["Nothing breaks", "Something breaks"]},
              {"id": "presets", "title": "The presets, three per voice: worth keeping?", "clips": [${BreakerVoice.entries.joinToString(", ") { "\"PRESETS/${name(it).replace(' ', '-')}\"" }}], "options": ["Keep them all", "Some aren't"]},
              {"id": "kits", "title": "The three kits side by side: is the shelf right?", "clips": [${BreakerKits.Kit.entries.joinToString(", ") { "\"KITS/${it.id}\"" }}], "options": ["Yes", "One needs work"]}
             ]}
        """.trimIndent()
        val manifest = StringBuilder("{\n\"engine\": \"BREAKER\", \"model\": ${Breaker.MODEL_VERSION},\n\"gate\": ").append(gate).append(",\n\"sections\": [\n")
        manifest.append(sections.joinToString(",\n") { (id, title, note) ->
            "  {\"id\": \"$id\", \"title\": \"${json(title)}\", \"note\": \"${json(note)}\", \"clips\": [\n" +
                clips.filter { it.section == id }.joinToString(",\n") {
                    String.format(Locale.ROOT, "    {\"id\": \"%s\", \"file\": \"%s/%s.wav\", \"label\": \"%s\", \"seconds\": %.3f, \"crest\": %.2f}",
                        it.id, it.section, it.id, json(it.label), it.seconds, it.crest)
                } + "\n  ]}"
        })
        manifest.append("\n]\n}\n")
        File(out, "manifest.json").writeText(manifest.toString())
        val page = requireNotNull(BreakerAuditionGenerator::class.java.getResourceAsStream("/audition/breaker-audition.html")) { "the audition page template is missing" }
        page.use { File(out, "index.html").writeBytes(it.readBytes()) }
        println("BREAKER audition: ${clips.size} clips, ${String.format(Locale.ROOT, "%.0f", clips.sumOf { it.seconds })} s, in $out, " +
            "built in ${(System.nanoTime() - began) / 1_000_000_000} s")
    }
}

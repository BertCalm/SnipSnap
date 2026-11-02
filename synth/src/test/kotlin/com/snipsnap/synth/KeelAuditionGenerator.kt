package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/**
 * Renders the KEEL R1 audition (docs/superpowers/specs/2026-10-23-keel-bass-engine-design.md, section 17's gate)
 * under testkit/keel-audition/ (gitignored): `index.html`, `manifest.json` and one folder of loudness-matched WAVs
 * per section.
 *
 * The page opens with one question and nine clips: does WEIGHT make the note heavier? The nine are the notes the
 * body of the spec's section 9 was measured on (REDUCE at MIDI 30 and 42, UPRIGHT at MIDI 36), each at WEIGHT 0,
 * .5 and 1, with the harness macro set and LENGTH .8, so the clips are the ones the measurements describe.
 * Everything else (the voices at three notes, each macro swept, SLIDE at five settings, the corners of WEIGHT by
 * SETTLE and of GRIT by touch) sits under a closed fold and is optional. The kit and the loop are not here
 * because R1 builds neither.
 *
 * Run via `./gradlew :synth:generateKeelAudition`. Nothing here establishes that anything sounds right; the page
 * exists so the owner can hear it. The generator does not clear an earlier run's files: a folder from an older
 * round keeps clips this page no longer lists.
 */
object KeelAuditionGenerator {
    private const val QUESTION = "Does WEIGHT make the note heavier?"

    /** The Python harness's macro set for the nine clips (UPRIGHT's own defaults differ), with the harness's LENGTH. */
    private val WEIGHT_CHECK = mapOf("SETTLE" to .5f, "GRIT" to .2f, "SLIDE" to .5f, "LENGTH" to .8f)
    private val NAMES = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    private class Clip(
        val id: String, val label: String, val note: String, val voice: KeelVoice, val midi: Int,
        val macros: Map<String, Float>, val velocity: Float = 1f,
    )
    private class Group(val title: String, val about: String, val clips: List<Clip>)
    private class Section(val id: String, val title: String, val about: String, val groups: List<Group>, val optional: Boolean)

    private fun noteName(midi: Int) = NAMES[midi % 12] + (midi / 12 - 1)
    private fun hzText(midi: Int) = "%.0f Hz".format(Keys.midiHz(midi))
    private fun fmt(v: Float) = if (v == 0f) "0" else if (v == 1f) "1" else ("%.2f".format(v)).trimEnd('0').trimStart('0')

    private fun clip(id: String, label: String, voice: KeelVoice, midi: Int, vararg macros: Pair<String, Float>, velocity: Float = 1f) =
        Clip(id, label, "${noteName(midi)}, ${hzText(midi)}", voice, midi, Keel.defaults(voice) + macros, velocity)

    private fun sections(): List<Section> {
        val start = Section(
            "START", "Start here: one question",
            "Each row plays one note three times. Only WEIGHT changes: 0, then .5, then 1. WEIGHT raises the note's own fundamental and first three overtones together, so it should get heavier without changing pitch. Every clip is matched in loudness.",
            listOf(
                Group("Row 1, REDUCE, F♯1", "The note is 46 Hz.", weightRow("start_reduce_f1", KeelVoice.REDUCE, 30)),
                Group("Row 2, REDUCE, F♯2", "The note is 92 Hz.", weightRow("start_reduce_f2", KeelVoice.REDUCE, 42)),
                Group("Row 3, UPRIGHT, C2", "A plucked string. The note is 65 Hz.", weightRow("start_upright_c2", KeelVoice.UPRIGHT, 36)),
            ),
            optional = false,
        )
        val notes = Section(
            "NOTES", "The voices at three notes", "Each voice at its own defaults on F♯1, C2 and F♯2.",
            KeelVoice.entries.map { voice ->
                Group(voice.name, voiceAbout(voice), listOf(30, 36, 42).map { midi ->
                    clip("notes_${voice.name.lowercase()}_$midi", noteName(midi), voice, midi)
                })
            },
            optional = true,
        )
        val macros = Section(
            "MACROS", "Each macro swept", "Each macro at both ends and the middle on C2, the rest at the voice's defaults. HOLD's sweep stops at .8, in the sustain stage: from .85 HOLD is the held loop, heard on the keys page.",
            KeelVoice.entries.flatMap { voice ->
                MACRO_SWEEPS.map { sweep ->
                    Group("${voice.name}, ${sweep.name}", sweep.about, sweep.steps.map { step ->
                        clip("macro_${voice.name.lowercase()}_${sweep.name.lowercase()}_${fmt(step).ifEmpty { "0" }.replace(".", "")}",
                            "${sweep.name} ${fmt(step)}", voice, 36, sweep.name to step)
                    })
                }
            },
            optional = true,
        )
        val slide = Section(
            "SLIDE", "SLIDE at five settings", "0 starts an octave below and rises onto the note, .5 does not slide, 1 starts an octave above and falls onto it. The glide takes 30 to 280 ms. Sliding down from above may sound like a cartoon; say so if it does.",
            KeelVoice.entries.map { voice ->
                Group(voice.name, "C2, WEIGHT .4, the body follows the same glide.", listOf(0f, .25f, .5f, .75f, 1f).map { s ->
                    clip("slide_${voice.name.lowercase()}_${(s * 100).toInt()}", "SLIDE ${fmt(s)}", voice, 36, "SLIDE" to s, "WEIGHT" to .4f)
                })
            },
            optional = true,
        )
        val corners = Section(
            "CORNERS", "The corners of two pairs", "WEIGHT against SETTLE decides when the body fills in. GRIT against touch (velocity) decides how hard the source is driven.",
            KeelVoice.entries.flatMap { voice ->
                listOf(
                    Group("${voice.name}, WEIGHT by SETTLE", "WEIGHT 0 or 1 across, SETTLE 0 (settles in 50 ms) or 1 (settles in over a second).",
                        listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (w, s) ->
                            clip("corner_${voice.name.lowercase()}_w${w.toInt()}_s${s.toInt()}", "WEIGHT ${fmt(w)}, SETTLE ${fmt(s)}", voice, 36, "WEIGHT" to w, "SETTLE" to s)
                        }),
                    Group("${voice.name}, GRIT by touch", "GRIT 0 or 1 across, touch .3 (soft) or 1 (hard).",
                        listOf(0f to .3f, 0f to 1f, 1f to .3f, 1f to 1f).map { (g, v) ->
                            clip("corner_${voice.name.lowercase()}_g${g.toInt()}_v${(v * 10).toInt()}", "GRIT ${fmt(g)}, touch ${fmt(v)}", voice, 36, "GRIT" to g, velocity = v)
                        }),
                )
            },
            optional = true,
        )
        return listOf(start, notes, macros, slide, corners)
    }

    private fun voiceAbout(voice: KeelVoice) = when (voice) {
        KeelVoice.REDUCE -> "Two-operator FM whose index decays on the settle clock, so it distils to a sine."
        KeelVoice.UPRIGHT -> "A plucked string through a fixed body and a closing low-pass."
        KeelVoice.BELLY -> "A sine belly and a sharp saw bite through a closing low-pass; the bite fades as the note settles."
    }

    private fun weightRow(prefix: String, voice: KeelVoice, midi: Int) =
        listOf(0f, .5f, 1f).map { w ->
            clip("${prefix}_w${(w * 10).toInt()}", "WEIGHT ${fmt(w)}", voice, midi, *(WEIGHT_CHECK + ("WEIGHT" to w)).toList().toTypedArray())
        }

    private class Sweep(val name: String, val steps: List<Float>, val about: String)

    private val MACRO_SWEEPS = listOf(
        Sweep("SETTLE", listOf(0f, .5f, 1f), "How fast the upper spectrum leaves: about 50 ms at 0, over a second at 1."),
        Sweep("WEIGHT", listOf(0f, .5f, 1f), "The body: the note's fundamental and overtones 2 to 4, raised together by up to 9 dB, more as the settle goes on."),
        Sweep("GRIT", listOf(0f, .5f, 1f), "A tanh drive on the source only. WEIGHT is added after it."),
        Sweep("LENGTH", listOf(0f, .5f, 1f), "The time to fade 60 dB: 0.2 s at 0, 1.25 s at 1."),
        Sweep("HOLD", listOf(0f, .4f, .8f), "Seconds at full level before the decay begins."),
    )

    private class Made(val clip: Clip, val file: String, val section: String, val snip: Snip)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/keel-audition").canonicalFile
        require(root.name.isNotEmpty() && root.parentFile != null) { "Use a dedicated audition output directory." }
        root.mkdirs()
        val made = mutableListOf<Made>()
        val sections = sections()
        val ids = sections.flatMap { s -> s.groups.flatMap { g -> g.clips.map { it.id } } }
        require(ids.distinct().size == ids.size) { "clip ids must be unique" }
        require(sections.first().groups.sumOf { it.clips.size } <= 10) { "the first section holds at most ten clips" }
        for (section in sections) for (group in section.groups) for (clip in group.clips) {
            val snip = Keel.render(clip.voice, clip.macros, clip.midi, clip.velocity)
            check(snip.samples.all { it.isFinite() } && snip.peak() > 1e-3f) { "${clip.id} rendered invalid or silent audio" }
            val leveled = AuditionLevel.level(snip)
            val file = "${section.id}/${clip.id}.wav"
            WavWriter.write(File(root, file), leveled, WavWriter.BitDepth.PCM_16)
            made += Made(clip, file, section.id, leveled)
        }
        File(root, "manifest.json").writeText(Json.write(manifest(made)))
        File(root, "index.html").writeText(page(sections, made.associateBy { it.clip.id }))
        println("wrote ${made.size} clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private fun manifest(made: List<Made>): JsonValue {
        // Floats are written as the decimal they were typed as (.7, not 0.699999988).
        fun num(v: Number) = JsonValue.Num(if (v is Float) v.toString().toDouble() else v.toDouble())
        return JsonValue.Obj(linkedMapOf(
            "engine" to JsonValue.Str(KeelPatch.ENGINE),
            "model" to num(Keel.MODEL_VERSION),
            "round" to JsonValue.Str("R1"),
            "question" to JsonValue.Str(QUESTION),
            "note" to JsonValue.Str("Clips are matched in Loudness.of, not in peak. Nothing here says what sounds right."),
            "clips" to JsonValue.Arr(made.map { m ->
                JsonValue.Obj(linkedMapOf(
                    "id" to JsonValue.Str(m.clip.id), "section" to JsonValue.Str(m.section), "file" to JsonValue.Str(m.file),
                    "voice" to JsonValue.Str(m.clip.voice.name), "midi" to num(m.clip.midi), "velocity" to num(m.clip.velocity),
                    "macros" to JsonValue.Obj(Keel.settled(m.clip.voice, m.clip.macros).mapValues { num(it.value) }),
                    "frames" to num(m.snip.frameCount), "seconds" to num(m.snip.frameCount.toDouble() / Dsp.RATE),
                    "peak" to num(m.snip.peak()), "loudness" to num(Loudness.of(m.snip)),
                ))
            }),
        ))
    }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(sections: List<Section>, byId: Map<String, Made>): String {
        val html = StringBuilder()
        var number = 0
        fun clips(group: Group, numbered: Boolean) {
            html.append("<h3>").append(esc(group.title)).append("</h3>\n<p class=\"about\">").append(esc(group.about)).append("</p>\n<ol class=\"clips\">\n")
            for (clip in group.clips) {
                val made = byId.getValue(clip.id)
                val tag = if (numbered) "<span class=\"n\">Clip ${++number}</span> " else ""
                html.append("<li>").append(tag).append("<span class=\"label\">").append(esc(clip.label)).append("</span> ")
                    .append("<span class=\"note\">").append(esc(clip.note)).append("</span>")
                    .append("<audio controls preload=\"none\" src=\"").append(made.file).append("\"></audio></li>\n")
            }
            html.append("</ol>\n")
        }
        val first = sections.first()
        html.append("<section id=\"start\">\n<h2>").append(esc(first.title)).append("</h2>\n")
            .append("<p class=\"q\">").append(esc(QUESTION)).append("</p>\n")
            .append("<p>").append(esc(first.about)).append(" Answer yes or no for each row; if yes, at which clip it first sounds heavier.</p>\n")
            .append("<p class=\"about\">The earlier Python-harness clips are in the harness-v3 folder. They are not on this page.</p>\n")
        for (group in first.groups) clips(group, numbered = true)
        html.append("</section>\n<section id=\"more\">\n<h2>Everything else, optional</h2>\n<p class=\"about\">None of this is needed to answer the question above. Open a fold to listen.</p>\n")
        for (section in sections.drop(1)) {
            html.append("<details>\n<summary>").append(esc(section.title)).append("</summary>\n<p class=\"about\">").append(esc(section.about)).append("</p>\n")
            for (group in section.groups) clips(group, numbered = false)
            html.append("</details>\n")
        }
        html.append("</section>\n")
        return PAGE_HEAD + html + PAGE_TAIL
    }

    private val PAGE_HEAD = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>KEEL first listen</title>
<style>
:root { --bg: #fbfbfa; --fg: #1d2326; --muted: #5b6a70; --line: #d5dcde; --accent: #0b6e7f; --box: #eef3f4; }
@media (prefers-color-scheme: dark) { :root { --bg: #14191b; --fg: #e4eaec; --muted: #9aabb1; --line: #2d393d; --accent: #5cc4d6; --box: #1c2427; } }
body { margin: 0 auto; max-width: 44rem; padding: 1.25rem 16px 4rem; background: var(--bg); color: var(--fg); font: 16px/1.5 system-ui, sans-serif; }
h1 { font-size: 1.6rem; margin: 0 0 .25rem; }
h2 { font-size: 1.2rem; margin: 2rem 0 .5rem; border-bottom: 1px solid var(--line); padding-bottom: .25rem; }
h3 { font-size: 1rem; margin: 1.25rem 0 .1rem; }
.lead, .about { color: var(--muted); margin: .1rem 0 .5rem; }
.q { font-size: 1.15rem; font-weight: 600; }
ol.clips { list-style: none; padding: 0; margin: 0; }
ol.clips li { padding: .5rem 0; border-top: 1px solid var(--line); display: flex; flex-wrap: wrap; align-items: center; gap: .25rem .75rem; }
.n { font-weight: 700; color: var(--accent); min-width: 4.5rem; }
.label { font-weight: 600; }
.note { color: var(--muted); }
audio { width: 100%; max-width: 22rem; }
details { margin: .75rem 0; border: 1px solid var(--line); border-radius: 6px; padding: .5rem .8rem; }
summary { cursor: pointer; font-weight: 600; }
</style>
</head>
<body>
<h1>KEEL first listen</h1>
<p class="lead">A bass whose upper spectrum distils away while its body fills in under the note. Two voices, REDUCE and UPRIGHT. Rendered offline at 44.1 kHz.</p>
"""

    private const val PAGE_TAIL = "</body>\n</html>\n"
}

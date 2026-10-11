package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders the GYRE round-two audition (docs/superpowers/plans/2026-10-04-gyre-round-2.md, Task 7) under
 * testkit/gyre-audition/ (gitignored): all four voices at three notes, TOUCH at the document's five points
 * on FLICK and DRAWN, the pluck the bow catches beside a 50/50 crossfade of the two ends (so "is it a
 * crossfade?" can be heard), TOUCH 0 against the frozen round-one engine, HOLD on the two bowed voices, SPIN
 * on DRAWN, the knobs together and the edges. Clips share one loudness ([AuditionLevel]). Writes
 * `manifest.json`, which the page builds itself from, then copies the listening page from the test resources.
 * Run via `./gradlew :synth:generateGyreAudition`.
 *
 * Earlier rounds' listens are in the spec and in the artifact database under `verdicts/gyre_r1_*`,
 * `gyre_r1b_*` and `gyre_r1c_*`: round one kept SYMPATHY and HOLD, round 1b fixed BODY and SPIN, round 1c
 * refitted HOLD. This round saves under `gyre_r2_*`.
 */
object GyreAuditionGenerator {

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    private const val DOT = "·"
    private val PHRASE = listOf(7, 12, 19)
    private val BOWED = listOf(GyreVoice.FLICK, GyreVoice.DRAWN)
    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** A MIDI note's name the house's way: 60 is C4. */
    private fun noteName(midi: Int) = NAMES[midi % 12] + (midi / 12 - 1)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/gyre-audition")
        root.mkdirs()
        var count = 0
        val sections = mutableListOf<String>()

        fun write(section: String, id: String, snip: Snip) {
            WavWriter.write(File(File(root, section), "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            count++
        }
        fun render(voice: GyreVoice, macros: Map<String, Float>) = Gyre.render(voice, macros)
        fun tag(voice: GyreVoice) = voice.name.lowercase()
        fun c4(voice: GyreVoice) = Gyre.defaults(voice) + ("TUNE" to 12f / Gyre.TUNE_SEMITONES)
        fun shares(touch: Float) = "the pluck %d%%, the bow's contact %d%%".format(Math.round(Gyre.pluckAmount(touch) * 100), Math.round(Gyre.contactFor(touch) * 100))

        // 1. The voices, three notes each.
        sections += section(
            "VOICES", "THE VOICES", "each voice at its defaults, three notes (the same three intervals above its own root)",
            listOf(
                "FLICK: A DRY, PLUCKED STRING ON A SMALL BODY",
                "HALO: SOFTER, LONGER, A CLOUD OF SYMPATHETIC STRINGS",
                "DRAWN: A BOWED STRING WITH A PLUCK'S ATTACK IN IT",
                "BOURDON: A LOW DRONE ON A LARGE BODY",
            ),
            GyreVoice.entries.map { voice ->
                Group(voice.name, key = true, clips = PHRASE.map { semis ->
                    val id = "${tag(voice)}_${noteName(Gyre.rootMidi(voice) + semis).lowercase().replace("#", "s")}"
                    write("VOICES", id, render(voice, mapOf("TUNE" to semis.toFloat() / Gyre.TUNE_SEMITONES)))
                    Clip(id, "${voice.name} ${noteName(Gyre.rootMidi(voice) + semis)}", "every knob at its default (TOUCH ${fmt(Gyre.defaults(voice).getValue("TOUCH"))})")
                })
            },
        )

        // 2. TOUCH at the document's five points, on the plucked voice and the bowed one.
        sections += section(
            "TOUCH", "TOUCH", "FLICK and DRAWN at the document's five points, C4 above each root's octave, the other knobs at their defaults",
            listOf("FLICK: A PLUCK, THEN A BOW LOWERED ONTO IT", "DRAWN: THE SAME, ON THE VOICE THAT STARTS BOWED"),
            BOWED.map { voice ->
                Group("${voice.name} ${DOT} DEFAULT TOUCH " + fmt(Gyre.defaults(voice).getValue("TOUCH")), key = true, clips = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { v ->
                    val id = "${tag(voice)}_touch${fmt(v).trimStart('.')}"
                    write("TOUCH", id, render(voice, c4(voice) + ("TOUCH" to v)))
                    Clip(id, "TOUCH ${fmt(v)}", shares(v))
                })
            },
        )

        // 3. The question itself: the middle beside a crossfade of the ends.
        sections += section(
            "CATCH", "THE PLUCK THE BOW CATCHES", "TOUCH .5 beside the two ends and a 50/50 mix of them: is the middle one string being plucked and bowed, or two instruments at once?",
            listOf("THE MIDDLE IS THE MECHANISM CHANGING; THE MIX IS TWO SOUNDS TOGETHER"),
            BOWED.map { voice ->
                val plucked = render(voice, c4(voice) + ("TOUCH" to 0f))
                val bowed = render(voice, c4(voice) + ("TOUCH" to 1f))
                val middle = render(voice, c4(voice) + ("TOUCH" to 0.5f))
                val n = maxOf(plucked.samples.size, bowed.samples.size)
                val mix = FloatArray(n) { i -> 0.5f * (plucked.samples.getOrElse(i) { 0f }) + 0.5f * (bowed.samples.getOrElse(i) { 0f }) }
                write("CATCH", "${tag(voice)}_pluck", plucked)
                write("CATCH", "${tag(voice)}_bow", bowed)
                write("CATCH", "${tag(voice)}_middle", middle)
                write("CATCH", "${tag(voice)}_mix", Snip(mix, channels = 1, sampleRate = Dsp.RATE))
                Group(voice.name, key = true, clips = listOf(
                    Clip("${tag(voice)}_pluck", "PLUCK", "TOUCH 0: the string alone"),
                    Clip("${tag(voice)}_bow", "BOW", "TOUCH 1: the bow alone"),
                    Clip("${tag(voice)}_middle", "TOUCH .5", "one string, plucked and bowed (the bow catches near here)"),
                    Clip("${tag(voice)}_mix", "50/50 MIX", "the PLUCK and the BOW clips played together, for comparison: this is what a crossfade sounds like"),
                ))
            },
        )

        // 4. TOUCH 0 against round one, keyed for an A/B.
        sections += section(
            "ROUND1", "TOUCH 0 IS ROUND ONE", "FLICK and HALO from the frozen round-one engine, beside the new engine at TOUCH 0: they should be the same sound",
            listOf("EACH PAIR: ROUND ONE FIRST, THEN ROUND TWO AT TOUCH 0"),
            listOf(GyreVoice.FLICK, GyreVoice.HALO).map { voice ->
                Group(voice.name, key = true, clips = PHRASE.flatMap { semis ->
                    val m = mapOf("TUNE" to semis.toFloat() / Gyre.TUNE_SEMITONES)
                    val name = noteName(Gyre.rootMidi(voice) + semis)
                    val old = "${tag(voice)}_${name.lowercase().replace("#", "s")}_r1"
                    val now = "${tag(voice)}_${name.lowercase().replace("#", "s")}_r2"
                    write("ROUND1", old, LegacyGyre.render(LegacyGyreVoice.valueOf(voice.name), m))
                    write("ROUND1", now, render(voice, m))
                    listOf(Clip(old, "$name ROUND 1", "the frozen round-one engine"), Clip(now, "$name ROUND 2", "the new engine, TOUCH 0"))
                })
            },
        )

        // 5. HOLD on a bowed note: the stroke.
        val bowedVoices = listOf(GyreVoice.DRAWN, GyreVoice.BOURDON)
        sections += section(
            "HOLD", "HOLD ON A BOWED NOTE", "HOLD at five settings on the two bowed voices: from a bow that stops almost at once to one drawn for its whole stroke",
            listOf("THE BOW LIFTS WHEN THE HAND LANDS; AT THE TOP IT HAS BEEN DRAWN FOR THE STROKE"),
            bowedVoices.map { voice ->
                Group("${voice.name} ${DOT} DEFAULT HOLD " + fmt(Gyre.defaults(voice).getValue("HOLD")), key = true, clips = listOf(0f, 0.25f, 0.5f, 0.75f, 0.95f).map { v ->
                    val id = "${tag(voice)}_hold${fmt(v).trimStart('.')}"
                    write("HOLD", id, render(voice, Gyre.defaults(voice) + ("HOLD" to v)))
                    val s = Gyre.handSeconds(voice, Gyre.defaults(voice) + ("HOLD" to v))
                    Clip(id, "HOLD ${fmt(v)}", "the bow lifts after %.2f s".format(java.util.Locale.ROOT, s) + if (v <= 0f) ": choked" else if (v >= 0.95f) ": nearly the whole stroke (%.0f s)".format(java.util.Locale.ROOT, Gyre.shapeOf(voice).stroke) else "")
                })
            },
        )

        // 6. SPIN on DRAWN, as round 1b's SPIN section.
        run {
            val voice = GyreVoice.DRAWN
            val spins = listOf(0f, 0.02f, 0.25f, 0.45f, 0.75f, 1f)
            val m = Gyre.defaults(voice) + mapOf("SPIN" to 0.45f)
            val spun = render(voice, m)
            val still = render(voice, m + ("SPIN" to 0f)).samples
            val hz = Gyre.rotorHz(0.45f).toDouble()
            val depth = logSwing(spun.samples, hz)
            val tremolo = FloatArray(still.size) { i -> (still[i] * exp(depth * sin(2 * PI * hz * i / Dsp.RATE))).toFloat() }
            write("SPIN", "drawn_rotor", spun)
            write("SPIN", "drawn_tremolo", Snip(tremolo, channels = 1, sampleRate = Dsp.RATE))
            sections += section(
                "SPIN", "SPIN ON A BOWED NOTE", "DRAWN at six settings of SPIN, then the rotor beside a tremolo of the same depth: with a bow on the strings the rotor also moves how hard each upper string is bowed",
                listOf("THE ROTOR MOVES THE TIMBRE INSIDE THE NOTE, NEVER ITS LOUDNESS"),
                listOf(
                    Group("DRAWN ${DOT} DEFAULT SPIN " + fmt(Gyre.defaults(voice).getValue("SPIN")), key = true, clips = spins.map { v ->
                        val id = "drawn_spin${fmt(v).trimStart('.')}"
                        write("SPIN", id, render(voice, Gyre.defaults(voice) + ("SPIN" to v)))
                        Clip(id, "SPIN ${fmt(v)}", if (v <= 0f) "the rotor still" else "the rotor at %.2f Hz".format(java.util.Locale.ROOT, Gyre.rotorHz(v)))
                    }),
                    Group("DRAWN: THE ROTOR AGAINST A TREMOLO", key = true, clips = listOf(
                        Clip("drawn_rotor", "ROTOR", "SPIN .45, %.1f Hz: the rotor inside the instrument".format(java.util.Locale.ROOT, hz)),
                        Clip("drawn_tremolo", "TREMOLO", "the still sound with its volume swung by the same amount: only the level moves"),
                    )),
                ),
            )
        }

        // 7. Together.
        fun corners(a: String, b: String): List<Group> = BOWED.map { voice ->
            Group("${voice.name}: $a ${DOT} $b", key = false, clips = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (x, y) ->
                val id = "${tag(voice)}_${a.lowercase()}${fmt(x).trimStart('.')}_${b.lowercase()}${fmt(y).trimStart('.')}"
                write("TOGETHER", id, render(voice, c4(voice) + mapOf(a to x, b to y)))
                Clip(id, "$a ${fmt(x)} $b ${fmt(y)}", "the rest at the defaults")
            })
        }
        sections += section(
            "TOGETHER", "THE KNOBS TOGETHER", "each pair at its four corners, on FLICK and DRAWN",
            listOf("TOUCH X SPIN: THE ROTOR AND THE BOW", "TOUCH X SYMPATHY: A SUSTAINED BOW KEEPS THE SYMPATHETIC STRINGS RINGING"),
            corners("TOUCH", "SPIN") + corners("TOUCH", "SYMPATHY"),
        )

        // 8. The edges.
        val edges = listOf(
            "touch1" to mapOf("TOUCH" to 1f), "sym1" to mapOf("SYMPATHY" to 1f), "body1" to mapOf("BODY" to 1f), "spin1" to mapOf("SPIN" to 1f),
            "all1" to mapOf("TOUCH" to 1f, "SYMPATHY" to 1f, "BODY" to 1f, "SPIN" to 1f),
        )
        sections += section(
            "EDGES", "THE EDGES", "every knob at its top, then all of them, on the two bowed voices",
            listOf("BOUNDED BY CONSTRUCTION: NOTHING HERE CAN RUN AWAY"),
            bowedVoices.map { voice ->
                Group(voice.name, key = false, clips = edges.map { (id, m) ->
                    write("EDGES", "${tag(voice)}_$id", render(voice, m))
                    Clip("${tag(voice)}_$id", m.keys.joinToString(" ") { "$it 1" }, "the rest at the defaults")
                })
            },
        )

        File(root, "manifest.json").writeText("{\"voices\": [\n" + sections.joinToString(",\n") + "\n]}\n")
        val page = GyreAuditionGenerator::class.java.getResourceAsStream("/audition/gyre-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /** The swing of the log level at [hz] (20 ms blocks, a linear trend removed): the rotor's level movement, for the tremolo control. */
    private fun logSwing(x: FloatArray, hz: Double): Double {
        val block = (0.02 * Dsp.RATE).toInt()
        val n = x.size / block
        val lv = DoubleArray(n) { b ->
            var s = 0.0
            for (i in b * block until (b + 1) * block) s += x[i].toDouble() * x[i]
            ln(sqrt(s / block) + 1e-9)
        }
        val mt = (n - 1) / 2.0
        val my = lv.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (i - mt) * (lv[i] - my); sxx += (i - mt) * (i - mt) }
        val slope = sxy / sxx
        var re = 0.0; var im = 0.0
        val bs = block.toDouble() / Dsp.RATE
        for (i in 0 until n) {
            val r = lv[i] - (my + slope * (i - mt))
            re += r * cos(2 * PI * hz * i * bs); im += r * sin(2 * PI * hz * i * bs)
        }
        return 2 * sqrt(re * re + im * im) / n
    }

    /** A macro value the way the app's sliders read it: `0`, `.35`, `1`. */
    private fun fmt(v: Float) = when {
        v <= 0f -> "0"
        v >= 1f -> "1"
        else -> "." + (v * 100).roundToInt().toString().padStart(2, '0')
    }

    private fun section(id: String, display: String, body: String, readout: List<String>, groups: List<Group>): String {
        val g = groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { "[${q(it.id)},${q(it.name)},${q(it.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":${grp.key},\"clips\":[$c]}"
        }
        val r = readout.joinToString(",") { q(it) }
        return "{\"id\":${q(id)},\"display\":${q(display)},\"body\":${q(body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

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
 * Renders the GYRE round-one audition (docs/superpowers/specs/2026-10-01-gyre-coupled-string-engine-design.md,
 * the round-one gate; the clip list is the plan's Task 4) under testkit/gyre-audition/ (gitignored): both
 * voices at three notes, the round's own claim as sound (one string plucked with the others answering, and
 * with the bridge off), each knob at five settings, the sympathetic strings alone, the rotor beside a tremolo
 * of the same depth, the knobs together, and the edges. Clips share one loudness ([AuditionLevel]). Writes
 * `manifest.json`, which the page builds itself from, then copies the listening page from the test resources.
 * Run via `./gradlew :synth:generateGyreAudition`.
 *
 * Round 1b re-renders it after the owner's first listen, which kept SYMPATHY and HOLD and sent BODY ("Body
 * doesn't make an impact") and SPIN ("Spin not noticable on short notes") back; SPIN gains a short FLICK note,
 * still and turning. Its verdicts save under their own prefix, beside the first listen's.
 * Round 1c re-renders it after the second listen, which passed BODY and SPIN and found no difference between
 * HOLD's steps ("I don't hear the distinction"): HOLD now runs from choked to open at five settings.
 */
object GyreAuditionGenerator {

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    private const val DOT = "·"
    private val PHRASE = listOf(7f / 24, 12f / 24, 19f / 24)
    private val NOTES = listOf("G3", "C4", "G4")

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
        fun probed(voice: GyreVoice, macros: Map<String, Float>, probe: Gyre.Probe) =
            Snip(Gyre.finish(Gyre.play(voice, macros, probe).raw), channels = 1, sampleRate = Dsp.RATE)
        fun tag(voice: GyreVoice) = voice.name.lowercase()

        // 1. The voices, three notes each.
        sections += section(
            "VOICES", "THE VOICES", "each voice at its defaults, three notes",
            listOf("FLICK: A DRY, PLUCKED STRING ON A SMALL BODY", "HALO: SOFTER, LONGER, A CLOUD OF SYMPATHETIC STRINGS"),
            GyreVoice.entries.map { voice ->
                Group(voice.name, key = true, clips = PHRASE.mapIndexed { i, t ->
                    val id = "${tag(voice)}_${NOTES[i].lowercase()}"
                    write("VOICES", id, render(voice, mapOf("TUNE" to t)))
                    Clip(id, "${voice.name} ${NOTES[i]}", "every knob at its default")
                })
            },
        )

        // 2. The claim: strings answering strings.
        sections += section(
            "ANSWER", "STRINGS ANSWERING STRINGS", "only the first string is plucked; the other three answer through the bridge (sympathetic strings off in both, so only the bridge differs)",
            listOf("BRIDGE ON: THE OTHERS RING IN", "BRIDGE OFF: THE SAME PLUCK, ALONE"),
            GyreVoice.entries.map { voice ->
                val d = Gyre.defaults(voice)
                // The sympathetic strings listen to the bridge whatever its coupling, so both clips leave
                // them out: the only difference between ON and OFF is the bridge.
                write("ANSWER", "${tag(voice)}_on", probed(voice, d, Gyre.Probe(solo = 0, sympathy = false)))
                write("ANSWER", "${tag(voice)}_off", probed(voice, d, Gyre.Probe(solo = 0, coupling = 0f, sympathy = false)))
                Group(voice.name, key = true, clips = listOf(
                    Clip("${tag(voice)}_on", "BRIDGE ON", "one string plucked; the others answer"),
                    Clip("${tag(voice)}_off", "BRIDGE OFF", "the same string alone: nothing to answer it"),
                ))
            },
        )

        // 3-6. Each knob at five settings, both voices; SYMPATHY also alone, SPIN also against a tremolo.
        val shares = mapOf(0f to "none", 0.3f to "subtle, about -22 dB", 0.6f to "clear, about -12 dB", 0.8f to "a halo, about -8 dB", 1f to "a cloud, about -5 dB")
        sections += knob("SYMPATHY", listOf(0f, 0.3f, 0.6f, 0.8f, 1f), { _, v -> "the sympathetic strings: " + shares.getValue(v) }, ::write) { voice, groups ->
            val m = Gyre.defaults(voice)
            val on = Gyre.play(voice, m).raw
            val off = Gyre.play(voice, m, Gyre.Probe(sympathy = false)).raw
            val alone = FloatArray(on.size) { on[it] - off[it] }
            write("SYMPATHY", "${tag(voice)}_alone", Snip(Gyre.finish(alone), channels = 1, sampleRate = Dsp.RATE))
            groups += Group("${voice.name}: THE SYMPATHETIC STRINGS ALONE", key = false, clips = listOf(
                Clip("${tag(voice)}_alone", "ALONE", "at the default SYMPATHY " + fmt(m.getValue("SYMPATHY")) + ": the strings nobody plucked"),
            ))
        }
        sections += knob("BODY", listOf(0f, 0.25f, 0.5f, 0.75f, 1f), { _, v ->
            val low = Dsp.expMap(Gyre.boxSize(v), Gyre.BOX_SMALL_HZ[0], Gyre.BOX_LARGE_HZ[0])
            "the box's lowest mode at %.0f Hz".format(java.util.Locale.ROOT, low) + if (v <= 0f) ": small, thin and nasal" else if (v >= 1f) ": large, hollow and warm" else ""
        }, ::write)
        sections += knob("SPIN", listOf(0f, 0.02f, 0.25f, 0.45f, 0.75f, 1f), { _, v ->
            if (v <= 0f) "the rotor still" else "the rotor at %.2f Hz".format(java.util.Locale.ROOT, Gyre.rotorHz(v))
        }, ::write) { voice, groups ->
            if (voice == GyreVoice.FLICK) {
                // The first listen's complaint, as its own pair: a short note, still and at a quarter turn.
                // The hand 0.55 s after the pluck, the test's short note.
                val open = Gyre.openSeconds(Gyre.defaults(voice).getValue("SYMPATHY"))
                val hold = (ln(0.55 / Gyre.CHOKE_SECONDS) / ln(open / Gyre.CHOKE_SECONDS.toDouble())).toFloat() * Gyre.LOOP_THRESHOLD
                val short = Gyre.defaults(voice) + ("HOLD" to hold)
                write("SPIN", "flick_short_still", render(voice, short + ("SPIN" to 0f)))
                write("SPIN", "flick_short_spun", render(voice, short + ("SPIN" to 0.25f)))
                groups += Group("FLICK: A SHORT NOTE (%.2f S), STILL AND TURNING".format(java.util.Locale.ROOT, Gyre.dampSeconds(hold, short.getValue("SYMPATHY"))), key = true, clips = listOf(
                    Clip("flick_short_still", "STILL", "SPIN 0: the rotor still"),
                    Clip("flick_short_spun", "SPIN .25", "the rotor at %.2f Hz: it should move before the hand lands".format(java.util.Locale.ROOT, Gyre.rotorHz(0.25f))),
                ))
                return@knob
            }
            val m = Gyre.defaults(voice) + mapOf("SPIN" to 0.45f, "HOLD" to 0.9f)
            val spun = render(voice, m)
            val still = render(voice, m + ("SPIN" to 0f)).samples
            val hz = Gyre.rotorHz(0.45f).toDouble()
            val depth = logSwing(spun.samples, hz)
            val tremolo = FloatArray(still.size) { i -> (still[i] * exp(depth * sin(2 * PI * hz * i / Dsp.RATE))).toFloat() }
            write("SPIN", "halo_rotor", spun)
            write("SPIN", "halo_tremolo", Snip(tremolo, channels = 1, sampleRate = Dsp.RATE))
            groups += Group("HALO: THE ROTOR AGAINST A TREMOLO", key = true, clips = listOf(
                Clip("halo_rotor", "ROTOR", "SPIN .45, %.1f Hz: the rotor inside the instrument".format(java.util.Locale.ROOT, hz)),
                Clip("halo_tremolo", "TREMOLO", "the still sound with its volume swung by the same amount: only the level moves"),
            ))
        }
        sections += knob("HOLD", listOf(0f, 0.25f, 0.5f, 0.75f, 0.95f), { voice, v ->
            "the hand lands after %.2f s".format(java.util.Locale.ROOT, Gyre.dampSeconds(v, Gyre.defaults(voice).getValue("SYMPATHY"))) +
                if (v <= 0f) ": choked" else if (v >= 0.95f) ": open, the note has rung out" else ""
        }, ::write)

        // 7. Together.
        fun corners(a: String, b: String): List<Group> = GyreVoice.entries.map { voice ->
            Group("${voice.name}: $a ${DOT} $b", key = false, clips = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (x, y) ->
                val id = "${tag(voice)}_${a.lowercase()}${fmt(x).trimStart('.')}_${b.lowercase()}${fmt(y).trimStart('.')}"
                write("TOGETHER", id, render(voice, mapOf(a to x, b to y)))
                Clip(id, "$a ${fmt(x)} $b ${fmt(y)}", "the rest at the defaults")
            })
        }
        sections += section(
            "TOGETHER", "THE KNOBS TOGETHER", "each pair at its four corners",
            listOf("SYMPATHY X BODY: A BIGGER BODY FEEDS THE SYMPATHETIC STRINGS MORE", "SYMPATHY X SPIN: THE ROTOR PICKS WHICH SYMPATHETIC STRING ANSWERS"),
            corners("SYMPATHY", "BODY") + corners("SYMPATHY", "SPIN"),
        )

        // 8. The edges.
        val edges = listOf(
            "sym1" to mapOf("SYMPATHY" to 1f), "body1" to mapOf("BODY" to 1f), "spin1" to mapOf("SPIN" to 1f),
            "all1" to mapOf("SYMPATHY" to 1f, "BODY" to 1f, "SPIN" to 1f),
        )
        sections += section(
            "EDGES", "THE EDGES", "every knob at its top, then all of them",
            listOf("BOUNDED BY CONSTRUCTION: NOTHING HERE CAN RUN AWAY"),
            GyreVoice.entries.map { voice ->
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

    /** One knob at [values] on both voices, the rest at the defaults; [extra] may add groups for a voice. */
    private fun knob(
        name: String,
        values: List<Float>,
        describe: (GyreVoice, Float) -> String,
        write: (String, String, Snip) -> Unit,
        extra: (GyreVoice, MutableList<Group>) -> Unit = { _, _ -> },
    ): String {
        val groups = mutableListOf<Group>()
        for (voice in GyreVoice.entries) {
            groups += Group("${voice.name} ${DOT} DEFAULT " + fmt(Gyre.defaults(voice).getValue(name)), key = true, clips = values.map { v ->
                val id = voice.name.lowercase() + "_" + fmt(v).trimStart('.')
                write(name, id, Gyre.render(voice, mapOf(name to v)))
                Clip(id, "$name ${fmt(v)}", describe(voice, v))
            })
            extra(voice, groups)
        }
        return section(name, name, "$name at ${values.size} settings, the other knobs at their defaults", listOf("BOTH VOICES"), groups)
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

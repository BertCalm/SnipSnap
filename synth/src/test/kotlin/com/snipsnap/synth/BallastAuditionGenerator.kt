package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the BALLAST audition (docs/superpowers/specs/2026-10-04-ballast-bass-engine-design.md, the S24 gate)
 * under testkit/ballast-audition/ (gitignored):
 * - the sixteen-pad kit as it lands on the MPC;
 * - for each voice: its default, C1, C2, C3 and C4, a bass line, three velocities, each knob at both ends, the held
 *   loop, and its own eight presets;
 * - the structure taken apart on ROOT, WIRE and PRISM: the bass alone, the structure alone, both, everything, and the
 *   bass stopped dead so the wires, the frame and the glass settle by themselves;
 * - three interactions the design claims, each a 3x3 grid.
 *
 * Clips share one loudness ([AuditionLevel]). It writes `manifest.json`, which the page builds itself from, so the clip
 * list lives here and nowhere else, then copies the listening page from the test resources. Run via
 * `./gradlew :synth:generateBallastAudition`, then publish the folder as the listening artifact. Nothing in BALLAST
 * has been heard by anyone when this is first run.
 */
object BallastAuditionGenerator {

    private class Knob(val name: String, val low: String, val high: String)

    /** The knobs at both ends. TUNE is a note, not a knob to audition (the range group plays it). */
    private val KNOBS = listOf(
        Knob("DRIVE", "a gentle bass: little force into the structure, a rounder filter", "a hard bass: much more force, the filter opens, the pulse narrows and the structure answers loudly"),
        Knob("SYMPATHY", "the wires barely hear the frame and stop quickly", "the wires are tightly coupled to the frame and ring for seconds"),
        Knob("SPAN", "only the strings near the note take part", "all six octaves, the double octave below and the three above, answer"),
        Knob("GLASS", "the tiles are boxed in far from each other: no knocks", "the tiles nearly touch and chatter: a dense, bright rattle"),
        Knob("FRAME", "a stiff, short mount: the structure follows the bass at once", "a soft, long mount: it rocks, lags and keeps moving after the bass moves on"),
        Knob("HOLD", "the shortest gate: 0.6 s", "the held loop: the bass sustains and the structure settles into a repeating pattern"),
    )

    private val BODIES = mapOf(
        BallastVoice.ROOT to "the bass alone-ish: a clean, deep note with the structure as a faint low body",
        BallastVoice.WIRE to "long sympathetic wires: the frame rings the strings, which keep going after the bass",
        BallastVoice.GLINT to "the glass up front: tiles that tap on the bass's slow movement and ring bright",
        BallastVoice.DEEP to "the double-octave below: a heavy, soft-mounted frame and the lowest wires weighted up",
        BallastVoice.BLOOM to "a structure that arrives late: a slow rise, a soft mount, the glass opening after the note",
        BallastVoice.SWARM to "everything at once: hard drive, wide span, loose tiles knocking on a busy frame",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/ballast-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.ballast().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the ballast kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as BallastPatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            Clip(id, "$tag ${patch.name.uppercase()}", patch.voice.displayName + " " + DOT + " " + noteName(Ballast.midiFor(patch.voice, patch.macros.getValue("TUNE"))) + " " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT", display = "THE BALLAST KIT", body = "sixteen pads as the MPC gets them",
                readout = listOf("A01-A04 ROOT UP THE MINOR PENTATONIC FROM C2", "A05-A14 TWO PRESETS EACH OF WIRE, DEEP, PRISM, BLOOM, SWARM", "A15-A16 TWO LONG GATES"),
                groups = listOf(
                    Group("ROOT, THE PENTATONIC", key = true, clips = kitClips.subList(0, 4)),
                    Group("PRESETS", key = true, clips = kitClips.subList(4, 14)),
                    Group("LONG GATES", key = true, clips = kitClips.subList(14, 16)),
                ),
            ),
        )

        for (voice in BallastVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Ballast.defaults(voice)
            fun writeSnip(id: String, snip: Snip) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }
            fun write(id: String, macros: Map<String, Float>) = writeSnip(id, Ballast.render(voice, defaults + macros))

            write("default", emptyMap())
            val groups = mutableListOf(Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", macroLine(voice, defaults)))))

            val range = listOf(0, 12, 24, 36).map { k ->
                val id = "tune_$k"
                write(id, mapOf("TUNE" to k / Ballast.TUNE_SEMITONES.toFloat()))
                Clip(id, noteName(Ballast.midiFor(voice, k / Ballast.TUNE_SEMITONES.toFloat())), if (k == 0) "the bottom of TUNE" else if (k == 36) "the top" else "an octave step")
            }
            groups += Group("THE RANGE", key = true, clips = range)

            val steps = listOf(0, 0, 7, 5, 3, 3, 7, 10)
            val notes = steps.map { s -> Ballast.render(voice, defaults + mapOf("TUNE" to (12 + s) / Ballast.TUNE_SEMITONES.toFloat(), "HOLD" to 0.05f)).samples }
            val spacing = (0.5 * Dsp.RATE).toInt()
            val mix = FloatArray(spacing * (notes.size - 1) + notes.last().size)
            for ((k, n) in notes.withIndex()) for (i in n.indices) mix[k * spacing + i] += n[i]
            writeSnip("phrase", Snip(mix, channels = 1, sampleRate = Dsp.RATE))
            groups += Group("A BASS LINE", key = true, clips = listOf(Clip("phrase", "LINE", "eight notes 0.5 s apart from C2, HOLD .05, each ringing into the next")))

            val patch = BallastPatch("Velocity", voice, defaults)
            val hardReference = patch.render()
            val velocityGain = AuditionLevel.level(hardReference).peak() / hardReference.peak()
            val vel = listOf(0.3f to "SOFT", 0.65f to "MEDIUM", 1f to "HARD").map { (v, label) ->
                val id = "velocity_" + fmt(v).trimStart('.')
                val touched = Velocity.atVelocity(patch, v)
                WavWriter.write(File(dir, "$id.wav"), Snip(touched.samples.map { it * velocityGain }.toFloatArray(), channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_16)
                count++
                Clip(id, label, "velocity ${fmt(v)}: a slower attack, a darker filter and less force into the structure when soft")
            }
            groups += Group("VELOCITY", key = false, clips = vel)

            for (knob in KNOBS) {
                val lo = knob.name.lowercase() + "_0"
                val hi = knob.name.lowercase() + "_1"
                write(lo, mapOf(knob.name to 0f))
                write(hi, mapOf(knob.name to 1f))
                groups += Group(
                    knob.name + " " + DOT + " DEFAULT " + fmt(defaults.getValue(knob.name)), key = false,
                    clips = listOf(Clip(lo, "${knob.name} 0", knob.low), Clip(hi, "${knob.name} 1", knob.high)),
                )
            }

            val presets = BallastPresets.forVoice(voice).map { preset ->
                val id = "preset_" + preset.name.lowercase().replace(' ', '_')
                writeSnip(id, preset.render())
                Clip(id, preset.name, noteName(Ballast.midiFor(voice, preset.macros.getValue("TUNE"))) + " " + DOT + " " + macroLine(voice, preset.macros))
            }
            groups += Group("${voice.displayName}'S OWN PRESETS", key = true, clips = presets)

            sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name, display = voice.displayName, body = BODIES.getValue(voice),
                    readout = listOf(
                        "ROOT " + noteName(Ballast.rootMidi(voice)) + " " + DOT + " 36 SEMITONES OF TRAVEL",
                        "DRIVE " + fmt(defaults.getValue("DRIVE")) + " " + DOT + " SYMPATHY " + fmt(defaults.getValue("SYMPATHY")) + " " + DOT + " GLASS " + fmt(defaults.getValue("GLASS")),
                    ),
                    groups = groups,
                ),
            )
        }

        val partsDir = File(root, "STRUCTURE")
        val partsGroups = listOf(BallastVoice.ROOT, BallastVoice.WIRE, BallastVoice.GLINT).map { voice ->
            val macros = Ballast.defaults(voice) + mapOf("TUNE" to 12 / Ballast.TUNE_SEMITONES.toFloat(), "HOLD" to 0.6f)
            val parts = Ballast.renderParts(voice, macros)
            val off = Ballast.renderParts(voice, macros, sourceOffAt = 1.0)
            val whole = Snip(parts.getValue("all"), channels = 1, sampleRate = Dsp.RATE)
            val partGain = AuditionLevel.level(whole).peak() / whole.peak()
            val tag = voice.name.lowercase()
            val clips = mutableListOf<Clip>()
            fun emit(id: String, name: String, desc: String, samples: FloatArray) {
                WavWriter.write(File(partsDir, "$id.wav"), Snip(samples.map { it * partGain }.toFloatArray(), channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_16)
                count++
                clips += Clip(id, name, desc)
            }
            emit("${tag}_bass", "BASS ONLY", "the oscillators and the filter, heard directly, with the structure muted", parts.getValue("bass"))
            emit("${tag}_structure", "STRUCTURE ONLY", "the wires and the frame alone: what the bass is shaking", parts.getValue("structure"))
            emit("${tag}_both", "BASS + STRUCTURE", "the bass and what it shakes, without the glass", parts.getValue("bass+structure"))
            emit("${tag}_all", "EVERYTHING", "the whole voice at its default, C2, a 0.6 s gate", parts.getValue("all"))
            emit("${tag}_off", "BASS STOPPED AT 1 S", "the oscillators and the force stop dead at one second; the wires, the frame and the tiles settle on their own", off.getValue("all"))
            Group("${voice.displayName} " + DOT + " C2 " + DOT + " DEFAULT", key = true, clips = clips)
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "STRUCTURE", display = "THE STRUCTURE", body = "the same note with the structure taken apart, and with the bass stopped dead",
                readout = listOf("THREE VOICES " + DOT + " C2", "ONE GAIN PER GROUP, SO THE PARTS ADD UP"),
                groups = partsGroups,
            ),
        )

        val gridDir = File(root, "GRIDS")
        val grids = listOf(
            Triple(BallastVoice.GLINT, "DRIVE" to "GLASS", "more force into tiles that sit closer: the knocks go from none to a rattle"),
            Triple(BallastVoice.WIRE, "SYMPATHY" to "SPAN", "how tightly the wires hear the frame, against how many octaves are in the room"),
            Triple(BallastVoice.BLOOM, "FRAME" to "GLASS", "a soft mount moving tiles that are free to move"),
        )
        val gridGroups = grids.map { (voice, pair, what) ->
            val (a, b) = pair
            val clips = listOf(0f, 0.5f, 1f).flatMap { va ->
                listOf(0f, 0.5f, 1f).map { vb ->
                    val id = "${voice.name.lowercase()}_${a.lowercase()}${fmt(va).trimStart('.')}_${b.lowercase()}${fmt(vb).trimStart('.')}"
                    val snip = Ballast.render(voice, Ballast.defaults(voice) + mapOf("TUNE" to 12 / Ballast.TUNE_SEMITONES.toFloat(), a to va, b to vb))
                    WavWriter.write(File(gridDir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                    count++
                    Clip(id, "$a ${fmt(va)} $b ${fmt(vb)}", voice.displayName)
                }
            }
            Group("${voice.displayName}: $a x $b, $what", key = true, clips = clips)
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "GRIDS", display = "THE INTERACTIONS", body = "the three the design claims: each should change the structure, not only the level",
                readout = listOf("EACH 3 x 3 " + DOT + " 0, .5, 1", "C2 " + DOT + " THE REST OF THE KNOBS AT THE VOICE'S DEFAULTS"),
                groups = gridGroups,
            ),
        )

        val manifestFile = File(root, "manifest.json")
        manifestFile.writeText("{\"voices\": [\n$sections\n]}\n")
        val page = BallastAuditionGenerator::class.java.getResourceAsStream("/audition/ballast-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        val pageFile = File(root, "index.html")
        pageFile.outputStream().use { out -> page.use { it.copyTo(out) } }
        // The clip list is written into the page, so opening index.html from this folder plays the clips.
        // A server publish still has manifest.json beside it.
        val html = pageFile.readText()
        val marker = "var INLINE = null;"
        require(html.contains(marker)) { "the listening page has no place to write the clip list" }
        pageFile.writeText(html.replace(marker, "var INLINE = ${manifestFile.readText()};"))
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

    /** The knobs that differ from the voice's defaults, the way the app's sliders read them. */
    private fun macroLine(voice: BallastVoice, macros: Map<String, Float>): String {
        val d = Ballast.defaults(voice)
        val moved = Ballast.macrosFor(voice).map { it.name }.filter { it != "TUNE" && (macros[it] ?: d.getValue(it)) != d.getValue(it) }
        return if (moved.isEmpty()) "every knob at its default" else moved.joinToString(" ") { "$it ${fmt(macros.getValue(it))}" }
    }

    /** A macro value the way the app's sliders read it: `0`, `.35`, `1`. */
    private fun fmt(v: Float) = when {
        v <= 0f -> "0"
        v >= 1f -> "1"
        else -> "." + (v * 100).roundToInt().toString().padStart(2, '0')
    }

    private fun sectionJson(id: String, display: String, body: String, readout: List<String>, groups: List<Group>): String {
        val g = groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { "[${q(it.id)},${q(it.name)},${q(it.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":${grp.key},\"clips\":[$c]}"
        }
        val r = readout.joinToString(",") { q(it) }
        return "{\"id\":${q(id)},\"display\":${q(display)},\"body\":${q(body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

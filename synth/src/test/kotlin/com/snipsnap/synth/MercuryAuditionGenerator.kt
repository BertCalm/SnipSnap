package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the MERCURY audition (docs/superpowers/specs/2026-10-01-mercury-modal-glass-engine-design.md,
 * the S21 gate, the spec's §17 listening set) under testkit/mercury-audition/ (gitignored):
 * - the sixteen-pad kit as it lands on the MPC;
 * - for each voice: its default, the bottom, middle and top of TUNE, a short phrase whose notes ring
 *   into each other, three velocities, each knob at both ends, the spec's dead-zone probes (BEND
 *   .40/.50/.60, WATER 0/.05/.10/.20), and its own eight presets;
 * - the five interactions the design claims, each a 3×3 grid.
 *
 * Clips share one loudness ([AuditionLevel]). It writes `manifest.json`, which the page builds itself
 * from, so the clip list lives here and nowhere else, then copies the listening page from the test
 * resources. Run via `./gradlew :synth:generateMercuryAudition`, then publish the folder as the
 * listening artifact. Nothing in MERCURY has been heard by anyone when this is first run.
 */
object MercuryAuditionGenerator {

    private class Knob(val name: String, val low: String, val high: String)

    /** The knobs at both ends. TUNE is a note, not a knob to audition (the range group plays it). */
    private val KNOBS = listOf(
        Knob("BEND", "concave: the object bends in from below the note, its upper modes pulled one way", "convex: it bends in from above, its upper modes pulled the other way"),
        Knob("RUB", "a tap: one strike, then the object rings and decays", "a rub: a finger on the rim, sustained friction for the whole contact"),
        Knob("WATER", "still: no mass moving, the object exactly as struck", "a lot of water: the mass swirls fast and deep, every mode drifts and damps together"),
        Knob("GLASS", "a damped, flexible, rough surface: short highs, a gritty rub", "clear glass: long, selective ringing, a pure rub"),
        Knob("COUPLE", "the modes independent: the vessel never answers", "strong springs: the vessel blooms after the hit, energy sloshes and beats"),
        Knob("HOLD", "the shortest contact: 0.3 s, then the ring", "the longest: 4 s of contact, then the ring"),
    )

    private val BODIES = mapOf(
        MercuryVoice.PING to "struck glass: a rim tapped once, its modes Rayleigh's thin ring, the vessel answering through the springs",
        MercuryVoice.SING to "rubbed glass: a wet finger on the rim, friction locking the fundamental, the glass harmonica's sustain",
        MercuryVoice.BLADE to "bowed steel: a free-free blade, a gesture bending it into the note, the musical saw's glide",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/mercury-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        // The kit, as it lands: SynthKits.mercury() is the one list of what is on which pad.
        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.mercury().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the mercury kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as MercuryPatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            Clip(id, "$tag ${patch.name.uppercase()}", patch.voice.name + " " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT", display = "THE MERCURY KIT", body = "sixteen pads as the MPC gets them",
                readout = listOf("A01-A08 PING UP THE MINOR PENTATONIC FROM C4", "A09-A12 FOUR SING PRESETS", "A13-A16 FOUR BLADE PRESETS"),
                groups = listOf(
                    Group("PING, THE PENTATONIC", key = true, clips = kitClips.subList(0, 8)),
                    Group("SING PRESETS", key = true, clips = kitClips.subList(8, 12)),
                    Group("BLADE PRESETS", key = true, clips = kitClips.subList(12, 16)),
                ),
            ),
        )

        for (voice in MercuryVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Mercury.defaults(voice)
            fun writeSnip(id: String, snip: Snip) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }
            fun write(id: String, macros: Map<String, Float>) = writeSnip(id, Mercury.render(voice, defaults + macros))

            write("default", emptyMap())
            val groups = mutableListOf(Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", macroLine(voice, defaults)))))

            val range = listOf(0f, 0.5f, 1f).map { t ->
                val id = "tune_" + fmt(t).trimStart('.')
                write(id, mapOf("TUNE" to t))
                Clip(id, noteName(Mercury.midiFor(voice, t)), if (t == 0f) "the bottom of TUNE" else if (t == 1f) "the top" else "the middle, the default")
            }
            groups += Group("THE RANGE", key = true, clips = range)

            // A phrase: the minor pentatonic up and back, each note starting 0.45 s after the last and ringing into the next.
            val steps = listOf(0, 3, 5, 7, 10, 7, 5, 12)
            val notes = steps.map { s -> Mercury.render(voice, defaults + mapOf("TUNE" to (6 + s) / Mercury.TUNE_SEMITONES.toFloat(), "HOLD" to 0.15f)).samples }
            val spacing = (0.45 * Dsp.RATE).toInt()
            val mix = FloatArray(spacing * (notes.size - 1) + notes.last().size)
            for ((k, n) in notes.withIndex()) for (i in n.indices) mix[k * spacing + i] += n[i]
            writeSnip("phrase", Snip(mix, channels = 1, sampleRate = Dsp.RATE))
            groups += Group("A PHRASE", key = true, clips = listOf(Clip("phrase", "PENTATONIC", "eight notes 0.45 s apart, HOLD .15, each ringing into the next")))

            val patch = MercuryPatch("Velocity", voice, defaults)
            val vel = listOf(0.3f to "SOFT", 0.65f to "MEDIUM", 1f to "HARD").map { (v, label) ->
                val id = "velocity_" + fmt(v).trimStart('.')
                writeSnip(id, Velocity.atVelocity(patch, v))
                Clip(id, label, "velocity ${fmt(v)}: softened (no knob is wired to velocity yet, the design's decision 8)")
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

            val bendProbe = listOf(0.4f, 0.5f, 0.6f).map { b ->
                val id = "bend_probe_" + fmt(b).trimStart('.')
                write(id, mapOf("BEND" to b))
                Clip(id, "BEND ${fmt(b)}", if (b == 0.5f) "flat: no gesture" else "a small bend either side of flat")
            }
            groups += Group("BEND NEAR THE MIDDLE (NO DEAD ZONE?)", key = false, clips = bendProbe)
            val waterProbe = listOf(0f, 0.05f, 0.1f, 0.2f).map { w ->
                val id = "water_probe_" + fmt(w).trimStart('.').ifEmpty { "0" }
                write(id, mapOf("WATER" to w))
                Clip(id, "WATER ${fmt(w)}", if (w == 0f) "still" else "a little water: should already move")
            }
            groups += Group("A LITTLE WATER (NO DEAD ZONE?)", key = false, clips = waterProbe)

            val presets = MercuryPresets.forVoice(voice).map { preset ->
                val id = "preset_" + preset.name.lowercase().replace(' ', '_')
                writeSnip(id, preset.render())
                Clip(id, preset.name, noteName(Mercury.midiFor(voice, preset.macros.getValue("TUNE"))) + " " + DOT + " " + macroLine(voice, preset.macros))
            }
            groups += Group("${voice.name}'S OWN PRESETS", key = true, clips = presets)

            sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name, display = voice.name, body = BODIES.getValue(voice),
                    readout = listOf(
                        "ROOT " + noteName(Mercury.rootMidi(voice)) + " " + DOT + " 24 SEMITONES OF TRAVEL",
                        "RUB " + fmt(defaults.getValue("RUB")) + " " + DOT + " GLASS " + fmt(defaults.getValue("GLASS")) + " " + DOT + " BEND " + fmt(defaults.getValue("BEND")),
                    ),
                    groups = groups,
                ),
            )
        }

        // The five interactions the design claims (§10), each a 3×3 grid on the voice that shows it best.
        val gridDir = File(root, "GRIDS")
        val grids = listOf(
            Triple(MercuryVoice.BLADE, "BEND" to "WATER", "the same curvature under moving loading"),
            Triple(MercuryVoice.SING, "RUB" to "GLASS", "friction from broad and damped to selective, ringing sustain"),
            Triple(MercuryVoice.PING, "WATER" to "COUPLE", "moving loading changing which modes trade energy"),
            Triple(MercuryVoice.BLADE, "BEND" to "COUPLE", "deformation changing partial capture and beating"),
            Triple(MercuryVoice.SING, "RUB" to "WATER", "motion changing the contact as well as the pitch"),
        )
        val gridGroups = grids.map { (voice, pair, what) ->
            val (a, b) = pair
            val clips = listOf(0f, 0.5f, 1f).flatMap { va ->
                listOf(0f, 0.5f, 1f).map { vb ->
                    val id = "${voice.name.lowercase()}_${a.lowercase()}${fmt(va).trimStart('.')}_${b.lowercase()}${fmt(vb).trimStart('.')}"
                    val snip = Mercury.render(voice, Mercury.defaults(voice) + mapOf(a to va, b to vb))
                    WavWriter.write(File(gridDir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
                    count++
                    Clip(id, "$a ${fmt(va)} $b ${fmt(vb)}", voice.name)
                }
            }
            Group("${voice.name}: $a × $b, $what", key = true, clips = clips)
        }
        sections.append(",\n")
        sections.append(
            sectionJson(
                id = "GRIDS", display = "THE INTERACTIONS", body = "the five the design claims: each should change the object, not only its pitch or level",
                readout = listOf("EACH 3 × 3 " + DOT + " 0, .5, 1", "THE REST OF THE KNOBS AT THE VOICE'S DEFAULTS"),
                groups = gridGroups,
            ),
        )

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = MercuryAuditionGenerator::class.java.getResourceAsStream("/audition/mercury-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

    /** The knobs that differ from the voice's defaults, the way the app's sliders read them. */
    private fun macroLine(voice: MercuryVoice, macros: Map<String, Float>): String {
        val d = Mercury.defaults(voice)
        val moved = Mercury.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
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

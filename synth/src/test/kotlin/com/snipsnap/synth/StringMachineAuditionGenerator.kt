package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the string-machine gate (the ARCO design's PR-E2,
 * docs/superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md,
 * "STRING MACHINE without ARCO") under testkit/stringmachine-audition/
 * (gitignored): the four presets that land with an ENSEMBLE chain
 * ([StringMachine]), each as three things and a control.
 *
 * - the bare voice, mono - what the SYNTH panel's instant loop played and
 *   SEND TO PAD landed before this change;
 * - the pad as it lands now, stereo, with its mono fold beside it (the fold
 *   is what the phone plays, and what the panel's instant loop now plays),
 *   captioned by [FoldMeter], the meter [StringMachineTest] pins;
 * - the control: the sound the owner approved in PR-E1, VELVET's FANFARE or
 *   RESIN's WIDE SECTION through ENSEMBLE at its defaults, so "is this a
 *   better string machine than the chorus it already had" has a clip to
 *   be answered against.
 *
 * Clips share one loudness ([AuditionLevel]); a fold is the fold of its
 * levelled stereo file, not re-levelled. The preset values are candidates
 * chosen from measurement, not from listening, so this page is where they
 * are first heard. Writes `manifest.json`, which the page builds itself
 * from, then copies the listening page from the test resources. Run via
 * `./gradlew :synth:generateStringMachineAudition`, then publish the folder
 * as the listening artifact.
 */
object StringMachineAuditionGenerator {

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    /** A string machine and the E1 sound it is heard against. */
    private class Machine(val id: String, val engine: String, val voice: String, val name: String, val control: String, val body: String)

    private val MACHINES = listOf(
        Machine(
            "STRING_MACHINE", VelvetPatch.ENGINE, "BRASS", "STRING MACHINE", "FANFARE",
            "the classic: VELVET's saw stack with SQUEEZE at its floor (the least resonance and the shallowest filter sweep it has) and left to ring, through ENSEMBLE's own defaults. The question is whether this is the string machine FANFARE was almost being, or just a longer FANFARE",
        ),
        Machine(
            "THIN_STRINGS", VelvetPatch.ENGINE, "BRASS", "THIN STRINGS", "FANFARE",
            "the same voice pitched up and slimmed (a narrower detune), through a shallower, slightly quicker swing: the section as shimmer rather than lushness. It should be clearly not STRING MACHINE, or one of the two is redundant",
        ),
        Machine(
            "WIDE_STRINGS", ResinPatch.ENGINE, "BRASS", "WIDE STRINGS", "WIDE SECTION",
            "RESIN's stacked oscillators through the ladder at STACK .90 with the wah and the snap out. It is the widest source there is, so its pair barely correlates: this is the one whose fold to mono is worth the closest listen",
        ),
        Machine(
            "DARK_STRINGS", ResinPatch.ENGINE, "BRASS", "DARK STRINGS", "WIDE SECTION",
            "a low, closed tone under a slower, deeper swing so the swell has room to be felt. It should read as the same instrument as WIDE STRINGS with the lights down, not as a different one",
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/stringmachine-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        for (m in MACHINES) {
            val dir = File(root, m.id)
            dir.mkdirs()
            val patch = requireNotNull(Presets.byName(m.engine, m.voice, m.name)) { "${m.name} is not on the ${m.engine} ${m.voice} roster" }
            val chain = requireNotNull(Presets.landingFor(m.engine, m.voice, patch.macros)) { "${m.name} lands dry" }
            val controlPatch = requireNotNull(Presets.byName(m.engine, m.voice, m.control)) { "${m.control} is not on the ${m.engine} ${m.voice} roster" }

            fun write(id: String, snip: Snip): Snip {
                val levelled = AuditionLevel.level(snip)
                WavWriter.write(File(dir, "$id.wav"), levelled, WavWriter.BitDepth.PCM_16)
                count++
                return levelled
            }

            /** The fold clip for a stereo [levelled] file, its caption [FoldMeter]'s, the same meter the test pins. */
            fun foldClip(id: String, levelled: Snip, what: String): Clip {
                val foldId = id + "_fold"
                WavWriter.write(File(dir, "$foldId.wav"), Cleanup.toMono(levelled), WavWriter.BitDepth.PCM_16)
                count++
                return Clip(foldId, "\u21B3 ITS FOLD", "$what averaged to mono. Measured: ${FoldMeter.caption(FoldMeter.report(levelled))}")
            }

            val dry = patch.render()
            write("dry", dry)
            val landed = write("landed", PadRecipe(patch, chain).render())
            val controlDry = controlPatch.render()
            val control = write("control", Ensemble.process(controlDry, Ensemble.defaults()))

            val ensemble = requireNotNull(chain.section("ensemble"))
            val groups = listOf(
                Group(
                    "THE PAD AS IT LANDS NOW", key = true,
                    clips = listOf(
                        Clip("landed", "LANDED", "what SEND TO PAD now makes: the voice through ENSEMBLE, stereo, one file"),
                        foldClip("landed", landed, "the same file"),
                    ),
                ),
                Group(
                    "BEFORE THIS CHANGE", key = false,
                    clips = listOf(Clip("dry", "THE BARE VOICE", "mono, no section: what the panel's instant loop played and SEND TO PAD landed until now")),
                ),
                Group(
                    "THE CONTROL: WHAT YOU APPROVED IN PR-E1", key = false,
                    clips = listOf(
                        Clip("control", "${m.control} + ENSEMBLE", "${m.engine} ${m.voice} ${m.control} through ENSEMBLE at its defaults, stereo: the sound a string-machine preset has to beat"),
                        foldClip("control", control, "the same file"),
                    ),
                ),
            )

            val hz = Pitch.detect(dry)?.hz
            val rootLabel = if (hz == null) "UNPITCHED" else "ROOT ${Scales.nameOf(Scales.hzToMidi(hz).roundToInt())} (${hz.roundToInt()} HZ)"
            val readout = listOf(
                "${m.engine} ${m.voice} " + patch.macros.entries.joinToString(" ") { "${it.key} ${fmt(it.value)}" },
                "LANDS WITH ENSEMBLE " + DOT + " " + ensemble.entries.joinToString(" ") { "${it.key} ${fmt(it.value)}" },
                "$rootLabel $DOT ${"%.2f".format(dry.durationSeconds)} S $DOT SHORT AND DECAYING, NOT A HELD PAD",
            )
            if (sections.isNotEmpty()) sections.append(",\n")
            sections.append(sectionJson(m.id, m.name, m.body, readout, groups))
        }

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = StringMachineAuditionGenerator::class.java.getResourceAsStream("/audition/stringmachine-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "\u00B7"

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

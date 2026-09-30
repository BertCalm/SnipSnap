package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the ENSEMBLE gate (the ARCO design's PR-E1,
 * docs/superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md,
 * "ENSEMBLE, the rack section") under testkit/ensemble-audition/
 * (gitignored): four sources — a THUMP kick, VELVET's FANFARE, RESIN's WIDE
 * SECTION and a VOX CHOIR — each dry, then at DEPTH .25 / .50 / 1 with
 * WIDTH 0 and WIDTH 1, every stereo clip with its mono fold beside it (the
 * CHOIR's dry and WIDTH 0 clips included, since a choir is stereo before
 * the section touches it), because the phone plays the fold on the instant
 * loop and the fold is what a pad is heard as. Clips share one loudness ([AuditionLevel]); a fold is
 * the fold of its levelled stereo file, not re-levelled, so what the fold
 * loses is audible rather than hidden, and its caption is [FoldMeter]'s —
 * the meter [FxTest] pins, so the page's numbers are the test's. Writes `manifest.json`, which the
 * page builds itself from, then copies the listening page from the test
 * resources. Run via `./gradlew :synth:generateEnsembleAudition`, then
 * publish the folder as the listening artifact.
 *
 * Pass rule, from the design: FANFARE through ENSEMBLE reads "string
 * machine" to the owner, and the fold does not pump past what the printed
 * ripple says.
 */
object EnsembleAuditionGenerator {

    private class Source(val id: String, val display: String, val body: String, val readout: List<String>, val render: () -> Snip)
    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    private val DEPTHS = listOf(0.25f, 0.5f, 1f)

    private val SOURCES = listOf(
        Source(
            "KICK", "THUMP KICK",
            "the identity check: a kick through the section must still be a kick, and three copies of a 45 Hz thump a few milliseconds apart should stay coherent where a kick lives",
            listOf("THE CRUNCH RULE: IDENTITY SURVIVES", "A FOLD THAT PUMPS HERE FAILS"),
        ) { Thump.render(ThumpVoice.KICK) },
        Source(
            "FANFARE", "VELVET BRASS · FANFARE",
            "the string machine's own source: a saw under a filter. Through ENSEMBLE this is the pass rule — does it read as a section of strings, and at which DEPTH",
            listOf("THE PASS RULE", "VELVET BRASS, FANFARE, AS SHIPPED"),
        ) { VelvetPresets.forVoice(VelvetVoice.BRASS).first { it.name == "FANFARE" }.render() },
        Source(
            "WIDE_SECTION", "RESIN BRASS · WIDE SECTION",
            "the other brass: RESIN's stacked oscillators through the ladder. A second candidate for the string-machine presets of PR-E2",
            listOf("RESIN BRASS, WIDE SECTION, AS SHIPPED"),
        ) { ResinPresets.forVoice(ResinVoice.BRASS).first { it.name == "WIDE SECTION" }.render() },
        Source(
            "CHOIR", "VOX CHOIR",
            "a source that is already stereo: each channel goes through its own line and taps, so the image must survive. WIDTH 0 here is two mono ensembles, one per side",
            listOf("STEREO IN, STEREO OUT", "THE IMAGE MUST SURVIVE"),
        ) { Vox.render(VoxVoice.CHOIR) },
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/ensemble-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        for (source in SOURCES) {
            val dir = File(root, source.id)
            val dry = source.render()
            fun write(id: String, snip: Snip): Snip {
                val levelled = AuditionLevel.level(snip)
                WavWriter.write(File(dir, "$id.wav"), levelled, WavWriter.BitDepth.PCM_16)
                count++
                return levelled
            }
            fun writeFold(id: String, levelledStereo: Snip) {
                WavWriter.write(File(dir, "$id.wav"), Cleanup.toMono(levelledStereo), WavWriter.BitDepth.PCM_16)
                count++
            }

            /** The fold clip for a stereo [levelled] file, or nothing for a mono one; its caption is [FoldMeter]'s, the same meter the test pins. */
            fun foldClipFor(id: String, levelled: Snip, what: String): List<Clip> {
                if (levelled.channels != 2) return emptyList()
                val foldId = id + "_fold"
                writeFold(foldId, levelled)
                return listOf(Clip(foldId, "↳ ITS FOLD", "$what averaged to mono — what the phone plays. Measured: ${FoldMeter.caption(FoldMeter.report(levelled))}"))
            }

            val levelledDry = write("dry", dry)
            val chDry = if (dry.channels == 2) "stereo" else "mono"
            val groups = mutableListOf(
                Group(
                    "DRY", key = true,
                    clips = listOf(Clip("dry", "DRY", "the source as it ships, $chDry, no section")) + foldClipFor("dry", levelledDry, "the source itself"),
                ),
            )

            val monoClips = DEPTHS.flatMap { depth ->
                val id = "depth_${tag(depth)}_width_0"
                val out = write(id, Ensemble.process(dry, mapOf("DEPTH" to depth, "WIDTH" to 0f)))
                listOf(Clip(id, "DEPTH ${fmt(depth)} · WIDTH 0", "the three taps summed to ${if (out.channels == 2) "each of the source's two channels — two mono ensembles, one per side" else "one channel"}; the WAV stays $chDry")) +
                    foldClipFor(id, out, "the same file")
            }
            groups += Group(if (dry.channels == 2) "WIDTH 0 · TWO MONO ENSEMBLES, ONE PER SIDE" else "WIDTH 0 · THE MONO ENSEMBLE", key = false, clips = monoClips)

            val wideClips = mutableListOf<Clip>()
            for (depth in DEPTHS) {
                val id = "depth_${tag(depth)}_width_1"
                val out = write(id, Ensemble.process(dry, mapOf("DEPTH" to depth, "WIDTH" to 1f)))
                val (slow, fast) = Ensemble.peakCents(depth, 1f)
                val weights = "%.2f/%.2f/%.2f".format(Ensemble.WIDE_X, Ensemble.WIDE_Y, Ensemble.WIDE_Z)
                val pair = if (dry.channels == 2) {
                    "each channel through its own line and taps, the left's weighted L $weights over its taps and the right's mirrored"
                } else {
                    "the stereo pair (L $weights over the taps, R mirrored)"
                }
                wideClips += Clip(
                    id, "DEPTH ${fmt(depth)} · WIDTH 1",
                    "$pair; per tap up to ${slow.roundToInt()} c on the swell and ${fast.roundToInt()} c on the shimmer",
                )
                wideClips += foldClipFor(id, out, "the same file")
            }
            groups += Group("WIDTH 1 · THE STEREO PAIR, EACH WITH ITS FOLD", key = true, clips = wideClips)

            if (sections.isNotEmpty()) sections.append(",\n")
            sections.append(
                sectionJson(
                    id = source.id, display = source.display, body = source.body,
                    readout = source.readout + listOf(
                        "RATE .50 (%.2f / %.2f HZ) ".format(Ensemble.SLOW_HZ, Ensemble.FAST_HZ) + DOT + " TONE %.1f KHZ ".format(Ensemble.TONE_HZ / 1000f) + DOT + " 100% WET",
                        "ONE-SHOTS ONLY: KEEP A LOOP DRY, THE WRAP WOULD TICK",
                    ),
                    groups = groups,
                ),
            )
        }

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = EnsembleAuditionGenerator::class.java.getResourceAsStream("/audition/ensemble-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private const val DOT = "·"

    private fun tag(v: Float): String = (v * 100).roundToInt().toString().padStart(3, '0')

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

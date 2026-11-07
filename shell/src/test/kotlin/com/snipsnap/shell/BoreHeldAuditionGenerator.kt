package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.BoreKeys
import com.snipsnap.synth.BorePresets
import com.snipsnap.synth.BoreVoice
import com.snipsnap.synth.KeyNote
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/**
 * BORE held, for the ear: each voice's LOOP preset (FLUTE STEADY LOOP, SAX SOLO LOOP) at its lowest, middle and highest
 * zone, played as a key held for [HOLD_SECONDS] and released, through the preview's own transport ([BorePadMaker.play],
 * the JVM sample player): the hard layer, the soft layer, and the loop-only zone [LoopPadMaker] made for BORE until
 * 2026-11-06 at the same note, for the A/B. Every clip is played at the same velocity ([VELOCITY]), so the two layers
 * differ by timbre alone and stand at the loop-only zone's gain; on the MPC a soft key would also be quieter by the
 * player's velocity gain. The player's output sits about 11 dB under the samples (velocity times its voice level), so
 * every clip is raised by one common gain, the most that keeps the loudest peak of all of them at [PEAK_DBFS]: each clip's
 * level against every other is the player's.
 *
 * Writes `<voice>-<note>-held-hard.wav`, `-held-soft.wav` and `-loop-only.wav` (mono, 44.1 kHz, PCM_24) and `clips.txt`,
 * which says what each clip is, where its loop wraps and where its blend sits, into the first argument. Run via
 * `./gradlew :shell:generateBoreHeldAudition` (`-PboreHeldOutput=<dir>` to write elsewhere). It lives in `:shell`, beside
 * the maker, because `:synth`'s `generateBoreAudition` cannot see `:shell`. Nothing here has been heard.
 */
object BoreHeldAuditionGenerator {

    const val HOLD_SECONDS = 8f
    const val VELOCITY = .8f
    const val PEAK_DBFS = -1.0

    private class Clip(val name: String, val snip: Snip, val about: String, val heldFrom: Int)

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/bore-held-audition").also { it.mkdirs() }
        val clips = ArrayList<Clip>()
        val heads = LinkedHashMap<Int, String>()
        for ((voice, preset) in listOf(BoreVoice.FLUTE to "STEADY LOOP", BoreVoice.SAX to "SOLO LOOP")) {
            val patch = BorePresets.forVoice(voice).first { it.name == preset }
            val spec = BorePadMaker.Spec(patch)
            val loopSpec = LoopPadMaker.Spec(patch, spec.releaseSeconds)
            val midis = BorePadMaker.zoneMidis(spec)
            val breaths = BoreKeys.layerBreaths(voice, patch.macros)
            heads[clips.size] = "$voice, preset $preset ${patch.macros}"
            for (midi in listOf(midis.first(), midis[midis.size / 2], midis.last())) {
                val stem = "${voice.name.lowercase()}-${Scales.nameOf(midi)}"
                for ((layer, kind) in listOf(1 to "hard", 0 to "soft")) {
                    val t0 = System.nanoTime()
                    val note = BoreKeys.padLayer(voice, patch.macros, midi, breaths[layer])
                    val ms = (System.nanoTime() - t0) / 1e6
                    val clip = BorePadMaker.play(note, midi, HOLD_SECONDS, VELOCITY, spec.releaseSeconds, "BORE held audition")
                    clips += Clip("$stem-held-$kind.wav", clip,
                        "the $kind layer (BREATH %.2f), rendered in %.0f ms. ".format(java.util.Locale.ROOT, breaths[layer], ms) + describe(note),
                        note.loopStartFrame.toInt())
                }
                val loop = LoopPadMaker.renderZone(loopSpec, midi)
                val clip = BorePadMaker.play(loop, midi, HOLD_SECONDS, VELOCITY, spec.releaseSeconds, "BORE loop-only audition")
                val period = loop.snip.frameCount - loop.loopStartFrame.toInt()
                clips += Clip("$stem-loop-only.wav", clip,
                    "the loop-only zone (HOLD 1, BREATH %.2f): a %.3f s loop from the first frame, no attack, repeating at %s s. ".format(
                        java.util.Locale.ROOT, breaths[1], period.toDouble() / rate, repeats(period, period)),
                    0)
            }
        }

        val peak = clips.maxOf { c -> c.snip.samples.maxOf { abs(it) } }
        val gain = (Math.pow(10.0, PEAK_DBFS / 20) / peak).toFloat()
        val notes = StringBuilder()
        notes.appendLine("BORE held keygroup: audition clips (nothing here has been heard)")
        notes.appendLine()
        notes.appendLine("Each clip is one key held for %.0f s, then released over %.2f s (RELEASE's default), played by the JVM sample player".format(java.util.Locale.ROOT, HOLD_SECONDS, BorePadMaker.RELEASE.default))
        notes.appendLine("at velocity %.1f, then raised by one gain for every clip (%+.1f dB, the loudest peak of all at %.0f dBFS), so the levels between clips are the player's.".format(java.util.Locale.ROOT, VELOCITY, 20 * log10(gain.toDouble()), PEAK_DBFS))
        notes.appendLine("held-hard and held-soft are the new instrument's two layers: the note's own attack, then the loop.")
        notes.appendLine("loop-only is the instrument BORE had until now: its HOLD LOOP from the first frame, no attack. All three play the same patch.")
        notes.appendLine("Both layers are levelled to the same loudness on their loops, so they differ by timbre here; on the MPC a soft key is quieter too.")
        notes.appendLine("What to listen for: the attack, and the first pass from it into the loop at 1.80 s (nothing should change there);")
        notes.appendLine("each wrap, and the 0.3 s before it, where the loop's end fades into the audio before the loop start (the breath dips there);")
        notes.appendLine("whether the same breath and vibrato coming round every 1.9 to 2.1 s can be heard; and how far apart the soft and hard layers are.")
        clips.forEachIndexed { i, c ->
            heads[i]?.let { notes.appendLine().appendLine(it) }
            val scaled = Snip(FloatArray(c.snip.samples.size) { c.snip.samples[it] * gain }, 1, c.snip.sampleRate)
            WavWriter.write(File(out, c.name), scaled, WavWriter.BitDepth.PCM_24)
            notes.appendLine("  ${c.name}: ${c.about}${levels(scaled, c.heldFrom)}")
        }
        File(out, "clips.txt").writeText(notes.toString())
        print(notes)
        println("wrote ${clips.size} clips and clips.txt under ${out.absolutePath}")
    }

    private val rate = WavWriter.MPC_SAMPLE_RATE

    /** Where a held zone's loop starts, how long it is, when the held key wraps (the file's end, then every loop) and where its blend is. */
    private fun describe(note: KeyNote): String {
        val start = note.loopStartFrame.toInt()
        val size = note.snip.frameCount
        val loop = size - start
        return "Attack and first pass to %.2f s, then a %.3f s loop; the key wraps at %s s, each wrap closing a 0.3 s blend. ".format(
            java.util.Locale.ROOT, start.toDouble() / rate, loop.toDouble() / rate, repeats(size, loop))
    }

    /** The seconds, inside the hold, at which a read that reaches [first] frames and then repeats every [every] wraps. */
    private fun repeats(first: Int, every: Int): String {
        val hold = (HOLD_SECONDS * rate).toInt()
        return generateSequence(first) { it + every }.takeWhile { it < hold }.joinToString(", ") { "%.2f".format(java.util.Locale.ROOT, it.toDouble() / rate) }
    }

    /** The clip's peak and the loudness of its held part from [from] (the loop start) to the release, in dBFS. */
    private fun levels(clip: Snip, from: Int): String {
        val hold = (HOLD_SECONDS * rate).toInt()
        val loud = Loudness.of(clip.samples.copyOfRange(from, hold), 1, rate)
        return "Peak %.1f dBFS, held loudness %.1f dBFS.".format(java.util.Locale.ROOT, 20 * log10(clip.samples.maxOf { abs(it) } + 1e-12), 20 * log10(loud + 1e-12))
    }
}

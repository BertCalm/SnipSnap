package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.BallastPatch
import com.snipsnap.synth.BallastPresets
import com.snipsnap.synth.BallastVoice
import com.snipsnap.synth.Keys
import java.io.File
import java.util.Locale

/**
 * BALLAST held, packaged to play: four keygroup instruments written by the production exporter
 * ([BallastPadMaker.export], both MPC generations and the phone's sidecar), and for each one two previews played
 * through the phone's own sample player ([InstrumentEngine]): one key held for nine seconds, so the loop repeats
 * and the wrap is heard, and a walk up two octaves, so a zone boundary that jumps in tone is heard.
 *
 * `./gradlew :shell:generateBallastKeys` writes `testkit/ballast-keys/` (gitignored).
 */
object BallastKeysGenerator {

    private const val RATE = 44_100
    private const val RELEASE_SECONDS = 0.6f

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "testkit/ballast-keys").canonicalFile
        out.deleteRecursively()
        val mpc = File(out, "mpc").apply { mkdirs() }
        val previews = File(out, "previews").apply { mkdirs() }

        val picks: List<BallastPatch> = listOf(
            BallastPresets.forVoice(BallastVoice.ROOT).single { it.name == "TIGHT BASE" },
            BallastPresets.forVoice(BallastVoice.ROOT).single { it.name == "NIGHT DRIVE" },
            BallastPresets.forVoice(BallastVoice.DEEP).first(),
            BallastPresets.forVoice(BallastVoice.WIRE).first(),
        )
        val lines = StringBuilder()
        for (patch in picks) {
            val name = "BALLAST ${patch.name}"
            val spec = BallastPadMaker.Spec(patch.voice, patch.macros, RELEASE_SECONDS)
            val midis = BallastPadMaker.zoneMidis(spec)
            val started = System.nanoTime()
            val notes = midis.parallelStream().map { BallastPadMaker.renderZone(spec, it) }.toList()
            val seconds = (System.nanoTime() - started) / 1e9
            BallastPadMaker.export(name, spec, notes, mpc)

            val (sidecar, instrument) = InstrumentStore.list(mpc).single { it.second.name == name }
            val loaded = InstrumentEngine.Loaded(
                instrument, RATE, instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples },
            )
            val stem = patch.name.lowercase(Locale.ROOT).replace(' ', '_')
            WavWriter.write(File(previews, "${stem}_held_Fs2.wav"), levelled(held(loaded, 42)), WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(previews, "${stem}_walk_C1_C3.wav"), levelled(walk(loaded, 24..48)), WavWriter.BitDepth.PCM_16)

            val attack = notes.map { it.loopStartFrame.toDouble() / RATE }
            val total = notes.sumOf { it.snip.frameCount.toLong() }
            val worstSeam = notes.maxOf { Keys.seamError(it.snip.samples, it.loopStartFrame.toInt()) }
            val line = "%-22s %-5s zones %d, attack %.1f-%.1f s, %.1f s of audio, worst seam %.1e, rendered in %.1f s".format(
                Locale.ROOT, name, patch.voice.name, midis.size, attack.min(), attack.max(), total.toDouble() / RATE, worstSeam, seconds,
            )
            println(line)
            lines.append(line).append('\n')
        }
        File(out, "README.txt").writeText(
            """
            |BALLAST keys: four BALLAST sounds as MPC keygroup instruments. Made 2026-10-24 by :shell:generateBallastKeys.
            |
            |mpc/       copy to the MPC. Each instrument has thirteen zones, a minor third apart, C1 to C4.
            |           Each zone plays the note's own onset and the structure settling, then loops the settled bass.
            |           One velocity layer, rendered at full touch. RELEASE ${RELEASE_SECONDS} s.
            |previews/  the same files played through the phone's sample player: one key held 9 s, and a walk C1 to C3.
            |           Previews are brought to one loudness; the files in mpc/ are as exported.
            |           Each zone is levelled on its first 0.85 s, so short notes match across zones and a long hold
            |           can sit a few dB above or below its neighbour.
            |
            |Listen on monitors or headphones, not a phone speaker. Three questions:
            |Q1  Held: is this the BALLAST you liked, with its onset intact?
            |Q2  Hold a key past about 6 s: is there a click or a pump each time the loop comes round?
            |Q3  Walk up the keyboard: does any step between zones jump in tone?
            |
            |$lines
            """.trimMargin(),
        )
        println("Wrote ${out.path}")
    }

    private fun held(loaded: InstrumentEngine.Loaded, midi: Int): Snip {
        val engine = InstrumentEngine(loaded, RATE)
        check(engine.noteOn(midi))
        val a = render(engine, 9f)
        engine.noteOff(midi)
        return a + render(engine, RELEASE_SECONDS + 0.4f)
    }

    private fun walk(loaded: InstrumentEngine.Loaded, notes: IntRange): Snip {
        val engine = InstrumentEngine(loaded, RATE)
        var all = Snip(FloatArray(0), 2, RATE)
        for (n in notes) {
            check(engine.noteOn(n))
            all += render(engine, 0.45f)
            engine.noteOff(n)
            all += render(engine, 0.15f)
        }
        return all + render(engine, RELEASE_SECONDS + 0.4f)
    }

    private operator fun Snip.plus(other: Snip) = Snip(samples + other.samples, 2, RATE)

    /** The house melodic loudness (Dsp.MELODIC_LOUDNESS_TARGET), so every preview plays at one level whatever the player's own headroom. */
    private const val PREVIEW_LOUDNESS = 0.1834f

    private fun levelled(s: Snip): Snip {
        val loud = Loudness.of(s)
        if (loud <= 1e-6f) return s
        var g = PREVIEW_LOUDNESS / loud
        val peak = s.samples.maxOf { kotlin.math.abs(it) }
        if (peak * g > 0.99f) g = 0.99f / peak
        return Snip(FloatArray(s.samples.size) { s.samples[it] * g }, s.channels, s.sampleRate)
    }

    private fun render(engine: InstrumentEngine, seconds: Float): Snip {
        val frames = (seconds * RATE).toInt()
        val out = FloatArray(frames * 2)
        val block = FloatArray(512)
        var at = 0
        while (at < frames) {
            val n = minOf(256, frames - at)
            engine.render(block, n)
            System.arraycopy(block, 0, out, at * 2, n * 2)
            at += n
        }
        return Snip(out, 2, RATE)
    }
}

package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Keel
import com.snipsnap.synth.KeelVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import java.io.File
import java.util.Locale

/**
 * KEEL held, packaged to play (spec section 17, R2's gate: the owner plays the keygroup). Four keygroup
 * instruments written by the production exporter ([KeelPadMaker.export], both MPC generations and the phone's
 * sidecar): each voice at its defaults and at WEIGHT 1, the top of the body (section 15, risk 10). These are the
 * section 12 defaults, not the factory presets (`KeelPresets`). For each, previews played through the phone's own sample
 * player ([InstrumentEngine]): C2 held to its loop's start and twice round, so the wrap is heard twice, and a walk
 * up two octaves, so a zone boundary that jumps is heard; for UPRIGHT also C1 held, where the sustain's lift is
 * largest.
 *
 * `./gradlew :shell:generateKeelKeys` writes `testkit/keel-keys/` (gitignored).
 */
object KeelKeysGenerator {

    private const val RATE = 44_100
    private const val RELEASE_SECONDS = 0.6f

    private class Pick(val name: String, val voice: KeelVoice, val macros: Map<String, Float>, val heldMidis: List<Int>)

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "testkit/keel-keys").canonicalFile
        out.deleteRecursively()
        val mpc = File(out, "mpc").apply { mkdirs() }
        val previews = File(out, "previews").apply { mkdirs() }

        val picks = listOf(
            Pick("KEEL REDUCE", KeelVoice.REDUCE, Keel.defaults(KeelVoice.REDUCE), listOf(36)),
            Pick("KEEL REDUCE HEAVY", KeelVoice.REDUCE, Keel.defaults(KeelVoice.REDUCE) + ("WEIGHT" to 1f), listOf(36)),
            Pick("KEEL UPRIGHT", KeelVoice.UPRIGHT, Keel.defaults(KeelVoice.UPRIGHT), listOf(36, 24)),
            Pick("KEEL UPRIGHT HEAVY", KeelVoice.UPRIGHT, Keel.defaults(KeelVoice.UPRIGHT) + ("WEIGHT" to 1f), listOf(36)),
        )
        val lines = StringBuilder()
        for (pick in picks) {
            val spec = KeelPadMaker.Spec(pick.voice, pick.macros, RELEASE_SECONDS)
            val midis = KeelPadMaker.zoneMidis(spec)
            val started = System.nanoTime()
            val notes = midis.map { KeelPadMaker.renderZone(spec, it) }
            val seconds = (System.nanoTime() - started) / 1e9
            KeelPadMaker.export(pick.name, spec, notes, mpc)

            val (sidecar, instrument) = InstrumentStore.list(mpc).single { it.second.name == pick.name }
            val loaded = InstrumentEngine.Loaded(
                instrument, RATE, instrument.zones.map { WavReader.read(File(sidecar.parentFile, it.sample)).samples },
            )
            val stem = pick.name.removePrefix("KEEL ").lowercase(Locale.ROOT).replace(' ', '_')
            for (midi in pick.heldMidis) {
                val zone = notes[midis.indexOf(midi)]
                WavWriter.write(File(previews, "${stem}_held_${Scales.nameOf(midi)}.wav"), levelled(held(loaded, midi, zone)), WavWriter.BitDepth.PCM_16)
            }
            WavWriter.write(File(previews, "${stem}_walk_C1_C3.wav"), levelled(walk(loaded, 24..48)), WavWriter.BitDepth.PCM_16)

            val total = notes.sumOf { it.snip.frameCount.toLong() }
            val worstSeam = notes.maxOf { Keys.seamError(it.snip.samples, it.loopStartFrame.toInt()) }
            val line = "%-19s %-7s zones %d, loop starts %.1f-%.1f s, zones %.1f-%.1f s, %.1f s of audio (%.1f MB at 24-bit, as exported), worst seam %.1e, rendered in %.1f s".format(
                Locale.ROOT, pick.name, pick.voice.name, midis.size,
                notes.minOf { it.loopStartFrame } / RATE.toDouble(), notes.maxOf { it.loopStartFrame } / RATE.toDouble(),
                notes.minOf { it.snip.durationSeconds.toDouble() }, notes.maxOf { it.snip.durationSeconds.toDouble() },
                total.toDouble() / RATE, total * 3 / 1e6, worstSeam, seconds,
            )
            println(line)
            lines.append(line).append('\n')
        }
        File(out, "README.txt").writeText(
            """
            |KEEL keys: KEEL's two voices as MPC keygroup instruments, at their defaults and at WEIGHT 1.
            |Made by :shell:generateKeelKeys. Kotlin, round R2 with UPRIGHT's bow locked to the string; these are the defaults, not the factory presets.
            |
            |mpc/       copy to the MPC. Each instrument has nine zones, a minor third apart, C1 to C3.
            |           Each zone plays the note's attack and its settle, then loops the settled note.
            |           One velocity layer, rendered at full touch. RELEASE ${RELEASE_SECONDS} s.
            |previews/  the same files played through the phone's sample player: C2 held until its loop has
            |           come round twice (UPRIGHT also C1), and a walk C1 to C3.
            |           Previews are brought to one loudness; the files in mpc/ are as exported.
            |           Each zone is levelled on its first ${Keel.KEY_LEVEL_SECONDS} s, so short presses match across zones.
            |           UPRIGHT's bowed sustain is lifted to sit ${Keel.KEY_SUSTAIN_GAP_DB} dB under its pluck where it would sit lower
            |           (it sits 19 dB under at C1 as rendered), on the loop's loudest window: the level heard as about right, twice.
            |
            |Listen on headphones or monitors, not a phone speaker. Three questions:
            |Q1  Hold an UPRIGHT key (C1, C2): is the bowed sustain too quiet, about right, or too loud under the pluck?
            |Q2  Hold any key past its loop start, and walk the keys: is there a click, a pump or a jump?
            |    (R2's first files pumped: UPRIGHT's noise bow beat in its loop, up to 12.5 dB at A2, every 3 s.)
            |Q3  The HEAVY instruments are WEIGHT 1: still KEEL, or boom / distortion?
            |
            |$lines
            """.trimMargin(),
        )
        println("Wrote ${out.path}")
    }

    /** [midi] held until [zone]'s loop has come round twice, then let go. */
    private fun held(loaded: InstrumentEngine.Loaded, midi: Int, zone: KeyNote): Snip {
        val engine = InstrumentEngine(loaded, RATE)
        check(engine.noteOn(midi))
        val loop = zone.snip.frameCount - zone.loopStartFrame
        val a = render(engine, (zone.loopStartFrame + 2 * loop).toFloat() / RATE + 0.5f)
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

    /** Mono (the player's two channels averaged) at [PREVIEW_LOUDNESS], peaks held under 0.99. */
    private fun levelled(s: Snip): Snip {
        val mono = FloatArray(s.frameCount) { (s.samples[2 * it] + s.samples[2 * it + 1]) * 0.5f }
        val m = Snip(mono, 1, RATE)
        val loud = Loudness.of(m)
        if (loud <= 1e-6f) return m
        var g = PREVIEW_LOUDNESS / loud
        val peak = mono.maxOf { kotlin.math.abs(it) }
        if (peak * g > 0.99f) g = 0.99f / peak
        return Snip(FloatArray(mono.size) { mono[it] * g }, 1, RATE)
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

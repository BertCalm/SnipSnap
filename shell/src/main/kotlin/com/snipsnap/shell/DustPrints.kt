package com.snipsnap.shell

import com.snipsnap.audio.Dust
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A tape's dust, made once and kept beside the tape (`docs/DUST.md` §3).
 *
 * `Dust.print` reads every hit's tail on a tape, so it is the one
 * expensive step in dusting, and dusting sixteen pads from one tape
 * must not pay it sixteen times. The print lives in [DIR] beside the
 * tape on the SNIPS shelf as two small WAVs, the way peaks are cached,
 * and is remade when the tape is newer than they are. A tape with no
 * dust to give (`Dust.print` null) is not cached: it is cheap to ask
 * again and the answer may change once the tape is re-recorded.
 *
 * Which tape a pad dusts from is decided here too: its own (the RE-TRIM
 * keys every chopped pad carries), else the one most of the kit came
 * off, else none — a synth or a mic capture borrows the kit's room.
 */
object DustPrints {

    /** The cache folder beside the tapes, like the shelf's own `.bin`. */
    const val DIR = ".dust"

    /** [Dust.Print.hiss] sits at unit RMS, above full scale; on disk it is scaled to this peak and levelled again on read. */
    private const val STORED_PEAK = 0.9f

    /** The tape [pad] dusts from: its own, else the kit's ([kitTape]), else null. */
    fun tapeFor(kit: Kit, pad: KitPad): String? = Retrim.tapeName(pad) ?: kitTape(kit)

    /** The tape most of [kit]'s pads came off, or null when none did. */
    fun kitTape(kit: Kit): String? =
        kit.pads.mapNotNull { Retrim.tapeName(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * The print of [tape], from the cache when it is current, else made
     * and cached. Null when the tape is not a file or has no dust to give.
     */
    fun forTape(tape: File): Dust.Print? {
        if (!tape.isFile) return null
        val dir = File(tape.parentFile, DIR)
        val hissFile = File(dir, "${tape.name}.hiss.wav")
        val roomFile = File(dir, "${tape.name}.room.wav")
        val current = hissFile.isFile && roomFile.isFile &&
            hissFile.lastModified() >= tape.lastModified() && roomFile.lastModified() >= tape.lastModified()
        if (current) {
            runCatching { levelled(Dust.Print(WavReader.read(hissFile), WavReader.read(roomFile))) }.getOrNull()?.let { return it }
        }
        val print = Dust.print(WavReader.read(tape)) ?: return null
        dir.mkdirs()
        val peak = print.hiss.samples.maxOfOrNull { abs(it) } ?: 0f
        val scale = if (peak > 0f) STORED_PEAK / peak else 1f
        WavWriter.write(hissFile, Snip(FloatArray(print.hiss.samples.size) { print.hiss.samples[it] * scale }, 1, print.hiss.sampleRate))
        WavWriter.write(roomFile, print.room)
        return print
    }

    /** A print read back off disk, re-levelled to the contract: HISS at unit RMS, ROOM's absolute values summing to one. */
    private fun levelled(p: Dust.Print): Dust.Print {
        val hissRms = sqrt(p.hiss.samples.fold(0.0) { a, v -> a + v.toDouble() * v } / p.hiss.frameCount.coerceAtLeast(1)).toFloat()
        val hiss = if (hissRms > 1e-6f) Snip(FloatArray(p.hiss.samples.size) { p.hiss.samples[it] / hissRms }, 1, p.hiss.sampleRate) else p.hiss
        val l1 = p.room.samples.fold(0.0) { a, v -> a + abs(v) }.toFloat()
        val room = if (l1 > 1e-6f) Snip(FloatArray(p.room.samples.size) { p.room.samples[it] / l1 }, 1, p.room.sampleRate) else p.room
        return Dust.Print(hiss, room)
    }
}

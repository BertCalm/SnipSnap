package com.snipsnap.cli

import com.snipsnap.audio.Separate
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap split <song.wav>` — the Split at song scale: the
 * percussive layer lands as "<Song> Drums.wav", the harmonic layer as
 * "<Song> Music.wav", and because the masks sum to one the two files
 * sum back to the song. Both halves are always written — even a
 * drums-only song has a body the harmonic telling honestly claims —
 * and the summary names the verdict with measured shares instead of
 * pretending. The halves then feed any verb: `chop` the drums, `keys`
 * or `dig --air` the music.
 */
object SplitCommand {

    /** A side owning at least this much of the energy names the verdict. */
    const val VERDICT_SHARE = 0.65

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--out"), boolean = setOf("--overwrite"))
        val input = opts.positional.getOrNull(0)
            ?: throw CliError("split wants a song: snipsnap split <song.wav>")
        if (opts.positional.size > 1) throw CliError("split takes one song")
        val file = File(input)
        if (!file.isFile) throw CliError("no such file: $input")

        val song = WavReader.read(file)
        val split = Separate.hpss(song)
        fun energy(s: FloatArray): Double = s.sumOf { (it * it).toDouble() }
        val eD = energy(split.percussive.samples)
        val eM = energy(split.harmonic.samples)
        val total = (eD + eM).coerceAtLeast(1e-12)
        val dShare = eD / total
        val verdict = when {
            dShare >= VERDICT_SHARE -> "mostly drums"
            1 - dShare >= VERDICT_SHARE -> "mostly music"
            else -> "a mix"
        }

        val dir = opts["--out"]?.let { File(it).apply { mkdirs() } } ?: file.parentFile ?: File(".")
        val drums = File(dir, "${file.nameWithoutExtension} Drums.wav")
        val music = File(dir, "${file.nameWithoutExtension} Music.wav")
        for (dest in listOf(drums, music)) {
            if (dest.exists() && !opts.has("--overwrite")) {
                throw CliError("destination already exists: $dest (pass --overwrite to replace it)")
            }
        }
        // Masked halves can locally overshoot full scale even when the
        // song doesn't; the WAV boundary would clip that silently. Scale
        // BOTH halves by the same factor instead - they still sum to the
        // song, just that much quieter - and say so.
        val peak = maxOf(
            split.percussive.samples.maxOf { Math.abs(it) },
            split.harmonic.samples.maxOf { Math.abs(it) },
        )
        var (dOut, mOut) = split.percussive to split.harmonic
        if (peak > 1f) {
            val g = 1f / peak
            fun scaled(s: com.snipsnap.audio.Snip) =
                com.snipsnap.audio.Snip(FloatArray(s.samples.size) { s.samples[it] * g }, s.channels, s.sampleRate)
            dOut = scaled(split.percussive)
            mOut = scaled(split.harmonic)
            out.println("halves scaled by %.3f to fit the file format - they still sum to the song, that much quieter".format(g))
        }
        WavWriter.write(drums, dOut)
        WavWriter.write(music, mOut)
        out.println(
            "${file.name}: drums %.0f%% / music %.0f%% - $verdict".format(dShare * 100, (1 - dShare) * 100),
        )
        out.println("-> ${drums.path}")
        out.println("-> ${music.path}")
        out.println("(the two halves sum back to the song)")
        return 0
    }
}

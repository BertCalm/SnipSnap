package com.snipsnap.shell

import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource.RoundRobinPolicy
import java.io.File
import kotlin.math.sqrt

/**
 * Saved-player evidence for MURMUR's repeat policies. Everything here goes the whole way:
 * native cells are rendered into a real bank, the bank is saved, admitted to the shelf,
 * reloaded from its PCM24 files and played by [MurmurRoundRobinPlayer]. Nothing is
 * re-rendered between the save and the sound. The test and the audition share this code,
 * so the owner hears exactly what the test measured.
 */
internal object MurmurRoundRobinPlayback {
    const val RATE = 44_100
    const val C2 = 36
    /** The accepted C2 patch's own touch; it lands in the middle velocity window. */
    const val TOUCH = .65f

    fun source(): MurmurPatch = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch()

    /** Writes, admits and returns the saved bank for [policy]. */
    fun savedBank(policy: RoundRobinPolicy, work: File, name: String): MurmurRoundRobinShelf.SavedBank {
        val bank = File(work, "bank-${policy.name.lowercase()}")
        MurmurRoundRobinInstrumentBank.writeNew(name, MurmurRoundRobinInstrumentBank.Spec(source(), policy = policy), bank)
        return MurmurRoundRobinShelf.admitSaved(name, bank, File(work, "shelf"))
    }

    data class Strike(val take: Int, val samples: FloatArray)

    /**
     * Plays [count] separate C2 attacks, each heard to silence before the next, and returns
     * the left channel of each with the take the player chose.
     */
    fun isolatedStrikes(saved: MurmurRoundRobinShelf.SavedBank, shelf: File, count: Int, seconds: Double = 2.5): List<Strike> =
        MurmurRoundRobinShelf.loadReference(saved, shelf, RATE).use { player ->
            List(count) {
                val attack = requireNotNull(player.noteOn(C2, TOUCH)) { "the saved player refused C2" }
                val frames = (seconds * RATE).toInt()
                val stereo = renderBlocks(player, frames)
                player.noteOff(C2)
                drain(player)
                Strike(attack.take, FloatArray(frames) { stereo[it * 2] })
            }
        }

    /** Repeated C2s at [bpm] eighths: what the owner's ear judged. Tails overlap, as in playing. */
    fun repeatedRun(saved: MurmurRoundRobinShelf.SavedBank, shelf: File, notes: Int = 8, bpm: Double = 96.0,
                    gate: Double = .8, tailSeconds: Double = 2.0): Pair<List<Int>, FloatArray> =
        MurmurRoundRobinShelf.loadReference(saved, shelf, RATE).use { player ->
            val step = (60.0 / bpm / 2 * RATE).toInt()
            val held = (step * gate).toInt()
            val total = step * notes + (tailSeconds * RATE).toInt()
            val out = FloatArray(total * 2)
            val takes = mutableListOf<Int>()
            var at = 0
            fun advance(frames: Int) {
                renderBlocks(player, frames).copyInto(out, at * 2)
                at += frames
            }
            repeat(notes) {
                takes += requireNotNull(player.noteOn(C2, TOUCH)) { "the saved player refused C2" }.take
                advance(held)
                player.noteOff(C2)
                advance(step - held)
            }
            advance(total - at)
            takes to out
        }

    /** The player takes callback-sized blocks, as the app's audio thread gives it. */
    private fun renderBlocks(player: MurmurRoundRobinPlayer, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(BLOCK * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            player.render(block, n)
            block.copyInto(out, done * 2, 0, n * 2)
            done += n
        }
        return out
    }
    private const val BLOCK = 4096

    private fun drain(player: MurmurRoundRobinPlayer) {
        val block = FloatArray(4096 * 2)
        repeat(64) { if (player.activeNotes.isEmpty()) return; player.render(block, 4096) }
    }

    /**
     * What is left of [b] after the best single gain makes it match [a], relative to [a].
     * Zero means the two are the same strike at another level; a louder take of the
     * same strike scores zero, so level can never pass as round robin.
     */
    fun gainRemovedResidual(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var ab = 0.0; var bb = 0.0; var aa = 0.0
        for (i in 0 until n) { ab += a[i] * b[i]; bb += b[i] * b[i]; aa += a[i] * a[i] }
        require(aa > 0 && bb > 0) { "silent strike" }
        val g = ab / bb
        var r = 0.0
        for (i in 0 until n) { val d = a[i] - g * b[i]; r += d * d }
        return sqrt(r / aa)
    }

    fun pairwiseResiduals(strikes: List<FloatArray>): List<Double> =
        strikes.indices.flatMap { i -> (i + 1 until strikes.size).map { j -> gainRemovedResidual(strikes[i], strikes[j]) } }

    /**
     * Phase-blind difference: Hann-windowed 2048-point power spectra every 512 frames over
     * the first [seconds], each strike normalised to unit total power, then half the summed
     * absolute difference. 0 is the same spectrum over time, 1 shares nothing. A phase-only
     * change, which the time residual above counts, barely moves this.
     */
    fun spectralDistance(a: FloatArray, b: FloatArray, seconds: Double = 1.0): Double {
        val pa = spectrogram(a, seconds); val pb = spectrogram(b, seconds)
        val sa = pa.sum(); val sb = pb.sum()
        require(sa > 0 && sb > 0) { "silent strike" }
        var d = 0.0
        for (i in pa.indices) d += kotlin.math.abs(pa[i] / sa - pb[i] / sb)
        return d / 2
    }

    fun pairwiseSpectral(strikes: List<FloatArray>): List<Double> =
        strikes.indices.flatMap { i -> (i + 1 until strikes.size).map { j -> spectralDistance(strikes[i], strikes[j]) } }

    private fun spectrogram(x: FloatArray, seconds: Double): DoubleArray {
        val n = 2048; val hop = 512
        val end = minOf(x.size, (seconds * RATE).toInt())
        val frames = maxOf(1, (end - n) / hop + 1)
        val out = DoubleArray(frames * (n / 2))
        val window = FloatArray(n) { (.5 - .5 * kotlin.math.cos(2 * Math.PI * it / (n - 1))).toFloat() }
        for (f in 0 until frames) {
            val re = FloatArray(n) { i -> val k = f * hop + i; if (k < x.size) x[k] * window[i] else 0f }
            val im = FloatArray(n)
            com.snipsnap.audio.Fft.forward(re, im)
            for (bin in 0 until n / 2) out[f * (n / 2) + bin] = (re[bin] * re[bin] + im[bin] * im[bin]).toDouble()
        }
        return out
    }
}

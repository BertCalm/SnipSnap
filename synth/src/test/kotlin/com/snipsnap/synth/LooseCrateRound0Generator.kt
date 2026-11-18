package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Cypher.Note
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * LOOSE CRATE round 0: the candidate hits and one groove, rendered from the engines as they stand, so the
 * owner can hear whether the measured traits of the reference loops survive in SnipSnap's own drums.
 *
 * Nothing here is a kit yet. Every number is a shape value aimed at a measurement of the owner's reference
 * files (docs/LOOSE_CRATE.md has the table); none has been heard. The reference audio is never read here:
 * the targets are numbers, written down.
 *
 * What is new is one strike law, [multi]: one pad that is several fresh strikes of the same drum a few
 * milliseconds apart. A flam and a bouncing snare are both that, and the rack's shortest echo (60 ms) is
 * far too long for either. If round 0 keeps it, it moves into [KitStrikes] for round 1.
 *
 * Output (args[0], default testkit/loose-crate-round0):
 *   hits/<name>.wav             the hard first take, printed
 *   hits/<name>-dry.wav         the same strike with no print
 *   strips/<name>-takes.wav     four takes at full velocity, 0.7 s apart (round robin)
 *   strips/<name>-velocity.wav  soft, medium, hard, 0.7 s apart
 *   groove/hummin-88.wav        four bars at 88 BPM, printed, bar-exact so it loops
 *   groove/hummin-88-dry.wav    the same notes with no print
 *   compare/cypher-dust-*.wav   CYPHER DUST's kick, snare and break, as shipped, for comparison
 */
object LooseCrateRound0Generator {

    /** A pad's velocity zones, as CYPHER uses them. */
    private val velocities = LooseCrateKits.velocities
    private const val TAKES = LooseCrateKits.TAKES

    /** Round 0's print, now the HUMMIN kit's ([LooseCrateKits.CRATE]): a hit carries a light rumble, the groove the heavy bed. */
    internal fun crate(x: Snip, phrase: Boolean = false): Snip = Snip(LooseCrateKits.CRATE.apply(x.samples, phrase), 1, Dsp.RATE)

    /** The candidates: name, the strike, and what it is aimed at. */
    internal class Candidate(val name: String, val strike: Strike, val aim: String)

    // The strikes live in [LooseCrateKits], where the kits use them; round 0 renders them as the owner heard them.
    internal val candidates = listOf(
        Candidate("hum-kick-sub", LooseCrateKits.humSub,
            "the humming kick as THUMP SUB alone: 54 Hz, aimed at -20 dB near 500 ms"),
        Candidate("hum-kick-layer", LooseCrateKits.humKick,
            "the humming kick as SKIN's knock over the SUB: the knock's 61 Hz attack and beater, the SUB's hum"),
        Candidate("thud-kick", LooseCrateKits.thudKick,
            "the short thud: 50 Hz with a drop from the attack, aimed at -20 dB by 60 to 95 ms"),
        Candidate("flam-snare", LooseCrateKits.flamSnare,
            "a flam: a grace stroke, the main stroke 7 ms later, two more at 23 and 40 ms; body 260 Hz"),
        Candidate("bounce-snare", LooseCrateKits.bounceSnare,
            "a deep snare that bounces: 196 Hz, six taps 10 to 13 ms apart, long wires"),
        Candidate("slap-snare", LooseCrateKits.slapSnare,
            "one high, tight slap: a 396 Hz hand slap over snare wires, body -20 dB by about 25 ms"),
        Candidate("dark-hat", LooseCrateKits.darkHat,
            "the groove's closed hat: low in SKIN's range so it sits under the kick; a soft hit is darker and shorter"),
    )

    private fun candidate(name: String) = candidates.first { it.name == name }

    /** One finished pad: struck at a velocity and take, printed (or not), levelled like a CYPHER pad. */
    internal fun pad(c: Candidate, velocity: Float, take: Int, printed: Boolean): Snip {
        val dry = KitStrikes.struck(c.strike, velocity, take, CypherKits.touch(velocity))
        val x = if (printed) crate(Snip(dry, 1, Dsp.RATE)).samples.copyOf() else dry
        return KitStrikes.finish(x, 1f)
    }

    private const val BPM = 88
    private const val BARS = 4

    /**
     * The HUMMIN groove, four bars at 88 BPM ([LooseCrateKits.humminNotes]). Parts: 0 the layered hum kick, 1
     * the flam snare, 2 the dark hat, 3 the bounce snare as a ghost.
     */
    internal val hummin: List<Note> = LooseCrateKits.humminNotes(kick = 0, snare = 1, hat = 2, ghost = 3)

    private val grooveParts = listOf("hum-kick-layer", "flam-snare", "dark-hat", "bounce-snare").map { candidate(it).strike }

    /**
     * The groove, bar-exact: the notes are summed over four bars plus a tail, and the tail is folded back
     * onto the start, so the loop's last bar rings into its first as it would when the pad repeats.
     */
    internal fun groove(printed: Boolean): Snip {
        val frames = Math.round(BARS * 4 * 60.0 / BPM * Dsp.RATE).toInt()
        val tail = Dsp.RATE * 3 / 2
        val raw = Cypher.phraseRaw(hummin, BPM, 1f, 0, TAKES, (frames + tail).toFloat() / Dsp.RATE, CypherKits::touch) { grooveParts[it] }
        val looped = FloatArray(frames) { raw[it] + (if (it < tail) raw[frames + it] else 0f) }
        val x = if (printed) crate(Snip(looped, 1, Dsp.RATE), phrase = true).samples.copyOf(frames) else looped
        // Level only: no tail fade, so the loop wraps.
        val peak = KitStrikes.peakOf(x)
        val mean = x.average().toFloat()
        return Snip(FloatArray(frames) { (x[it] - mean) * .95f / max(peak, 1e-9f) }, 1, Dsp.RATE)
    }

    /** Pads [pads] in a row, [gapSeconds] apart, each cut at the gap. */
    private fun strip(pads: List<Snip>, gapSeconds: Float = .7f): Snip {
        val step = (gapSeconds * Dsp.RATE).toInt()
        val out = FloatArray(step * pads.size)
        for ((i, p) in pads.withIndex()) {
            val n = min(p.samples.size, step - 64)
            for (j in 0 until n) out[i * step + j] = p.samples[j] * if (j > n - 256) (n - j) / 256f else 1f
        }
        return Snip(out, 1, Dsp.RATE)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/loose-crate-round0")
        fun write(path: String, s: Snip) = WavWriter.write(File(root, path), s).also { println("wrote ${it.path}") }
        for (c in candidates) {
            write("hits/${c.name}.wav", pad(c, 1f, 0, printed = true))
            write("hits/${c.name}-dry.wav", pad(c, 1f, 0, printed = false))
            write("strips/${c.name}-takes.wav", strip(List(TAKES) { pad(c, 1f, it, printed = true) }))
            write("strips/${c.name}-velocity.wav", strip(velocities.map { pad(c, it, 0, printed = true) }))
        }
        write("groove/hummin-88.wav", groove(printed = true))
        write("groove/hummin-88-dry.wav", groove(printed = false))
        val dust = CypherKits.Kit.DUST
        val roles = CypherKits.roles(dust)
        for ((file, role) in listOf("cypher-dust-kick" to "Tape Thud", "cypher-dust-snare" to "Wood Crack", "cypher-dust-break" to "Dusty Break")) {
            write("compare/$file.wav", CypherKits.render(dust, roles.indexOf(role), 1f, 0))
        }
    }
}

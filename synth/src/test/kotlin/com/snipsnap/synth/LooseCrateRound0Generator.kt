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
    private val velocities = listOf(.30f, .65f, 1f)
    private const val TAKES = 4

    /**
     * One pad that is several strikes of one drum: strike i lands [offsetsMs]\[i] after the first, at
     * [weights]\[i] of the pad's velocity (its timbre struck that softly, and its level scaled by the same
     * weight). Every strike is a fresh take of the engine, so no two strikes inside a pad, and no two takes
     * of a pad, share audio. [single] renders one strike at a velocity and an engine take, peak-normalised.
     */
    internal fun multi(offsetsMs: List<Float>, weights: List<Float>, single: (velocity: Float, engineTake: Int) -> FloatArray): Strike {
        require(offsetsMs.size == weights.size && offsetsMs.isNotEmpty() && offsetsMs.first() == 0f)
        return { v, t, _ ->
            val strikes = offsetsMs.indices.map { i ->
                // Engine take 0 is the drum's authored strike; the rest are fresh. 1 + t*16 + i keeps every
                // strike of every pad take on its own seed.
                single((v * weights[i]).coerceIn(.05f, 1f), 1 + t * 16 + i)
            }
            val starts = offsetsMs.map { Math.round(it * Dsp.RATE / 1000f) }
            val out = FloatArray(starts.indices.maxOf { starts[it] + strikes[it].size })
            for (i in strikes.indices) {
                val peak = KitStrikes.peakOf(strikes[i]).let { if (it > 1e-9f) weights[i] / it else 0f }
                for (j in strikes[i].indices) out[starts[i] + j] += strikes[i][j] * peak
            }
            out
        }
    }

    private fun skinSnare(vararg m: Pair<String, Float>): (Float, Int) -> FloatArray = { v, take ->
        Skin.render(SkinVoice.SNARE, mapOf(*m), v, take).samples
    }

    /**
     * The print every LOOSE CRATE pad goes through, in the order the records were made: sampled into a
     * 12-bit converter at about 32 kHz (CRUNCH), bounced to a worn tape (TAPE), the record's rumble under it
     * (VINYL, after TAPE here: the bed is the record the hit was lifted from, heard through the same tape
     * would darken it, and the references' quiet moments are dark rumble, not tape hiss). Shape values.
     */
    internal fun crate(x: Snip, rumble: Float = HIT_RUMBLE): Snip {
        // Drive is light (GRIT .05, DRIVE .15): at DRIVE .40 the printed snares measured 7 dB more RMS than
        // the references at the same peak, the transient squeezed into the body.
        // CRUNCH and TAPE match their input's peak and then clamp at full scale, so a summed flam or a whole
        // groove (peaks of 2 and more) would be squared off. Round 0's first render did exactly that: the
        // printed groove measured a 2.6 dB crest factor. Bring anything over [HEADROOM] down first; a single
        // strike at its zone's level passes untouched, so the converter and tape still hear a softer hit softer.
        val peak = x.samples.maxOf { kotlin.math.abs(it) }
        val k = if (peak > HEADROOM) HEADROOM / peak else 1f
        val fed = Snip(FloatArray(x.samples.size) { x.samples[it] * k }, 1, Dsp.RATE)
        val crunched = Crunch.process(fed, mapOf("BITS" to .45f, "RATE" to .22f, "TONE" to .95f, "GRIT" to .05f))
        val taped = Tape.process(crunched, mapOf("WOBBLE" to .08f, "DRIVE" to .15f, "AGE" to .60f))
        return Vinyl.process(taped, mapOf("CRACKLE" to .20f, "RUMBLE" to rumble, "HISS" to .25f))
    }

    /** The most the print is fed. Shape. */
    private const val HEADROOM = .9f

    /** A one-shot's rumble is light, since pads overlap and their beds add; the groove carries the heavy bed. */
    private const val HIT_RUMBLE = .30f
    private const val GROOVE_RUMBLE = .70f

    /** The candidates: name, the strike, and what it is aimed at. */
    internal class Candidate(val name: String, val strike: Strike, val aim: String)

    // SKIN's kick: 45 to 75 Hz over TUNE. 54 Hz is TUNE .357, 50 Hz is .206.
    private val humKnock = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .36f, "DECAY" to .90f, "TONE" to .30f, "PUNCH" to .40f, "CLICK" to .35f, "DROP" to .15f)
    // THUMP's SUB: E1 (41.2 Hz) to E2 over TUNE; 54 Hz is TUNE .39. DECAY .85 is about a 0.95 s t60: the
    // print lengthens a low tail by about half (measured 550 → 773 ms to -20 dB at DECAY 1), so the dry
    // hum is aimed short of the 500 to 555 ms target.
    private val humSub = KitStrikes.sub("TUNE" to .39f, "DECAY" to .85f, "SLIDE" to .5f, "DRIVE" to .45f, "CLICK" to .30f)

    /**
     * The closed hat, with its own contact law. Through [KitStrikes.touched]'s one-pole (7 kHz at the soft
     * zone) and the print's 6 kHz tape head, the first try measured the same centroid at every velocity
     * (4788, 4752, 4778 Hz): volume only. Two poles falling to 3 kHz on a soft hit, and a shorter ring, make
     * the soft hit a different sound. Every take re-seeds the hat's noise.
     */
    private val darkHat: Strike = { v, t, _ ->
        val m = KitStrikes.varied(mapOf("TUNE" to .15f, "DECAY" to .12f + .10f * v, "TONE" to .20f, "PUNCH" to .3f + .3f * v), listOf("TUNE", "DECAY"), t)
        val x = Skin.render(SkinVoice.HAT_CLOSED, m, 1f, t, reseedUnstruck = true).samples
        val hz = 3_000f + 13_000f * v * v
        val a = Dsp.FixedOnePole(hz); val b = Dsp.FixedOnePole(hz)
        FloatArray(x.size) { b.lp(a.lp(x[it])) }
    }

    internal val candidates = listOf(
        Candidate("hum-kick-sub", humSub,
            "the humming kick as THUMP SUB alone: 54 Hz, aimed at -20 dB near 500 ms"),
        Candidate("hum-kick-layer", KitStrikes.layered(humKnock, .7f, humSub, 1f),
            "the humming kick as SKIN's knock over the SUB: the knock's 61 Hz attack and beater, the SUB's hum"),
        Candidate("thud-kick", KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .21f, "DECAY" to .25f, "TONE" to .35f, "PUNCH" to .50f, "CLICK" to .60f, "DROP" to .55f),
            "the short thud: 50 Hz with a drop from the attack, aimed at -20 dB by 60 to 95 ms"),
        // SKIN's snare: 170 to 260 Hz over TUNE; the flam reference's body is 262 Hz, the top.
        Candidate("flam-snare", multi(listOf(0f, 7f, 23f, 40f), listOf(.50f, 1f, .60f, .70f),
            skinSnare("TUNE" to 1f, "SNAP" to .80f, "DECAY" to .10f, "PUNCH" to .60f, "RATTLE" to .65f, "SIZZLE" to .55f)),
            "a flam: a grace stroke, the main stroke 7 ms later, two more at 23 and 40 ms; body 260 Hz"),
        // 196 Hz is TUNE .34. The bounce reference's taps fall about 2.5 dB each, every 10 to 13 ms.
        Candidate("bounce-snare", multi(listOf(0f, 9.5f, 22.5f, 35f, 45f, 56f), listOf(.90f, 1f, .69f, .52f, .40f, .30f),
            skinSnare("TUNE" to .34f, "SNAP" to .80f, "DECAY" to .90f, "PUNCH" to .50f, "RATTLE" to .85f, "SIZZLE" to .80f)),
            "a deep snare that bounces: 196 Hz, six taps 10 to 13 ms apart, long wires"),
        // The slap reference's loudest band is 0.5 to 2 kHz, which no SKIN snare setting reaches (round 0's
        // first try, a 220 Hz SKIN snare, put 13 dB less there). TERRA's hand slap does: its head is 160 to
        // 400 Hz over TUNE (.99 is 396 Hz) and SMACK is the hand. BOWL is off: at its default .5 the bowl's air rang for 0.9 s and put 95 percent of
        // the slap's energy under 215 Hz. SKIN's wires sit under it.
        Candidate("slap-snare", KitStrikes.layered(
            KitStrikes.terra(TerraVoice.SLAP, mapOf("TUNE" to .99f, "DECAY" to .10f, "FORCE" to .95f, "POS" to .875f, "BOWL" to 0f, "SMACK" to .85f)), 1f,
            KitStrikes.skinStruck(SkinVoice.SNARE, "TUNE" to .62f, "SNAP" to .90f, "DECAY" to .12f, "PUNCH" to .80f, "RATTLE" to .50f, "SIZZLE" to .60f), .55f),
            "one high, tight slap: a 396 Hz hand slap over snare wires, body -20 dB by about 25 ms"),
        Candidate("dark-hat", darkHat,
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
     * The HUMMIN groove, four bars at 88 BPM. Parts: 0 the layered hum kick, 1 the flam snare, 2 the dark
     * hat, 3 the bounce snare as a ghost. The feel copies the two 88 BPM references' measured lean: the kick
     * on the grid, the backbeat about 14 ms late, the hats 20 ms late on the eighths and 38 ms late on the
     * sixteenths between (about a 60 percent swing), then every note moved by its own seeded amount within
     * 10 ms, so no two bars land the same.
     */
    internal val hummin: List<Note> = run {
        val notes = buildList {
            for (bar in 0 until BARS) {
                val b = bar * 4.0
                val kicks = if (bar % 2 == 0) listOf(0.0 to 1f, 1.75 to .70f, 2.5 to .90f)
                else listOf(0.0 to 1f, .75 to .55f, 2.5 to .85f, 2.75 to .60f)
                for ((beat, w) in kicks) add(Note(b + beat, 0, w))
                add(Note(b + 1.0, 1, .95f, offsetMs = 14f))
                add(Note(b + 3.0, 1, 1f, offsetMs = 16f))
                if (bar % 2 == 1) add(Note(b + 3.75, 3, .30f, offsetMs = 30f))
                if (bar == BARS - 1) add(Note(b + 3.5, 3, .45f, offsetMs = 22f))
                for (eighth in 0 until 8) add(Note(b + eighth * .5, 2, if (eighth % 2 == 0) .75f else .50f, offsetMs = 20f))
                // A sixteenth pickup before the backbeats, dragged further.
                for (s in listOf(.75, 2.75)) add(Note(b + s, 2, .35f, offsetMs = 38f))
            }
        }
        Cypher.humanised(notes, 10f, "loose-crate-hummin")
    }

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
        val x = if (printed) crate(Snip(looped, 1, Dsp.RATE), GROOVE_RUMBLE).samples.copyOf(frames) else looped
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

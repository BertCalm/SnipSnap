package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Cypher.Note
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * LOOSE CRATE round 2a: why round 1 sounds "a bit drum machine like" (the owner, 2026-11-18), tried on HUMMIN's
 * groove before any kit changes.
 *
 * Measured on the two 88 BPM references against round 1's HUMMIN groove (docs/LOOSE_CRATE.md, round 2): the
 * references never go quiet (their quietest tenth of 10 ms frames sits 18 to 23 dB under the loudest; ours 45),
 * their cymbal band never stops (26 to 29 dB down at its quietest; ours 49), and their kicks land 9 to 11 dB
 * apart from soft to loud (ours 1.6). A sampled break is one kit in one room on shared microphones: the room rings
 * between hits, the snare wires buzz when the kick lands, the cymbals wash, a compressor on the mics breathes
 * with the whole kit, and a drummer's kicks are not all the same. Round 1 summed isolated dry hits into silence.
 *
 * Two treatments, both the same notes and feel as round 1's groove:
 * - **A, the room:** round 1's own sounds, played in one room. Kicks follow a drummer's dynamics, every kick
 *   buzzes the snare wires, a soft dark ride washes on the quarters and the open hat rings unchoked, and the whole
 *   kit goes through ROOM then COMPRESSOR (the mics breathing) before round 1's crate print.
 * - **B, the acoustic kit:** A with EMBER's acoustic snare (struck as the same flam), hats and ride in place of
 *   SKIN's synthetic ones. The hum kick, which the owner approved, stays.
 *
 * Output (args[0], default testkit/loose-crate-round2a): `hummin-round1.wav` (as shipped), `hummin-a-room.wav`,
 * `hummin-b-acoustic.wav`, and the same three dry of the print as `*-bus.wav` where a bus applies.
 * Every number here is a shape value; nothing has been heard.
 */
object LooseCrateRound2Generator {
    private val kit = LooseCrateKits.Kit.HUMMIN
    private const val TAKES = LooseCrateKits.TAKES

    /** Parts of the round-2 groove: the kit's pads it borrows, and the new layers the room adds. */
    private class Parts(val kick: Strike, val snare: Strike, val hat: Strike, val openHat: Strike, val ghost: Strike, val ride: Strike, val wires: Strike)

    /** Only the wires of a snare: the drum struck very softly and its body filtered away (a two-pole high-pass at 1.8 kHz). */
    private fun wiresOf(snare: (Float, Int) -> FloatArray): Strike = { v, t, _ ->
        val x = snare(v, t)
        val a = Dsp.FixedOnePole(1_800f)
        val b = Dsp.FixedOnePole(1_800f)
        FloatArray(x.size) { val h = x[it] - a.lp(x[it]); h - b.lp(h) }
    }

    private val skinWires = wiresOf { v, t -> Skin.render(SkinVoice.SNARE, mapOf("TUNE" to 1f, "SNAP" to .30f, "DECAY" to .60f, "RATTLE" to .90f, "SIZZLE" to .80f), v, 100 + t).samples }

    private fun ember(voice: EmberVoice, vararg m: Pair<String, Float>): Strike = { v, t, _ -> Ember.render(voice, mapOf(*m), v, t).samples }

    // EMBER's controls: DISTANCE 0 and WARMTH 0 leave the mic close and the record clean: the room and the crate print
    // supply both here (KNEE BREAK's proof did the same).
    private val emberSnare: Strike = KitStrikes.multi(listOf(0f, 7f, 23f, 40f), listOf(.50f, 1f, .60f, .70f)) { v, t ->
        Ember.render(EmberVoice.SNARE, mapOf("TUNE" to .75f, "DAMP" to .45f, "BUZZ" to .70f, "DISTANCE" to 0f, "WARMTH" to 0f), v, t).samples
    }
    private val emberWires = wiresOf { v, t -> Ember.render(EmberVoice.SNARE, mapOf("TUNE" to .75f, "DAMP" to .30f, "BUZZ" to 1f, "DISTANCE" to 0f, "WARMTH" to 0f), v, 100 + t).samples }

    private val partsA: Parts = LooseCrateKits.parts(kit).let { p ->
        Parts(kick = p[0].strike, snare = p[1].strike, hat = p[2].strike, openHat = p[3].strike, ghost = p[5].strike, ride = p[10].strike, wires = skinWires)
    }

    private val partsB: Parts = LooseCrateKits.parts(kit).let { p ->
        // EMBER's snare body measured 6 dB over the references between 200 Hz and 1 kHz at full weight.
        Parts(kick = p[0].strike, snare = KitStrikes.softer(emberSnare, .75f),
            hat = ember(EmberVoice.HAT_CLOSED, "TUNE" to .40f, "DAMP" to .55f, "DISTANCE" to 0f, "WARMTH" to 0f),
            openHat = ember(EmberVoice.HAT_OPEN, "TUNE" to .40f, "DAMP" to .40f, "DISTANCE" to 0f, "WARMTH" to 0f),
            ghost = KitStrikes.softer(emberSnare, .45f),
            ride = ember(EmberVoice.RIDE, "TUNE" to .35f, "DAMP" to .45f, "DISTANCE" to 0f, "WARMTH" to 0f),
            wires = emberWires)
    }

    private const val KICK = 0
    private const val SNARE = 1
    private const val HAT = 2
    private const val OPEN_HAT = 3
    private const val GHOST = 4
    private const val RIDE = 5
    private const val WIRES = 6

    /** How hard a drummer's kicks land: round 1's weight w becomes w^KICK_CURVE (0.55 → 0.17, 0.9 → 0.73). */
    private const val KICK_CURVE = 3.0f

    /**
     * The hats against round 1's: in the room they ring on, and at round 1's weights the 1 to 16 kHz bands measured
     * 5 to 8 dB over the references'.
     */
    private const val HAT_LEVEL = .7f

    /** How loud the wires answer each kick, against the kick's own weight. */
    private const val WIRE_WEIGHT = .10f

    /**
     * Round 1's HUMMIN notes with a drummer's dynamics on the kick, the wires answering every kick, the open hat on
     * the last eighth of each bar left to ring, and a soft ride on the quarters. The ride and the wires keep the
     * kit sounding between hits, as a room full of metal does.
     */
    private val notes: List<Note> = run {
        val round1 = LooseCrateKits.humminNotes(kick = KICK, snare = SNARE, hat = HAT, ghost = GHOST)
        buildList {
            for (n in round1) {
                if (n.part == KICK) {
                    val w = n.weight.pow(KICK_CURVE)
                    add(Note(n.beat, KICK, w, n.offsetMs))
                    add(Note(n.beat, WIRES, (w * WIRE_WEIGHT).coerceAtLeast(.05f), n.offsetMs + 2f))
                } else if (n.part == HAT && abs(n.beat % 4.0 - 3.5) < 1e-6) {
                    add(Note(n.beat, OPEN_HAT, n.weight * .8f * HAT_LEVEL, n.offsetMs))
                } else if (n.part == HAT) {
                    add(Note(n.beat, HAT, n.weight * HAT_LEVEL, n.offsetMs))
                } else add(n)
            }
            for (q in 0 until LooseCrateKits.GROOVE_BARS * 4) add(Note(q.toDouble(), RIDE, if (q % 2 == 0) .10f else .06f, offsetMs = 12f))
        }
    }

    /** The kit's mics: a small hard room, then a compressor that breathes with the whole kit. Shape values. */
    private val ROOM = mapOf("SIZE" to .22f, "WALLS" to .55f, "DISTANCE" to .60f)
    private val BUS = mapOf("AMOUNT" to .45f, "ATTACK" to .80f, "RECOVER" to .45f)

    private fun strikeOf(parts: Parts, part: Int): Strike = when (part) {
        KICK -> parts.kick; SNARE -> parts.snare; HAT -> parts.hat; OPEN_HAT -> parts.openHat
        GHOST -> parts.ghost; RIDE -> parts.ride; else -> parts.wires
    }

    /** The groove dry, then (on [bus]) through the room and the compressor, unfolded: bars plus every tail. */
    private fun busRaw(parts: Parts, bus: Boolean): FloatArray {
        val frames = LooseCrateKits.grooveFrames(kit)
        val seconds = frames.toFloat() / Dsp.RATE + 2.5f
        val dry = Cypher.phraseRaw(notes, kit.bpm, 1f, 0, TAKES, seconds, LooseCrateKits::touch) { strikeOf(parts, it) }
        if (!bus) return dry
        val peak = dry.maxOf { abs(it) }
        val fed = Snip(FloatArray(dry.size) { dry[it] * .9f / max(peak, 1e-9f) }, 1, Dsp.RATE)
        val squeezed = Compressor.process(Room.process(fed, ROOM), BUS).samples
        // A dark mix: the references carry 4 to 6 dB less above 1 kHz than this kit in a room (round 2a's measure).
        val shelf = Dsp.Biquad().apply { highShelf(SHELF_HZ, SHELF_DB) }
        return FloatArray(squeezed.size) { shelf.process(squeezed[it]) }
    }

    private const val SHELF_HZ = 1_200f
    private const val SHELF_DB = -5f

    /** Fold everything past the bars back onto the start, print twice end to end and keep the second pass, as the kit does. */
    private fun printedLoop(raw: FloatArray, print: Boolean): Snip {
        val n = LooseCrateKits.grooveFrames(kit)
        val folded = FloatArray(n)
        for (i in raw.indices) folded[i % n] += raw[i]
        val out = if (!print) folded else {
            val p = LooseCrateKits.CRATE.apply(FloatArray(2 * n) { folded[it % n] }, phrase = true)
            val fade = (.02f * Dsp.RATE).toInt()
            FloatArray(n) { j -> if (j < n - fade) p[n + j] else { val w = (j - (n - fade) + 1).toFloat() / fade; p[n + j] * (1 - w) + p[j] * w } }
        }
        val mean = out.average().toFloat()
        val peak = out.maxOf { abs(it - mean) }
        return Snip(FloatArray(n) { (out[it] - mean) * .92f / max(peak, 1e-9f) }, 1, Dsp.RATE)
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/loose-crate-round2a")
        fun write(name: String, s: Snip) = WavWriter.write(File(root, name), s).also { println("wrote ${it.path}") }
        write("hummin-round1.wav", LooseCrateKits.render(kit, LooseCrateKits.GROOVE, 1f, 0))
        write("hummin-a-room.wav", printedLoop(busRaw(partsA, bus = true), print = true))
        write("hummin-a-room-bus.wav", printedLoop(busRaw(partsA, bus = true), print = false))
        write("hummin-b-acoustic.wav", printedLoop(busRaw(partsB, bus = true), print = true))
        write("hummin-b-acoustic-bus.wav", printedLoop(busRaw(partsB, bus = true), print = false))
    }
}

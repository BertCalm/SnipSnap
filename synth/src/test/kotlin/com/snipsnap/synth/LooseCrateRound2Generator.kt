package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * LOOSE CRATE round 2a: the two treatments the owner chose between after hearing round 1 as "a bit drum machine
 * like" (docs/LOOSE_CRATE.md, round 2a). The owner chose A (2026-11-18), and A is now HUMMIN's groove in the kit
 * itself ([LooseCrateKits.roomOf]); this generator renders it from there, so the treatment has one definition.
 *
 * - **A, the room:** round 1's sounds played as one kit in one room: [LooseCrateKits.render] of HUMMIN's groove.
 * - **B, the acoustic kit (not chosen):** the same notes, room and print with EMBER's acoustic snare (struck as the
 *   same flam, at 0.75), hats and ride in place of SKIN's; the approved hum kick stays. Kept so the comparison the
 *   owner heard can be rendered again.
 *
 * Output (args[0], default testkit/loose-crate-round2a): `hummin-round1.wav` (round 1's groove: the round-1 notes
 * through the crate print with no room), `hummin-a-room.wav`, `hummin-b-acoustic.wav`, and the last two before the
 * crate print as `*-bus.wav`.
 */
object LooseCrateRound2Generator {
    private val kit = LooseCrateKits.Kit.HUMMIN

    private fun ember(voice: EmberVoice, vararg m: Pair<String, Float>): Strike = { v, t, _ -> Ember.render(voice, mapOf(*m), v, t).samples }

    // EMBER's DISTANCE 0 and WARMTH 0 leave the mic close and the record clean: the room and the crate print supply both.
    private val emberSnare: Strike = KitStrikes.multi(listOf(0f, 7f, 23f, 40f), listOf(.50f, 1f, .60f, .70f)) { v, t ->
        Ember.render(EmberVoice.SNARE, mapOf("TUNE" to .75f, "DAMP" to .45f, "BUZZ" to .70f, "DISTANCE" to 0f, "WARMTH" to 0f), v, t).samples
    }

    /** B's strikes on HUMMIN's part numbers: EMBER's snare (at 0.75; its body measured 6 dB over the references), hats and ride. */
    private fun acoustic(part: Int): Strike = when (part) {
        1 -> KitStrikes.softer(emberSnare, .75f)
        2 -> ember(EmberVoice.HAT_CLOSED, "TUNE" to .40f, "DAMP" to .55f, "DISTANCE" to 0f, "WARMTH" to 0f)
        3 -> ember(EmberVoice.HAT_OPEN, "TUNE" to .40f, "DAMP" to .40f, "DISTANCE" to 0f, "WARMTH" to 0f)
        5 -> KitStrikes.softer(emberSnare, .45f)
        10 -> ember(EmberVoice.RIDE, "TUNE" to .35f, "DAMP" to .45f, "DISTANCE" to 0f, "WARMTH" to 0f)
        LooseCrateKits.WIRES -> emberWires
        else -> LooseCrateKits.strikeOf(kit, part)
    }

    /** EMBER's snare wires alone, as A's are SKIN's: struck softly, the body high-passed away at 1.8 kHz. */
    private val emberWires: Strike = { v, t, _ ->
        val x = Ember.render(EmberVoice.SNARE, mapOf("TUNE" to .75f, "DAMP" to .30f, "BUZZ" to 1f, "DISTANCE" to 0f, "WARMTH" to 0f), v, 100 + t).samples
        val a = Dsp.FixedOnePole(1_800f)
        val b = Dsp.FixedOnePole(1_800f)
        FloatArray(x.size) { val h = x[it] - a.lp(x[it]); h - b.lp(h) }
    }

    /** B before the print: HUMMIN's played notes with B's strikes, through HUMMIN's room, folded onto four bars. */
    private fun acousticBus(): FloatArray {
        val notes = LooseCrateKits.played(kit, LooseCrateKits.humminNotes(kick = 0, snare = 1, hat = 2, ghost = 5))
        val frames = LooseCrateKits.grooveFrames(kit)
        val raw = Cypher.phraseRaw(notes, kit.bpm, 1f, 0, LooseCrateKits.TAKES, frames.toFloat() / Dsp.RATE + 2.5f, LooseCrateKits::touch) { acoustic(it) }
        val roomed = LooseCrateKits.roomOf(kit).play(raw)
        return FloatArray(frames).also { out -> for (i in roomed.indices) out[i % frames] += roomed[i] }
    }

    /** Round 1's groove for comparison: the round-1 notes, no room, through the crate print. */
    private fun round1(): FloatArray {
        val notes = LooseCrateKits.humminNotes(kick = 0, snare = 1, hat = 2, ghost = 5)
        val frames = LooseCrateKits.grooveFrames(kit)
        val raw = Cypher.phraseRaw(notes, kit.bpm, 1f, 0, LooseCrateKits.TAKES, frames.toFloat() / Dsp.RATE + 1.5f, LooseCrateKits::touch) { LooseCrateKits.strikeOf(kit, it) }
        return FloatArray(frames).also { out -> for (i in raw.indices) out[i % frames] += raw[i] }
    }

    /** The crate print on a four-bar loop, printed twice and the second pass kept, its seam crossfaded, as the kit does. */
    private fun printed(folded: FloatArray): FloatArray {
        val n = folded.size
        val p = LooseCrateKits.CRATE.apply(FloatArray(2 * n) { folded[it % n] }, phrase = true)
        val fade = (.02f * Dsp.RATE).toInt()
        return FloatArray(n) { j -> if (j < n - fade) p[n + j] else { val w = (j - (n - fade) + 1).toFloat() / fade; p[n + j] * (1 - w) + p[j] * w } }
    }

    private fun levelled(x: FloatArray): Snip {
        val mean = x.average().toFloat()
        val peak = x.maxOf { abs(it - mean) }
        return Snip(FloatArray(x.size) { (x[it] - mean) * .92f / max(peak, 1e-9f) }, 1, Dsp.RATE)
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/loose-crate-round2a")
        fun write(name: String, s: Snip) = WavWriter.write(File(root, name), s).also { println("wrote ${it.path}") }
        // `grooves`: only every kit's groove (hard first take), printed and before the print, for tuning the rooms.
        if (args.getOrNull(1) == "grooves") {
            for (k in LooseCrateKits.Kit.entries) {
                write("grooves/${k.id}.wav", LooseCrateKits.render(k, LooseCrateKits.GROOVE, 1f, 0))
                write("grooves/${k.id}-bus.wav", LooseCrateKits.dry(k, LooseCrateKits.GROOVE, 1f, 0))
                for (role in listOf(0, 4, 8)) write("grooves/${k.id}-pad-${role + 1}.wav", LooseCrateKits.render(k, role, 1f, 0))
            }
            return
        }
        write("hummin-round1.wav", levelled(printed(round1())))
        write("hummin-a-room.wav", LooseCrateKits.render(kit, LooseCrateKits.GROOVE, 1f, 0))
        write("hummin-a-room-bus.wav", LooseCrateKits.dry(kit, LooseCrateKits.GROOVE, 1f, 0))
        val b = acousticBus()
        write("hummin-b-acoustic.wav", levelled(printed(b)))
        write("hummin-b-acoustic-bus.wav", levelled(b))
    }
}

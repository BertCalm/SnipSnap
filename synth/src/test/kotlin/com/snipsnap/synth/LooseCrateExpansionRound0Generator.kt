package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Cypher.Note
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * LOOSE CRATE's expansion, round 0: one groove and a few key hits for each of the three kits the owner asked for
 * (2026-11-18, "Let's do all 3"), before any kit is built. Each reuses LOOSE CRATE's room ([LooseCrateKits.Room])
 * and crate print ([LooseCrateKits.CRATE]), so they sound like members of the same family. No reference loops were
 * given for these three; the targets are the styles themselves and, for SPEED, HUMMIN. Shape values, unheard.
 *
 * - **BLUE, jazz breaks off a record (90 BPM):** EMBER's felt kick, brush taps, ride, foot hat and shaker (the
 *   owner approved EMBER's cymbals after they were rebuilt from measurements of real ones) played as a hip-hop beat
 *   lifted from a jazz record: the ride swung in triplets, the brushes on two and four, a feathered kick with a
 *   boom-bap push, then the room and the crate print.
 * - **SPEED, sped-up breaks (108 BPM):** HUMMIN's approved strikes played at 80 BPM in HUMMIN's room, then the whole
 *   record played fast, exactly 45 / 33 1/3 = 1.35 (about 5.2 semitones): shorter, tighter, higher hits, the kick's
 *   54 Hz hum lifted to 73 Hz, the groove landing on 108 BPM to the sample (529,200 frames / 1.35 = 392,000). The
 *   speed-up comes before the crate print: the record was sped up, then sampled.
 * - **RAGA, Indian-record percussion (94 BPM):** TERRA's tabla (the dayan's open "na" and closed "ti", the bayan's
 *   bending "ge") and dholak over a hip-hop kick and snare, a small room, the crate print.
 *
 * Output (args[0], default testkit/loose-crate-expansion-round0): `<kit>/groove.wav`, `<kit>/groove-noprint.wav`
 * and `<kit>/hit-<name>.wav`, each printed hit at full velocity.
 */
object LooseCrateExpansionRound0Generator {
    private const val BARS = 4

    // ---------- BLUE ----------

    private fun ember(voice: EmberVoice, vararg m: Pair<String, Float>): Strike = { v, t, _ ->
        Ember.render(voice, mapOf("DISTANCE" to 0f, "WARMTH" to 0f) + mapOf(*m), v, t).samples
    }

    /**
     * BLUE's ride, round 0f: "The ride needs to be brighter and more of a light shimmery wash after the attack" (the
     * owner, 2026-11-19). EMBER's flat ride is bright dry (its wash centred near 6.7 kHz), but the room's shelf (-4 dB
     * over 1.2 kHz) and the crate print's tape head (AGE .60, a one-pole near 6 kHz) take the shimmer: through them
     * its wash centred at 1.1 kHz. Both shape the whole kit and the tape is the family's, so the ride is treated on
     * its own channel, as a mixing engineer would:
     * - TUNE .65 (was .40) and DAMP .10 (was .40): the modes higher, the ring longer;
     * - a high shelf, +[RIDE_SHELF_DB] at [RIDE_AIR_HZ], to stand up to the room and the tape;
     * - the wash lifted: the ride's own band over [RIDE_AIR_HZ] (two one-poles' high-pass), added [RIDE_LIFT] times
     *   over, faded in across the first [RIDE_LIFT_SECONDS] s so the strike itself is unchanged.
     * Measured through the room and the print (no vinyl): the band over 5 kHz in the wash (0.2 to 0.6 s after the
     * strike) from 12.6 to 7.4 dB under the attack, the wash's centre from 1.1 to 3.4 kHz.
     */
    private val shimmeringRide: Strike = { v, t, _ ->
        val x = Ember.render(EmberVoice.RIDE, mapOf("TUNE" to .65f, "DAMP" to .10f, "BUZZ" to .20f, "DISTANCE" to 0f, "WARMTH" to 0f), v, t).samples
        val shelf = Dsp.Biquad().apply { highShelf(RIDE_AIR_HZ, RIDE_SHELF_DB) }
        val y = FloatArray(x.size) { shelf.process(x[it]) }
        val a = Dsp.FixedOnePole(RIDE_AIR_HZ)
        val b = Dsp.FixedOnePole(RIDE_AIR_HZ)
        val ramp = RIDE_LIFT_SECONDS * Dsp.RATE
        FloatArray(y.size) { val h1 = y[it] - a.lp(y[it]); val h = h1 - b.lp(h1); y[it] + RIDE_LIFT * h * minOf(1f, it / ramp) }
    }

    /** Where the ride's air starts, Hz: its shelf and its lifted wash. */
    private const val RIDE_AIR_HZ = 5_000f
    private const val RIDE_SHELF_DB = 6f
    /** How many times over the ride's air is added again after the strike: 2 is about +9.5 dB on that band. */
    private const val RIDE_LIFT = 2f
    private const val RIDE_LIFT_SECONDS = .15f

    private val blueParts: List<Pair<String, Strike>> = listOf(
        "felt-kick" to ember(EmberVoice.KICK, "TUNE" to .35f, "DAMP" to .65f, "BUZZ" to .15f),
        "brush-tap" to ember(EmberVoice.BRUSH_TAP, "TUNE" to .55f, "DAMP" to .35f, "BUZZ" to .55f),
        "ride" to shimmeringRide,
        "foot-hat" to ember(EmberVoice.HAT_FOOT, "TUNE" to .45f, "DAMP" to .50f, "BUZZ" to .10f),
        // Round 0e: the brush sweep is gone. Three rounds of it (round 0 "harsh and overbearing", 0c "scraping rather
        // than a gently rattling sweep", 0d "just awful") and the owner chose a shaker in its place (2026-11-19).
        // EMBER's shaker is fitted to a CC0 recording of a large shaker; TUNE a little low and DAMP a little long
        // to sit with the brushes. Shape values, unheard.
        "shaker" to ember(EmberVoice.SHAKER, "TUNE" to .30f, "DAMP" to .45f),
    )

    /**
     * A hip-hop beat off a jazz record at 90: the ride's spang-a-lang in triplet swing (beats 0, 1, 1 2/3, 2, 3,
     * 3 2/3), the foot hat on two and four, brush taps on two and four with ghosts, a shaker on swung eighths,
     * and a feathered kick that pushes like boom bap (0, 1.75 soft, 2.5). Every note moves by its own seeded amount
     * within 12 ms.
     */
    private val blueNotes: List<Note> = Cypher.humanised(buildList {
        for (bar in 0 until BARS) {
            val b = bar * 4.0
            for ((beat, w) in listOf(0.0 to .70f, 1.0 to .60f, 1.0 + 2.0 / 3 to .40f, 2.0 to .65f, 3.0 to .60f, 3.0 + 2.0 / 3 to .40f))
                add(Note(b + beat, 2, w, offsetMs = 6f))
            add(Note(b + 1.0, 3, .55f)); add(Note(b + 3.0, 3, .55f))
            add(Note(b + 1.0, 1, .90f, offsetMs = 12f)); add(Note(b + 3.0, 1, .95f, offsetMs = 14f))
            add(Note(b + 2.0 + 2.0 / 3, 1, .25f, offsetMs = 10f))
            if (bar % 2 == 1) add(Note(b + 3.0 + 2.0 / 3, 1, .30f, offsetMs = 12f))
            // The shaker on swung eighths: soft on the beat, firmer on the swung "and" (the hand's back stroke),
            // the last of each bar lifted a touch.
            for (beat in 0 until 4) {
                add(Note(b + beat, 4, SHAKE_ON, offsetMs = 8f))
                add(Note(b + beat + 2.0 / 3, 4, if (beat == 3) SHAKE_AND + .08f else SHAKE_AND, offsetMs = 8f))
            }
            add(Note(b + 0.0, 0, .85f)); add(Note(b + 1.75, 0, .45f)); add(Note(b + 2.5, 0, .75f))
        }
    }, 12f, "loose-crate-blue")

    /** The shaker's weights: the forward stroke on the beat, the back stroke on the swung "and". */
    private const val SHAKE_ON = .35f
    private const val SHAKE_AND = .55f

    /**
     * The shaker's fader. A part that sits back needs a fader, not only a soft weight (round 0c): a note's level
     * follows its weight only gently ([LooseCrateKits.touch], never under 0.35 of full).
     */
    private const val SHAKER_FADER = .35f
    private val blueFaders = mapOf(4 to SHAKER_FADER)

    /** The shaker's fader in the softer comparison groove: about 3 dB under [SHAKER_FADER]. */
    private const val SHAKER_SOFTER = .25f

    /** BLUE's room: HUMMIN's, a little smaller and drier, for brushes; a soft compressor. */
    private val blueRoom = LooseCrateKits.Room(mapOf("SIZE" to .18f, "WALLS" to .50f, "DISTANCE" to .50f), mapOf("AMOUNT" to .35f, "ATTACK" to .80f, "RECOVER" to .50f),
        -4f, kickCurve = 1f, wireWeight = 0f, hatLevel = 1f, ringBarEnd = false, ride = null)

    // ---------- SPEED ----------

    /** 45 rpm over 33 1/3. */
    private const val SPEED_RATIO = 1.35
    private const val SPEED_SOURCE_BPM = 80

    // ---------- RAGA ----------

    private fun terra(voice: TerraVoice, vararg m: Pair<String, Float>): Strike = { v, t, _ ->
        Terra.render(voice, mapOf(*m), null, v, t).samples
    }

    private val ragaParts: List<Pair<String, Strike>> = listOf(
        "kick" to LooseCrateKits.thudKick,
        "snare" to LooseCrateKits.slapSnare,
        "dayan-na" to terra(TerraVoice.DAYAN, "TUNE" to .55f, "DECAY" to .55f, "FORCE" to .85f, "OPEN" to 1f, "CLICK" to .65f),
        "dayan-ti" to terra(TerraVoice.DAYAN, "TUNE" to .55f, "DECAY" to .30f, "FORCE" to .75f, "OPEN" to 0f, "CLICK" to .80f),
        "bayan-ge" to terra(TerraVoice.BAYAN, "TUNE" to .45f, "DECAY" to .60f, "FORCE" to .80f, "BEND" to 1f),
        "dholak" to terra(TerraVoice.DHOLAK, "TUNE" to .50f, "DECAY" to .45f, "FORCE" to .75f, "DROP" to .50f, "TREBLE" to .55f),
    )

    /**
     * Tabla and dholak over a hip-hop kick and snare at 94: the kick on one and the and of three, the slap on two and
     * four; the bayan's bending "ge" on the beat and pickups; the dayan running sixteenths, open "na" on the
     * accents and closed "ti" between; the dholak answering on the off-beats. A 10 ms seeded spread.
     */
    private val ragaNotes: List<Note> = Cypher.humanised(buildList {
        for (bar in 0 until BARS) {
            val b = bar * 4.0
            add(Note(b + 0.0, 0, 1f)); add(Note(b + 2.5, 0, .80f)); if (bar % 2 == 1) add(Note(b + 3.25, 0, .55f))
            add(Note(b + 1.0, 1, .90f, offsetMs = 10f)); add(Note(b + 3.0, 1, .95f, offsetMs = 12f))
            for ((beat, w) in listOf(0.0 to .90f, .75 to .55f, 2.0 to .80f, 2.75 to .50f, 3.5 to .45f)) add(Note(b + beat, 4, w))
            for (s in 0 until 16) {
                val accent = s % 4 == 0 || s == 6 || s == 14
                add(Note(b + s * .25, if (accent) 2 else 3, if (accent) .70f else if (s % 2 == 0) .45f else .30f, offsetMs = if (s % 2 == 1) 14f else 4f))
            }
            for (o in listOf(.5, 1.5, 2.5, 3.5)) add(Note(b + o, 5, if (o == 3.5) .65f else .45f, offsetMs = 8f))
        }
    }, 10f, "loose-crate-raga")

    private val ragaRoom = LooseCrateKits.Room(mapOf("SIZE" to .25f, "WALLS" to .50f, "DISTANCE" to .55f), mapOf("AMOUNT" to .40f, "ATTACK" to .80f, "RECOVER" to .50f),
        -4f, kickCurve = 1f, wireWeight = 0f, hatLevel = 1f, ringBarEnd = false, ride = null)

    // ---------- shared ----------

    private fun frames(bpm: Int) = Math.round(BARS * 4 * 60.0 / bpm * Dsp.RATE).toInt()

    /**
     * Notes summed at [bpm], through [room], folded onto [BARS] bars: the dry loop before the print. [faders] turns a
     * part's notes down as a whole (a mixing fader): a note's level follows its weight only gently (`touch`, at least
     * 0.35 of full), so weight alone cannot sit a part far back.
     */
    private fun loopDry(notes: List<Note>, bpm: Int, parts: List<Strike>, room: LooseCrateKits.Room, faders: Map<Int, Float> = emptyMap()): FloatArray {
        val n = frames(bpm)
        val raw = rawMix(notes, bpm, n.toFloat() / Dsp.RATE + 2.5f, parts, faders)
        val roomed = room.play(raw)
        return FloatArray(n).also { out -> for (i in roomed.indices) out[i % n] += roomed[i] }
    }

    /** The notes summed dry, each part in [faders] rendered on its own and scaled by its fader. */
    private fun rawMix(notes: List<Note>, bpm: Int, seconds: Float, parts: List<Strike>, faders: Map<Int, Float>): FloatArray {
        fun raw(only: List<Note>) = Cypher.phraseRaw(only, bpm, 1f, 0, LooseCrateKits.TAKES, seconds, LooseCrateKits::touch) { parts[it] }
        val out = raw(notes.filter { it.part !in faders })
        for ((part, gain) in faders) {
            val x = raw(notes.filter { it.part == part })
            for (i in out.indices) out[i] += x[i] * gain
        }
        return out
    }

    /**
     * A loop played [ratio] times faster, as a record at the wrong speed: the samples read as if recorded at
     * RATE x [ratio] and resampled to RATE. Three copies end to end and the middle one kept, so the resampler's
     * edges never reach the loop.
     */
    private fun sped(loop: FloatArray, ratio: Double): FloatArray {
        val n = loop.size
        val fast = Resampler.resample(Snip(FloatArray(3 * n) { loop[it % n] }, 1, Math.round(Dsp.RATE * ratio).toInt()), Dsp.RATE).samples
        val m = Math.round(n / ratio).toInt()
        return fast.copyOfRange(m, 2 * m)
    }

    private fun printedLoop(loop: FloatArray): FloatArray {
        val n = loop.size
        val p = LooseCrateKits.CRATE.apply(FloatArray(2 * n) { loop[it % n] }, phrase = true)
        val fade = (.02f * Dsp.RATE).toInt()
        return FloatArray(n) { j -> if (j < n - fade) p[n + j] else { val w = (j - (n - fade) + 1).toFloat() / fade; p[n + j] * (1 - w) + p[j] * w } }
    }

    private fun levelled(x: FloatArray): Snip {
        val mean = x.average().toFloat()
        val peak = x.maxOf { abs(it - mean) }
        return Snip(FloatArray(x.size) { (x[it] - mean) * .92f / max(peak, 1e-9f) }, 1, Dsp.RATE)
    }

    /** One printed hit at full velocity, optionally sped like the record. */
    private fun hit(strike: Strike, ratio: Double = 1.0): Snip {
        var x = KitStrikes.struck(strike, 1f, 0, LooseCrateKits.touch(1f))
        if (ratio != 1.0) x = Resampler.resample(Snip(x, 1, Math.round(Dsp.RATE * ratio).toInt()), Dsp.RATE).samples
        return KitStrikes.finish(LooseCrateKits.CRATE.apply(x, phrase = false), 1f).let { s ->
            val peak = s.samples.maxOf { abs(it) }
            Snip(FloatArray(s.samples.size) { s.samples[it] * .92f / max(peak, 1e-9f) }, 1, Dsp.RATE)
        }
    }

        @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/loose-crate-expansion-round0")
        fun write(name: String, s: Snip) = WavWriter.write(File(root, name), s).also { println("wrote ${it.path}") }

        val blue = loopDry(blueNotes, 90, blueParts.map { it.second }, blueRoom, blueFaders)
        write("blue/groove.wav", levelled(printedLoop(blue)))
        write("blue/groove-noprint.wav", levelled(blue))
        // The same groove with the shaker about 3 dB down, so the owner can choose its level by ear.
        write("blue/groove-shaker-softer.wav", levelled(printedLoop(loopDry(blueNotes, 90, blueParts.map { it.second }, blueRoom, mapOf(4 to SHAKER_SOFTER)))))
        for ((name, s) in blueParts) write("blue/hit-$name.wav", hit(s))
        // The shaker's share of the groove, dry and on one scale: the shaker alone and everything else alone.
        // Stems for measuring only.
        run {
            val strikes = blueParts.map { it.second }
            val seconds = frames(90).toFloat() / Dsp.RATE + 2.5f
            val k = .9f / rawMix(blueNotes, 90, seconds, strikes, blueFaders).maxOf { abs(it) }
            val sh = rawMix(blueNotes.filter { it.part == 4 }, 90, seconds, strikes, blueFaders)
            val rest = rawMix(blueNotes.filter { it.part != 4 }, 90, seconds, strikes, emptyMap())
            write("blue/stem-shaker.wav", Snip(FloatArray(sh.size) { sh[it] * k }, 1, Dsp.RATE))
            write("blue/stem-rest.wav", Snip(FloatArray(rest.size) { rest[it] * k }, 1, Dsp.RATE))
        }

        val hummin = LooseCrateKits.Kit.HUMMIN
        val source = loopDry(LooseCrateKits.played(hummin, LooseCrateKits.humminNotes(kick = 0, snare = 1, hat = 2, ghost = 5)), SPEED_SOURCE_BPM,
            List(LooseCrateKits.WIRES + 1) { if (it < LooseCrateKits.HITS || it == LooseCrateKits.WIRES) LooseCrateKits.strikeOf(hummin, it) else LooseCrateKits.strikeOf(hummin, 0) },
            LooseCrateKits.roomOf(hummin))
        val fast = sped(source, SPEED_RATIO)
        check(fast.size == frames(108)) { "SPEED's loop is ${fast.size} frames, 108 BPM is ${frames(108)}" }
        write("speed/groove.wav", levelled(printedLoop(fast)))
        write("speed/groove-noprint.wav", levelled(fast))
        write("speed/groove-source-80bpm.wav", levelled(printedLoop(source)))
        for ((name, role) in listOf("hum-kick" to 0, "flam-snare" to 1, "dark-hat" to 2, "thud-kick" to 6)) {
            write("speed/hit-$name.wav", hit(LooseCrateKits.strikeOf(hummin, role), SPEED_RATIO))
            write("speed/hit-$name-original.wav", hit(LooseCrateKits.strikeOf(hummin, role)))
        }

        val raga = loopDry(ragaNotes, 94, ragaParts.map { it.second }, ragaRoom)
        write("raga/groove.wav", levelled(printedLoop(raga)))
        write("raga/groove-noprint.wav", levelled(raga))
        for ((name, s) in ragaParts.drop(2)) write("raga/hit-$name.wav", hit(s))
    }
}

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
     * BLUE's ride, round 0h: "The ride should have less shimmer. It should be like these" (the owner, 2026-11-19, with
     * two recordings of jazz rides, used only to measure). Round 0f's shelf and lift had put air over 12 kHz: the
     * references' wash (0.2 to 0.6 s after the strike) holds 21 to 30 dB under its whole there, round 0g's 15. The
     * references live between 3 and 12 kHz, a bright stick ping over a wash that falls 20 dB in 0.5 to 1.3 s. The
     * print's converter filter and tape head (about 6 kHz) take that band, so round 0h shaped the ride before it:
     * EMBER's flat ride at TUNE .40, DAMP .30, BUZZ 0 (no rivets: EMBER's fitted cymbals start at 0); a 500 Hz low
     * cut; +12 dB at 8 kHz (peaking, Q .8) against the tape head; the strike 3 dB softer, back over 50 ms, so the
     * wash sat as the references' does against the strike; a steep cut at 12 kHz (two Butterworth low-passes,
     * 24 dB an octave): no shimmer. Round 0k ([stickRide]) keeps the presence and the cut and turns the rest.
     * Scored against the references by the share of five bands (under 1 kHz to over 12 kHz) at the strike, the wash
     * and the tail, and the wash's and tail's levels against the strike, the printed hit (BLUE's print, vinyl
     * included) sits 5.3 dB from the references' mean (round 0g 7.0; the two references sit 3.5 from it). What is
     * left is the print's own: vinyl hiss over 12 kHz in the tail and the strike's lows.
     */
    private val recordRide: Strike = stickRide(RIDE_FADE_SECONDS)

    /**
     * Round 0k: "It's not right. Tone down the sizzle, it should be a bright stick hit to the ride (not bell) with a
     * light and shimmery ring that fades quickly" (the owner, 2026-11-20). Round 0h had softened the strike to lift
     * the wash; this turns that round: the stick forward, the ring light and short. Against the round-0h references
     * (used only to measure) and the owner's words, on the printed hit:
     * - the stick's first 40 ms centred at 7.8 kHz (references 8.6 and 7.7, round 0j 7.1): a low cut at
     *   [RIDE_LOW_CUT_HZ] and the ride's own band over [RIDE_PING_HZ] added [RIDE_PING] times over, gone in
     *   [RIDE_PING_SECONDS] s (the ping);
     * - not bell: 1 to 3 kHz holds -17.5 dB of the stick (references -25 and -15, round 0j -12.5);
     * - the ring light, 21 dB under the stick from 0.2 to 0.6 s (references 13 and 8, round 0j 9), and fading
     *   quickly, 40 dB down at 0.65 s (references 1.8 and 3.2, round 0j 1.4): DAMP .55 and, after the first
     *   [RIDE_FADE_AFTER] s, an exponential fade of [fadeSeconds];
     * - the sizzle down with the ring; its peaks 21.6 dB over the floor between them (references 17.6 and 10).
     * Round 0h's +12 dB at 8 kHz against the tape head and its cut at 12 kHz stay, as do BLUE's print's full-rate
     * converter and steady tape.
     */
    private fun stickRide(fadeSeconds: Double): Strike = { v, t, _ ->
        val x = Ember.render(EmberVoice.RIDE, mapOf("TUNE" to .40f, "DAMP" to .55f, "BUZZ" to 0f, "DISTANCE" to 0f, "WARMTH" to 0f), v, t).samples
        val a = Dsp.FixedOnePole(RIDE_LOW_CUT_HZ)
        val b = Dsp.FixedOnePole(RIDE_LOW_CUT_HZ)
        val presence = Dsp.Biquad().apply { peaking(RIDE_PRESENCE_HZ, RIDE_PRESENCE_DB, .8f) }
        val c = Dsp.FixedOnePole(RIDE_PING_HZ)
        val d = Dsp.FixedOnePole(RIDE_PING_HZ)
        val shaped = FloatArray(x.size) {
            val h1 = x[it] - a.lp(x[it])
            val y = presence.process(h1 - b.lp(h1))
            val p1 = y - c.lp(y)
            val seconds = it.toDouble() / Dsp.RATE
            val pinged = y + RIDE_PING * (p1 - d.lp(p1)) * Math.exp(-seconds / RIDE_PING_SECONDS).toFloat()
            if (seconds <= RIDE_FADE_AFTER) pinged else pinged * Math.exp(-(seconds - RIDE_FADE_AFTER) / fadeSeconds).toFloat()
        }
        lowPass(lowPass(shaped, RIDE_TOP_HZ), RIDE_TOP_HZ)
    }

    private const val RIDE_LOW_CUT_HZ = 2_000f
    private const val RIDE_PRESENCE_HZ = 8_000f
    private const val RIDE_PRESENCE_DB = 12f
    private const val RIDE_PING_HZ = 5_000f
    private const val RIDE_PING = 1.5f
    private const val RIDE_PING_SECONDS = .010
    private const val RIDE_FADE_AFTER = .03
    private const val RIDE_FADE_SECONDS = .35
    /** The comparison ring, a little longer: 40 dB down at 0.77 s. */
    private const val RIDE_FADE_LONGER = .50
    private const val RIDE_TOP_HZ = 12_000f

    /** A Butterworth low-pass (RBJ's cookbook, Q 0.7071), 12 dB an octave; twice over, 24. */
    private fun lowPass(x: FloatArray, hz: Float): FloatArray {
        val w = 2 * Math.PI * hz / Dsp.RATE
        val alpha = Math.sin(w) / (2 * .7071)
        val cw = Math.cos(w)
        val a0 = 1 + alpha
        val b0 = (1 - cw) / 2 / a0
        val b1 = (1 - cw) / a0
        val a1 = -2 * cw / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return FloatArray(x.size) {
            val xi = x[it].toDouble()
            val y = b0 * xi + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            y.toFloat()
        }
    }

    private val blueParts: List<Pair<String, Strike>> = listOf(
        "felt-kick" to ember(EmberVoice.KICK, "TUNE" to .35f, "DAMP" to .65f, "BUZZ" to .15f),
        "brush-tap" to ember(EmberVoice.BRUSH_TAP, "TUNE" to .55f, "DAMP" to .35f, "BUZZ" to .55f),
        "ride" to recordRide,
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

    /**
     * Round 0i, three grooves on the same sounds: "Maybe the groove isn't good. It just isn't clicking" (the owner,
     * 2026-11-19). Round 0's groove mixed two grids: the ride, shaker and ghosts on triplet swing, the kick on straight
     * sixteenths (1.75 and 2.5, landing 56 ms after and 111 ms before the triplet notes beside them at 90), and every
     * note moved by its own random draw of up to 12 ms, so the parts did not lock. Each of these keeps one grid, a
     * fixed feel per part (the brushes laid back, the ride on top, the kick on the grid) and a random spread of
     * [POCKET_SPREAD_MS]. Shape values, unheard.
     * - [pocketNotes]: round 0's idea on one grid. The kick on the triplet swing (1, the swung "and" of 2 soft, the
     *   swung "and" of 3), the brush ghosts moved off the kick, the shaker only on the swung "and" (the back stroke).
     * - [breakNotes]: a hip-hop break instead of jazz time. Sixteenths swung at [BREAK_SWING] (an MPC's swing), the ride
     *   on swung eighths, the kick "boom, b-boom" (1, the "a" of 1 soft, the "and" of 3), the brushes on two and four
     *   with a ghost on the "a" of 4 every other bar, the shaker out.
     * - [pocketNotes] without the shaker, for space.
     */
    private fun pocket(build: MutableList<Note>.() -> Unit): List<Note> {
        val laid = mapOf(0 to 0f, 1 to 16f, 2 to -4f, 3 to 6f, 4 to 4f)
        return Cypher.humanised(buildList(build).map { Note(it.beat, it.part, it.weight, laid.getValue(it.part)) }, POCKET_SPREAD_MS, "loose-crate-blue-pocket")
    }

    private const val POCKET_SPREAD_MS = 4f
    private const val T = 2.0 / 3

    private val pocketNotes: List<Note> = pocket {
        for (bar in 0 until BARS) {
            val b = bar * 4.0
            for ((beat, w) in listOf(0.0 to .70f, 1.0 to .60f, 1 + T to .40f, 2.0 to .65f, 3.0 to .60f, 3 + T to .40f)) add(Note(b + beat, 2, w))
            add(Note(b + 1.0, 3, .55f)); add(Note(b + 3.0, 3, .55f))
            add(Note(b + 1.0, 1, .90f)); add(Note(b + 3.0, 1, .95f))
            add(Note(b + T, 1, .22f))
            if (bar % 2 == 1) add(Note(b + 3 + T, 1, .28f))
            for (beat in 0 until 4) add(Note(b + beat + T, 4, if (beat == 3) SHAKE_AND + .08f else SHAKE_AND))
            add(Note(b + 0.0, 0, .85f)); add(Note(b + 1 + T, 0, .45f)); add(Note(b + 2 + T, 0, .75f))
        }
    }

    /** How late a swung sixteenth lands, as a share of its eighth: 0.5 is straight, 0.58 an MPC's gentle swing. */
    private const val BREAK_SWING = .58

    /** The [i]th sixteenth of a bar, the odd ones swung by [BREAK_SWING]. */
    private fun sixteenth(i: Int) = (i / 2) * .5 + if (i % 2 == 1) BREAK_SWING * .5 else 0.0

    private val breakNotes: List<Note> = pocket {
        for (bar in 0 until BARS) {
            val b = bar * 4.0
            for (i in 0 until 8) add(Note(b + sixteenth(2 * i), 2, if (i % 2 == 0) .65f else .38f))
            add(Note(b + 1.0, 3, .50f)); add(Note(b + 3.0, 3, .50f))
            add(Note(b + 1.0, 1, .92f)); add(Note(b + 3.0, 1, .95f))
            if (bar % 2 == 1) add(Note(b + 3 + sixteenth(3), 1, .30f))
            add(Note(b + 0.0, 0, .90f)); add(Note(b + sixteenth(3), 0, .50f)); add(Note(b + sixteenth(10), 0, .80f))
        }
    }

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

    /**
     * BLUE's print, round 0g: the crate print with the tape's wobble off. "It's too noisy", the ride hissy (the owner,
     * 2026-11-19, of round 0f's brighter ride). The ride is all ringing modes dry and stays so through the room and
     * the converter; the tape's wow and flutter (WOBBLE .08, about 0.1% of pitch) smear its hundreds of high modes
     * into swish. The share of the ride's 5 to 14 kHz wash (0.2 to 0.6 s) in peaks 8 dB over the local floor, through
     * the converter and the tape: round 0e's ride 73%, round 0f's 57%, round 0f's with no wobble 94% (no drive: 57%,
     * no change). On a kit led by a ride, a steady deck. [LooseCrateKits.CRATE] itself, and the approved kits, keep it.
     *
     * Round 0j, the converter at its full rate: "The ride is too noisy" (the owner, 2026-11-20, of round 0h's ride).
     * The converter holds its input at about 32 kHz (RATE .22), not a divisor of 44.1 kHz, so a cymbal's top folds
     * back as tones in tune with nothing: through the converter alone the ride's wash (0.2 to 0.6 s) fell from 30.0
     * to 15.5 dB of ringing peaks over the floor between them (200 Hz to 16 kHz, the floor a 150 Hz running median),
     * its floor from 2 to 8 kHz rising from -43.6 to -29.3 dB of the strike. Bit depth (16 bits) changed nothing, nor
     * did a gentler hold (RATE .05). With RATE 0 the printed hit's wash is 27.6 dB over its floor (round 0h 14.7), its
     * shape against the references unchanged (5.4 dB from their mean, round 0h 5.3). The references measure 17.6 and
     * 10.0: a real cymbal's fizz is dense, in tune with the cymbal; the fold-back was not. The kick, brushes, hat and
     * shaker carry little over 16 kHz to fold; the 12 bits, the tape and the vinyl stay.
     */
    private val BLUE_PRINT = LooseCrateKits.CRATE.let { c ->
        LooseCrateKits.Print("blue", c.crunch + ("RATE" to 0f), c.tape + ("WOBBLE" to 0f), c.hitVinyl, c.grooveVinyl, c.valve, c.bassSqueeze)
    }

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

    private fun printedLoop(loop: FloatArray, print: LooseCrateKits.Print = LooseCrateKits.CRATE): FloatArray {
        val n = loop.size
        val p = print.apply(FloatArray(2 * n) { loop[it % n] }, phrase = true)
        val fade = (.02f * Dsp.RATE).toInt()
        return FloatArray(n) { j -> if (j < n - fade) p[n + j] else { val w = (j - (n - fade) + 1).toFloat() / fade; p[n + j] * (1 - w) + p[j] * w } }
    }

    private fun levelled(x: FloatArray): Snip {
        val mean = x.average().toFloat()
        val peak = x.maxOf { abs(it - mean) }
        return Snip(FloatArray(x.size) { (x[it] - mean) * .92f / max(peak, 1e-9f) }, 1, Dsp.RATE)
    }

    /** One printed hit at full velocity, optionally sped like the record. */
    private fun hit(strike: Strike, ratio: Double = 1.0, print: LooseCrateKits.Print = LooseCrateKits.CRATE): Snip {
        var x = KitStrikes.struck(strike, 1f, 0, LooseCrateKits.touch(1f))
        if (ratio != 1.0) x = Resampler.resample(Snip(x, 1, Math.round(Dsp.RATE * ratio).toInt()), Dsp.RATE).samples
        return KitStrikes.finish(print.apply(x, phrase = false), 1f).let { s ->
            val peak = s.samples.maxOf { abs(it) }
            Snip(FloatArray(s.samples.size) { s.samples[it] * .92f / max(peak, 1e-9f) }, 1, Dsp.RATE)
        }
    }

        @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/loose-crate-expansion-round0")
        fun write(name: String, s: Snip) = WavWriter.write(File(root, name), s).also { println("wrote ${it.path}") }

        val blue = loopDry(blueNotes, 90, blueParts.map { it.second }, blueRoom, blueFaders)
        write("blue/groove.wav", levelled(printedLoop(blue, BLUE_PRINT)))
        write("blue/groove-noprint.wav", levelled(blue))
        // The same groove with the shaker about 3 dB down, so the owner can choose its level by ear.
        write("blue/groove-shaker-softer.wav", levelled(printedLoop(loopDry(blueNotes, 90, blueParts.map { it.second }, blueRoom, mapOf(4 to SHAKER_SOFTER)), BLUE_PRINT)))
        for ((tag, notes) in listOf("a-pocket" to pocketNotes, "b-break" to breakNotes, "c-pocket-no-shaker" to pocketNotes.filter { it.part != 4 }))
            write("blue/groove-$tag.wav", levelled(printedLoop(loopDry(notes, 90, blueParts.map { it.second }, blueRoom, blueFaders), BLUE_PRINT)))
        for ((name, s) in blueParts) write("blue/hit-$name.wav", hit(s, print = BLUE_PRINT))
        // Round 0k's ring a little longer, for the owner to judge "fades quickly" by ear.
        write("blue/hit-ride-longer.wav", hit(stickRide(RIDE_FADE_LONGER), print = BLUE_PRINT))
        write("blue/groove-a-pocket-ride-longer.wav", levelled(printedLoop(loopDry(pocketNotes, 90,
            blueParts.map { it.second }.toMutableList().also { it[2] = stickRide(RIDE_FADE_LONGER) }, blueRoom, blueFaders), BLUE_PRINT)))

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

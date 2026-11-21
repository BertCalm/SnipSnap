package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** The six instruments. Any of them can lead, and any can sit in a harmony chair. */
enum class ConsortVoice { FLUTE, SAX, CLARINET, OBOE, BASSOON, DUDUK }

/** A harmony chair's fixed distance from the lead (the owner's choice over scale-aware harmony, 2026-11-19). */
enum class ConsortInterval(val semitones: Int) { OCT_DOWN(-12), UNISON(0), FIFTH(7), MIN7(10), MAJ7(11), OCT_UP(12) }

/** One harmony player: which instrument, and how far from the lead. */
data class ConsortChair(val voice: ConsortVoice, val interval: ConsortInterval)

/** What the GESTURE knob steps through. */
enum class ConsortGesture { PLAIN, SCOOP, TRILL, FLUTTER, FALL }

/**
 * CONSORT: a woodwind section in one pad (docs/superpowers/specs/2026-11-19-consort-woodwind-ensemble-design-brief.md).
 *
 * A lead and up to three harmony players, each a separate blown pipe. It is put together in four
 * layers, and each layer is one function below:
 *
 *  1. **A player is a plan before it is a sound** ([plan]). From the note, the velocity and the macros
 *     the plan writes four curves over the note: mouth pressure, pitch offset in cents, breath noise,
 *     and (flute) how long the air jet takes to cross the mouth hole. A trill, a fall, a flutter-tongue
 *     and an overblow are shapes in those curves, so a new ornament is a new shape, not a new synthesizer.
 *  2. **A pipe turns the plan into sound** ([blow]). It is BORE's loop ([Strings.Loop.reflected] /
 *     [Strings.Loop.inject]) with BORE's two valves: Smith's reed reflection table, clamped so it can
 *     never add energy, and the air jet. Three pipes: a cone (SAX, OBOE, BASSOON: full-period loop,
 *     every harmonic), a closed cylinder (CLARINET, DUDUK: half-period loop with its sign flipped, so
 *     only the odd harmonics, which is the hollow sound) and the jet (FLUTE), whose transit time halves
 *     to crack the note up an octave.
 *  3. **A body is a fixed tone shape** ([body]): the bell's tilt and two or three formants that stay at
 *     the same frequencies whatever note is played. This is why a harmony player is their own pipe and
 *     not a pitch-shifted copy: shifting a copy drags the formants with it and the instrument changes size.
 *  4. **A room seats them** ([seat], [wash]): each chair has its own first wall bounces and distance
 *     from the microphone, then all share one wash, the rack ROOM's own network ([Room.bloom]).
 *
 * **Velocity is breath effort**, not level (every render is levelled to the same loudness): effort is
 * `0.5 BREATH + 0.6 velocity`, and above [OVERBLOW_FROM] the flute cracks to its octave and the reeds
 * growl. A reed with no register hole does not overblow (BORE's finding), so the growl is what a hard
 * reed does here; nothing claims a reed register jump.
 *
 * **Takes are ways of playing**, not random dust ([TAKES]). The owner could not hear takes that only
 * re-rolled a few cents and milliseconds, and could hear these (2026-11-19, "this is better").
 *
 * What was heard, and on what: the owner heard the Python prototype over three rounds
 * (docs/prototypes/consort/consort_proto.py), then this engine's K1 audition, and approved it
 * (2026-11-20: "Yes, I listened; it sounds good"). That render is the accepted baseline; a change here
 * that moves the sound needs a new listen, and a change that should not is measured against it.
 *
 * HOLD is breath length. Its top step ([LOOP_FROM], BORE's) is a held note as one seamless loop
 * ([renderLoop]), for a gated pad that sustains while it is held: a held note ([heldNote]) whose slow
 * motions, noise and players repeat every eight seconds with deeper wanders ([PAD_HELD], the owner's
 * pick), cut once and closed by a blend. Every HOLD under the step renders as before.
 */
object Consort {

    internal enum class Pipe { CONE, CYL, JET }

    /**
     * One instrument. [offset] is where the reed rests (lower is looser and buzzier), [bell] the loop's
     * low-pass in Hz (for the flute, a multiple of the note), [rasp] how hard the reed beats, [vibHz]
     * and [vibCents] the vibrato (from the owner's 24 references: 3.0 to 6.1 Hz, 6 to 22 cents),
     * [formants] the body's fixed bumps as (Hz, dB, Q).
     */
    internal class Spec(
        val pipe: Pipe, val root: Int, val rasp: Float, val offset: Float, val bell: Float,
        val noiseLow: Float, val noiseHigh: Float, val vibHz: Float, val vibCents: Float,
        val attack: Float, val tiltHz: Float, val formants: List<Triple<Float, Float, Float>>,
    )

    internal fun spec(voice: ConsortVoice): Spec = when (voice) {
        ConsortVoice.FLUTE -> Spec(Pipe.JET, 60, 0.8f, 0f, 3.5f, 0.08f, 0.55f, 4.8f, 9f, 0.060f, 2400f,
            listOf(Triple(800f, 3f, 1.2f), Triple(2600f, 4f, 1.5f)))
        ConsortVoice.SAX -> Spec(Pipe.CONE, 48, 3.2f, 0.60f, 2600f, 0.04f, 0.30f, 4.6f, 14f, 0.035f, 4200f,
            listOf(Triple(650f, 5f, 1.6f), Triple(1450f, 6f, 2.0f), Triple(2900f, 7f, 2.0f)))
        ConsortVoice.CLARINET -> Spec(Pipe.CYL, 50, 1.6f, 0.68f, 2400f, 0.02f, 0.16f, 3.2f, 6f, 0.045f, 3000f,
            listOf(Triple(1550f, 4f, 2.0f), Triple(3600f, 3f, 2.0f)))
        ConsortVoice.OBOE -> Spec(Pipe.CONE, 58, 3.6f, 0.74f, 3400f, 0.015f, 0.12f, 4.4f, 10f, 0.030f, 3600f,
            listOf(Triple(1100f, 9f, 3.0f), Triple(2850f, 8f, 3.0f)))
        ConsortVoice.BASSOON -> Spec(Pipe.CONE, 34, 2.6f, 0.70f, 1900f, 0.02f, 0.15f, 5.3f, 13f, 0.050f, 2400f,
            listOf(Triple(470f, 8f, 2.5f), Triple(1150f, 6f, 2.5f), Triple(2000f, 2f, 2.0f)))
        ConsortVoice.DUDUK -> Spec(Pipe.CYL, 57, 1.1f, 0.56f, 1500f, 0.05f, 0.28f, 5.1f, 20f, 0.110f, 1500f,
            listOf(Triple(600f, 5f, 1.5f), Triple(1250f, 4f, 2.0f)))
    }

    /**
     * A take: one way of playing the note. [effort] is added to breath effort, [attack] multiplies the
     * attack time, [chiff] the tongue's noise and seed, [scoop] is cents under at the start, [vibAt]
     * seconds before vibrato enters, and [vibRate], [vibDepth], [air] are multipliers. Level and pitch
     * centre are never varied: a take must be the same note.
     */
    internal class Take(
        val name: String, val effort: Float, val attack: Float, val chiff: Float, val scoop: Float,
        val vibAt: Float, val vibRate: Float, val vibDepth: Float, val air: Float,
    )

    internal val TAKES = listOf(
        Take("centred", 0.00f, 1.00f, 1.0f, 0f, 0.18f, 1.00f, 1.0f, 1.0f),
        Take("soft", -0.15f, 2.20f, 0.3f, 0f, 0.42f, 0.92f, 0.7f, 1.5f),
        Take("bitten", 0.14f, 0.45f, 2.6f, 0f, 0.08f, 1.08f, 1.2f, 0.8f),
        Take("leaning", 0.04f, 1.30f, 1.0f, 110f, 0.30f, 1.00f, 1.5f, 1.1f),
    )

    const val TUNE_SEMITONES = 24

    /** The ways of playing a take steps through ([TAKES]): the picker's TAKE buttons. A later take repeats a way with fresh humanization. */
    val TAKE_COUNT: Int get() = TAKES.size
    const val MAX_CHAIRS = 3
    const val DEFAULT_VELOCITY = 0.7f

    private const val INTERNAL = RATE * Dsp.OVERSAMPLE
    /** Pitch is re-solved every this many internal samples, BORE's vibrato interval. */
    private const val BLOCK = 64
    private const val REED_SLOPE = -0.3f          // Bore.REED_SLOPE
    private const val LOOP_GAIN = 0.95f           // Bore.LOOP_GAIN
    const val OVERBLOW_FROM = 0.85f
    private const val GROWL_HZ = 31.0             // Bore.OVERBLOW_GROWL_HZ, the rate the owner chose by ear for BORE
    private const val FLUTTER_HZ = 24.0           // a flutter-tongue is 20 to 30 rolls a second
    private const val RELEASE_SECONDS = 0.09f
    /** How much louder the breath noise is at the tongue than in the held note: the chiff. */
    private const val CHIFF = 2.5f
    private const val TOP_HZ = 9_000f
    /**
     * The breath noise's own low-pass. White noise at the internal rate reaches 88 kHz, and the body's
     * tilt then lifts what survives of it: measured, the shortest notes (HOLD 0) of FLUTE, SAX, BASSOON
     * and DUDUK filed as SNARE (flatness 0.26 to 0.36 against the classifier's 0.25, 12 of 180 renders).
     * A corner at 8 kHz still misfiled 2 of 180; 5 kHz and 3 kHz none. The noise below the corner is
     * untouched, so the breath a listener hears keeps its level; a 5 kHz low-pass on the whole voice
     * also cleared it, and would have darkened every harmonic to do so.
     */
    private const val AIR_HZ = 3_000f
    private const val OUTPUT_DC_HZ = 20f

    const val HOLD_MIN_SECONDS = 0.3f             // Bore.HOLD_MIN_SECONDS
    const val HOLD_MAX_SECONDS = 4f               // Bore.HOLD_MAX_SECONDS
    /** After the breath stops: the pipe's release and the latest player's lateness. */
    private const val AFTER_SECONDS = 0.35f
    /** The classifier files anything over this as a loop (`Classifier.LOOP_MIN_SECONDS`, private there). */
    const val LOOP_THRESHOLD_SECONDS = 1.5f

    // The room: 9 x 7 x 3.6 m, the microphone at MIC, the lead closest and the others behind and to the sides.
    private val ROOM_DIMS = floatArrayOf(9.0f, 7.0f, 3.6f)
    private val MIC = floatArrayOf(4.5f, 2.0f, 1.5f)
    private val CHAIRS = arrayOf(
        floatArrayOf(4.4f, 3.2f, 1.2f), floatArrayOf(3.1f, 4.3f, 1.2f),
        floatArrayOf(5.8f, 4.5f, 1.2f), floatArrayOf(4.6f, 5.4f, 1.3f),
    )
    private const val SPEED_OF_SOUND = 343f       // Room's own
    private const val BOUNCE_GAIN = 0.75f
    private const val WASH_MIN_SECONDS = 0.35f
    private const val WASH_MAX_SECONDS = 0.9f     // Room's MAX_RT60

    private val MACROS = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("BREATH", 0.6f),
        MacroSpec("GESTURE", 0f),
        MacroSpec("MOTION", 0.5f),
        MacroSpec("BLEND", 0.6f),
        MacroSpec("LOOSE", 0.5f),
        MacroSpec("ROOM", 0.4f),
        MacroSpec("HOLD", 0.45f),
    )

    /** Eight controls, the same for every voice: the voice is the instrument, not a different panel. */
    @Suppress("UNUSED_PARAMETER")
    fun macrosFor(voice: ConsortVoice): List<MacroSpec> = MACROS

    fun defaults(voice: ConsortVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(macros: Map<String, Float>, voice: ConsortVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun rootMidi(voice: ConsortVoice): Int = spec(voice).root
    fun midiFor(voice: ConsortVoice, tune: Float): Int = rootMidi(voice) + Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
    fun frequencyFor(voice: ConsortVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    fun gestureFor(value: Float): ConsortGesture {
        val all = ConsortGesture.entries
        return all[min(all.size - 1, (value.coerceIn(0f, 1f) * all.size).toInt())]
    }

    /** The middle of a gesture's step on the GESTURE knob. */
    fun gestureValue(gesture: ConsortGesture): Float = (gesture.ordinal + 0.5f) / ConsortGesture.entries.size

    fun holdSeconds(hold: Float): Float = Dsp.expMap(hold, HOLD_MIN_SECONDS, HOLD_MAX_SECONDS)

    /** Where HOLD's top step starts, BORE's [Bore.LOOP_THRESHOLD]: from here a render is one seamless loop. */
    const val LOOP_FROM = Bore.LOOP_THRESHOLD

    /** HOLD's top step: the render is one seamless loop for a gated, sustaining pad ([renderLoop]). */
    fun isLoop(hold: Float): Boolean = hold >= LOOP_FROM

    private fun washSeconds(room: Float): Float = Dsp.lin(room, WASH_MIN_SECONDS, WASH_MAX_SECONDS)

    /** The render's length in output frames, fixed by HOLD and ROOM alone, so [drumClassFor] agrees with it to the frame. */
    internal fun renderFrames(m: Map<String, Float>): Int {
        val dry = ((holdSeconds(m.getValue("HOLD")) + AFTER_SECONDS) * RATE).toInt()
        val room = m.getValue("ROOM")
        return if (room > 0f) dry + (washSeconds(room) * 1.3f * RATE).toInt() else dry
    }

    fun drumClassFor(voice: ConsortVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return DrumClass.LOOP
        return if (renderFrames(m).toFloat() / RATE > LOOP_THRESHOLD_SECONDS) DrumClass.LOOP else DrumClass.PERC
    }

    /** SCRAMBLE near the defaults or near [near]. GESTURE is rolled like any knob: it is a choice of ornament. */
    fun scramble(voice: ConsortVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        val rolled = Dsp.scrambleNear(seed, temperature, random)
        // HOLD's top step (a sustaining loop) is a choice, not a roll: BORE caps it the same way
        return rolled + ("HOLD" to rolled.getValue("HOLD").coerceAtMost(Math.nextDown(LOOP_FROM)))
    }

    /** Velocity rides on BREATH: BREATH sets where the player sits, the hit pushes from there. */
    internal fun effortOf(breath: Float, velocity: Float): Float = (0.5f * breath + 0.6f * velocity).coerceIn(0f, 1f)

    /**
     * Slow seeded wander in -1..1: white noise through two one-poles at 1 kHz, read by linear
     * interpolation. It is what stops a held pitch, a breath or a vibrato from being a straight line.
     */
    internal class Wander(seed: Int, cornerHz: Float, seconds: Float, private val period: Float = 0f) {
        private val y: FloatArray
        init {
            if (period > 0f) {
                // Periodic (a held note's, [HELD_PERIOD_SECONDS]): the same filter run round a ring of noise until its
                // state is the ring's own, so the last point runs on into the first. Statistically the same wander.
                val m = Math.round(period * 1000f)
                val noise = Dsp.Noise(seed)
                val white = FloatArray(m) { noise.next() }
                val a = exp(-2.0 * PI * cornerHz / 1000.0).toFloat()
                y = FloatArray(m + 1)
                var s1 = 0f
                var s2 = 0f
                repeat(WANDER_RING_PASSES) { pass ->
                    for (i in 0 until m) {
                        s1 = a * s1 + (1f - a) * white[i]
                        s2 = a * s2 + (1f - a) * s1
                        if (pass == WANDER_RING_PASSES - 1) y[i] = s2
                    }
                }
                y[m] = y[0]
                var peak = 1e-9f
                for (i in 0 until m) peak = max(peak, abs(y[i]))
                for (i in 0..m) y[i] /= peak
            } else {
                val m = (seconds * 1000f).toInt() + 4
                val noise = Dsp.Noise(seed)
                val a = exp(-2.0 * PI * cornerHz / 1000.0).toFloat()
                y = FloatArray(m)
                var s1 = 0f
                var s2 = 0f
                var peak = 1e-9f
                for (i in 0 until m) {
                    s1 = a * s1 + (1f - a) * noise.next()
                    s2 = a * s2 + (1f - a) * s1
                    y[i] = s2
                    peak = max(peak, abs(s2))
                }
                for (i in 0 until m) y[i] /= peak
            }
        }

        fun at(seconds: Double): Float {
            if (period > 0f) {
                val m = y.size - 1
                val x = Math.floorMod((seconds * 1000.0).toLong(), m.toLong()).toDouble() + (seconds * 1000.0 - floor(seconds * 1000.0))
                val i = x.toInt()
                return y[i] + (y[i + 1] - y[i]) * (x - i).toFloat()
            }
            val x = (seconds * 1000.0).coerceIn(0.0, (y.size - 2).toDouble())
            val i = x.toInt()
            val f = (x - i).toFloat()
            return y[i] + (y[i + 1] - y[i]) * f
        }
    }

    /** What the pipe is blown with: one value a sample, plus the few numbers the pipe needs once. */
    internal class Plan(
        val pressure: FloatArray, val cents: FloatArray, val noise: FloatArray, val jet: FloatArray,
        val effort: Float, val over: Float, val seed: Int, val late: Float, val attack: Float, val tongue: Float,
        val way: String,
        /** A held note's period in internal samples ([HELD_PERIOD_SECONDS]): the breath's noise starts over each one. 0 when not held. */
        val ring: Int = 0,
    )

    /**
     * The player. [role] 0 is the lead; harmony players (1..3) breathe, scoop and fall with the lead but
     * only the lead trills and flutters, the way a section holds under a soloist's ornament. [frames] is
     * at the internal rate.
     */
    internal fun plan(
        voice: ConsortVoice, midi: Int, velocity: Float, m: Map<String, Float>, role: Int, frames: Int, take: Int,
        held: Held? = null, trimCents: Float = 0f,
    ): Plan {
        val s = spec(voice)
        val seconds = frames.toFloat() / INTERNAL
        // a take reseeds everything a player does differently and nothing about the instrument
        val seed = Dsp.seedFor("CONSORT", voice.name, midi, role, take)
        val gesture = gestureFor(m.getValue("GESTURE"))
        val motion = m.getValue("MOTION")
        // LOOSE is how human the players are. The lead gets 60 % of it: steadier than the section, not a machine.
        val loose = if (role > 0) m.getValue("LOOSE") else 0.6f * m.getValue("LOOSE")
        val blown = if (held != null) held.seconds else holdSeconds(m.getValue("HOLD"))

        // this chair's way of playing on this take; chairs are staggered so a section does not all bite together
        val way = TAKES[Math.floorMod(take + role, TAKES.size)]
        val stray = Dsp.lin(m.getValue("LOOSE"), 0.3f, 1.5f)
        val base = effortOf(m.getValue("BREATH"), velocity)
        val varied = (base + stray * way.effort).coerceIn(0f, 1f)
        // a take may not push a note across the overblow line: that is velocity's job, not chance's
        val effort = if (base < OVERBLOW_FROM || held != null) min(varied, OVERBLOW_FROM - 0.01f) else max(varied, OVERBLOW_FROM)
        val over = max(0f, (effort - OVERBLOW_FROM) / (1f - OVERBLOW_FROM))

        val rnd = Dsp.Noise(seed + 7)
        val late = if (role > 0) (0.004f + 0.031f * abs(rnd.next())) * loose else 0f
        // six ways no two players are alike, each drawn once per player per take and scaled by LOOSE
        val flat = (if (role > 0) 12f else 4f) * loose * rnd.next()      // where they sit against the pitch (the lead is the reference)
        val attackScale = 1f + 0.45f * loose * rnd.next()                // how quickly their note speaks
        val stop = 0.07f * loose * rnd.next()                            // who lets go first
        val release = RELEASE_SECONDS * (1f + 0.6f * loose * abs(rnd.next()))
        val under = 1f + 0.8f * loose * rnd.next()                       // how far under the note they start
        val vibLate = 0.12f * loose * rnd.next()                         // when their vibrato comes in
        val air = 1f + 0.5f * loose * rnd.next()                         // how much air leaks
        val vibRateOwn = 1f + 0.06f * rnd.next()
        val vibPhase0 = 2.0 * PI * abs(rnd.next())
        val ends = late + blown + stop

        val attack = s.attack * Dsp.lin(effort, 1.5f, 0.6f) * attackScale * way.attack.pow(stray)   // a hard blow speaks faster
        // a held note's slow motions repeat every HELD_PERIOD_SECONDS, so its sustain can loop ([heldNote])
        val ring = held?.period ?: 0f
        val breathWander = Wander(seed + 17, 2.5f, seconds, ring)
        val drift = Wander(seed + 11, 0.8f, seconds, ring)
        val rateWander = Wander(seed + 19, 1.2f, seconds, ring)
        val depthWander = Wander(seed + 23, 0.9f, seconds, ring)
        val trillWander = Wander(seed + 13, 2.0f, seconds, ring)
        val dutyWander = Wander(seed + 29, 3.0f, seconds, ring)
        val airWander = Wander(seed + 31, 4.0f, seconds, ring)

        val vibHz = s.vibHz * vibRateOwn * way.vibRate.pow(stray)
        val vibBase = s.vibCents * (if (gesture == ConsortGesture.PLAIN) Dsp.lin(motion, 0.2f, 1.6f) else 0.6f) * way.vibDepth.pow(stray)
        val vibStart = 0.18f + stray * (way.vibAt - 0.18f) + vibLate
        val driftCents = Dsp.lin(loose, 2f, 14f) * (held?.wander ?: 1f)
        val underCents = Dsp.lin(effort, 6f, 22f) * under
        val takeScoop = stray * way.scoop
        val scoopCents = Dsp.lin(motion, 80f, 300f)
        val scoopTau = Dsp.lin(motion, 0.05f, 0.14f)
        val fallLength = Dsp.lin(motion, 0.15f, 0.6f)
        val fallCents = Dsp.lin(motion, 400f, 1200f)
        val trillBase = Dsp.lin(motion, 5f, 8f)
        val trillGrow = Dsp.lin(motion, 0.5f, 3f)
        val flutterDepth = Dsp.lin(motion, 0.25f, 0.75f)
        val growl = 0.4f * over.pow(1.2f)
        val noiseBase = Dsp.lin(effort, s.noiseLow, s.noiseHigh) * air * way.air.pow(stray)
        val chiff = CHIFF * way.chiff.pow(stray)
        val jetPipe = s.pipe == Pipe.JET
        val trilling = role == 0 && gesture == ConsortGesture.TRILL
        val fluttering = role == 0 && gesture == ConsortGesture.FLUTTER

        val pressure = FloatArray(frames)
        val cents = FloatArray(frames)
        val noise = FloatArray(frames)
        val jet = FloatArray(frames)
        // the trill's "upper note" flag, smoothed below: each finger move takes about 12 ms, not zero
        val upper = if (trilling) FloatArray(frames) else null
        // The slow parts of a performance (the breath's swell and wander, the pitch's drift and settle, the
        // vibrato's depth and speed, the air) change over tens of milliseconds, so they are worked out once
        // every BLOCK samples (0.36 ms) and drawn as straight lines between. Only what must be exact to the
        // sample is done per sample: the attack and release, the vibrato's own sine, the flutter and the growl.
        // Measured on a default OBOE note: the plan takes 6 ms of a 53 ms dry render this way.
        val dt = 1.0 / INTERNAL
        val blocks = frames / BLOCK + 2
        val slowEnv = FloatArray(blocks)
        val slowCents = FloatArray(blocks)
        val slowScale = FloatArray(blocks)
        val slowDepth = FloatArray(blocks)
        val slowRate = FloatArray(blocks)
        val slowNoise = FloatArray(blocks)
        val slowTrill = FloatArray(blocks)
        val slowDuty = FloatArray(blocks)
        val wild = held?.wander ?: 1f
        val breathDepth = Dsp.lin(loose, 0.01f, 0.09f) * wild
        for (k in 0 until blocks) {
            val t = k * BLOCK * dt
            val tt = max(t - late, 0.0)
            // held, the breath swells to its middle in its first half second and never runs out
            val frac = if (held != null) min(tt / 1.0, 0.5) else (tt / max(blown, 1e-3f)).coerceIn(0.0, 1.0)
            // a breath swells toward the middle, and it runs out: over the last third the pressure tapers and the pitch sags
            val tired = ((frac - 0.67) / 0.33).coerceIn(0.0, 1.0).let { it * it }
            // the diaphragm is not a pump: the pressure wanders a few percent the whole way through
            slowEnv[k] = ((1.0 + 0.06 * sin(PI * frac) * (0.5 + effort)) * (1.0 - 0.14 * loose * tired) *
                (1.0 + breathDepth * breathWander.at(t))).toFloat()
            // a played vibrato is not a sine from a box: its speed and depth wander
            slowRate[k] = (vibHz * (1.0 + 0.09 * loose * rateWander.at(t) * wild)).toFloat()
            slowDepth[k] = (vibBase * ((tt - vibStart) / 0.35).coerceIn(0.0, 1.0) * (1.0 + 0.35 * loose * depthWander.at(t) * wild)).toFloat()
            var c = driftCents * drift.at(t) + flat - 9.0 * loose * tired + trimCents
            // every wind attack starts a few cents under and settles; a harder blow starts further under
            c -= underCents * exp(-tt / 0.04)
            c -= takeScoop * exp(-tt / 0.13)                                        // the "leaning" take comes up into the note
            var scale = 1.0
            when (gesture) {
                ConsortGesture.SCOOP -> c -= scoopCents * exp(-tt / scoopTau)
                ConsortGesture.FALL -> {
                    // the end of the note drops away: pitch falls and the breath lets go with it
                    val x = ((t - (ends - fallLength)) / fallLength).coerceIn(0.0, 1.0)
                    c -= fallCents * x.pow(1.6)
                    scale = 1.0 - 0.45 * x
                }
                else -> {}
            }
            slowCents[k] = c.toFloat()
            slowScale[k] = scale.toFloat()
            // extra air at the tongue (the chiff), and the leak is never steady
            slowNoise[k] = (noiseBase * (1.0 + chiff * exp(-tt / 0.05)) * (1.0 + 0.4 * loose * airWander.at(t) * wild)).toFloat()
            if (trilling) {
                // the trill starts slow and speeds up, and the upper note is held a little shorter than the lower
                slowTrill[k] = ((trillBase + trillGrow * ((tt - 0.22) / 1.2).coerceIn(0.0, 1.0)) * (1.0 + 0.05 * trillWander.at(t))).toFloat()
                slowDuty[k] = (0.46 + 0.07 * loose * dutyWander.at(t)).toFloat()
            }
        }
        if (held != null) {
            // whole vibrato and trill cycles in each period, so the period repeats to the phase
            wholeCycles(slowRate, blocks, held.period)
            if (trilling) wholeCycles(slowTrill, blocks, held.period)
        }
        var vibPhase = vibPhase0
        var trillPhase = 0.0
        val twoPi = 2.0 * PI
        for (i in 0 until frames) {
            val t = i * dt
            val tt = max(t - late, 0.0)
            val k = i / BLOCK
            val f = (i - k * BLOCK).toFloat() / BLOCK
            fun line(a: FloatArray): Float = a[k] + (a[k + 1] - a[k]) * f
            // to the sample: the tongue opens the note, and the breath stops and lets the pipe ring out
            var env = if (t < late) 0.0 else if (tt >= attack) 1.0 else (tt / attack).pow(0.7)
            env *= ((ends - t) / release).coerceIn(0.0, 1.0)
            env *= line(slowEnv)
            // the vibrato shakes the breath as well as the pitch
            vibPhase += twoPi * line(slowRate) * dt
            val depth = line(slowDepth)
            var c = line(slowCents).toDouble()
            if (depth != 0f) {
                c += depth * sin(vibPhase)
                env *= 1.0 + 0.0025 * depth * sin(vibPhase + 0.6)
            }
            var scale = line(slowScale).toDouble()
            if (upper != null) {
                trillPhase += line(slowTrill) * dt
                if (trillPhase - floor(trillPhase) < line(slowDuty) && tt > 0.22 && t < ends - 0.05) upper[i] = 1f
            }
            if (fluttering) {
                // flutter-tongue: the tongue rolls and chops the breath
                scale *= 1.0 - flutterDepth * (0.5 + 0.5 * sin(twoPi * FLUTTER_HZ * tt)) * ((tt - 0.05) / 0.08).coerceIn(0.0, 1.0)
            }
            var j = 0.5
            if (over > 0f) {
                if (jetPipe) {
                    // the flute's overblow: once the low note has spoken the jet's transit time halves and the note cracks to the octave
                    j -= 0.25 * ((tt - 0.14) / 0.005).coerceIn(0.0, 1.0)
                    scale *= 1.0 + 0.25 * over * sin(twoPi * GROWL_HZ * tt) * ((tt - 0.2) / 0.05).coerceIn(0.0, 1.0)
                } else {
                    // an overblown reed growls: the throat shakes the mouth pressure
                    scale *= 1.0 + growl * sin(twoPi * GROWL_HZ * tt) * ((tt - 0.06) / 0.05).coerceIn(0.0, 1.0)
                }
            }
            pressure[i] = (env * scale).toFloat()
            cents[i] = c.toFloat()
            noise[i] = line(slowNoise)
            jet[i] = j.toFloat()
        }
        if (upper != null) {
            // two 6 ms running means make a 12 ms ramp of each finger move
            val moved = boxed(boxed(upper, (0.006f * INTERNAL).toInt()), (0.006f * INTERNAL).toInt())
            for (i in 0 until frames) {
                cents[i] += 200f * moved[i]
                // each finger move leaks: the tone dips while the hole is half covered
                pressure[i] *= 1f - 0.5f * loose * 4f * moved[i] * (1f - moved[i])
            }
        }
        return Plan(pressure, cents, noise, jet, effort, over, seed, late, attack, way.chiff.pow(stray), way.name,
            ring = if (held != null) Math.round(held.period * INTERNAL) else 0)
    }

    /**
     * Scales [rate], a periodic rate in Hz worked out every [BLOCK] samples, so its integral over one
     * [HELD_PERIOD_SECONDS] (read from 2 s on, past the trill's speeding up) is a whole number of cycles.
     */
    private fun wholeCycles(rate: FloatArray, blocks: Int, period: Float) {
        val from = (2.0 * INTERNAL / BLOCK).toInt()
        require(from + period * INTERNAL / BLOCK < blocks) { "a held note too short for its period" }
        val n = (period.toDouble() * INTERNAL / BLOCK)
        var cycles = 0.0
        val whole = n.toInt()
        for (k in from until min(blocks, from + whole)) cycles += rate[k].toDouble() * BLOCK / INTERNAL
        cycles += rate[min(blocks - 1, from + whole)].toDouble() * (n - whole) * BLOCK / INTERNAL
        if (cycles <= 0.5) return
        val g = (Math.round(cycles).toDouble() / cycles).toFloat()
        for (k in rate.indices) rate[k] *= g
    }

    /** A centred running mean of [width] samples. */
    private fun boxed(x: FloatArray, width: Int): FloatArray {
        val w = max(1, width)
        val half = w / 2
        val out = FloatArray(x.size)
        var sum = 0.0
        for (i in 0 until x.size + half) {
            if (i < x.size) sum += x[i]
            if (i - w >= 0) sum -= x[i - w]
            val at = i - half
            if (at >= 0 && at < x.size) out[at] = (sum / w).toFloat()
        }
        return out
    }

    /**
     * The pipe. One sample at a time, because each new sample depends on the wave coming back from the
     * far end. Returns the wave inside the bore: BORE's lesson is to listen there, not to the valve's
     * own sample, which carries the mouth's steady pressure.
     */
    internal fun blow(voice: ConsortVoice, hz: Float, p: Plan): FloatArray {
        val s = spec(voice)
        val n = p.pressure.size
        val effort = p.effort
        val half = s.pipe == Pipe.CYL
        val reed = s.pipe != Pipe.JET
        val bellHz = (if (reed) s.bell * Dsp.lin(effort, 0.75f, 1.35f) else s.bell * hz * Dsp.lin(effort, 0.8f, 1.25f))
            .coerceAtMost(0.4f * INTERNAL)
        val dcHz = if (reed) hz / 25f else Strings.DC_BLOCK_HZ      // Bore.SAX_BLOCKER_DIVISOR
        val roundTrip = if (half) 0.5 else 1.0
        // the ring is built for the lowest pitch the plan asks for; it only ever retunes upward from there
        var low = 0f
        for (c in p.cents) low = min(low, c)
        val lowest = hz * 2f.pow((low - 8f) / 1200f)
        val tuning = Strings.tune(lowest, bellHz, INTERNAL, dcBlock = true, dcHz = dcHz, roundTrip = roundTrip)
        val loop = Strings.Loop(tuning.n, tuning.a, LOOP_GAIN, bellHz, INTERNAL, dcBlock = true, dcHz = dcHz, roundTrip = roundTrip)
        val pin = pinCents(voice)

        var noise = Dsp.Noise(p.seed + 1)
        val air = Dsp.FixedOnePole(AIR_HZ, INTERNAL)
        val offset = s.offset
        val closing = (1f - offset) / -REED_SLOPE                    // the pressure that shuts the reed
        val share = Dsp.lin(effort, 0.60f + 0.63f * (offset - 0.5f) + 0.08f, 0.93f)
        val flutePressure = Dsp.lin(effort, 0.80f, 1.02f)
        val bias = Dsp.lin(effort, 0.42f, 0.22f)                     // where the jet rests against the edge

        // the tongue's seed: eight periods of the note put straight into the bore, so a reed speaks at once.
        // Its strength is part of the take: a bitten note is seeded hard, a soft one has to find the note itself.
        val seedStart = ((p.late + 0.6f * p.attack) * INTERNAL).toInt()
        val seedN = if (reed) (8f * INTERNAL / hz).toInt() else 0
        val seedAmp = min(0.6f, 0.3f * Dsp.lin(effort, 0.3f, 1f) * p.tongue)
        // an octave seed where the flute's jet arrives, as BORE's crack has
        val crackStart = ((p.late + 0.145f) * INTERNAL).toInt()
        val crackN = if (!reed && p.over > 0f) (8f * INTERNAL / (2f * hz)).toInt() else 0

        val jetMax = ceil(0.5f * INTERNAL / lowest).toInt() + 4
        val jet = FloatArray(jetMax)
        var jw = 0
        var freq = hz
        val out = FloatArray(n)
        for (i in 0 until n) {
            // a held note's breath noise repeats with its period, so the turbulence, and every restart it seeds, repeats too
            if (p.ring > 0 && i > 0 && i % p.ring == 0) noise = Dsp.Noise(p.seed + 1)
            if (i % BLOCK == 0) {
                freq = hz * 2f.pow((p.cents[i] + pin) / 1200f)
                loop.retune(freq)
            }
            val back = loop.reflected()
            val returning = if (half) -back else back
            val y = if (reed) {
                val pm = min(closing * share * p.pressure[i] * (1f + p.noise[i] * air.lp(noise.next())), closing * 0.99f)
                // Smith's reed reflection table: passive by construction, |r| <= 1
                val delta = returning - pm
                val r = (offset + REED_SLOPE * delta).coerceIn(-1f, 1f)
                pm + delta * r
            } else {
                val pm = min(flutePressure * p.pressure[i] * (1f + p.noise[i] * air.lp(noise.next())), 1.3f)
                // the returning wave deflects the jet; the disturbance crosses the mouth; the edge turns it into a pressure
                val delay = max(p.jet[i] * INTERNAL / freq, 2f)
                val at = jw - delay
                val i0 = floor(at).toInt()
                val f = at - i0
                val older = jet[Math.floorMod(i0, jetMax)]
                val newer = jet[Math.floorMod(i0 + 1, jetMax)]
                val jetOut = older + (newer - older) * f
                jet[jw] = -0.5f * returning
                jw = (jw + 1) % jetMax
                tanh(3f * pm * jetOut + bias) * min(pm, 1f) + 0.5f * returning
            }
            var tongue = 0f
            if (seedN > 0 && i >= seedStart && i < seedStart + seedN) {
                val k = i - seedStart
                tongue = seedAmp * (0.5f - 0.5f * cos(2.0 * PI * k / seedN).toFloat()) * sin(2.0 * PI * hz * k / INTERNAL).toFloat()
            } else if (crackN > 0 && i >= crackStart && i < crackStart + crackN) {
                val k = i - crackStart
                tongue = 0.3f * (0.5f - 0.5f * cos(2.0 * PI * k / crackN).toFloat()) * sin(2.0 * PI * 2.0 * hz * k / INTERNAL).toFloat()
            }
            // the cylinder's sign flip happens once a trip, on the way back (above); what goes in is not flipped again
            loop.inject(y + tongue)
            out[i] = returning
        }
        return out
    }

    /**
     * Cents added so a pipe lands on its note: the valve pulls a blown loop a little off the ring's own
     * pitch (BORE carries SAX_PIN_CENTS and FLUTE_PIN_CENTS for the same reason). Measured by
     * `ConsortTest`'s pitch probe on the middle note of each voice with these at 0, then set.
     */
    internal fun pinCents(voice: ConsortVoice): Float = PIN_CENTS[voice.ordinal]

    // With these at 0 the three notes of each voice read: FLUTE +5.3 +3.7 +6.6, SAX -5.3 -5.0 -3.2, CLARINET -9.2 -6.5 -3.7,
    // OBOE -4.0 -2.4 -0.6, BASSOON -4.9 -1.2 -3.8, DUDUK -1.1 +1.2 +0.9 cents (LOOSE 0, ROOM 0, velocity 0.7).
    internal val PIN_CENTS = floatArrayOf(-5f, 4.5f, 6.5f, 2.5f, 3.5f, 0f)

    /**
     * The reed beating against the mouthpiece. The loop alone gives a soft, nearly pure wave; a real
     * reed slams shut once a cycle and that is where a sax's edge comes from. BORE's RASP is a memoryless
     * bend, `tanh(drive * (x + bias))`, and so is this: no memory, so it cannot move the pitch or the
     * timing, only add harmonics, and more of them the harder the player blows, which is what makes
     * velocity a change of tone. The cylinders get a symmetric bend so their odd-harmonic hollow survives.
     */
    internal fun rasp(x: FloatArray, voice: ConsortVoice, effort: Float) {
        val s = spec(voice)
        val drive = s.rasp * Dsp.expMap(effort, 0.2f, 2.4f)
        val bias = if (s.pipe == Pipe.CYL) 0f else 0.4f              // Bore.RASP_BIAS
        var peak = 1e-9f
        for (v in x) peak = max(peak, abs(v))
        val rest = tanh(drive * bias)
        val gain = peak / max(drive, 1f)
        for (i in x.indices) x[i] = (tanh(drive * (x[i] / peak + bias)) - rest) * gain
    }

    /**
     * The instrument's fixed tone shape. A bell radiates highs better than lows, so the bore wave is
     * tilted up between 300 Hz and a corner that opens with effort; then each formant is a bump that
     * does not move with the note; then the top is rolled off at 9 kHz.
     *
     * It runs at the internal rate, before the decimator, and that is a listening decision, not a
     * technical one. Every stage here is a linear filter working under 10 kHz, so K2 moved it to the
     * output rate to save 13 ms a note. Measured, that darkened every voice: against the 100 to 500 Hz
     * band, 2 to 5 kHz fell by 0.2 (DUDUK) to 1.9 dB (SAX), because a one-pole and a biquad are not the
     * same filter at 44.1 kHz as at 176.4 kHz. The owner had approved the sound with the filters here
     * (2026-11-20), so they stay here. [rate] is a parameter only so a probe can repeat that measurement.
     */
    internal fun body(x: FloatArray, voice: ConsortVoice, effort: Float, rate: Int = INTERNAL) {
        val s = spec(voice)
        val tilt = s.tiltHz * Dsp.expMap(effort, 0.45f, 1.6f)
        val low = Dsp.FixedOnePole(150f, rate)
        val corner = Dsp.FixedOnePole(tilt, rate)
        val lift = tilt / 300f
        val peaks = s.formants.map { (hz, db, q) -> Dsp.Biquad().apply { peaking(hz, db, q, rate) } }
        var quiet = 0
        var end = x.size
        for (i in x.indices) {
            // nothing radiates below about 150 Hz. After the breath the pipe settles to a slowly fading offset,
            // and the high-pass's difference of two nearly equal numbers sinks into subnormal floats, which
            // every filter after it then works through at up to ten times the cost (on a default OBOE note,
            // 27,000 of 231,500 samples). Under SILENT (about -600 dB) the difference is taken as silence.
            val diff = x[i] - low.lp(x[i])
            val high = if (abs(diff) < SILENT) 0f else diff
            val l = corner.lp(high)
            var y = l + lift * (high - l)                            // (1 + s/w300) / (1 + s/wTilt)
            for (p in peaks) y = p.process(y)
            x[i] = y
            // once the high-pass has been silent for QUIET_RUN samples and the body has rung down under SILENT,
            // the rest is silence: the filters' states would only decay on through subnormal floats
            quiet = if (high == 0f) quiet + 1 else 0
            if (quiet >= QUIET_RUN && abs(y) < SILENT) { end = i + 1; break }
        }
        java.util.Arrays.fill(x, end, x.size, 0f)
        if (end == x.size) Dsp.TptSvf(rate).lowPassInPlace(x, TOP_HZ, 1.414f)
        else {
            val sounding = x.copyOfRange(0, end)
            Dsp.TptSvf(rate).lowPassInPlace(sounding, TOP_HZ, 1.414f)
            sounding.copyInto(x)
        }
    }

    /** One player, dry, at the output rate and at unit level (RMS after the attack), ready to be seated. */
    internal fun player(
        voice: ConsortVoice, midi: Int, velocity: Float, m: Map<String, Float>, role: Int, frames: Int, take: Int,
        held: Held? = null, trimCents: Float = 0f,
    ): FloatArray {
        val p = plan(voice, midi, velocity, m, role, frames * Dsp.OVERSAMPLE, take, held, trimCents)
        val raw = blow(voice, Keys.midiHz(midi), p)
        rasp(raw, voice, p.effort)
        body(raw, voice, p.effort)
        // The band limit runs over the sounding part only: past the last sample at or above SILENT, plus
        // QUIET_RUN for it to ring down, the rest stays exact zeros. Its natural continuation there is a
        // decay through subnormal floats, which cost up to ten times the arithmetic of normal ones.
        var end = raw.size
        while (end > 0 && abs(raw[end - 1]) < SILENT) end--
        end = min(raw.size, end + QUIET_RUN)
        if (end == raw.size) Tide.bandLimit(raw, INTERNAL)
        else {
            val sounding = raw.copyOfRange(0, end)
            Tide.bandLimit(sounding, INTERNAL)
            sounding.copyInto(raw)
            java.util.Arrays.fill(raw, end, raw.size, 0f)
        }
        val y = Dsp.decimate(raw, RATE)
        var sum = 0.0
        val from = min((0.1f * RATE).toInt(), y.size / 2)
        for (i in from until y.size) sum += y[i].toDouble() * y[i]
        val rms = sqrt(sum / max(1, y.size - from)).toFloat()
        if (rms > 1e-9f) for (i in y.indices) y[i] /= rms
        return y
    }

    /**
     * A chair's first wall bounces: the six walls and the two-wall paths of a shoebox room (its mirror
     * images), each delayed by its extra distance, quieter by distance and by [BOUNCE_GAIN] a bounce, and
     * duller the more walls it touched. Geometry, not a recording. Adds into [out] from [offset].
     */
    private fun seat(x: FloatArray, role: Int, amount: Float, level: Float, offset: Int, out: FloatArray) {
        val src = CHAIRS[role]
        val direct = dist(src, MIC)
        for (i in x.indices) if (i + offset < out.size) out[i + offset] += level * x[i]
        if (amount <= 0f) return
        val layers = Array(3) { FloatArray(out.size) }
        for (ix in -1..1) for (iy in -1..1) for (iz in -1..1) {
            val bounces = abs(ix) + abs(iy) + abs(iz)
            if (bounces == 0 || bounces > 2) continue
            val image = FloatArray(3)
            val steps = intArrayOf(ix, iy, iz)
            for (a in 0..2) image[a] = when (steps[a]) { -1 -> -src[a]; 1 -> 2f * ROOM_DIMS[a] - src[a]; else -> src[a] }
            val d = dist(image, MIC)
            val at = offset + ((d - direct) / SPEED_OF_SOUND * RATE).toInt()
            val sign = if (Math.floorMod(ix + iy + iz, 2) == 0) 1f else -1f
            val g = level * amount * BOUNCE_GAIN.pow(bounces) * (direct / d) * sign
            val layer = layers[bounces]
            for (i in x.indices) { val k = i + at; if (k >= layer.size) break; layer[k] += g * x[i] }
        }
        for (b in 1..2) {
            // a bounce off a wall is duller than the direct sound: spread it over 0.25 ms a wall
            val spread = boxed(layers[b], (0.00025f * RATE * b).toInt() + 1)
            for (i in out.indices) out[i] += spread[i]
        }
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }

    /**
     * The room's wash after the first bounces: the rack ROOM's own sixteen-line network, at the decay
     * [washSeconds] asks for, 16 dB under the players at ROOM 0 rising to 5 dB under. The network's
     * response to one click is measured and scaled to unit energy first, so a held note comes back from
     * it at about its own level times the wet gain, whatever the decay.
     */
    private fun wash(mix: FloatArray, room: Float) {
        val space = spaceFor(room)
        val p = space.plan
        val wet = 10f.pow(Dsp.lin(room, -16f, -5f) / 20f) / sqrt(max(space.energy, 1e-24)).toFloat()
        val start = (0.025f * RATE).toInt()
        val feed = FloatArray(mix.size)
        for (i in 0 until mix.size - start) feed[i + start] = mix[i]
        val late = Room.bloom(feed, p, RATE)
        for (i in mix.indices) mix[i] += wet * late[i]
    }

    /** The wash's network for one ROOM value and the energy of its response to one click. */
    private class Space(val plan: Room.Plan, val energy: Double)

    /**
     * Precomputed per ROOM value and kept: finding the ROOM size whose decay is the one wanted (its decay
     * grows with size) and measuring the network's click response depend on the knob alone, and together
     * they were about 12 ms of every render. The result is a pure function of [room], so keeping it changes
     * no sample. The cache is dropped whole if a session ever fills it with [SPACES] distinct knob values.
     */
    private val spaces = java.util.concurrent.ConcurrentHashMap<Int, Space>()
    private const val SPACES = 256

    private fun spaceFor(room: Float): Space {
        val key = room.toRawBits()
        spaces[key]?.let { return it }
        val want = washSeconds(room)
        var lo = 0f
        var hi = 1f
        repeat(14) {
            val mid = 0.5f * (lo + hi)
            if (Room.plan(mapOf("SIZE" to mid, "WALLS" to 0.5f, "DISTANCE" to 0.6f), RATE).rt60 < want) lo = mid else hi = mid
        }
        val p = Room.plan(mapOf("SIZE" to 0.5f * (lo + hi), "WALLS" to 0.5f, "DISTANCE" to 0.6f), RATE)
        val click = FloatArray((1.2f * RATE).toInt()).also { it[0] = 1f }
        var energy = 0.0
        for (v in Room.bloom(click, p, RATE)) energy += v.toDouble() * v
        val made = Space(p, energy)
        if (spaces.size >= SPACES) spaces.clear()
        spaces[key] = made
        return made
    }

    /**
     * The engine. Pure: the same inputs give the same samples. [chairs] is the section, up to
     * [MAX_CHAIRS] harmony players; [take] is the round-robin number.
     */
    fun render(
        voice: ConsortVoice,
        macros: Map<String, Float> = emptyMap(),
        chairs: List<ConsortChair> = emptyList(),
        velocity: Float = DEFAULT_VELOCITY,
        take: Int = 0,
    ): Snip {
        require(chairs.size <= MAX_CHAIRS) { "CONSORT seats at most $MAX_CHAIRS harmony players, got ${chairs.size}" }
        val m = settled(macros, voice)
        val v = if (velocity.isFinite()) velocity.coerceIn(0f, 1f) else DEFAULT_VELOCITY
        if (isLoop(m.getValue("HOLD"))) return Snip(renderLoop(voice, m, chairs, v, take).loop, channels = 1, sampleRate = RATE)
        val out = perform(voice, m, chairs, v, take, renderFrames(m), ((holdSeconds(m.getValue("HOLD")) + AFTER_SECONDS) * RATE).toInt(), held = null)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * The section blown, seated and washed, DC removed, not levelled: [render]'s body, and a held note's
     * ([heldNote]). [m] is settled, [frames] the output length and [dry] each player's length, both at [RATE].
     */
    private fun perform(
        voice: ConsortVoice, m: Map<String, Float>, chairs: List<ConsortChair>, v: Float, take: Int,
        frames: Int, dry: Int, held: Held?,
    ): FloatArray {
        val midi = midiFor(voice, m.getValue("TUNE"))
        val room = m.getValue("ROOM")
        val out = FloatArray(frames)
        val bounce = Dsp.lin(room, 0f, 0.9f)
        // BLEND is how far forward the section sits: tucked 14 dB under the lead, up to level with it
        val blend = 10f.pow(Dsp.lin(m.getValue("BLEND"), -14f, 0f) / 20f)
        val leadDistance = dist(CHAIRS[0], MIC)
        // Each player is their own pipe and shares nothing with the others, so a section's players are
        // blown side by side (BREAKER's fill does the same with its toms) and then seated in chair order,
        // which keeps the sum, and so every sample, the same from run to run.
        fun blown(role: Int): FloatArray {
            val who = if (role == 0) voice else chairs[role - 1].voice
            val note = if (role == 0) midi else midi + chairs[role - 1].interval.semitones
            val vel = if (role == 0) v else v * 0.92f
            if (held == null) return player(who, note, vel, m, role, dry, take)
            // Held: blow once, read how far the wave has slipped after one period, and blow again that many
            // cents sharper or flatter, so this player's wave, and not only its motions, repeats on the period.
            var trim = 0f
            var y = player(who, note, vel, m, role, dry, take, held = held)
            for (pass in 0 until HELD_TRIM_PASSES) {
                val more = heldTrimCents(y, Keys.midiHz(note), held.period)
                if (pass > 0 && abs(more) < HELD_TRIM_SETTLED_CENTS) break
                trim += more
                y = player(who, note, vel, m, role, dry, take, held = held, trimCents = trim)
            }
            return y
        }
        val roles = (0..chairs.size).toList()
        val players = if (chairs.isEmpty()) listOf(blown(0))
            else roles.parallelStream().map { blown(it) }.collect(java.util.stream.Collectors.toList())
        for (role in roles) {
            val y = players[role]
            // further chairs are quieter and arrive later, as well as bouncing differently
            val d = dist(CHAIRS[role], MIC)
            val level = (if (role == 0) 1f else blend) * (leadDistance / d).pow(0.6f * room)
            val offset = (room * (d - leadDistance) / SPEED_OF_SOUND * RATE).toInt()
            seat(y, role, bounce, level, offset, out)
        }
        if (room > 0f) wash(out, room)
        var mean = 0.0
        for (x in out) mean += x
        val mm = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mm
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        return out
    }

    /**
     * A held note is blown for this long ([heldNote]), BORE's held length ([Bore.LOOP_HELD_SECONDS]): long
     * enough for [Bore.LOOP_START_SECONDS] of settling, a loop of about [Bore.LOOP_SECONDS] and its blend.
     */
    const val HELD_SECONDS = Bore.LOOP_HELD_SECONDS

    /** A held note's slow motions repeat this often, [Bore.LOOP_SECONDS], the loop length the cut searches around. */
    const val HELD_PERIOD_SECONDS = Bore.LOOP_SECONDS

    /** Where a held loop's blend reference starts: [Bore.LOOP_START_SECONDS], past every onset. */
    internal val HELD_FROM_FRAMES: Int = (Bore.LOOP_START_SECONDS * RATE).toInt()

    private const val WANDER_RING_PASSES = 4

    /** Under this a sample is silence (about -600 dB): the body's high-pass flushes it and [player]'s band limit skips it. */
    private const val SILENT = 1e-30f
    /** The band limit's ring-down past the last sounding sample ([player]). */
    private const val QUIET_RUN = 4096

    /** At most this many retunes of a held player ([heldTrimCents]); a later one only if the slip left is over [HELD_TRIM_SETTLED_CENTS]. */
    private const val HELD_TRIM_PASSES = 2
    private const val HELD_TRIM_SETTLED_CENTS = 0.05f

    /**
     * The cents that make held player [y]'s wave repeat after exactly [HELD_PERIOD_SECONDS]: where 50 ms of it
     * from 2 s on is found again near one period later (within half a cycle of [hz], to the sub-sample), the
     * wave repeats every period plus that slip, so the note is raised by the ratio. A player blown at those cents
     * slips by a small part of what it did ([heldNote] blows each player twice).
     */
    internal fun heldTrimCents(y: FloatArray, hz: Float, periodSeconds: Float = HELD_PERIOD_SECONDS): Float {
        val period = (periodSeconds * RATE).toDouble()
        val from = (2.0 * RATE).toInt()
        val w = (0.05 * RATE).toInt()
        val half = max(2, (0.5 * RATE / hz).toInt())
        val base = from + period.toInt()
        if (base + half + w >= y.size) return 0f
        fun corr(d: Int): Double {
            var dot = 0.0; var a = 0.0; var b = 0.0
            for (i in 0 until w) {
                val p = y[from + i].toDouble(); val q = y[base + d + i].toDouble()
                dot += p * q; a += p * p; b += q * q
            }
            return if (a * b > 1e-24) dot / sqrt(a * b) else 0.0
        }
        var best = 0
        var bestR = Double.NEGATIVE_INFINITY
        for (d in -half..half) { val r = corr(d); if (r > bestR) { bestR = r; best = d } }
        var slip = best.toDouble()
        if (best > -half && best < half) {
            val l = corr(best - 1); val r = corr(best + 1)
            val curve = l - 2 * bestR + r
            if (curve < 0) slip += 0.5 * (l - r) / curve
        }
        return (1200.0 * ln((period + slip) / period) / ln(2.0)).toFloat()
    }

    /**
     * The held note a key's sustain loop is cut from (`ConsortKeys`): the section at [velocity] and [take]
     * blown for [HELD_SECONDS] whatever HOLD says, through the same players, chairs and room as [render],
     * not levelled and with no tail. Held, so it never overblows: a key plays its own note (BORE's rule,
     * [Bore.heldNote]); the effort is capped under [OVERBLOW_FROM] and nothing else changes.
     */
    internal fun heldNote(
        voice: ConsortVoice, macros: Map<String, Float>, chairs: List<ConsortChair>, velocity: Float, take: Int,
        held: Held = KEYS_HELD,
    ): FloatArray {
        require(chairs.size <= MAX_CHAIRS) { "CONSORT seats at most $MAX_CHAIRS harmony players, got ${chairs.size}" }
        val m = settled(macros, voice)
        val v = if (velocity.isFinite()) velocity.coerceIn(0f, 1f) else DEFAULT_VELOCITY
        val frames = ((held.seconds + AFTER_SECONDS) * RATE).toInt()
        return perform(voice, m, chairs, v, take, frames, frames, held = held)
    }

    /**
     * How a held note is blown: its slow motions, noise and players repeat every [period] seconds, it is
     * blown for [seconds], and [wander] scales the depth of its slow wanders (breath, pitch drift, vibrato
     * speed and depth, air) against a one-shot's.
     */
    internal data class Held(val period: Float, val seconds: Float, val wander: Float) {
        /** How much of the held note a loop may use: [HELD_USABLE_FRAMES]'s rule at this length. */
        val usableFrames: Int get() = ((seconds - 0.08f - Bore.LOOP_MARGIN_SECONDS) * RATE).toInt()
    }

    /**
     * The held note kept by ear in round K3: a two-second period, BORE's held length, the one-shot's wanders. The keys
     * used it until the owner asked for them to be like the held pads (2026-11-21); both use [PAD_HELD] now.
     */
    internal val KEYS_HELD = Held(HELD_PERIOD_SECONDS, HELD_SECONDS, 1f)

    /** A held note blown long enough to cut and retune a loop of [period] seconds: settle, trim window, blend and margin. */
    internal fun heldFor(period: Float, wander: Float): Held = Held(period, max(HELD_SECONDS, period + 2.6f), wander)

    /**
     * A sustaining pad's held note: an eight-second cycle with the slow wanders 1.6 times as deep as a one-shot's.
     * The owner heard the keys' two-second cycle on a held pad and asked for more humanization, then heard this
     * against a six-second cycle and the two-second one, and chose it (2026-11-21, "I like c"); the keys followed ("Keys should be like held").
     */
    internal val PAD_HELD: Held = heldFor(PAD_PERIOD_SECONDS, PAD_WANDER)
    const val PAD_PERIOD_SECONDS = 8f
    const val PAD_WANDER = 1.6f

    /**
     * How much of a [heldNote] a loop may use: the breath, less the earliest a player lets go (7 % of a
     * second early at full LOOSE) and [Bore.LOOP_MARGIN_SECONDS] before the release.
     */
    internal val HELD_USABLE_FRAMES: Int = ((HELD_SECONDS - 0.08f - Bore.LOOP_MARGIN_SECONDS) * RATE).toInt()

    /**
     * How alike the two waves a held loop blends must be ([ConsortKeys] and [renderLoop]). A solo voice
     * is held to BORE's floor ([Bore.LOOP_MIN_CORRELATION]); a section to [SECTION_MIN_CORRELATION],
     * since a chair taken far above its own range never quite repeats; FLUTTER to the level guard alone,
     * since the tongue restarts the tone 24 times a second. The dip guard applies to all of them.
     */
    internal fun loopFloor(m: Map<String, Float>, chairs: List<ConsortChair>): Double = when {
        gestureFor(m.getValue("GESTURE")) == ConsortGesture.FLUTTER -> 0.0
        chairs.isNotEmpty() -> SECTION_MIN_CORRELATION
        else -> Bore.LOOP_MIN_CORRELATION
    }

    /** The section floor: the lowest join measured on the K3 presets' keys was 0.82, at REED TRIO's top key. */
    const val SECTION_MIN_CORRELATION = 0.8

    /** A pad's loop and how its join was made. */
    internal class LoopRender(val loop: FloatArray, val lengthFrames: Int, val correlation: Double, val blendDipDb: Double)

    /**
     * HOLD's top step: the held note ([heldNote]) cut once into one loop of about [HELD_PERIOD_SECONDS],
     * from [HELD_FROM_FRAMES], its length searched as BORE's is ([Bore.bestLength]) and its head blended
     * from the audio that follows its end ([Bore.assembleLoop]), so its last sample runs on into its first;
     * turned onto a zero crossing ([Bore.rotateOntoCrossing]) and levelled. The keys' guards hold it: the
     * waves blended correlate at least [loopFloor], and the blend dips the note no more than
     * [Bore.LOOP_MAX_DIP_DB] against the note itself ([BoreKeys.sourceDipDb]). Refused by name otherwise.
     */
    internal fun renderLoop(
        voice: ConsortVoice, m: Map<String, Float>, chairs: List<ConsortChair>, v: Float, take: Int, held: Held = PAD_HELD,
    ): LoopRender {
        val x = heldNote(voice, m, chairs, v, take, held)
        val blend = Bore.LOOP_BLEND_FRAMES
        val from = HELD_FROM_FRAMES
        val slack = (Bore.LOOP_SLACK_SECONDS * RATE).toInt()
        val period = Math.round(held.period * RATE)
        val (length, correlation) = Bore.bestLength(x, from, blend, period - slack, period + slack)
        require(from + length + blend <= held.usableFrames) { "CONSORT $voice: no room for a loop" }
        val loop = Bore.assembleLoop(x, from, length, blend, correlation)
        val probe = x.copyOf()
        for (i in 0 until blend) probe[from + i] = loop[i]
        val dip = BoreKeys.sourceDipDb(x, probe, from, blend)
        val name = "CONSORT $voice${if (chairs.isEmpty()) "" else " section"} HOLD loop"
        val floor = loopFloor(m, chairs)
        require(correlation >= floor) {
            "$name: the loop's join does not hold (the waves blended correlate %.3f, floor %.2f)".format(java.util.Locale.ROOT, correlation, floor)
        }
        require(dip >= -Bore.LOOP_MAX_DIP_DB) {
            "$name: the loop dips %.2f dB inside its blend (limit %.1f)".format(java.util.Locale.ROOT, -dip, Bore.LOOP_MAX_DIP_DB)
        }
        val (rotated, _) = Bore.rotateOntoCrossing(loop)
        Dsp.levelTo(rotated, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return LoopRender(rotated, length, correlation, dip)
    }
}

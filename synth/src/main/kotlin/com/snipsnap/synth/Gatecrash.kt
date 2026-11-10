package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * GATECRASH: the drum's room, crushed until it stands as loud as the drum, then cut off.
 *
 * The characteristic gesture is **strike → the room rises to meet it → held flat → cut to black**.
 *
 * This is a treatment stage for the GATECRASH kits ([GatecrashKits]), not a Patch engine. It takes one dry
 * mono hit (or a dry phrase of hits) at [Dsp.RATE] and returns the hit with its space. Two mechanisms, chosen
 * per pad by [Mode], because the 1980s reached this sound two different ways:
 *
 * **[Mode.STONE]: a live room heard through an overdriven listen microphone, then a keyed gate.**
 * 1. The room's answer alone (no direct sound): [Room]'s image-source taps and its sixteen-line bloom, heard
 *    from the far end of the room.
 * 2. The crush: a fast, steep compressor whose threshold sits far below the room's first level. While the
 *    room is above the threshold the compressor's gain rises as the room decays, so the level stays flat.
 *    This, not the gate, is what makes the room as loud as the drum.
 * 3. A soft clip, run at 4x like [Valve] and [Forge], for the microphone amplifier's overload.
 * 4. The gate, opened by the *dry* drum's attack, not by the room's level: it holds for a set time after the
 *    last attack and then closes in a few milliseconds. Keying from the attack is what lets a fill retrigger
 *    one shared room, and why a ringing tom does not hold its own gate open.
 *
 * **[Mode.BURST]: a nonlinear reverb program.** No room and no gate: the hit is convolved with a burst of
 * sparse, randomly signed reflections (velvet noise) whose level is flat or rising for a set time and then
 * stops. Nothing decays, so nothing needs gating. It is linear up to the same soft clip.
 *
 * A compressor evens levels out, so on its own the crush would give a tap nearly the room a full hit gets.
 * The send law undoes that on purpose: the return is sent at the hit's level to the power [SEND_POWER], so
 * a softer hit is drier as well as quieter. [touch] tells the stage how hard the hit was against a full one,
 * and keeps the crush threshold where a full hit put it rather than letting it follow the hit down.
 *
 * Every numeric constant here is a shape value: chosen to make the gesture measurable, not yet heard.
 */
object Gatecrash {

    /** Bump when a change alters rendered audio: saved kits name the version they were printed with. */
    const val VERSION = 1

    enum class Mode { STONE, BURST }

    /**
     * One pad's space.
     *
     * [size] and [walls] are [Room]'s SIZE and WALLS for [Mode.STONE]; for [Mode.BURST] only [walls] is read,
     * as the burst's brightness. [crush] is how far under the room's first level the compressor's threshold
     * sits (0 is 6 dB, 1 is 40 dB). [holdMs] is how long the space stays open after the last attack and
     * [closeMs] how long it takes to shut. [room] is the return's level against a full-scale dry hit.
     * [drive] is the soft clip. [tilt] (BURST only) leans the burst from flat (0) to rising (1).
     * [dryCut] gates the dry drum with the room, 0 leaving it to ring and 1 cutting it fully.
     */
    data class Space(
        val mode: Mode,
        val size: Float = 0.5f,
        val walls: Float = 0.8f,
        val crush: Float = 0.8f,
        val holdMs: Float = 280f,
        val closeMs: Float = 18f,
        val room: Float = 0.8f,
        val drive: Float = 0.4f,
        val tilt: Float = 0f,
        val dryCut: Float = 0f,
    ) {
        init {
            require(listOf(size, walls, crush, room, drive, tilt, dryCut).all { it.isFinite() && it in 0f..1.5f })
            require(holdMs.isFinite() && holdMs in 10f..1200f && closeMs.isFinite() && closeMs in 2f..400f)
        }
    }

    /** What the stage made, kept apart so a test can measure each claim rather than the sum. */
    class Stages(
        /** The finished pad: dry plus the gated return, not yet levelled. */
        val out: FloatArray,
        /** The return alone, after crush, clip and gate, at the level it is mixed. */
        val ret: FloatArray,
        /** The gate's gain, 0..1, per frame (all ones in the open part of a BURST). */
        val gate: FloatArray,
        /** Frames at which the key heard an attack. */
        val attacks: List<Int>,
    )

    private const val RATE = Dsp.RATE

    // STONE's far microphone: Room's DISTANCE, near the far corner.
    private const val FAR_MIC = 0.9f
    private const val CRUSH_RATIO = 12f
    private const val CRUSH_ATTACK_SEC = 0.0004f
    private const val CRUSH_RELEASE_SEC = 0.045f
    private const val GATE_OPEN_SEC = 0.0015f

    /** The return's level follows the hit's level to this power. 1 would track it; more makes soft hits drier. */
    const val SEND_POWER = 1.5f

    // The key: the hit's level now against its own held peak a moment ago. An attack is where the level
    // leads that held peak by KEY_LEAD and is above KEY_FLOOR of full scale. A ringing drum never leads its
    // own held peak (the hold falls 15 percent across a 100 Hz cycle), so it cannot retrigger; the next hit
    // of a roll does, because the hold has fallen by the time it lands.
    private const val KEY_HOLD_RELEASE_SEC = 0.060f
    private const val KEY_LOOKBACK_SEC = 0.003f
    private const val KEY_LEAD = 1.7f
    private const val KEY_FLOOR = 0.05f
    private const val KEY_REARM_SEC = 0.012f

    // A BURST is fed only the front of each hit, so a long drum does not smear the burst past its own end.
    private const val BURST_SEND_SEC = 0.030f
    private const val BURST_SEND_CLOSE_SEC = 0.012f

    private const val BURST_TAPS_PER_SEC = 2200f
    private const val BURST_PREDELAY_SEC = 0.008f

    fun crushDepthDb(crush: Float): Float = Dsp.lin(crush.coerceIn(0f, 1f), 6f, 40f)

    fun driveGain(drive: Float): Float = Dsp.lin(drive.coerceIn(0f, 1f), 0.5f, 3.2f)

    /**
     * [dry] is a mono hit or phrase at [Dsp.RATE] whose full-strength version peaks near 1. [touch] is this
     * hit's level against that full version (1 for the hard layer), so the crush threshold and the room send
     * stay where a full hit put them. [seed] fixes a BURST's reflection pattern.
     */
    fun process(dry: FloatArray, space: Space, touch: Float = 1f, seed: Int = 0): Stages {
        require(touch.isFinite() && touch > 0f && touch <= 1f) { "touch is a level against a full hit, (0, 1]" }
        val attacks = attacks(dry)
        val holdFrames = (space.holdMs * 0.001f * RATE).toInt()
        val closeFrames = max((space.closeMs * 0.001f * RATE).toInt(), 2)
        val full = dry.size + holdFrames + closeFrames + (0.03f * RATE).toInt()
        val gate = gate(attacks, full, holdFrames, closeFrames)
        val raw = when (space.mode) {
            Mode.STONE -> crushed(roomReturn(dry, space, full), space, touch)
            Mode.BURST -> burst(dry, attacks, space, full, touch, seed)
        }
        // The crush has already pulled a soft hit's room nearly up to a hard hit's. The send takes it back
        // down, and further than the dry hit fell, so soft hits are drier.
        val send = space.room * touch.pow(SEND_POWER)
        val clipped = softClip(raw, space.drive)
        val ret = FloatArray(full)
        val out = FloatArray(full)
        for (i in 0 until full) {
            // A BURST is shaped by its own envelope; only STONE is gated.
            val g = if (space.mode == Mode.STONE) gate[i] else 1f
            ret[i] = send * clipped[i] * g
            val d = if (i < dry.size) dry[i] * (1f - space.dryCut * (1f - gate[i])) else 0f
            out[i] = d + ret[i]
        }
        return Stages(out, ret, if (space.mode == Mode.STONE) gate else FloatArray(full) { 1f }, attacks)
    }

    /** Where the dry signal has an attack: the frames the gate is keyed from. */
    internal fun attacks(dry: FloatArray): List<Int> {
        val release = onePole(KEY_HOLD_RELEASE_SEC)
        val back = (KEY_LOOKBACK_SEC * RATE).toInt()
        val rearm = (KEY_REARM_SEC * RATE).toInt()
        val held = FloatArray(back)      // the held peak, [back] frames ago
        var hold = 0f
        var last = -1
        val found = mutableListOf<Int>()
        for (i in dry.indices) {
            val a = abs(dry[i])
            val before = held[i % back]
            if (a > KEY_FLOOR && a > KEY_LEAD * before) {
                if (last < 0 || i - last > rearm) found += i
                last = i      // still inside one attack: keep the re-arm window open
            }
            hold = if (a > hold) a else hold - release * hold
            held[i % back] = hold
        }
        return found
    }

    /** Opens in [GATE_OPEN_SEC] at each attack, holds [holdFrames] after the last, closes over [closeFrames]. */
    internal fun gate(attacks: List<Int>, frames: Int, holdFrames: Int, closeFrames: Int): FloatArray {
        val out = FloatArray(frames)
        val openStep = 1f / (GATE_OPEN_SEC * RATE)
        val closeStep = 1f / closeFrames
        var next = 0
        var until = -1
        var g = 0f
        for (i in 0 until frames) {
            while (next < attacks.size && attacks[next] <= i) { until = attacks[next] + holdFrames; next++ }
            g = if (i <= until) min(1f, g + openStep) else max(0f, g - closeStep)
            // A raised cosine on the ramp: no corner at either end of the close, so the cut does not click.
            out[i] = (0.5 - 0.5 * cos(PI * g)).toFloat()
        }
        return out
    }

    /**
     * A room is a pure function of SIZE and WALLS, and measuring its late level costs a 1.2 s network run, so
     * each room is planned once and kept. The plan holds no state between renders.
     */
    private val rooms = java.util.concurrent.ConcurrentHashMap<Pair<Float, Float>, Pair<Room.Plan, Float>>()

    /** STONE step 1: the room's answer with no direct sound, [frames] long. */
    private fun roomReturn(dry: FloatArray, space: Space, frames: Int): FloatArray {
        val (p, lateGain) = rooms.getOrPut(space.size to space.walls) {
            val plan = Room.plan(Room.settled(mapOf("SIZE" to space.size, "WALLS" to space.walls, "DISTANCE" to FAR_MIC)), RATE)
            plan to Room.lateGain(plan, RATE)
        }
        val out = FloatArray(frames)
        // The walls' first answers. The loop is Room.process's own: that function sums them with the direct
        // sound and peak-matches the total, so the return cannot be had from it by subtraction.
        val early = Array(3) { FloatArray(frames) }
        for ((delay, gain, bounces) in p.taps) {
            val whole = delay.toInt()
            val frac = delay - whole
            val e = early[bounces]
            for (i in dry.indices) {
                val k = i + whole
                if (k + 1 >= frames) break
                e[k] += dry[i] * gain * (1f - frac)
                e[k + 1] += dry[i] * gain * frac
            }
        }
        for (b in 1..2) {
            val e = early[b]
            repeat(b) { val lp = Dsp.FixedOnePole(p.wallHz, RATE); for (i in e.indices) e[i] = lp.lp(e[i]) }
            for (i in 0 until frames) out[i] += e[i]
        }
        val feed = FloatArray(frames)
        for (i in dry.indices) if (i + p.bloomStart < frames) feed[i + p.bloomStart] = dry[i]
        val late = Room.bloom(feed, p, RATE)
        for (i in 0 until frames) out[i] += lateGain * late[i]
        return out
    }

    /**
     * STONE step 2: the crush. The threshold is set against the level a *full* hit's room first reaches
     * (this hit's peak over [touch]), so a soft hit meets the same threshold from lower down. The result is
     * scaled so the threshold is 1: a held room sits a little above 1 whatever the room was.
     */
    private fun crushed(wet: FloatArray, space: Space, touch: Float): FloatArray {
        var peak = 0f
        for (v in wet) peak = max(peak, abs(v))
        if (peak <= 1e-12f) return wet
        val threshold = (peak / touch) * 10f.pow(-crushDepthDb(space.crush) / 20f)
        val attack = onePole(CRUSH_ATTACK_SEC)
        val release = onePole(CRUSH_RELEASE_SEC)
        val slope = 1f / CRUSH_RATIO - 1f
        var env = 0f
        val out = FloatArray(wet.size)
        for (i in wet.indices) {
            val a = abs(wet[i])
            env += (if (a > env) attack else release) * (a - env)
            val gain = if (env > threshold) (env / threshold).pow(slope) else 1f
            out[i] = wet[i] * gain / threshold
        }
        return out
    }

    /**
     * BURST: the hit convolved with sparse, randomly signed taps whose level is flat or rising for the hold
     * time, then closes. Scaled, like [crushed], so a full hit's burst sits near 1.
     */
    private fun burst(hit: FloatArray, attacks: List<Int>, space: Space, frames: Int, touch: Float, seed: Int): FloatArray {
        val window = gate(attacks, hit.size, (BURST_SEND_SEC * RATE).toInt(), (BURST_SEND_CLOSE_SEC * RATE).toInt())
        val dry = FloatArray(hit.size) { hit[it] * window[it] }
        val hold = space.holdMs * 0.001f
        val close = space.closeMs * 0.001f
        val pre = (BURST_PREDELAY_SEC * RATE).toInt()
        val cell = RATE / BURST_TAPS_PER_SEC
        val count = ((hold + close) * BURST_TAPS_PER_SEC).toInt().coerceAtLeast(8)
        val noise = Dsp.Noise(Dsp.seedFor("GATECRASH", "burst", seed))
        val at = IntArray(count)
        val gain = FloatArray(count)
        var energy = 0.0
        for (k in 0 until count) {
            val u = (noise.next() + 1f) * 0.5f
            at[k] = pre + ((k + u.coerceIn(0f, 0.999f)) * cell).toInt()
            val t = k * cell / RATE
            val lean = 1f + space.tilt * (t / hold - 0.5f)
            val shut = if (t <= hold) 1f else (0.5 + 0.5 * cos(PI * ((t - hold) / close).coerceIn(0f, 1f))).toFloat()
            gain[k] = (if (noise.next() >= 0f) 1f else -1f) * lean.coerceAtLeast(0.1f) * shut
            energy += gain[k].toDouble() * gain[k]
        }
        val norm = (1.0 / sqrt(max(energy, 1e-12))).toFloat()
        val out = FloatArray(frames)
        // Only the windowed front of each hit is non-zero: stop at its end rather than convolve silence.
        var live = 0
        for (i in dry.indices) if (dry[i] != 0f) live = i + 1
        for (k in 0 until count) {
            val g = gain[k] * norm
            val off = at[k]
            val n = min(live, frames - off)
            for (i in 0 until n) out[off + i] += dry[i] * g
        }
        // The box's converters and filters: no sub, and a top set by WALLS.
        val low = Dsp.FixedOnePole(180f, RATE)
        val high = Dsp.FixedOnePole(Dsp.expMap(space.walls, 3_500f, 12_000f), RATE)
        for (i in out.indices) { val v = out[i]; out[i] = high.lp(v - low.lp(v)) }
        // Scale by the burst's loudest 5 ms for a full hit, so `room` means the same thing in both modes.
        val span = (0.005f * RATE).toInt()
        var sum = 0.0
        var loudest = 0.0
        for (i in out.indices) {
            sum += out[i].toDouble() * out[i]
            if (i >= span) sum -= out[i - span].toDouble() * out[i - span]
            loudest = max(loudest, sum)
        }
        val level = sqrt(loudest / span).toFloat() * 1.6f
        if (level <= 1e-12f) return out
        val k = touch / level
        for (i in out.indices) out[i] *= k
        return out
    }

    /**
     * The overloaded amplifier: tanh, scaled so a level of 1 stays 1. Run at 4x and brought back through the
     * house band-limiter, as [Valve] and [Forge] do, so the clip's harmonics do not fold into the audio band.
     */
    internal fun softClip(x: FloatArray, drive: Float): FloatArray {
        // Only the live part goes through the filters. A burst is exact silence after it ends, and the
        // band-limiter's states then decay through denormal numbers, which the processor handles slowly:
        // measured 97 ms for a silent tail against 12 ms for the same length ringing.
        var live = 0
        for (i in x.indices) if (abs(x[i]) > SILENCE) live = i + 1
        if (live == 0) return FloatArray(x.size)
        val head = if (live + CLIP_RING_FRAMES >= x.size) x else x.copyOf(live + CLIP_RING_FRAMES)
        val g = driveGain(drive)
        val unity = tanh(g)
        val up = Valve.upsample(head, RATE)
        for (i in up.indices) up[i] = tanh(up[i] * g) / unity
        Tide.bandLimit(up, RATE * Dsp.OVERSAMPLE)
        return Dsp.decimate(up, RATE).copyOf(x.size)
    }

    private const val SILENCE = 1e-9f

    /** Room left after the last live sample for the band-limiter to finish ringing: 6 ms. */
    private const val CLIP_RING_FRAMES = 256

    private fun onePole(seconds: Float): Float = (1.0 - kotlin.math.exp(-1.0 / (seconds * RATE))).toFloat()
}

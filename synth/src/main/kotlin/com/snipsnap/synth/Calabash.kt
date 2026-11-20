package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.CalabashDsp.FS
import com.snipsnap.synth.CalabashDsp.RATE
import com.snipsnap.synth.CalabashDsp.Rng
import com.snipsnap.synth.CalabashDsp.TAU
import com.snipsnap.synth.CalabashDsp.add
import com.snipsnap.synth.CalabashDsp.band
import com.snipsnap.synth.CalabashDsp.conv
import com.snipsnap.synth.CalabashDsp.hannForce
import com.snipsnap.synth.CalabashDsp.hp
import com.snipsnap.synth.CalabashDsp.lin
import com.snipsnap.synth.CalabashDsp.lp
import com.snipsnap.synth.CalabashDsp.modal
import com.snipsnap.synth.CalabashDsp.nOf
import com.snipsnap.synth.CalabashDsp.peak
import com.snipsnap.synth.CalabashDsp.peakNorm
import com.snipsnap.synth.CalabashDsp.semis
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** The 36 CALABASH voices, each filed as the kit role it plays, with its length and its controls. */
enum class CalabashVoice(val drumClass: DrumClass, internal val seconds: Double, internal val controls: List<String>) {
    // the kit
    KICK(DrumClass.KICK, 0.90, listOf("TUNE", "DAMP", "BEATER")),
    DEEP_KICK(DrumClass.KICK, 1.00, listOf("TUNE", "DAMP", "CLICK")),
    SNARE(DrumClass.SNARE, 0.80, listOf("TUNE", "DAMP", "WIRES")),
    RIMSHOT(DrumClass.SNARE, 0.80, listOf("TUNE", "DAMP", "WIRES")),
    RIM(DrumClass.PERC, 0.45, listOf("TUNE", "DAMP")),
    TOM(DrumClass.TOM, 1.20, listOf("TUNE", "DAMP")),
    HAT_CLOSED(DrumClass.HAT_CLOSED, 0.45, listOf("TUNE", "DAMP")),
    HAT_OPEN(DrumClass.HAT_OPEN, 1.20, listOf("TUNE", "DAMP")),
    HAT_FOOT(DrumClass.HAT_CLOSED, 0.45, listOf("TUNE", "DAMP")),
    RIDE(DrumClass.PERC, 2.20, listOf("TUNE", "DAMP")),
    CRASH(DrumClass.PERC, 2.60, listOf("TUNE", "DAMP")),
    CLAP(DrumClass.CLAP, 0.60, listOf("TUNE", "CROWD")),
    SNAP(DrumClass.CLAP, 0.55, listOf("TUNE", "CROWD")),
    SHAKER(DrumClass.PERC, 0.50, listOf("TUNE", "STROKE")),
    // hand drums (fitted)
    QUINTO(DrumClass.PERC, 0.90, listOf("TUNE", "DAMP", "STROKE")),
    CONGA(DrumClass.PERC, 0.95, listOf("TUNE", "DAMP", "STROKE")),
    TUMBA(DrumClass.PERC, 1.05, listOf("TUNE", "DAMP", "STROKE")),
    BONGO_HIGH(DrumClass.PERC, 0.70, listOf("TUNE", "DAMP", "STROKE")),
    BONGO_LOW(DrumClass.PERC, 0.75, listOf("TUNE", "DAMP", "STROKE")),
    // bells and wood (fitted)
    COWBELL(DrumClass.PERC, 0.90, listOf("TUNE", "DAMP", "STROKE")),
    BIG_BELL(DrumClass.PERC, 1.10, listOf("TUNE", "DAMP", "STROKE")),
    AGOGO_HIGH(DrumClass.PERC, 1.10, listOf("TUNE", "DAMP")),
    AGOGO_LOW(DrumClass.PERC, 1.30, listOf("TUNE", "DAMP")),
    CLAVE(DrumClass.PERC, 0.60, listOf("TUNE", "DAMP")),
    STICKS(DrumClass.PERC, 0.60, listOf("TUNE", "DAMP")),
    // physical
    SHEKERE(DrumClass.PERC, 0.70, listOf("TUNE", "DAMP", "STROKE")),
    DUNDUN(DrumClass.PERC, 1.00, listOf("TUNE", "SQUEEZE", "CONTOUR")),
    TAMA(DrumClass.PERC, 0.80, listOf("TUNE", "SQUEEZE", "CONTOUR")),
    NDER(DrumClass.PERC, 0.80, listOf("TUNE", "DAMP", "STROKE")),
    MBENG(DrumClass.PERC, 0.85, listOf("TUNE", "DAMP", "STROKE")),
    COL(DrumClass.TOM, 1.10, listOf("TUNE", "DAMP", "STROKE")),
    TIMBALE(DrumClass.PERC, 1.10, listOf("TUNE", "DAMP", "STROKE")),
    // the four authored rolls and risers (the percussion gate)
    SHEKERE_RUSH(DrumClass.PERC, 2.60, listOf("TUNE", "SPEED")),
    TALKING_CALL(DrumClass.PERC, 2.60, listOf("TUNE", "SPEED", "SQUEEZE")),
    SABAR_ROLL(DrumClass.PERC, 2.40, listOf("TUNE", "SPEED")),
    ABANICO(DrumClass.PERC, 2.20, listOf("TUNE", "SPEED")),
}

/**
 * CALABASH: drum kits for Afrobeat, Afrobeats, Mbalax and Afro-Cuban jazz.
 *
 * Round 1: the Kotlin port of the round-0 Python prototype (`prototype/calabash/`, docs/CALABASH.md), which the owner
 * heard and approved on 2026-11-15 ("Sounds great"; "I listened: approve all"). Every constant here is that
 * prototype's, read at 967f53f7f, including the pieces it borrowed from EMBER's prototype (the membrane, the stick,
 * mallet and hand, the snare wires, the snapping crowd, the 1962 chain), which are pinned here because EMBER is still
 * changing. What cannot be the same arithmetic is disclosed in [CalabashDsp]: another random generator (so every
 * take is another realisation of the same law), the house decimator, and each mode stopping at -80 dB on its own.
 *
 * The gesture: a palm, fingertips or a thin stick lands on a skin, a bell or a gourd → the stroke decides which of
 * the instrument's own resonances ring → the hand, the bead net or the jingles answer → the room and the era's tape
 * close around it.
 *
 *     voice, macros, velocity, take
 *        ─► stroke (palm | fingertips | cupped slap | switch | curved stick | timbale stick | bead net | a fit's own strike)
 *        ─► body: fitted modes + residual @44.1 kHz, upsampled | membrane modes on a pitch path + the dense upper head
 *                 | the shell's air (tube, cavity, gourd) | steel ring modes | EMBER's hats, BREAKER's ride and crash
 *        ─► ROOM (cached) ─► AGE: ribbon, valve, tape ─► band-limit, decimate, DC, fade, peak 0.89
 *
 * - **Fitted where a CC0 recording exists** ([CalabashFits]): quinto, conga, tumba, both bongos, the cowbells, agogo
 *   bells, claves, shaker and claps. Their open and muffled strokes are the recorded strokes; the bass and slap are
 *   built on them (no slap recording exists in the library).
 * - **Physical where none does**: the shekere (a bead cloud on a gourd), the dundun and tama (both heads tighten as
 *   the arm squeezes, so every mode rises while it rings), the sabar family, the timbales, the kit drums.
 * - **STROKE is the event**, not a filter: between two anchor strokes both are rendered and blended at equal power
 *   (an analogy of a hand partly one way, not a solve).
 * - **A take is another landing**, seeded by voice and take only, so velocity never reshuffles it.
 *
 * Controls: TUNE, DAMP, STROKE, SQUEEZE, CONTOUR, BEATER, CLICK, WIRES, CROWD, SPEED by voice, and ROOM and AGE on
 * every voice (AGE 0 is an exact bypass). All 0..1. No HOLD: every hit is finite. No maker or model names a voice.
 */
object Calabash {

    const val RATE = Dsp.RATE
    /** Round 0's arithmetic, approved. Bump when a pad's audio changes; saved patches carry it. */
    const val MODEL_VERSION = 1
    /** The takes a pad may name, 0 to [MAX_TAKE]: four round-robin strikes. */
    const val MAX_TAKE = 3
    /** Every hit is peak-matched to this, as the prototype's audition was. */
    const val PEAK = 0.89f

    private val DEFAULTS = mapOf(
        "TUNE" to .5f, "DAMP" to .3f, "BEATER" to .5f, "CLICK" to .5f, "WIRES" to .5f, "CROWD" to .3f,
        "STROKE" to (1f / 3f), "SQUEEZE" to .45f, "CONTOUR" to 0f, "SPEED" to .5f, "ROOM" to .25f, "AGE" to .3f,
    )
    /** Each voice opens on its signature stroke. */
    private val VOICE_DEFAULTS: Map<CalabashVoice, Map<String, Float>> = mapOf(
        CalabashVoice.SHAKER to mapOf("STROKE" to 0f), CalabashVoice.COWBELL to mapOf("STROKE" to 0f),
        CalabashVoice.BIG_BELL to mapOf("STROKE" to 0f), CalabashVoice.SHEKERE to mapOf("STROKE" to 0f),
        CalabashVoice.NDER to mapOf("STROKE" to (2f / 3f)), CalabashVoice.MBENG to mapOf("STROKE" to 0f),
        CalabashVoice.COL to mapOf("STROKE" to 0f), CalabashVoice.TIMBALE to mapOf("STROKE" to 0f),
        CalabashVoice.TAMA to mapOf("SQUEEZE" to .35f),
    )

    fun macrosFor(voice: CalabashVoice): List<MacroSpec> =
        (voice.controls + listOf("ROOM", "AGE")).map { MacroSpec(it, VOICE_DEFAULTS[voice]?.get(it) ?: DEFAULTS.getValue(it)) }

    fun defaults(voice: CalabashVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] over the defaults: unknown keys dropped, a non-finite value falls back, everything clamped 0..1. */
    fun settled(voice: CalabashVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun drumClassFor(voice: CalabashVoice): DrumClass = voice.drumClass

    /** SCRAMBLE near the defaults (there is no preset bank yet), the way CLUTTER and SCUFF roll. */
    fun scramble(voice: CalabashVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** The stroke anchors STROKE moves between, per voice family, with their names (the audition prints them). */
    internal val HAND = doubleArrayOf(0.0, 1.0 / 3, 2.0 / 3, 1.0)
    internal val SABAR = HAND
    internal val THREE = doubleArrayOf(0.0, 0.5, 1.0)
    internal val TWO = doubleArrayOf(0.0, 1.0)

    /** One hit at 44.1 kHz: [velocity] 0..1 is the stroke's force, [take] 0..[MAX_TAKE] which of four landings. */
    fun render(voice: CalabashVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0): Snip {
        val s = settled(voice, macros)
        val v = (if (velocity.isFinite()) velocity.toDouble() else 1.0).coerceIn(0.05, 1.0)
        val m = s.mapValues { it.value.toDouble() }
        val seconds = voice.seconds
        val n = nOf(seconds + 0.05)
        val tr = CalabashDsp.rngFor(voice.name, take.coerceIn(0, MAX_TAKE))
        val raw = voiceRaw(voice, m, v, tr, n)
        check(raw.all { it.isFinite() }) { "CALABASH $voice rendered a non-finite sample" }
        return Snip(finish(raw, m, v, seconds), channels = 1, sampleRate = RATE)
    }

    /**
     * One stroke from any landing, not only the four takes: [render] with [landing] left unclamped, so landings 0 to
     * [MAX_TAKE] are exactly [render]'s takes. A RIDDIM roll strikes a CALABASH drum dozens of times in one bar
     * (Sabar Swing's nder roll), and each stroke is its own landing, as RIDDIM's prototype draws it; clamped, the
     * strokes would repeat four landings and every take of the roll would be the same performance.
     */
    internal fun stroke(voice: CalabashVoice, macros: Map<String, Float>, velocity: Float, landing: Int): Snip {
        val s = settled(voice, macros)
        val v = (if (velocity.isFinite()) velocity.toDouble() else 1.0).coerceIn(0.05, 1.0)
        val m = s.mapValues { it.value.toDouble() }
        val seconds = voice.seconds
        val raw = voiceRaw(voice, m, v, CalabashDsp.rngFor(voice.name, landing), nOf(seconds + 0.05))
        check(raw.all { it.isFinite() }) { "CALABASH $voice rendered a non-finite sample" }
        return Snip(finish(raw, m, v, seconds), channels = 1, sampleRate = RATE)
    }

    /** A hit drawn from any seed, not only the four takes: for the round-1 A/B of the port's take law against the
     * prototype's (`CalabashLawTest`, `compare_kotlin.py`), never for a kit. */
    internal fun renderSeeded(voice: CalabashVoice, macros: Map<String, Float>, velocity: Float, seed: Int): Snip {
        val s = settled(voice, macros)
        val v = (if (velocity.isFinite()) velocity.toDouble() else 1.0).coerceIn(0.05, 1.0)
        val m = s.mapValues { it.value.toDouble() }
        val n = nOf(voice.seconds + 0.05)
        return Snip(finish(voiceRaw(voice, m, v, Rng(seed), n), m, v, voice.seconds), channels = 1, sampleRate = RATE)
    }

    private fun voiceRaw(voice: CalabashVoice, m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray = when (voice) {
        CalabashVoice.KICK -> kick(m, v, tr, n)
        CalabashVoice.DEEP_KICK -> deepKick(m, v, tr, n)
        CalabashVoice.SNARE -> snare(m, v, tr, n)
        CalabashVoice.RIMSHOT -> rimshot(m, v, tr, n)
        CalabashVoice.RIM -> rim(m, v, tr, n)
        CalabashVoice.TOM -> tom(m, v, tr, n)
        CalabashVoice.HAT_CLOSED -> CalabashDsp.upsample4(CalabashFits.envelopeHat("hat_closed", m.g("TUNE"), m.g("DAMP"), v, tr, n / 4 + 1), n)
        CalabashVoice.HAT_FOOT -> CalabashDsp.upsample4(CalabashFits.envelopeHat("hat_pedal", m.g("TUNE"), m.g("DAMP"), v, tr, n / 4 + 1), n)
        CalabashVoice.HAT_OPEN -> CalabashFits.struck("hat_open", m.g("TUNE"), m.g("DAMP"), v, tr, n)
        CalabashVoice.RIDE -> CalabashFits.struck("breaker_ride", m.g("TUNE"), m.g("DAMP"), v, tr, n)
        CalabashVoice.CRASH -> CalabashFits.struck("breaker_crash", m.g("TUNE"), m.g("DAMP"), v, tr, n, burst = 0.8)
        CalabashVoice.CLAP -> clap(m, v, tr, n)
        CalabashVoice.SNAP -> snaps(m, v, tr, n)
        CalabashVoice.SHAKER -> shaker(m, v, tr, n)
        CalabashVoice.QUINTO -> handDrum("quinto", 4.0, m, v, tr, n)
        CalabashVoice.CONGA -> handDrum("conga", 4.0, m, v, tr, n)
        CalabashVoice.TUMBA -> handDrum("tumba", 4.0, m, v, tr, n)
        CalabashVoice.BONGO_HIGH -> handDrum("bongo_hi", 4.0, m, v, tr, n)
        CalabashVoice.BONGO_LOW -> handDrum("bongo_lo", 4.0, m, v, tr, n)
        CalabashVoice.COWBELL -> blended(m.g("STROKE"), THREE) { fittedSimple(listOf("bell1_mouth", "bell1_body", "bell1_muted")[it], m, v, tr, n) }
        CalabashVoice.BIG_BELL -> blended(m.g("STROKE"), TWO) { fittedSimple(listOf("bell2_open", "bell2_muted")[it], m, v, tr, n) }
        CalabashVoice.AGOGO_HIGH -> fittedSimple("agogo_hi", m, v, tr, n)
        CalabashVoice.AGOGO_LOW -> fittedSimple("agogo_lo", m, v, tr, n)
        CalabashVoice.CLAVE -> fittedSimple("clave2", m, v, tr, n, contact = 0.15)
        CalabashVoice.STICKS -> fittedSimple("clave1", m, v, tr, n, contact = 0.15)
        CalabashVoice.SHEKERE -> blended(m.g("STROKE"), THREE) { shekereStroke(m, v, tr, n, SHAKE_KINDS[it]) }
        CalabashVoice.DUNDUN -> talking(m, v, tr, n, 150.0, 0.55, 1.0, 0.38e-3, 0.62)
        CalabashVoice.TAMA -> talking(m, v, tr, n, 420.0, 0.45, 1.05, 0.25e-3, 0.7, kind = "TAMA", riseS = 0.065)
        CalabashVoice.NDER -> sabar(m, v, tr, n, 340.0, 0.78, closed = false)
        CalabashVoice.MBENG -> sabar(m, v, tr, n, 270.0, 0.55, closed = false)
        CalabashVoice.COL -> sabar(m, v, tr, n, 125.0, 0.5, closed = true)
        CalabashVoice.TIMBALE -> timbaleStroke(m, v, tr, n, m.g("STROKE"))
        CalabashVoice.SHEKERE_RUSH -> shekereRush(m, v, tr, n)
        CalabashVoice.TALKING_CALL -> talkingCall(m, v, tr, n)
        CalabashVoice.SABAR_ROLL -> sabarRoll(m, v, tr, n)
        CalabashVoice.ABANICO -> abanico(m, v, tr, n)
    }

    private fun Map<String, Double>.g(k: String): Double = getValue(k)

    // ------------------------------------------------------------------ strokes between anchors

    /**
     * The prototype's `blended`: the anchor strokes either side of [x], blended at equal power; at an anchor (within
     * 1e-6, which also absorbs a Float macro's rounding) only that stroke is rendered.
     */
    private fun blended(x: Double, anchors: DoubleArray, make: (Int) -> DoubleArray): DoubleArray {
        if (x <= anchors[0]) return make(0)
        for (i in 0 until anchors.size - 1) {
            if (x <= anchors[i + 1]) {
                val w = (x - anchors[i]) / (anchors[i + 1] - anchors[i])
                if (w <= 1e-6) return make(i)
                if (w >= 1 - 1e-6) return make(i + 1)
                val a = make(i)
                val b = make(i + 1)
                val n = max(a.size, b.size)
                val ca = cos(w * PI / 2); val sb = sin(w * PI / 2)
                return DoubleArray(n) { (if (it < a.size) ca * a[it] else 0.0) + (if (it < b.size) sb * b[it] else 0.0) }
            }
        }
        return make(anchors.size - 1)
    }

    // ------------------------------------------------------------------ the room and the era

    private val rooms = ConcurrentHashMap<Int, DoubleArray>()

    /**
     * ROOM, cached: a dead booth (0) to the back of a big live studio (1). Early reflections at delays that grow with
     * the room, then a two-band tail (lows 0.2 to 1.1 s, highs 0.12 to 0.6 s), balanced by energy from +18 dB
     * direct-to-room over the drum to -2 dB at the back. Shape: no real room was measured.
     */
    internal fun roomIr(room: Double): DoubleArray = rooms.getOrPut(Math.round(room * 1000).toInt()) {
        // The prototype's own room noise: its lows are the generator's first n draws, its highs the next n.
        val noise = CalabashFits.roomNoise
        val size = 0.35 + 1.65 * room
        val rtLo = lin(room, 0.2, 1.1); val rtHi = lin(room, 0.12, 0.6)
        val n = nOf(min(1.6, 0.05 + rtLo * 1.25))
        val h = DoubleArray(n)
        val taps = arrayOf(doubleArrayOf(3.1, .55, 7000.0), doubleArrayOf(4.9, .45, 8000.0), doubleArrayOf(7.3, .38, 5500.0),
            doubleArrayOf(10.2, .30, 6500.0), doubleArrayOf(13.6, .24, 4500.0), doubleArrayOf(17.9, .2, 5000.0),
            doubleArrayOf(23.0, .15, 4000.0), doubleArrayOf(29.5, .12, 3500.0))
        for ((ms, g, cut) in taps.map { Triple(it[0], it[1], it[2]) }) {
            val i = (ms * size * 1e-3 * FS).toInt()
            if (i >= n) continue
            val tap = DoubleArray(nOf(0.004)).also { it[0] = g }
            val y = lp(tap, cut, 1)
            for (j in y.indices) if (i + j < n) h[i + j] += y[j]
        }
        check(2 * n <= noise.size) { "room $room needs ${2 * n} noise values" }
        val lo = lp(noise.copyOfRange(0, n), 1000.0)
        val hi = hp(noise.copyOfRange(n, 2 * n), 1000.0)
        for (i in 0 until n) {
            val t = i.toDouble() / FS
            val onset = ((t - 0.004 * size) / (0.02 * size)).coerceIn(0.0, 1.0)
            h[i] += (lo[i] * exp(-6.9078 * t / rtLo) * 0.09 + hi[i] * exp(-6.9078 * t / rtHi) * 0.06) * onset * 1.6
        }
        val hl = lp(h, 11000.0, 1)
        val direct = DoubleArray(nOf(0.01)).also { it[(room * 0.004 * FS).toInt()] = 1.0 }
        val dl = lp(direct, CalabashDsp.expMap(room, 20000.0, 7000.0), 1)
        val drrDb = lin(room, 18.0, -2.0)
        val out = DoubleArray(n)
        val dn = sqrt(dl.sumOf { it * it })
        val hn = sqrt(hl.sumOf { it * it })
        for (i in dl.indices) out[i] += dl[i] / dn
        for (i in 0 until n) out[i] += hl[i] / hn * CalabashDsp.db(-drrDb)
        out
    }

    /**
     * AGE, EMBER's 1962 chain at its round-0e voicing: a ribbon mic's soft top and low lift, a valve preamp
     * (asymmetric soft clip, driven harder by harder hits), tape's head bump. AGE 0 returns its input.
     */
    internal fun ageChain(x: DoubleArray, warmth: Double, v: Double): DoubleArray {
        if (warmth <= 0) return x
        var y = lp(x, CalabashDsp.expMap(warmth, 19000.0, 10500.0), 2)
        val low = lp(y, 160.0, 1)
        y = DoubleArray(y.size) { y[it] + warmth * 0.15 * low[it] }
        val pk = peak(y) + 1e-12
        val drive = 0.6 + 3.0 * warmth * (0.4 + 0.6 * v)
        val bias = 0.18 * warmth
        val tb = tanh(bias)
        y = DoubleArray(y.size) {
            val z = y[it] / pk * drive
            (1 - warmth) * y[it] + warmth * ((tanh(z + bias) - tb) / drive * pk)
        }
        val (b, a) = CalabashDsp.peaking(70.0, 2.8 * warmth, 0.9)
        y = CalabashDsp.lfilter(b, a, y)
        return lp(y, CalabashDsp.expMap(warmth, 19500.0, 13000.0), 1)
    }

    /** ROOM, AGE, then the house output stage at 44.1 kHz: band-limit, decimate, DC out, a 50 ms fade, peak 0.89. */
    private fun finish(raw: DoubleArray, m: Map<String, Double>, v: Double, seconds: Double): FloatArray {
        val n = raw.size
        var y = conv(raw, roomIr(Math.round(m.g("ROOM") * 1000) / 1000.0), n)
        y = ageChain(y, m.g("AGE"), v)
        val f = FloatArray(n) { y[it].toFloat() }
        Tide.bandLimit(f, FS)
        val dec = Dsp.decimate(f, RATE)
        val len = min(dec.size, (seconds * RATE).toInt())
        var mean = 0.0
        for (x in dec) mean += x
        mean /= dec.size
        val rc = 1 / (TAU * 20.0)
        val al = rc / (rc + 1.0 / RATE)
        val out = DoubleArray(len)
        var px = 0.0; var py = 0.0
        for (i in 0 until len) { val xi = dec[i] - mean; py = al * xi - al * px + al * py; px = xi; out[i] = py }
        val fade = min(len, (0.05 * RATE).toInt())
        for (q in 0 until fade) out[len - fade + q] *= 0.5 * (1 + cos(PI * q / (fade - 1).coerceAtLeast(1)))
        val p = peak(out)
        val g = if (p > 0) PEAK / p else 0.0
        return FloatArray(len) { (out[it] * g).toFloat() }
    }

    // ------------------------------------------------------------------ EMBER's membrane and implements (pinned)

    /** The 20 lowest zeros of J_m for m 0..9 (scipy `jn_zeros`), ascending: (zero, m, n). */
    private val BESSEL = arrayOf(
        doubleArrayOf(2.4048255576957724, 0.0, 1.0), doubleArrayOf(3.8317059702075125, 1.0, 1.0), doubleArrayOf(5.135622301840683, 2.0, 1.0),
        doubleArrayOf(5.520078110286311, 0.0, 2.0), doubleArrayOf(6.380161895923984, 3.0, 1.0), doubleArrayOf(7.015586669815619, 1.0, 2.0),
        doubleArrayOf(7.588342434503804, 4.0, 1.0), doubleArrayOf(8.417244140399866, 2.0, 2.0), doubleArrayOf(8.653727912911013, 0.0, 3.0),
        doubleArrayOf(8.771483815959954, 5.0, 1.0), doubleArrayOf(9.76102312998167, 3.0, 2.0), doubleArrayOf(9.936109524217686, 6.0, 1.0),
        doubleArrayOf(10.173468135062722, 1.0, 3.0), doubleArrayOf(11.064709488501185, 4.0, 2.0), doubleArrayOf(11.086370019245084, 7.0, 1.0),
        doubleArrayOf(11.61984117214906, 2.0, 3.0), doubleArrayOf(11.791534439014281, 0.0, 4.0), doubleArrayOf(12.225092264004656, 8.0, 1.0),
        doubleArrayOf(12.338604197466944, 5.0, 2.0), doubleArrayOf(13.015200721698434, 3.0, 3.0),
    )

    /**
     * EMBER's `membrane_ir`: a headed drum struck at radius [r] (0 centre .. 1 edge), [angle]. Mode k's t60 is
     * t1 (f_k / f1)^-alpha; strike weight |J_m(z r)| times the angle's pattern; m = 0 radiates best. [reso]: the second
     * head rings the low modes a little higher (ratio, gain). [sag]: the pitch starts that much sharp and settles in
     * [sagTau]; [bend], when given, is the pitch path itself (a ratio per sample). [scatter]: a take's 1.5 dB per mode.
     */
    private fun membrane(f1: Double, n: Int, t1: Double, alpha: Double, r: Double, angle: Double, sag: Double = 0.0,
                         sagTau: Double = 0.05, reso: DoubleArray? = doubleArrayOf(1.025, 0.35), count: Int = 18,
                         bend: DoubleArray? = null, scatter: Rng? = null): DoubleArray {
        val j01 = BESSEL[0][0]
        val fr = ArrayList<Double>(); val am = ArrayList<Double>(); val tt = ArrayList<Double>(); val ph = ArrayList<Double>()
        for (row in BESSEL.take(count)) {
            val z = row[0]; val m = row[1].toInt(); val nn = row[2]
            val ratio = z / j01
            val f = f1 * ratio
            val wgt = abs(Bessel.j(m, z * r)) * (if (m == 0) 1.0 else cos(m * angle).pow(2) * 0.8 + 0.2)
            val rad = if (m == 0) 1.0 else if (m == 1) 0.55 else 0.35
            var a = wgt * rad / (1 + 0.15 * nn)
            if (scatter != null) a *= CalabashDsp.db(scatter.normal(0.0, 1.5))
            val t60 = t1 * ratio.pow(-alpha)
            fr += f; am += a; tt += t60; ph += 0.0
            if (reso != null && ratio < 3.2) { fr += f * reso[0]; am += a * reso[1]; tt += t60 * 1.15; ph += 0.4 }
        }
        val warp: DoubleArray? = when {
            bend != null -> { var s = 0.0; DoubleArray(n) { s += bend[it]; s / FS } }
            sag > 0 -> DoubleArray(n) { val t = it.toDouble() / FS; t + sag * sagTau * (1 - exp(-t / sagTau)) }
            else -> null
        }
        return modal(fr.toDoubleArray(), am.toDoubleArray(), tt.toDoubleArray(), n, ph.toDoubleArray(), warp)
    }

    /** EMBER's `stick`: a hickory tip, a short contact that grows longer (darker) and much weaker when soft. */
    private fun stick(v: Double, tr: Rng, n: Int, widthHard: Double = 0.28e-3, at: Double = 0.0): DoubleArray {
        val w = widthHard * v.pow(-0.3) * (1 + 0.07 * tr.normal())
        return hannForce(w, v.pow(1.25), n, at)
    }

    /** EMBER's `mallet`: a felt beater's broad contact. */
    private fun mallet(v: Double, tr: Rng, n: Int, at: Double = 0.0, widthHard: Double = 1.6e-3): DoubleArray {
        val w = widthHard * v.pow(-0.35) * (1 + 0.06 * tr.normal())
        return hannForce(w, v.pow(1.3), n, at)
    }

    /** EMBER's `hand`: a palm's broad push plus four finger slaps; cupping sharpens the slap. */
    private fun hand(v: Double, tr: Rng, n: Int, at: Double = 0.0, cupped: Double = 0.0): DoubleArray {
        val f = hannForce((2.4 - 1.2 * cupped) * 1e-3 * v.pow(-0.2), v.pow(1.2), n, at)
        for (i in 0 until 4) {
            val amt = (0.15 + 0.5 * cupped) * v.pow(1.6) * tr.uniform(0.6, 1.0)
            add(f, hannForce(0.35e-3, amt, n, at + 0.0006 * i + tr.uniform(0.0, 4e-4)))
        }
        return f
    }

    /**
     * CALABASH's stick tip: a half-sine contact (12 dB an octave above its corner, where EMBER's raised cosine is gone
     * above 2/w) plus the tip's own 0.04 ms click. Width grows as velocity falls (v^-0.3); force v^1.25.
     */
    private fun tip(v: Double, tr: Rng, n: Int, widthHard: Double = 0.28e-3, at: Double = 0.0, scale: Double = 1.0,
                    click: Double = 0.06): DoubleArray {
        val w = max(4, (widthHard * v.pow(-0.3) * (1 + 0.07 * tr.normal()) * FS).toInt())
        val f = DoubleArray(n)
        val i = (at * FS).toInt()
        if (i >= n) return f
        val p = DoubleArray(w) { sin(PI * (it + 0.5) / w) }
        val ps = p.sum()
        val k = max(3, (0.04e-3 * FS).toInt())
        val c = CalabashDsp.hanningInner(k)
        val cs = c.sum()
        val ck = click * v.pow(1.6) * scale / cs * 40.0 * tr.uniform(0.7, 1.3)
        for (j in 0 until min(w, n - i)) f[i + j] += p[j] * v.pow(1.25) * scale / ps * 40.0
        for (j in 0 until min(k, n - i)) f[i + j] += c[j] * ck
        return f
    }

    private const val WIRE_GAIN = 9.0

    /**
     * EMBER's `wires`: snare wires slapping the bottom head whenever its low motion passes their tension; looser
     * (more WIRES) lifts sooner, rings longer and darker. One-way: they never damp the head. WIRES 0 is off.
     */
    private fun wires(head: DoubleArray, v: Double, buzz: Double, tr: Rng, n: Int, sym: Double = 1.0): DoubleArray {
        if (buzz <= 0) return DoubleArray(n)
        val theta = lin(buzz, 0.42, 0.05)
        val low = lp(head, 700.0)
        val drive0 = DoubleArray(n) { max(0.0, abs(low[it]) - theta) * sym }
        val drive = CalabashDsp.envFollow(drive0, lin(buzz, 1.5, 6.0))
        val grit = DoubleArray(n) { val g = tr.normal(); if (tr.random() < 0.35) g else 0.0 }
        val gd = DoubleArray(n) { grit[it] * drive[it] }
        val a = band(gd, 2200 - 900 * buzz, lin(buzz, 9500.0, 5200.0))
        val b = band(gd, 5500.0, 12000.0)
        val k = min(1.0, buzz / 0.1)
        return DoubleArray(n) { (a[it] + 0.35 * b[it]) * k }
    }

    private val THIRD_OCTAVES = DoubleArray(40) { 25.0 * 2.0.pow(it / 3.0) }.filter { it <= 16000.0 }

    private const val SKIN_T60_FLOOR = 0.006

    /**
     * The upper head, statistically: band noise per third octave from [fFrom] up, each band dying at the head's loss
     * law T60 = t1 (f / f1)^-alpha (floored at 6 ms), tilted [tiltDb] an octave. Peak-normalised. Fresh every take.
     */
    private fun denseHead(f1: Double, t1: Double, alpha: Double, n: Int, tr: Rng, fFrom: Double, tiltDb: Double): DoubleArray {
        val out = DoubleArray(n)
        for (fc in THIRD_OCTAVES) {
            if (fc < fFrom) continue
            val t60 = max(SKIN_T60_FLOOR, t1 * (fc / f1).pow(-alpha))
            val m = min(n, (t60 * 1.6 * FS).toInt() + 1)
            val raw = band(tr.normals(m + 600), fc / 2.0.pow(1.0 / 6), fc * 2.0.pow(1.0 / 6))
            val x = raw.copyOfRange(600, raw.size)
            val r = CalabashDsp.rms(x) + 1e-20
            val g = CalabashDsp.db(tiltDb * log2(fc / fFrom))
            for (i in 0 until m) out[i] += g * x[i] / r * exp(-6.9078 * (i.toDouble() / FS) / t60)
        }
        return peakNorm(out)
    }

    private val SKIN = mapOf("TOM" to 8.0, "DUNDUN" to 4.0, "TAMA" to 16.0, "SABAR_HAND" to 3.0, "SABAR_SLAP" to 4.0,
        "SABAR_STICK" to 8.0, "SABAR_PRESSED" to 4.0, "TIMBALE_HEAD" to 4.0, "TIMBALE_RIM" to 4.0)
    private val SKIN_TILT = mapOf("TOM" to 3.0, "DUNDUN" to 0.0, "TAMA" to 3.0, "SABAR_STICK" to 3.0, "SABAR_SLAP" to 3.0,
        "SABAR_PRESSED" to 3.0, "TIMBALE_HEAD" to 0.0, "TIMBALE_RIM" to 0.0)
    private val CLICK = mapOf("TOM" to 2.4, "DUNDUN" to 2.4, "TAMA" to 0.1, "SABAR" to 1.2, "TIMBALE" to 2.4, "TIMBALE_SHELL" to 0.6)

    /** The stroke's force through the dense upper head, times [amount] (the swept per-stroke `SKIN`). */
    private fun skin(force: DoubleArray, f1: Double, t1: Double, alpha: Double, n: Int, tr: Rng, amount: Double,
                     fFrom: Double? = null, tiltDb: Double? = null, key: String? = null): DoubleArray {
        if (amount <= 0) return DoubleArray(n)
        val tilt = tiltDb ?: SKIN_TILT[key] ?: -3.0
        val ir = denseHead(f1, t1, alpha, n, tr, fFrom ?: (6.5 * f1), tilt)
        return CalabashDsp.scaled(conv(force, ir, n), amount)
    }

    // ------------------------------------------------------------------ the kit

    /** A coated plastic head: its high modes outlive calf's (EMBER's calf is 1.15). Shape. */
    private const val HEAD_ALPHA = 0.85

    private fun kick(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val f1 = 56.0 * semis(m.g("TUNE"), 6.0)
        val t1 = lin(m.g("DAMP"), 0.9, 0.12)
        val hard = m.g("BEATER")
        val beater = mallet(v, tr, n, widthHard = lin(hard, 1.8e-3, 0.45e-3))
        val air = hannForce(8e-3, 0.45 * v.pow(1.6), n)
        // EMBER's `_drum`: no wires on a kick.
        val r = (0.1 + tr.normal(0.0, 0.05)).coerceIn(0.0, 0.95)
        val ang = tr.uniform(0.0, PI)
        val ir = peakNorm(membrane(f1, n, t1, HEAD_ALPHA + 0.3, r, ang, sag = 0.08 * v * v, sagTau = 0.04 + 0.02 * tr.random()))
        val force = CalabashDsp.sum(beater, air)
        val out = conv(force, ir, n)
        val sk = skin(beater, f1, t1, HEAD_ALPHA + 0.3, n, tr, 0.12, fFrom = 900.0)
        val p1 = peak(out)
        add(out, sk, p1)
        val click = hp(hannForce(0.35e-3, (0.02 + 0.12 * hard) * v.pow(1.8), n), 1800.0)
        add(out, click, peak(out) * 6)
        return out
    }

    private fun deepKick(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val fEnd = 48.0 * semis(m.g("TUNE"), 7.0)
        val fStart = fEnd * (2.4 + 1.6 * v) * tr.uniform(0.95, 1.05)
        val tauP = (0.022 + 0.01 * (1 - v)) * tr.uniform(0.9, 1.1)
        val decay = lin(m.g("DAMP"), 0.75, 0.16) * tr.uniform(0.94, 1.06)
        val drive = 1.2 + 2.2 * v
        val td = tanh(drive)
        val out = DoubleArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / FS
            ph += TAU * (fEnd + (fStart - fEnd) * exp(-t / tauP)) / FS
            val body = sin(ph) * exp(-6.9078 * t / decay) * min(1.0, t / 0.0006)
            out[i] = tanh(body * drive) / td
        }
        val tickNoise = DoubleArray(n) { tr.normal() * exp(-(it.toDouble() / FS) / 0.0015) }
        val tick = hp(tickNoise, 2500.0)
        val tg = (0.05 + 0.4 * m.g("CLICK")) * v.pow(1.5) * tr.uniform(0.75, 1.25)
        for (i in 0 until n) {
            val t = i.toDouble() / FS
            out[i] += tick[i] * tg + sin(TAU * 1500 * t) * exp(-t / 0.0025) * 0.25 * m.g("CLICK") * v
        }
        return out
    }

    /** EMBER's snare (head plus wires driven by its motion), with a plastic head and WIRES for BUZZ. */
    private fun snareCore(m: Map<String, Double>, v: Double, tr: Rng, n: Int, force: DoubleArray, rBase: Double): DoubleArray {
        val f1 = 300.0 * semis(m.g("TUNE"), 5.0)
        val t1 = lin(m.g("DAMP"), 0.42, 0.07)
        val r = (rBase + tr.normal(0.0, 0.07)).coerceIn(0.0, 0.95)
        val ir = peakNorm(membrane(f1, n, t1, HEAD_ALPHA, r, tr.uniform(0.0, PI), sag = 0.03 * v * v,
            sagTau = 0.04 + 0.02 * tr.random(), scatter = tr))
        val head = conv(force, ir, n)
        val k = nOf(0.08)
        val refFull = conv(hannForce(0.28e-3, 1.0, k), ir.copyOf(k), k)
        val refPeak = peak(refFull)
        val lowRef = peak(lp(refFull, 900.0)) + 1e-12
        val lowHead = lp(head, 900.0)
        val hd = DoubleArray(n) { val x = lowHead[it] / lowRef; Math.signum(x) * abs(x).pow(0.6) }
        add(head, wires(hd, v, m.g("WIRES"), tr, n), WIRE_GAIN * refPeak)
        return head
    }

    private fun snare(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val force = stick(v, tr, n)
        val out = snareCore(m, v, tr, n, force, 0.3)
        add(out, hp(force, 2500.0), 0.2)
        return out
    }

    private fun rimshot(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val force = stick(v, tr, n, widthHard = 0.2e-3)
        val s = semis(m.g("TUNE"), 5.0)
        val hoop = modal(doubleArrayOf(960 * s, 2210 * s, 3720 * s, 5300 * s), doubleArrayOf(1.0, .7, .5, .3),
            doubleArrayOf(.09, .06, .045, .03), n)
        val rim = conv(force, peakNorm(hoop), n)
        val out = snareCore(m, v, tr, n, CalabashDsp.scaled(force, 1.2), 0.85)
        add(out, rim, 0.9)
        add(out, hp(force, 2000.0), 0.35)
        return out
    }

    /** The programmed rim: a dry side stick, butt on the steel hoop and shaft on the head, struck by the tip. */
    private fun rim(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val s = semis(m.g("TUNE"), 5.0)
        val force = tip(v, tr, n, widthHard = 0.15e-3, click = 0.8)
        val damp = lin(m.g("DAMP"), 1.4, 0.5)
        val wf = tr.uniform(0.97, 1.03)
        val wa = tr.uniform(0.5, 1.5, 5)
        val wood = modal(doubleArrayOf(505.0, 1390.0, 2730.0, 4500.0, 6700.0).map { it * s * wf }.toDoubleArray(),
            doubleArrayOf(1.0, .8, .5, .3, .2).mapIndexed { i, a -> a * wa[i] }.toDoubleArray(),
            doubleArrayOf(.05, .035, .02, .012, .008).map { it * damp }.toDoubleArray(), n)
        val ha = tr.uniform(0.4, 1.6, 6)
        val hoop = modal(doubleArrayOf(1180.0, 1960.0, 2840.0, 3950.0, 5600.0, 7900.0).map { it * s }.toDoubleArray(),
            doubleArrayOf(.6, 1.0, .5, .35, .25, .15).mapIndexed { i, a -> a * ha[i] }.toDoubleArray(),
            doubleArrayOf(.07, .06, .04, .03, .02, .012).map { it * damp }.toDoubleArray(), n)
        val head = membrane(300 * s, n, 0.12 * damp, HEAD_ALPHA, 0.8, tr.uniform(0.0, PI), reso = null, scatter = tr)
        val pw = peakNorm(wood); val ph = peakNorm(hoop)
        val hg = 0.25 + 0.75 * v.pow(1.5)
        val body = DoubleArray(n) { pw[it] + hg * ph[it] }
        val out = conv(force, body, n)
        add(out, conv(force, peakNorm(head), n), 0.35 - 0.15 * v)
        add(out, hp(force, 3000.0), 1.2 * v.pow(0.8))
        return out
    }

    /** A rack or floor tom, plastic heads: TUNE spans 74 to 230 Hz. */
    private fun tom(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val f1 = 130.0 * semis(m.g("TUNE"), 10.0)
        val t1 = lin(m.g("DAMP"), 1.1, 0.14) * (130.0 / f1).pow(0.3)
        val force = tip(v, tr, n, click = CLICK.getValue("TOM"))
        val r = (0.3 + tr.normal(0.0, 0.05)).coerceIn(0.0, 0.95)
        val ir = membrane(f1, n, t1, HEAD_ALPHA + .15, r, tr.uniform(0.0, PI), sag = 0.05 * v * v, sagTau = 0.05, scatter = tr)
        val out = conv(force, peakNorm(ir), n)
        add(out, skin(force, f1, t1, HEAD_ALPHA + .15, n, tr, SKIN.getValue("TOM"), key = "TOM"))
        add(out, hp(force, 2500.0), 0.12)
        return out
    }

    // ------------------------------------------------------------------ claps and snaps

    private fun clap(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val ratio = semis(m.g("TUNE"), 3.0)
        return blended(m.g("CROWD"), TWO) { i ->
            CalabashDsp.upsample4(CalabashFits.fitted(if (i == 0) "clap_solo" else "clap_group", v, tr, n.toDouble() / FS, tuneRatio = ratio), n)
        }
    }

    /** EMBER's snapping crowd: each person a finger cracking onto the palm, a little early or late. */
    private fun snaps(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val people = 1 + CalabashDsp.roundHalfEven(m.g("CROWD") * 5)
        val out = DoubleArray(n)
        val s = semis(m.g("TUNE"), 5.0)
        val joining = 1 + CalabashDsp.roundHalfEven((people - 1) * (0.25 + 0.75 * v))
        for (p in 0 until joining) {
            // The same people every take (the prototype's values); only when they land moves with the take.
            val person = CalabashFits.crowd[p]
            val at = 0.004 + (if (p == 0) 0.0 else abs(tr.normal(0.0, 0.012)) + person.late)
            val a = (if (p == 0) 1.0 else person.gain) * v.pow(if (p != 0) 1.2 else 1.0)
            val cav = person.cavity * s
            val width = nOf(person.width)
            val burst = DoubleArray(width) { tr.normal() * exp(-it / (width / 3.0)) }
            val x = DoubleArray(n)
            val i0 = (at * FS).toInt()
            for (j in 0 until max(0, min(width, n - i0))) x[i0 + j] = burst[j]
            val (b, aa) = CalabashDsp.iirpeak(cav, 5.0)
            val body = CalabashDsp.lfilter(b, aa, x)
            val crack = CalabashDsp.scaled(hp(hannForce(0.12e-3, 1.0, n, at), 1800.0), 0.5 + 0.5 * v)
            val pb = peakNorm(body); val pc = peakNorm(crack); val px = peakNorm(hp(x, 3000.0))
            for (j in 0 until n) out[j] += a * (pb[j] + 0.8 * pc[j] * v.pow(1.5) + 0.15 * px[j])
        }
        return out
    }

    private val SHAKE_KINDS = listOf("push", "pull", "slap")

    private fun shaker(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val names = listOf("shaker_down", "shaker_up", "shaker_hit")
        val ratio = semis(m.g("TUNE"), 3.0)
        return blended(m.g("STROKE"), THREE) { CalabashDsp.upsample4(CalabashFits.fitted(names[it], v, tr, n.toDouble() / FS, tuneRatio = ratio), n) }
    }

    // ------------------------------------------------------------------ fitted hand drums, bells, wood

    private fun fittedSimple(name: String, m: Map<String, Double>, v: Double, tr: Rng, n: Int, contact: Double = 0.2): DoubleArray =
        CalabashDsp.upsample4(CalabashFits.fitted(name, v, tr, n.toDouble() / FS, tuneRatio = semis(m.g("TUNE"), 3.0),
            damp = m["DAMP"] ?: 0.3, contactMs = contact), n)

    /**
     * A conga or bongo. STROKE runs bass (the palm in the middle), open, muffled, slap. Open and muffled are the
     * recordings' strokes; bass and slap are built on them (shape: the library has neither).
     */
    private fun handDrum(prefix: String, tuneSpan: Double, m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val secs = n.toDouble() / FS
        val ratio = semis(m.g("TUNE"), tuneSpan)
        val damp = m.g("DAMP")
        val f0 = CalabashFits.hand(prefix + "_open").strongest() * ratio
        val fAir = min(CalabashFits.hand(prefix + "_muff").strongest(), CalabashFits.hand(prefix + "_open").strongest()) * ratio
        val open = prefix + "_open"
        val muff = prefix + "_muff"
        val y = blended(m.g("STROKE"), HAND) { which ->
            when (which) {
                0 -> {
                    // The palm lands flat in the middle and bounces: the lowest mode and the shell's air sing.
                    val keep = { f: Double -> CalabashDsp.db(if (f < 1.35 * f0) 0.0 else -10.0 - 9.0 * log2(f / (1.35 * f0))) }
                    val (am, ar) = CalabashFits.strike(open, v, tr, secs, ratio, damp, modeGain = keep, attackDb = -9.0, residualDb = -4.0, contactMs = 2.2)
                    val airy = { f: Double -> CalabashDsp.db(if (f < 1.2 * fAir) 3.0 else -18.0) }
                    val (bm, br) = CalabashFits.strike(muff, v, tr, secs, ratio, damp, modeGain = airy, attackDb = -12.0, residualDb = -8.0, contactMs = 2.2)
                    DoubleArray(am.size) { am[it] + ar[it] + 0.8 * (bm[it] + br[it]) }
                }
                1 -> CalabashFits.fitted(open, v, tr, secs, ratio, damp, contactMs = 0.7)
                2 -> CalabashFits.fitted(muff, v, tr, secs, ratio, damp, contactMs = 0.7)
                else -> slap(open, f0, v, tr, secs, ratio, damp)
            }
        }
        return CalabashDsp.upsample4(y, n)
    }

    /**
     * The slap: the heel presses near the rim while the cupped fingers crack onto the head and stay a moment. The
     * fundamental is choked (-14 dB, gone four times faster), the upper modes ring briefly and brighter, and the
     * skin-on-skin smack is the loudest thing in the first few milliseconds. At 44.1 kHz.
     */
    private fun slap(open: String, f0: Double, v: Double, tr: Rng, secs: Double, ratio: Double, damp: Double): DoubleArray {
        val shape = { f: Double -> CalabashDsp.db(if (f < 1.3 * f0) -14.0 else min(9.0, 3.0 + 4.0 * log2(f / (1.3 * f0)))) }
        val slope = { f: Double -> if (f < 1.3 * f0) 4.0 else 2.4 }
        val (modes, res) = CalabashFits.strike(open, v, tr, secs, ratio, damp, modeGain = shape, slopeMul = slope,
            attackDb = 6.0, residualDb = -3.0, contactMs = 0.22, glideMul = 0.5)
        val k = modes.size
        val body = DoubleArray(k) { modes[it] + res[it] }
        val smack = DoubleArray(k)
        val w = max(3, (0.00022 * v.pow(-0.3) * RATE * tr.uniform(0.85, 1.15)).toInt())
        val hw = CalabashDsp.hanningInner(w)
        for (i in 0 until min(w, k)) smack[i] = hw[i]
        val sm = CalabashDsp.butter(2, CalabashDsp.Kind.BAND, 1200.0, 9000.0, RATE).run(smack)
        val noise = CalabashDsp.butter(2, CalabashDsp.Kind.BAND, 1500.0, 7000.0, RATE).run(tr.normals(k))
        val crack = DoubleArray(k) { noise[it] * exp(-it / (0.003 * RATE)) }
        val ref = CalabashDsp.rms(body, 0, (0.05 * RATE).toInt()) + 1e-12
        val c200 = crack.copyOf(min(200, k))
        val mean = c200.average()
        val sd = sqrt(c200.sumOf { (it - mean) * (it - mean) } / c200.size) + 1e-12
        val ps = peakNorm(sm)
        return DoubleArray(k) { body[it] + ps[it] * 2.2 * ref * v.pow(0.5) + 0.7 * ref * v.pow(0.7) * crack[it] / sd }
    }

    // ------------------------------------------------------------------ the shekere (physical)

    /** A bead net hitting a gourd: Poisson impacts at [rate] per second, each a click, plus the net's own slide. */
    private fun beadCloud(rate: DoubleArray, n: Int, tr: Rng, bright: Double, gain: DoubleArray): DoubleArray {
        val amp = DoubleArray(n)
        for (i in 0 until n) {
            val p = (rate[i] / FS).coerceIn(0.0, 0.5)
            if (tr.random() < p) amp[i] = tr.uniform(0.3, 1.0) * gain[i]
        }
        val out = DoubleArray(n)
        for ((lo, hi, g) in listOf(Triple(1800.0, 5200.0, 1.0), Triple(4200.0, 11000.0, 0.55 + 0.6 * bright))) {
            val sg = tr.signs(n)
            val click = band(DoubleArray(n) { amp[it] * sg[it] }, lo, hi)
            for (i in 0 until n) out[i] += g * click[i]
        }
        val slide = band(tr.normals(n), 2500.0, 9000.0)
        for (i in 0 until n) out[i] += slide[i] * sqrt(max(0.0, rate[i]) / 4000.0) * gain[i] * 0.08
        return out
    }

    private val gourds = ConcurrentHashMap<Int, Pair<DoubleArray, DoubleArray>>()

    /** The calabash's air (165 Hz for an 8-litre gourd with a 4 cm neck) and its dry shell, cached by TUNE. */
    private fun gourd(tune: Double): Pair<DoubleArray, DoubleArray> = gourds.getOrPut(Math.round(tune * 1000).toInt()) {
        val s = semis(Math.round(tune * 1000) / 1000.0, 4.0)
        val k = nOf(0.5)
        val air = modal(doubleArrayOf(165 * s), doubleArrayOf(1.0), doubleArrayOf(0.18), k)
        val shell = modal(doubleArrayOf(380.0, 610.0, 870.0, 1240.0, 1900.0).map { it * s }.toDoubleArray(),
            doubleArrayOf(.8, .9, .7, .5, .3), doubleArrayOf(.06, .05, .04, .03, .02), k)
        peakNorm(air) to peakNorm(shell)
    }

    /**
     * One shekere stroke: "push" (the gourd thrown forward, the net lagging and landing), "pull" (back the other way, a
     * longer smoother swirl), "slap" (the palm on the gourd's bottom: its air booms and the whole net jumps).
     */
    private fun shekereStroke(m: Map<String, Double>, v: Double, tr: Rng, n: Int, kind: String, at: Double = 0.0, scale: Double = 1.0): DoubleArray {
        val tight = m.g("DAMP")
        val (air, shell) = gourd(m.g("TUNE"))
        val dur = if (kind == "slap") 0.05 else (if (kind == "push") 0.085 else 0.16) * (1 - 0.35 * tight) * tr.uniform(0.92, 1.08)
        val landAt = dur * (if (kind == "push") 0.75 else 0.55)
        val landTau = 0.018 * (1 - 0.5 * tight)
        val drive = DoubleArray(n) {
            val t = it.toDouble() / FS - at
            val acc = if (t >= 0 && t < dur) sin(PI * (t / dur).coerceIn(0.0, 1.0)) else 0.0
            val land = if (t >= landAt) exp(-max(0.0, t - landAt) / landTau) else 0.0
            if (kind == "slap") land * 1.2 * scale else (0.55 * acc + land * (if (kind == "push") 1.0 else 0.6)) * scale
        }
        val beads = 260 * (1 - 0.4 * tight)
        val rate = DoubleArray(n) { beads * 40 * max(0.0, drive[it] * v - 0.08) }
        val gain = DoubleArray(n) { max(0.0, drive[it]) * v.pow(0.8) }
        val cloud = beadCloud(rate, n, tr, v, gain)
        val knock = lp(DoubleArray(n) { abs(cloud[it]) }, 400.0)
        val body = conv(knock, shell, n)
        val bodyAir = conv(knock, air, n)
        val out = DoubleArray(n) { 0.5 * body[it] + 0.06 * bodyAir[it] }
        if (kind == "slap") {
            val palm = CalabashDsp.scaled(hand(v, tr, n, at = max(0.0, at), cupped = 0.1), scale)
            add(out, conv(palm, air, n), 1.6)
            add(out, conv(palm, shell, n), 0.7)
        }
        return DoubleArray(n) { cloud[it] + out[it] * 0.15 }
    }

    // ------------------------------------------------------------------ talking drums (physical)

    /**
     * The talking drum's pitch over time, as a ratio. CONTOUR 0: press up after the hit; 0.5: hit already squeezed and
     * let go; 1: up and back down. Blended between. Shape: the squeeze's timing was not measured.
     */
    private fun squeezePath(n: Int, squeezeSemis: Double, contour: Double, tr: Rng, hitAt: Double, riseS: Double, startS: Double): DoubleArray {
        val start = startS + tr.uniform(0.0, 0.015)
        val rise = riseS * tr.uniform(0.85, 1.15)
        return DoubleArray(n) {
            val t = it.toDouble() / FS - hitAt
            val x = ((t - start) / rise).coerceIn(0.0, 1.0)
            val smooth = x * x * (3 - 2 * x)
            val bump = sin(PI * ((t - start) / (2.2 * rise)).coerceIn(0.0, 1.0)).pow(1.5)
            val shape = if (contour <= 0.5) { val w = contour / 0.5; (1 - w) * smooth + w * (1 - smooth) }
            else { val w = (contour - 0.5) / 0.5; (1 - w) * (1 - smooth) + w * bump }
            2.0.pow(squeezeSemis * shape / 12)
        }
    }

    /**
     * An hourglass drum, two goatskin heads laced together: the arm squeezes the lacing and both heads tighten at
     * once, so every mode rises together while it rings. The stick is curved; the waist's air adds body.
     */
    private fun talking(m: Map<String, Double>, v: Double, tr: Rng, n: Int, fBase: Double, t1: Double, alpha: Double, stickW: Double,
                        cavity: Double, scale: Double = 1.0, kind: String = "DUNDUN", riseS: Double = 0.12): DoubleArray {
        val sq = m.g("SQUEEZE") * 10.0
        val path = squeezePath(n, sq, m.g("CONTOUR"), tr, 0.0, riseS, if (riseS > 0.1) 0.02 else 0.008)
        val f1 = fBase * semis(m.g("TUNE"), 6.0)
        val r = (0.35 + tr.normal(0.0, 0.06)).coerceIn(0.0, 0.9)
        val bend = DoubleArray(n) { path[it] * (1 + 0.06 * v * v * exp(-(it.toDouble() / FS) / 0.04)) }
        val ir = membrane(f1, n, t1, alpha, r, tr.uniform(0.0, PI), bend = bend, reso = doubleArrayOf(1.012, 0.45), scatter = tr)
        val force = tip(v, tr, n, widthHard = stickW, scale = scale, click = CLICK.getValue(kind))
        val out = conv(force, peakNorm(ir), n)
        val waist = conv(force, peakNorm(membrane(f1 * cavity, n, t1 * 0.7, 1.4, 0.0, 0.0, bend = path, reso = null, count = 3)), n)
        add(out, waist, 0.25)
        add(out, skin(force, f1, t1, alpha, n, tr, SKIN.getValue(kind), key = kind))
        add(out, hp(force, 3000.0), 0.08)
        return out
    }

    // ------------------------------------------------------------------ the sabar family (physical)

    /** The air in the shell: an open-bottom tube's odd resonances, or the col's closed egg (one cavity mode). */
    private fun pipe(lengthM: Double, n: Int, closed: Boolean, damping: Double): DoubleArray {
        if (closed) return modal(doubleArrayOf(95.0), doubleArrayOf(1.0), doubleArrayOf(0.25 * damping), n)
        val f = 343.0 / (4 * lengthM)
        return modal(doubleArrayOf(f, 3 * f, 5 * f), doubleArrayOf(1.0, .45, .2), doubleArrayOf(.22 * damping, .12 * damping, .07 * damping), n)
    }

    /** The galan, the sabar's thin springy switch: a very short contact and the stick's own whip. */
    private fun galan(v: Double, tr: Rng, n: Int, scale: Double = 1.0): Pair<DoubleArray, DoubleArray> {
        val f = tip(v, tr, n, widthHard = 0.14e-3, scale = scale, click = CLICK.getValue("SABAR"))
        val whip = modal(doubleArrayOf(1450 * tr.uniform(0.97, 1.03), 3900 * tr.uniform(0.97, 1.03)), doubleArrayOf(1.0, .4), doubleArrayOf(.012, .006), n)
        return f to conv(f, whip, n)
    }

    /** Skin meeting skin: a broadband smack (1.5 to 10 kHz) over about 3 ms, louder than the head at first. Shape. */
    private fun handCrack(body: DoubleArray, v: Double, tr: Rng, n: Int, level: Double = 0.8): DoubleArray {
        val noise = band(tr.normals(n), 1500.0, 10000.0)
        val x = DoubleArray(n) { noise[it] * exp(-(it.toDouble() / FS) / 0.003) }
        val k = (peak(body) + 1e-12) * level * v.pow(0.7) / (peak(x) + 1e-12)
        return CalabashDsp.scaled(x, k)
    }

    /**
     * One sabar stroke: pegged goatskin ringing high over a tall shell. STROKE: open hand, hand slap, open stick,
     * pressed stick (the hand presses the head as the stick lands: higher, short).
     */
    private fun sabar(m: Map<String, Double>, v: Double, tr: Rng, n: Int, fBase: Double, lengthM: Double, closed: Boolean,
                      scale: Double = 1.0, stroke: Double? = null): DoubleArray {
        val s = semis(m.g("TUNE"), 5.0)
        val f1 = fBase * s
        val damp = m.g("DAMP")
        val pipeIr = peakNorm(pipe(lengthM / s.pow(0.25), n, closed, lin(damp, 1.2, 0.4)))
        val x = stroke ?: m.g("STROKE")
        fun head(t1: Double, r: Double, pitch: Double = 1.0, alpha: Double = 1.0, force: DoubleArray, bright: Double, key: String): DoubleArray {
            val t1d = t1 * lin(damp, 1.25, 0.45)
            val ir = membrane(f1 * pitch, n, t1d, alpha, r, tr.uniform(0.0, PI), sag = 0.035 * v * v, sagTau = 0.03, reso = null, scatter = tr)
            val out = conv(force, peakNorm(ir), n)
            add(out, skin(force, f1 * pitch, t1d, alpha, n, tr, bright, key = key))
            return out
        }
        return blended(x, SABAR) { which ->
            when (which) {
                0 -> {
                    val f = CalabashDsp.scaled(hand(v, tr, n, cupped = 0.0), scale)
                    val h = head(0.45, 0.25 + tr.normal(0.0, 0.04), force = f, bright = SKIN.getValue("SABAR_HAND"), key = "SABAR_HAND")
                    val out = h.copyOf()
                    add(out, conv(lp(f, 600.0), pipeIr, n), 0.55 * (if (closed) 1.6 else 1.0))
                    add(out, handCrack(h, v, tr, n, level = if (closed) 0.12 else 0.22))
                    out
                }
                1 -> {
                    val f = CalabashDsp.scaled(hand(v, tr, n, cupped = 1.0), scale)
                    val h = head(0.11, 0.85 + tr.normal(0.0, 0.03), alpha = 0.8, force = f, bright = SKIN.getValue("SABAR_SLAP"), key = "SABAR_SLAP")
                    val out = h.copyOf()
                    add(out, hp(f, 1800.0), 0.9)
                    add(out, conv(lp(f, 500.0), pipeIr, n), 0.05)
                    add(out, handCrack(h, v, tr, n))
                    out
                }
                2 -> {
                    val (f, whip) = galan(v, tr, n, scale)
                    val out = head(0.5, 0.6 + tr.normal(0.0, 0.05), alpha = 0.75, force = f, bright = SKIN.getValue("SABAR_STICK"), key = "SABAR_STICK")
                    add(out, whip, 0.35)
                    add(out, hp(f, 2500.0), 0.35)
                    add(out, conv(lp(f, 600.0), pipeIr, n), 0.25)
                    out
                }
                else -> {
                    val (f, whip) = galan(v, tr, n, scale)
                    val out = head(0.08, 0.55 + tr.normal(0.0, 0.05), pitch = 1.12 * tr.uniform(0.99, 1.01), alpha = 0.7, force = f,
                        bright = SKIN.getValue("SABAR_PRESSED"), key = "SABAR_PRESSED")
                    add(out, whip, 0.5)
                    add(out, hp(f, 2500.0), 0.45)
                    out
                }
            }
        }
    }

    // ------------------------------------------------------------------ timbales (physical)

    /** A shallow steel shell's ring modes (thin-ring ratios, as axial pairs), a few hundred ms. */
    private fun steelShell(n: Int, s: Double, damp: Double, tr: Rng, gainHi: Double = 1.0): DoubleArray {
        val base = 1050.0 * s
        val fr = ArrayList<Double>(); val am = ArrayList<Double>(); val tt = ArrayList<Double>()
        for (k in 2..8) {
            val r = k * (k * k - 1) / sqrt(k * k + 1.0) / (2 * 3 / sqrt(5.0))
            for ((ax, g) in listOf(1.0 to 1.0, (1.18 + 0.03 * k) to 0.6)) {
                val f = base * r * ax * tr.uniform(0.995, 1.005)
                fr += f
                am += g * (if (f > 3000) gainHi else 1.0) / (1 + 0.25 * k)
                tt += lin(damp, 0.55, 0.12) * (1050.0 / max(f, 1050.0)).pow(0.4)
            }
        }
        val ph = tr.uniform(0.0, TAU, fr.size)
        return modal(fr.toDoubleArray(), am.toDoubleArray(), tt.toDoubleArray(), n, ph)
    }

    /** A timbale: a thin, very tight head on a shallow steel shell. STROKE: head, rimshot, shell (the paila). */
    private fun timbaleStroke(m: Map<String, Double>, v: Double, tr: Rng, n: Int, x: Double, scale: Double = 1.0): DoubleArray {
        val s = semis(m.g("TUNE"), 6.0)
        val f1 = 310.0 * s
        val damp = m.g("DAMP")
        return blended(x, THREE) { which ->
            when (which) {
                0 -> {
                    val f = tip(v, tr, n, widthHard = 0.22e-3, scale = scale, click = CLICK.getValue("TIMBALE"))
                    val t1 = lin(damp, 0.75, 0.15)
                    val ir = membrane(f1, n, t1, 0.62, 0.45 + tr.normal(0.0, 0.05), tr.uniform(0.0, PI), sag = 0.025 * v * v, sagTau = 0.04, reso = null, scatter = tr)
                    val out = conv(f, peakNorm(ir), n)
                    add(out, skin(f, f1, t1, 0.62, n, tr, SKIN.getValue("TIMBALE_HEAD"), key = "TIMBALE_HEAD"))
                    add(out, conv(f, peakNorm(steelShell(n, s, damp, tr)), n), 0.35)
                    add(out, hp(f, 2500.0), 0.15)
                    out
                }
                1 -> {
                    val f = tip(v, tr, n, widthHard = 0.16e-3, scale = scale * 1.3, click = CLICK.getValue("TIMBALE"))
                    val t1 = lin(damp, 0.6, 0.12)
                    val ir = membrane(f1, n, t1, 0.62, 0.92, tr.uniform(0.0, PI), sag = 0.03 * v * v, reso = null, scatter = tr)
                    val h = conv(f, peakNorm(ir), n)
                    add(h, skin(f, f1, t1, 0.62, n, tr, SKIN.getValue("TIMBALE_RIM"), key = "TIMBALE_RIM"))
                    val out = CalabashDsp.scaled(h, 0.8)
                    add(out, conv(f, peakNorm(steelShell(n, s, damp, tr, 1.6)), n), 0.9)
                    add(out, hp(f, 1800.0), 0.5)
                    out
                }
                else -> {
                    val f = tip(v, tr, n, widthHard = 0.12e-3, scale = scale, click = CLICK.getValue("TIMBALE_SHELL"))
                    val out = conv(f, peakNorm(steelShell(n, s, lin(damp, 0.5, 1.0), tr, 1.8)), n)
                    val ir = membrane(f1, n, 0.3, 0.62, 0.95, 0.0, reso = null, count = 6)
                    add(out, conv(lp(f, 800.0), peakNorm(ir), n), 0.12)
                    add(out, hp(f, 3000.0), 0.6)
                    out
                }
            }
        }
    }

    // ------------------------------------------------------------------ the four rolls and risers

    /** Each roll stroke is rendered this long from its own onset, then placed (a roll is quick to build). */
    private const val STROKE_S = 0.6

    private fun place(out: DoubleArray, y: DoubleArray, at: Double, k: Double = 1.0) = add(out, y, k, Math.rint(at * FS).toInt())

    /** Stroke onsets accelerating from [rate0] to [rate1] a second over [tEnd], with the player's own unevenness. */
    private fun rollTimes(tEnd: Double, rate0: Double, rate1: Double, tr: Rng): List<Double> {
        val out = ArrayList<Double>()
        var tt = 0.02
        while (tt < tEnd - 0.03) {
            out += tt
            tt += 1.0 / lin(tt / tEnd, rate0, rate1) * tr.uniform(0.93, 1.07)
        }
        return out
    }

    /** Afrobeat's riser: shekere strokes doubling from eighths to thirty-seconds, into a bottom slap at 2.3 s. */
    private fun shekereRush(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val mm = mapOf("TUNE" to m.g("TUNE"), "DAMP" to 0.25)
        val out = DoubleArray(n)
        val tEnd = 2.3
        for ((k, at) in rollTimes(tEnd, lin(m.g("SPEED"), 4.0, 6.0), lin(m.g("SPEED"), 12.0, 20.0), tr).withIndex()) {
            val a = 0.2 + 0.8 * (at / tEnd).pow(1.6)
            place(out, shekereStroke(mm, max(0.1, v * a), tr, nOf(STROKE_S), if (k % 2 == 0) "push" else "pull"), at)
        }
        place(out, shekereStroke(mm, v, tr, nOf(STROKE_S), "slap"), tEnd, 1.3)
        return out
    }

    /** Afrobeats' riser: the talking drum calling, each stroke squeezed higher, into a long up-and-down glide. */
    private fun talkingCall(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val out = DoubleArray(n)
        val tEnd = 2.25
        for (at in rollTimes(tEnd, lin(m.g("SPEED"), 3.0, 5.0), lin(m.g("SPEED"), 9.0, 15.0), tr)) {
            val x = at / tEnd
            val mm = mapOf("TUNE" to m.g("TUNE"), "SQUEEZE" to m.g("SQUEEZE") * (0.2 + 0.8 * x), "CONTOUR" to 0.0)
            place(out, talking(mm, max(0.1, v * (0.3 + 0.7 * x.pow(1.3))), tr, nOf(STROKE_S), 150.0, 0.4, 1.0, 0.38e-3, 0.62), at)
        }
        val mm = mapOf("TUNE" to m.g("TUNE"), "SQUEEZE" to m.g("SQUEEZE"), "CONTOUR" to 1.0)
        place(out, talking(mm, v, tr, n - nOf(tEnd), 150.0, 0.55, 1.0, 0.38e-3, 0.62), tEnd, 1.2)
        return out
    }

    /** Mbalax's roll: the nder's stick rolling, open hand tones under it, into a pressed-stick accent and a slap. */
    private fun sabarRoll(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val mm = mapOf("TUNE" to m.g("TUNE"), "DAMP" to 0.3, "STROKE" to 2.0 / 3)
        val out = DoubleArray(n)
        val tEnd = 2.15
        for ((k, at) in rollTimes(tEnd, lin(m.g("SPEED"), 8.0, 12.0), lin(m.g("SPEED"), 18.0, 28.0), tr).withIndex()) {
            val a = 0.18 + 0.82 * (at / tEnd).pow(1.7)
            val st = if (k % 3 != 0) 2.0 / 3 else 0.0
            place(out, sabar(mm, max(0.1, v * a), tr, nOf(STROKE_S), 340.0, 0.78, false, stroke = st), at)
        }
        place(out, sabar(mm, v, tr, nOf(STROKE_S), 340.0, 0.78, false, stroke = 1.0), tEnd, 1.2)
        place(out, sabar(mm, v, tr, nOf(STROKE_S), 340.0, 0.78, false, stroke = 1.0 / 3), tEnd + 0.004, 0.9)
        return out
    }

    /** The timbalero's abanico: single strokes speeding up, a press roll on the head, then the rimshot at 1.9 s. */
    private fun abanico(m: Map<String, Double>, v: Double, tr: Rng, n: Int): DoubleArray {
        val mm = mapOf("TUNE" to m.g("TUNE"), "DAMP" to 0.3)
        val out = DoubleArray(n)
        val tEnd = 1.9
        for (at in rollTimes(tEnd - 0.25, lin(m.g("SPEED"), 6.0, 9.0), lin(m.g("SPEED"), 14.0, 20.0), tr)) {
            val a = 0.2 + 0.8 * (at / tEnd).pow(1.5)
            place(out, timbaleStroke(mm, max(0.1, v * a), tr, nOf(STROKE_S), 0.0), at)
        }
        var tt = tEnd - 0.25
        while (tt < tEnd - 0.01) {
            val a = 0.45 + 0.5 * (tt - (tEnd - 0.25)) / 0.25
            val bounces = tr.integers(3, 6)
            for (b in 0 until bounces) place(out, timbaleStroke(mm, max(0.08, v * a * 0.6.pow(b)), tr, nOf(STROKE_S), 0.0), tt + b * 0.006, 0.8)
            tt += 1.0 / 22 * tr.uniform(0.9, 1.1)
        }
        place(out, timbaleStroke(mm, v, tr, n - nOf(tEnd), 0.5), tEnd, 1.6)
        return out
    }
}

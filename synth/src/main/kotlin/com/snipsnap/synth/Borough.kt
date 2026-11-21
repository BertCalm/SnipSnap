package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.EmberDsp.Rng
import com.snipsnap.synth.EmberDsp.conv
import com.snipsnap.synth.EmberDsp.hannForce
import com.snipsnap.synth.EmberDsp.peakNorm
import com.snipsnap.synth.EmberDsp.peakOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * BOROUGH: six boom-bap drum kits and four percussion stacks, generated in the app from small recipes on top of
 * EMBER's acoustic models. Round 3 of docs/BOROUGH.md: the Kotlin port of the round-2 Python prototype the owner
 * accepted ("I think they are all ready", 2026-11-20) and chose to generate in the app rather than ship as samples.
 *
 * The characteristic gesture is heavy swept kick → papery snare crack with late wires → dark, driven recording
 * chain → a perc stack riding on top.
 *
 * What is here, each ported line by line from the prototype and naming the function it stands in for:
 *
 *     designed kick  (designed.kick):  EMBER felt beater -> EMBER membrane at the loop's pitch, 2% second head
 *                                      -> EMBER room at 0.05 -> 44.1 kHz ; + punch falling onto the head
 *                                      + beater click -> grit above 200 Hz -> mud cut 350 Hz -> low-pass
 *     designed snare (designed.snare): EMBER stick -> membrane -> threshold wires (acoustic.snare, chain off)
 *                                      + a body tone at the head's pitch + a crack of band noise
 *                                      -> 90 Hz low cut -> mud cut 400 Hz -> grit -> low-pass
 *     ghost          (build.render_pad 'dghost'): the designed snare at 0.4 x velocity, 0.35 s
 *     EMBER voices   (acoustic.ember_voice, hat, ride): [Ember] with WARMTH 0 at the pad's DISTANCE, then the record
 *     stack pads     (acoustic.stack_pad): [Ember] at the resolved TUNE, DISTANCE 0.25, WARMTH 0, a Fourier resample
 *                                      by the recipe's ratio where flagged, then the stack's record
 *     the record     (acoustic.record): compressor -> tanh drive scaled by the strike -> high tilt -> 2-pole low-pass
 *     the end        (acoustic.end): 20 Hz high-pass, trim at -60 dB, a release where still ringing, peak 1
 *
 * The recipes are numbers only (`resources/borough/v1/recipes.json`, written by `prototype/borough/export_recipes.py`).
 *
 * What cannot be the same arithmetic, disclosed as EMBER's port did: Kotlin's [Random] (through [Rng]) draws every
 * noise and strike scatter, so a Kotlin take is another realisation of the same recipe, not a copy of a Python one;
 * every strike is seeded by its kit (or stack), pad, zone and take. EMBER's own voices leave EMBER through the house
 * band limit and decimator ([Ember.finish]); the designed drums' own way down is scipy's `resample_poly(y, 1, 4)`
 * filter itself ([BoroughDsp.down4]). The room convolution is the house single-precision FFT ([Ember.club]).
 */
object Borough {
    const val RATE = Dsp.RATE
    private const val SR = RATE.toDouble()
    private const val FSD = (Dsp.RATE * Dsp.OVERSAMPLE).toDouble()
    /** Bump when a pad's audio changes; saved kits carry it. */
    const val MODEL_VERSION = 1

    // ------------------------------------------------------------------ recipes

    /** The record chain's amounts for one pad (acoustic.CHAIN): room DISTANCE, squash, drive, high tilt (dB), low-pass (Hz). */
    class Chain(val distance: Double, val comp: Double, val drive: Double, val lp: Double, val tilt: Double)

    enum class Kind { KICK, SNARE, GHOST, HAT, RIDE, EMBER }

    /**
     * One drum pad's recipe: a designed drum ([Kind.KICK], [Kind.SNARE], [Kind.GHOST], whose [params] are
     * designed.SPACES' numbers), or an EMBER [voice] at [macros] through [chain].
     */
    class DrumPad(val pad: Int, val label: String, val kind: Kind, val params: Map<String, Double>, val voice: EmberVoice?, val macros: Map<String, Float>, val chain: Chain?)

    /**
     * One kit: its pads and balance, [glueLimiterDriveDb] (the limiter drive the round-2 listening page used, kept for
     * the record), and [glue], the live whole-kit glue pinned for the app's native bus in round 3.1
     * (`kits[].glue`, prototype/borough/glue_recipe.py): input trim, saturation (null for none: RIDE AND RIMS), the
     * compressor at -10 dBFS and 4:1, the limiter's drive and its -1 dBFS ceiling. The trim is set for the exported
     * samples at their own level; the app adds its own live calibration ([BoroughKits.glueOf]).
     */
    class KitRecipe(
        val id: String, val name: String, val bpm: Int, val role: String, val ride: Boolean, val defaultStack: String,
        val kickTrimDb: Double, val restTrimDb: Double, val glueLimiterDriveDb: Double, val pads: List<DrumPad>,
        val glue: com.snipsnap.kit.KitGlue,
    )

    /** One stack pad: an EMBER voice at [macros] with TUNE resolved to [tune], resampled by [ratio] when [resample]. */
    class StackPad(val pad: Int, val label: String, val voice: EmberVoice, val macros: Map<String, Float>, val tune: Double, val ratio: Double, val resample: Boolean)

    class StackRecipe(val id: String, val name: String, val pads: List<StackPad>)

    /**
     * How a stack sits in a kit (round 3.1, fuller.py; owner: "C for all except rides and rims", 2026-11-21): after
     * [holdMs] an exponential decay reaching -30 dB at [tailMs] ([longTailMs] for [longLabels], the bells and rides),
     * so the stack stops filling the space between hits and masking the kick.
     */
    class StackTail(val holdMs: Double, val tailMs: Double, val longTailMs: Double, val longLabels: Set<String>) {
        fun tailMsOf(label: String): Double = if (label in longLabels) longTailMs else tailMs
    }

    class Recipes(val zones: List<Float>, val takes: Int, val stackChain: Chain, val stackTail: StackTail, val kits: List<KitRecipe>, val stacks: List<StackRecipe>)

    val recipes: Recipes by lazy { parse(checkNotNull(javaClass.getResourceAsStream("/borough/v1/recipes.json")) { "Missing BOROUGH recipes" }.bufferedReader().use { it.readText() }) }

    private fun glueOf(o: Map<String, JsonValue>): com.snipsnap.kit.KitGlue {
        fun f(key: String) = o.getValue(key).num().toFloat()
        return com.snipsnap.kit.KitGlue(
            inputTrimDb = f("input_trim_db"),
            saturationDb = o.getValue("saturation_db").let { if (it is JsonValue.Null) null else it.num().toFloat() },
            thresholdDbfs = f("threshold_dbfs"), ratio = f("ratio"), makeupDb = f("makeup_db"),
            limiterDriveDb = f("limiter_drive_db"), ceilingDbfs = f("ceiling_dbfs"),
        )
    }

    private fun chainOf(o: Map<String, JsonValue>) = Chain(o.getValue("distance").num(), o.getValue("comp").num(), o.getValue("drive").num(), o.getValue("lp").num(), o.getValue("tilt").num())

    internal fun parse(text: String): Recipes {
        val root = Json.parse(text).obj()
        require(root.getValue("version").int() == 1) { "BOROUGH recipes: unknown version" }
        val rules = root.getValue("rules").obj()
        val kits = root.getValue("kits").arr().map { kj ->
            val k = kj.obj()
            KitRecipe(
                k.getValue("id").str(), k.getValue("name").str(), k.getValue("bpm").int(), k.getValue("role").str(), k.getValue("ride").bool(),
                k.getValue("default_stack").str(), k.getValue("kick_trim_db").num(), k.getValue("rest_trim_db").num(),
                k.getValue("glue_limiter_drive_db").num(),
                k.getValue("pads").arr().map { pj ->
                    val p = pj.obj()
                    val params = p.getValue("params").obj()
                    val extra = p.getValue("extra").obj()
                    val label = p.getValue("label").str()
                    val nums = params.filterValues { it is JsonValue.Num }.mapValues { it.value.num() }
                    when (val kind = p.getValue("kind").str()) {
                        "dkick", "dsnare", "dghost" -> DrumPad(p.getValue("pad").int(), label,
                            mapOf("dkick" to Kind.KICK, "dsnare" to Kind.SNARE, "dghost" to Kind.GHOST).getValue(kind), nums, null, emptyMap(), null)
                        "hat", "ride" -> {
                            val voice = EmberVoice.valueOf(extra["kind"]?.str() ?: if (kind == "hat") "HAT_CLOSED" else "RIDE")
                            // acoustic.hat / ride: TUNE, DAMP, BUZZ and DISTANCE from the fitted numbers
                            val macros = mapOf("TUNE" to nums.getValue("tune"), "DAMP" to nums.getValue("damp"), "BUZZ" to nums.getValue("buzz"),
                                "DISTANCE" to nums.getValue("distance")).mapValues { it.value.toFloat() }
                            DrumPad(p.getValue("pad").int(), label, if (kind == "hat") Kind.HAT else Kind.RIDE, nums, voice, macros, chainOf(params))
                        }
                        "ember" -> {
                            val chain = chainOf(params)
                            // acoustic.ember_voice: DISTANCE defaults to the chain's room
                            val macros = params.getValue("macros").obj().mapValues { it.value.num().toFloat() }.toMutableMap()
                            macros.putIfAbsent("DISTANCE", chain.distance.toFloat())
                            DrumPad(p.getValue("pad").int(), label, Kind.EMBER, nums, EmberVoice.valueOf(params.getValue("voice").str()), macros, chain)
                        }
                        else -> error("BOROUGH recipes: unknown pad kind $kind")
                    }
                },
                glueOf(k.getValue("glue").obj()),
            )
        }
        val stacks = root.getValue("stacks").arr().map { sj ->
            val s = sj.obj()
            StackRecipe(s.getValue("id").str(), s.getValue("name").str(), s.getValue("pads").arr().map { pj ->
                val p = pj.obj()
                StackPad(p.getValue("pad").int(), p.getValue("label").str(), EmberVoice.valueOf(p.getValue("voice").str()),
                    p.getValue("macros").obj().mapValues { it.value.num().toFloat() }, p.getValue("tune").num(),
                    p.getValue("resample_ratio").num(), p.getValue("resample").bool())
            })
        }
        val tail = rules.getValue("stack_tail").obj()
        val stackTail = StackTail(tail.getValue("hold_ms").num(), tail.getValue("tail_ms").num(), tail.getValue("long_tail_ms").num(),
            tail.getValue("long_labels").arr().map { it.str() }.toSet())
        return Recipes(rules.getValue("zones").arr().map { it.num().toFloat() }, rules.getValue("takes").int(), chainOf(rules.getValue("stack_chain").obj()),
            stackTail, kits, stacks)
    }

    // ------------------------------------------------------------------ the takes

    /**
     * build.jitter: take 0 is the recipe; a later take is the drum retensioned (pitches sd 0.4%), its decays and
     * contact widths moved (sd 4%) and its click and wires (sd 10%, clipped to 0.7..1.3). Shape constants.
     */
    internal fun jitter(p: Map<String, Double>, rng: Rng): Map<String, Double> = p.mapValues { (k, v) ->
        when {
            k.endsWith("_hz") || k in setOf("f0", "f1", "metal_hz", "j1") -> v * (1 + rng.normal(0.0, 0.004))
            k.endsWith("_ms") || k == "t1" -> v * (1 + rng.normal(0.0, 0.04))
            k in setOf("click", "wire", "skin", "shell") -> v * (1 + rng.normal(0.0, 0.1)).coerceIn(0.7, 1.3)
            else -> v
        }
    }

    /** One drum pad struck at [velocity] (0..1), its strike drawn from [rng]: build.render_pad at the end of the pad, peak 1. */
    internal fun renderDrum(pad: DrumPad, velocity: Double, rng: Rng, jittered: Boolean): DoubleArray {
        val p = if (jittered) jitter(pad.params, rng) else pad.params
        return when (pad.kind) {
            Kind.KICK -> designedKick(p, velocity, rng)
            Kind.SNARE -> designedSnare(p, velocity, rng)
            Kind.GHOST -> designedSnare(p, velocity * 0.4, rng, seconds = 0.35)   // the stick barely leaves the head
            Kind.HAT, Kind.RIDE, Kind.EMBER -> emberVoice(checkNotNull(pad.voice), pad.macros, checkNotNull(pad.chain), velocity, rng)
        }
    }

    /** acoustic.stack_pad: the voice at its resolved TUNE in the stack's room, dry of WARMTH, resampled when flagged, then the stack's record. */
    internal fun renderStack(pad: StackPad, velocity: Double, rng: Rng, chain: Chain = recipes.stackChain): DoubleArray {
        val m = pad.macros + mapOf("TUNE" to pad.tune.toFloat(), "DISTANCE" to chain.distance.toFloat(), "WARMTH" to 0f)
        var y = emberRaw(pad.voice, m, velocity, rng)
        if (pad.resample) y = BoroughDsp.resample(y, max(64, (y.size / pad.ratio).toInt()))
        return end(record(y, chain, velocity), y.size / SR)
    }

    /**
     * fuller.shorten: [y] untouched for [holdMs], then an added exponential decay reaching -30 dB at [tailMs]. Past
     * -60 dB (hold + 2 x (tail - hold)) the sound is cut, with a 10 ms cosine fade into the cut. Every stack pad
     * peaks inside the hold, so the peak, and the level law on it, is unchanged.
     */
    internal fun shortenTail(y: DoubleArray, tailMs: Double, holdMs: Double): DoubleArray {
        require(tailMs > holdMs && holdMs >= 0.0)
        val dbPerMs = 30.0 / (tailMs - holdMs)
        val n = min(y.size, ceil((holdMs + 2.0 * (tailMs - holdMs)) * SR / 1000.0).toInt())
        val fade = min(n, (0.010 * SR).roundToInt())
        return DoubleArray(n) { i ->
            val ms = i * 1000.0 / SR
            var g = 10.0.pow(-max(ms - holdMs, 0.0) * dbPerMs / 20)
            if (n < y.size && i >= n - fade) g *= 0.5 * (1 + cos(PI * (i - (n - fade) + 1) / fade))
            y[i] * g
        }
    }

    /** acoustic.ember_voice: any EMBER voice dry of EMBER's warmth, through this collection's record chain, for its whole render. */
    internal fun emberVoice(voice: EmberVoice, macros: Map<String, Float>, chain: Chain, velocity: Double, rng: Rng): DoubleArray {
        val y = emberRaw(voice, macros + ("WARMTH" to 0f), velocity, rng)
        return end(record(y, chain, velocity), y.size / SR)
    }

    /** EMBER's render at 44.1 kHz, peak 0.89 as `ember_proto.render` returns it. */
    private fun emberRaw(voice: EmberVoice, macros: Map<String, Float>, velocity: Double, rng: Rng): DoubleArray {
        val f = Ember.renderRaw(voice, macros, velocity.toFloat(), rng)
        val pk = f.maxOfOrNull { abs(it) }?.toDouble() ?: 0.0
        val k = if (pk > 0) Ember.PEAK / pk else 1.0
        return DoubleArray(f.size) { f[it] * k }
    }

    // ------------------------------------------------------------------ designed drums (designed.py)

    private fun nOf(seconds: Double) = (seconds * FSD).roundToInt()

    /**
     * designed.kick. Boom (EMBER's membrane at f1, ringing t1, its second head 2% up) + punch (a sine struck at
     * punch_hz falling onto f1 over punch_ms, punch_db against the boom) + click (beater, click_db) -> grit on what is
     * above 200 Hz (drive) -> mud cut at 350 Hz (mud_db) -> low-pass. Velocity: the punch and click grow faster than
     * the boom.
     */
    internal fun designedKick(p: Map<String, Double>, vel: Double, rng: Rng, seconds: Double = 0.6): DoubleArray {
        val n = nOf(seconds + 0.05)
        val beater = Ember.mallet(vel, rng, n, widthHard = p.getValue("beater_ms") * 1e-3)
        val r = (0.15 + rng.normal(0.0, 0.03)).coerceIn(0.0, 0.5)
        val angle = rng.uniform(0.0, PI)
        val ir = peakNorm(Ember.membraneIr(p.getValue("f1"), n, p.getValue("t1"), p.getValue("alpha"), r, angle,
            sag = 0.03 * vel * vel, sagTau = 0.04, resoRatio = 1.02))
        val boom = to44(conv(beater, ir, n), 0.05)
        val bk = 1.0 / (peakOf(boom) + 1e-12)
        val len = boom.size
        val f1 = p.getValue("f1")
        val tau = p.getValue("punch_ms") * 1e-3
        val punchHz = p.getValue("punch_hz")
        val punchGain = 10.0.pow(p.getValue("punch_db") / 20) * vel.pow(0.6)
        val lo = DoubleArray(len)
        var ph = 0.0
        for (i in 0 until len) {
            val t = i / SR
            ph += 2 * PI * (f1 + (punchHz - f1) * exp(-t / tau)) / SR      // np.cumsum: the first sample already advanced
            val punch = sin(ph) * exp(-t / (2.2 * tau)) * (1 - exp(-t / 0.0008)) * punchGain
            lo[i] = boom[i] * bk + punch
        }
        val noise = rng.normals(len)
        val clk = EmberDsp.hp(DoubleArray(len) { noise[it] * exp(-it / SR / 0.0007) }, 1500.0, SR)
        val clickGain = 10.0.pow(p.getValue("click_db") / 20) * vel.pow(1.5)
        val above = EmberDsp.hp(lo, 200.0, SR)
        val hi = DoubleArray(len) { above[it] + clk[it] * clickGain }
        for (i in 0 until len) lo[i] -= above[i]
        val d = p.getValue("drive") * (0.4 + 0.6 * vel)
        if (d > 1e-3) {
            val g = 1 + 8 * d
            val m = peakOf(hi) + 1e-12
            val tg = tanh(g)
            for (i in 0 until len) hi[i] = tanh(g * hi[i] / m) * m / tg
        }
        var y = DoubleArray(len) { lo[it] + hi[it] }
        y = EmberDsp.peaking(350.0, -p.getValue("mud_db"), 0.9, SR).run(y)
        y = EmberDsp.lp(y, p.getValue("lp"), SR)
        return end(y.copyOf(min(y.size, (seconds * SR).toInt())), seconds)
    }

    /**
     * acoustic.snare with the record chain off (as designed.snare calls it): EMBER's stick on a membrane at the
     * fitted pitch, decay and loss, struck at r, threshold wires driven by the head's own motion, in the room at
     * 0.05, at 44.1 kHz, peak 1.
     */
    private fun snareCore(p: Map<String, Double>, vel: Double, rng: Rng, seconds: Double): DoubleArray {
        val n = nOf(seconds + 0.05)
        val force = Ember.stick(vel, rng, n, widthHard = p.getValue("stick_ms") * 1e-3)
        val r = (p.getValue("r") + rng.normal(0.0, 0.07)).coerceIn(0.0, 0.95)
        val angle = rng.uniform(0.0, PI)
        val sagTau = 0.04 + 0.02 * rng.random()
        val ir = peakNorm(Ember.membraneIr(p.getValue("f1"), n, p.getValue("t1"), p.getValue("alpha"), r, angle,
            sag = 0.03 * vel * vel, sagTau = sagTau, scatter = rng))
        val head = conv(force, ir, n)
        val k = nOf(0.08)
        val ref = conv(hannForce(0.28e-3, 1.0, k, 0.0, FSD), ir.copyOf(k), k)
        val low = EmberDsp.lp(head, 900.0, FSD)
        val lr = peakOf(EmberDsp.lp(ref, 900.0, FSD)) + 1e-12
        val hd = DoubleArray(n) { val x = low[it] / lr; sign(x) * abs(x).pow(0.6) }
        EmberDsp.add(head, Ember.wires(hd, p.getValue("buzz"), rng, n), p.getValue("wire") * Ember.WIRE_GAIN * peakOf(ref))
        return end(record(to44(head, 0.05), CORE_CHAIN, vel), seconds)
    }

    private val CORE_CHAIN = Chain(0.05, 0.0, 0.0, 16000.0, 0.0)

    /**
     * designed.snare. EMBER's snare core (head f1, t1, wires) with the record chain off + a body tone at f1 (body_db,
     * body_ms) + a crack (noise from crack_hz / 2 to crack_hz x 2, crack_db, crack_ms) -> low cut 90 Hz -> mud cut at
     * 400 Hz -> grit (drive) -> low-pass. Velocity: the crack grows fastest.
     */
    internal fun designedSnare(p: Map<String, Double>, vel: Double, rng: Rng, seconds: Double = 0.45): DoubleArray {
        val core = snareCore(p, vel, rng, seconds)
        val len = core.size
        val f1 = p.getValue("f1")
        val bodyTau = p.getValue("body_ms") * 1e-3
        val bodyGain = 10.0.pow(p.getValue("body_db") / 20) * vel.pow(0.8)
        val crackHz = p.getValue("crack_hz")
        val crack = EmberDsp.band(rng.normals(len), crackHz / 2, min(crackHz * 2, SR * 0.45), SR)
        val crackTau = p.getValue("crack_ms") * 1e-3
        for (i in 0 until len) crack[i] *= exp(-i / SR / crackTau)
        val ck = 10.0.pow(p.getValue("crack_db") / 20) * vel.pow(1.4) / (peakOf(crack) + 1e-12)
        var y = DoubleArray(len) { i ->
            val t = i / SR
            val body = sin(2 * PI * f1 * t * (1 - 0.03 * exp(-t / 0.02))) * exp(-t / bodyTau) * (1 - exp(-t / 0.0006)) * bodyGain
            core[i] + body + crack[i] * ck
        }
        y = EmberDsp.hp(y, 90.0, SR)
        y = EmberDsp.peaking(400.0, -p.getValue("mud_db"), 0.9, SR).run(y)
        val d = p.getValue("drive") * (0.4 + 0.6 * vel)
        if (d > 1e-3) {
            val g = 1 + 8 * d
            val m = peakOf(y) + 1e-12
            val tg = tanh(g)
            for (i in y.indices) y[i] = tanh(g * y[i] / m) * m / tg
        }
        y = EmberDsp.lp(y, p.getValue("lp"), SR)
        return end(y.copyOf(min(y.size, (seconds * SR).toInt())), seconds)
    }

    // ------------------------------------------------------------------ the record (acoustic.py, 44.1 kHz)

    /** acoustic.to44: the club at [distance] (EMBER's room, at 176.4 kHz), then scipy's 4:1 polyphase decimation, mean out. */
    fun to44(raw: DoubleArray, distance: Double): DoubleArray {
        val y = BoroughDsp.down4(Ember.club(raw, Math.round(distance * 100) / 100.0))
        val mean = y.average()
        for (i in y.indices) y[i] -= mean
        return y
    }

    /**
     * acoustic.compress: a feed-forward peak compressor. [amount] 0 is a bypass; 1 puts the threshold 24 dB under the
     * hit's peak. Brings the room and the drum's tail up against the attack, as a squashed break does.
     */
    fun compress(x: DoubleArray, amount: Double, attackMs: Double = 2.0, releaseMs: Double = 90.0, ratio: Double = 4.0): DoubleArray {
        if (amount <= 1e-3) return x
        val pk = peakOf(x) + 1e-12
        val thr = pk * 10.0.pow(-24 * amount / 20)
        val aAt = exp(-1 / (attackMs * 1e-3 * SR))
        val aRe = exp(-1 / (releaseMs * 1e-3 * SR))
        val ex = 1 / ratio - 1
        var e = 0.0
        return DoubleArray(x.size) { i ->
            val l = abs(x[i])
            e = if (l > e) aAt * e + (1 - aAt) * l else aRe * e + (1 - aRe) * l
            x[i] * max(e / thr, 1.0).pow(ex)
        }
    }

    /**
     * acoustic.record: the sampled-break chain on one hit at 44.1 kHz: compress -> drive (harder hits drive harder)
     * -> tilt -> low-pass.
     */
    fun record(y44: DoubleArray, c: Chain, vel: Double): DoubleArray {
        var y = compress(y44, c.comp)
        val d = c.drive * (0.35 + 0.65 * vel)
        if (d > 1e-3) {
            val g = 1 + 10 * d
            val pk = peakOf(y) + 1e-12
            val off = tanh(0.1 * d)
            y = DoubleArray(y.size) { (tanh(g * y[it] / pk + 0.1 * d) - off) * pk / g }
        }
        if (c.tilt != 0.0) {
            val hi = EmberDsp.hp(y, 1000.0, SR, order = 1)
            EmberDsp.add(y, hi, 10.0.pow(c.tilt / 20) - 1)
        }
        return EmberDsp.lp(y, min(c.lp, SR * 0.45), SR)
    }

    /**
     * acoustic.end: cut to length, a 20 Hz high-pass, trim 60 dB under the 10 ms envelope's peak, a raised-cosine
     * release (250 ms where the last full 10 ms still rings above 1e-3 of the peak, else 10 ms), peak 1.
     */
    fun end(yIn: DoubleArray, seconds: Double): DoubleArray {
        var y = EmberDsp.hp(yIn.copyOf(min(yIn.size, (seconds * SR).toInt())), 20.0, SR, order = 1)
        val w = (0.01 * SR).toInt()
        val n = y.size
        // np.convolve(y*y, ones(w)/w, 'same'): a centred 441-sample mean (220 either side)
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + y[i] * y[i]
        val e = DoubleArray(n) { i ->
            val a = max(0, i - (w - 1) / 2); val b = min(n, i + w / 2 + 1)
            sqrt(max(0.0, pre[b] - pre[a]) / w)
        }
        val emax = e.maxOrNull() ?: 0.0
        var last = -1
        for (i in 0 until n) if (e[i] > emax * 1e-3) last = i
        val stop = min(n, (if (last >= 0) last else n) + w)
        y = y.copyOf(stop)
        val from = max(0, stop - w)
        val tail = sqrt((from until stop).sumOf { y[it] * y[it] } / max(1, stop - from))
        val still = tail > peakOf(y) * 1e-3
        val k = if (still) min((0.25 * SR).toInt(), stop / 4) else min(w, stop / 4)
        for (q in 0 until k) {
            val g = 0.5 + 0.5 * cos(if (k > 1) PI * q / (k - 1) else 0.0)
            y[stop - k + q] *= g
        }
        val pk = peakOf(y) + 1e-12
        for (i in y.indices) y[i] /= pk
        return y
    }
}

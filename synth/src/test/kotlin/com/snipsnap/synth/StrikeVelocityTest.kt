package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A strike velocity for the modal drums ([StrikeVelocity]): SKIN's KICK and
 * TOM, every TERRA voice, and THUMP's and ONSET's kicks. A soft hit tilts the
 * modes toward the root, bends a membrane (or a kick's sweep) less, lands
 * with less grit or a quieter, duller click, and drives TERRA's buzz and
 * cavity and THUMP's DRIVE less. Full velocity is each engine's existing
 * render, bit for bit.
 *
 * Brightness here is the magnitude-weighted centroid of the first 30 ms
 * ([attackCentroid]), not [com.snipsnap.audio.FeatureExtractor]'s
 * power-weighted one: power weighting lets a kick's fundamental outvote
 * every overtone (SKIN KICK's power centroid moves 4% across the whole
 * velocity range while its magnitude centroid moves 27%), and the
 * overtones are what a strike changes.
 */
class StrikeVelocityTest {

    private val velocities = listOf(0.25f, 0.5f, 0.75f, 1f)

    /** Magnitude-weighted spectral centroid of the first [seconds], Hann-windowed over that span and zero-padded to 16384. */
    private fun attackCentroid(s: Snip, seconds: Double = 0.030, from: Double = 0.0): Double {
        val o = (from * s.sampleRate).toInt()
        val n = minOf(s.samples.size - o, (seconds * s.sampleRate).toInt())
        val size = 16384
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until n) re[i] = s.samples[o + i] * (0.5f - 0.5f * cos(2.0 * Math.PI * i / (n - 1)).toFloat())
        Fft.forward(re, im)
        var weighted = 0.0
        var total = 0.0
        for (b in 0..size / 2) {
            val m = sqrt((re[b] * re[b] + im[b] * im[b]).toDouble())
            weighted += m * b * s.sampleRate / size
            total += m
        }
        return weighted / total
    }

    private fun skinCases(voice: SkinVoice): List<SkinPatch> =
        listOf(SkinPatch("default", voice, Skin.defaults(voice))) + SkinPresets.forVoice(voice)

    private fun thumpKicks(): List<ThumpPatch> =
        listOf(ThumpPatch("default", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK))) + ThumpPresets.forVoice(ThumpVoice.KICK)

    /** ONSET's default kick, its three presets and the acceptance kit's four blends, at the current model. */
    private fun onsetKicks(): List<OnsetPatch> =
        listOf(OnsetPatch("default", OnsetVoice.KICK, Onset.defaults(OnsetVoice.KICK))) + OnsetPresets.forVoice(OnsetVoice.KICK) +
            OnsetKits.patches().filter { it.voice == OnsetVoice.KICK }

    private fun terraCases(): List<TerraPatch> =
        TerraVoice.entries.map { TerraPatch("default", it, Terra.defaults(it)) } +
            TerraKits.classic().map { pad -> requireNotNull(pad).recipe!!.let { PadRecipe.fromJsonValue(it).patch as TerraPatch } }

    // ---------------------------------------------------------------- full velocity is today

    /**
     * Every SKIN voice and preset at full velocity, against hashes taken
     * from the source before velocity existed (ca5c327). Per patch: the
     * first 16 hex digits of SHA-256 over the little-endian floats; per
     * voice: the same over those digits, concatenated in preset order. The
     * non-struck voices are pinned too, because they share render().
     */
    @Test
    fun `SKIN at full velocity is the render it always made`() {
        val pinned = mapOf(
            SkinVoice.KICK to "da7c1288c708a1d2", SkinVoice.SNARE to "d76b1d547e2822e5",
            SkinVoice.HAT_CLOSED to "27e5de1c89b7ef1b", SkinVoice.HAT_OPEN to "b6503222cdee2b00",
            SkinVoice.TOM to "3aa1e089c6ff9cb2", SkinVoice.RIDE to "c647665316dd6497",
            SkinVoice.SHAKER to "680d84ab635a00ec", SkinVoice.STICK to "6a93fca3f86c3b66",
        )
        fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        for (voice in SkinVoice.entries) {
            val digits = skinCases(voice).joinToString("") { p ->
                val s = Skin.render(voice, p.macros, velocity = 1f).samples
                val bb = ByteBuffer.allocate(s.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (x in s) bb.putFloat(x)
                hex(bb.array())
            }
            assertEquals(pinned.getValue(voice), hex(digits.toByteArray()), "$voice at full velocity")
        }
    }

    /**
     * THUMP's and ONSET's kicks at full velocity, against hashes taken from
     * the source before they read a velocity (22a6b4005): THUMP's default
     * and twenty presets; ONSET's default, three presets and four kit blends
     * at each saved model. Hashed as in the SKIN pin above.
     */
    @Test
    fun `THUMP and ONSET kicks at full velocity are the renders they always made`() {
        fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        fun hash(s: FloatArray): String {
            val bb = ByteBuffer.allocate(s.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in s) bb.putFloat(x)
            return hex(bb.array())
        }
        val thump = thumpKicks()
        assertEquals(21, thump.size)
        assertEquals("8fef496220685a1b", hex(thump.joinToString("") { hash(Thump.render(ThumpVoice.KICK, it.macros, velocity = 1f).samples) }.toByteArray()), "THUMP KICK")
        val onset = onsetKicks()
        assertEquals(8, onset.size)
        val pinned = mapOf(1 to "c896d49ef18708fb", 2 to "7808097023b24cca", 3 to "7808097023b24cca")
        for ((model, pin) in pinned) {
            assertEquals(pin, hex(onset.joinToString("") { hash(Onset.render(OnsetVoice.KICK, it.macros, model, velocity = 1f).samples) }.toByteArray()), "ONSET KICK model $model")
        }
    }

    @Test
    fun `a full-velocity touch is the patch's own render`() {
        val patches = Skin.STRUCK.flatMap { skinCases(it) } + terraCases() + thumpKicks() + onsetKicks()
        for (p in patches) assertContentEquals(p.render().samples, Velocity.atVelocity(p, 1f).samples, "${p.engine} ${p.voiceName} ${p.name}")
    }

    @Test
    fun `every factor is exactly one at full velocity, above it, and for a NaN`() {
        for (v in listOf(1f, 1.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(StrikeVelocity.isFull(v), "$v")
            assertEquals(1f, StrikeVelocity.modeFactor(3.7f, v))
            assertEquals(1f, StrikeVelocity.droopFactor(v))
            assertEquals(1f, StrikeVelocity.pulseLevel(v))
            assertEquals(1f, StrikeVelocity.cornerFactor(v))
            assertEquals(1f, StrikeVelocity.gritFactor(v))
            assertEquals(1f, StrikeVelocity.levelSeen(v))
            assertContentEquals(floatArrayOf(1f, 1f, 1f), StrikeVelocity.modeFactors(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, .5f, .2f), v))
        }
    }

    // ---------------------------------------------------------------- the law

    @Test
    fun `a softer hit tilts the modes toward the root and keeps their summed gain`() {
        val ratios = floatArrayOf(1f, 1.99f, 2.98f, 3.99f)
        val gains = floatArrayOf(1f, .65f, .45f, .25f)
        var previous: FloatArray? = null
        for (v in listOf(1f, .75f, .5f, .25f, .05f)) {
            val f = StrikeVelocity.modeFactors(ratios, gains, v)
            val sum = gains.indices.sumOf { (gains[it] * f[it]).toDouble() }
            assertEquals(gains.sum().toDouble(), sum, 1e-5, "summed gain at $v")
            for (k in 1 until ratios.size) assertTrue(f[k] / f[0] <= f[k - 1] / f[0] + 1e-6f, "mode $k never outranks mode ${k - 1} at $v")
            previous?.let { p -> for (k in 1 until ratios.size) assertTrue(f[k] / f[0] < p[k] / p[0], "mode $k loses ground to the root as v falls to $v") }
            previous = f
        }
        assertEquals(.25f, StrikeVelocity.droopFactor(.5f), 1e-7f)
    }

    /**
     * What TERRA hands its bank: the bend scaled by v^2, and the same modes
     * tilted, read off [Terra.Body] through the input hook. BOX is the
     * exception, by design: its contact law is the slap's own pulse width
     * (TerraBox.render), so its modes reach the bank untilted at every hit.
     * So are the hand drums (SLAP, DAYAN, DARBUKA; TerraHands), for the
     * same reason.
     */
    @Test
    fun `TERRA's bank sees the bend by v squared and the tilted modes`() {
        for (voice in TerraVoice.entries) {
            if (voice == TerraVoice.BOX || voice in Terra.HAND_DRUMS) {
                fun bodyAt(v: Float): Terra.Body {
                    var seen: Terra.Body? = null
                    Terra.renderAt(voice, Terra.defaults(voice), v) { body -> seen = body; null }
                    return assertNotNull(seen)
                }
                val full = bodyAt(1f)
                val soft = bodyAt(.25f)
                assertEquals(full.modes.map { it.gain }, soft.modes.map { it.gain }, "$voice's modes are not tilted by velocity")
                // Nor moved: the same ratios over the same fundamental, so the drum's pitch is velocity's
                // own by construction (the root test below reads it for the other voices).
                assertEquals(full.fundamentalHz, soft.fundamentalHz, "$voice's fundamental")
                assertEquals(full.modes.map { it.ratio }, soft.modes.map { it.ratio }, "$voice's mode ratios")
                continue
            }
            val macros = Terra.defaults(voice) + ("DROOP" to .8f).takeIf { "DROOP" in Terra.defaults(voice) }.let { listOfNotNull(it).toMap() }
            fun bodyAt(v: Float): Terra.Body {
                var seen: Terra.Body? = null
                Terra.renderAt(voice, macros, v) { body -> seen = body; null }
                return assertNotNull(seen)
            }
            val full = bodyAt(1f)
            val soft = bodyAt(.5f)
            assertEquals(full.droopDepth * .25f, soft.droopDepth, 1e-6f, "$voice droop")
            assertEquals(full.modes.sumOf { it.gain.toDouble() }, soft.modes.sumOf { it.gain.toDouble() }, 1e-5, "$voice summed gain")
            for (k in 1 until full.modes.size) {
                if (full.modes[k].gain == 0f) continue
                assertTrue(soft.modes[k].gain / soft.modes[0].gain < full.modes[k].gain / full.modes[0].gain, "$voice mode $k against the root")
            }
        }
    }

    // ---------------------------------------------------------------- what it sounds like, measured

    /** Every way [render] fails to brighten with velocity, as messages; empty when it does. */
    private fun brightening(label: String, minRise: Double, from: Double = 0.0, render: (Float) -> Snip): List<String> {
        val c = velocities.map { attackCentroid(render(it), from = from) }
        val shown = c.joinToString { "%.0f".format(it) }
        val out = mutableListOf<String>()
        for (i in 1 until c.size) if (!(c[i] > c[i - 1])) out += "$label: not brighter at ${velocities[i]} than ${velocities[i - 1]}: [$shown]"
        val rise = c.last() / c.first() - 1
        if (rise < minRise) out += "$label: the first 30 ms rise ${"%.0f".format(rise * 100)}% from v=.25 to 1, under ${"%.0f".format(minRise * 100)}%: [$shown]"
        return out
    }

    /**
     * Measured at the time of writing (min over the cases): SKIN KICK +25%,
     * SKIN TOM +5%, TERRA bells +8%, every other TERRA case +51%. The floors
     * sit under those with room; a TOM is two partials, so there is little
     * to tilt, and a forged bell's quadratic damping already strips its top.
     */
    /**
     * SKIN presets whose onset brightness velocity cannot move, measured
     * within 3% across the range: FELT BOOM is a felt beater on a sub (no
     * CLICK, TONE 0.15, so no overtones to tilt; its velocity is the DROP
     * bend), and FAST TOM and RUSH DRUM run PUNCH at 0.88 and 0.68 with no
     * CLICK or DROP, whose saturator rebuilds what the tilt takes out.
     */
    private val FLAT = setOf("FELT BOOM", "FAST TOM", "RUSH DRUM")

    @Test
    fun `a harder hit is brighter at its onset, on every SKIN kick and tom`() {
        val failures = skinCases(SkinVoice.KICK).filter { it.name !in FLAT }
            .flatMap { p -> brightening("SKIN KICK ${p.name}", .15) { Skin.render(SkinVoice.KICK, p.macros, it) } } +
            skinCases(SkinVoice.TOM).filter { it.name !in FLAT }
                .flatMap { p -> brightening("SKIN TOM ${p.name}", .02) { Skin.render(SkinVoice.TOM, p.macros, it) } }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        // The exemptions are named so a new flat preset fails here instead of
        // joining them silently, and they stay flat rather than darkening.
        for (name in FLAT) {
            val p = (skinCases(SkinVoice.KICK) + skinCases(SkinVoice.TOM)).single { it.name == name }
            val c = velocities.map { attackCentroid(Skin.render(p.voice, p.macros, it)) }
            assertTrue(c.max() / c.min() < 1.05, "$name stays within 5% across velocity: $c")
        }
    }

    /**
     * THUMP's kicks are read from 2.5 ms on, past PUNCH's 2 ms onset window.
     * Inside it PUNCH's saturator has a step at the window's end that is
     * there on every hit: for any PUNCH above 0, a sample's drive amount
     * there is above 0, and [Dsp.drive] at an amount just above 0 is
     * tanh(x) / tanh(1), about 1.31x for a small sample, not x. So the first
     * 2 ms sit ~2.4 dB above the rest, and that edge is a fixed click. On a
     * soft hit, with the sweep and the beater click softened, it is the
     * brightest thing left: from 0 ms, 18 of THUMP's 21 kicks read brighter
     * at v = .25 than at .5 (default 86 / 80 / 81 / 103 Hz); from 2.5 ms all
     * 21 brighten at every step, by at least 52%. It predates velocity and
     * moves every THUMP, SKIN and TERRA render, so it is disclosed
     * (docs/STRIKE_VELOCITY.md), not fixed here. ONSET's kicks, which run
     * their own saturator, brighten from 0 ms (at least 22% at the time of
     * writing).
     */
    @Test
    fun `a harder hit is brighter, on every THUMP and ONSET kick`() {
        val failures = thumpKicks().flatMap { p -> brightening("THUMP KICK ${p.name}", .40, from = .0025) { Velocity.atVelocity(p, it) } } +
            onsetKicks().flatMap { p -> brightening("ONSET KICK ${p.name}", .15) { Velocity.atVelocity(p, it) } } +
            (1..2).flatMap { model ->
                val p = OnsetPatch("default", OnsetVoice.KICK, Onset.defaults(OnsetVoice.KICK), model)
                brightening("ONSET KICK model $model", .15) { Velocity.atVelocity(p, it) }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `a harder hit is brighter at its onset, on every TERRA voice and kit pad`() {
        val failures = terraCases().flatMap { p ->
            val floor = if (p.voice == TerraVoice.CONICAL_BELL) .04 else .30
            brightening("TERRA ${p.voiceName} ${p.name}", floor) { Velocity.atVelocity(p, it) }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The root a pitched pad is filed under survives a soft hit: within 25 cents of the full-velocity reading wherever both are confident. */
    @Test
    fun `the root survives a soft hit`() {
        var checked = 0
        val patches = Skin.STRUCK.flatMap { skinCases(it) } + terraCases() + thumpKicks() + onsetKicks()
        // Read after the bend has settled (TERRA's droop has a 20 ms time
        // constant, SKIN's tom 1/18 s), so a hard hit's own sharp start is
        // not mistaken for a moved root.
        for (p in patches) {
            // BOX and the hand drums are held by their modes instead (the bank test above: the same
            // ratios over the same fundamental at every velocity). On their dense inharmonic heads the
            // detector's reading moves with the modes' balance: DARBUKA's (0, 1) mode is 340.0 Hz at
            // every velocity, read as 344.9 Hz hard and 337.9 soft, 35 cents apart.
            if (p is TerraPatch && (p.voice == TerraVoice.BOX || p.voice in Terra.HAND_DRUMS)) continue
            val hardSnip = p.render()
            if (hardSnip.durationSeconds < 0.32f) continue
            val hard = Pitch.detect(hardSnip, fromSec = 0.22f, windowSec = 0.10f) ?: continue
            val soft = Pitch.detect(Velocity.atVelocity(p, .25f), fromSec = 0.22f, windowSec = 0.10f) ?: continue
            if (hard.confidence < .5f || soft.confidence < .5f) continue
            val cents = 1200.0 * ln(soft.hz.toDouble() / hard.hz) / ln(2.0)
            assertTrue(abs(cents) <= 25.0, "${p.engine} ${p.voiceName} ${p.name}: ${"%.1f".format(cents)} cents")
            checked++
        }
        assertTrue(checked >= 20, "enough confident roots to mean something: $checked")
    }

    /**
     * SKIN's DROP bends the head sharp at the strike. A soft hit bends it
     * less: more zero crossings in the first 40 ms at full velocity than at
     * a quarter, and by more than the tilt alone explains (the same
     * comparison with DROP at 0).
     */
    @Test
    fun `a soft SKIN kick bends less`() {
        fun crossings(s: Snip): Int {
            val n = (0.040 * s.sampleRate).toInt()
            var c = 0
            for (i in 1 until n) if ((s.samples[i - 1] < 0f) != (s.samples[i] < 0f)) c++
            return c
        }
        fun gap(drop: Float): Int {
            val m = Skin.defaults(SkinVoice.KICK) + mapOf("DROP" to drop, "CLICK" to 0f)
            return crossings(Skin.render(SkinVoice.KICK, m, 1f)) - crossings(Skin.render(SkinVoice.KICK, m, .25f))
        }
        val bent = gap(1f)
        val flat = gap(0f)
        assertTrue(bent > flat + 1, "a hard hit's bend adds crossings a soft one lacks: $bent with DROP 1, $flat with DROP 0")
    }

    /** THUMP's SWEEP is its kick's bend: a soft hit sweeps less, by more than the click and drive explain (SWEEP 0 against 1, CLICK 0). */
    @Test
    fun `a soft THUMP kick sweeps less`() {
        fun crossings(s: Snip): Int {
            val n = (0.040 * s.sampleRate).toInt()
            var c = 0
            for (i in 1 until n) if ((s.samples[i - 1] < 0f) != (s.samples[i] < 0f)) c++
            return c
        }
        fun gap(sweep: Float): Int {
            val m = Thump.defaults(ThumpVoice.KICK) + mapOf("SWEEP" to sweep, "CLICK" to 0f)
            return crossings(Thump.render(ThumpVoice.KICK, m, 1f)) - crossings(Thump.render(ThumpVoice.KICK, m, .25f))
        }
        val swept = gap(1f)
        val flat = gap(0f)
        assertTrue(swept > flat + 1, "a hard hit's sweep adds crossings a soft one lacks: $swept with SWEEP 1, $flat with SWEEP 0")
    }

    // ---------------------------------------------------------------- what it leaves alone

    @Test
    fun `SKIN voices outside STRUCK ignore velocity and keep their old touch`() {
        for (voice in SkinVoice.entries - Skin.STRUCK) {
            val m = Skin.defaults(voice)
            assertContentEquals(Skin.render(voice, m).samples, Skin.render(voice, m, .3f).samples, "$voice ignores a render velocity")
        }
    }

    @Test
    fun `THUMP and ONSET voices other than KICK ignore velocity`() {
        for (voice in ThumpVoice.entries - ThumpVoice.KICK) {
            val m = Thump.defaults(voice)
            assertContentEquals(Thump.render(voice, m).samples, Thump.render(voice, m, .3f).samples, "THUMP $voice ignores a render velocity")
        }
        for (voice in OnsetVoice.entries - OnsetVoice.KICK) {
            val m = Onset.defaults(voice)
            assertContentEquals(Onset.render(voice, m).samples, Onset.render(voice, m, velocity = .3f).samples, "ONSET $voice ignores a render velocity")
        }
    }

    @Test
    fun `soft hits are deterministic, finite and bounded`() {
        val patches = Skin.STRUCK.flatMap { skinCases(it).take(3) } + terraCases() + thumpKicks().take(5) + onsetKicks()
        for (p in patches) {
            val a = Velocity.atVelocity(p, .37f)
            assertContentEquals(a.samples, Velocity.atVelocity(p, .37f).samples, "${p.name} twice")
            assertTrue(a.samples.all { it.isFinite() && abs(it) <= 1f }, "${p.name} finite and within 1")
            assertEquals(p.render().samples.size, a.samples.size, "${p.name}: velocity keeps the filed length")
        }
    }

    @Test
    fun `a struck TERRA pad takes velocity on top of HIT`() {
        val head = TerraStrikers.head("kick01")
        val p = TerraPatch("struck", TerraVoice.COMPOUND_MEMBRANE, Terra.defaults(TerraVoice.COMPOUND_MEMBRANE), TerraPatch.Striker(head, .8f))
        val soft = Velocity.atVelocity(p, .3f)
        assertFalse(soft.samples.contentEquals(p.render().samples), "velocity moves a struck pad")
        val plain = TerraPatch("plain", p.voice, p.macros)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(plain, .3f).samples), "and HIT still colours it")
        assertTrue(soft.samples.all { it.isFinite() && abs(it) <= 1f })
    }
}

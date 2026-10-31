package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ECHO's HEAD macro (docs/superpowers/specs/2026-10-22-delay-head-design.md).
 *
 * The first test pins ECHO as it shipped before HEAD existed: SHA-256 hashes of its output, recorded on the base.
 * Every setting is a literal, so a later change to a recipe, to Siren, or to the macro list (whose scramble gains a
 * fifth draw) cannot move what the pin compares. The sources are engine renders (the spec's choice), so their own hashes
 * are pinned first: if a source moves, the test says the source moved, not ECHO. Only then may the output pins be
 * re-recorded, and only by running the base Echo.kt (as of e9d62a05, unchanged since #335) on the new render. The rest
 * pin what HEAD does when it is on, at the values the spec measured, with the bars at the measured value less a stated
 * margin.
 */
class EchoTest {

    private val kick = Thump.render(ThumpVoice.KICK)
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val choir = Vox.render(VoxVoice.CHOIR)

    private val sources = linkedMapOf("kick" to kick, "snare" to snare, "choir" to choir)

    /** Shipped settings, as literals: the bank's `slapback`, SIREN's `LANDING_ECHO`, ECHO's defaults, and three draws made once with Random(0..2). */
    private val settings: Map<String, Map<String, Float>> = linkedMapOf(
        "slapback" to mapOf("TIME" to 0.25f, "REPEAT" to 0.3f, "TONE" to 0.4f, "MIX" to 0.5f),
        "landing" to mapOf("TIME" to 0.45f, "REPEAT" to 0.55f, "TONE" to 0.5f, "MIX" to 0.45f),
        "defaults" to emptyMap(),
        "random0" to mapOf("TIME" to 0.54963315f, "REPEAT" to 0.32810485f, "TONE" to 0.84885544f, "MIX" to 0.6613528f),
        "random1" to mapOf("TIME" to 0.13972723f, "REPEAT" to 0.6433261f, "TONE" to 0.87724733f, "MIX" to 0.62778014f),
        "random2" to mapOf("TIME" to 0.84498405f, "REPEAT" to 0.28444004f, "TONE" to 0.91098607f, "MIX" to 0.0035728216f),
    )

    /** The three sources as they render on the base: a mismatch here means a source moved, not ECHO. */
    private val SOURCE_PINS: Map<String, String> = mapOf(
        "kick" to "8ea2ee2d857e0dc74ad4d7f81a30db5c3c92b3000c2e5080d983c153e72a660d",
        "snare" to "a5d3bed7f4a5f64dfb127096d7e0b855b19d6b2f4a3d791f7d694e7b39dc5a51",
        "choir" to "8980c33ae0658f771a66eba27c5317bc3dd616df5fade7d90d6833f6cdfa98a7",
    )

    /** Recorded on the base before HEAD existed (plan Task 1). With the sources unmoved, a mismatch means HEAD 0 is no longer ECHO. */
    private val PINS: Map<String, String> = mapOf(
        "kick/slapback" to "979160c2327daedc29f8cc69a2a991bfd3e63ea3b9f0d88c0bc0f0675118b44e",
        "kick/landing" to "beeec4a2b1ccd365d06eade56dd90135cc4e6ab04c19a1980a64f04a6be9fce5",
        "kick/defaults" to "290689c66d2351602b7e018b0ac2e399d1baf0adf6459fbece0c4b789ba172e0",
        "kick/random0" to "58b05a9f184bb13bd4bb4c78170767bba9fd928838070dc214354ba4ad558a6d",
        "kick/random1" to "664acb2867d39193b8c781d5c1b91e18e56f2acd6bc81b755c735c22dc14c49b",
        "kick/random2" to "7db263c9eaef8e2ea3c64abaf7241e51571e04e63ed7d53d27c010ca478672d8",
        "snare/slapback" to "df17bd11a82b532a725acebc0cadea825d7555570735ae831f980aecd50d23a2",
        "snare/landing" to "7b617a491ff491f8a2ea1ecccdf0fdefcfded23ae8c7e6f54f5e67e7b2cc7a42",
        "snare/defaults" to "23b98ce5e351f75040869a72066d64b263f96d440716bfea5aa4785b1e08755d",
        "snare/random0" to "5d2e7a8e1c700967c6c956ba1b4e620a4af5c7cd191ece01f97ff2143705a078",
        "snare/random1" to "620a79343cf98d4be84425c196ed562dddc1ab232186fb2b870b0dc8d4656ca0",
        "snare/random2" to "368e2b4f24c091248486cd640f710a186a2558a550d8ca8467c260dffccba975",
        "choir/slapback" to "4179f4089f14b3a3588fc7b0777f58bd102ad9fcc3804db16c5771cd8dc6dcb5",
        "choir/landing" to "1369ed8d04a9220dc10e987e1938b0f36fcc50e0254fa4fcd6966d3f4a6cca56",
        "choir/defaults" to "4b4f4b734faa6ac239688dc6405d9a39e1ce1b25a1f5f81dba0946e1e9ce2692",
        "choir/random0" to "26027182fed16bb37bb596c7c92c6e415c3e0f5adae0ca34c661a3ae22e8b7cb",
        "choir/random1" to "ca5ca6a1392622124de6cde3909d1e0359036017da89897a9a77c58356ea37ec",
        "choir/random2" to "1c9a6551e31d9a3a6e9e3cc40d830c87d50757c5c33edc12b3bd1774e9e4bdf4",
    )

    /**
     * The recipe the HEAD gate measured (the `delayed` character from 2026-10-23 to 2026-10-28), spelled out so HEAD's
     * figures below are read off a literal. The owner chose HEAD off at that gate, so the shipped character is now
     * [delayed]; HEAD stays a macro for kits and the CLI, and these are its measurements.
     */
    private val headRecipe = mapOf("TIME" to 0.75f, "REPEAT" to 0.85f, "TONE" to 0.35f, "MIX" to 0.75f, "HEAD" to 1f)

    /** The `delayed` character at full, the DELAY pedal, as the owner confirmed it (2026-10-28): HEAD off. */
    private val delayed = mapOf("TIME" to 1f, "REPEAT" to 0.85f, "TONE" to 0.45f, "MIX" to 0.9f)

    /** [headRecipe] with [mix] for its MIX, faded to [amount] the way the pad sheet fades ECHO (every macro linear, neutral 0). */
    private fun at(mix: Float, amount: Float) = (headRecipe + ("MIX" to mix)).mapValues { it.value * amount }

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun channel(s: Snip, ch: Int): FloatArray = FloatArray(s.frameCount) { s.samples[it * s.channels + ch] }
    private fun doubled(s: Snip): Snip { val m = channel(s, 0); return Snip(FloatArray(m.size * 2) { m[it / 2] }, 2, s.sampleRate) }
    private fun hardLeft(s: Snip): Snip { val m = channel(s, 0); return Snip(FloatArray(m.size * 2) { if (it % 2 == 0) m[it / 2] else 0f }, 2, s.sampleRate) }
    private fun mono(s: Snip): Snip = Snip(channel(s, 0), 1, s.sampleRate)
    private fun ms(rate: Int, x: Float): Int = (x * rate / 1000f).toInt()

    /** The rms of every channel over frames [from, to). */
    private fun rms(s: Snip, from: Int, to: Int): Double {
        var acc = 0.0; var n = 0
        for (f in from until minOf(to, s.frameCount)) for (ch in 0 until s.channels) { val v = s.samples[f * s.channels + ch].toDouble(); acc += v * v; n++ }
        return sqrt(acc / maxOf(1, n))
    }

    /** The peak of every channel over frames [from, to). */
    private fun peak(s: Snip, from: Int, to: Int): Double {
        var p = 0.0
        for (f in from until minOf(to, s.frameCount)) for (ch in 0 until s.channels) p = maxOf(p, abs(s.samples[f * s.channels + ch].toDouble()))
        return p
    }

    private fun db(a: Double, b: Double): Double = 20 * log10(maxOf(a, 1e-12) / maxOf(b, 1e-12))

    /**
     * The spec's gap level: the whole output's rms, dry note included, from 10 ms after the head's first repeat ends
     * to 5 ms before the second repeat lands, against the rms of the first repeat's first 20 ms. Lower is more discrete.
     */
    private fun gap(out: Snip, macros: Map<String, Float>): Double {
        val r = out.sampleRate
        val delay = Echo.delayFrames(macros.getValue("TIME"), r)
        val head = Echo.headFrames(delay, r)
        return db(rms(out, delay + head + ms(r, 10f), 2 * delay - ms(r, 5f)), rms(out, delay, delay + ms(r, 20f)))
    }

    /**
     * A mono pad's wet alone, `(out / k - dry) / mix`, with k read off the loudest dry frame before the first repeat
     * (where the output is the dry times the peak match's one gain).
     */
    private fun wet(dry: Snip, macros: Map<String, Float>): Snip {
        val r = dry.sampleRate
        val out = Echo.process(dry, macros)
        val delay = Echo.delayFrames(macros.getValue("TIME"), r)
        var f0 = 0
        for (f in 0 until delay) if (abs(dry.samples[f]) > abs(dry.samples[f0])) f0 = f
        val k = out.samples[f0] / dry.samples[f0]
        val mix = macros.getValue("MIX")
        return Snip(FloatArray(out.frameCount) { f -> (out.samples[f] / k - (if (f < dry.frameCount) dry.samples[f] else 0f)) / mix }, 1, r)
    }

    /** [gap]'s two windows on a mono pad's [wet] alone. */
    private fun wetGap(dry: Snip, macros: Map<String, Float>): Double {
        val r = dry.sampleRate
        val w = wet(dry, macros)
        val delay = Echo.delayFrames(macros.getValue("TIME"), r)
        val head = Echo.headFrames(delay, r)
        return db(rms(w, delay + head + ms(r, 10f), 2 * delay - ms(r, 5f)), rms(w, delay, delay + ms(r, 20f)))
    }

    /** A held 220 Hz tone with a 2 ms click at its start: a picked note with nothing else in it. */
    private fun heldTone(seconds: Float = 2f, rate: Int = 44_100): Snip {
        val n = (seconds * rate).toInt()
        val click = ms(rate, 2f)
        return Snip(FloatArray(n) { i -> (0.3 * sin(2 * PI * 220.0 * i / rate)).toFloat() + if (i < click) 0.6f else 0f }, 1, rate)
    }

    // ---------------------------------------------------------------- HEAD 0 is ECHO

    @Test
    fun `HEAD 0 is ECHO as it shipped, byte for byte - absent, zero, under the off point and at it`() {
        assertEquals(sources.keys.flatMap { src -> settings.keys.map { "$src/$it" } }.toSet(), PINS.keys, "one pin per source and setting")
        for ((src, snip) in sources) {
            assertEquals(SOURCE_PINS.getValue(src), hash(snip.samples), "the $src render moved, not ECHO: re-record its PINS with the base Echo.kt (e9d62a05) on the new render")
        }
        for ((src, snip) in sources) for ((set, m) in settings) {
            val want = PINS.getValue("$src/$set")
            assertEquals(want, hash(Echo.process(snip, m).samples), "$src/$set: HEAD absent is no longer the shipped ECHO")
            for (off in listOf(0f, 0.0005f, Echo.HEAD_OFF)) {
                assertEquals(want, hash(Echo.process(snip, m + ("HEAD" to off)).samples), "$src/$set at HEAD $off")
            }
        }
    }

    // ---------------------------------------------------------------- the precedent's pins (EnsembleSectionTest's three)

    @Test
    fun `HEAD off renders the shipped samples on a mono, a doubled and a hard-left pad`() {
        for (signal in listOf(mono(snare), doubled(snare), hardLeft(snare))) for ((set, m) in settings) {
            val shipped = Echo.process(signal, m)
            for (off in listOf(0f, 0.0005f, Echo.HEAD_OFF)) {
                assertContentEquals(shipped.samples, Echo.process(signal, m + ("HEAD" to off)).samples, "$set at HEAD $off on ${signal.channels} channels")
            }
        }
    }

    @Test
    fun `a recipe written before HEAD existed reads back without it, writes back without it, and renders as slapback`() {
        val text = """{"fx":1,"reverse":false,"echo":{"TIME":0.25,"REPEAT":0.3,"TONE":0.4,"MIX":0.5}}"""
        val chain = FxChain.fromJsonText(text)
        assertEquals(null, chain.section("echo")?.get("HEAD"))
        assertTrue("HEAD" !in chain.toJsonText(), "reading an old recipe and writing it back added the key")
        assertContentEquals(Treatments.chain("slapback").process(snare).samples, chain.process(snare).samples)
    }

    @Test
    fun `HEAD is the last macro, off by default with neutral 0, and the first four random draws stay where they were`() {
        assertEquals(listOf("TIME", "REPEAT", "TONE", "MIX", "HEAD"), Echo.MACROS.map { it.name })
        assertEquals(0f, Echo.defaults().getValue("HEAD"))
        assertEquals(0f, Echo.MACROS.last().neutral)
        val rolled = Echo.scramble(Random(0))
        val r = Random(0)
        for (name in listOf("TIME", "REPEAT", "TONE", "MIX")) assertEquals(r.nextFloat(), rolled.getValue(name), "the $name draw moved")
    }

    // ---------------------------------------------------------------- HEAD on

    @Test
    fun `HEAD on keeps the section contract - deterministic, stereo-identical, no widening, mono stays mono`() {
        assertContentEquals(Echo.process(snare, headRecipe).samples, Echo.process(snare, headRecipe).samples, "not deterministic")
        for (s in listOf(kick, choir)) {
            val out = Echo.process(doubled(s), headRecipe)
            assertContentEquals(channel(out, 0), channel(out, 1), "a doubled pad's channels diverged")
        }
        val left = Echo.process(hardLeft(snare), headRecipe)
        assertTrue(channel(left, 1).all { it == 0f }, "a hard-left pad gained a right channel")
        assertEquals(1, Echo.process(mono(snare), headRecipe).channels, "a mono pad was widened")
    }

    @Test
    fun `the first repeat's onset is the same with HEAD on and off, up to the peak match's one gain`() {
        val on = Echo.process(choir, headRecipe)
        val off = Echo.process(choir, headRecipe + ("HEAD" to 0f))
        val delay = Echo.delayFrames(headRecipe.getValue("TIME"), choir.sampleRate)
        val head = Echo.headFrames(delay, choir.sampleRate)
        val end = (delay + head - head / 3) * choir.channels
        var p = 0
        for (i in 0 until end) if (abs(off.samples[i]) > abs(off.samples[p])) p = i
        val k = on.samples[p] / off.samples[p]
        for (i in 0 until end) assertEquals(k * off.samples[i], on.samples[i], 1e-5f, "sample $i of the onset")
    }

    @Test
    fun `HEAD makes the repeats discrete - the gap after the first repeat falls`() {
        val off = headRecipe + ("HEAD" to 0f)
        val choirOn = gap(Echo.process(choir, headRecipe), headRecipe)
        val choirOff = gap(Echo.process(choir, off), off)
        println(String.format(Locale.ROOT, "DELAY gap, choir: HEAD 0 %.1f dB, HEAD 1 %.1f dB", choirOff, choirOn))
        assertTrue(choirOff - choirOn >= 7.0, "HEAD opened only ${choirOff - choirOn} dB on the choir (measured 9.8)")
        // A held tone never decays, so with the dry note included its gap is set by the delay's phase against the tone,
        // not by HEAD (at 220 Hz the plain repeat lands near antiphase: HEAD 0 -5.0 dB, HEAD 1 +3.2). Its wet alone shows HEAD.
        val tone = heldTone()
        val toneOn = wetGap(tone, headRecipe)
        val toneOff = wetGap(tone, off)
        println(String.format(Locale.ROOT, "DELAY wet gap, held tone: HEAD 0 %.1f dB, HEAD 1 %.1f dB", toneOff, toneOn))
        assertTrue(toneOn < -60.0, "with HEAD on, a held tone's wet between repeats is only $toneOn dB under its first repeat")
        assertTrue(toneOff >= -6.7, "with HEAD off, a held tone's body should keep feeding the repeats (measured -3.7 dB; the bar is 3 dB under), got $toneOff")
    }

    @Test
    fun `the head window shrinks with a short TIME so a repeat of the head ends before the next lands`() {
        val r = 44_100
        assertEquals(2116, Echo.headFrames(Echo.delayFrames(0f, r), r))
        assertEquals(4100, Echo.headFrames(Echo.delayFrames(0.375f, r), r))
        assertEquals(5292, Echo.headFrames(Echo.delayFrames(0.75f, r), r))
        val m = headRecipe + ("TIME" to 0f)
        val w = wet(heldTone(), m).samples
        val delay = Echo.delayFrames(0f, r)
        val head = Echo.headFrames(delay, r)
        val wetPeak = w.maxOf { abs(it) }.toDouble()
        val worst = (delay + head + ms(r, 2f)..2 * delay - ms(r, 1f)).maxOf { abs(w[it]) }.toDouble()
        assertTrue(db(worst, wetPeak) < -60.0, "the wet between repeats at TIME 0 is ${db(worst, wetPeak)} dB under its peak")
    }

    @Test
    fun `the head window holds, fades on a raised cosine over its last third, then closes`() {
        for (h in listOf(5292, 4100, 2116)) {
            val fade = h / 3
            assertEquals(1f, Echo.window(0, h))
            assertEquals(1f, Echo.window(h - fade, h), "the fade starts at the window's last third")
            assertEquals(0.5f, Echo.window(h - fade / 2, h), 2e-3f, "half way down at the fade's middle")
            assertEquals(0f, Echo.window(h, h))
            for (f in 1..h) {
                val step = Echo.window(f - 1, h) - Echo.window(f, h)
                assertTrue(step >= 0f, "the window rose at frame $f of $h")
                assertTrue(step <= PI / (2 * fade) + 1e-6, "a step of $step at frame $f of $h: the fade is not a raised cosine")
            }
        }
    }

    @Test
    fun `on a pad that ends inside the head, HEAD changes nothing - every repeat and the tail are ECHO's own`() {
        // The feed factor is exactly 1f wherever the window is open, and the dry is 0 after the pad, so the bus, its feedback
        // and the tail's length are the same with HEAD on and off: the feedback path is checked repeat by repeat.
        val click = Snip(FloatArray(44_100) { if (it < 32) 0.9f else 0f }, 1, 44_100)
        for (time in listOf(0.75f, 0f)) for (s in listOf(click, doubled(click))) {
            val m = headRecipe + ("TIME" to time)
            assertContentEquals(Echo.process(s, m + ("HEAD" to 0f)).samples, Echo.process(s, m).samples, "TIME $time on ${s.channels} channels")
        }
    }

    @Test
    fun `the delayed character is the recipe the owner confirmed - HEAD off, repeats 350 ms apart at full and 206 at the first tap`() {
        assertEquals(delayed, Treatments.chain("delayed").echo, "delayed moved from the recipe the owner confirmed (2026-10-28)")
        assertEquals(delayed.mapValues { it.value * 0.7f }, Treatments.chain("delayed", 0.7f).echo, "the pad sheet no longer fades ECHO linearly")
        assertEquals(0f, Echo.reach(delayed), "the shipped recipe has HEAD off: the whole note feeds the repeats")
        val rate = 44_100
        assertEquals(350.0, Echo.delayFrames(1f, rate) * 1000.0 / rate, 0.5, "the repeats at full are not the 350 ms apart the owner asked for")
        assertEquals(206.0, Echo.delayFrames(0.7f, rate) * 1000.0 / rate, 0.5, "the repeats at AMT 70 are not 206 ms apart")
        // HEAD off is ECHO as it always was, bit for bit: the shipped recipe renders as the same values with HEAD set to 0.
        assertContentEquals(Echo.process(choir, delayed + ("HEAD" to 0f)).samples, Echo.process(choir, delayed).samples)
    }

    @Test
    fun `the HEAD recipe's first tap is the sheet's fade of it`() {
        assertEquals(Treatments.fade(FxChain(echo = headRecipe), 0.7f).echo, at(0.75f, 0.7f), "the pad sheet no longer fades ECHO linearly: the AMT 70 figures here are not what it plays")
    }

    @Test
    fun `HEAD is stretched by 1_5 so the first tap still feeds the head alone`() {
        assertEquals(1f, Echo.reach(mapOf("HEAD" to 2f / 3f)))
        assertEquals(1f, Echo.reach(mapOf("HEAD" to 0.7f)), "the HEAD recipe at AMT 70")
        assertEquals(0.9f, Echo.reach(mapOf("HEAD" to 0.6f)), 1e-6f)
        assertEquals(0.001f, Echo.HEAD_OFF, "the off point is the spec's")
        assertEquals(0f, Echo.reach(mapOf("HEAD" to Echo.HEAD_OFF)))
        assertTrue(Echo.reach(mapOf("HEAD" to 0.0011f)) > 0f, "just above the off point HEAD is on")
        assertEquals(0f, Echo.reach(emptyMap()))
        assertContentEquals(Echo.process(choir, headRecipe).samples, Echo.process(choir, headRecipe + ("HEAD" to 0.7f)).samples)
        assertNotEquals(Echo.process(choir, headRecipe).samples.toList(), Echo.process(choir, headRecipe + ("HEAD" to 0.6f)).samples.toList())
    }

    // ---------------------------------------------------------------- the numbers the HEAD recipe's MIX rested on

    @Test
    fun `the jangle's pick and the snare's repeat at the first tap - the figures MIX 0_75 was chosen from`() {
        val jangle = Magnet.landingChain(MagnetVoice.JANGLE).process(Magnet.render(MagnetVoice.JANGLE))
        val chug = Magnet.landingChain(MagnetVoice.CHUG).process(Magnet.render(MagnetVoice.CHUG))
        // The pick over the body: the first repeat's first 25 ms over the 20 ms ending 5 ms before it, peak.
        fun pick(out: Snip, delay: Int) = db(peak(out, delay, delay + ms(out.sampleRate, 25f)), peak(out, delay - ms(out.sampleRate, 25f), delay - ms(out.sampleRate, 5f)))
        // The repeat against the hit: the first repeat's first 20 ms over the hit's first 20 ms, rms.
        fun repeat(out: Snip, delay: Int) = db(rms(out, delay, delay + ms(out.sampleRate, 20f)), rms(out, 0, ms(out.sampleRate, 20f)))
        for (amount in listOf(1f, 0.7f)) for (mix in listOf(0.5f, 0.75f, 1f)) {
            val m = at(mix, amount)
            val delay = Echo.delayFrames(m.getValue("TIME"), 44_100)
            println(String.format(Locale.ROOT, "DELAY probe AMT %.0f, HEAD recipe MIX %.2f: jangle pick %.1f dB, snare repeat %.1f dB, choir gap HEAD 0 %.1f / HEAD 1 %.1f dB",
                amount * 100, mix, pick(Echo.process(jangle, m), delay), repeat(Echo.process(snare, m), delay),
                gap(Echo.process(choir, m + ("HEAD" to 0f)), m), gap(Echo.process(choir, m), m)))
            for ((name, s) in linkedMapOf("choir" to choir, "chug" to chug, "jangle" to jangle, "snare" to snare)) {
                println(String.format(Locale.ROOT, "DELAY gap AMT %.0f, HEAD recipe MIX %.2f, %s: HEAD 0 %.1f dB, HEAD 1 %.1f dB",
                    amount * 100, mix, name, gap(Echo.process(s, m + ("HEAD" to 0f)), m), gap(Echo.process(s, m), m)))
            }
        }
        val first = at(0.75f, 0.7f)
        val delay = Echo.delayFrames(first.getValue("TIME"), 44_100)
        assertEquals(3.5, pick(Echo.process(jangle, first), delay), 0.5, "the jangle's pick at AMT 70 (spec: 3.5 dB)")
        assertEquals(-7.8, repeat(Echo.process(snare, first), delay), 0.5, "the snare's repeat at AMT 70 (spec: -7.8 dB)")
    }
}

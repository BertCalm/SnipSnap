package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROOM's own claims (docs/superpowers/specs/2026-10-31-room-section-design-brief.md: the macro contract and
 * "Risks"). The rack's shared contract - deterministic, finite, peak-matched, stereo intact - is FxTest's,
 * and covers ROOM through FxChain.SECTIONS.
 */
class RoomTest {

    private val rate = 44_100
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val kick = Thump.render(ThumpVoice.KICK)
    private val hat = Thump.render(ThumpVoice.HAT_CLOSED)

    // ---------- helpers ----------

    private fun energy(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until minOf(to, x.size)) s += x[i].toDouble() * x[i]
        return s
    }

    /** The share of a render's energy after its dry source has ended: how much room you hear. */
    private fun roomShare(out: Snip, dry: Snip) = energy(out.samples, dry.frameCount, out.frameCount) / energy(out.samples, 0, out.frameCount)

    private fun peak(x: FloatArray, from: Int, to: Int): Float {
        var p = 0f
        for (i in from until minOf(to, x.size)) p = maxOf(p, abs(x[i]))
        return p
    }

    /** RT60 from the Schroeder backward integral, fitted between -5 and -25 dB. */
    private fun rt60(ir: FloatArray): Double {
        val e = DoubleArray(ir.size)
        var acc = 0.0
        for (i in ir.indices.reversed()) { acc += ir[i].toDouble() * ir[i]; e[i] = acc }
        val db = DoubleArray(e.size) { 10 * log10(e[it] / e[0] + 1e-30) }
        val i5 = db.indexOfFirst { it < -5 }
        val i25 = db.indexOfFirst { it < -25 }
        return -60.0 / ((db[i25] - db[i5]) / ((i25 - i5).toDouble() / rate))
    }

    private fun bloomOfClick(macros: Map<String, Float>, clamp: Float = Room.JOT_CLAMP): FloatArray {
        val click = FloatArray((1.2f * rate).toInt())
        click[0] = 1f
        return Room.bloom(click, Room.plan(Room.settled(macros), rate, clamp), rate)
    }

    /** Share of power above [hz] in [n] samples from [from] (zero-padded, no window). */
    private fun shareAbove(x: FloatArray, hz: Float, from: Int, n: Int): Double {
        var size = 1
        while (size < n) size = size shl 1
        val re = FloatArray(size) { if (it < n) x.getOrElse(from + it) { 0f } else 0f }
        val im = FloatArray(size)
        Fft.forward(re, im)
        var above = 0.0
        var all = 0.0
        for (b in 1 until size / 2) {
            val p = re[b].toDouble() * re[b] + im[b].toDouble() * im[b]
            all += p
            if (b.toFloat() * rate / size >= hz) above += p
        }
        return above / all.coerceAtLeast(1e-30)
    }

    // ---------- the contract ----------

    @Test
    fun `the macros are SIZE, WALLS and DISTANCE, and DISTANCE 0 is no room`() {
        assertEquals(listOf("SIZE", "WALLS", "DISTANCE"), Room.MACROS.map { it.name })
        assertEquals(listOf(0.3f, 0.5f, 0.6f), Room.MACROS.map { it.default })
        assertEquals(listOf(0f, 0f, 0f), Room.MACROS.map { it.neutral })
        assertContentEquals(snare.samples, Room.process(snare, mapOf("DISTANCE" to 0f)).samples, "DISTANCE 0 is not a bypass")
    }

    @Test
    fun `an unknown key is ignored and a non-finite macro falls back to its default`() {
        val defaults = Room.process(snare).samples
        assertContentEquals(defaults, Room.process(snare, mapOf("SPEED" to 0.9f)).samples, "an unknown key moved the room")
        assertContentEquals(defaults, Room.process(snare, mapOf("SIZE" to Float.NaN)).samples, "a NaN SIZE was not settled")
    }

    @Test
    fun `the tail stays within the rack's budget and fades out`() {
        for (m in listOf(Room.defaults(), mapOf("SIZE" to 1f, "WALLS" to 1f, "DISTANCE" to 1f))) {
            val out = Room.process(snare, m)
            assertTrue(out.frameCount >= snare.frameCount, "the room cut the hit short")
            assertTrue(out.frameCount <= snare.frameCount + (Room.MAX_TAIL_SECONDS * rate).toInt(), "$m: tail over budget")
            assertTrue(abs(out.samples.last()) < 1e-4f, "$m: the tail stops on a click: ${out.samples.last()}")
        }
    }

    @Test
    fun `the room never grows - its last tenth of a second is quieter than its first`() {
        // Independent windows: the tail's first 100 ms against its last 100 ms, not a peak over the whole render.
        for (m in listOf(Room.defaults(), mapOf("SIZE" to 1f, "WALLS" to 1f, "DISTANCE" to 1f), mapOf("WALLS" to 1f, "SIZE" to 0.55f))) {
            val out = Room.process(snare, m)
            val start = snare.frameCount
            val window = (0.1f * rate).toInt()
            val early = peak(out.samples, start, start + window)
            val late = peak(out.samples, out.frameCount - window, out.frameCount)
            assertTrue(late < early * 0.5f, "$m: the tail grew: first $early, last $late")
        }
    }

    // ---------- the macros ----------

    @Test
    fun `SIZE makes the room bigger - longer, later and more of it`() {
        val sizes = listOf(0.3f, 0.55f, 0.78f, 1f)
        val plans = sizes.map { Room.plan(Room.settled(mapOf("SIZE" to it)), rate) }
        val shares = sizes.map { roomShare(Room.process(snare, mapOf("SIZE" to it)), snare) }
        val tails = sizes.map { Room.process(snare, mapOf("SIZE" to it)).frameCount }
        println("ROOM SIZE ${sizes}: bloom starts ${plans.map { it.bloomStart }} samples, room share ${shares.map { "%.4f".format(it) }}, frames $tails")
        // Measured: bloom 416, 722, 1273, 2114 samples; share 0.0013, 0.0084, 0.0219, 0.0302.
        for (i in 1 until sizes.size) {
            assertTrue(plans[i].bloomStart > plans[i - 1].bloomStart, "the bloom did not start later in a bigger room")
            assertTrue(shares[i] > shares[i - 1], "a bigger room was not more room: $shares")
            assertTrue(tails[i] >= tails[i - 1], "a bigger room had a shorter tail: $tails")
        }
    }

    @Test
    fun `WALLS sets the decay, soft is short and hard is long`() {
        val measured = listOf(0f, 0.5f, 1f).map { rt60(bloomOfClick(mapOf("WALLS" to it))) }
        println("ROOM WALLS 0 / 0.5 / 1, measured RT60: ${measured.map { "%.3f".format(it) }}")
        // Measured: 0.134, 0.626, 0.894 s.
        for (i in 1 until measured.size) assertTrue(measured[i] > measured[i - 1], "harder walls did not ring longer: $measured")
        assertTrue(measured.last() < Room.MAX_TAIL_SECONDS, "the hardest room's decay is past the tail budget: ${measured.last()}")
    }

    @Test
    fun `the decay lands on Sabine's in small and middle rooms`() {
        // RT_CALIBRATION: the network's measured decay ran about 0.77 of what it was told. Halls land under
        // their 0.9 s target (about 0.7 s); disclosed in the brief, not asserted.
        for (m in listOf(mapOf("SIZE" to 0.07f), mapOf("SIZE" to 0.3f), mapOf("WALLS" to 1f))) {
            val want = Room.plan(Room.settled(m), rate).rt60
            val got = rt60(bloomOfClick(m))
            assertTrue(abs(got - want) < 0.1 * want, "$m: measured RT60 $got against Sabine's $want")
        }
    }

    @Test
    fun `DISTANCE moves the mic back - more room, less bass and less top on the drum`() {
        val distances = listOf(0.05f, 0.35f, 0.6f, 0.8f, 1f)
        val plans = distances.map { Room.plan(Room.settled(mapOf("DISTANCE" to it)), rate) }
        val shares = distances.map { roomShare(Room.process(snare, mapOf("DISTANCE" to it)), snare) }
        println("ROOM DISTANCE $distances: mic ${plans.map { "%.2f".format(it.micMetres) }} m, bass lift ${plans.map { it.proximityDb }}, " +
            "top ${plans.map { it.darkenDb }}, room share ${shares.map { "%.4f".format(it) }}")
        for (i in 1 until distances.size) {
            assertTrue(plans[i].micMetres > plans[i - 1].micMetres, "the mic did not move back")
            assertTrue(plans[i].proximityDb <= plans[i - 1].proximityDb, "a farther mic got more bass")
            assertTrue(plans[i].darkenDb < plans[i - 1].darkenDb, "a farther mic did not lose top")
            assertTrue(shares[i] > shares[i - 1], "a farther mic heard less room: $shares")
        }
        // On a hat, the top of the first 10 ms falls as the mic moves back (the room is peak-matched, so this is tone).
        val close = shareAbove(Room.process(hat, mapOf("DISTANCE" to 0.05f)).samples, 6_000f, 0, (0.01f * rate).toInt())
        val far = shareAbove(Room.process(hat, mapOf("DISTANCE" to 1f)).samples, 6_000f, 0, (0.01f * rate).toInt())
        println("ROOM DISTANCE on the hat, share over 6 kHz in the first 10 ms: close %.3f, far %.3f".format(close, far))
        // Measured: 0.882 close, 0.715 far (0.81 of it).
        assertTrue(far < close * 0.9, "the far mic's hat is as bright as the close one's: $close -> $far")
    }

    // ---------- the risks ----------

    @Test
    fun `the clamp on Jot's coefficient is what keeps a soft hall stable`() {
        val softHall = mapOf("SIZE" to 1f, "WALLS" to 0f)
        val clamped = bloomOfClick(softHall)
        assertTrue(clamped.all { it.isFinite() }, "the clamped soft hall is not finite")
        assertTrue(peak(clamped, clamped.size - rate / 10, clamped.size) < peak(clamped, 0, rate / 10) * 1e-3f, "the clamped soft hall does not decay")
        val unclamped = bloomOfClick(softHall, clamp = 10f)
        assertTrue(unclamped.any { !it.isFinite() || abs(it) > 1e6f }, "without the clamp the soft hall still behaves: the clamp guards nothing")
    }

    @Test
    fun `drums stay what they are in the room`() {
        assertEquals(DrumClass.SNARE, Classifier.classify(Room.process(snare)).drumClass)
        assertEquals(DrumClass.KICK, Classifier.classify(Room.process(kick)).drumClass)
    }

    @Test
    fun `a pad renders in tens of milliseconds`() {
        Room.process(snare) // warm the JIT
        val t0 = System.nanoTime()
        repeat(5) { Room.process(snare, mapOf("SIZE" to 1f, "DISTANCE" to 1f)) }
        val ms = (System.nanoTime() - t0) / 5e6
        println("ROOM render, THUMP snare in a hall: %.1f ms".format(ms))
        // Measured about 45 ms on this container. The bound is loose on purpose: CI machines vary.
        assertTrue(ms < 1_000, "ROOM took $ms ms for one pad")
    }

    @Test
    fun `an empty or silent pad passes through`() {
        val silent = Snip(FloatArray(1_000), 1, rate)
        assertContentEquals(silent.samples, Room.process(silent).samples)
        assertEquals(0, Room.process(Snip(FloatArray(0), 1, rate)).frameCount)
    }
}

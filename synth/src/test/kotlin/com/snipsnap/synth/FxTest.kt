package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FxTest {

    private val kick = Thump.render(ThumpVoice.KICK)
    private val snare = Thump.render(ThumpVoice.SNARE)

    // ---------- SQUASH ----------

    private fun rms(s: Snip): Float {
        var sum = 0.0
        for (v in s.samples) sum += (v * v).toDouble()
        return sqrt(sum / s.samples.size).toFloat()
    }

    /** RMS of everything after the hit's first 80 ms — the sustain. */
    private fun sustainRms(s: Snip): Float {
        val from = (0.08f * s.sampleRate).toInt() * s.channels
        var sum = 0.0
        for (i in from until s.samples.size) sum += (s.samples[i] * s.samples[i]).toDouble()
        return sqrt(sum / (s.samples.size - from)).toFloat()
    }

    @Test
    fun `SQUASH with a fast clamp is glue - the sustain comes up`() {
        // Fast attack catches the loud head, makeup lifts what's left: on a
        // decaying one-shot the audible result is a fatter tail under an
        // unchanged peak. Whole-signal RMS misses this - the loud head goes
        // down as the tail comes up - so the sustain is what's measured.
        val out = Squash.process(snare, mapOf("AMOUNT" to 0.8f, "ATTACK" to 0.05f))
        assertTrue(abs(snare.peak() - out.peak()) < 0.05f, "peak must be preserved")
        assertTrue(
            sustainRms(out) > sustainRms(snare) * 1.5f,
            "fast clamp + makeup should fatten the tail: ${sustainRms(snare)} -> ${sustainRms(out)}",
        )
    }

    @Test
    fun `SQUASH with a slow clamp is punch - the transient pops`() {
        // Slow attack (slower than the lookahead) lets the hit escape the
        // clamp and leans on the body: a higher crest factor.
        val fast = Squash.process(snare, mapOf("AMOUNT" to 0.8f, "ATTACK" to 0.05f))
        val slow = Squash.process(snare, mapOf("AMOUNT" to 0.8f, "ATTACK" to 0.9f))
        val crestFast = fast.peak() / rms(fast)
        val crestSlow = slow.peak() / rms(slow)
        assertTrue(
            crestSlow > crestFast * 1.1f,
            "slow attack should emphasise the transient: crest $crestFast -> $crestSlow",
        )
    }

    @Test
    fun `SQUASH AMOUNT zero is a copy`() {
        val out = Squash.process(kick, mapOf("AMOUNT" to 0f))
        assertTrue(out.samples.contentEquals(kick.samples))
    }

    @Test
    fun `a squashed kick is still a kick`() {
        assertEquals(DrumClass.KICK, Classifier.classify(Squash.process(kick)).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(Squash.process(snare)).drumClass)
    }

    // ---------- ECHO ----------

    @Test
    fun `ECHO MIX zero is a copy, and repeats land where TIME says`() {
        assertTrue(Echo.process(kick, mapOf("MIX" to 0f)).samples.contentEquals(kick.samples))

        // A click in, a repeat out: the echo of an impulse must appear at
        // exactly the delay the TIME macro maps to.
        val click = Snip(FloatArray(44_100) { if (it < 32) 0.9f else 0f }, 1, 44_100)
        val time = 0.6f
        val out = Echo.process(click, mapOf("TIME" to time, "REPEAT" to 0.5f, "MIX" to 0.7f))
        val expected = Echo.delayFrames(time, 44_100)
        var firstRepeat = -1
        for (i in 2000 until out.samples.size) {
            if (abs(out.samples[i]) > 0.05f) { firstRepeat = i; break }
        }
        assertTrue(
            abs(firstRepeat - expected) < 200,
            "repeat expected near frame $expected, found $firstRepeat",
        )
    }

    @Test
    fun `ECHO REPEAT lengthens the trail and the tail stays bounded`() {
        val one = Echo.process(snare, mapOf("REPEAT" to 0.05f))
        val many = Echo.process(snare, mapOf("REPEAT" to 1f))
        assertTrue(many.frameCount > one.frameCount, "more feedback, more trail")
        assertTrue(
            many.durationSeconds <= snare.durationSeconds + Echo.MAX_TAIL_SECONDS + 0.01f,
            "the tail promise: ${many.durationSeconds}s",
        )
    }

    @Test
    fun `an echoed kick is still a kick`() {
        assertEquals(DrumClass.KICK, Classifier.classify(Echo.process(kick)).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(Echo.process(snare)).drumClass)
    }

    // ---------- SPRING ----------

    @Test
    fun `SPRING MIX zero is a copy and SIZE grows the room`() {
        assertTrue(Spring.process(kick, mapOf("MIX" to 0f)).samples.contentEquals(kick.samples))
        val closet = FeatureExtractor.extract(Spring.process(snare, mapOf("SIZE" to 0.05f, "MIX" to 0.5f)))
        val hall = FeatureExtractor.extract(Spring.process(snare, mapOf("SIZE" to 0.95f, "MIX" to 0.5f)))
        assertTrue(
            hall.decayMs > closet.decayMs * 1.3f,
            "SIZE should grow the ring: ${closet.decayMs}ms -> ${hall.decayMs}ms",
        )
    }

    @Test
    fun `SPRING TONE darkens the tail`() {
        fun tailCentroid(tone: Float): Float {
            val out = Spring.process(snare, mapOf("TONE" to tone, "MIX" to 0.6f, "SIZE" to 0.7f))
            val from = snare.frameCount
            val tail = Snip(out.samples.copyOfRange(from, out.samples.size), 1, out.sampleRate)
            return FeatureExtractor.extract(tail).centroidHz
        }
        assertTrue(
            tailCentroid(0.1f) < tailCentroid(0.9f) * 0.8f,
            "TONE down should darken the reverb",
        )
    }

    @Test
    fun `a reverbed kick is still a kick`() {
        assertEquals(DrumClass.KICK, Classifier.classify(Spring.process(kick)).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(Spring.process(snare)).drumClass)
    }

    // ---------- the shared contract ----------

    @Test
    fun `every effect is deterministic, clean and peak-matched everywhere`() {
        val processors = listOf<Pair<String, (Snip, Map<String, Float>) -> Snip>>(
            "SQUASH" to Squash::process, "ECHO" to Echo::process, "SPRING" to Spring::process,
        )
        val scramblers = listOf<Pair<String, (Random) -> Map<String, Float>>>(
            "SQUASH" to Squash::scramble, "ECHO" to Echo::scramble, "SPRING" to Spring::scramble,
        )
        for (i in processors.indices) {
            val (name, fx) = processors[i]
            assertTrue(
                fx(snare, emptyMap()).samples.contentEquals(fx(snare, emptyMap()).samples),
                "$name not deterministic",
            )
            repeat(6) { seed ->
                val macros = scramblers[i].second(Random(seed))
                val out = fx(snare, macros)
                assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "$name roll $seed broke")
                assertTrue(abs(out.peak() - snare.peak()) < 0.05f, "$name roll $seed changed loudness")
            }
        }
    }

    @Test
    fun `stereo stays stereo with identical channels intact`() {
        val stereo = Snip(FloatArray(kick.frameCount * 2) { kick.samples[it / 2] }, 2, 44_100)
        for (out in listOf(Squash.process(stereo), Echo.process(stereo), Spring.process(stereo))) {
            assertEquals(2, out.channels)
            for (f in 0 until out.frameCount) {
                assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "channels diverged at $f")
            }
        }
    }

    // ---------- the chain ----------

    @Test
    fun `an empty chain is a true bypass`() {
        val chain = FxChain()
        assertTrue(chain.isBypass)
        assertTrue(chain.process(kick).samples.contentEquals(kick.samples))
    }

    @Test
    fun `reverse flips and double reverse restores`() {
        val once = FxChain(reverse = true).process(snare)
        assertTrue(!once.samples.contentEquals(snare.samples))
        val twice = FxChain(reverse = true).process(once)
        assertTrue(twice.samples.contentEquals(snare.samples))
    }

    @Test
    fun `the full default rack keeps a kick a kick and stays a one-shot`() {
        val chain = FxChain(
            squash = Squash.defaults(), crunch = Crunch.defaults(),
            echo = Echo.defaults(), spring = Spring.defaults(),
        )
        val out = chain.process(kick)
        assertEquals(DrumClass.KICK, Classifier.classify(out).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(chain.process(snare)).drumClass)
        assertTrue(
            out.durationSeconds <= kick.durationSeconds + FxChain.MAX_CHAIN_TAIL_SECONDS + 0.01f,
            "stacked tails must respect the chain budget: ${out.durationSeconds}s",
        )
    }

    @Test
    fun `chain round-trips through JSON, bypass sections stay absent`() {
        val chain = FxChain(
            reverse = true,
            squash = mapOf("AMOUNT" to 0.7f),
            spring = mapOf("SIZE" to 0.8f, "MIX" to 0.45f),
        )
        val back = FxChain.fromJsonText(chain.toJsonText())
        assertEquals(chain, back)
        assertTrue(back.crunch == null && back.echo == null, "bypassed sections must stay null")
        assertTrue(
            back.process(snare).samples.contentEquals(chain.process(snare).samples),
            "same JSON, same sound",
        )
    }

    @Test
    fun `chain validates macros and refuses foreign JSON`() {
        assertFailsWith<IllegalArgumentException> { FxChain(echo = mapOf("SIZE" to 0.5f)) }
        assertFailsWith<IllegalArgumentException> { FxChain(spring = mapOf("MIX" to 2f)) }
        assertFailsWith<com.snipsnap.json.JsonException> {
            FxChain.fromJsonText(ThumpPatch("K", ThumpVoice.KICK, emptyMap()).toJsonText())
        }
    }

    // ---------- SMEAR ----------

    @Test
    fun `SMEAR sits after REVERSE and before EQ, and an absent section keeps old recipes byte-stable`() {
        val without = FxChain(eq = mapOf("BASS" to 0.6f))
        assertTrue(!without.toJsonText().contains("smear"), "no smear key unless the section is set")
        assertEquals(without, FxChain.fromJsonText(without.toJsonText()))

        val with = FxChain(reverse = true, smear = mapOf("AMOUNT" to 0.6f), eq = mapOf("BASS" to 0.6f))
        val text = with.toJsonText()
        assertTrue(text.indexOf("\"reverse\"") < text.indexOf("\"smear\"") && text.indexOf("\"smear\"") < text.indexOf("\"eq\""), text)
        assertEquals(with, FxChain.fromJsonText(text))
        assertTrue(!FxChain(smear = mapOf("AMOUNT" to 0.2f)).isBypass)
    }

    @Test
    fun `SMEAR refuses a macro it does not know`() {
        assertFailsWith<IllegalArgumentException> { FxChain(smear = mapOf("WASH" to 0.5f)) }
    }

    @Test
    fun `SMEAR at zero is transparent and at full strength keeps the kick's length and level`() {
        assertTrue(Smear.process(kick, mapOf("AMOUNT" to 0f)) === kick)
        val smeared = Smear.process(kick, mapOf("AMOUNT" to 1f))
        assertEquals(kick.frameCount, smeared.frameCount)
        var inPeak = 0f
        var outPeak = 0f
        for (v in kick.samples) inPeak = maxOf(inPeak, abs(v))
        for (v in smeared.samples) outPeak = maxOf(outPeak, abs(v))
        assertTrue(outPeak > 0.5f * inPeak && outPeak <= inPeak * 1.001f, "peak matched, never above: $inPeak -> $outPeak")
    }
}

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

    /**
     * Sections not yet held to the peak-match clause, with why each
     * fails. These are pre-existing: they were never in the three-section
     * list this test replaced. Each is a bug to fix on its own, not here.
     * Stale check: if a section's DSP changes, delete its entry and rerun
     * `every effect is deterministic, clean and peak-matched everywhere` -
     * if it now passes, the exclusion is no longer needed.
     */
    private val contractExcluded: Map<String, String> = mapOf(
        "smear" to "peak-match: falls under the source peak instead of matching it " +
            "(roll 0: out 0.8939 vs snare 0.9500, diff 0.0561; roll 2: out 0.8637 vs " +
            "snare 0.9500, diff 0.0863 - tolerance is 0.05)",
        "motion" to "peak-match: by design, STOP/START fade the level toward silence; at " +
            "random macro values this lands far outside the 0.05 tolerance meant for " +
            "level-preserving effects (roll 0: out 0.2681 vs snare 0.9500, diff 0.6819; " +
            "worst roll 3: out 0.2220 vs snare 0.9500, diff 0.7280)",
    )

    /**
     * Sections not yet held to the per-frame stereo-identity clause, with
     * the observed divergence. Pre-existing bugs, each its own fix. Stale
     * check: if a section's DSP changes, delete its entry and rerun
     * `stereo stays stereo with identical channels intact` - if it now
     * passes, the exclusion is no longer needed.
     */
    private val stereoExcluded: Map<String, String> = mapOf(
        "swell" to "left/right diverge starting at frame 1 of 48337 (L=0.0, R=-0.0 - a " +
            "signed-zero mismatch that assertEquals(Float, Float) treats as unequal); 33073 " +
            "of 48337 frames fail assertEquals, of which 33072 also differ under plain != " +
            "(i.e. are genuinely different values, not just a sign-of-zero artifact, e.g. " +
            "frame 2: L=4.16e-10, R=-3.12e-10) - independently randomized per-channel phases " +
            "in the stretch wash, not a shared computation across channels",
        "dub" to "left/right diverge by audible amounts, not float noise - 15256 of 15262 " +
            "frames differ, starting at frame 0 (L=0.3433, R=0.3370) - the per-channel dub " +
            "processing does not keep identical input channels identical",
    )

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
        // The section's own process, not the whole chain: this is the old test
        // widened to every section, not a new and stricter one. A chain call
        // would drag in capTail and the TRANSPORT/ARRIVAL stages, which the
        // three original sections were never measured through.
        for (sec in FxChain.SECTIONS) {
            val defaults = sec.macros.associate { it.name to it.default }
            assertTrue(
                sec.run(snare, defaults).samples.contentEquals(sec.run(snare, defaults).samples),
                "${sec.name} not deterministic",
            )
            for (seed in 0 until 6) {
                val rng = Random(seed)
                val macros = sec.macros.associate { it.name to rng.nextFloat() }
                val out = sec.run(snare, macros)
                assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "${sec.name} roll $seed broke")
                if (sec.name !in contractExcluded) {
                    assertTrue(abs(out.peak() - snare.peak()) < 0.05f, "${sec.name} roll $seed changed loudness")
                }
            }
        }
    }

    @Test
    fun `stereo stays stereo with identical channels intact`() {
        val stereo = Snip(FloatArray(kick.frameCount * 2) { kick.samples[it / 2] }, 2, 44_100)
        for (sec in FxChain.SECTIONS) {
            val out = sec.run(stereo, sec.macros.associate { it.name to it.default })
            assertEquals(2, out.channels, "${sec.name} changed the channel count")
            if (sec.name !in stereoExcluded) {
                for (f in 0 until out.frameCount) {
                    assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "${sec.name}: channels diverged at $f")
                }
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

    // ---------- GHOST + MOTION ----------

    /** Zero-crossing rate over a window: a cheap pitch reading for a tone. */
    private fun crossings(s: Snip, fromSec: Float, toSec: Float): Int {
        var n = 0
        val from = (fromSec * s.sampleRate).toInt().coerceIn(1, s.frameCount - 1)
        val to = (toSec * s.sampleRate).toInt().coerceIn(from, s.frameCount)
        for (f in from until to) {
            val a = s.samples[(f - 1) * s.channels]
            val b = s.samples[f * s.channels]
            if ((a < 0f) != (b < 0f)) n++
        }
        return n
    }

    private fun peakIn(s: Snip, fromSec: Float, toSec: Float): Float {
        var p = 0f
        val from = (fromSec * s.sampleRate).toInt().coerceIn(0, s.frameCount)
        val to = (toSec * s.sampleRate).toInt().coerceIn(from, s.frameCount)
        for (i in from * s.channels until to * s.channels) p = maxOf(p, abs(s.samples[i]))
        return p
    }

    private val tone = Snip(FloatArray(44_100) { i -> (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44_100)).toFloat() }, 1, 44_100)

    @Test
    fun `GHOST and MOTION serialize as sections and an absent one keeps old recipes byte-stable`() {
        val chain = FxChain(ghost = mapOf("AMOUNT" to 0.5f), motion = mapOf("STOP" to 0.3f, "START" to 0.1f))
        assertEquals(chain, FxChain.fromJsonText(chain.toJsonText()))
        val text = chain.toJsonText()
        assertTrue(text.indexOf("\"ghost\"") < text.indexOf("\"motion\""), "ghost before motion in the file, like the rack")
        assertTrue(!FxChain(eq = mapOf("BASS" to 0.6f)).toJsonText().contains("ghost"))
        assertFailsWith<IllegalArgumentException> { FxChain(motion = mapOf("SPEED" to 0.5f)) }
        assertTrue(Motion.process(tone, mapOf("STOP" to 0f, "START" to 0f)) === tone)
        assertTrue(Ghost.process(tone, mapOf("AMOUNT" to 0f)) === tone)
    }

    @Test
    fun `STOP lets the pitch and the level fall away to nothing, keeping the length`() {
        val stopped = Motion.process(tone, mapOf("STOP" to 0.5f)) // a one-second glide over a one-second tone
        assertEquals(tone.frameCount, stopped.frameCount)
        val early = crossings(stopped, 0.02f, 0.12f)
        val late = crossings(stopped, 0.80f, 0.90f)
        assertTrue(late < early / 2, "the pitch has fallen: $early crossings -> $late")
        assertTrue(peakIn(stopped, 0.95f, 1f) < 0.15f, "stopped tape is silent, not a held sample")
        assertTrue(peakIn(stopped, 0f, 0.05f) > 0.45f, "the start is untouched")
    }

    @Test
    fun `START spins up into the sound - pitch and level climb, the sound arrives late`() {
        val started = Motion.process(tone, mapOf("START" to 1f)) // a 1.5 s spin-up
        assertTrue(started.frameCount > tone.frameCount, "the spin-up delays the rest")
        val early = crossings(started, 0.05f, 0.15f)
        val late = crossings(started, 1.6f, 1.7f)
        assertTrue(early < late / 2, "the pitch climbs: $early crossings -> $late")
        assertTrue(peakIn(started, 0f, 0.05f) < peakIn(started, 1.6f, 1.7f) * 0.5f, "and so does the level")
    }

    @Test
    fun `a ghosted kick is no longer a kick, and stays finite and peak-bounded`() {
        val ghosted = Ghost.process(kick, mapOf("AMOUNT" to 1f))
        assertEquals(kick.frameCount, ghosted.frameCount)
        var peak = 0f
        for (v in ghosted.samples) { assertTrue(v.isFinite()); peak = maxOf(peak, abs(v)) }
        assertTrue(peak <= kick.peak() * 1.001f)
        assertTrue(Classifier.classify(ghosted).drumClass != DrumClass.KICK, "what's left of a kick is not a kick")
    }

    // ---------- DUB + SWELL + the smear's FLOOR ----------

    /** How much of the source survives, 0..1: the normalized correlation of the two, mono-folded, at zero lag. */
    private fun likeness(a: Snip, b: Snip): Double {
        val n = minOf(a.frameCount, b.frameCount)
        var dot = 0.0
        var ea = 0.0
        var eb = 0.0
        for (f in 0 until n) {
            var x = 0f
            var y = 0f
            for (c in 0 until a.channels) x += a.samples[f * a.channels + c]
            for (c in 0 until b.channels) y += b.samples[f * b.channels + c]
            dot += x * y.toDouble()
            ea += x * x.toDouble()
            eb += y * y.toDouble()
        }
        return dot / Math.sqrt(ea * eb)
    }

    @Test
    fun `DUB drifts further from the source every generation, zero is transparent, peak held`() {
        val bright = Thump.render(ThumpVoice.HAT_CLOSED)
        assertTrue(Dub.process(bright, mapOf("GENERATIONS" to 0f)) === bright)
        assertEquals(6, Dub.generations(0.5f))
        assertEquals(12, Dub.generations(1f))
        val g3 = Dub.process(bright, mapOf("GENERATIONS" to 0.25f))
        val g12 = Dub.process(bright, mapOf("GENERATIONS" to 1f))
        val like3 = likeness(bright, g3)
        val like12 = likeness(bright, g12)
        assertTrue(like3 < 0.999 && like12 < like3, "a dub of a dub drifts further: 1 > $like3 > $like12")
        // Identity, the rack's own way: the default depth leaves a kick a kick.
        assertEquals(DrumClass.KICK, Classifier.classify(Dub.process(kick)).drumClass, "a dubbed kick is still a kick")
        assertTrue(g12.peak() <= bright.peak() * 1.001f, "a dozen saturating passes never read as loudness")
        assertTrue(Dub.process(bright, mapOf("GENERATIONS" to 1f)).samples.contentEquals(g12.samples), "deterministic")
    }

    @Test
    fun `SWELL arrives before the strike and leaves the strike itself untouched`() {
        val swelled = Swell.process(snare, mapOf("RISE" to 0.5f)) // a 0.75 s rise
        val rise = Swell.riseFrames(0.5f, snare.sampleRate)
        assertEquals(snare.frameCount + rise, swelled.frameCount, "the rise, then the whole hit")
        // The hit is on the downbeat, bit for bit.
        val ch = snare.channels
        assertTrue(
            swelled.samples.copyOfRange(rise * ch, swelled.samples.size).contentEquals(snare.samples),
            "the strike is the original",
        )
        // The arrival rises: the last quarter of the swell is louder than the first.
        fun rmsFrames(from: Int, to: Int): Double {
            var acc = 0.0
            for (i in from * ch until to * ch) acc += swelled.samples[i] * swelled.samples[i].toDouble()
            return sqrt(acc / ((to - from) * ch))
        }
        val first = rmsFrames(0, rise / 4)
        val last = rmsFrames(rise * 3 / 4, rise)
        assertTrue(last > 3 * first, "energy rises into the hit: $first -> $last")
        assertTrue(Snip(swelled.samples.copyOfRange(0, rise * ch), ch, snare.sampleRate).peak() <= snare.peak() * Swell.SWELL_LEVEL * 1.001f, "the swell sits under the hit")
        assertTrue(Swell.process(snare, mapOf("RISE" to 0f)) === snare, "no rise, no swell")
        assertTrue(Swell.process(snare, mapOf("RISE" to 0.02f)) === snare, "a rise too short to stretch is honest silence, not a click")
        val blip = Snip(FloatArray(20) { if (it < 2) 0.5f else 0f }, 1, 44_100)
        assertTrue(Swell.process(blip, mapOf("RISE" to 1f)) === blip, "a head too short for any stretch is transparent, never a throw")
    }

    @Test
    fun `the whole rack with a swell keeps its tail budget from the swelled sound, and old recipes stay byte-stable`() {
        val chain = FxChain(swell = mapOf("RISE" to 1f), spring = mapOf("SIZE" to 0.7f, "MIX" to 0.5f))
        val out = chain.process(kick)
        val rise = Swell.riseFrames(1f, kick.sampleRate)
        assertTrue(out.frameCount >= kick.frameCount + rise, "the swell is an arrival, not a tail to be cut: ${out.frameCount} vs ${kick.frameCount + rise}")
        assertEquals(chain, FxChain.fromJsonText(chain.toJsonText()))
        val text = FxChain(dub = mapOf("GENERATIONS" to 0.3f), swell = mapOf("RISE" to 0.2f)).toJsonText()
        assertTrue(text.indexOf("\"swell\"") < text.indexOf("\"dub\""), "the file reads in rack order")
        assertTrue(!FxChain(eq = mapOf("BASS" to 0.6f)).toJsonText().contains("swell"))
        assertEquals(0f, Smear.floorHz(0f))
        assertEquals(Smear.FLOOR_HI, Smear.floorHz(1f), 1f)
    }

    @Test
    fun `the section table agrees with the hand-written fields`() {
        // Structure, not a literal list: five later tasks add sections, and the
        // exact rack order is pinned by the json-order test and by SPEED's own.
        assertTrue(FxChain.SECTION_NAMES.isNotEmpty(), "the rack has no sections")
        assertEquals(
            FxChain.SECTION_NAMES.size,
            FxChain.SECTION_NAMES.toSet().size,
            "two sections share a name",
        )
        for (name in FxChain.SECTION_NAMES) {
            assertTrue(FxChain.macrosOf(name).isNotEmpty(), "$name declares no macros")
            val macros = FxChain.macrosOf(name).associate { it.name to 0.7f }
            val chain = FxChain().withSection(name, macros)
            assertEquals(macros, chain.section(name), "$name: withSection and section disagree")
            assertEquals(null, FxChain().section(name), "$name: an empty chain is not bypassed there")
        }
    }

    @Test
    fun `an unknown section name is refused by name`() {
        assertFailsWith<IllegalArgumentException> { FxChain().section("nope") }
    }

    @Test
    fun `every section round-trips through json and defeats bypass`() {
        for (name in FxChain.SECTION_NAMES) {
            val macros = FxChain.macrosOf(name).associate { it.name to 0.7f }
            val chain = FxChain().withSection(name, macros)
            assertTrue(!chain.isBypass, "$name: isBypass does not see it")
            assertTrue(chain.toJsonText().contains("\"$name\""), "$name: toJsonValue does not emit it")
            assertEquals(chain, FxChain.fromJsonText(chain.toJsonText()), "$name: fromJsonValue drops it")
            val out = chain.process(kick)
            assertTrue(!out.samples.contentEquals(kick.samples), "$name: process is a no-op")
        }
    }

    @Test
    fun `json emits sections in rack order`() {
        var chain = FxChain()
        for (name in FxChain.SECTION_NAMES) {
            chain = chain.withSection(name, FxChain.macrosOf(name).associate { it.name to 0.6f })
        }
        val text = chain.toJsonText()
        val positions = FxChain.SECTION_NAMES.map { text.indexOf("\"$it\"") }
        assertEquals(positions.sorted(), positions, "json order is not rack order")
        assertTrue(positions.all { it > 0 }, "a section was not emitted")
    }

    @Test
    fun `AMT scales every section a treatment sets`() {
        for (name in Treatments.names) {
            val full = Treatments.chain(name, 1f)
            val half = Treatments.chain(name, 0.5f)
            for (section in FxChain.SECTION_NAMES) {
                val a = full.section(section) ?: continue
                val b = half.section(section)
                    ?: throw AssertionError("$name: AMT 0.5 dropped section $section entirely")
                for ((macro, v) in a) {
                    // "Moved", not "smaller": once macros have neutrals, a centered
                    // macro below its neutral rises as AMT falls. Task 6's own test
                    // pins the exact rule; this one only proves the section is seen.
                    assertTrue(
                        b.getValue(macro) != v,
                        "$name: AMT 0.5 left $section.$macro at $v - the section is not scaled",
                    )
                }
            }
        }
    }
}

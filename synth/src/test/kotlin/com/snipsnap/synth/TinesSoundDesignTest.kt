package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TINES' sound-design round - [ThumpSoundDesignTest]'s promises for the FM
 * engine: every new macro's default is the constant it replaced, each does
 * what its comment in `Tines.kt` says, and the voices keep TINES' own
 * contract at both ends of every one ([TinesTest]: never the kick slot,
 * never the loop shelf, always a one-shot).
 */
class TinesSoundDesignTest {

    private val added = mapOf(
        TinesVoice.BELL to listOf("BITE", "CLANG"),
        TinesVoice.CHIME to listOf("BITE", "RATIO"),
        TinesVoice.BLOCK to listOf("BITE", "RATIO"),
        TinesVoice.ZAP to listOf("BITE", "BEND", "RATIO"),
        TinesVoice.TOY to listOf("BITE", "RATIO", "SHAPE"),
        TinesVoice.KALIMBA to listOf("BITE", "TICK"),
    )

    private fun render(voice: TinesVoice, vararg macros: Pair<String, Float>) = Tines.render(voice, macros.toMap())
    private fun features(voice: TinesVoice, vararg macros: Pair<String, Float>) = FeatureExtractor.extract(render(voice, *macros))

    // ---------- the default is the old constant, exactly ----------

    @Test
    fun `every new macro sits at a default a missing key renders identically to`() {
        for ((voice, names) in added) {
            val bare = render(voice).samples
            val explicit = Tines.render(voice, names.associateWith { n -> Tines.defaults(voice).getValue(n) }).samples
            assertTrue(bare.contentEquals(explicit), "$voice: spelling out $names at their defaults changed the render")
        }
    }

    @Test
    fun `a newly opened RATIO defaults onto the ratio its voice used to hardcode`() {
        // CHIME 3.5, BLOCK 1.4, ZAP 2.7, TOY 2 - each default lands on its
        // own entry of RATIOS, and only there.
        for ((voice, ratio) in listOf(
            TinesVoice.CHIME to 3.5f, TinesVoice.BLOCK to 1.4f, TinesVoice.ZAP to 2.7f, TinesVoice.TOY to 2f,
        )) {
            val default = Tines.defaults(voice).getValue("RATIO")
            val index = (default * (Tines.RATIOS.size - 1)).toInt()
            assertTrue(Tines.RATIOS[index] == ratio, "$voice RATIO default $default snaps to ${Tines.RATIOS[index]}, not $ratio")
        }
    }

    // ---------- each macro does what it says ----------

    @Test
    fun `every new macro changes the render at both ends of its travel`() {
        for ((voice, names) in added) for (name in names) {
            val default = Tines.defaults(voice).getValue(name)
            val mid = render(voice, name to default).samples
            for (end in floatArrayOf(0f, 1f)) {
                if (end == default) continue
                assertTrue(!render(voice, name to end).samples.contentEquals(mid), "$voice $name at $end renders the same as its default")
            }
        }
    }

    @Test
    fun `BITE low keeps the FM bright, high lets it settle pure`() {
        for (voice in TinesVoice.entries) {
            val growl = features(voice, "BITE" to 0f)
            val pure = features(voice, "BITE" to 1f)
            if (voice == TinesVoice.KALIMBA) {
                // Only the tongue's strike takes BITE; the bar's partials
                // keep their own, so the move is smaller but still one way.
                assertTrue(growl.centroidHz > pure.centroidHz, "KALIMBA BITE 0 centroid ${growl.centroidHz} vs BITE 1 ${pure.centroidHz}")
            } else {
                assertTrue(growl.highRatio > pure.highRatio * 3f, "$voice BITE 0 high ratio ${growl.highRatio} vs BITE 1 ${pure.highRatio}")
            }
        }
    }

    @Test
    fun `bell CLANG pushes the partner strike up and out`() {
        val fifth = features(TinesVoice.BELL, "CLANG" to 0f).centroidHz
        val gong = features(TinesVoice.BELL, "CLANG" to 1f).centroidHz
        assertTrue(gong > fifth * 1.05f, "CLANG 1 centroid $gong vs CLANG 0 $fifth")
    }

    @Test
    fun `RATIO runs from round to clanging on every voice that newly has it`() {
        for (voice in listOf(TinesVoice.CHIME, TinesVoice.BLOCK, TinesVoice.ZAP, TinesVoice.TOY)) {
            val round = features(voice, "RATIO" to 0f).highRatio
            val clang = features(voice, "RATIO" to 1f).highRatio
            assertTrue(clang > round * 3f, "$voice RATIO 1 high ratio $clang vs RATIO 0 $round")
        }
    }

    @Test
    fun `zap BEND slow keeps the pitch up, fast drops it at once`() {
        val slow = features(TinesVoice.ZAP, "BEND" to 0f).centroidHz
        val fast = features(TinesVoice.ZAP, "BEND" to 1f).centroidHz
        assertTrue(slow > fast * 2f, "BEND 0 centroid $slow vs BEND 1 $fast")
    }

    @Test
    fun `toy SHAPE steps the pitch instead of sliding it`() {
        // A pitch property, invisible to the spectral features: counted as
        // zero crossings per 10 ms window over the note's first 200 ms. A
        // square LFO parks the pitch at its two extremes, so the per-window
        // rates spread further from their mean than a sine's sweep does.
        fun spread(shape: Float): Double {
            val s = render(TinesVoice.TOY, "SHAPE" to shape, "BRIGHT" to 0f)
            val win = s.sampleRate / 100
            val rates = (0 until 20).map { w ->
                (w * win + 1 until (w + 1) * win).count { i -> (s.samples[i - 1] < 0f) != (s.samples[i] < 0f) }.toDouble()
            }
            val mean = rates.average()
            return rates.sumOf { (it - mean) * (it - mean) } / rates.size
        }
        val slide = spread(0f)
        val step = spread(1f)
        assertTrue(step > slide * 1.3, "SHAPE 1 crossing-rate variance $step vs SHAPE 0 $slide")
    }

    @Test
    fun `kalimba TICK is the nail at the onset`() {
        // A 3 ms click under a note already at full level: spectral
        // features over the onset barely register it (high ratio moved in
        // the third decimal), so this measures the click itself - what
        // TICK 1 adds over a clickless thumb in the first 4 ms, against
        // the note's own level there.
        val thumb = render(TinesVoice.KALIMBA, "TICK" to 0f)
        val nail = render(TinesVoice.KALIMBA, "TICK" to 1f)
        val n = (0.004f * thumb.sampleRate).toInt()
        fun rms(f: (Int) -> Float) = kotlin.math.sqrt((0 until n).sumOf { f(it).toDouble().let { v -> v * v } } / n)
        val click = rms { nail.samples[it] - thumb.samples[it] }
        val note = rms { thumb.samples[it] }
        assertTrue(click > note * 0.15, "TICK 1 adds $click RMS over a ${note} RMS onset")
    }

    // ---------- TINES' own contract, at both ends ----------

    @Test
    fun `no new macro lands a voice on the kick slot or the loop shelf`() {
        val failures = mutableListOf<String>()
        for ((voice, names) in added) for (name in names) for (end in floatArrayOf(0f, 1f)) {
            val snip = render(voice, name to end)
            val cls = Classifier.classify(snip).drumClass
            if (cls == DrumClass.KICK || cls == DrumClass.LOOP || cls == DrumClass.UNKNOWN) failures += "$voice $name=$end: $cls"
            if (snip.durationSeconds >= 1.5f) failures += "$voice $name=$end: ${snip.durationSeconds}s"
        }
        assertTrue(failures.isEmpty(), "new macros that break TINES' contract:\n${failures.joinToString("\n")}")
    }
}

package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Tests the accepted acoustic transfer, independently of event/solver behavior. */
class AccordAcousticsTest {
    @Test
    fun `the sparse force floor cannot change Float excitation samples`() {
        val unfloored = DoubleArray((AccordAcoustics.INTERNAL_RATE * .4).toInt())
        unfloored[0] = 1.0
        unfloored[10_000] = .3
        val floored = unfloored.copyOf()
        AccordAcoustics.filterExcitation(unfloored, tinyStateFloor = false)
        AccordAcoustics.filterExcitation(floored)
        assertContentEquals(FloatArray(unfloored.size) { unfloored[it].toFloat() },
            FloatArray(floored.size) { floored[it].toFloat() })
        assertTrue(unfloored.indices.maxOf { abs(unfloored[it] - floored[it]) } < 1e-94)
    }

    @Test
    fun `roughness prefix reuse and eviction cannot change rendered audio`() {
        val config = AccordAcoustics.configuration(AccordVoice.BRUSH, AccordAcousticProfile.BASE, 48, .75f)
        val pressure = DoubleArray(500) { 1.0 }
        fun render(seed: Long) = AccordAcoustics.renderConditioned(config, emptyList(), pressure,
            .25, seed, 0.0, 2.0).samples
        AccordAcoustics.clearRoughnessCache()
        val first = render(1)
        assertContentEquals(first, render(1))
        assertTrue(AccordAcoustics.roughnessCacheSnapshot().hits > 0)
        render(2)
        render(3)
        val snapshot = AccordAcoustics.roughnessCacheSnapshot()
        assertTrue(snapshot.entries <= 2 && snapshot.bytes <= 24L * 1024 * 1024)
        assertContentEquals(first, render(1))
    }

    @Test
    fun `approved C3 impulse responses retain the compensated Python transfer`() {
        // SciPy Double modal_ring(compensate=True), original accepted tables,
        // unit impulse, 176400 Hz. These are external numeric fixtures, not a
        // second copy of the Kotlin recurrence or the house output filters.
        val indices = intArrayOf(0, 1, 32, 173, 1024, 22050)
        val fixtures = listOf(
            Triple(AccordVoice.BRUSH, AccordAcousticProfile.BASE,
                doubleArrayOf(.007522352165283281, .015043476938093391, .24548554973031306,
                    1.016639382174524, -1.081919247277949, .3902028589187805)),
            Triple(AccordVoice.WOOD, AccordAcousticProfile.BASE,
                doubleArrayOf(.014945296505537952, .02988342934819211, .47931675596175405,
                    1.363115999226279, -.7178565556922398, .11457581304282387)),
            Triple(AccordVoice.TINE, AccordAcousticProfile.REFERENCE,
                doubleArrayOf(.028091183162700307, .05616133915985543, .8440086478338932,
                    .6895520334065199, -.9645904893890381, .25676785415431536)),
        )
        for ((voice, profile, expected) in fixtures) {
            val config = AccordAcoustics.configuration(voice, profile, 48, .75f)
            val actual = AccordAcoustics.freeResponse(config, .13)
            for (j in indices.indices) assertEquals(expected[j], actual[indices[j]], 1e-8,
                "$voice impulse frame ${indices[j]}; check compensation, mode ratio, decay and body send")
        }
    }

    @Test
    fun `free root stays on requested note at register and acoustic extremes`() {
        val cases = listOf(
            AccordVoice.BRUSH to AccordAcousticProfile.BASE,
            AccordVoice.WOOD to AccordAcousticProfile.BASE,
            AccordVoice.TINE to AccordAcousticProfile.REFERENCE,
            AccordVoice.TINE to AccordAcousticProfile.SOFT_ATTACK,
            AccordVoice.TINE to AccordAcousticProfile.BRIGHT_EDGE,
            AccordVoice.TINE to AccordAcousticProfile.LONG_RING,
        )
        for ((voice, profile) in cases) for (midi in listOf(36, 48, 60, 72)) {
            // CONTACT/RAFT/velocity can reshape excitation and damping, but
            // must not disguise an empirical detuning curve in the root.
            val config = AccordAcoustics.configuration(voice, profile, midi, 1f, 1f, 1f)
            val response = AccordAcoustics.freeResponse(config, .45, rootOnly = true)
            val crossings = ArrayList<Double>()
            for (i in 1 until response.size) if (response[i - 1] < 0.0 && response[i] >= 0.0) {
                val at = i - 1 - response[i - 1] / (response[i] - response[i - 1])
                if (at > .02 * AccordAcoustics.INTERNAL_RATE) crossings += at
            }
            assertTrue(crossings.size > 10)
            val measured = AccordAcoustics.INTERNAL_RATE * (crossings.size - 1) /
                (crossings.last() - crossings.first())
            val cents = 1200.0 * ln(measured / config.rootHz) / ln(2.0)
            assertTrue(abs(cents) < .01, "$voice/$profile C$midi free root $cents cents")
            assertTrue(response.all { it.isFinite() })
        }
    }

    @Test
    fun `long ring loses less energy after drive stops than the original TINE`() {
        val reference = AccordAcoustics.freeResponse(AccordAcoustics.configuration(
            AccordVoice.TINE, AccordAcousticProfile.REFERENCE, 48, .75f), 1.0, rootOnly = true)
        val long = AccordAcoustics.freeResponse(AccordAcoustics.configuration(
            AccordVoice.TINE, AccordAcousticProfile.LONG_RING, 48, .75f), 1.0, rootOnly = true)
        fun energy(signal: DoubleArray, start: Double): Double {
            val from = (start * AccordAcoustics.INTERNAL_RATE).toInt()
            val to = from + (AccordAcoustics.INTERNAL_RATE * .05).toInt()
            var sum = 0.0
            for (i in from until to) sum += signal[i] * signal[i]
            return sum / (to - from)
        }
        val refRatio = energy(reference, .8) / energy(reference, .02)
        val longRatio = energy(long, .8) / energy(long, .02)
        assertTrue(longRatio > refRatio * 100.0, "Long Ring must change stored decay, not gain")
    }
}

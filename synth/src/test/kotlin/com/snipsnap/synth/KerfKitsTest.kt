package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KERF's four genre kits ([KerfKits]) against the drum gate's rule: distinct kits are authored choices with their own
 * drums, roles and character, not one kit at other settings. Save, playback and export are `:shell`'s `KerfKitTest`.
 */
class KerfKitsTest {
    private val G = KerfKits.Genre.entries

    /** Each pad's fitted hit, by its seed: one identity across both banks. */
    private fun drums(g: KerfKits.Genre) = KerfKits.pads(g).map { KerfKits.hitOf(g, it).patch.seed }

    @Test
    fun `every pair of kits has mostly different drums and a different kick and snare`() {
        for (i in G.indices) for (j in i + 1 until G.size) {
            val a = drums(G[i]); val b = drums(G[j])
            val shared = a.toSet().intersect(b.toSet())
            assertTrue(shared.size <= 3, "${G[i]} and ${G[j]} share ${shared.size} hits: $shared")
            assertTrue(a[0] != b[0], "${G[i]} and ${G[j]} share a lead kick")
            assertTrue(a[3] != b[3], "${G[i]} and ${G[j]} share a lead snare")
            assertTrue(G[i].bpm != G[j].bpm, "${G[i]} and ${G[j]} share a tempo")
            val settings = listOf(G[i].tune - G[j].tune, G[i].decay - G[j].decay, G[i].floor - G[j].floor, G[i].bleed - G[j].bleed, G[i].slop - G[j].slop)
            assertTrue(settings.count { abs(it) >= .05f } >= 3, "${G[i]} and ${G[j]} differ in at least three settings: $settings")
        }
    }

    @Test
    fun `every kit has the roles a break needs, names its pads once and points at real hits`() {
        for (g in G) {
            val pads = KerfKits.pads(g)
            assertEquals(KerfKits.HITS, pads.size)
            val names = KerfKits.names(g)
            assertEquals(KerfKits.PADS, names.size); assertEquals(names.size, names.toSet().size, "$g pad names")
            for (v in listOf(KerfVoice.KICK, KerfVoice.SNARE)) assertTrue(pads.count { it.voice == v } >= 2, "$g has two ${v}s")
            // MURK's dark loops hold one usable ghost note (genre_bank.py), and the kit keeps what its loops have
            assertTrue(pads.count { it.voice == KerfVoice.GHOST } >= 1, "$g has a ghost")
            assertTrue(pads.count { it.voice.drumClass == DrumClass.HAT_CLOSED || it.voice.drumClass == DrumClass.HAT_OPEN } >= 3, "$g has three hats")
            assertEquals(pads.size, drums(g).toSet().size, "$g plays no hit twice")
            for (p in pads) {
                assertEquals(g.second, p.second, "$g ${p.name} reads its genre's bank")
                if (p.second) assertTrue(p.hit in KerfBank.genreKits.getValue(g.name).indices, "$g ${p.name}")
                else assertTrue(p.hit in Kerf.pool(p.voice).indices, "$g ${p.name}")
            }
            assertEquals(KerfVoice.KICK, pads[0].voice); assertEquals(KerfVoice.SNARE, pads[3].voice)
            // the kick and snare lead; hats and ghosts sit under them, as in the loops
            for (p in pads) if (p.voice == KerfVoice.HAT || p.voice == KerfVoice.GHOST) assertTrue(p.levelDb <= -5.0, "$g ${p.name} level")
        }
    }

    private fun db(x: Double) = 20 * log10(x + 1e-12)

    private fun onsets(x: FloatArray, rate: Int): Int {
        // count rises of a 5 ms rms envelope by 6 dB over its value 10 ms earlier, at least 30 ms apart
        val hop = rate / 200; val env = DoubleArray(x.size / hop) { w -> var s = 0.0; for (k in w * hop until (w + 1) * hop) s += x[k].toDouble() * x[k]; db(kotlin.math.sqrt(s / hop)) }
        var n = 0; var last = -100
        for (w in 2 until env.size) if (env[w] > env[w - 2] + 6 && env[w] > -60 && w - last >= 6) { n++; last = w }
        return n
    }

    @Test
    fun `the roll and the riser are one bar of fresh strikes that build, under the kit's tempo`() {
        for (g in listOf(KerfKits.Genre.DUST, KerfKits.Genre.FUNK, KerfKits.Genre.RUSH, KerfKits.Genre.MURK)) for (role in listOf(KerfKits.HITS, KerfKits.HITS + 1)) {
            val hard = KerfKits.render(g, role, 1f, 0).samples
            assertEquals(((KerfKits.barSeconds(g) + KerfKits.TAIL_SECONDS) * Dsp.RATE).toInt(), hard.size, "$g $role length")
            assertTrue(hard.all { it.isFinite() })
            // it builds: the last quarter of the bar is louder than the first
            val q = (KerfKits.barSeconds(g) / 4 * Dsp.RATE).toInt()
            val first = hard.copyOfRange(0, q).maxOf { abs(it) }; val last = hard.copyOfRange(3 * q, 4 * q).maxOf { abs(it) }
            assertTrue(last > first * 1.5f, "$g $role builds: first quarter $first, last $last")
            assertTrue(onsets(hard, Dsp.RATE) >= 8, "$g $role has strikes: ${onsets(hard, Dsp.RATE)}")
            // a soft cell is softer (the kit scales the whole grid by one gain), and another take is other strikes
            assertTrue(KerfKits.render(g, role, .3f, 0).samples.maxOf { abs(it) } < hard.maxOf { abs(it) } * .8f, "$g $role soft")
            val other = KerfKits.render(g, role, 1f, 1).samples
            var d = 0.0; var s = 0.0
            for (i in hard.indices) { val e = (hard[i] - other[i]).toDouble(); d += e * e; s += hard[i].toDouble() * hard[i] }
            assertTrue(10 * log10(d / s) > -20, "$g $role take 1 is other strikes (${10 * log10(d / s)} dB)")
        }
    }

    @Test
    fun `round 5's kits play only hits the first bank and the engine's pools do not hold`() {
        val first = KerfBank.kits.values.flatten().map { it.patch.seed }.toSet()
        val pools = KerfVoice.entries.flatMap { Kerf.pool(it) }.map { it.patch.seed }.toSet()
        assertEquals(63, pools.size, "the engine's pools are still the first bank's 63 hits")
        for (g in KerfKits.Genre.entries.filter { it.second }) {
            assertEquals(KerfKits.HITS, KerfBank.genreKits.getValue(g.name).size, "$g bank")
            for (seed in drums(g)) { assertTrue(seed !in first, "$g reuses a first-bank hit"); assertTrue(seed !in pools) }
            for (p in KerfBank.genreKits.getValue(g.name)) assertTrue(p.patch.seconds <= Kerf.MAX_SECONDS, "$g pad ${p.pad}")
        }
    }
}

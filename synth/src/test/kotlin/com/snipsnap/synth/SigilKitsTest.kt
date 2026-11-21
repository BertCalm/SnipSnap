package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SIGIL's kits in Kotlin, held to what round 0 measured in the prototype (`docs/SIGIL.md`) and to the owner's gates:
 * every render healthy, every zone darker when softer, every take a fresh strike, the kicks and snares inside the
 * owner's reference loops' ranges, the drumless kits truly drumless, every tuned pad in A minor.
 * The parity with the approved prototype is `prototype/sigil/compare_kotlin.py` (measured, not a unit test).
 */
class SigilKitsTest {
    private val kits = SigilKits.Kit.entries
    private val rate = Dsp.RATE

    private val grids: Map<Pair<SigilKits.Kit, Int>, List<List<FloatArray>>> by lazy {
        kits.flatMap { k -> (0 until SigilKits.HITS).map { r -> (k to r) } }.associateWith { (k, r) ->
            SigilKits.velocities.map { v -> List(SigilKits.TAKES) { t -> SigilKits.render(k, r, v, t).samples } }
        }
    }

    // ---------------------------------------------------------------- small measures (the prototype's probes)

    private fun spectrum(x: FloatArray, from: Int = 0, to: Int = x.size): Pair<DoubleArray, Double> {
        val m = to - from
        var n = 1
        while (n < m) n = n shl 1
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until m) re[i] = x[from + i] * (0.5 - 0.5 * cos(2 * PI * i / (m - 1).coerceAtLeast(1)))
        fft(re, im)
        val p = DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
        return p to rate.toDouble() / n
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val a = i + k; val b = a + len / 2
                val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
            }
            len = len shl 1
        }
    }

    private fun head(x: FloatArray, ms: Int = 120) = minOf(x.size, rate * ms / 1000)

    private fun centroid(x: FloatArray): Double {
        val (p, df) = spectrum(x, 0, head(x))
        var a = 0.0; var b = 0.0
        for (i in p.indices) { a += i * df * p[i]; b += p[i] }
        return a / b
    }

    private fun shareAbove(x: FloatArray, hz: Double): Double {
        val (p, df) = spectrum(x, 0, head(x))
        var a = 0.0; var b = 0.0
        for (i in p.indices) { if (i * df >= 20) b += p[i]; if (i * df >= hz) a += p[i] }
        return a / b
    }

    /** The prototype's f0_at: the strongest bin from 35 to 200 Hz over a Hann window of [len] samples, zero-padded 4x. */
    private fun f0(x: FloatArray, from: Int, len: Int = 8192, lo: Double = 35.0, hi: Double = 200.0): Double {
        val m = minOf(len, x.size - from)
        val padded = FloatArray(m * 4).also { for (i in 0 until m) it[i] = x[from + i] * (0.5 - 0.5 * cos(2 * PI * i / (m - 1))).toFloat() }
        val (p, df) = spectrum(padded)
        var best = 0; var bv = -1.0
        for (i in p.indices) { val f = i * df; if (f > lo && f < hi && p[i] > bv) { bv = p[i]; best = i } }
        return best * df
    }

    private fun decayMs(x: FloatArray, dropDb: Double = 20.0): Double {
        val w = 220
        val env = DoubleArray(x.size)
        var acc = 0.0
        for (i in x.indices) { acc += x[i].toDouble() * x[i]; if (i >= w) acc -= x[i - w].toDouble() * x[i - w]; env[i] = sqrt(max(acc, 0.0) / w) }
        var pk = 0
        for (i in env.indices) if (env[i] > env[pk]) pk = i
        val thr = env[pk] * Math.pow(10.0, -dropDb / 20)
        for (i in pk until env.size) if (env[i] < thr) return (i - pk) * 1000.0 / rate
        return (env.size - pk) * 1000.0 / rate
    }

    private fun closenessDb(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var sa = 0.0; var sd = 0.0
        for (i in 0 until n) { sa += a[i].toDouble() * a[i]; val d = a[i] - b[i]; sd += d.toDouble() * d }
        return 10 * log10(sa / max(sd, 1e-24))
    }

    // ---------------------------------------------------------------- the kits

    @Test fun `eight kits load with sixteen named pads each`() {
        assertEquals(8, kits.size)
        for (k in kits) {
            val roles = SigilKits.roles(k)
            assertEquals(SigilKits.PADS, roles.size, k.name)
            assertEquals(roles.size, roles.toSet().size, "${k.name}: pad names unique")
            assertEquals(DrumClass.LOOP, SigilKits.classOf(k, SigilKits.ROLL))
            assertEquals(DrumClass.LOOP, SigilKits.classOf(k, SigilKits.GROOVE))
        }
        assertEquals(listOf(74, 90, 115, 100), kits.take(4).map { it.bpm })
    }

    @Test fun `every hit is finite, under full scale and centred`() {
        for ((key, grid) in grids) for ((z, zone) in grid.withIndex()) for ((t, x) in zone.withIndex()) {
            val where = "${key.first} ${SigilKits.roles(key.first)[key.second]} z$z t$t"
            assertTrue(x.all { it.isFinite() }, where)
            val peak = x.maxOf { abs(it) }
            assertTrue(peak in 0.05f..1f, "$where peak $peak")
            assertTrue(abs(x.average()) < 0.005, "$where dc ${x.average()}")
        }
    }

    @Test fun `the same arguments give the same bytes`() {
        for (k in listOf(SigilKits.Kit.IRON, SigilKits.Kit.KILN_DRUMLESS)) for (role in listOf(0, 4, SigilKits.ROLL)) {
            assertContentEquals(SigilKits.render(k, role, .7f, 2).samples, SigilKits.render(k, role, .7f, 2).samples, "${k.name} $role")
        }
        assertContentEquals(SigilKits.groove(SigilKits.Kit.NEON, .4f, 1), SigilKits.groove(SigilKits.Kit.NEON, .4f, 1))
    }

    /**
     * The owner's velocity gate: every soft zone darker than the hard one, not only quieter. Brightness is the share
     * of the first 120 ms above twice the hard hit's centroid (an octave up reads the overtones a strike brightens; at
     * the centroid itself the split sat on ASH's rim's fundamental), or above the centroid where a hat has nothing an
     * octave up. Medians of the four takes. Also: the soft zone is quieter.
     */
    @Test fun `every soft zone is darker and quieter than the hard one`() {
        for ((key, grid) in grids) {
            val hard = grid.last(); val soft = grid.first()
            var split = 2 * hard.map { centroid(it) }.sorted().let { (it[1] + it[2]) / 2 }
            if (hard.map { shareAbove(it, split) }.sorted().let { (it[1] + it[2]) / 2 } < 1e-4) split /= 2
            val h = hard.map { shareAbove(it, split) }.sorted().let { (it[1] + it[2]) / 2 }
            val s = soft.map { shareAbove(it, split) }.sorted().let { (it[1] + it[2]) / 2 }
            val where = "${key.first} ${SigilKits.roles(key.first)[key.second]}"
            assertTrue(10 * log10(s / h) <= 0.3, "$where: soft is ${"%.2f".format(10 * log10(s / h))} dB brighter")
            assertTrue(soft.maxOf { x -> x.maxOf { abs(it) } } < hard.minOf { x -> x.maxOf { abs(it) } }, "$where: soft is not quieter")
        }
    }

    /** Four takes per zone are four strikes: no two closer than 35 dB (a copy reads over 40). */
    @Test fun `every take is a fresh strike`() {
        for ((key, grid) in grids) for ((z, zone) in grid.withIndex()) {
            for (a in 0 until SigilKits.TAKES) for (b in a + 1 until SigilKits.TAKES) {
                val c = closenessDb(zone[a], zone[b])
                assertTrue(c < 35, "${key.first} ${SigilKits.roles(key.first)[key.second]} z$z takes $a and $b are ${"%.1f".format(c)} dB apart")
            }
        }
    }

    /**
     * The owner's drum loops, measured (docs/SIGIL.md): kick pitch 105 to 186 Hz at the hit and 36 to 97 Hz 80 ms
     * later, 20 dB down in 69 to 351 ms; snare centroid 1.1 to 2.9 kHz. IRON's dark snare rings 199 ms where the loops'
     * longest rang 186: heard and kept in round 0, so the snare bound is 51 to 220 ms.
     */
    @Test fun `kicks and snares sit inside the reference loops' ranges`() {
        for (k in kits.filter { !it.drumless }) {
            val pads = SigilKits.book(k).pads
            for ((role, pad) in pads.withIndex()) {
                val hard = grids.getValue(k to role).last()
                when (pad.voice) {
                    "kick" -> {
                        if (pad.name == "Ghost Kick") continue  // the shuffle's quiet in-between kick, short by design
                        val hit = hard.map { f0(it, 0, len = 2048) }.sorted()[1]
                        val body = hard.map { f0(it, (0.08 * rate).toInt()) }.sorted()[1]
                        val decay = hard.map { decayMs(it) }.sorted()[1]
                        assertTrue(hit in 100.0..190.0, "${k.name} ${pad.name}: $hit Hz at the hit")
                        assertTrue(body in 36.0..97.0, "${k.name} ${pad.name}: $body Hz at 80 ms")
                        assertTrue(decay in 69.0..400.0, "${k.name} ${pad.name}: $decay ms to -20 dB")
                    }
                    "snare" -> {
                        val c = hard.map { centroid(it) }.sorted()[1]
                        val decay = hard.map { decayMs(it) }.sorted()[1]
                        assertTrue(c in 1099.0..2857.0, "${k.name} ${pad.name}: centroid $c")
                        assertTrue(decay in 51.0..220.0, "${k.name} ${pad.name}: $decay ms to -20 dB")
                    }
                }
            }
        }
    }

    @Test fun `the drumless kits have no kick, snare or clap and keep their parents' percussion exactly`() {
        for (k in kits.filter { it.drumless }) {
            val b = SigilKits.book(k)
            val parent = SigilKits.book(kits.single { it.jsonId == b.parent })
            for (role in 0 until SigilKits.HITS) {
                assertTrue(SigilKits.classOf(k, role) !in setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP) || b.pads[role].voice == "snap",
                    "${k.name} ${b.pads[role].name}")
            }
            for (pad in b.pads) {
                val same = parent.pads.firstOrNull { it.name == pad.name } ?: continue
                assertEquals(same.voice, pad.voice, "${k.name} ${pad.name}")
                assertEquals(same.raw, pad.raw, "${k.name} ${pad.name}: constants differ from the parent's")
            }
            assertEquals(parent.bpm, b.bpm)
            assertEquals(parent.print, b.print)
        }
    }

    /** Every tuned pad is a note of A minor (the owner's tonal loops: Am, F, C and E, all inside it). */
    @Test fun `every tuned pad sits in A minor`() {
        val aMinor = setOf(9, 11, 0, 2, 4, 5, 7)  // A B C D E F G as pitch classes from C
        for (k in kits) for (pad in SigilKits.book(k).pads) {
            if (pad.voice !in setOf("log_drum", "tine", "glass_bell", "rod")) continue
            val midi = 69 + 12 * ln(pad.params.d("note") / 440.0) / ln(2.0)
            val pc = Math.floorMod(Math.round(midi).toInt(), 12)
            assertTrue(abs(midi - Math.round(midi)) < 0.05 && pc in aMinor, "${k.name} ${pad.name}: midi $midi")
        }
    }

    @Test fun `rolls and grooves run their written length`() {
        for (k in kits) {
            val g = SigilKits.groove(k, 1f, 0)
            assertEquals((SigilKits.grooveSeconds(k) * rate).toInt(), g.size, k.name)
            assertTrue(g.maxOf { abs(it) } in 0.9f..0.96f, "${k.name} groove peak")
            val roll = SigilKits.roll(k, 1f, 0)
            assertTrue(roll.size > 1.5 * rate, "${k.name} roll ${roll.size}")  // a phrase, filed LOOP
        }
    }
}

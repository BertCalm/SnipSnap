package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.*

/** Model 1: top vertex first, CCW triangle, right-side (outward) Koch bumps.
 * Samples exclude the endpoint, use equal polygon arc length, perimeter centroid,
 * and unit maximum sampled radius. The exact reference is never asymmetrized. */
internal object RimeContour {
    data class Path(val x: DoubleArray, val y: DoubleArray, val iteration: Int, val edges: Int, val radius: Double)
    data class Coefficient(val n: Int, val re: Double, val im: Double) {
        val energy get() = re * re + im * im
    }

    fun generate(iteration: Int = 5, samples: Int = 16384): Path {
        require(iteration in 0..6 && samples >= 1024 && samples.countOneBits() == 1)
        var vertices = Array(3) { k -> doubleArrayOf(cos(PI / 2 + 2 * PI * k / 3), sin(PI / 2 + 2 * PI * k / 3)) }
        repeat(iteration) {
            val old = vertices
            vertices = Array(old.size * 4) { j ->
                val a = old[j / 4]; val b = old[(j / 4 + 1) % old.size]
                val dx = b[0] - a[0]; val dy = b[1] - a[1]
                when (j % 4) {
                    0 -> a.copyOf()
                    1 -> doubleArrayOf(a[0] + dx / 3, a[1] + dy / 3)
                    2 -> doubleArrayOf(a[0] + dx / 2 + sqrt(3.0) * dy / 6, a[1] + dy / 2 - sqrt(3.0) * dx / 6)
                    else -> doubleArrayOf(a[0] + 2 * dx / 3, a[1] + 2 * dy / 3)
                }
            }
        }
        val cumulative = DoubleArray(vertices.size + 1)
        for (i in vertices.indices) {
            val a = vertices[i]; val b = vertices[(i + 1) % vertices.size]
            cumulative[i + 1] = cumulative[i] + hypot(b[0] - a[0], b[1] - a[1])
        }
        val x = DoubleArray(samples); val y = DoubleArray(samples)
        var edge = 0
        for (i in 0 until samples) {
            val distance = cumulative.last() * i / samples
            while (edge + 1 < vertices.size && cumulative[edge + 1] <= distance) edge++
            val f = (distance - cumulative[edge]) / (cumulative[edge + 1] - cumulative[edge])
            val a = vertices[edge]; val b = vertices[(edge + 1) % vertices.size]
            x[i] = a[0] + f * (b[0] - a[0]); y[i] = a[1] + f * (b[1] - a[1])
        }
        val mx = x.average(); val my = y.average()
        var radius = 0.0
        for (i in x.indices) { x[i] -= mx; y[i] -= my; radius = max(radius, hypot(x[i], y[i])) }
        for (i in x.indices) { x[i] /= radius; y[i] /= radius }
        return Path(x, y, iteration, vertices.size, radius)
    }

    /** Fft.forward is unscaled exp(-iωt); wrap bins to signed indices explicitly. */
    fun extract(path: Path, maximum: Int = 512): List<Coefficient> {
        require(maximum in 1 until path.x.size / 2)
        val re = FloatArray(path.x.size) { path.x[it].toFloat() }
        val im = FloatArray(path.y.size) { path.y[it].toFloat() }
        Fft.forward(re, im)
        return (-maximum..maximum).map { n ->
            val k = (n + re.size) % re.size
            Coefficient(n, if (n == 0) 0.0 else re[k].toDouble() / re.size,
                if (n == 0) 0.0 else im[k].toDouble() / re.size)
        }
    }

    fun evaluate(coefficients: List<Coefficient>, u: Double): DoubleArray {
        var x = 0.0; var y = 0.0
        for (c in coefficients) {
            val a = 2 * PI * c.n * u; val co = cos(a); val si = sin(a)
            x += c.re * co - c.im * si; y += c.re * si + c.im * co
        }
        return doubleArrayOf(x, y)
    }

    /** Each table is a disjoint frequency band; all tables share u=0. */
    class Band(val x: FloatArray, val y: FloatArray, val dx: FloatArray, val dy: FloatArray) {
        fun read(table: FloatArray, phase: Double): Double {
            val p = (phase - floor(phase)) * table.size
            val i = p.toInt(); val f = p - i
            return table[i] + f * (table[(i + 1) % table.size] - table[i])
        }
    }

    class Prepared(val bands: List<Band>, val cutoff: Double, val safeMaximum: Int, val coefficients: List<Coefficient>)
    val reference: List<Coefficient> by lazy { extract(generate()) }
    const val TABLE_SIZE = 8192
    const val BANDWIDTH = 18000.0

    private data class TableKey(val hz: Double, val detail: Double, val edge: Double)
    private val preparedCache = object : LinkedHashMap<TableKey, Prepared>(12, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TableKey, Prepared>?): Boolean = size > 12
    }

    /** At most twelve immutable banks, about 14 MiB at the maximum band count. */
    @Synchronized fun prepare(hz: Double, detail: Double, edge: Double): Prepared {
        val key = TableKey(hz, detail, edge)
        return preparedCache.getOrPut(key) { build(hz, detail, edge) }
    }

    private fun build(hz: Double, detail: Double, edge: Double): Prepared {
        val maximum = min(512, floor(BANDWIDTH / hz).toInt()).coerceAtLeast(1)
        // Compress the proposed 1/8/32/128/512 scale onto this note's safe range.
        val segment = min(3, floor(detail * 4).toInt())
        val anchors = doubleArrayOf(0.0, 3.0, 5.0, 7.0, 9.0)
        val fraction = detail * 4 - segment
        val exponent = (anchors[segment] + fraction * (anchors[segment + 1] - anchors[segment])) / 9
        val cutoff = maximum.toDouble().pow(exponent)
        val transition = smooth(detail / .12)
        val selected = reference.filter { it.n != 0 && abs(it.n) <= maximum }.map { c ->
            val n = abs(c.n)
            val admission = if (n == 1) 1.0 else smooth((cutoff * 1.12 - n) / max(1.0, cutoff * .24))
            val bandwidth = smooth((BANDWIDTH - n * hz) / 2000)
            val taper = if (n == 1) 1.0 else exp(-(5.5 * (1 - edge).pow(2) + .03) * (n / cutoff).pow(2))
            val w = admission * bandwidth * taper
            val re = c.re * transition
            val im = c.im * transition + if (c.n == 1) 1 - transition else 0.0
            Coefficient(c.n, re * w, im * w)
        }
        val slopeNorm = sqrt(selected.filter { abs(it.n) > 1 }.sumOf { it.n.toDouble().pow(2) * it.energy }).coerceAtLeast(.8)
        val count = selected.filter { it.energy > 1e-16 }.maxOfOrNull { bandFor(abs(it.n)) }?.plus(1) ?: 1
        val bands = (0 until count).map { band ->
            val xr = FloatArray(TABLE_SIZE); val yi = FloatArray(TABLE_SIZE)
            val dr = FloatArray(TABLE_SIZE); val di = FloatArray(TABLE_SIZE)
            for (c in selected) if (bandFor(abs(c.n)) == band) {
                val index = (c.n + TABLE_SIZE) % TABLE_SIZE
                xr[index] = (c.re * TABLE_SIZE).toFloat(); yi[index] = (c.im * TABLE_SIZE).toFloat()
                if (abs(c.n) > 1) {
                    dr[index] = (-c.n * c.im / slopeNorm * TABLE_SIZE).toFloat()
                    di[index] = (c.n * c.re / slopeNorm * TABLE_SIZE).toFloat()
                }
            }
            Fft.inverse(xr, yi); Fft.inverse(dr, di)
            Band(xr, yi, dr, di)
        }
        return Prepared(bands, cutoff, maximum, selected)
    }

    private fun bandFor(n: Int): Int = when {
        n <= 1 -> 0
        n <= 4 -> 1
        else -> (ceil(log2(n.toDouble())).toInt() - 1).coerceAtLeast(1)
    }
    fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
}

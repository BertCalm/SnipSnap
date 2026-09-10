package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import java.util.Locale

/**
 * DOUBLES: pads across the shelf whose feature vectors sit within a
 * distance of each other — the number on every row, nothing deleted,
 * moved or merged. X-RAY's posture turned on the user's own library:
 * the claim is the vector and the distance, never a verdict.
 *
 * Built entirely on what [Crate] already holds: [Crate.index] measures
 * every pad once (cached by path, mtime and size), [Crate.dupes] is the
 * pairwise pass. This adds the grouping — union-find over the pairs,
 * single linkage — and the words.
 *
 * The threshold is a dial the user reads, not a fence we assert:
 * [Crate.DUPE_DISTANCE] (0.02) was never tuned as a "same sound" claim,
 * only as the "same bytes" exclusion the roulette needs, so DOUBLES
 * carries its own steps ([WITHIN_STEPS]) and shows each cluster's own
 * spread (WITHIN) so a wider ring reads as what it is.
 */
object Doubles {

    /** The rings the screen steps through: the roulette's "same bytes" fence, then two wider "same sound" rings. */
    val WITHIN_STEPS: List<Float> = listOf(0.02f, 0.05f, 0.10f)

    /** Where the dial starts — one ring wider than the byte fence, still tight. */
    const val DEFAULT_WITHIN = 0.05f

    /**
     * One group of pads within [within] of each other. [entries] are
     * sorted by kit then slot; [within] is the cluster's own widest
     * intra-cluster distance (its honest spread, at most the threshold
     * only for a pair — single linkage lets a chain reach further);
     * [label] is the majority stored class; [kitCount] how many kits it
     * spans.
     */
    data class Cluster(val entries: List<Crate.Entry>, val within: Float, val label: DrumClass, val kitCount: Int)

    /**
     * The pairwise pass, done once: every pair of pads within [ring] of
     * each other, with its distance. [clusters] regroups these in memory
     * for any [Cluster.within] up to the ring, so a screen stepping
     * between rings never rescans the shelf — [Crate.dupes] is an
     * all-pairs pass over the whole index, the one thing here that grows
     * with the square of the library.
     */
    data class Measured(
        val index: Crate.Index,
        /** The widest ring these pairs cover; a tighter ring is a filter over them. */
        val ring: Float,
        val pairs: List<Triple<Crate.Entry, Crate.Entry, Float>>,
    )

    /** Measure once at the widest step (or [ring]); regroup with [clusters] as the dial turns. */
    fun measure(index: Crate.Index, ring: Float = WITHIN_STEPS.max()): Measured {
        require(ring > 0f) { "ring must be positive, got $ring" }
        return Measured(index, ring, Crate.dupes(index, ring))
    }

    /** Every cluster under [within], tightest first — one measure and one regroup. */
    fun clusters(index: Crate.Index, within: Float = DEFAULT_WITHIN): List<Cluster> =
        clusters(measure(index, within), within)

    /**
     * Every cluster under [within], tightest first, regrouped from pairs
     * already [measure]d. Union-find over the pairs inside the ring: two
     * pads that are each within the ring of a third belong together even
     * when they sit further from each other — which is exactly why every
     * cluster shows its own WITHIN. A [within] wider than what was
     * measured would silently miss pairs, so it refuses instead.
     */
    fun clusters(measured: Measured, within: Float = DEFAULT_WITHIN): List<Cluster> {
        require(within > 0f) { "within must be positive, got $within" }
        require(within <= measured.ring) { "within $within is wider than the measured ring ${measured.ring}" }
        val entries = measured.index.entries
        if (entries.size < 2) return emptyList()
        val parent = IntArray(entries.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        val at = entries.withIndex().associate { (i, e) -> e.file to i }
        for ((a, b, d) in measured.pairs) {
            if (d > within) continue
            val ra = find(at.getValue(a.file))
            val rb = find(at.getValue(b.file))
            if (ra != rb) parent[ra] = rb
        }
        val groups = entries.indices.groupBy { find(it) }.values.filter { it.size >= 2 }
        return groups.map { members ->
            val es = members.map { entries[it] }.sortedWith(compareBy({ it.kitDir.lowercase() }, { it.slot }))
            var spread = 0f
            for (i in es.indices) for (j in i + 1 until es.size) {
                val d = Crate.distance(es[i].vector, es[j].vector)
                if (d > spread) spread = d
            }
            Cluster(es, spread, majorityClass(es), es.map { it.kitDir }.toSet().size)
        }.sortedWith(compareBy<Cluster> { it.within }.thenByDescending { it.entries.size }.thenBy { it.entries.first().file })
    }

    /** "KICK · 3 PADS IN 2 KITS · WITHIN 0.04" — the cluster's header row. */
    fun headline(c: Cluster): String =
        "${c.label.name.replace('_', ' ')} · ${c.entries.size} PADS IN ${c.kitCount} ${if (c.kitCount == 1) "KIT" else "KITS"} · WITHIN ${fmt(c.within)}"

    /** "FACTORY · A01 · KICK 01" — one member row. */
    fun memberLine(e: Crate.Entry): String = "${e.kitName.uppercase()} · ${e.label} · ${e.padName.uppercase()}"

    /** Two decimals, the resolution the steps are named in. */
    fun fmt(distance: Float): String = String.format(Locale.ROOT, "%.2f", distance)

    /** The most common stored class; a tie goes to the class that comes first in [DrumClass]. */
    private fun majorityClass(es: List<Crate.Entry>): DrumClass =
        es.groupingBy { it.storedClass }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<DrumClass, Int>> { it.value }.thenBy { it.key.ordinal })
            .first().key
}

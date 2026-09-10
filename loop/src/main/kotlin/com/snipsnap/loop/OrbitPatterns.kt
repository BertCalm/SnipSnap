package com.snipsnap.loop

/**
 * Ways to fill a ring faster than one tap per step.
 *
 * A ring is the natural home for Euclidean rhythms — "spread k hits as
 * evenly as n steps allow" is the tresillo at 3 over 8, the cinquillo at
 * 5 over 8, and most of the world's bell patterns besides — so [euclid] is
 * Toussaint's telling of Bjorklund, onset first, the same algorithm the
 * CLI's `euclid` command uses. [scramble] is the app's dice: a seeded
 * roll that can be rolled again, and the same seed always lands the same.
 * Everything here returns a new ring; nothing mutates.
 */
object OrbitPatterns {

    /**
     * The steps on which k onsets fall when spread across n steps as evenly
     * as the integers allow, rotated [rotation] steps later: E(3,8) is
     * `x..x..x.` → 0, 3, 6; E(5,8) is `x.xx.xx.` → 0, 2, 3, 5, 6.
     */
    fun euclid(k: Int, n: Int, rotation: Int = 0): List<Int> {
        require(n >= 1) { "n must be positive: $n" }
        require(k in 0..n) { "k wants 0..$n, got $k" }
        if (k == 0) return emptyList()
        val onsets = bjorklund(k, n)
        return (0 until n).filter { onsets[Math.floorMod(it - rotation, n)] }.sorted()
    }

    /** [ring] with [slot]'s hits replaced by k spread evenly round it, the downbeat a little harder. */
    fun spread(ring: Orbit, slot: Int, k: Int, rotation: Int = 0): Orbit {
        val content = ring.content as? PatternOrbit ?: return ring
        require(slot in ring.pads) { "ring '${ring.name}' does not play pad $slot" }
        val kept = content.hits.filter { it.slot != slot }
        val spread = euclid(k.coerceIn(0, ring.steps), ring.steps, rotation).map { step ->
            OrbitHit(step, slot, if (step == 0) DOWNBEAT_VELOCITY else HIT_VELOCITY)
        }
        return ring.copy(content = content.copy(hits = (kept + spread).sortedWith(compareBy({ it.step }, { it.slot }))))
    }

    /**
     * [ring] turned [by] steps later (earlier when negative), every hit
     * wrapping round the ring. Turning a Euclidean pattern one step is how
     * a tresillo becomes a different groove; turning a copy is how two
     * rings start to phase.
     */
    fun turn(ring: Orbit, by: Int): Orbit {
        val content = ring.content as? PatternOrbit ?: return ring
        if (Math.floorMod(by, ring.steps) == 0) return ring
        val hits = content.hits.map { it.copy(step = Math.floorMod(it.step + by, ring.steps)) }
        return ring.copy(content = content.copy(hits = hits.sortedWith(compareBy({ it.step }, { it.slot }))))
    }

    /**
     * [ring] with a hit for [slot] placed on [step] if there is none yet —
     * what a pad tap writes while the rail is armed. An existing hit is
     * left as it is, weight and all: playing over a hit is not lifting it.
     */
    fun place(ring: Orbit, slot: Int, step: Int, velocity: Float = HIT_VELOCITY): Orbit {
        val content = ring.content as? PatternOrbit ?: return ring
        require(slot in ring.pads) { "ring '${ring.name}' does not play pad $slot" }
        require(step in 0 until ring.steps) { "ring '${ring.name}' has no step $step" }
        if (content.hits.any { it.step == step && it.slot == slot }) return ring
        val hits = content.hits + OrbitHit(step, slot, velocity)
        return ring.copy(content = content.copy(hits = hits.sortedWith(compareBy({ it.step }, { it.slot }))))
    }

    /** The name a copy of [name] takes: "KICK" → "KICK 2", "KICK 2" → "KICK 3". */
    fun copyName(name: String): String {
        val m = Regex("""^(.*?)\s(\d+)$""").find(name)
        return if (m != null) "${m.groupValues[1]} ${m.groupValues[2].toInt() + 1}" else "$name 2"
    }

    /** [ring] with no hits at all. Its voice and shape stay. */
    fun clear(ring: Orbit): Orbit {
        val content = ring.content as? PatternOrbit ?: return ring
        return ring.copy(content = content.copy(hits = emptyList()))
    }

    /**
     * [ring] rolled: every pad in its voice gets a handful of hits on random
     * steps at random weights — between a sixth and a third of the ring per
     * pad, never on the same step twice for one pad. Deterministic per
     * [seed]: roll again by changing it.
     */
    fun scramble(ring: Orbit, seed: Int): Orbit {
        val content = ring.content as? PatternOrbit ?: return ring
        var rng = if (seed == 0) 1 else seed
        fun roll(): Float { rng = (rng * 1103515245 + 12345) and 0x7fffffff; return rng / 0x7fffffff.toFloat() }

        val hits = ArrayList<OrbitHit>()
        for (slot in ring.pads) {
            val least = (ring.steps / 6).coerceAtLeast(1)
            val most = (ring.steps / 3).coerceAtLeast(least)
            val count = least + (roll() * (most - least + 1)).toInt().coerceIn(0, most - least)
            val steps = (0 until ring.steps).shuffled(java.util.Random(rng.toLong())).take(count).sorted()
            roll()
            for (step in steps) {
                val weight = roll()
                val velocity = when {
                    weight < 0.2f -> SOFT_VELOCITY
                    weight > 0.85f -> ACCENT_VELOCITY
                    else -> HIT_VELOCITY
                }
                hits += OrbitHit(step, slot, velocity)
            }
        }
        return ring.copy(content = content.copy(hits = hits.sortedWith(compareBy({ it.step }, { it.slot }))))
    }

    /** The next weight round after [velocity]: soft → normal → accent → soft. */
    fun nextVelocity(velocity: Float): Float = when {
        velocity < HIT_VELOCITY -> HIT_VELOCITY
        velocity < ACCENT_VELOCITY -> ACCENT_VELOCITY
        else -> SOFT_VELOCITY
    }

    const val SOFT_VELOCITY = 0.5f
    const val HIT_VELOCITY = 0.9f
    const val DOWNBEAT_VELOCITY = 0.95f
    const val ACCENT_VELOCITY = 1f

    /** Ring sizes worth a chip of their own: the common bars, beats, odd meters and their doubles. */
    val STEP_CHOICES: List<Int> = listOf(3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 20, 24, 28, 32, 48, 64)

    private fun bjorklund(k: Int, n: Int): BooleanArray {
        if (k == n) return BooleanArray(n) { true }
        var main = MutableList(k) { mutableListOf(true) }
        var rem = MutableList(n - k) { mutableListOf(false) }
        while (rem.size > 1) {
            val m = minOf(main.size, rem.size)
            val combined = MutableList(m) { (main[it] + rem[it]).toMutableList() }
            val leftover = (main.drop(m) + rem.drop(m)).toMutableList()
            main = combined
            rem = leftover
        }
        val flat = (main + rem).flatten()
        return BooleanArray(n) { flat[it] }
    }
}

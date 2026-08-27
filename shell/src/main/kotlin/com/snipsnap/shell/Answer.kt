package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.Kit
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.Tonewheel
import com.snipsnap.synth.TonewheelVoice
import com.snipsnap.synth.Velvet
import com.snipsnap.synth.VelvetVoice
import kotlin.random.Random

/**
 * The Answer — chop a break, get the B-side. The kit already knows its
 * key, its groove, its feel; this derives the complement: an S5 bass
 * playing a **counter-pattern in the groove's gaps** — the pocket
 * inverted. Three rules, in order:
 *
 * 1. **Avoid the strong hits.** A 16th-step carrying ≥ [STRONG_FRACTION]
 *    of the groove's loudest velocity is the kit's statement; the answer
 *    never lands there.
 * 2. **Sit in the key.** Notes are scale degrees off the kit's root in
 *    the bass register, weighted hard toward root and fifth — a bassline,
 *    not a solo.
 * 3. **Follow the feel.** The donor's timing/accent template applies; in
 *    gaps the donor never played, its lean *generalises* by 16th parity
 *    (even/odd), so the answer leans the way the drummer does.
 *
 * [band] grows the answer into sidemen: Tonewheel stab triads on the
 * gaps the bass leaves open, and a Velvet CHIP tick on the off-16ths
 * the groove leaves free — each key-locked, each following the feel,
 * all seeded off the same reroll seed. Deterministic per seed.
 */
object Answer {

    /** A step at or past this fraction of the groove's peak velocity is a strong hit. */
    const val STRONG_FRACTION = 0.6f

    data class Derived(
        val name: String,
        /** The rendered bass note (via the Velvet BASS engine) and its OneNote build. */
        val one: OneNote.Result,
        /** The bassline, feel applied. */
        val clip: Mpc3Clip,
        val key: KeySpec,
        val seed: Int,
    )

    /** One sideman: a rendered note, its OneNote build, and its line. */
    data class Sideman(
        val name: String,
        val one: OneNote.Result,
        val clip: Mpc3Clip,
    )

    /** The whole band: the bass plus whoever found room to play. */
    data class Band(
        val bass: Derived,
        val stabs: Sideman?,
        val shaker: Sideman?,
    ) {
        val sidemen: List<Sideman> get() = listOfNotNull(stabs, shaker)
    }

    // ---- the groove, read once ---------------------------------------------

    /** What the groove says: step strengths, the hit line, and the feel. */
    private class Pocket(groove: Mpc3Clip) {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val steps = groove.bars * 16
        val bars = groove.bars
        val limit = groove.bars * Mpc3Clip.PULSES_PER_BAR
        val strength = FloatArray(steps)
        val strongCut: Float
        val template: GrooveFeel.Template
        private val evenLean: Long?
        private val oddLean: Long?
        private val evenAccent: Float?
        private val oddAccent: Float?

        init {
            for (n in groove.notes) {
                val pos = (((n.timePulses + s16 / 2) / s16) % steps).toInt()
                if (n.velocity > strength[pos]) strength[pos] = n.velocity
            }
            strongCut = STRONG_FRACTION * groove.notes.maxOf { it.velocity }
            template = GrooveFeel.extract(groove)
            fun lean(parity: Int): Long? =
                template.offsets.filterIndexed { i, v -> i % 2 == parity && v != null }
                    .filterNotNull().sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            evenLean = lean(0)
            oddLean = lean(1)
            fun accent(parity: Int): Float? =
                template.accents.filterIndexed { i, v -> i % 2 == parity && v != null }
                    .filterNotNull().let { if (it.isEmpty()) null else it.average().toFloat() }
            evenAccent = accent(0)
            oddAccent = accent(1)
        }

        fun strong(step: Int): Boolean = strength[step] >= strongCut

        /** The donor's offset at this step, its parity lean where it never played. */
        fun offset(step: Int): Long = template.offsets[step % GrooveFeel.POSITIONS]
            ?: (if (step % 2 == 0) evenLean else oddLean) ?: 0L

        fun accent(step: Int): Float = template.accents[step % GrooveFeel.POSITIONS]
            ?: (if (step % 2 == 0) evenAccent else oddAccent) ?: 1f

        fun timeOf(step: Int): Long = (step * s16 + offset(step)).coerceIn(0L, limit - 1)
    }

    // ---- the bass ----------------------------------------------------------

    fun derive(kit: Kit, groove: Mpc3Clip, seed: Int = 1): Derived {
        val key = requireNotNull(kit.key) {
            "the kit doesn't know its key - chop with --key auto, or retune it into one"
        }
        require(groove.notes.isNotEmpty()) { "the groove has no notes to answer" }
        val pocket = Pocket(groove)

        val rnd = Random(seed)
        val rootMidi = 36 + key.rootSemitone // C2..B2 - the bass register
        val intervals = key.scale.intervals

        // Root and fifth carry a bassline; the rest of the scale seasons it.
        fun pickOffset(): Int {
            val r = rnd.nextFloat()
            return when {
                r < 0.40f -> 0
                r < 0.62f && intervals.contains(7) -> 7
                r < 0.74f -> 12
                else -> intervals[rnd.nextInt(intervals.size)]
            }
        }

        data class Chosen(val step: Int, val offset: Int)
        val chosen = mutableListOf<Chosen>()
        var lastStep = -2
        for (step in 0 until pocket.steps) {
            if (pocket.strong(step)) continue // never on a strong hit
            if (step - lastStep < 2) continue // breathing room between bass notes
            val empty = pocket.strength[step] == 0f
            val density = when {
                step % 8 == 0 -> if (empty) 0.85f else 0.5f // half-bar anchors want a note
                step % 4 == 0 -> if (empty) 0.55f else 0.25f
                else -> if (empty) 0.28f else 0.10f
            }
            if (rnd.nextFloat() < density) {
                chosen += Chosen(step, pickOffset())
                lastStep = step
            }
        }
        // A bar with no answer gets its first gap anyway - silence isn't one.
        for (bar in 0 until pocket.bars) {
            if (chosen.none { it.step / 16 == bar }) {
                (bar * 16 until (bar + 1) * 16).firstOrNull { !pocket.strong(it) }
                    ?.let { chosen += Chosen(it, 0) }
            }
        }
        chosen.sortBy { it.step }
        require(chosen.isNotEmpty()) { "the groove leaves no gaps to answer in" }

        val notes = chosen.mapIndexed { i, c ->
            val nextStep = chosen.getOrNull(i + 1)?.step ?: pocket.steps
            val base = 0.72f + (if (c.step % 16 == 0) 0.16f else 0f) + (rnd.nextFloat() - 0.5f) * 0.12f
            Mpc3Note(
                note = rootMidi + c.offset,
                timePulses = pocket.timeOf(c.step),
                velocity = (base * pocket.accent(c.step)).coerceIn(0.3f, 1f),
                lengthPulses = (minOf(nextStep - c.step, 3).coerceAtLeast(1)) * pocket.s16,
            )
        }

        // The bass note itself: the Velvet BASS engine tuned to the key's
        // root (its TUNE macro walks semitones up from A1 = 55 Hz), held
        // long enough that OneNote hears the pitch with confidence.
        val name = "${kit.name} Answer"
        val tune = (rootMidi - 33) / 24f
        val macros = Velvet.defaults(VelvetVoice.BASS) + mapOf("TUNE" to tune, "DECAY" to 0.7f)
        val note: Snip = Velvet.render(VelvetVoice.BASS, macros)
        val one = OneNote.program(name, note)

        return Derived(
            name = name,
            one = one,
            clip = Mpc3Clip(name, groove.bars, notes.sortedBy { it.timePulses }),
            key = key,
            seed = seed,
        )
    }

    // ---- the band ----------------------------------------------------------

    /**
     * The bass plus its sidemen. The bass is bit-identical to [derive]
     * with the same seed — asking for the band never rewrites the answer.
     */
    fun band(kit: Kit, groove: Mpc3Clip, seed: Int = 1): Band {
        val bass = derive(kit, groove, seed)
        val pocket = Pocket(groove)
        val key = bass.key
        val bassSteps = bass.clip.notes
            .map { (((it.timePulses + pocket.s16 / 2) / pocket.s16) % pocket.steps).toInt() }
            .toSet()
        return Band(
            bass = bass,
            stabs = stabs(kit, key, pocket, bassSteps, Random(seed * 31 + 1)),
            shaker = shaker(kit, key, pocket, Random(seed * 31 + 2)),
        )
    }

    /**
     * Sparse triads — root, the scale's own third, the fifth — on gaps the
     * bass leaves open too. A comp, not a solo: at most one stab per
     * couple of steps, and every bar pair gets at least one.
     */
    private fun stabs(kit: Kit, key: KeySpec, pocket: Pocket, bassSteps: Set<Int>, rnd: Random): Sideman? {
        val intervals = key.scale.intervals
        val third = if (3 in intervals) 3 else 4
        val fifth = 7
        val rootMidi = 48 + key.rootSemitone // C3..B3 - above the bass, under the melody

        val chosen = mutableListOf<Int>()
        var last = -4
        for (step in 0 until pocket.steps) {
            if (pocket.strong(step) || step in bassSteps) continue
            if (step - last < 4) continue
            val p = if (step % 4 == 2) 0.5f else 0.12f // the and-of-the-beat is where a stab lives
            if (rnd.nextFloat() < p) {
                chosen += step
                last = step
            }
        }
        for (barPair in 0 until (pocket.bars + 1) / 2) {
            val range = barPair * 32 until minOf((barPair + 1) * 32, pocket.steps)
            if (chosen.none { it in range }) {
                range.firstOrNull { !pocket.strong(it) && it !in bassSteps }?.let { chosen += it }
            }
        }
        if (chosen.isEmpty()) return null
        chosen.sort()

        val notes = chosen.flatMap { step ->
            val vel = (0.55f + (rnd.nextFloat() - 0.5f) * 0.1f) * pocket.accent(step)
            listOf(0, third, fifth).map { iv ->
                Mpc3Note(
                    note = rootMidi + iv,
                    timePulses = pocket.timeOf(step),
                    velocity = vel.coerceIn(0.25f, 1f),
                    lengthPulses = 2 * pocket.s16,
                )
            }
        }
        val name = "${kit.name} Answer Stabs"
        val tune = (rootMidi - 45) / 24f // Tonewheel's TUNE walks semitones up from A2 = 110 Hz
        val macros = Tonewheel.defaults(TonewheelVoice.STAB) + mapOf("TUNE" to tune)
        val one = OneNote.program(name, Tonewheel.render(TonewheelVoice.STAB, macros))
        return Sideman(name, one, Mpc3Clip(name, pocket.bars, notes.sortedBy { it.timePulses }))
    }

    /**
     * The tick: a single high chip note on every off-16th the groove
     * leaves completely free — steady like a shaker, leaning with the
     * feel, soft against the kit.
     */
    private fun shaker(kit: Kit, key: KeySpec, pocket: Pocket, rnd: Random): Sideman? {
        val tickMidi = 69 + key.rootSemitone // A4.. - up out of the drums' way
        val free = (0 until pocket.steps).filter { it % 2 == 1 && pocket.strength[it] == 0f }
        if (free.isEmpty()) return null
        val notes = free.map { step ->
            val vel = (if ((step / 2) % 2 == 0) 0.5f else 0.36f) + (rnd.nextFloat() - 0.5f) * 0.08f
            Mpc3Note(
                note = tickMidi,
                timePulses = pocket.timeOf(step),
                velocity = (vel * pocket.accent(step)).coerceIn(0.15f, 0.8f),
                lengthPulses = pocket.s16,
            )
        }
        val name = "${kit.name} Answer Shaker"
        val tune = (tickMidi - 57) / 24f // Velvet CHIP's TUNE walks semitones up from A3 = 220 Hz
        val macros = Velvet.defaults(VelvetVoice.CHIP) + mapOf("TUNE" to tune, "DECAY" to 0.3f)
        val one = OneNote.program(name, Velvet.render(VelvetVoice.CHIP, macros))
        return Sideman(name, one, Mpc3Clip(name, pocket.bars, notes))
    }
}

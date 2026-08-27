package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.Kit
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
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
 * Deterministic per seed — reroll with another one.
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

    fun derive(kit: Kit, groove: Mpc3Clip, seed: Int = 1): Derived {
        val key = requireNotNull(kit.key) {
            "the kit doesn't know its key - chop with --key auto, or retune it into one"
        }
        require(groove.notes.isNotEmpty()) { "the groove has no notes to answer" }

        val s16 = Mpc3Clip.PULSES_PER_16TH
        val steps = groove.bars * 16
        val strength = FloatArray(steps)
        for (n in groove.notes) {
            val pos = (((n.timePulses + s16 / 2) / s16) % steps).toInt()
            if (n.velocity > strength[pos]) strength[pos] = n.velocity
        }
        val strongCut = STRONG_FRACTION * groove.notes.maxOf { it.velocity }

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
        for (step in 0 until steps) {
            if (strength[step] >= strongCut) continue // never on a strong hit
            if (step - lastStep < 2) continue // breathing room between bass notes
            val empty = strength[step] == 0f
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
        for (bar in 0 until groove.bars) {
            if (chosen.none { it.step / 16 == bar }) {
                (bar * 16 until (bar + 1) * 16).firstOrNull { strength[it] < strongCut }
                    ?.let { chosen += Chosen(it, 0) }
            }
        }
        chosen.sortBy { it.step }
        require(chosen.isNotEmpty()) { "the groove leaves no gaps to answer in" }

        // The donor's feel: its offset/accent where it played this 16th
        // position, its parity lean generalised where it didn't.
        val template = GrooveFeel.extract(groove)
        fun lean(values: List<Long?>, parity: Int): Long? =
            values.filterIndexed { i, v -> i % 2 == parity && v != null }
                .filterNotNull().sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val evenLean = lean(template.offsets, 0)
        val oddLean = lean(template.offsets, 1)
        fun accentLean(parity: Int): Float? =
            template.accents.filterIndexed { i, v -> i % 2 == parity && v != null }
                .filterNotNull().let { if (it.isEmpty()) null else it.average().toFloat() }
        val evenAccent = accentLean(0)
        val oddAccent = accentLean(1)

        val limit = groove.bars * Mpc3Clip.PULSES_PER_BAR
        val notes = chosen.mapIndexed { i, c ->
            val pos16 = c.step % GrooveFeel.POSITIONS
            val offset = template.offsets[pos16]
                ?: (if (c.step % 2 == 0) evenLean else oddLean) ?: 0L
            val accent = template.accents[pos16]
                ?: (if (c.step % 2 == 0) evenAccent else oddAccent) ?: 1f
            val nextStep = chosen.getOrNull(i + 1)?.step ?: steps
            val base = 0.72f + (if (c.step % 16 == 0) 0.16f else 0f) + (rnd.nextFloat() - 0.5f) * 0.12f
            Mpc3Note(
                note = rootMidi + c.offset,
                timePulses = (c.step * s16 + offset).coerceIn(0L, limit - 1),
                velocity = (base * accent).coerceIn(0.3f, 1f),
                lengthPulses = (minOf(nextStep - c.step, 3).coerceAtLeast(1)) * s16,
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
}

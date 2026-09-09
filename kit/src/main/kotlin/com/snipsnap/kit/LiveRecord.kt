package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File

/**
 * Playing a beat in — the pure arithmetic that turns pad hits at wall-clock
 * times into notes at musical positions. GROOVE's clock hands this elapsed
 * seconds and a tempo; this hands back pulses, exactly as [ReadGroove.read]
 * (`shell/.../ReadGroove.kt:57-85`) turns a heard transient's frame into a
 * pulse — same shape, touch instead of audio.
 *
 * True time, always. Every capture path in this codebase stores what
 * actually happened and applies the grid later, on request, via
 * [GrooveEdit.quantized] — see the live-record plan's Design Question 2.
 * Nothing in this file snaps a hit to a step.
 */
object LiveRecord {

    /**
     * Seconds since the take armed -> pulses at [bpm], 960 PPQ. The same
     * `stepsPerSecond`-shaped formula GROOVE's own playback loop uses
     * (`bpm / 60.0 * 4.0` steps/sec, `GrooveScreen.kt:381-382`) and
     * [CapturedGroove.clip] / [ReadGroove.read] already apply frame-side
     * (`bpm / 60.0 * 960.0`, `CapturedGroove.kt:61`) — expressed here in
     * pulses per elapsed second directly, since a touch hit has no frame,
     * only a clock reading. 960.0 is 960 PPQ, not a fresh constant: it's
     * the same literal every pulse conversion in this codebase already
     * uses (no shared "pulses per quarter" constant exists to reuse).
     */
    fun pulsesFor(elapsedSeconds: Double, bpm: Float): Long =
        Math.round(elapsedSeconds * (bpm / 60.0) * 960.0)

    /**
     * Wraps a free-time pulse position into one loop of [bars] bars — the
     * free-time analog of [GrooveEdit.quantized]'s own step-index-modulo
     * wrap (`GrooveEdit.kt:80-89`, `:95`). A hit landing a few ms past the
     * nominal loop end is normal human timing, not an edge case: it must
     * land at the top of the next pass, not be thrown away or crash the
     * take against [Mpc3Note]'s own `require(timePulses < bars *
     * PULSES_PER_BAR)` (`Mpc3TrackWriter.kt:25`, `:46`).
     *
     * The exact boundary (`timePulses == limit`) wraps to pulse 0: `limit %
     * limit == 0`, the same place [GrooveEdit.quantized]'s `% stepsInClip`
     * sends a step index that lands exactly on the loop length, and
     * musically the loop end *is* the next pass's downbeat.
     *
     * The extra `+ limit) % limit` guards a pathological negative input —
     * shouldn't occur from a monotonic elapsed-time source, but
     * [Mpc3Note]'s own `require(timePulses >= 0)` means this must never be
     * able to hand it a negative value even from a caller bug.
     */
    fun wrapped(timePulses: Long, bars: Int): Long {
        require(bars in 1..64) { "bars out of range: $bars" }
        val limit = bars.toLong() * Mpc3Clip.PULSES_PER_BAR
        return ((timePulses % limit) + limit) % limit
    }

    /** Accumulates hits during one take; not itself persisted. */
    class Take(val bars: Int) {
        private val hits = mutableListOf<Mpc3Note>()

        /** Wraps before storing — a hit is never held at an out-of-range pulse even transiently. */
        fun add(note: Int, elapsedSeconds: Double, bpm: Float, velocity: Float) {
            val pulses = wrapped(pulsesFor(elapsedSeconds, bpm), bars)
            hits += Mpc3Note(note = note, timePulses = pulses, velocity = velocity)
        }

        /**
         * Time-sorted; same-(note, pulse) collisions deduped louder-wins,
         * via [GrooveEdit.dedupeLouder] — [GrooveEdit.quantized]'s own
         * collision rule, reused rather than reimplemented, because two
         * hits landing on the exact same pulse (two pads bulk-triggered in
         * one frame during overdub is plausible) must resolve the same way
         * everywhere in this codebase.
         */
        fun notes(): List<Mpc3Note> = GrooveEdit.dedupeLouder(hits)
    }

    /**
     * A take -> a named base clip, overdubbed onto [existing] when given.
     * With no [existing], the take alone becomes the clip. With an
     * [existing] base, the take's notes ADD to it (a second take overdubs,
     * it never replaces) — same-(note, timePulses) collisions between the
     * two takes resolve by the same louder-wins rule.
     *
     * `bars` always comes from [existing] when it's given: a take is
     * always recorded against the currently-loaded base's own bar count
     * (Task 4 owns the from-scratch case, where there is no [existing]
     * yet). A [take] recorded against a different bar count than
     * [existing] is a caller bug — refused here, in words, rather than
     * left to crash unnamed inside [Mpc3Clip]'s own `require` when a
     * wrapped note from the wrong-length take falls outside the kept bars.
     */
    fun toClip(take: Take, name: String, existing: Mpc3Clip?): Mpc3Clip {
        require(existing == null || existing.bars == take.bars) {
            "take recorded against ${take.bars} bar(s) but the base is ${existing?.bars} - can't merge"
        }
        val bars = existing?.bars ?: take.bars
        val merged = existing?.notes.orEmpty() + take.notes()
        return Mpc3Clip(name, bars, GrooveEdit.dedupeLouder(merged))
    }

    /**
     * Lands [clip] as the kit's captured base, exactly as [ReadGroove.land]
     * does (`ReadGroove.kt:92-95`): the standard variation set is rewritten
     * from it, and an existing PROG E rides along untouched. Refuses a
     * silent take rather than landing a [Mpc3Clip] with zero notes ([clip]
     * itself allows that; landing one is never the intended action).
     */
    fun land(kitDir: File, clip: Mpc3Clip): File {
        require(clip.notes.isNotEmpty()) { "nothing recorded - the take has no notes to land" }
        val e = GrooveEdit.load(kitDir)
        return GrooveStore.save(kitDir, GrooveVariations.standard(clip) + listOfNotNull(e))
    }

    /**
     * Discards back to [preTake] (null = no base existed before this
     * take). Three branches, covering every state the base/E pair can be
     * in before a take:
     * - [preTake] non-null (base + E, or base alone): re-save the
     *   standard variations of [preTake] with whatever E is currently
     *   stored riding along, the same [ReadGroove.land] shape.
     * - [preTake] null and E exists (E alone, no base): [GrooveStore.delete]
     *   clears the take's landed clips, then [GrooveEdit.save] writes E
     *   back on its own — `GrooveStore.save(kitDir, listOfNotNull(e))`,
     *   reached through the existing store/edit API rather than a raw
     *   clip-list write.
     * - [preTake] null and no E (neither existed): [GrooveStore.delete]
     *   outright — no groove.json survives, since [GrooveStore.save]
     *   itself refuses an empty clip list.
     */
    fun undo(kitDir: File, preTake: Mpc3Clip?) {
        if (preTake != null) {
            val e = GrooveEdit.load(kitDir)
            GrooveStore.save(kitDir, GrooveVariations.standard(preTake) + listOfNotNull(e))
            return
        }
        val e = GrooveEdit.load(kitDir)
        GrooveStore.delete(kitDir)
        if (e != null) GrooveEdit.save(kitDir, e)
    }
}

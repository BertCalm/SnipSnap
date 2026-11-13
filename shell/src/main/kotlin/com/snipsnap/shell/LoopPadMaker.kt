package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.MurmurBody
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Patch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException

/**
 * The shared keys instrument for HOLD-loop engines (the owner's call, 2026-11-04:
 * qualify first, then build the qualifiers through one maker). An engine whose top
 * HOLD step is a loop that closes on itself, plays the key it is asked for and holds
 * a steady level ([LoopInstrumentScan]'s three readings) becomes an instrument with
 * no engine-specific code: [SynthRoot] moves the patch to each zone's note, HOLD is
 * forced to its top step, and the loop it renders is the zone.
 *
 * Each zone is the loop twice, back to back, with the sustain loop starting at the
 * second copy, [MercuryPadMaker]'s shape: a keygroup layer whose loop starts at frame
 * 0 reads as no loop at all. There is no attack: a held key plays the loop from its
 * first frame. RELEASE alone, the fade the MPC's keygroup player runs after a key lifts.
 *
 * Only the voices in [QUALIFIED] are offered. They passed the scan at three notes
 * each; every zone is checked again as it renders ([renderZone] refuses a click),
 * and none has been heard yet: each waits on the owner's listen.
 */
object LoopPadMaker {

    /** The same range every held pad settled on ([ResinPadMaker], [MercuryPadMaker]). */
    const val RELEASE_MIN_SECONDS = 0.1f
    const val RELEASE_MAX_SECONDS = 1.5f

    val RELEASE: Knob = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, 0.6f, exponential = true)

    /** A join louder in its high frequencies than this many times the sound's own 95th percentile is a click. */
    const val MAX_WRAP_RATIO = 2.0

    /** Zones every minor third, up to an octave either side of the patch's own root. */
    private const val STEP = 3
    private const val SPAN = 12

    /**
     * Engine to voices that passed [LoopInstrumentScan] on 2026-11-04 with their first
     * factory preset: a clean wrap, the key's pitch and a level steady within 6 dB at
     * the lowest, root and highest note. EMBOUCHURE reads exactly an octave above every
     * key, the same at each note: a weak fundamental can fool the detector that way, so
     * its octave waits on the owner's ear. FLOTILLA's CROSSWAVE and GATHER are left out
     * because their octave jumps between notes. ARCO and BORE passed too and left on
     * 2026-11-06 for makers of their own, [ArcoPadMaker] (each key begins with the bow's
     * stroke) and [BorePadMaker] (each zone plays the note's own attack).
     */
    val QUALIFIED: Map<String, Set<String>> = mapOf(
        "AEROSTAT" to setOf("FLOAT"),
        "ALLOY" to setOf("BASS", "KEY", "PLUCK", "BRASS", "STRING", "SOFT"),
        "COROLLA" to setOf("TONGUE", "CHOIR", "CHATTER", "ORBIT"),
        "EMBOUCHURE" to setOf("REED", "BRASS", "BASS", "SOFT", "BOW", "WHISTLE"),
        "FLOTILLA" to setOf("KNOCK", "DRIFT"),
        "LACQUER" to setOf("GLASS", "KEY", "BRASS", "BASS", "PLUCK", "SILK"),
        "MURMUR" to setOf("BASS", "KEY", "PLUCK", "METAL"),
        "PITCHWHEEL" to setOf("PLUCK", "DRAW", "THAWED", "TURN"),
        "SKITTER" to setOf("BASS", "LEAD", "HOLLOW", "FLUTTER", "CHIRP", "FRAY"),
        "STOOP" to setOf("TINE", "CLAV", "ORGAN", "LEAD"),
        "TANGLE" to setOf("LEAD", "HOLLOW", "PULSE", "METAL"),
        "THAW" to setOf("BRITTLE", "RUNNER", "MELT", "CHANNEL", "FROST", "SHEET"),
        "UNDERTOW" to setOf("KNOCK", "BREATH", "FLUTTER", "SEAL", "HOLLOW", "SURGE"),
    )

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(
        val patch: Patch,
        val releaseSeconds: Float = RELEASE.default,
    ) {
        init {
            require(releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    /** Whether [engine] has any voice this maker offers; a cheap gate before a patch is built. */
    fun offers(engine: String): Boolean = engine in QUALIFIED

    /** Whether [patch] can become an instrument here: a qualified voice with a root to move. */
    fun supports(patch: Patch): Boolean =
        // Revised PLUCK sources need bounded finite/held makers; this route forces HOLD
        // and stretches descriptors across the keyboard. Legacy source recipes stay eligible.
        !(patch is MurmurPatch && patch.config.body != MurmurBody.LEGACY) &&
            patch.voiceName in QUALIFIED[patch.engine].orEmpty() && SynthRoot.of(held(patch)) != null

    /** The sheet's one stepper as a spec, clamped as [MercuryPadMaker.spec] is. */
    fun spec(patch: Patch, releaseFraction: Float): Spec =
        Spec(patch, releaseSeconds = RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    /** [patch] at HOLD's top step: the loop a held key repeats. */
    fun held(patch: Patch): Patch {
        require(!(patch is MurmurPatch && patch.config.body != MurmurBody.LEGACY)) {
            "MURMUR revised bodies require a bounded source-aware finite or held maker"
        }
        return patch.withMacros(patch.macros + ("HOLD" to 1f))
    }

    /** The zones' roots: every minor third from the patch's root, an octave either way, inside the engine's range. */
    fun zoneMidis(spec: Spec): List<Int> {
        val control = checkNotNull(SynthRoot.of(held(spec.patch))) { "${spec.patch.engine} ${spec.patch.voiceName} has no root to move" }
        val root = control.midi
        return (-SPAN..SPAN step STEP).map { root + it }.filter { it in control.range }
    }

    /**
     * One zone: the loop at [midi], mono, twice over, its sustain loop at the second
     * copy. Refused, naming the zone, when the loop's join clicks ([wrapRatio]) rather
     * than shipping one. [cancelled] is asked before and after the render; the render
     * itself stops on its thread's interrupt, as the SYNTH preview's does.
     */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        if (cancelled()) throw CancellationException("loop instrument cancelled")
        val control = checkNotNull(SynthRoot.of(held(spec.patch))) { "${spec.patch.engine} has no root to move" }
        val rendered = control.at(midi).render()
        if (cancelled()) throw CancellationException("loop instrument cancelled")
        val loop = (if (rendered.channels == 1) rendered else Cleanup.toMono(rendered)).samples
        val ratio = wrapRatio(loop)
        require(ratio <= MAX_WRAP_RATIO) {
            "${spec.patch.engine} ${spec.patch.voiceName} at ${Scales.nameOf(midi)}: the loop's join clicks (%.1fx)".format(java.util.Locale.ROOT, ratio)
        }
        val twice = FloatArray(loop.size * 2)
        loop.copyInto(twice, 0)
        loop.copyInto(twice, loop.size)
        return KeyNote(Snip(twice, 1, rendered.sampleRate), loopStartFrame = loop.size.toLong())
    }

    /**
     * The zones, in [zoneMidis] order, as a keygroup program and the samples it names:
     * [ResinPadMaker.assemble]'s own layout, edge zones reaching nine semitones past
     * the ends and inner zones tiling at ±1.
     */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { i, midi ->
            val note = notes[i]
            val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}")
            samples[stem] = note.snip
            Keygroup(
                lowNote = (if (i == 0) midi - 9 else midi - 1).coerceIn(0, 127),
                highNote = (if (i == midis.lastIndex) midi + 9 else midi + 1).coerceIn(0, 127),
                rootNote = midi,
                layers = listOf(
                    VelocityLayer(
                        stem, note.snip.frameCount.toLong(), velStart = 0, velEnd = 127,
                        loopStartFrame = note.loopStartFrame,
                    ),
                ),
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** The middle zone's own render: two loop passes, what a held key sounds like. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        val midis = zoneMidis(spec)
        return renderZone(spec, midis[midis.size / 2], cancelled).snip
    }

    /**
     * Second-difference energy in a 32-sample window across the loop's end-to-start
     * join, over the 95th percentile of the same window at every other place in it. A
     * join that is an ordinary moment of the sound reads at or under 1; a click stands
     * out above all of them. Infinite for a loop too short to judge.
     */
    fun wrapRatio(s: FloatArray): Double {
        val n = s.size
        if (n < 2048) return Double.POSITIVE_INFINITY
        fun at(i: Int) = s[((i % n) + n) % n].toDouble()
        fun d2(i: Int): Double { val d = at(i + 1) - 2 * at(i) + at(i - 1); return d * d }
        val half = 16
        fun window(centre: Int): Double { var e = 0.0; for (i in centre - half until centre + half) e += d2(i); return e }
        val join = window(n)
        val body = (half * 2 until n - half * 2 step half).map { window(it) }.sorted()
        val p95 = body[(body.size * .95).toInt().coerceAtMost(body.lastIndex)]
        return if (p95 <= 0.0) (if (join <= 0.0) 1.0 else Double.POSITIVE_INFINITY) else join / p95
    }
}

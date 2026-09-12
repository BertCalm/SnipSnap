package com.snipsnap.kit

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The kit playing its own beat — an offline sampler render of pad WAVs at
 * clip note times, honouring levels, pans, velocities and choke groups.
 * This is what `[Previews]/` and `.xpn` previews carry, and what the app
 * shares as audio: a kit you can *hear* before you load it.
 *
 * No groove? [defaultPattern] plays something honest anyway: the core
 * classes on a two-bar backbone when the kit knows its classes, a plain
 * pad walk when it doesn't (imported kits arrive UNKNOWN).
 */
object KitPreview {

    const val RATE = 44_100
    const val DEFAULT_BPM = 92f

    /** A preview clamps tempo to a musical range, so frame math can't overflow. */
    const val MIN_BPM = 20f
    const val MAX_BPM = 400f

    /** Ring-out tail after the last bar, seconds. */
    private const val TAIL_SEC = 0.6f

    /** Choke fade, frames — a cut, but never a click. */

    fun render(
        kit: Kit,
        kitDir: File,
        clip: Mpc3Clip? = null,
        tempoBpm: Float? = null,
    ): Snip {
        require(kit.pads.isNotEmpty()) { "an empty kit has nothing to preview" }
        val groove = clip ?: GrooveStore.load(kitDir).firstOrNull() ?: defaultPattern(kit)
        // kit.tempoBpm is only validated >0 <1000, so a hostile or nonsense
        // 0.001 would blow framesPerPulse up until the frame math overflows to
        // a negative array size. A preview clamps to a musical range - it is
        // cosmetic, not the place to honour an impossible tempo.
        val bpm = (tempoBpm ?: kit.tempoBpm ?: DEFAULT_BPM).coerceIn(MIN_BPM, MAX_BPM)
        val framesPerPulse = 60.0 / bpm * RATE / 960.0

        data class Voice(
            val start: Int,
            val samples: Snip,
            val gain: Float,
            val pan: Float,
            val muteGroup: Int,
            var end: Int,
            /** Ended early by a choke, and the ramp that end carries. */
            var choked: Boolean = false,
            var fadeLen: Int = 0,
            /** Pad shape, approximated in the render (see below). Null = none. */
            val attack: Float? = null,
            val decay: Float? = null,
        )

        val cache = HashMap<String, Snip>()
        val voices = mutableListOf<Voice>()
        // Chain pads step per hit, so the preview counts them - per zone
        // lane on a grid, so each zone cycles its own takes independently.
        val hitsByLane = HashMap<Int, Int>()
        for (note in groove.notes.sortedBy { it.timePulses }) {
            // Mpc3Note.slotFor, not `note - 36 + 1`: the map wraps, so
            // notes 0..35 are pads 93..128. Subtracting alone gives those
            // a negative slot, and the pad - the whole ring - renders silent.
            val slot = Mpc3Note.slotFor(note.note)
            val pad = kit.pad(slot) ?: continue
            val whole = cache.getOrPut(pad.sampleFile) { WavReader.read(File(kitDir, pad.sampleFile)) }
            // Slice Motion, audible before the card: velocity picks the
            // zone (grids grade soft->hard), then hit k in that lane plays
            // slice (base + k mod cycle), same as the hardware's increment.
            val snip = pad.chain?.let { c ->
                val zone = c.zoneFor((note.velocity * 127).roundToInt().coerceIn(0, 127))
                val base = zone?.baseSlice ?: 0
                val cycle = zone?.cycle ?: c.cycle
                val hit = hitsByLane.merge(slot * 1000 + base, 1, Int::plus)!! - 1
                val w = c.window(base + hit % cycle, whole.frameCount.toLong())
                val from = w.first.toInt().coerceIn(0, whole.frameCount)
                val to = (w.last + 1).toInt().coerceIn(from, whole.frameCount)
                Snip(
                    whole.samples.copyOfRange(from * whole.channels, to * whole.channels),
                    whole.channels,
                    whole.sampleRate,
                )
            } ?: whole
            val start = (note.timePulses * framesPerPulse).roundToInt()
            val voice = Voice(
                start = start,
                samples = snip,
                gain = pad.level * (0.35f + 0.65f * note.velocity),
                pan = pad.pan,
                muteGroup = pad.muteGroup,
                end = start + snip.frameCount,
                attack = pad.attack,
                decay = pad.decay,
            )
            if (pad.muteGroup != 0) {
                // The kit rule, honoured in the render: a new voice in the
                // group chokes everything still ringing in it.
                //
                // The ramp spans whatever is left when less than a full
                // fade remains, and a voice already fading keeps the ramp
                // it is on - the same two rules `OrbitEngine` plays by, so
                // the preview and the live engine agree on a pattern.
                voices.filter { it.muteGroup == pad.muteGroup && it.end > start && !it.choked }
                    .forEach {
                        val len = min(AutoPlace.CHOKE_FADE, it.end - start)
                        it.end = start + len
                        it.fadeLen = len
                        it.choked = true
                    }
            }
            voices += voice
        }
        require(voices.isNotEmpty()) { "no clip note lands on a pad this kit has" }

        val totalFrames = ((groove.lengthPulses * framesPerPulse).roundToInt() +
            (TAIL_SEC * RATE).toInt()).coerceAtLeast(voices.maxOf { min(it.end, it.start + it.samples.frameCount) })
        val out = FloatArray(totalFrames * 2)

        for (v in voices) {
            val frames = min(v.samples.frameCount, v.end - v.start)
            // Equal-power pan.
            val left = sqrt(1.0 - v.pan.toDouble()).toFloat() * v.gain
            val right = sqrt(v.pan.toDouble()).toFloat() * v.gain
            // The pad shape, approximated so a tighten is audible before
            // the card - PadShape's reading, the same one the SFZ writer
            // and the phone's HIT audition use. The hardware's exact
            // envelope curves are its own; this render is honest about
            // being a preview.
            val attackFrames = PadShape.attackFrames(v.attack, RATE)
            val decayEnd = PadShape.decayEnd(v.decay, v.samples.frameCount)
            for (i in 0 until frames) {
                val at = v.start + i
                if (at >= totalFrames) break
                // Choke fade: the voice ramps out across its own fade,
                // which is a full one or whatever the sample had left.
                var fade = if (v.choked && v.fadeLen > 0 && i >= frames - v.fadeLen) {
                    (frames - i).toFloat() / v.fadeLen
                } else {
                    1f
                }
                if (i >= decayEnd) break
                fade *= PadShape.gainAt(i, attackFrames, decayEnd)
                val s = sampleMono(v.samples, i) * fade
                out[at * 2] += s * left
                out[at * 2 + 1] += s * right
            }
        }

        // Headroom, then a hard ceiling: a preview must never clip harshly.
        var peak = 0f
        for (s in out) {
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        if (peak > 0.95f) {
            val k = 0.95f / peak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, 2, RATE)
    }

    private fun sampleMono(snip: Snip, frame: Int): Float =
        if (snip.channels == 1) {
            snip.samples[frame]
        } else {
            var sum = 0f
            for (ch in 0 until snip.channels) sum += snip.samples[frame * snip.channels + ch]
            sum / snip.channels
        }

    /**
     * Two honest bars when the kit has no groove of its own: kick on the
     * one and three, snare on two and four, hats on the eighths — using
     * whatever classes the kit actually has; a class-less kit gets a plain
     * walk over its pads, sixteenth by sixteenth.
     */
    fun defaultPattern(kit: Kit): Mpc3Clip {
        val bySlot = { dc: DrumClass -> kit.pads.firstOrNull { it.drumClass == dc }?.slot }
        val kick = bySlot(DrumClass.KICK)
        val snare = bySlot(DrumClass.SNARE) ?: bySlot(DrumClass.CLAP)
        val hat = bySlot(DrumClass.HAT_CLOSED) ?: bySlot(DrumClass.HAT_OPEN) ?: bySlot(DrumClass.PERC)
        val s16 = Mpc3Clip.PULSES_PER_16TH

        val notes = mutableListOf<Mpc3Note>()
        if (kick != null || snare != null || hat != null) {
            for (bar in 0 until 2) {
                val b = bar * Mpc3Clip.PULSES_PER_BAR
                kick?.let {
                    notes += Mpc3Note(Mpc3Note.noteFor(it), b, 0.9f)
                    notes += Mpc3Note(Mpc3Note.noteFor(it), b + 8 * s16, 0.85f)
                }
                snare?.let {
                    notes += Mpc3Note(Mpc3Note.noteFor(it), b + 4 * s16, 0.85f)
                    notes += Mpc3Note(Mpc3Note.noteFor(it), b + 12 * s16, 0.9f)
                }
                hat?.let {
                    for (e in 0 until 8) {
                        notes += Mpc3Note(Mpc3Note.noteFor(it), b + e * 2L * s16, if (e % 2 == 0) 0.6f else 0.4f)
                    }
                }
            }
        } else {
            // The pad walk: every pad in slot order, a 16th each.
            kit.pads.sortedBy { it.slot }.take(32).forEachIndexed { i, pad ->
                notes += Mpc3Note(Mpc3Note.noteFor(pad.slot), i * s16, 0.8f)
            }
        }
        val bars = ((notes.maxOf { it.timePulses } / Mpc3Clip.PULSES_PER_BAR) + 1).toInt().coerceIn(1, 64)
        return Mpc3Clip("${kit.name} Preview", bars, notes)
    }
}

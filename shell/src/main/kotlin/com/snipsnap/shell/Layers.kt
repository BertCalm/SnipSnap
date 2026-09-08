package com.snipsnap.shell

import com.snipsnap.audio.Separate
import com.snipsnap.audio.Snip

/**
 * SPLIT's desk: one sound taken apart, then put back together on your
 * terms.
 *
 * [Separate.stn] already does the hard half — a sound's sines, its
 * transient and the air between, three fuzzy masks that sum to one, so
 * the three parts **sum back to the input**. Everything here is what
 * happens after: three faders, three REVERSE buttons, mute and solo, and
 * an exact offline render of whatever they add up to.
 *
 * Two things follow from the parts summing back:
 *
 *  - **The desk is transparent at rest.** All three at [UNITY], nothing
 *    muted, nothing reversed, and [render] returns the source itself,
 *    sample for sample. There is a test that says so. A mixer you cannot
 *    hear when it is doing nothing is the only kind worth printing
 *    through.
 *  - **PRINT needs no capture.** The mix is completely determined by the
 *    three buffers and the desk, so a print is [render] — deterministic,
 *    the same every time, and testable — rather than a recording of the
 *    audio thread with all the timing that implies. Live playback still
 *    goes through the native engine; the two agree because reversing a
 *    window frame by frame is exactly what the engine's backwards read
 *    does over the whole window.
 *
 * Nothing here clips or normalises. Above [UNITY] a fader is you asking
 * for more of a part than the sound contained, and the result says so
 * honestly — the screen can read the peak and warn; the arithmetic does
 * not quietly turn your mix down.
 */
object Layers {

    /** As `snipsnap dissect` already names them, so one split has one vocabulary. */
    enum class Part(val label: String) {
        /** The body: what holds a line across time. */
        SINES("SINES"),

        /** The attack: what covers every bin for an instant. */
        TRANSIENT("TRANSIENT"),

        /** The noise between: neither a held line nor a broadband instant. */
        AIR("AIR"),
    }

    /** A fader doing nothing. Three of these render the source itself. */
    const val UNITY = 1f

    /** The top of a fader: +6 dB, for lifting a part that was quiet to begin with. */
    const val MAX_LEVEL = 2f

    /** One channel strip's state. */
    data class Strip(
        val level: Float = UNITY,
        val reverse: Boolean = false,
        val muted: Boolean = false,
        val soloed: Boolean = false,
    ) {
        init {
            // NaN fails the range test, which is what we want: a fader is
            // never "unknown", and a NaN gain would poison the whole mix.
            require(level in 0f..MAX_LEVEL) { "a fader is 0..$MAX_LEVEL, got $level" }
        }
    }

    /** The three strips together. */
    data class Desk(
        val sines: Strip = Strip(),
        val transient: Strip = Strip(),
        val air: Strip = Strip(),
    ) {
        fun strip(part: Part): Strip = when (part) {
            Part.SINES -> sines
            Part.TRANSIENT -> transient
            Part.AIR -> air
        }

        /** The desk with one strip changed; everything else as it was. */
        fun with(part: Part, change: (Strip) -> Strip): Desk = when (part) {
            Part.SINES -> copy(sines = change(sines))
            Part.TRANSIENT -> copy(transient = change(transient))
            Part.AIR -> copy(air = change(air))
        }

        /** Any strip soloed at all — the thing that silences the others. */
        val anySoloed: Boolean get() = sines.soloed || transient.soloed || air.soloed

        /**
         * What [part] actually contributes. Mute wins over solo: muting is
         * the more deliberate of the two, so a strip that is both stays
         * off rather than surprising you when you solo it.
         */
        fun gain(part: Part): Float {
            val s = strip(part)
            if (s.muted) return 0f
            if (anySoloed && !s.soloed) return 0f
            return s.level
        }

        /** Nothing is getting through: the render would be silence. */
        val silent: Boolean get() = Part.entries.all { gain(it) <= 0f }

        /** True when nothing is doing anything — [render] would return the source. */
        val atRest: Boolean
            get() = Part.entries.all { p ->
                val s = strip(p)
                s.level == UNITY && !s.reverse && !s.muted && !s.soloed
            }
    }

    /** The split's three parts, in strip order. */
    fun parts(stn: Separate.Stn): List<Pair<Part, Snip>> = listOf(
        Part.SINES to stn.sines,
        Part.TRANSIENT to stn.transients,
        Part.AIR to stn.noise,
    )

    /**
     * The mix, exactly: every part at its gain, reversed where its strip
     * says so. This is what PRINT writes, and — bar the engine's own
     * interpolation — what the phone is playing.
     */
    fun render(stn: Separate.Stn, desk: Desk): Snip {
        val parts = parts(stn)
        val shape = parts[0].second
        require(shape.samples.isNotEmpty()) { "the split is empty" }
        require(
            parts.all {
                it.second.channels == shape.channels &&
                    it.second.sampleRate == shape.sampleRate &&
                    it.second.samples.size == shape.samples.size
            },
        ) { "the three parts must be one shape; they came from one split" }

        val out = FloatArray(shape.samples.size)
        for ((part, snip) in parts) {
            val gain = desk.gain(part)
            if (gain <= 0f) continue
            if (desk.strip(part).reverse) {
                addReversed(out, snip, gain)
            } else {
                for (i in out.indices) out[i] += snip.samples[i] * gain
            }
        }
        return Snip(out, shape.channels, shape.sampleRate)
    }

    /**
     * A part read back to front. Frames are reversed, not samples: a
     * stereo frame keeps its left beside its right, or the image would
     * flip along with the time.
     */
    fun reversed(snip: Snip): Snip {
        val out = FloatArray(snip.samples.size)
        addReversed(out, snip, 1f)
        return Snip(out, snip.channels, snip.sampleRate)
    }

    private fun addReversed(out: FloatArray, snip: Snip, gain: Float) {
        val channels = snip.channels
        val frames = snip.frameCount
        for (f in 0 until frames) {
            val src = (frames - 1 - f) * channels
            val dst = f * channels
            for (c in 0 until channels) out[dst + c] += snip.samples[src + c] * gain
        }
    }
}

package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip

/**
 * The capture ring as a SURFACE voice: RING freezes the last few seconds
 * of whatever the phone is hearing - the room through the mic, or another
 * app's playback through APP AUDIO - into the engine's first source slot,
 * where PAD ◄ ► would otherwise put a kit pad. A snapshot, taken once on
 * the tap: the ring keeps rolling underneath, the voice does not follow
 * it, and the next tap takes a fresh one. That is the S-4's TAPE mode
 * with the one thing an S-4 cannot do, granulate the video that is
 * playing right now.
 *
 * Pure: the snapshot in, the voice out. The app side is a few lines -
 * `MicSessionService.snapshotTail` for [frames] of audio, this to make
 * a voice of them, `SurfaceEngine.load` to play it - and nothing is
 * written anywhere: a freeze is a moment in RAM, not a tape on the shelf,
 * so it is not in `surface.json` either. Reopening the kit brings the
 * pad back.
 *
 * EVERY BAR takes the freeze again on every bar line ([Refresh.EVERY_BAR],
 * [barIndex]): the voice tracks whatever is playing, a bar behind, so a
 * finger on the pad plays the track in the next app as it goes by. The
 * bar is the modulators' own bar ([barSeconds]), counted from the same
 * origin, so a RANDOM on X and the re-freeze land on the same line.
 */
object RingSlot {

    /** How the ring becomes the voice: once, on the tap, or again on every bar line. */
    enum class Refresh { ONCE, EVERY_BAR }

    /**
     * One bar at [bpm], in seconds - [Modulator]'s own one bar, which is
     * [PrintLength]'s, so BARS on PRINT, RATE on MOD and EVERY BAR here
     * cannot disagree about how long a bar is. No tempo runs at GROOVE's
     * default, as they do.
     */
    fun barSeconds(bpm: Float?): Float = Modulator.periodSeconds(Modulator.DEFAULT_RATE_INDEX, bpm)

    /**
     * Which bar [seconds] from the origin falls in, for a bar of
     * [barSeconds]: the freeze fires when this changes between two frames.
     * Time before the origin, or none at all, is the first bar.
     */
    fun barIndex(seconds: Double, barSeconds: Float): Long {
        require(barSeconds.isFinite() && barSeconds > 0f) { "a bar is a positive number of seconds, got $barSeconds" }
        val t = if (seconds.isFinite() && seconds > 0.0) seconds else 0.0
        return kotlin.math.floor(t / barSeconds).toLong()
    }

    /** How much of the ring a freeze takes: a phrase, not the whole minute the ring holds. */
    const val SECONDS = 4

    /** [SECONDS] of the ring at its rate - what to ask the ring for. */
    fun frames(sampleRate: Int): Int {
        require(sampleRate > 0) { "the ring's rate is positive, got $sampleRate" }
        return SECONDS * sampleRate
    }

    /**
     * What the PAD readout wears while the voice is the ring: furniture,
     * not a sentence, and the voice's own length rather than [SECONDS] -
     * what was kept, not what was asked for.
     */
    fun label(seconds: Float): String = "RING ${"%.1f".format(java.util.Locale.ROOT, seconds)} S"

    /**
     * The snapshot as a voice. `Cleanup`'s own chain and nothing else: DC
     * corrected, dead air trimmed off both ends, levelled to the shelf's
     * peak, click-guarded at the edges - the same treatment a SNIP gets
     * before the doctor, minus the doctor. `CaptureDoctor` refuses a
     * capture it judges distorted, which is the right door for a pad going
     * onto the shelf and the wrong one here: a hot moment of a live mix
     * is exactly what RING is for, and a freeze that refused it would be
     * a button that works on quiet music only.
     *
     * Null when there is nothing in it - a quiet room, or a ring that only
     * just started - so the caller can say so rather than load silence.
     */
    fun freeze(raw: FloatArray, sampleRate: Int): Snip? {
        require(sampleRate > 0) { "the ring's rate is positive, got $sampleRate" }
        if (raw.isEmpty()) return null
        val voice = Cleanup.process(Snip(raw, channels = 1, sampleRate = sampleRate))
        // Two frames is the engine's own floor for a loaded slot
        // (SurfaceEngine::slotLoaded); anything under it plays as silence,
        // which is the answer "nothing" gives, not the answer RING should.
        return voice.takeIf { it.frameCount >= MIN_FRAMES }
    }

    /** The shortest voice worth loading: below this the engine treats the slot as empty. */
    const val MIN_FRAMES = 2
}

package com.snipsnap.shell

import kotlin.math.abs
import kotlin.math.floor

/**
 * The TAPE screen's transport — the drag-audio-under-a-fixed-needle editor,
 * ported coefficient-for-coefficient from `prototype/tapedeck.html`, where
 * this exact physics has been played with and felt right twice (it survived
 * into both working phone prototypes).
 *
 * The model is a per-audio-frame simulation: [step] advances it by N frames
 * and returns whatever noteworthy happened. The app calls it from the audio
 * callback (using [position]'s sub-frame value to resample the tape for
 * varispeed playback) or from a frame clock when silent. All rate constants
 * are per-frame at the buffer's own sample rate, exactly as the prototype
 * ran them per-sample.
 *
 * Modes:
 * - **IDLE** — transport: speed eases toward [targetSpeed] (tape spin-up /
 *   wind-down; 1.0 = play, ±7 = wind, −11 = pencil rewind).
 * - **DRAG** — finger on the tape: position eases toward the drag target.
 * - **COAST** — flicked: speed decays by friction, then snaps to the
 *   nearest onset when it dies.
 * - **GLIDE** — seeking (overview tap, or post-stop onset snap): position
 *   eases toward a target and locks on within [GLIDE_LOCK_FRAMES].
 */
class TapeDeckModel(
    /** Mono analysis buffer — zero-crossing snap reads it. */
    private val tape: FloatArray,
    val sampleRate: Int = 44_100,
    /** Detected onset frames, for snapping. Sorted ascending. */
    private val onsets: IntArray = IntArray(0),
) {

    enum class Mode { IDLE, DRAG, COAST, GLIDE }

    sealed interface Event {
        /** Coast died near an onset; the deck is gliding onto it. */
        data class SnappingToOnset(val frame: Int) : Event
        /** Loop preview wrapped from OUT back to IN: whatever plays the tape restarts there. */
        object Looped : Event
        /** Play ran off the end of the tape and stopped. */
        data object HitEnd : Event
        /** Pencil rewind reached the top of the tape. */
        data object PencilDone : Event
    }

    val lengthFrames: Int = tape.size

    var mode: Mode = Mode.IDLE
        private set

    /** Tape position under the needle, in frames (fractional). */
    var position: Double = 0.0
        private set

    /** Current tape speed in frames per frame (1.0 = normal play). */
    var speed: Double = 0.0
        private set

    /** What IDLE mode's speed is easing toward. */
    var targetSpeed: Double = 0.0
        private set

    var playing: Boolean = false
        private set

    var pencilActive: Boolean = false
        private set

    // Toggles, exactly the prototype's defaults.
    var snapToOnset: Boolean = true
    var snapToZero: Boolean = true
    var loopPreview: Boolean = false

    /** Selection in frames; -1 = unset. */
    var inFrame: Int = -1
        private set
    var outFrame: Int = -1
        private set

    val hasSelection: Boolean get() = inFrame >= 0 && outFrame > inFrame

    // Zoom — the px-per-second ladder the drag gesture converts through.
    private var zoomIdx = 0
    val pxPerSec: Int get() = ZOOM_PX_PER_SEC[zoomIdx]
    val zoomLabel: String get() = "ZOOM x${1 shl zoomIdx}"
    fun cycleZoom() {
        zoomIdx = (zoomIdx + 1) % ZOOM_PX_PER_SEC.size
    }

    /** Triple-tapping the position LCD flips it to the mechanical counter. */
    var odometer: Boolean = false
        private set
    fun toggleOdometer() {
        odometer = !odometer
    }

    private var dragTarget = 0.0
    private var glideTarget = 0.0
    private var dragVelocity = 0.0 // tape-seconds per wall second, EMA

    // ---------- simulation ----------

    /**
     * Advance [frames] audio frames. Returns the events that fired, in
     * order. Between events this is exactly the prototype's inner loop.
     */
    fun step(frames: Int): List<Event> {
        if (lengthFrames == 0) return emptyList()
        val events = mutableListOf<Event>()
        repeat(frames) {
            when (mode) {
                Mode.DRAG -> position += (dragTarget - position) * DRAG_EASE
                Mode.GLIDE -> {
                    position += (glideTarget - position) * GLIDE_EASE
                    if (abs(glideTarget - position) < GLIDE_LOCK_FRAMES) {
                        position = glideTarget
                        mode = Mode.IDLE
                        speed = 0.0
                    }
                }
                Mode.COAST -> {
                    position += speed
                    speed *= COAST_FRICTION
                    if (abs(speed) < COAST_STOP) {
                        mode = Mode.IDLE
                        speed = 0.0
                        snapAfterStop()?.let { events += Event.SnappingToOnset(it) }
                    }
                }
                Mode.IDLE -> {
                    speed += (targetSpeed - speed) * SPIN_EASE
                    if (targetSpeed == 0.0 && abs(speed) < SPEED_EPSILON) speed = 0.0
                    position += speed
                }
            }

            // Loop preview: playing at normal speed inside a selection wraps.
            if (loopPreview && hasSelection && mode == Mode.IDLE && targetSpeed == PLAY_SPEED &&
                position >= outFrame
            ) {
                position = inFrame + (position - outFrame)
                events += Event.Looped
            }

            if (position < 0) {
                position = 0.0
                if (mode == Mode.COAST) {
                    mode = Mode.IDLE
                    speed = 0.0
                }
                if (targetSpeed < 0) {
                    targetSpeed = 0.0
                    if (pencilActive) {
                        pencilActive = false
                        events += Event.PencilDone
                    }
                }
            }
            val end = (lengthFrames - 2).toDouble()
            if (position > end) {
                position = end
                if (mode == Mode.COAST) {
                    mode = Mode.IDLE
                    speed = 0.0
                }
                if (targetSpeed > 0) {
                    targetSpeed = 0.0
                    if (playing) {
                        playing = false
                        events += Event.HitEnd
                    }
                }
            }
        }
        return events
    }

    // ---------- transport ----------

    fun play() {
        pencilOff()
        playing = true
        mode = Mode.IDLE
        targetSpeed = PLAY_SPEED
        // Starting loop preview from outside the selection begins at IN.
        if (loopPreview && hasSelection && (position < inFrame || position >= outFrame)) {
            position = inFrame.toDouble()
        }
    }

    fun stop() {
        pencilOff()
        playing = false
        mode = Mode.IDLE
        targetSpeed = 0.0
    }

    fun togglePlay() {
        if (playing) {
            playing = false
            targetSpeed = 0.0
        } else {
            play()
        }
    }

    /** Hold-down wind: [direction] −1 rewind / +1 fast-forward. */
    fun windStart(direction: Int) {
        require(direction == -1 || direction == 1) { "direction is -1 or 1" }
        pencilOff()
        playing = false
        mode = Mode.IDLE
        targetSpeed = direction * WIND_SPEED
    }

    /** Release the wind button. Only stops if the wind is still the target. */
    fun windStop(direction: Int) {
        if (targetSpeed == direction * WIND_SPEED) targetSpeed = 0.0
    }

    // ---------- pencil rewind (the gag that's also the rewind) ----------

    /**
     * Tap the cassette: a pencil winds the tape back to zero at 11×.
     * Refuses within [PENCIL_MIN_POS_SEC] of the top — nothing to wind.
     * Returns true if the pencil started.
     */
    fun pencilRewind(): Boolean {
        if (pencilActive || position < sampleRate * PENCIL_MIN_POS_SEC) return false
        pencilActive = true
        playing = false
        mode = Mode.IDLE
        targetSpeed = PENCIL_SPEED
        return true
    }

    fun pencilOff() {
        if (!pencilActive) return
        pencilActive = false
        if (targetSpeed < 0) targetSpeed = 0.0
    }

    // ---------- gestures ----------

    /** Finger down on the waveform: the tape sticks to the finger. */
    fun dragStart() {
        pencilOff()
        playing = false
        dragTarget = position
        mode = Mode.DRAG
        targetSpeed = 0.0
        dragVelocity = 0.0
    }

    /**
     * Finger moved [dxPixels] over [dtMillis]. Converts through the current
     * zoom — dragging right moves the tape backward under the fixed needle.
     */
    fun dragBy(dxPixels: Double, dtMillis: Double) {
        if (mode != Mode.DRAG) return
        val dFrames = -dxPixels * (sampleRate.toDouble() / pxPerSec)
        dragTarget = (dragTarget + dFrames).coerceIn(0.0, (lengthFrames - 2).toDouble())
        val v = dFrames / dtMillis.coerceAtLeast(1.0) * 1000.0 / sampleRate // tape-sec per sec
        dragVelocity = dragVelocity * 0.7 + v * 0.3
    }

    /** Finger up: a flick coasts with momentum, a stop snaps to the onset. */
    fun dragEnd(): Event.SnappingToOnset? {
        if (mode != Mode.DRAG) return null
        return if (abs(dragVelocity) > FLICK_MIN_TAPE_SEC_PER_SEC) {
            speed = dragVelocity.coerceIn(-COAST_MAX, COAST_MAX)
            mode = Mode.COAST
            null
        } else {
            mode = Mode.IDLE
            speed = 0.0
            snapAfterStop()?.let { Event.SnappingToOnset(it) }
        }
    }

    /** Overview tap: glide to a frame. */
    fun seekTo(frame: Int) {
        pencilOff()
        playing = false
        glideTarget = frame.toDouble().coerceIn(0.0, (lengthFrames - 2).toDouble())
        mode = Mode.GLIDE
        targetSpeed = 0.0
    }

    // ---------- snapping ----------

    private fun nearestOnset(frame: Double, maxDist: Double): Int {
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (o in onsets) {
            val d = abs(o - frame)
            if (d < bestDist) {
                bestDist = d
                best = o
            }
        }
        return if (best >= 0 && bestDist <= maxDist) best else -1
    }

    /** Nearest zero crossing within ±[ZERO_SPAN_FRAMES]; the frame itself if none. */
    private fun nearestZero(frame: Int): Int {
        val from = (frame - ZERO_SPAN_FRAMES).coerceAtLeast(1)
        val to = (frame + ZERO_SPAN_FRAMES).coerceAtMost(lengthFrames - 1)
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (i in from until to) {
            if ((tape[i - 1] < 0) != (tape[i] < 0)) {
                val d = abs(i - frame)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
        }
        return if (best >= 0) best else frame
    }

    /** After motion dies: glide onto an onset within 0.30 s, if snapping. */
    private fun snapAfterStop(): Int? {
        if (!snapToOnset) return null
        val o = nearestOnset(position, SNAP_AFTER_STOP_SEC * sampleRate)
        if (o < 0) return null
        glideTarget = o.toDouble()
        mode = Mode.GLIDE
        return o
    }

    /**
     * Where a SET IN/OUT lands from [frame]: the onset within 0.12 s wins
     * (if onset snap is on), then the nearest zero crossing (if zero snap
     * is on), clamped to the tape.
     */
    fun snapPoint(frame: Int): Int {
        var f = frame
        if (snapToOnset) {
            val o = nearestOnset(f.toDouble(), SNAP_POINT_SEC * sampleRate)
            if (o >= 0) f = o
        }
        if (snapToZero) f = nearestZero(f)
        return f.coerceIn(0, lengthFrames - 2)
    }

    // ---------- selection ----------

    fun setIn() {
        inFrame = snapPoint(position.toInt())
        if (outFrame in 0..inFrame) outFrame = -1
    }

    fun setOut() {
        val f = snapPoint(position.toInt())
        if (inFrame >= 0 && f <= inFrame) {
            // Marked OUT before IN: the two swap rather than erroring.
            outFrame = inFrame
            inFrame = f
        } else {
            outFrame = f
        }
    }

    fun clearSelection() {
        inFrame = -1
        outFrame = -1
    }

    /**
     * A selection handed in whole — DIG's found break (wave ZZ) — rather
     * than marked from the head. Clamped to the tape and ordered; an
     * empty or off-tape range clears the selection instead of setting an
     * unplayable one. The head parks at IN so PLAY previews the find.
     */
    fun select(from: Int, to: Int) {
        val a = minOf(from, to).coerceIn(0, lengthFrames)
        val b = maxOf(from, to).coerceIn(0, lengthFrames)
        if (b <= a) {
            clearSelection()
            return
        }
        inFrame = a
        outFrame = b
        position = a.toDouble()
    }

    /**
     * COMMIT: the selection as a frame range, or null when in/out aren't
     * both set (the UI toasts SET IN + OUT FIRST).
     */
    fun commitSelection(): IntRange? =
        if (hasSelection) inFrame until outFrame else null

    // ---------- readouts ----------

    /** `POS m:ss.ss`, or `CNT 042` in odometer mode — the position LCD. */
    val positionReadout: String
        get() = if (odometer) {
            "CNT " + (position / lengthFrames.coerceAtLeast(1) * 999).toInt().toString().padStart(3, '0')
        } else {
            "POS " + formatTime(position)
        }

    /** `LEN 1.24s` or `LEN --.--` — the selection LCD. */
    val lengthReadout: String
        get() = if (hasSelection) {
            "LEN %.2fs".format(java.util.Locale.ROOT, (outFrame - inFrame).toFloat() / sampleRate)
        } else {
            "LEN --.--"
        }

    private fun formatTime(frames: Double): String {
        val s = frames / sampleRate
        val mm = floor(s / 60).toInt()
        val ss = s - mm * 60
        return "%d:%05.2f".format(java.util.Locale.ROOT, mm, ss)
    }

    companion object {
        // Physics, per audio frame — the felt-right values from the prototype.
        const val DRAG_EASE = 0.004
        const val GLIDE_EASE = 0.002
        const val GLIDE_LOCK_FRAMES = 40.0
        const val COAST_FRICTION = 0.99988
        const val COAST_STOP = 0.02
        const val SPIN_EASE = 0.0012
        const val SPEED_EPSILON = 0.004
        const val PLAY_SPEED = 1.0
        const val WIND_SPEED = 7.0
        const val PENCIL_SPEED = -11.0
        const val COAST_MAX = 14.0

        /** Below this flick speed (tape-seconds per second) a release stops dead. */
        const val FLICK_MIN_TAPE_SEC_PER_SEC = 0.35

        const val SNAP_AFTER_STOP_SEC = 0.30
        const val SNAP_POINT_SEC = 0.12
        const val ZERO_SPAN_FRAMES = 2400
        const val PENCIL_MIN_POS_SEC = 0.05

        /** The zoom ladder: ×1 / ×2 / ×4, as pixels per tape-second. */
        val ZOOM_PX_PER_SEC = intArrayOf(90, 180, 360)
    }
}

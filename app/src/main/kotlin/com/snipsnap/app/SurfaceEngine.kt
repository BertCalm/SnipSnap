package com.snipsnap.app

import com.snipsnap.audio.Snip
import com.snipsnap.shell.TouchSurface

/**
 * The Kotlin owner of one native surface engine: a looping sample under
 * pitch, filter and drive macros, played through Oboe's low-latency
 * callback, with the resample tap that turns a gesture into a new
 * sample. See `app/src/main/cpp/SurfaceEngine.h` for the threading rules;
 * this class keeps to the UI thread and never touches audio memory.
 *
 * Close it. A [SurfaceEngine] holds a native stream; [close] is idempotent
 * and every other call after it is a no-op rather than a crash.
 *
 * Every method is synchronised on the engine: [stopPrint] waits (bounded)
 * for the callback's last write and belongs on a worker thread, while
 * [control] and [close] come from the UI - serialising them is what makes
 * a close during a stop safe, and an uncontended monitor costs nothing at
 * screen rate.
 */
class SurfaceEngine(preferredSampleRate: Int) {

    private var handle: Long = NativeSurface.create(preferredSampleRate)
    private val open get() = handle != 0L

    /** The rate the device actually gave us; a printed sample carries it. */
    val sampleRate: Int get() = if (open) NativeSurface.sampleRate(handle) else preferredRate

    private val preferredRate = preferredSampleRate

    @Synchronized
    fun start(): Boolean = open && NativeSurface.start(handle)

    @Synchronized
    fun stop() {
        if (open) NativeSurface.stop(handle)
    }

    /** The device refused an exclusive stream and the shared fallback is playing (a mixer stage more latency). */
    @Synchronized
    fun isShared(): Boolean = open && NativeSurface.isShared(handle)

    /**
     * The stream's round-trip latency in ms, or null when there is none or
     * the device will not say. Cheap but not free (it reads a timestamp),
     * so poll it about once a second, not per frame.
     */
    @Synchronized
    fun latencyMillis(): Double? =
        if (open) NativeSurface.latencyMillis(handle).takeIf { it > 0.0 } else null

    /** A route change closed the stream; the caller reopens with [start]. */
    @Synchronized
    fun needsRestart(): Boolean = open && NativeSurface.needsRestart(handle)

    /**
     * Load a snip as one of the engine's source slots; stereo is folded to
     * mono, the file's own rate is kept (the engine repitches). Slots
     * 0/1/2/3 are the four vertices [control]'s `sampleA/B/C/D` blend
     * between. See [MAX_SOURCES] (kept in sync with SurfaceEngine.h's
     * kMaxSources by hand, not by any shared build-time constant).
     */
    @Synchronized
    fun load(snip: Snip, slot: Int = 0) {
        if (!open) return
        require(slot in 0 until MAX_SOURCES) { "slot is 0..${MAX_SOURCES - 1}, got $slot" }
        val mono = if (snip.channels == 1) {
            snip.samples
        } else {
            FloatArray(snip.samples.size / 2) { i -> (snip.samples[2 * i] + snip.samples[2 * i + 1]) * 0.5f }
        }
        NativeSurface.loadSample(handle, mono, snip.sampleRate, slot)
    }

    /**
     * Clears a source slot back to silence - call this when the pad that
     * used to live there is no longer chosen (a load failed, or a new kit
     * has none), so a stale sample from before cannot keep sounding at
     * that vertex once the caller's own state says the slot is empty. A
     * zero-length load, through the same handshake [load] uses: the
     * engine already treats a too-short sample as silence and excludes it
     * from the blend's renormalisation (see readSlot/slotLoaded in
     * SurfaceEngine.cpp), so there is no separate native "unload" to keep
     * in sync with this one.
     */
    @Synchronized
    fun clearSlot(slot: Int) {
        if (!open) return
        require(slot in 0 until MAX_SOURCES) { "slot is 0..${MAX_SOURCES - 1}, got $slot" }
        NativeSurface.loadSample(handle, FloatArray(0), 0, slot)
    }

    /**
     * One control frame; call at screen rate with the smoothed reading.
     * [sampleA]/[sampleB]/[sampleC]/[sampleD] weight slots 0/1/2/3 - a
     * barycentric blend across the pad, independent of [mode]. Not
     * required to sum to 1 - the engine renormalises every sample, over
     * whichever slots are actually loaded. An unloaded slot's own weight
     * is silence, but only once it is genuinely empty - see [clearSlot].
     */
    @Synchronized
    fun control(
        mode: TouchSurface.Mode,
        reading: TouchSurface.Reading,
        tilt: Float,
        sampleA: Float = 1f,
        sampleB: Float = 0f,
        sampleC: Float = 0f,
        sampleD: Float = 0f,
        gate: Boolean,
    ) {
        if (!open) return
        NativeSurface.control(
            handle, mode.ordinal,
            reading.x, reading.y, reading.z, tilt,
            reading.a, reading.b, reading.c, reading.d,
            sampleA, sampleB, sampleC, sampleD, gate,
        )
    }

    /** Corner 0..3 = A, B, C, D of the morph pad; every macro 0..1. */
    @Synchronized
    fun setCorner(index: Int, pitch: Float, cutoff: Float, resonance: Float, drive: Float) {
        require(index in 0..3) { "corner is 0..3, got $index" }
        if (open) NativeSurface.setCorner(handle, index, pitch, cutoff, resonance, drive)
    }

    // ---- the resample tap -----------------------------------------------------

    enum class PrintState { IDLE, RECORDING, STOPPING, DONE }

    /**
     * Reserve [seconds] of RAM and record from the next callback. False
     * when a print is still recording or stopping - take that one first.
     */
    @Synchronized
    fun armPrint(seconds: Float): Boolean {
        require(seconds > 0f && seconds <= MAX_PRINT_SECONDS) { "print length is 0..$MAX_PRINT_SECONDS s, got $seconds" }
        return open && NativeSurface.armPrint(handle, (seconds * sampleRate).toInt())
    }

    @Synchronized
    fun printState(): PrintState =
        if (open) PrintState.entries[NativeSurface.printState(handle)] else PrintState.IDLE

    /**
     * Stop and take the print as a mono snip at the engine's rate; null when
     * nothing was captured. Waits up to half a second for the callback's last
     * write, so call it off the main thread.
     */
    @Synchronized
    fun stopPrint(): Snip? {
        if (!open) return null
        val frames = NativeSurface.stopPrint(handle) ?: return null
        return Snip(frames, 1, sampleRate)
    }

    @Synchronized
    fun close() {
        if (!open) return
        NativeSurface.stop(handle)
        NativeSurface.destroy(handle)
        handle = 0L
    }

    companion object {
        /** The print ceiling: a minute at 48 kHz is 11.5 MB of floats, reserved up front. */
        const val MAX_PRINT_SECONDS = 60f

        /** Source slots the engine holds - must match SurfaceEngine.h's kMaxSources. */
        const val MAX_SOURCES = 4
    }
}

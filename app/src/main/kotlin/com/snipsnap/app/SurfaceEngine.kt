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
 */
class SurfaceEngine(preferredSampleRate: Int) {

    private var handle: Long = NativeSurface.create(preferredSampleRate)
    private val open get() = handle != 0L

    /** The rate the device actually gave us; a printed sample carries it. */
    val sampleRate: Int get() = if (open) NativeSurface.sampleRate(handle) else preferredRate

    private val preferredRate = preferredSampleRate

    fun start(): Boolean = open && NativeSurface.start(handle)

    fun stop() {
        if (open) NativeSurface.stop(handle)
    }

    /** A route change closed the stream; the caller reopens with [start]. */
    fun needsRestart(): Boolean = open && NativeSurface.needsRestart(handle)

    /** Load a snip as the voice; stereo is folded to mono, the file's own rate is kept (the engine repitches). */
    fun load(snip: Snip) {
        if (!open) return
        val mono = if (snip.channels == 1) {
            snip.samples
        } else {
            FloatArray(snip.samples.size / 2) { i -> (snip.samples[2 * i] + snip.samples[2 * i + 1]) * 0.5f }
        }
        NativeSurface.loadSample(handle, mono, snip.sampleRate)
    }

    /** One control frame; call at screen rate with the smoothed reading. */
    fun control(mode: TouchSurface.Mode, reading: TouchSurface.Reading, tilt: Float, gate: Boolean) {
        if (!open) return
        NativeSurface.control(
            handle, mode.ordinal,
            reading.x, reading.y, reading.z, tilt,
            reading.a, reading.b, reading.c, reading.d,
            gate,
        )
    }

    /** Corner 0..3 = A, B, C, D of the morph pad; every macro 0..1. */
    fun setCorner(index: Int, pitch: Float, cutoff: Float, resonance: Float, drive: Float) {
        require(index in 0..3) { "corner is 0..3, got $index" }
        if (open) NativeSurface.setCorner(handle, index, pitch, cutoff, resonance, drive)
    }

    // ---- the resample tap -----------------------------------------------------

    enum class PrintState { IDLE, RECORDING, STOPPING, DONE }

    /** Reserve [seconds] of RAM (UI thread) and record from the next callback. */
    fun armPrint(seconds: Float) {
        require(seconds > 0f && seconds <= MAX_PRINT_SECONDS) { "print length is 0..$MAX_PRINT_SECONDS s, got $seconds" }
        if (open) NativeSurface.armPrint(handle, (seconds * sampleRate).toInt())
    }

    fun printState(): PrintState =
        if (open) PrintState.entries[NativeSurface.printState(handle)] else PrintState.IDLE

    /** Stop and take the print as a mono snip at the engine's rate; null when nothing was captured. */
    fun stopPrint(): Snip? {
        if (!open) return null
        val frames = NativeSurface.stopPrint(handle) ?: return null
        return Snip(frames, 1, sampleRate)
    }

    fun close() {
        if (!open) return
        NativeSurface.stop(handle)
        NativeSurface.destroy(handle)
        handle = 0L
    }

    companion object {
        /** The print ceiling: a minute at 48 kHz is 11.5 MB of floats, reserved up front. */
        const val MAX_PRINT_SECONDS = 60f
    }
}

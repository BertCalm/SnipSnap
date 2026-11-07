package com.snipsnap.app

import com.snipsnap.loop.OrbitEngine
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prepare before starting playback. After transfer to the ORBIT/bounce
 * worker, only that worker may process or close, even after a timed UI
 * join. UI updates carry preflattened immutable values through an atomic.
 */
class OrbitAquariumProcessor(rate: Int, settings: AquariumPerformanceSettings) : OrbitEngine.OutputProcessor, Closeable {
    private data class Snapshot(val settings: AquariumPerformanceSettings, val values: FloatArray, val reset: Boolean = false)
    private fun snapshot(settings: AquariumPerformanceSettings) = Snapshot(settings,
        FloatArray(AquariumRecipe.PARAMETERS.size) { settings.settings.parameters.getValue(AquariumRecipe.PARAMETERS[it].id) })
    private val wanted = AtomicReference<Snapshot?>(snapshot(settings))
    private val resetRequested = AtomicBoolean(false)
    private var handle = NativeAquariumBus.create(rate).also {
        check(it != 0L || !settings.enabled) { "Cannot prepare ORBIT Aquarium" }
    }
    private var activeSettings = settings
    private var closed = false
    @Volatile var faulted = handle == 0L
        private set
    @Volatile var limitedFrames = 0L
        private set

    /** Control thread only. Coalesces edits; every adopted sound edit retains the bus transition policy. */
    fun configure(settings: AquariumPerformanceSettings, reset: Boolean = false) {
        val next = snapshot(settings)
        wanted.getAndUpdate { pending -> if (reset || pending?.reset == true) next.copy(reset = true) else next }
    }
    fun resetTail() { resetRequested.set(true) }

    override fun process(block: FloatArray, bpm: Float, restart: Boolean) {
        check(!closed) { "ORBIT Aquarium is closed" }
        wanted.getAndSet(null)?.let { update ->
            val s = update.settings
            activeSettings = s
            if (handle == 0L) return@let
            check(NativeAquariumBus.configure(handle, update.values, s.settings.seed, s.settings.model,
                bpm.toDouble(), s.outputGain, s.enabled, update.reset)) { "ORBIT Aquarium refused settings" }
        }
        // Optional preparation must not take default-dry playback down.
        // A later wet request fails explicitly rather than playing dry.
        if (handle == 0L) {
            check(!activeSettings.enabled) { "ORBIT Aquarium is unavailable" }
            return
        }
        val reset = resetRequested.getAndSet(false) || restart
        check(NativeAquariumBus.process(handle, block, bpm.toDouble(), reset)) { "ORBIT Aquarium processing failed" }
        faulted = NativeAquariumBus.faulted(handle)
        limitedFrames = NativeAquariumBus.limitedFrames(handle)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (handle != 0L) NativeAquariumBus.destroy(handle)
        handle = 0L
    }
}

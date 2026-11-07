package com.snipsnap.app

import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A prepared continuous bus for one JVM audio/render worker. After transfer,
 * only that owner may process/close. Controls carry preflattened snapshots;
 * closing a UI's sink or timing out a join does not release this handle.
 */
class AquariumPerformanceProcessor(rate: Int, settings: AquariumPerformanceSettings, initialBpm: Double? = null) : Closeable {
    private data class Snapshot(val sound: AquariumPerformanceSettings, val values: FloatArray, val reset: Boolean = false)
    private fun snapshot(sound: AquariumPerformanceSettings) = Snapshot(sound,
        FloatArray(AquariumRecipe.PARAMETERS.size) { sound.settings.parameters.getValue(AquariumRecipe.PARAMETERS[it].id) })
    private val initial = snapshot(settings)
    private val wanted = AtomicReference<Snapshot?>(if (initialBpm == null) initial else null)
    private val resetRequested = AtomicBoolean(false)
    private var handle = (if (initialBpm == null) NativeAquariumBus.create(rate) else
        NativeAquariumBus.createConfigured(rate, initial.values, settings.settings.seed, settings.settings.model,
            initialBpm, settings.outputGain, settings.enabled)).also {
        check(it != 0L || !settings.enabled) { "Cannot prepare Aquarium" }
    }
    private var activeSettings = settings
    private var closed = false
    @Volatile var faulted = handle == 0L
        private set
    @Volatile var limitedFrames = 0L
        private set

    fun configure(settings: AquariumPerformanceSettings, reset: Boolean = false) {
        val next = snapshot(settings)
        wanted.getAndUpdate { pending -> if (reset || pending?.reset == true) next.copy(reset = true) else next }
    }
    fun resetTail() { resetRequested.set(true) }
    fun process(block: FloatArray, bpm: Double, restart: Boolean = false) {
        check(!closed) { "Aquarium is closed" }
        wanted.getAndSet(null)?.let { update ->
            val s = update.sound
            activeSettings = s
            if (handle == 0L) return@let
            check(NativeAquariumBus.configure(handle, update.values, s.settings.seed, s.settings.model,
                bpm, s.outputGain, s.enabled, update.reset)) { "Aquarium refused settings" }
        }
        if (handle == 0L) {
            check(!activeSettings.enabled) { "Aquarium is unavailable" }
            return
        }
        val reset = resetRequested.getAndSet(false) || restart
        check(NativeAquariumBus.process(handle, block, bpm, reset)) { "Aquarium processing failed" }
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

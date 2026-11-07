package com.snipsnap.app

import com.snipsnap.loop.OrbitEngine
import com.snipsnap.synth.AquariumPerformanceSettings
import java.io.Closeable

/** ORBIT's existing owner contract, backed by the shared JVM-worker bus. */
class OrbitAquariumProcessor(rate: Int, settings: AquariumPerformanceSettings) : OrbitEngine.OutputProcessor, Closeable {
    private val bus = AquariumPerformanceProcessor(rate, settings)
    val faulted: Boolean get() = bus.faulted
    val limitedFrames: Long get() = bus.limitedFrames
    fun configure(settings: AquariumPerformanceSettings, reset: Boolean = false) = bus.configure(settings, reset)
    fun resetTail() = bus.resetTail()
    override fun process(block: FloatArray, bpm: Float, restart: Boolean) = bus.process(block, bpm.toDouble(), restart)
    override fun close() = bus.close()
}

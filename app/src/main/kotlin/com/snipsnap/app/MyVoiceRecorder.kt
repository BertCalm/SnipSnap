package com.snipsnap.app

import android.content.Context
import android.os.SystemClock
import com.snipsnap.audio.Snip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.util.UUID

/** A finite raw take. No cleanup, normalization or SnipStore preparation precedes assessment. */
internal class MyVoiceRecorder(
    private val device: Device,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    private val maximumMillis: Long = 6_000L,
    private val armTimeoutMillis: Long = 5_000L,
) {
    internal interface Device {
        val armed: Boolean
        val microphone: Boolean
        val session: Long
        val ownerToken: String?
        val sampleRate: Int
        fun arm(ownerToken: String)
        fun eject(ownerToken: String)
        fun snapshot(frames: Int): FloatArray?
    }

    private var ownsRequest = false
    private var ownedSession: Long? = null
    private var finishRequested = false
    private var cancelled = false
    private var generation = 0
    private var ownedToken: String? = null

    suspend fun record(onStarted: () -> Unit): Snip {
        require(!device.armed) { "End the current capture session before recording My Voice." }
        check(!ownsRequest) { "A voice recording is already starting." }
        val request = ++generation
        val token = UUID.randomUUID().toString()
        ownedToken = token
        finishRequested = false
        cancelled = false
        ownsRequest = true
        try {
            device.arm(token)
            val started = withTimeoutOrNull(armTimeoutMillis) {
                while (!device.armed) { coroutineContext.ensureActive(); check(!cancelled && generation == request) { "Recording cancelled." }; delay(25) }
                true
            } ?: false
            check(started) { "The microphone did not start. Check microphone access and try again." }
            check(!cancelled && generation == request) { "Recording cancelled." }
            check(device.microphone && device.ownerToken == token) { "The capture source changed. Try recording again." }
            ownedSession = device.session
            val start = elapsedMillis()
            onStarted()
            while (!finishRequested && elapsedMillis() - start < maximumMillis) {
                coroutineContext.ensureActive()
                check(!cancelled && generation == request) { "Recording cancelled." }
                check(device.armed && device.microphone && device.session == ownedSession && device.ownerToken == token) {
                    "The microphone stopped before the take was ready. Try recording again."
                }
                delay(25)
            }
            coroutineContext.ensureActive()
            check(!cancelled && generation == request && device.armed && device.microphone && device.session == ownedSession && device.ownerToken == token) {
                "The microphone stopped before the take was ready. Try recording again."
            }
            val rate = device.sampleRate
            val frames = ((elapsedMillis() - start).coerceIn(0L, maximumMillis) * rate / 1_000L).toInt()
            require(frames > 0) { "The recording was too short. Hold a steady vowel and try again." }
            val samples = withContext(Dispatchers.IO) { device.snapshot(frames) }
            coroutineContext.ensureActive()
            check(!cancelled && generation == request && device.armed && device.microphone &&
                device.session == ownedSession && device.ownerToken == token && device.sampleRate == rate) {
                "The microphone changed before the take was ready. Try recording again."
            }
            require(samples != null && samples.isNotEmpty()) { "The microphone returned no audio. Check microphone access and try again." }
            return Snip(samples, 1, rate)
        } finally {
            ejectOwned(request)
        }
    }

    fun finish() { finishRequested = true }

    fun cancel() {
        cancelled = true
        ejectOwned(generation)
        generation++
    }

    private fun ejectOwned(request: Int) {
        if (!ownsRequest || generation != request) return
        ownsRequest = false
        // The service checks the token even when ARM has not reached its ring yet.
        ownedToken?.let(device::eject)
        ownedToken = null
        ownedSession = null
    }

    companion object {
        fun microphone(context: Context): MyVoiceRecorder = MyVoiceRecorder(object : Device {
            override val armed get() = MicSessionService.armed.value
            override val microphone get() = MicSessionService.source.value == MicSessionService.Source.MIC
            override val session get() = MicSessionService.armedAtElapsedRealtime
            override val ownerToken get() = MicSessionService.activeOwnerToken
            override val sampleRate get() = MicSessionService.SAMPLE_RATE
            override fun arm(ownerToken: String) = MicSessionService.arm(context, ownerToken)
            override fun eject(ownerToken: String) = MicSessionService.eject(context, ownerToken)
            override fun snapshot(frames: Int) = MicSessionService.snapshotTail(frames)
        })
    }
}

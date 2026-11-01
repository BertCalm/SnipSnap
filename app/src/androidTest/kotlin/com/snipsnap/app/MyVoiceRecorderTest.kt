package com.snipsnap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.audio.Snip
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Raw capture is a device boundary; fake only that boundary, including ownership races. */
@RunWith(AndroidJUnit4::class)
class MyVoiceRecorderTest {
    @Test
    fun raw_take_reaches_assessment_without_level_repair() = runBlocking {
        val device = Device()
        var time = 0L
        val recorder = MyVoiceRecorder(device, elapsedMillis = { time.also { time += 100 } }, maximumMillis = 100)
        val raw: Snip = recorder.record {}
        assertEquals(device.sampleRate, raw.sampleRate)
        assertEquals(1, raw.channels)
        assertTrue(raw.frameCount > 0)
        assertEquals("rail contacts must remain visible to assessment", 1f, raw.samples[0], 0f)
        assertEquals("quiet samples must not be normalized", .0001f, raw.samples[1], 0f)
        assertFalse(device.armed)
    }

    @Test
    fun recording_refuses_an_existing_session_and_reports_arm_failure() = runBlocking {
        val existing = Device().apply { armed = true; ownerToken = "dock" }
        val refusal = runCatching { MyVoiceRecorder(existing).record {} }.exceptionOrNull()
        assertTrue(refusal?.message.orEmpty().contains("End the current capture"))
        assertEquals(0, existing.arms)
        assertTrue(existing.armed)
        val denied = Device().apply { denied = true }
        val failure = runCatching { MyVoiceRecorder(denied).record {} }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("permission denied"))
        assertFalse(denied.armed)
    }

    @Test
    fun a_replacement_during_snapshot_is_refused_and_never_ejected() = runBlocking {
        val device = Device()
        device.onSnapshot = { device.ownerToken = "replacement"; device.session++; device.microphone = false }
        var time = 0L
        val recorder = MyVoiceRecorder(device, elapsedMillis = { time.also { time += 100 } }, maximumMillis = 100)
        val failure = runCatching { recorder.record {} }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("microphone changed"))
        assertEquals("replacement", device.ownerToken)
        assertTrue("late cleanup must retain the replacement session", device.armed)
    }

    @Test
    fun cancelling_a_pending_arm_invalidates_its_result_without_ending_a_replacement() = runBlocking {
        val device = Device().apply { deferArm = true }
        val recorder = MyVoiceRecorder(device, armTimeoutMillis = 500)
        val result = async { runCatching { recorder.record {} } }
        while (device.arms == 0) delay(5)
        recorder.cancel()
        device.armed = true; device.ownerToken = "replacement"; device.session++
        val failure = result.await().exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("cancelled"))
        assertTrue(device.armed)
        assertEquals("replacement", device.ownerToken)
    }

    private class Device : MyVoiceRecorder.Device {
        override var armed = false
        override var microphone = true
        override var session = 1L
        override var ownerToken: String? = null
        override val sampleRate = 44_100
        var arms = 0
        var denied = false
        var deferArm = false
        var onSnapshot: (() -> Unit)? = null
        override fun arm(ownerToken: String) {
            arms++
            if (denied) throw SecurityException("microphone permission denied")
            if (!deferArm) { this.ownerToken = ownerToken; armed = true; session++ }
        }
        override fun eject(ownerToken: String) {
            if (this.ownerToken == ownerToken) { armed = false; this.ownerToken = null }
        }
        override fun snapshot(frames: Int): FloatArray {
            onSnapshot?.invoke()
            return FloatArray(frames) { if (it == 0) 1f else .0001f }
        }
    }
}

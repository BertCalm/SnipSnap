package com.snipsnap.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snipsnap.shell.OutputRoute

/**
 * Whether the sound is going out over Bluetooth right now. One
 * [AudioDeviceCallback], on the main looper, kept current while a holder
 * ([register]) is up; the rule that says what counts is `:shell`'s
 * [OutputRoute], so it is tested there. [isBluetooth] is Compose state: the
 * dock's line follows it.
 */
object AudioRoute {
    var isBluetooth by mutableStateOf(false)
        private set

    private var callback: AudioDeviceCallback? = null

    /** Start listening, and read the route once now. Safe to call twice. */
    fun register(context: Context) {
        if (callback != null) return
        val am = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        refresh(am)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh(am)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh(am)
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        callback = cb
    }

    fun unregister(context: Context) {
        val cb = callback ?: return
        callback = null
        context.applicationContext.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(cb)
    }

    private fun refresh(am: AudioManager) {
        isBluetooth = OutputRoute.isBluetooth(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }, Build.VERSION.SDK_INT)
    }
}

/**
 * How late the player hears a beat, and so how far a recorded hit is moved
 * back: SETUP's RECORD OFFSET ([manualMs], null for AUTO) over the
 * stream's own measurement ([measuredMs], written about once a second by
 * [PadHost.pump]) over the Bluetooth assumption. The arithmetic is
 * [OutputRoute.compensationMs]. Only RECORD reads it: live pad playback is
 * never shifted, because nothing can make a sound earlier than the stream.
 */
object RecordLatency {
    private const val KEY_MS = "record.offset.ms"

    /** The player's own figure in ms, or null for AUTO. */
    var manualMs by mutableStateOf<Int?>(null)
        private set

    /** The last usable reading from the stream, or null. Written off the composition; read at hit time. */
    @Volatile
    var measuredMs: Double? = null

    private var prefs: SharedPreferences? = null

    /** Read the saved offset, once. Safe to call again. */
    fun init(context: Context) {
        if (prefs != null) return
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = saved
        manualMs = if (saved.contains(KEY_MS)) saved.getInt(KEY_MS, 0).coerceIn(0, OutputRoute.MAX_COMPENSATION_MS) else null
    }

    /** Set the offset in ms (clamped to 0..400), or null for AUTO. */
    fun choose(ms: Int?) {
        val next = ms?.coerceIn(0, OutputRoute.MAX_COMPENSATION_MS)
        manualMs = next
        prefs?.edit()?.also { if (next == null) it.remove(KEY_MS) else it.putInt(KEY_MS, next) }?.apply()
    }

    /** What a take is moved back by right now, in ms. */
    fun currentMs(): Int = OutputRoute.compensationMs(manualMs, measuredMs, AudioRoute.isBluetooth)

    /** A touch's own `uptimeMillis`, as the time it was heard to land: what RECORD stamps and gates on. */
    fun hitNanos(uptimeMillis: Long): Long = OutputRoute.hitNanos(uptimeMillis, currentMs())
}

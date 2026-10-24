package com.snipsnap.app

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.snipsnap.kit.MidiBytes
import com.snipsnap.shell.PadHit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the app's own notes go out: the input ports of every connected MIDI
 * device, and (Task 4) the published "SnipSnap Out" port.
 *
 * **Threads.** [noteOn], [noteOff] and [allNotesOff] are called from the
 * main thread; each encodes one small `ByteArray` there with [MidiBytes] and
 * posts the send to a `HandlerThread("midi-out")`. `MidiInputPort.send` may
 * block, so it runs only on that thread - never main, never audio. The
 * thread is started by [install] and lives for the process.
 *
 * **No thru.** The callers are touch and the sequencer only. Notes that
 * arrive from a connected keyboard are never forwarded here, so a keyboard
 * that echoes what it receives cannot start a loop.
 *
 * **The switch.** Hardware destinations send only while [hardwareEnabled]
 * is true (default false: a keyboard receiving every pad hit is a surprise).
 * Destinations added with `hardware = false` send regardless. [install]
 * reads the saved choice once; whoever changes it later writes the pref and
 * assigns [hardwareEnabled] - persistence is not this object's job.
 *
 * A destination whose `send` throws [IOException] (the cable came out) is
 * logged and dropped, once, on the out thread.
 */
object MidiOut {

    /** A destination as SETUP shows it: its name and whether it opened. */
    data class Port(val name: String, val open: Boolean)

    /** Somewhere bytes can go. [send] gets the message and a `System.nanoTime()`-domain stamp (0 = now). */
    class Destination(val id: String, val name: String, val send: (ByteArray, Long) -> Unit)

    private class Entry(val destination: Destination, val hardware: Boolean)

    private const val TAG = "MidiOut"
    private const val DRUM_CHANNEL = 9
    private const val KEYS_CHANNEL = 0

    private val entries = CopyOnWriteArrayList<Entry>()
    private val refused = LinkedHashMap<String, String>() // id -> name; guarded by lock
    private val lock = Any()
    private val _ports = MutableStateFlow<List<Port>>(emptyList())

    @Volatile
    private var handler: Handler? = null

    /** Main thread. Hardware destinations send only while this is true. */
    @Volatile
    var hardwareEnabled: Boolean = false

    /** Every registered destination (open) plus the ones that would not open. */
    val ports: StateFlow<List<Port>> = _ports.asStateFlow()

    /** Reads the saved switch once and starts the out thread. Safe to call twice. */
    fun install(context: Context) {
        hardwareEnabled = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("midi.out", false)
        }.getOrDefault(false)
        synchronized(lock) {
            if (handler == null) {
                val thread = HandlerThread("midi-out").also { it.start() }
                handler = Handler(thread.looper)
            }
        }
    }

    fun add(d: Destination, hardware: Boolean = true) {
        synchronized(lock) {
            entries.removeAll { it.destination.id == d.id }
            refused.remove(d.id)
            entries += Entry(d, hardware)
            publish()
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            entries.removeAll { it.destination.id == id }
            refused.remove(id)
            publish()
        }
    }

    /** A port that would not open (another app holds it): listed as not [Port.open]. */
    fun reportRefused(id: String, name: String) {
        synchronized(lock) {
            refused[id] = name
            publish()
        }
    }

    fun noteOn(channel: Int, note: Int, velocity: Float, timestampNanos: Long) {
        post(MidiBytes.noteOn(channel, note, PadHit.midiVelocity(velocity)), timestampNanos)
    }

    fun noteOff(channel: Int, note: Int, timestampNanos: Long) {
        post(MidiBytes.noteOff(channel, note), timestampNanos)
    }

    /** CC 123 on the drum and keys channels, now: a receiver must not keep a note the app let go. */
    fun allNotesOff() {
        post(MidiBytes.allNotesOff(DRUM_CHANNEL), 0L)
        post(MidiBytes.allNotesOff(KEYS_CHANNEL), 0L)
    }

    private fun publish() {
        _ports.value = entries.map { Port(it.destination.name, true) } + refused.values.map { Port(it, false) }
    }

    private fun post(bytes: ByteArray, timestampNanos: Long) {
        val h = handler ?: return
        h.post {
            val hardwareOn = hardwareEnabled
            for (e in entries) {
                if (e.hardware && !hardwareOn) continue
                try {
                    e.destination.send(bytes, timestampNanos)
                } catch (ex: IOException) {
                    // The port is gone (cable out). Logged once: the
                    // destination is removed, so it is never tried again.
                    Log.w(TAG, "MIDI destination ${e.destination.name} failed, dropping it", ex)
                    remove(e.destination.id)
                }
            }
        }
    }
}

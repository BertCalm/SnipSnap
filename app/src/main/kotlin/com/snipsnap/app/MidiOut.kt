package com.snipsnap.app

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.snipsnap.kit.MidiBytes
import com.snipsnap.kit.MidiGroove
import com.snipsnap.shell.PadHit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The manufacturer and product strings of SnipSnap's own published MIDI
 * device. `res/xml/midi_device_info.xml` (Task 4) must carry exactly these
 * values: the XML cannot reference Kotlin, so this is one quantity in two
 * places by necessity, and Task 4's reviewer checks they match.
 * [MidiInput] uses them (with `TYPE_VIRTUAL`) to skip the app's own device.
 */
internal const val MIDI_DEVICE_MANUFACTURER = "SnipSnap"
internal const val MIDI_DEVICE_PRODUCT = "SnipSnap"

/** The SharedPreferences key of SETUP's MIDI OUT switch: written by `App`, read once by [MidiOut.install]. */
internal const val PREF_MIDI_OUT = "midi.out"

/**
 * Where the app's own notes go out: the input ports of every connected MIDI
 * device, and (Task 4) the published "SnipSnap Out" port.
 *
 * **Threads.** [noteOn], [noteOff], the drum and keys wrappers and
 * [allNotesOff] are called from the main thread; each encodes one small
 * `ByteArray` there with [MidiBytes] and posts the send to a
 * `HandlerThread("midi-out")`. `MidiInputPort.send` may block, so it runs
 * only on that thread - never main, never audio. The thread is started by
 * [install] and lives for the process.
 *
 * **Who owns what.** `outstanding` and `pendingOffs` belong to the main
 * thread alone: every read and write of them is there. The out thread never
 * touches them; when a gate-off fires it sends the off and posts the
 * bookkeeping (drop the key from both) back to main, guarded by the
 * runnable's identity so a retrigger that replaced it is not undone.
 * Cancelling a pending off from main is `Handler.removeCallbacks`, which is
 * thread-safe; an off already running is followed by main's own off and on,
 * in that order, so the receiver never ends up with a stuck note.
 *
 * **Outstanding notes.** Every note this object sends on is remembered until
 * its off goes out (a `HashSet` of `(channel shl 8) or note`). [allNotesOff]
 * sends CC 123 only on channels that still have one, then forgets them all.
 * An echoed All Notes Off (a receiver that answers CC 123 with CC 123, or a
 * router joining "SnipSnap Out" to "SnipSnap In") arrives to find nothing
 * outstanding and dies after one round instead of doubling each time. Every
 * note SnipSnap started is still ended by PANIC, a cable pull, a local
 * silence and a screen's disposal, because each of those calls
 * [allNotesOff] while the note is outstanding.
 *
 * **Hardware, for the life of the process.** [install] also opens the input
 * ports of every connected non-SnipSnap device and follows devices as they
 * come and go (see `openHardware`); screens do not own these, so the
 * sequencer on BEAT and SETUP's list work whichever screen is up.
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
 * A destination whose `send` throws (typically `IOException`: the cable came
 * out) is logged and dropped, once, on the out thread. Any exception is
 * caught: that thread has no uncaught-exception handler, so one escaping
 * would end the process.
 */
object MidiOut {

    /** A destination as SETUP shows it: its name and whether it opened. */
    data class Port(val name: String, val open: Boolean)

    /** Somewhere bytes can go. [send] gets the message and a `System.nanoTime()`-domain stamp (0 = now). */
    class Destination(val id: String, val name: String, val send: (ByteArray, Long) -> Unit)

    private class Entry(val destination: Destination, val hardware: Boolean)

    private const val TAG = "MidiOut"
    private const val KEYS_CHANNEL = 0

    private val entries = CopyOnWriteArrayList<Entry>()
    private val refused = LinkedHashMap<String, String>() // id -> name; guarded by lock
    private val lock = Any()
    private val _ports = MutableStateFlow<List<Port>>(emptyList())

    private var installed = false // guarded by lock

    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. `(channel shl 8) or note` of every note sent on and not yet sent off. */
    private val outstanding = HashSet<Int>()

    /** Main thread only. Key -> the gate-off waiting on the out thread's queue. */
    private val pendingOffs = HashMap<Int, Runnable>()

    /** What one device's input ports opened as, by `MidiDeviceInfo.id`. Main thread only. */
    private class OpenedOut(val device: MidiDevice, val ports: List<Pair<String, MidiInputPort>>, val refusedIds: List<String>)

    private val hardware = HashMap<Int, OpenedOut>()
    private val knownDevices = HashSet<Int>()

    @Volatile
    private var handler: Handler? = null

    /** Main thread. Hardware destinations send only while this is true. */
    @Volatile
    var hardwareEnabled: Boolean = false

    /** Every registered destination (open) plus the ones that would not open. */
    val ports: StateFlow<List<Port>> = _ports.asStateFlow()

    /** Reads the saved switch and starts the out thread, on the first call only; later calls do nothing. */
    fun install(context: Context) {
        synchronized(lock) {
            if (installed) return
            installed = true
            hardwareEnabled = runCatching {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PREF_MIDI_OUT, false)
            }.getOrDefault(false)
            val thread = HandlerThread("midi-out").also { it.start() }
            handler = Handler(thread.looper)
        }
        openHardware(context.applicationContext)
    }

    /**
     * Opens the input ports of every device but SnipSnap's own, now and as
     * they arrive (main thread; [install] is called from `Application.onCreate`).
     * The naming trap: a device's *input* port is where bytes go in to it, so
     * it is what this app sends into. `MidiInput` opens the same devices for
     * their *output* ports. That is two `MidiDevice` handles on one device in
     * this process, which `MidiManager` allows (ports are exclusive, devices
     * are not), and the two openers never open the same port.
     */
    private fun openHardware(context: Context) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) return
        val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) = openDeviceIn(manager, info)
            override fun onDeviceRemoved(info: MidiDeviceInfo) = closeDeviceIn(info.id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, { main.post(it) }, cb)
            manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).forEach { openDeviceIn(manager, it) }
        } else {
            @Suppress("DEPRECATION")
            manager.registerDeviceCallback(cb, main)
            @Suppress("DEPRECATION")
            manager.devices.forEach { openDeviceIn(manager, it) }
        }
    }

    private fun openDeviceIn(manager: MidiManager, info: MidiDeviceInfo) {
        if (MidiInput.isOwnDevice(info)) return
        if (info.inputPortCount == 0) return
        if (!knownDevices.add(info.id)) return
        manager.openDevice(info, { device ->
            if (device == null) {
                Log.w(TAG, "MIDI device ${MidiInput.nameOf(info)} would not open for output")
                knownDevices.remove(info.id)
                return@openDevice
            }
            // Removed while it was opening: not ours to keep.
            if (info.id !in knownDevices) {
                device.close()
                return@openDevice
            }
            val name = MidiInput.nameOf(info)
            val key = MidiInput.fingerprintOf(info).key
            val ports = ArrayList<Pair<String, MidiInputPort>>()
            val refusedIds = ArrayList<String>()
            for (p in 0 until info.inputPortCount) {
                // info.id in the id: two identical units share a fingerprint.
                val destId = "$key/${info.id}/in$p"
                val destName = if (info.inputPortCount > 1) "$name $p" else name
                val port = runCatching { device.openInputPort(p) }.getOrNull()
                if (port == null) {
                    Log.w(TAG, "MIDI device $name input port $p would not open")
                    reportRefused(destId, destName)
                    refusedIds += destId
                    continue
                }
                add(Destination(destId, destName) { bytes, ts -> port.send(bytes, 0, bytes.size, ts) })
                ports += destId to port
            }
            hardware[info.id] = OpenedOut(device, ports, refusedIds)
        }, main)
    }

    private fun closeDeviceIn(id: Int) {
        knownDevices.remove(id)
        val gone = hardware.remove(id) ?: return
        // What this app had turned on is gone with the cable; whatever else is connected hears the silence.
        allNotesOff()
        gone.refusedIds.forEach(::remove)
        for ((destId, port) in gone.ports) {
            remove(destId)
            runCatching { port.close() }
        }
        runCatching { gone.device.close() }
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

    private fun removeEntry(e: Entry) {
        synchronized(lock) {
            if (entries.remove(e)) publish()
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
        val key = key(channel, note)
        flushPending(key)
        outstanding += key
        post(MidiBytes.noteOn(channel, note, PadHit.midiVelocity(velocity)), timestampNanos)
    }

    fun noteOff(channel: Int, note: Int, timestampNanos: Long) {
        val key = key(channel, note)
        cancelPending(key)
        outstanding -= key
        post(MidiBytes.noteOff(channel, note), timestampNanos)
    }

    /**
     * The drum channel's pad hit and release. Named apart from [noteOn] so the
     * pad screens, which also hold a VoiceAllocator, do not carry a `.noteOn(`
     * that ConventionTest's allocation law would read as an allocator call.
     */
    fun drumOn(note: Int, velocity: Float, timestampNanos: Long) = noteOn(MidiGroove.DRUM_CHANNEL, note, velocity, timestampNanos)

    fun drumOff(note: Int, timestampNanos: Long) = noteOff(MidiGroove.DRUM_CHANNEL, note, timestampNanos)

    /** The keys channel's note, named apart from [noteOn] for the same reason as [drumOn]. */
    fun keysOn(note: Int, velocity: Float, timestampNanos: Long) = noteOn(KEYS_CHANNEL, note, velocity, timestampNanos)

    fun keysOff(note: Int, timestampNanos: Long) = noteOff(KEYS_CHANNEL, note, timestampNanos)

    /**
     * A drum hit with no release of its own (a sequencer step, a window-grid
     * tap): the on goes now, stamped [timestampNanos], and the off is
     * scheduled on the out thread [gateNanos] later. A future-stamped off
     * would not do: the system cannot withdraw it, and "SnipSnap Out" delivers
     * it at once. Here a retrigger of the same note first sends the pending
     * off (so the new note is not cut by the old one's timer), and
     * [allNotesOff] flushes whatever is pending.
     */
    fun drumOnGated(note: Int, velocity: Float, timestampNanos: Long, gateNanos: Long) {
        val h = handler ?: return
        val channel = MidiGroove.DRUM_CHANNEL
        val key = key(channel, note)
        drumOn(note, velocity, timestampNanos) // also flushes a pending off for this key
        val off = MidiBytes.noteOff(channel, note)
        val gated = object : Runnable {
            override fun run() {
                sendAll(off, 0L)
                main.post {
                    if (pendingOffs[key] === this) {
                        pendingOffs.remove(key)
                        outstanding -= key
                    }
                }
            }
        }
        pendingOffs[key] = gated
        h.postDelayed(gated, gateNanos / 1_000_000L)
    }

    /**
     * CC 123, now, on each channel that still has a note outstanding, after
     * flushing every pending gate-off; nothing is sent when nothing is
     * sounding. A receiver must not keep a note the app let go, and an
     * echoed All Notes Off must not be answered.
     */
    fun allNotesOff() {
        for ((key, pending) in pendingOffs) {
            handler?.removeCallbacks(pending)
            post(MidiBytes.noteOff(key shr 8, key and 0xFF), 0L)
        }
        pendingOffs.clear()
        val channels = outstanding.map { it shr 8 }.toSortedSet()
        outstanding.clear()
        for (channel in channels) post(MidiBytes.allNotesOff(channel), 0L)
    }

    private fun key(channel: Int, note: Int) = (channel shl 8) or note

    /** A new on for a key with a gate-off waiting: the old note ends first. */
    private fun flushPending(key: Int) {
        val pending = pendingOffs.remove(key) ?: return
        handler?.removeCallbacks(pending)
        post(MidiBytes.noteOff(key shr 8, key and 0xFF), 0L)
    }

    private fun cancelPending(key: Int) {
        val pending = pendingOffs.remove(key) ?: return
        handler?.removeCallbacks(pending)
    }

    private fun publish() {
        _ports.value = entries.map { Port(it.destination.name, true) } + refused.values.map { Port(it, false) }
    }

    private fun post(bytes: ByteArray, timestampNanos: Long) {
        val h = handler ?: return
        h.post { sendAll(bytes, timestampNanos) }
    }

    /** Out thread only. */
    private fun sendAll(bytes: ByteArray, timestampNanos: Long) {
        val hardwareOn = hardwareEnabled
        for (e in entries) {
            if (e.hardware && !hardwareOn) continue
            try {
                e.destination.send(bytes, timestampNanos)
            } catch (ex: Exception) {
                // The port is gone (cable out), or the lambda broke. Logged once: the
                // destination is removed, so it is never tried again.
                Log.w(TAG, "MIDI destination ${e.destination.name} failed, dropping it", ex)
                // By the entry, not its id: a same-id destination re-added meanwhile is a different entry.
                removeEntry(e)
            }
        }
    }
}

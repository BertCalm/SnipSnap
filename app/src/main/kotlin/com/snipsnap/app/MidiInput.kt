package com.snipsnap.app

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.snipsnap.kit.MidiWire
import com.snipsnap.shell.MidiDeviceId
import com.snipsnap.shell.MidiFingerprint

/**
 * A keyboard plugged into the phone, as events on the main thread.
 *
 * The phone's `MidiManager` lists every MIDI device the system knows -
 * a USB keyboard on an OTG cable the moment it is plugged in - and hands
 * each over as ports. A Bluetooth keyboard is not among them until some
 * app opens it with `openBluetoothDevice` after a scan, with Bluetooth
 * permissions this app does not hold; nothing here does that, so only
 * USB is wired.
 * A keyboard's *output* port is where its notes come out (the naming is
 * the device's point of view), so [open] connects a receiver to every
 * output port of every device, and keeps doing so as devices come and go.
 *
 * Bytes arrive on the MIDI service's own thread, in whatever chunks the
 * transport made of them; each port has its own [MidiWire] because
 * running status is per stream, and the events it completes are posted
 * to the main thread in order. That hop is deliberate, not laziness: a
 * screen's `hit` touches Compose state and the allocator, and a touch on
 * a pad reaches the same `hit` through the main thread too, so a MIDI
 * note is no further from the engine than a finger is. What the hop
 * must not cost is the note's *when*: the service stamps each delivery
 * in the `System.nanoTime()` domain, and that stamp rides on every event
 * as `timestampNanos`, so a screen that learns of a note a frame late
 * can still say when it was played.
 *
 * Nothing here decides what a note means - [Listener.onEvent] hands the
 * typed note or controller event to the screen (real-time bytes, the clock
 * among them, stop here: see [Receiver]), and `:shell`'s `MidiKeys` and `MidiPads`
 * are the tested rules. [Listener.onDevicesChanged] is for the LCD and
 * for whatever will key on a device: each [MidiDeviceId] carries the name
 * to print and a [MidiFingerprint] steadier than it, empty when nothing
 * is connected; and
 * [Listener.onDeviceRemoved] is the cable coming out, which the screen
 * answers by letting every note go, because the keyboard's own
 * note-offs are never coming.
 *
 * Main thread only. [close] is idempotent, and a device that finishes
 * opening after it is closed again rather than kept.
 */
class MidiInput(context: Context) {

    interface Listener {
        fun onEvent(event: MidiWire.Event)
        fun onDevicesChanged(devices: List<MidiDeviceId>)

        /**
         * A device went away - the cable was pulled. Its note-offs will
         * never come, so whatever it had turned on is the screen's to
         * silence now; [onDevicesChanged] follows with what is left. Any
         * event of its still in flight to the main thread is dropped, so
         * nothing it sent can replay a note after this.
         */
        fun onDeviceRemoved()
    }

    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        } else {
            null
        }
    private val handler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var callback: MidiManager.DeviceCallback? = null

    /** SnipSnap's own published port: notes from another app, and its being bound as a device. */
    private val virtualListener = object : VirtualMidi.Listener {
        override fun onEvent(event: MidiWire.Event) {
            listener?.onEvent(event)
        }

        override fun onBound(id: MidiDeviceId) {
            listener?.onDevicesChanged(devices)
        }

        override fun onUnbound() {
            // The other app is gone with its held notes; let them go first,
            // as closeDevice does for a pulled cable.
            listener?.onDeviceRemoved()
            listener?.onDevicesChanged(devices)
        }
    }

    private class Opened(
        val info: MidiDeviceInfo,
        val id: MidiDeviceId,
        val device: MidiDevice,
        val ports: List<Pair<MidiOutputPort, Receiver>>,
    )

    private val opened = LinkedHashMap<Int, Opened>()

    /** The phone can do MIDI at all; false on one with no `android.software.midi`. */
    val supported: Boolean get() = manager != null

    /** What is connected right now, by name and fingerprint, in the order it was found. */
    val devices: List<MidiDeviceId> get() = opened.values.map { it.id } + listOfNotNull(VirtualMidi.boundId)

    /**
     * Start listening: every device present now is opened, and the
     * callback opens each one that arrives later. False when the phone
     * has no MIDI feature, in which case nothing will ever be heard.
     */
    fun open(listener: Listener): Boolean {
        val manager = manager ?: return false
        if (this.listener != null) return true
        this.listener = listener
        VirtualMidi.subscribe(virtualListener)
        // A screen opened while another app already holds "SnipSnap In" must print it now, not on the next change.
        if (VirtualMidi.boundId != null) listener.onDevicesChanged(devices)
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) = openDevice(info)
            override fun onDeviceRemoved(info: MidiDeviceInfo) = closeDevice(info.id)
        }
        callback = cb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, { handler.post(it) }, cb)
            manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).forEach(::openDevice)
        } else {
            @Suppress("DEPRECATION")
            manager.registerDeviceCallback(cb, handler)
            @Suppress("DEPRECATION")
            manager.devices.forEach(::openDevice)
        }
        return true
    }

    private fun openDevice(info: MidiDeviceInfo) {
        val manager = manager ?: return
        if (info.id in opened) return
        // SnipSnap's own published device is listed like any other; opening
        // it would hear our own notes and send into ourselves.
        if (isOwnDevice(info)) return
        if (info.outputPortCount == 0) return
        manager.openDevice(info, { device ->
            if (device == null) {
                Log.w(TAG, "MIDI device ${nameOf(info)} would not open")
                return@openDevice
            }
            // Opened after close(), or opened twice: not ours to keep.
            if (listener == null || info.id in opened) {
                device.close()
                return@openDevice
            }
            val ports = ArrayList<Pair<MidiOutputPort, Receiver>>()
            for (p in 0 until info.outputPortCount) {
                val port = runCatching { device.openOutputPort(p) }.getOrNull()
                if (port == null) {
                    Log.w(TAG, "MIDI device ${nameOf(info)} port $p would not open")
                    continue
                }
                val receiver = Receiver()
                port.connect(receiver)
                ports += port to receiver
            }
            val id = MidiDeviceId(nameOf(info), fingerprintOf(info))
            // Output ports only. The device's input ports (where this app
            // sends) are opened for the whole process by MidiOut, through a
            // second MidiDevice handle: ports are exclusive, devices are not,
            // and the two openers never open the same port.
            opened[info.id] = Opened(info, id, device, ports)
            listener?.onDevicesChanged(devices)
        }, handler)
    }

    private fun closeDevice(id: Int) {
        val gone = opened.remove(id) ?: return
        release(gone)
        // A receiver must not keep a note the app let go: whatever else is
        // still connected hears the silence before the screen is told.
        MidiOut.allNotesOff()
        // The cable is out: every note it turned on is held by nothing
        // now, and the screen must let them all go before it hears about
        // the shorter list. In this order, so the readout never names a
        // device whose notes are still sounding.
        listener?.onDeviceRemoved()
        listener?.onDevicesChanged(devices)
    }

    private fun release(it: Opened) {
        for ((port, receiver) in it.ports) {
            // Dead first: a chunk the service thread already parsed and
            // posted lands on the main thread after this, and must not
            // start a note whose off will never come.
            receiver.alive = false
            runCatching { port.disconnect(receiver) }
            runCatching { port.close() }
        }
        runCatching { it.device.close() }
    }

    /** Stop listening and let every device go. Safe to call twice. */
    fun close() {
        val manager = manager ?: return
        listener = null
        VirtualMidi.unsubscribe(virtualListener)
        // One unregister serves both registrations; only the register
        // call grew a transport argument in 33.
        callback?.let(manager::unregisterDeviceCallback)
        callback = null
        opened.values.forEach(::release)
        opened.clear()
    }

    /**
     * One port's bytes, parsed on the service's thread and posted whole to
     * the main one. [alive] is flipped by [release] on the main thread and
     * read there by the posted block, so an event already in flight when
     * its device went is dropped rather than played.
     */
    private inner class Receiver : MidiReceiver() {
        private val wire = MidiWire()

        @Volatile
        var alive = true

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            // The service's own stamp for this delivery, kept on the event:
            // the one fact about timing that is lost for good if it is
            // dropped here, because nothing downstream can read it again.
            // The main-thread hop exists for screen state. The clock belongs to
            // the follower that will read it on this thread (roadmap P1.3/P1.8);
            // until that exists each 0xF8 would be a posted lambda thrown away
            // 24 times a beat.
            val events = wire.feed(msg, offset, count, timestamp).filter { it !is MidiWire.Event.RealTime }
            if (events.isEmpty()) return
            handler.post {
                if (!alive) return@post
                val l = listener ?: return@post
                events.forEach(l::onEvent)
            }
        }
    }

    companion object {
        private const val TAG = "MidiInput"

        /**
         * SnipSnap's own published device, which neither this class nor
         * [MidiOut]'s input-port opener may open (it would hear or send into
         * ourselves). The one definition. Public properties only; a
         * third-party virtual device that calls itself SnipSnap/SnipSnap
         * would be skipped too, which is acceptable.
         */
        internal fun isOwnDevice(info: MidiDeviceInfo): Boolean {
            val p = info.properties
            return info.type == MidiDeviceInfo.TYPE_VIRTUAL &&
                p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) == MIDI_DEVICE_MANUFACTURER &&
                p.getString(MidiDeviceInfo.PROPERTY_PRODUCT) == MIDI_DEVICE_PRODUCT
        }

        /**
         * What the device says it is: transport, USB vendor and product ids
         * (0 when it is not USB), port counts, manufacturer and product.
         * The Bluetooth device property is deliberately not read - its
         * address randomises.
         */
        fun fingerprintOf(info: MidiDeviceInfo): MidiFingerprint {
            val p = info.properties
            val transport = when (info.type) {
                MidiDeviceInfo.TYPE_USB -> MidiFingerprint.Transport.USB
                MidiDeviceInfo.TYPE_BLUETOOTH -> MidiFingerprint.Transport.BLUETOOTH
                MidiDeviceInfo.TYPE_VIRTUAL -> MidiFingerprint.Transport.VIRTUAL
                else -> MidiFingerprint.Transport.UNKNOWN
            }
            val usb: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                p.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                p.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE)
            }
            return MidiFingerprint.of(
                transport,
                usb?.vendorId ?: 0,
                usb?.productId ?: 0,
                info.inputPortCount,
                info.outputPortCount,
                p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
                p.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
                p.getString(MidiDeviceInfo.PROPERTY_NAME),
            )
        }

        /** The device's own name, falling back to product and manufacturer, then to its id. */
        fun nameOf(info: MidiDeviceInfo): String {
            val p = info.properties
            return p.getString(MidiDeviceInfo.PROPERTY_NAME)?.takeIf { it.isNotBlank() }
                ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)?.takeIf { it.isNotBlank() }
                ?: p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)?.takeIf { it.isNotBlank() }
                ?: "MIDI DEVICE ${info.id}"
        }
    }
}

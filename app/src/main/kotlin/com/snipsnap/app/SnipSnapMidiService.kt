package com.snipsnap.app

import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.snipsnap.kit.MidiWire
import com.snipsnap.shell.MidiDeviceId
import com.snipsnap.shell.MidiFingerprint

/**
 * SnipSnap as a MIDI device other apps can open: "SnipSnap In" takes notes
 * from them (they play pads and keys), "SnipSnap Out" gives them the notes
 * SnipSnap plays ([MidiOut]).
 *
 * The system binds this service when the first app opens the device and
 * unbinds after the last one closes; [onCreate] and [onDestroy] are that
 * lifecycle and the only one this service has. While it exists the device
 * counts as bound for [VirtualMidi] and "SNIPSNAP OUT" is a [MidiOut]
 * destination (sent regardless of the hardware switch: whoever opened it
 * asked for the notes).
 *
 * The receiver runs on a binder thread: it parses with its own [MidiWire]
 * (running status is per stream), drops real-time bytes like
 * [MidiInput]'s receiver, and posts the rest to the main thread. It never
 * touches UI state.
 */
class SnipSnapMidiService : MidiDeviceService() {

    private val main = Handler(Looper.getMainLooper())

    private val inReceiver = object : MidiReceiver() {
        private val wire = MidiWire()

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val events = wire.feed(msg, offset, count, timestamp).filter { it !is MidiWire.Event.RealTime }
            if (events.isEmpty()) return
            main.post { VirtualMidi.deliver(events) }
        }
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf(inReceiver)

    override fun onCreate() {
        super.onCreate()
        MidiOut.add(
            MidiOut.Destination(OUT_ID, "SNIPSNAP OUT") { bytes, ts ->
                val receivers = outputPortReceivers
                if (receivers.isNullOrEmpty()) {
                    Log.w(TAG, "SnipSnap Out has no port receiver; note dropped")
                } else {
                    receivers[0].send(bytes, 0, bytes.size, ts)
                }
            },
            hardware = false,
        )
        VirtualMidi.bound(
            MidiDeviceId(
                "SNIPSNAP IN",
                MidiFingerprint.of(
                    MidiFingerprint.Transport.VIRTUAL, 0, 0, 1, 1,
                    MIDI_DEVICE_MANUFACTURER, "SnipSnap In", null,
                ),
            ),
        )
    }

    override fun onDestroy() {
        MidiOut.remove(OUT_ID)
        VirtualMidi.unbound()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SnipSnapMidiService"
        const val OUT_ID = "snipsnap-out"
    }
}

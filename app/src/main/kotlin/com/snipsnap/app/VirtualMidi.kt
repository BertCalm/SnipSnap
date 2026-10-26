package com.snipsnap.app

import com.snipsnap.kit.MidiWire
import com.snipsnap.shell.MidiDeviceId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The seam between [SnipSnapMidiService] (the published "SnipSnap In" port)
 * and whoever listens to it ([MidiInput]). The service reports when another
 * app holds the device and what it plays; listeners hear both.
 *
 * Main thread only, every call. The service posts to the main thread before
 * calling [deliver]; the listener list is a [CopyOnWriteArrayList] so a
 * listener may unsubscribe from inside a callback.
 */
object VirtualMidi {

    interface Listener {
        fun onEvent(event: MidiWire.Event)
        fun onBound(id: MidiDeviceId)
        fun onUnbound()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Non-null while another app holds the device. */
    var boundId: MidiDeviceId? = null
        private set

    fun subscribe(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun unsubscribe(l: Listener) {
        listeners.remove(l)
    }

    internal fun bound(id: MidiDeviceId) {
        boundId = id
        listeners.forEach { it.onBound(id) }
    }

    internal fun unbound() {
        if (boundId == null) return
        boundId = null
        listeners.forEach { it.onUnbound() }
    }

    internal fun deliver(events: List<MidiWire.Event>) {
        // A delivery posted before the other app let go lands after unbound():
        // dropped, so it cannot start a note whose off will never come.
        if (boundId == null) return
        for (e in events) listeners.forEach { it.onEvent(e) }
    }
}

package com.snipsnap.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.MidiInput
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdInk2
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.MidiWire
import com.snipsnap.shell.MidiDeviceId
import com.snipsnap.shell.MidiEndpoint
import com.snipsnap.shell.MidiMonitor
import com.snipsnap.shell.MidiReport

/**
 * MIDI: what is plugged in, and what it sends. Behind the gear, so it is
 * one tap from every screen, the way a player checks a cable.
 *
 * Step 1 of `docs/MIDI_CONTROLLER_PLAN_2026_10.md`, and the MIDI roadmap's
 * P0.7. Every connectivity complaint about MIDI on Android is one of four
 * cases (no MIDI on the phone, nothing found, a device that will not
 * open, a device that sends what no screen uses), and until now the only
 * way to tell them apart was logcat. This screen says which, in words
 * (`:shell`'s [MidiReport] holds them, so they are tested), lists each
 * device with its ports, and shows every delivery as it arrives through
 * [MidiMonitor]: the bytes, what they mean, and the time since the last
 * one from that port.
 *
 * It only listens. Its [MidiInput] is made with `tapRaw`, so the monitor
 * sees pitch bend, pressure and SysEx that the playing screens' parser
 * drops; nothing here plays a note or sends a byte back. It opens and
 * closes with the lifecycle as KEYS does, so a device pulled while the app
 * is in the background is not still listed when it comes back.
 *
 * COPY REPORT puts [MidiReport.text] on the clipboard: the phone, each
 * device's ids and ports, and the last lines of the monitor, ready to
 * paste into an issue.
 */
@Composable
fun MidiScreen() {
    val context = LocalContext.current
    val scheme = LocalScheme.current
    val versionName = rememberVersionName()

    val monitor = remember { MidiMonitor() }
    var snapshot by remember { mutableStateOf(monitor.snapshot()) }
    var endpoints by remember { mutableStateOf<List<MidiEndpoint>>(emptyList()) }
    var refused by remember { mutableStateOf<List<String>>(emptyList()) }
    var supported by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    val midi = remember { MidiInput(context, tapRaw = true) }
    val listener = remember {
        object : MidiInput.Listener {
            // The parsed events are the playing screens' business; the
            // monitor reads the raw bytes below, which carry everything.
            override fun onEvent(event: MidiWire.Event) {}
            override fun onDevicesChanged(devices: List<MidiDeviceId>) { endpoints = midi.endpoints }
            override fun onDeviceRemoved() {}
            override fun onRaw(device: MidiDeviceId, port: String, bytes: ByteArray, timestampNanos: Long) {
                monitor.deliver("${device.name} · $port", bytes, timestampNanos = timestampNanos)
                snapshot = monitor.snapshot()
                copied = false
            }
            override fun onRefused(name: String) {
                if (name !in refused) refused = refused + name
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    supported = midi.open(listener)
                    endpoints = midi.endpoints
                }
                Lifecycle.Event.ON_STOP -> {
                    midi.close()
                    endpoints = emptyList()
                    refused = emptyList()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            midi.close()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .lcdPanel(scheme)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val status = MidiReport.status(supported, endpoints, refused)
            TapeText(MidiReport.TITLE, TapeType.lcdHeader, scheme.lcdInk.tape)
            TapeText(status.first(), TapeType.lcdReadout, scheme.lcdInk.tape, maxLines = 2)
            for (line in status.drop(1)) {
                TapeText(line, TapeType.lcdSmall, scheme.lcdInk2, maxLines = 2)
            }
        }

        for (e in endpoints) {
            var open by rememberSaveable(e.device.fingerprint.key) { mutableStateOf(true) }
            GroupBox(
                legend = e.device.name.uppercase(),
                summary = MidiReport.transport(e.device.fingerprint),
                open = open,
                onToggle = { open = !open },
                scheme = scheme,
            ) {
                TapeText("SENDS: ${e.sends.ifEmpty { listOf("NONE") }.joinToString(" | ")}", TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
                TapeText("RECEIVES: ${e.receives.ifEmpty { listOf("NONE") }.joinToString(" | ")}", TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
                TapeText("KEY ${e.device.fingerprint.key}", TapeType.pixelSmall, scheme.ink2.tape)
            }
        }

        var monitorOpen by rememberSaveable { mutableStateOf(true) }
        GroupBox(
            legend = MidiReport.MONITOR,
            summary = "${snapshot.total} MSG · CLOCK ${snapshot.clockTicks}",
            open = monitorOpen,
            onToggle = { monitorOpen = !monitorOpen },
            scheme = scheme,
        ) {
            if (snapshot.lines.isEmpty()) {
                TapeText(MidiReport.PLAY_SOMETHING, TapeType.lcdSmall, scheme.ink2.tape, maxLines = 2)
            }
            for (l in snapshot.lines) {
                // Two lines each: what it means first, the bytes and timing under it.
                TapeText(l.meaning, TapeType.lcdSmall, scheme.ink.tape, maxLines = 2)
                TapeText(
                    "${l.deltaMillis?.let { "+%.1f MS".format(java.util.Locale.ROOT, it) } ?: "FIRST"}  ${l.hex}  ${l.port.uppercase()}",
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    maxLines = 2,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                if (copied) MidiReport.COPIED else MidiReport.COPY,
                scheme,
                enabled = true,
                modifier = Modifier.weight(1f),
            ) {
                val phone = MidiReport.Phone(versionName, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL)
                val text = MidiReport.text(phone, supported, endpoints, refused, snapshot)
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(MidiReport.CLIP_LABEL, text))
                copied = true
            }
            ActionButton(MidiReport.CLEAR, scheme, enabled = snapshot.total > 0, modifier = Modifier.weight(1f)) {
                monitor.clear()
                snapshot = monitor.snapshot()
                copied = false
            }
        }
    }
}

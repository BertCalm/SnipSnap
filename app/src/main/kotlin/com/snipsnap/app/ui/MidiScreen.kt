package com.snipsnap.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.MidiInput
import com.snipsnap.app.PadEngine
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdInk2
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.MidiWire
import com.snipsnap.shell.ClockProbe
import com.snipsnap.shell.Copy
import com.snipsnap.shell.MidiDeviceId
import com.snipsnap.shell.MidiEndpoint
import com.snipsnap.shell.MidiMonitor
import com.snipsnap.shell.MidiReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

    // CLOCK CHECK (bench S1). The engine lives only for the run; [hold] is how the
    // screen closes it if the screen is left or stopped before the run ends.
    val scope = rememberCoroutineScope()
    val hold = remember { ClockHold() }
    var clockRunning by remember { mutableStateOf(false) }
    var clockLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var clockReport by remember { mutableStateOf<MidiReport.ClockReport?>(null) }
    fun abortClock() {
        hold.job?.cancel()
        hold.job = null
        hold.engine?.close()
        hold.engine = null
        clockRunning = false
    }
    fun startClock() {
        if (clockRunning) return
        clockRunning = true
        clockLines = emptyList()
        clockReport = null
        copied = false
        hold.job = scope.launch {
            val outcome = try {
                runClockCheck(context, hold)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClockOutcome.NoStream
            }
            // Only the run that is still current may write: an abort has already reset the screen.
            if (hold.job === coroutineContext[Job]) {
                clockLines = when (outcome) {
                    ClockOutcome.NoStream -> listOf(Copy.MIDI_CLOCK_NO_STREAM)
                    ClockOutcome.RouteChanged -> listOf(Copy.MIDI_CLOCK_ROUTE_CHANGED)
                    is ClockOutcome.Done ->
                        listOf(outcome.report.line) + if (outcome.shared) listOf(Copy.MIDI_CLOCK_SHARED) else emptyList()
                }
                clockReport = (outcome as? ClockOutcome.Done)?.report
                hold.job = null
                clockRunning = false
            }
        }
    }

    val midi = remember { MidiInput(context, tapRaw = true) }
    val listener = remember {
        object : MidiInput.Listener {
            // The parsed events are the playing screens' business; the
            // monitor reads the raw bytes below, which carry everything.
            override fun onEvent(event: MidiWire.Event, device: MidiDeviceId?) {}
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
                    // A background app gets no frames, so a run left going would resume on
                    // a dead or rerouted stream; end it here and let the player run it again.
                    abortClock()
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
            abortClock()
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

        Column(
            Modifier
                .fillMaxWidth()
                .lcdPanel(scheme)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton(
                if (clockRunning) Copy.MIDI_CLOCK_RUNNING else Copy.MIDI_CLOCK_CHECK,
                scheme,
                enabled = !clockRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { startClock() }
            for (line in clockLines) {
                TapeText(line, TapeType.lcdSmall, scheme.lcdInk2, maxLines = 4)
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
                val text = MidiReport.text(phone, supported, endpoints, refused, snapshot, clock = clockReport)
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

/**
 * What a CLOCK CHECK leaves behind: the engine it opened (so the screen can
 * close it if the screen goes first) and the job running it. Main thread only.
 */
private class ClockHold {
    var engine: PadEngine? = null
    var job: Job? = null
}

private sealed interface ClockOutcome {
    data object NoStream : ClockOutcome
    data object RouteChanged : ClockOutcome
    data class Done(val report: MidiReport.ClockReport, val shared: Boolean) : ClockOutcome
}

/** How long the check samples, and how often the click sounds. */
private const val CLOCK_RUN_NANOS = 6_000_000_000L
private const val CLOCK_HIT_EVERY_NANOS = 1_000_000_000L

/**
 * Bench S1 (`docs/MIDI_CLOCK_PROBE_SPEC_2026_10.md` 2.3): open a pad engine the way
 * [com.snipsnap.app.PadHost] does, sound a click once a second for six seconds, and
 * sample the frames written against the hardware position once per display frame.
 *
 * Main thread throughout: every [PadEngine] call, [PadEngine.hardwarePosition] above
 * all, happens inside `withFrameNanos`, which is why this runs in the screen's scope
 * and nowhere else. The engine is closed in `finally`, so a cancel (screen left, app
 * stopped) closes it too; [hold] lets the screen close it first if it goes first.
 */
private suspend fun runClockCheck(context: Context, hold: ClockHold): ClockOutcome {
    val engine = PadEngine(deviceSampleRate(context))
    hold.engine = engine
    try {
        if (!engine.start()) return ClockOutcome.NoStream
        val click = DrumSynth.click(engine.sampleRate(), accent = false)
        val sample = engine.loadSnips(listOf(click)).firstOrNull() ?: return ClockOutcome.NoStream
        val route = outputRoute(context)
        val out = LongArray(2)
        val samples = ArrayList<ClockProbe.Sample>()
        var startNanos = -1L
        var nextHit = 0L
        var voiceId = 0
        while (true) {
            val s = withFrameNanos {
                val now = System.nanoTime()
                if (startNanos < 0L) startNanos = now
                if (now - startNanos >= nextHit) {
                    nextHit += CLOCK_HIT_EVERY_NANOS
                    engine.hitLayers(
                        layers = listOf(PadEngine.Layer(voiceId++, sample, PadEngine.CLICK_GAIN, PadEngine.CLICK_GAIN, reverse = false)),
                        startFrame = 0L,
                        endFrame = click.frameCount.toLong(),
                        loopStart = -1L,
                        pitch = 1.0,
                    )
                }
                val ok = engine.hardwarePosition(out)
                ClockProbe.Sample(now, engine.framesRendered(), if (ok) out[0] else null, if (ok) out[1] else null)
            }
            // A route change (headphones in or out) restarts the stream under us: what
            // was sampled before it and after it is two clocks, so stop rather than fit across them.
            if (engine.needsRestart()) return ClockOutcome.RouteChanged
            samples += s
            if (s.wallNanos - startNanos >= CLOCK_RUN_NANOS) break
        }
        // A route change that Android made without asking the stream to restart (a quiet
        // re-route) shows only in the device list: if it moved, the run spans two outputs.
        // No automatic retry; the owner is told to run it again.
        if (outputRoute(context) != route) return ClockOutcome.RouteChanged
        val rate = engine.sampleRate()
        val result = ClockProbe.analyze(samples, rate)
        val shared = engine.isShared()
        return ClockOutcome.Done(
            MidiReport.ClockReport(
                line = ClockProbe.describe(result),
                result = result,
                sampleRate = rate,
                shared = shared,
                framesPerBurst = engine.framesPerBurst(),
                audioApi = engine.audioApi(),
                latencyMillis = engine.latencyMillis(),
                model = Build.MODEL,
                sdk = Build.VERSION.SDK_INT,
                route = route,
            ),
            shared,
        )
    } finally {
        engine.close()
        if (hold.engine === engine) hold.engine = null
    }
}

/** The first of Bluetooth, wired, USB that is plugged in, else the speaker: what the report calls the route. */
private fun outputRoute(context: Context): MidiReport.Route {
    val am = context.getSystemService(AudioManager::class.java) ?: return MidiReport.Route.SPEAKER
    val types = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
    return when {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && AudioDeviceInfo.TYPE_BLE_HEADSET in types) -> MidiReport.Route.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types || AudioDeviceInfo.TYPE_WIRED_HEADSET in types -> MidiReport.Route.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET in types || AudioDeviceInfo.TYPE_USB_DEVICE in types -> MidiReport.Route.USB
        else -> MidiReport.Route.SPEAKER
    }
}

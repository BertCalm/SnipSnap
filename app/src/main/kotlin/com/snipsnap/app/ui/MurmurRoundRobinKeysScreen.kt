package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.AudioVoice
import com.snipsnap.app.MurmurRoundRobinKeysLayout
import com.snipsnap.app.MurmurRoundRobinOwnedPlayer
import com.snipsnap.app.MurmurRoundRobinScreenSession
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Busy
import com.snipsnap.shell.KeysLayout
import com.snipsnap.shell.Layout
import com.snipsnap.shell.MurmurRoundRobinShelf
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Separate player/route for admitted RR banks; the ordinary sidecar KeysScreen is unchanged. */
@Composable
internal fun MurmurRoundRobinKeysScreen(
    saved: MurmurRoundRobinShelf.SavedBank,
    shelf: File,
    backLabel: String,
    onBack: () -> Unit,
) {
    val scheme = LocalScheme.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = remember(shelf, saved.name, saved.identitySha256) {
        val owner = MurmurRoundRobinOwnedPlayer()
        MurmurRoundRobinScreenSession(saved.supportedMidi, object : MurmurRoundRobinScreenSession.Port {
            override val ready get() = owner.identity != null
            override val activeCount get() = owner.activeCount
            override val hasPendingStops get() = owner.hasPendingStops
            override fun open(cancelled: () -> Boolean) = owner.open(saved, shelf, cancelled)
            override fun chord(notes: List<Int>, velocity: Float) = owner.noteOnChord(notes, velocity).size == notes.size
            override fun release(note: Int) = owner.noteOff(note)
            override fun allOff() = owner.allOff()
            override fun poll() = owner.poll()
            override fun close() = owner.close()
        }).apply { foreground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    }
    var state by remember(session) { mutableStateOf(session.state) }
    var mode by remember(session) { mutableStateOf(MurmurRoundRobinKeysLayout.Mode.KEYS) }
    var octave by remember(session) { mutableIntStateOf(0) }
    val pads = remember(mode, octave) { MurmurRoundRobinKeysLayout.pads(mode, octave) }

    LaunchedEffect(session) {
        session.load()
        while (isActive) {
            state = session.state
            // Keep reaping endings/retrying release debt after the last finger lifts.
            if (state.needsPoll) session.poll()
            delay(16)
        }
    }
    ReportBusy(remember(session, state.loading) {
        if (state.loading) Busy("LOADING ROUND-ROBIN INSTRUMENT", cancel = session::cancelLoading) else null
    })
    val audioVoice = remember(session) { object : AudioVoice { override fun silence() = session.silence() } }
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    session.foreground(true)
                    AudioFocus.acquire(audioVoice)
                    if (!session.state.loaded && !session.state.loading) session.load()
                }
                Lifecycle.Event.ON_STOP -> { session.foreground(false); AudioFocus.release(audioVoice) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.close() // Immediate cancellation; native destruction runs on its FIFO worker.
            AudioFocus.release(audioVoice)
        }
    }
    fun back() { session.close(); onBack() }
    BackHandler(onBack = ::back)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().lcdPanel(scheme).padding(10.dp)) {
            TapeText(saved.name.uppercase(java.util.Locale.ROOT), TapeType.lcdHeader, scheme.lcdInk.tape)
            TapeText("ROUND ROBIN · ${KeysLayout.label(saved.supportedMidi.first)}–${KeysLayout.label(saved.supportedMidi.last)} · OCT ${if (octave >= 0) "+" else ""}$octave", TapeType.lcdSmall, scheme.amber.tape)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(backLabel, scheme, enabled = true, onClick = ::back)
            ActionButton("OCT −", scheme, enabled = octave > -2 && !state.loading, modifier = Modifier.weight(1f)) {
                session.silence(); octave--
            }
            ActionButton("OCT +", scheme, enabled = octave < 2 && !state.loading, modifier = Modifier.weight(1f)) {
                session.silence(); octave++
            }
            ActionButton("STOP", scheme, enabled = state.loaded || state.loading, onClick = session::silence)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (choice in MurmurRoundRobinKeysLayout.Mode.entries) {
                ActionButton(choice.label, scheme, enabled = !state.loading, dimmed = choice != mode,
                    modifier = Modifier.weight(1f)) { session.silence(); mode = choice }
            }
        }
        key(session, mode, octave) {
            Column(Modifier.fillMaxWidth().systemGestureExclusion(), verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                for (row in WINDOW_GRID_ROWS) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                        for (slot in row) {
                            val pad = pads[slot - 1]
                            KeyPad(pad.label, state.canPlay && pad.covered(saved.supportedMidi),
                                onPress = { velocity -> session.press(slot, pad.notes, velocity) },
                                onRelease = { session.release(slot) }, modifier = Modifier.weight(1f),
                                disabledReason = if (state.loading) "LOADING" else if (!state.canPlay) "UNAVAILABLE" else "OUTSIDE INSTRUMENT RANGE")
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(if (state.loading) "CANCEL LOAD" else "RELOAD", scheme, enabled = !state.closed, modifier = Modifier.weight(1f)) {
                if (state.loading) session.cancelLoading() else session.load()
            }
            TapeText("${state.activeCount}/8 VOICES", TapeType.lcdSmall, scheme.ink2.tape)
        }
        if (state.error != null) TapeText(checkNotNull(state.error), TapeType.lcdSmall, scheme.accent.tape, maxLines = 3)
        else if (state.loading) TapeText("LOADING… KEYS WILL BE READY SHORTLY.", TapeType.lcdSmall, scheme.ink2.tape)
    }
}

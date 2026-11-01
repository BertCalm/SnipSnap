package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.AudioFocus
import com.snipsnap.app.AudioVoice
import com.snipsnap.app.InstrumentPlayer
import com.snipsnap.app.MidiInput
import com.snipsnap.app.MidiOut
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.MidiWire
import com.snipsnap.kit.OneNote
import com.snipsnap.shell.Busy
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KeysLayout
import com.snipsnap.shell.KeysMidi
import com.snipsnap.shell.Layout
import com.snipsnap.shell.MidiDeviceId
import com.snipsnap.shell.MidiKeys
import com.snipsnap.shell.PadHit
import com.snipsnap.shell.Schemes
import com.snipsnap.xpm.VoiceMode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * KEYS: an instrument the shop made, played on the same 4×4 the drums
 * use (UI_DESIGN "keys on the grid") — the instrument's root on A01,
 * ascending left to right and bottom to top, LAYOUT the chromatic or a
 * scale, OCTAVE moving the whole grid. Press to sound, let go to
 * release: a looped pad holds while the finger does. Over
 * `InstrumentPlayer`, `:shell`'s `InstrumentEngine` on the device.
 *
 * A MIDI keyboard plays the same instrument through the same player:
 * `MidiInput` hands every connected device's notes to `:shell`'s
 * `MidiKeys`, whose rules (the keyboard's own velocity, the sustain
 * pedal, the panics) are tested there. The grid and the keyboard are two
 * hands on one instrument, so a keyboard note and a grid note share the
 * eight voices and steal from each other's oldest.
 *
 * LEARN opens MIDI learn: a knob on the player's volume and keys or pads
 * on OCT − and OCT +, through `:shell`'s `KeysMidi`, kept beside the other
 * screens' bindings.
 */
private val GRID_ROWS = WINDOW_GRID_ROWS

@Composable
fun KeysScreen(
    sidecar: File,
    instrument: InstrumentStore.Instrument,
    backLabel: String,
    onBack: () -> Unit,
    // Where a failed voice-mode save is told; the app wires the dock's toast, a screen shown alone drops it.
    onToast: (String) -> Unit = {},
    // Told with the re-read instrument after a voice-mode save, so the caller's copy is not left stale. Does not re-key the player or reseed the label.
    onSaved: (InstrumentStore.Instrument) -> Unit = {},
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    // Keyed on both: a re-read sidecar (same file, new contents) reloads the
    // player. The zones' WAVs are read off the main thread; until they land
    // a key plays nothing, never a stale instrument.
    // A voice-mode save hands the caller a re-read instrument that differs only
    // in its mode; that must not reload the player, so the mode is left out of
    // the key (the player and the label already hold it).
    val loadKey = instrument.copy(voiceMode = VoiceMode.POLY)
    val player = remember(sidecar, loadKey) { InstrumentPlayer(context) }
    DisposableEffect(player) { onDispose { player.close() } }
    // True from the player's birth until its zones have been read. Keyed on
    // the player, so a superseded open's `finally` lowers the flag of the
    // player it was opening and never the new one's.
    var opening by remember(player) { mutableStateOf(true) }
    LaunchedEffect(player) {
        try {
            withContext(Dispatchers.IO) { player.open(sidecar, instrument) }
        } finally {
            opening = false
        }
    }

    var layout by remember(sidecar) { mutableStateOf(KeysLayout.DEFAULT_LAYOUT) }
    var octave by remember(sidecar) { mutableIntStateOf(0) }
    val root = instrument.rootNote

    // The instrument's voice mode: heard at once through the player, saved to
    // the package's three descriptors in the background. A failed save puts
    // the label (and the player) back. Work that dies with the screen, so it
    // reports through ReportBusy.
    var voiceMode by remember(sidecar, loadKey) { mutableStateOf(instrument.voiceMode) }
    var savingVoice by remember(sidecar, loadKey) { mutableStateOf(false) }
    ReportBusy(remember(opening, savingVoice) {
        when {
            opening -> Busy(Copy.KEYS_LOADING_BUSY)
            savingVoice -> Busy(Copy.KEYS_VOICE_BUSY)
            else -> null
        }
    })
    val scope = rememberCoroutineScope()
    fun cycleVoiceMode() {
        // Not while the zones load: the player has no instrument to change yet, and the load would overwrite the choice.
        if (savingVoice || opening) return
        val previous = voiceMode
        val next = VoiceMode.entries[(previous.ordinal + 1) % VoiceMode.entries.size]
        voiceMode = next
        player.setVoiceMode(next)
        savingVoice = true
        scope.launch {
            try {
                // Backing out of the screen must not strand the caller's copy: once the write is
                // under way, the re-read and onSaved run to the end (onSaved updates App-level state
                // that outlives this screen).
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        // The package is named for its sidecar file, which may differ from the program's own name.
                        OneNote.rewriteDescriptors(
                            checkNotNull(sidecar.parentFile),
                            sidecar.name.removeSuffix(InstrumentStore.SUFFIX),
                            instrument.toProgram().copy(voiceMode = next),
                        )
                    }
                    // The write is done; a failed re-read only leaves the caller's copy stale.
                    val saved = withContext(Dispatchers.IO) { runCatching { InstrumentStore.read(sidecar) }.getOrNull() }
                    if (saved != null) onSaved(saved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                voiceMode = previous
                player.setVoiceMode(previous)
                onToast(Copy.KEYS_VOICE_FAILED)
            } finally {
                savingVoice = false
            }
        }
    }

    // The keyboard's rules over this same player. Every "go quiet" on
    // this screen goes through `midiKeys.allOff()` rather than the
    // player's own, so the sustain pedal is forgotten with the notes:
    // a pedal still "down" after a panic would hold the next key up.
    val midiKeys = remember(player) {
        MidiKeys(object : MidiKeys.Voice {
            override fun noteOn(note: Int, velocity: Float) { player.noteOn(note, velocity) }
            override fun noteOff(note: Int) = player.noteOff(note)
            // Every local silence is mirrored on the wire (the one funnel for
            // midiKeys.allOff()): a local silence that is not mirrored leaves a
            // receiver holding a note whose off will name a different key.
            override fun allOff() { player.allOff(); MidiOut.allNotesOff() }
        })
    }
    fun silence() = midiKeys.allOff()
    // The LCD's readout: what is plugged in, by name. Empty when nothing
    // is, and never shown at all on a phone that cannot do MIDI. The
    // keyboard is opened and closed with the lifecycle, below, beside the
    // audio focus: a note arriving while the app is backgrounded would
    // otherwise restart the stream ON_STOP had just silenced.
    var midiDevices by remember(player) { mutableStateOf<List<MidiDeviceId>>(emptyList()) }
    var midiSupported by remember(player) { mutableStateOf(false) }

    // MIDI learn (the LEARN panel): a knob on the player's volume, keys or
    // pads on OCT − and OCT +. The rules are `:shell`'s KeysMidi and
    // MidiLearn; a control learned here is consumed, so it no longer plays
    // a note, and anything not learned reaches MidiKeys as before.
    val learnState = rememberMidiLearn(KeysMidi.SCREEN, prefer = KeysMidi::prefer)
    val learn = learnState.learn
    var showLearn by remember { mutableStateOf(false) }
    LaunchedEffect(showLearn) { learnState.showing = showLearn }
    // Not saved: each visit starts with the instrument as it was made.
    var volume by remember(player) { mutableStateOf(1f) }
    LaunchedEffect(player, volume) { player.setVolume(KeysMidi.gain(volume)) }

    fun stepOctave(step: Int) {
        val next = KeysMidi.octave(octave, step)
        if (next == octave) return
        silence()
        octave = next
    }

    val midi = remember(player) { MidiInput(context) }
    val midiListener = remember(player) {
        object : MidiInput.Listener {
            override fun onEvent(event: MidiWire.Event, device: MidiDeviceId?) {
                learnState.heard(event)
                val result = learn.handle(event, device?.fingerprint?.key) { if (it == KeysMidi.VOLUME) volume else null }
                learnState.learned(result.learned, KeysMidi::settle)
                val change = KeysMidi.changes(result.actions)
                change.volume?.let { volume = it }
                if (change.octaveStep != 0) stepOctave(change.octaveStep)
                if (!result.consumed) midiKeys.handle(event)
            }
            override fun onDevicesChanged(devices: List<MidiDeviceId>) { midiDevices = devices }
            // The cable is out: its note-offs will never come.
            override fun onDeviceRemoved() = midiKeys.allOff()
        }
    }

    // The back chip's own path (it reads [backLabel], the model's `Navigation.backChip`) — shared by the chip and system Back so both
    // silence the instrument before leaving.
    fun requestBack() {
        silence()
        onBack()
    }
    BackHandler(onBack = ::requestBack)

    // Audit finding: KEYS was one of two audio-bearing screens with no
    // ON_STOP handler — PLAY/KIT/GROOVE all silence on backgrounding, but a
    // held key here kept ringing. Same lesson, same shape: ON_STOP means
    // allOff(), and a focus loss (a call, another app's audio) asks for the
    // same silence, so the same AudioFocus registration rides this effect —
    // see PlayScreen's own copy of this pattern for the full reasoning.
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioVoice = remember(player) { object : AudioVoice { override fun silence() = midiKeys.allOff() } }
    DisposableEffect(lifecycleOwner, player) {
        AudioFocus.acquire(audioVoice)
        // The keyboard rides the same events: open while the screen can
        // make sound (addObserver replays ON_START for a screen already
        // up), closed the moment it must not. The readout is cleared on
        // the way down so a device pulled while backgrounded is not still
        // named when the screen comes back; reopening lists what is there.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    silence()
                    AudioFocus.release(audioVoice)
                    midi.close()
                    midiDevices = emptyList()
                }
                Lifecycle.Event.ON_START -> {
                    AudioFocus.acquire(audioVoice)
                    midiSupported = midi.open(midiListener)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            AudioFocus.release(audioVoice)
            midi.close()
            // Leaving the screen with a key down: its release never comes.
            MidiOut.allNotesOff()
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Layout.LCD_HEADER_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TapeText(instrument.name.uppercase(java.util.Locale.ROOT), TapeType.lcdHeader, scheme.lcdInk.tape, Modifier.weight(1f, fill = false))
            TapeText(
                "ROOT ${KeysLayout.label(root)}  OCT ${if (octave >= 0) "+" else ""}$octave" + (if (voiceMode != VoiceMode.POLY) "  ${voiceMode.name}" else "") + if (volume < 1f) "  ${KeysMidi.label(volume)}" else "",
                TapeType.lcdSmall,
                scheme.amber.tape,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(backLabel, scheme, enabled = true, onClick = ::requestBack)
            ActionButton("OCT −", scheme, enabled = octave > KeysLayout.OCTAVE_MIN, modifier = Modifier.weight(1f), onClick = { stepOctave(-1) })
            ActionButton("OCT +", scheme, enabled = octave < KeysLayout.OCTAVE_MAX, modifier = Modifier.weight(1f), onClick = { stepOctave(+1) })
            ActionButton("VOICE ${voiceMode.name}", scheme, enabled = !savingVoice && !opening, working = savingVoice, modifier = Modifier.weight(1f), onClick = ::cycleVoiceMode)
            // Only where a keyboard could be heard at all, like the line under the grid.
            if (midiSupported) {
                ActionButton(
                    "LEARN",
                    scheme,
                    enabled = true,
                    dimmed = !showLearn,
                    modifier = Modifier.semantics { selected = showLearn },
                ) { showLearn = !showLearn }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (l in KeysLayout.LAYOUTS) {
                val selected = l == layout
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .raisedBevel(scheme, fill = if (selected) scheme.amber.tape.copy(alpha = 0.85f) else null)
                        .tapeClick(label = l) { silence(); layout = l }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(l, TapeType.pixelSmall, if (selected) scheme.titleInk.tape else scheme.ink2.tape, maxLines = 2)
                }
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
            for (row in GRID_ROWS) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                    for (slot in row) {
                        val note = KeysLayout.noteFor(slot, root, layout, octave)
                        val covered = note != null && instrument.zoneFor(note) != null
                        KeyPad(
                            label = note?.let { KeysLayout.label(it) } ?: "—",
                            enabled = covered,
                            // Touch only: these go out over MIDI on the keys channel, and only for a note that sounded locally.
                            // The MidiKeys.Voice above is MIDI in and stays silent.
                            onPress = { v -> note?.let { if (player.noteOn(it, v)) MidiOut.keysOn(it, v, System.nanoTime()) } },
                            onRelease = { note?.let { player.noteOff(it); MidiOut.keysOff(it, System.nanoTime()) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (midiSupported && showLearn) {
            MidiLearnPanel(
                learnState,
                KeysMidi.TARGETS,
                supported = true,
                connected = midiDevices.isNotEmpty(),
                hint = "${KeysMidi.label(volume)} · A LEARNED KEY PLAYS NO NOTE",
            )
        }

        // The keyboard line, PLAY's own hint row's shape: what is plugged
        // in, or how to plug one in. A phone with no MIDI feature draws
        // nothing here rather than an invitation it cannot honour.
        if (midiSupported) {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                TapeText(midiLine(midiDevices), TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
            }
        }
    }
}

/** The words under the grid: the connected keyboard by name, or the invitation. */
internal fun midiLine(devices: List<MidiDeviceId>): String =
    if (devices.isEmpty()) NO_MIDI_DEVICE
    else "MIDI · " + devices.joinToString(" + ") { it.name.uppercase(java.util.Locale.ROOT) }

/** Nothing plugged in: say what would work, in the words the USB cable is sold under. */
internal const val NO_MIDI_DEVICE = "NO MIDI KEYBOARD · PLUG ONE IN OVER USB"

/**
 * Touch-Y velocity, like PLAY's pads: the top of the pad is softest.
 *
 * The floor comes from `PadHit.SOFTEST` rather than being written here.
 * It used to be a second `0.35f` with the ramp retyped beside it, which is
 * how KIT later came to disagree with PLAY about which end of a pad is
 * loud.
 */
private const val MIN_VELOCITY = PadHit.SOFTEST

/** The velocity a synthesized TalkBack click uses — see PlayScreen.kt's own CENTER_VELOCITY. */
private val CENTER_VELOCITY = PadHit.CENTER

@Composable
private fun KeyPad(
    label: String,
    enabled: Boolean,
    onPress: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalScheme.current
    val shape = RoundedCornerShape(Layout.PAD_RADIUS.dp)
    var down by remember { mutableStateOf(false) }
    val ink = scheme.amber.tape
    Box(
        modifier
            .height(Layout.PAD_H.dp)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            .background(ink.copy(alpha = if (down) 0.45f else if (enabled) 0.12f else 0.03f), shape)
            .border(2.dp, if (enabled) ink else ink.copy(alpha = 0.3f), shape)
            // Always a semantics node, enabled or not — dropping the
            // modifier chain when !enabled (the pointerInput branch
            // below still does, for touch) removed this cell from the
            // accessibility tree entirely, the same "vanishes instead
            // of announcing unavailable" gap as finding 12 elsewhere.
            // mergeDescendants: without it, the label TapeText below is
            // a second, separately-focusable node.
            .semantics(mergeDescendants = true) {
                contentDescription = if (enabled) "KEY $label" else "KEY $label: NOT MAPPED"
                if (enabled) {
                    onClick(label = "PLAY") { onPress(CENTER_VELOCITY); onRelease(); true }
                } else {
                    disabled()
                }
            }
            .let { m ->
                if (!enabled) m else m.pointerInput(label) {
                    while (true) {
                        val first = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        val t = (first.position.y / height).coerceIn(0f, 1f)
                        down = true
                        onPress(PadHit.velocityAt(t, 1f))
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                                if (!change.pressed) break
                            }
                        }
                        down = false
                        onRelease()
                    }
                }
            }
            .padding(5.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        TapeText(label, TapeType.marker, if (enabled) scheme.ink.tape else scheme.ink3.tape)
    }
}

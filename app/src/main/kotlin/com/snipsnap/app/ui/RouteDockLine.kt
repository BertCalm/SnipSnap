package com.snipsnap.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.snipsnap.app.AudioRoute
import com.snipsnap.app.RecordLatency
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Location
import com.snipsnap.shell.Stage

/**
 * Keeps the audio route watched while the app is up, and says so in the
 * dock when KIT or BEAT is open over Bluetooth: the delay is a fact the
 * player cannot see and will otherwise blame on the app.
 *
 * It uses the dock's ordinary line, said at this location, so moving to
 * another place or stage rests it, and a line something else said first is
 * not replaced. Its own composable so the callers (App) take one call and
 * no state.
 */
@Composable
internal fun RouteDockLine(dock: DockState, location: Location) {
    val context = LocalContext.current
    remember(context) { RecordLatency.init(context) }
    DisposableEffect(context) {
        AudioRoute.register(context)
        onDispose { AudioRoute.unregister(context) }
    }
    val bluetooth = AudioRoute.isBluetooth
    val stage = location.stage
    val onKitOrBeat = location.benches.isEmpty() && (stage == Stage.PADS || stage == Stage.BEAT)
    LaunchedEffect(bluetooth, stage, onKitOrBeat) {
        if (bluetooth && onKitOrBeat && dock.now.at == null) dock.say(Copy.BLUETOOTH_ADDS_DELAY)
    }
}

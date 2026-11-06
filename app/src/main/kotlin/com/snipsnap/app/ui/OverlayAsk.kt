package com.snipsnap.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy

/**
 * The Bubble's permission, asked in words (UAT E2-OVERLAY, ruled 3 Nov 2026). Android keeps
 * "Display over other apps" behind a settings page, not a dialog, and the app used to open that
 * page by itself the first time the player armed: a stranger to the app landed in system
 * settings mid-capture with nothing said. Now nothing opens until the player asks.
 *
 * While a session is armed and Android has not allowed drawing over other apps, this draws
 * [Copy.BUBBLE_WHY] and a door, [Copy.BUBBLE_ALLOW], which is the only thing that opens the
 * settings page. It is a standing line, not a once-only offer: the Bubble is optional (the
 * notification's SNIP is the whole story without it), so the line asks once per sight and never
 * blocks anything, and it goes the moment the permission is granted. The grant is found by
 * re-reading [Settings.canDrawOverlays] every time the Activity starts or resumes, which is how
 * the player's return from the settings page lands. When the grant arrives during a session,
 * [MicSessionService.arm] runs again: its re-entry path attaches the Bubble without touching
 * the AudioRecord, as the old result callback did.
 *
 * Draws nothing when idle, when granted, or on a phone with no settings page to open it on
 * (that failure is said through [onFailed]).
 */
@Composable
fun OverlayAsk(onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val armed by MicSessionService.armed.collectAsState()
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // The grant must be re-read live after the settings page: a closed-over value would never see it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                allowed = Settings.canDrawOverlays(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Armed while the grant was missing: the Bubble was not attached, so when the grant lands
    // the running session is told once to attach it.
    var waitedWhileArmed by remember { mutableStateOf(false) }
    LaunchedEffect(armed, allowed) {
        if (armed && !allowed) waitedWhileArmed = true
        if (armed && allowed && waitedWhileArmed) {
            waitedWhileArmed = false
            MicSessionService.arm(context)
        }
    }
    if (!armed || allowed) return

    val scheme = LocalScheme.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText(Copy.BUBBLE_WHY, TapeType.pixelSmall, scheme.ink2.tape, Modifier.weight(1f), maxLines = 3)
        ActionButton(Copy.BUBBLE_ALLOW, scheme, enabled = true) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            }
            if (opened.isFailure) onFailed()
        }
    }
}

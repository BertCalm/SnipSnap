package com.snipsnap.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Busy
import com.snipsnap.shell.MurmurRoundRobinBankImport
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One explicitly picked complete RR bank ZIP; ordinary instrument import stays unchanged. */
@Composable
internal fun MurmurRoundRobinImport(shelf: File, enabled: Boolean, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var pending by remember(shelf) { mutableStateOf<Uri?>(null) }
    val scheme = LocalScheme.current
    var cancellation by remember(shelf) { mutableStateOf<AtomicBoolean?>(null) }
    var picking by remember(shelf) { mutableStateOf(false) }
    var message by remember(shelf) { mutableStateOf<String?>(null) }
    DisposableEffect(shelf, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> { foreground = false; cancellation?.set(true) }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancellation?.set(true) }
    }
    val active = cancellation
    if (active != null) ReportBusy(remember(active) { Busy("IMPORTING RR BANK", cancel = { active.set(true) }) })
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null && cancellation == null) {
            if (uri.scheme != "content") message = "CHOOSE AN RR BANK ZIP FROM FILES."
            else pending = uri
        }
    }
    LaunchedEffect(pending, foreground, enabled) {
        val uri = pending
        if (uri != null && foreground && cancellation == null && enabled) {
            pending = null
            val cancel = AtomicBoolean(false)
            cancellation = cancel
            message = null
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalArgumentException("Document could not be opened")
                        val saved = try {
                            MurmurRoundRobinBankImport.importZip(input, context.cacheDir.canonicalFile, shelf, cancel::get)
                        } catch (failure: Throwable) {
                            try { input.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                            throw failure
                        }
                        // A provider close failure after publication is cleanup debt, not failed import.
                        val closed = try { input.close(); true } catch (_: Exception) { false }
                        saved to closed
                    }
                    // A completed atomic admission wins a late cancel; refresh the real shelf.
                    message = if (imported.first.stagingCleaned && imported.second) "RR BANK IMPORTED. OPEN IT BELOW."
                        else "RR BANK IMPORTED. CLEANUP NEEDS ATTENTION."
                    onImported()
                } catch (_: CancellationException) {
                    message = "RR BANK IMPORT CANCELLED."
                } catch (_: Exception) {
                    message = "COULD NOT IMPORT. CHOOSE A COMPLETE SUPPORTED RR BANK ZIP."
                } finally { cancellation = null }
            }
        }
    }
    Column {
        TapeText("CHOOSE A COMPLETE STEADY ROOT C2 RR BANK ZIP.", TapeType.lcdSmall, scheme.ink2.tape, maxLines = 2)
        ActionButton(if (active != null) "CANCEL RR IMPORT" else "IMPORT RR BANK", scheme,
            enabled = active != null || (enabled && foreground && !picking && pending == null)) {
            if (active != null) active.set(true)
            else { picking = true; picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        }
        if (active != null) TapeText("IMPORTING… CANCELLING MAY TAKE A MOMENT.", TapeType.lcdSmall, scheme.ink2.tape, maxLines = 2)
        else if (message != null) TapeText(checkNotNull(message), TapeType.lcdSmall, scheme.ink2.tape, maxLines = 3)
    }
}

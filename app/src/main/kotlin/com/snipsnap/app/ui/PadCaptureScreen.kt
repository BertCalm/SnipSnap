package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.PadCapture
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** GRAB looks back this far into the ring for the last hit — PadCapture.grabOneShot trims it down from there. */
private const val GRAB_SECONDS = 2
private val GRAB_FRAMES get() = GRAB_SECONDS * MicSessionService.SAMPLE_RATE

private fun padTag(slot: Int): String = "A%02d".format(slot)

/**
 * The capture surface: what a long-press on an empty [KitScreen] pad opens.
 * One slot, one job — arm the mic (if it isn't already), watch the meter for
 * the hit that just happened, GRAB it onto this pad.
 *
 * This screen opens its own [KitBuilderModel] on [entry]'s folder for the
 * GRAB write, same as `PadSheetScreen`/`SynthScreen` do — a kit is its
 * folder, so a fresh open always sees whatever's actually on disk right now.
 * [appScope] is `App()`'s own `rememberCoroutineScope()`; the GRAB write is
 * launched into it (not a locally-remembered scope) so a MenuRow tab switch
 * mid-write can't cancel a WAV that's already partway onto disk.
 */
@Composable
fun PadCaptureScreen(
    entry: KitShelf.Entry,
    slot: Int,
    armed: Boolean,
    level: Float,
    onRequestArm: () -> Unit,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
    appScope: CoroutineScope,
) {
    val scheme = LocalScheme.current
    var grabbing by remember { mutableStateOf(false) }

    fun grab() {
        if (!armed || grabbing) return
        grabbing = true
        appScope.launch {
            try {
                val updated: Kit? = withContext(Dispatchers.IO) {
                    val raw = MicSessionService.snapshotTail(GRAB_FRAMES) ?: return@withContext null
                    val snip: Snip = PadCapture.grabOneShot(raw, MicSessionService.SAMPLE_RATE)
                        ?: return@withContext null
                    val cls = Classifier.classify(snip).drumClass
                    val model = KitBuilderModel.open(entry.dir)
                    model.assign(slot, snip, cls, "%s".format(cls))
                    model.save()
                    model.kit
                }
                if (updated == null) {
                    // Distinguish the two null paths live, not off the
                    // composition's `armed` param — a session can eject
                    // mid-grab (EJECT from the notification, most likely),
                    // and this read is what keeps the toast honest about
                    // which of the two actually happened.
                    onToast(if (!MicSessionService.armed.value) "NOT LISTENING YET" else "NOTHING TO GRAB YET")
                } else {
                    onKitUpdated(updated)
                    onToast("GRABBED → PAD $slot")
                    onBack()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onToast("GRAB FAILED: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                grabbing = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Layout.LCD_HEADER_H.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip("◄ KIT", scheme, Modifier.width(64.dp), onClick = onBack)
            Spacer(Modifier.weight(1f))
            TapeText("PAD ${padTag(slot)}", TapeType.lcdHeader, scheme.lcdInk.tape)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).lcdPanel(scheme).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (armed) {
                    CaptureLevelIndicator()
                    TapeText(
                        "HIT SOMETHING, THEN GRAB IT.",
                        TapeType.lcdSmall,
                        scheme.lcdInk.tape,
                        maxLines = 2,
                    )
                } else {
                    TapeText(
                        "NOT LISTENING YET. START THE MIC, THEN HIT SOMETHING.",
                        TapeType.lcdSmall,
                        scheme.lcdInk.tape,
                        maxLines = 3,
                    )
                }
            }
        }

        if (!armed) {
            PrimaryAction(label = "START MIC", enabled = true, onClick = onRequestArm)
        }
        PrimaryAction(
            label = if (grabbing) "GRABBING…" else "GRAB",
            enabled = armed && !grabbing,
            onClick = ::grab,
        )
    }
}

@Composable
private fun HeaderChip(
    label: String,
    scheme: com.snipsnap.shell.Scheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}

/**
 * The armed session's live-level bar + elapsed counter. Structurally the
 * same composable as `KitsScreen.kt`'s own private `RecordingIndicator` /
 * `LevelBar` / `formatElapsed` — replicated rather than hoisted, per the
 * house convention that file's own `BIN_RED_BORDER`/`BIN_RED_GLOW` comment
 * states explicitly ("duplicated, not hoisted... do it if a clean
 * one-liner, else duplicate with a comment"). Extracting three private
 * symbols out of `KitsScreen.kt` into a third shared file was worse than
 * a small, faithful duplicate here.
 */
@Composable
private fun CaptureLevelIndicator() {
    val scheme = LocalScheme.current
    val level by MicSessionService.level.collectAsState()

    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val anchor = MicSessionService.armedAtElapsedRealtime
            elapsedSeconds = if (anchor == 0L) {
                0
            } else {
                ((SystemClock.elapsedRealtime() - anchor) / 1000L).toInt().coerceAtLeast(0)
            }
            delay(1000)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .sunkenField(scheme)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CaptureLevelBar(level, scheme, Modifier.weight(1f).fillMaxHeight())
        TapeText(formatCaptureElapsed(elapsedSeconds), TapeType.pixelSmall, scheme.amber.tape)
    }
}

@Composable
private fun CaptureLevelBar(level: Float, scheme: com.snipsnap.shell.Scheme, modifier: Modifier = Modifier) {
    val filled = sqrt(level.coerceIn(0f, 1f))
    Canvas(modifier) {
        drawRect(color = scheme.field.tape, size = size)
        if (filled > 0f) {
            drawRect(color = scheme.amber.tape, size = Size(size.width * filled, size.height))
        }
    }
}

private fun formatCaptureElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

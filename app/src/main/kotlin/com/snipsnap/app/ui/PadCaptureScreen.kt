package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.MicSessionService
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.oilslickSweep
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.PadCapture
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.SchemeId
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** GRAB looks back this far into the ring for the last hit — PadCapture.grabOneShot trims it down from there. */
private const val GRAB_SECONDS = 2
private val GRAB_FRAMES get() = GRAB_SECONDS * MicSessionService.SAMPLE_RATE

/** Shorter than this ⇒ treat as an accidental tap, not a HOLD — toast a hint instead of committing a sliver. */
private const val MIN_HOLD_MS = 150L

/**
 * How long a screen-reader RECORD holds for, in milliseconds. A TalkBack
 * double-tap reports no duration, so the action has to supply one on a
 * coroutine — and it must clear [MIN_HOLD_MS] by a real margin, not sit
 * on it. Firing press and release back to back (which this action used to
 * do) always measured 0ms, so it landed in the accidental-tap branch
 * every single time: it toasted "HOLD TO RECORD" and committed nothing,
 * while still reporting success to the screen reader.
 *
 * Half a second is long enough to catch an actual hit rather than a
 * sliver, and short enough that the action still feels like a tap. A
 * screen-reader user cannot vary this the way a finger can — it is a
 * fixed, usable take, not a substitute for the sustained gesture.
 */
private const val HOLD_A11Y_MS = 500L

private fun padTag(slot: Int): String = "A%02d".format(slot)

/** Which gesture's commit is in flight, if any — drives the busy label on each control. */
private enum class Gesture { GRAB, HOLD }

// The open→assign→save sequence below is serialized through
// `KitWrites.mutex`, not a screen-local lock — PadSheetScreen, SynthScreen,
// and TakesBinScreen all write the same `kit.json` and race this screen's
// GRAB/HOLD commit exactly the way two GRABs would race each other. See
// `KitWrites`'s own KDoc for the full "one grab's audio lost with no toast,
// no crash" scenario a screen-local mutex here used to leave open. Only the
// mutation itself is behind the lock; the snapshot/DSP/classify work above
// it is read-only and stays concurrent.

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
    onRequestArm: () -> Unit,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
    appScope: CoroutineScope,
) {
    val scheme = LocalScheme.current
    var committing by remember { mutableStateOf<Gesture?>(null) }
    var holding by remember { mutableStateOf(false) }
    var holdStart by remember { mutableStateOf(0L) }

    // Mirrors the header's own ◄ KIT chip, which is unconditionally
    // enabled here (unlike PAD SHEET's — nothing on this screen debounces
    // a save to flush, so there's nothing extra for Back to await).
    BackHandler(onBack = onBack)

    // GRAB and HOLD are two producers of the same commit: classify → the
    // KitWrites-mutex-serialized open/assign/save → the same success/null/
    // error reporting. `committing` is the ONE guard both share, so a GRAB
    // press mid-HOLD-commit (or vice versa) is a no-op, not a second write
    // racing the first — see `KitWrites`'s own KDoc for what a second writer
    // would otherwise clobber. `producer` runs on the IO dispatcher, same as
    // the snapshot/DSP work it replaces. Which [Gesture] is stashed only
    // changes which control's busy label lights up — the guard/disable/
    // finally-clear semantics are identical to the old shared boolean.
    fun commitToPad(
        gesture: Gesture,
        successLabel: String,
        nothingLabel: String,
        failurePrefix: String,
        producer: suspend () -> Snip?,
    ) {
        if (!armed || committing != null) return
        committing = gesture
        appScope.launch {
            try {
                val updated: Kit? = withContext(Dispatchers.IO) {
                    val snip = producer() ?: return@withContext null
                    val cls = Classifier.classify(snip).drumClass
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(entry.dir)
                        model.assign(slot, snip, cls, cls.name.replace('_', ' '))
                        model.save()
                        model.kit
                    }
                }
                if (updated == null) {
                    // Distinguish the two null paths live, not off the
                    // composition's `armed` param — a session can eject
                    // mid-commit (EJECT from the notification, most likely),
                    // and this read is what keeps the toast honest about
                    // which of the two actually happened.
                    onToast(if (!MicSessionService.armed.value) "NOT LISTENING YET" else nothingLabel)
                } else {
                    onKitUpdated(updated)
                    onToast(successLabel)
                    onBack()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onToast("$failurePrefix: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                committing = null
            }
        }
    }

    fun grab() {
        commitToPad(
            gesture = Gesture.GRAB,
            successLabel = "GRABBED → PAD ${padTag(slot)}",
            nothingLabel = "NOTHING TO GRAB YET",
            failurePrefix = "GRAB FAILED",
        ) {
            val raw = MicSessionService.snapshotTail(GRAB_FRAMES) ?: return@commitToPad null
            PadCapture.grabOneShot(raw, MicSessionService.SAMPLE_RATE)
        }
    }

    fun startHold() {
        holdStart = SystemClock.elapsedRealtime()
        holding = true
    }

    fun endHold() {
        holding = false
        val heldMs = SystemClock.elapsedRealtime() - holdStart
        if (heldMs < MIN_HOLD_MS) {
            onToast("HOLD TO RECORD")
            return
        }
        // The ring was recording the whole time this control was held down —
        // no recorder starts or stops here, only a slice of it. `heldMs` →
        // frames, capped to PadCapture.MAX_HOLD_FRAMES so an absurdly long
        // hold still resolves to a bounded snapshot (the ring itself holds
        // MicSessionService.RING_SECONDS, comfortably more than the cap).
        val frames = (heldMs * MicSessionService.SAMPLE_RATE / 1000L)
            .toInt()
            .coerceIn(1, PadCapture.MAX_HOLD_FRAMES)
        commitToPad(
            gesture = Gesture.HOLD,
            successLabel = "RECORDED → PAD ${padTag(slot)}",
            nothingLabel = "NOTHING RECORDED",
            failurePrefix = "RECORD FAILED",
        ) {
            MicSessionService.snapshotTail(frames)?.let { PadCapture.holdClip(it, MicSessionService.SAMPLE_RATE) }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // heightIn, not a fixed height — Layout.LCD_HEADER_H (40dp) is
                // shorter than Layout.MIN_HIT_TARGET (44dp), and this header
                // (unlike KitsScreen's own, which holds no tappable chip)
                // holds the ◄ KIT back chip; a fixed parent height would clip
                // that chip's min hit target. Same fix PadSheetScreen's own
                // header already applies.
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
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
                    // States GRAB's actual window (GRAB_SECONDS, interpolated
                    // so this can't drift from the real cap) instead of the
                    // old "the sound that just happened" — which, paired
                    // with no stated window, read as a retroactive
                    // catch-all reaching further back than it does, and
                    // further back than the mic has even been listening (it
                    // can't reach before LISTEN was pressed at all).
                    TapeText(
                        "GRAB KEEPS THE LAST ${GRAB_SECONDS}s HEARD. HOLD RECORDS AS LONG AS YOU HOLD IT.",
                        TapeType.lcdSmall,
                        scheme.lcdInk.tape,
                        maxLines = 3,
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
            label = if (committing == Gesture.GRAB) "GRABBING…" else "GRAB ▸ LAST ${GRAB_SECONDS}s",
            enabled = armed && committing == null,
            onClick = ::grab,
        )
        HoldRecordAction(
            label = when {
                holding -> "RECORDING…"
                committing == Gesture.HOLD -> "PLACING…"
                else -> "HOLD TO REC"
            },
            enabled = armed && committing == null,
            onPress = ::startHold,
            onRelease = ::endHold,
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
            .tapeClick(label = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}

/**
 * HOLD's control — same visual family as `PrimaryAction` (52dp, rimmed, dark
 * fill; see `KitsScreen.kt`) but press/release semantics instead of a tap.
 * A `tapeClick` only fires once, on tap-up, with no way to observe how long
 * the tap was held — HOLD's whole gesture is the duration of the press, so
 * it needs the raw down/up events instead.
 */
@Composable
private fun HoldRecordAction(label: String, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    val rim =
        if (scheme.id == SchemeId.OILSLICK) {
            Modifier.border(2.dp, oilslickSweep(), RoundedCornerShape(6.dp))
        } else {
            Modifier.border(2.dp, scheme.amber.tape, RoundedCornerShape(6.dp))
        }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Layout.PRIMARY_ACTION_H.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(6.dp))
            .then(rim)
            // A screen-reader double-tap has no duration to report, so
            // this action supplies one: HOLD_A11Y_MS of real elapsed time
            // on a coroutine between press and release. Firing the two
            // back to back — which this did — always measured 0ms, which
            // is below MIN_HOLD_MS, so it took the accidental-tap branch
            // every time: it toasted a hint, committed nothing, and still
            // told the screen reader it had succeeded. An action that
            // announces and does nothing is worse than no action at all.
            // label (this button's own visible text) is the
            // merged accessible name.
            // mergeDescendants(true) on both branches: this Box sets no
            // contentDescription of its own, so the label TapeText below
            // is what supplies the accessible name — without the merge
            // flag it would instead stay a second, separately-focusable
            // node, leaving the action/disabled node nameless.
            .then(
                if (enabled) {
                    Modifier.semantics(mergeDescendants = true) {
                        onClick(label = "RECORD") {
                            scope.launch {
                                onPress()
                                delay(HOLD_A11Y_MS)
                                onRelease()
                            }
                            true
                        }
                    }
                } else {
                    Modifier.semantics(mergeDescendants = true) { disabled() }
                },
            )
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            onPress()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                change.consume()
                            }
                            onRelease()
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(
            label,
            TapeType.displayBig,
            if (enabled) scheme.lcdInk.tape else scheme.lcdInk.tape.copy(alpha = 0.5f),
        )
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
 *
 * Self-collects `MicSessionService.level`, same as `RecordingIndicator`
 * does — the smallest composable scope that should recompose at ~21 Hz.
 * `App.kt` collects `armed` (which changes rarely) for `PadCaptureScreen`'s
 * signature, but does NOT collect `level`: doing so up there would make the
 * 21Hz tick recompose the whole surface (header + GRAB button included)
 * instead of just this leaf.
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
    Canvas(
        // Static label, not a live stateDescription — see KitsScreen.kt's
        // own LevelBar, this composable's duplication source, for why
        // (level updates at ~21Hz; the adjacent elapsed-time text is the
        // slow-changing, screen-reader-legible proof of a live signal).
        modifier.semantics { contentDescription = "MIC LEVEL METER" },
    ) {
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

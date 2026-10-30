package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Busy
import com.snipsnap.shell.BusyLatch
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Motion
import kotlinx.coroutines.delay

/*
 * How a job in flight is drawn: a bar for a job the dock is carrying, a
 * spinner for the control that started one, and a panel or a card for a
 * place where a result will land. All of them are drawn here, with
 * `foundation` and a canvas, because TapeOS screens carry no Material3
 * composable - and all of them say what they are to TalkBack through
 * `progressSemantics`, so a person who cannot see the motion is still told
 * that something is working and, when it can be counted, how far along it
 * is.
 *
 * Every one of them stands still when `LocalReduceMotion` is on. They draw
 * without it, because a bar that vanished when animation is off would hide
 * the one fact it exists to show; the animated form is a composable of its
 * own, so the still branch composes no infinite transition at all and has
 * no frame loop for anything to wait on.
 */

/** How tall the busy bar is unless its caller says: thin enough to sit on a dock line without a row of its own. */
private val BAR_HEIGHT = 4.dp

/** How tall the bar on the loading card is: it is the card's centrepiece, not a hairline on an edge. */
private val CARD_BAR_HEIGHT = 6.dp

/** How wide the loading card is, as a share of the place it is laid over. */
private const val CARD_WIDTH_SHARE = 0.86f

/** The loading card's padding, all round. */
private val CARD_PADDING = 24.dp

/** The loading card's spinner: bold enough to be the first thing seen on the screen. */
private val CARD_SPINNER = 40.dp

/** How much of its colour the track behind a counted bar's fill keeps. */
private const val TRACK_ALPHA = 0.20f

/** The share of the track the indeterminate bar's moving segment covers. */
private const val SWEEP_SHARE = 0.30f

/**
 * The share of the track the indeterminate bar's solid segment covers when it
 * cannot move: centred, so it reads as a bar with something on it and not as a
 * divider line.
 */
private const val STILL_SHARE = 0.40f

/** The spinner's arc, in degrees: three quarters of a circle, so it reads as turning. */
private const val SPINNER_ARC = 270f

/** How thick the spinner's stroke is, as a share of its diameter: about a tenth, so it grows with the ring. */
private const val SPINNER_STROKE_SHARE = 0.10f

/** The thinnest the spinner's stroke gets, so the 14 dp ring on a button keeps the 2 dp line it always had. */
private val SPINNER_MIN_STROKE = 2.dp

/**
 * A job's progress as a thin bar the width of whatever holds it.
 *
 *  - **Counted** ([Busy.fraction] not null): a filled rect that long over
 *    a faint full-width track ([TRACK_ALPHA] of [color]), with the fraction
 *    in its semantics. The track is why a job at 0 of N is still a bar -
 *    without it the bar would draw nothing until the first item finished.
 *    Reduced motion does not change it; a count is not an animation.
 *  - **Not counted:** a [SWEEP_SHARE] segment sweeping left to right over
 *    [Motion.BUSY_SWEEP_MS], linear, repeating - "still working", with no
 *    claim about how much is left. With animations off it is the same
 *    track (at [TRACK_ALPHA]) with a solid segment, [STILL_SHARE] of the
 *    width, centred in it: a bar with something on it, which says the same
 *    thing without moving. (The first still form was the track alone,
 *    dimmed, and on the dock's edge that read as a divider.)
 *
 * Drawn in [color], which the caller picks to read on its own surface (an
 * LCD's amber, a chrome ink), [height] tall - the dock's thin line unless
 * the caller wants more. It owns no label: the words belong to whatever it
 * sits under.
 */
@Composable
fun BusyBar(busy: Busy, color: Color, modifier: Modifier = Modifier, height: Dp = BAR_HEIGHT) {
    val bar = modifier.fillMaxWidth().height(height)
    val fraction = busy.fraction
    when {
        fraction != null -> Box(
            bar
                .progressSemantics(fraction)
                .drawBehind {
                    drawRect(color.copy(alpha = TRACK_ALPHA))
                    drawRect(color, size = Size(size.width * fraction, size.height))
                },
        )
        LocalReduceMotion.current -> Box(
            bar
                .progressSemantics()
                .drawBehind { drawStill(color) },
        )
        else -> SweepingBar(color, bar)
    }
}

/** The indeterminate bar's moving form; composed only when motion is allowed. */
@Composable
private fun SweepingBar(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "busySweep")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.BUSY_SWEEP_MS, easing = LinearEasing)),
        label = "busySweepPhase",
    )
    SweepBar(color, modifier) { phase.value }
}

/**
 * The sweep at the [phase] it is handed, 0..1. Read inside the draw lambda, so
 * a frame redraws the bar and recomposes nothing. A separate function from the
 * transition that feeds it so the device test can pin the phase where the
 * system pins it when it skips an infinite animation to its end.
 */
@Composable
internal fun SweepBar(color: Color, modifier: Modifier, phase: () -> Float) {
    Box(
        modifier
            .progressSemantics()
            .drawBehind { drawSweep(color, phase()) },
    )
}

/**
 * The bar that cannot move: the whole track at [TRACK_ALPHA], and over it a
 * solid segment [STILL_SHARE] of the width, centred.
 */
private fun DrawScope.drawStill(color: Color) {
    drawRect(color.copy(alpha = TRACK_ALPHA))
    val segment = size.width * STILL_SHARE
    drawRect(color, topLeft = Offset((size.width - segment) / 2f, 0f), size = Size(segment, size.height))
}

/**
 * The segment at [phase] 0..1: it enters past the left edge, crosses, and
 * leaves past the right, so it is fully off the track at both ends of the
 * loop and the repeat has no jump in it. What lies outside the track is
 * not drawn.
 *
 * A segment with no width at any phase past the start is not a sweep but a
 * pinned animation: when the system's animator scale is 0 Compose skips an
 * infinite transition to its end, the phase sits at 1, and the segment is off
 * the right edge for good. That draws [drawStill] rather than nothing, so a
 * bar the app has not yet been told to hold still (the setting is changed
 * while the app is up; see `rememberReduceMotion`) never vanishes. Phase 0
 * is exempt: it is the first frame of every loop, the segment about to
 * enter, and flashing the still bar for it would flash a centred segment each
 * time a bar appears.
 */
private fun DrawScope.drawSweep(color: Color, phase: Float) {
    val segment = size.width * SWEEP_SHARE
    val left = ((size.width + segment) * phase - segment).coerceAtLeast(0f)
    val right = ((size.width + segment) * phase).coerceAtMost(size.width)
    if (right > left) {
        drawRect(color, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
    } else if (phase > 0f) {
        drawStill(color)
    }
}

/**
 * A small ring with a gap, turning once every [Motion.SPINNER_MS]: on the
 * control that started a job, so the thing a person just pressed is the
 * thing that visibly answers. Still - the same arc, held - when animations
 * are off. [size] is its diameter, and its stroke grows with it:
 * [SPINNER_STROKE_SHARE] of the diameter, never under [SPINNER_MIN_STROKE],
 * so the 14 dp ring on a button keeps its 2 dp line and the 40 dp one on the
 * loading card is a bold 4 dp.
 */
@Composable
fun Spinner(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val ring = modifier.size(size).progressSemantics()
    if (LocalReduceMotion.current) {
        Box(ring.drawBehind { drawSpinner(color, 0f) })
    } else {
        TurningSpinner(color, ring)
    }
}

/** The spinner's moving form; composed only when motion is allowed. */
@Composable
private fun TurningSpinner(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val degrees = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(Motion.SPINNER_MS, easing = LinearEasing)),
        label = "spinnerDegrees",
    )
    Box(modifier.drawBehind { drawSpinner(color, degrees.value) })
}

/** The [SPINNER_ARC] ring from [startDegrees], clockwise from three o'clock, inset by half its stroke. */
private fun DrawScope.drawSpinner(color: Color, startDegrees: Float) {
    val stroke = maxOf(size.minDimension * SPINNER_STROKE_SHARE, SPINNER_MIN_STROKE.toPx())
    drawArc(
        color = color,
        startAngle = startDegrees,
        sweepAngle = SPINNER_ARC,
        useCenter = false,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(width = stroke),
    )
}

/**
 * A lit panel with [label] centred in it and a bar with no end under it:
 * what stands where a screen's content will be while it is read from disk,
 * so the place is never just empty. To TalkBack the panel is one node named
 * [label]; the bar under it stays a node of its own, because
 * `progressSemantics` is a merge boundary, and is the one that says "in
 * progress".
 */
@Composable
fun LoadingPanel(label: String, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    Column(
        modifier
            .semantics(mergeDescendants = true) { contentDescription = label }
            .lcdPanel(scheme)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TapeText(label, TapeType.lcdSmall, scheme.lcdInk.tape)
        Spacer(Modifier.height(10.dp))
        BusyBar(Busy(label), scheme.amber.tape)
    }
}

/**
 * The card that says a place's content is on its way: a lit panel centred
 * over where the content will land, with a bold [Spinner], [busy]'s label in
 * the display face, a [BusyBar] under it and, when the job can be counted,
 * its count under that. It is what a person looking at the screen sees first,
 * where [LoadingPanel] is a quiet line and the dock's bar a hairline on the
 * far edge.
 *
 * The caller places it (`Modifier.align(Alignment.Center)` in a `Box` over the
 * placeholders) and owns when it appears: a card on a job that has not run
 * [BusyLatch.SHOW_AFTER_MS] yet would flash, so `KitLoading` and the shelf
 * hold it behind [rememberBusyVisible].
 *
 * To TalkBack it is one node, named [Busy.label] and in progress - counted
 * when [Busy.fraction] is not null, with no end in sight when it is. The
 * ring and the bar under it are drawing, not two more nodes, so they are
 * cleared of their own semantics here. Both stand still under reduced motion.
 */
@Composable
fun LoadingCard(busy: Busy, modifier: Modifier = Modifier) {
    val scheme = LocalScheme.current
    val fraction = busy.fraction
    val total = busy.total
    Column(
        modifier
            .fillMaxWidth(CARD_WIDTH_SHARE)
            // One modifier chain on one layout node: the description and the
            // progress collapse into the single node a test (and TalkBack) asks.
            .semantics(mergeDescendants = true) { contentDescription = busy.label }
            .then(if (fraction != null) Modifier.progressSemantics(fraction) else Modifier.progressSemantics())
            .lcdPanel(scheme)
            .padding(CARD_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.clearAndSetSemantics {}) { Spinner(scheme.amber.tape, size = CARD_SPINNER) }
        Spacer(Modifier.height(16.dp))
        TapeText(
            busy.label,
            TapeType.display.copy(textAlign = TextAlign.Center),
            scheme.lcdInk.tape,
            Modifier.fillMaxWidth(),
            maxLines = 2,
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            BusyBar(busy, scheme.amber.tape, height = CARD_BAR_HEIGHT)
        }
        if (total != null) {
            Spacer(Modifier.height(8.dp))
            TapeText(Copy.busyCount(busy.done, total), TapeType.lcdSmall, scheme.lcdInk.tape)
        }
    }
}

/**
 * Whether the bar for [busy] should be on screen now: false for its first
 * [BusyLatch.SHOW_AFTER_MS], so a job that finishes at once draws nothing,
 * and true for at least [BusyLatch.MIN_VISIBLE_MS] once it has shown, so a
 * bar that appeared can be read. The rules are [BusyLatch]'s; this is the
 * clock under them, [SystemClock.uptimeMillis], and a coroutine that sleeps
 * until the latch says it will next answer differently.
 *
 * Whether a job exists is what drives it, not which [Busy] it is: a
 * progress update is a new value for the same job and leaves the latch
 * alone.
 *
 * The minimum is owed to a bar that went up: one this has returned true for
 * by the time its job ended. A job that ends after the delay but before that
 * (the delay's wake-up had not yet run) shows none: the latch forgets the run
 * ([BusyLatch.discard]).
 *
 * It counts from the first frame that draws the bar, not from the moment the
 * latch schedules it. This turns true at the schedule, but the bar needs a
 * frame to be drawn in, and a cold start's first frames can come about 160 ms
 * late: counted from the schedule, the bar would then be on screen for less
 * than [BusyLatch.MIN_VISIBLE_MS]. So when this turns true it waits for the
 * next frame, the one that composes the bar, reads the clock at its start and
 * hands that to [BusyLatch.markShown].
 */
@Composable
fun rememberBusyVisible(busy: Busy?): Boolean = rememberBusyVisible(busy, SystemClock::uptimeMillis)

/**
 * [rememberBusyVisible] on a clock the caller supplies, in ms and
 * monotonic. The device test hands it the compose test clock: the effect's
 * `delay` runs on that clock's virtual time, and [SystemClock] would not
 * move with it.
 */
@Composable
internal fun rememberBusyVisible(busy: Busy?, now: () -> Long): Boolean {
    val latch = remember { BusyLatch() }
    var visible by remember { mutableStateOf(false) }
    val clock by rememberUpdatedState(now)
    val running = busy != null
    LaunchedEffect(running) {
        when {
            running -> latch.start(clock())
            // The bar is up: it stays its minimum.
            visible -> latch.stop(clock())
            // It never was: `visible` has not turned true. The delay's wake-up
            // and the job's end are two moments, and a job can end between them:
            // the latch alone would say a bar is owed for a job that is over,
            // and showing it then would put a loading screen over the result it
            // was loading (the device showed exactly that for a starter that took
            // about 250 ms). A bar that was not up has no minimum to serve.
            else -> latch.discard()
        }
        var told = false
        while (true) {
            val t = clock()
            val shown = latch.visible(t)
            visible = shown
            if (shown && !told) {
                // The bar is due, so the next frame composes it and draws it:
                // the minimum counts from that frame (the clock read in it), and
                // not from the schedule the frame may have missed. Every effect
                // asks for its own frame and does not rely on an earlier one's:
                // a job that ends while a frame is awaited replaces this effect
                // and cancels the wait, and the next one finds the bar up and
                // asks again. The latch keeps the first mark of a run and ignores
                // the rest, so asking again costs a frame's wait and nothing else.
                latch.markShown(withFrameNanos { clock() })
                told = true
                continue
            }
            val next = latch.nextChangeAt(t) ?: break
            delay(next - t)
        }
    }
    return visible
}

/** The job a control's working ring stands for: held once so the key under [rememberRingUp] is one object. */
private val RING_JOB = Busy(Copy.WORKING_BUSY)

/**
 * Whether the ring on the control that started a job should be on screen
 * now: false for the first [BusyLatch.SHOW_AFTER_MS] after [working] turns
 * true, so a job that is over by then draws nothing, and once it has shown,
 * true for at least [BusyLatch.MIN_VISIBLE_MS] even if the job ends sooner.
 * The same rule as the dock's bar and the loading card, on the same latch.
 * [ActionButton] and [PrimaryAction] both ask here, and draw the ring while
 * it answers true. They say [Copy.WORKING_BUSY] as their state only while it
 * answers true and the job is still running, so a ring held out its minimum
 * after the job ended is seen and not announced.
 *
 * [now] is [rememberBusyVisible]'s clock: a device test hands it the compose
 * test clock.
 */
@Composable
internal fun rememberRingUp(working: Boolean, now: () -> Long = SystemClock::uptimeMillis): Boolean =
    rememberBusyVisible(if (working) RING_JOB else null, now)

/**
 * Where a screen's own job goes to reach the dock. The app provides the
 * sink at its root and the dock draws what it is handed; the default drops
 * every report, so a screen shown on its own, in a test or a preview, has
 * nowhere to send one and loses nothing by trying.
 */
val LocalBusySink = staticCompositionLocalOf<(Busy?) -> Unit> { {} }

/**
 * Tells the dock about [busy], a job this screen is carrying, for as long as
 * it is given and as long as the screen is composed.
 *
 * Use this only for work that dies with the screen. The report is withdrawn
 * (the sink is handed null) the moment [busy] changes and the moment this
 * leaves the composition, so a screen that is navigated away from mid-job
 * cannot leave its bar on the dock. That is wrong for work that outlives its
 * screen (the pad editor's treatments run on `appScope` on purpose, so the
 * write survives a tab switch): its bar would go the moment the screen did,
 * with the write still running. Begin that work on the app's job board,
 * found at [LocalJobs], and not here; the board keeps it on the dock until it
 * ends. A changed [busy] (the count moving on) is a withdrawal followed at
 * once by the new report, so the sink's last word is always the job as it now
 * stands.
 *
 * The sink is read as of the latest composition, so the withdrawal always
 * reaches the sink the app has now.
 *
 * [busy] is the effect's key, so a caller that passes a [Busy] carrying a
 * `cancel` lambda must `remember` it: a fresh lambda on every recomposition
 * is a changed key, and churns the dock with a withdrawal and a report each
 * time.
 */
@Composable
fun ReportBusy(busy: Busy?) {
    val sink by rememberUpdatedState(LocalBusySink.current)
    DisposableEffect(busy) {
        sink(busy)
        onDispose { sink(null) }
    }
}

/**
 * What a screen draws, and tells the dock, while it reads a recording from
 * disk: TAPE and CHOP, which have nothing of their own to show until the
 * decode is done. Returns true when the screen's place is held by this - drawn
 * here, so the caller returns - and false when the screen should draw what it
 * has (its deck, its chop, or the empty face for a read that found nothing).
 *
 * While [decoding] the dock is told [Copy.READING_RECORDING] (one
 * [ReportBusy] per screen; the [Busy] is remembered under [decoding], so the
 * sink sees a job start and end and not a new object on every recomposition).
 * The screen's place says it too, by the same rule as the dock's bar though
 * not the same latch (the dock keeps its own): a quiet lit panel for the
 * first [BusyLatch.SHOW_AFTER_MS], so a read that ends at once draws no words
 * at all, then a [LoadingPanel], and the words stay at least
 * [BusyLatch.MIN_VISIBLE_MS] once they have shown. That last part is why this
 * holds after [decoding] ends, ahead of the empty face and the content: a read
 * that ends a moment after the words appear would otherwise leave them a
 * flash. [LoadingPanel] does not keep the rule itself - it is a bare column -
 * so the screen asks for it here, once, above its early return.
 *
 * [clock] is the latch's, in ms and monotonic: a device test hands it the
 * compose test clock, for the reason [rememberBusyVisible]'s own overload has
 * one.
 */
@Composable
fun holdWhileReading(decoding: Boolean, clock: () -> Long = SystemClock::uptimeMillis): Boolean {
    val reading = remember(decoding) { if (decoding) Busy(Copy.READING_RECORDING) else null }
    ReportBusy(reading)
    val panelShown = rememberBusyVisible(reading, clock)
    if (!decoding && !panelShown) return false
    if (panelShown) {
        LoadingPanel(Copy.READING_RECORDING, Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().lcdPanel(LocalScheme.current))
    }
    return true
}

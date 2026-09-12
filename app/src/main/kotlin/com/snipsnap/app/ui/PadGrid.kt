package com.snipsnap.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.Schemes

/**
 * PLAY's pad-grid surface — `PlayPad`/`PlayBank`/`BankRow` and their
 * supporting constants — extracted out of `PlayScreen.kt` so both PLAY and
 * GROOVE can render the same pad grid rather than each screen carrying its
 * own copy. This is the extraction `PlayScreen.kt`'s own KDoc already
 * flagged as owed (it didn't reuse `KitScreen`'s private `PadCell` because
 * that didn't extract cleanly without exporting it). Nothing in this file
 * references anything PLAY-specific — no `KitShelf.Entry`, no `PadEngine` —
 * it only takes a [Kit] and hit/release callbacks, so any screen with its
 * own engine and allocator can render from here.
 *
 * `onHit` carries a third argument, `uptimeMillis` — the touch's own
 * hardware timestamp (`PointerInputChange.uptimeMillis`, `SystemClock
 * .uptimeMillis()`-based, same `CLOCK_MONOTONIC` timebase Compose's frame
 * clock uses — verified for the live-record plan, not assumed). PLAY
 * ignores it; GROOVE's live-record path needs it to place a recorded note
 * at the position it actually sounded, not the position a re-read of the
 * clock some microseconds after dispatch would imply. One callback shape,
 * not a second optional one — see `ConventionTest`'s own KDoc for why a
 * shape that can diverge between call sites is the bug pattern this
 * codebase specifically tests against.
 */
internal val WINDOW_GRID_ROWS = listOf(13..16, 9..12, 5..8, 1..4)
internal val BANK_A_ROWS = listOf(9..16, 1..8)
internal val BANK_B_ROWS = listOf(25..32, 17..24)

/** Touch-Y velocity: top of the pad is softest, bottom is full velocity. */
private const val MIN_VELOCITY = 0.35f

internal fun velocityFromY(y: Float, height: Float): Float {
    val t = (y / height).coerceIn(0f, 1f)
    return MIN_VELOCITY + (1f - MIN_VELOCITY) * t
}

/**
 * The velocity a synthesized TalkBack click uses: `velocityFromY` needs a
 * real touch Y, which a semantics `onClick` action doesn't have (audit
 * finding 1's own framing) — this is what a tap at the pad's vertical
 * center would have produced, i.e. neither the softest nor the hardest
 * hit available to a sighted finger.
 */
private val CENTER_VELOCITY = velocityFromY(0.5f, 1f)

/**
 * Bank-aware pad tag ("A01".."A16", "B01".."B16"), over [PadBanks].
 *
 * This comment used to explain that four other screens declared their own
 * single-bank `padTag(slot) = "A%02d".format(slot)` for "a different
 * (single-bank) context", and that reconciling them was outside that
 * task's scope. The September UAT's finding 11 ended the single-bank
 * context: bank B pads are editable now, so those four were about to
 * start titling slot 17 "A17". They all read the one rule.
 */
private fun padTag(slot: Int): String = PadBanks.tag(slot)

/** HANDOFF: tint for text on dark = the class colour mixed 55% to white. */
private fun classTint(rgb: Int): Color {
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    fun up(c: Int) = c + ((255 - c) * 0.55f).toInt()
    return Color(0xFF shl 24 or (up(r) shl 16) or (up(g) shl 8) or up(b))
}

/**
 * Both banks, side by side. `Layout.MIN_HIT_TARGET` is non-negotiable
 * (Lessons), so this measures the available width first: wide enough for
 * both banks at the floor width, share it with `weight` so pads grow to
 * fill a roomy landscape screen; too narrow (a compact phone's landscape
 * width, minus insets, can land under 8 pads' worth of 44dp), fall back to
 * fixed floor-width banks inside a horizontal scroll — `weight` and
 * `horizontalScroll` can't be combined in the same axis, hence the split.
 */
@Composable
internal fun BankRow(
    kit: Kit,
    glow: Map<Int, Animatable<Float, AnimationVector1D>>,
    onHit: (Int, Float, Long) -> Unit,
    onRelease: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
    emptyHits: Boolean = false,
) {
    val bankFloor = (Layout.MIN_HIT_TARGET * 8 + Layout.PAD_GAP * 7).dp
    BoxWithConstraints(modifier) {
        if (maxWidth >= bankFloor * 2 + Layout.PAD_GAP.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                PlayBank(kit, BANK_A_ROWS, glow, onHit, onRelease, Modifier.weight(1f).fillMaxHeight(), emptyHits)
                PlayBank(kit, BANK_B_ROWS, glow, onHit, onRelease, Modifier.weight(1f).fillMaxHeight(), emptyHits)
            }
        } else {
            Row(
                Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
            ) {
                PlayBank(kit, BANK_A_ROWS, glow, onHit, onRelease, Modifier.width(bankFloor).fillMaxHeight(), emptyHits)
                PlayBank(kit, BANK_B_ROWS, glow, onHit, onRelease, Modifier.width(bankFloor).fillMaxHeight(), emptyHits)
            }
        }
    }
}

/** One bank's 8×2 block — [rows] is top-row-first, matching KIT's own bottom-up numbering. */
@Composable
internal fun PlayBank(
    kit: Kit,
    rows: List<IntRange>,
    glow: Map<Int, Animatable<Float, AnimationVector1D>>,
    onHit: (Int, Float, Long) -> Unit,
    onRelease: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
    emptyHits: Boolean = false,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
    ) {
        for (row in rows) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp),
            ) {
                for (slot in row) {
                    PlayPad(
                        slot = slot,
                        pad = kit.pad(slot),
                        glow = glow[slot],
                        onHit = onHit,
                        onRelease = onRelease,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        emptyHits = emptyHits,
                    )
                }
            }
        }
    }
}

/**
 * The pad's press-and-release, shared by an assigned cell and (for CATCH A
 * HIT) an empty one. The accessibility floor is a single click action —
 * fire the hit at CENTER_VELOCITY, then release immediately. A gated
 * (non-one-shot) pad won't sustain the way a real press-and-hold would,
 * but this is strictly better than the false affordance a focusable,
 * silently-inert cell was before (audit finding 1). mergeDescendants:
 * without it, the tag and name TapeTexts stay separate focus stops from
 * the cell's own. A synthesized TalkBack click has no MotionEvent, hence
 * no hardware touch time (same reasoning as CENTER_VELOCITY's own KDoc) —
 * "now" is the only timestamp available, for the release as for the hit.
 * A real release carries the up event's own `uptimeMillis`.
 */
private fun Modifier.padGesture(
    slot: Int,
    description: String,
    onHit: (Int, Float, Long) -> Unit,
    onRelease: (Int, Long) -> Unit,
): Modifier = this
    .semantics(mergeDescendants = true) {
        contentDescription = description
        onClick(label = "PLAY") {
            val now = SystemClock.uptimeMillis()
            onHit(slot, CENTER_VELOCITY, now)
            onRelease(slot, now)
            true
        }
    }
    .pointerInput(slot) {
        while (true) {
            val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
            val height = size.height.toFloat().coerceAtLeast(1f)
            onHit(slot, velocityFromY(down.position.y, height), down.uptimeMillis)
            var up = down.uptimeMillis
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    up = change.uptimeMillis
                    if (!change.pressed) break
                }
            }
            onRelease(slot, up)
        }
    }

/**
 * PLAY's own pad cell — mirrors KitScreen's `PadCell` (same tag/name
 * layout, same class-colour source) but implements the handoff's actual
 * two pad states instead of KIT's single hit-flash: *assigned* (a resting
 * outer wash) and *lit* (a stronger inset wash, scaled down 3%). Compose
 * has no free-standing blurred box-shadow to match the handoff's literal
 * "14px"/"26px" glow radii; both states are flat alpha fills over the pad
 * itself, same approximation KitScreen's own cell already uses for its
 * hit flash. No long-press here — PLAY is for playing, not the pad
 * inspector — and release fires `onRelease` so gate (non-one-shot) pads
 * actually stop.
 */
@Composable
internal fun PlayPad(
    slot: Int,
    pad: KitPad?,
    glow: Animatable<Float, AnimationVector1D>?,
    onHit: (Int, Float, Long) -> Unit,
    /** Finger up: the slot and the up event's own `uptimeMillis` (CATCH places the lift on the tape by it; PLAY ignores it). */
    onRelease: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * CATCH A HIT (docs/CATCH.md): an empty slot takes the same press and
     * release an assigned one does, so a hit can be caught onto it. Off
     * (every other screen), an empty slot is inert — PLAY has nothing to
     * play there and GROOVE nothing to record.
     */
    emptyHits: Boolean = false,
) {
    val scheme = LocalScheme.current
    val shape = RoundedCornerShape(Layout.PAD_RADIUS.dp)
    val tag = padTag(slot)

    if (pad == null || glow == null) {
        Box(
            modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .raisedBevel(scheme, Layout.PAD_RADIUS.dp)
                .then(if (emptyHits) Modifier.padGesture(slot, "EMPTY PAD $tag", onHit, onRelease) else Modifier),
            contentAlignment = Alignment.TopEnd,
        ) {
            // Full-opacity ink2, not a dimmed copy — the alpha reduction
            // (was 0.6f) put this, the only text naming which of 32 slots
            // an empty pad is, at 2.09-2.99:1 in every scheme (audit
            // finding 5). ink2 itself now clears 4.5:1 against gray in all
            // 8 schemes at full opacity — see Schemes.kt and ContrastTest.
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape, Modifier.padding(4.dp))
        }
        return
    }

    val cls = pad.colorHex?.removePrefix("#")?.toIntOrNull(16) ?: Schemes.classColor(pad.drumClass)
    val g = glow.value

    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .scale(1f - 0.03f * g)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            // assigned: resting outer wash (~class@60%, flattened — see KDoc above)
            .background(cls.tape.copy(alpha = 0.12f), shape)
            // lit: inset wash at class@40%, driven by the trigger/choke glow
            .background(cls.tape.copy(alpha = 0.40f * g), shape)
            .border(2.dp, cls.tape, shape)
            // No long-press here (see this composable's own KDoc — PLAY
            // is for playing): the press-and-release gesture and its
            // accessibility floor live in `padGesture`, shared with the
            // empty slot CATCH can land on.
            .padGesture(slot, "PAD $tag: ${pad.displayName}", onHit, onRelease)
            .padding(5.dp),
    ) {
        // Full-opacity ink2 on its own solid backing chip, not a dimmed
        // copy drawn straight over the cell (audit finding 5). The chip
        // matters here specifically: this cell carries a persistent
        // class-colour wash (`cls.tape.copy(alpha = 0.12f)`, above) under
        // the tag at all times, not just while lit — so without an opaque
        // backdrop the tag's real background is class-colour-dependent
        // and can fall to ~3.9:1 for a bright class like HAT_OPEN even
        // with ink2 itself fixed — see ContrastTest.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .background(Schemes.darken(scheme.gray, 0.30f).tape, RoundedCornerShape(3.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape)
        }
        TapeText(
            pad.displayName,
            TapeType.marker,
            classTint(cls),
            Modifier.align(Alignment.BottomStart),
            maxLines = 2,
        )
    }
}

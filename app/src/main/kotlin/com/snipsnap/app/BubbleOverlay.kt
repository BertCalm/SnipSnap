package com.snipsnap.app

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.CaptureRing
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Schemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Bubble ring diameter. Well clear of [Layout.MIN_HIT_TARGET]. */
private const val BUBBLE_DIAMETER_DP = 56
private const val BUBBLE_STROKE_DP = 5

/**
 * The overlay window itself is drawn larger than the visible ring so
 * [Motion.BUBBLE_DRAG_SCALE]'s 1.08x grow during a drag has room —
 * without this the scaled circle clips against the window's own bounds
 * (the window doesn't resize mid-drag, only its content scales).
 */
private const val BUBBLE_WINDOW_DP = 64

/**
 * The floating bubble: a small WindowManager-attached ComposeView riding
 * over whatever app is playing while a [MicSessionService] session is
 * armed and [android.provider.Settings.canDrawOverlays] is granted.
 *
 * Attached and detached in lockstep with the session — [MicSessionService]
 * owns both calls (`attachIfAllowed` from `handleArm`, `detach` from
 * `stopReaderAndRecord`, its one teardown funnel). Entirely
 * device-gated: WindowManager overlay windows, real drag, and the
 * permission's Settings round-trip cannot be exercised without a device,
 * so this file has no test — the compile gate is the only proof available
 * here (see the Task 5 report).
 *
 * `SYSTEM_ALERT_WINDOW` is optional by design: when it isn't granted,
 * [attachIfAllowed] is a silent no-op and the notification's own SNIP
 * action remains the whole story.
 */
object BubbleOverlay {

    private var hostView: ComposeView? = null
    private var bubbleOwner: BubbleLifecycleOwner? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var overlayScope: CoroutineScope? = null

    /** Read by the ring-fill tick, written into the composition below. */
    private val fillFraction = mutableFloatStateOf(0f)

    /**
     * Attaches the bubble if the overlay permission is granted; a no-op
     * if it isn't, or if a bubble is already attached. Safe to call
     * unconditionally — [MicSessionService.handleArm]'s re-entry path
     * (session already armed, called again so a just-granted overlay
     * permission can attach the bubble without restarting the
     * `AudioRecord`) relies on that.
     */
    fun attachIfAllowed(service: Service, ringProvider: () -> CaptureRing?) {
        if (hostView != null) return
        if (!Settings.canDrawOverlays(service)) return

        val appContext = service.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = ComposeView(appContext)
        val owner = BubbleLifecycleOwner()
        owner.onCreate()
        // ComposeView is final — the owner triad lives on a standalone
        // object instead of a subclass, wired onto the plain view via
        // these View extension functions. Order matters: all three run
        // before WindowManager.addView below.
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)

        val lp = WindowManager.LayoutParams(
            BUBBLE_WINDOW_DP.dpToPx(appContext),
            BUBBLE_WINDOW_DP.dpToPx(appContext),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = INITIAL_X_DP.dpToPx(appContext)
            y = INITIAL_Y_DP.dpToPx(appContext)
        }

        // handleArm (this call's only caller) runs from the ARM button in
        // a resumed Activity — SnipSnap itself is foreground at the exact
        // moment this view is born. Setting the starting visibility here,
        // not waiting for the appForeground collector's first emission
        // below, is what keeps the bubble from flashing over the shelf
        // before that collector catches up.
        view.visibility = if (SnipSnapApplication.appForeground.value) View.GONE else View.VISIBLE

        view.setContent {
            var scale by remember { mutableFloatStateOf(1f) }
            BubbleContent(
                fraction = fillFraction.floatValue,
                scale = scale,
                onTap = {
                    MicSessionService.snip(appContext)
                    Toast.makeText(appContext, Copy.SNIPPED, Toast.LENGTH_SHORT).show()
                },
                onDragStart = { scale = Motion.BUBBLE_DRAG_SCALE },
                onDragEnd = {
                    scale = 1f
                    if (isInHotZone(appContext)) {
                        MicSessionService.eject(appContext)
                        Toast.makeText(appContext, Copy.BUBBLE_EJECTED, Toast.LENGTH_SHORT).show()
                    }
                },
                onDrag = { dx, dy -> moveBy(view, dx, dy) },
            )
        }

        wm.addView(view, lp)
        hostView = view
        bubbleOwner = owner
        windowManager = wm
        params = lp

        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        overlayScope = scope
        // Ring fill — the artboard's "24s ON TAPE" reads in whole
        // seconds, so a 1s tick is plenty; nothing here touches the ring
        // itself beyond the two read-only Int properties CaptureRing
        // already exposes for exactly this.
        scope.launch {
            while (isActive) {
                val ring = ringProvider()
                fillFraction.floatValue = if (ring != null && ring.capacityFrames > 0) {
                    ring.filledFrames.toFloat() / ring.capacityFrames
                } else {
                    0f
                }
                delay(RING_TICK_MS)
            }
        }
        // Foreground-hide: SnipSnap's own in-app controls own the surface
        // whenever any of this app's Activities is started; toggling
        // visibility (not add/remove) keeps the dragged-to position.
        scope.launch {
            SnipSnapApplication.appForeground.collect { foreground ->
                view.visibility = if (foreground) View.GONE else View.VISIBLE
            }
        }
    }

    /** Detaches the bubble. Safe to call when nothing is attached. */
    fun detach() {
        overlayScope?.cancel()
        overlayScope = null

        val wm = windowManager
        val view = hostView
        if (wm != null && view != null) {
            // removeView throws if the view was already detached out from
            // under us (window token death, etc.) — this is teardown, not
            // the realtime path, so that's caught and swallowed, not
            // logged per-frame like the audio loops.
            runCatching { wm.removeView(view) }
        }
        bubbleOwner?.onDestroy()

        hostView = null
        bubbleOwner = null
        windowManager = null
        params = null
        dragRemainderX = 0f
        dragRemainderY = 0f
    }

    // Sub-pixel remainder from moveBy's float->Int truncation each frame;
    // without it, small per-frame deltas (a slow drag) floor to 0 and the
    // bubble stalls under the finger instead of tracking it.
    private var dragRemainderX = 0f
    private var dragRemainderY = 0f

    private fun moveBy(view: View, dxPx: Float, dyPx: Float) {
        val lp = params ?: return
        val wm = windowManager ?: return
        val totalX = dxPx + dragRemainderX
        val totalY = dyPx + dragRemainderY
        val stepX = totalX.toInt()
        val stepY = totalY.toInt()
        dragRemainderX = totalX - stepX
        dragRemainderY = totalY - stepY
        lp.x += stepX
        lp.y += stepY
        runCatching { wm.updateViewLayout(view, lp) }
    }

    /**
     * Whether the bubble's center sits in the artboard's bottom hot zone
     * — computed from the live screen's real height at release time (the
     * bubble rides over other apps that may rotate), not a hardcoded
     * pixel threshold.
     */
    private fun isInHotZone(context: Context): Boolean {
        val lp = params ?: return false
        val screenH = screenHeightPx(context)
        val bubbleCenterY = lp.y + BUBBLE_WINDOW_DP.dpToPx(context) / 2
        return bubbleCenterY >= screenH * HOT_ZONE_Y_FRACTION
    }

    private fun screenHeightPx(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            // minSdk 29: R (30) needs the pre-WindowMetrics fallback.
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.heightPixels
        }
    }

    private fun Int.dpToPx(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()

    private const val INITIAL_X_DP = 24
    private const val INITIAL_Y_DP = 160

    private const val RING_TICK_MS = 1000L

    /**
     * The artboard's bottom hot zone (`design/Bubble.dc.html`,
     * HANDOFF.md's Bubble row: "drag-down=eject (hot zone bottom,
     * y>660)") is y > 660 of an 844dp-tall design frame — [Layout.FRAME_H]
     * is that same 844. Kept as the *proportion* the artboard actually
     * encodes, not the 660px it happened to be drawn at, so a release at
     * this same fraction of whatever the live screen's real height turns
     * out to be reads as the same gesture regardless of device size.
     */
    private const val HOT_ZONE_Y_FRACTION = 660f / Layout.FRAME_H
}

/**
 * The bubble's own [LifecycleOwner]/[SavedStateRegistryOwner]/
 * [ViewModelStoreOwner] — a `ComposeView` attached directly via
 * `WindowManager` has no Activity to inherit these from, so it carries
 * its own. `ComposeView` is `final` (can't be subclassed to implement
 * these directly the way an Activity does), so this lives as a
 * standalone object wired onto the plain view via the `View.setViewTree*
 * Owner` extension functions instead. Order matters:
 * [SavedStateRegistryController.performRestore] runs before the
 * lifecycle moves off `INITIALIZED`, and all three `setViewTree*Owner`
 * calls run before `WindowManager.addView`.
 */
private class BubbleLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/**
 * The bubble's visible content: a ring that fills as
 * `ring.filledFrames / capacity` fills, OILSLICK-scheme colors via the
 * same [Schemes] plumbing the rest of the app draws from — not the
 * artboard's raw hex, per the standing "no hardcoded colors" rule.
 * [Schemes.OILSLICK.warn] is the fill arc: "always warm" per its own
 * KDoc, the same read the artboard's orange ring was going for.
 *
 * Tap and drag are two separate `pointerInput` blocks — one block
 * detecting both gestures has them fight each other for the same pointer
 * events. Separate blocks don't arbitrate on their own, though: a drag's
 * release still reaches the tap detector as an up event, so without
 * [dragged] a drag-to-eject would also fire a spurious SNIP. [dragged] is
 * cleared on every new press (`onPress`, which always runs before either
 * detector can decide tap vs. drag) and set the instant a drag actually
 * starts — before any release can land — so the tap check is correct no
 * matter which detector's release callback happens to run first.
 */
@Composable
private fun BubbleContent(
    fraction: Float,
    scale: Float,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    val scheme = Schemes.OILSLICK
    var dragged by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(BUBBLE_WINDOW_DP.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { dragged = false },
                    onTap = { if (!dragged) onTap() },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragged = true; onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, amount ->
                    change.consume()
                    onDrag(amount.x, amount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(BUBBLE_DIAMETER_DP.dp)
                .scale(scale),
        ) {
            val stroke = BUBBLE_STROKE_DP.dp.toPx()
            val inset = stroke / 2
            val ringSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(color = scheme.lcd.tape, radius = size.minDimension / 2 - stroke)
            // Unfilled track.
            drawArc(
                color = scheme.ink3.tape.copy(alpha = 0.35f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(inset, inset),
                size = ringSize,
            )
            // Tape fill.
            drawArc(
                color = scheme.warn.tape,
                startAngle = -90f,
                sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(inset, inset),
                size = ringSize,
            )
        }
    }
}

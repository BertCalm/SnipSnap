package com.snipsnap.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.snipsnap.app.LiveSnapVoice
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import com.snipsnap.synth.Photo
import com.snipsnap.synth.Snap
import com.snipsnap.synth.SnapVoice
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [ImageAnalysis]'s own target — CameraX picks the closest supported
 * resolution, not this exactly; it only bounds how much a frame costs
 * before [toSmallBitmap] downscales it further.
 */
private val LIVE_ANALYSIS_RESOLUTION = Size(640, 480)

/**
 * The live [Photo]'s own long side. [Snap.table]/[Snap.look] already read
 * a thumbnail-sized grid for an ordinary photo (`MAX_PHOTO_SIDE` above);
 * LIVE runs that same read every frame, so it stays well under it — cheap
 * enough that [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] is the only
 * throttle this needs.
 */
private const val LIVE_FRAME_SIDE = 120

/**
 * LIVE ▸ on SNAP (PHOTO_SPECS.md §8): the camera's preview read every
 * frame, straight into [Snap]'s own line and reading, played by a
 * continuous native voice ([LiveSnapVoice]) instead of a one-shot render.
 * The preview fills the panel, the line just read is drawn over it, and
 * the four macro sliders track the same reading live. FREEZE hands the
 * last frame back to [onFrozen] as an ordinary photo — the exact door
 * TAKE PHOTO already opens on `SnapScreen`, so everything below it there
 * (DRAW, FIELD, SEND TO PAD, ...) works on a live capture exactly as it
 * would on a snapshot.
 *
 * DECAY has no engine to reach here — see `LiveSnapEngine.h`'s own KDoc
 * for why a continuous voice has nothing to decay. It is still read off
 * every frame and shown on its own slider, like the other three; only
 * TUNE/BRIGHT/GRIT are ever sent to [LiveSnapVoice.setMacros].
 *
 * CAMERA is asked for once, on the way in; a refusal closes this screen
 * with a word rather than sitting on a preview that can never fill.
 */
@Composable
fun LiveSnapScreen(
    scheme: Scheme,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onFrozen: (Bitmap, Photo, Snap.Reading) -> Unit,
) {
    // No BackHandler of this screen's own: `SnapScreen.kt`'s own overlay
    // block already registers one for showLive, the same shape the
    // chooser overlays lean on (unlike GrainFieldScreen, which keeps its
    // own - see that file's header comment for why FIELD is different).
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            hasCamera = true
        } else {
            onToast(Copy.LIVE_CAMERA_DENIED)
            onBack()
        }
    }
    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Nothing below opens a stream or a camera until the grant is in hand —
    // the system dialog (or a fast prior grant) decides `hasCamera` above,
    // and this composition just waits rather than standing up an engine or
    // a preview that has nothing yet to show.
    if (!hasCamera) return

    val voice = remember { LiveSnapVoice(deviceSampleRate(context)) }
    DisposableEffect(voice) {
        val started = voice.start()
        if (!started) {
            onToast(Copy.noLowLatencyStream("LIVE"))
        } else if (voice.isShared()) {
            onToast(Copy.SURFACE_SHARED_STREAM)
        }
        onDispose { voice.close() }
    }

    // The latest frame's own reading, mirrored into Compose state by the
    // analyzer below (a background thread — see its own comment) so the
    // line, the sliders and FREEZE all agree on what was last seen.
    var latestSmall by remember { mutableStateOf<Bitmap?>(null) }
    var latestPhoto by remember { mutableStateOf<Photo?>(null) }
    var latestReading by remember { mutableStateOf<Snap.Reading?>(null) }
    var latestTable by remember { mutableStateOf<IntArray?>(null) }
    var latestMacros by remember { mutableStateOf<Map<String, Float>>(Snap.defaults(SnapVoice.HORIZON)) }

    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    LaunchedEffect(voice) {
        val provider = try {
            ProcessCameraProvider.getInstance(context).await(context)
        } catch (ex: Exception) {
            Log.e("LiveSnapScreen", "getInstance: failed", ex)
            onToast(Copy.LIVE_CAMERA_UNAVAILABLE)
            onBack()
            return@LaunchedEffect
        }

        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(LIVE_ANALYSIS_RESOLUTION)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        // The analyzer's own thread, never the main one: every read here
        // (the downscale, Snap.look/table, the JNI pushFrame/setMacros
        // calls) is exactly the off-main-thread work every other :synth
        // pass on this screen already does through Dispatchers.Default -
        // STRATEGY_KEEP_ONLY_LATEST means a slow frame is dropped, not
        // queued, so this never falls behind the camera.
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                val small = proxy.toSmallBitmap(LIVE_FRAME_SIDE)
                val photo = small.toPhoto()
                val reading = Snap.look(photo)
                val table = Snap.table(photo, SnapVoice.HORIZON)
                val macros = Snap.macrosFrom(reading)
                voice.pushFrame(Snap.liveCycle(table))
                voice.setMacros(macros.getValue("TUNE"), macros.getValue("BRIGHT"), macros.getValue("GRIT"))
                latestSmall = small
                latestPhoto = photo
                latestReading = reading
                latestTable = table
                latestMacros = macros
            } catch (ex: Exception) {
                // One bad frame is not this screen's failure — the next
                // one is a beat away. Logged, not toasted: a toast per
                // dropped frame would be noise at camera frame rate.
                Log.e("LiveSnapScreen", "analyze: frame dropped", ex)
            } finally {
                proxy.close()
            }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (ex: Exception) {
            Log.e("LiveSnapScreen", "bindToLifecycle: failed", ex)
            onToast(Copy.LIVE_CAMERA_UNAVAILABLE)
            onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(scheme.lcd.tape).padding(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip("◄ SNAP", scheme, Modifier.width(72.dp), onClick = onBack)
            TapeText("LIVE", TapeType.pixel, scheme.titleInk.tape)
            Spacer(Modifier.width(72.dp))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .padding(top = 6.dp)
                .lcdPanel(scheme),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            LiveLine(latestTable, scheme.accent.tape, Modifier.fillMaxSize())
        }

        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (spec in Snap.macrosFor(SnapVoice.HORIZON)) {
                MacroSlider(
                    label = spec.name,
                    value = latestMacros.getValue(spec.name),
                    fillColor = scheme.accent.tape,
                    scheme = scheme,
                    // Read, not played: LIVE's own reading drives every
                    // slider, never a finger - see this screen's own KDoc
                    // for why DECAY especially is along for the ride only.
                    onValueChange = {},
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .heightIn(min = Layout.PRIMARY_ACTION_H.dp)
                .lcdPanel(scheme)
                .tapeClick(label = "FREEZE", enabled = latestPhoto != null) {
                    val small = latestSmall ?: return@tapeClick
                    val photo = latestPhoto ?: return@tapeClick
                    val reading = latestReading ?: return@tapeClick
                    onToast(Copy.LIVE_FROZEN)
                    onFrozen(small, photo, reading)
                },
            contentAlignment = Alignment.Center,
        ) {
            TapeText("FREEZE", TapeType.pixel, scheme.titleInk.tape)
        }
    }
}

/** This file's own back chip — each overlay screen keeps its own copy rather than sharing one; see `DoublesScreen.kt`'s stated convention. */
@Composable
private fun HeaderChip(label: String, scheme: Scheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.ink2.tape, RoundedCornerShape(3.dp))
            .tapeClick(label = label, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, scheme.ink.tape)
    }
}

/** The line [Snap.table] just read off the live frame, drawn over the preview exactly as `DrawLcd` draws a table over its own panel - same coordinates (x centred per point, y a fraction of brightness), no fill, no floor line: nothing here is a control surface, only a picture of what the ear is hearing. */
@Composable
private fun LiveLine(points: IntArray?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val table = points ?: return@Canvas
        val n = table.size
        if (n == 0) return@Canvas
        val w = size.width
        val h = size.height
        val path = Path()
        for (i in 0 until n) {
            val x = w * (i + 0.5f) / n
            val y = h * (1f - table[i] / 255f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * [proxy]'s single RGBA_8888 plane (`ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888`)
 * as a [Bitmap], downscaled to at most [longSide] on its long edge.
 * Android's ARGB_8888 config stores pixels in the same R,G,B,A byte order
 * CameraX hands back in RGBA_8888 - that agreement is the whole reason
 * this format was picked over the default YUV_420_888, so `copyPixelsFromBuffer`
 * needs no channel shuffle, only the row-stride padding a camera's own
 * buffer usually carries handled first.
 */
private fun ImageProxy.toSmallBitmap(longSide: Int): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val rowBytes = width * pixelStride
    val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    if (rowStride == rowBytes) {
        full.copyPixelsFromBuffer(buffer)
    } else {
        val packed = ByteBuffer.allocateDirect(rowBytes * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, rowStride)
            packed.put(row, 0, rowBytes)
        }
        packed.rewind()
        full.copyPixelsFromBuffer(packed)
    }
    val scale = longSide.toFloat() / maxOf(full.width, full.height)
    if (scale >= 1f) return full
    val w = (full.width * scale).roundToInt().coerceAtLeast(1)
    val h = (full.height * scale).roundToInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(full, w, h, true)
    full.recycle()
    return small
}

/**
 * A `ListenableFuture` awaited without a full Guava dependency - CameraX
 * already pulls in the `listenablefuture` stub this interface needs, and
 * `ProcessCameraProvider.getInstance` is the one call in this file that
 * returns one. [context] only supplies the callback's executor; the
 * result itself is read straight off the future, once.
 */
private suspend fun <T> ListenableFuture<T>.await(context: Context): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (ex: Exception) {
                    cont.resumeWithException(ex)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

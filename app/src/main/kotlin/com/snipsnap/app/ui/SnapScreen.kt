package com.snipsnap.app.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Photo
import com.snipsnap.synth.Snap
import com.snipsnap.synth.SnapPatch
import com.snipsnap.synth.SnapVoice
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Same trailing-edge debounce as SYNTH's: a slider drag never renders 60×/s. */
private const val MACRO_DEBOUNCE_MS = 100L
private const val RENDER_SHIMMER_DELAY_MS = 150L

/**
 * The longest side a photo is read at. `TakePicturePreview` is documented
 * as a small bitmap, but it crosses from the camera app through shared
 * memory and some camera apps fill it with the whole frame — a 12 MP one
 * would want a 48 MB pixel array here. Everything `Snap` measures is
 * grid-based (its own `DETAIL_GRID`) or a 256-point line, so 512 px
 * loses it nothing.
 */
private const val MAX_PHOTO_SIDE = 512

/**
 * One line through one photo, read by one LINE chip: the table and
 * whether it had any swing in it, kept together with what produced them
 * so SEND TO PAD can never pair the previous line's table with the
 * current chip's name (a tap on PLUMB followed by SEND before the read
 * lands used to do exactly that).
 */
private class Line(val photo: Photo, val voice: SnapVoice, val table: IntArray, val flat: Boolean)

private fun padTag(slot: Int): String = PadBanks.tag(slot)

/**
 * SNAP — a photo becomes a pad. TAKE PHOTO asks the system camera for a
 * picture (`TakePicturePreview`: the small bitmap the camera app hands
 * back, no file, no storage permission, no CAMERA permission of our own —
 * the camera app holds that), and `:synth`'s `Snap` does the rest: the
 * LINE chips pick which line through the picture is read as one cycle of
 * a waveform (HORIZON across, PLUMB down, ORBIT around the centre), the
 * photo's own numbers set the four knobs (hue → TUNE, brightness →
 * BRIGHT, colour → DECAY, texture → GRIT; AS SHOT puts them back), and
 * the rest of the screen is SYNTH's: the scope, the sliders, AUDITION,
 * SEND TO PAD through the same slot chooser and the same kit write.
 *
 * What lands on the pad is a `SnapPatch` recipe — the 256 brightness
 * values plus the macros, never the photo — so the pad regenerates from
 * `kit.json` like every other synth pad, and the picture's line sits in
 * the sidecar as plain digits.
 *
 * The LCD readout beside the thumbnail shows the reading the knobs were
 * set from (LUM, SAT, HUE, DETAIL), so a pad that came out dark or dirty
 * can be traced to the picture that made it — the same "inspectable, not
 * mysterious" rule the classifier lives by.
 */
@Composable
fun SnapScreen(
    entry: KitShelf.Entry?,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
    // App()'s own scope, same as SynthScreen's: a SEND TO PAD write in
    // flight survives a MenuRow tab switch instead of being cancelled by it.
    appScope: CoroutineScope,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var photo by remember { mutableStateOf<Photo?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var reading by remember { mutableStateOf<Snap.Reading?>(null) }
    var voice by remember { mutableStateOf(SnapVoice.HORIZON) }
    var macros by remember { mutableStateOf(Snap.defaults(SnapVoice.HORIZON)) }
    // The line read for the current (photo, voice) — see [Line]. A flat
    // line is `Snap.read`'s one refusal; it is toasted once when the line
    // is read, and SEND stays off until a different line or photo reads
    // through. `line` is stale (and SEND off) from the moment the photo
    // or the chip changes until the read below replaces it.
    var line by remember { mutableStateOf<Line?>(null) }
    val current = line?.takeIf { it.photo === photo && it.voice == voice }
    var looking by remember { mutableStateOf(false) }

    // Nothing is heard until the first real touch, same as SYNTH: landing
    // on the tab never plays a note unasked. Taking a photo counts as one.
    var touched by remember { mutableStateOf(false) }
    fun updateMacro(name: String, value: Float) {
        touched = true
        macros = macros + (name to value)
    }

    var snip by remember { mutableStateOf<Snip?>(null) }
    var rendering by remember { mutableStateOf(false) }

    var voicePlayer by remember { mutableStateOf<TapeVoice?>(null) }
    DisposableEffect(Unit) { onDispose { voicePlayer?.release() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voicePlayer?.release()
                voicePlayer = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun audition(target: Snip) {
        voicePlayer?.stop()
        val mono = Cleanup.toMono(target)
        val v = TapeVoice(mono.samples, mono.sampleRate)
        voicePlayer = v
        v.start(0)
    }

    // ---- TAKE PHOTO ----
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        // null: the camera app was dismissed without a picture. Nothing
        // changes — the last photo, if any, stays on the LCD.
        if (bitmap == null) return@rememberLauncherForActivityResult
        looking = true
        scope.launch {
            try {
                val (small, p, r) = withContext(Dispatchers.Default) {
                    val small = bitmap.shrunk()
                    val p = small.toPhoto()
                    Triple(small, p, Snap.look(p))
                }
                thumb = small
                photo = p
                reading = r
                macros = Snap.macrosFrom(r)
                touched = true
            } catch (ex: OutOfMemoryError) {
                // Not an Exception: the catch below would let it through
                // and the process would die reading a picture. TapeScreen
                // and ChopScreen catch it on their own decodes for the
                // same reason.
                Log.e("SnapScreen", "look: out of memory", ex)
                onToast(Copy.SNAP_TOO_BIG)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Log.e("SnapScreen", "look: failed", ex)
                onToast(Copy.RENDER_FAILED)
            } finally {
                looking = false
            }
        }
    }
    fun takePhoto() {
        try {
            camera.launch(null)
        } catch (ex: ActivityNotFoundException) {
            onToast(Copy.SNAP_NO_CAMERA)
        }
    }

    // The line through the photo, re-read when the photo or the LINE
    // changes. Cheap (one pass over a thumbnail) but off the main thread
    // like every other piece of `:synth` work on a screen.
    LaunchedEffect(photo, voice) {
        val p = photo
        if (p == null) {
            line = null
            return@LaunchedEffect
        }
        val v = voice
        val t = withContext(Dispatchers.Default) { Snap.table(p, v) }
        val isFlat = Snap.isFlat(t)
        line = Line(p, v, t, isFlat)
        if (isFlat) onToast(Copy.SNAP_FLAT)
    }

    // The debounced re-render + retrigger loop, SYNTH's own. Keyed on the
    // line as read, not on the chip: a new photo's knobs arrive with the
    // photo, a beat before its line does, and the old line under the new
    // knobs is not a sound anyone asked for.
    LaunchedEffect(current, macros) {
        val l = current
        if (l == null || l.flat) {
            snip = null
            return@LaunchedEffect
        }
        delay(MACRO_DEBOUNCE_MS)
        val shimmerJob = launch { delay(RENDER_SHIMMER_DELAY_MS); rendering = true }
        try {
            val rendered = withContext(Dispatchers.Default) { Snap.render(l.table, macros) }
            snip = rendered
            if (touched) audition(rendered)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SnapScreen", "render: failed", e)
            onToast(Copy.RENDER_FAILED)
        } finally {
            shimmerJob.cancel()
            rendering = false
        }
    }

    // ---- SEND TO PAD ----
    var showChooser by remember { mutableStateOf(false) }
    var sendBusy by remember { mutableStateOf(false) }
    val kit = entry?.kit
    fun patchNameFor(v: SnapVoice) = "Snap ${v.name.lowercase().replaceFirstChar { it.uppercase() }}"
    // A note, like PLUCK's: the pad is tonal whatever the picture, and
    // AutoPlace's colour and (no) choke group follow from that.
    val cls = DrumClass.TONAL
    val classColor = Schemes.classColor(cls).tape

    fun sendToSlot(slot: Int) {
        val e = entry ?: return
        // The line as read, voice and table together (see [Line]): what
        // lands is what was heard, named for the chip that read it.
        val l = current ?: return
        if (sendBusy || l.flat) return
        val patchName = patchNameFor(l.voice)
        sendBusy = true
        appScope.launch {
            try {
                val patch = SnapPatch(patchName, l.voice, macros, l.table)
                val recipe = PadRecipe(patch = patch).toJsonValue()
                val (existed, updatedKit) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        val rendered = patch.render()
                        val alreadyThere = model.pad(slot) != null
                        if (alreadyThere) {
                            // REPLACE, then re-identify the pad — same two
                            // steps and the same reason as SynthScreen's.
                            model.replaceAudio(slot, recipe) { _ -> rendered }
                            model.update(slot) { p ->
                                p.copy(
                                    displayName = patchName,
                                    drumClass = cls,
                                    colorHex = AutoPlace.colorFor(cls),
                                    muteGroup = AutoPlace.muteGroupFor(cls),
                                )
                            }
                        } else {
                            model.assign(slot, rendered, cls, patchName)
                            model.update(slot) { p -> p.copy(recipe = recipe) }
                        }
                        model.save()
                        alreadyThere to model.kit
                    }
                }
                showChooser = false
                onKitUpdated(updatedKit)
                onToast(Copy.synthSent(padTag(slot), patchName, replaced = existed))
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                if (ex is IllegalStateException || ex is IllegalArgumentException) {
                    onToast(Copy.SYNTH_PAD_REFUSED)
                } else {
                    Log.e("SnapScreen", "sendToSlot: failed", ex)
                    onToast(Copy.SEND_FAILED)
                }
            } finally {
                sendBusy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                TapeText("SNAP", TapeType.lcdHeader, scheme.lcdInk.tape)
                TapeText(if (photo == null) "NO PHOTO" else "LINE ${voice.name}", TapeType.lcdSmall, scheme.amber.tape)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhotoStrip(thumb, reading, looking, scheme)

                ScopeLcd(snip, rendering, scheme, Modifier.fillMaxWidth().height(104.dp))

                LinePicker(voice, scheme, onSelect = { touched = true; voice = it })

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (spec in Snap.macrosFor(voice)) {
                        MacroSlider(
                            label = spec.name,
                            value = macros.getValue(spec.name),
                            fillColor = classColor,
                            scheme = scheme,
                            onValueChange = { v -> updateMacro(spec.name, v) },
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabButton(
                        if (looking) "…" else "TAKE PHOTO",
                        scheme,
                        enabled = !looking,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "TAKE PHOTO",
                    ) { takePhoto() }
                    LabButton(
                        "AS SHOT",
                        scheme,
                        enabled = reading != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        reading?.let { touched = true; macros = Snap.macrosFrom(it) }
                    }
                    LabButton(
                        if (sendBusy) "…" else "SEND TO PAD ▸",
                        scheme,
                        enabled = kit != null && current != null && !current.flat && !sendBusy,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "SEND TO PAD",
                    ) {
                        showChooser = true
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Layout.PRIMARY_ACTION_H.dp)
                    .raisedBevel(scheme, fill = classColor.copy(alpha = 0.85f))
                    .tapeClick(label = "AUDITION") { snip?.let { audition(it) } }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("AUDITION", TapeType.pixel, scheme.titleInk.tape)
            }
        }

        if (showChooser) {
            val cancelChooser = { if (!sendBusy) showChooser = false }
            SlotChooserOverlay(
                kit = kit,
                previewColor = classColor,
                scheme = scheme,
                busy = sendBusy,
                onPick = ::sendToSlot,
                onCancel = cancelChooser,
            )
            BackHandler(onBack = cancelChooser)
        }
    }
}

/**
 * The camera's bitmap at no more than [MAX_PHOTO_SIDE] on its long side,
 * in software memory. `getPixels` refuses a HARDWARE bitmap (its pixels
 * live on the GPU), so that one is copied first; the preview the camera
 * app hands back is a plain ARGB_8888 thumbnail in practice, and the
 * copy is the guard, not the path. A bitmap already small enough is
 * returned as it is.
 */
private fun Bitmap.shrunk(): Bitmap {
    val soft = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val long = maxOf(soft.width, soft.height)
    if (long <= MAX_PHOTO_SIDE) return soft
    val scale = MAX_PHOTO_SIDE.toFloat() / long
    val w = (soft.width * scale).roundToInt().coerceAtLeast(1)
    val h = (soft.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(soft, w, h, true)
}

/** A (small) bitmap as `:synth`'s pixel grid. */
private fun Bitmap.toPhoto(): Photo {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return Photo(width, height, px)
}

// ---------- the photo and its numbers ----------

/**
 * The thumbnail beside the reading it produced — LUM, SAT, HUE, DETAIL as
 * whole numbers on the LCD, the same digits `Snap.macrosFrom` turned into
 * the knobs. Before a photo, the strip says so and draws the line's
 * empty scope in its place.
 */
@Composable
private fun PhotoStrip(thumb: Bitmap?, reading: Snap.Reading?, looking: Boolean, scheme: Scheme) {
    Row(
        Modifier.fillMaxWidth().height(96.dp).lcdPanel(scheme).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "THE PHOTO",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // An empty frame: the corners of where the picture goes.
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "NO PHOTO YET" }) {
                    val ink = scheme.lcdInk.tape.copy(alpha = 0.5f)
                    val c = 10.dp.toPx()
                    val w = size.width
                    val h = size.height
                    val s = 1.5.dp.toPx()
                    drawLine(ink, Offset(0f, 0f), Offset(c, 0f), s); drawLine(ink, Offset(0f, 0f), Offset(0f, c), s)
                    drawLine(ink, Offset(w, 0f), Offset(w - c, 0f), s); drawLine(ink, Offset(w, 0f), Offset(w, c), s)
                    drawLine(ink, Offset(0f, h), Offset(c, h), s); drawLine(ink, Offset(0f, h), Offset(0f, h - c), s)
                    drawLine(ink, Offset(w, h), Offset(w - c, h), s); drawLine(ink, Offset(w, h), Offset(w, h - c), s)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when {
                looking -> TapeText("LOOKING…", TapeType.lcdSmall, scheme.amber.tape)
                reading == null -> {
                    TapeText("TAKE A PHOTO.", TapeType.lcdSmall, scheme.lcdInk.tape)
                    TapeText("ITS LINE IS THE WAVE,", TapeType.lcdSmall, scheme.lcdInk.tape.copy(alpha = 0.7f))
                    TapeText("ITS COLOURS ARE THE KNOBS.", TapeType.lcdSmall, scheme.lcdInk.tape.copy(alpha = 0.7f))
                }
                else -> {
                    TapeText("LUM ${pct(reading.luminance)}  SAT ${pct(reading.saturation)}", TapeType.lcdSmall, scheme.lcdInk.tape)
                    TapeText("HUE ${reading.hue.roundToInt()}°  DETAIL ${pct(reading.detail)}", TapeType.lcdSmall, scheme.lcdInk.tape)
                    TapeText("CONTRAST ${pct(reading.contrast)}", TapeType.lcdSmall, scheme.lcdInk.tape.copy(alpha = 0.7f))
                }
            }
        }
    }
}

private fun pct(v: Float): String = (v * 100).roundToInt().toString().padStart(2, '0')

// ---------- the line ----------

/** HORIZON / PLUMB / ORBIT: which line through the photo is read as the cycle. Same chip as SYNTH's voice picker. */
@Composable
private fun LinePicker(current: SnapVoice, scheme: Scheme, onSelect: (SnapVoice) -> Unit) {
    val color = Schemes.classColor(DrumClass.TONAL).tape
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (v in SnapVoice.entries) {
            val selected = v == current
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                    .semantics { this.selected = selected }
                    .tapeClick(label = "LINE ${v.name}") { onSelect(v) }
                    .padding(horizontal = 2.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TapeText(v.name, TapeType.pixelSmall, color, maxLines = 1)
                TapeText(
                    when (v) {
                        SnapVoice.HORIZON -> "ACROSS"
                        SnapVoice.PLUMB -> "DOWN"
                        SnapVoice.ORBIT -> "AROUND"
                    },
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    maxLines = 1,
                )
            }
        }
    }
}

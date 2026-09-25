package com.snipsnap.app.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
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
import com.snipsnap.synth.Draw
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Photo
import com.snipsnap.synth.PhotoField
import com.snipsnap.synth.PhotoKit
import com.snipsnap.synth.PhotoPath
import com.snipsnap.synth.Snap
import com.snipsnap.synth.SnapPatch
import com.snipsnap.synth.SnapVoice
import com.snipsnap.synth.Spectrogram
import com.snipsnap.synth.Telephone
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
private class Line(val photo: Photo?, val voice: SnapVoice, val table: IntArray, val flat: Boolean)

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
    // App()'s own busy lock: KIT ▸ renders off-thread through it (the
    // same DUBBING…-shaped overlay FRESH TAPE and BREED use), since it
    // lands on the shelf and leaves this screen rather than staying on
    // it the way FIELD/CLOUD's own local busy flags do.
    busy: Boolean,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
    // KIT ▸: build a whole kit off the current photo and land it on the
    // shelf. App.kt owns the shelf write and the navigation to it.
    onBuildKit: (Photo) -> Unit,
    // PATH's RING ▸: the walked cells as a fresh ORBIT ring, named for the
    // open kit's own pads. App.kt owns the orbits.json write and the
    // navigation to ORBIT — the same shape as onBuildKit above.
    onBuildPathRing: (cells: List<Pair<Int, Int>>, bpm: Float) -> Unit,
    // CHORD ▸: the photo read as a chord and landed on the shelf as a kit,
    // plus an arpeggio ring built for that same new kit — App.kt owns the
    // shelf write, the orbits.json write, and the navigation to ORBIT, the
    // same shape as onBuildKit and onBuildPathRing above.
    onBuildChord: (Photo) -> Unit,
    // FIELD's own PRINT, threaded down to GrainFieldScreen: a print landed
    // on TAPE, the same reload request a share-sheet import raises.
    onFieldPrinted: () -> Unit,
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
    // A drawn line has no photo behind it and is current for as long as
    // the DRAW chip is the selected one; a photo line is current while it
    // is the one read off this photo along this chip.
    val current = line?.takeIf { (it.voice == SnapVoice.DRAWN && voice == SnapVoice.DRAWN) || (it.photo === photo && it.voice == voice) }
    var looking by remember { mutableStateOf(false) }
    // The drawn volume shape, or null for SNAP's own decay. Independent of
    // the line: a photo's line under a drawn shape is a fine pad.
    var envelope by remember { mutableStateOf<IntArray?>(null) }
    // The DRAW surface is open: it auditions on its own, so the main
    // render loop below stands still while it is.
    var drawing by remember { mutableStateOf(false) }
    // The last line a finger drew, kept across chip taps and new photos:
    // a photo chip used to throw it away (its read replaced `line`), and
    // reopening DRAW started from blank. DRAW's LAST button brings it back.
    var drawn by remember { mutableStateOf<IntArray?>(null) }
    // Set when the surface closes: it just played whatever it had, so the
    // main loop's re-render on its way back renders without a replay.
    var quietResume by remember { mutableStateOf(false) }
    // PHOTO FIELD: the picture cut into grains (PhotoField.build), built
    // once per photo on first FIELD and kept until the next photo; the
    // GRAIN FIELD screen plays it over the picture, and CLOUD lands it.
    var field by remember { mutableStateOf<PhotoField.Field?>(null) }
    var buildingField by remember { mutableStateOf(false) }
    var showField by remember { mutableStateOf(false) }
    var cloudBusy by remember { mutableStateOf(false) }
    var showCloudChooser by remember { mutableStateOf(false) }

    // PATH's LOOP PAD ▸: the walked cells, held until a slot is picked and
    // the render lands — the RING ▸ landing goes straight through
    // onBuildPathRing instead, with nothing to hold here.
    var pathBusy by remember { mutableStateOf(false) }
    var showPathChooser by remember { mutableStateOf(false) }
    var pendingPath by remember { mutableStateOf<Pair<List<Pair<Int, Int>>, Float>?>(null) }

    // SPECTRUM: the photo read as a spectrogram and inverted — needs
    // only the photo itself, unlike CLOUD's field.
    var spectrumBusy by remember { mutableStateOf(false) }
    var showSpectrumChooser by remember { mutableStateOf(false) }

    // TELEPHONE: SPECTRUM's own reading, passed down eight more
    // generations (Telephone.chain) — each a picture of what the last one
    // sounded like, whispered forward. Stays on SNAP like FIELD/CLOUD/
    // SPECTRUM; its own strip picks a generation, SEND TO PAD ▸ reuses
    // SlotChooserOverlay the same way those three do.
    var telephoneBusy by remember { mutableStateOf(false) }
    var generations by remember { mutableStateOf<List<Telephone.Generation>>(emptyList()) }
    var showTelephone by remember { mutableStateOf(false) }
    var showTelephoneChooser by remember { mutableStateOf(false) }
    var telephonePick by remember { mutableStateOf(0) }

    // LIVE: the camera preview read every frame, over its own screen —
    // see LiveSnapScreen.kt. Not a chooser like FIELD/CLOUD/SPECTRUM:
    // FREEZE lands straight back on this screen's own photo/reading/
    // macros, the exact door TAKE PHOTO already opens.
    var showLive by remember { mutableStateOf(false) }

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
                // The old picture's field is not this picture's: drop it
                // and close the field if it is up, or the main loop below
                // would stand still behind an overlay that is no longer
                // shown. A build in flight for the old photo checks
                // `photo` when it lands and drops itself.
                field = null
                showField = false
                reading = r
                macros = Snap.macrosFrom(r)
                // A new photo is a new line: its knobs would otherwise
                // land on a drawn line the photo has nothing to do with,
                // with the picture on the LCD and its line unheard. The
                // drawing stays reachable through DRAW's LAST.
                if (voice == SnapVoice.DRAWN) voice = SnapVoice.HORIZON
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
        // The drawn line is not read off anything: DRAW's DONE sets it.
        if (voice == SnapVoice.DRAWN) return@LaunchedEffect
        val p = photo
        if (p == null) {
            line = null
            return@LaunchedEffect
        }
        val v = voice
        // The one unguarded step on the whole post-capture path. This
        // effect runs *because* `photo` just changed, so it fires a beat
        // after TAKE PHOTO returns — and an uncaught throw in a main-
        // dispatcher LaunchedEffect takes the process with it, while the
        // capture callback above and the render loop below both catch and
        // toast. Same handler as the render loop's: a failure here is a
        // named logcat line and a toast, not a silent death.
        try {
            val t = withContext(Dispatchers.Default) { Snap.table(p, v) }
            val isFlat = Snap.isFlat(t)
            line = Line(p, v, t, isFlat)
            if (isFlat) onToast(Copy.SNAP_FLAT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SnapScreen", "table: failed", e)
            onToast(Copy.RENDER_FAILED)
        }
    }

    // The debounced re-render + retrigger loop, SYNTH's own. Keyed on the
    // line as read, not on the chip: a new photo's knobs arrive with the
    // photo, a beat before its line does, and the old line under the new
    // knobs is not a sound anyone asked for.
    LaunchedEffect(current, macros, envelope, drawing, showField, showTelephone) {
        // The DRAW surface, the PHOTO FIELD, and the TELEPHONE strip each
        // have a voice of their own; this loop stands still while any is up.
        if (drawing || showField || showTelephone) return@LaunchedEffect
        val l = current
        if (l == null || l.flat) {
            snip = null
            // A way back with nothing to render still consumes the quiet,
            // or the next real change would play nothing.
            quietResume = false
            return@LaunchedEffect
        }
        delay(MACRO_DEBOUNCE_MS)
        val shimmerJob = launch { delay(RENDER_SHIMMER_DELAY_MS); rendering = true }
        try {
            val rendered = withContext(Dispatchers.Default) { Snap.render(l.table, macros, envelope) }
            snip = rendered
            if (touched && !quietResume) audition(rendered)
            quietResume = false
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
                val patch = SnapPatch(patchName, l.voice, macros, l.table, envelope)
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

    // ---- PHOTO FIELD ----
    fun openField() {
        val p = photo ?: return
        val built = field
        if (built != null) {
            // The SNAP audition and the field's voice must not overlap.
            voicePlayer?.stop()
            showField = true
            return
        }
        if (buildingField) return
        buildingField = true
        scope.launch {
            try {
                val f = withContext(Dispatchers.Default) { PhotoField.build(p) }
                // A newer photo arrived while this built: this field is
                // the old picture's and lands nowhere.
                if (photo !== p) return@launch
                field = f
                voicePlayer?.stop()
                showField = true
            } catch (ex: OutOfMemoryError) {
                Log.e("SnapScreen", "field: out of memory", ex)
                onToast(Copy.SNAP_TOO_BIG)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Log.e("SnapScreen", "field: failed", ex)
                onToast(Copy.RENDER_FAILED)
            } finally {
                buildingField = false
            }
        }
    }

    // CLOUD: the whole field through GRAINS, landed as audio — no recipe,
    // like every GRAINS pad; the picture's cloud is the truth of the pad.
    val cloudName = "Snap Cloud"
    val cloudClass = DrumClass.LOOP
    fun sendCloudToSlot(slot: Int) {
        val e = entry ?: return
        val f = field ?: return
        if (cloudBusy) return
        cloudBusy = true
        appScope.launch {
            try {
                val cloud = withContext(Dispatchers.Default) { PhotoField.cloud(f) }
                val (existed, updatedKit) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        val alreadyThere = model.pad(slot) != null
                        if (alreadyThere) {
                            // replaceAudio keeps a pad's recipe when handed
                            // null; a synth pad's recipe would then regenerate
                            // the old note over the cloud's audio on the next
                            // rebuild from the sidecar. The cloud has no
                            // recipe, so the pad must not either.
                            model.replaceAudio(slot, null) { _ -> cloud }
                            model.update(slot) { p ->
                                p.copy(
                                    displayName = cloudName,
                                    drumClass = cloudClass,
                                    colorHex = AutoPlace.colorFor(cloudClass),
                                    muteGroup = AutoPlace.muteGroupFor(cloudClass),
                                    recipe = null,
                                )
                            }
                        } else {
                            model.assign(slot, cloud, cloudClass, cloudName)
                        }
                        model.save()
                        alreadyThere to model.kit
                    }
                }
                showCloudChooser = false
                onKitUpdated(updatedKit)
                onToast(Copy.synthSent(padTag(slot), cloudName, replaced = existed))
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                if (ex is IllegalStateException || ex is IllegalArgumentException) {
                    onToast(Copy.SYNTH_PAD_REFUSED)
                } else {
                    Log.e("SnapScreen", "sendCloudToSlot: failed", ex)
                    onToast(Copy.SEND_FAILED)
                }
            } finally {
                cloudBusy = false
            }
        }
    }

    // SPECTRUM: the photo read as a spectrogram — columns time, rows
    // frequency, brightness level — and inverted, landed as audio with
    // no recipe, the same shape CLOUD's texture lands in. Needs only the
    // photo itself, not FIELD's own grid.
    val spectrumName = "Snap Spectrum"
    val spectrumClass = DrumClass.LOOP
    fun sendSpectrumToSlot(slot: Int) {
        val e = entry ?: return
        val p = photo ?: return
        if (spectrumBusy) return
        spectrumBusy = true
        appScope.launch {
            try {
                val spectrum = withContext(Dispatchers.Default) { Spectrogram.read(p, seconds = 2.5f) }
                val (existed, updatedKit) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        val alreadyThere = model.pad(slot) != null
                        if (alreadyThere) {
                            model.replaceAudio(slot, null) { _ -> spectrum }
                            model.update(slot) { pd ->
                                pd.copy(
                                    displayName = spectrumName,
                                    drumClass = spectrumClass,
                                    colorHex = AutoPlace.colorFor(spectrumClass),
                                    muteGroup = AutoPlace.muteGroupFor(spectrumClass),
                                    recipe = null,
                                )
                            }
                        } else {
                            model.assign(slot, spectrum, spectrumClass, spectrumName)
                        }
                        model.save()
                        alreadyThere to model.kit
                    }
                }
                showSpectrumChooser = false
                onKitUpdated(updatedKit)
                onToast(Copy.synthSent(padTag(slot), spectrumName, replaced = existed))
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                if (ex is IllegalStateException || ex is IllegalArgumentException) {
                    onToast(Copy.SYNTH_PAD_REFUSED)
                } else {
                    Log.e("SnapScreen", "sendSpectrumToSlot: failed", ex)
                    onToast(Copy.SEND_FAILED)
                }
            } finally {
                spectrumBusy = false
            }
        }
    }

    // PATH's LOOP PAD ▸: the walked cells rendered as one gapless loop
    // ([PhotoPath.render]) — landed the same way CLOUD's is, audio with
    // no recipe, since a walked path has no per-cell knobs to regenerate.
    val pathName = "Snap Path"
    val pathClass = DrumClass.LOOP
    fun sendPathToSlot(slot: Int) {
        val e = entry ?: return
        val p = photo ?: return
        val pending = pendingPath ?: return
        if (pathBusy) return
        pathBusy = true
        appScope.launch {
            try {
                val loop = withContext(Dispatchers.Default) { PhotoPath.render(p, pending.first, pending.second) }
                val (existed, updatedKit) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        val alreadyThere = model.pad(slot) != null
                        if (alreadyThere) {
                            model.replaceAudio(slot, null) { _ -> loop }
                            model.update(slot) { pd ->
                                pd.copy(
                                    displayName = pathName,
                                    drumClass = pathClass,
                                    colorHex = AutoPlace.colorFor(pathClass),
                                    muteGroup = AutoPlace.muteGroupFor(pathClass),
                                    recipe = null,
                                )
                            }
                        } else {
                            model.assign(slot, loop, pathClass, pathName)
                        }
                        model.save()
                        alreadyThere to model.kit
                    }
                }
                showPathChooser = false
                pendingPath = null
                onKitUpdated(updatedKit)
                onToast(Copy.synthSent(padTag(slot), pathName, replaced = existed))
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                if (ex is IllegalStateException || ex is IllegalArgumentException) {
                    onToast(Copy.SYNTH_PAD_REFUSED)
                } else {
                    Log.e("SnapScreen", "sendPathToSlot: failed", ex)
                    onToast(Copy.SEND_FAILED)
                }
            } finally {
                pathBusy = false
            }
        }
    }

    // TELEPHONE: SPECTRUM's own reading, whispered down eight more
    // generations (Telephone.chain) — each a picture of what the last one
    // sounded like. Stays on this screen: the strip picks a generation,
    // SEND TO PAD ▸ lands it the same way CLOUD's/SPECTRUM's own texture
    // lands, audio with no recipe.
    val telephoneName = "Snap Telephone"
    val telephoneClass = DrumClass.LOOP
    fun runTelephone() {
        val p = photo ?: return
        if (telephoneBusy) return
        telephoneBusy = true
        appScope.launch {
            try {
                // Same reading SPECTRUM ▸ itself makes, so the line starts
                // from exactly what SPECTRUM ▸ would have landed.
                val start = withContext(Dispatchers.Default) { Spectrogram.read(p, seconds = 2.5f) }
                val line = withContext(Dispatchers.Default) { Telephone.chain(start, 8) }
                // A newer photo arrived while this chained: this line is
                // the old picture's and opens nowhere, the same guard
                // openField() makes.
                if (photo !== p) return@launch
                generations = line
                telephonePick = 0
                // The SNAP audition and the strip's own portraits must not
                // overlap, the same reasoning FIELD's and LIVE's own opens do.
                voicePlayer?.stop()
                showTelephone = true
            } catch (ex: OutOfMemoryError) {
                // Not an Exception, so the catch below would let it through
                // and the process would die whispering — the same split
                // openField() and the photo decode above both make. A line
                // is eight portraits and eight sounds held at once, which is
                // the most this screen ever asks for in one go.
                Log.e("SnapScreen", "telephone: out of memory", ex)
                onToast(Copy.SNAP_TOO_BIG)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Log.e("SnapScreen", "telephone: failed", ex)
                onToast(Copy.SEND_FAILED)
            } finally {
                telephoneBusy = false
            }
        }
    }
    fun sendTelephoneToSlot(slot: Int) {
        val e = entry ?: return
        // The tapped portrait's own sound, not a re-read: TELEPHONE's line
        // is fixed the moment it chains: picking a pad should not chain
        // it again.
        val g = generations.getOrNull(telephonePick) ?: return
        if (telephoneBusy) return
        telephoneBusy = true
        appScope.launch {
            try {
                val (existed, updatedKit) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        val alreadyThere = model.pad(slot) != null
                        if (alreadyThere) {
                            model.replaceAudio(slot, null) { _ -> g.sound }
                            model.update(slot) { pd ->
                                pd.copy(
                                    displayName = telephoneName,
                                    drumClass = telephoneClass,
                                    colorHex = AutoPlace.colorFor(telephoneClass),
                                    muteGroup = AutoPlace.muteGroupFor(telephoneClass),
                                    recipe = null,
                                )
                            }
                        } else {
                            model.assign(slot, g.sound, telephoneClass, telephoneName)
                        }
                        model.save()
                        alreadyThere to model.kit
                    }
                }
                showTelephoneChooser = false
                onKitUpdated(updatedKit)
                onToast(Copy.synthSent(padTag(slot), telephoneName, replaced = existed))
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                if (ex is IllegalStateException || ex is IllegalArgumentException) {
                    onToast(Copy.SYNTH_PAD_REFUSED)
                } else {
                    Log.e("SnapScreen", "sendTelephoneToSlot: failed", ex)
                    onToast(Copy.SEND_FAILED)
                }
            } finally {
                telephoneBusy = false
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
                val lineWord = when {
                    voice == SnapVoice.DRAWN -> "LINE DRAWN"
                    photo == null -> "NO PHOTO"
                    else -> "LINE ${voice.name}"
                }
                TapeText(
                    when {
                        buildingField -> Copy.SNAP_FIELD_BUSY
                        cloudBusy -> Copy.SNAP_CLOUD_BUSY
                        pathBusy -> Copy.SNAP_PATH_SEND_BUSY
                        spectrumBusy -> Copy.SNAP_SPECTRUM_BUSY
                        telephoneBusy -> Copy.SNAP_TELEPHONE_BUSY
                        busy -> Copy.SNAP_KIT_BUSY
                        envelope != null -> "$lineWord · SHAPE DRAWN"
                        else -> lineWord
                    },
                    TapeType.lcdSmall,
                    scheme.amber.tape,
                )
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhotoStrip(thumb, reading, looking, scheme)

                ScopeLcd(snip, rendering, scheme, Modifier.fillMaxWidth().height(104.dp))

                LinePicker(
                    voice,
                    scheme,
                    onSelect = { touched = true; voice = it },
                    onDraw = { touched = true; drawing = true },
                )

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
                        // Not while a field builds either: a photo landing
                        // mid-build would open the old picture's field over
                        // the new picture (the build checks, but the camera
                        // app in front while a field opens behind it is no
                        // better).
                        enabled = !looking && !buildingField,
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

                // The whole picture: FIELD to play it under a finger, CLOUD
                // to land it on a pad as a texture, KIT to cut it 4x4 and
                // land all sixteen pads at once.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabButton(
                        if (buildingField) "…" else "FIELD ▸",
                        scheme,
                        enabled = photo != null && !buildingField,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "PHOTO FIELD",
                    ) { openField() }
                    LabButton(
                        if (cloudBusy) "…" else "CLOUD ▸",
                        scheme,
                        enabled = kit != null && field != null && !cloudBusy,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "CLOUD TO PAD",
                    ) {
                        showCloudChooser = true
                    }
                    LabButton(
                        if (busy) "…" else "KIT ▸",
                        scheme,
                        enabled = photo != null && !busy,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "PHOTO KIT",
                    ) {
                        photo?.let(onBuildKit)
                    }
                }

                // The literal reading: the photo as a spectrogram, columns
                // time and rows frequency, inverted to audio and landed
                // like CLOUD's own texture — needs only the photo, not
                // FIELD's own grid. LIVE needs no photo at all yet — it
                // is where one comes from, through the camera preview.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabButton(
                        if (spectrumBusy) "…" else "SPECTRUM ▸",
                        scheme,
                        enabled = kit != null && photo != null && !spectrumBusy,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "SPECTRUM TO PAD",
                    ) {
                        showSpectrumChooser = true
                    }
                    LabButton(
                        "LIVE ▸",
                        scheme,
                        enabled = !looking,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "LIVE CAMERA",
                    ) {
                        // LIVE is its own voice; SNAP's own must not sound
                        // under it, the same reasoning FIELD's own tap stops it.
                        voicePlayer?.stop()
                        showLive = true
                    }
                }

                // CHORD reads the whole photo as a chord and lands a
                // brand-new kit plus an arpeggio already circling it on
                // ORBIT — App.kt owns both writes, the same hand-off as
                // KIT ▸ above. TELEPHONE reads the photo the way SPECTRUM
                // ▸ does, then passes what comes back down eight more
                // generations, each a picture of what the last one
                // sounded like — a strip to pick from, staying here.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabButton(
                        if (busy) "…" else "CHORD ▸",
                        scheme,
                        enabled = photo != null && !busy,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "PHOTO CHORD",
                    ) {
                        photo?.let(onBuildChord)
                    }
                    LabButton(
                        if (telephoneBusy) "…" else "TELEPHONE ▸",
                        scheme,
                        enabled = photo != null && !telephoneBusy,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "TELEPHONE CHAIN",
                    ) {
                        runTelephone()
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

        if (drawing) {
            val closeDrawing = {
                quietResume = true
                drawing = false
            }
            DrawOverlay(
                // Draw over the line that is playing: the photo's, or the
                // last drawing when nothing is. Nothing at all is a blank
                // line at rest.
                startTable = current?.table ?: drawn ?: Draw.blank(),
                startEnvelope = envelope,
                lastDrawn = drawn,
                macros = macros,
                scheme = scheme,
                onAudition = ::audition,
                onToast = onToast,
                onDone = { table, waveDrawn, shape ->
                    if (waveDrawn) {
                        drawn = table
                        line = Line(null, SnapVoice.DRAWN, table, Snap.isFlat(table))
                        voice = SnapVoice.DRAWN
                    }
                    envelope = shape
                    closeDrawing()
                },
                onCancel = closeDrawing,
                photo = photo,
                thumb = thumb,
                bpm = kit?.tempoBpm ?: 92f,
                kitOpen = kit != null,
                onPathLoopPad = { cells, bpm ->
                    pendingPath = cells to bpm
                    showPathChooser = true
                    closeDrawing()
                },
                onPathRing = { cells, bpm ->
                    closeDrawing()
                    onBuildPathRing(cells, bpm)
                },
            )
            BackHandler(onBack = closeDrawing)
        }

        if (showField) {
            field?.let { f ->
                // The GRAIN FIELD screen over this one, on the picture: its
                // own voice, its own back chip, its own BackHandler. The
                // gesture catch-all is the slot chooser's own: a plain
                // background does not hit-test, and a tap in a gap of the
                // field's layout would otherwise reach the AUDITION bar
                // beneath and play SNAP's voice over the field's.
                // The prebuilt field is remembered: GrainFieldScreen keys
                // its load and its voice on it, and a fresh instance per
                // recomposition (a toast, a kit refresh) would tear the
                // voice down mid-drag and start it again.
                val prebuilt = remember(f, thumb) { PrebuiltField(f.source, f.map, thumb, "PHOTO FIELD", "◄ SNAP") }
                Box(Modifier.fillMaxSize().background(scheme.lcd.tape).pointerInput(Unit) { detectTapGestures { } }) {
                    GrainFieldScreen(
                        entry = null,
                        slot = null,
                        onBack = {
                            quietResume = true
                            showField = false
                        },
                        onToast = onToast,
                        onRequestArm = {},
                        onFieldPrinted = onFieldPrinted,
                        prebuilt = prebuilt,
                    )
                }
            }
        }

        if (showLive) {
            LiveSnapScreen(
                scheme = scheme,
                onBack = { showLive = false },
                onToast = onToast,
                onFrozen = { small, p, r ->
                    thumb = small
                    photo = p
                    field = null
                    showField = false
                    reading = r
                    macros = Snap.macrosFrom(r)
                    if (voice == SnapVoice.DRAWN) voice = SnapVoice.HORIZON
                    touched = true
                    showLive = false
                },
            )
            BackHandler(onBack = { showLive = false })
        }

        if (showCloudChooser) {
            val cancelCloud = { if (!cloudBusy) showCloudChooser = false }
            SlotChooserOverlay(
                kit = kit,
                previewColor = Schemes.classColor(cloudClass).tape,
                scheme = scheme,
                busy = cloudBusy,
                onPick = ::sendCloudToSlot,
                onCancel = cancelCloud,
            )
            BackHandler(onBack = cancelCloud)
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

        if (showPathChooser) {
            val cancelPath = { if (!pathBusy) { showPathChooser = false; pendingPath = null } }
            SlotChooserOverlay(
                kit = kit,
                previewColor = Schemes.classColor(pathClass).tape,
                scheme = scheme,
                busy = pathBusy,
                onPick = ::sendPathToSlot,
                onCancel = cancelPath,
            )
            BackHandler(onBack = cancelPath)
        }

        if (showSpectrumChooser) {
            val cancelSpectrum = { if (!spectrumBusy) showSpectrumChooser = false }
            SlotChooserOverlay(
                kit = kit,
                previewColor = Schemes.classColor(spectrumClass).tape,
                scheme = scheme,
                busy = spectrumBusy,
                onPick = ::sendSpectrumToSlot,
                onCancel = cancelSpectrum,
            )
            BackHandler(onBack = cancelSpectrum)
        }

        if (showTelephone) {
            val cancelTelephone = {
                if (!telephoneBusy) {
                    quietResume = true
                    showTelephone = false
                }
            }
            TelephoneOverlay(
                generations = generations,
                picked = telephonePick,
                scheme = scheme,
                onPick = { i ->
                    telephonePick = i
                    audition(generations[i].sound)
                },
                onSend = { showTelephoneChooser = true },
                onCancel = cancelTelephone,
            )
            BackHandler(onBack = cancelTelephone)
        }

        // Declared after the strip above so it wins the back gesture while
        // both are open — the same layering every other chooser here uses.
        if (showTelephoneChooser) {
            val cancelTelephoneChooser = { if (!telephoneBusy) showTelephoneChooser = false }
            SlotChooserOverlay(
                kit = kit,
                previewColor = Schemes.classColor(telephoneClass).tape,
                scheme = scheme,
                busy = telephoneBusy,
                onPick = ::sendTelephoneToSlot,
                onCancel = cancelTelephoneChooser,
            )
            BackHandler(onBack = cancelTelephoneChooser)
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

/** A (small) bitmap as `:synth`'s pixel grid — shared with [LiveSnapScreen]'s own FREEZE, which has no camera-app thumbnail to shrink first. */
internal fun Bitmap.toPhoto(): Photo {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return Photo(width, height, px)
}

/** The reverse of [Bitmap.toPhoto] — TELEPHONE's own portraits, `:synth`'s packed ARGB read straight back as a drawable bitmap. */
internal fun Photo.toBitmap(): Bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

// ---------- TELEPHONE: a strip of eight whispers ----------

/**
 * The full-screen strip over eight [Telephone.Generation]s, the same
 * shape as [SlotChooserOverlay]'s own gesture catch-all: tap a portrait to
 * hear its sound, SEND TO PAD ▸ lands whichever was last tapped. The eight
 * bitmaps are built once per chain, not once per recomposition — this
 * screen recomposes on every macro-slider frame.
 */
@Composable
private fun TelephoneOverlay(
    generations: List<Telephone.Generation>,
    picked: Int,
    scheme: Scheme,
    onPick: (Int) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val portraits = remember(generations) { generations.map { it.portrait.toBitmap().asImageBitmap() } }
    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).pointerInput(Unit) { detectTapGestures { } }.padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapeText("TELEPHONE — TAP ONE TO HEAR IT", TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.weight(1f))
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                        .tapeClick(label = "CANCEL", onClick = onCancel)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("CANCEL", TapeType.pixel, scheme.amber.tape)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in generations.indices) {
                    TelephonePortrait(
                        index = i,
                        bitmap = portraits[i],
                        picked = i == picked,
                        scheme = scheme,
                        onTap = { onPick(i) },
                    )
                }
            }
            LabButton(
                "SEND TO PAD ▸",
                scheme,
                enabled = generations.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                accessibilityLabel = "SEND GENERATION TO PAD",
                onClick = onSend,
            )
        }
    }
}

/**
 * One portrait: [picked] shown as a heavier amber border over its own
 * number, the border-and-sub-label pair every selected control here wears.
 *
 * The count is carried twice on purpose. The border is what an eye picks a
 * generation out by; `selected` is what a screen reader reads, which a
 * border alone never reaches — [LinePicker] sets the same flag beside its
 * own bevel for the same reason. The portrait itself takes no
 * `contentDescription`: the tap target around it is already named, and an
 * inner node would only have the reader say the number twice.
 */
@Composable
private fun TelephonePortrait(index: Int, bitmap: ImageBitmap, picked: Boolean, scheme: Scheme, onTap: () -> Unit) {
    Column(
        Modifier
            .semantics { selected = picked }
            .tapeClick(label = "GENERATION ${index + 1}", onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .border(
                    if (picked) 2.dp else 1.dp,
                    if (picked) scheme.amber.tape else scheme.grayEdge.tape,
                    RoundedCornerShape(4.dp),
                ),
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        TapeText(
            "${index + 1}",
            TapeType.pixelSmall,
            if (picked) scheme.amber.tape else scheme.ink3.tape,
            maxLines = 1,
        )
    }
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

/**
 * HORIZON / PLUMB / ORBIT / DRAW: which line is the cycle. The three
 * photo chips select; the DRAW chip opens the surface ([DrawOverlay])
 * and reads SELECTED while the drawn line is the one playing. Same chip
 * as SYNTH's voice picker.
 */
@Composable
private fun LinePicker(current: SnapVoice, scheme: Scheme, onSelect: (SnapVoice) -> Unit, onDraw: () -> Unit) {
    val color = Schemes.classColor(DrumClass.TONAL).tape
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (v in SnapVoice.entries) {
            val selected = v == current
            val label = if (v == SnapVoice.DRAWN) "DRAW" else v.name
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                    .semantics { this.selected = selected }
                    .tapeClick(label = if (v == SnapVoice.DRAWN) "DRAW THE LINE" else "LINE ${v.name}") {
                        if (v == SnapVoice.DRAWN) onDraw() else onSelect(v)
                    }
                    .padding(horizontal = 2.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TapeText(label, TapeType.pixelSmall, color, maxLines = 1)
                TapeText(
                    when (v) {
                        SnapVoice.HORIZON -> "ACROSS"
                        SnapVoice.PLUMB -> "DOWN"
                        SnapVoice.ORBIT -> "AROUND"
                        SnapVoice.DRAWN -> "YOURS"
                    },
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------- DRAW: the oscillator you draw ----------

private enum class DrawTab { WAVE, SHAPE, PATH }

private val PATH_STEP_CHOICES = listOf(8, 16, 32, 64)

/**
 * The drawing surface, over the whole screen like the slot chooser: a
 * WAVE tab for the cycle (256 points, the seam blended by the engine so a
 * line that ends elsewhere than it started does not click), a SHAPE tab
 * for the volume over the note (64 points; DECAY then sets only the
 * length), and — over a photo — a PATH tab that walks the picture in
 * time instead of drawing a sound directly. A finger draws, starting
 * shapes are a tap away, SMOOTH takes the shake out, and every WAVE/SHAPE
 * change re-renders and plays through [onAudition] so the shape is heard
 * as it is drawn — the same debounce as the sliders.
 *
 * Drafts live here and are committed whole by DONE: the wave only if it
 * was drawn on or a starting shape was picked (a visit to set the SHAPE
 * alone leaves the photo's line as the photo's), the shape only if one
 * was drawn (CLEAR on the SHAPE tab hands the volume back to SNAP's own
 * decay). CANCEL and back leave everything as it was.
 *
 * PATH commits nothing through DONE: LOOP PAD ▸ and RING ▸ land the
 * walked cells directly (see [onPathLoopPad]/[onPathRing]), the same way
 * KIT ▸ on the main screen leaves through its own callback rather than a
 * draft.
 */
@Composable
private fun DrawOverlay(
    startTable: IntArray,
    startEnvelope: IntArray?,
    /** The last committed drawing, for LAST; null before any. */
    lastDrawn: IntArray?,
    macros: Map<String, Float>,
    scheme: Scheme,
    onAudition: (Snip) -> Unit,
    onToast: (String) -> Unit,
    onDone: (table: IntArray, waveDrawn: Boolean, envelope: IntArray?) -> Unit,
    onCancel: () -> Unit,
    /** PATH's own backdrop; the tab is hidden without one — a path walks a picture, never a blank line. */
    photo: Photo?,
    thumb: Bitmap?,
    /** The open kit's tempo (or SNAP's own fallback): PATH's step grid. */
    bpm: Float,
    /** Whether a kit is open to land on — both PATH landings need pads to name or fill. */
    kitOpen: Boolean,
    /** LOOP PAD ▸: the walked cells and the tempo they were sampled at, for the caller's own slot chooser. */
    onPathLoopPad: (cells: List<Pair<Int, Int>>, bpm: Float) -> Unit,
    /** RING ▸: the walked cells and the tempo, landed as a fresh ORBIT ring. */
    onPathRing: (cells: List<Pair<Int, Int>>, bpm: Float) -> Unit,
) {
    val tabs = if (photo != null) DrawTab.entries.toList() else listOf(DrawTab.WAVE, DrawTab.SHAPE)
    var tab by remember { mutableStateOf(DrawTab.WAVE) }
    var table by remember { mutableStateOf(startTable) }
    var waveDrawn by remember { mutableStateOf(false) }
    // The SHAPE tab shows the plain fall SNAP's own decay makes until a
    // shape is drawn; only a drawn one is committed.
    var shape by remember { mutableStateOf(startEnvelope ?: Draw.shape(Draw.Shape.FALL)) }
    var shapeDrawn by remember { mutableStateOf(startEnvelope != null) }
    var rendering by remember { mutableStateOf(false) }
    // Counts every change a hand makes here. The audition below keys on
    // it rather than on the arrays, so opening the surface plays nothing
    // (the main screen just played this very sound) and the first sound
    // is the first stroke's.
    var edits by remember { mutableStateOf(0) }
    val color = Schemes.classColor(DrumClass.TONAL).tape

    // PATH: the drawn polyline (0..1 both ways, plain top-down y — the
    // photo's own, not the wave/shape panels' y-up) and the step count
    // it is walked at.
    var pathPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var pathSteps by remember { mutableStateOf(16) }
    val backdrop = remember(thumb) { thumb?.asImageBitmap() }
    fun pathCells() = PhotoPath.cellsFor(PhotoPath.sample(pathPoints, pathSteps))

    // Hear it as it is drawn.
    LaunchedEffect(edits) {
        if (edits == 0) return@LaunchedEffect
        delay(MACRO_DEBOUNCE_MS)
        if (Snap.isFlat(table)) return@LaunchedEffect
        val env = if (shapeDrawn && Draw.opens(shape)) shape else null
        val shimmerJob = launch { delay(RENDER_SHIMMER_DELAY_MS); rendering = true }
        try {
            val rendered = withContext(Dispatchers.Default) { Snap.render(table, macros, env) }
            onAudition(rendered)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SnapScreen", "draw render: failed", e)
            onToast(Copy.RENDER_FAILED)
        } finally {
            shimmerJob.cancel()
            rendering = false
        }
    }

    fun done() {
        if (waveDrawn && Snap.isFlat(table)) {
            onToast(Copy.SNAP_DRAW_FLAT)
            return
        }
        if (shapeDrawn && !Draw.opens(shape)) {
            onToast(Copy.SNAP_SHAPE_SILENT)
            return
        }
        // A shape drawn over nothing (no photo, the blank line) is kept,
        // and the screen says why it is not yet heard.
        if (shapeDrawn && !waveDrawn && Snap.isFlat(table)) onToast(Copy.SNAP_SHAPE_NO_LINE)
        onDone(table, waveDrawn, if (shapeDrawn) shape else null)
    }

    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).pointerInput(Unit) { detectTapGestures { } }.padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapeText(
                    when (tab) {
                        DrawTab.WAVE -> "DRAW — THE WAVE, ONE CYCLE"
                        DrawTab.SHAPE -> "DRAW — THE VOLUME, ONE NOTE"
                        DrawTab.PATH -> "DRAW — A PATH THROUGH THE PICTURE"
                    },
                    TapeType.lcdSmall,
                    scheme.lcdInk.tape,
                    Modifier.weight(1f),
                )
                if (rendering) TapeText("RENDERING…", TapeType.lcdSmall, scheme.amber.tape)
            }

            // WAVE | SHAPE | PATH (the last only over a photo)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in tabs) {
                    val selected = t == tab
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                            .semantics { this.selected = selected }
                            .tapeClick(label = "DRAW ${t.name}") { tab = t },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(
                            if (t == DrawTab.SHAPE && shapeDrawn) "SHAPE · DRAWN" else t.name,
                            TapeType.pixel,
                            if (selected) scheme.ink.tape else scheme.ink2.tape,
                        )
                    }
                }
            }

            when (tab) {
                DrawTab.WAVE -> DrawLcd(
                    points = table,
                    circular = true,
                    color = color,
                    scheme = scheme,
                    description = "DRAW THE WAVE",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { x0, y0, x1, y1 ->
                    table = Draw.stroke(table, x0, y0, x1, y1)
                    waveDrawn = true
                    edits++
                }
                DrawTab.SHAPE -> DrawLcd(
                    points = shape,
                    circular = false,
                    color = color,
                    scheme = scheme,
                    description = "DRAW THE VOLUME",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { x0, y0, x1, y1 ->
                    shape = Draw.stroke(shape, x0, y0, x1, y1)
                    shapeDrawn = true
                    edits++
                }
                DrawTab.PATH -> PathLcd(
                    backdrop = backdrop,
                    points = pathPoints,
                    color = color,
                    scheme = scheme,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onStart = { pathPoints = emptyList() },
                    onPoint = { x, y -> pathPoints = pathPoints + (x to y) },
                )
            }

            if (tab == DrawTab.PATH) {
                // STEPS: how many points the walk resamples down to.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (n in PATH_STEP_CHOICES) {
                        val selected = n == pathSteps
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                                .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                                .semantics { this.selected = selected }
                                .tapeClick(label = "$n STEPS") { pathSteps = n },
                            contentAlignment = Alignment.Center,
                        ) {
                            TapeText("$n", TapeType.pixel, if (selected) scheme.ink.tape else scheme.ink2.tape)
                        }
                    }
                }
            } else {
                // Starting shapes: draw over one rather than from nothing.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (tab) {
                        DrawTab.WAVE -> for (w in Draw.Wave.entries) {
                            LabButton(w.name, scheme, enabled = true, modifier = Modifier.weight(1f)) {
                                table = Draw.wave(w)
                                waveDrawn = true
                                edits++
                            }
                        }
                        DrawTab.SHAPE -> for (sh in Draw.Shape.entries) {
                            LabButton(sh.name, scheme, enabled = true, modifier = Modifier.weight(1f)) {
                                shape = Draw.shape(sh)
                                shapeDrawn = true
                                edits++
                            }
                        }
                        DrawTab.PATH -> {}
                    }
                }
            }

            if (tab == DrawTab.PATH) {
                val hasPath = pathPoints.size >= 2
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabButton("CLEAR", scheme, enabled = pathPoints.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        pathPoints = emptyList()
                    }
                    LabButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                    LabButton(
                        "LOOP PAD ▸",
                        scheme,
                        enabled = kitOpen && hasPath,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "PATH LOOP PAD",
                    ) {
                        onPathLoopPad(pathCells(), bpm)
                    }
                    LabButton(
                        "RING ▸",
                        scheme,
                        enabled = kitOpen && hasPath,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "PATH RING",
                    ) {
                        onPathRing(pathCells(), bpm)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabButton("SMOOTH", scheme, enabled = true, modifier = Modifier.weight(1f)) {
                        when (tab) {
                            DrawTab.WAVE -> { table = Draw.smooth(table, circular = true); waveDrawn = true }
                            DrawTab.SHAPE -> { shape = Draw.smooth(shape, circular = false); shapeDrawn = true }
                            DrawTab.PATH -> {}
                        }
                        edits++
                    }
                    LabButton("CLEAR", scheme, enabled = true, modifier = Modifier.weight(1f)) {
                        when (tab) {
                            DrawTab.WAVE -> { table = Draw.blank(); waveDrawn = true }
                            // Back to SNAP's own decay: shown as the fall it is,
                            // committed as nothing.
                            DrawTab.SHAPE -> { shape = Draw.shape(Draw.Shape.FALL); shapeDrawn = false }
                            DrawTab.PATH -> {}
                        }
                        edits++
                    }
                    // The last drawing back on the WAVE panel — the way back
                    // to a line a photo chip replaced.
                    LabButton("LAST", scheme, enabled = tab == DrawTab.WAVE && lastDrawn != null, modifier = Modifier.weight(1f)) {
                        lastDrawn?.let {
                            table = it
                            waveDrawn = true
                            edits++
                        }
                    }
                    LabButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                    LabButton("DONE", scheme, enabled = true, modifier = Modifier.weight(1f)) { done() }
                }
            }
        }
    }
}

/**
 * The drawing panel: the points as a line, a rest line through the
 * middle (WAVE) or along the floor (SHAPE), and a finger that draws.
 * Touch positions become 0..1 across and 0..1 **up**, and each move is
 * one [Draw.stroke] from the last position, so a fast finger leaves a
 * line, not dots. Invisible to the accessibility tree except as a named
 * canvas: a drawn waveform has no spoken equivalent, and the starting
 * shapes beneath it are the reachable way to a sound.
 */
@Composable
private fun DrawLcd(
    points: IntArray,
    circular: Boolean,
    color: androidx.compose.ui.graphics.Color,
    scheme: Scheme,
    description: String,
    modifier: Modifier = Modifier,
    onStroke: (x0: Float, y0: Float, x1: Float, y1: Float) -> Unit,
) {
    val currentOnStroke by androidx.compose.runtime.rememberUpdatedState(onStroke)
    Canvas(
        modifier
            .lcdPanel(scheme)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A panel of no size has nowhere to draw: a touch
                    // measured against it would be zero over zero.
                    if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
                    fun norm(p: Offset) = (p.x / size.width.toFloat()) to (1f - p.y / size.height.toFloat())
                    var (px, py) = norm(down.position)
                    currentOnStroke(px, py, px, py)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val (nx, ny) = norm(change.position)
                        currentOnStroke(px, py, nx, ny)
                        px = nx
                        py = ny
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val restStroke = 1.dp.toPx()
        // The floor line sits just inside the panel, not half off its edge.
        val rest = if (circular) h * (1f - Draw.REST / 255f) else h - restStroke / 2f
        drawLine(scheme.lcdInk.tape.copy(alpha = 0.3f), Offset(0f, rest), Offset(w, rest), restStroke)
        val path = Path()
        val n = points.size
        for (i in 0 until n) {
            // Point i is drawn where the pen would put it: the centre of
            // the column Draw.stroke maps a touch at that x onto (floor of
            // x times n), so the line and the finger agree to the pixel.
            val x = w * (i + 0.5f) / n
            val y = h * (1f - points[i] / 255f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * The PATH panel: the photo dimmed underneath ([GrainFieldScreen]'s own
 * backdrop, `alpha = 0.6f`) and the drawn polyline over it, plain
 * top-down 0..1 both ways — the picture's own coordinates, unlike
 * [DrawLcd]'s y-up wave/shape convention, since a path is walked over a
 * picture, not read as a signal. A touch-down starts a fresh path
 * ([onStart]); every move afterwards is one more point ([onPoint]), so a
 * lingering finger leaves many points close together — exactly the
 * "lingers in one cell" case [PhotoPath.sample] is built to preserve.
 */
@Composable
private fun PathLcd(
    backdrop: ImageBitmap?,
    points: List<Pair<Float, Float>>,
    color: androidx.compose.ui.graphics.Color,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onPoint: (x: Float, y: Float) -> Unit,
) {
    val currentOnStart by androidx.compose.runtime.rememberUpdatedState(onStart)
    val currentOnPoint by androidx.compose.runtime.rememberUpdatedState(onPoint)
    Canvas(
        modifier
            .lcdPanel(scheme)
            .semantics { contentDescription = "DRAW THE PATH" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
                    fun norm(p: Offset) = (p.x / size.width.toFloat()).coerceIn(0f, 1f) to (p.y / size.height.toFloat()).coerceIn(0f, 1f)
                    currentOnStart()
                    val (x0, y0) = norm(down.position)
                    currentOnPoint(x0, y0)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val (nx, ny) = norm(change.position)
                        currentOnPoint(nx, ny)
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        if (backdrop != null && w > 0f && h > 0f) {
            drawImage(backdrop, dstSize = IntSize(w.toInt(), h.toInt()), alpha = 0.6f)
        }
        if (points.size >= 2) {
            val path = Path()
            points.forEachIndexed { i, (x, y) ->
                val px = w * x
                val py = h * y
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        } else if (points.size == 1) {
            val (x, y) = points[0]
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(w * x, h * y))
        }
    }
}

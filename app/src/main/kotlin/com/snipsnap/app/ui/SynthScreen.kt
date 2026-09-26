package com.snipsnap.app.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.LoopWrites
import com.snipsnap.app.deviceSampleRate
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.BinRedGlow
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.OneNote
import com.snipsnap.json.JsonValue
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.loop.SessionStore
import com.snipsnap.shell.Ages
import com.snipsnap.shell.Copy
import com.snipsnap.shell.DroneMaker
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.ResinPadMaker
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.Spread
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Patch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Fathom
import com.snipsnap.synth.FathomPatch
import com.snipsnap.synth.FathomVoice
import com.snipsnap.synth.Resin
import com.snipsnap.synth.ResinDrone
import com.snipsnap.synth.ResinPatch
import com.snipsnap.synth.ResinVoice
import com.snipsnap.synth.Tide
import com.snipsnap.synth.TidePatch
import com.snipsnap.synth.TideVoice
import com.snipsnap.synth.Pluck
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckVoice
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Skin
import com.snipsnap.synth.SkinPatch
import com.snipsnap.synth.SkinVoice
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.synth.Tines
import com.snipsnap.synth.TinesPatch
import com.snipsnap.synth.TinesVoice
import com.snipsnap.synth.Tonewheel
import com.snipsnap.synth.TonewheelPatch
import com.snipsnap.synth.TonewheelVoice
import com.snipsnap.synth.Velvet
import com.snipsnap.synth.VelvetPatch
import com.snipsnap.synth.VelvetVoice
import com.snipsnap.synth.Vox
import com.snipsnap.synth.VoxPatch
import com.snipsnap.synth.VoxVoice
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How long a macro/voice/SCRAMBLE change waits, quiet, before it actually
 * re-renders — `prototype/thumplab.html`'s own `touched()` debounces at
 * 70ms; the brief calls for ~100ms so a slider drag never renders 60×/s.
 */
private const val MACRO_DEBOUNCE_MS = 100L

/**
 * How long a render has to still be running before the SCOPE shows a busy
 * shimmer. Most renders finish well under this — a raced delayed reveal
 * means the common case never flashes it.
 */
private const val RENDER_SHIMMER_DELAY_MS = 150L

/**
 * SYNTH — the ten-engine drum/tonal-synthesis lab: pick an engine, pick a
 * voice, shape it with macro sliders, SCRAMBLE it, watch the scope, audition
 * it, and land it on a pad. `synth/` is the tested engine layer; this is the
 * Compose surface plus the SEND TO PAD action, multiplexed over all ten
 * registered engines via the file-private [Engine] adapter below.
 *
 * `prototype/thumplab.html` is the interaction truth this ports: every
 * macro/voice/SCRAMBLE change re-renders and retrigger-plays the audition
 * voice (its own on-screen label says so — "EVERY MOVE RE-RENDERS +
 * RETRIGGERS"), debounced so a drag doesn't hammer the DSP. `design/
 * HANDOFF.md`'s SYNTH row says "5 voices" — that's roadmap-era and THUMP-
 * only; reality wins: THUMP alone ships eight voices, and nine more engines
 * (SKIN, TINES, VELVET, VOX, PLUCK, TONEWHEEL, FATHOM, RESIN, TIDE) join it here.
 * GRAINS is out of scope — it has no voice enum, a different shape entirely.
 *
 * One copy carve-out remains: SCRAMBLE has no toast (the prototype's
 * `SCRAMBLE_LINES` are prototype-only flavour, never ported to `Copy`).
 * SEND TO PAD's own landing line used to be a second carve-out — a plain
 * inline sentence, because neither `Copy.treated` nor `Copy.INSTRUMENT_MADE`
 * is semantically "a synth patch landed on this pad" — but a copy-
 * consolidation pass gave it its own line, `Copy.synthSent`, rather than
 * leaving it outside every law in `PersonalityTest`.
 */
@Composable
fun SynthScreen(
    entry: KitShelf.Entry?,
    // The shelf's own root: SAVE AS PRESET (docs/WORKSHOP.md, WS5) keeps
    // the player's presets in one file beside the kits, not in the open
    // kit — a preset is for every kit, and it is saved with no kit open.
    shelfRoot: File,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
    // MAKE INSTRUMENT (RESIN, held) writes beside the kits; App's instrument
    // list is not reactive to that folder, so this tells it to re-read -
    // PadSheetScreen's own parameter of the same name, for the same reason.
    onShelfAssetWritten: () -> Unit,
    // DRONE TO LOOP (RESIN): the drone's recipe on its root, handed to App,
    // whose sendSnipToLoop already owns the grid's sidecar and its one writer.
    onDroneToLoop: (name: String, recipe: JsonValue, rootMidi: Int) -> Unit,
    // App()'s own scope — the same one PadSheetScreen/PadCaptureScreen
    // receive as their own `appScope` — so a SEND TO PAD write in flight
    // survives a MenuRow tab switch instead of being cancelled by it (see
    // state-findings.md #3: this screen used to take its own locally-scoped
    // `rememberCoroutineScope()` for exactly this write, unlike every
    // sibling overlay).
    appScope: CoroutineScope,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf(Engine.THUMP) }
    var voice by remember { mutableStateOf<Enum<*>>(ThumpVoice.KICK) }
    // Per-(engine, voice) macro state, seeded with factory defaults and kept
    // for the life of the screen: switching engine or voice (or coming back
    // to one) restores whatever was last touched on it, same as the
    // prototype's `state.macros` map — not a fresh set of defaults every
    // time. Populated eagerly for every (engine, voice) pair up front, same
    // idiom the THUMP-only screen used for its own eight voices — cheap
    // (27 entries total across all six engines) and means no read site ever
    // has to defend against a missing key.
    val macrosByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, Map<String, Float>>().apply {
            for (e in Engine.entries) {
                for (v in e.voices()) put(e to v, e.defaults(v))
            }
        }
    }
    val macros = macrosByVoice.getValue(engine to voice)
    // Which preset (if any) the current macro values for this (engine,
    // voice) still match — U1's own framing (`docs/SYNTH_UPGRADE.md`) is
    // "a starting point to wreck, not a locked sound", so this clears the
    // moment a slider or SCRAMBLE moves the macros away from what was
    // loaded, rather than keep highlighting a name that no longer
    // describes the sound. Not pre-seeded like [macrosByVoice] — nothing
    // is "loaded" until a tap picks one.
    val currentPresetByVoice = remember { mutableStateMapOf<Pair<Engine, Enum<*>>, String>() }
    // The prototype gates its very first sound behind a "TAP TO POWER ON"
    // veil — a Web Audio autoplay-policy workaround, not part of the actual
    // interaction — and only *after* that makes every move retrigger. This
    // is the Android equivalent: the scope still renders and draws the
    // instant the screen opens, but nothing is heard until the first real
    // touch (a slider drag, a voice pick, an engine cycle, SCRAMBLE), so
    // landing on SYNTH never plays a kick unasked.
    var touched by remember { mutableStateOf(false) }
    fun updateMacro(name: String, value: Float) {
        touched = true
        currentPresetByVoice.remove(engine to voice)
        macrosByVoice[engine to voice] = macrosByVoice.getValue(engine to voice) + (name to value)
    }
    fun loadPreset(patch: Patch) {
        touched = true
        // Merged over the engine's own defaults, same as macrosByVoice's own
        // seeding above: a preset only has to name the macros it cares
        // about (PUNCH, added after every existing THUMP preset was
        // written, names none of them), and every read site here relies on
        // that "no missing key" invariant - a bare `patch.macros` would
        // otherwise leave PUNCH's slider crashing on getValue the moment
        // someone loaded a pre-PUNCH preset.
        macrosByVoice[engine to voice] = engine.defaults(voice) + patch.macros
        currentPresetByVoice[engine to voice] = patch.name
    }

    // ---- SAVE AS PRESET (docs/WORKSHOP.md, WS5) ----
    // The player's own presets, every engine and voice at once, read off
    // the shelf when the screen opens and again after every save. A file
    // this build cannot read lists nothing here, and its save refuses in
    // words rather than writing over it (UserPresets.save's own rule).
    var userPresets by remember { mutableStateOf<List<UserPresets.Saved>>(emptyList()) }
    // FORGET → BIN, and the bin it waits in (the WS5 follow-up): read
    // beside the strip, since a held chip and DELETED PRESETS both need
    // it, and refreshed with the strip after every move.
    var binnedPresets by remember { mutableStateOf<List<UserPresets.Binned>>(emptyList()) }
    LaunchedEffect(Unit) {
        try {
            val (live, bin) = withContext(Dispatchers.IO) { UserPresets.read(shelfRoot) to UserPresets.bin(shelfRoot) }
            userPresets = live
            binnedPresets = bin
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SynthScreen", "presets: unreadable", e)
        }
    }
    var namingPreset by remember { mutableStateOf(false) }
    var saveBusy by remember { mutableStateOf(false) }
    // The chip being held, while its FORGET slip asks; the bin's door; and
    // one busy flag for both moves, so a second tap never races the file.
    var forgetTarget by remember { mutableStateOf<Patch?>(null) }
    var binOpen by remember { mutableStateOf(false) }
    var binBusy by remember { mutableStateOf(false) }
    fun forgetPreset(patch: Patch) {
        if (binBusy) return
        binBusy = true
        // The strip the chip was held on, captured now: the highlight there
        // must not keep naming a chip that is gone, whatever voice is
        // showing by the time the write lands.
        val held = engine to voice
        appScope.launch {
            try {
                val gone = withContext(Dispatchers.IO) {
                    UserPresets.forget(shelfRoot, patch.engine, patch.voiceName, patch.name, System.currentTimeMillis())
                }
                val (live, bin) = withContext(Dispatchers.IO) { UserPresets.read(shelfRoot) to UserPresets.bin(shelfRoot) }
                userPresets = live
                binnedPresets = bin
                if (gone == null) {
                    // A row that outran the tap: nothing by that name was there.
                    onToast(Copy.BIN_ITEM_GONE)
                } else {
                    if (currentPresetByVoice[held] == patch.name) currentPresetByVoice.remove(held)
                    onToast(Copy.presetForgotten(gone.saved.name))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "forgetPreset: failed", e)
                onToast(Copy.PRESET_FORGET_FAILED)
            } finally {
                binBusy = false
            }
        }
    }
    fun restorePreset(binned: UserPresets.Binned) {
        if (binBusy) return
        binBusy = true
        appScope.launch {
            try {
                val back = withContext(Dispatchers.IO) { UserPresets.unforget(shelfRoot, binned) }
                val (live, bin) = withContext(Dispatchers.IO) { UserPresets.read(shelfRoot) to UserPresets.bin(shelfRoot) }
                userPresets = live
                binnedPresets = bin
                // The name it actually landed under — a clash with a preset
                // saved since may have freshened it — never the row's own.
                onToast(if (back == null) Copy.BIN_ITEM_GONE else Copy.presetRestored(back.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "restorePreset: failed", e)
                onToast(Copy.PRESET_RESTORE_FAILED)
            } finally {
                binBusy = false
            }
        }
    }
    fun savePreset(check: UserPresets.Check) {
        val name = when (check) {
            is UserPresets.Check.Fresh -> check.name
            is UserPresets.Check.Replaces -> check.name
            else -> return
        }
        if (saveBusy) return
        saveBusy = true
        namingPreset = false
        // Captured now: the dialog's own engine/voice/macros, before an
        // engine cycle or a slider drag can move them under the write.
        val savedEngine = engine
        val savedVoice = voice
        val patch = engine.buildPatch(name, voice, macros)
        appScope.launch {
            try {
                val (saved, all) = withContext(Dispatchers.IO) {
                    val s = UserPresets.save(shelfRoot, patch, System.currentTimeMillis())
                    s to UserPresets.read(shelfRoot)
                }
                userPresets = all
                currentPresetByVoice[savedEngine to savedVoice] = saved.name
                onToast(if (check is UserPresets.Check.Replaces) Copy.presetReplaced(name) else Copy.presetSaved(name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "savePreset: failed", e)
                onToast(Copy.PRESET_SAVE_FAILED)
            } finally {
                saveBusy = false
            }
        }
    }

    var snip by remember { mutableStateOf<Snip?>(null) }
    var rendering by remember { mutableStateOf(false) }

    var voicePlayer by remember { mutableStateOf<TapeVoice?>(null) }
    DisposableEffect(Unit) { onDispose { voicePlayer?.release() } }
    // Backgrounding mid-audition must stop the voice, not wait for this
    // composable to next leave composition (PadSheetScreen's own fix).
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
        // Signal-only at the swap site: `stop()` is a non-blocking flag flip
        // — the old voice's own stream thread releases its own AudioTrack on
        // its way out (TapeVoice's per-thread-owns-its-track contract) — so
        // this never blocks the render loop on a 200ms join during a drag or
        // a rapid macro-change cycle. Reassigning `voicePlayer` to the new
        // instance below is what drops the old reference; full `release()`
        // (which does join, bounded) is reserved for ON_STOP and teardown,
        // where a one-time bounded wait is the documented intent.
        voicePlayer?.stop()
        val mono = Cleanup.toMono(target)
        val v = TapeVoice(mono.samples, mono.sampleRate)
        voicePlayer = v
        v.start(0)
    }

    // The debounced re-render + retrigger loop: LaunchedEffect's own key
    // change cancels whatever render was in flight and restarts the delay,
    // which is exactly the trailing-edge debounce `touched()` does by hand
    // with clearTimeout/setTimeout in the prototype. Keyed on `engine` too
    // now — switching engines must cancel an in-flight render exactly like
    // switching voices always has.
    LaunchedEffect(engine, voice, macros) {
        delay(MACRO_DEBOUNCE_MS)
        val shimmerJob = launch { delay(RENDER_SHIMMER_DELAY_MS); rendering = true }
        try {
            val rendered = withContext(Dispatchers.Default) { engine.render(voice, macros) }
            snip = rendered
            if (touched) audition(rendered)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SynthScreen", "render: failed", e)
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

    fun sendToSlot(slot: Int) {
        val e = entry ?: return
        if (sendBusy) return
        sendBusy = true
        appScope.launch {
            try {
                // The patch's own init validates its macros (Patches.
                // validateMacros) — practically unreachable given every
                // value here already came from a 0..1 slider or SCRAMBLE,
                // but constructing it inside the try means an unexpected
                // failure toasts honestly instead of crashing the screen.
                val name = engine.patchDisplayName(voice)
                val patch = engine.buildPatch(name, voice, macros)
                val recipe = PadRecipe(patch = patch).toJsonValue()
                val cls = engine.drumClass(voice)
                val (existed, updatedKit) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        val rendered = patch.render()
                        val alreadyThere = model.pad(slot) != null
                        if (alreadyThere) {
                            // REPLACE: `replaceAudio` only rewrites the sample +
                            // recipe of a pad that already exists — it leaves
                            // displayName/drumClass/colorHex/muteGroup exactly as
                            // they were, so without this follow-up `update` the
                            // pad's identity would still say (and choke-group
                            // with) whatever it was before, while sounding like
                            // the new voice.
                            model.replaceAudio(slot, recipe) { _ -> rendered }
                            model.update(slot) { p ->
                                p.copy(
                                    displayName = name,
                                    drumClass = cls,
                                    colorHex = AutoPlace.colorFor(cls),
                                    muteGroup = AutoPlace.muteGroupFor(cls),
                                )
                            }
                        } else {
                            // ADD: `assign` places new audio on an empty slot —
                            // it derives displayName/colorHex/muteGroup itself,
                            // but has no notion of a synth recipe, so the recipe
                            // rides a follow-up `update` (its guards permit a
                            // recipe-only edit; slot/sampleFile stay put).
                            model.assign(slot, rendered, cls, name)
                            model.update(slot) { p -> p.copy(recipe = recipe) }
                        }
                        model.save()
                        alreadyThere to model.kit
                    }
                }
                showChooser = false
                onKitUpdated(updatedKit)
                // Copy.synthSent: only the REPLACE branch bins anything —
                // `replaceAudio` moves the displaced WAV to the bin
                // (`moveToBin`), the same fact `Copy.treated`'s "ORIGINAL
                // SLEEPS IN THE BIN" states for TREATMENT — `assign`'s own
                // `deleteIfUnreferenced` is a no-op on an empty slot (there
                // was no original), so the ADD branch doesn't claim it.
                onToast(Copy.synthSent(padTag(slot), name, replaced = existed))
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                if (ex is IllegalStateException || ex is IllegalArgumentException) {
                    // Same race as SPLIT/SURFACE's own → PAD landing (the kit
                    // changed under the chooser - a layered or chained pad):
                    // the exception's own text is KitBuilder's internal "no
                    // pad on slot N", not user copy, so it stays out of the
                    // toast, same as Copy.PRINT_PAD_REFUSED's own reasoning.
                    onToast(Copy.SYNTH_PAD_REFUSED)
                } else {
                    Log.e("SynthScreen", "sendToSlot: failed", ex)
                    onToast(Copy.SEND_FAILED)
                }
            } finally {
                sendBusy = false
            }
        }
    }

    // ---- MAKE INSTRUMENT: RESIN, held ----
    // docs/superpowers/specs/2026-09-25-resin-held-pad-design.md. Nine held
    // zones render off the main thread, in parallel, with a progress bar
    // the whole way (the author's call: a slow phone shows work, it does not
    // drop zones); the package is written once every zone has landed, so a
    // CANCEL mid-render leaves nothing on the shelf.
    var makingInstrument by remember { mutableStateOf(false) }
    var holdAttack by remember { mutableStateOf(ResinPadMaker.ATTACK.defaultFraction) }
    var holdRelease by remember { mutableStateOf(ResinPadMaker.RELEASE.defaultFraction) }
    // Zones landed during MAKE, of [holdTotal]; -1 when no MAKE is running.
    var holdDone by remember { mutableStateOf(-1) }
    var holdTotal by remember { mutableStateOf(0) }
    var holdPreviewing by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    // The name MAKE will land under, read off the shelf when the sheet opens
    // so the sheet can say it; MAKE asks the shelf again at write time.
    var holdName by remember { mutableStateOf("") }
    val holdBase = currentPresetByVoice[engine to voice] ?: "RESIN ${voice.name}"
    val instrumentsDir = File(shelfRoot, KitShelf.INSTRUMENTS_DIR)
    LaunchedEffect(makingInstrument, holdBase) {
        if (!makingInstrument) return@LaunchedEffect
        holdName = try {
            withContext(Dispatchers.IO) { OneNote.freshName(instrumentsDir, holdBase) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SynthScreen", "holdName: shelf unreadable", e)
            holdBase
        }
    }

    fun previewHeld() {
        if (engine != Engine.RESIN || holdDone >= 0 || holdPreviewing) return
        val spec = ResinPadMaker.spec(voice as ResinVoice, macros, holdAttack, holdRelease)
        holdPreviewing = true
        holdJob = appScope.launch {
            try {
                val heard = withContext(Dispatchers.Default) { ResinPadMaker.preview(spec) }
                audition(heard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "previewHeld: failed", e)
                onToast(Copy.RENDER_FAILED)
            } finally {
                holdPreviewing = false
                holdJob = null
            }
        }
    }

    fun makeHeld() {
        if (engine != Engine.RESIN || holdDone >= 0 || holdPreviewing) return
        // Captured now: the sheet's own sound, before anything can move it.
        val spec = ResinPadMaker.spec(voice as ResinVoice, macros, holdAttack, holdRelease)
        val base = holdBase
        val midis = ResinPadMaker.zoneMidis(spec)
        holdTotal = midis.size
        holdDone = 0
        holdJob = appScope.launch {
            try {
                // coroutineScope, not bare asyncs on appScope: a zone that
                // throws must surface here as an exception this catch sees,
                // not fail the app's own scope.
                val notes = coroutineScope {
                    midis.map { midi ->
                        async(Dispatchers.Default) {
                            val note = ResinPadMaker.renderZone(spec, midi)
                            withContext(Dispatchers.Main) { holdDone += 1 }
                            note
                        }
                    }.awaitAll()
                }
                // Past here the write lands even under CANCEL: a package half
                // written is worse than a whole one the player can bin.
                val made = withContext(NonCancellable + Dispatchers.IO) {
                    val name = OneNote.freshName(instrumentsDir, base)
                    ResinPadMaker.export(name, spec, notes, instrumentsDir)
                    name
                }
                makingInstrument = false
                onShelfAssetWritten()
                onToast(Copy.madeNamed("INSTRUMENT", made))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "makeHeld: failed", e)
                onToast(Copy.actionFailed("INSTRUMENT"))
            } finally {
                holdDone = -1
                holdJob = null
            }
        }
    }

    fun closeHeld() {
        // Every zone rendered means the write is under way; let it land.
        if (holdDone >= 0 && holdDone >= holdTotal) return
        holdJob?.cancel()
        holdJob = null
        holdDone = -1
        holdPreviewing = false
        makingInstrument = false
    }

    // ---- DRONE TO LOOP: RESIN, droning ----
    // docs/superpowers/specs/2026-09-25-resin-drone-design.md. SEND puts a
    // recipe on the grid, not audio: LOOP renders it at its own tempo, so
    // nothing here renders except PREVIEW, which renders it the way LOOP
    // will (the grid's tempo, bars and the device's rate) and plays it
    // twice, so the wrap is heard.
    val context = LocalContext.current
    var droneOpen by remember { mutableStateOf(false) }
    var droneRoot by remember { mutableStateOf<Int?>(null) }
    var droneMotion by remember { mutableStateOf(DroneMaker.DEFAULT_MOTION) }
    var droneRate by remember { mutableStateOf(DroneMaker.DEFAULT_RATE) }
    var dronePreviewing by remember { mutableStateOf(false) }
    var droneJob by remember { mutableStateOf<Job?>(null) }
    // The grid the drone will land on, read when the sheet opens: its tempo
    // and bars decide the span the readout names and PREVIEW renders.
    var droneSession by remember { mutableStateOf<Session?>(null) }
    LaunchedEffect(droneOpen, voice) {
        if (!droneOpen || engine != Engine.RESIN) return@LaunchedEffect
        val v = voice as? ResinVoice ?: return@LaunchedEffect
        if (droneRoot?.let { it in DroneMaker.roots(v) } != true) droneRoot = DroneMaker.defaultRoot(v, kit?.key)
        droneSession = withContext(Dispatchers.IO) {
            val rate = deviceSampleRate(context)
            val dir = LoopWrites.dir(context)
            runCatching { SessionStore.load(dir) }.getOrNull()?.copy(sampleRate = rate)
                ?: SessionBuilder.empty(rate)
        }
    }

    fun droneSpec() = DroneMaker.spec(voice as ResinVoice, macros, droneMotion, droneRate)

    fun previewDrone() {
        val root = droneRoot ?: return
        val session = droneSession ?: return
        if (engine != Engine.RESIN || dronePreviewing) return
        val spec = droneSpec()
        dronePreviewing = true
        droneJob = appScope.launch {
            try {
                val heard = withContext(Dispatchers.Default) {
                    val once = DroneMaker.render(spec, root, session)
                    Snip(FloatArray(once.size * 2) { once[it % once.size] }, 1, session.sampleRate)
                }
                audition(heard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "previewDrone: failed", e)
                onToast(Copy.RENDER_FAILED)
            } finally {
                dronePreviewing = false
                droneJob = null
            }
        }
    }

    fun sendDrone() {
        val root = droneRoot ?: return
        if (engine != Engine.RESIN) return
        val name = "${currentPresetByVoice[engine to voice] ?: "RESIN ${voice.name}"} DRONE"
        onDroneToLoop(name, droneSpec().toJson(), root)
        droneJob?.cancel()
        droneJob = null
        dronePreviewing = false
        droneOpen = false
    }

    fun closeDrone() {
        droneJob?.cancel()
        droneJob = null
        dronePreviewing = false
        droneOpen = false
    }

    // ---- SPREAD ----
    // The patch as it stood when SPREAD opened, rendered once: the panel
    // previews and the write lands the same audio its pitch was measured on.
    var spreadSource by remember { mutableStateOf<SpreadSource?>(null) }
    var spreadPatch by remember { mutableStateOf<Patch?>(null) }
    var spreadClass by remember { mutableStateOf(DrumClass.TONAL) }
    var spreadOpening by remember { mutableStateOf(false) }
    var spreadBusy by remember { mutableStateOf(false) }

    fun openSpread() {
        if (spreadOpening || sendBusy || entry == null) return
        spreadOpening = true
        val patch = engine.buildPatch(engine.patchDisplayName(voice), voice, macros)
        val cls = engine.drumClass(voice)
        scope.launch {
            try {
                val src = withContext(Dispatchers.Default) {
                    val rendered = patch.render()
                    val midi = Spread.detect(rendered)
                    SpreadSource(rendered, midi, Spread.defaultRoot(midi))
                }
                spreadPatch = patch
                spreadClass = cls
                spreadSource = src
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "openSpread: render failed", e)
                onToast(Copy.RENDER_FAILED)
            } finally {
                spreadOpening = false
            }
        }
    }

    fun spread(options: Spread.Options) {
        val e = entry ?: return
        val src = spreadSource ?: return
        val patch = spreadPatch ?: return
        if (spreadBusy) return
        spreadBusy = true
        val sound = Spread.Sound(
            name = patch.name,
            drumClass = spreadClass,
            colorHex = AutoPlace.colorFor(spreadClass),
            recipe = PadRecipe(patch = patch).toJsonValue(),
            source = mapOf(Spread.FROM_KEY to "SYNTH"),
        )
        appScope.launch {
            try {
                // Planned again over the kit on disk, under the lock: the
                // panel's preview read App's copy, which a write elsewhere
                // may have moved on since.
                val (plan, updatedKit) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        val plan = Spread.plan(model.kit, src.midi, options)
                        if (plan.notes.isNotEmpty()) {
                            Spread.apply(model, src.snip, plan, sound)
                            model.save()
                        }
                        plan to model.kit
                    }
                }
                if (plan.notes.isNotEmpty()) {
                    spreadSource = null
                    onKitUpdated(updatedKit)
                }
                onToast(Spread.toast(plan))
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Log.e("SynthScreen", "spread: failed", ex)
                onToast(Copy.SPREAD_FAILED)
            } finally {
                spreadBusy = false
            }
        }
    }

    val classColor = Schemes.classColor(engine.drumClass(voice)).tape

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // `Layout.LCD_HEADER_H` (40dp) is less than
                    // `Layout.MIN_HIT_TARGET` (44dp) — every other header in
                    // the app can get away with that because nothing in it
                    // is tappable. This one now has a cycler in it, and the
                    // brief is explicit that the cycler keeps the standing
                    // hit-target rule, so this screen's own header grows to
                    // whichever constant is taller rather than clipping the
                    // cycler's touch target to a shared constant this file
                    // isn't allowed to change. Local, minimal, and it only
                    // affects SYNTH's own header row.
                    .height(max(Layout.LCD_HEADER_H, Layout.MIN_HIT_TARGET).dp)
                    .lcdPanel(scheme)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // ENGINE cycler — tap advances to the next entry, wrapping,
                // on the LCD header's title in place of a static "THUMP"
                // label. Long-press is not used, per the brief.
                //
                // EXPORT's format row used to be the same idiom and is now a
                // picker instead (September UAT, finding 7), because eight
                // one-way states with no back step is a walk. This cycler is
                // deliberately left alone: four engines, and the tap is an
                // audition you want to hear one after another.
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        // States which engine is current and what the tap
                        // does, the same shape as KitsScreen's SORT/SHOW
                        // cyclers (a real word name, not a glyph, but the
                        // TapeText below still doesn't merge into this
                        // node for free).
                        .tapeClick(label = "ENGINE ${engine.name} · TAP FOR NEXT") {
                            touched = true
                            val next = engine.next()
                            engine = next
                            voice = next.voices().first()
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Batch 3, Task 4: no ▸ — the tap cycles to the next
                    // engine in place, same as the cyclers below; it doesn't
                    // navigate or open a panel.
                    TapeText(engine.name, TapeType.lcdHeader, scheme.lcdInk.tape)
                }
                TapeText(chipLabel(engine, voice), TapeType.lcdSmall, scheme.amber.tape)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopeLcd(snip, rendering, scheme, Modifier.fillMaxWidth().height(104.dp))

                VoicePicker(engine, voice, scheme, onSelect = { touched = true; voice = it })

                PresetList(
                    engine = engine,
                    voice = voice,
                    yours = UserPresets.forVoice(userPresets, engine.name, voice.name).map { it.patch },
                    current = currentPresetByVoice[engine to voice],
                    scheme = scheme,
                    onSelect = ::loadPreset,
                    onHold = { forgetTarget = it },
                )
                // The door back, gated on the bin holding something, the way
                // the shelf's DELETED KITS row is: never a door onto an empty
                // room. Every engine's bin, not only this voice's — a preset
                // forgotten on SNARE is found from KICK.
                if (binnedPresets.isNotEmpty()) {
                    LabButton(
                        "DELETED PRESETS ▸ ${binnedPresets.size} WAITING",
                        scheme,
                        enabled = !binBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        binOpen = true
                    }
                }

                val macroSpecs = remember(engine, voice) { engine.macrosFor(voice) }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (spec in macroSpecs) {
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
                        "SCRAMBLE",
                        scheme,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        touched = true
                        currentPresetByVoice.remove(engine to voice)
                        macrosByVoice[engine to voice] = engine.scramble(voice, Random(System.nanoTime()))
                    }
                    // Between SCRAMBLE and SEND TO PAD: the sound is kept
                    // before it is placed. No kit is needed — a preset is
                    // the shelf's, not the open kit's.
                    LabButton(
                        if (saveBusy) "…" else "SAVE PRESET ▸",
                        scheme,
                        enabled = !saveBusy,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "SAVE PRESET",
                    ) {
                        namingPreset = true
                    }
                    LabButton(
                        if (sendBusy) "…" else "SEND TO PAD ▸",
                        scheme,
                        enabled = kit != null && !sendBusy,
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "SEND TO PAD",
                    ) {
                        showChooser = true
                    }
                }
                // One sound, a whole bank of notes: its own row, since the
                // three above already fill theirs at the pixel face.
                LabButton(
                    if (spreadOpening) "…" else "SPREAD ▸ SCALE ACROSS PADS",
                    scheme,
                    enabled = kit != null && !sendBusy && !spreadOpening,
                    modifier = Modifier.fillMaxWidth(),
                    accessibilityLabel = "SPREAD ACROSS PADS",
                ) {
                    openSpread()
                }
                // RESIN, held: the sound as a keys instrument. RESIN only -
                // it is the one engine whose held render closes its loops.
                // Full width under SPREAD's row, the DELETED PRESETS door's shape.
                if (engine == Engine.RESIN) {
                    LabButton(
                        "MAKE INSTRUMENT ▸",
                        scheme,
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                        accessibilityLabel = "MAKE INSTRUMENT",
                    ) {
                        makingInstrument = true
                    }
                    // RESIN, droning: the sound as a breathing loop-grid track.
                    LabButton(
                        "DRONE TO LOOP ▸",
                        scheme,
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                        accessibilityLabel = "DRONE TO LOOP",
                    ) {
                        droneOpen = true
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
            // Innermost: same self-guarded cancel as the overlay's own
            // CANCEL — a send in flight (sendBusy) makes both no-ops.
            BackHandler(onBack = cancelChooser)
        }

        spreadSource?.let { src ->
            // Self-guarded like the chooser's CANCEL: a write in flight
            // keeps the panel up until it lands.
            val cancelSpread = { if (!spreadBusy) spreadSource = null }
            SpreadOverlay(
                kit = kit,
                source = src,
                colorHex = AutoPlace.colorFor(spreadClass),
                scheme = scheme,
                busy = spreadBusy,
                onSpread = ::spread,
                onCancel = cancelSpread,
            )
            BackHandler(onBack = cancelSpread)
        }

        if (namingPreset) {
            val cancelNaming = { namingPreset = false }
            PresetNameDialog(
                engine = engine,
                voice = voice,
                yours = userPresets,
                scheme = scheme,
                onCancel = cancelNaming,
                onSave = ::savePreset,
            )
            BackHandler(onBack = cancelNaming)
        }

        forgetTarget?.let { target ->
            val cancelForget = { forgetTarget = null }
            PresetForgetDialog(
                name = target.name,
                scheme = scheme,
                onCancel = cancelForget,
                onForget = {
                    forgetTarget = null
                    forgetPreset(target)
                },
            )
            BackHandler(onBack = cancelForget)
        }

        if (binOpen) {
            // Same self-guarded close as the chooser's CANCEL: a move in
            // flight makes both no-ops, and the handler stays registered.
            val closeBin = { if (!binBusy) binOpen = false }
            DeletedPresetsOverlay(
                binned = binnedPresets,
                scheme = scheme,
                busy = binBusy,
                onRestore = ::restorePreset,
                onClose = closeBin,
            )
            BackHandler(onBack = closeBin)
        }

        if (makingInstrument && engine == Engine.RESIN) {
            HeldInstrumentSheet(
                heading = "${engine.name} · ${chipLabel(engine, voice)}",
                name = holdName,
                attack = holdAttack,
                release = holdRelease,
                done = holdDone,
                total = holdTotal,
                previewing = holdPreviewing,
                fillColor = classColor,
                scheme = scheme,
                onAttack = { holdAttack = it },
                onRelease = { holdRelease = it },
                onPreview = ::previewHeld,
                onMake = ::makeHeld,
                onCancel = ::closeHeld,
            )
            BackHandler(onBack = ::closeHeld)
        }

        val droneVoice = voice as? ResinVoice
        if (droneOpen && engine == Engine.RESIN && droneVoice != null) {
            DroneSheet(
                heading = "${engine.name} · ${chipLabel(engine, voice)}",
                voice = droneVoice,
                root = droneRoot,
                session = droneSession,
                motion = droneMotion,
                rate = droneRate,
                previewing = dronePreviewing,
                fillColor = classColor,
                scheme = scheme,
                onRoot = { droneRoot = it },
                onMotion = { droneMotion = it },
                onRate = { droneRate = it },
                onPreview = ::previewDrone,
                onSend = ::sendDrone,
                onCancel = ::closeDrone,
            )
            BackHandler(onBack = ::closeDrone)
        }
    }
}

// ---------- FORGET → BIN: the ask, and the bin's door ----------

/**
 * A held YOURS chip asks before anything moves — `KitDeleteConfirmDialog`'s
 * shape, its line [Copy.presetForgetAsk]: what goes, where it waits, for how
 * long. CANCEL beside FORGET → BIN in the bin's red, the same dress every
 * delete in the app wears.
 */
@Composable
private fun PresetForgetDialog(name: String, scheme: Scheme, onCancel: () -> Unit, onForget: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // No descendant text of its own — labelled with the same
            // word the visible CANCEL button below uses.
            .tapeClick(label = "CANCEL", onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                // Swallows the tap so it doesn't fall through to the
                // scrim's CANCEL — a bare gesture detector, which registers
                // no semantics node (MessageBox.kt's pattern).
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText(Copy.presetForgetAsk(name), TapeType.lcdSmall, scheme.ink.tape, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .background(scheme.lcd.tape, RoundedCornerShape(4.dp))
                        .border(2.dp, BIN_RED_BORDER, RoundedCornerShape(4.dp))
                        .tapeClick(label = "FORGET $name", onClick = onForget)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("FORGET → BIN", TapeType.pixel, BinRedGlow)
                }
            }
        }
    }
}

/**
 * DELETED PRESETS: the bin's own listing, `DeletedKitsScreen`'s shape as an
 * overlay of this screen (the `SlotChooserOverlay` frame): one row per
 * forgotten preset — its name, its engine and voice, when it went, the days
 * it has left (the last two in `warn`, as every bin counts down) — and
 * RESTORE. No EMPTY THE BIN NOW, unlike the bins that hold WAVs and kits:
 * a preset is a few hundred bytes, so nothing is bought by emptying early,
 * and every site that cannot be undone is one `ReversalTest` counts. The
 * sweep takes each row when its days run out.
 */
@Composable
private fun DeletedPresetsOverlay(
    binned: List<UserPresets.Binned>,
    scheme: Scheme,
    busy: Boolean,
    onRestore: (UserPresets.Binned) -> Unit,
    onClose: () -> Unit,
) {
    // The same catch-all for touch the chooser uses, and for the same
    // reason: a tap in a gap must not reach the strip underneath.
    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).pointerInput(Unit) { detectTapGestures { } }.padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TapeText("DELETED PRESETS", TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.weight(1f))
                TapeText("${binned.size}", TapeType.lcdSmall, scheme.amber.tape)
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                        // Always clickable, `!busy` forwarded rather than
                        // dropped (accessibility audit finding 12); the name
                        // stays put through the "…" swap, as the chooser's does.
                        .tapeClick(label = "BACK TO SYNTH", enabled = !busy, onClick = onClose)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(if (busy) "…" else "◄ SYNTH", TapeType.pixel, scheme.amber.tape)
                }
            }
            if (binned.isEmpty()) {
                // The last RESTORE empties the list under the reader; the
                // door itself is gone by the time this closes.
                Box(Modifier.fillMaxWidth().weight(1f).padding(14.dp), contentAlignment = Alignment.Center) {
                    TapeText(Copy.NOTHING_DELETED, TapeType.lcdSmall, scheme.lcdInk.tape)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).sunkenField(scheme).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // A name forgotten twice is two rows; the stamp tells them apart.
                    items(binned, key = { "${it.saved.engine}/${it.saved.voice}/${it.saved.name}/${it.binnedAt}" }) { row ->
                        BinnedPresetRow(row, scheme, busy, onRestore = { onRestore(row) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BinnedPresetRow(row: UserPresets.Binned, scheme: Scheme, busy: Boolean, onRestore: () -> Unit) {
    val now = System.currentTimeMillis()
    val daysLeft = row.daysLeft(now)
    // ≤2 days left renders in `scheme.warn`, the threshold every other bin's row uses.
    val dayColor = if (daysLeft <= 2) scheme.warn.tape else scheme.amber.tape
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(scheme.lcd.tape, RoundedCornerShape(5.dp))
            .border(1.dp, scheme.grayEdge.tape, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TapeText(row.saved.name, TapeType.marker, scheme.ink.tape, maxLines = 1)
            TapeText(
                "${row.saved.engine} · ${row.saved.voice.replace('_', ' ')} · ${Ages.ago(row.binnedAt, now)}",
                TapeType.pixelSmall,
                scheme.ink2.tape,
                maxLines = 1,
            )
        }
        TapeText("${daysLeft}D LEFT", TapeType.lcdSmall, dayColor)
        Box(
            Modifier
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                // Always clickable, `!busy` forwarded rather than dropped
                // (accessibility audit finding 12).
                .tapeClick(label = "RESTORE ${row.saved.name}", enabled = !busy, onClick = onRestore)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TapeText("RESTORE", TapeType.pixelSmall, if (busy) scheme.ink3.tape else scheme.amber.tape)
        }
    }
}

// Duplicated, not hoisted — `KitsScreen.kt`'s and `DeletedKitsScreen.kt`'s
// own `BIN_RED_BORDER`, deliberately constant across every scheme so a
// delete reads as "red" even in a scheme with no red anywhere else in it.
private val BIN_RED_BORDER = Color(0xFF6A2020)

// ---------- SAVE AS PRESET: the name ----------

/**
 * The slip SAVE PRESET ▸ drops (`docs/WORKSHOP.md`, WS5): `KitRenameDialog`'s
 * shape — scrim, raised bevel, a `BasicTextField` in a sunken field,
 * CANCEL beside the one real button — hung from the top with the keyboard
 * already up, the way `BenchNoteDialog` is and for the same reason.
 *
 * The field opens on [UserPresets.suggest]'s placeholder, selected to its
 * end so typing continues it and a swipe replaces it. Every keystroke is
 * judged by [UserPresets.check], the one home of the name rules: the
 * caption under the field says which rule a name fails, or what saving
 * under one of your own names costs, and the button reads SAVE or
 * REPLACE accordingly — the same word the toast will use. A refusal is
 * dim with its reason, never a silent no.
 */
@Composable
private fun PresetNameDialog(
    engine: Engine,
    voice: Enum<*>,
    yours: List<UserPresets.Saved>,
    scheme: Scheme,
    onCancel: () -> Unit,
    onSave: (UserPresets.Check) -> Unit,
) {
    val suggestion = remember(engine, voice) { UserPresets.suggest(engine.name, voice.name, yours) }
    var field by remember { mutableStateOf(TextFieldValue(suggestion, TextRange(suggestion.length))) }
    val check = UserPresets.check(field.text, engine.name, voice.name, yours)
    val (caption, captionColor) = when (check) {
        is UserPresets.Check.Blank -> Copy.PRESET_NAME_BLANK to scheme.warn.tape
        is UserPresets.Check.TooLong -> Copy.PRESET_NAME_NOTE to scheme.warn.tape
        is UserPresets.Check.Factory -> Copy.presetNameFactory(check.name) to scheme.warn.tape
        is UserPresets.Check.Fresh -> Copy.PRESET_NAME_NOTE to scheme.ink2.tape
        is UserPresets.Check.Replaces -> Copy.presetReplaces(check.name) to scheme.amber.tape
    }
    val canSave = check is UserPresets.Check.Fresh || check is UserPresets.Check.Replaces
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .imePadding()
            // No descendant text of its own — labelled with the same
            // word the visible CANCEL button below uses.
            .tapeClick(label = "CANCEL", onClick = onCancel),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                // Swallows the tap so it doesn't fall through to the
                // scrim's CANCEL — a bare gesture detector, which registers
                // no semantics node (MessageBox.kt's pattern).
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("SAVE AS PRESET", TapeType.lcdSmall, scheme.ink.tape)
            TapeText("${engine.name} · ${chipLabel(engine, voice)}", TapeType.pixel, scheme.ink2.tape)
            Box(
                Modifier
                    .fillMaxWidth()
                    .sunkenField(scheme)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = TapeType.marker.copy(color = scheme.ink.tape),
                    cursorBrush = SolidColor(scheme.ink.tape),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            TapeText(caption, TapeType.pixelSmall, captionColor, maxLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton(
                    if (check is UserPresets.Check.Replaces) "REPLACE" else "SAVE",
                    scheme,
                    enabled = canSave,
                    dimmed = !canSave,
                    modifier = Modifier.weight(1f),
                    onClick = { onSave(check) },
                )
            }
        }
    }
}

// ---------- MAKE INSTRUMENT: RESIN, held ----------

/**
 * MAKE INSTRUMENT ▸'s sheet (RESIN only): [PresetNameDialog]'s frame - scrim,
 * raised bevel, CANCEL beside the real buttons - over the two knobs a held
 * note has that a one-shot never needed, ATTACK and RELEASE, with their
 * seconds spelled out. While MAKE runs the knobs give way to [HeldProgress],
 * a bar that fills as each zone lands and the line counting them; PREVIEW's
 * one zone says RENDERING… the way the scope does. CANCEL stays live until
 * the last zone lands, and the write that follows is left to finish.
 */
@Composable
private fun HeldInstrumentSheet(
    heading: String,
    name: String,
    attack: Float,
    release: Float,
    done: Int,
    total: Int,
    previewing: Boolean,
    fillColor: Color,
    scheme: Scheme,
    onAttack: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    onPreview: () -> Unit,
    onMake: () -> Unit,
    onCancel: () -> Unit,
) {
    val making = done >= 0
    val busy = making || previewing
    val writing = making && done >= total
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // No descendant text of its own - labelled with the same word
            // the visible CANCEL button below uses.
            .tapeClick(label = "CANCEL", enabled = !writing, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                // Swallows the tap so it doesn't fall through to the scrim's
                // CANCEL (MessageBox.kt's pattern, as PresetNameDialog's).
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("MAKE INSTRUMENT", TapeType.lcdSmall, scheme.ink.tape)
            TapeText(heading, TapeType.pixel, scheme.ink2.tape)
            if (name.isNotEmpty()) {
                TapeText(Copy.heldInstrumentNote(name), TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
            }
            if (making) {
                HeldProgress(done, total, fillColor, scheme)
            } else {
                MacroSlider("ATTACK", attack, fillColor, scheme, onAttack)
                MacroSlider("RELEASE", release, fillColor, scheme, onRelease)
                TapeText(
                    "ATTACK ${ResinPadMaker.secondsLabel(ResinPadMaker.ATTACK.value(attack))} · " +
                        "RELEASE ${ResinPadMaker.secondsLabel(ResinPadMaker.RELEASE.value(release))}",
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                )
                if (previewing) TapeText("RENDERING…", TapeType.lcdSmall, scheme.amber.tape)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = !writing, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton("PREVIEW", scheme, enabled = !busy, dimmed = busy, modifier = Modifier.weight(1f), onClick = onPreview)
                ActionButton("MAKE", scheme, enabled = !busy, dimmed = busy, lit = !busy, modifier = Modifier.weight(1f), onClick = onMake)
            }
        }
    }
}

// ---------- DRONE TO LOOP: RESIN, droning ----------

/**
 * DRONE TO LOOP ▸'s sheet (RESIN only), [HeldInstrumentSheet]'s frame over
 * the three things a drone has that a one-shot never needed: ROOT (a note
 * stepper across the voice's own register), MOTION (how far the filter
 * breathes) and BREATHS (how many times per drone). The readout under ROOT
 * is [DroneMaker.label]: the note, how many bars before it repeats at the
 * grid's tempo, and the nudge the loop needed to close.
 */
@Composable
private fun DroneSheet(
    heading: String,
    voice: ResinVoice,
    root: Int?,
    session: Session?,
    motion: Float,
    rate: Int,
    previewing: Boolean,
    fillColor: Color,
    scheme: Scheme,
    onRoot: (Int) -> Unit,
    onMotion: (Float) -> Unit,
    onRate: (Int) -> Unit,
    onPreview: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val ready = root != null && session != null
    val range = DroneMaker.roots(voice)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .tapeClick(label = "CANCEL", onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText("DRONE TO LOOP", TapeType.lcdSmall, scheme.ink.tape)
            TapeText(heading, TapeType.pixel, scheme.ink2.tape)
            TapeText(Copy.DRONE_NOTE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    "−",
                    scheme,
                    enabled = root != null && root > range.first,
                    accessibilityLabel = "ROOT DOWN",
                    modifier = Modifier.weight(1f),
                ) { root?.let { onRoot(it - 1) } }
                TapeText(
                    if (root != null && session != null) DroneMaker.label(root, session) else "…",
                    TapeType.lcdSmall,
                    scheme.ink.tape,
                    modifier = Modifier.weight(3f),
                )
                ActionButton(
                    "+",
                    scheme,
                    enabled = root != null && root < range.last,
                    accessibilityLabel = "ROOT UP",
                    modifier = Modifier.weight(1f),
                ) { root?.let { onRoot(it + 1) } }
            }
            MacroSlider("MOTION", motion, fillColor, scheme, onMotion)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapeText(
                    "MOTION ${DroneMaker.motionLabel(motion)}",
                    TapeType.pixelSmall,
                    scheme.ink2.tape,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    DroneMaker.breathsLabel(rate),
                    scheme,
                    enabled = !previewing,
                    modifier = Modifier.weight(1f),
                ) {
                    val rates = ResinDrone.RATES
                    onRate(rates[(rates.indexOf(rate) + 1) % rates.size])
                }
            }
            if (previewing) TapeText("RENDERING…", TapeType.lcdSmall, scheme.amber.tape)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton(
                    "PREVIEW",
                    scheme,
                    enabled = ready && !previewing,
                    dimmed = !ready || previewing,
                    modifier = Modifier.weight(1f),
                    onClick = onPreview,
                )
                ActionButton(
                    "SEND",
                    scheme,
                    enabled = ready,
                    dimmed = !ready,
                    lit = ready,
                    modifier = Modifier.weight(1f),
                    onClick = onSend,
                )
            }
        }
    }
}

/** MAKE's progress: a bar filling zone by zone, and [Copy.instrumentRendering] counting them. */
@Composable
private fun HeldProgress(done: Int, total: Int, fillColor: Color, scheme: Scheme) {
    val fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val line = Copy.instrumentRendering(done, total)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .sunkenField(scheme)
                // Canvas-free, but still a bar a screen reader must hear as
                // one: the same semantics MacroSlider gives its fill.
                .semantics {
                    contentDescription = line
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(fillColor.copy(alpha = 0.85f)))
        }
        TapeText(line, TapeType.lcdSmall, scheme.amber.tape)
    }
}

// ---------- the engine adapter ----------

/**
 * The screen's own multi-engine adapter — file-private, per the brief ("the
 * engine abstraction stays file-private to the screen — :synth is not to
 * change"). All ten registered engines already converge on one shape (an
 * `<X>Voice` enum, `macrosFor`/`defaults`/`scramble`/`render`, and an
 * `<X>Patch(name, voice, macros)` constructor registered in Patches.kt) —
 * this just gives the screen one dispatch point instead of ten near-
 * identical call sites, adapting to that convergence rather than the other
 * way around. Voices are held as `Enum<*>` (not each engine's own sealed
 * voice type) because the screen keeps "the current voice" as a single piece
 * of state that survives an engine switch; every `when (this)` branch below
 * casts back to the one concrete voice type that engine is ever paired
 * with — safe by construction, since [voices] is the only place a voice for
 * a given engine ever comes from.
 */
private enum class Engine {
    THUMP, SKIN, TINES, VELVET, VOX, PLUCK, TONEWHEEL, FATHOM, RESIN, TIDE;

    /** THUMP → SKIN → TINES → VELVET → VOX → PLUCK → TONEWHEEL → FATHOM → RESIN → TIDE → THUMP. */
    fun next(): Engine = entries[(ordinal + 1) % entries.size]

    fun voices(): List<Enum<*>> = when (this) {
        THUMP -> ThumpVoice.entries
        SKIN -> SkinVoice.entries
        TINES -> TinesVoice.entries
        VELVET -> VelvetVoice.entries
        VOX -> VoxVoice.entries
        PLUCK -> PluckVoice.entries
        TONEWHEEL -> TonewheelVoice.entries
        FATHOM -> FathomVoice.entries
        RESIN -> ResinVoice.entries
        TIDE -> TideVoice.entries
    }

    fun macrosFor(voice: Enum<*>) = when (this) {
        THUMP -> Thump.macrosFor(voice as ThumpVoice)
        SKIN -> Skin.macrosFor(voice as SkinVoice)
        TINES -> Tines.macrosFor(voice as TinesVoice)
        VELVET -> Velvet.macrosFor(voice as VelvetVoice)
        VOX -> Vox.macrosFor(voice as VoxVoice)
        PLUCK -> Pluck.macrosFor(voice as PluckVoice)
        TONEWHEEL -> Tonewheel.macrosFor(voice as TonewheelVoice)
        FATHOM -> Fathom.macrosFor(voice as FathomVoice)
        RESIN -> Resin.macrosFor(voice as ResinVoice)
        TIDE -> Tide.macrosFor(voice as TideVoice)
    }

    fun defaults(voice: Enum<*>): Map<String, Float> = when (this) {
        THUMP -> Thump.defaults(voice as ThumpVoice)
        SKIN -> Skin.defaults(voice as SkinVoice)
        TINES -> Tines.defaults(voice as TinesVoice)
        VELVET -> Velvet.defaults(voice as VelvetVoice)
        VOX -> Vox.defaults(voice as VoxVoice)
        PLUCK -> Pluck.defaults(voice as PluckVoice)
        TONEWHEEL -> Tonewheel.defaults(voice as TonewheelVoice)
        FATHOM -> Fathom.defaults(voice as FathomVoice)
        RESIN -> Resin.defaults(voice as ResinVoice)
        TIDE -> Tide.defaults(voice as TideVoice)
    }

    fun scramble(voice: Enum<*>, random: Random): Map<String, Float> = when (this) {
        THUMP -> Thump.scramble(voice as ThumpVoice, random)
        SKIN -> Skin.scramble(voice as SkinVoice, random)
        TINES -> Tines.scramble(voice as TinesVoice, random)
        VELVET -> Velvet.scramble(voice as VelvetVoice, random)
        VOX -> Vox.scramble(voice as VoxVoice, random)
        PLUCK -> Pluck.scramble(voice as PluckVoice, random)
        TONEWHEEL -> Tonewheel.scramble(voice as TonewheelVoice, random)
        FATHOM -> Fathom.scramble(voice as FathomVoice, random)
        RESIN -> Resin.scramble(voice as ResinVoice, random)
        TIDE -> Tide.scramble(voice as TideVoice, random)
    }

    // Every engine's `render(voice, macros)` takes exactly those two
    // params with the rest defaulted — TONEWHEEL alone also carries a
    // `gateSeconds` param (its stab-vs-sustain knob for the not-yet-built
    // key-patch side), but its default already produces the one-shot stab
    // this screen wants, so the uniform two-arg call reaches it fine.
    fun render(voice: Enum<*>, macros: Map<String, Float>): Snip = when (this) {
        THUMP -> Thump.render(voice as ThumpVoice, macros)
        SKIN -> Skin.render(voice as SkinVoice, macros)
        TINES -> Tines.render(voice as TinesVoice, macros)
        VELVET -> Velvet.render(voice as VelvetVoice, macros)
        VOX -> Vox.render(voice as VoxVoice, macros)
        PLUCK -> Pluck.render(voice as PluckVoice, macros)
        TONEWHEEL -> Tonewheel.render(voice as TonewheelVoice, macros)
        FATHOM -> Fathom.render(voice as FathomVoice, macros)
        RESIN -> Resin.render(voice as ResinVoice, macros)
        TIDE -> Tide.render(voice as TideVoice, macros)
    }

    fun drumClass(voice: Enum<*>): DrumClass = when (this) {
        THUMP -> (voice as ThumpVoice).drumClass
        SKIN -> (voice as SkinVoice).drumClass
        TINES -> (voice as TinesVoice).drumClass
        VELVET -> (voice as VelvetVoice).drumClass
        VOX -> (voice as VoxVoice).drumClass
        PLUCK -> (voice as PluckVoice).drumClass
        TONEWHEEL -> (voice as TonewheelVoice).drumClass
        FATHOM -> (voice as FathomVoice).drumClass
        RESIN -> (voice as ResinVoice).drumClass
        TIDE -> (voice as TideVoice).drumClass
    }

    fun buildPatch(name: String, voice: Enum<*>, macros: Map<String, Float>): Patch = when (this) {
        THUMP -> ThumpPatch(name, voice as ThumpVoice, macros)
        SKIN -> SkinPatch(name, voice as SkinVoice, macros)
        TINES -> TinesPatch(name, voice as TinesVoice, macros)
        VELVET -> VelvetPatch(name, voice as VelvetVoice, macros)
        VOX -> VoxPatch(name, voice as VoxVoice, macros)
        PLUCK -> PluckPatch(name, voice as PluckVoice, macros)
        TONEWHEEL -> TonewheelPatch(name, voice as TonewheelVoice, macros)
        FATHOM -> FathomPatch(name, voice as FathomVoice, macros)
        RESIN -> ResinPatch(name, voice as ResinVoice, macros)
        TIDE -> TidePatch(name, voice as TideVoice, macros)
    }

    /** A saved patch's human name — "Hat Closed Thump", "Bell Tines". */
    fun patchDisplayName(voice: Enum<*>): String {
        val voiceName = voice.name.split('_').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        val engineName = name.lowercase().replaceFirstChar { it.uppercase() }
        return "$voiceName $engineName"
    }
}

// ---------- voice ↔ drum class ----------
//
// One mapping per engine, all named in this one place, per the brief.
// THUMP's is unchanged (COWBELL/RIM have no dedicated class — they read as
// PERC, same as the KIT screen's own AutoPlace mapping). TINES splits by how
// ThumpKits.classic() itself already classifies these same voices when it
// borrows TINES for its own top row (BELL/CHIME -> TONAL, BLOCK/ZAP ->
// PERC) plus the brief's own explicit call for TOY -> PERC (the cheap-
// keyboard laser/game hit reads as a percussive one-shot, not a pitched
// note). VELVET/VOX/PLUCK/TONEWHEEL are silent in SynthKits.kt about most of
// their own voices — but every voice from these four engines SynthKits DOES
// render (VELVET's CHIP, VOX's CHOIR/ROBOT/GHOST, PLUCK's NYLON/KALIMBA/
// HARP, TONEWHEEL's SOUL/STAB/FULL) is classified TONAL there, and every
// remaining voice in these four engines is likewise a pitched note (VELVET's
// BASS/BRASS/SQUELCH, PLUCK's KOTO) — so per the brief's fallback rule
// ("tonal-pitched voices -> TONAL, percussive -> PERC"), all four engines
// are TONAL across the board. RESIN's three voices (BASS/LEAD/BRASS) are
// pitched notes through a filter, never judged from a render (ResinPresetsTest
// carries no classifier identity check, exactly as VelvetPresetsTest explains)
// — the same fallback, so five engines are TONAL across the board.
//
// FATHOM is the one engine here that isn't: `FathomTest`'s own factory-
// defaults classifier check (and now `FathomPresetsTest`'s identity check
// over its presets) already establishes DEEP and GRIND as DrumClass.KICK
// and GLASS as PERC — real classifier judgments, not a fallback guess, so
// this mirrors them rather than defaulting FATHOM to TONAL the way the
// other five engines are.

private val ThumpVoice.drumClass: DrumClass
    get() = when (this) {
        ThumpVoice.KICK -> DrumClass.KICK
        ThumpVoice.SNARE -> DrumClass.SNARE
        ThumpVoice.HAT_CLOSED -> DrumClass.HAT_CLOSED
        ThumpVoice.HAT_OPEN -> DrumClass.HAT_OPEN
        ThumpVoice.CLAP -> DrumClass.CLAP
        ThumpVoice.TOM -> DrumClass.TOM
        ThumpVoice.COWBELL -> DrumClass.PERC
        ThumpVoice.RIM -> DrumClass.PERC
    }

// SKIN's voices are built to hit the same classifier gates THUMP's do
// (SkinTest's own `factory X is a X` set proves it for KICK/SNARE/
// HAT_CLOSED/HAT_OPEN/TOM), so those mirror THUMP's labels directly.
// RIDE is built from hat()'s exact recipe (denser, longer) and genuinely
// measures HAT_OPEN too - real structural kinship, not a fallback guess,
// same as FATHOM's DEEP/GRIND mirroring a real KICK verdict. SHAKER and
// STICK have no dedicated DrumClass to aim for at all (there's no
// "shaker" or "rimshot" class the way there's no "cowbell" or "rim" one)
// so, like THUMP's own COWBELL/RIM, they land on PERC by the same
// no-better-bucket convention rather than a classifier assertion neither
// SkinTest nor ThumpTest makes for their counterparts.
private val SkinVoice.drumClass: DrumClass
    get() = when (this) {
        SkinVoice.KICK -> DrumClass.KICK
        SkinVoice.SNARE -> DrumClass.SNARE
        SkinVoice.HAT_CLOSED -> DrumClass.HAT_CLOSED
        SkinVoice.HAT_OPEN -> DrumClass.HAT_OPEN
        SkinVoice.TOM -> DrumClass.TOM
        SkinVoice.RIDE -> DrumClass.HAT_OPEN
        SkinVoice.SHAKER -> DrumClass.PERC
        SkinVoice.STICK -> DrumClass.PERC
    }

private val TinesVoice.drumClass: DrumClass
    get() = when (this) {
        TinesVoice.BELL, TinesVoice.CHIME -> DrumClass.TONAL
        TinesVoice.BLOCK, TinesVoice.ZAP, TinesVoice.TOY -> DrumClass.PERC
    }

private val VelvetVoice.drumClass: DrumClass get() = DrumClass.TONAL
private val VoxVoice.drumClass: DrumClass get() = DrumClass.TONAL
private val PluckVoice.drumClass: DrumClass get() = DrumClass.TONAL
private val TonewheelVoice.drumClass: DrumClass get() = DrumClass.TONAL

private val FathomVoice.drumClass: DrumClass
    get() = when (this) {
        FathomVoice.DEEP, FathomVoice.GRIND -> DrumClass.KICK
        FathomVoice.GLASS -> DrumClass.PERC
    }

// RESIN's three voices are pitched notes through a filter — the same
// "tonal-pitched voices -> TONAL" fallback VELVET/VOX/PLUCK/TONEWHEEL
// take above, never judged from a render (ResinPresetsTest says why).
private val ResinVoice.drumClass: DrumClass
    get() = DrumClass.TONAL

// TIDE is FATHOM's rule, not RESIN's: a real classifier judgment.
// `TidePresetsTest` holds every voice's defaults to PERC and at least 8 of
// its 10 presets with them — the low-pass gate's struck envelope is what
// the classifier hears, FLARE's lead included.
private val TideVoice.drumClass: DrumClass
    get() = DrumClass.PERC

/**
 * Chip/header label. THUMP and SKIN keep prototype-verbatim abbreviations
 * (`HAT_CLOSED` → "HAT CL", `HAT_OPEN` → "HAT OP" — the rest are already
 * short enough as-is); every other engine's voice names are already one
 * short word (BELL, SQUELCH, KALIMBA, …) with no underscore to break up, so
 * the raw enum name reads fine unmodified. Without this, this picker's
 * one-line visible label and TalkBack announcement both fall back to the
 * raw enum name — underscores and all, and liable to be ellipsized.
 */
private fun chipLabel(engine: Engine, voice: Enum<*>): String = when (engine) {
    Engine.THUMP -> thumpChipLabel(voice as ThumpVoice)
    Engine.SKIN -> skinChipLabel(voice as SkinVoice)
    else -> voice.name
}

private fun thumpChipLabel(voice: ThumpVoice): String = when (voice) {
    ThumpVoice.KICK -> "KICK"
    ThumpVoice.SNARE -> "SNARE"
    ThumpVoice.HAT_CLOSED -> "HAT CL"
    ThumpVoice.HAT_OPEN -> "HAT OP"
    ThumpVoice.CLAP -> "CLAP"
    ThumpVoice.TOM -> "TOM"
    ThumpVoice.COWBELL -> "COWBELL"
    ThumpVoice.RIM -> "RIM"
}

private fun skinChipLabel(voice: SkinVoice): String = when (voice) {
    SkinVoice.KICK -> "KICK"
    SkinVoice.SNARE -> "SNARE"
    SkinVoice.HAT_CLOSED -> "HAT CL"
    SkinVoice.HAT_OPEN -> "HAT OP"
    SkinVoice.TOM -> "TOM"
    SkinVoice.RIDE -> "RIDE"
    SkinVoice.SHAKER -> "SHAKER"
    SkinVoice.STICK -> "STICK"
}

// One rule, one home ([PadBanks]): this said "A%02d".format(slot) until
// the September UAT's finding 11 made bank B reachable, at which point a
// pad on slot 17 would have been titled A17 on this screen.
private fun padTag(slot: Int): String = PadBanks.tag(slot)

// ---------- voice picker ----------

/**
 * Batch 3, Task 5: bright border + sub-label, not a solid fill — SETUP's
 * own selected-state treatment (`PropertiesScreen.kt`'s `SchemeRow`,
 * [pressedBevel] vs [raisedBevel]), applied here so this picker agrees with
 * every other one in the app. [pressedBevel]'s own KDoc already makes the
 * colourblindness case for a border over a fill (a thicker, distinctly-hued
 * ring reads even to someone who can't use the hue at all); this call site
 * is citing that, not re-deriving it. The move matters more here than on
 * CHOP: this screen's solid fill already carries THREE meanings at once —
 * selected voice (this fill), a macro slider's value ([MacroSlider]'s own
 * fill), and AUDITION's primary-action fill — and the same fill standing
 * for "selected engine" too is exactly why it couldn't also mean that.
 * [Schemes.classColor] moves onto the label text instead of the
 * background, so the kick/snare/hat colour coding survives the swap rather
 * than disappearing with the fill.
 */
@Composable
private fun VoicePicker(engine: Engine, current: Enum<*>, scheme: Scheme, onSelect: (Enum<*>) -> Unit) {
    val voices = engine.voices()
    // THUMP's and SKIN's eight voices each split into two even rows of
    // four; every other engine has four or fewer, so one row fits them
    // all without inventing a lonely single-chip second row.
    val rows = if (voices.size <= 4) {
        listOf(voices)
    } else {
        val half = (voices.size + 1) / 2
        listOf(voices.subList(0, half), voices.subList(half, voices.size))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (v in row) {
                    val selected = v == current
                    val color = Schemes.classColor(engine.drumClass(v)).tape
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                            .semantics { this.selected = selected }
                            .tapeClick(label = chipLabel(engine, v)) { onSelect(v) }
                            .padding(horizontal = 2.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TapeText(
                            chipLabel(engine, v),
                            TapeType.pixelSmall,
                            color,
                            maxLines = 1,
                        )
                        // Reserved on every chip, not just the selected one
                        // (blank when not selected) — so one selection
                        // doesn't grow only its own cell and leave the row
                        // jagged against its neighbours.
                        TapeText(if (selected) "SELECTED" else "", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------- preset list ----------

/**
 * U1's preset library (`docs/SYNTH_UPGRADE.md`), by voice — rule 1's other
 * half, "preset-first, knobs-second": sits between the voice picker and the
 * macro sliders it fills in. [Presets] returns an empty list for a voice
 * with no roster yet (every engine but THUMP, until their own U1 passes
 * land), and an empty row draws nothing rather than a blank sunken strip —
 * this screen already omits controls this way (SEND TO PAD's own `kit ==
 * null` case has no placeholder either).
 *
 * A horizontally-scrolling strip of chips inside one [sunkenField], not a
 * vertical list: sixteen names have to fit next to a voice picker and five
 * sliders on one phone screen, and a horizontal strip is what "tap it, hear
 * it, tap the next one" (the roadmap's own browsing-speed framing) wants
 * anyway — no per-row height cost as the roster grows. [current] is `null`
 * once any slider or SCRAMBLE has moved the macros away from what a tap
 * loaded (`SynthScreen`'s own `currentPresetByVoice`), so the highlight
 * never claims a name for a sound that no longer matches it.
 */
@Composable
private fun PresetList(
    engine: Engine,
    voice: Enum<*>,
    yours: List<Patch>,
    current: String?,
    scheme: Scheme,
    onSelect: (Patch) -> Unit,
    onHold: (Patch) -> Unit,
) {
    val presets = remember(engine, voice) { Presets.forVoice(engine.name, voice.name) }
    PresetStrip(engine, voice, presets, current, scheme, label = null, spoken = "PRESET", onSelect = onSelect)
    // The player's own, under the factory row (docs/WORKSHOP.md, WS5):
    // a second strip that exists only once this voice has one, with the
    // same chips and the same highlight rule, and YOURS in the slider
    // label column below it so the two rows read as two rows. A hold on
    // one of these asks to forget it; the factory's chips have no hold.
    PresetStrip(engine, voice, yours, current, scheme, label = "YOURS", spoken = "YOUR PRESET", onSelect = onSelect, onHold = onHold)
}

/**
 * One horizontally-scrolling strip of preset chips inside a [sunkenField];
 * nothing at all when [presets] is empty. [label] is the column word to its
 * left, or none; [spoken] prefixes each chip's accessible name. With
 * [onHold], a long press on a chip is the FORGET gesture (the rooms row's
 * own hold), and the tap still loads.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetStrip(
    engine: Engine,
    voice: Enum<*>,
    presets: List<Patch>,
    current: String?,
    scheme: Scheme,
    label: String?,
    spoken: String,
    onSelect: (Patch) -> Unit,
    onHold: ((Patch) -> Unit)? = null,
) {
    if (presets.isEmpty()) return
    // Keyed on (engine, voice), not the plain `rememberScrollState()` every
    // other scroll in this file uses: those all sit inside a screen-level
    // Column that never itself changes identity, but this strip is rebuilt
    // fresh per voice. Unkeyed, a scroll position picked up browsing one
    // voice's roster would carry into the next voice's — Copilot's own
    // finding — and could open a shorter roster already scrolled past its
    // first presets.
    val scrollState = remember(engine, voice) { ScrollState(0) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) TapeText(label, TapeType.pixelSmall, scheme.ink2.tape, Modifier.width(56.dp))
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .clip(RoundedCornerShape(4.dp))
                .sunkenField(scheme)
                .horizontalScroll(scrollState)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in presets) {
                val selected = p.name == current
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .let { if (selected) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                        .semantics { this.selected = selected }
                        .let { chip ->
                            if (onHold == null) {
                                chip.tapeClick(label = "$spoken ${p.name}") { onSelect(p) }
                            } else {
                                // A plain tap/long-press with no drag —
                                // combinedClickable registers both as real
                                // accessibility actions (KitsScreen's RoomRow).
                                chip.combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "$spoken ${p.name}",
                                    onLongClickLabel = "FORGET ${p.name}",
                                    onLongClick = { onHold(p) },
                                    onClick = { onSelect(p) },
                                )
                            }
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(p.name, TapeType.pixelSmall, if (selected) scheme.amber.tape else scheme.ink2.tape, maxLines = 1)
                }
            }
        }
    }
}

// ---------- macro slider ----------

/**
 * The stepper-slider idiom from `PadSheetScreen.kt`'s `StepperSlider` is
 * `private` to that file (Kotlin file-private, not module-private), and the
 * brief allows touching only `SynthScreen.kt` and `App.kt` — so it can't be
 * imported. This reproduces the same gesture shape (a sunken track, a
 * pointer-driven fraction, a coloured fill) rather than sharing it; unlike
 * PADS SHEET's version there is no separate commit step, because a macro
 * isn't a debounced disk write — every drag position is live state that
 * feeds the render loop above directly, same as the prototype's slider.
 */
@Composable
internal fun MacroSlider(
    label: String,
    value: Float,
    fillColor: Color,
    scheme: Scheme,
    onValueChange: (Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    Row(
        Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TapeText(label, TapeType.pixelSmall, scheme.ink2.tape, Modifier.width(56.dp))
        Box(
            Modifier
                .weight(1f)
                .height(Layout.MIN_HIT_TARGET.dp)
                .clip(RoundedCornerShape(4.dp))
                .sunkenField(scheme)
                // Same shape as PadSheetScreen's StepperSlider (this
                // file's own KDoc explains why that one couldn't be
                // imported directly) — Canvas-drawn, invisible to the
                // a11y tree by default (finding 4). progressBarRangeInfo
                // + setProgress give TalkBack's adjust gesture a real
                // target.
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                    setProgress { target -> currentOnChange(target.coerceIn(0f, 1f)); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun fractionAt(x: Float) = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        currentOnChange(fractionAt(down.position.x))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            currentOnChange(fractionAt(change.position.x))
                            change.consume()
                            if (!change.pressed) break
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .background(fillColor.copy(alpha = 0.85f)),
            )
        }
        Box(Modifier.width(44.dp).heightIn(min = 26.dp).lcdPanel(scheme, 3.dp), contentAlignment = Alignment.Center) {
            TapeText("${(value * 100).roundToInt()}", TapeType.lcdSmall, scheme.lcdInk.tape)
        }
    }
}

// ---------- scope ----------

/**
 * The current render's waveform, real min/max columns like TapeScreen's own
 * `WaveformLcd` — dp-first bar step/width, converted to px only at draw
 * time — rather than PadSheetScreen's fixed-count amplitude-only bars.
 */
@Composable
internal fun ScopeLcd(snip: Snip?, rendering: Boolean, scheme: Scheme, modifier: Modifier = Modifier) {
    Box(modifier.lcdPanel(scheme)) {
        val peaks = remember(snip) { snip?.let { PeaksPyramid.fromSnip(it) } }
        if (peaks != null && snip != null && snip.frameCount > 0) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val barStep = 3.dp.toPx()
                val barWidth = 2.dp.toPx()
                val halfH = size.height / 2f
                val columnCount = max(1, (size.width / barStep).toInt())
                val columns = peaks.columns(0, snip.frameCount, columnCount)
                for ((i, col) in columns.withIndex()) {
                    val x = i * barStep
                    val top = (halfH - col.max * halfH).coerceIn(0f, size.height)
                    val bottom = (halfH - col.min * halfH).coerceIn(0f, size.height)
                    drawRect(
                        color = scheme.lcdInk.tape,
                        topLeft = Offset(x, top),
                        size = Size(barWidth, (bottom - top).coerceAtLeast(1f)),
                    )
                }
            }
        }
        if (rendering) {
            Box(
                Modifier.fillMaxSize().background(scheme.lcd.tape.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("RENDERING…", TapeType.lcdSmall, scheme.amber.tape)
            }
        }
    }
}

// ---------- buttons ----------

@Composable
internal fun LabButton(
    label: String,
    scheme: Scheme,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Override for [label] as the accessible name — for the one call site
     * (SEND TO PAD ▸) whose visible [label] itself turns into "…" while
     * busy: `enabled` already carries that busy state to a screen reader,
     * so the name stays stable instead of briefly becoming an ellipsis.
     * `null` (every other call site) falls back to [label] alone.
     */
    accessibilityLabel: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            // Always clickable, `enabled` forwarded rather than dropped: a
            // screen reader is told this control is temporarily unavailable
            // instead of it silently vanishing from the tree (accessibility
            // audit finding 12 — see ActionButton in PadSheetScreen.kt).
            .tapeClick(label = accessibilityLabel ?: label, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (enabled) scheme.ink.tape else scheme.ink3.tape, maxLines = 1)
    }
}

// ---------- SEND TO PAD: slot chooser ----------

/** Bank A, MPC geometry — A13–A16 across the top, A01 bottom-left (same rows KitScreen's own grid uses). */
private val SLOT_ROWS = listOf(13..16, 9..12, 5..8, 1..4)

@Composable
internal fun SlotChooserOverlay(
    kit: Kit?,
    previewColor: Color,
    scheme: Scheme,
    busy: Boolean,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    // A plain `background` doesn't hit-test — without a consuming gesture
    // here, a tap in any gap this overlay doesn't fully cover (the grid's
    // leftover space, the header row's empty middle) would fall through to
    // whatever SynthScreen composable sits underneath (SCRAMBLE, SEND TO
    // PAD, the AUDITION pad). A raw pointerInput, not tapeClick: this Box
    // wraps the whole overlay (CANCEL + the slot grid below), each with
    // its own accessible name — tapeClick's clickable() would add a
    // second, nameless actionable node wrapping all of them, exactly the
    // regression the KitsScreen/MessageBox dialog cards had to be
    // corrected out of. A bare gesture detector registers no semantics
    // node at all, so it's still a catch-all for touch without touching
    // the accessibility tree.
    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).pointerInput(Unit) { detectTapGestures { } }.padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapeText("SEND TO PAD — PICK A SLOT", TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.weight(1f))
                Box(
                    Modifier
                        .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                        .border(1.dp, scheme.amber.tape, RoundedCornerShape(4.dp))
                        // Always clickable, `!busy` forwarded rather than
                        // dropped (accessibility audit finding 12) — the
                        // "…" label swap below is this control's visual
                        // distinction, so no separate colour dim is needed.
                        // Name stays "CANCEL" through the swap: `enabled`
                        // already tells a screen reader this is busy, and
                        // a spoken "…" would name nothing.
                        .tapeClick(label = "CANCEL", enabled = !busy, onClick = onCancel)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText(if (busy) "…" else "CANCEL", TapeType.pixel, scheme.amber.tape)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                for (row in SLOT_ROWS) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.PAD_GAP.dp)) {
                        for (slot in row) {
                            SlotCell(
                                slot = slot,
                                pad = kit?.pad(slot),
                                previewColor = previewColor,
                                enabled = !busy,
                                scheme = scheme,
                                onTap = { onPick(slot) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One chooser cell. An empty slot is offerable (`KitBuilderModel.assign`
 * lands new audio there — `replaceAudio` alone would refuse it, but
 * `sendToSlot` branches to `assign` for exactly this case), and previews in
 * [previewColor] — the CURRENT voice's class colour — so the cell shows the
 * sound about to land, not a "nothing here" dead zone. A velocity-layered or
 * chained pad refuses `replaceAudio` outright (its own guards), so those are
 * grayed out and non-tappable here instead of letting the tap arrive at a
 * refusal toast.
 */
@Composable
private fun SlotCell(
    slot: Int,
    pad: KitPad?,
    previewColor: Color,
    enabled: Boolean,
    scheme: Scheme,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Layout.PAD_RADIUS.dp)
    val tag = padTag(slot)

    if (pad == null) {
        // Disabled dims the same amount branch two's locked cells do
        // (0.35×) — one "can't drop here right now" look shared by empty
        // and occupied slots, rather than a third treatment invented here.
        val fade = if (enabled) 1f else 0.35f
        Box(
            modifier
                .height(Layout.PAD_H.dp)
                .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
                .border(2.dp, previewColor.copy(alpha = 0.55f * fade), shape)
                // Always clickable, `enabled` forwarded rather than dropped:
                // a screen reader is told this cell is temporarily
                // unavailable instead of it silently vanishing from the
                // tree (accessibility audit finding 12).
                .tapeClick(label = "$tag, EMPTY", enabled = enabled, onClick = onTap)
                .padding(5.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape.copy(alpha = 0.7f * fade))
        }
        return
    }

    val locked = pad.velocityLayers.isNotEmpty() || pad.chain != null
    val tappable = enabled && !locked
    val cls = pad.colorHex?.removePrefix("#")?.toIntOrNull(16) ?: Schemes.classColor(pad.drumClass)
    // Locked (chained/layered, refused by replaceAudio) and explicitly
    // disabled (SlotChooserOverlay's `busy`) read as the same "can't tap
    // this" state — one fade, not a distinct look per reason.
    val fade = if (tappable) 1f else 0.35f
    Box(
        modifier
            .height(Layout.PAD_H.dp)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            .border(2.dp, cls.tape.copy(alpha = fade), shape)
            // Always clickable, `tappable` forwarded rather than dropped so
            // a locked or disabled cell still announces itself instead of
            // vanishing from the accessibility tree (finding 12). Names
            // which pad and what's on it (task convention), plus LOCKED
            // when a velocity-layered/chained pad refuses replaceAudio.
            .tapeClick(label = "$tag, ${pad.displayName}" + if (locked) ", LOCKED" else "", enabled = tappable, onClick = onTap)
            .padding(5.dp),
    ) {
        TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape.copy(alpha = 0.7f * fade), Modifier.align(Alignment.TopEnd))
        TapeText(
            pad.displayName,
            TapeType.marker,
            cls.tape.copy(alpha = fade),
            Modifier.align(Alignment.BottomStart),
            maxLines = 2,
        )
    }
}

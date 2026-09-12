package com.snipsnap.app.ui

import android.util.Log
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.TapeVoice
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.synth.Patch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Pluck
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckVoice
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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * SYNTH — the six-engine drum/tonal-synthesis lab: pick an engine, pick a
 * voice, shape it with macro sliders, SCRAMBLE it, watch the scope, audition
 * it, and land it on a pad. `synth/` is the tested engine layer; this is the
 * Compose surface plus the SEND TO PAD action, multiplexed over all six
 * registered engines via the file-private [Engine] adapter below.
 *
 * `prototype/thumplab.html` is the interaction truth this ports: every
 * macro/voice/SCRAMBLE change re-renders and retrigger-plays the audition
 * voice (its own on-screen label says so — "EVERY MOVE RE-RENDERS +
 * RETRIGGERS"), debounced so a drag doesn't hammer the DSP. `design/
 * HANDOFF.md`'s SYNTH row says "5 voices" — that's roadmap-era and THUMP-
 * only; reality wins: THUMP alone ships eight voices, and five more engines
 * (TINES, VELVET, VOX, PLUCK, TONEWHEEL) join it here. GRAINS is out of
 * scope — it has no voice enum, a different shape entirely.
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
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
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
        macrosByVoice[engine to voice] = macrosByVoice.getValue(engine to voice) + (name to value)
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
                        .tapeClick(label = null) {
                            touched = true
                            val next = engine.next()
                            engine = next
                            voice = next.voices().first()
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    TapeText("${engine.name} ▸", TapeType.lcdHeader, scheme.lcdInk.tape)
                }
                TapeText(chipLabel(engine, voice), TapeType.lcdSmall, scheme.amber.tape)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopeLcd(snip, rendering, scheme, Modifier.fillMaxWidth().height(104.dp))

                VoicePicker(engine, voice, scheme, onSelect = { touched = true; voice = it })

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
                        macrosByVoice[engine to voice] = engine.scramble(voice, Random(System.nanoTime()))
                    }
                    LabButton(
                        if (sendBusy) "…" else "SEND TO PAD ▸",
                        scheme,
                        enabled = kit != null && !sendBusy,
                        modifier = Modifier.weight(1f),
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
                    .tapeClick(label = null) { snip?.let { audition(it) } }
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
    }
}

// ---------- the engine adapter ----------

/**
 * The screen's own multi-engine adapter — file-private, per the brief ("the
 * engine abstraction stays file-private to the screen — :synth is not to
 * change"). All six registered engines already converge on one shape (an
 * `<X>Voice` enum, `macrosFor`/`defaults`/`scramble`/`render`, and an
 * `<X>Patch(name, voice, macros)` constructor registered in Patches.kt) —
 * this just gives the screen one dispatch point instead of six near-
 * identical call sites, adapting to that convergence rather than the other
 * way around. Voices are held as `Enum<*>` (not each engine's own sealed
 * voice type) because the screen keeps "the current voice" as a single piece
 * of state that survives an engine switch; every `when (this)` branch below
 * casts back to the one concrete voice type that engine is ever paired
 * with — safe by construction, since [voices] is the only place a voice for
 * a given engine ever comes from.
 */
private enum class Engine {
    THUMP, TINES, VELVET, VOX, PLUCK, TONEWHEEL;

    /** THUMP → TINES → VELVET → VOX → PLUCK → TONEWHEEL → THUMP, per the brief. */
    fun next(): Engine = entries[(ordinal + 1) % entries.size]

    fun voices(): List<Enum<*>> = when (this) {
        THUMP -> ThumpVoice.entries
        TINES -> TinesVoice.entries
        VELVET -> VelvetVoice.entries
        VOX -> VoxVoice.entries
        PLUCK -> PluckVoice.entries
        TONEWHEEL -> TonewheelVoice.entries
    }

    fun macrosFor(voice: Enum<*>) = when (this) {
        THUMP -> Thump.macrosFor(voice as ThumpVoice)
        TINES -> Tines.macrosFor(voice as TinesVoice)
        VELVET -> Velvet.macrosFor(voice as VelvetVoice)
        VOX -> Vox.macrosFor(voice as VoxVoice)
        PLUCK -> Pluck.macrosFor(voice as PluckVoice)
        TONEWHEEL -> Tonewheel.macrosFor(voice as TonewheelVoice)
    }

    fun defaults(voice: Enum<*>): Map<String, Float> = when (this) {
        THUMP -> Thump.defaults(voice as ThumpVoice)
        TINES -> Tines.defaults(voice as TinesVoice)
        VELVET -> Velvet.defaults(voice as VelvetVoice)
        VOX -> Vox.defaults(voice as VoxVoice)
        PLUCK -> Pluck.defaults(voice as PluckVoice)
        TONEWHEEL -> Tonewheel.defaults(voice as TonewheelVoice)
    }

    fun scramble(voice: Enum<*>, random: Random): Map<String, Float> = when (this) {
        THUMP -> Thump.scramble(voice as ThumpVoice, random)
        TINES -> Tines.scramble(voice as TinesVoice, random)
        VELVET -> Velvet.scramble(voice as VelvetVoice, random)
        VOX -> Vox.scramble(voice as VoxVoice, random)
        PLUCK -> Pluck.scramble(voice as PluckVoice, random)
        TONEWHEEL -> Tonewheel.scramble(voice as TonewheelVoice, random)
    }

    // Every engine's `render(voice, macros)` takes exactly those two
    // params with the rest defaulted — TONEWHEEL alone also carries a
    // `gateSeconds` param (its stab-vs-sustain knob for the not-yet-built
    // key-patch side), but its default already produces the one-shot stab
    // this screen wants, so the uniform two-arg call reaches it fine.
    fun render(voice: Enum<*>, macros: Map<String, Float>): Snip = when (this) {
        THUMP -> Thump.render(voice as ThumpVoice, macros)
        TINES -> Tines.render(voice as TinesVoice, macros)
        VELVET -> Velvet.render(voice as VelvetVoice, macros)
        VOX -> Vox.render(voice as VoxVoice, macros)
        PLUCK -> Pluck.render(voice as PluckVoice, macros)
        TONEWHEEL -> Tonewheel.render(voice as TonewheelVoice, macros)
    }

    fun drumClass(voice: Enum<*>): DrumClass = when (this) {
        THUMP -> (voice as ThumpVoice).drumClass
        TINES -> (voice as TinesVoice).drumClass
        VELVET -> (voice as VelvetVoice).drumClass
        VOX -> (voice as VoxVoice).drumClass
        PLUCK -> (voice as PluckVoice).drumClass
        TONEWHEEL -> (voice as TonewheelVoice).drumClass
    }

    fun buildPatch(name: String, voice: Enum<*>, macros: Map<String, Float>): Patch = when (this) {
        THUMP -> ThumpPatch(name, voice as ThumpVoice, macros)
        TINES -> TinesPatch(name, voice as TinesVoice, macros)
        VELVET -> VelvetPatch(name, voice as VelvetVoice, macros)
        VOX -> VoxPatch(name, voice as VoxVoice, macros)
        PLUCK -> PluckPatch(name, voice as PluckVoice, macros)
        TONEWHEEL -> TonewheelPatch(name, voice as TonewheelVoice, macros)
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
// are TONAL across the board.

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

private val TinesVoice.drumClass: DrumClass
    get() = when (this) {
        TinesVoice.BELL, TinesVoice.CHIME -> DrumClass.TONAL
        TinesVoice.BLOCK, TinesVoice.ZAP, TinesVoice.TOY -> DrumClass.PERC
    }

private val VelvetVoice.drumClass: DrumClass get() = DrumClass.TONAL
private val VoxVoice.drumClass: DrumClass get() = DrumClass.TONAL
private val PluckVoice.drumClass: DrumClass get() = DrumClass.TONAL
private val TonewheelVoice.drumClass: DrumClass get() = DrumClass.TONAL

/**
 * Chip/header label. THUMP keeps its prototype-verbatim abbreviations
 * (`HAT_CLOSED` → "HAT CL", `HAT_OPEN` → "HAT OP" — the rest are already
 * short enough as-is); every other engine's voice names are already one
 * short word (BELL, SQUELCH, KALIMBA, …) with no underscore to break up, so
 * the raw enum name reads fine unmodified.
 */
private fun chipLabel(engine: Engine, voice: Enum<*>): String =
    if (engine == Engine.THUMP) thumpChipLabel(voice as ThumpVoice) else voice.name

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

// One rule, one home ([PadBanks]): this said "A%02d".format(slot) until
// the September UAT's finding 11 made bank B reachable, at which point a
// pad on slot 17 would have been titled A17 on this screen.
private fun padTag(slot: Int): String = PadBanks.tag(slot)

// ---------- voice picker ----------

@Composable
private fun VoicePicker(engine: Engine, current: Enum<*>, scheme: Scheme, onSelect: (Enum<*>) -> Unit) {
    val voices = engine.voices()
    // THUMP's eight voices split into two even rows of four, same as
    // before; every other engine has four or fewer, so one row fits them
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
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme, fill = if (selected) color.copy(alpha = 0.85f) else null)
                            .tapeClick(label = null) { onSelect(v) }
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(
                            chipLabel(engine, v),
                            TapeType.pixelSmall,
                            if (selected) scheme.titleInk.tape else scheme.ink2.tape,
                            maxLines = 1,
                        )
                    }
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
private fun MacroSlider(
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
private fun ScopeLcd(snip: Snip?, rendering: Boolean, scheme: Scheme, modifier: Modifier = Modifier) {
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
private fun LabButton(
    label: String,
    scheme: Scheme,
    enabled: Boolean,
    modifier: Modifier = Modifier,
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
            .tapeClick(label = null, enabled = enabled, onClick = onClick)
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
    // PAD, the AUDITION pad). `tapeClick {}` makes the backdrop itself the
    // catch-all, same as any other TapeOS surface that means to block input.
    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).tapeClick(label = null) {}.padding(10.dp)) {
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
                        .tapeClick(label = null, enabled = !busy, onClick = onCancel)
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
                .tapeClick(label = null, enabled = enabled, onClick = onTap)
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
            // vanishing from the accessibility tree (finding 12).
            .tapeClick(label = null, enabled = tappable, onClick = onTap)
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

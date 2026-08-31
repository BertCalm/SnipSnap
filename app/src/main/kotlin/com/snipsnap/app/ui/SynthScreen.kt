package com.snipsnap.app.ui

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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snipsnap.app.KitShelf
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
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.ThumpVoice
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long a macro/voice/SCRAMBLE change waits, quiet, before it actually
 * re-renders — `prototype/thumplab.html`'s own `touched()` debounces at
 * 70ms; the brief calls for ~100ms so a slider drag never renders 60×/s.
 */
private const val MACRO_DEBOUNCE_MS = 100L

/**
 * How long a render has to still be running before the SCOPE shows a busy
 * shimmer. Most THUMP renders finish well under this — a raced delayed
 * reveal means the common case never flashes it.
 */
private const val RENDER_SHIMMER_DELAY_MS = 150L

/**
 * SYNTH — the THUMP drum-synthesis lab: pick a voice, shape it with macro
 * sliders, SCRAMBLE it, watch the scope, audition it, and land it on a pad.
 * `synth/Thump.kt` is the tested engine; this is the Compose surface plus
 * the SEND TO PAD action.
 *
 * `prototype/thumplab.html` is the interaction truth this ports: every
 * macro/voice/SCRAMBLE change re-renders and retrigger-plays the audition
 * voice (its own on-screen label says so — "EVERY MOVE RE-RENDERS +
 * RETRIGGERS"), debounced so a drag doesn't hammer the DSP. `design/
 * HANDOFF.md`'s SYNTH row says "5 voices" — that's roadmap-era; the engine
 * (`ThumpVoice`) has eight, and reality wins: all eight ship here.
 *
 * Two copy carve-outs, both because `Personality.kt`'s `Copy` object has no
 * matching line and the brief says not to invent one this wave:
 * - SCRAMBLE has no toast (the prototype's `SCRAMBLE_LINES` are prototype-
 *   only flavour, never ported to `Copy`).
 * - SEND TO PAD's success toast is a plain inline sentence, for both of its
 *   branches (REPLACE an assigned pad's audio, or ADD to an empty slot) —
 *   `Copy.treated` and `Copy.INSTRUMENT_MADE` both exist but neither is
 *   semantically a "a synth patch landed on this pad" line, so nothing in
 *   the TREATED/INSTRUMENT family fits either shape.
 */
@Composable
fun SynthScreen(
    entry: KitShelf.Entry?,
    onToast: (String) -> Unit,
    onKitUpdated: (Kit) -> Unit,
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    var voice by remember { mutableStateOf(ThumpVoice.KICK) }
    // Per-voice macro state, seeded with factory defaults and kept for the
    // life of the screen: switching voices (or coming back to one) restores
    // whatever was last touched on it, same as the prototype's `state.macros`
    // map — not a fresh set of defaults every time.
    val macrosByVoice = remember {
        mutableStateMapOf<ThumpVoice, Map<String, Float>>().apply {
            ThumpVoice.entries.forEach { put(it, Thump.defaults(it)) }
        }
    }
    val macros = macrosByVoice.getValue(voice)
    // The prototype gates its very first sound behind a "TAP TO POWER ON"
    // veil — a Web Audio autoplay-policy workaround, not part of the actual
    // interaction — and only *after* that makes every move retrigger. This
    // is the Android equivalent: the scope still renders and draws the
    // instant the screen opens, but nothing is heard until the first real
    // touch (a slider drag, a voice pick, SCRAMBLE), so landing on SYNTH
    // never plays a kick unasked.
    var touched by remember { mutableStateOf(false) }
    fun updateMacro(name: String, value: Float) {
        touched = true
        macrosByVoice[voice] = macrosByVoice.getValue(voice) + (name to value)
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
    // with clearTimeout/setTimeout in the prototype.
    LaunchedEffect(voice, macros) {
        delay(MACRO_DEBOUNCE_MS)
        val shimmerJob = launch { delay(RENDER_SHIMMER_DELAY_MS); rendering = true }
        try {
            val rendered = withContext(Dispatchers.Default) { Thump.render(voice, macros) }
            snip = rendered
            if (touched) audition(rendered)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onToast("RENDER FAILED: ${e.message ?: e.javaClass.simpleName}")
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
        scope.launch {
            try {
                // ThumpPatch's own init validates its macros (Patches.
                // validateMacros) — practically unreachable given every
                // value here already came from a 0..1 slider or SCRAMBLE,
                // but constructing it inside the try means an unexpected
                // failure toasts honestly instead of crashing the screen.
                val patch = ThumpPatch(patchDisplayName(voice), voice, macros)
                val recipe = PadRecipe(patch = patch).toJsonValue()
                val name = patchDisplayName(voice)
                val (existed, updatedKit) = withContext(Dispatchers.IO) {
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
                                drumClass = voice.drumClass,
                                colorHex = AutoPlace.colorFor(voice.drumClass),
                                muteGroup = AutoPlace.muteGroupFor(voice.drumClass),
                            )
                        }
                    } else {
                        // ADD: `assign` places new audio on an empty slot —
                        // it derives displayName/colorHex/muteGroup itself,
                        // but has no notion of a synth recipe, so the recipe
                        // rides a follow-up `update` (its guards permit a
                        // recipe-only edit; slot/sampleFile stay put).
                        model.assign(slot, rendered, voice.drumClass, name)
                        model.update(slot) { p -> p.copy(recipe = recipe) }
                    }
                    model.save()
                    alreadyThere to model.kit
                }
                showChooser = false
                onKitUpdated(updatedKit)
                // No Copy line fits a synth patch landing on a pad (see the
                // file KDoc's carve-out) — the simplest honest sentence,
                // said plainly. Only the REPLACE branch bins anything —
                // `replaceAudio` moves the displaced WAV to the bin
                // (`moveToBin`), the same fact `Copy.treated`'s "ORIGINAL
                // SLEEPS IN THE BIN" states for TREATMENT — `assign`'s own
                // `deleteIfUnreferenced` is a no-op on an empty slot (there
                // was no original), so the ADD branch doesn't claim it.
                onToast(
                    if (existed) {
                        "PAD ${padTag(slot)} REPLACED WITH ${name.uppercase()}. ORIGINAL SLEEPS IN THE BIN."
                    } else {
                        "PAD ${padTag(slot)} ADDED: ${name.uppercase()}."
                    },
                )
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                if (ex is IllegalStateException || ex is IllegalArgumentException) {
                    onToast(ex.message ?: "SEND REFUSED.")
                } else {
                    onToast("SEND FAILED: ${ex.message ?: ex.javaClass.simpleName}")
                }
            } finally {
                sendBusy = false
            }
        }
    }

    val classColor = Schemes.classColor(voice.drumClass).tape

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
                TapeText("THUMP", TapeType.lcdHeader, scheme.lcdInk.tape)
                TapeText(chipLabel(voice), TapeType.lcdSmall, scheme.amber.tape)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopeLcd(snip, rendering, scheme, Modifier.fillMaxWidth().height(104.dp))

                VoicePicker(voice, scheme, onSelect = { touched = true; voice = it })

                val macroSpecs = remember(voice) { Thump.macrosFor(voice) }
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
                        macrosByVoice[voice] = Thump.scramble(voice, Random(System.nanoTime()))
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
                    .tapeClick { snip?.let { audition(it) } }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TapeText("AUDITION", TapeType.pixel, scheme.titleInk.tape)
            }
        }

        if (showChooser) {
            SlotChooserOverlay(
                kit = kit,
                previewColor = classColor,
                scheme = scheme,
                busy = sendBusy,
                onPick = ::sendToSlot,
                onCancel = { if (!sendBusy) showChooser = false },
            )
        }
    }
}

// ---------- voice ↔ drum class ----------

/** COWBELL/RIM have no dedicated drum class — they read as PERC, same as the KIT screen's own AutoPlace mapping. */
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

/** Chip/header label, verbatim from the prototype's voice picker text. */
private fun chipLabel(voice: ThumpVoice): String = when (voice) {
    ThumpVoice.KICK -> "KICK"
    ThumpVoice.SNARE -> "SNARE"
    ThumpVoice.HAT_CLOSED -> "HAT CL"
    ThumpVoice.HAT_OPEN -> "HAT OP"
    ThumpVoice.CLAP -> "CLAP"
    ThumpVoice.TOM -> "TOM"
    ThumpVoice.COWBELL -> "COWBELL"
    ThumpVoice.RIM -> "RIM"
}

/** A saved patch's human name — "Hat Closed", not the enum's `HAT_CLOSED`. */
private fun patchDisplayName(voice: ThumpVoice): String =
    voice.name.split('_').joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }

private fun padTag(slot: Int): String = "A%02d".format(slot)

// ---------- voice picker ----------

@Composable
private fun VoicePicker(current: ThumpVoice, scheme: Scheme, onSelect: (ThumpVoice) -> Unit) {
    val voices = ThumpVoice.entries
    val half = (voices.size + 1) / 2
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in listOf(voices.subList(0, half), voices.subList(half, voices.size))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (v in row) {
                    val selected = v == current
                    val color = Schemes.classColor(v.drumClass).tape
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .raisedBevel(scheme, fill = if (selected) color.copy(alpha = 0.85f) else null)
                            .tapeClick { onSelect(v) }
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeText(
                            chipLabel(v),
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
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .clip(RoundedCornerShape(4.dp))
                .sunkenField(scheme)
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
            .let { if (enabled) it.tapeClick(onClick) else it }
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
private fun SlotChooserOverlay(
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
    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).tapeClick {}.padding(10.dp)) {
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
                        .let { if (!busy) it.tapeClick(onCancel) else it }
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
        Box(
            modifier
                .height(Layout.PAD_H.dp)
                .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
                .border(2.dp, previewColor.copy(alpha = 0.55f), shape)
                .let { if (enabled) it.tapeClick(onTap) else it }
                .padding(5.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            TapeText(tag, TapeType.pixelSmall, scheme.ink2.tape.copy(alpha = 0.7f))
        }
        return
    }

    val locked = pad.velocityLayers.isNotEmpty() || pad.chain != null
    val tappable = enabled && !locked
    val cls = pad.colorHex?.removePrefix("#")?.toIntOrNull(16) ?: Schemes.classColor(pad.drumClass)
    val fade = if (locked) 0.35f else 1f
    Box(
        modifier
            .height(Layout.PAD_H.dp)
            .background(Schemes.darken(scheme.gray, 0.30f).tape, shape)
            .border(2.dp, cls.tape.copy(alpha = fade), shape)
            .let { if (tappable) it.tapeClick(onTap) else it }
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

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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.semantics.disabled
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
import com.snipsnap.shell.OrphicaPadMaker
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaConfig
import com.snipsnap.synth.OrphicaMaterial
import com.snipsnap.synth.OrphicaMode
import com.snipsnap.synth.OrphicaPatch
import com.snipsnap.app.theme.BinRedGlow
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdInk2
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.pressedBevel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.OneNote
import com.snipsnap.json.JsonValue
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionBuilder
import com.snipsnap.loop.SessionStore
import com.snipsnap.shell.Ages
import com.snipsnap.shell.Breed
import com.snipsnap.shell.BellowsPadMaker
import com.snipsnap.shell.Copy
import com.snipsnap.shell.DroneMaker
import com.snipsnap.shell.GlintPadMaker
import com.snipsnap.shell.OpenSkyPadMaker
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.Layout
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PeaksPyramid
import com.snipsnap.shell.ResinPadMaker
import com.snipsnap.shell.RegistersPadMaker
import com.snipsnap.shell.SirenDroneMaker
import com.snipsnap.shell.SirenPadMaker
import com.snipsnap.shell.Scheme
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.Spread
import com.snipsnap.shell.SurfaceStore
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Onset
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Patches
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Fathom
import com.snipsnap.synth.FathomPatch
import com.snipsnap.synth.FathomVoice
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.Resin
import com.snipsnap.synth.ResinDrone
import com.snipsnap.synth.ResinPatch
import com.snipsnap.synth.ResinVoice
import com.snipsnap.synth.Tide
import com.snipsnap.synth.TidePatch
import com.snipsnap.synth.TideVoice
import com.snipsnap.synth.Glint
import com.snipsnap.synth.GlintPatch
import com.snipsnap.synth.GlintVoice
import com.snipsnap.synth.Siren
import com.snipsnap.synth.SirenDrone
import com.snipsnap.synth.SirenPatch
import com.snipsnap.synth.SirenVoice
import com.snipsnap.xpm.KeygroupProgram
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
import com.snipsnap.synth.Flotilla
import com.snipsnap.synth.FlotillaPatch
import com.snipsnap.synth.Corolla
import com.snipsnap.synth.CorollaVoice
import com.snipsnap.synth.CorollaPatch
import com.snipsnap.synth.FlotillaVoice
import com.snipsnap.synth.Cistern
import com.snipsnap.synth.CisternPatch
import com.snipsnap.synth.CisternVoice
import com.snipsnap.synth.Murk
import com.snipsnap.synth.MurkPatch
import com.snipsnap.synth.MurkVoice
import com.snipsnap.synth.Nimbus
import com.snipsnap.synth.NimbusPatch
import com.snipsnap.synth.NimbusVoice
import com.snipsnap.synth.Tessera
import com.snipsnap.synth.TesseraPatch
import com.snipsnap.synth.TesseraVoice
import com.snipsnap.synth.Pitchwheel
import com.snipsnap.synth.PitchwheelPatch
import com.snipsnap.synth.PitchwheelVoice
import com.snipsnap.synth.Murmur
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurVoice
import com.snipsnap.synth.Trio
import com.snipsnap.synth.TrioPatch
import com.snipsnap.synth.TrioVoice
import com.snipsnap.synth.OpenSky
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OpenSkyPresets
import com.snipsnap.synth.OpenSkyVoice
import com.snipsnap.synth.Osprey
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.OspreyVoice
import com.snipsnap.synth.Oven
import com.snipsnap.synth.OvenPatch
import com.snipsnap.synth.OvenVoice
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.Stoop
import com.snipsnap.synth.StoopPatch
import com.snipsnap.synth.StoopVoice
import com.snipsnap.synth.Lacquer
import com.snipsnap.synth.LacquerPatch
import com.snipsnap.synth.LacquerVoice
import com.snipsnap.synth.Lattice
import com.snipsnap.synth.LatticePatch
import com.snipsnap.synth.LatticeVoice
import com.snipsnap.synth.Skitter
import com.snipsnap.synth.SkitterPatch
import com.snipsnap.synth.SkitterVoice
import com.snipsnap.synth.Shoal
import com.snipsnap.synth.ShoalMaterial
import com.snipsnap.synth.ShoalPatch
import com.snipsnap.synth.ShoalVoice
import com.snipsnap.synth.Tangle
import com.snipsnap.synth.TanglePatch
import com.snipsnap.synth.TangleVoice
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreaseVoice
import com.snipsnap.synth.Overlap
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.OverlapTopology
import com.snipsnap.synth.Relay
import com.snipsnap.synth.RelayPatch
import com.snipsnap.synth.RelayVoice
import com.snipsnap.synth.Embouchure
import com.snipsnap.synth.EmbouchurePatch
import com.snipsnap.synth.EmbouchureVoice
import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.synth.Thaw
import com.snipsnap.synth.ThawPatch
import com.snipsnap.synth.ThawVoice
import com.snipsnap.synth.Undertow
import com.snipsnap.synth.UndertowPatch
import com.snipsnap.synth.UndertowVoice
import com.snipsnap.synth.Suture
import com.snipsnap.synth.SuturePatch
import com.snipsnap.synth.SutureVoice
import com.snipsnap.synth.Spool
import com.snipsnap.synth.SpoolPatch
import com.snipsnap.synth.SpoolVoice
import com.snipsnap.synth.Revel
import com.snipsnap.synth.RevelVoice
import com.snipsnap.synth.RevelPatch
import com.snipsnap.synth.Fork
import com.snipsnap.synth.ForkPatch
import com.snipsnap.synth.ForkVoice
import com.snipsnap.synth.Circuit
import com.snipsnap.synth.CircuitPatch
import com.snipsnap.synth.CircuitVoice
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.ObbligatoRelationship
import com.snipsnap.synth.Alloy
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.AlloyVoice
import com.snipsnap.synth.Registers
import com.snipsnap.synth.RegistersPatch
import com.snipsnap.synth.RegistersVoice
import com.snipsnap.synth.Vent
import com.snipsnap.synth.VentPatch
import com.snipsnap.synth.VentVoice
import java.io.File
import java.util.Locale
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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

// The keys SYNTH writes into its `settings` bag (docs/SCREEN_PLAN_2026_10.md
// §3.6, the MAKE checklist's last item). The bag is the navigation model's
// MakeState for this lab: it outlives the composable, so the player's work
// survives leaving to hear a pad and coming back. Everything is a string —
// enum NAMES, floats via toString, recipes as JSON — so the navigation
// model never learns an engine.
private const val SETTINGS_ENGINE = "engine"
private const val SETTINGS_VOICE = "voice"
private const val SETTINGS_MACRO_PREFIX = "macro."
private const val SETTINGS_PRESET_PREFIX = "preset."
private const val SETTINGS_SHOAL_PATCH_PREFIX = "shoal.patch."

private fun shoalPatchSettingsKey(voice: ShoalVoice): String = "$SETTINGS_SHOAL_PATCH_PREFIX${voice.name}"

private fun storedShoalPatch(settings: Map<String, String>, voice: ShoalVoice): ShoalPatch? =
    settings[shoalPatchSettingsKey(voice)]?.let { text ->
        runCatching { ShoalPatch.fromJsonText(text) }.getOrNull()?.takeIf { it.voice == voice }
    }

private const val SETTINGS_ORPHICA_PREFIX = "patch.ORPHICA."

private fun storedOrphicaPatch(settings: Map<String, String>, material: OrphicaMaterial): OrphicaPatch? =
    settings["$SETTINGS_ORPHICA_PREFIX${material.name}"]?.let { json ->
        runCatching { OrphicaPatch.fromJsonText(json) }.getOrNull()?.takeIf { it.config.material == material }
    }

private const val SETTINGS_OVEN_PATCH_PREFIX = "patch.OVEN."

private fun macroSettingsKey(engine: Engine, voice: Enum<*>, macro: String): String =
    "$SETTINGS_MACRO_PREFIX${engine.name}.${voice.name}.$macro"

private fun presetSettingsKey(engine: Engine, voice: Enum<*>): String =
    "$SETTINGS_PRESET_PREFIX${engine.name}.${voice.name}"

/** A stale, invalid or differently tagged recipe in the lab bag falls back to the usual defaults. */
private fun storedOvenPatches(settings: Map<String, String>): Map<Pair<Engine, Enum<*>>, OvenPatch> =
    buildMap {
        for (voice in OvenVoice.entries) {
            val text = settings["$SETTINGS_OVEN_PATCH_PREFIX${voice.name}"] ?: continue
            val patch = runCatching { Patches.fromJsonText(text) }.getOrNull() as? OvenPatch ?: continue
            if (patch.voice == voice) put(Engine.OVEN to voice, patch)
        }
    }

/**
 * The highlighted-preset names the bag holds, by (engine, voice). A name the
 * bag keeps for an engine or voice this build no longer has is simply not
 * found here — a stale bag falls back to nothing highlighted, never throws.
 */
private fun storedPresetNames(settings: Map<String, String>): Map<Pair<Engine, Enum<*>>, String> {
    if (settings.isEmpty()) return emptyMap()
    val found = LinkedHashMap<Pair<Engine, Enum<*>>, String>()
    for (e in Engine.entries) {
        for (v in e.voices()) {
            val name = settings[presetSettingsKey(e, v)]
            if (!name.isNullOrEmpty()) found[e to v] = name
        }
    }
    return found
}

/** A tapped preset, made editable — the one canonicalisation `loadPreset` and the restore below share. */
private fun editablePreset(patch: Patch): Patch = if (patch is RegistersPatch) patch.forEditing() else patch

/**
 * The slider defaults a loaded patch fills its missing macros from: its own
 * model/configuration's defaults where those differ by recipe (ORPHICA, OSPREY,
 * OVERLAP, CREASE), the engine's [base] otherwise. Shared by `loadPreset` and
 * by reopening a pad, so both put a saved recipe on the same sliders.
 */
private fun editableDefaults(base: Map<String, Float>, editable: Patch): Map<String, Float> = when (editable) {
    is OrphicaPatch -> Orphica.macrosFrom(editable.config)
    is OspreyPatch -> Osprey.defaults(editable.voice, editable.modelVersion)
    is OverlapPatch -> Overlap.defaults(editable.topology, editable.model)
    is CreasePatch -> Crease.defaults(editable.voice, editable.model)
    else -> base
}

/**
 * SYNTH — the drum/tonal-synthesis lab: pick an engine, pick a
 * voice, shape it with macro sliders, SCRAMBLE it, watch the scope, audition
 * it, and land it on a pad. `synth/` is the tested engine layer; this is the
 * Compose surface plus the SEND TO PAD action, multiplexed over the
 * registered engines via the file-private [Engine] adapter below.
 *
 * `prototype/thumplab.html` is the interaction truth this ports: every
 * macro/voice/SCRAMBLE change re-renders and retrigger-plays the audition
 * voice (its own on-screen label says so — "EVERY MOVE RE-RENDERS +
 * RETRIGGERS"), debounced so a drag doesn't hammer the DSP. `design/
 * HANDOFF.md`'s SYNTH row says "5 voices" — that's roadmap-era and THUMP-
 * only; reality wins: THUMP alone ships eight voices, and the other
 * registered engines, including ALLOY, CIRCUIT, THAW, MURK, NIMBUS, UNDERTOW, TESSERA, PITCHWHEEL, PRISM, CONTOUR, STOOP, SKITTER, RELAY, REGISTERS, OVEN, MURMUR and TRIO, join it here.
 * GRAINS is out of scope — it has no voice enum, a different shape entirely.
 *
 * One copy carve-out remains: SCRAMBLE has no toast (the prototype's
 * `SCRAMBLE_LINES` are prototype-only flavour, never ported to `Copy`).
 * SEND TO PAD's own landing line used to be a second carve-out — a plain
 * inline sentence, because neither `Copy.treated` nor `Copy.INSTRUMENT_MADE`
 * is semantically "a synth patch landed on this pad" — but a copy-
 * consolidation pass gave it its own line, `Copy.synthSent`, rather than
 * leaving it outside every law in `PersonalityTest`.
 *
 * [settings] is this lab's bag in the navigation model's MAKE state
 * (`MakeState.settings`, §3.6): leaving to hear a pad and coming back used to
 * reset the lab, and this is what keeps it. The lab stays the owner of its
 * live state — it seeds its `remember`ed vars from the bag when it composes
 * and writes them back as they change; the bag is not Compose state. It
 * holds the player's work, as strings, under these keys:
 *  - `engine` — the engine's name, e.g. `RESIN`.
 *  - `voice` — the voice's name within that engine, e.g. `KICK`.
 *  - `macro.<ENGINE>.<VOICE>.<MACRO>` — one macro slider's value (a float,
 *    0..1) for every (engine, voice) the player has touched.
 *  - `preset.<ENGINE>.<VOICE>` — the name of the preset (factory or YOURS)
 *    last tapped on that voice and not edited since.
 *  - `workingRecipe.<ENGINE>.<VOICE>` — the complete reopened pad recipe,
 *    edited macros and optional shelf-relative source kit/slot. Reopened
 *    pads retain their full working recipe between visits.
 *  - `recipe.<ENGINE>.<VOICE>` — the loaded patch's complete recipe, kept
 *    after edits clear its preset highlight. The macro keys above retain
 *    the current slider values independently of that loaded recipe.
 *  - `patch.ORPHICA.<MATERIAL>` — ORPHICA's complete editable recipe, keeping
 *    its mode, root and capture with its macro values across navigation.
 *  - `patch.OVEN.<VOICE>` — OVEN's complete current recipe, including the
 *    saved root, strike velocity and model after the preset has been edited.
 *    Where a `patch.` entry and a `recipe.` entry both exist for a voice,
 *    the `patch.` one is the fresher and is adopted.
 *  - `shoal.patch.<VOICE>` — the complete Shoal recipe, retaining material,
 *    synthesis model, pitch and velocity after edits clear a preset highlight.
 *    It too is fresher than a `recipe.` entry for that voice and is adopted.
 * The default is an empty map, so every other call site, and the device
 * tests, compose the lab from factory defaults as before.
 *
 * The audition and sheets are not kept.
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
    // MAKE INSTRUMENT writes beside the kits; App's instrument
    // list is not reactive to that folder, so this tells it to re-read -
    // PadSheetScreen's own parameter of the same name, for the same reason.
    onShelfAssetWritten: () -> Unit,
    // DRONE TO LOOP (RESIN): the drone's recipe on its root, handed to App,
    // whose sendSnipToLoop already owns the grid's sidecar and its one writer.
    onDroneToLoop: (name: String, recipe: JsonValue, rootMidi: Int) -> Unit,
    // → SURFACE (SIREN, a LOOP): the pad has landed and surface.json points
    // at it; App switches to the SURFACE screen, which reads that file as
    // it opens. Navigation is App's, the same way every MenuRow tap is.
    onOpenSurface: () -> Unit,
    // App()'s own scope — the same one PadSheetScreen/PadCaptureScreen
    // receive as their own `appScope` — so a SEND TO PAD write in flight
    // survives a MenuRow tab switch instead of being cancelled by it (see
    // state-findings.md #3: this screen used to take its own locally-scoped
    // `rememberCoroutineScope()` for exactly this write, unlike every
    // sibling overlay).
    appScope: CoroutineScope,
    // This lab's bag in the navigation model's MAKE state (see the KDoc).
    settings: MutableMap<String, String> = mutableMapOf(),
) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()

    val seededWork = remember {
        SynthWork.read(settings).mapNotNull { work ->
            val patch = work.recipe.patch ?: return@mapNotNull null
            val sourceEngine = Engine.entries.firstOrNull { it.name == patch.engine } ?: return@mapNotNull null
            val sourceVoice = sourceEngine.voices().firstOrNull { it.name == patch.voiceName } ?: return@mapNotNull null
            (sourceEngine to sourceVoice) to work
        }.toMap()
    }
    // Seeded from the bag (§3.6) with the factory start as the fallback: a
    // name the bag holds that this build cannot resolve is ignored, and a
    // voice the restored engine does not have falls back to its first.
    var engine by remember {
        mutableStateOf(Engine.entries.firstOrNull { it.name == settings[SETTINGS_ENGINE] } ?: Engine.THUMP)
    }
    var voice by remember {
        mutableStateOf<Enum<*>>(
            engine.voices().firstOrNull { it.name == settings[SETTINGS_VOICE] } ?: engine.voices().first(),
        )
    }
    LaunchedEffect(engine) { settings[SETTINGS_ENGINE] = engine.name }
    LaunchedEffect(voice) { settings[SETTINGS_VOICE] = voice.name }
    val seededOvenPatches = remember { storedOvenPatches(settings) }
    // Per-(engine, voice) macro state, seeded with factory defaults and kept
    // for the life of the screen: switching engine or voice (or coming back
    // to one) restores whatever was last touched on it, same as the
    // prototype's `state.macros` map — not a fresh set of defaults every
    // time. Populated eagerly for every (engine, voice) pair up front, same
    // idiom the THUMP-only screen used for its own eight voices — cheap
    // (one entry per engine and voice) and means no read site
    // ever has to defend against a missing key.
    val macrosByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, Map<String, Float>>().apply {
            for (e in Engine.entries) {
                for (v in e.voices()) {
                    val orphica = if (e == Engine.ORPHICA) {
                        storedOrphicaPatch(settings, v as OrphicaMaterial)
                            ?: (Presets.forVoice(e.name, v.name).firstOrNull {
                                it.name == settings[presetSettingsKey(e, v)]
                            } as? OrphicaPatch)
                    } else null
                    val defaults = if (orphica != null) Orphica.macrosFrom(orphica.config) + orphica.macros
                        else e.defaults(v)
                    val recipe = seededOvenPatches[e to v]
                    // The bag's value where it parses, the default where it
                    // does not (§3.6): an unparseable or non-finite string
                    // is a stale bag, never a crash.
                    // A reopened pad's working recipe is the freshest statement of
                    // its macros; a complete OVEN recipe is next: its legacy
                    // macro keys may predate an edit followed by a voice switch.
                    val workingPatch = seededWork[e to v]?.recipe?.patch?.let(::editablePreset)
                    put(e to v, when {
                        workingPatch != null -> editableDefaults(e.defaults(v), workingPatch) + workingPatch.macros
                        recipe != null -> defaults + recipe.macros
                        settings.isEmpty() || orphica != null -> defaults
                        else -> defaults.mapValues { (name, fallback) ->
                            settings[macroSettingsKey(e, v, name)]?.toFloatOrNull()
                                ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback
                        }
                    })
                }
            }
        }
    }
    // FLOTILLA, CISTERN, PITCHWHEEL, STOOP, LACQUER, SKITTER, TANGLE, SHOAL, CREASE, LATTICE, EMBOUCHURE, BELLOWS, MURMUR and OSPREY carry note and velocity outside the macro map;
    // OBBLIGATO also retains its renderer model and independent-player seed.
    // REVEL also retains its microphones, phrase and seed configuration.
    // REGISTERS, OVERLAP, CREASE and LATTICE retain their synthesis model, saved pitch and velocity.
    // OVEN likewise retains its saved root, strike velocity and material model.
    // PRISM also preserves its spectral/envelope template and strike velocity,
    // ALLOY its synthesis configuration, CONTOUR its authored table pair and
    // trajectory configuration, SPOOL its tape-machine configuration and
    // velocity, SKITTER its noise seed, RELAY its generated sources and phrase
    // schedules, EMBOUCHURE and BELLOWS their player gestures, ONSET its renderer version, and MURMUR its operator routing, formant
    // targets and envelope definitions. OSPREY retains its variation seed, finite duration
    // and model version. VENT keeps its note, velocity, trajectory seed and saved model.
    // FLOCK retains its explicit note, velocity, full-width
    // variation seed and finite renderer model. OPENSKY retains its authored timbre, note,
    // velocity, gate, effect mode and model. ORPHICA retains its material, mode,
    // capture, seed and physical grain configuration. TRIO retains its seeded performance
    // and model. Keep that state through preview, editing, saving and placing by
    // editing the loaded patch rather than constructing anew.
    // A working recipe survives independently of its preset highlight:
    // editing a slider must not discard saved pitch, velocity or model on
    // a trip to the grid. Older bags fall back to their named factory
    // preset; a YOURS preset is adopted when its shelf read lands below.
    val seededPresets = remember { storedPresetNames(settings) }
    val loadedPatchesByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, Patch>().apply {
            for (patch in SynthDrafts.read(settings)) {
                val e = Engine.entries.firstOrNull { it.name == patch.engine } ?: continue
                val v = e.voices().firstOrNull { it.name == patch.voiceName } ?: continue
                put(e to v, editablePreset(patch))
            }
            for ((pair, name) in seededPresets) {
                if (containsKey(pair)) continue
                val factory = Presets.forVoice(pair.first.name, pair.second.name).firstOrNull { it.name == name }
                if (factory != null) put(pair, editablePreset(factory))
            }
            // A reopened pad's working recipe replaces the draft and preset
            // restores above. SHOAL, OVEN and ORPHICA then re-read their own
            // complete snapshots below; those are written from this same loaded
            // patch, so they carry the same recipe.
            for ((pair, work) in seededWork) put(pair, editablePreset(requireNotNull(work.recipe.patch)))
            for (v in ShoalVoice.entries) {
                val pair = Engine.SHOAL to v
                val restored = storedShoalPatch(settings, v)
                if (restored != null) {
                    // The complete snapshot is authoritative for material, model,
                    // pitch, energy and macros, even after a preset was edited.
                    put(pair, restored)
                    macrosByVoice[pair] = Shoal.defaults(v) + restored.macros
                } else if (!containsKey(pair)) {
                    val values = macrosByVoice.getValue(pair)
                    val oldMacroState = Shoal.macrosFor(v).any { settings.containsKey(macroSettingsKey(Engine.SHOAL, v, it.name)) }
                    if (oldMacroState || Shoal.isLoop(values["HOLD"] ?: 0f)) {
                        // Earlier bags stored macros alone. Those were harmonic
                        // notes; a restored HOLD must never enter finite WOOD.
                        put(pair, ShoalPatch(Engine.SHOAL.patchDisplayName(v), v, values,
                            model = Shoal.HARMONIC_MODEL_VERSION, material = ShoalMaterial.HARMONIC))
                    }
                }
                val patch = get(pair) as? ShoalPatch
                if (patch != null && !Shoal.supportsHold(patch.model, patch.material)) {
                    macrosByVoice[pair] = macrosByVoice.getValue(pair) + ("HOLD" to 0f)
                }
            }
            putAll(seededOvenPatches)
            for (material in OrphicaMaterial.entries) {
                storedOrphicaPatch(settings, material)?.let { put(Engine.ORPHICA to material, it) }
            }
        }
    }
    val macros = macrosByVoice.getValue(engine to voice)
    // Every macro write (a slider, a preset, SCRAMBLE) lands on the current
    // (engine, voice), so this is the one place the bag needs to follow; the
    // pairs the player left earlier already wrote theirs when they were open.
    LaunchedEffect(engine, voice, macros) {
        for ((name, value) in macros) settings[macroSettingsKey(engine, voice, name)] = value.toString()
    }
    val loadedRecipes = loadedPatchesByVoice.toMap()
    LaunchedEffect(loadedRecipes) {
        SynthDrafts.write(settings, loadedRecipes.values)
    }
    // Reopened pads retain the whole processing recipe, including its explicit
    // alias flag and named treatment. Loading a preset starts a new recipe.
    val loadedRecipesByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, PadRecipe>().apply {
            for ((pair, work) in seededWork) put(pair, work.recipe)
        }
    }
    val reopenedPadsByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, SynthWork.Source>().apply {
            for ((pair, work) in seededWork) work.source?.let { put(pair, it) }
        }
    }
    fun persistReopenedWork() {
        SynthWork.replace(settings, loadedRecipesByVoice.map { (pair, recipe) ->
            val patch = requireNotNull(loadedPatchesByVoice[pair])
            SynthWork.Working(
                // ORPHICA's sliders include controls derived from its config;
                // keep only real overrides, as buildCurrentPatch does.
                recipe.copy(patch = if (patch is OrphicaPatch) orphicaPatchForControls(patch, patch.name, macrosByVoice.getValue(pair))
                    else Patches.edited(patch, patch.name, macrosByVoice.getValue(pair))),
                reopenedPadsByVoice[pair],
            )
        })
    }
    val recipesSnapshot = loadedRecipesByVoice.toMap()
    val patchesSnapshot = loadedPatchesByVoice.toMap()
    val macroSnapshot = macrosByVoice.toMap()
    val sourcesSnapshot = reopenedPadsByVoice.toMap()
    LaunchedEffect(recipesSnapshot, patchesSnapshot, macroSnapshot, sourcesSnapshot) { persistReopenedWork() }
    // A navigation tap can dispose the bench before the state effect runs.
    // These maps remain the live session maps, so flush the final edit here.
    DisposableEffect(Unit) { onDispose { persistReopenedWork() } }
    val loadedPatch = loadedPatchesByVoice[engine to voice]
    val loadedRecipe = loadedRecipesByVoice[engine to voice]
    fun buildCurrentPatch(name: String): Patch = loadedPatch?.let {
        if (it is OrphicaPatch) orphicaPatchForControls(it, name, macros)
        else Patches.edited(it, name, macros)
    } ?: engine.buildPatch(name, voice, macros)
    // Keep complete Shoal recipes for every voice, rather than only the currently
    // selected preset name. Material and legacy model survive ordinary edits and
    // tab/voice switches alongside independent root and playing energy.
    val shoalSettingsPatches = ShoalVoice.entries.associateWith { v ->
        val values = macrosByVoice.getValue(Engine.SHOAL to v)
        val saved = loadedPatchesByVoice[Engine.SHOAL to v] as? ShoalPatch
        saved?.withMacros(values) ?: ShoalPatch(Engine.SHOAL.patchDisplayName(v), v, values)
    }
    LaunchedEffect(shoalSettingsPatches) {
        for ((v, patch) in shoalSettingsPatches) settings[shoalPatchSettingsKey(v)] = patch.toJsonText()
    }
    LaunchedEffect(engine, voice, loadedPatch, macros) {
        if (engine == Engine.ORPHICA) {
            settings["$SETTINGS_ORPHICA_PREFIX${voice.name}"] = buildCurrentPatch(engine.patchDisplayName(voice)).toJsonText()
        }
    }
    // Which preset (if any) the current macro values for this (engine,
    // voice) still match — U1's own framing (`docs/SYNTH_UPGRADE.md`) is
    // "a starting point to wreck, not a locked sound", so this clears the
    // moment a slider or SCRAMBLE moves the macros away from what was
    // loaded, rather than keep highlighting a name that no longer
    // describes the sound. Not pre-seeded like [macrosByVoice] — nothing
    // is "loaded" until a tap picks one.
    val currentPresetByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, String>().apply {
            putAll(seededPresets)
            seededWork.keys.forEach { remove(it) }
        }
    }
    // A preset can be cleared or set on a voice that is not showing (a FORGET
    // on another voice's chip, a save), so the bag takes the whole map.
    val presetNames = currentPresetByVoice.toMap()
    LaunchedEffect(presetNames) {
        settings.keys.removeAll { it.startsWith(SETTINGS_PRESET_PREFIX) }
        for ((pair, name) in presetNames) settings[presetSettingsKey(pair.first, pair.second)] = name
    }
    // OVEN's relative TUNE alone cannot recover an edited root or strike.
    // Keep its full recipe in this lab's existing bag, including voices left
    // earlier in the visit. Other engines retain their established bag format.
    val ovenRecipes = OvenVoice.entries.associate { ovenVoice ->
        val pair = Engine.OVEN to ovenVoice
        val controls = macrosByVoice.getValue(pair)
        val patch = loadedPatchesByVoice[pair] as? OvenPatch
            ?: OvenPatch(Engine.OVEN.patchDisplayName(ovenVoice), ovenVoice, controls)
        ovenVoice to Patches.edited(patch, currentPresetByVoice[pair] ?: patch.name, controls).toJsonText()
    }
    LaunchedEffect(ovenRecipes) {
        settings.keys.removeAll { it.startsWith(SETTINGS_OVEN_PATCH_PREFIX) }
        for ((ovenVoice, recipe) in ovenRecipes) settings["$SETTINGS_OVEN_PATCH_PREFIX${ovenVoice.name}"] = recipe
    }
    // The prototype gates its very first sound behind a "TAP TO POWER ON"
    // veil — a Web Audio autoplay-policy workaround, not part of the actual
    // interaction — and only *after* that makes every move retrigger. This
    // is the Android equivalent: the scope still renders and draws the
    // instant the screen opens, but nothing is heard until the first real
    // touch (a slider drag, a voice pick, an engine cycle, SCRAMBLE), so
    // landing on SYNTH never plays a kick unasked.
    var touched by remember { mutableStateOf(false) }
    fun macroRange(name: String): ClosedFloatingPointRange<Float> {
        if (name != "TUNE") return 0f..1f
        return when (val patch = loadedPatch) {
            is EmbouchurePatch -> 0f..((Embouchure.midiRange(patch.voice).last - patch.midi) / Embouchure.TUNE_SEMITONES.toFloat())
            is BellowsPatch -> 0f..((Bellows.midiRange(patch.voice).last - patch.midi) / Bellows.TUNE_SEMITONES.toFloat())
            else -> 0f..1f
        }
    }
    fun updateMacro(name: String, value: Float) {
        touched = true
        currentPresetByVoice.remove(engine to voice)
        macrosByVoice[engine to voice] = macrosByVoice.getValue(engine to voice) + (name to value.coerceIn(macroRange(name)))
    }
    fun updateStoopRoot(midi: Int) {
        val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as? StoopPatch ?: return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        // Copy the current recipe so transposition retains its source,
        // answering gesture, chord intervals and velocity.
        loadedPatchesByVoice[engine to voice] = patch.copy(midi = midi.coerceIn(Stoop.MIDI_MIN, Stoop.MIDI_MAX))
    }
    fun updateTrioPatch(change: (TrioPatch) -> TrioPatch) {
        val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as? TrioPatch ?: return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        loadedPatchesByVoice[engine to voice] = change(patch)
    }
    fun updateOrphicaConfig(change: (OrphicaConfig) -> OrphicaConfig) {
        val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as? OrphicaPatch ?: return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        loadedPatchesByVoice[engine to voice] = patch.copy(config = change(patch.config))
    }
    fun loadPreset(patch: Patch) {
        touched = true
        loadedRecipesByVoice.remove(engine to voice)
        reopenedPadsByVoice.remove(engine to voice)
        // Audition recipes can save an explicit root beside a different TUNE.
        // Canonicalize the sliders without changing that sound or its model.
        val editable = editablePreset(patch)
        loadedPatchesByVoice[engine to voice] = editable
        // Merged over the engine's own defaults, same as macrosByVoice's own
        // seeding above: a preset only has to name the macros it cares
        // about (PUNCH, added after every existing THUMP preset was
        // written, names none of them), and every read site here relies on
        // that "no missing key" invariant - a bare `patch.macros` would
        // otherwise leave PUNCH's slider crashing on getValue the moment
        // someone loaded a pre-PUNCH preset.
        // Physical ORPHICA settings, OSPREY's saved model, OVERLAP's saved
        // model and CREASE's saved model all select their displayed defaults;
        // loading a partial recipe keeps its sound.
        macrosByVoice[engine to voice] = editableDefaults(engine.defaults(voice), editable) + editable.macros
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
            // The YOURS presets the bag named (§3.6), now that the shelf has
            // been read: re-adopt each whole, or drop the highlight when the
            // preset was forgotten while the lab was away. Live state is
            // re-read here, after the await: a preset tapped meanwhile wins.
            for ((pair, name) in seededPresets) {
                if (currentPresetByVoice[pair] != name) continue
                val factory = Presets.byName(pair.first.name, pair.second.name, name)
                // SHOAL always holds a complete snapshot, so a YOURS name must still be adopted whole.
                if (loadedPatchesByVoice[pair] != null && (pair.first != Engine.SHOAL || factory != null)) {
                    // OVEN can retain a full edited recipe after its YOURS
                    // preset has left the shelf. Keep the sound, not a stale chip.
                    if (pair.first == Engine.OVEN && factory == null &&
                        UserPresets.forVoice(live, pair.first.name, pair.second.name).none { it.name == name }) {
                        currentPresetByVoice.remove(pair)
                    }
                    continue
                }
                val mine = UserPresets.forVoice(live, pair.first.name, pair.second.name)
                    .firstOrNull { it.patch.name == name }?.patch
                if (mine != null) {
                    val editable = editablePreset(mine)
                    loadedPatchesByVoice[pair] = editable
                    if (editable is ShoalPatch) macrosByVoice[pair] = Shoal.defaults(editable.voice) + editable.macros
                    // The named preset is being restored whole. A partial legacy
                    // OVERLAP recipe must fill its sliders from its saved model.
                    if (mine is OverlapPatch) {
                        macrosByVoice[pair] = Overlap.defaults(mine.topology, mine.model) + mine.macros
                    }
                } else currentPresetByVoice.remove(pair)
            }
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
        val patch = buildCurrentPatch(name)
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
        val heard = if (engine == Engine.ORPHICA || engine == Engine.OPENSKY) target else Cleanup.toMono(target)
        val v = TapeVoice(heard.samples, heard.sampleRate, channels = heard.channels)
        voicePlayer = v
        v.start(0)
    }

    // The debounced re-render + retrigger loop: LaunchedEffect's own key
    // change cancels whatever render was in flight and restarts the delay,
    // which is exactly the trailing-edge debounce `touched()` does by hand
    // with clearTimeout/setTimeout in the prototype. Keyed on `engine` too
    // now — switching engines must cancel an in-flight render exactly like
    // switching voices always has.
    LaunchedEffect(engine, voice, macros, loadedPatch, loadedRecipe) {
        delay(MACRO_DEBOUNCE_MS)
        val shimmerJob = launch { delay(RENDER_SHIMMER_DELAY_MS); rendering = true }
        try {
            val previewJob = coroutineContext[Job]
            val rendered = runInterruptible(Dispatchers.Default) {
                val patch = if (loadedPatch != null || engine == Engine.OPENSKY) buildCurrentPatch(engine.patchDisplayName(voice)) else null
                val dry = when {
                    patch is OpenSkyPatch -> patch.render {
                        previewJob?.isActive == false || Thread.currentThread().isInterrupted
                    }
                    patch != null -> patch.render()
                    else -> engine.render(voice, macros)
                }
                // A string machine (an unmoved STRING MACHINE / THIN STRINGS / WIDE
                // STRINGS / DARK STRINGS) previews through the ENSEMBLE it will land
                // with, so the audition is the pad and not the bare saw stack. Every
                // other sound, SIREN's echo included, previews dry as before. Inside
                // Default with the render: six sines a frame is not main-thread work.
                // A reopened pad auditions its saved processing, while the
                // native OPENSKY renderer above keeps its cancellation hook.
                if (loadedRecipe != null) loadedRecipe.process(dry)
                else Presets.landingFor(engine.name, voice.name, macros)?.process(dry) ?: dry
            }
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
    // → SURFACE opened the chooser: after the landing, point the kit's
    // surface at the slot and open the SURFACE screen on it. Read at the
    // start of the send, so a chooser opened one way and cancelled cannot
    // leak its intent into the next.
    var sendToSurface by remember { mutableStateOf(false) }
    val kit = entry?.kit
    var reopeningPad by remember { mutableStateOf(false) }
    val reopenablePads = kit?.pads.orEmpty().filter { pad ->
        val patch = Breed.recipeOf(pad)?.patch
        val sourceEngine = Engine.entries.firstOrNull { it.name == patch?.engine }
        pad.velocityLayers.isEmpty() && pad.chain == null &&
            patch != null && sourceEngine?.voices()?.any { it.name == patch.voiceName } == true
    }
    fun reopenPad(slot: Int) {
        val sourceEntry = entry ?: return
        val pad = sourceEntry.kit.pad(slot) ?: return
        val recipe = Breed.recipeOf(pad) ?: return
        val patch = recipe.patch ?: return
        val sourceEngine = Engine.entries.firstOrNull { it.name == patch.engine } ?: return
        val sourceVoice = sourceEngine.voices().firstOrNull { it.name == patch.voiceName } ?: return
        val key = sourceEngine to sourceVoice
        val editable = editablePreset(patch)
        loadedPatchesByVoice[key] = editable
        loadedRecipesByVoice[key] = recipe
        SynthWork.source(shelfRoot, sourceEntry.dir, slot)?.let { reopenedPadsByVoice[key] = it }
            ?: reopenedPadsByVoice.remove(key)
        macrosByVoice[key] = editableDefaults(sourceEngine.defaults(sourceVoice), editable) + editable.macros
        currentPresetByVoice.remove(key)
        touched = true
        engine = sourceEngine
        voice = sourceVoice
        reopeningPad = false
    }

    fun sendToSlot(slot: Int) {
        val e = entry ?: return
        if (sendBusy) return
        sendBusy = true
        val toSurface = sendToSurface
        appScope.launch {
            try {
                // The patch's own init validates its macros (Patches.
                // validateMacros) — practically unreachable given every
                // value here already came from a 0..1 slider or SCRAMBLE,
                // but constructing it inside the try means an unexpected
                // failure toasts honestly instead of crashing the screen.
                val source = reopenedPadsByVoice[engine to voice]
                val reopenedHere = source != null && source.slot == slot && source.matches(shelfRoot, e.dir)
                val name = if (reopenedHere) loadedPatch?.name ?: engine.patchDisplayName(voice)
                    else engine.patchDisplayName(voice)
                val patch = buildCurrentPatch(name)
                // A SIREN one-shot carries the rack's ECHO in its recipe and
                // renders through it; a SIREN LOOP lands dry, since the
                // SURFACE's echo is its own (Siren.landingChain). An unmoved
                // string machine (VELVET / RESIN BRASS) carries its ENSEMBLE the
                // same way, found from the macros so a moved slider or a
                // player's own sound under a factory name lands dry
                // (Presets.landingFor). Every other sound lands with no chain,
                // exactly as before.
                val fx = if (engine == Engine.SIREN) Siren.landingChain(macros) else Presets.landingFor(engine.name, voice.name, macros)
                val cls = engine.drumClass(voice, macros)
                val (existed, updatedKit) = withContext(Dispatchers.IO) {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        // A held Murmur source is seamless, but a one-shot pad stops
                        // at its final frame. Save the delivery fade with that pad;
                        // gated pads and SURFACE keep the unfaded loop source.
                        val terminalFade = if (!toSurface && (model.pad(slot)?.oneShot ?: true) &&
                            patch is MurmurPatch && Murmur.isLoop(patch.macros["HOLD"] ?: 0f)
                        ) 8f else null
                        // A reopened pad keeps its whole processing recipe (alias flag,
                        // treatment, board); only the source and the delivery fade
                        // are re-stated from the sliders and the target pad.
                        val padRecipe = loadedRecipe?.copy(patch = patch, terminalFadeMs = terminalFade)
                            ?: PadRecipe(patch = patch, fx = fx, terminalFadeMs = terminalFade)
                        val recipe = padRecipe.toJsonValue()
                        val rendered = padRecipe.render()
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
                            if (!reopenedHere) {
                                model.update(slot) { p ->
                                    p.copy(
                                        displayName = name,
                                        drumClass = cls,
                                        colorHex = AutoPlace.colorFor(cls),
                                        muteGroup = AutoPlace.muteGroupFor(cls),
                                    )
                                }
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
                if (toSurface) {
                    // The pad is on disk; now the surface's own sidecar says
                    // which pad it plays (SurfaceStore.choosePad keeps the
                    // corners and the rest), and App opens the screen. The
                    // SURFACE screen is not showing while SYNTH is, so nothing
                    // else writes surface.json underneath this.
                    withContext(Dispatchers.IO) { SurfaceStore.choosePad(e.dir, slot) }
                    onToast(Copy.sirenToSurface(padTag(slot), name, replaced = existed))
                    onOpenSurface()
                    return@launch
                }
                // Copy.synthSent: only the REPLACE branch bins anything —
                // `replaceAudio` moves the displaced WAV to the bin
                // (`moveToBin`), the same fact `Copy.treated`'s "ORIGINAL
                // SLEEPS IN THE BIN" states for TREATMENT — `assign`'s own
                // `deleteIfUnreferenced` is a no-op on an empty slot (there
                // was no original), so the ADD branch doesn't claim it.
                onToast(
                    if (engine == Engine.SIREN) {
                        Copy.sirenSent(padTag(slot), name, replaced = existed, loop = Siren.isLoop(macros.getValue("HOLD")))
                    } else {
                        Copy.synthSent(padTag(slot), name, replaced = existed)
                    },
                )
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
                sendToSurface = false
            }
        }
    }

    // ---- MAKE INSTRUMENT: held synths (BELLOWS among them) and ORPHICA's captured sustain ----
    // docs/superpowers/specs/2026-09-25-resin-held-pad-design.md (RESIN),
    // docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md, door 3
    // (SIREN) and docs/superpowers/specs/2026-09-29-glint-paths-design.md §3
    // (GLINT). Zones render off the main thread, with OPENSKY's larger
    // wet-state renders serialized and the other makers in parallel. A
    // progress bar stays live (the author's call: a slow phone shows
    // work, it does not drop zones); the package is written once every zone
    // has landed, so a CANCEL mid-render leaves nothing on the shelf.
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
    val supportsHeldInstrument = engine == Engine.ORPHICA || engine == Engine.RESIN || engine == Engine.SIREN || engine == Engine.GLINT || engine == Engine.REGISTERS || engine == Engine.BELLOWS ||
        (engine == Engine.OPENSKY && OpenSkyPadMaker.supports(voice as OpenSkyVoice))
    val heldInstrumentReady = when (engine) {
        Engine.OPENSKY -> OpenSkyPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch)
        Engine.BELLOWS -> BellowsPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as BellowsPatch)
        else -> true
    }
    val holdBase = currentPresetByVoice[engine to voice] ?: "${engine.name} ${voice.name}"
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

    // MAKE INSTRUMENT's engines behind one shape, so the render/export
    // orchestration below (identical for all) is written once: RESIN's
    // spec carries an ATTACK; SIREN's LOOP render has none to carry
    // (Keys.sirenPad's own KDoc) and neither does GLINT's (its onset and
    // breath come from the patch). OPENSKY HALO likewise keeps its authored
    // onset; REGISTERS retains its speaking attack before the verified
    // periodic sustain. These specs expose only a release control.
    fun heldSpec(): HeldSpec? = when (engine) {
        Engine.RESIN -> HeldSpec.Resin(ResinPadMaker.spec(voice as ResinVoice, macros, holdAttack, holdRelease))
        Engine.SIREN -> HeldSpec.Siren(SirenPadMaker.spec(voice as SirenVoice, macros, holdRelease))
        Engine.GLINT -> HeldSpec.Glint(GlintPadMaker.spec(voice as GlintVoice, macros, holdRelease))
        Engine.ORPHICA -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as OrphicaPatch
            HeldSpec.Orphica(OrphicaPadMaker.spec(patch.config, patch.macros, holdRelease))
        }
        Engine.OPENSKY -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch
            if (OpenSkyPadMaker.supports(patch)) HeldSpec.OpenSky(OpenSkyPadMaker.spec(patch, holdRelease)) else null
        }
        Engine.REGISTERS -> HeldSpec.Registers(RegistersPadMaker.spec(
            buildCurrentPatch(engine.patchDisplayName(voice)) as RegistersPatch, holdRelease,
        ))
        Engine.BELLOWS -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as BellowsPatch
            if (BellowsPadMaker.supports(patch)) HeldSpec.Bellows(BellowsPadMaker.spec(patch, holdRelease)) else null
        }
        else -> null
    }

    fun previewHeld() {
        if (holdDone >= 0 || holdPreviewing) return
        val spec = heldSpec() ?: return
        holdPreviewing = true
        holdJob = appScope.launch {
            try {
                // CANCEL cancels this job; the render asks and stops.
                val heard = withContext(Dispatchers.Default) { spec.preview { !isActive } }
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
        if (holdDone >= 0 || holdPreviewing) return
        // Captured now: the sheet's own sound, before anything can move it.
        val spec = heldSpec() ?: return
        val base = holdBase
        val midis = spec.zoneMidis
        holdTotal = midis.size
        holdDone = 0
        holdJob = appScope.launch {
            try {
                // coroutineScope, not bare asyncs on appScope: a zone that
                // throws must surface here as an exception this catch sees,
                // not fail the app's own scope.
                val notes = coroutineScope {
                    if (spec is HeldSpec.OpenSky || spec is HeldSpec.Bellows) {
                        // OPENSKY has larger wet state; BELLOWS renders three
                        // velocity layers per zone. Render serially so phones never hold nine
                        // independent oversampled states at once; CANCEL and
                        // the same zone counter remain live between them.
                        midis.map { midi ->
                            val note = withContext(Dispatchers.Default) {
                                spec.renderZone(midi) { !isActive }
                            }
                            withContext(Dispatchers.Main) { holdDone += 1 }
                            note
                        }
                    } else {
                        midis.map { midi ->
                            async(Dispatchers.Default) {
                                // CANCEL cancels the scope; each zone asks and stops,
                                // rather than all nine running on for seconds.
                                val note = spec.renderZone(midi) { !isActive }
                                withContext(Dispatchers.Main) { holdDone += 1 }
                                note
                            }
                        }.awaitAll()
                    }
                }
                // Past here the write lands even under CANCEL: a package half
                // written is worse than a whole one the player can bin.
                val made = withContext(NonCancellable + Dispatchers.IO) {
                    val name = OneNote.freshName(instrumentsDir, base)
                    spec.export(name, notes, instrumentsDir)
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

    // ---- DRONE TO LOOP: RESIN and SIREN, droning ----
    // docs/superpowers/specs/2026-09-25-resin-drone-design.md (RESIN) and
    // docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md, door 4
    // (SIREN). SEND puts a recipe on the grid, not audio: LOOP renders it at
    // its own tempo, so nothing here renders except PREVIEW, which renders
    // it the way LOOP will (the grid's tempo, bars and the device's rate)
    // and plays it twice, so the wrap is heard.
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

    // RESIN and SIREN behind one shape, the same division [HeldSpec] draws:
    // RESIN's own MOTION and BREATHS are knobs invented for the drone, since
    // RESIN's held macros carry no swing of their own; SIREN already *is* a
    // movement (RATE and DEPTH, dialed on the panel above), so its own
    // DroneSpec has no extra knob and [hasMotion] is false.
    fun droneSpec(): DroneSpec? = when (engine) {
        Engine.RESIN -> DroneSpec.Resin(voice as ResinVoice, macros, droneMotion, droneRate)
        Engine.SIREN -> DroneSpec.Siren(voice as SirenVoice, macros)
        else -> null
    }

    // The sheet's own readout. RESIN's own label is closed-form pitch
    // arithmetic (DroneMaker.label, effectively free); SIREN's own reads
    // its true carrier fit (SirenDroneMaker.label), an LFO phase integral
    // over the candidate span's own frame count — at the grid's slowest
    // tempo and longest bars this is real work, measured at ~850 ms per
    // voice (SirenDroneMakerTest's own "span and label finish quickly
    // even at the grid's slowest, longest setting"), so it runs off the
    // main thread rather than inline in the composable body, the same
    // reason droneSession itself does.
    var droneLabel by remember { mutableStateOf("…") }
    LaunchedEffect(droneOpen, droneRoot, droneSession, engine, voice, macros) {
        // Reset before recomputing, not after: root or macros just moved,
        // and the stale string is for a spec PREVIEW/SEND no longer read
        // off the moment either changed — showing it a beat longer would
        // name a note that is no longer the one either button plays.
        droneLabel = "…"
        val root = droneRoot
        val session = droneSession
        val spec = droneSpec()
        if (droneOpen && root != null && session != null && spec != null) {
            droneLabel = withContext(Dispatchers.Default) {
                // cancelled reaches SirenDrone.fitCarrier's own check: a
                // rapid run of ROOT taps must stop one computation before
                // starting the next, not pile them up on the pool that
                // runs them (review finding on PR #373). Falls back to
                // the placeholder on any other failure — a readout that
                // never lands is a worse sheet, not a crashed one; PREVIEW
                // and SEND key off root/session directly, never off this
                // string — but a cancellation is this effect's own
                // restart, not a failure, and must keep propagating.
                try {
                    spec.label(root, session) { !isActive }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SynthScreen", "drone label failed", e)
                    "…"
                }
            }
        }
    }

    LaunchedEffect(droneOpen, voice) {
        if (!droneOpen) return@LaunchedEffect
        val spec = droneSpec() ?: return@LaunchedEffect
        if (droneRoot?.let { it in spec.roots } != true) droneRoot = spec.defaultRoot(kit?.key)
        droneSession = withContext(Dispatchers.IO) {
            val rate = deviceSampleRate(context)
            val dir = LoopWrites.dir(context)
            runCatching { SessionStore.load(dir) }.getOrNull()?.copy(sampleRate = rate)
                ?: SessionBuilder.empty(rate)
        }
    }

    fun previewDrone() {
        val root = droneRoot ?: return
        val session = droneSession ?: return
        val spec = droneSpec() ?: return
        if (dronePreviewing) return
        dronePreviewing = true
        droneJob = appScope.launch {
            try {
                val heard = withContext(Dispatchers.Default) {
                    // CANCEL (or closing the sheet) cancels this job; the
                    // render asks and stops rather than running on for seconds.
                    val once = spec.render(root, session) { !isActive }
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
        val spec = droneSpec() ?: return
        val name = "${currentPresetByVoice[engine to voice] ?: "${engine.name} ${voice.name}"} DRONE"
        onDroneToLoop(name, spec.toJson(), root)
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
        val patch = buildCurrentPatch(engine.patchDisplayName(voice))
        val cls = engine.drumClass(voice, macros)
        scope.launch {
            try {
                val src = withContext(Dispatchers.Default) {
                    val rendered = if (patch is OpenSkyPatch) patch.render { !isActive } else patch.render()
                    // Authored roots survive chords, octave layers and upper
                    // shimmer that can outweigh the fundamental in detection.
                    val midi = when (patch) {
                        is OrphicaPatch -> Orphica.configWithMacros(patch.config, patch.macros).midi.toFloat()
                        else -> Spread.detect(rendered, patch)
                    }
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
                // deliberately left alone: each tap auditions the next engine.
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

                if (engine == Engine.SHOAL) TapeText("MOTION", TapeType.pixelSmall, scheme.ink2.tape)
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

                if (engine == Engine.STOOP) {
                    val root = (loadedPatch as? StoopPatch)?.midi ?: Stoop.DEFAULT_MIDI
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionButton(
                            "OCT −", scheme, enabled = root - 12 >= Stoop.MIDI_MIN,
                            accessibilityLabel = "ROOT OCTAVE DOWN", modifier = Modifier.weight(1f),
                        ) { updateStoopRoot(root - 12) }
                        ActionButton(
                            "−", scheme, enabled = root > Stoop.MIDI_MIN,
                            accessibilityLabel = "ROOT DOWN", modifier = Modifier.weight(1f),
                        ) { updateStoopRoot(root - 1) }
                        TapeText("ROOT ${Scales.nameOf(root)}", TapeType.pixel, scheme.ink.tape, Modifier.weight(1.4f))
                        ActionButton(
                            "+", scheme, enabled = root < Stoop.MIDI_MAX,
                            accessibilityLabel = "ROOT UP", modifier = Modifier.weight(1f),
                        ) { updateStoopRoot(root + 1) }
                        ActionButton(
                            "OCT +", scheme, enabled = root + 12 <= Stoop.MIDI_MAX,
                            accessibilityLabel = "ROOT OCTAVE UP", modifier = Modifier.weight(1f),
                        ) { updateStoopRoot(root + 12) }
                    }
                }
                // OVERLAP keeps its reference note, velocity and render model
                // beside the six controls. Copying the loaded recipe retains
                // those fields through slider edits, saving and pad placement.
                if (engine == Engine.OVERLAP) {
                    val overlapPatch = buildCurrentPatch(engine.patchDisplayName(voice)) as OverlapPatch
                    MacroSlider(
                        label = "ROOT",
                        value = (overlapPatch.midi - Overlap.MIN_MIDI).toFloat() / (Overlap.MAX_MIDI - Overlap.MIN_MIDI),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(overlapPatch.midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val midi = (Overlap.MIN_MIDI + value * (Overlap.MAX_MIDI - Overlap.MIN_MIDI)).roundToInt()
                                .coerceIn(Overlap.MIN_MIDI, Overlap.MAX_MIDI)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OverlapPatch).copy(midi = midi)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = overlapPatch.velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OverlapPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                    TapeText(
                        "ROOT IS THE REFERENCE NOTE. HOLD EXTENDS A FINITE SOUND.",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
                    )
                }

                // CREASE's five shape controls and HOLD leave pitch and playing
                // intensity in the saved recipe. Copy the current patch so a
                // legacy model keeps its timing through root/velocity edits.
                if (engine == Engine.CREASE) {
                    val creasePatch = buildCurrentPatch(engine.patchDisplayName(voice)) as CreasePatch
                    MacroSlider(
                        label = "ROOT",
                        value = (creasePatch.midi - Crease.MIDI_MIN).toFloat() / (Crease.MIDI_MAX - Crease.MIDI_MIN),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(creasePatch.midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val root = (Crease.MIDI_MIN + value * (Crease.MIDI_MAX - Crease.MIDI_MIN)).roundToInt()
                                .coerceIn(Crease.MIDI_MIN, Crease.MIDI_MAX)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as CreasePatch).copy(midi = root)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = creasePatch.velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as CreasePatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                    TapeText(
                        if (Crease.isLoop(macros.getValue("HOLD"))) "HOLD: REPEATING PRESSURE GESTURE."
                        else "HOLD: NOTE LENGTH · TOP STEP: LOOP.",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
                    )
                }

                if (engine == Engine.ORPHICA) {
                    val config = (loadedPatch as? OrphicaPatch)?.config
                        ?: OrphicaConfig(material = voice as OrphicaMaterial)
                    OrphicaModePicker(config.mode, scheme) { mode ->
                        updateOrphicaConfig { it.copy(mode = mode) }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionButton(
                            "OCT −", scheme, enabled = config.midi - 12 >= Orphica.MIDI_MIN,
                            accessibilityLabel = "ROOT OCTAVE DOWN", modifier = Modifier.weight(1f),
                        ) { updateOrphicaConfig { it.copy(midi = config.midi - 12) } }
                        ActionButton(
                            "−", scheme, enabled = config.midi > Orphica.MIDI_MIN,
                            accessibilityLabel = "ROOT DOWN", modifier = Modifier.weight(1f),
                        ) { updateOrphicaConfig { it.copy(midi = config.midi - 1) } }
                        TapeText("ROOT ${Scales.nameOf(config.midi)}", TapeType.pixel, scheme.ink.tape, Modifier.weight(1.4f))
                        ActionButton(
                            "+", scheme, enabled = config.midi < Orphica.MIDI_MAX,
                            accessibilityLabel = "ROOT UP", modifier = Modifier.weight(1f),
                        ) { updateOrphicaConfig { it.copy(midi = config.midi + 1) } }
                        ActionButton(
                            "OCT +", scheme, enabled = config.midi + 12 <= Orphica.MIDI_MAX,
                            accessibilityLabel = "ROOT OCTAVE UP", modifier = Modifier.weight(1f),
                        ) { updateOrphicaConfig { it.copy(midi = config.midi + 12) } }
                    }
                }

                // MURMUR has five timbral controls and HOLD. Its requested root and
                // gesture energy are independent patch fields, retained with the full
                // operator/formant definition through preview, saving and placement.
                if (engine == Engine.MURMUR) {
                    val murmurPatch = loadedPatch as? MurmurPatch
                    val midi = murmurPatch?.midi ?: Murmur.DEFAULT_MIDI
                    val velocity = murmurPatch?.velocity ?: 1f
                    MacroSlider(
                        label = "ROOT",
                        value = (midi - Murmur.MIN_MIDI).toFloat() / (Murmur.MAX_MIDI - Murmur.MIN_MIDI),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val root = (Murmur.MIN_MIDI + value * (Murmur.MAX_MIDI - Murmur.MIN_MIDI)).roundToInt()
                                .coerceIn(Murmur.MIN_MIDI, Murmur.MAX_MIDI)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as MurmurPatch).copy(midi = root)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as MurmurPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                }

                // OVEN's root and hammer energy are saved beside its six macros.
                // TUNE remains a relative ±12-semitone adjustment to this root.
                if (engine == Engine.OVEN) {
                    val ovenPatch = buildCurrentPatch(engine.patchDisplayName(voice)) as OvenPatch
                    MacroSlider(
                        label = "ROOT",
                        value = (ovenPatch.midi - Oven.MIDI_MIN).toFloat() / (Oven.MIDI_MAX - Oven.MIDI_MIN),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(ovenPatch.midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val midi = (Oven.MIDI_MIN + value * (Oven.MIDI_MAX - Oven.MIDI_MIN)).roundToInt()
                                .coerceIn(Oven.MIDI_MIN, Oven.MIDI_MAX)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OvenPatch).copy(midi = midi)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = ovenPatch.velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OvenPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                }

                // Shoal's root and excitation energy are independent of its six controls.
                if (engine == Engine.SHOAL) {
                    val shoalPatch = buildCurrentPatch(engine.patchDisplayName(voice)) as ShoalPatch
                    TapeText("MATERIAL", TapeType.pixelSmall, scheme.ink2.tape)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (material in ShoalMaterial.entries) {
                            val selectedMaterial = material == shoalPatch.material
                            ActionButton(
                                if (material == ShoalMaterial.HARMONIC) "HARM" else material.name,
                                scheme,
                                enabled = true,
                                lit = selectedMaterial,
                                modifier = Modifier.weight(1f).semantics { selected = selectedMaterial },
                                accessibilityLabel = "MATERIAL ${material.name}",
                            ) {
                                if (material != shoalPatch.material) {
                                    val current = buildCurrentPatch(engine.patchDisplayName(voice)) as ShoalPatch
                                    val nextMacros = if (Shoal.supportsHold(Shoal.MODEL_VERSION, material)) macros
                                        else macros + ("HOLD" to 0f)
                                    touched = true
                                    currentPresetByVoice.remove(engine to voice)
                                    macrosByVoice[engine to voice] = nextMacros
                                    loadedPatchesByVoice[engine to voice] = current.copy(
                                        macros = nextMacros, model = Shoal.MODEL_VERSION, material = material,
                                    )
                                }
                            }
                        }
                    }
                    TapeText(
                        if (Shoal.supportsHold(shoalPatch.model, shoalPatch.material))
                            "HARMONIC TONE CAN LOOP. WOOD, BOWL AND WIRE TURN HOLD OFF."
                        else "MATERIAL NOTES ARE FINITE. HOLD IS AVAILABLE FOR HARMONIC TONE.",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
                    )
                    MacroSlider(
                        label = "ROOT",
                        value = (shoalPatch.midi - Shoal.MIN_MIDI).toFloat() / (Shoal.MAX_MIDI - Shoal.MIN_MIDI),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(shoalPatch.midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val midi = (Shoal.MIN_MIDI + value * (Shoal.MAX_MIDI - Shoal.MIN_MIDI)).roundToInt()
                                .coerceIn(Shoal.MIN_MIDI, Shoal.MAX_MIDI)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as ShoalPatch).copy(midi = midi)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = shoalPatch.velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as ShoalPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                }

                // FLOCK's note and initial energy live beside its six macros.
                // Copy the full recipe so a note/velocity edit keeps its seed
                // and saved model, including a model-1 audition recipe.
                if (engine == Engine.FLOCK) {
                    val flockPatch = buildCurrentPatch(engine.patchDisplayName(voice)) as FlockPatch
                    MacroSlider(
                        label = "ROOT",
                        value = (flockPatch.midi - Flock.MIN_MIDI).toFloat() / (Flock.MAX_MIDI - Flock.MIN_MIDI),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(flockPatch.midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val midi = (Flock.MIN_MIDI + value * (Flock.MAX_MIDI - Flock.MIN_MIDI)).roundToInt()
                                .coerceIn(Flock.MIN_MIDI, Flock.MAX_MIDI)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as FlockPatch).copy(midi = midi)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = flockPatch.velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as FlockPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                    TapeText(
                        "FINITE GESTURE · HOLD SETS LENGTH",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
                    )
                }

                // Relay's touch changes excitation and brightness without replacing its
                // saved sources, handoff schedules or phrase clock.
                if (engine == Engine.RELAY) {
                    val relayPatch = loadedPatch as? RelayPatch
                    MacroSlider(
                        label = "VELOCITY",
                        value = relayPatch?.velocity ?: 1f,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as RelayPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                }

                // VENT's root and excitation strength live beside its six macros.
                // Edit the whole recipe so changing a control preserves the trajectory.
                if (engine == Engine.VENT) {
                    val ventPatch = buildCurrentPatch(engine.patchDisplayName(voice)) as VentPatch
                    MacroSlider(
                        label = "ROOT",
                        value = (ventPatch.midi - Vent.MIDI_MIN).toFloat() / (Vent.MIDI_MAX - Vent.MIDI_MIN),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(ventPatch.midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val midi = (Vent.MIDI_MIN + value * (Vent.MIDI_MAX - Vent.MIDI_MIN)).roundToInt()
                                .coerceIn(Vent.MIDI_MIN, Vent.MIDI_MAX)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as VentPatch).copy(midi = midi)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = ventPatch.velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as VentPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                }

                // SWELL is a finite sea gesture. ROOT and VELOCITY are recipe
                // fields beside its six macros; SECONDS bounds the complete render.
                if (engine == Engine.OSPREY) {
                    val ospreyPatch = loadedPatch as? OspreyPatch
                    val midi = ospreyPatch?.midi ?: Osprey.DEFAULT_MIDI
                    val velocity = ospreyPatch?.velocity ?: 1f
                    val seconds = ospreyPatch?.durationSeconds ?: 8f
                    MacroSlider(
                        label = "ROOT",
                        value = (midi - Osprey.MIDI_MIN).toFloat() / (Osprey.MIDI_MAX - Osprey.MIDI_MIN),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val root = (Osprey.MIDI_MIN + value * (Osprey.MIDI_MAX - Osprey.MIDI_MIN)).roundToInt()
                                .coerceIn(Osprey.MIDI_MIN, Osprey.MIDI_MAX)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OspreyPatch).copy(midi = root)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OspreyPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                    MacroSlider(
                        label = "SECONDS",
                        value = (seconds - .25f) / (Osprey.MAX_SECONDS - .25f),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = "%.1fs".format(Locale.ROOT, seconds),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val duration = .25f + value.coerceIn(0f, 1f) * (Osprey.MAX_SECONDS - .25f)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OspreyPatch)
                                    .copy(durationSeconds = duration)
                        },
                    )
                }

                // OPENSKY recipes keep their source design outside the four macros.
                // Edit the full patch so note, energy, gate and effects never reset
                // its accepted timbre or the saved renderer model.
                if (engine == Engine.OPENSKY) {
                    val openSkyPatch = buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch
                    val midiSpan = (OpenSkyPatch.MIDI_MAX - OpenSkyPatch.MIDI_MIN).toFloat()
                    val gateSpan = OpenSkyPatch.GATE_SECONDS_MAX - OpenSkyPatch.GATE_SECONDS_MIN
                    MacroSlider(
                        label = "NOTE",
                        value = (openSkyPatch.midi - OpenSkyPatch.MIDI_MIN) / midiSpan,
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(openSkyPatch.midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch)
                                    .copy(midi = (OpenSkyPatch.MIDI_MIN + value * midiSpan).roundToInt()
                                        .coerceIn(OpenSkyPatch.MIDI_MIN, OpenSkyPatch.MIDI_MAX))
                        },
                    )
                    MacroSlider(
                        label = "VEL",
                        value = openSkyPatch.velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                    MacroSlider(
                        label = "GATE",
                        value = (openSkyPatch.gateSeconds - OpenSkyPatch.GATE_SECONDS_MIN) / gateSpan,
                        fillColor = classColor,
                        scheme = scheme,
                        readout = String.format(java.util.Locale.ROOT, "%.2f s", openSkyPatch.gateSeconds),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch)
                                    .copy(gateSeconds = (OpenSkyPatch.GATE_SECONDS_MIN + gateSpan * value)
                                        .coerceIn(OpenSkyPatch.GATE_SECONDS_MIN, OpenSkyPatch.GATE_SECONDS_MAX))
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (wet in listOf(false, true)) {
                            ActionButton(
                                if (wet) "WET" else "DRY",
                                scheme,
                                enabled = true,
                                lit = openSkyPatch.wet == wet,
                                modifier = Modifier.weight(1f).semantics { selected = openSkyPatch.wet == wet },
                                accessibilityLabel = if (wet) "OPENSKY WET" else "OPENSKY DRY",
                            ) {
                                touched = true
                                currentPresetByVoice.remove(engine to voice)
                                loadedPatchesByVoice[engine to voice] =
                                    (buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch).copy(wet = wet)
                            }
                        }
                    }
                }

                if (engine == Engine.REGISTERS) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        TapeText(
                            when (voice as RegistersVoice) {
                                RegistersVoice.PIPE -> "PIPE SPEECH, CHIFF AND SOFT SUSTAINED AIR."
                                RegistersVoice.MUSETTE -> "THREE BEATING REEDS, SHAPED BY BELLOWS."
                                RegistersVoice.TRANSISTOR -> "SQUARE-WAVE RANKS WITH A TRANSISTOR KEYER."
                            },
                            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
                        )
                        TapeText(
                            when (voice as RegistersVoice) {
                                RegistersVoice.PIPE -> "COLOR: BRIGHTNESS · AIR: FLOW · EDGE: CHIFF"
                                RegistersVoice.MUSETTE -> "COLOR: REED BODY · AIR: BELLOWS · EDGE: BUZZ"
                                RegistersVoice.TRANSISTOR -> "COLOR: RANKS · AIR: KEY CLICK · EDGE: DRIVE"
                            },
                            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
                        )
                        TapeText(
                            when (voice as RegistersVoice) {
                                RegistersVoice.PIPE -> "SPREAD: ENSEMBLE · MOTION: TREMULANT"
                                RegistersVoice.MUSETTE -> "SPREAD: BEATING · MOTION: VIBRATO + SWELL"
                                RegistersVoice.TRANSISTOR -> "SPREAD: DETUNED RANKS · MOTION: VIBRATO"
                            },
                            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
                        )
                        TapeText(
                            if (Registers.isLoop(macros.getValue("HOLD")))
                                "LOOP STARTS AT THE SETTLED TONE. LOW SPREAD MAY SOUND IN UNISON."
                            else "HOLD: NOTE LENGTH · TOP STEP: LOOP WITHOUT ATTACK.",
                            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
                        )
                    }
                }

                if (engine == Engine.TRIO) {
                    val trioPatch = (loadedPatch as? TrioPatch)
                        ?: (buildCurrentPatch(engine.patchDisplayName(voice)) as TrioPatch)
                    val config = trioPatch.config
                    TapeText(
                        if (voice == TrioVoice.ENSEMBLE)
                            "DRAMA BRINGS ANSWERS TOGETHER · SIDES PICKS THE OUTSIDER · MOTION MOVES THE ALLIANCE"
                        else "SOLO PERFORMER · DRAMA, SIDES AND MOTION SHAPE THE ENSEMBLE",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4,
                    )
                    MacroSlider(
                        label = "ROOT",
                        value = (trioPatch.midi - Trio.MIN_MIDI).toFloat() / (Trio.MAX_MIDI - Trio.MIN_MIDI),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(trioPatch.midi),
                        onValueChange = { value ->
                            val root = (Trio.MIN_MIDI + value * (Trio.MAX_MIDI - Trio.MIN_MIDI)).roundToInt()
                                .coerceIn(Trio.MIN_MIDI, Trio.MAX_MIDI)
                            updateTrioPatch { it.copy(midi = root) }
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY", value = trioPatch.velocity,
                        fillColor = classColor, scheme = scheme,
                        onValueChange = { value -> updateTrioPatch { it.copy(velocity = value.coerceIn(0f, 1f)) } },
                    )
                    // The gesture stays within its two-second playing window;
                    // changing tempo retains the selected number of beats.
                    val minimumTempo = maxOf(60, config.beats * 30)
                    MacroSlider(
                        label = "TEMPO",
                        value = (config.tempoBpm - minimumTempo) / (200f - minimumTempo),
                        fillColor = classColor, scheme = scheme,
                        readout = "${config.tempoBpm.roundToInt()} BPM",
                        onValueChange = { value ->
                            val bpm = (minimumTempo + value * (200f - minimumTempo)).roundToInt()
                                .coerceIn(minimumTempo, 200).toFloat()
                            updateTrioPatch { it.copy(config = it.config.copy(tempoBpm = bpm)) }
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (beats in 1..4) {
                            ActionButton(
                                "$beats ${if (beats == 1) "BEAT" else "BEATS"}", scheme,
                                enabled = beats * 60f / config.tempoBpm <= 2f,
                                lit = config.beats == beats,
                                accessibilityLabel = "PHRASE $beats ${if (beats == 1) "BEAT" else "BEATS"}",
                                modifier = Modifier.weight(1f).semantics { selected = config.beats == beats },
                            ) { updateTrioPatch { it.copy(config = it.config.copy(beats = beats)) } }
                        }
                    }
                }

                if (engine == Engine.OBBLIGATO) {
                    val duet = loadedPatch as? ObbligatoPatch
                    val midi = duet?.midi ?: Obbligato.DEFAULT_MIDI
                    val velocity = duet?.velocity ?: Obbligato.DEFAULT_VELOCITY
                    MacroSlider(
                        label = "ROOT",
                        value = (midi - Obbligato.MIDI_MIN).toFloat() / (Obbligato.MIDI_MAX - Obbligato.MIDI_MIN),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(midi),
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            val root = (Obbligato.MIDI_MIN + value * (Obbligato.MIDI_MAX - Obbligato.MIDI_MIN)).roundToInt()
                                .coerceIn(Obbligato.MIDI_MIN, Obbligato.MIDI_MAX)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as ObbligatoPatch).copy(midi = root)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as ObbligatoPatch)
                                    .copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                    TapeText("BOND ${obbligatoBondLabel(macros.getValue("BOND"))}", TapeType.pixelSmall, scheme.ink2.tape)
                    TapeText("HOLD EXTENDS A FINITE NOTE", TapeType.pixelSmall, scheme.ink2.tape)
                }

                val creaseModel = (loadedPatch as? CreasePatch)?.model ?: Crease.MODEL_VERSION
                val macroSpecs = remember(engine, voice, creaseModel) {
                    if (engine == Engine.CREASE) Crease.macrosFor(voice as CreaseVoice, creaseModel)
                    else engine.macrosFor(voice)
                }
                val registersRoot = if (engine == Engine.REGISTERS)
                    (buildCurrentPatch(engine.patchDisplayName(voice)) as RegistersPatch).midi else null
                val shoalHoldSupported = if (engine == Engine.SHOAL) {
                    val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as ShoalPatch
                    Shoal.supportsHold(patch.model, patch.material)
                } else true
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (spec in macroSpecs) {
                        MacroSlider(
                            label = spec.name,
                            value = macros.getValue(spec.name),
                            fillColor = classColor,
                            scheme = scheme,
                            // SIREN's HOLD reads LOOP at its top: the render is
                            // one seamless loop for the SURFACE, not a longer
                            // hold (Siren.isLoop), the way GLINT's PEAK readout
                            // would say the harmonic it snapped to.
                            readout = when {
                                engine == Engine.SHOAL && spec.name == "HOLD" && !shoalHoldSupported -> "NOTE"
                                engine == Engine.OVERLAP && spec.name == "HOLD" -> {
                                    val hold = macros.getValue(spec.name)
                                    val legacy = (loadedPatch as? OverlapPatch)?.model == 1
                                    val seconds = if (legacy) .008f + 2.8f * hold * hold
                                        else .040f + 5.96f * hold * hold
                                    "%.1fs".format(Locale.ROOT, seconds)
                                }
                                engine == Engine.FLOCK && spec.name == "HOLD" ->
                                    "%.1fs".format(Locale.ROOT, 2f + 4f * macros.getValue(spec.name))
                                engine == Engine.ORPHICA && spec.name == "HOLD" && Orphica.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.OSPREY && spec.name == "HOLD" && macros.getValue(spec.name) >= .999f -> "EXTEND"
                                engine == Engine.SIREN && spec.name == "HOLD" && Siren.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.FLOTILLA && spec.name == "HOLD" && Flotilla.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.CISTERN && spec.name == "HOLD" && Cistern.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.CIRCUIT && spec.name == "HOLD" && Circuit.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.ALLOY && spec.name == "HOLD" && Alloy.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.REGISTERS && spec.name == "HOLD" && Registers.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.REGISTERS && spec.name == "TUNE" && registersRoot != null -> Scales.nameOf(registersRoot)
                                engine == Engine.MURK && spec.name == "HOLD" && Murk.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.NIMBUS && spec.name == "HOLD" && Nimbus.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.TESSERA && spec.name == "HOLD" && Tessera.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.PITCHWHEEL && spec.name == "HOLD" && Pitchwheel.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.MURMUR && spec.name == "HOLD" && Murmur.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.PRISM && spec.name == "HOLD" && Prism.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.CONTOUR && spec.name == "HOLD" && ContourEngine.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.STOOP && spec.name == "HOLD" && Stoop.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.LACQUER && spec.name == "HOLD" && Lacquer.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.SKITTER && spec.name == "HOLD" && Skitter.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.TANGLE && spec.name == "HOLD" && Tangle.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.SHOAL && spec.name == "HOLD" && Shoal.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.CREASE && spec.name == "HOLD" && Crease.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.LATTICE && spec.name == "HOLD" && Lattice.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.RELAY && spec.name == "HOLD" && Relay.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.RELAY && spec.name == "TUNE" ->
                                    Scales.nameOf(Relay.midiFor(voice as RelayVoice, macros.getValue(spec.name)))
                                engine == Engine.EMBOUCHURE && spec.name == "HOLD" && Embouchure.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.BELLOWS && spec.name == "HOLD" && Bellows.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.COROLLA && spec.name == "HOLD" && Corolla.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.THAW && spec.name == "HOLD" && Thaw.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.UNDERTOW && spec.name == "HOLD" && Undertow.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.SUTURE && spec.name == "HOLD" && Suture.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.SPOOL && spec.name == "HOLD" && Spool.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                engine == Engine.REVEL && spec.name == "HOLD" && Revel.isLoop(macros.getValue(spec.name)) -> "LOOP"
                                else -> null
                            },
                            onValueChange = { v -> updateMacro(spec.name, v) },
                            valueRange = macroRange(spec.name),
                            enabled = spec.name != "HOLD" || shoalHoldSupported,
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
                        val random = Random(System.nanoTime())
                        val scrambled = when {
                            engine == Engine.CREASE ->
                                Crease.scramble(voice as CreaseVoice, random, near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.LATTICE ->
                                Lattice.scramble(voice as LatticeVoice, random, near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.FLOCK ->
                                Flock.scramble(voice as FlockVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.EMBOUCHURE && loadedPatch is EmbouchurePatch ->
                                Embouchure.scramble(voice as EmbouchureVoice, random, near = loadedPatch)
                            engine == Engine.BELLOWS && loadedPatch is BellowsPatch ->
                                Bellows.scramble(voice as BellowsVoice, random, near = loadedPatch)
                            engine == Engine.OVEN ->
                                Oven.scramble(voice as OvenVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            else -> engine.scramble(voice, random)
                        }
                        macrosByVoice[engine to voice] = scrambled.mapValues { (key, value) -> value.coerceIn(macroRange(key)) }
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
                LabButton(
                    "REOPEN PAD ▸ EDIT ITS RECIPE",
                    scheme,
                    enabled = reopenablePads.isNotEmpty() && !sendBusy,
                    modifier = Modifier.fillMaxWidth(),
                    accessibilityLabel = "REOPEN PAD RECIPE",
                ) {
                    reopeningPad = true
                }
                reopenedPadsByVoice[engine to voice]?.takeIf { it.matches(shelfRoot, entry?.dir) }?.let { source ->
                    val slot = source.slot
                    val target = kit?.pad(slot)
                    LabButton(
                        if (sendBusy) "…" else "UPDATE PAD ${padTag(slot)}",
                        scheme,
                        enabled = !sendBusy && target != null && target.velocityLayers.isEmpty() && target.chain == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        sendToSlot(slot)
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
                // SIREN at LOOP: land it and open the SURFACE on it - the
                // siren-box gesture (hold, slide, let go into the echo) one
                // tap away. Only a LOOP: a one-shot on the surface would
                // replay its sweep at every wrap (the spec's SURFACE section).
                if (engine == Engine.SIREN && Siren.isLoop(macros.getValue("HOLD"))) {
                    LabButton(
                        if (sendBusy && sendToSurface) "…" else "→ SURFACE ▸",
                        scheme,
                        enabled = kit != null && !sendBusy,
                        modifier = Modifier.fillMaxWidth(),
                        accessibilityLabel = "TO SURFACE",
                    ) {
                        sendToSurface = true
                        showChooser = true
                    }
                }
                // RESIN, SIREN, GLINT or OPENSKY HALO, held: the sound as a
                // keys instrument when its renderer closes its own loop.
                // REGISTERS retains the authored attack before its held region.
                // ORPHICA holds a captured sustain behind its own plucked onset.
                // Full width under SPREAD's row, the DELETED PRESETS door's
                // shape. Shown regardless of SIREN's own HOLD slider: MAKE
                // always substitutes the loop-and-release envelope, the
                // same way it ignores RESIN's one-shot DECAY.
                if (supportsHeldInstrument) {
                    LabButton(
                        "MAKE INSTRUMENT ▸",
                        scheme,
                        enabled = heldInstrumentReady,
                        modifier = Modifier.fillMaxWidth(),
                        accessibilityLabel = "MAKE INSTRUMENT",
                    ) {
                        makingInstrument = true
                    }
                    if (!heldInstrumentReady) {
                        TapeText(
                            if (engine == Engine.BELLOWS) "MAKE INSTRUMENT NEEDS A CURRENT BELLOWS SOUND. THE SAVED ORIGINAL STILL PLAYS AS BEFORE."
                            else "MAKE INSTRUMENT NEEDS ROOT ${Scales.nameOf(OpenSkyPadMaker.ROOT_MIDI_MIN)}–${Scales.nameOf(OpenSkyPadMaker.ROOT_MIDI_MAX)}",
                            TapeType.pixelSmall,
                            scheme.ink2.tape,
                            maxLines = 2,
                        )
                    }
                }
                // RESIN or SIREN, droning: the sound as a loop-grid track,
                // RESIN's own breathing filter or SIREN's own wail.
                if (engine == Engine.RESIN || engine == Engine.SIREN) {
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
            val cancelChooser = { if (!sendBusy) { showChooser = false; sendToSurface = false } }
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

        if (reopeningPad) {
            val cancelReopen = { reopeningPad = false }
            ReopenPadOverlay(reopenablePads, scheme, onPick = ::reopenPad, onCancel = cancelReopen)
            BackHandler(onBack = cancelReopen)
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

        if (makingInstrument && supportsHeldInstrument) {
            // Recomputed here rather than cached: cheap (a Spec is a data
            // class, no rendering), and it must reflect whatever the panel
            // holds right now, the same as every value below it.
            heldSpec()?.let { spec ->
                HeldInstrumentSheet(
                    heading = "${engine.name} · ${chipLabel(engine, voice)}",
                    name = holdName,
                    hasAttack = spec.hasAttack,
                    attack = holdAttack,
                    release = holdRelease,
                    knobLabel = spec.knobLabel,
                    instrumentNote = spec.instrumentNote,
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
            }
            BackHandler(onBack = ::closeHeld)
        }

        if (droneOpen) {
            // The spec itself is recomputed here rather than cached, the
            // same call [heldSpec] makes and for the same reason: cheap
            // (it is only a voice and a macro map, no rendering), and it
            // must reflect whatever the panel holds right now. The
            // readout is not recomputed here — [droneLabel] is, off the
            // main thread, since SIREN's own is not free.
            droneSpec()?.let { spec ->
                val root = droneRoot
                val session = droneSession
                val ready = root != null && session != null
                DroneSheet(
                    heading = "${engine.name} · ${chipLabel(engine, voice)}",
                    roots = spec.roots,
                    hasMotion = spec.hasMotion,
                    root = root,
                    ready = ready,
                    label = droneLabel,
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
            }
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
 * DELETED PRESETS: the bin's own listing, `DeletedKitsSection`'s shape as an
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
            TapeText(row.saved.name, TapeType.marker, scheme.lcdInk.tape, maxLines = 1)
            TapeText(
                "${row.saved.engine} · ${row.saved.voice.replace('_', ' ')} · ${Ages.ago(row.binnedAt, now)}",
                TapeType.pixelSmall,
                scheme.lcdInk2,
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

// Duplicated, not hoisted — `KitsScreen.kt`'s and `DeletedKitsSection.kt`'s
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

// ---------- MAKE INSTRUMENT: held synths (BELLOWS among them) and captured sustain ----------

/**
 * MAKE INSTRUMENT's engines behind one shape, so the render/export
 * orchestration in [SynthScreen] (identical for all — render every zone,
 * show progress, write the package once every zone has landed) is written
 * once. RESIN's spec carries an ATTACK the sheet draws a slider for;
 * SIREN's LOOP render has none to carry (`Keys.sirenPad`'s own KDoc) and
 * neither does GLINT's (`Keys.glintPad`: the onset and the breath come from
 * the patch), nor OPENSKY HALO's authored onset. These expose only release,
 * so their slider never appears. REGISTERS likewise has no attack knob: its
 * speaking onset is in the instrument sample, before the verified held cycle.
 * ORPHICA keeps its plucked onset, with the captured grain cycle supplying sustain.
 */
private sealed interface HeldSpec {
    val zoneMidis: List<Int>
    val hasAttack: Boolean
    val instrumentNote: String? get() = null

    /** The seconds line under the sliders — each implementation reads its own [ResinPadMaker.Spec]/[SirenPadMaker.Spec]/[GlintPadMaker.Spec]/[RegistersPadMaker.Spec]/[OpenSkyPadMaker.Spec], never the raw stepper fractions again. */
    val knobLabel: String
    /** One entry per velocity layer, soft first; legacy instruments return one. */
    fun renderZone(midi: Int, cancelled: () -> Boolean): List<KeyNote>
    fun preview(cancelled: () -> Boolean): Snip
    fun export(name: String, notes: List<List<KeyNote>>, destRoot: File): KeygroupProgram

    class Resin(private val spec: ResinPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = ResinPadMaker.zoneMidis(spec)
        override val hasAttack get() = true
        override val knobLabel
            get() = "ATTACK ${ResinPadMaker.secondsLabel(spec.attackSeconds)} · " +
                "RELEASE ${ResinPadMaker.secondsLabel(spec.releaseSeconds)}"
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(ResinPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = ResinPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = ResinPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }

    class Siren(private val spec: SirenPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = SirenPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${SirenPadMaker.secondsLabel(spec.releaseSeconds)}"
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(SirenPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = SirenPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = SirenPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }

    class Glint(private val spec: GlintPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = GlintPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${GlintPadMaker.secondsLabel(spec.releaseSeconds)}"
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(GlintPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = GlintPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = GlintPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }

    class Orphica(private val spec: OrphicaPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = OrphicaPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "CAPTURED SUSTAIN · RELEASE ${OrphicaPadMaker.secondsLabel(spec.releaseSeconds)}"
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(OrphicaPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = OrphicaPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = OrphicaPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }

    class OpenSky(private val spec: OpenSkyPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = OpenSkyPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${OpenSkyPadMaker.secondsLabel(spec.releaseSeconds)}"
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(OpenSkyPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = OpenSkyPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = OpenSkyPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }

    class Registers(private val spec: RegistersPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = RegistersPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${RegistersPadMaker.secondsLabel(spec.releaseSeconds)}"
        override val instrumentNote get() =
            "KEEPS THE NOTE'S ATTACK, THEN HOLDS ITS SETTLED TONE. SYNTH'S LOOP AUDITION STARTS AFTER THE ATTACK."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(RegistersPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = RegistersPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = RegistersPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }

    class Bellows(private val spec: BellowsPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = BellowsPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${BellowsPadMaker.secondsLabel(spec.releaseSeconds)}"
        override val instrumentNote get() = if (spec.patch.voice == BellowsVoice.WHEEL)
            "C3–C5 · SOFT, MEDIUM AND HARD TOUCH. MELODY ONLY; THE TONIC BED PLAYS SEPARATELY."
        else "C3–C5 · SOFT, MEDIUM AND HARD TOUCH. KEEPS THE ATTACK AND SUSTAINS WHILE HELD."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = BellowsPadMaker.renderZone(spec, midi, cancelled)
        override fun preview(cancelled: () -> Boolean) = BellowsPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = BellowsPadMaker.export(name, spec, notes, destRoot)
    }
}

/**
 * MAKE INSTRUMENT ▸'s sheet (RESIN, SIREN, GLINT, REGISTERS, BELLOWS or OPENSKY HALO): [PresetNameDialog]'s frame -
 * scrim, raised bevel, CANCEL beside the real buttons - over the knobs a
 * held note has that a one-shot never needed. [hasAttack] hides ATTACK's
 * slider for an engine with none to dial (SIREN, GLINT, REGISTERS, OPENSKY HALO); RELEASE always shows,
 * and [knobLabel] spells out whichever of the two apply in seconds. While
 * MAKE runs the knobs give way to [HeldProgress], a bar that fills as each
 * zone lands and the line counting them; PREVIEW's one zone says
 * RENDERING… the way the scope does. CANCEL stays live until the last
 * zone lands, and the write that follows is left to finish.
 */
@Composable
private fun HeldInstrumentSheet(
    heading: String,
    name: String,
    hasAttack: Boolean,
    attack: Float,
    release: Float,
    knobLabel: String,
    instrumentNote: String?,
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
            if (instrumentNote != null) {
                TapeText(instrumentNote, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 4)
            }
            if (making) {
                HeldProgress(done, total, fillColor, scheme)
            } else {
                if (hasAttack) MacroSlider("ATTACK", attack, fillColor, scheme, onAttack)
                MacroSlider("RELEASE", release, fillColor, scheme, onRelease)
                TapeText(knobLabel, TapeType.pixelSmall, scheme.ink2.tape)
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

// ---------- DRONE TO LOOP: RESIN and SIREN, droning ----------

/**
 * DRONE TO LOOP's two engines behind one shape, mirroring [HeldSpec]: RESIN
 * invents MOTION and BREATHS because its held macros carry no swing of
 * their own; SIREN already is a movement (RATE and DEPTH), so
 * [DroneSpec.Siren] adds nothing and [hasMotion] is false.
 */
private sealed interface DroneSpec {
    val roots: IntRange
    val hasMotion: Boolean
    fun defaultRoot(key: KeySpec?): Int
    fun label(root: Int, session: Session, cancelled: () -> Boolean): String
    fun render(root: Int, session: Session, cancelled: () -> Boolean): FloatArray
    fun toJson(): JsonValue

    class Resin(private val voice: ResinVoice, macros: Map<String, Float>, motion: Float, rate: Int) : DroneSpec {
        private val spec = DroneMaker.spec(voice, macros, motion, rate)
        override val roots get() = DroneMaker.roots(voice)
        override val hasMotion get() = true
        override fun defaultRoot(key: KeySpec?) = DroneMaker.defaultRoot(voice, key)
        override fun label(root: Int, session: Session, cancelled: () -> Boolean) = DroneMaker.label(root, session)
        override fun render(root: Int, session: Session, cancelled: () -> Boolean) = DroneMaker.render(spec, root, session, cancelled)
        override fun toJson() = spec.toJson()
    }

    class Siren(private val voice: SirenVoice, macros: Map<String, Float>) : DroneSpec {
        private val spec = SirenDroneMaker.spec(voice, macros)
        override val roots get() = SirenDroneMaker.roots(voice)
        override val hasMotion get() = false
        override fun defaultRoot(key: KeySpec?) = SirenDroneMaker.defaultRoot(voice, key)
        override fun label(root: Int, session: Session, cancelled: () -> Boolean) = SirenDroneMaker.label(spec, root, session, cancelled)
        override fun render(root: Int, session: Session, cancelled: () -> Boolean) = SirenDroneMaker.render(spec, root, session, cancelled)
        override fun toJson() = spec.toJson()
    }
}

/**
 * DRONE TO LOOP ▸'s sheet (RESIN or SIREN), [HeldInstrumentSheet]'s frame
 * over the things a drone has that a one-shot never needed: ROOT (a note
 * stepper across the voice's own register), and — RESIN only — MOTION (how
 * far the filter breathes) and BREATHS (how many times per drone). SIREN
 * already is a movement: RATE and DEPTH are the patch's own swing and
 * speed, dialed on the panel above, so [hasMotion] is false and that row
 * never appears. The readout under ROOT is [label]: the note, how many
 * bars before it repeats at the grid's tempo, and the nudge the loop
 * needed to close.
 */
@Composable
private fun DroneSheet(
    heading: String,
    roots: IntRange,
    hasMotion: Boolean,
    root: Int?,
    ready: Boolean,
    label: String,
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
    val range = roots
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
                    label,
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
            if (hasMotion) {
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

/** Keep untouched physical grain settings exact, including sub-frame Float values. */
internal fun orphicaPatchForControls(patch: OrphicaPatch, name: String, controls: Map<String, Float>): OrphicaPatch {
    val displayed = Orphica.macrosFrom(patch.config) + patch.macros
    val overrides = controls.filter { (key, value) -> key in patch.macros || value != displayed[key] }
    return patch.copy(name = name, macros = overrides)
}

// ---------- the engine adapter ----------

// OBBLIGATO has one complete duet rather than separate source voices.
private enum class ObbligatoVoice { AIR_REED }

/**
 * The screen's own multi-engine adapter — file-private, per the brief ("the
 * engine abstraction stays file-private to the screen — :synth is not to
 * change"). The registered engines already converge on one shape (an
 * `<X>Voice` enum, `macrosFor`/`defaults`/`scramble`/`render`, and an
 * `<X>Patch(name, voice, macros)` constructor registered in Patches.kt) —
 * this gives the screen one dispatch point instead of several near-
 * identical call sites, adapting to that convergence rather than the other
 * way around. Voices are held as `Enum<*>` (not each engine's own sealed
 * voice type) because the screen keeps "the current voice" as a single piece
 * of state that survives an engine switch; every `when (this)` branch below
 * casts back to the one concrete voice type that engine is ever paired
 * with — safe by construction, since [voices] is the only place a voice for
 * a given engine ever comes from.
 */
private enum class Engine {
    THUMP, SKIN, ONSET, TINES, VELVET, VOX, PLUCK, ORPHICA, TONEWHEEL, REGISTERS, FATHOM, RESIN, TIDE, GLINT, SIREN, FORK, FLOTILLA, CISTERN, CIRCUIT, OBBLIGATO, ALLOY, THAW, MURK, NIMBUS, COROLLA, UNDERTOW, SUTURE, TESSERA, PITCHWHEEL, REVEL, PRISM, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, OSPREY, OPENSKY, OVERLAP, OVEN, FLOCK, VENT, CREASE, LATTICE, TRIO;

    /** Cycle through the registered synth engines, wrapping back to THUMP. */
    fun next(): Engine = entries[(ordinal + 1) % entries.size]

    fun voices(): List<Enum<*>> = when (this) {
        THUMP -> ThumpVoice.entries
        SKIN -> SkinVoice.entries
        ONSET -> listOf(OnsetVoice.KICK, OnsetVoice.SNARE, OnsetVoice.HAT_CLOSED, OnsetVoice.HAT_OPEN)
        TINES -> TinesVoice.entries
        VELVET -> VelvetVoice.entries
        VOX -> VoxVoice.entries
        PLUCK -> PluckVoice.entries
        ORPHICA -> OrphicaMaterial.entries
        TONEWHEEL -> TonewheelVoice.entries
        REGISTERS -> RegistersVoice.entries
        FATHOM -> FathomVoice.entries
        RESIN -> ResinVoice.entries
        TIDE -> TideVoice.entries
        GLINT -> GlintVoice.entries
        SIREN -> SirenVoice.entries
        FORK -> ForkVoice.entries
        FLOTILLA -> FlotillaVoice.entries
        CISTERN -> CisternVoice.entries
        CIRCUIT -> CircuitVoice.entries
        OBBLIGATO -> ObbligatoVoice.entries
        ALLOY -> AlloyVoice.entries
        MURK -> MurkVoice.entries
        NIMBUS -> NimbusVoice.entries
        THAW -> ThawVoice.entries
        UNDERTOW -> UndertowVoice.entries
        COROLLA -> CorollaVoice.entries
        SUTURE -> SutureVoice.entries
        TESSERA -> TesseraVoice.entries
        PITCHWHEEL -> PitchwheelVoice.entries
        REVEL -> RevelVoice.entries
        MURMUR -> MurmurVoice.entries
        TRIO -> TrioVoice.entries
        OSPREY -> OspreyVoice.entries
        FLOCK -> FlockVoice.entries
        OPENSKY -> OpenSkyVoice.entries
        OVERLAP -> OverlapTopology.entries
        VENT -> listOf(VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME)
        PRISM -> PrismVoice.entries
        CONTOUR -> ContourVoice.entries
        STOOP -> StoopVoice.entries
        LACQUER -> LacquerVoice.entries
        SPOOL -> SpoolVoice.entries
        SKITTER -> SkitterVoice.entries
        TANGLE -> TangleVoice.entries
        SHOAL -> ShoalVoice.entries
        CREASE -> CreaseVoice.entries
        LATTICE -> LatticeVoice.entries
        RELAY -> RelayVoice.entries
        EMBOUCHURE -> EmbouchureVoice.entries
        BELLOWS -> BellowsVoice.entries
        OVEN -> OvenVoice.entries
    }

    fun macrosFor(voice: Enum<*>) = when (this) {
        THUMP -> Thump.macrosFor(voice as ThumpVoice)
        SKIN -> Skin.macrosFor(voice as SkinVoice)
        ONSET -> Onset.macrosFor(voice as OnsetVoice)
        TINES -> Tines.macrosFor(voice as TinesVoice)
        VELVET -> Velvet.macrosFor(voice as VelvetVoice)
        VOX -> Vox.macrosFor(voice as VoxVoice)
        PLUCK -> Pluck.macrosFor(voice as PluckVoice)
        ORPHICA -> Orphica.macrosFor(voice as OrphicaMaterial)
        TONEWHEEL -> Tonewheel.macrosFor(voice as TonewheelVoice)
        REGISTERS -> Registers.macrosFor(voice as RegistersVoice)
        FATHOM -> Fathom.macrosFor(voice as FathomVoice)
        RESIN -> Resin.macrosFor(voice as ResinVoice)
        TIDE -> Tide.macrosFor(voice as TideVoice)
        GLINT -> Glint.macrosFor(voice as GlintVoice)
        SIREN -> Siren.macrosFor(voice as SirenVoice)
        FORK -> Fork.macrosFor(voice as ForkVoice)
        FLOTILLA -> Flotilla.macrosFor(voice as FlotillaVoice)
        CISTERN -> Cistern.macrosFor(voice as CisternVoice)
        CIRCUIT -> Circuit.macrosFor(voice as CircuitVoice)
        OBBLIGATO -> Obbligato.macrosFor()
        ALLOY -> Alloy.macrosFor(voice as AlloyVoice)
        MURK -> Murk.macrosFor(voice as MurkVoice)
        NIMBUS -> Nimbus.macrosFor(voice as NimbusVoice)
        THAW -> Thaw.macrosFor(voice as ThawVoice)
        UNDERTOW -> Undertow.macrosFor(voice as UndertowVoice)
        COROLLA -> Corolla.macrosFor(voice as CorollaVoice)
        SUTURE -> Suture.macrosFor(voice as SutureVoice)
        TESSERA -> Tessera.macrosFor(voice as TesseraVoice)
        PITCHWHEEL -> Pitchwheel.macrosFor(voice as PitchwheelVoice)
        REVEL -> Revel.macrosFor(voice as RevelVoice)
        MURMUR -> Murmur.macrosFor(voice as MurmurVoice)
        TRIO -> Trio.macrosFor(voice as TrioVoice)
        OSPREY -> Osprey.macrosFor(voice as OspreyVoice)
        FLOCK -> Flock.macrosFor(voice as FlockVoice)
        OPENSKY -> OpenSky.macrosFor(voice as OpenSkyVoice)
        OVERLAP -> Overlap.macrosFor(voice as OverlapTopology)
        VENT -> Vent.macrosFor(voice as VentVoice)
        PRISM -> Prism.macrosFor(voice as PrismVoice)
        CONTOUR -> ContourEngine.macrosFor(voice as ContourVoice)
        STOOP -> Stoop.macrosFor(voice as StoopVoice)
        LACQUER -> Lacquer.macrosFor(voice as LacquerVoice)
        SPOOL -> Spool.macrosFor(voice as SpoolVoice)
        SKITTER -> Skitter.macrosFor(voice as SkitterVoice)
        TANGLE -> Tangle.macrosFor(voice as TangleVoice)
        SHOAL -> Shoal.macrosFor(voice as ShoalVoice)
        CREASE -> Crease.macrosFor(voice as CreaseVoice)
        LATTICE -> Lattice.macrosFor(voice as LatticeVoice)
        RELAY -> Relay.macrosFor(voice as RelayVoice)
        EMBOUCHURE -> Embouchure.macrosFor(voice as EmbouchureVoice)
        BELLOWS -> Bellows.macrosFor(voice as BellowsVoice)
        OVEN -> Oven.macrosFor(voice as OvenVoice)
    }

    fun defaults(voice: Enum<*>): Map<String, Float> = when (this) {
        THUMP -> Thump.defaults(voice as ThumpVoice)
        SKIN -> Skin.defaults(voice as SkinVoice)
        ONSET -> Onset.defaults(voice as OnsetVoice)
        TINES -> Tines.defaults(voice as TinesVoice)
        VELVET -> Velvet.defaults(voice as VelvetVoice)
        VOX -> Vox.defaults(voice as VoxVoice)
        PLUCK -> Pluck.defaults(voice as PluckVoice)
        ORPHICA -> Orphica.defaults(voice as OrphicaMaterial)
        TONEWHEEL -> Tonewheel.defaults(voice as TonewheelVoice)
        REGISTERS -> Registers.defaults(voice as RegistersVoice)
        FATHOM -> Fathom.defaults(voice as FathomVoice)
        RESIN -> Resin.defaults(voice as ResinVoice)
        TIDE -> Tide.defaults(voice as TideVoice)
        GLINT -> Glint.defaults(voice as GlintVoice)
        SIREN -> Siren.defaults(voice as SirenVoice)
        FORK -> Fork.defaults(voice as ForkVoice)
        FLOTILLA -> Flotilla.defaults(voice as FlotillaVoice)
        CISTERN -> Cistern.defaults(voice as CisternVoice)
        CIRCUIT -> Circuit.defaults(voice as CircuitVoice)
        OBBLIGATO -> Obbligato.defaults()
        ALLOY -> Alloy.defaults(voice as AlloyVoice)
        MURK -> Murk.defaults(voice as MurkVoice)
        NIMBUS -> Nimbus.defaults(voice as NimbusVoice)
        THAW -> Thaw.defaults(voice as ThawVoice)
        UNDERTOW -> Undertow.defaults(voice as UndertowVoice)
        COROLLA -> Corolla.defaults(voice as CorollaVoice)
        SUTURE -> Suture.defaults(voice as SutureVoice)
        TESSERA -> Tessera.defaults(voice as TesseraVoice)
        PITCHWHEEL -> Pitchwheel.defaults(voice as PitchwheelVoice)
        REVEL -> Revel.defaults(voice as RevelVoice)
        MURMUR -> Murmur.defaults(voice as MurmurVoice)
        TRIO -> Trio.defaults(voice as TrioVoice)
        OSPREY -> Osprey.defaults(voice as OspreyVoice)
        FLOCK -> Flock.defaults(voice as FlockVoice)
        OPENSKY -> OpenSkyPresets.forVoice(voice as OpenSkyVoice).first().macros
        OVERLAP -> Overlap.defaults(voice as OverlapTopology)
        VENT -> Vent.defaultsFor(voice as VentVoice)
        PRISM -> Prism.defaults(voice as PrismVoice)
        CONTOUR -> ContourEngine.defaults(voice as ContourVoice)
        STOOP -> Stoop.defaults(voice as StoopVoice)
        LACQUER -> Lacquer.defaults(voice as LacquerVoice)
        SPOOL -> Spool.defaults(voice as SpoolVoice)
        SKITTER -> Skitter.defaults(voice as SkitterVoice)
        TANGLE -> Tangle.defaults(voice as TangleVoice)
        SHOAL -> Shoal.defaults(voice as ShoalVoice)
        CREASE -> Crease.defaults(voice as CreaseVoice)
        LATTICE -> Lattice.defaults(voice as LatticeVoice)
        RELAY -> Relay.defaults(voice as RelayVoice)
        EMBOUCHURE -> Embouchure.defaults(voice as EmbouchureVoice)
        BELLOWS -> Bellows.defaults(voice as BellowsVoice)
        OVEN -> Oven.defaults(voice as OvenVoice)
    }

    fun scramble(voice: Enum<*>, random: Random): Map<String, Float> = when (this) {
        THUMP -> Thump.scramble(voice as ThumpVoice, random)
        SKIN -> Skin.scramble(voice as SkinVoice, random)
        ONSET -> Onset.scramble(voice as OnsetVoice, random)
        TINES -> Tines.scramble(voice as TinesVoice, random)
        VELVET -> Velvet.scramble(voice as VelvetVoice, random)
        VOX -> Vox.scramble(voice as VoxVoice, random)
        PLUCK -> Pluck.scramble(voice as PluckVoice, random)
        ORPHICA -> Orphica.macrosFor(voice as OrphicaMaterial).associate {
            it.name to if (it.name == "HOLD") random.nextFloat() * .95f else random.nextFloat()
        }
        TONEWHEEL -> Tonewheel.scramble(voice as TonewheelVoice, random)
        REGISTERS -> Registers.scramble(voice as RegistersVoice, random)
        FATHOM -> Fathom.scramble(voice as FathomVoice, random)
        RESIN -> Resin.scramble(voice as ResinVoice, random)
        TIDE -> Tide.scramble(voice as TideVoice, random)
        GLINT -> Glint.scramble(voice as GlintVoice, random)
        SIREN -> Siren.scramble(voice as SirenVoice, random)
        FORK -> Fork.scramble(voice as ForkVoice, random)
        FLOTILLA -> Flotilla.scramble(voice as FlotillaVoice, random)
        CISTERN -> Cistern.scramble(voice as CisternVoice, random)
        CIRCUIT -> Circuit.scramble(voice as CircuitVoice, random)
        OBBLIGATO -> Obbligato.macrosFor().associate { it.name to random.nextFloat() }
        ALLOY -> Alloy.scramble(voice as AlloyVoice, random)
        MURK -> Murk.scramble(voice as MurkVoice, random)
        NIMBUS -> Nimbus.scramble(voice as NimbusVoice, random)
        THAW -> Thaw.scramble(voice as ThawVoice, random)
        UNDERTOW -> Undertow.scramble(voice as UndertowVoice, random)
        COROLLA -> Corolla.scramble(voice as CorollaVoice, random)
        SUTURE -> Suture.scramble(voice as SutureVoice, random)
        TESSERA -> Tessera.scramble(voice as TesseraVoice, random)
        PITCHWHEEL -> Pitchwheel.scramble(voice as PitchwheelVoice, random)
        REVEL -> Revel.scramble(voice as RevelVoice, random)
        MURMUR -> Murmur.scramble(voice as MurmurVoice, random)
        TRIO -> Trio.macrosFor(voice as TrioVoice).associate { it.name to random.nextFloat() }
        OSPREY -> Osprey.scramble(voice as OspreyVoice, random)
        FLOCK -> Flock.scramble(voice as FlockVoice, random)
        OPENSKY -> OpenSky.scramble(voice as OpenSkyVoice, random)
        OVERLAP -> Overlap.scramble(voice as OverlapTopology, random)
        VENT -> Vent.scramble(voice as VentVoice, random)
        PRISM -> Prism.scramble(voice as PrismVoice, random)
        CONTOUR -> ContourEngine.scramble(voice as ContourVoice, random)
        STOOP -> Stoop.scramble(voice as StoopVoice, random)
        LACQUER -> Lacquer.scramble(voice as LacquerVoice, random)
        SPOOL -> Spool.scramble(voice as SpoolVoice, random)
        SKITTER -> Skitter.scramble(voice as SkitterVoice, random)
        TANGLE -> Tangle.scramble(voice as TangleVoice, random)
        SHOAL -> Shoal.scramble(voice as ShoalVoice, random)
        CREASE -> Crease.scramble(voice as CreaseVoice, random)
        LATTICE -> Lattice.scramble(voice as LatticeVoice, random)
        RELAY -> Relay.scramble(voice as RelayVoice, random)
        EMBOUCHURE -> Embouchure.scramble(voice as EmbouchureVoice, random)
        BELLOWS -> Bellows.scramble(voice as BellowsVoice, random)
        OVEN -> Oven.scramble(voice as OvenVoice, random)
    }

    // Every engine's `render(voice, macros)` takes exactly those two
    // params with the rest defaulted — TONEWHEEL alone also carries a
    // `gateSeconds` param (its stab-vs-sustain knob for the not-yet-built
    // key-patch side), but its default already produces the one-shot stab
    // this screen wants, so the uniform two-arg call reaches it fine.
    fun render(voice: Enum<*>, macros: Map<String, Float>): Snip = when (this) {
        THUMP -> Thump.render(voice as ThumpVoice, macros)
        SKIN -> Skin.render(voice as SkinVoice, macros)
        ONSET -> Onset.render(voice as OnsetVoice, macros)
        TINES -> Tines.render(voice as TinesVoice, macros)
        VELVET -> Velvet.render(voice as VelvetVoice, macros)
        VOX -> Vox.render(voice as VoxVoice, macros)
        PLUCK -> Pluck.render(voice as PluckVoice, macros)
        ORPHICA -> buildPatch(patchDisplayName(voice), voice, macros).render()
        TONEWHEEL -> Tonewheel.render(voice as TonewheelVoice, macros)
        REGISTERS -> Registers.render(voice as RegistersVoice, macros)
        FATHOM -> Fathom.render(voice as FathomVoice, macros)
        RESIN -> Resin.render(voice as ResinVoice, macros)
        TIDE -> Tide.render(voice as TideVoice, macros)
        GLINT -> Glint.render(voice as GlintVoice, macros)
        SIREN -> Siren.render(voice as SirenVoice, macros)
        FORK -> Fork.render(voice as ForkVoice, macros)
        FLOTILLA -> Flotilla.render(voice as FlotillaVoice, macros)
        CISTERN -> Cistern.render(voice as CisternVoice, macros)
        CIRCUIT -> Circuit.render(voice as CircuitVoice, macros)
        OBBLIGATO -> Obbligato.render(macros)
        ALLOY -> Alloy.render(voice as AlloyVoice, macros)
        MURK -> Murk.render(voice as MurkVoice, macros)
        NIMBUS -> Nimbus.render(voice as NimbusVoice, macros)
        THAW -> Thaw.render(voice as ThawVoice, macros)
        UNDERTOW -> Undertow.render(voice as UndertowVoice, macros)
        COROLLA -> Corolla.render(voice as CorollaVoice, macros)
        SUTURE -> Suture.render(voice as SutureVoice, macros)
        TESSERA -> Tessera.render(voice as TesseraVoice, macros)
        PITCHWHEEL -> Pitchwheel.render(voice as PitchwheelVoice, macros)
        REVEL -> Revel.render(voice as RevelVoice, macros)
        MURMUR -> Murmur.render(voice as MurmurVoice, macros)
        TRIO -> Trio.render(voice as TrioVoice, macros)
        OSPREY -> Osprey.render(voice as OspreyVoice, macros)
        FLOCK -> Flock.render(voice as FlockVoice, macros)
        OPENSKY -> OpenSkyPresets.forVoice(voice as OpenSkyVoice).first().copy(macros = macros).render()
        OVERLAP -> Overlap.render(voice as OverlapTopology, macros)
        VENT -> Vent.render(voice as VentVoice, macros)
        PRISM -> Prism.render(voice as PrismVoice, macros)
        CONTOUR -> ContourEngine.render(voice as ContourVoice, macros)
        STOOP -> Stoop.render(voice as StoopVoice, macros)
        LACQUER -> Lacquer.render(voice as LacquerVoice, macros)
        SPOOL -> Spool.render(voice as SpoolVoice, macros)
        SKITTER -> Skitter.render(voice as SkitterVoice, macros)
        TANGLE -> Tangle.render(voice as TangleVoice, macros)
        SHOAL -> ShoalPatch("Preview", voice as ShoalVoice, macros).render()
        CREASE -> Crease.render(voice as CreaseVoice, macros)
        LATTICE -> Lattice.render(voice as LatticeVoice, macros)
        RELAY -> Relay.render(voice as RelayVoice, macros)
        EMBOUCHURE -> Embouchure.render(voice as EmbouchureVoice, macros)
        BELLOWS -> Bellows.render(voice as BellowsVoice, macros)
        OVEN -> Oven.render(voice as OvenVoice, macros)
    }

    /**
     * What a pad holding this voice's sound is filed as. [macros] matters
     * only where one knob picks the drum: VOX BEATBOX's HIT.
     */
    fun drumClass(voice: Enum<*>, macros: Map<String, Float> = emptyMap()): DrumClass = when (this) {
        THUMP -> (voice as ThumpVoice).drumClass
        SKIN -> (voice as SkinVoice).drumClass
        ONSET -> (voice as OnsetVoice).drumClass
        TINES -> (voice as TinesVoice).drumClass
        VELVET -> (voice as VelvetVoice).drumClass
        VOX -> Vox.drumClassFor(voice as VoxVoice, macros)
        PLUCK -> (voice as PluckVoice).drumClass
        ORPHICA -> if (Orphica.isLoop(macros["HOLD"] ?: 0f)) DrumClass.LOOP else DrumClass.TONAL
        TONEWHEEL -> (voice as TonewheelVoice).drumClass
        REGISTERS -> Registers.drumClassFor(voice as RegistersVoice, macros)
        FATHOM -> (voice as FathomVoice).drumClass
        RESIN -> (voice as ResinVoice).drumClass
        TIDE -> (voice as TideVoice).drumClass
        GLINT -> (voice as GlintVoice).drumClass
        // SIREN: a LOOP by name when HOLD is at its top, a pitched note otherwise - the one knob that picks the class, like VOX's HIT.
        SIREN -> Siren.drumClassFor(voice as SirenVoice, macros)
        // FORK: DECAY alone decides it, the same shape SIREN's HOLD takes.
        FORK -> Fork.drumClassFor(voice as ForkVoice, macros)
        FLOTILLA -> Flotilla.drumClassFor(voice as FlotillaVoice, macros)
        CISTERN -> Cistern.drumClassFor(voice as CisternVoice, macros)
        CIRCUIT -> Circuit.drumClassFor(voice as CircuitVoice, macros)
        OBBLIGATO -> Obbligato.drumClassFor()
        ALLOY -> Alloy.drumClassFor(voice as AlloyVoice, macros)
        MURK -> Murk.drumClassFor(voice as MurkVoice, macros)
        NIMBUS -> Nimbus.drumClassFor(voice as NimbusVoice, macros)
        THAW -> Thaw.drumClassFor(voice as ThawVoice, macros)
        UNDERTOW -> Undertow.drumClassFor(voice as UndertowVoice, macros)
        COROLLA -> Corolla.drumClassFor(voice as CorollaVoice, macros)
        SUTURE -> Suture.drumClassFor(voice as SutureVoice, macros)
        TESSERA -> Tessera.drumClassFor(voice as TesseraVoice, macros)
        PITCHWHEEL -> Pitchwheel.drumClassFor(voice as PitchwheelVoice, macros)
        REVEL -> Revel.drumClassFor(voice as RevelVoice, macros)
        MURMUR -> Murmur.drumClassFor(voice as MurmurVoice, macros)
        TRIO -> DrumClass.TONAL
        OSPREY -> Osprey.drumClassFor(voice as OspreyVoice, macros)
        FLOCK -> Flock.drumClassFor(voice as FlockVoice, macros)
        OPENSKY -> OpenSky.drumClassFor(voice as OpenSkyVoice, macros)
        OVERLAP -> Overlap.drumClassFor(voice as OverlapTopology, macros)
        VENT -> Vent.drumClassFor(voice as VentVoice, macros)
        PRISM -> Prism.drumClassFor(voice as PrismVoice, macros)
        CONTOUR -> ContourEngine.drumClassFor(voice as ContourVoice, macros)
        STOOP -> Stoop.drumClassFor(voice as StoopVoice, macros)
        LACQUER -> Lacquer.drumClassFor(voice as LacquerVoice, macros)
        SPOOL -> Spool.drumClassFor(voice as SpoolVoice, macros)
        SKITTER -> Skitter.drumClassFor(voice as SkitterVoice, macros)
        TANGLE -> Tangle.drumClassFor(voice as TangleVoice, macros)
        SHOAL -> Shoal.drumClassFor(voice as ShoalVoice, macros)
        CREASE -> Crease.drumClassFor(voice as CreaseVoice, macros)
        LATTICE -> Lattice.drumClassFor(voice as LatticeVoice, macros)
        RELAY -> Relay.drumClassFor(voice as RelayVoice, macros)
        EMBOUCHURE -> Embouchure.drumClassFor(voice as EmbouchureVoice, macros)
        BELLOWS -> Bellows.drumClassFor(voice as BellowsVoice, macros)
        OVEN -> Oven.drumClassFor(voice as OvenVoice, macros)
    }

    fun buildPatch(name: String, voice: Enum<*>, macros: Map<String, Float>): Patch = when (this) {
        THUMP -> ThumpPatch(name, voice as ThumpVoice, macros)
        SKIN -> SkinPatch(name, voice as SkinVoice, macros)
        ONSET -> OnsetPatch(name, voice as OnsetVoice, macros)
        TINES -> TinesPatch(name, voice as TinesVoice, macros)
        VELVET -> VelvetPatch(name, voice as VelvetVoice, macros)
        VOX -> VoxPatch(name, voice as VoxVoice, macros)
        PLUCK -> PluckPatch(name, voice as PluckVoice, macros)
        ORPHICA -> OrphicaPatch(name, OrphicaConfig(material = voice as OrphicaMaterial), macros)
        TONEWHEEL -> TonewheelPatch(name, voice as TonewheelVoice, macros)
        REGISTERS -> RegistersPatch(name, voice as RegistersVoice, macros)
        FATHOM -> FathomPatch(name, voice as FathomVoice, macros)
        RESIN -> ResinPatch(name, voice as ResinVoice, macros)
        TIDE -> TidePatch(name, voice as TideVoice, macros)
        GLINT -> GlintPatch(name, voice as GlintVoice, macros)
        SIREN -> SirenPatch(name, voice as SirenVoice, macros)
        FORK -> ForkPatch(name, voice as ForkVoice, macros)
        FLOTILLA -> FlotillaPatch(name, voice as FlotillaVoice, macros)
        CISTERN -> CisternPatch(name, voice as CisternVoice, macros)
        CIRCUIT -> CircuitPatch(name, voice as CircuitVoice, macros)
        OBBLIGATO -> ObbligatoPatch(name, macros)
        ALLOY -> AlloyPatch(name, voice as AlloyVoice, macros)
        MURK -> MurkPatch(name, voice as MurkVoice, macros)
        NIMBUS -> NimbusPatch(name, voice as NimbusVoice, macros)
        THAW -> ThawPatch(name, voice as ThawVoice, macros)
        UNDERTOW -> UndertowPatch(name, voice as UndertowVoice, macros)
        COROLLA -> CorollaPatch(name, voice as CorollaVoice, macros)
        SUTURE -> SuturePatch(name, voice as SutureVoice, macros)
        TESSERA -> TesseraPatch(name, voice as TesseraVoice, macros)
        PITCHWHEEL -> PitchwheelPatch(name, voice as PitchwheelVoice, macros)
        REVEL -> RevelPatch(name, voice as RevelVoice, macros)
        MURMUR -> MurmurPatch(name, voice as MurmurVoice, macros)
        TRIO -> TrioPatch(name, voice as TrioVoice, macros)
        OSPREY -> OspreyPatch(name, voice as OspreyVoice, macros)
        FLOCK -> FlockPatch(name, voice as FlockVoice, macros)
        OPENSKY -> OpenSkyPresets.forVoice(voice as OpenSkyVoice).first().copy(name = name, macros = macros)
        OVERLAP -> OverlapPatch(name, voice as OverlapTopology, macros)
        VENT -> VentPatch(name, voice as VentVoice, macros)
        PRISM -> PrismPatch(name, voice as PrismVoice, macros)
        CONTOUR -> ContourPatch(name, voice as ContourVoice, macros)
        STOOP -> StoopPatch(name, voice as StoopVoice, macros)
        LACQUER -> LacquerPatch(name, voice as LacquerVoice, macros)
        SPOOL -> SpoolPatch(name, voice as SpoolVoice, macros)
        SKITTER -> SkitterPatch(name, voice as SkitterVoice, macros)
        TANGLE -> TanglePatch(name, voice as TangleVoice, macros)
        SHOAL -> ShoalPatch(name, voice as ShoalVoice, macros)
        CREASE -> CreasePatch(name, voice as CreaseVoice, macros)
        LATTICE -> LatticePatch(name, voice as LatticeVoice, macros)
        RELAY -> RelayPatch(name, voice as RelayVoice, macros)
        EMBOUCHURE -> EmbouchurePatch(name, voice as EmbouchureVoice, macros)
        BELLOWS -> BellowsPatch(name, voice as BellowsVoice, macros)
        OVEN -> OvenPatch(name, voice as OvenVoice, macros)
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
// render (VELVET's CHIP, VOX's CHOIR/ROBOT/GHOST, PLUCK's NYLON/HARP,
// TONEWHEEL's SOUL/STAB/FULL) is classified TONAL there, and every
// remaining voice in these four engines is likewise a pitched note (VELVET's
// BASS/BRASS/SQUELCH, PLUCK's KOTO/BANJO) — so per the brief's fallback rule
// ("tonal-pitched voices -> TONAL, percussive -> PERC"), all four engines
// are TONAL across the board. RESIN's three voices (BASS/LEAD/BRASS) are
// pitched notes through a filter, never judged from a render (ResinPresetsTest
// carries no classifier identity check, exactly as VelvetPresetsTest explains)
// — the same fallback, so five engines are TONAL across the board (GLINT
// makes six — see its own extension below).
//
// FATHOM is the one engine here that isn't: `FathomTest`'s own factory-
// defaults classifier check (and now `FathomPresetsTest`'s identity check
// over its presets) already establishes DEEP and GRIND as DrumClass.KICK
// and GLASS as PERC — real classifier judgments, not a fallback guess, so
// this mirrors them rather than defaulting FATHOM to TONAL the way the
// other five engines are.

private val OnsetVoice.drumClass: DrumClass
    get() = when (this) {
        OnsetVoice.KICK -> DrumClass.KICK
        OnsetVoice.SNARE -> DrumClass.SNARE
        OnsetVoice.HAT_CLOSED -> DrumClass.HAT_CLOSED
        OnsetVoice.HAT_OPEN -> DrumClass.HAT_OPEN
    }

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
        TinesVoice.BELL, TinesVoice.CHIME, TinesVoice.KALIMBA -> DrumClass.TONAL
        TinesVoice.BLOCK, TinesVoice.ZAP, TinesVoice.TOY -> DrumClass.PERC
    }

private val VelvetVoice.drumClass: DrumClass get() = DrumClass.TONAL
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

// TIDE splits. BONGO and DRIP are FATHOM's rule, a real classifier
// judgment: `TidePresetsTest` holds their defaults to PERC and at least 8
// of each voice's 10 presets with them. GONG and FLARE are RESIN's rule:
// pitched notes that DECAY can hold, which the classifier reads as PERC,
// SNARE or LOOP depending on length alone, so they are TONAL by design.
private val TideVoice.drumClass: DrumClass
    get() = when (this) {
        TideVoice.BONGO, TideVoice.DRIP -> DrumClass.PERC
        TideVoice.GONG, TideVoice.FLARE -> DrumClass.TONAL
    }

// GLINT's voices are pitched notes with a formant on them — the same
// "tonal-pitched voices -> TONAL" fallback VELVET/VOX/PLUCK/TONEWHEEL/RESIN
// take, never judged from a render.
private val GlintVoice.drumClass: DrumClass
    get() = DrumClass.TONAL

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
    Engine.ONSET -> when (voice as OnsetVoice) {
        OnsetVoice.KICK -> "KICK"
        OnsetVoice.SNARE -> "SNARE"
        OnsetVoice.HAT_CLOSED -> "HAT CL"
        OnsetVoice.HAT_OPEN -> "HAT OP"
    }
    Engine.OBBLIGATO -> "AIR + REED"
    else -> voice.name
}

/** Name exact stages, and show the two neighbors while BOND blends between them. */
private fun obbligatoBondLabel(bond: Float): String {
    val position = bond.coerceIn(0f, 1f) * ObbligatoRelationship.entries.lastIndex
    val nearest = position.roundToInt()
    if (kotlin.math.abs(position - nearest) < .00001f) return ObbligatoRelationship.entries[nearest].name
    val lower = position.toInt()
    return "${ObbligatoRelationship.entries[lower].name} ↔ ${ObbligatoRelationship.entries[lower + 1].name}"
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
    // Small voice rosters fit one row. Larger ones, including THAW's six,
    // split evenly across two rows.
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

/** Ways the harp's own sustain unfolds, separate from its string material. */
@Composable
private fun OrphicaModePicker(current: OrphicaMode, scheme: Scheme, onSelect: (OrphicaMode) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapeText("MODE", TapeType.pixelSmall, scheme.ink2.tape)
        for (modes in OrphicaMode.entries.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (mode in modes) {
                    val chosen = mode == current
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                            .let { if (chosen) it.pressedBevel(scheme) else it.raisedBevel(scheme) }
                            .semantics { selected = chosen }
                            .tapeClick(label = "MODE ${mode.name}") { onSelect(mode) }
                            .padding(horizontal = 2.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TapeText(mode.name, TapeType.pixelSmall, scheme.ink.tape, maxLines = 1)
                        TapeText(if (chosen) "SELECTED" else "", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 1)
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
 * vertical list: seventeen names have to fit next to a voice picker and five
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

/** Reopen works from recipe JSON, so imported kits use the same door as local pads. */
@Composable
private fun ReopenPadOverlay(
    pads: List<KitPad>,
    scheme: Scheme,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(scheme.lcd.tape).pointerInput(Unit) { detectTapGestures { } }.padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TapeText("REOPEN PAD RECIPE", TapeType.lcdSmall, scheme.lcdInk.tape, Modifier.weight(1f))
                LabButton("CANCEL", scheme, enabled = true, onClick = onCancel)
            }
            TapeText("LOAD, EDIT, THEN SEND TO PAD", TapeType.lcdSmall, scheme.ink2.tape)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pads.sortedBy { it.slot }, key = { it.slot }) { pad ->
                    val patch = Breed.recipeOf(pad)?.patch
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp).raisedBevel(scheme)
                            .tapeClick(label = "REOPEN ${padTag(pad.slot)} ${pad.displayName}") { onPick(pad.slot) }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TapeText("${padTag(pad.slot)} · ${pad.displayName}", TapeType.pixelSmall, scheme.ink2.tape)
                        TapeText("${patch?.engine} · ${patch?.voiceName?.replace('_', ' ')}", TapeType.lcdSmall, scheme.amber.tape)
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
internal fun MacroSlider(
    label: String,
    value: Float,
    fillColor: Color,
    scheme: Scheme,
    onValueChange: (Float) -> Unit,
    /**
     * What the LCD says instead of the whole percent, when a knob's position
     * means a word (SIREN's HOLD at LOOP). Last, after the callback, so the
     * positional five-argument calls below (ATTACK, RELEASE, MOTION) keep
     * binding their lambda to [onValueChange].
     */
    readout: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    Row(
        Modifier.fillMaxWidth().heightIn(min = Layout.MIN_HIT_TARGET.dp).alpha(if (enabled) 1f else .45f),
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
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
                    if (enabled) setProgress { target -> currentOnChange(target.coerceIn(valueRange)); true }
                    else disabled()
                }
                .pointerInput(valueRange, enabled) {
                    if (enabled) awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun fractionAt(x: Float) = valueRange.start +
                            (x / size.width.toFloat()).coerceIn(0f, 1f) * (valueRange.endInclusive - valueRange.start)
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
                    .fillMaxWidth(if (valueRange.endInclusive > valueRange.start) {
                        ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                    } else 0f)
                    .background(fillColor.copy(alpha = 0.85f)),
            )
        }
        Box(Modifier.width(44.dp).heightIn(min = 26.dp).lcdPanel(scheme, 3.dp), contentAlignment = Alignment.Center) {
            TapeText(readout ?: "${(value * 100).roundToInt()}", TapeType.lcdSmall, scheme.lcdInk.tape)
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

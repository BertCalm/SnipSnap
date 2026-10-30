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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.snipsnap.audio.Classifier
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
import com.snipsnap.shell.FlockPadMaker
import com.snipsnap.shell.OhmPadMaker
import com.snipsnap.shell.CreasePadMaker
import com.snipsnap.shell.Copy
import com.snipsnap.shell.ContourPadMaker
import com.snipsnap.shell.DroneMaker
import com.snipsnap.shell.GlintPadMaker
import com.snipsnap.shell.OpenSkyPadMaker
import com.snipsnap.shell.TidalPadMaker
import com.snipsnap.shell.TidalPad
import com.snipsnap.shell.OrigamiPadMaker
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
import com.snipsnap.synth.Aerostat
import com.snipsnap.synth.AerostatPatch
import com.snipsnap.synth.AerostatVoice
import com.snipsnap.synth.Tremor
import com.snipsnap.synth.TremorPatch
import com.snipsnap.synth.TremorVoice
import com.snipsnap.synth.Ballast
import com.snipsnap.synth.BallastPatch
import com.snipsnap.synth.BallastVoice
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Patches
import com.snipsnap.synth.ScannedChain
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Fathom
import com.snipsnap.synth.FathomPatch
import com.snipsnap.synth.FathomVoice
import com.snipsnap.synth.Filament
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentVoice
import com.snipsnap.shell.FilamentPadMaker
import com.snipsnap.shell.FilamentPreviewCache
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.Resin
import com.snipsnap.synth.ResinDrone
import com.snipsnap.synth.ResinPatch
import com.snipsnap.synth.ResinVoice
import com.snipsnap.synth.Tide
import com.snipsnap.synth.TidePatch
import com.snipsnap.synth.TideVoice
import com.snipsnap.synth.Tidal
import com.snipsnap.synth.TidalPatch
import com.snipsnap.synth.TidalVoice
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
import com.snipsnap.synth.MacroSpec
import com.snipsnap.synth.Mercury
import com.snipsnap.synth.MercuryPatch
import com.snipsnap.synth.MercuryVoice
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
import com.snipsnap.synth.Trefoil
import com.snipsnap.synth.TrefoilPatch
import com.snipsnap.synth.TrefoilVoice
import com.snipsnap.synth.TrioVoice
import com.snipsnap.synth.OpenSky
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OpenSkyPresets
import com.snipsnap.synth.OpenSkyVoice
import com.snipsnap.synth.Osprey
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.OspreyVoice
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.OhmVoice
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
import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.ChamberRenderer
import com.snipsnap.synth.ChamberVoice
import com.snipsnap.synth.Nacre
import com.snipsnap.synth.NacrePatch
import com.snipsnap.synth.NacrePresets
import com.snipsnap.synth.NacreVoice
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.Overlap
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.Apsis
import com.snipsnap.synth.ApsisPatch
import com.snipsnap.synth.ApsisVoice
import com.snipsnap.shell.ApsisPad
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
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiGesture
import com.snipsnap.synth.OrigamiGestures
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiVoice
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
private const val SETTINGS_PATCH_PREFIX = "patch."

/** Initial shelf read only; the screen's public API and normal disk reader stay unchanged. */
internal val LocalSynthPresetReader = staticCompositionLocalOf<
    (File) -> Pair<List<UserPresets.Saved>, List<UserPresets.Binned>>
> { { root -> UserPresets.read(root) to UserPresets.bin(root) } }

private const val SETTINGS_SCANNED_RECIPE_PREFIX = "scanned.recipe."

private fun scannedRecipeSettingsKey(voice: ScannedChainVoice): String = "$SETTINGS_SCANNED_RECIPE_PREFIX${voice.name}"
private const val SETTINGS_ORIGAMI_PATCH_PREFIX = "patch.ORIGAMI."
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
private const val SETTINGS_OHM_PATCH = "patch.OHM.STRING"

/** Invalid or foreign settings never become an OHM recipe. Saved model 1 remains model 1. */
private fun restoredOhmPatch(settings: Map<String, String>): OhmPatch? =
    settings[SETTINGS_OHM_PATCH]?.let { text -> runCatching { OhmPatch.fromJsonText(text) }.getOrNull() }

private fun macroSettingsKey(engine: Engine, voice: Enum<*>, macro: String): String =
    "$SETTINGS_MACRO_PREFIX${engine.name}.${voice.name}.$macro"

private fun presetSettingsKey(engine: Engine, voice: Enum<*>): String =
    "$SETTINGS_PRESET_PREFIX${engine.name}.${voice.name}"

private fun embouchureSettingsKey(voice: EmbouchureVoice): String =
    "$SETTINGS_PATCH_PREFIX${EmbouchurePatch.ENGINE}.${voice.name}"

/** The selected BRASS model survives edits even after the preset highlight clears. */
private fun storedEmbouchurePatch(settings: Map<String, String>, voice: EmbouchureVoice): EmbouchurePatch? =
    settings[embouchureSettingsKey(voice)]?.let { text ->
        runCatching { EmbouchurePatch.fromJsonText(text) }.getOrNull()?.takeIf { it.voice == voice }
    }

/**
 * What SYNTH restores for EMBOUCHURE when it reopens (BRASS GROW is a different saved model with
 * different controls, so its recipe must come back whole).
 */
private class EmbouchureRestore(
    settings: Map<String, String>,
    seededPresets: Map<Pair<Engine, Enum<*>>, String>,
    reopenedPads: Set<Pair<Engine, Enum<*>>>,
    /** The initial shelf read (a test seam, see [LocalSynthPresetReader]); kept here to spare SynthScreen a local. */
    val readShelf: (File) -> Pair<List<UserPresets.Saved>, List<UserPresets.Binned>>,
) {
    /**
     * An unresolved YOURS recipe arrives after the default panel starts writing.
     * Keep its original macro bag so that temporary legacy defaults cannot erase GROW.
     */
    val macroSettings: Map<String, String> = settings.toMap()

    /**
     * The restorable recipes, oldest source first: the factory preset the bag names, then the
     * recipe draft, then the `patch.EMBOUCHURE.<VOICE>` snapshot, which is the freshest (it is
     * written from the loaded patch plus its current sliders).
     */
    val patches: Map<Pair<Engine, Enum<*>>, EmbouchurePatch> = buildMap {
        for ((pair, name) in seededPresets) {
            if (pair.first != Engine.EMBOUCHURE) continue
            (Presets.forVoice(pair.first.name, pair.second.name).firstOrNull { it.name == name }
                as? EmbouchurePatch)?.let { put(pair, it) }
        }
        for (draft in SynthDrafts.read(settings)) {
            if (draft !is EmbouchurePatch) continue
            val v = EmbouchureVoice.entries.firstOrNull { it.name == draft.voiceName } ?: continue
            put(Engine.EMBOUCHURE to v, draft)
        }
        for (v in EmbouchureVoice.entries) {
            storedEmbouchurePatch(settings, v)?.let { put(Engine.EMBOUCHURE to v, it) }
        }
    }

    /** The sliders for a restored EMBOUCHURE [patch]; null for every voice with nothing to restore. */
    fun macrosFor(engine: Engine, voice: Enum<*>, settings: Map<String, String>): Map<String, Float>? =
        patches[engine to voice]?.let { restoredMacros(engine, voice, settings, it) }

    /** Adopt the restorable recipes into [loaded], except where a reopened pad already supplied its own. */
    fun seedInto(loaded: MutableMap<Pair<Engine, Enum<*>>, Patch>, reopenedPads: Set<Pair<Engine, Enum<*>>>) {
        for ((pair, patch) in patches) if (pair !in reopenedPads) loaded[pair] = patch
    }

    /**
     * YOURS recipes still waiting on the shelf read. A reopened pad's working recipe is the
     * freshest statement of all, so it is never pending. A false TIDAL entry
     * means the read failed: keep its recipe protected until the player chooses another.
     */
    val pending = mutableStateMapOf<Pair<Engine, Enum<*>>, Boolean>().apply {
        for (pair in seededPresets.keys) {
            if (pair.first == Engine.EMBOUCHURE && pair !in patches && pair !in reopenedPads) put(pair, true)
            // A named-only TIDAL or TREMOR YOURS restore has no typed performance yet.
            // Default sliders must not materialize model 2 before its shelf read.
            if ((pair.first == Engine.TIDAL || pair.first == Engine.TREMOR) && pair !in reopenedPads &&
                SynthDrafts.read(settings).none { it.engine == pair.first.name && it.voiceName == pair.second.name } &&
                Presets.byName(pair.first.name, pair.second.name, seededPresets.getValue(pair)) == null) {
                put(pair, true)
            }
        }
    }

    /** A named recipe's saved model must arrive before default controls can replace it. */
    fun waitsForRecipe(engine: Engine, voice: Enum<*>): Boolean =
        (engine == Engine.TIDAL || engine == Engine.TREMOR) && pending.containsKey(engine to voice)
}

@Composable
private fun rememberEmbouchureRestore(
    settings: Map<String, String>,
    seededPresets: Map<Pair<Engine, Enum<*>>, String>,
    reopenedPads: Set<Pair<Engine, Enum<*>>>,
): EmbouchureRestore {
    val reader = LocalSynthPresetReader.current
    return remember { EmbouchureRestore(settings, seededPresets, reopenedPads, reader) }
}

/** A visited TREMOR default keeps its renderer when its controls enter the navigation bag. */
private fun retainTremorDraft(
    loaded: MutableMap<Pair<Engine, Enum<*>>, Patch>,
    voice: TremorVoice,
    macros: Map<String, Float>,
    model: Int,
) {
    val pair = Engine.TREMOR to voice
    if (pair !in loaded) loaded[pair] = TremorPatch(
        Engine.TREMOR.patchDisplayName(voice), voice, macros, model = model,
    )
}

/** Restore only the selected model's controls; a legacy BRASS recipe never inherits GROW. */
private fun restoredMacros(
    engine: Engine,
    voice: Enum<*>,
    settings: Map<String, String>,
    patch: Patch?,
): Map<String, Float> {
    val defaults = if (patch is EmbouchurePatch) Embouchure.defaults(patch.voice, patch.model)
        else engine.defaults(voice)
    return defaults.mapValues { (name, fallback) ->
        val value = settings[macroSettingsKey(engine, voice, name)]?.toFloatOrNull()
            ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            ?: if (patch is EmbouchurePatch) patch.macros[name] ?: fallback else fallback
        if (name == "TUNE" && patch is EmbouchurePatch) {
            val remaining = (Embouchure.midiRange(patch.voice).last - patch.midi) / Embouchure.TUNE_SEMITONES.toFloat()
            value.coerceIn(0f, remaining.coerceIn(0f, 1f))
        } else value
    }
}

/** ORIGAMI's source, pitch and frozen model survive editing away from a preset name. */
private fun restoredOrigamiPatches(settings: Map<String, String>): Map<OrigamiVoice, OrigamiPatch> =
    OrigamiVoice.entries.mapNotNull { voice ->
        val saved = settings["$SETTINGS_ORIGAMI_PATCH_PREFIX${voice.name}"] ?: return@mapNotNull null
        val patch = runCatching { OrigamiPatch.fromJsonText(saved) }.getOrNull()
            ?.takeIf { it.voice == voice } ?: return@mapNotNull null
        voice to patch
    }.toMap()

/** The displayed pitch is the absolute override, with each model's own sparse defaults. */
private fun origamiEditingMacros(patch: OrigamiPatch): Map<String, Float> {
    val resolved = patch.resolvedMacros()
    val absoluteMidi = patch.midi ?: return resolved
    return resolved + ("TUNE" to (absoluteMidi - Origami.MIDI_MIN).toFloat() / Origami.TUNE_SEMITONES)
}

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
 * OVERLAP, CREASE, CAUSTIC), the engine's [base] otherwise. Shared by `loadPreset` and
 * by reopening a pad, so both put a saved recipe on the same sliders.
 */
private fun editableDefaults(base: Map<String, Float>, editable: Patch): Map<String, Float> = when (editable) {
    is OrphicaPatch -> Orphica.macrosFrom(editable.config)
    is OspreyPatch -> Osprey.defaults(editable.voice, editable.modelVersion)
    is OverlapPatch -> Overlap.defaults(editable.topology, editable.model)
    is CreasePatch -> Crease.defaults(editable.voice, editable.model)
    is CausticPatch -> CausticInstrument.defaults(editable.voice, editable.model)
    is EmbouchurePatch -> Embouchure.defaults(editable.voice, editable.model)
    is TidalPatch -> Tidal.defaults(editable.voice, editable.model)
    is OhmPatch -> Ohm.defaults(editable.model)
    else -> base
}

/**
 * The macros a loaded patch lays over [editableDefaults]. ORIGAMI shows its
 * absolute pitch override as the TUNE slider position; every other engine's
 * own macros are used as saved.
 */
private fun editableMacros(editable: Patch): Map<String, Float> =
    if (editable is OrigamiPatch) origamiEditingMacros(editable) else editable.macros

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
 * registered engines, including ALLOY, CIRCUIT, THAW, MURK, NIMBUS, UNDERTOW, TESSERA, PITCHWHEEL, PRISM, CONTOUR, STOOP, SKITTER, RELAY, REGISTERS, OVEN, MURMUR, TRIO, ORIGAMI and OHM, join it here.
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
 *    FILAMENT also updates its complete draft with every macro edit.
 *  - `patch.ORIGAMI.<VOICE>` — ORIGAMI's complete edited recipe, including
 *    its model, source seed, absolute note and velocity. Like the `patch.`
 *    entries below, it is fresher than a `recipe.` entry and is adopted.
 *  - `patch.ORPHICA.<MATERIAL>` — ORPHICA's complete editable recipe, keeping
 *    its mode, root and capture with its macro values across navigation.
 *  - `patch.OVEN.<VOICE>` — OVEN's complete current recipe, including the
 *    saved root, strike velocity and model after the preset has been edited.
 *    Where a `patch.` entry and a `recipe.` entry both exist for a voice,
 *    the `patch.` one is the fresher and is adopted.
 *  - `patch.EMBOUCHURE.<VOICE>` — the active player recipe, including its
 *    model, gesture, MIDI and velocity. This keeps an edited BRASS GROW
 *    patch in its four-control mode after returning to SYNTH. It is fresher
 *    than a `recipe.` entry and is adopted.
 *  - `scanned.recipe.<VOICE>` — SCANNED CHAIN's complete pad recipe (its saved
 *    shape, physical settings and any recalled effects), so a trip to KIT to
 *    hear the rendered pad returns to the same sound.
 *  - `shoal.patch.<VOICE>` — the complete Shoal recipe, retaining material,
 *    synthesis model, pitch and velocity after edits clear a preset highlight.
 *    It too is fresher than a `recipe.` entry for that voice and is adopted.
 *  - `patch.OHM.STRING` — OHM's complete edited recipe, including model,
 *    native note, velocity, seed and finite performance gesture. Like the
 *    other `patch.` entries it is fresher than a `recipe.` entry and is adopted.
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

    val seed = remember { SynthSeed(settings) }

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
    // EMBOUCHURE's restore state, one holder rather than four locals: this composable is already
    // near the limit the Kotlin/D8 toolchain verifies (a few more locals failed device verification).
    val embouchureRestore = rememberEmbouchureRestore(settings, seed.seededPresets, seed.seededWork.keys)
    // Custom coordinates and recalled pad effects are part of this voice's work,
    // including a trip to KIT to hear the rendered pad. The navigation bag holds
    // the complete finite recipe; no source WAV or mutable solver state is needed.
    val seededDrafts = remember { SynthDrafts.read(settings) }
    val seededFilamentPatches = remember { seededDrafts.filterIsInstance<FilamentPatch>().associateBy { it.voice } }
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
                    val origami =if (e == Engine.ORIGAMI) seed.restoredOrigami[v as OrigamiVoice] else null
                    val orphica = if (e == Engine.ORPHICA) {
                        storedOrphicaPatch(settings, v as OrphicaMaterial)
                            ?: (Presets.forVoice(e.name, v.name).firstOrNull {
                                it.name == settings[presetSettingsKey(e, v)]
                            } as? OrphicaPatch)
                    } else null
                    val nativeFactory = if (e == Engine.FILAMENT || e == Engine.CHAMBER || e == Engine.TREMOR || e == Engine.NACRE) {
                        factoryPresets(e, v).firstOrNull {
                            it.name == settings[presetSettingsKey(e, v)]
                        }
                    } else null
                    val defaults = when {
                        origami != null -> origamiEditingMacros(origami)
                        orphica != null -> Orphica.macrosFrom(orphica.config) + orphica.macros
                        else -> e.defaults(v) + nativeFactory?.macros.orEmpty()
                    }
                    val recipe = seed.seededOvenPatches[e to v]
                        ?: if (e == Engine.FILAMENT) seededFilamentPatches[v as FilamentVoice]
                        else if (e == Engine.PLUCK && v == PluckVoice.UPRIGHT)
                            seededDrafts.filterIsInstance<PluckPatch>().firstOrNull { it.voice == PluckVoice.UPRIGHT }
                        else if (e == Engine.TIDAL) seededDrafts.firstOrNull { it is TidalPatch && it.voice == v }
                        else if (e == Engine.TREMOR) seededDrafts.firstOrNull { it is TremorPatch && it.voice == v }
                        else if (e == Engine.CHAMBER) seed.restoredChamber[v as ChamberVoice]
                        else if (e == Engine.NACRE) seededDrafts.filterIsInstance<NacrePatch>()
                            .firstOrNull { it.voice == v } else null
                    // The bag's value where it parses, the default where it
                    // does not (§3.6): an unparseable or non-finite string
                    // is a stale bag, never a crash.
                    // A reopened pad's working recipe is the freshest statement of
                    // its macros; a complete OVEN, FILAMENT, CHAMBER or OHM recipe is next: their legacy
                    // macro keys may predate an edit followed by a voice switch.
                    // SCANNED CHAIN's saved recipe carries its own macros likewise.
                    val workingPatch = seed.seededWork[e to v]?.recipe?.patch?.let(::editablePreset)
                    val restoredScanned = if (e == Engine.SCANNED_CHAIN) seed.restoredScannedRecipes[v as ScannedChainVoice]?.patch?.macros else null
                    put(e to v, when {
                        workingPatch != null -> editableDefaults(e.defaults(v), workingPatch) + editableMacros(workingPatch)
                        e == Engine.OHM && seed.restoredOhm != null -> Ohm.defaults(seed.restoredOhm.model) + seed.restoredOhm.macros
                        restoredScanned != null -> defaults + restoredScanned
                        recipe is TidalPatch -> Tidal.defaults(recipe.voice, recipe.model) + recipe.macros
                        recipe != null -> defaults + recipe.macros
                        settings.isEmpty() || origami != null || orphica != null -> defaults
                        // EMBOUCHURE restores only the selected model's controls; a legacy
                        // BRASS recipe never inherits GROW and GROW never inherits model 2.
                        else -> embouchureRestore.macrosFor(e, v, settings) ?: defaults.mapValues { (name, fallback) ->
                            // Finite CAUSTIC and CHAMBER reserve HOLD: a stale
                            // navigation value must not leave the voice stuck.
                            if ((e == Engine.CAUSTIC || e == Engine.CHAMBER || e == Engine.NACRE) && name == "HOLD") fallback
                            else if (e == Engine.TIDAL && name == "HOLD") {
                                if (settings[macroSettingsKey(e, v, name)]?.toFloatOrNull() == 1f) 1f else 0f
                            } else settings[macroSettingsKey(e, v, name)]?.toFloatOrNull()
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
    // REGISTERS, OVERLAP, CREASE, CAUSTIC and LATTICE retain their synthesis model, saved pitch and velocity.
    // OVEN likewise retains its saved root, strike velocity and material model.
    // PRISM also preserves its spectral/envelope template and strike velocity,
    // ALLOY its synthesis configuration, CONTOUR its authored table pair and
    // trajectory configuration, SPOOL its tape-machine configuration and
    // velocity, SKITTER its noise seed, RELAY its generated sources and phrase
    // schedules, EMBOUCHURE and BELLOWS their player gestures, ONSET its renderer version, and MURMUR its operator routing, formant
    // targets and envelope definitions. OSPREY retains its variation seed, finite duration
    // and model version. VENT keeps its note, velocity, trajectory seed and saved model.
    // FLOCK retains its explicit note, velocity, full-width
    // variation seed, renderer model, explicit playback mode and held release. OPENSKY retains its authored timbre, note,
    // velocity, gate, effect mode and model. ORPHICA retains its material, mode,
    // capture, seed and physical grain configuration. TRIO retains its seeded performance
    // and model. ORIGAMI retains its model, seed, absolute MIDI override and velocity.
    // FILAMENT retains its strand configuration, frozen model, root and velocity.
    // OHM retains note, velocity, seed, gesture and renderer model, including
    // when the MAKE bench is left.
    // SCANNED CHAIN retains its saved shape, physical configuration, note and velocity.
    // Keep that state through preview, editing, saving and placing by
    // editing the loaded patch rather than constructing anew.
    // A working recipe survives independently of its preset highlight:
    // editing a slider must not discard saved pitch, velocity or model on
    // a trip to the grid. Older bags fall back to their named factory
    // preset; a YOURS preset is adopted when its shelf read lands below.
    val loadedPatchesByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, Patch>().apply {
            for (patch in seededDrafts) {
                val e = Engine.entries.firstOrNull { it.name == patch.engine } ?: continue
                val v = e.voices().firstOrNull { it.name == patch.voiceName } ?: continue
                put(e to v, editablePreset(patch))
            }
            for ((pair, name) in seed.seededPresets) {
                if (containsKey(pair)) continue
                val factory = factoryPresets(pair.first, pair.second).firstOrNull { it.name == name }
                if (factory != null) {
                    val editable = editablePreset(factory)
                    // Before modelled TREMOR recipes, a named factory and macro bag
                    // described the original renderer. Loading that old bag is not a
                    // request to replace its sound with today's factory model.
                    put(pair, if (editable is TremorPatch)
                        editable.copy(model = seed.tremorModelsWithoutRecipe.getValue(editable.voice))
                    else editable)
                }
            }
            // SCANNED CHAIN's saved recipe holds its whole shape and settings.
            for ((v, recipe) in seed.restoredScannedRecipes) put(Engine.SCANNED_CHAIN to v, requireNotNull(recipe.patch))
            // A reopened pad's working recipe replaces the draft and preset
            // restores above. SHOAL, OVEN and ORPHICA then re-read their own
            // complete snapshots below; those are written from this same loaded
            // patch, so they carry the same recipe.
            for ((pair, work) in seed.seededWork) put(pair, editablePreset(requireNotNull(work.recipe.patch)))
            // EMBOUCHURE's own newest recipe (its model, gesture, pitch and velocity) beats the
            // draft and preset restores above, but never a reopened pad's working recipe.
            embouchureRestore.seedInto(this, seed.seededWork.keys)
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
            putAll(seed.seededOvenPatches)
            for (material in OrphicaMaterial.entries) {
                storedOrphicaPatch(settings, material)?.let { put(Engine.ORPHICA to material, it) }
            }
            // A reopened pad's working recipe (seededWork) is the freshest ORIGAMI
            // statement, so the bag's snapshot never replaces it.
            for ((origamiVoice, patch) in seed.restoredOrigami) {
                val pair = Engine.ORIGAMI to origamiVoice
                if (pair !in seed.seededWork) put(pair, patch)
            }
            // Likewise OHM: the bag's complete recipe never replaces a reopened pad's.
            if (seed.restoredOhm != null && (Engine.OHM to OhmVoice.STRING) !in seed.seededWork) {
                put(Engine.OHM to OhmVoice.STRING, seed.restoredOhm)
            }
        }
    }
    val macros = macrosByVoice.getValue(engine to voice)
    // Every macro write (a slider, a preset, SCRAMBLE) lands on the current
    // (engine, voice), so this is the one place the bag needs to follow; the
    // pairs the player left earlier already wrote theirs when they were open.
    LaunchedEffect(engine, voice, macros, embouchureRestore.pending.containsKey(engine to voice)) {
        // Temporary model-2 controls must not replace a still-unread YOURS recipe.
        if (embouchureRestore.pending.containsKey(engine to voice)) return@LaunchedEffect
        // Keep a visited default's renderer before persisting its controls, so
        // a later restore cannot mistake the new macro bag for an old model.
        if (engine == Engine.TREMOR) retainTremorDraft(
            loadedPatchesByVoice, voice as TremorVoice, macros,
            seed.tremorModelsWithoutRecipe.getValue(voice as TremorVoice),
        )
        if (engine == Engine.EMBOUCHURE) {
            val prefix = "$SETTINGS_MACRO_PREFIX${engine.name}.${voice.name}."
            settings.keys.removeAll { it.startsWith(prefix) && it.removePrefix(prefix) !in macros }
        }
        for ((name, value) in macros) settings[macroSettingsKey(engine, voice, name)] = value.toString()
    }
    fun currentDrafts(): List<Patch> = loadedPatchesByVoice.map { (pair, patch) ->
        // FILAMENT, TIDAL, TREMOR, CHAMBER, PRISM, NACRE and UPRIGHT keep complete recipes in the
        // existing draft format, including a last-frame macro edit before navigation.
        if (patch is FilamentPatch || patch is TidalPatch || patch is TremorPatch || patch is ChamberPatch ||
            patch is PrismPatch || patch is NacrePatch ||
            patch is PluckPatch && patch.voice == PluckVoice.UPRIGHT)
            patch.withMacros(macrosByVoice.getValue(pair)) else patch
    }
    val loadedRecipes = currentDrafts()
    LaunchedEffect(loadedRecipes) {
        SynthDrafts.write(settings, loadedRecipes)
    }
    // Reopened pads retain the whole processing recipe, including its explicit
    // alias flag and named treatment. Loading a preset starts a new recipe.
    val loadedRecipesByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, PadRecipe>().apply {
            for ((pair, work) in seed.seededWork) put(pair, work.recipe)
        }
    }
    val reopenedPadsByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, SynthWork.Source>().apply {
            for ((pair, work) in seed.seededWork) work.source?.let { put(pair, it) }
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
    // Scoped so the debug dex frees these registers after the effect: SynthScreen's
    // one method sits at ART's verifier limit (see [macroReadout]).
    run {
        val recipesSnapshot = loadedRecipesByVoice.toMap()
        val patchesSnapshot = loadedPatchesByVoice.toMap()
        val macroSnapshot = macrosByVoice.toMap()
        val sourcesSnapshot = reopenedPadsByVoice.toMap()
        LaunchedEffect(recipesSnapshot, patchesSnapshot, macroSnapshot, sourcesSnapshot) { persistReopenedWork() }
    }
    // A navigation tap can dispose the bench before the state effect runs.
    // These maps remain the live session maps, so flush the final edit here.
    DisposableEffect(Unit) {
        onDispose {
            SynthDrafts.write(settings, currentDrafts())
            persistReopenedWork()
        }
    }
    val loadedPatch = loadedPatchesByVoice[engine to voice]
    val prismMotionStudio = rememberPrismMotionStudio()
    // SCANNED CHAIN's recalled or restored pad recipe (effects and all) is kept apart
    // from a reopened pad's working recipe: it is not a shelf pad, so it never writes
    // a reopen entry and never clears the preset highlight. A reopened recipe wins.
    val recalledRecipesByVoice = remember {
        mutableStateMapOf<Pair<Engine, Enum<*>>, PadRecipe>().apply {
            for ((v, recipe) in seed.restoredScannedRecipes) put(Engine.SCANNED_CHAIN to v, recipe)
        }
    }
    val activeRecipe = loadedRecipesByVoice[engine to voice] ?: recalledRecipesByVoice[engine to voice]
    fun buildCurrentPatch(name: String): Patch {
        if (engine == Engine.TREFOIL) {
            // SAVE or SEND can arrive in the same frame as a root/touch/contact edit.
            // Read the current recipe map rather than the previous composition's snapshot.
            val current = loadedPatchesByVoice[engine to voice] as? TrefoilPatch
            val controls = macrosByVoice.getValue(engine to voice)
            return current?.let { Patches.edited(it, name, controls) }
                ?: TrefoilPatch(name, voice as TrefoilVoice, controls)
        }
        if (engine == Engine.NACRE) {
            // Use the live draft for same-frame root/velocity and macro edits. Copying it
            // retains an older saved rendition and the full-width variation seed.
            val current = loadedPatchesByVoice[engine to voice] as? NacrePatch
            val controls = macrosByVoice.getValue(engine to voice)
            return current?.let { Patches.edited(it, name, controls) }
                ?: NacrePatch(name, voice as NacreVoice, controls)
        }
        if (engine == Engine.PRISM) {
            // Detail edits and accessibility events can share a frame with SAVE or HOLD.
            val current = loadedPatchesByVoice[engine to voice] as? PrismPatch
            val controls = macrosByVoice.getValue(engine to voice)
            return current?.let { Patches.edited(it, name, controls) }
                ?: PrismPatch(name, voice as PrismVoice, controls)
        }
        if (engine == Engine.TIDAL) {
            // Accessibility and pointer events can arrive before recomposition.
            // Saving/sending must observe the last edit, not the previous frame's patch.
            val current = loadedPatchesByVoice[engine to voice] as? TidalPatch
            val controls = macrosByVoice.getValue(engine to voice)
            return current?.let { Patches.edited(it, name, controls) }
                ?: TidalPatch(name, voice as TidalVoice, controls)
        }
        if (engine == Engine.TREMOR) {
            // A queued save or pad update can share the frame of a preset choice.
            // Read its live recipe so the renderer model cannot lag one frame behind.
            val current = loadedPatchesByVoice[engine to voice] as? TremorPatch
            val controls = macrosByVoice.getValue(engine to voice)
            return current?.let { Patches.edited(it, name, controls) }
                ?: TremorPatch(name, voice as TremorVoice, controls,
                    model = seed.tremorModelsWithoutRecipe.getValue(voice as TremorVoice))
        }
        return loadedPatch?.let {
            if (it is OrphicaPatch) orphicaPatchForControls(it, name, macros)
            else Patches.edited(it, name, macros)
        } ?: engine.buildPatch(name, voice, macros)
    }
    // SCANNED CHAIN keeps its complete pad recipe (drawn or sampled shape, recalled
    // effects) in the navigation bag, so a trip to KIT to hear the rendered pad
    // returns to the same sound.
    LaunchedEffect(engine, voice, macros, loadedPatch, activeRecipe) {
        if (engine == Engine.SCANNED_CHAIN) {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice))
            settings[scannedRecipeSettingsKey(voice as ScannedChainVoice)] =
                (activeRecipe?.copy(patch = patch) ?: PadRecipe(
                    patch = patch,
                    fx = Presets.landingFor(engine.name, voice.name, macros),
                )).toJsonText()
        }
    }
    // Keep complete Shoal recipes for every voice, rather than only the currently
    // selected preset name. Material and legacy model survive ordinary edits and
    // tab/voice switches alongside independent root and playing energy.
    run {
        val shoalSettingsPatches = ShoalVoice.entries.associateWith { v ->
            val values = macrosByVoice.getValue(Engine.SHOAL to v)
            val saved = loadedPatchesByVoice[Engine.SHOAL to v] as? ShoalPatch
            saved?.withMacros(values) ?: ShoalPatch(Engine.SHOAL.patchDisplayName(v), v, values)
        }
        LaunchedEffect(shoalSettingsPatches) {
            for ((v, patch) in shoalSettingsPatches) settings[shoalPatchSettingsKey(v)] = patch.toJsonText()
        }
    }
    LaunchedEffect(engine, voice, loadedPatch, macros) {
        if (engine == Engine.ORPHICA) {
            settings["$SETTINGS_ORPHICA_PREFIX${voice.name}"] = buildCurrentPatch(engine.patchDisplayName(voice)).toJsonText()
        }
        if (engine == Engine.ORIGAMI) {
            settings["$SETTINGS_ORIGAMI_PATCH_PREFIX${voice.name}"] =
                buildCurrentPatch(engine.patchDisplayName(voice)).toJsonText()
        }
    }
    LaunchedEffect(engine, voice, macros, loadedPatch, embouchureRestore.pending.containsKey(engine to voice)) {
        if (engine == Engine.EMBOUCHURE && !embouchureRestore.pending.containsKey(engine to voice)) {
            settings[embouchureSettingsKey(voice as EmbouchureVoice)] =
                buildCurrentPatch(engine.patchDisplayName(voice)).toJsonText()
        }
    }
    fun keepOhmPatch(patch: OhmPatch) {
        loadedPatchesByVoice[Engine.OHM to OhmVoice.STRING] = patch
        macrosByVoice[Engine.OHM to OhmVoice.STRING] = Ohm.defaults(patch.model) + patch.macros
        // Write with the edit, before navigation can dispose remember-state.
        settings[SETTINGS_OHM_PATCH] = patch.toJsonText()
    }
    fun keepOhmMacros(values: Map<String, Float>) {
        if (engine != Engine.OHM) return
        settings.remove(presetSettingsKey(Engine.OHM, OhmVoice.STRING))
        val source = loadedPatchesByVoice[Engine.OHM to OhmVoice.STRING] as? OhmPatch
            ?: OhmPatch(engine.patchDisplayName(voice), Ohm.defaults())
        keepOhmPatch(Patches.edited(source, source.name, values) as OhmPatch)
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
            putAll(seed.seededPresets)
            seed.seededWork.keys.forEach { remove(it) }
        }
    }
    // A preset can be cleared or set on a voice that is not showing (a FORGET
    // on another voice's chip, a save), so the bag takes the whole map.
    run {
        val presetNames = currentPresetByVoice.toMap()
        LaunchedEffect(presetNames) {
            settings.keys.removeAll { it.startsWith(SETTINGS_PRESET_PREFIX) }
            for ((pair, name) in presetNames) settings[presetSettingsKey(pair.first, pair.second)] = name
        }
    }
    // OVEN's relative TUNE alone cannot recover an edited root or strike.
    // Keep its full recipe in this lab's existing bag, including voices left
    // earlier in the visit. Other engines retain their established bag format.
    run {
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
        // Motion recipes contain a finite opening. Keep HOLD below the loop
        // threshold so a slider or accessibility edit cannot discard that gesture.
        if (name == "HOLD" && prismFiniteMotion(loadedPatchesByVoice[engine to voice] as? PrismPatch)) {
            return 0f..Math.nextDown(Prism.LOOP_THRESHOLD)
        }
        if (name != "TUNE") return 0f..1f
        return when (val patch = loadedPatch) {
            is EmbouchurePatch -> 0f..((Embouchure.midiRange(patch.voice).last - patch.midi) / Embouchure.TUNE_SEMITONES.toFloat())
            is BellowsPatch -> 0f..((Bellows.midiRange(patch.voice).last - patch.midi) / Bellows.TUNE_SEMITONES.toFloat())
            else -> 0f..1f
        }
    }
    fun ensureNativeDraft() {
        val pair = engine to voice
        // A first macro edit or SCRAMBLE may start from defaults. Materialize
        // that edited recipe so onDispose can flush it before navigation.
        if (pair !in loadedPatchesByVoice) {
            when (engine) {
                Engine.FILAMENT -> loadedPatchesByVoice[pair] = FilamentPatch(
                    engine.patchDisplayName(voice), voice as FilamentVoice, macrosByVoice.getValue(pair),
                )
                Engine.CHAMBER -> loadedPatchesByVoice[pair] = ChamberPatch(
                    engine.patchDisplayName(voice), voice as ChamberVoice, macrosByVoice.getValue(pair),
                )
                Engine.TREMOR -> retainTremorDraft(
                    loadedPatchesByVoice, voice as TremorVoice, macrosByVoice.getValue(pair),
                    seed.tremorModelsWithoutRecipe.getValue(voice as TremorVoice),
                )
                Engine.NACRE -> loadedPatchesByVoice[pair] = NacrePatch(
                    engine.patchDisplayName(voice), voice as NacreVoice, macrosByVoice.getValue(pair),
                )
                else -> Unit
            }
        }
        if (engine == Engine.PLUCK && voice == PluckVoice.UPRIGHT && pair !in loadedPatchesByVoice) {
            loadedPatchesByVoice[pair] = PluckPatch(
                engine.patchDisplayName(voice), PluckVoice.UPRIGHT, macrosByVoice.getValue(pair),
                velocity = .65f,
            )
        }
        if (engine == Engine.TIDAL && pair !in loadedPatchesByVoice) {
            loadedPatchesByVoice[pair] = TidalPatch(
                engine.patchDisplayName(voice), voice as TidalVoice, macrosByVoice.getValue(pair),
            )
        }
    }
    fun updateUprightVelocity(value: Float) {
        if (engine != Engine.PLUCK || voice != PluckVoice.UPRIGHT) return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        settings.remove(presetSettingsKey(engine, voice))
        val patch = (buildCurrentPatch(engine.patchDisplayName(voice)) as PluckPatch)
            .copy(velocity = value.coerceIn(0f, 1f))
        loadedPatchesByVoice[engine to voice] = patch
        // Flush with the gesture: a navigation tap can precede the draft effect.
        settings["recipe.PLUCK.UPRIGHT"] = patch.toJsonText()
    }
    fun updateMacro(name: String, value: Float) {
        if (embouchureRestore.waitsForRecipe(engine, voice)) return
        touched = true
        embouchureRestore.pending.remove(engine to voice)
        currentPresetByVoice.remove(engine to voice)
        val bounded = value.coerceIn(macroRange(name))
        val values = macrosByVoice.getValue(engine to voice) + (name to bounded)
        macrosByVoice[engine to voice] = values
        // Navigation can replace this screen before its state effect runs.
        settings[macroSettingsKey(engine, voice, name)] = bounded.toString()
        ensureNativeDraft()
        // An absolute ORIGAMI MIDI note overrides TUNE. A deliberate pitch edit
        // must move that override too; timbral edits retain the whole saved recipe.
        val origami = loadedPatch as? OrigamiPatch
        if (name == "TUNE" && origami?.midi != null) {
            loadedPatchesByVoice[engine to voice] = origami.copy(midi = Origami.midiFor(origami.voice, bounded))
        }
        keepOhmMacros(values)
    }
    fun updateOhmPerformance(edit: (OhmPatch) -> OhmPatch) {
        if (engine != Engine.OHM) return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        settings.remove(presetSettingsKey(Engine.OHM, OhmVoice.STRING))
        val source = loadedPatchesByVoice[Engine.OHM to OhmVoice.STRING] as? OhmPatch
            ?: OhmPatch(engine.patchDisplayName(voice), Ohm.defaults())
        keepOhmPatch(edit(Patches.edited(source, source.name, macrosByVoice.getValue(engine to voice)) as OhmPatch))
    }
    fun updateOrigamiGesture(gesture: OrigamiGesture) {
        if (engine != Engine.ORIGAMI) return
        val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as OrigamiPatch
        if (patch.model != Origami.MODEL_VERSION || patch.gesture == gesture) return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        settings.remove(presetSettingsKey(engine, voice))
        val next = patch.copy(gesture = gesture)
        loadedPatchesByVoice[engine to voice] = next
        // A gesture tap can be followed immediately by navigation, before the
        // state effect runs. Keep the complete recipe with the edit itself.
        settings["$SETTINGS_ORIGAMI_PATCH_PREFIX${voice.name}"] = next.toJsonText()
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
    fun updateFlockPatch(change: (FlockPatch) -> FlockPatch) {
        val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as? FlockPatch ?: return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        loadedPatchesByVoice[engine to voice] = change(patch)
    }
    fun updateTidalPatch(expectedVoice: TidalVoice, change: (TidalPatch) -> TidalPatch) {
        if (engine != Engine.TIDAL || voice != expectedVoice ||
            embouchureRestore.pending.containsKey(engine to voice)) return
        val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as? TidalPatch ?: return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        settings.remove(presetSettingsKey(engine, voice))
        val updated = change(patch)
        loadedPatchesByVoice[engine to voice] = updated
        // Performance controls also own NOTE/HOLD. Keep the live macro map in
        // sync so buildCurrentPatch cannot overwrite that choice on save/send.
        macrosByVoice[engine to voice] = Tidal.defaults(updated.voice, updated.model) + updated.macros
    }
    fun updateFilamentPatch(change: (FilamentPatch) -> FilamentPatch) {
        val pair = engine to voice
        val controls = macrosByVoice.getValue(pair)
        val patch = (loadedPatchesByVoice[pair] as? FilamentPatch)?.withMacros(controls)
            ?: FilamentPatch(engine.patchDisplayName(voice), voice as FilamentVoice, controls)
        touched = true
        currentPresetByVoice.remove(pair)
        loadedPatchesByVoice[pair] = change(patch)
    }
    fun updateOrphicaConfig(change: (OrphicaConfig) -> OrphicaConfig) {
        val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as? OrphicaPatch ?: return
        touched = true
        currentPresetByVoice.remove(engine to voice)
        loadedPatchesByVoice[engine to voice] = patch.copy(config = change(patch.config))
    }
    fun editPrismMotion(target: PrismVoice, change: (PrismPatch) -> PrismPatch) {
        if (engine != Engine.PRISM || voice != target) return
        val pair = engine to voice
        val name = loadedPatchesByVoice[pair]?.name ?: engine.patchDisplayName(voice)
        val next = change(buildCurrentPatch(name) as PrismPatch)
        touched = true
        Snapshot.withMutableSnapshot {
            currentPresetByVoice.remove(pair)
            loadedPatchesByVoice[pair] = next
            macrosByVoice[pair] = Prism.defaults(next.voice) + next.macros
        }
    }
    fun loadPreset(patch: Patch) {
        touched = true
        loadedRecipesByVoice.remove(engine to voice)
        recalledRecipesByVoice.remove(engine to voice)
        reopenedPadsByVoice.remove(engine to voice)
        // Audition recipes can save an explicit root beside a different TUNE.
        // Canonicalize the sliders without changing that sound or its model.
        val editable = editablePreset(patch)
        if (editable is PrismPatch) prismMotionStudio.selected(editable)
        // Merged over the engine's own defaults, same as macrosByVoice's own
        // seeding above: a preset only has to name the macros it cares
        // about (PUNCH, added after every existing THUMP preset was
        // written, names none of them), and every read site here relies on
        // that "no missing key" invariant - a bare `patch.macros` would
        // otherwise leave PUNCH's slider crashing on getValue the moment
        // someone loaded a pre-PUNCH preset.
        // Physical ORPHICA settings, OSPREY's saved model, OVERLAP's saved
        // model, CREASE's saved model and EMBOUCHURE's saved model (BRASS GROW
        // has its own four controls) all select their displayed defaults;
        // loading a partial recipe keeps its sound.
        val loadedMacros = editableDefaults(engine.defaults(voice), editable) + editableMacros(editable)
        // A model can change the control names (legacy BRASS versus GROW).
        // Publish the recipe and its controls together, including the restore gate.
        Snapshot.withMutableSnapshot {
            loadedPatchesByVoice[engine to voice] = editable
            macrosByVoice[engine to voice] = loadedMacros
            embouchureRestore.pending.remove(engine to voice)
            currentPresetByVoice[engine to voice] = patch.name
        }
        if (editable is OhmPatch) {
            keepOhmPatch(editable.copy(macros = loadedMacros))
            settings[presetSettingsKey(Engine.OHM, OhmVoice.STRING)] = patch.name
        }
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
            val (live, bin) = offMain { embouchureRestore.readShelf(shelfRoot) }
            userPresets = live
            binnedPresets = bin
            // The YOURS presets the bag named (§3.6), now that the shelf has
            // been read: re-adopt each whole, or drop the highlight when the
            // preset was forgotten while the lab was away. Live state is
            // re-read here, after the await: a preset tapped meanwhile wins.
            for ((pair, name) in seed.seededPresets) {
                if (currentPresetByVoice[pair] != name) {
                    embouchureRestore.pending.remove(pair)
                    continue
                }
                val factory = factoryPresets(pair.first, pair.second).firstOrNull { it.name == name }
                // SHOAL always holds a complete snapshot, so a YOURS name must still be adopted whole.
                if (loadedPatchesByVoice[pair] != null && (pair.first != Engine.SHOAL || factory != null)) {
                    // OVEN can retain a full edited recipe after its YOURS
                    // preset has left the shelf. Keep the sound, not a stale chip.
                    if (pair.first == Engine.OVEN && factory == null &&
                        UserPresets.forVoice(live, pair.first.name, pair.second.name).none { it.name == name }) {
                        currentPresetByVoice.remove(pair)
                    }
                    embouchureRestore.pending.remove(pair)
                    continue
                }
                val mine = UserPresets.forVoice(live, pair.first.name, pair.second.name)
                    .firstOrNull { it.patch.name == name }?.patch
                // The delayed shelf read must not expose a new model with the old
                // control map, or clear its persistence gate before both are ready.
                Snapshot.withMutableSnapshot {
                    if (mine != null) {
                        val editable = editablePreset(mine)
                        loadedPatchesByVoice[pair] = editable
                        if (editable is PrismPatch) {
                            macrosByVoice[pair] = Prism.defaults(editable.voice) + editable.macros
                            if (pair == (engine to voice)) prismMotionStudio.selected(editable)
                        }
                        if (editable is EmbouchurePatch) {
                            macrosByVoice[pair] = restoredMacros(pair.first, pair.second, embouchureRestore.macroSettings, editable)
                        }
                        if (editable is ShoalPatch) macrosByVoice[pair] = Shoal.defaults(editable.voice) + editable.macros
                        if (editable is FilamentPatch) macrosByVoice[pair] = Filament.defaults(editable.voice) + editable.macros
                        if (editable is TidalPatch) macrosByVoice[pair] = Tidal.defaults(editable.voice, editable.model) + editable.macros
                        if (editable is TremorPatch) macrosByVoice[pair] = Tremor.defaults(editable.voice) + editable.macros
                        if (editable is ChamberPatch) macrosByVoice[pair] = Chamber.defaults(editable.voice) + editable.macros
                        if (editable is NacrePatch) macrosByVoice[pair] = Nacre.defaults(editable.voice) + editable.macros
                        // The named preset is being restored whole. A partial legacy
                        // OVERLAP recipe must fill its sliders from its saved model.
                        if (mine is OverlapPatch) {
                            macrosByVoice[pair] = Overlap.defaults(mine.topology, mine.model) + mine.macros
                        }
                    } else currentPresetByVoice.remove(pair)
                    embouchureRestore.pending.remove(pair)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SynthScreen", "presets: unreadable", e)
            for (pair in embouchureRestore.pending.keys.toList()) {
                if (pair.first == Engine.TIDAL || pair.first == Engine.TREMOR) embouchureRestore.pending[pair] = false
            }
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
                val gone = offMain {
                    UserPresets.forget(shelfRoot, patch.engine, patch.voiceName, patch.name, System.currentTimeMillis())
                }
                val (live, bin) = offMain { UserPresets.read(shelfRoot) to UserPresets.bin(shelfRoot) }
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
                val back = offMain { UserPresets.unforget(shelfRoot, binned) }
                val (live, bin) = offMain { UserPresets.read(shelfRoot) to UserPresets.bin(shelfRoot) }
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
                val (saved, all) = offMain {
                    val s = UserPresets.save(shelfRoot, patch, System.currentTimeMillis())
                    s to UserPresets.read(shelfRoot)
                }
                userPresets = all
                currentPresetByVoice[savedEngine to savedVoice] = saved.name
                embouchureRestore.pending.remove(savedEngine to savedVoice)
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
    val flockHeldPreview = rememberFlockHeldPreview(
        if (engine == Engine.FLOCK) buildCurrentPatch(engine.patchDisplayName(voice)) as FlockPatch else null,
    ) { onToast(Copy.RENDER_FAILED) }

    var voicePlayer by remember { mutableStateOf<TapeVoice?>(null) }
    DisposableEffect(Unit) { onDispose { voicePlayer?.release() } }
    // Backgrounding mid-audition must stop the voice, not wait for this
    // composable to next leave composition (PadSheetScreen's own fix).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (engine == Engine.APSIS) touched = false
                if (engine == Engine.PRISM) { touched = false; prismMotionStudio.cancel() }
                voicePlayer?.release()
                voicePlayer = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun audition(target: Snip, allowLoop: Boolean) {
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
        v.start(0, loop = if (allowLoop && ((engine == Engine.APSIS && Apsis.isLoop(macros["HOLD"] ?: 0f)) ||
            (engine == Engine.TIDAL && Tidal.isHeld(macros["HOLD"] ?: 0f))))
            0 until heard.frameCount else null)
    }

    val filamentPreviews = rememberFilamentPreviews()

    // The debounced re-render + retrigger loop: LaunchedEffect's own key
    // change cancels whatever render was in flight and restarts the delay,
    // which is exactly the trailing-edge debounce `touched()` does by hand
    // with clearTimeout/setTimeout in the prototype. Keyed on `engine` too
    // now — switching engines must cancel an in-flight render exactly like
    // switching voices always has.
    LaunchedEffect(engine, voice, macros, loadedPatch, activeRecipe, embouchureRestore.pending.containsKey(engine to voice), prismMotionStudio.ownsPreview) {
        if (engine == Engine.PRISM && prismMotionStudio.ownsPreview) {
            rendering = false
            return@LaunchedEffect
        }
        if (embouchureRestore.waitsForRecipe(engine, voice)) {
            voicePlayer?.stop()
            snip = null
            rendering = false
            return@LaunchedEffect
        }
        if (flockHeldPreview != null) {
            // Its cancellable root capture is rendered once by rememberFlockHeldPreview.
            // A held gesture starts/stops that cached sample through InstrumentPlayer.
            voicePlayer?.stop()
            snip = null
            rendering = false
            return@LaunchedEffect
        }
        val previewJob = coroutineContext[Job]
        renderSynthPreview(
            debounceMillis = MACRO_DEBOUNCE_MS,
            shimmerDelayMillis = RENDER_SHIMMER_DELAY_MS,
            onRendering = { rendering = it },
            render = {
                val patch = if (loadedPatch != null || engine == Engine.OPENSKY || engine == Engine.FILAMENT || engine == Engine.TIDAL || engine == Engine.TREMOR || engine == Engine.CHAMBER || engine == Engine.NACRE)
                    buildCurrentPatch(engine.patchDisplayName(voice)) else null
                val dry = when {
                    patch is ChamberPatch -> seed.chamberRenderer.render(patch) {
                        previewJob?.isActive == false || Thread.currentThread().isInterrupted
                    }
                    patch is FilamentPatch -> {
                        val cancelled = { previewJob?.isActive == false || Thread.currentThread().isInterrupted }
                        filamentPreviews.getOrRender(patch, cancelled) {
                            Filament.render(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config,
                                patch.model, cancelled)
                        }
                    }
                    patch is OpenSkyPatch -> patch.render {
                        previewJob?.isActive == false || Thread.currentThread().isInterrupted
                    }
                    patch is TidalPatch -> Tidal.render(
                        patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed, patch.model,
                        cancelled = { previewJob?.isActive == false || Thread.currentThread().isInterrupted },
                    )
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
                if (activeRecipe != null) activeRecipe.process(dry)
                else Presets.landingFor(engine.name, voice.name, macros)?.process(dry) ?: dry
            },
            publish = { rendered ->
                if (engine != Engine.PRISM || !prismMotionStudio.ownsPreview) {
                    snip = rendered
                    if (touched) audition(rendered, allowLoop = true)
                }
            },
            onFailure = { failure ->
                Log.e("SynthScreen", "render: failed", failure)
                onToast(Copy.RENDER_FAILED)
            },
        )
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
        if (editable is PrismPatch) prismMotionStudio.selected(editable)
        loadedPatchesByVoice[key] = editable
        loadedRecipesByVoice[key] = recipe
        recalledRecipesByVoice.remove(key)
        SynthWork.source(shelfRoot, sourceEntry.dir, slot)?.let { reopenedPadsByVoice[key] = it }
            ?: reopenedPadsByVoice.remove(key)
        macrosByVoice[key] = editableDefaults(sourceEngine.defaults(sourceVoice), editable) + editableMacros(editable)
        currentPresetByVoice.remove(key)
        embouchureRestore.pending.remove(key)
        touched = true
        engine = sourceEngine
        voice = sourceVoice
        reopeningPad = false
    }

    fun sendToSlot(slot: Int) {
        val e = entry ?: return
        if (sendBusy) return
        if (embouchureRestore.waitsForRecipe(engine, voice)) return
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
                val cls = if (patch is OhmPatch) Ohm.drumClassFor(patch) else engine.drumClass(voice, macros)
                // These engines author their pitch. Detection can invent a cent
                // offset and retune an already equal-tempered note.
                val authoredMidi = when (patch) {
                    is TrefoilPatch -> patch.midi
                    is ApsisPatch -> patch.midi
                    is OrigamiPatch -> patch.midi ?: Origami.midiFor(patch.voice, patch.resolvedMacros().getValue("TUNE"))
                    is FilamentPatch -> patch.midi
                    is PluckPatch -> if (patch.voice == PluckVoice.UPRIGHT)
                        28 + ((Pluck.defaults(patch.voice) + patch.macros).getValue("TUNE") * Pluck.TUNE_SEMITONES).roundToInt()
                        else null
                    is TidalPatch -> patch.midi
                    is OhmPatch -> patch.midi
                    is ChamberPatch -> patch.midi
                    else -> null
                }
                val authoredSource = authoredMidi?.let {
                    mapOf("from" to "SYNTH", "rootMidi" to it.toString(), "rootNote" to Scales.nameOf(it), "rootOrigin" to "authored")
                }
                val tonalRoot = authoredMidi?.takeIf { cls == DrumClass.TONAL }
                val (existed, updatedKit) = offMain {
                    KitWrites.mutex.withLock {
                        val model = KitBuilderModel.open(e.dir)
                        // A held Murmur source is seamless, but a one-shot pad stops
                        // at its final frame. Save the delivery fade with that pad;
                        // gated pads and SURFACE keep the unfaded loop source.
                        val terminalFade = if (!toSurface && (model.pad(slot)?.oneShot ?: true)) {
                            when {
                                patch is FlockPatch && patch.playback == FlockPlayback.HELD -> 20f
                                patch is OhmPatch && Ohm.isHeld(patch) -> 20f
                                patch is MurmurPatch && Murmur.isLoop(patch.macros["HOLD"] ?: 0f) -> 8f
                                else -> null
                            }
                        } else null
                        // A reopened pad keeps its whole processing recipe (alias flag,
                        // treatment, board); only the source and the delivery fade
                        // are re-stated from the sliders and the target pad.
                        val padRecipe = activeRecipe?.copy(patch = patch, terminalFadeMs = terminalFade)
                            ?: PadRecipe(patch = patch, fx = fx, terminalFadeMs = terminalFade)
                        val recipe = padRecipe.toJsonValue()
                        val rendered = if (patch is ChamberPatch) padRecipe.process(seed.chamberRenderer.render(patch))
                            else padRecipe.render()
                        // SPARK spans low drum and brighter percussion roles.
                        // Its picker color is nominal; the saved pad follows
                        // the actual delivered audio, like its expression kit.
                        val deliveredClass = if (patch is ThumpPatch && patch.voice == ThumpVoice.SPARK) {
                            Classifier.classify(rendered).drumClass
                        } else cls
                        val alreadyThere = model.pad(slot) != null
                        if (alreadyThere) {
                            val previousFilament = model.pad(slot)?.let { Breed.recipeOf(it)?.patch as? FilamentPatch }
                            val filamentRootChanged = patch is FilamentPatch && previousFilament?.midi != patch.midi
                            // REPLACE: `replaceAudio` only rewrites the sample +
                            // recipe of a pad that already exists — it leaves
                            // displayName/drumClass/colorHex/muteGroup exactly as
                            // they were, so without this follow-up `update` the
                            // pad's identity would still say (and choke-group
                            // with) whatever it was before, while sounding like
                            // the new voice.
                            model.replaceAudio(slot, recipe) { _ -> rendered }
                            // FILAMENT and CHAMBER keep deliberate pad tuning
                            // when reopened. ROOT changes the native pitch
                            // and source tags; the player's tune offsets stay put.
                            // ORIGAMI retains its existing finite-note behavior.
                            val knownTune = when {
                                patch is ApsisPatch && cls == DrumClass.LOOP -> 0 to 0
                                (patch is FilamentPatch || patch is ChamberPatch) && reopenedHere -> null
                                patch is FilamentPatch && cls == DrumClass.LOOP -> 0 to 0
                                patch is TidalPatch && !reopenedHere -> 0 to 0
                                else -> tonalRoot?.let { model.tuningForKnownRoot(it) }
                            }
                            if (!reopenedHere || authoredMidi != null) {
                                model.update(slot) { p ->
                                    val identified = if (reopenedHere) p else p.copy(
                                        displayName = name,
                                        drumClass = deliveredClass,
                                        colorHex = AutoPlace.colorFor(deliveredClass),
                                        muteGroup = AutoPlace.muteGroupFor(deliveredClass),
                                        loop = false,
                                    )
                                    identified.copy(
                                        tuneCoarse = knownTune?.first ?: identified.tuneCoarse,
                                        tuneFine = knownTune?.second ?: identified.tuneFine,
                                        oneShot = if (patch is OrigamiPatch || patch is TidalPatch) true else identified.oneShot,
                                        source = when {
                                            patch is FilamentPatch && reopenedHere ->
                                                if (filamentRootChanged) identified.source + authoredSource.orEmpty() else identified.source
                                            patch is ChamberPatch && reopenedHere -> identified.source + authoredSource.orEmpty()
                                            else -> authoredSource ?: identified.source
                                        },
                                    )
                                }
                            }
                        } else {
                            // ADD: `assign` places new audio on an empty slot —
                            // it derives displayName/colorHex/muteGroup itself,
                            // but has no notion of a synth recipe, so the recipe
                            // rides a follow-up `update` (its guards permit a
                            // recipe-only edit; slot/sampleFile stay put).
                            model.assign(
                                slot, rendered, deliveredClass, name,
                                source = authoredSource ?: emptyMap(),
                                knownRootMidi = tonalRoot,
                            )
                            model.update(slot) { p -> p.copy(recipe = recipe) }
                        }
                        if (patch is ApsisPatch) {
                            model.update(slot) { ApsisPad.configure(it, patch) }
                        }
                        if (patch is TidalPatch) model.update(slot) { TidalPad.configure(it, patch) }
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
                    offMain { SurfaceStore.choosePad(e.dir, slot) }
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
    // (GLINT). Zones render off the main thread. OPENSKY's larger wet
    // state, BELLOWS's velocity layers and CONTOUR's seventeen zones render
    // serially; the other makers run in parallel. A progress bar stays live
    // (the author's call: a slow phone shows work, it does not drop zones);
    // the package is written once every zone
    // has landed, so a CANCEL mid-render leaves nothing on the shelf.
    var makingInstrument by remember { mutableStateOf(false) }
    val heldSheet = remember { HeldSheetState() }
    // The name MAKE will land under, read off the shelf when the sheet opens
    // so the sheet can say it; MAKE asks the shelf again at write time.
    val supportsHeldInstrument = (engine == Engine.TIDAL && !embouchureRestore.pending.containsKey(engine to voice)) || engine == Engine.CREASE || engine == Engine.ORPHICA || engine == Engine.RESIN || engine == Engine.SIREN || engine == Engine.GLINT || engine == Engine.REGISTERS || engine == Engine.BELLOWS || engine == Engine.CONTOUR ||
        (engine == Engine.OPENSKY && OpenSkyPadMaker.supports(voice as OpenSkyVoice)) ||
        (engine == Engine.ORIGAMI && OrigamiPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as OrigamiPatch)) ||
        (engine == Engine.FLOCK && FlockPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as FlockPatch)) ||
        (engine == Engine.OHM && OhmPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as OhmPatch)) ||
        (engine == Engine.FILAMENT && FilamentPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as FilamentPatch))
    val heldInstrumentReady = when (engine) {
        Engine.CREASE -> CreasePadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as CreasePatch)
        Engine.OPENSKY -> OpenSkyPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch)
        Engine.BELLOWS -> BellowsPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as BellowsPatch)
        Engine.FILAMENT -> FilamentPadMaker.supports(buildCurrentPatch(engine.patchDisplayName(voice)) as FilamentPatch)
        else -> true
    }
    val holdBase = currentPresetByVoice[engine to voice] ?: "${engine.name} ${voice.name}"
    val instrumentsDir = File(shelfRoot, KitShelf.INSTRUMENTS_DIR)
    LaunchedEffect(makingInstrument, holdBase) {
        if (!makingInstrument) return@LaunchedEffect
        heldSheet.holdName = try {
            offMain { OneNote.freshName(instrumentsDir, holdBase) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SynthScreen", "heldSheet.holdName: shelf unreadable", e)
            holdBase
        }
    }

    // MAKE INSTRUMENT's engines behind one shape, so the render/export
    // orchestration below (identical for all) is written once: RESIN's
    // spec carries an ATTACK; SIREN's LOOP render has none to carry
    // (Keys.sirenPad's own KDoc) and neither does GLINT's (its onset and
    // breath come from the patch). OPENSKY HALO likewise keeps its authored
    // onset; REGISTERS retains its speaking attack before the verified
    // periodic sustain. CONTOUR starts directly from its settled tone.
    // These specs expose only a release control.
    fun heldSpec(): HeldSpec? = when (engine) {
        Engine.RESIN -> HeldSpec.Resin(ResinPadMaker.spec(voice as ResinVoice, macros, heldSheet.holdAttack, heldSheet.holdRelease))
        Engine.SIREN -> HeldSpec.Siren(SirenPadMaker.spec(voice as SirenVoice, macros, heldSheet.holdRelease))
        Engine.GLINT -> HeldSpec.Glint(GlintPadMaker.spec(voice as GlintVoice, macros, heldSheet.holdRelease))
        Engine.ORPHICA -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as OrphicaPatch
            HeldSpec.Orphica(OrphicaPadMaker.spec(patch.config, patch.macros, heldSheet.holdRelease))
        }
        Engine.OPENSKY -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as OpenSkyPatch
            if (OpenSkyPadMaker.supports(patch)) HeldSpec.OpenSky(OpenSkyPadMaker.spec(patch, heldSheet.holdRelease)) else null
        }
        Engine.CREASE -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as CreasePatch
            if (CreasePadMaker.supports(patch))
                HeldSpec.Crease(CreasePadMaker.spec(patch, heldSheet.holdRelease, heldSheet.holdTouchLayers)) else null
        }
        Engine.REGISTERS -> HeldSpec.Registers(RegistersPadMaker.spec(
            buildCurrentPatch(engine.patchDisplayName(voice)) as RegistersPatch, heldSheet.holdRelease,
        ))
        Engine.BELLOWS -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as BellowsPatch
            if (BellowsPadMaker.supports(patch)) HeldSpec.Bellows(BellowsPadMaker.spec(patch, heldSheet.holdRelease)) else null
        }
        Engine.FLOCK -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as FlockPatch
            if (FlockPadMaker.supports(patch))
                HeldSpec.Flock(FlockPadMaker.Spec(patch, touchLayers = heldSheet.holdFlockTouchLayers)) else null
        }
        Engine.OHM -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as OhmPatch
            if (OhmPadMaker.supports(patch)) HeldSpec.Ohm(OhmPadMaker.Spec(patch)) else null
        }
        Engine.TIDAL -> HeldSpec.Tidal(TidalPadMaker.spec(
            buildCurrentPatch(engine.patchDisplayName(voice)) as TidalPatch, heldSheet.holdRelease,
        ))
        Engine.CONTOUR -> HeldSpec.Contour(ContourPadMaker.spec(
            buildCurrentPatch(engine.patchDisplayName(voice)) as ContourPatch, heldSheet.holdRelease,
        ))
        Engine.ORIGAMI -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as OrigamiPatch
            if (OrigamiPadMaker.supports(patch)) HeldSpec.Origami(OrigamiPadMaker.spec(patch, heldSheet.holdRelease)) else null
        }
        Engine.FILAMENT -> {
            val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as FilamentPatch
            if (FilamentPadMaker.supports(patch)) HeldSpec.Filament(FilamentPadMaker.spec(patch, heldSheet.holdRelease)) else null
        }
        else -> null
    }

    fun previewHeld() {
        if (heldSheet.holdDone >= 0 || heldSheet.holdPreviewing) return
        val spec = heldSpec() ?: return
        flockHeldPreview?.silence()
        heldSheet.holdPreviewing = true
        heldSheet.holdJob = appScope.launch {
            try {
                // CANCEL cancels this job; the render asks and stops.
                val heard = if (spec is HeldSpec.Ohm || spec is HeldSpec.Tidal || spec is HeldSpec.Flock) {
                    val renderJob = coroutineContext[Job]
                    offMainInterruptible(Dispatchers.Default) {
                        spec.preview { renderJob?.isActive == false || Thread.currentThread().isInterrupted }
                    }
                } else offMain(Dispatchers.Default) { spec.preview { !isActive } }
                audition(heard, allowLoop = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "previewHeld: failed", e)
                onToast(Copy.RENDER_FAILED)
            } finally {
                heldSheet.holdPreviewing = false
                heldSheet.holdJob = null
            }
        }
    }

    fun makeHeld() {
        if (heldSheet.holdDone >= 0 || heldSheet.holdPreviewing) return
        // Captured now: the sheet's own sound, before anything can move it.
        val spec = heldSpec() ?: return
        flockHeldPreview?.silence()
        val base = holdBase
        val midis = spec.zoneMidis
        heldSheet.holdTotal = midis.size * spec.progressPerZone
        heldSheet.holdDone = 0
        heldSheet.holdJob = appScope.launch {
            try {
                // coroutineScope, not bare asyncs on appScope: a zone that
                // throws must surface here as an exception this catch sees,
                // not fail the app's own scope.
                val notes = coroutineScope {
                    if (spec is HeldSpec.OpenSky || spec is HeldSpec.Bellows || spec is HeldSpec.Crease || spec is HeldSpec.Contour || spec is HeldSpec.Origami || spec is HeldSpec.Flock || spec is HeldSpec.Ohm || spec is HeldSpec.Filament || spec is HeldSpec.Tidal) {
                        // OPENSKY has larger wet state, BELLOWS has three
                        // velocity layers, CREASE evolves its attack and
                        // held control history, CONTOUR spans seventeen zones,
                        // and ORIGAMI keeps its oversampled attack and sustain
                        // work to one zone at a time.
                        // FILAMENT similarly bounds its network history to one root.
                        // Render serially so phones never hold nine
                        // independent oversampled states at once. CANCEL stays live;
                        // the counter advances after each note's samples finish.
                        midis.map { midi ->
                            val note = if (spec is HeldSpec.Ohm || spec is HeldSpec.Tidal || spec is HeldSpec.Flock) {
                                val renderJob = coroutineContext[Job]
                                offMainInterruptible(Dispatchers.Default) {
                                    spec.renderZone(midi) { renderJob?.isActive == false || Thread.currentThread().isInterrupted }
                                }
                            } else offMain(Dispatchers.Default) { spec.renderZone(midi) { !isActive } }
                            withContext(Dispatchers.Main) { heldSheet.holdDone += spec.progressPerZone }
                            note
                        }
                    } else {
                        midis.map { midi ->
                            async(Dispatchers.Default) {
                                // CANCEL cancels the scope; each zone asks and stops,
                                // rather than all nine running on for seconds.
                                val note = spec.renderZone(midi) { !isActive }
                                withContext(Dispatchers.Main) { heldSheet.holdDone += spec.progressPerZone }
                                note
                            }
                        }.awaitAll()
                    }
                }
                // Past here the write lands even under CANCEL: a package half
                // written is worse than a whole one the player can bin.
                val made = offMain(NonCancellable + Dispatchers.IO) {
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
                heldSheet.holdDone = -1
                heldSheet.holdJob = null
            }
        }
    }

    fun closeHeld() {
        // Every zone rendered means the write is under way; let it land.
        if (heldSheet.holdDone >= 0 && heldSheet.holdDone >= heldSheet.holdTotal) return
        flockHeldPreview?.silence()
        val job = heldSheet.holdJob
        if (job == null) {
            heldSheet.holdDone = -1
            heldSheet.holdPreviewing = false
        } else {
            job.invokeOnCompletion {
                appScope.launch(Dispatchers.Main.immediate) {
                    // Cancellation before the body starts skips its finally.
                    // A later job must never be cleared by this one's handler.
                    if (heldSheet.holdJob === job) {
                        heldSheet.holdJob = null
                        heldSheet.holdDone = -1
                        heldSheet.holdPreviewing = false
                    }
                }
            }
            job.cancel()
        }
        // Hide now, but retain the busy state until the worker's finally
        // drains. Reopening must not launch another render that the old
        // worker's cleanup could then overwrite.
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
    val droneSheet = remember { DroneSheetState() }
    // The grid the drone will land on, read when the sheet opens: its tempo
    // and bars decide the span the readout names and PREVIEW renders.

    // RESIN and SIREN behind one shape, the same division [HeldSpec] draws:
    // RESIN's own MOTION and BREATHS are knobs invented for the drone, since
    // RESIN's held macros carry no swing of their own; SIREN already *is* a
    // movement (RATE and DEPTH, dialed on the panel above), so its own
    // DroneSpec has no extra knob and [hasMotion] is false.
    fun droneSpec(): DroneSpec? = when (engine) {
        Engine.RESIN -> DroneSpec.Resin(voice as ResinVoice, macros, droneSheet.droneMotion, droneSheet.droneRate)
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
    LaunchedEffect(droneSheet.droneOpen, droneSheet.droneRoot, droneSheet.droneSession, engine, voice, macros) {
        // Reset before recomputing, not after: root or macros just moved,
        // and the stale string is for a spec PREVIEW/SEND no longer read
        // off the moment either changed — showing it a beat longer would
        // name a note that is no longer the one either button plays.
        droneSheet.droneLabel = "…"
        val root = droneSheet.droneRoot
        val session = droneSheet.droneSession
        val spec = droneSpec()
        if (droneSheet.droneOpen && root != null && session != null && spec != null) {
            droneSheet.droneLabel = offMain(Dispatchers.Default) {
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

    LaunchedEffect(droneSheet.droneOpen, voice) {
        if (!droneSheet.droneOpen) return@LaunchedEffect
        val spec = droneSpec() ?: return@LaunchedEffect
        if (droneSheet.droneRoot?.let { it in spec.roots } != true) droneSheet.droneRoot = spec.defaultRoot(kit?.key)
        droneSheet.droneSession = offMain {
            val rate = deviceSampleRate(context)
            val dir = LoopWrites.dir(context)
            runCatching { SessionStore.load(dir) }.getOrNull()?.copy(sampleRate = rate)
                ?: SessionBuilder.empty(rate)
        }
    }

    fun previewDrone() {
        val root = droneSheet.droneRoot ?: return
        val session = droneSheet.droneSession ?: return
        val spec = droneSpec() ?: return
        if (droneSheet.dronePreviewing) return
        droneSheet.dronePreviewing = true
        droneSheet.droneJob = appScope.launch {
            try {
                val heard = offMain(Dispatchers.Default) {
                    // CANCEL (or closing the sheet) cancels this job; the
                    // render asks and stops rather than running on for seconds.
                    val once = spec.render(root, session) { !isActive }
                    Snip(FloatArray(once.size * 2) { once[it % once.size] }, 1, session.sampleRate)
                }
                audition(heard, allowLoop = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SynthScreen", "previewDrone: failed", e)
                onToast(Copy.RENDER_FAILED)
            } finally {
                droneSheet.dronePreviewing = false
                droneSheet.droneJob = null
            }
        }
    }

    fun sendDrone() {
        val root = droneSheet.droneRoot ?: return
        val spec = droneSpec() ?: return
        val name = "${currentPresetByVoice[engine to voice] ?: "${engine.name} ${voice.name}"} DRONE"
        onDroneToLoop(name, spec.toJson(), root)
        droneSheet.droneJob?.cancel()
        droneSheet.droneJob = null
        droneSheet.dronePreviewing = false
        droneSheet.droneOpen = false
    }

    fun closeDrone() {
        droneSheet.droneJob?.cancel()
        droneSheet.droneJob = null
        droneSheet.dronePreviewing = false
        droneSheet.droneOpen = false
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
        if (embouchureRestore.waitsForRecipe(engine, voice)) return
        spreadOpening = true
        val patch = buildCurrentPatch(engine.patchDisplayName(voice))
        val cls = if (patch is OhmPatch) Ohm.drumClassFor(patch) else engine.drumClass(voice, macros)
        scope.launch {
            try {
                val spreadJob = coroutineContext[Job]
                val src = offMainInterruptible(Dispatchers.Default) {
                    val cancelled = { spreadJob?.isActive == false || Thread.currentThread().isInterrupted }
                    val rendered = when (patch) {
                        is ChamberPatch -> seed.chamberRenderer.render(patch, cancelled)
                        is TidalPatch -> Tidal.render(patch.voice, patch.macros, patch.midi, patch.velocity,
                            patch.seed, patch.model, cancelled)
                        is OpenSkyPatch -> patch.render(cancelled)
                        else -> patch.render()
                    }
                    // Authored roots survive chords, octave layers and upper
                    // shimmer that can outweigh the fundamental in detection.
                    val midi = when (patch) {
                        is OrphicaPatch -> Orphica.configWithMacros(patch.config, patch.macros).midi.toFloat()
                        else -> Spread.detect(rendered, patch)
                    }
                    SpreadSource(rendered, midi, Spread.defaultRoot(midi))
                }
                coroutineContext.ensureActive()
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
                val (plan, updatedKit) = offMain {
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

    // The engine list's sheet and the engines picked from it lately, in one holder: this
    // composable is at the limit of what the toolchain verifies (see `embouchureRestore`).
    val enginePicker = rememberEnginePicker(ENGINE_NAME_SET)

    // Arriving on a different engine, whichever way: ◄, ► or a row of the engine list other than
    // the current engine's (that row only closes the list, see the sheet's onPick). It is exactly
    // what the tap-to-cycle header did, so the new engine is touched (and so auditioned, as
    // every touch is) and starts on its first voice.
    fun arrive(next: Engine) {
        touched = true
        engine = next
        voice = next.voices().first()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ENGINE header: ◄ NAME ▾ ►. The owner asked for "a better way to search, scroll
            // through, select engines. Click to cycle takes too much": 63 engines, one way
            // round and no way back from an overshoot. The name opens the engine list (search,
            // sound-type chips, recents); ◄ and ► are the old tap, one engine either side and
            // wrapping, kept because stepping is still the quickest way to audition neighbours.
            // All three arrive the same way, through `arrive`. See [EngineHeader].
            EngineHeader(
                engine = engine,
                voice = voice,
                scheme = scheme,
                onOpen = { enginePicker.open = true },
                onPrevious = { arrive(engine.previous()) },
                onNext = { arrive(engine.next()) },
            )

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopeLcd(if (flockHeldPreview != null) flockHeldPreview.snip else snip, flockHeldPreview?.rendering ?: rendering,
                    scheme, Modifier.fillMaxWidth().height(104.dp))

                if (engine == Engine.SHOAL) TapeText("MOTION", TapeType.pixelSmall, scheme.ink2.tape)
                VoicePicker(engine, voice, scheme, onSelect = {
                    touched = true
                    if (engine == Engine.PRISM) { prismMotionStudio.cancel(); voicePlayer?.stop() }
                    voice = it
                })

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

                if (engine == Engine.PLUCK && voice == PluckVoice.UPRIGHT) {
                    UprightVelocityControl(
                        patch = buildCurrentPatch(engine.patchDisplayName(voice)) as PluckPatch,
                        fillColor = classColor, scheme = scheme, onValueChange = ::updateUprightVelocity,
                    )
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

                if (engine == Engine.NACRE) {
                    NacreNoteControls(
                        patch = buildCurrentPatch(loadedPatch?.name ?: engine.patchDisplayName(voice)) as NacrePatch,
                        fillColor = classColor,
                        scheme = scheme,
                        onRootChange = { root ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(loadedPatchesByVoice[engine to voice]?.name ?: engine.patchDisplayName(voice)) as NacrePatch).copy(midi = root)
                        },
                        onVelocityChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(loadedPatchesByVoice[engine to voice]?.name ?: engine.patchDisplayName(voice)) as NacrePatch).copy(velocity = value)
                        },
                    )
                }

                if (engine == Engine.CHAMBER) {
                    ChamberNoteControls(
                        patch = buildCurrentPatch(loadedPatch?.name ?: engine.patchDisplayName(voice)) as ChamberPatch,
                        fillColor = classColor,
                        scheme = scheme,
                    ) { edited ->
                        touched = true
                        currentPresetByVoice.remove(engine to voice)
                        loadedPatchesByVoice[engine to voice] = edited
                    }
                }

                // CAUSTIC is a finite instrument. Keep its explicit root and
                // strike velocity in the loaded recipe, beside its macros.
                if (engine == Engine.CAUSTIC) {
                    CausticNoteControls(
                        patch = buildCurrentPatch(engine.patchDisplayName(voice)) as CausticPatch,
                        fillColor = classColor,
                        scheme = scheme,
                        onRootChange = { root ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as CausticPatch).copy(midi = root)
                        },
                        onVelocityChange = { value ->
                            touched = true
                            currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] =
                                (buildCurrentPatch(engine.patchDisplayName(voice)) as CausticPatch).copy(velocity = value)
                        },
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

                if (engine == Engine.APSIS) {
                    ApsisPlayingControls(
                        buildCurrentPatch(engine.patchDisplayName(voice)) as ApsisPatch,
                        classColor, scheme,
                    ) { patch ->
                        touched = true
                        currentPresetByVoice.remove(engine to voice)
                        loadedPatchesByVoice[engine to voice] = patch
                    }
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

                // FILAMENT's note and playing energy are separate from its six controls.
                // Copy the complete recipe so edits keep its strand roster and model.
                if (engine == Engine.FILAMENT) {
                    val filamentPatch = buildCurrentPatch(engine.patchDisplayName(voice)) as FilamentPatch
                    MacroSlider(
                        label = "ROOT",
                        value = (filamentPatch.midi - Filament.MIN_MIDI).toFloat() / (Filament.MAX_MIDI - Filament.MIN_MIDI),
                        fillColor = classColor,
                        scheme = scheme,
                        readout = Scales.nameOf(filamentPatch.midi),
                        onValueChange = { value ->
                            val midi = (Filament.MIN_MIDI + value * (Filament.MAX_MIDI - Filament.MIN_MIDI)).roundToInt()
                                .coerceIn(Filament.MIN_MIDI, Filament.MAX_MIDI)
                            updateFilamentPatch { it.copy(midi = midi) }
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY",
                        value = filamentPatch.velocity,
                        fillColor = classColor,
                        scheme = scheme,
                        onValueChange = { value ->
                            updateFilamentPatch { it.copy(velocity = value.coerceIn(0f, 1f)) }
                        },
                    )
                }

                // The extracted source panel returns complete recipes, including captured
                // excitation and the opt-in held model, without expanding this composable's locals.
                if (engine == Engine.OHM) {
                    // Keep the saved name. buildCurrentPatch's name argument replaces it,
                    // and a display name would discard a recipe the player already named.
                    OhmControls(
                        patch = buildCurrentPatch((loadedPatch as? OhmPatch)?.name ?: engine.patchDisplayName(voice)) as OhmPatch,
                        entry = entry,
                        fillColor = classColor,
                        onPatchChanged = { next -> updateOhmPerformance { next } },
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

                // Copy the full FLOCK recipe so finite/held edits retain its
                // exact note, velocity, Long seed and original renderer model.
                if (engine == Engine.FLOCK) {
                    FlockPerformanceControls(
                        patch = buildCurrentPatch(engine.patchDisplayName(voice)) as FlockPatch,
                        fill = classColor,
                        scheme = scheme,
                        onEdit = { edited -> updateFlockPatch { edited } },
                    )
                }

                if (engine == Engine.TIDAL) {
                    if (embouchureRestore.pending.containsKey(engine to voice)) {
                        TapeText(
                            if (embouchureRestore.pending[engine to voice] == false)
                                "SAVED TIDAL COULD NOT OPEN. CHOOSE A PRESET."
                            else "RESTORING SAVED TIDAL…",
                            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
                        )
                    } else {
                        TidalPerformanceControls(
                            patch = buildCurrentPatch(engine.patchDisplayName(voice)) as TidalPatch,
                            fill = classColor,
                            scheme = scheme,
                            onEdit = ::updateTidalPatch,
                        )
                    }
                }

                if (engine == Engine.TREMOR && embouchureRestore.waitsForRecipe(engine, voice)) {
                    TapeText(
                        if (embouchureRestore.pending[engine to voice] == false)
                            "SAVED TREMOR COULD NOT OPEN. CHOOSE A PRESET."
                        else "RESTORING SAVED TREMOR…",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
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

                if (loadedPatch is EmbouchurePatch && loadedPatch.model == Embouchure.BRASS_GROW_MODEL) {
                    TapeText(
                        "GROW: ROUGH → CENTERED → EXPRESSIVE. HELD SAMPLE EXCLUDES ATTACK; PADS PLAY ONE LOOP LENGTH.",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
                    )
                }

                if (engine == Engine.SCANNED_CHAIN) {
                    key(voice) {
                        ScannedShapeControls(
                            patch = buildCurrentPatch(engine.patchDisplayName(voice)) as ScannedChainPatch,
                            entry = entry,
                            onPatchChanged = { edited ->
                                val target = Engine.SCANNED_CHAIN to edited.voice
                                touched = true
                                currentPresetByVoice.remove(target)
                                loadedPatchesByVoice[target] = edited
                            },
                            onRecallPatch = { recipe ->
                                val patch = recipe.patch as? ScannedChainPatch ?: return@ScannedShapeControls
                                val target = Engine.SCANNED_CHAIN to patch.voice
                                touched = true
                                engine = Engine.SCANNED_CHAIN
                                voice = patch.voice
                                loadedPatchesByVoice[target] = patch
                                // A recalled pad's recipe replaces any reopened one, and
                                // has no shelf source of its own to write back to.
                                recalledRecipesByVoice[target] = recipe
                                loadedRecipesByVoice.remove(target)
                                reopenedPadsByVoice.remove(target)
                                macrosByVoice[target] = ScannedChain.defaults(patch.voice) + patch.macros
                                currentPresetByVoice.remove(target)
                            },
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
                        else "SOLO PERFORMER · DRAMA, SIDES AND MOTION ARE ENSEMBLE CONTROLS",
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
                    // The beat grid spans at most two seconds; answers may extend past it.
                    // Changing tempo retains the selected number of beats.
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

                if (engine == Engine.TREFOIL) {
                    val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as TrefoilPatch
                    MacroSlider(
                        label = "ROOT", value = (patch.midi - Trefoil.MIN_MIDI).toFloat() / (Trefoil.MAX_MIDI - Trefoil.MIN_MIDI),
                        fillColor = classColor, scheme = scheme, readout = Scales.nameOf(patch.midi),
                        onValueChange = { value ->
                            touched = true; currentPresetByVoice.remove(engine to voice)
                            val midi = (Trefoil.MIN_MIDI + value * (Trefoil.MAX_MIDI - Trefoil.MIN_MIDI)).roundToInt().coerceIn(Trefoil.MIN_MIDI, Trefoil.MAX_MIDI)
                            loadedPatchesByVoice[engine to voice] = (buildCurrentPatch(engine.patchDisplayName(voice)) as TrefoilPatch).copy(midi = midi)
                        },
                    )
                    MacroSlider(
                        label = "VELOCITY", value = patch.velocity, fillColor = classColor, scheme = scheme,
                        onValueChange = { value ->
                            touched = true; currentPresetByVoice.remove(engine to voice)
                            loadedPatchesByVoice[engine to voice] = (buildCurrentPatch(engine.patchDisplayName(voice)) as TrefoilPatch).copy(velocity = value.coerceIn(0f, 1f))
                        },
                    )
                    MacroSlider(
                        label = "CONTACT", value = (patch.activeSeconds - .05f) / (patch.seconds - .15f),
                        fillColor = classColor, scheme = scheme, readout = "%.2fs".format(Locale.ROOT, patch.activeSeconds),
                        onValueChange = { value ->
                            touched = true; currentPresetByVoice.remove(engine to voice)
                            val current = buildCurrentPatch(engine.patchDisplayName(voice)) as TrefoilPatch
                            loadedPatchesByVoice[engine to voice] = current.copy(activeSeconds = (.05f + value * (current.seconds - .15f)).coerceIn(.05f, current.seconds - .1f))
                        },
                    )
                }

                if (engine == Engine.TRIO) {
                    TapeText("BEAT GRID UP TO 2s · ANSWERS AND RING CONTINUE · 3.5s RECORDING",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
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

                if (engine == Engine.PRISM) {
                    PrismMotionPanel(
                        patch = buildCurrentPatch(loadedPatch?.name ?: engine.patchDisplayName(voice)) as PrismPatch,
                        studio = prismMotionStudio, scheme = scheme, color = classColor,
                        currentPatch = {
                            if (engine == Engine.PRISM) buildCurrentPatch(loadedPatchesByVoice[engine to voice]?.name
                                ?: engine.patchDisplayName(voice)) as PrismPatch else null
                        },
                        onEdit = ::editPrismMotion,
                        onPreview = { target -> snip = target; audition(target, allowLoop = false) },
                        onStopPreview = { pause -> if (pause) touched = false; voicePlayer?.stop() },
                    )
                }
                if (prismFiniteMotion(loadedPatch as? PrismPatch)) {
                    TapeText("FINITE NOTE · HOLD SETS LENGTH", TapeType.pixelSmall, scheme.ink2.tape)
                }

                val origamiPatch = if (engine == Engine.ORIGAMI)
                    buildCurrentPatch(engine.patchDisplayName(voice)) as OrigamiPatch else null
                if (origamiPatch != null) {
                    if (origamiPatch.model == Origami.MODEL_VERSION) {
                        TapeText("GESTURE", TapeType.pixelSmall, scheme.ink2.tape)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (gesture in OrigamiGesture.entries) {
                                ActionButton(
                                    gesture.name,
                                    scheme,
                                    enabled = true,
                                    lit = origamiPatch.gesture == gesture,
                                    modifier = Modifier.weight(1f).semantics { selected = origamiPatch.gesture == gesture },
                                    accessibilityLabel = "ORIGAMI ${gesture.name}",
                                ) {
                                    updateOrigamiGesture(gesture)
                                }
                            }
                        }
                        TapeText(
                            when (origamiPatch.gesture) {
                                OrigamiGesture.STEADY -> "MOTION MOVES THE FOLD AXIS."
                                OrigamiGesture.BLOOM -> "START OPEN, THEN FOLD."
                                OrigamiGesture.UNFOLD -> "START FOLDED, THEN OPEN."
                            },
                            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
                        )
                        if (origamiPatch.gesture != OrigamiGesture.STEADY) {
                            TapeText(
                                "MOTION SETS GESTURE TIME · FAST → SLOW · PLAYS ONCE",
                                TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
                            )
                        }
                    } else {
                        TapeText(
                            "THIS SAVED RECIPE KEEPS ITS ORIGINAL SOUND. LOAD A FACTORY PRESET FOR GESTURES.",
                            TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
                        )
                    }
                }

                val creaseModel = (loadedPatch as? CreasePatch)?.model ?: Crease.MODEL_VERSION
                // BRASS GROW (model 3) swaps in its own four controls; other EMBOUCHURE models keep the engine's six.
                val macroSpecs = remember(engine, voice, creaseModel, (loadedPatch as? EmbouchurePatch)?.model, (loadedPatch as? CausticPatch)?.model, embouchureRestore.pending.containsKey(engine to voice)) {
                    if (embouchureRestore.waitsForRecipe(engine, voice)) emptyList()
                    else if (engine == Engine.CREASE) Crease.macrosFor(voice as CreaseVoice, creaseModel)
                    else if (loadedPatch is CausticPatch) CausticInstrument.macrosFor(loadedPatch.voice, loadedPatch.model)
                    else if (loadedPatch is EmbouchurePatch) Embouchure.macrosFor(loadedPatch.voice, loadedPatch.model)
                    else engine.macrosFor(voice)
                }
                val registersRoot = if (engine == Engine.REGISTERS)
                    (buildCurrentPatch(engine.patchDisplayName(voice)) as RegistersPatch).midi else null
                val shoalHoldSupported = if (engine == Engine.SHOAL) {
                    val patch = buildCurrentPatch(engine.patchDisplayName(voice)) as ShoalPatch
                    Shoal.supportsHold(patch.model, patch.material)
                } else true
                MacroSliders(
                    engine, voice, macroSpecs, macros, loadedPatch, origamiPatch, registersRoot, shoalHoldSupported,
                    holdingRibbon = flockHeldPreview != null,
                    classColor = classColor,
                    scheme = scheme,
                    macroRange = ::macroRange,
                    onMacro = ::updateMacro,
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabButton(
                        "SCRAMBLE",
                        scheme,
                        enabled = !embouchureRestore.waitsForRecipe(engine, voice),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (embouchureRestore.waitsForRecipe(engine, voice)) return@LabButton
                        touched = true
                        embouchureRestore.pending.remove(engine to voice)
                        currentPresetByVoice.remove(engine to voice)
                        val random = Random(System.nanoTime())
                        val scrambled = when {
                            engine == Engine.APSIS ->
                                Apsis.scramble(voice as ApsisVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.CHAMBER ->
                                Chamber.scramble(voice as ChamberVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.CAUSTIC ->
                                CausticInstrument.scramble(voice as CausticVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.CREASE ->
                                Crease.scramble(voice as CreaseVoice, random, near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.LATTICE ->
                                Lattice.scramble(voice as LatticeVoice, random, near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.FLOCK ->
                                Flock.scramble(voice as FlockVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.TIDAL ->
                                Tidal.scramble(voice as TidalVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.EMBOUCHURE && loadedPatch is EmbouchurePatch ->
                                Embouchure.scramble(voice as EmbouchureVoice, random, near = loadedPatch)
                            engine == Engine.BELLOWS && loadedPatch is BellowsPatch ->
                                Bellows.scramble(voice as BellowsVoice, random, near = loadedPatch)
                            engine == Engine.OVEN ->
                                Oven.scramble(voice as OvenVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            engine == Engine.FILAMENT ->
                                Filament.scramble(voice as FilamentVoice, random,
                                    near = buildCurrentPatch(engine.patchDisplayName(voice)))
                            else -> engine.scramble(voice, random)
                        }
                        val next = scrambled.mapValues { (key, value) -> value.coerceIn(macroRange(key)) }
                        // ORIGAMI's six controls describe one finite pitched note.
                        // Reroll its geometry while retaining the displayed/saved pitch.
                        val values = when (engine) {
                            Engine.ORIGAMI -> next + ("TUNE" to macros.getValue("TUNE"))
                            Engine.OHM -> next + macros.filterKeys { it == "HOLD" }
                            else -> next
                        }
                        macrosByVoice[engine to voice] = values
                        keepOhmMacros(values)
                        ensureNativeDraft()
                    }
                    // Between SCRAMBLE and SEND TO PAD: the sound is kept
                    // before it is placed. No kit is needed — a preset is
                    // the shelf's, not the open kit's.
                    LabButton(
                        if (saveBusy) "…" else "SAVE PRESET ▸",
                        scheme,
                        enabled = !saveBusy && !embouchureRestore.waitsForRecipe(engine, voice),
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "SAVE PRESET",
                    ) {
                        if (embouchureRestore.waitsForRecipe(engine, voice)) return@LabButton
                        namingPreset = true
                    }
                    LabButton(
                        if (sendBusy) "…" else "SEND TO PAD ▸",
                        scheme,
                        enabled = kit != null && !sendBusy && !embouchureRestore.waitsForRecipe(engine, voice),
                        modifier = Modifier.weight(1f),
                        accessibilityLabel = "SEND TO PAD",
                    ) {
                        if (embouchureRestore.waitsForRecipe(engine, voice)) return@LabButton
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
                    enabled = kit != null && !sendBusy && !spreadOpening &&
                        !embouchureRestore.waitsForRecipe(engine, voice),
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
                // REGISTERS retains the authored attack before its held region;
                // CREASE keeps its pressure attack before continuous held motion.
                // ORPHICA holds a captured sustain behind its own plucked onset.
                // CONTOUR starts from its settled repeating tone.
                // ORIGAMI offers held keys for current FAN/STILL sounds; CRANE
                // and saved earlier models retain their finite-only path.
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
                            when (engine) {
                                Engine.CREASE -> "MAKE INSTRUMENT NEEDS MODEL 3. THE SAVED ORIGINAL STILL PLAYS AS BEFORE."
                                Engine.BELLOWS -> "MAKE INSTRUMENT NEEDS A CURRENT BELLOWS SOUND. THE SAVED ORIGINAL STILL PLAYS AS BEFORE."
                                else -> "MAKE INSTRUMENT NEEDS ROOT ${Scales.nameOf(OpenSkyPadMaker.ROOT_MIDI_MIN)}–${Scales.nameOf(OpenSkyPadMaker.ROOT_MIDI_MAX)}"
                            },
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
                        droneSheet.droneOpen = true
                    }
                }
            }

            if (flockHeldPreview != null) {
                FlockHeldAudition(flockHeldPreview, classColor, scheme)
            } else if (engine == Engine.APSIS) {
                ApsisAuditionControls(classColor, scheme,
                    onAudition = { touched = true; snip?.let { audition(it, allowLoop = true) } },
                    onStop = { touched = false; voicePlayer?.stop(); voicePlayer = null },
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Layout.PRIMARY_ACTION_H.dp)
                        .raisedBevel(scheme, fill = classColor.copy(alpha = 0.85f))
                        .tapeClick(label = "AUDITION", enabled = !embouchureRestore.waitsForRecipe(engine, voice)) {
                            snip?.let { audition(it, allowLoop = true) }
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TapeText("AUDITION", TapeType.pixel, scheme.titleInk.tape)
                }
            }
        }

        if (enginePicker.open) {
            EnginePickerSheet(
                names = ENGINE_NAMES,
                voicesOf = ::engineVoiceNames,
                current = engine.name,
                recents = enginePicker.recents,
                onPick = { name ->
                    enginePicker.open = false
                    // The engine it is already on is not an arrival: arriving resets the voice and
                    // plays the sound again, and a pick that was a look at the list has asked for neither.
                    if (name != engine.name) arrive(Engine.valueOf(name))
                    enginePicker.picked(name)
                },
                onDismiss = { enginePicker.open = false },
            )
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
            SynthOverlayBackHandler(onBack = cancelChooser)
        }

        if (reopeningPad) {
            val cancelReopen = { reopeningPad = false }
            ReopenPadOverlay(reopenablePads, scheme, onPick = ::reopenPad, onCancel = cancelReopen)
            SynthOverlayBackHandler(onBack = cancelReopen)
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
            SynthOverlayBackHandler(onBack = cancelSpread)
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
            SynthOverlayBackHandler(onBack = cancelNaming)
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
            SynthOverlayBackHandler(onBack = cancelForget)
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
            SynthOverlayBackHandler(onBack = closeBin)
        }

        if (makingInstrument && supportsHeldInstrument) {
            // Recomputed here rather than cached: cheap (a Spec is a data
            // class, no rendering), and it must reflect whatever the panel
            // holds right now, the same as every value below it.
            heldSpec()?.let { spec ->
                HeldInstrumentSheet(
                    heading = "${engine.name} · ${chipLabel(engine, voice)}",
                    name = heldSheet.holdName,
                    hasAttack = spec.hasAttack,
                    attack = heldSheet.holdAttack,
                    release = spec.releaseFraction ?: heldSheet.holdRelease,
                    knobLabel = spec.knobLabel,
                    instrumentNote = spec.instrumentNote,
                    touchLayers = spec.touchLayers,
                    touchLayersNote = spec.touchLayersNote,
                    done = heldSheet.holdDone,
                    total = heldSheet.holdTotal,
                    previewing = heldSheet.holdPreviewing,
                    heldPreview = if (engine == Engine.FLOCK && !heldSheet.holdFlockTouchLayers) flockHeldPreview else null,
                    fillColor = classColor,
                    scheme = scheme,
                    onAttack = { heldSheet.holdAttack = it },
                    onRelease = { fraction ->
                        if (engine == Engine.FLOCK) updateFlockPatch {
                            it.copy(releaseSeconds = FlockPadMaker.RELEASE.value(fraction)
                                .coerceIn(FlockPadMaker.RELEASE.lo, FlockPadMaker.RELEASE.hi))
                        } else if (engine == Engine.OHM) updateOhmPerformance {
                            it.copy(releaseSeconds = OhmPadMaker.RELEASE.value(fraction)
                                .coerceIn(OhmPadMaker.RELEASE.lo, OhmPadMaker.RELEASE.hi))
                        } else heldSheet.holdRelease = fraction
                    },
                    onTouchLayers = {
                        flockHeldPreview?.silence()
                        if (engine == Engine.FLOCK) heldSheet.holdFlockTouchLayers = it
                        else heldSheet.holdTouchLayers = it
                    },
                    onPreview = ::previewHeld,
                    onMake = ::makeHeld,
                    onCancel = ::closeHeld,
                )
            }
            SynthOverlayBackHandler(onBack = ::closeHeld)
        }

        if (droneSheet.droneOpen) {
            // The spec itself is recomputed here rather than cached, the
            // same call [heldSpec] makes and for the same reason: cheap
            // (it is only a voice and a macro map, no rendering), and it
            // must reflect whatever the panel holds right now. The
            // readout is not recomputed here — [droneLabel] is, off the
            // main thread, since SIREN's own is not free.
            droneSpec()?.let { spec ->
                val root = droneSheet.droneRoot
                val session = droneSheet.droneSession
                val ready = root != null && session != null
                DroneSheet(
                    heading = "${engine.name} · ${chipLabel(engine, voice)}",
                    roots = spec.roots,
                    hasMotion = spec.hasMotion,
                    root = root,
                    ready = ready,
                    label = droneSheet.droneLabel,
                    motion = droneSheet.droneMotion,
                    rate = droneSheet.droneRate,
                    previewing = droneSheet.dronePreviewing,
                    fillColor = classColor,
                    scheme = scheme,
                    onRoot = { droneSheet.droneRoot = it },
                    onMotion = { droneSheet.droneMotion = it },
                    onRate = { droneSheet.droneRate = it },
                    onPreview = ::previewDrone,
                    onSend = ::sendDrone,
                    onCancel = ::closeDrone,
                )
            }
            SynthOverlayBackHandler(onBack = ::closeDrone)
        }
    }
}

/** Keep the preview resource's inline remember/disposal bookkeeping out of the large screen method. */
@Composable
private fun rememberFilamentPreviews(): FilamentPreviewCache {
    val previews = remember { FilamentPreviewCache() }
    DisposableEffect(previews) { onDispose { previews.clear() } }
    return previews
}

/** Separate transport controls for the repeating Apsis preview. */
@Composable
private fun ApsisAuditionControls(
    classColor: Color,
    scheme: Scheme,
    onAudition: () -> Unit,
    onStop: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.weight(1f).heightIn(min = Layout.PRIMARY_ACTION_H.dp)
                .raisedBevel(scheme, fill = classColor.copy(alpha = .85f))
                .tapeClick(label = "AUDITION", onClick = onAudition)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { TapeText("AUDITION", TapeType.pixel, scheme.titleInk.tape) }
        LabButton("STOP", scheme, enabled = true,
            modifier = Modifier.weight(.45f).heightIn(min = Layout.PRIMARY_ACTION_H.dp),
            accessibilityLabel = "STOP AUDITION", onClick = onStop)
    }
}

/** Apsis playing controls stay outside the large screen's Compose method. */
@Composable
private fun ApsisPlayingControls(
    patch: ApsisPatch,
    classColor: Color,
    scheme: Scheme,
    onPatch: (ApsisPatch) -> Unit,
) {
    MacroSlider(
        label = "ROOT",
        value = (patch.midi - Apsis.MIDI_MIN).toFloat() / (Apsis.MIDI_MAX - Apsis.MIDI_MIN),
        fillColor = classColor,
        scheme = scheme,
        readout = Scales.nameOf(patch.midi),
        onValueChange = { value ->
            val midi = (Apsis.MIDI_MIN + value * (Apsis.MIDI_MAX - Apsis.MIDI_MIN)).roundToInt()
                .coerceIn(Apsis.MIDI_MIN, Apsis.MIDI_MAX)
            onPatch(patch.copy(midi = midi))
        },
    )
    MacroSlider(
        label = "VELOCITY", value = patch.velocity, fillColor = classColor, scheme = scheme,
        onValueChange = { onPatch(patch.copy(velocity = it.coerceIn(0f, 1f))) },
    )
    TapeText(
        if (Apsis.isLoop(patch.macros["HOLD"] ?: 0f)) "HOLD: SETTLED CYCLE · REPEATS UNTIL STOP."
        else "HOLD: NOTE LENGTH · TOP STEP: LOOP.",
        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
    )
}

/**
 * The MACRO sliders, CONTOUR's under its TONE/ENVELOPE/MOVEMENT/FILTER/PLAY
 * headings. A composable of its own because `Column` is inline: inside
 * [SynthScreen] this block compiled into the screen's one method, which ART's
 * verifier rejected once that method outgrew its registers.
 */
@Composable
private fun MacroSliders(
    engine: Engine,
    voice: Enum<*>,
    macroSpecs: List<MacroSpec>,
    macros: Map<String, Float>,
    loadedPatch: Patch?,
    origamiPatch: OrigamiPatch?,
    registersRoot: Int?,
    shoalHoldSupported: Boolean,
    holdingRibbon: Boolean,
    classColor: Color,
    scheme: Scheme,
    macroRange: (String) -> ClosedFloatingPointRange<Float>,
    onMacro: (String, Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((heading, specs) in if (engine == Engine.CONTOUR) {
            listOf("TONE", "ENVELOPE", "MOVEMENT", "FILTER", "PLAY").map { heading ->
                heading to macroSpecs.filter { contourMacroGroup(it.name) == heading }
            }
        } else listOf(null to macroSpecs)) {
            if (heading != null) TapeText(
                heading, TapeType.pixelSmall, scheme.ink2.tape,
                Modifier.padding(top = 8.dp),
            )
            for (spec in specs) {
                // HOLD remains a finite 2–6 s control. Held RIBBON uses
                // its saved release instead, without changing HOLD's value.
                if (holdingRibbon && spec.name == "HOLD") continue
                MacroSlider(
                    label = spec.name,
                    value = macros.getValue(spec.name),
                    fillColor = classColor,
                    scheme = scheme,
                    // SIREN's HOLD reads LOOP at its top: the render is
                    // one seamless loop for the SURFACE, not a longer
                    // hold (Siren.isLoop), the way GLINT's PEAK readout
                    // would say the harmonic it snapped to.
                    readout = macroReadout(spec.name, engine, voice, macros, loadedPatch, origamiPatch, registersRoot, shoalHoldSupported),
                    onValueChange = { v -> onMacro(spec.name, v) },
                    valueRange = macroRange(spec.name),
                    enabled = (spec.name != "HOLD" || (shoalHoldSupported && engine != Engine.CAUSTIC)) &&
                        (engine != Engine.TRIO || Trio.isMacroActive(voice as TrioVoice, spec.name)),
                    semanticRange = if (engine == Engine.PRISM && spec.name == "HOLD")
                        ({ macroRange(spec.name) }) else null,
                )
            }
        }
    }
}

/**
 * MAKE INSTRUMENT ▸'s sheet: its ATTACK/RELEASE knobs, zone progress, preview and render job, and the name it lands under.
 * One remembered holder rather than 7 remembered locals, like [SynthSeed]:
 * SynthScreen's single method sits at the register limit ART's verifier accepts.
 */
private class HeldSheetState {
    var holdAttack by mutableStateOf(ResinPadMaker.ATTACK.defaultFraction)
    var holdRelease by mutableStateOf(ResinPadMaker.RELEASE.defaultFraction)
    // An instrument-building choice, separate from the saved synthesis recipe.
    var holdTouchLayers by mutableStateOf(false)
    // FLOCK opens with physical touch enabled without changing CREASE's existing choice.
    var holdFlockTouchLayers by mutableStateOf(true)
    // Completed zones (or touch samples), of [holdTotal]; -1 when idle.
    var holdDone by mutableStateOf(-1)
    var holdTotal by mutableStateOf(0)
    var holdPreviewing by mutableStateOf(false)
    var holdJob by mutableStateOf<Job?>(null)
    var holdName by mutableStateOf("")
}

/**
 * MAKE DRONE ▸'s sheet: open state, ROOT/MOTION/RATE, preview, render job, the session it plays through and its label.
 * One remembered holder rather than 8 remembered locals, like [SynthSeed]:
 * SynthScreen's single method sits at the register limit ART's verifier accepts.
 */
private class DroneSheetState {
    var droneOpen by mutableStateOf(false)
    var droneRoot by mutableStateOf<Int?>(null)
    var droneMotion by mutableStateOf(DroneMaker.DEFAULT_MOTION)
    var droneRate by mutableStateOf(DroneMaker.DEFAULT_RATE)
    var dronePreviewing by mutableStateOf(false)
    var droneJob by mutableStateOf<Job?>(null)
    var droneSession by mutableStateOf<Session?>(null)
    var droneLabel by mutableStateOf("…")
}

/**
 * What the navigation bag held when SYNTH opened, read once at the first
 * composition: reopened work, preset names and the OHM, SCANNED CHAIN, OVEN and
 * ORIGAMI recipes that seed the per-voice maps. One remembered holder rather than
 * six remembered locals, as with [rememberEmbouchureRestore]: SynthScreen's single
 * method sits at the register limit ART's verifier accepts from the debug dex.
 */
private class SynthSeed(settings: Map<String, String>) {
    val seededWork = run {
        SynthWork.read(settings).mapNotNull { work ->
            val patch = work.recipe.patch ?: return@mapNotNull null
            val sourceEngine = Engine.entries.firstOrNull { it.name == patch.engine } ?: return@mapNotNull null
            val sourceVoice = sourceEngine.voices().firstOrNull { it.name == patch.voiceName } ?: return@mapNotNull null
            (sourceEngine to sourceVoice) to work
        }.toMap()
    }
    val seededPresets = storedPresetNames(settings)
    // Old macro-only work predates a saved model field. New voices start at the
    // current renderer and get a typed draft when visited; old work stays legacy.
    val tremorModelsWithoutRecipe = TremorVoice.entries.associateWith { v ->
        if (settings.containsKey(presetSettingsKey(Engine.TREMOR, v)) ||
            Tremor.macrosFor(v).any { settings.containsKey(macroSettingsKey(Engine.TREMOR, v, it.name)) })
            Tremor.LEGACY_MODEL else Tremor.MODEL
    }
    val restoredOhm = restoredOhmPatch(settings)
    val restoredScannedRecipes = run {
        ScannedChainVoice.entries.mapNotNull { v ->
            settings[scannedRecipeSettingsKey(v)]?.let { stored ->
                runCatching { PadRecipe.fromJsonText(stored) }.getOrNull()
                    ?.takeIf { (it.patch as? ScannedChainPatch)?.voice == v }?.let { v to it }
            }
        }.toMap()
    }
    val seededOvenPatches = storedOvenPatches(settings)
    val restoredOrigami = restoredOrigamiPatches(settings)
    val restoredChamber = SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().associateBy { it.voice }
    // The screen owns this bounded cache; the preview, SPREAD and pad delivery
    // reuse exact completed recipes without retaining solver state or mutable PCM.
    val chamberRenderer = ChamberRenderer()
}

/**
 * MACRO slider readouts (SIREN's LOOP, OVERLAP's seconds, ORIGAMI's note...).
 * Kept out of [SynthScreen]'s composable: inline, this `when` grew that method
 * until ART's verifier rejected it on device ("register has type Conflict").
 */
private fun macroReadout(
    name: String,
    engine: Engine,
    voice: Enum<*>,
    macros: Map<String, Float>,
    loadedPatch: Patch?,
    origamiPatch: OrigamiPatch?,
    registersRoot: Int?,
    shoalHoldSupported: Boolean,
): String? = when {
    engine == Engine.PLUCK && voice == PluckVoice.UPRIGHT && name == "TUNE" ->
        Scales.nameOf(28 + (macros.getValue(name) * Pluck.TUNE_SEMITONES).roundToInt())
    engine == Engine.APSIS && name == "HOLD" && Apsis.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.CAUSTIC && name == "HOLD" -> "OFF"
    engine == Engine.PRISM && name == "HOLD" && prismFiniteMotion(loadedPatch as? PrismPatch) -> "NOTE"
    engine == Engine.SHOAL && name == "HOLD" && !shoalHoldSupported -> "NOTE"
    engine == Engine.OVERLAP && name == "HOLD" -> {
        val hold = macros.getValue(name)
        val legacy = (loadedPatch as? OverlapPatch)?.model == 1
        val seconds = if (legacy) .008f + 2.8f * hold * hold
            else .040f + 5.96f * hold * hold
        "%.1fs".format(Locale.ROOT, seconds)
    }
    engine == Engine.FLOCK && name == "HOLD" ->
        "%.1fs".format(Locale.ROOT, 2f + 4f * macros.getValue(name))
    engine == Engine.ORPHICA && name == "HOLD" && Orphica.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.OSPREY && name == "HOLD" && macros.getValue(name) >= .999f -> "EXTEND"
    engine == Engine.ORIGAMI && name == "TUNE" -> Scales.nameOf(
        (loadedPatch as? OrigamiPatch)?.midi
            ?: Origami.midiFor(voice as OrigamiVoice, macros.getValue(name)),
    )
    origamiPatch != null && name == "MOTION" && origamiPatch.gesture != OrigamiGesture.STEADY ->
        "%.2fs".format(Locale.ROOT, OrigamiGestures.durationSeconds(origamiPatch.voice, macros.getValue(name)))
    engine == Engine.SIREN && name == "HOLD" && Siren.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.FLOTILLA && name == "HOLD" && Flotilla.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.AEROSTAT && name == "HOLD" && Aerostat.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.TREMOR && name == "HOLD" && macros.getValue(name) >= Tremor.HOLD_LOOP -> "LOOP"
    engine == Engine.BALLAST && name == "HOLD" && Ballast.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.CISTERN && name == "HOLD" && Cistern.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.CIRCUIT && name == "HOLD" && Circuit.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.ALLOY && name == "HOLD" && Alloy.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.REGISTERS && name == "HOLD" && Registers.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.REGISTERS && name == "TUNE" && registersRoot != null -> Scales.nameOf(registersRoot)
    engine == Engine.MURK && name == "HOLD" && Murk.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.NIMBUS && name == "HOLD" && Nimbus.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.TESSERA && name == "HOLD" && Tessera.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.PITCHWHEEL && name == "HOLD" && Pitchwheel.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.FILAMENT && name == "HOLD" && Filament.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.MURMUR && name == "HOLD" && Murmur.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.PRISM && name == "HOLD" && Prism.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.CONTOUR && name == "TUNE" ->
        Scales.nameOf(ContourEngine.midiFor(voice as ContourVoice, macros.getValue(name)))
    engine == Engine.CONTOUR && name == "HOLD" && ContourEngine.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.STOOP && name == "HOLD" && Stoop.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.LACQUER && name == "HOLD" && Lacquer.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.SKITTER && name == "HOLD" && Skitter.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.TANGLE && name == "HOLD" && Tangle.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.SHOAL && name == "HOLD" && Shoal.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.CREASE && name == "HOLD" && Crease.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.LATTICE && name == "HOLD" && Lattice.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.RELAY && name == "HOLD" && Relay.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.RELAY && name == "TUNE" ->
        Scales.nameOf(Relay.midiFor(voice as RelayVoice, macros.getValue(name)))
    engine == Engine.EMBOUCHURE && name == "HOLD" && Embouchure.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.BELLOWS && name == "HOLD" && Bellows.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.COROLLA && name == "HOLD" && Corolla.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.THAW && name == "HOLD" && Thaw.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.UNDERTOW && name == "HOLD" && Undertow.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.SUTURE && name == "HOLD" && Suture.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.SPOOL && name == "HOLD" && Spool.isLoop(macros.getValue(name)) -> "LOOP"
    engine == Engine.REVEL && name == "HOLD" && Revel.isLoop(macros.getValue(name)) -> "LOOP"
    else -> null
}

/**
 * Every overlay keeps Back registered and guards busy work inside its callback. Supplying the
 * constant here also keeps Compose's default-Boolean placeholder out of the large screen method:
 * D8 otherwise merged that unused placeholder with an object register and ART rejected the screen.
 */
@Composable
private fun SynthOverlayBackHandler(onBack: () -> Unit) {
    BackHandler(true, onBack)
}

// ---------- ENGINE header and list ----------

/** Every picker engine by constant name, in the enum's own order: what the engine list is handed. */
private val ENGINE_NAMES = Engine.entries.map { it.name }

/** The same, as a set: what the saved recents are checked against. */
private val ENGINE_NAME_SET = ENGINE_NAMES.toSet()

/** The voice names of the engine called [name], for the engine list's search and its voices line. */
private fun engineVoiceNames(name: String): List<String> = Engine.valueOf(name).voices().map { it.name }

/**
 * SYNTH's LCD header: `◄  NAME ▾  ►` at the left and the voice's chip at the right.
 *
 * The name opens the engine list ([onOpen]); the ▾ says it opens a sheet. ◄ and ►
 * ([onPrevious], [onNext]) step to the engine either side, wrapping, and each is a
 * 48 dp target named for what it does. Stepping is the old tap-to-cycle, kept because
 * walking a few neighbours is still the quickest way to audition them; the list is
 * for the other times, when 63 engines are too many to walk.
 *
 * The row is as tall as the standing hit-target rule asks, not the 40 dp LCD header
 * height, which nothing else tappable in an LCD header was ever held to.
 */
@Composable
private fun EngineHeader(
    engine: Engine,
    voice: Enum<*>,
    scheme: Scheme,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(max(Layout.LCD_HEADER_H, Layout.MIN_HIT_TARGET).dp)
            .lcdPanel(scheme)
            .padding(start = 2.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // The cluster gives way to the voice chip, never the other way round: the chip is
        // the shorter, and a name that is cut still opens the list.
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            EngineStep("◄", "PREVIOUS ENGINE", scheme, onPrevious)
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                    // Says which engine is current and what the tap does: the words, not the
                    // ▾, which a screen reader would read as a symbol.
                    .tapeClick(label = "ENGINE ${engine.displayName}. CHOOSE ENGINE", onClick = onOpen)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TapeText("${engine.displayName} ▾", TapeType.lcdHeader, scheme.lcdInk.tape)
            }
            EngineStep("►", "NEXT ENGINE", scheme, onNext)
        }
        TapeText(chipLabel(engine, voice), TapeType.lcdSmall, scheme.amber.tape)
    }
}

/** One of the header's arrows: a [Layout.MIN_HIT_TARGET] square, named for the step it takes. */
@Composable
private fun EngineStep(glyph: String, label: String, scheme: Scheme, onClick: () -> Unit) {
    Box(
        Modifier.size(Layout.MIN_HIT_TARGET.dp).tapeClick(label = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(glyph, TapeType.lcdReadout, scheme.lcdInk.tape)
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
            // A merging clickable here swallows the field's SetText action,
            // so the name cannot be typed. The CANCEL button below is the
            // named control; this scrim only catches the dim tap.
            .pointerInput(onCancel) { detectTapGestures { onCancel() } },
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
            TapeText("${engine.displayName} · ${chipLabel(engine, voice)}", TapeType.pixel, scheme.ink2.tape)
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

/** Contour's expanded panel follows the sound's tone, envelope, scan, filter and playback controls. */
private fun contourMacroGroup(name: String): String = when (name) {
    "ATTACK", "DECAY" -> "ENVELOPE"
    "SCAN", "OFFSET", "MOTION", "RATE" -> "MOVEMENT"
    "FILTER", "RESO", "ENV" -> "FILTER"
    "TUNE", "HOLD" -> "PLAY"
    else -> "TONE"
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
 * CREASE keeps its first pressure attack before the repeating moving sustain.
 * CONTOUR begins at its settled tone, omitting the original finite attack.
 */
private sealed interface HeldSpec {
    val zoneMidis: List<Int>
    val hasAttack: Boolean
    val instrumentNote: String? get() = null
    val releaseFraction: Float? get() = null
    val touchLayers: Boolean? get() = null
    val touchLayersNote: String? get() = null
    /** Layered makers count touch samples; other makers retain their existing zone count. */
    val progressPerZone: Int get() = 1

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

    class Tidal(private val spec: TidalPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = TidalPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${TidalPadMaker.secondsLabel(spec.releaseSeconds)}"
        override val instrumentNote get() =
            "C2–C6 · KEEPS THE ENCOUNTER, THEN HOLDS ITS MOVING AFTERMATH. LET GO TO FADE."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(TidalPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = TidalPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) =
            TidalPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
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

    class Crease(private val spec: CreasePadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = CreasePadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val progressPerZone get() = spec.layerCount
        override val touchLayers get() = spec.touchLayers
        override val knobLabel get() = "RELEASE ${CreasePadMaker.secondsLabel(spec.releaseSeconds)}"
        override val instrumentNote get() = if (spec.touchLayers)
            "KEEPS THE PRESSURE ATTACK AND MOVING SUSTAIN. SOFT, MID AND HARD TOUCH CHANGE PRESSURE AND TONE."
        else "KEEPS THE PRESSURE ATTACK, THEN REPEATS ITS MOVING SUSTAIN WHILE HELD. KEY TOUCH CHANGES VOLUME."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = CreasePadMaker.renderLayers(spec, midi, cancelled)
        override fun preview(cancelled: () -> Boolean) = CreasePadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = CreasePadMaker.exportLayers(name, spec, notes, destRoot)
    }

    class Contour(private val spec: ContourPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = ContourPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${ContourPadMaker.secondsLabel(spec.releaseSeconds)}"
        override val instrumentNote get() =
            "FOUR OCTAVES OF SETTLED TONE. ORIGINAL ATTACK OMITTED. TUNE FOLLOWS THE KEYBOARD."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(ContourPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = ContourPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) = ContourPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
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

    class Flock(private val spec: FlockPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = FlockPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val progressPerZone get() = spec.layerCount
        override val touchLayers get() = spec.touchLayers
        override val touchLayersNote get() =
            "ON: SOFT, MID AND HARD TOUCH CHANGE FLOCK MOTION. VELOCITY SETS THE HARD TOUCH. THREE SAMPLES PER NOTE; ABOUT 3× THE TIME AND STORAGE."
        override val releaseFraction get() = FlockPadMaker.RELEASE.fraction(spec.releaseSeconds)
        override val knobLabel get() = "RELEASE ${FlockPadMaker.secondsLabel(spec.releaseSeconds)}"
        override val instrumentNote get() = if (spec.touchLayers)
            "DRY RIBBON · SOFT, MID AND HARD TOUCH CHANGE THE FLOCK'S DISTURBANCE. PREVIEW PLAYS ALL THREE; LIFT A KEY TO RELEASE."
        else "DRY RIBBON · KEEPS THE ATTACK, THEN REPEATS CAPTURED MOTION UNTIL NOTE-OFF. KEY TOUCH CHANGES VOLUME."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = FlockPadMaker.renderLayers(spec, midi, cancelled)
        override fun preview(cancelled: () -> Boolean) = FlockPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) =
            FlockPadMaker.exportLayers(name, spec, notes, destRoot)
    }

    class Ohm(private val spec: OhmPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = OhmPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val releaseFraction get() = OhmPadMaker.RELEASE.fraction(spec.releaseSeconds)
        override val knobLabel get() = "RELEASE ${OhmPadMaker.secondsLabel(spec.releaseSeconds)}"
        override val instrumentNote get() =
            "C3–C5 · KEEPS THE ARRIVAL, THEN HOLDS THE SHARED TONE. LET GO TO RELEASE."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(OhmPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = OhmPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) =
            OhmPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }

    class Origami(private val spec: OrigamiPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = OrigamiPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE %.2f s".format(Locale.ROOT, spec.releaseSeconds)
        override val instrumentNote get() = "C3–C5 · HOLD A KEY TO SUSTAIN. LET GO TO FADE."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(OrigamiPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = OrigamiPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) =
            OrigamiPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }

    class Filament(private val spec: FilamentPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = FilamentPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${FilamentPadMaker.secondsLabel(spec.releaseSeconds)}"
        override val instrumentNote get() = "C3–C5 · KEEPS THE ATTACK. HOLD A KEY TO SUSTAIN; LET GO TO FADE."
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = listOf(FilamentPadMaker.renderZone(spec, midi, cancelled))
        override fun preview(cancelled: () -> Boolean) = FilamentPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<List<KeyNote>>, destRoot: File) =
            FilamentPadMaker.export(name, spec, notes.map { it.single() }, destRoot)
    }
}

/**
 * MAKE INSTRUMENT ▸'s sheet (RESIN, SIREN, GLINT, REGISTERS, CREASE, BELLOWS, CONTOUR, OPENSKY HALO or ORIGAMI FAN/STILL): [PresetNameDialog]'s frame -
 * scrim, raised bevel, CANCEL beside the real buttons - over the knobs a
 * held note has that a one-shot never needed. [hasAttack] hides ATTACK's
 * slider for an engine with none to dial (SIREN, GLINT, REGISTERS, OPENSKY HALO); RELEASE always shows,
 * and [knobLabel] spells out whichever of the two apply in seconds. The
 * optional touch-layer button leaves the saved recipe alone. While
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
    touchLayers: Boolean?,
    touchLayersNote: String?,
    done: Int,
    total: Int,
    previewing: Boolean,
    heldPreview: FlockHeldPreviewState?,
    fillColor: Color,
    scheme: Scheme,
    onAttack: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    onTouchLayers: (Boolean) -> Unit,
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
                if (touchLayers != null) {
                    ActionButton(
                        "TOUCH LAYERS ${if (touchLayers) "ON" else "OFF"}", scheme,
                        enabled = !busy,
                        lit = touchLayers,
                        accessibilityLabel = "TOUCH LAYERS",
                        modifier = Modifier.fillMaxWidth().semantics { selected = touchLayers },
                    ) { onTouchLayers(!touchLayers) }
                    TapeText(
                        touchLayersNote ?: "ON: SOFT, MID AND HARD TOUCH CHANGE PRESSURE AND TONE. THREE SAMPLES PER NOTE; ABOUT 3× THE TIME AND STORAGE.",
                        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 5,
                    )
                }
                if (previewing) TapeText("RENDERING…", TapeType.lcdSmall, scheme.amber.tape)
                if (heldPreview != null) FlockHeldAudition(heldPreview, fillColor, scheme,
                    accessibilityLabel = "INSTRUMENT AUDITION")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = !writing, modifier = Modifier.weight(1f), onClick = onCancel)
                if (heldPreview == null) {
                    ActionButton("PREVIEW", scheme, enabled = !busy, dimmed = busy, modifier = Modifier.weight(1f), onClick = onPreview)
                }
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

/** MAKE advances per completed zone; CREASE's count includes each touch sample in that zone. */
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

/** Performance edits copy the complete finite recipe; variation never passes through a Double. */
@Composable
internal fun TidalPerformanceControls(
    patch: TidalPatch,
    fill: Color,
    scheme: Scheme,
    onEdit: (TidalVoice, (TidalPatch) -> TidalPatch) -> Unit,
) {
    MacroSlider(
        label = "ROOT",
        value = (patch.midi - Tidal.MIN_MIDI).toFloat() / (Tidal.MAX_MIDI - Tidal.MIN_MIDI),
        fillColor = fill,
        scheme = scheme,
        readout = Scales.nameOf(patch.midi),
        onValueChange = { value ->
            onEdit(patch.voice) { current ->
                current.copy(midi = (Tidal.MIN_MIDI + value * (Tidal.MAX_MIDI - Tidal.MIN_MIDI))
                    .roundToInt().coerceIn(Tidal.MIN_MIDI, Tidal.MAX_MIDI))
            }
        },
    )
    MacroSlider("VELOCITY", patch.velocity, fill, scheme,
        onValueChange = { value -> onEdit(patch.voice) { it.copy(velocity = value.coerceIn(0f, 1f)) } })
    TapeText("SOURCE", TapeType.pixelSmall, scheme.ink2.tape)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((model, title) in listOf(1 to "ORIGINAL", 2 to "MATERIAL")) {
            ActionButton(
                title, scheme, enabled = true, lit = patch.model == model,
                modifier = Modifier.weight(1f).semantics { selected = patch.model == model },
                accessibilityLabel = "TIDAL $title SOURCE",
            ) { onEdit(patch.voice) { it.copy(model = model) } }
        }
    }
    var seedText by remember(patch.voice, patch.seed) { mutableStateOf(patch.seed.toString()) }
    TapeText("VARIATION SEED", TapeType.pixelSmall, scheme.ink2.tape)
    Box(Modifier.fillMaxWidth().sunkenField(scheme).padding(horizontal = 8.dp, vertical = 8.dp)) {
        BasicTextField(
            value = seedText,
            onValueChange = { text ->
                seedText = text
                text.toLongOrNull()?.let { seed -> onEdit(patch.voice) { it.copy(seed = seed) } }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii),
            textStyle = TapeType.pixelSmall.copy(color = scheme.ink.tape),
            cursorBrush = SolidColor(scheme.ink.tape),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "TIDAL VARIATION SEED" },
        )
    }
    if (seedText.toLongOrNull() == null) {
        TapeText("INVALID SEED · PREVIOUS VARIATION KEPT", TapeType.pixelSmall, scheme.warn.tape, maxLines = 3)
    }
    TapeText("PLAYBACK", TapeType.pixelSmall, scheme.ink2.tape)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((hold, title) in listOf(0f to "NOTE", 1f to "HOLD")) {
            val active = (patch.macros["HOLD"] ?: 0f) == hold
            ActionButton(title, scheme, enabled = true, lit = active,
                modifier = Modifier.weight(1f).semantics { selected = active },
                accessibilityLabel = "TIDAL $title PLAYBACK",
            ) { onEdit(patch.voice) { it.copy(macros = it.macros + ("HOLD" to hold)) } }
        }
    }
    TapeText(if (Tidal.isHeld(patch.macros["HOLD"] ?: 0f))
        "PADS LOOP THE AFTERMATH · MAKE INSTRUMENT KEEPS THE ENCOUNTER"
        else "ONE FINITE ENCOUNTER · PLAYS ONCE", TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3)
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
    LATTICE,
    THUMP, SCANNED_CHAIN, SKIN, ONSET, TINES, VELVET, VOX, PLUCK, ORPHICA, TONEWHEEL, REGISTERS, FATHOM, RESIN, TIDE, GLINT, SIREN, FORK, FLOTILLA, AEROSTAT, TREMOR, BALLAST, CISTERN, CIRCUIT, OBBLIGATO, ALLOY, THAW, MURK, NIMBUS, COROLLA, UNDERTOW, SUTURE, TESSERA, PITCHWHEEL, REVEL, PRISM, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, OSPREY, OPENSKY, OVERLAP, OVEN, FLOCK, VENT, CREASE, TRIO, NACRE, ORIGAMI, TREFOIL, OHM, MERCURY, CAUSTIC, FILAMENT, APSIS, TIDAL, CHAMBER;
    val displayName: String get() = name.replace('_', ' ')

    /** The engine after this one in the enum's order, wrapping from the last back to the first. */
    fun next(): Engine = entries[(ordinal + 1) % entries.size]

    /** The engine before this one, wrapping from the first round to the last. */
    fun previous(): Engine = entries[(ordinal + entries.size - 1) % entries.size]

    fun voices(): List<Enum<*>> = when (this) {
        THUMP -> ThumpVoice.entries
        SCANNED_CHAIN -> ScannedChainVoice.entries
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
        AEROSTAT -> AerostatVoice.entries
        TREMOR -> TremorVoice.entries
        BALLAST -> BallastVoice.entries
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
        TREFOIL -> TrefoilVoice.entries
        OSPREY -> OspreyVoice.entries
        FLOCK -> FlockVoice.entries
        OPENSKY -> OpenSkyVoice.entries
        OHM -> OhmVoice.entries
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
        CAUSTIC -> CausticVoice.entries
        LATTICE -> LatticeVoice.entries
        RELAY -> RelayVoice.entries
        EMBOUCHURE -> EmbouchureVoice.entries
        BELLOWS -> BellowsVoice.entries
        APSIS -> ApsisVoice.entries
        OVEN -> OvenVoice.entries
        ORIGAMI -> OrigamiVoice.entries
        // MERCURY shows the three voices the owner kept at R1's gate (2026-10-04). EDDY, VESSEL
        // and SHARD (R2c) stay in the roster and the CLI until round 7's listen, VENT's shape.
        MERCURY -> listOf(MercuryVoice.PING, MercuryVoice.SING, MercuryVoice.BLADE)
        FILAMENT -> FilamentVoice.entries
        TIDAL -> TidalVoice.entries
        CHAMBER -> ChamberVoice.entries
        NACRE -> NacreVoice.entries
    }

    fun macrosFor(voice: Enum<*>) = when (this) {
        THUMP -> Thump.macrosFor(voice as ThumpVoice)
        SCANNED_CHAIN -> ScannedChain.macrosFor(voice as ScannedChainVoice)
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
        AEROSTAT -> Aerostat.macrosFor(voice as AerostatVoice)
        TREMOR -> Tremor.macrosFor(voice as TremorVoice)
        BALLAST -> Ballast.macrosFor(voice as BallastVoice)
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
        TREFOIL -> Trefoil.macrosFor(voice as TrefoilVoice).filter { it.name != "HOLD" }
        OSPREY -> Osprey.macrosFor(voice as OspreyVoice)
        FLOCK -> Flock.macrosFor(voice as FlockVoice)
        OPENSKY -> OpenSky.macrosFor(voice as OpenSkyVoice)
        OHM -> Ohm.MACROS
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
        CAUSTIC -> CausticInstrument.macrosFor(voice as CausticVoice)
        LATTICE -> Lattice.macrosFor(voice as LatticeVoice)
        RELAY -> Relay.macrosFor(voice as RelayVoice)
        EMBOUCHURE -> Embouchure.macrosFor(voice as EmbouchureVoice)
        BELLOWS -> Bellows.macrosFor(voice as BellowsVoice)
        APSIS -> Apsis.macrosFor(voice as ApsisVoice)
        OVEN -> Oven.macrosFor(voice as OvenVoice)
        ORIGAMI -> Origami.macrosFor(voice as OrigamiVoice)
        MERCURY -> Mercury.macrosFor(voice as MercuryVoice)
        FILAMENT -> Filament.macrosFor(voice as FilamentVoice)
        TIDAL -> Tidal.macrosFor(voice as TidalVoice).filterNot { it.name == "HOLD" }
        CHAMBER -> Chamber.macrosFor(voice as ChamberVoice).filter { it.name != "HOLD" }
        NACRE -> Nacre.macrosFor(voice as NacreVoice).filter { it.name != "HOLD" }
    }

    fun defaults(voice: Enum<*>): Map<String, Float> = when (this) {
        THUMP -> Thump.defaults(voice as ThumpVoice)
        SCANNED_CHAIN -> ScannedChain.defaults(voice as ScannedChainVoice)
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
        AEROSTAT -> Aerostat.defaults(voice as AerostatVoice)
        TREMOR -> Tremor.defaults(voice as TremorVoice)
        BALLAST -> Ballast.defaults(voice as BallastVoice)
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
        TREFOIL -> Trefoil.defaults(voice as TrefoilVoice)
        OSPREY -> Osprey.defaults(voice as OspreyVoice)
        FLOCK -> Flock.defaults(voice as FlockVoice)
        OPENSKY -> OpenSkyPresets.forVoice(voice as OpenSkyVoice).first().macros
        OHM -> Ohm.defaults()
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
        CAUSTIC -> CausticInstrument.defaults(voice as CausticVoice)
        LATTICE -> Lattice.defaults(voice as LatticeVoice)
        RELAY -> Relay.defaults(voice as RelayVoice)
        EMBOUCHURE -> Embouchure.defaults(voice as EmbouchureVoice)
        BELLOWS -> Bellows.defaults(voice as BellowsVoice)
        APSIS -> Apsis.defaults(voice as ApsisVoice)
        OVEN -> Oven.defaults(voice as OvenVoice)
        ORIGAMI -> Origami.defaults(voice as OrigamiVoice)
        MERCURY -> Mercury.defaults(voice as MercuryVoice)
        FILAMENT -> Filament.defaults(voice as FilamentVoice)
        TIDAL -> Tidal.defaults(voice as TidalVoice)
        CHAMBER -> Chamber.defaults(voice as ChamberVoice)
        NACRE -> Nacre.defaults(voice as NacreVoice)
    }

    fun scramble(voice: Enum<*>, random: Random): Map<String, Float> = when (this) {
        THUMP -> Thump.scramble(voice as ThumpVoice, random)
        SCANNED_CHAIN -> ScannedChain.scramble(voice as ScannedChainVoice, random)
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
        AEROSTAT -> Aerostat.scramble(voice as AerostatVoice, random)
        TREMOR -> Tremor.macrosFor(voice as TremorVoice).associate { it.name to random.nextFloat() }
        BALLAST -> Ballast.scramble(voice as BallastVoice, random)
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
        TREFOIL -> Trefoil.macrosFor(voice as TrefoilVoice).associate { it.name to if (it.name == "HOLD") 0f else random.nextFloat() }
        OSPREY -> Osprey.scramble(voice as OspreyVoice, random)
        FLOCK -> Flock.scramble(voice as FlockVoice, random)
        OPENSKY -> OpenSky.scramble(voice as OpenSkyVoice, random)
        OHM -> Ohm.MACROS.associate { it.name to random.nextFloat() }
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
        CAUSTIC -> CausticInstrument.scramble(voice as CausticVoice, random)
        LATTICE -> Lattice.scramble(voice as LatticeVoice, random)
        RELAY -> Relay.scramble(voice as RelayVoice, random)
        EMBOUCHURE -> Embouchure.scramble(voice as EmbouchureVoice, random)
        BELLOWS -> Bellows.scramble(voice as BellowsVoice, random)
        APSIS -> Apsis.scramble(voice as ApsisVoice, random)
        OVEN -> Oven.scramble(voice as OvenVoice, random)
        ORIGAMI -> Origami.defaults(voice as OrigamiVoice).mapValues { (_, value) ->
            (value + (random.nextFloat() - .5f) * .7f).coerceIn(0f, 1f)
        }
        MERCURY -> Mercury.scramble(voice as MercuryVoice, random)
        FILAMENT -> Filament.scramble(voice as FilamentVoice, random)
        TIDAL -> Tidal.scramble(voice as TidalVoice, random)
        CHAMBER -> Chamber.scramble(voice as ChamberVoice, random)
        NACRE -> Nacre.defaults(voice as NacreVoice) + mapOf(
            "TOUCH" to (.15f + .55f * random.nextFloat()),
            "DUET" to (.10f + .80f * random.nextFloat()),
            "CURRENT" to random.nextFloat(), "VENUE" to random.nextFloat(),
            "BLOOM" to (.10f + .65f * random.nextFloat()), "HOLD" to 0f,
        )
    }

    // Every engine's `render(voice, macros)` takes exactly those two
    // params with the rest defaulted — TONEWHEEL alone also carries a
    // `gateSeconds` param (its stab-vs-sustain knob for the not-yet-built
    // key-patch side), but its default already produces the one-shot stab
    // this screen wants, so the uniform two-arg call reaches it fine.
    fun render(voice: Enum<*>, macros: Map<String, Float>): Snip = when (this) {
        THUMP -> Thump.render(voice as ThumpVoice, macros)
        SCANNED_CHAIN -> ScannedChain.render(voice as ScannedChainVoice, macros)
        SKIN -> Skin.render(voice as SkinVoice, macros)
        ONSET -> Onset.render(voice as OnsetVoice, macros)
        TINES -> Tines.render(voice as TinesVoice, macros)
        VELVET -> Velvet.render(voice as VelvetVoice, macros)
        VOX -> Vox.render(voice as VoxVoice, macros)
        PLUCK -> Pluck.render(voice as PluckVoice, macros, velocity = if (voice == PluckVoice.UPRIGHT) .65f else 1f)
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
        AEROSTAT -> Aerostat.render(voice as AerostatVoice, macros)
        TREMOR -> Tremor.render(voice as TremorVoice, macros)
        BALLAST -> Ballast.render(voice as BallastVoice, macros)
        CISTERN -> Cistern.render(voice as CisternVoice, macros)
        CIRCUIT -> Circuit.render(voice as CircuitVoice, macros)
        OBBLIGATO -> Obbligato.render(macros)
        ALLOY -> Alloy.render(voice as AlloyVoice, macros, config = Alloy.defaultConfigFor(voice))
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
        TREFOIL -> Trefoil.render(voice as TrefoilVoice, macros)
        OSPREY -> Osprey.render(voice as OspreyVoice, macros)
        FLOCK -> Flock.render(voice as FlockVoice, macros)
        OPENSKY -> OpenSkyPresets.forVoice(voice as OpenSkyVoice).first().copy(macros = macros).render()
        OHM -> Ohm.render(macros)
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
        CAUSTIC -> CausticInstrument.render(voice as CausticVoice, macros)
        LATTICE -> Lattice.render(voice as LatticeVoice, macros)
        RELAY -> Relay.render(voice as RelayVoice, macros)
        EMBOUCHURE -> Embouchure.render(voice as EmbouchureVoice, macros)
        BELLOWS -> Bellows.render(voice as BellowsVoice, macros)
        APSIS -> Apsis.render(voice as ApsisVoice, macros)
        OVEN -> Oven.render(voice as OvenVoice, macros)
        ORIGAMI -> Origami.render(voice as OrigamiVoice, macros)
        MERCURY -> Mercury.render(voice as MercuryVoice, macros)
        FILAMENT -> Filament.render(voice as FilamentVoice, macros)
        TIDAL -> Tidal.render(voice as TidalVoice, macros)
        CHAMBER -> Chamber.render(voice as ChamberVoice, macros)
        NACRE -> Nacre.render(voice as NacreVoice, macros)
    }

    /**
     * What a pad holding this voice's sound is filed as. [macros] matters
     * only where one knob picks the drum: VOX BEATBOX's HIT.
     */
    fun drumClass(voice: Enum<*>, macros: Map<String, Float> = emptyMap()): DrumClass = when (this) {
        THUMP -> (voice as ThumpVoice).drumClass
        SCANNED_CHAIN -> ScannedChain.drumClassFor(voice as ScannedChainVoice, macros)
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
        AEROSTAT -> Aerostat.drumClassFor(voice as AerostatVoice, macros)
        TREMOR -> Tremor.drumClassFor(voice as TremorVoice, macros)
        BALLAST -> Ballast.drumClassFor(voice as BallastVoice, macros)
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
        TREFOIL -> DrumClass.TONAL
        OSPREY -> Osprey.drumClassFor(voice as OspreyVoice, macros)
        FLOCK -> Flock.drumClassFor(voice as FlockVoice, macros)
        OPENSKY -> OpenSky.drumClassFor(voice as OpenSkyVoice, macros)
        OHM -> if ((macros["HOLD"] ?: 0f) >= .99f) DrumClass.LOOP else DrumClass.TONAL
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
        CAUSTIC -> CausticInstrument.drumClassFor(voice as CausticVoice, macros)
        LATTICE -> Lattice.drumClassFor(voice as LatticeVoice, macros)
        RELAY -> Relay.drumClassFor(voice as RelayVoice, macros)
        EMBOUCHURE -> Embouchure.drumClassFor(voice as EmbouchureVoice, macros)
        BELLOWS -> Bellows.drumClassFor(voice as BellowsVoice, macros)
        APSIS -> Apsis.drumClassFor(voice as ApsisVoice, macros)
        OVEN -> Oven.drumClassFor(voice as OvenVoice, macros)
        // All ORIGAMI voices are finite pitched notes, including STILL.
        ORIGAMI -> DrumClass.TONAL
        MERCURY -> Mercury.drumClassFor(voice as MercuryVoice, macros)
        FILAMENT -> Filament.drumClassFor(voice as FilamentVoice, macros)
        TIDAL -> Tidal.drumClassFor(voice as TidalVoice, macros)
        CHAMBER -> Chamber.drumClassFor(voice as ChamberVoice, macros)
        NACRE -> Nacre.drumClassFor(voice as NacreVoice, macros)
    }

    fun buildPatch(name: String, voice: Enum<*>, macros: Map<String, Float>): Patch = when (this) {
        THUMP -> ThumpPatch(name, voice as ThumpVoice, macros)
        SCANNED_CHAIN -> ScannedChainPatch(name, voice as ScannedChainVoice, macros)
        SKIN -> SkinPatch(name, voice as SkinVoice, macros)
        ONSET -> OnsetPatch(name, voice as OnsetVoice, macros)
        TINES -> TinesPatch(name, voice as TinesVoice, macros)
        VELVET -> VelvetPatch(name, voice as VelvetVoice, macros)
        VOX -> VoxPatch(name, voice as VoxVoice, macros)
        PLUCK -> PluckPatch(name, voice as PluckVoice, macros, velocity = if (voice == PluckVoice.UPRIGHT) .65f else 1f)
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
        AEROSTAT -> AerostatPatch(name, voice as AerostatVoice, macros)
        TREMOR -> TremorPatch(name, voice as TremorVoice, macros)
        BALLAST -> BallastPatch(name, voice as BallastVoice, macros)
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
        TREFOIL -> TrefoilPatch(name, voice as TrefoilVoice, macros)
        OSPREY -> OspreyPatch(name, voice as OspreyVoice, macros)
        FLOCK -> FlockPatch(name, voice as FlockVoice, macros)
        OPENSKY -> OpenSkyPresets.forVoice(voice as OpenSkyVoice).first().copy(name = name, macros = macros)
        OHM -> OhmPatch(name, macros)
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
        CAUSTIC -> CausticPatch(name, voice as CausticVoice, macros)
        LATTICE -> LatticePatch(name, voice as LatticeVoice, macros)
        RELAY -> RelayPatch(name, voice as RelayVoice, macros)
        EMBOUCHURE -> EmbouchurePatch(name, voice as EmbouchureVoice, macros)
        BELLOWS -> BellowsPatch(name, voice as BellowsVoice, macros)
        APSIS -> ApsisPatch(name, voice as ApsisVoice, macros)
        OVEN -> OvenPatch(name, voice as OvenVoice, macros)
        ORIGAMI -> OrigamiPatch(name, voice as OrigamiVoice, macros)
        MERCURY -> MercuryPatch(name, voice as MercuryVoice, macros)
        FILAMENT -> FilamentPatch(name, voice as FilamentVoice, macros)
        TIDAL -> TidalPatch(name, voice as TidalVoice, macros)
        CHAMBER -> ChamberPatch(name, voice as ChamberVoice, macros)
        NACRE -> NacrePatch(name, voice as NacreVoice, macros)
    }

    /** A saved patch's human name — "Hat Closed Thump", "Bell Tines". */
    fun patchDisplayName(voice: Enum<*>): String {
        val labelledVoice = if (voice is BallastVoice) voice.displayName else voice.name
        val voiceName = labelledVoice.split('_').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        val engineName = displayName.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar { letter -> letter.uppercase() } }
        return "$voiceName $engineName"
    }
}

/** Starting recipes supplied directly by a provisional engine remain available in MAKE. */
private fun factoryPresets(engine: Engine, voice: Enum<*>): List<Patch> =
    if (engine == Engine.NACRE) NacrePresets.forVoice(voice as NacreVoice)
    else Presets.forVoice(engine.name, voice.name)

// ---------- voice ↔ drum class ----------
//
// One mapping per engine, all named in this one place, per the brief.
// THUMP's COWBELL/RIM and provisional SPARK have a nominal PERC label here.
// Exported SPARK families classify each render through ThumpFamilies.
// TINES splits by how
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
        ThumpVoice.SPARK -> DrumClass.PERC
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
    Engine.BALLAST -> (voice as BallastVoice).displayName
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
    ThumpVoice.SPARK -> "SPARK"
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
                    key(v) {
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
    // A new voice must not reuse the previous voice's chip nodes. Positional
    // reuse kept the old spoken names, so a later voice's preset (PRISM BASS
    // "Hollow Rise" after the KEY openings) was absent from the tree.
    val presets = factoryPresets(engine, voice)
    key(engine, voice) {
        PresetStrip(engine, voice, presets, current, scheme, label = null, spoken = "PRESET", onSelect = onSelect)
    }
    // The player's own, under the factory row (docs/WORKSHOP.md, WS5):
    // a second strip that exists only once this voice has one, with the
    // same chips and the same highlight rule, and YOURS in the slider
    // label column below it so the two rows read as two rows. A hold on
    // one of these asks to forget it; the factory's chips have no hold.
    key(engine, voice, "YOURS") {
        PresetStrip(engine, voice, yours, current, scheme, label = "YOURS", spoken = "YOUR PRESET", onSelect = onSelect, onHold = onHold)
    }
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
                key(p.name) {
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
                        val voiceLabel = (patch as? BallastPatch)?.voice?.displayName ?: patch?.voiceName?.replace('_', ' ')
                        TapeText("${patch?.engine} · $voiceLabel", TapeType.lcdSmall, scheme.amber.tape)
                    }
                }
            }
        }
    }
}

// ---------- macro slider ----------

/** A saved excitation strength, separate from the four UPRIGHT timbre controls. */
@Composable
private fun UprightVelocityControl(
    patch: PluckPatch,
    fillColor: Color,
    scheme: Scheme,
    onValueChange: (Float) -> Unit,
) {
    MacroSlider(label = "VELOCITY", value = patch.velocity, fillColor = fillColor,
        scheme = scheme, onValueChange = onValueChange)
}

/** Change the playing gesture while retaining the authored room, model and exact seed. */
@Composable
private fun ChamberNoteControls(
    patch: ChamberPatch,
    fillColor: Color,
    scheme: Scheme,
    onPatchChange: (ChamberPatch) -> Unit,
) {
    MacroSlider(
        label = "ROOT",
        value = (patch.midi - Chamber.MIDI_MIN).toFloat() / (Chamber.MIDI_MAX - Chamber.MIDI_MIN),
        fillColor = fillColor,
        scheme = scheme,
        readout = Scales.nameOf(patch.midi),
        onValueChange = { value ->
            onPatchChange(patch.copy(midi = (Chamber.MIDI_MIN + value *
                (Chamber.MIDI_MAX - Chamber.MIDI_MIN)).roundToInt().coerceIn(Chamber.MIDI_MIN, Chamber.MIDI_MAX)))
        },
    )
    MacroSlider(
        label = "VELOCITY",
        value = patch.velocity,
        fillColor = fillColor,
        scheme = scheme,
        onValueChange = { onPatchChange(patch.copy(velocity = it.coerceIn(0f, 1f))) },
    )
    TapeText(
        "FINITE STRIKE · ROOM TAIL INCLUDED. SPREAD TRANSPOSES THE ROOM TOO.",
        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 3,
    )
}

/** Root and strike strength travel with the complete finite harp recipe. */
@Composable
private fun NacreNoteControls(
    patch: NacrePatch,
    fillColor: Color,
    scheme: Scheme,
    onRootChange: (Int) -> Unit,
    onVelocityChange: (Float) -> Unit,
) {
    MacroSlider(
        label = "ROOT",
        value = (patch.midi - Nacre.MIN_MIDI).toFloat() / (Nacre.MAX_MIDI - Nacre.MIN_MIDI),
        fillColor = fillColor,
        scheme = scheme,
        readout = Scales.nameOf(patch.midi),
        onValueChange = { value ->
            onRootChange((Nacre.MIN_MIDI + value * (Nacre.MAX_MIDI - Nacre.MIN_MIDI)).roundToInt()
                .coerceIn(Nacre.MIN_MIDI, Nacre.MAX_MIDI))
        },
    )
    MacroSlider(
        label = "VELOCITY", value = patch.velocity, fillColor = fillColor, scheme = scheme,
        onValueChange = { onVelocityChange(it.coerceIn(0f, 1f)) },
    )
    TapeText(
        "PAIRED HARP · FINITE STRUM · SPACE FOLLOWS THE NOTE.",
        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
    )
}

/** Note controls stay outside the large screen body and retain the whole native recipe. */
@Composable
private fun CausticNoteControls(
    patch: CausticPatch,
    fillColor: Color,
    scheme: Scheme,
    onRootChange: (Int) -> Unit,
    onVelocityChange: (Float) -> Unit,
) {
    MacroSlider(
        label = "ROOT",
        value = (patch.midi - CausticInstrument.MIDI_MIN).toFloat() /
            (CausticInstrument.MIDI_MAX - CausticInstrument.MIDI_MIN),
        fillColor = fillColor,
        scheme = scheme,
        readout = Scales.nameOf(patch.midi),
        onValueChange = { value ->
            onRootChange((CausticInstrument.MIDI_MIN + value *
                (CausticInstrument.MIDI_MAX - CausticInstrument.MIDI_MIN)).roundToInt()
                .coerceIn(CausticInstrument.MIDI_MIN, CausticInstrument.MIDI_MAX))
        },
    )
    MacroSlider(
        label = "VELOCITY",
        value = patch.velocity,
        fillColor = fillColor,
        scheme = scheme,
        onValueChange = { onVelocityChange(it.coerceIn(0f, 1f)) },
    )
    TapeText(
        "FINITE NOTE · LENGTH SETS DECAY · HOLD RESERVED.",
        TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2,
    )
}

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
    semanticRange: (() -> ClosedFloatingPointRange<Float>)? = null,
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentSemanticRange by rememberUpdatedState(semanticRange)
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
                    if (enabled) setProgress { target ->
                        // PRISM can change OPEN/REPEAT before this node recomposes. Its
                        // action validates against that live mode; other sliders keep
                        // their captured range, and pointer geometry stays unchanged.
                        currentOnChange(target.coerceIn(currentSemanticRange?.invoke() ?: valueRange))
                        true
                    }
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

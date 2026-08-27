package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad

/**
 * One velocity zone of a pad: which WAV answers which MIDI velocities.
 * Zones ascend soft → hard without overlap; the hardware does the rest.
 */
data class KitLayer(
    val sampleFile: String,
    val velStart: Int,
    val velEnd: Int,
) {
    init {
        require(sampleFile.isNotBlank()) { "layer sampleFile must not be blank" }
        require('/' !in sampleFile && '\\' !in sampleFile) { "layer sampleFile must be a bare filename" }
        require(velStart in 0..127 && velEnd in 0..127 && velStart <= velEnd) {
            "bad velocity window $velStart..$velEnd"
        }
    }

    val sampleStem: String get() = sampleFile.substringBeforeLast('.')
}

/**
 * One pad's worth of a kit.
 *
 * [sampleFile] is a bare filename resolved against the kit's own folder — a
 * kit *is* a directory, and everything it references lives inside it. That's
 * what keeps export a copy-and-render instead of a gather-and-convert.
 */
data class KitPad(
    /** 1-based pad slot; 1..16 is bank A. */
    val slot: Int,
    val sampleFile: String,
    val displayName: String = sampleFile.substringBeforeLast('.'),
    val drumClass: DrumClass = DrumClass.UNKNOWN,
    /** TapeOS class colour as #rrggbb; null = undecided. */
    val colorHex: String? = null,
    val level: Float = 0.707946f,
    val pan: Float = 0.5f,
    val tuneCoarse: Int = 0,
    val tuneFine: Int = 0,
    val muteGroup: Int = 0,
    val oneShot: Boolean = true,
    /** Provenance, freeform: where the snip came from ("app", "title", "at"). */
    val source: Map<String, String> = emptyMap(),
    /**
     * The pad's recipe — how to regenerate or re-treat its WAV — as an
     * opaque JSON object round-tripped verbatim. The kit layer stores it
     * next to the sample so the sound stays editable forever; *reading* it
     * (synth patch, FX chain) is the `:synth` layer's business, which is
     * what keeps this module ignorant of engines.
     */
    val recipe: JsonValue.Obj? = null,
    /**
     * Velocity zones, soft first, when the pad is velocity-layered. Empty =
     * the classic single-sample pad. When set, [sampleFile] is the loudest
     * zone's file (so single-sample consumers still hear the right thing).
     */
    val velocityLayers: List<KitLayer> = emptyList(),
    /**
     * Pad shape as metadata — the hardware renders it, the audio on disk
     * stays pristine, and undo is setting it back to null (the format's
     * own default). All 0..1: [attack]/[decay] on the volume envelope,
     * [cutoff]/[resonance] on the pad's filter.
     */
    val attack: Float? = null,
    val decay: Float? = null,
    val cutoff: Float? = null,
    val resonance: Float? = null,
    /**
     * Per-hit randomization 0..1 — subtle pitch/volume/pan variation the
     * MPC 3 renders on every hit (no two alike). MPC 2 exports have no
     * such fields and honestly ignore it.
     */
    val humanize: Float? = null,
) {
    init {
        require(slot in 1..128) { "slot out of range: $slot" }
        require(sampleFile.isNotBlank()) { "sampleFile must not be blank" }
        require('/' !in sampleFile && '\\' !in sampleFile) {
            "sampleFile must be a bare filename inside the kit folder: $sampleFile"
        }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(level in 0f..1f) { "level out of range: $level" }
        require(pan in 0f..1f) { "pan out of range: $pan" }
        require(tuneCoarse in -36..36) { "tuneCoarse out of range: $tuneCoarse" }
        require(tuneFine in -100..100) { "tuneFine out of range: $tuneFine" }
        require(muteGroup in 0..32) { "muteGroup out of range: $muteGroup" }
        colorHex?.let {
            require(Regex("^#[0-9a-fA-F]{6}$").matches(it)) { "colorHex must be #rrggbb: $it" }
        }
        for ((name, v) in listOf(
            "attack" to attack, "decay" to decay, "cutoff" to cutoff,
            "resonance" to resonance, "humanize" to humanize,
        )) {
            v?.let { require(it in 0f..1f) { "$name out of range: $it" } }
        }
        if (velocityLayers.isNotEmpty()) {
            require(velocityLayers.size in 1..4) { "a pad has 1..4 velocity layers" }
            for (i in 1 until velocityLayers.size) {
                require(velocityLayers[i].velStart > velocityLayers[i - 1].velEnd) {
                    "velocity zones must ascend without overlap"
                }
            }
            require(velocityLayers.last().sampleFile == sampleFile) {
                "sampleFile must be the loudest zone's file when layered"
            }
        }
    }

    /** Filename without extension — what the MPC program refers to. */
    val sampleStem: String get() = sampleFile.substringBeforeLast('.')
}

/**
 * The wear ledger — the kit as a living tape. Plays and saves accrue
 * [mileage], and the kit's *rendered* sound ages by it. The curve is
 * patina physics: `w = 1 − exp(−mileage/K)` — fast at first, asymptotic
 * at well-worn, never ruined. Only the ledger is stored; [w] is derived,
 * never written down. And because wear is a render-time recipe over
 * pristine WAVs (no audio is ever rewritten), wiping the ledger *is* a
 * new tape.
 */
data class WearLedger(
    val mileage: Double = 0.0,
    val enabled: Boolean = true,
    /** Mileage at which the tape is ~63% worn. */
    val k: Double = DEFAULT_K,
) {
    init {
        require(mileage.isFinite() && mileage >= 0.0) { "mileage out of range: $mileage" }
        require(k.isFinite() && k > 0.0) { "K must be positive: $k" }
    }

    /** Earned wear in [0, 1) — the curve saturates, it never arrives. */
    val w: Float get() = (1.0 - Math.exp(-mileage / k)).toFloat().coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_K = 250.0
    }
}

/**
 * A kit: a name and up to 128 filled pad slots.
 *
 * On disk a kit is a folder holding the WAVs plus a `kit.json` sidecar (see
 * [KitStore]); this class is the in-memory shape of that folder.
 */
data class Kit(
    val name: String,
    val pads: List<KitPad>,
    /**
     * The kit's musical key, when one has been chosen — what in-key
     * retuning targets and the keys-on-pads layouts root on. Null means
     * no key: nothing is ever "corrected" against a key nobody set.
     */
    val key: KeySpec? = null,
    /**
     * The source material's tempo, when detection was confident. Project
     * exports use it as the master tempo — which also buys warping for
     * free, since the MPC stretches correctly when the metadata is right.
     */
    val tempoBpm: Float? = null,
    /**
     * The tape's wear ledger, when the kit has opted into aging. Null
     * means a kit that doesn't wear — the default, and every kit made
     * before the ledger existed.
     */
    val wear: WearLedger? = null,
) {
    init {
        tempoBpm?.let { require(it > 0f && it < 1000f) { "tempoBpm out of range: $it" } }
        require(name.isNotBlank()) { "kit name must not be blank" }
        require(pads.map { it.slot }.toSet().size == pads.size) {
            "duplicate pad slots: ${pads.groupBy { it.slot }.filterValues { it.size > 1 }.keys}"
        }
    }

    fun pad(slot: Int): KitPad? = pads.firstOrNull { it.slot == slot }

    val highestSlot: Int get() = pads.maxOfOrNull { it.slot } ?: 0

    /**
     * Render as an MPC drum program, given each pad's sample length in frames
     * (from [com.snipsnap.xpm.WavInfo] — getting it wrong truncates the pad).
     */
    fun toDrumProgram(frameCountOf: (KitPad) -> Long): DrumProgram {
        val slots = arrayOfNulls<Pad>(highestSlot)
        for (p in pads) {
            slots[p.slot - 1] = Pad(
                sampleName = p.sampleStem,
                frameCount = frameCountOf(p),
                level = p.level,
                pan = p.pan,
                tuneCoarse = p.tuneCoarse,
                tuneFine = p.tuneFine,
                muteGroup = p.muteGroup,
                oneShot = p.oneShot,
                attack = p.attack,
                decay = p.decay,
                cutoff = p.cutoff,
                resonance = p.resonance,
                humanize = p.humanize,
            )
        }
        return DrumProgram(name, slots.toList())
    }
}

/** Filename rules for things that must survive a FAT SD card and an MPC browser. */
object Names {

    private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** True when [name] can be a folder/file stem on the MPC's card as-is. */
    fun isMpcSafe(name: String): Boolean =
        name.isNotBlank() &&
            name.none { it.code < 32 || it.code > 126 || it in FORBIDDEN } &&
            !name.endsWith('.') && !name.endsWith(' ') && !name.startsWith(' ')

    /**
     * Force a sample stem into MPC-safe shape: bad characters become `_`,
     * runs collapse, and an empty result falls back to "Sample".
     */
    fun sanitizeStem(stem: String): String {
        val cleaned = stem.map { c ->
            if (c.code in 32..126 && c !in FORBIDDEN) c else '_'
        }.joinToString("")
            .replace(Regex("_+"), "_")
            .trim(' ', '_', '.')
        return cleaned.ifBlank { "Sample" }
    }
}

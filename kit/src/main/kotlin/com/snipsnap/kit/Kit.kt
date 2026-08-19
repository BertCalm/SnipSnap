package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad

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
    }

    /** Filename without extension — what the MPC program refers to. */
    val sampleStem: String get() = sampleFile.substringBeforeLast('.')
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
) {
    init {
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

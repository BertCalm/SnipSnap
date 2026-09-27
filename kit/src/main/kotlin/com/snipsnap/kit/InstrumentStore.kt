package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.KeygroupProgram
import java.io.File

/**
 * The phone's own reading of an instrument: a sidecar
 * `<name>.instrument.json` written beside every keygroup package the
 * shop exports (`OneNote.writePackage` — MAKE INSTRUMENT, MAKE PAD, the
 * `keys` and `pad` verbs), naming each zone's note range, root, sample
 * file, length and loop start, and the program's release. The MPC reads
 * the `.xty` / `.xpm`; the phone reads this, so it can *play* what it
 * made without a keygroup parser of its own. Older packages without a
 * sidecar simply do not appear on the shelf's INSTRUMENTS list.
 */
object InstrumentStore {

    const val SUFFIX = ".instrument.json"
    const val VERSION = 1

    /**
     * The longest release a sidecar may ask for. Every writer in the shop
     * stays under two seconds; past this a release is a held note by another
     * name, and a hand-edited `1e39` (which reads as an infinite Float) would
     * leave a looped key sounding forever.
     */
    const val MAX_RELEASE_SECONDS = 30f

    /** One keygroup zone, as the player needs it. */
    data class Zone(
        val lowNote: Int,
        val highNote: Int,
        val rootNote: Int,
        /** The sample file, relative to the sidecar's folder. */
        val sample: String,
        val frameCount: Long,
        /** 0 = plays unlooped; otherwise the frame playback wraps back to (looping to the end). */
        val loopStartFrame: Long,
    )

    data class Instrument(
        val name: String,
        /** Seconds, the amp envelope's release once a note lets go. */
        val release: Float,
        val zones: List<Zone>,
    ) {
        /**
         * [release] as a player may use it: finite, not negative, at most
         * [MAX_RELEASE_SECONDS]. [read] already refuses worse, but an
         * instrument built in code never passed through it, and an infinite
         * fade is a key that never stops.
         */
        val playableRelease: Float
            get() = if (release.isFinite()) release.coerceIn(0f, MAX_RELEASE_SECONDS) else 0f

        /** The zone [note] falls in, or null when the instrument does not cover it. */
        fun zoneFor(note: Int): Zone? = zones.firstOrNull { note in it.lowNote..it.highNote }

        /** The lowest root the instrument has — where the grid opens. */
        val rootNote: Int get() = zones.minOf { it.rootNote }
    }

    fun sidecar(destRoot: File, name: String): File = File(destRoot, "$name$SUFFIX")

    /** Write the sidecar for [program], whose samples live under [dataDirName] beside it. */
    fun write(destRoot: File, name: String, program: KeygroupProgram, dataDirName: String) {
        val zones = program.keygroups.map { kg ->
            // The loudest layer is the one the phone plays: velocity zones are the hardware's.
            val layer = kg.layers.last()
            JsonValue.Obj(
                linkedMapOf<String, JsonValue>(
                    "low" to JsonValue.Num(kg.lowNote.toDouble()),
                    "high" to JsonValue.Num(kg.highNote.toDouble()),
                    "root" to JsonValue.Num(kg.rootNote.toDouble()),
                    "sample" to JsonValue.Str("$dataDirName/${layer.sampleName}.wav"),
                    "frames" to JsonValue.Num(layer.frameCount.toDouble()),
                    "loopStart" to JsonValue.Num(layer.loopStartFrame.toDouble()),
                ),
            )
        }
        val obj = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "name" to JsonValue.Str(program.name),
                "release" to JsonValue.Num(program.volumeRelease.toDouble()),
                "zones" to JsonValue.Arr(zones),
            ),
        )
        AtomicFile.writeText(sidecar(destRoot, name), Json.write(obj))
    }

    /**
     * Read a sidecar; throws [JsonException] on a shape it does not know - every v1 field is required, so a half-written file never reaches the shelf.
     *
     * And on values no writer here produces, since a sidecar is also a file a
     * person can edit: a release that is not a length of time (negative,
     * infinite), a zone outside MIDI or upside down, a sample with no frames,
     * a sample path that leaves the sidecar's own folder. A release longer
     * than [MAX_RELEASE_SECONDS] is capped rather than refused: it is a
     * strange instrument, not a broken one.
     */
    fun read(file: File): Instrument {
        val obj = Json.parse(file.readText()).obj()
        val version = obj["version"]?.int() ?: throw JsonException("instrument has no version")
        if (version != VERSION) throw JsonException("unsupported instrument version $version")
        val name = obj["name"]?.str() ?: throw JsonException("instrument has no name")
        val releaseRaw = obj["release"]?.num() ?: throw JsonException("instrument has no release")
        if (!releaseRaw.isFinite() || releaseRaw < 0.0 || !releaseRaw.toFloat().isFinite()) {
            throw JsonException("instrument release isn't a length of time: $releaseRaw")
        }
        val release = releaseRaw.toFloat().coerceAtMost(MAX_RELEASE_SECONDS)
        val folder = file.absoluteFile.parentFile
        val zones = (obj["zones"] as? JsonValue.Arr)?.items?.map { z ->
            val o = z.obj()
            val zone = Zone(
                lowNote = o["low"]?.int() ?: throw JsonException("zone has no low note"),
                highNote = o["high"]?.int() ?: throw JsonException("zone has no high note"),
                rootNote = o["root"]?.int() ?: throw JsonException("zone has no root"),
                sample = o["sample"]?.str() ?: throw JsonException("zone has no sample"),
                frameCount = o["frames"]?.long() ?: throw JsonException("zone has no frame count"),
                loopStartFrame = o["loopStart"]?.long() ?: throw JsonException("zone has no loop start"),
            )
            if (zone.lowNote !in MIDI || zone.highNote !in MIDI || zone.rootNote !in MIDI || zone.lowNote > zone.highNote) {
                throw JsonException("zone ${zone.lowNote}..${zone.highNote} (root ${zone.rootNote}) isn't a range of MIDI notes")
            }
            if (zone.frameCount <= 0) throw JsonException("zone sample has no frames: ${zone.frameCount}")
            if (zone.loopStartFrame < 0) throw JsonException("zone loop start is negative: ${zone.loopStartFrame}")
            if (!staysIn(folder, zone.sample)) throw JsonException("zone sample leaves the instrument's folder: '${zone.sample}'")
            zone
        } ?: throw JsonException("instrument has no zones")
        if (zones.isEmpty()) throw JsonException("instrument has no zones")
        return Instrument(name, release, zones)
    }

    private val MIDI = 0..127

    /**
     * Whether [sample] names a file under [folder]: relative, no `..`, and
     * still inside once resolved. Samples live in a subfolder beside the
     * sidecar (`Name_[TrackData]/Name_A1.wav`), so this cannot be
     * [SafePath.child]'s direct-child rule.
     */
    private fun staysIn(folder: File, sample: String): Boolean {
        if (sample.isBlank() || sample.startsWith("/") || sample.startsWith("\\") || sample.contains('\u0000')) return false
        if (File(sample).isAbsolute) return false
        if (sample.split('/', '\\').any { it == ".." }) return false
        val root = folder.canonicalFile
        val resolved = File(folder, sample).canonicalFile
        return generateSequence(resolved.parentFile) { it.parentFile }.any { it == root }
    }

    /** Every readable sidecar under [root], by name; a broken one is skipped, never fatal. */
    fun list(root: File): List<Pair<File, Instrument>> =
        root.listFiles { f: File -> f.isFile && f.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { f -> runCatching { f to read(f) }.getOrNull() }
            ?: emptyList()
}

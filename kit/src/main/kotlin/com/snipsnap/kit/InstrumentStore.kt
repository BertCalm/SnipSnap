package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.VoiceMode
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
    const val VERSION = 2

    /**
     * The longest release a sidecar may ask for. Every writer in the shop
     * stays under two seconds; past this a release is a held note by another
     * name, and a hand-edited `1e39` (which reads as an infinite Float) would
     * leave a looped key sounding forever.
     */
    const val MAX_RELEASE_SECONDS = 30f

    /** One sample selected by an inclusive MPC velocity window. */
    data class Layer(
        val sample: String,
        val frameCount: Long,
        val loopStartFrame: Long,
        val velStart: Int = 0,
        val velEnd: Int = 127,
    )

    /** One pitch zone; the headline sample remains its hardest layer for older callers. */
    data class Zone(
        val lowNote: Int,
        val highNote: Int,
        val rootNote: Int,
        /** The sample file, relative to the sidecar's folder. */
        val sample: String,
        val frameCount: Long,
        /** 0 = plays unlooped; otherwise the frame playback wraps back to (looping to the end). */
        val loopStartFrame: Long,
        /** Empty on a v1 sidecar or a legacy code-built zone: its headline sample covers all velocities. */
        val layers: List<Layer> = emptyList(),
    ) {
        val playableLayers: List<Layer>
            get() = layers.ifEmpty { listOf(Layer(sample, frameCount, loopStartFrame)) }

        /** Gaps stay silent, matching the exported MPC windows. */
        fun layerFor(midiVelocity: Int): Layer? = playableLayers.firstOrNull { midiVelocity in it.velStart..it.velEnd }
    }

    data class Instrument(
        val name: String,
        /** Seconds, the amp envelope's release once a note lets go. */
        val release: Float,
        val zones: List<Zone>,
        /** How the keys share the voice; a sidecar that does not say is polyphonic. */
        val voiceMode: VoiceMode = VoiceMode.POLY,
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

        /** Every layer must load, including soft samples that are not a zone's headline. */
        val sampleFiles: List<String> get() = zones.flatMap { it.playableLayers }.map { it.sample }.distinct()
    }

    /** The program this instrument was written from: one keygroup per zone, one layer per playable layer. */
    fun Instrument.toProgram(): KeygroupProgram = KeygroupProgram(
        name = name,
        keygroups = zones.map { zone ->
            Keygroup(
                zone.lowNote, zone.highNote, zone.rootNote,
                zone.playableLayers.map { l ->
                    VelocityLayer(
                        l.sample.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".wav"),
                        l.frameCount, l.velStart, l.velEnd, l.loopStartFrame,
                    )
                },
            )
        },
        volumeRelease = playableRelease,
        voiceMode = voiceMode,
    )

    fun sidecar(destRoot: File, name: String): File = File(destRoot, "$name$SUFFIX")

    /** Write the sidecar for [program], whose samples live under [dataDirName] beside it. */
    fun write(destRoot: File, name: String, program: KeygroupProgram, dataDirName: String) {
        val zones = program.keygroups.map { kg ->
            val layer = kg.layers.last()
            JsonValue.Obj(
                linkedMapOf<String, JsonValue>(
                    "low" to JsonValue.Num(kg.lowNote.toDouble()),
                    "high" to JsonValue.Num(kg.highNote.toDouble()),
                    "root" to JsonValue.Num(kg.rootNote.toDouble()),
                    "sample" to JsonValue.Str("$dataDirName/${layer.sampleName}.wav"),
                    "frames" to JsonValue.Num(layer.frameCount.toDouble()),
                    "loopStart" to JsonValue.Num(layer.loopStartFrame.toDouble()),
                    "layers" to JsonValue.Arr(kg.layers.map { l ->
                        JsonValue.Obj(linkedMapOf(
                            "sample" to JsonValue.Str("$dataDirName/${l.sampleName}.wav"),
                            "frames" to JsonValue.Num(l.frameCount.toDouble()),
                            "loopStart" to JsonValue.Num(l.loopStartFrame.toDouble()),
                            "velStart" to JsonValue.Num(l.velStart.toDouble()),
                            "velEnd" to JsonValue.Num(l.velEnd.toDouble()),
                        ))
                    }),
                ),
            )
        }
        val obj = JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "name" to JsonValue.Str(program.name),
                "release" to JsonValue.Num(program.volumeRelease.toDouble()),
                "voiceMode" to JsonValue.Str(program.voiceMode.name),
                "zones" to JsonValue.Arr(zones),
            ),
        )
        AtomicFile.writeText(sidecar(destRoot, name), Json.write(obj))
    }

    /**
     * Read v1 single-sample or v2 velocity-layer sidecars. Every field in the selected
     * version is required, so an interrupted write cannot silently lose its soft layers.
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
        if (version != 1 && version != VERSION) throw JsonException("unsupported instrument version $version")
        val name = obj["name"]?.str() ?: throw JsonException("instrument has no name")
        val releaseRaw = obj["release"]?.num() ?: throw JsonException("instrument has no release")
        if (!releaseRaw.isFinite() || releaseRaw < 0.0 || !releaseRaw.toFloat().isFinite()) {
            throw JsonException("instrument release isn't a length of time: $releaseRaw")
        }
        val release = releaseRaw.toFloat().coerceAtMost(MAX_RELEASE_SECONDS)
        val voiceMode = VoiceMode.parse(obj["voiceMode"]?.str())
        val folder = file.absoluteFile.parentFile
        val zones = (obj["zones"] as? JsonValue.Arr)?.items?.map { z ->
            val o = z.obj()
            val layers = if (version == 1) emptyList() else {
                val values = (o["layers"] as? JsonValue.Arr)?.items ?: throw JsonException("zone has no velocity layers")
                if (values.size !in 1..8) throw JsonException("a keygroup zone has 1..8 velocity layers")
                values.map { value ->
                    val l = value.obj()
                    val layer = Layer(
                        sample = l["sample"]?.str() ?: throw JsonException("layer has no sample"),
                        frameCount = l["frames"]?.long() ?: throw JsonException("layer has no frame count"),
                        loopStartFrame = l["loopStart"]?.long() ?: throw JsonException("layer has no loop start"),
                        velStart = l["velStart"]?.int() ?: throw JsonException("layer has no velocity start"),
                        velEnd = l["velEnd"]?.int() ?: throw JsonException("layer has no velocity end"),
                    )
                    if (layer.velStart !in MIDI || layer.velEnd !in MIDI || layer.velStart > layer.velEnd) {
                        throw JsonException("invalid layer velocity window ${layer.velStart}..${layer.velEnd}")
                    }
                    validateSample(folder, layer.sample, layer.frameCount, layer.loopStartFrame)
                    layer
                }.also { ordered ->
                    for (i in 1 until ordered.size) if (ordered[i].velStart <= ordered[i - 1].velEnd) {
                        throw JsonException("velocity layers must ascend without overlap")
                    }
                }
            }
            val zone = Zone(
                lowNote = o["low"]?.int() ?: throw JsonException("zone has no low note"),
                highNote = o["high"]?.int() ?: throw JsonException("zone has no high note"),
                rootNote = o["root"]?.int() ?: throw JsonException("zone has no root"),
                sample = o["sample"]?.str() ?: throw JsonException("zone has no sample"),
                frameCount = o["frames"]?.long() ?: throw JsonException("zone has no frame count"),
                loopStartFrame = o["loopStart"]?.long() ?: throw JsonException("zone has no loop start"),
                layers = layers,
            )
            if (zone.lowNote !in MIDI || zone.highNote !in MIDI || zone.rootNote !in MIDI || zone.lowNote > zone.highNote) {
                throw JsonException("zone ${zone.lowNote}..${zone.highNote} (root ${zone.rootNote}) isn't a range of MIDI notes")
            }
            validateSample(folder, zone.sample, zone.frameCount, zone.loopStartFrame)
            if (layers.isNotEmpty()) {
                val hard = layers.last()
                if (zone.sample != hard.sample || zone.frameCount != hard.frameCount || zone.loopStartFrame != hard.loopStartFrame) {
                    throw JsonException("zone headline must match its hardest velocity layer")
                }
            }
            zone
        } ?: throw JsonException("instrument has no zones")
        if (zones.isEmpty()) throw JsonException("instrument has no zones")
        return Instrument(name, release, zones, voiceMode)
    }

    private val MIDI = 0..127

    private fun validateSample(folder: File, sample: String, frames: Long, loopStart: Long) {
        if (frames <= 0) throw JsonException("sample has no frames: $frames")
        if (loopStart < 0 || loopStart >= frames) throw JsonException("loop start $loopStart is outside $frames frames")
        if (!staysIn(folder, sample)) throw JsonException("sample leaves the instrument's folder: '$sample'")
    }

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

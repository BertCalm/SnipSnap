package com.snipsnap.mpc3

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.KeygroupProgram
import java.io.File

/**
 * One content track of a project: a drum kit (optionally carrying a clip
 * that also becomes the project sequence) or a keygroup instrument.
 */
sealed interface Mpc3ProjectTrack {
    data class Drum(
        val program: DrumProgram,
        val colour: Int = Mpc3TrackWriter.DEFAULT_TRACK_COLOUR,
        val clip: Mpc3Clip? = null,
    ) : Mpc3ProjectTrack

    data class Keys(
        val program: KeygroupProgram,
        val colour: Int = Mpc3TrackWriter.DEFAULT_TRACK_COLOUR,
    ) : Mpc3ProjectTrack
}

/**
 * Writes a whole session as one MPC 3 project — the `.xpj` a Live III opens
 * directly: kit, instruments, mixer and sequence together, beside a flat
 * `<name>_[ProjectData]/` folder of WAVs.
 *
 * **How it's built:** a project's `tracks[]` elements are byte-shaped like
 * standalone track files' `data` — the format doc's "hoisted" relationship —
 * so the content tracks come straight from [Mpc3TrackWriter]'s builders.
 * Everything *around* them (the mixer, pad-perform settings, QLink
 * assignments, the 32 empty song slots — some sixty top-level keys) is the
 * harvested DD1 Chamber project's own boilerplate, carried verbatim as a
 * resource skeleton with the content-specific parts scrubbed. Verbatim
 * beats reconstruction: the one field the community write-up was mocked for
 * ("schema version 28 does not exist") turns out to be real at project
 * level, and the skeleton simply carries it.
 *
 * Beside the content tracks the writer adds the mixer-infrastructure
 * tracks every harvested project carries: `Submix 1` and the `Out` pairs.
 * The first drum track's clip also becomes `sequences[0]` — the project
 * opens with the groove on the timeline, not just parked in a clip slot.
 *
 * Output is deterministic. Same guard as every MPC 3 writer: no key path
 * absent from the real project.
 */
class Mpc3ProjectWriter(
    private val firmware: String = "3.7.0.56",
    private val platform: String = "Linux",
) {

    private val trackWriter = Mpc3TrackWriter(firmware, platform)

    fun write(name: String, tracks: List<Mpc3ProjectTrack>, tempoBpm: Float = 92f): ByteArray =
        Acvs.write(
            AcvsHeader(firmware, Mpc3Project.PROJECT_OBJECT_TYPE, AcvsHeader.ENCODING_JSON, platform),
            payloadText(name, tracks, tempoBpm),
        )

    /** Write `<name>.xpj` into [directory]; WAVs go in the sibling `_[ProjectData]/`. */
    fun writeTo(directory: File, name: String, tracks: List<Mpc3ProjectTrack>, tempoBpm: Float = 92f): File {
        require(directory.isDirectory) { "not a directory: $directory" }
        val file = File(directory, "$name.xpj")
        file.writeBytes(write(name, tracks, tempoBpm))
        return file
    }

    fun payloadText(name: String, tracks: List<Mpc3ProjectTrack>, tempoBpm: Float = 92f): String {
        require(tracks.isNotEmpty()) { "a project needs at least one content track" }
        require(tempoBpm in 30f..300f) { "tempo out of range: $tempoBpm" }

        // In a project the pattern lives on the sequence timeline, not in the
        // track's own clip slots — the harvested project keeps every track's
        // sharedClipMap empty and its notes in sequences[0].trackClipMaps.
        val trackObjs = tracks.map {
            when (it) {
                is Mpc3ProjectTrack.Drum -> trackWriter.drumTrackObject(it.program, it.colour, clip = null, includeSolo = false)
                is Mpc3ProjectTrack.Keys -> trackWriter.keygroupTrackObject(it.program, it.colour, includeSolo = false)
            }
        } + listOf(
            trackWriter.infrastructureTrackObject("Submix 1", type = 8),
            trackWriter.infrastructureTrackObject("Out 1/2", type = 9),
            trackWriter.infrastructureTrackObject("Out 3/4", type = 9),
        )

        // Project-level pool: every track's samples, deduplicated, in order.
        val sampleNames = LinkedHashSet<String>()
        for (track in tracks) {
            when (track) {
                is Mpc3ProjectTrack.Drum -> track.program.pads.forEach { pad ->
                    pad ?: return@forEach
                    (pad.velocityLayers ?: listOf(null)).forEach { layer ->
                        sampleNames.add(layer?.sampleName ?: pad.sampleName)
                    }
                }
                is Mpc3ProjectTrack.Keys -> track.program.keygroups.forEach { kg ->
                    kg.layers.forEach { sampleNames.add(it.sampleName) }
                }
            }
        }
        val samples = J.A(
            sampleNames.map { name ->
                obj("name" to s(name), "path" to s("$name.wav"), "loadImpl" to i(0))
            },
        )

        // The first drum clip becomes the project sequence, its notes in
        // trackClipMaps keyed by the track's name — where DD1 keeps its own.
        val firstClip = tracks.filterIsInstance<Mpc3ProjectTrack.Drum>().firstNotNullOfOrNull { drum ->
            drum.clip?.let { drum.program.name to it }
        }
        val allTrackNames = tracks.map {
            when (it) {
                is Mpc3ProjectTrack.Drum -> it.program.name
                is Mpc3ProjectTrack.Keys -> it.program.name
            }
        } + listOf("Submix 1", "Out 1/2", "Out 3/4")
        val sequences = J.A(listOf(sequence(allTrackNames, firstClip, tempoBpm)))

        var text = skeleton
        text = text.replace("@TRACKS@", renderAt(J.A(trackObjs), SPLICE_INDENT).trimStart())
        text = text.replace("@SAMPLES@", renderAt(samples, SPLICE_INDENT).trimStart())
        text = text.replace("@SEQUENCES@", renderAt(sequences, SPLICE_INDENT).trimStart())
        text = text.replace("@MASTER_TEMPO@", tempoBpm.toDouble().toString())
        return text
    }

    private fun sequence(trackNames: List<String>, clipByTrack: Pair<String, Mpc3Clip>?, tempoBpm: Float): J {
        val bars = clipByTrack?.second?.bars ?: 2
        val pulses = bars * Mpc3Clip.PULSES_PER_BAR
        return obj(
            "key" to i(0),
            "value" to obj(
                "version" to i(5),
                "name" to s(clipByTrack?.second?.name ?: "Sequence 01"),
                "bpm" to d(tempoBpm.toDouble()),
                "lengthBars" to i(bars.toLong()),
                "loopStartBar" to i(0),
                "loopEndBar" to i(bars.toLong()),
                "loop" to b(true),
                "tempoEnable" to b(true),
                "transposition" to i(0),
                "smpteStart" to obj(
                    "hours" to i(0), "mins" to i(0), "secs" to i(0), "frames" to i(0), "subframes" to i(0),
                ),
                "timeSignatureTrack" to obj(
                    "timeSignatures" to J.A(
                        listOf(obj("beatsPerBar" to i(4), "beatLength" to i(960), "barStart" to i(0))),
                    ),
                ),
                // One clip row mapping every track by name, as DD1 does —
                // the groove on the content track, empty clips elsewhere.
                "trackClipMaps" to J.A(
                    listOf(
                        J.A(
                            trackNames.map { trackName ->
                                val clip = clipByTrack?.takeIf { it.first == trackName }?.second
                                    ?: Mpc3Clip(trackName, bars, emptyList())
                                obj("key" to s(trackName), "value" to trackWriter.clipValue(clip, includeMidiBank = false))
                            },
                        ),
                    ),
                ),
                "seqEventList" to obj(
                    "length" to i(Long.MAX_VALUE),
                    "events" to J.A(emptyList()),
                    "version" to i(2),
                    "quantisation" to obj(
                        "version" to i(1), "pulses" to i(240), "swing" to d(0.0), "strength" to d(1.0),
                    ),
                    "numFilterTypes" to i(30),
                ),
                "locators" to obj(
                    "version" to i(1),
                    "names" to J.A(List(6) { s("") }),
                    "positions" to J.A(
                        List(6) { obj("bar" to i(-1), "beat" to i(-1), "pulse" to i(-1)) },
                    ),
                    "colours" to J.A(List(6) { i(0) }),
                ),
                "lengthPulses" to i(pulses),
                "loopStartPulses" to i(0),
                "loopEndPulses" to i(pulses),
                "autoSelectTrackIndex" to i(-1),
            ),
        )
    }

    companion object {
        /** Sibling folder name for a project's WAVs, per the corpus convention. */
        fun projectDataDirName(projectName: String): String = "${projectName}_[ProjectData]"

        /** Splice depth of the placeholder values: data's children sit two pad levels in. */
        private const val SPLICE_INDENT = 2

        private val skeleton: String by lazy {
            Mpc3ProjectWriter::class.java.getResourceAsStream("/mpc3/project-skeleton.json")!!
                .readBytes().toString(Charsets.UTF_8)
        }
    }
}

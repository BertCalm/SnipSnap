package com.snipsnap.mpc3

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * One note of an embedded pattern clip. [note] is a MIDI note — for a drum
 * track, pad A0N plays note 36+N-1 under the writer's chromatic map.
 * Velocity is MPC 3's normalised float, not a 0..127 int.
 */
data class Mpc3Note(
    val note: Int,
    val timePulses: Long,
    val velocity: Float,
    val lengthPulses: Long = 240L,
) {
    init {
        require(note in 0..127) { "note out of range: $note" }
        require(timePulses >= 0) { "timePulses must not be negative" }
        require(velocity in 0f..1f) { "velocity out of range: $velocity" }
        require(lengthPulses > 0) { "lengthPulses must be positive" }
    }
}

/**
 * A pattern clip carried inside the track — MPC 3's `sharedClipMap`, the
 * thing the MPC 2 generation simply cannot do: the kit arrives with a
 * groove ready to play. 960 PPQ, so a 16th step is 240 pulses and a 4/4
 * bar is [PULSES_PER_BAR].
 */
data class Mpc3Clip(
    val name: String,
    val bars: Int,
    val notes: List<Mpc3Note>,
) {
    init {
        require(name.isNotBlank()) { "clip name must not be blank" }
        require(bars in 1..64) { "bars out of range: $bars" }
        notes.forEach {
            require(it.timePulses < bars * PULSES_PER_BAR) { "note at ${it.timePulses} falls outside $bars bars" }
        }
    }

    companion object {
        /** 960 PPQ × 4 quarters. */
        const val PULSES_PER_BAR: Long = 3840L
        const val PULSES_PER_16TH: Long = 240L
    }
}

/**
 * Renders a [DrumProgram] as an MPC 3 native standalone drum track — the
 * `.xtd` container the Live III's own generation writes and reads.
 *
 * **Provenance:** templated field-for-field from the commercial `.xtd` files
 * in `reference/golden/mpc3-track/` (see docs/MPC3_FORMAT.md), primarily the
 * `0.1.0.99x` build-family shape — the most common across the corpus — with
 * every default value copied from a real file rather than invented. The
 * things the corpus proved load-bearing are all here:
 *
 * - all 128 instrument slots fully formed, empty ones with the corpus'
 *   empty-pad encoding (`sampleName ""`, `sliceIndex 128`, `sliceInfo.End 0`);
 * - 8 `layersv` slots per instrument; velocity zones written **loudest
 *   first**, descending, tiling 0..127 — the MPC 3 convention (our
 *   [Pad.velocityLayers] are soft-first, so the order flips here);
 * - the sample named twice per layer — bare `sampleName` and `sampleFile`
 *   with extension — both matching the track-level `samples[]` pool 1:1;
 * - length in `sliceInfo.End`; `sampleEnd` stays 0 (it is ignored — the
 *   worst field in the format to trust);
 * - the 0-based `padNoteMap` (`value0` = pad A01 = note 36, chromatic with
 *   wraparound — MPC 3 dropped MPC 2's shuffled classic table);
 * - `poliphony` misspelled at `program.drum`, `polyphony` spelled correctly
 *   per instrument — two different fields, both verbatim;
 * - `0.5` for pan centre (never the `0.50393…` float32(64/127) artefact);
 * - per-pad colours in `program.programPads` — same packed-`0xRRGGBB`,
 *   `Universal` off encoding MPC 2 buried in an escaped blob, here as plain
 *   JSON.
 *
 * Output is deterministic: same program in, byte-identical `.xtd` out.
 * The container comes from [Acvs.write]; the payload is rendered with the
 * 4-space pretty style real files use, and floats keep their decimal point
 * (`"pitch": 0.0`, not `0`).
 *
 * **Hardware-verified, 2026-08-23:** the generated factory kit loaded and
 * played on a Live III — pads on their assigned slots, class colours lit,
 * A03 choking A04, and the embedded "SnipSnap Groove" clip playing from the
 * clip list. The drum path is a proven shipping path; the keygroup path
 * awaits its own load check.
 */
class Mpc3TrackWriter(
    /** Header line 2 — the exporter build. Default is the standalone firmware stamp observed on 59 real projects. */
    private val firmware: String = "3.7.0.56",
    /** Header line 5 — the exporter host. `Linux` is what standalone hardware (and Android) reports. */
    private val platform: String = "Linux",
) {

    /** The `.xtd` container bytes. [clip] embeds a pattern the kit carries along. */
    fun write(program: DrumProgram, trackColour: Int = DEFAULT_TRACK_COLOUR, clip: Mpc3Clip? = null): ByteArray =
        write(program, trackColour, listOfNotNull(clip))

    /**
     * The `.xtd` container bytes with up to [MAX_CLIPS] embedded patterns —
     * the clip *list* is the container's own shape (commercial kits ship
     * four named clips; the browser flips between them).
     */
    fun write(program: DrumProgram, trackColour: Int = DEFAULT_TRACK_COLOUR, clips: List<Mpc3Clip>): ByteArray =
        Acvs.write(
            AcvsHeader(firmware, Mpc3Project.TRACK_OBJECT_TYPE, AcvsHeader.ENCODING_JSON, platform),
            payloadText(program, trackColour, clips),
        )

    /**
     * Write `<name>.xtd` into [directory]. The WAVs belong in a sibling
     * `<name>_[TrackData]/` folder, flat, one file per `samples[].path` —
     * that copy is the exporter's job, not this writer's.
     */
    fun writeTo(
        directory: File,
        program: DrumProgram,
        trackColour: Int = DEFAULT_TRACK_COLOUR,
        clip: Mpc3Clip? = null,
    ): File = writeTo(directory, program, trackColour, listOfNotNull(clip))

    /** [writeTo] with the clip list. */
    fun writeTo(
        directory: File,
        program: DrumProgram,
        trackColour: Int = DEFAULT_TRACK_COLOUR,
        clips: List<Mpc3Clip>,
    ): File {
        require(directory.isDirectory) { "not a directory: $directory" }
        val file = File(directory, "${program.name}.xtd")
        file.writeBytes(write(program, trackColour, clips))
        return file
    }

    /** The uncompressed JSON payload, for tests and diffing. */
    fun payloadText(program: DrumProgram, trackColour: Int = DEFAULT_TRACK_COLOUR, clip: Mpc3Clip? = null): String =
        payloadText(program, trackColour, listOfNotNull(clip))

    /** [payloadText] with the clip list. */
    fun payloadText(program: DrumProgram, trackColour: Int = DEFAULT_TRACK_COLOUR, clips: List<Mpc3Clip>): String =
        render(payload(program, trackColour, clips))

    /** The `.xty` container bytes for a keygroup (instrument) track. */
    fun writeKeygroup(program: KeygroupProgram, trackColour: Int = DEFAULT_TRACK_COLOUR): ByteArray =
        Acvs.write(
            AcvsHeader(firmware, Mpc3Project.TRACK_OBJECT_TYPE, AcvsHeader.ENCODING_JSON, platform),
            keygroupPayloadText(program, trackColour),
        )

    /** Write `<name>.xty` into [directory]; WAVs go in the sibling `_[TrackData]/`. */
    fun writeKeygroupTo(directory: File, program: KeygroupProgram, trackColour: Int = DEFAULT_TRACK_COLOUR): File {
        require(directory.isDirectory) { "not a directory: $directory" }
        val file = File(directory, "${program.name}.xty")
        file.writeBytes(writeKeygroup(program, trackColour))
        return file
    }

    /** The uncompressed keygroup JSON payload, for tests and diffing. */
    fun keygroupPayloadText(program: KeygroupProgram, trackColour: Int = DEFAULT_TRACK_COLOUR): String =
        render(keygroupPayload(program, trackColour))

    companion object {
        /** The container's clip slots — commercial kits ship exactly four. */
        const val MAX_CLIPS = 4

        /** Sibling folder name for the WAVs, per the corpus convention. */
        fun trackDataDirName(programName: String): String = "${programName}_[TrackData]"

        /** SnipSnap green by default; any packed 0xRRGGBB int is legal. */
        const val DEFAULT_TRACK_COLOUR: Int = 0x8FD424

        // Defaults below are verbatim from Kit-SFM 909 Crisp 123.xtd
        // (build 0.1.0.992) — real doubles, not reconstructions.
        private const val MPC_LEVEL: Double = 0.7079460024833679
        private const val MPC_LEVEL_EMPTY: Double = 0.7079457640647888
        private const val SHORT_DECAY: Double = 0.04724400117993355
        private const val SHORT_DECAY_EMPTY: Double = 0.04724409431219101
        private const val INT64_MAX = Long.MAX_VALUE
        private const val RNG_SEED = 1163972992L
        private const val BAR_PULSES = 7680L
    }

    // ---- project-facing builders ------------------------------------------

    internal fun drumTrackObject(program: DrumProgram, trackColour: Int, clip: Mpc3Clip?, includeSolo: Boolean): J =
        trackObject(program.name, drumSampleNames(program), programObj(program), trackColour, listOfNotNull(clip), includeSolo)

    internal fun keygroupTrackObject(program: KeygroupProgram, trackColour: Int, includeSolo: Boolean): J =
        trackObject(program.name, keygroupSampleNames(program), keygroupProgramObj(program), trackColour, emptyList(), includeSolo)

    /**
     * The mixer-infrastructure tracks every harvested project carries beside
     * its content: `Submix 1` (type 8) and the `Out N/N` pairs (type 9) —
     * the track shell around a program with no drum or keygroup block.
     */
    internal fun infrastructureTrackObject(name: String, type: Int): J =
        trackObject(
            name,
            sampleNames = emptyList(),
            programObj = programShell(
                name, type, uncolouredProgramPads(),
                level = MPC_LEVEL, pan = 0.5,
                padNoteMap = padNoteMap(chromaticFrom36 = true),
                drum = null, keygroup = null,
            ),
            trackColour = 0,
            clips = emptyList(),
            includeSolo = false,
        )

    // ---- payload assembly -------------------------------------------------

    private fun payload(program: DrumProgram, trackColour: Int, clips: List<Mpc3Clip>): J {
        require(clips.size <= MAX_CLIPS) { "at most $MAX_CLIPS clips per track, got ${clips.size}" }
        return trackData(
            name = program.name,
            sampleNames = drumSampleNames(program),
            programObj = programObj(program),
            trackColour = trackColour,
            clips = clips,
        )
    }

    private fun keygroupPayload(program: KeygroupProgram, trackColour: Int): J =
        trackData(
            name = program.name,
            sampleNames = keygroupSampleNames(program),
            programObj = keygroupProgramObj(program),
            trackColour = trackColour,
            clips = emptyList(),
        )

    private fun trackData(
        name: String,
        sampleNames: List<String>,
        programObj: J,
        trackColour: Int,
        clips: List<Mpc3Clip>,
    ): J = obj("data" to trackObject(name, sampleNames, programObj, trackColour, clips, includeSolo = true))

    /**
     * One element of a project's `tracks[]` — identical to a standalone
     * track file's `data`, which is the "hoisted" relationship the format
     * doc describes. Projects omit `solo` (per the harvested DD1 project);
     * standalone files carry it.
     */
    internal fun trackObject(
        name: String,
        sampleNames: List<String>,
        programObj: J,
        trackColour: Int,
        clips: List<Mpc3Clip>,
        includeSolo: Boolean,
    ): J = J.O(
        buildList {
            add("version" to i(5))
            add("name" to s(name))
            add("volume" to d(1.0))
            add("volumeKnown" to b(false))
            add("pan" to d(0.5))
            add("panKnown" to b(false))
            add("mute" to b(false))
            if (includeSolo) add("solo" to b(false))
            add("cvPort" to i(0))
            add("gatePort" to i(1))
            add("length" to i(0))
            add("velocityScale" to d(1.0))
            add("muteGroup" to i(0))
            add("transposition" to i(0))
            add("colour" to i(trackColour.toLong()))
            add("padsFollowTrackColour" to b(false))
            add("skipFromRowLaunch" to b(false))
            add("samples" to samplesPool(sampleNames))
            add("program" to programObj)
            add("lengthFollowsSequenceLength" to b(true))
            add("midiEventsFilter" to midiEventsFilter())
            add("arrangementClipMap" to arrangementClips(name))
            add(
                "sharedClipMap" to J.A(clips.mapIndexed { index, c -> clipEntry(c, key = index + 1) }),
            )
            add("recordArm" to b(true))
            add(
                "midiBankAndProgramNumber" to obj(
                    "midiBankEnable" to b(false),
                    "midiBankMsb" to i(0),
                    "midiBankLsb" to i(0),
                    "midiProgramNumberEnable" to b(false),
                    "midiProgramNumber" to i(0),
                ),
            )
            add(
                "midiInputRoute" to obj(
                    "inputPort" to obj(
                        "type" to i(0), "deviceName" to s("All Ports"), "deviceId" to s(""), "os" to s(platform),
                    ),
                    "inputChannel" to i(0),
                ),
            )
            add(
                "midiOutputRoute" to obj(
                    "outputPort" to obj(
                        "type" to i(1), "deviceName" to s("<none>"), "deviceId" to s(""), "os" to s(platform),
                    ),
                    "outputChannel" to i(0),
                ),
            )
            add("midiMonitorable" to obj("state" to i(2)))
        },
    )

    /**
     * The track-level pool: distinct samples in first-appearance order, each
     * once — `name` bare, `path` with extension, exactly mirroring the layer
     * references. Real kits repeat samples across pads; the pool doesn't.
     */
    private fun samplesPool(sampleNames: List<String>): J = J.A(
        sampleNames.map { name ->
            obj("name" to s(name), "path" to s("$name.wav"), "loadImpl" to i(0))
        },
    )

    private fun drumSampleNames(program: DrumProgram): List<String> {
        val seen = LinkedHashSet<String>()
        for (pad in program.pads) {
            pad ?: continue
            for (zone in zonesOf(pad)) seen.add(zone.sampleName)
        }
        return seen.toList()
    }

    private fun keygroupSampleNames(program: KeygroupProgram): List<String> {
        val seen = LinkedHashSet<String>()
        for (kg in program.keygroups) for (layer in kg.layers) seen.add(layer.sampleName)
        return seen.toList()
    }

    private fun programObj(program: DrumProgram): J = programShell(
        name = program.name,
        type = 0,
        programPads = programPads(program),
        level = program.level.toDouble(),
        pan = program.pan.toDouble(),
        padNoteMap = padNoteMap(chromaticFrom36 = true),
        drum = drumObj(program),
        keygroup = null,
    )

    private fun keygroupProgramObj(program: KeygroupProgram): J = programShell(
        name = program.name,
        type = 1,
        programPads = uncolouredProgramPads(),
        level = MPC_LEVEL,
        pan = 0.5,
        // Instrument tracks carry an inert identity map — pitch comes from
        // the zones' key ranges, not the pad table.
        padNoteMap = padNoteMap(chromaticFrom36 = false),
        drum = keygroupDrumObj(program),
        keygroup = keygroupBlock(program),
    )

    private fun programShell(
        name: String,
        type: Int,
        programPads: J,
        level: Double,
        pan: Double,
        padNoteMap: J,
        drum: J?,
        keygroup: J?,
    ): J = J.O(
        buildList {
            add("version" to i(2))
            add("name" to s(name))
            add("type" to i(type.toLong()))
            add("programPads" to programPads)
            add("customQLinks" to J.A(List(16) { emptyQLink() }))
            add("transpose" to i(0))
            add("midiKillGroup" to i(-1))
            add("chainID" to i(0))
            add("midiEventsFilter" to midiEventsFilter())
            add("mixable" to mixable(level = level, pan = pan, destination = 2))
            add("customisable" to obj("mapping" to J.A(emptyList())))
            add("renderable" to obj("sendToCueBus" to b(false)))
            add("xfaderRoute" to i(0))
            add("padNoteMap" to padNoteMap)
            drum?.let { add("drum" to it) }
            // Real instrument tracks put the keygroup block right after drum.
            keygroup?.let { add("keygroup" to it) }
            add("fxRackQLinks" to J.A(emptyList()))
            add("customMacroSceneData" to macroScenes())
            add("fxRackMacroSceneData" to macroScenes())
        },
    )

    /**
     * Same colour blob as MPC 2's ProgramPads, minus the XML escaping:
     * packed 0xRRGGBB per pad, 0 = unset, `Universal` off when any pad
     * carries its own colour.
     */
    private fun programPads(program: DrumProgram): J {
        val coloured = program.pads.any { it?.color != null }
        return obj(
            "Universal" to obj("value0" to b(!coloured)),
            "Type" to obj("value0" to i(5)),
            "universalPad" to i(0x00FF00),
            "pads" to obj(
                *(0 until 128).map { n ->
                    "value$n" to i(if (coloured) (program.pads.getOrNull(n)?.color ?: 0).toLong() else 0L)
                }.toTypedArray(),
            ),
            "UnusedPads" to obj("value0" to i(1)),
            "PadsFollowTrackColour" to obj("value0" to b(false)),
        )
    }

    /**
     * The drum default is chromatic from 36 with wraparound — pad A01 =
     * note 36; instrument tracks use the inert identity map.
     */
    private fun padNoteMap(chromaticFrom36: Boolean): J = obj(
        "noteForPad" to obj(
            *(0 until 128).map { n ->
                "value$n" to i(if (chromaticFrom36) ((36 + n) % 128).toLong() else n.toLong())
            }.toTypedArray(),
        ),
    )

    private fun uncolouredProgramPads(): J = obj(
        "Universal" to obj("value0" to b(true)),
        "Type" to obj("value0" to i(5)),
        "universalPad" to i(0x00FF00),
        "pads" to obj(*(0 until 128).map { "value$it" to i(0) }.toTypedArray()),
        "UnusedPads" to obj("value0" to i(1)),
        "PadsFollowTrackColour" to obj("value0" to b(false)),
    )

    private fun drumObj(program: DrumProgram): J = drumBlock(
        instruments = J.A((0 until 128).map { instrument(program.pads.getOrNull(it)) }),
        poliphony = 0,
        monophonic = false,
    )

    /**
     * A keygroup track keeps its zones in the same `drum.instruments` array a
     * kit uses — `program.keygroup` holds only global synth state. The
     * drum-level `poliphony: 1, monophonic: true` pair is the vestigial shape
     * every real instrument track carries.
     */
    private fun keygroupDrumObj(program: KeygroupProgram): J = drumBlock(
        instruments = J.A(
            (0 until 128).map { keygroupZone(program.keygroups.getOrNull(it), program.volumeRelease.toDouble()) },
        ),
        poliphony = 1,
        monophonic = true,
    )

    private fun drumBlock(instruments: J, poliphony: Int, monophonic: Boolean): J = obj(
        "version" to i(8),
        "pitch" to d(0.0),
        "coarseTune" to i(0),
        "fineTune" to i(0),
        "monophonic" to b(monophonic),
        // The misspelling is the format's, at exactly this path — the
        // per-instrument field below is spelled correctly. Both verbatim.
        "poliphony" to i(poliphony.toLong()),
        "portamentoTime" to d(0.0),
        "portamentoLegato" to b(false),
        "padGroup" to obj(*(0 until 128).map { "value$it" to i(0) }.toTypedArray()),
        "instruments" to instruments,
        "driftSpeed" to d(0.0),
        "portamentoQuantised" to b(false),
        "monoRetrigger" to b(false),
        "freeRunningLfoData" to obj(
            "value0" to freeLfo(), "value1" to freeLfo(),
        ),
    )

    /** Global keygroup state, templated from the F9 J8 instrument track. */
    private fun keygroupBlock(program: KeygroupProgram): J = obj(
        "transpose" to i(0),
        "numKeygroups" to i(program.keygroups.size.toLong()),
        "pitchBendRange" to d(program.pitchBendRange.toDouble()),
        "keygroupVersion" to i(6),
        "version" to i(8),
        "wheelToLfo" to obj("value0" to d(1.0), "value1" to d(0.0)),
        "afterTouchToFilter" to obj("value0" to d(0.0), "value1" to d(0.0)),
        "modlinksData" to obj(
            *(0 until 32).map { n ->
                "value$n" to obj(
                    "link.source" to i(0),
                    "link.target" to i(0),
                    "link.min" to d(0.0),
                    "link.max" to d(1.0),
                    "link.amount" to d(1.0),
                    "link.viaSource" to i(0),
                    "link.viaAmount" to d(1.0),
                    "link.shaper" to i(0),
                    "link.shaperAmount" to d(1.0),
                )
            }.toTypedArray(),
        ),
        "noteCountSize" to i(2),
        "legacyMode" to b(false),
        "timbreShift" to i(0),
        "pitchBendPositiveRange" to i(2),
        "pitchBendNegativeRange" to i(2),
        // Dormant global copy — the per-zone sections do the playing, and
        // all four *EnvelopeGlobal link toggles below stay off.
        "synthSection" to synthSection(filled = true, version = 18),
        "filterEnvelopeGlobal" to b(false),
        "ampEnvelopeGlobal" to b(false),
        "pitchEnvelopeGlobal" to b(false),
        "auxEnvelopeGlobal" to b(false),
        "CurrentStackProcessor" to i(0),
        "unisonProcessor" to obj(
            "mode" to i(0), "voices" to i(0), "detuneAmount" to d(0.0), "stereoSpread" to d(0.0),
        ),
        "harmoniserProcessor" to obj(
            "version" to i(1),
            "voicesData" to obj(
                *(0 until 4).map { n ->
                    "value$n" to obj(
                        "version" to i(1),
                        "voiceData.enabled" to b(true),
                        "voiceData.shift" to i(0),
                        "voiceData.detune" to d(0.0),
                        "voiceData.pan" to d(0.0),
                        "voiceData.velScale" to d(1.0),
                        "voiceData.volume" to d(0.25),
                        "voiceData.delay" to d(0.0),
                        "voiceData.delaySync" to i(0),
                    )
                }.toTypedArray(),
            ),
            "harmonizerMix" to d(0.5),
        ),
        "channelPressureToFilter" to obj("value0" to d(0.0), "value1" to d(0.0)),
    )

    private fun freeLfo(): J = obj(
        "version" to i(25), "lfoLevel" to d(1.0), "lfoWaveformType" to i(0), "lfoRate" to d(0.5), "lfoSync" to i(0),
    )

    // ---- instruments ------------------------------------------------------

    private fun zonesOf(pad: Pad): List<VelocityLayer> =
        pad.velocityLayers ?: listOf(VelocityLayer(pad.sampleName, pad.frameCount, 0, 127))

    private fun instrument(pad: Pad?): J {
        val filled = pad != null
        // MPC 3 layers descend from loudest at index 0; ours ascend soft-first.
        val zones = pad?.let { zonesOf(it).asReversed() } ?: emptyList()
        return instrumentShell(
            coarseTune = pad?.tuneCoarse ?: 0,
            fineTune = pad?.tuneFine ?: 0,
            polyphony = 3,
            lowNote = 0,
            highNote = 127,
            whichMuteGroup = pad?.muteGroup ?: 0,
            synthSection = synthSection(filled, version = 15),
            // 0 = One Shot (the whole sample fires), 2 = Note On (sustains
            // while held) — a per-pad musical choice in real kits.
            triggerMode = if (pad?.oneShot != false) 0 else 2,
            layers = (0 until 8).map { slot -> layer(zones.getOrNull(slot), filledInstrument = filled, rootNote = 0) },
            keygroupExtras = false,
            level = pad?.level?.toDouble() ?: MPC_LEVEL_EMPTY,
            pan = pad?.pan?.toDouble() ?: 0.5,
        )
    }

    /**
     * A keygroup zone in the same 128-slot array: key range on the
     * instrument, root note on each filled layer, `triggerMode 2` (Note On —
     * every real zone sustains), and the extra articulation fields the
     * instrument-track build family carries.
     */
    private fun keygroupZone(kg: Keygroup?, release: Double): J {
        val filled = kg != null
        val zones = kg?.layers?.asReversed() ?: emptyList()
        return instrumentShell(
            coarseTune = 0,
            fineTune = 0,
            polyphony = 0,
            lowNote = kg?.lowNote ?: 0,
            highNote = kg?.highNote ?: 127,
            whichMuteGroup = 0,
            synthSection = synthSection(filled, version = 15, sustained = filled, release = release),
            triggerMode = 2,
            layers = (0 until 8).map { slot ->
                layer(zones.getOrNull(slot), filledInstrument = filled, rootNote = kg?.rootNote ?: 0)
            },
            keygroupExtras = true,
            level = if (filled) MPC_LEVEL else MPC_LEVEL_EMPTY,
            pan = 0.5,
        )
    }

    private fun instrumentShell(
        coarseTune: Int,
        fineTune: Int,
        polyphony: Int,
        lowNote: Int,
        highNote: Int,
        whichMuteGroup: Int,
        synthSection: J,
        triggerMode: Int,
        layers: List<J>,
        keygroupExtras: Boolean,
        level: Double,
        pan: Double,
    ): J = J.O(
        buildList {
            add("version" to i(15))
            add("coarseTune" to i(coarseTune.toLong()))
            add("fineTune" to i(fineTune.toLong()))
            add("monophonic" to b(false))
            add("polyphony" to i(polyphony.toLong()))
            add("lowNote" to i(lowNote.toLong()))
            add("highNote" to i(highNote.toLong()))
            add("ignoreBaseNote" to b(false))
            add("zonePlayTime" to i(1))
            add("whichMuteGroup" to i(whichMuteGroup.toLong()))
            add("muteTargets" to obj(*(0 until 4).map { "value$it" to i(0) }.toTypedArray()))
            add("simultPlayTargets" to obj(*(0 until 4).map { "value$it" to i(0) }.toTypedArray()))
            add("synthSection" to synthSection)
            add("triggerMode" to i(triggerMode.toLong()))
            add("tempo" to d(120.0))
            add("bpmLock" to b(true))
            add("warpEnable" to b(false))
            add("stretchPercentage" to i(100))
            add("layersv" to J.A(layers))
            add("editAllLayers" to b(false))
            add("articulationType" to i(0))
            add("articulationSpeed" to d(0.5))
            if (keygroupExtras) {
                add("articulationDynamics" to d(1.0))
                add("articulationStereo" to d(1.0))
                add("articulationSpeedScaling" to i(1))
            }
            add("userSelectableWarpPoolIndex" to i(0))
            if (keygroupExtras) add("velocityScale" to d(1.0))
            add("mixable" to mixable(level = level, pan = pan, destination = 0))
            add("padEffects" to padEffects())
        },
    )

    private fun layer(zone: VelocityLayer?, filledInstrument: Boolean, rootNote: Int): J = obj(
        "active" to b(true),
        "volume" to obj("gainCoefficient" to d(1.0), "controlValue" to d(1.0), "law" to i(0)),
        "pan" to d(0.5),
        "pitch" to d(0.0),
        "coarseTune" to i(0),
        "fineTune" to i(0),
        "velocityStart" to i((zone?.velStart ?: 0).toLong()),
        "velocityEnd" to i((zone?.velEnd ?: 127).toLong()),
        "sampleStart" to i(0),
        // sampleEnd is real, accepted, and ignored — length lives in
        // sliceInfo.End. Writing it would fail silently forever.
        "sampleEnd" to i(0),
        "loop" to b(false),
        "loopStart" to i(0),
        "loopEnd" to i(0),
        "loopCrossfadeLength" to i(0),
        "loopFineTune" to i(0),
        "mute" to b(false),
        // Filled keygroup layers carry the zone's real root; drums and empty
        // layers write 0. keyTrackEnable stays false — both real chromatic
        // basses ship false, whatever the flag actually gates.
        "rootNote" to i(if (zone != null) rootNote.toLong() else 0L),
        "keyTrackEnable" to b(false),
        "sampleName" to s(zone?.sampleName ?: ""),
        "sampleFile" to s(zone?.let { "${it.sampleName}.wav" } ?: ""),
        "sliceIndex" to i(if (zone != null) 0 else 128),
        "direction" to i(0),
        "offset" to i(0),
        "sliceInfo" to obj(
            "Start" to i(0),
            "End" to i(zone?.frameCount ?: 0L),
            // The sustain loop as PSK's rolls write it: LoopMode 1 +
            // LoopStart, looping to End.
            "LoopStart" to i(zone?.loopStartFrame ?: 0L),
            "LoopMode" to i(if ((zone?.loopStartFrame ?: 0L) > 0L) 1 else 0),
            "PulsePosition" to i(0),
            "LoopCrossfadeLength" to i(if (filledInstrument) -1 else 0),
            "LoopCrossfadeType" to i(0),
            "TailLength" to d(0.0),
            "TailLoopPosition" to d(0.5),
            "NumLoopRepeats" to i(0),
        ),
        "version" to i(7),
        "pitchRandom" to d(0.0),
        "VolumeRandom" to d(0.0),
        "PanRandom" to d(0.0),
        "OffsetRandom" to d(0.0),
        "sliceIncrement" to i(0),
        "sliceCycleLength" to i(1),
        "sliceIncrementRngSeed" to i(RNG_SEED),
        "oscillatorMode" to b(false),
        "oscillatorType" to i(0),
        "oscillatorDecay" to d(1.0),
        "oscillatorSubTypeName" to s(""),
        "oscillatorParams" to J.A(List(5) { d(0.0) }),
        "oscillatorStartPhase" to d(0.0),
    )

    // ---- synth section ----------------------------------------------------

    private fun synthSection(
        filled: Boolean,
        version: Int,
        sustained: Boolean = false,
        release: Double = 0.0,
    ): J = obj(
        "version" to i(version.toLong()),
        "filterData" to obj(
            "value0" to filterSlot(filterType = 2, version = version),
            "value1" to filterSlot(filterType = 0, version = version),
        ),
        "filterSerialRouting" to b(false),
        "filterBlend" to d(0.5),
        "lfoData" to obj(
            "value0" to lfoSlot(reset = !filled, version = version),
            "value1" to lfoSlot(reset = true, version = version),
        ),
        "velocityToStart" to d(0.0),
        "filterEnvelope" to envelope(
            decay = if (filled) SHORT_DECAY else SHORT_DECAY_EMPTY,
            decayFromEnd = true,
        ),
        "ampEnvelope" to if (sustained) {
            // A held keygroup note: sustain full, release from the program —
            // the observed boolean combo (AD, DecayFromEnd, OneShot off) is
            // what real sustaining zones carry.
            envelope(decay = 1.0, decayFromEnd = true, sustain = 1.0, releaseTime = release, oneShot = false)
        } else {
            envelope(
                decay = if (filled) 1.0 else SHORT_DECAY_EMPTY,
                decayFromEnd = !filled,
            )
        },
        "pitchEnvelope" to envelope(
            decay = if (filled) SHORT_DECAY else SHORT_DECAY_EMPTY,
            decayFromEnd = true,
        ),
        "pitchEnvelopeAmount" to d(0.5),
        "randomisationScale" to d(1.0),
        "attackRandom" to d(0.0),
        "decayRandom" to d(0.0),
        "auxEnvelope" to envelope(decay = SHORT_DECAY_EMPTY, decayFromEnd = true),
        "auxEnvelopeAmount" to d(1.0),
        "rampData" to obj("value0" to d(0.0), "value1" to d(0.0)),
        "driftSpeed" to d(0.0),
        "velocityToPitch" to d(0.0),
        "velocitySensitivity" to d(1.0),
        "velocityToPan" to d(0.0),
    )

    private fun filterSlot(filterType: Int, version: Int = 15): J = obj(
        "version" to i(version.toLong()),
        "filterKeytrack" to d(0.0),
        "filterType" to i(filterType.toLong()),
        "filterCutoff" to d(1.0),
        "filterResonance" to d(0.0),
        "filterEnvelopeAmount" to d(0.0),
        "afterTouchToFilter" to d(0.0),
        "filterVelocity" to d(0.0),
        "filterEnvelopeVelocity" to d(0.0),
        "cutoffRandom" to d(0.0),
        "resonanceRandom" to d(0.0),
        "outputLevel" to d(1.0),
        "pressureToFilter" to d(0.0),
    )

    private fun lfoSlot(reset: Boolean, version: Int = 15): J = obj(
        "version" to i(version.toLong()),
        "lfoPitch" to d(0.0),
        "lfoAmpLevel" to d(0.0),
        "lfoPan" to d(0.0),
        "lfoFilterCutOff" to obj("value0" to d(0.0), "value1" to d(0.0)),
        "lfoDelay" to d(0.0),
        "lfoLevel" to d(1.0),
        "lfoFadein" to d(0.0),
        "lfoWaveformType" to i(0),
        "lfoRate" to d(0.5),
        "lfoSync" to i(0),
        "lfoFreeRunning" to b(false),
        "lfoDelaySync" to i(0),
        "lfoFadeinSync" to i(0),
        "lfoReset" to b(reset),
    )

    /** The `{"value0": …}`-wrapped ADSR the firmware serializer emits. */
    private fun envelope(
        decay: Double,
        decayFromEnd: Boolean,
        sustain: Double = 1.0,
        releaseTime: Double = 0.0,
        oneShot: Boolean = true,
    ): J = obj(
        "version" to i(2),
        "Attack" to v0(d(0.0)),
        "VelocityToAttack" to v0(d(0.0)),
        "Decay" to v0(d(decay)),
        "Sustain" to v0(d(sustain)),
        "Release" to v0(d(releaseTime)),
        "Hold" to v0(d(0.0)),
        "DecayFromEnd" to v0(b(decayFromEnd)),
        "AD" to v0(b(true)),
        "OneShot" to v0(b(oneShot)),
        "AttackPhase" to v0(d(0.0)),
        "DecayPhase" to v0(d(0.0)),
        "DecayStart" to v0(i(0)),
        "Type" to i(0),
        "Delay" to v0(d(0.0)),
        "AttackCurve" to v0(d(0.375)),
        "DecayCurve" to v0(d(0.375)),
        "ReleaseCurve" to v0(d(0.375)),
        "tempoSync" to v0(s("None")),
        "Looped" to v0(b(false)),
        "TimeScaling" to v0(d(0.5)),
    )

    private fun padEffects(): J = obj(
        "padEffectsData" to obj(
            *listOf(0, 9, 1, 2, 3, 7, 4, 13).mapIndexed { n, fxType ->
                "value$n" to obj("fxType" to i(fxType.toLong()), "fxValue" to d(0.0))
            }.toTypedArray(),
        ),
    )

    // ---- shared blocks ----------------------------------------------------

    private fun mixable(level: Double, pan: Double, destination: Int): J = obj(
        "version" to i(1),
        "audioRoute" to obj(
            "destination" to i(destination.toLong()),
            "audioRouteSubIndex" to i(0),
            "channelBitmap" to obj("type" to i(0), "data" to i(3)),
        ),
        "volume" to d(level),
        "mute" to b(false),
        "solo" to b(false),
        "pan" to d(pan),
        "automationFilter" to i(1),
        "sends" to J.A(List(4) { d(0.0) }),
        "inserts" to obj("insertsEnabled" to b(true), "effects" to J.A(emptyList())),
    )

    private fun midiEventsFilter(): J = obj(
        "channelFilterData" to obj("channel" to i(255)),
        "velocityFilterData" to obj("min" to d(0.0), "max" to d(1.0)),
        "noteFilterData" to obj(
            "noteRange" to obj("type" to i(2), "data" to s("1".repeat(128))),
        ),
        "automationFilterData" to obj("filteredParams" to s("0".repeat(17))),
    )

    private fun emptyQLink(): J = obj(
        "name" to s(""),
        "controlType" to i(0),
        "targetData" to J.A(emptyList()),
        "momentary" to i(0),
        "controlValue" to d(0.0),
    )

    private fun macroScenes(): J = obj(
        *(0 until 8).map { n ->
            "value$n" to obj("first" to s(""), "second" to J.A(emptyList()))
        }.toTypedArray(),
    )

    /**
     * The embedded pattern — a `sharedClipMap` entry shaped like the real
     * clip-bearing kits' (SFM 808/909 ship four each). Note events only,
     * `type: 3`, with the modifier block and the one place the enum wrapper
     * genuinely appears in program-adjacent data.
     */
    private fun clipEntry(clip: Mpc3Clip, key: Int): J = obj(
        "key" to i(key.toLong()),
        "value" to clipValue(clip),
    )

    /**
     * The clip object itself — also the value a sequence's `trackClipMaps`
     * carries, where the real project omits the MIDI bank block.
     */
    internal fun clipValue(clip: Mpc3Clip, includeMidiBank: Boolean = true): J = J.O(
        buildList {
            add("version" to i(1))
            add("launchQuantisation" to i(0))
            add("startPulses" to i(0))
            add("endPulses" to i(clip.bars * Mpc3Clip.PULSES_PER_BAR))
            add("loopStartPulses" to i(0))
            add("loopEndPulses" to i(clip.bars * Mpc3Clip.PULSES_PER_BAR))
            add("loop" to b(true))
            add("legato" to b(false))
            add("launch" to i(0))
            add("name" to s(clip.name))
            add("colour" to i(0))
            add(
                "eventList" to obj(
                    "length" to i(INT64_MAX),
                    "events" to J.A(clip.notes.map { noteEvent(it) }),
                ),
            )
            if (includeMidiBank) {
                add(
                    "midiBankAndProgramNumber" to obj(
                        "midiBankEnable" to b(false),
                        "midiBankMsb" to i(0),
                        "midiBankLsb" to i(0),
                        "midiProgramNumberEnable" to b(false),
                        "midiProgramNumber" to i(0),
                    ),
                )
            }
        },
    )

    private fun noteEvent(n: Mpc3Note): J = J.O(
        buildList {
            add("version" to i(1))
            add("time" to i(n.timePulses))
            add("type" to i(3))
            add("selected" to b(false))
            add("muted" to b(false))
            add("invented" to b(false))
            add(
                "note" to J.O(
                    buildList {
                        add("version" to i(1))
                        add("note" to i(n.note.toLong()))
                        add("velocity" to d(n.velocity.toDouble()))
                        add("length" to i(n.lengthPulses))
                        add("probability" to i(100))
                        add("ratchet" to i(1))
                        add("articulation" to i(0))
                        for (m in 0 until 16) {
                            add("modifierValue$m" to d(if (m < 6) 0.5 else 0.0))
                            add("modifierActiveState$m" to b(false))
                        }
                        add("EnumCerealisationWrapper(selectedModifierType)" to s("Tuning (coarse)"))
                    },
                ),
            )
        },
    )

    /** 128 empty arrangement lanes, as every harvested kit ships. */
    private fun arrangementClips(name: String): J = J.A(
        (0 until 128).map { key ->
            obj(
                "key" to i(key.toLong()),
                "value" to obj(
                    "version" to i(1),
                    "launchQuantisation" to i(1),
                    "startPulses" to i(0),
                    "endPulses" to i(BAR_PULSES),
                    "loopStartPulses" to i(0),
                    "loopEndPulses" to i(BAR_PULSES),
                    "loop" to b(true),
                    "legato" to b(true),
                    "launch" to i(0),
                    "name" to s(name),
                    "colour" to i(0),
                    "eventList" to obj("length" to i(INT64_MAX), "events" to J.A(emptyList())),
                    "midiBankAndProgramNumber" to obj(
                        "midiBankEnable" to b(false),
                        "midiBankMsb" to i(0),
                        "midiBankLsb" to i(0),
                        "midiProgramNumberEnable" to b(false),
                        "midiProgramNumber" to i(0),
                    ),
                ),
            )
        },
    )

}

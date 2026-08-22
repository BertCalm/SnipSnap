package com.snipsnap.xpm

import java.io.File
import java.util.Locale

/**
 * One key zone of a keygroup program: the note span it answers, the note the
 * sample was recorded at, and its velocity layers (same [VelocityLayer] the
 * drum side uses).
 */
data class Keygroup(
    /** MIDI note range, inclusive. */
    val lowNote: Int,
    val highNote: Int,
    /**
     * The MIDI note the sample plays untransposed at. Real programs put the
     * root at the top of its zone (instrument 35..37 root 37), but any note
     * inside the range is legal.
     */
    val rootNote: Int,
    /** 1..8 velocity zones, soft first. Keygroup instruments carry 8 layer slots. */
    val layers: List<VelocityLayer>,
) {
    init {
        require(lowNote in 0..127 && highNote in 0..127 && lowNote <= highNote) {
            "bad note range $lowNote..$highNote"
        }
        require(rootNote in 0..127) { "rootNote out of range: $rootNote" }
        require(layers.size in 1..8) { "a keygroup has 1..8 layers, got ${layers.size}" }
    }
}

/**
 * A keygroup (multisampled instrument) program: what the MPC plays
 * chromatically across the pads. This is the S4 door from
 * docs/SYNTH_ROADMAP.md.
 */
data class KeygroupProgram(
    val name: String,
    val keygroups: List<Keygroup>,
    val pitchBendRange: Float = 0f,
    /** Seconds-ish release so held notes don't clip off. */
    val volumeRelease: Float = 0.5f,
) {
    init {
        require(name.isNotBlank()) { "program name must not be blank" }
        require(keygroups.isNotEmpty() && keygroups.size <= 128) {
            "1..128 keygroups, got ${keygroups.size}"
        }
        require(pitchBendRange in 0f..24f) { "pitchBendRange out of range: $pitchBendRange" }
    }
}

/**
 * Renders a [KeygroupProgram] as an MPC `.xpm` keygroup program.
 *
 * **Provenance and status:** shaped against the harvested commercial keygroup
 * programs in `reference/golden/keygroup/` (Ambient Box 2026, plus the
 * chromatically-sampled AM Upright Bass) — see "What `KeygroupWriter` gets
 * wrong" in docs/XPM_STRUCTURE.md, all of whose definite defects are fixed
 * here: no instrument-level Active/Tune/Transpose/RootNote/KeyTrack/OneShot,
 * instrument-level TuneCoarse/TuneFine instead, real per-layer RootNote on
 * filled layers, layer KeyTrack=False (13,083 of 13,083 real layers), 8 layer
 * slots, KeygroupNumKeygroups/KeygroupPitchBendRange after the maps at the end
 * of the program body, identity PadNoteMap (pad N → note N-1: keygroups are
 * chromatic on pads) and an all-zero PadGroupMap. The surrounding chassis
 * (Version block, ProgramName) is ours, from the real standalone drum save.
 * Still pending: loading the generated pack on hardware (the S4 acceptance
 * item in reference/README.md).
 */
class KeygroupWriter {

    fun write(program: KeygroupProgram): String {
        val sb = StringBuilder(32 * 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\n")
        sb.append("<MPCVObject>\n")
        sb.append("  <Version>\n")
        sb.append("    <File_Version>2.1</File_Version>\n")
        sb.append("    <Application>MPC-V</Application>\n")
        sb.append("    <Application_Version>2.9.1.2</Application_Version>\n")
        sb.append("    <Platform>Linux</Platform>\n")
        sb.append("  </Version>\n")
        sb.append("  <Program type=\"Keygroup\">\n")
        sb.append("    <ProgramName>").append(program.name.esc()).append("</ProgramName>\n")
        sb.append("    <Instruments>\n")
        program.keygroups.forEachIndexed { index, kg ->
            appendInstrument(sb, index, kg, program.volumeRelease)
        }
        sb.append("    </Instruments>\n")
        appendPadNoteMap(sb)
        appendPadGroupMap(sb)
        // Real programs put the Keygroup* block after the maps, at the end of
        // the program body — never before <Instruments>.
        sb.append("    <KeygroupNumKeygroups>").append(program.keygroups.size).append("</KeygroupNumKeygroups>\n")
        sb.append("    <KeygroupPitchBendRange>").append(program.pitchBendRange.f()).append("</KeygroupPitchBendRange>\n")
        sb.append("  </Program>\n")
        sb.append("</MPCVObject>\n")
        return sb.toString()
    }

    fun writeTo(directory: File, program: KeygroupProgram): File {
        require(directory.isDirectory) { "not a directory: $directory" }
        val file = File(directory, "${program.name}.xpm")
        file.writeText(write(program), Charsets.UTF_8)
        return file
    }

    private fun appendInstrument(sb: StringBuilder, index: Int, kg: Keygroup, release: Float) {
        sb.append("      <Instrument number=\"").append(index).append("\">\n")
        // Element order follows the harvested programs: mixer, filter,
        // envelope, tune, note range. No Active/RootNote/KeyTrack/OneShot at
        // this level — those exist only per layer (and OneShot not at all).
        sb.append("        <Volume>").append(1f.f()).append("</Volume>\n")
        sb.append("        <Pan>").append(0.5f.f()).append("</Pan>\n")
        sb.append("        <FilterType>2</FilterType>\n")
        sb.append("        <Cutoff>").append(1f.f()).append("</Cutoff>\n")
        sb.append("        <Resonance>").append(0f.f()).append("</Resonance>\n")
        sb.append("        <FilterEnvAmt>").append(0f.f()).append("</FilterEnvAmt>\n")
        sb.append("        <VolumeHold>").append(0f.f()).append("</VolumeHold>\n")
        sb.append("        <VolumeAttack>").append(0f.f()).append("</VolumeAttack>\n")
        sb.append("        <VolumeDecay>").append(0f.f()).append("</VolumeDecay>\n")
        sb.append("        <VolumeSustain>").append(1f.f()).append("</VolumeSustain>\n")
        sb.append("        <VolumeRelease>").append(release.f()).append("</VolumeRelease>\n")
        sb.append("        <TuneCoarse>0</TuneCoarse>\n")
        sb.append("        <TuneFine>0</TuneFine>\n")
        sb.append("        <LowNote>").append(kg.lowNote).append("</LowNote>\n")
        sb.append("        <HighNote>").append(kg.highNote).append("</HighNote>\n")
        sb.append("        <Layers>\n")
        for (layer in 1..8) {
            val zone = kg.layers.getOrNull(layer - 1)
            sb.append("          <Layer number=\"").append(layer).append("\">\n")
            sb.append("            <Active>").append(if (zone != null) "True" else "False").append("</Active>\n")
            sb.append("            <Volume>").append(1f.f()).append("</Volume>\n")
            sb.append("            <Pan>").append(0.5f.f()).append("</Pan>\n")
            sb.append("            <Pitch>").append(0f.f()).append("</Pitch>\n")
            sb.append("            <TuneCoarse>0</TuneCoarse>\n")
            sb.append("            <TuneFine>0</TuneFine>\n")
            // Empty layers get 0/0 (Rex Rule #3: a 0..127 window on a
            // silent layer can ghost-trigger).
            sb.append("            <VelStart>").append(zone?.velStart ?: 0).append("</VelStart>\n")
            sb.append("            <VelEnd>").append(zone?.velEnd ?: 0).append("</VelEnd>\n")
            sb.append("            <SampleStart>0</SampleStart>\n")
            sb.append("            <SampleEnd>0</SampleEnd>\n")
            sb.append("            <LoopStart>0</LoopStart>\n")
            sb.append("            <LoopEnd>0</LoopEnd>\n")
            sb.append("            <LoopCrossfadeLength>0</LoopCrossfadeLength>\n")
            sb.append("            <LoopTune>0</LoopTune>\n")
            // Filled layers carry the real root; only empty layers write 0.
            // KeyTrack is False in every real program — transposition comes
            // from RootNote and the zone, not this flag.
            sb.append("            <RootNote>").append(if (zone != null) kg.rootNote else 0).append("</RootNote>\n")
            sb.append("            <KeyTrack>False</KeyTrack>\n")
            sb.append("            <SampleName>").append(zone?.sampleName?.esc() ?: "").append("</SampleName>\n")
            sb.append("            <SampleFile></SampleFile>\n")
            sb.append("            <SliceIndex>129</SliceIndex>\n")
            sb.append("            <Direction>0</Direction>\n")
            sb.append("            <Offset>0</Offset>\n")
            sb.append("            <SliceStart>0</SliceStart>\n")
            sb.append("            <SliceEnd>").append(zone?.frameCount ?: 0L).append("</SliceEnd>\n")
            sb.append("            <SliceLoopStart>0</SliceLoopStart>\n")
            sb.append("            <SliceLoop>0</SliceLoop>\n")
            sb.append("            <SliceLoopCrossFadeLength>0</SliceLoopCrossFadeLength>\n")
            sb.append("          </Layer>\n")
        }
        sb.append("        </Layers>\n")
        sb.append("      </Instrument>\n")
    }

    /**
     * Keygroup programs map pads chromatically: pad N plays note N-1, unlike
     * the drum layout. Identity map in every harvested keygroup program.
     */
    private fun appendPadNoteMap(sb: StringBuilder) {
        sb.append("    <PadNoteMap>\n")
        for (pad in 1..PadNoteMap.PAD_COUNT) {
            sb.append("      <PadNote number=\"").append(pad).append("\">\n")
            sb.append("        <Note>").append(pad - 1).append("</Note>\n")
            sb.append("      </PadNote>\n")
        }
        sb.append("    </PadNoteMap>\n")
    }

    private fun appendPadGroupMap(sb: StringBuilder) {
        sb.append("    <PadGroupMap>\n")
        for (pad in 1..PadNoteMap.PAD_COUNT) {
            sb.append("      <PadGroup number=\"").append(pad).append("\">\n")
            sb.append("        <Group>0</Group>\n")
            sb.append("      </PadGroup>\n")
        }
        sb.append("    </PadGroupMap>\n")
    }

    private fun Float.f(): String = String.format(Locale.ROOT, "%.6f", this)

    private fun String.esc(): String = buildString(length) {
        for (c in this@esc) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

package com.snipsnap.xpm

import java.io.File
import java.util.Locale

/**
 * One key zone of a keygroup program: the note span it answers, and its
 * velocity layers (same [VelocityLayer] the drum side uses).
 */
data class Keygroup(
    /** MIDI note range, inclusive. */
    val lowNote: Int,
    val highNote: Int,
    /** 1..4 velocity zones, soft first. */
    val layers: List<VelocityLayer>,
) {
    init {
        require(lowNote in 0..127 && highNote in 0..127 && lowNote <= highNote) {
            "bad note range $lowNote..$highNote"
        }
        require(layers.size in 1..4) { "a keygroup has 1..4 layers, got ${layers.size}" }
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
    val pitchBendRange: Int = 12,
    /** Seconds-ish release so held notes don't clip off. */
    val volumeRelease: Float = 0.5f,
) {
    init {
        require(name.isNotBlank()) { "program name must not be blank" }
        require(keygroups.isNotEmpty() && keygroups.size <= 128) {
            "1..128 keygroups, got ${keygroups.size}"
        }
        require(pitchBendRange in 0..24) { "pitchBendRange out of range: $pitchBendRange" }
    }
}

/**
 * Renders a [KeygroupProgram] as an MPC `.xpm` keygroup program.
 *
 * **Provenance and status:** the keygroup element vocabulary
 * (`KeygroupNumKeygroups`, per-instrument `LowNote`/`HighNote`, the
 * `RootNote=0` auto-detect convention, `KeyTrack=True`, `VelStart=0` on
 * empty layers to prevent ghost triggering) is lifted from the XO_OX XPN
 * toolchain's shipping keygroup exporter (BertCalm/XO_OX-XOmnibus,
 * `Tools/xpn_keygroup_export.py` — "Rex's rules"). The surrounding chassis
 * (Version block, ProgramName) is ours, from the real standalone drum save.
 * **No real standalone keygroup save has been diffed against this yet** —
 * that golden file (see reference/README.md) remains the S4 acceptance
 * item; until then this writer's status matches ExpansionWriter's: best
 * available shape, one real file from fixing to verified.
 */
class KeygroupWriter(
    private val samplePathPrefix: String? = null,
) {

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
        sb.append("    <KeygroupNumKeygroups>").append(program.keygroups.size).append("</KeygroupNumKeygroups>\n")
        sb.append("    <KeygroupPitchBendRange>").append(program.pitchBendRange).append("</KeygroupPitchBendRange>\n")
        sb.append("    <Instruments>\n")
        program.keygroups.forEachIndexed { index, kg ->
            appendInstrument(sb, index, kg, program.volumeRelease)
        }
        sb.append("    </Instruments>\n")
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
        sb.append("        <Active>True</Active>\n")
        sb.append("        <Volume>").append(1f.f()).append("</Volume>\n")
        sb.append("        <Pan>").append(0.5f.f()).append("</Pan>\n")
        sb.append("        <Tune>0</Tune>\n")
        sb.append("        <Transpose>0</Transpose>\n")
        sb.append("        <VolumeAttack>").append(0f.f()).append("</VolumeAttack>\n")
        sb.append("        <VolumeHold>").append(0f.f()).append("</VolumeHold>\n")
        sb.append("        <VolumeDecay>").append(0f.f()).append("</VolumeDecay>\n")
        sb.append("        <VolumeSustain>").append(1f.f()).append("</VolumeSustain>\n")
        sb.append("        <VolumeRelease>").append(release.f()).append("</VolumeRelease>\n")
        sb.append("        <FilterType>2</FilterType>\n")
        sb.append("        <Cutoff>").append(1f.f()).append("</Cutoff>\n")
        sb.append("        <Resonance>").append(0f.f()).append("</Resonance>\n")
        sb.append("        <FilterEnvAmt>").append(0f.f()).append("</FilterEnvAmt>\n")
        sb.append("        <LowNote>").append(kg.lowNote).append("</LowNote>\n")
        sb.append("        <HighNote>").append(kg.highNote).append("</HighNote>\n")
        // RootNote 0 = "auto-detect" convention; KeyTrack makes the zone
        // transpose across its span.
        sb.append("        <RootNote>0</RootNote>\n")
        sb.append("        <KeyTrack>True</KeyTrack>\n")
        sb.append("        <OneShot>False</OneShot>\n")
        sb.append("        <Layers>\n")
        for (layer in 1..4) {
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
            sb.append("            <RootNote>0</RootNote>\n")
            sb.append("            <KeyTrack>True</KeyTrack>\n")
            sb.append("            <Loop>False</Loop>\n")
            sb.append("            <LoopStart>0</LoopStart>\n")
            sb.append("            <LoopEnd>0</LoopEnd>\n")
            sb.append("            <Mute>False</Mute>\n")
            sb.append("            <SampleName>").append(zone?.sampleName?.esc() ?: "").append("</SampleName>\n")
            if (samplePathPrefix != null && zone != null) {
                sb.append("            <SampleFile>").append("${zone.sampleName}.wav".esc()).append("</SampleFile>\n")
                sb.append("            <File>").append("$samplePathPrefix/${zone.sampleName}.wav".esc()).append("</File>\n")
            } else {
                sb.append("            <SampleFile></SampleFile>\n")
            }
            sb.append("            <SliceStart>0</SliceStart>\n")
            sb.append("            <SliceEnd>").append(zone?.frameCount ?: 0L).append("</SliceEnd>\n")
            sb.append("          </Layer>\n")
        }
        sb.append("        </Layers>\n")
        sb.append("      </Instrument>\n")
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

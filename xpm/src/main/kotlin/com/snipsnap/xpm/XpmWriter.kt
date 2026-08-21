package com.snipsnap.xpm

import java.io.File
import java.util.Locale

/**
 * Renders a [DrumProgram] as an MPC `.xpm` drum program.
 *
 * Targets the MPC 2-era format (`File_Version` 2.1) as written by MPC standalone
 * firmware, which is the common denominator across MPC One, Live II and Live III.
 * See `docs/XPM_STRUCTURE.md` for where this structure came from and which parts
 * are still unverified against hardware.
 *
 * Output is deterministic: same program in, byte-identical file out. The golden
 * test depends on that.
 */
class XpmWriter(
    /**
     * Whether `<Instrument number="...">` counts from 0 or 1.
     *
     * **Unverified.** Akai's own files appear to be 0-based, but at least one
     * third-party generator emits 1-based and reportedly loads. If a generated
     * kit comes up shifted by one pad on hardware, this is the knob. See
     * `docs/XPM_STRUCTURE.md`.
     */
    private val instrumentBaseIndex: Int = 0,
) {

    fun write(program: DrumProgram): String {
        val sb = StringBuilder(64 * 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\n")
        sb.append("<MPCVObject>\n")
        appendVersion(sb)
        sb.append("  <Program type=\"Drum\">\n")
        sb.append("    <ProgramName>").append(program.name.xmlEscaped()).append("</ProgramName>\n")
        appendProgramPads(sb)
        appendProgramParams(sb, program)
        appendInstruments(sb, program)
        appendPadNoteMap(sb)
        sb.append("  </Program>\n")
        sb.append("</MPCVObject>\n")
        return sb.toString()
    }

    /** Write the program next to its samples. The MPC resolves SampleName from this folder. */
    fun writeTo(directory: File, program: DrumProgram): File {
        require(directory.isDirectory) { "not a directory: $directory" }
        val file = File(directory, "${program.name}.xpm")
        file.writeText(write(program), Charsets.UTF_8)
        return file
    }

    private fun appendVersion(sb: StringBuilder) {
        sb.append("  <Version>\n")
        sb.append("    <File_Version>2.1</File_Version>\n")
        sb.append("    <Application>MPC-V</Application>\n")
        sb.append("    <Application_Version>2.9.1.2</Application_Version>\n")
        sb.append("    <Platform>Linux</Platform>\n")
        sb.append("  </Version>\n")
    }

    /**
     * The ProgramPads blob: JSON, XML-escaped, embedded as element text.
     *
     * Every entry is 0 (the "no override" value) and `universalPad` is the
     * constant the firmware writes for a fresh program. Pad colours live here
     * too, which is why v2 will need this to stop being a constant.
     */
    private fun appendProgramPads(sb: StringBuilder) {
        val json = buildString {
            append("{\n")
            append("    \"ProgramPads\": {\n")
            append("        \"Universal\": {\n            \"value0\": true\n        },\n")
            append("        \"Type\": {\n            \"value0\": 1\n        },\n")
            append("        \"universalPad\": 32512,\n")
            append("        \"pads\": {\n")
            for (i in 0 until PadNoteMap.PAD_COUNT) {
                append("            \"value").append(i).append("\": 0")
                if (i != PadNoteMap.PAD_COUNT - 1) append(",")
                append("\n")
            }
            append("        },\n")
            append("        \"UnusedPads\": {\n            \"value0\": 1\n        }\n")
            append("    }\n")
            append("}")
        }
        sb.append("    <ProgramPads>").append(json.xmlEscaped()).append("</ProgramPads>\n")
    }

    private fun appendProgramParams(sb: StringBuilder, program: DrumProgram) {
        sb.append("    <CueBusEnable>False</CueBusEnable>\n")
        appendAudioRoute(sb, indent = "    ", route = 2)
        for (n in 1..4) sb.append("    <Send").append(n).append(">").append(0f.f()).append("</Send").append(n).append(">\n")
        sb.append("    <Volume>").append(program.level.f()).append("</Volume>\n")
        sb.append("    <Mute>False</Mute>\n")
        sb.append("    <Solo>False</Solo>\n")
        sb.append("    <Pan>").append(program.pan.f()).append("</Pan>\n")
        sb.append("    <AutomationFilter>1</AutomationFilter>\n")
        sb.append("    <Pitch>").append(0f.f()).append("</Pitch>\n")
        sb.append("    <TuneCoarse>0</TuneCoarse>\n")
        sb.append("    <TuneFine>0</TuneFine>\n")
        sb.append("    <Mono>False</Mono>\n")
        sb.append("    <Program_Polyphony>").append(program.polyphony).append("</Program_Polyphony>\n")
        sb.append("    <PortamentoTime>").append(0f.f()).append("</PortamentoTime>\n")
        sb.append("    <PortamentoLegato>False</PortamentoLegato>\n")
        sb.append("    <PortamentoQuantized>False</PortamentoQuantized>\n")
        sb.append("    <Program.Xfader.Route>0</Program.Xfader.Route>\n")
    }

    private fun appendAudioRoute(sb: StringBuilder, indent: String, route: Int) {
        sb.append(indent).append("<AudioRoute>\n")
        sb.append(indent).append("  <AudioRoute>").append(route).append("</AudioRoute>\n")
        sb.append(indent).append("  <AudioRouteSubIndex>0</AudioRouteSubIndex>\n")
        sb.append(indent).append("  <AudioRouteChannelBitmap>3</AudioRouteChannelBitmap>\n")
        sb.append(indent).append("  <InsertsEnabled>True</InsertsEnabled>\n")
        sb.append(indent).append("</AudioRoute>\n")
    }

    /**
     * One `<Instrument>` per pad slot, from pad 1 up to the highest occupied pad.
     *
     * Empty slots in the middle still get an instrument (with no sample) so that
     * instrument index stays aligned with pad index. Emitting only the occupied
     * pads would silently shift every sample after a gap onto the wrong pad.
     */
    private fun appendInstruments(sb: StringBuilder, program: DrumProgram) {
        sb.append("    <Instruments>\n")
        val highest = program.pads.indexOfLast { it != null }
        for (i in 0..highest) {
            appendInstrument(sb, instrumentBaseIndex + i, program.pads[i])
        }
        sb.append("    </Instruments>\n")
    }

    private fun appendInstrument(sb: StringBuilder, number: Int, pad: Pad?) {
        sb.append("      <Instrument number=\"").append(number).append("\">\n")
        sb.append("        <CueBusEnable>False</CueBusEnable>\n")
        appendAudioRoute(sb, indent = "        ", route = 0)
        for (n in 1..4) sb.append("        <Send").append(n).append(">").append(0f.f()).append("</Send").append(n).append(">\n")
        sb.append("        <Volume>").append((pad?.level ?: 0.707946f).f()).append("</Volume>\n")
        sb.append("        <Mute>False</Mute>\n")
        sb.append("        <Solo>False</Solo>\n")
        sb.append("        <Pan>").append((pad?.pan ?: 0.5f).f()).append("</Pan>\n")
        sb.append("        <AutomationFilter>1</AutomationFilter>\n")
        sb.append("        <TuneCoarse>").append(pad?.tuneCoarse ?: 0).append("</TuneCoarse>\n")
        sb.append("        <TuneFine>").append(pad?.tuneFine ?: 0).append("</TuneFine>\n")
        sb.append("        <Mono>True</Mono>\n")
        sb.append("        <Polyphony>1</Polyphony>\n")
        sb.append("        <FilterKeytrack>").append(0f.f()).append("</FilterKeytrack>\n")
        sb.append("        <LowNote>0</LowNote>\n")
        sb.append("        <HighNote>127</HighNote>\n")
        sb.append("        <IgnoreBaseNote>False</IgnoreBaseNote>\n")
        sb.append("        <ZonePlay>1</ZonePlay>\n")
        sb.append("        <MuteGroup>").append(pad?.muteGroup ?: 0).append("</MuteGroup>\n")
        for (n in 1..4) sb.append("        <MuteTarget").append(n).append(">0</MuteTarget").append(n).append(">\n")
        for (n in 1..4) sb.append("        <SimultTarget").append(n).append(">0</SimultTarget").append(n).append(">\n")
        for (p in listOf("Pitch", "Cutoff", "Volume", "Pan")) {
            sb.append("        <Lfo").append(p).append(">").append(0f.f()).append("</Lfo").append(p).append(">\n")
        }
        sb.append("        <OneShot>").append((pad?.oneShot ?: true).b()).append("</OneShot>\n")
        sb.append("        <FilterType>2</FilterType>\n")
        sb.append("        <Cutoff>").append(1f.f()).append("</Cutoff>\n")
        sb.append("        <Resonance>").append(0f.f()).append("</Resonance>\n")
        sb.append("        <FilterEnvAmt>").append(0f.f()).append("</FilterEnvAmt>\n")
        sb.append("        <AfterTouchToFilter>").append(0f.f()).append("</AfterTouchToFilter>\n")
        sb.append("        <VelocityToStart>").append(0f.f()).append("</VelocityToStart>\n")
        sb.append("        <VelocityToFilterAttack>").append(0f.f()).append("</VelocityToFilterAttack>\n")
        sb.append("        <VelocityToFilter>").append(0f.f()).append("</VelocityToFilter>\n")
        sb.append("        <VelocityToFilterEnvelope>").append(0f.f()).append("</VelocityToFilterEnvelope>\n")
        sb.append("        <FilterAttack>").append(0f.f()).append("</FilterAttack>\n")
        sb.append("        <FilterDecay>").append(0.047244f.f()).append("</FilterDecay>\n")
        sb.append("        <FilterSustain>").append(1f.f()).append("</FilterSustain>\n")
        sb.append("        <FilterRelease>").append(0f.f()).append("</FilterRelease>\n")
        sb.append("        <FilterHold>").append(0f.f()).append("</FilterHold>\n")
        sb.append("        <FilterDecayType>True</FilterDecayType>\n")
        sb.append("        <FilterADEnvelope>True</FilterADEnvelope>\n")
        sb.append("        <VolumeHold>").append(0f.f()).append("</VolumeHold>\n")
        sb.append("        <VolumeDecayType>True</VolumeDecayType>\n")
        sb.append("        <VolumeADEnvelope>True</VolumeADEnvelope>\n")
        sb.append("        <VolumeAttack>").append(0f.f()).append("</VolumeAttack>\n")
        sb.append("        <VolumeDecay>").append(0.047244f.f()).append("</VolumeDecay>\n")
        sb.append("        <VolumeSustain>").append(1f.f()).append("</VolumeSustain>\n")
        sb.append("        <VolumeRelease>").append(0f.f()).append("</VolumeRelease>\n")
        sb.append("        <VelocityToPitch>").append(0f.f()).append("</VelocityToPitch>\n")
        sb.append("        <VelocityToVolumeAttack>").append(0f.f()).append("</VelocityToVolumeAttack>\n")
        sb.append("        <VelocitySensitivity>").append(1f.f()).append("</VelocitySensitivity>\n")
        sb.append("        <VelocityToPan>").append(0f.f()).append("</VelocityToPan>\n")
        sb.append("        <LFO>\n")
        sb.append("          <Type>Sine</Type>\n")
        sb.append("          <Rate>").append(0.5f.f()).append("</Rate>\n")
        sb.append("          <Sync>0</Sync>\n")
        sb.append("          <Reset>False</Reset>\n")
        sb.append("        </LFO>\n")
        sb.append("        <WarpTempo>").append(120f.f()).append("</WarpTempo>\n")
        sb.append("        <BpmLock>True</BpmLock>\n")
        sb.append("        <WarpEnable>False</WarpEnable>\n")
        sb.append("        <StretchPercentage>100</StretchPercentage>\n")
        appendLayers(sb, pad)
        sb.append("      </Instrument>\n")
    }

    /**
     * Four layers per instrument, which is what the format expects. We only ever
     * fill layer 1 — velocity layers and round robins are a later feature — but
     * the empty three still have to be present.
     */
    private fun appendLayers(sb: StringBuilder, pad: Pad?) {
        // Either the explicit velocity zones, or the classic single-sample
        // shape: pad on layer 1 across the whole velocity range. The golden
        // file pins the latter; zones only appear when a pad asks for them.
        val zones: List<VelocityLayer?> = pad?.velocityLayers
            ?: listOf(pad?.let { VelocityLayer(it.sampleName, it.frameCount, 0, 127) })

        sb.append("        <Layers>\n")
        for (layer in 1..4) {
            val sample = zones.getOrNull(layer - 1)
            sb.append("          <Layer number=\"").append(layer).append("\">\n")
            sb.append("            <Active>True</Active>\n")
            sb.append("            <Volume>").append(1f.f()).append("</Volume>\n")
            sb.append("            <Pan>").append(0.5f.f()).append("</Pan>\n")
            sb.append("            <Pitch>").append(0f.f()).append("</Pitch>\n")
            sb.append("            <TuneCoarse>0</TuneCoarse>\n")
            sb.append("            <TuneFine>0</TuneFine>\n")
            sb.append("            <VelStart>").append(sample?.velStart ?: 0).append("</VelStart>\n")
            sb.append("            <VelEnd>").append(sample?.velEnd ?: 127).append("</VelEnd>\n")
            sb.append("            <SampleStart>0</SampleStart>\n")
            sb.append("            <SampleEnd>0</SampleEnd>\n")
            sb.append("            <Loop>False</Loop>\n")
            sb.append("            <LoopStart>0</LoopStart>\n")
            sb.append("            <LoopEnd>0</LoopEnd>\n")
            sb.append("            <LoopCrossfadeLength>0</LoopCrossfadeLength>\n")
            sb.append("            <LoopTune>0</LoopTune>\n")
            sb.append("            <Mute>False</Mute>\n")
            sb.append("            <RootNote>0</RootNote>\n")
            sb.append("            <KeyTrack>False</KeyTrack>\n")
            sb.append("            <SampleName>").append(sample?.sampleName?.xmlEscaped() ?: "").append("</SampleName>\n")
            sb.append("            <SampleFile></SampleFile>\n")
            sb.append("            <SliceIndex>129</SliceIndex>\n")
            sb.append("            <Direction>0</Direction>\n")
            sb.append("            <Offset>0</Offset>\n")
            sb.append("            <SliceStart>0</SliceStart>\n")
            sb.append("            <SliceEnd>").append(sample?.frameCount ?: 0L).append("</SliceEnd>\n")
            sb.append("            <SliceLoopStart>0</SliceLoopStart>\n")
            sb.append("            <SliceLoop>0</SliceLoop>\n")
            sb.append("            <SliceLoopCrossFadeLength>0</SliceLoopCrossFadeLength>\n")
            sb.append("          </Layer>\n")
        }
        sb.append("        </Layers>\n")
    }

    private fun appendPadNoteMap(sb: StringBuilder) {
        sb.append("    <PadNoteMap>\n")
        for (pad in 1..PadNoteMap.PAD_COUNT) {
            sb.append("      <PadNote number=\"").append(pad).append("\">\n")
            sb.append("        <Note>").append(PadNoteMap.noteForPad(pad)).append("</Note>\n")
            sb.append("      </PadNote>\n")
        }
        sb.append("    </PadNoteMap>\n")
    }
}

/** Six decimal places, always a dot — the format is not locale-aware and neither are we. */
private fun Float.f(): String = String.format(Locale.ROOT, "%.6f", this)

private fun Boolean.b(): String = if (this) "True" else "False"

private fun String.xmlEscaped(): String = buildString(length + 16) {
    for (c in this@xmlEscaped) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

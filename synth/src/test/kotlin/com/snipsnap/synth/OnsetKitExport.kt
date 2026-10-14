package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.XpmWriter
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Sample-only MPC listening kit; ONSET is not a registered editable recipe engine. */
object OnsetKitExport {
    private const val NAME = "SnipSnap ONSET Audition"
    private val blends = listOf(0f, .25f, .5f, 1f)
    private val voices = listOf(OnsetVoice.KICK, OnsetVoice.SNARE, OnsetVoice.HAT_CLOSED, OnsetVoice.HAT_OPEN)

    fun write(root: File, renders: Map<Pair<OnsetVoice, Float>, Snip>, settingsJsonFile: File): File {
        require(settingsJsonFile.isFile) { "write settings.json before exporting the MPC kit" }
        val clips = Json.parse(File(root, "manifest.json").readText()).obj().getValue("clips").arr()
            .map { it.obj() }.filter { it.getValue("kind").str() == "hit" }
        val selected = blends.flatMap { blend -> voices.map { voice ->
            val clip = clips.single {
                it.getValue("voice").str() == voice.name && it.getValue("blend").num().toFloat() == blend
            }
            val raw = File(root, clip.getValue("raw").str()).canonicalFile
            require(raw.toPath().startsWith(root.canonicalFile.toPath()) && raw.isFile) { "missing local RAW source" }
            val supplied = renders.getValue(voice to blend)
            val decoded = WavReader.read(raw)
            check(decoded.channels == supplied.channels && decoded.sampleRate == supplied.sampleRate &&
                decoded.samples.contentEquals(supplied.samples)) { "supplied PCM differs from RAW source: $voice/$blend" }
            check(hash(raw) == clip.getValue("sha256").obj().getValue("raw").str()) { "RAW source hash changed: $raw" }
            Triple(voice, blend, raw)
        } }
        val work = File(root, ".onset-mpc-work")
        check(!work.exists() || work.deleteRecursively()) { "could not clear $work" }
        try {
            val staging = File(work, "samples")
            val arranged = selected.map { (voice, blend, raw) ->
                ArrangedPad(renders.getValue(voice to blend), DrumClass.valueOf(voice.name),
                    level = 1f, oneShot = true, displayName = "${voice.name} BLEND $blend",
                    source = mapOf("renderer" to "ONSET", "blend" to blend.toString(),
                        "auditionRaw" to raw.relativeTo(root.canonicalFile).invariantSeparatorsPath,
                        "rawSha256" to hash(raw)))
            }
            val kit = KitAssembler.assembleArranged(NAME, arranged, staging)
            check(kit.pads.size == 16)
            kit.pads.forEachIndexed { index, pad ->
                val voice = selected[index].first
                check(pad.slot == index + 1 && pad.level == 1f && pad.oneShot && pad.recipe == null)
                check(pad.muteGroup == if (voice == OnsetVoice.HAT_CLOSED || voice == OnsetVoice.HAT_OPEN) 1 else 0)
                // Do not re-quantize decoded PCM: replace the assembler's temporary WAV
                // with the exact already-delivered RAW file before native export.
                selected[index].third.copyTo(File(staging, pad.sampleFile), overwrite = true)
            }
            val destination = File(work, NAME).also { check(it.mkdirs()) }
            val dataDir = File(destination, Mpc3TrackWriter.trackDataDirName(NAME))
            val program = Mpc3Exporter.stageTrack(kit, staging, dataDir).copy(level = 1f)
            val xtd = Mpc3TrackWriter().writeTo(destination, program)
            // XPM VolumeDecayType=true is end-relative; zero disables that final fade.
            val xpm = XpmWriter().writeTo(dataDir, program.copy(pads = program.pads.map { it?.copy(decay = 0f) }))
            KitStore.save(kit, dataDir)
            settingsJsonFile.copyTo(File(destination, "settings.json"))
            File(destination, "README.txt").writeText(readme())
            File(destination, "raw-sources.tsv").writeText(buildString {
                append("pad\tvoice\tblend\tsample\trawSource\tsha256\n")
                kit.pads.forEachIndexed { index, pad ->
                    val (voice, blend, raw) = selected[index]
                    val exported = File(dataDir, pad.sampleFile)
                    check(hash(exported) == hash(raw)) { "export altered RAW WAV bytes: ${pad.sampleFile}" }
                    append("${pad.slot}\t${voice.name}\t$blend\t${pad.sampleFile}\t")
                    append(raw.relativeTo(root.canonicalFile).invariantSeparatorsPath).append('\t').append(hash(raw)).append('\n')
                }
            })
            verifyPrograms(xtd, xpm, kit.pads.map { renders.getValue(selected[it.slot - 1].first to selected[it.slot - 1].second).frameCount.toLong() })
            val archive = File(root, "onset-mpc-kit.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }
                    .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.forEach { file ->
                        zip.putNextEntry(ZipEntry("$NAME/${file.relativeTo(destination).invariantSeparatorsPath}").also { it.time = 0L })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            return archive
        } finally {
            work.deleteRecursively()
        }
    }

    /** Inspect the written formats, including full sample windows and all cross-row hat groups. */
    private fun verifyPrograms(xtd: File, xpm: File, frames: List<Long>) {
        val track = Mpc3Project.read(xtd)
        check(track.isTrack && track.programs().single().getValue("mixable").obj().getValue("volume").num() == 1.0)
        val instruments = track.drumPrograms().single().getValue("instruments").arr().map { it.obj() }
        check(instruments.count { it.getValue("layersv").arr().first().obj().getValue("sampleName").str().isNotEmpty() } == 16)
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xpm)
        val xmlProgram = xml.getElementsByTagName("Program").item(0) as Element
        fun Element.value(tag: String) = getElementsByTagName(tag).item(0).textContent
        check(xmlProgram.value("Volume").toDouble() == 1.0)
        val xmlInstruments = xml.getElementsByTagName("Instrument")
        check(xmlInstruments.length == 16)
        for (index in frames.indices) {
            val expectedMuteGroup = if (index % 4 >= 2) 1 else 0
            val instrument = instruments[index]
            check(instrument.getValue("whichMuteGroup").int() == expectedMuteGroup && instrument.getValue("triggerMode").int() == 0)
            check(instrument.getValue("mixable").obj().getValue("volume").num() == 1.0)
            val layer = instrument.getValue("layersv").arr().first().obj()
            check(layer.getValue("sliceInfo").obj().getValue("End").long() == frames[index])
            val envelope = instrument.getValue("synthSection").obj().getValue("ampEnvelope").obj()
            fun field(name: String) = envelope.getValue(name).obj().getValue("value0")
            check(field("Attack").num() == 0.0 && field("Decay").num() == 1.0 && field("Sustain").num() == 1.0 && field("OneShot").bool())
            val xmlInstrument = xmlInstruments.item(index) as Element
            check(xmlInstrument.getAttribute("number").toInt() == index)
            check(xmlInstrument.value("MuteGroup").toInt() == expectedMuteGroup && xmlInstrument.value("OneShot") == "True")
            check(xmlInstrument.value("Volume").toDouble() == 1.0 && xmlInstrument.value("SliceEnd").toLong() == frames[index])
            check(xmlInstrument.value("VolumeAttack").toDouble() == 0.0 && xmlInstrument.value("VolumeDecay").toDouble() == 0.0)
            check(xmlInstrument.value("VolumeSustain").toDouble() == 1.0 && xmlInstrument.value("VolumeDecayType") == "True")
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun readme() = """
        ONSET RAW audition kit — 16 sample pads

        MPC 3: load "$NAME.xtd" beside its unchanged _[TrackData] folder.
        MPC 2: open that folder and load "$NAME.xpm" beside its samples.

        A01–A04: BLEND 0; A05–A08: BLEND .25; A09–A12: BLEND .5; A13–A16: BLEND 1.
        Each row is KICK, SNARE, HAT_CLOSED, HAT_OPEN. All eight hats share mute group 1.
        MPC hardware numbers rows from the bottom: A01–A04 is the bottom row.

        WAVs are byte-identical copies of the selected primary RAW audition files,
        not the page's loudness-matched variants. No normalization, resampling,
        additional gain, trim, FX or new DSP is applied. Program and pad levels are
        unity; standard velocity response still applies, so use full velocity to
        compare RAW levels. Every one-shot references its full WAV frame range.
        XTD uses the native full-decay/sustain-one envelope. XPM uses zero attack,
        sustain one and zero end-relative decay, keeping the complete sample tail.

        settings.json records the generator settings; raw-sources.tsv maps every
        pad to its audition source and SHA-256. kit.json is a sample-kit sidecar,
        with no editable ONSET recipe because ONSET is not registered in SnipSnap.
        This kit has format, sample-byte, routing and envelope-field checks.
        Playback gain, envelope behavior and choke response need an MPC hardware listen.
    """.trimIndent() + "\n"
}

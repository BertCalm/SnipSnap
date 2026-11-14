package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.*
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Rendered-audio test kit, deliberately not advertised as editable PadRecipe patches. */
object BrineMpcGenerator {
    private data class Hit(val name: String, val kind: DrumClass, val snip: Snip, val mute: Int = 0)
    fun write(root: File) {
        val hits = listOf(
            Hit("Kick", DrumClass.KICK, BrineKit.render(solo=0)),
            Hit("Snare", DrumClass.SNARE, BrineKit.render(solo=1)),
            Hit("Closed Hat", DrumClass.HAT_CLOSED, BrineKit.render(solo=2), 1),
            Hit("Open Hat", DrumClass.HAT_OPEN, BrineKitExpansion.render(BrineKitExpansion.Voice.OPEN_HAT), 1),
            Hit("Half Open Hat", DrumClass.HAT_OPEN, BrineCymbals.render(BrineCymbals.Voice.HALF_HAT), 1),
            Hit("Resonant Floor Tom", DrumClass.TOM, BrineKitExpansion.renderThumpyTom()),
            Hit("Rack Tom", DrumClass.TOM, BrineKit.render(solo=3)),
            Hit("Cross Stick", DrumClass.PERC, BrineKitExpansion.render(BrineKitExpansion.Voice.CROSS_STICK)),
            Hit("Ghost Snare", DrumClass.SNARE, BrineKitExpansion.render(BrineKitExpansion.Voice.GHOST_SNARE)),
            Hit("Rimshot", DrumClass.SNARE, BrineKitExpansion.render(BrineKitExpansion.Voice.RIMSHOT)),
            Hit("Dark Ride", DrumClass.PERC, BrineCymbalReset.hit(BrineCymbals.Voice.RIDE,BrineCymbalReset.Variant.RING)),
            Hit("Thin Crash", DrumClass.PERC, BrineCymbalReset.hit(BrineCymbals.Voice.CRASH,BrineCymbalReset.Variant.FOCUSED), 2),
            Hit("Choked Crash", DrumClass.PERC, BrineCymbalReset.hit(BrineCymbals.Voice.CHOKED_CRASH,BrineCymbalReset.Variant.FOCUSED), 2))
        val destination = File(root, "mpc-test-kit").apply { mkdirs() }
        for (take in 0..1) for (wet in listOf(false, true)) {
            val name = (if (wet) "BRINE R13 Full Chain" else "BRINE R13 Before Pedals") + " Take ${take+1}"
            val work = File(root, ".brine-mpc-${if (wet) "wet" else "dry"}")
            fun process(x: Snip) = BrineExportTail.trim(if(wet) Brine.process(x,Brine.Settings(room=0f,grit=0f)) else x)
            val pads = hits.mapIndexed { index, h ->
                val dynamic = index in listOf(1,10)
                val layers = if(dynamic) BrinePerformance.velocities.map { process(if(index==10) BrineCymbalReset.playedRide(it,take) else BrinePerformance.hit(false,it,take)) } else emptyList()
                ArrangedPad(snip=layers.lastOrNull() ?: process(h.snip),
                    softVariants=if(dynamic) layers.dropLast(1) else emptyList(),
                    layerNames=if(dynamic) listOf("Soft","Medium","Hard") else emptyList(),
                    drumClass=h.kind,level=.707946f,displayName=h.name,muteGroup=h.mute,
                    source=mapOf("family" to "BRINE","revision" to "13","take" to "$take","sound" to h.name)) }
            val kit = KitAssembler.assembleArranged(name, pads, work)
            require(!Preflight.check(kit, work).blocked())
            val exported = Mpc3Exporter.exportTrack(kit, work, File(destination, name), overwrite=true, mpc2Twin=true)
            require(exported.program.isFile && exported.samples.size == 17)
            val data = exported.samples.first().parentFile
            require(File(data, "$name.xpm").isFile)
            KitStore.save(kit, data)
            val restored = KitStore.load(data)
            require(restored.pads.map { it.muteGroup } == hits.map { it.mute })
            require(restored.pads.all { it.oneShot })
            require(!Preflight.check(restored, data).blocked())
            for (sample in exported.samples) {
                val info = WavInfo.read(sample)
                require(info.sampleRate == 44100 && info.channels == 1 && info.bitsPerSample == 24)
                require(sample.readBytes().contentEquals(File(work, sample.name).readBytes()))
            }
            require(work.deleteRecursively())
        }
        File(root, "manifest.json").copyTo(File(destination, "audition-manifest.json"), overwrite=true)
        for (name in listOf("REFERENCE-CREDITS.txt", "LICENSE-CC0.txt", "reference-sources.json"))
            File(root,name).copyTo(File(destination,name),overwrite=true)
        File(destination, "README.txt").writeText("""
            BRINE R13 MPC TEST KIT — listener-selected cymbals and trimmed sample tails
            Four programs (before/full chain × take 1/2), 13 populated pads each, mono 44.1 kHz PCM24.
            MPC 3: load the .xtd with its TrackData folder alongside it.
            MPC 2: load the .xpm inside TrackData. Keep every WAV in that folder.
            Pads A01–A13:
            ${hits.mapIndexed { i, h -> "A%02d: %s".format(i+1, h.name) }.joinToString("\n")}
            A14–A16 remain empty. All pads are finite one-shots. Snare A02 and ride A11 have three velocity layers.
            Zones: 1–41 soft, 42–84 medium, 85–127 hard. Other pads retain one layer.
            Take 1/2 are separate programs, not automatic round robin.
            Ride uses the kept R10 body contour and ring, with two deterministic phase takes.
            Strike velocity drives the recording stage; the older R8 darkening filter is not applied.
            Crash and choked crash use the kept R9 focused treatment.
            Every sample is trimmed after its final processing: peak -90 dB plus 120 ms, with a quiet 10 ms fade.
            Hats share mute group 1; crash/choked crash share group 2; ride is ungrouped.
            MPC mute groups cut the entire rendered sample, including baked effect tails.
            The audition groove instead chokes the source before shared room/pedal processing.
            Likewise, separately rendered wet hits do not recreate the groove's shared nonlinear bus.
            Before Pedals includes the approved recording treatment. Full Chain bakes in the board.
            Native raw levels retained; no per-pad audition loudness matching. Mixer gain is -3 dB.
            These are rendered samples with source provenance, not editable BRINE PadRecipe patches.
            See audition-manifest.json and BrineReferenceCymbals.kt and CC0 profiles for source definitions.
            Export preflight, sample format and program references checked; MPC hardware loading unverified.
        """.trimIndent()+"\n")
        ZipOutputStream(File(root, "BRINE-MPC-test-kit.zip").outputStream()).use { zip ->
            destination.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                zip.putNextEntry(ZipEntry("BRINE MPC Test Kit/" + f.relativeTo(destination).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("Verified and packaged four 13-pad MPC test programs")
    }
}

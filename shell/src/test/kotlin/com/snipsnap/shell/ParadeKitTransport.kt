package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/** Exercise the real kit transport using pinned audition PCM. This is not a native synth renderer. */
object ParadeKitTransport {
    internal fun verifiedWav(root: File, item: JsonValue): Snip {
        val spec = item.obj()
        val file = File(root, spec.getValue("file").str()).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath())) { "Study path escapes input directory" }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        require(hash == spec.getValue("sha256").str()) { "Study PCM hash mismatch: ${file.name}" }
        return WavReader.read(file).also {
            require(it.channels == 1 && it.sampleRate == 44100 && it.samples.isNotEmpty())
            require(it.samples.all { sample -> sample.isFinite() && abs(sample) <= 1f })
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Pass study input and an empty output directory" }
        val input = File(args[0]); val output = File(args[1])
        require(!output.exists() || output.listFiles().orEmpty().isEmpty()) { "Output must be empty" }
        output.mkdirs()
        val diversityFailures = mutableListOf<String>()
        val manifestFile = File("prototype/drumline_parade/accepted-study.json")
        val manifest = Json.parse(manifestFile.readText()).obj()
        for (entry in manifest.getValue("kits").arr()) {
            val spec = entry.obj(); val id = spec.getValue("id").str(); val bpm = spec.getValue("bpm").num().toFloat()
            val recipes = spec.getValue("pads").arr(); require(recipes.size == 16)
            val pads = recipes.map { item ->
                val p = item.obj(); val name = p.getValue("name").str()
                val grid = p.getValue("grid").arr().map { row -> row.arr().map { verifiedWav(input, it) } }
                require(grid.size == 3 && grid.all { it.size == 3 })
                val type = when {
                    name.startsWith("bass") || name.startsWith("surdo") -> DrumClass.KICK
                    name.startsWith("snare") || name.startsWith("caixa") || name == "rimshot" -> DrumClass.SNARE
                    else -> DrumClass.PERC
                }
                ArrangedPad(grid.last().first(), type, displayName = name.replace('_', ' '),
                    level = if (name.startsWith("cymbal")) .55f else .8f, muteGroup = 0,
                    velocityTakes = grid, source = mapOf("study" to "drumline-parade", "revision" to manifest.getValue("revision").int().toString(),
                        "sourceCommit" to manifest.getValue("sourceCommit").str(), "kit" to id,
                        "sourceKind" to "rendered-python-study", "players" to p.getValue("players").int().toString()))
            }
            val root = File(output, id).apply { mkdirs() }; val native = File(root, "native")
            val name = if (id == "second-line") "Second Line Study" else "Samba Bateria Study"
            val assembled = KitAssembler.assembleArranged(name, pads, native, tempoBpm = bpm)
            val saved = KitStore.load(native); check(assembled == saved)
            TriadKitProof.verify(saved, native, pads)
            // The manifest stores the authored sketch; carry its timing into the actual MPC clip.
            val notes = spec.getValue("events").arr().map {
                val e = it.arr(); var beat = e[0].num()
                if (id == "second-line" && beat % 1.0 in listOf(.25, .75)) beat += .035
                Mpc3Note(Mpc3Note.noteFor(e[1].int() + 1), (beat * 960).roundToLong(), e[2].num().toFloat())
            }
            val clip = Mpc3Clip(name, 8, notes.sortedBy { it.timePulses })
            val measured = BreakerKitProof.measure(saved, native)
            measured.forEach {
                if (it.closestTakes >= BreakerKitProof.COPY_LIKENESS) diversityFailures += "$id copied takes: $it"
                if (it.leastZoneMoveDb <= BreakerKitProof.LEVEL_ONLY_DB) diversityFailures += "$id gain-only velocity: $it"
            }
            val exported = Mpc3Exporter.exportTrack(saved, native, File(root, "MPC3"), overwrite = false, mpc2Twin = true, clip = clip)
            TriadKitProof.verifyTrack(exported.program, saved)
            val data = exported.samples.first().parentFile
            check(!ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, exported.program, data, exported.findings)).blocked())
            check(!ExportReadBack.verifyProgram(saved, File(data, "${saved.name}.xpm"), ExportFormat.PROGRAM_FOLDER).blocked())
            // Resolve every MIDI velocity and two complete RR cycles against exported PCM.
            var hits = 0
            for ((i, pad) in saved.pads.withIndex()) {
                val wav = WavReader.read(File(data, pad.sampleFile)); val chain = requireNotNull(pad.chain)
                val zones = requireNotNull(chain.zones)
                for (velocity in 1..127) for (take in 0..5) {
                    val zoneIndex = zones.indexOfFirst { velocity in it.velStart..it.velEnd }; check(zoneIndex >= 0)
                    val hit = requireNotNull(PadHit.resolve(pad, velocity / 127f, take) { wav.frameCount.toLong() })
                    val expected = pads[i].velocityTakes[zoneIndex][take % 3].samples
                    check(hit.endFrameExclusive - hit.startFrame == expected.size.toLong())
                    check(expected.indices.all { abs(expected[it] - wav.samples[hit.startFrame.toInt() + it]) <= 3f / 8388608 })
                    hits++
                }
            }
            WavWriter.write(File(root, "groove.wav"), KitPreview.render(saved, native, clip, bpm), WavWriter.BitDepth.PCM_24)
            File(root, "verification.csv").writeText("pad,closest_takes,least_velocity_shape_db\n" + measured.joinToString("\n") { "${it.slot},${it.closestTakes},${it.leastZoneMoveDb}" } + "\n")
            manifestFile.copyTo(File(root, "accepted-study.json"))
            File(root, "README.txt").writeText("$name: revision ${manifest.getValue("revision").int()} audition PCM through SnipSnap native kit transport.\n" +
                "16 pads, 3 velocity zones x 3 takes; $hits exported PCM selection checks. Source hashes, save/load, actual playback windows and MPC3 layer/slice read-back passed.\n" +
                "MPC3 contains velocity plus round robin. MPC2 twin preserves velocity only (owner-accepted format limit). Hardware unverified.\n" +
                "These are rendered study packs, not editable Kotlin synthesis or factory shelf registration. The groove uses native velocity selection and mixer gains.\n")
            println("$id checked: 144 pinned WAVs, $hits selection checks, native save/load and MPC exports")
        }
        check(diversityFailures.isEmpty()) { diversityFailures.joinToString("\n") }
        File(output, "PASS.txt").writeText("Both kits pass transport and diversity; hardware and native synthesis remain unverified.\n")
        println("Both kits PASS all transport and diversity checks")
    }
}

package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.*
import com.snipsnap.xpm.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** One voice, one touch, same exported PCM and held gesture; only release routing changes. */
object FilamentHaloAuditionGenerator {
    private const val RATE = 44_100
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args.single()).canonicalFile
        require(!output.exists() || output.listFiles()?.isEmpty() == true) { "Use a fresh output directory" }
        output.mkdirs()
        val patch = FilamentPresets.forVoice(FilamentVoice.CURL).first().copy(midi = 60)
        check(patch.velocity == 1f)
        val start = System.nanoTime()
        val parts = FilamentHaloKeys.capture(patch)
        val renderMs = (System.nanoTime() - start) / 1e6
        val frames = parts.mixed.snip.frameCount.toLong()
        val marker = parts.mixed.loopStartFrame
        fun kg(stem: String, release: Float?) = Keygroup(54, 66, 60,
            listOf(VelocityLayer(stem, frames, 0, 127, loopStartFrame = marker)), release)
        val a = KeygroupProgram("Curl Shared Release", listOf(kg("mixed", null)),
            volumeRelease = .18f, explicitNoteOn = true)
        val b = KeygroupProgram("Curl Halo Release", listOf(kg("body", .18f), kg("halo", .85f)),
            volumeRelease = .18f, explicitNoteOn = true, stackZones = true)
        val programs = listOf(a to mapOf("mixed" to parts.mixed.snip),
            b to mapOf("body" to parts.body.snip, "halo" to parts.halo.snip))
        val holdFrames = frames.toInt() + (frames - marker).toInt() + RATE / 8
        val totalFrames = holdFrames + RATE * 2
        val takes = programs.mapIndexed { index, (program, samples) ->
            val folder = File(output, if (index == 0) "A" else "B")
            OneNote.writePackage(program.name, program, samples, folder, overwrite = false, spanKeyboard = false)
            val data = File(folder, Mpc3TrackWriter.trackDataDirName(program.name))
            File(data, "filament.patch.json").writeText(patch.toJsonText())
            val (sidecar, instrument) = InstrumentStore.list(folder).single()
            val decoded = instrument.sampleFiles.associateWith { WavReader.read(File(folder, it)) }
            for (zone in instrument.zones) for (layer in zone.playableLayers) {
                val sheet = requireNotNull(WavReader.readSmpl(File(folder, layer.sample)))
                check(sheet.rootNote == 60 && sheet.loop?.startFrame == marker)
                check(decoded.getValue(layer.sample).frameCount.toLong() == frames)
            }
            check(InstrumentStore.read(sidecar).toProgram() == program)
            val engine = InstrumentEngine(InstrumentEngine.Loaded(instrument, RATE,
                decoded.mapValues { it.value.samples }), RATE)
            check(engine.noteOn(60, .8f))
            val take = FloatArray(totalFrames * 2)
            val block = FloatArray(512)
            fun render(offset: Int, count: Int) {
                var done = 0
                while (done < count) {
                    val n = minOf(256, count - done)
                    engine.render(block, n)
                    block.copyInto(take, (offset + done) * 2, 0, n * 2)
                    done += n
                }
            }
            render(0, holdFrames)
            engine.noteOff(60)
            render(holdFrames, totalFrames - holdFrames)
            check(engine.activeNotes.isEmpty())
            WavWriter.write(File(output, if (index == 0) "A-shared-release.wav" else "B-halo-release.wav"),
                Snip(take, channels = 2, sampleRate = RATE))
            take
        }
        val heldError = (0 until holdFrames * 2).maxOf { abs(takes[0][it] - takes[1][it]) }
        check(heldError < 2e-7f) { "held prefix changed: $heldError" }
        fun rms(take: FloatArray, begin: Int, end: Int) = sqrt(
            (begin * 2 until end * 2).sumOf { take[it].toDouble() * take[it] } / ((end - begin) * 2))
        val tailBegin = holdFrames + RATE / 3
        val tailEnd = holdFrames + RATE * 2 / 3
        val aTail = rms(takes[0], tailBegin, tailEnd)
        val bTail = rms(takes[1], tailBegin, tailEnd)
        check(aTail == 0.0 && bTail > 1e-6)
        check(takes.all { take -> take.takeLast(RATE / 4).all { it == 0f } })
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).start()
            val value = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0)
            return value
        }
        val revision = git("rev-parse", "HEAD")
        val dirty = git("status", "--porcelain").isNotEmpty()
        val hashes = output.walkTopDown().filter { it.isFile }.sortedBy { it.path }.joinToString(",\n") {
            val hash = MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
            "    \"${it.relativeTo(output).invariantSeparatorsPath}\": \"$hash\""
        }
        File(output, "manifest.json").writeText("""
            {"prototypeVersion":1,"sourceCommit":"$revision","workingTreeDirty":$dirty,"voice":"CURL","model":3,
             "rootMidi":60,"nativeRange":[54,66],"touchLayers":1,"captureVelocity":1.0,"hostVelocity":0.8,
             "frames":$frames,"loopStartFrame":$marker,"holdFrames":$holdFrames,
             "bodyReleaseSeconds":0.18,"haloReleaseSeconds":0.85,"observedCaptureMs":$renderMs,
             "heldPrefixMaxError":$heldError,"sharedTailRms":$aTail,"haloTailRms":$bTail,
             "tailWindowAfterNoteOffSeconds":[0.333333333,0.666666667],
             "physicalPhoneValidated":false,"mpcHardwareValidated":false,"ownerListeningAcceptance":false,
             "sha256":{
            $hashes
             }}
        """.trimIndent() + "\n")
        File(output, "index.html").writeText("""
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>CURL · after the hand lifts</title><style>
            :root{color-scheme:dark}body{background:#111613;color:#e5e9df;font:18px/1.6 system-ui;max-width:760px;margin:6vh auto;padding:24px}
            h1{font:normal clamp(38px,7vw,58px)/1.1 Georgia}small{color:#d0efb8;letter-spacing:.12em;font:12px/1.5 monospace}
            article{border-top:1px solid #2b342b;padding:24px 0}h2{font-size:22px}
            audio{width:100%}a{color:#d0efb8;text-underline-offset:4px}p{max-width:65ch}
            button{background:#d0efb8;color:#111613;border:0;border-radius:3px;padding:12px 18px;cursor:pointer;font:inherit;margin-top:12px}
            :focus-visible{outline:2px solid #d6ab8c;outline-offset:5px}</style>
            <small>FILAMENT / CURL / RELEASE STUDY 01</small><h1>After the hand lifts.</h1>
            <p>The same C4 strike and sustain. A releases the whole sound in 180 ms.
            B releases the body in 180 ms and lets the halo fade for 850 ms.</p>
            <p>Note-off is at ${"%.2f".format(java.util.Locale.ROOT, holdFrames.toDouble()/RATE)} seconds. The note passes its first loop wrap before release.
            Both files use exported PCM24 at the same gain, without loudness matching.</p>
            <article><h2>A · Shared release</h2><audio id="a" controls preload="metadata" aria-label="A, shared release" src="A-shared-release.wav"></audio>
            <button data-audio="a">Hear A from just before release</button></article>
            <article><h2>B · Halo remains</h2><audio id="b" controls preload="metadata" aria-label="B, independent halo release" src="B-halo-release.wav"></audio>
            <button data-audio="b">Hear B from just before release</button></article>
            <p>Judge whether the lingering halo feels like part of the instrument, whether the body lets go cleanly,
            and whether the release distracts from the next note.</p>
            <p><a href="curl-release-prototype.zip">Download both MPC/phone test instruments and audio</a> ·
            <a href="manifest.json">Measurements and source hashes</a></p>
            <p>One voice, one touch, C4 source. Phone/reference fade times are shown above;
            exact MPC envelope times require hardware checking. This fades captured periodic sustain components;
            it does not simulate free decay after note-off. Physical phone/MPC import and owner listening remain unverified.</p>
            <p id="status" role="status"></p><script>
            const players = [...document.querySelectorAll('audio')];
            players.forEach(player => player.addEventListener('play', () => players.forEach(other => { if(other !== player) other.pause(); })));
            document.querySelectorAll('[data-audio]').forEach(button => button.addEventListener('click', () => {
                const player = document.getElementById(button.dataset.audio);
                player.currentTime = ${holdFrames.toDouble()/RATE} - 1.25;
                player.play().catch(() => { document.getElementById('status').textContent = 'Use the audio controls to start playback.'; });
            }));</script></html>
        """.trimIndent())
        File(output, "README.txt").writeText("""
            CURL RELEASE PROTOTYPE 1
            A: whole sound releases in 180 ms on phone/reference player.
            B: body releases in 180 ms, halo in 850 ms on phone/reference player.
            The same C4 capture, host velocity 0.8 and held gesture are used in both takes.
            Open index.html for listening. See manifest.json for the actual note-off frame and measurements.

            MPC 3: retain each .xty beside its complete matching _[TrackData] directory.
            MPC 2: load the .xpm inside that track-data directory, beside its WAVs.
            B uses overlapping keygroups with separate raw envelope parameters 0.18 and 0.85.
            Actual MPC durations/import and simultaneous playback require hardware checking.

            Phone: use a SnipSnap build containing the halo-release prototype. Keep each
            .instrument.json beside its complete track-data directory on the instrument shelf.
            The B sidecar is v3; earlier builds cannot read it. Do not replace your existing instruments.
            The standard synth maker still produces the established mixed programs.

            One model-3 CURL voice, one touch, MIDI range 54..66, source C4.
            Both stems share onset, clock, loop marker, FIR and gain. There is no stem leveling.
            The halo fades captured periodic sustain; it is not a new unforced physical simulation.
            Owner listening and physical phone/MPC acceptance remain open.
        """.trimIndent() + "\n")
        ZipOutputStream(File(output, "curl-release-prototype.zip").outputStream().buffered()).use { zip ->
            output.walkTopDown().filter { it.isFile && it.extension != "zip" }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(output).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        println("CURL capture ${renderMs}ms; held max error $heldError; tail RMS A=$aTail B=$bTail; output=$output")
    }
}

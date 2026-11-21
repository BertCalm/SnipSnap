package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.ChordStabs
import com.snipsnap.synth.Drawl
import com.snipsnap.synth.DrawlChords
import com.snipsnap.synth.DrawlPresets
import com.snipsnap.synth.DrawlVoice
import java.io.File

/**
 * DRAWL round 4's listening files: each voice's MAKE INSTRUMENT keys, written as the package the app
 * shelves, read back and played by the app's own sample player ([InstrumentEngine]) from the saved
 * PCM24. Per voice, under `keys/`:
 * - `<voice>-walk.wav`: the voice's major pentatonic up its two octaves, every key including the
 *   ones between sampled roots;
 * - `<voice>-touch.wav`: one key at soft, medium and hard velocity;
 * - `<voice>-takes.wav`: one key four times at medium, the two takes dealt in turn;
 * - `<voice>-chords.wav`: the chord tool's I, IV, V and I in the voice's root key, voiced by [DrawlChords].
 * `keys.txt` lists each package's sample count and size.
 */
object DrawlKeysAuditionGenerator {
    private const val RATE = 44_100

    private data class Event(val notes: List<Int>, val at: Double, val hold: Double, val velocity: Float = .65f)

    private fun play(player: InstrumentEngine, events: List<Event>, tail: Double = 2.5): Snip {
        val total = ((events.maxOf { it.at + it.hold } + tail) * RATE).toInt()
        val out = FloatArray(total)
        val block = 256
        val buffer = FloatArray(block * 2)
        val ons = events.flatMap { e -> e.notes.map { Triple((e.at * RATE).toInt(), true, it to e.velocity) } }
        val offs = events.flatMap { e -> e.notes.map { Triple(((e.at + e.hold) * RATE).toInt(), false, it to e.velocity) } }
        val queue = (ons + offs).sortedWith(compareBy({ it.first }, { if (it.second) 1 else 0 }))
        var next = 0
        var frame = 0
        while (frame < total) {
            while (next < queue.size && queue[next].first <= frame) {
                val (_, on, nv) = queue[next++]
                if (on) player.noteOn(nv.first, nv.second) else player.noteOff(nv.first)
            }
            val n = minOf(block, total - frame, (queue.getOrNull(next)?.first ?: total) - frame).coerceAtLeast(1)
            player.render(buffer, n)
            for (i in 0 until n) out[frame + i] = buffer[2 * i]
            frame += n
        }
        return Snip(out, 1, RATE)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/drawl-keys-audition").canonicalFile
        val keysDir = File(root, "keys").apply { mkdirs() }
        val report = StringBuilder()
        for (voice in DrawlVoice.entries) {
            val v = voice.name.lowercase()
            val patch = DrawlPresets.forVoice(voice).first()
            val spec = DrawlKeygroupMaker.Spec(patch)
            val name = "Drawl ${voice.name.lowercase().replaceFirstChar { it.uppercase() }} Keys"
            val packet = File(root, "instruments/$v")
            check(!packet.exists()) { "Use a fresh output directory: $packet" }
            DrawlKeygroupMaker.writeNewPackage(name, spec, DrawlKeygroupMaker.render(spec), packet)
            // From here on, only what was saved: the phone's own listing and the PCM24 on disk.
            val (sidecar, instrument) = InstrumentStore.list(packet).single()
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            fun player() = InstrumentEngine(InstrumentEngine.Loaded(instrument, RATE, samples), RATE)
            val home = Drawl.rootMidi(voice)
            val bytes = packet.walk().filter { it.isFile }.sumOf { it.length() }
            report.append("${voice.name}: ${instrument.sampleFiles.size} samples, ${"%.1f".format(bytes / 1e6)} MB, " +
                "${Scales.nameOf(spec.lowMidi)} to ${Scales.nameOf(spec.highMidi)}\n")

            val scale = (spec.lowMidi..spec.highMidi).filter { (it - home).mod(12) in setOf(0, 2, 4, 7, 9) }
            WavWriter.write(File(keysDir, "$v-walk.wav"),
                play(player(), scale.mapIndexed { i, midi -> Event(listOf(midi), i * .42, .38) }))
            WavWriter.write(File(keysDir, "$v-touch.wav"),
                play(player(), listOf(.2f, .55f, 1f).mapIndexed { i, vel -> Event(listOf(home), i * 1.6, 1.4, vel) }))
            WavWriter.write(File(keysDir, "$v-takes.wav"),
                play(player(), (0 until 4).map { i -> Event(listOf(home), i * .9, .8, .55f) }))
            val stabs = ChordStabs.stabs(KeySpec.parse(Scales.nameOf(home).filter { !it.isDigit() && it != '-' }))
            val progression = listOf(stabs[0], stabs[3], stabs[4], stabs[0])
            WavWriter.write(File(keysDir, "$v-chords.wav"),
                play(player(), progression.mapIndexed { i, stab -> Event(DrawlChords.notes(voice, stab), i * 1.8, 1.6) }))
            report.append("  chords: ${progression.joinToString { "${it.name} ${DrawlChords.notes(voice, it).map(Scales::nameOf)}" }}\n")
            println("DRAWL keys: ${voice.name}")
        }
        File(root, "keys.txt").writeText(report.toString())
        print(report)
    }
}

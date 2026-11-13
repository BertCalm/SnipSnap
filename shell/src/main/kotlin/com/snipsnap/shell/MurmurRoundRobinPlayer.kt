package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource
import java.io.File
import kotlin.math.roundToInt

/**
 * Saved-sample JVM reference player. A pitch zone owns one cursor shared across
 * all native touch layers. No capture/render callback exists here: reload reads
 * the exact admitted PCM24 files. This is not the Android/JNI player.
 *
 * Four persistent reference engines preserve earlier tails when a later attack
 * selects another take. Admission reserves at most eight active voices across all four engines;
 * released tails count until rendered out. The combined stereo reference bus
 * clamps at full scale. This is not
 * a claim that the phone's bus, voice stealing or whole-chord admission matches.
 */
class MurmurRoundRobinPlayer private constructor(
    private val zones: List<MurmurRoundRobinTakePackage.Zone>,
    private val instruments: List<InstrumentStore.Instrument>,
    private val loaded: List<InstrumentEngine.Loaded>,
    private val engines: List<InstrumentEngine>,
) : AutoCloseable {
    data class Attack(val midi: Int, val root: Int, val touchIndex: Int, val take: Int, val hit: KeyHit.Hit)
    private val cursors = IntArray(zones.size)
    private var closed = false
    val activeNotes: List<Int> get() = if (closed) emptyList() else engines.flatMap { it.activeNotes }

    /** Invalid/zero touches do not allocate a take. Native source touch and live gain stay distinct. */
    fun noteOn(midi: Int, velocity: Float): Attack? = noteOnChord(listOf(midi), velocity).singleOrNull()

    /**
     * Preflight the complete final-note chord before starting any voice or
     * advancing any cursor. Notes sharing a zone consume successive takes in
     * caller order; distinct zones advance independently. Duplicate final
     * notes and chords larger than eight are refused as a unit.
     */
    fun noteOnChord(finalNotes: List<Int>, velocity: Float): List<Attack> {
        if (closed || !velocity.isFinite() || velocity <= 0f || velocity > 1f || finalNotes.size !in 1..8 ||
            finalNotes.distinct().size != finalNotes.size || activeNotes.size + finalNotes.size > MAX_ACTIVE_VOICES) return emptyList()
        val next = cursors.copyOf()
        val midiVelocity = (velocity * 127).roundToInt().coerceIn(1, 127)
        val touch = MurmurRoundRobinTakePackage.VELOCITY_WINDOWS.indexOfFirst { midiVelocity in it }
        if (touch < 0) return emptyList()
        val plan = mutableListOf<Attack>()
        for (midi in finalNotes) {
            val zoneIndex = zones.indexOfFirst { midi in it.low..it.high }
            if (zoneIndex < 0) return emptyList()
            val take = next[zoneIndex]
            val hit = KeyHit.resolve(instruments[take], midi, velocity) { file ->
                loaded[take].sampleFor(file, zoneIndex)?.size?.toLong()
            } ?: return emptyList()
            plan += Attack(midi, zones[zoneIndex].root, touch, take, hit)
            next[zoneIndex] = (take + 1) % 4
        }
        // Every source/sample has been resolved against immutable owned load
        // state; the pure reference engines have no external queue refusal.
        for (attack in plan) check(engines[attack.take].noteOn(attack.midi, velocity))
        next.copyInto(cursors)
        return plan
    }

    /** Release all still-sounding attacks at this note, including earlier takes. */
    fun noteOff(midi: Int) { if (!closed) engines.forEach { it.noteOff(midi) } }
    /** Release every tail and restart every zone's sequence at take zero. */
    fun allOff() { if (!closed) engines.forEach { it.allOff() }; resetRoundRobin() }
    /** A sequence reset does not cut or retarget voices already sounding. */
    fun resetRoundRobin() { cursors.fill(0) }

    fun render(out: FloatArray, frames: Int) {
        require(frames in 0..MAX_RENDER_FRAMES && out.size.toLong() >= frames.toLong() * 2)
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        if (closed || frames == 0) return
        val block = FloatArray(frames * 2)
        for (engine in engines) {
            engine.render(block, frames)
            for (i in block.indices) out[i] += block[i]
        }
        for (i in 0 until frames * 2) out[i] = out[i].coerceIn(-1f, 1f)
    }

    override fun close() { allOff(); closed = true }

    companion object {
        const val MAX_ACTIVE_VOICES = 8
        const val MAX_LOADED_PCM_BYTES = 128L * 1024 * 1024
        const val MAX_RENDER_FRAMES = 8192

        fun load(name: String, root: File, outputRate: Int = WavWriter.MPC_SAMPLE_RATE): MurmurRoundRobinPlayer {
            require(outputRate > 0)
            val packet = MurmurRoundRobinInstrumentBank.validate(name, root)
            val bytes = packet.takes.sumOf { take -> take.rows.sumOf { it.frameCount.toLong() * Float.SIZE_BYTES } }
            require(bytes <= MAX_LOADED_PCM_BYTES) { "MURMUR RR reference player PCM budget exceeded" }
            // Build the map from admitted immutable rows, not a second sidecar
            // read. Every decoded cell is rebound to its admitted PCM hash.
            val instruments = packet.takes.map { take ->
                val data = Mpc3TrackWriter.trackDataDirName(take.program.name)
                val zones = take.program.keygroups.map { group ->
                    val layers = group.layers.map { layer -> InstrumentStore.Layer("$data/${layer.sampleName}.wav",
                        layer.frameCount, layer.loopStartFrame, layer.velStart, layer.velEnd) }
                    val hard = layers.last()
                    InstrumentStore.Zone(group.lowNote, group.highNote, group.rootNote, hard.sample,
                        hard.frameCount, hard.loopStartFrame, layers)
                }
                InstrumentStore.Instrument(take.program.name, packet.spec.releaseSeconds, zones, explicitNoteOn = true)
            }
            val loaded = instruments.mapIndexed { take, instrument ->
                val folder = File(root, MurmurRoundRobinInstrumentBank.takeFolder(take))
                val rows = packet.takes[take].rows.associateBy { it.sample }
                val samples = instrument.sampleFiles.associateWith { file ->
                    val row = rows.getValue(file.substringAfterLast('/'))
                    val snip = WavReader.read(File(folder, file))
                    require(snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.channels == 1 &&
                        snip.frameCount == row.frameCount && MurmurRoundRobinInstrumentSource.floatSha256(snip.samples) == row.decodedPcmSha256) {
                        "MURMUR RR sample changed while loading"
                    }
                    snip.samples
                }
                InstrumentEngine.Loaded(instrument, WavWriter.MPC_SAMPLE_RATE, samples)
            }
            return MurmurRoundRobinPlayer(MurmurRoundRobinInstrumentBank.zones(packet.spec), instruments,
                loaded, loaded.map { InstrumentEngine(it, outputRate) })
        }
    }
}

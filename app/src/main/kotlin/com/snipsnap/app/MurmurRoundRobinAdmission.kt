package com.snipsnap.app

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Admission/lifecycle state for one dedicated native RR handle and one immutable
 * committed bank. Use a freshly created handle for each controller; bank reload
 * destroys the old handle instead of reusing its ended-ID queue. Bank validation/
 * PCM loading and Android UI ownership are separate.
 * No legacy/scheduled note producer may use this handle. All calls are serialized;
 * [Port] must not re-enter this controller or throw after accepting a command.
 *
 * A zone owns a cursor shared by its three native touches. Eight outstanding IDs
 * across all takes include queued attacks, held notes and release tails. A stop
 * acceptance does not free a slot: only an actual native ended ID does so.
 */
internal class MurmurRoundRobinAdmission(zones: List<Zone>, private val releaseMs: Float, private val port: Port) {
    data class Cell(val sample: Int, val start: Long, val end: Long, val loopStart: Long = -1)
    /** Four takes, each containing soft/medium/hard cells in that order. */
    data class Zone(val low: Int, val high: Int, val root: Int, val takes: List<List<Cell>>)
    data class Command(val voiceId: Int, val sample: Int, val start: Long, val end: Long,
                       val loopStart: Long, val gain: Float, val pitch: Double)
    data class Attack(val midi: Int, val root: Int, val touch: Int, val take: Int, val command: Command)
    interface Port {
        /** True means the complete list was published; false means nothing was. */
        fun admit(chord: List<Command>): Boolean
        fun stopVoice(voiceId: Int, fadeMs: Float): Boolean
        fun drainEnded(): IntArray
    }

    private val zones = zones.map { it.copy(takes = it.takes.map { cells -> cells.toList() }) }
    private val cursors = IntArray(zones.size)
    private val outstanding = LinkedHashMap<Int, Attack>()
    private val requestedStops = LinkedHashSet<Int>()
    private val acceptedStops = LinkedHashSet<Int>()
    private var nextId = 1L

    init {
        require(releaseMs.isFinite() && releaseMs in 1f..60_000f)
        require(this.zones.isNotEmpty() && this.zones.size <= 128)
        for ((index, zone) in this.zones.withIndex()) {
            require(zone.low in 0..127 && zone.high in zone.low..127 && zone.root in zone.low..zone.high)
            require(this.zones.take(index).none { zone.low <= it.high && it.low <= zone.high })
            require(zone.takes.size == 4 && zone.takes.all { it.size == 3 })
            for (cell in zone.takes.flatten()) {
                require(cell.sample >= 0 && cell.start >= 0 && cell.end > cell.start && cell.end - cell.start >= 2)
                require(cell.loopStart == -1L || cell.loopStart in cell.start until cell.end - 1)
            }
        }
    }

    @get:Synchronized val activeCount: Int get() = outstanding.size
    @get:Synchronized val hasPendingStops: Boolean get() = requestedStops.isNotEmpty()

    /** Final MIDI chord, not chord roots: invalid/duplicate/unmapped notes refuse the whole request. */
    @Synchronized fun noteOnChord(finalNotes: List<Int>, velocity: Float): List<Attack> {
        poll()
        if (!velocity.isFinite() || velocity <= 0f || velocity > 1f || finalNotes.size !in 1..MAX_VOICES ||
            finalNotes.distinct().size != finalNotes.size || outstanding.size + finalNotes.size > MAX_VOICES ||
            nextId + finalNotes.size - 1 > Int.MAX_VALUE) return emptyList()
        val touch = when ((velocity * 127).roundToInt().coerceIn(1, 127)) { in 1..42 -> 0; in 43..84 -> 1; else -> 2 }
        val next = cursors.copyOf()
        val plan = ArrayList<Attack>(finalNotes.size)
        for ((offset, midi) in finalNotes.withIndex()) {
            val zoneIndex = zones.indexOfFirst { midi in it.low..it.high }
            if (zoneIndex < 0) return emptyList()
            val zone = zones[zoneIndex]
            val take = next[zoneIndex]
            val cell = zone.takes[take][touch]
            val command = Command((nextId + offset).toInt(), cell.sample, cell.start, cell.end,
                cell.loopStart, velocity * VOICE_LEVEL, 2.0.pow((midi - zone.root) / 12.0))
            plan += Attack(midi, zone.root, touch, take, command)
            next[zoneIndex] = (take + 1) % 4
        }
        if (!port.admit(plan.map { it.command })) return emptyList()
        next.copyInto(cursors)
        nextId += plan.size
        for (attack in plan) outstanding[attack.command.voiceId] = attack
        return plan.toList()
    }

    /** Request release of all earlier takes at this note, without touching later attacks. */
    @Synchronized fun noteOff(midi: Int) {
        for ((id, attack) in outstanding) if (attack.midi == midi && id !in acceptedStops) requestedStops += id
        flushStops()
    }

    /** Request all current releases and reset the sequence; release debt survives queue refusal. */
    @Synchronized fun allOff() {
        for (id in outstanding.keys) if (id !in acceptedStops) requestedStops += id
        resetRoundRobin()
        flushStops()
    }

    /** Sequence reset never frees, cuts, or retargets sounding voices. */
    @Synchronized fun resetRoundRobin() { cursors.fill(0) }

    /** Owner calls on its existing UI lifecycle tick, including while stop retries are pending. */
    @Synchronized fun poll() {
        for (id in port.drainEnded()) {
            outstanding.remove(id)
            requestedStops.remove(id)
            acceptedStops.remove(id)
        }
        flushStops()
    }

    private fun flushStops() {
        val iterator = requestedStops.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (port.stopVoice(id, releaseMs)) {
                acceptedStops += id
                iterator.remove()
            }
        }
    }

    companion object {
        const val MAX_VOICES = 8
        // Same per-voice live gain as KeyHit/InstrumentEngine; native bus remains its own bus.
        const val VOICE_LEVEL = 0.35f
    }
}

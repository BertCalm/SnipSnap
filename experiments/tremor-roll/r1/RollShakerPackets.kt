package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Listening-only ROLL contact radiator. A real bead/tray impulse buys a finite packet of
 * independent grains; nothing here drives a mechanical state or schedules another collision.
 * All constants below are audition shapes, not measured bead material or acoustic propagation.
 */
internal class RollShakerPackets(
    private val internalRate: Int,
    seed: Int,
    private val velocity: Double,
    private val beads: Double,
) {
    companion object {
        private const val RING_SIZE = 8192
        private const val RING_MASK = RING_SIZE - 1
        private const val LEDGER_LIMIT = 128
        private const val WINDOW_SECONDS = .010
        private const val PACKETS_PER_WINDOW = 64
    }

    private val rate = Dsp.RATE
    private val stride = internalRate / rate
    private val windowFrames = (rate * WINDOW_SECONDS).roundToInt().coerceAtLeast(1)
    private val layout = Dsp.Noise(Dsp.seedFor(seed, "packet-layout"))
    private val noiseSeed = Dsp.seedFor(seed, "packet-audio")
    private val futureAudio = DoubleArray(RING_SIZE)
    private val futureEnds = IntArray(RING_SIZE)
    // First128 packet descriptors are retained for diagnosis only. A full ledger never rejects audio.
    private val ledgerStart = IntArray(LEDGER_LIMIT)
    private val ledgerEnd = IntArray(LEDGER_LIMIT)
    private val ledgerImpulse = DoubleArray(LEDGER_LIMIT)
    private val ledgerGrains = IntArray(LEDGER_LIMIT)
    private var ledgerSize = 0
    private var ledgerFullCount = 0L
    private var rawFrame = 0
    private var windowIndex = -1
    private var windowAccepted = 0
    private var received = 0L
    private var accepted = 0L
    private var grainCount = 0L
    private var droppedRate = 0L
    private var acceptedImpulse = 0.0
    private var droppedImpulse = 0.0
    private var pending = 0
    private var queuePeak = 0
    private var maxDelay = 0
    private var maxDuration = 0
    private var previous = 0.0
    private var next = 0.0
    private var peakReadout = 0.0

    init {
        require(stride >= 1 && internalRate == rate * stride)
        require(RING_SIZE > rate * .060) // all packet samples must fit strictly inside the future horizon
    }

    fun atRawFrame(frame: Int) { rawFrame = frame }

    private fun unit(): Double = .5 + .5 * layout.next().toDouble()

    /** Called exactly once per actual floor collision, with its unmodified impulse and speed. */
    fun impact(impulse: Double, relativeSpeed: Double, position: Double) {
        require(impulse.isFinite() && impulse >= 0.0)
        if (impulse == 0.0) return
        received++
        val now = rawFrame / stride
        val thisWindow = now / windowFrames
        if (windowIndex != thisWindow) {
            windowIndex = thisWindow
            windowAccepted = 0
        }
        if (windowAccepted >= PACKETS_PER_WINDOW) {
            // This caps readout work only. The solver has already resolved and counted this collision.
            droppedRate++
            droppedImpulse += impulse
            return
        }
        windowAccepted++
        accepted++
        acceptedImpulse += impulse

        val hardness = (.25 * velocity + .75 * (relativeSpeed / .85).coerceIn(0.0, 1.0))
            .coerceIn(0.0, 1.0)
        val children = 1 + (2.0 * hardness + .40 * beads).toInt().coerceIn(0, 2)
        val spread = .012 + .018 * beads + .010 * (1.0 - hardness)
        // Linear impulse calibration with one fixed readout knee. The sqrt(N) child share
        // keeps nominal packet power comparable across counts. Overlap, filtering and
        // saturation mean this is an approximate radiation budget, not exact energy conservation.
        val requested = impulse * 1240.0 * (.65 + .35 * velocity) * (.20 + .80 * beads)
        val packetAmplitude = 2.0 * tanh(requested / 2.0)
        var packetEnd = now + 1
        var earliest = Int.MAX_VALUE
        for (child in 0 until children) {
            val delay = ((.0004 + spread * unit() + .0015 * position.coerceIn(0.0, 1.0)) * rate)
                .roundToInt().coerceAtLeast(1)
            val seconds = .0014 + .0018 * (1.0 - hardness) + .0015 * unit()
            val length = (seconds * rate).roundToInt().coerceAtLeast(8)
            require(delay + length < RING_SIZE)
            maxDelay = max(maxDelay, delay)
            maxDuration = max(maxDuration, length)
            earliest = minOf(earliest, now + delay)
            packetEnd = max(packetEnd, now + delay + length)
            val highPassHz = 2_600.0 + 1_000.0 * hardness + 900.0 * unit()
            val lowPassHz = 8_000.0 + 2_000.0 * velocity + 1_000.0 * unit()
            val hpA = 1.0 - exp(-2.0 * PI * highPassHz / rate)
            val lpA = 1.0 - exp(-2.0 * PI * lowPassHz / rate)
            val noise = Dsp.Noise(Dsp.seedFor(noiseSeed, received, child))
            // Fixed duration reference, not measured/renormalized event energy. Longer grains
            // spread one impulse budget instead of becoming louder merely through duration.
            val amplitude = packetAmplitude / sqrt(children.toDouble()) * sqrt(.003 / seconds)
            var low = 0.0
            var reject1 = 0.0
            var reject2 = 0.0
            for (i in 0 until length) {
                low += lpA * (noise.next().toDouble() - low)
                reject1 += hpA * (low - reject1)
                val high1 = low - reject1
                reject2 += hpA * (high1 - reject2)
                val high2 = high1 - reject2
                val window = sin(PI * i / (length - 1)).let { it * it }
                val index = (now + delay + i) and RING_MASK
                futureAudio[index] += amplitude * window * high2
            }
            grainCount++
        }
        futureEnds[packetEnd and RING_MASK]++
        pending++
        queuePeak = max(queuePeak, pending)
        if (ledgerSize < LEDGER_LIMIT) {
            ledgerStart[ledgerSize] = earliest
            ledgerEnd[ledgerSize] = packetEnd
            ledgerImpulse[ledgerSize] = impulse
            ledgerGrains[ledgerSize] = children
            ledgerSize++
        } else ledgerFullCount++
    }

    /** Linear4x interpolation avoids generating/iterating independent noise at176.4kHz. */
    fun sample(frame: Int): Double {
        rawFrame = frame
        if (frame % stride == 0) {
            val index = (frame / stride) and RING_MASK
            pending -= futureEnds[index]
            futureEnds[index] = 0
            previous = next
            next = futureAudio[index]
            futureAudio[index] = 0.0
        }
        val x = previous + (next - previous) * ((frame % stride + 1).toDouble() / stride)
        peakReadout = max(peakReadout, kotlin.math.abs(x))
        return x
    }

    fun diagnostics(): Map<String, Double> = java.util.Collections.unmodifiableMap(linkedMapOf(
        "receivedPackets" to received.toDouble(),
        "acceptedPackets" to accepted.toDouble(),
        "grainCount" to grainCount.toDouble(),
        "queuePeak" to queuePeak.toDouble(),
        "pendingAtEnd" to pending.toDouble(),
        "droppedRatePackets" to droppedRate.toDouble(),
        "droppedPackets" to droppedRate.toDouble(),
        "retainedLedgerCount" to ledgerSize.toDouble(),
        "ledgerFullPacketCount" to ledgerFullCount.toDouble(),
        "acceptedImpulse" to acceptedImpulse,
        "droppedImpulse" to droppedImpulse,
        "maxDelayMs" to 1000.0 * maxDelay / rate,
        "maxGrainMs" to 1000.0 * maxDuration / rate,
        "peakReadout" to peakReadout,
        "futureRingFrames" to RING_SIZE.toDouble(),
        "maxPacketsPer10msWindow" to PACKETS_PER_WINDOW.toDouble(),
    ))
}

package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Listening-only loose ROLL arrivals. Rounded R2 grain amplitude, color, envelope and noise
 * samples remain unchanged. A separate deterministic stream observes stable bead identity
 * and moves whole packets later; it never drives the solver or creates another collision.
 * Timing shapes are proposed listening controls, not measured acoustic propagation.
 */
internal class RollLoosePackets(
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
        private const val MAX_BEADS = 24
        private const val MAX_TRACE_GRAINS = 1024
    }

    private val rate = Dsp.RATE
    private val stride = internalRate / rate
    private val windowFrames = (rate * WINDOW_SECONDS).roundToInt().coerceAtLeast(1)
    private val layout = Dsp.Noise(Dsp.seedFor(seed, "packet-layout"))
    private val noiseSeed = Dsp.seedFor(seed, "packet-audio")
    // Independent of R2's layout and grain-audio streams. Offsets stay attached to bead IDs
    // across their later bounces; small per-impact jitter avoids another repeating arrival grid.
    private val arrivalSeed = Dsp.seedFor(seed, "arrival-disorder-r3")
    private val arrivalLayout = Dsp.Noise(Dsp.seedFor(arrivalSeed, "fixed-bead-arrivals"))
    private val fixedArrivalFrames = IntArray(MAX_BEADS) {
        (.056 * rate * (.5 + .5 * arrivalLayout.next().toDouble())).roundToInt()
    }
    private val beadImpactCounts = IntArray(MAX_BEADS)
    private val softening = ((beads - .20) / (.75 - .20)).coerceIn(0.0, 1.0).let {
        it * it * (3.0 - 2.0 * it)
    }
    private val originalRadiationFactor = .20 + .80 * beads
    // Count already rises through the actual solver. The dense end no longer multiplies that
    // extra population by another large readout gain; this is radiation, not dropped contacts.
    private val radiationFactor = if (softening == 0.0) originalRadiationFactor
        else originalRadiationFactor + softening *
            (minOf(originalRadiationFactor, .36) - originalRadiationFactor)
    private val pressureKnee = if (softening == 0.0) 2.0 else 2.0 + softening * (.65 - 2.0)
    private val durationMultiplier = if (softening == 0.0) 1.0 else 1.0 + .80 * softening
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
    private var observedHpMin = 0.0
    private var observedHpMax = 0.0
    private var observedLpMin = 0.0
    private var observedLpMax = 0.0
    private var extraArrivalSum = 0L
    private var extraArrivalMin = 0
    private var extraArrivalMax = 0
    private var delayedPackets = 0L
    private var traceDroppedGrains = 0L
    private val grainTrace = StringBuilder("received,child,beadIndex,impactFrame,baseDelayFrames,extraDelayFrames,startFrame,endFrame,durationFrames,amplitude,hpHz,lpHz,waveformSha256\n")
    private val waveformAggregate = java.security.MessageDigest.getInstance("SHA-256")

    fun grainTraceCsv(): String = grainTrace.toString()
    /** Finalize once, only after play has finished synthesizing. The candidate stores the result. */
    fun waveformDigestHex(): String = digestHex(waveformAggregate.digest())

    private fun digestHex(bytes: ByteArray): String {
        val alphabet = "0123456789abcdef"
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 255
            chars[2 * i] = alphabet[value ushr 4]
            chars[2 * i + 1] = alphabet[value and 15]
        }
        return String(chars)
    }

    init {
        require(stride >= 1 && internalRate == rate * stride)
        require(RING_SIZE > rate * .120) // R2 delay+grain plus <=60ms extra stays inside120ms
    }

    fun atRawFrame(frame: Int) { rawFrame = frame }

    private fun unit(): Double = .5 + .5 * layout.next().toDouble()

    /** Called exactly once per actual floor collision, with its unmodified impulse and speed. */
    fun impact(beadIndex: Int, impulse: Double, relativeSpeed: Double, position: Double) {
        require(beadIndex in 0 until MAX_BEADS)
        require(impulse.isFinite() && impulse >= 0.0)
        if (impulse == 0.0) return
        received++
        val impactOrdinal = beadImpactCounts[beadIndex]++
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
        val jitter = Dsp.Noise(Dsp.seedFor(arrivalSeed, "impact-jitter", beadIndex, impactOrdinal))
            .next().toDouble() * (.004 * rate)
        val fullExtra = (fixedArrivalFrames[beadIndex] + jitter).coerceIn(0.0, .060 * rate)
        // Explicit bypass preserves all accepted LIGHT BED samples, rather than approximately
        // rebuilding its old delays. Additional time is always causal and moves the whole packet.
        val extraArrival = if (softening == 0.0) 0 else (fullExtra * softening).roundToInt()
        extraArrivalMin = if (accepted == 1L) extraArrival else minOf(extraArrivalMin, extraArrival)
        extraArrivalMax = max(extraArrivalMax, extraArrival)
        extraArrivalSum += extraArrival
        if (extraArrival > 0) delayedPackets++

        val hardness = (.25 * velocity + .75 * (relativeSpeed / .85).coerceIn(0.0, 1.0))
            .coerceIn(0.0, 1.0)
        val children = 1 + (2.0 * hardness + .40 * beads).toInt().coerceIn(0, 2)
        val spread = .012 + .018 * beads + .010 * (1.0 - hardness)
        // Linear impulse calibration with one fixed readout knee. The sqrt(N) child share
        // keeps nominal packet power comparable across counts. Overlap, filtering and
        // saturation mean this is an approximate radiation budget, not exact energy conservation.
        val requested = impulse * 1240.0 * (.65 + .35 * velocity) * radiationFactor
        val packetAmplitude = pressureKnee * tanh(requested / pressureKnee)
        var packetEnd = now + 1
        var earliest = Int.MAX_VALUE
        for (child in 0 until children) {
            val baseDelay = ((.0004 + spread * unit() + .0015 * position.coerceIn(0.0, 1.0)) * rate)
                .roundToInt().coerceAtLeast(1)
            val delay = baseDelay + extraArrival
            val originalSeconds = .0014 + .0018 * (1.0 - hardness) + .0015 * unit()
            val seconds = if (softening == 0.0) originalSeconds else originalSeconds * durationMultiplier
            val length = (seconds * rate).roundToInt().coerceAtLeast(8)
            require(delay + length < RING_SIZE)
            maxDelay = max(maxDelay, delay)
            maxDuration = max(maxDuration, length)
            earliest = minOf(earliest, now + delay)
            packetEnd = max(packetEnd, now + delay + length)
            // Draw exactly the same two layout values as R1, in exactly the same order.
            val hpDraw = unit()
            val originalHighPassHz = 2_600.0 + 1_000.0 * hardness + 900.0 * hpDraw
            val roundedHighPassHz = 1_700.0 + 500.0 * hardness + 350.0 * hpDraw
            val highPassHz = if (softening == 0.0) originalHighPassHz
                else originalHighPassHz + softening * (roundedHighPassHz - originalHighPassHz)
            val lpDraw = unit()
            val originalLowPassHz = 8_000.0 + 2_000.0 * velocity + 1_000.0 * lpDraw
            val roundedLowPassHz = 4_200.0 + 1_000.0 * velocity + 400.0 * lpDraw
            val lowPassHz = if (softening == 0.0) originalLowPassHz
                else originalLowPassHz + softening * (roundedLowPassHz - originalLowPassHz)
            observedHpMin = if (grainCount == 0L) highPassHz else minOf(observedHpMin, highPassHz)
            observedHpMax = max(observedHpMax, highPassHz)
            observedLpMin = if (grainCount == 0L) lowPassHz else minOf(observedLpMin, lowPassHz)
            observedLpMax = max(observedLpMax, lowPassHz)
            val hpA = 1.0 - exp(-2.0 * PI * highPassHz / rate)
            val lpA = 1.0 - exp(-2.0 * PI * lowPassHz / rate)
            val noise = Dsp.Noise(Dsp.seedFor(noiseSeed, received, child))
            // Fixed duration reference, not measured/renormalized event energy. Longer grains
            // spread one impulse budget instead of becoming louder merely through duration.
            val amplitude = packetAmplitude / sqrt(children.toDouble()) * sqrt(.003 / seconds)
            var low = 0.0
            var low2 = 0.0
            var reject1 = 0.0
            var reject2 = 0.0
            val grainDigest = java.security.MessageDigest.getInstance("SHA-256")
            val digestBytes = ByteArray(8)
            for (i in 0 until length) {
                low += lpA * (noise.next().toDouble() - low)
                low2 += lpA * (low - low2)
                val roundedLow = if (softening == 0.0) low else low + softening * (low2 - low)
                reject1 += hpA * (roundedLow - reject1)
                val high1 = roundedLow - reject1
                reject2 += hpA * (high1 - reject2)
                val high2 = high1 - reject2
                val window = sin(PI * i / (length - 1)).let { it * it }
                val index = (now + delay + i) and RING_MASK
                val grainSample = amplitude * window * high2
                futureAudio[index] += grainSample
                val bits = java.lang.Double.doubleToRawLongBits(grainSample)
                for (byteIndex in 0 until 8) digestBytes[byteIndex] =
                    (bits ushr (56 - 8 * byteIndex)).toByte()
                grainDigest.update(digestBytes)
            }
            val waveformHash = grainDigest.digest()
            waveformAggregate.update(waveformHash)
            if (grainCount < MAX_TRACE_GRAINS) {
                grainTrace.append(received).append(',').append(child).append(',').append(beadIndex)
                    .append(',').append(now).append(',').append(baseDelay).append(',').append(extraArrival)
                    .append(',').append(now + delay).append(',').append(now + delay + length)
                    .append(',').append(length).append(',').append(amplitude)
                    .append(',').append(highPassHz).append(',').append(lowPassHz)
                    .append(',').append(digestHex(waveformHash)).append('\n')
            } else traceDroppedGrains++
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
        "softening" to softening,
        "radiationFactor" to radiationFactor,
        "pressureKnee" to pressureKnee,
        "grainDurationMultiplier" to durationMultiplier,
        "lowPassSecondPoleMix" to softening,
        "observedHpMinHz" to observedHpMin,
        "observedHpMaxHz" to observedHpMax,
        "observedLpMinHz" to observedLpMin,
        "observedLpMaxHz" to observedLpMax,
        "extraArrivalMinMs" to 1000.0 * extraArrivalMin / rate,
        "extraArrivalMaxMs" to 1000.0 * extraArrivalMax / rate,
        "extraArrivalMeanMs" to if (accepted == 0L) 0.0 else 1000.0 * extraArrivalSum / accepted / rate,
        "extraArrivalLimitMs" to 60.0,
        "delayedPackets" to delayedPackets.toDouble(),
        "retainedGrainTraceCount" to minOf(grainCount, MAX_TRACE_GRAINS.toLong()).toDouble(),
        "grainTraceDroppedRows" to traceDroppedGrains.toDouble(),
    ))
}

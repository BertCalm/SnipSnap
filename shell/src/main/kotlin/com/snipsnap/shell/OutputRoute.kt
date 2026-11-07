package com.snipsnap.shell

import kotlin.math.max

/**
 * Which way the sound leaves the phone, and what that costs a take.
 *
 * Bluetooth is the one route that matters to RECORD: the player hears a beat a
 * codec's worth of time after the app issued it, and every hit they play on
 * that beat is stamped against the issue time. Nothing can make the sound
 * earlier, but the take can be corrected by the same amount. This is the
 * arithmetic for both halves, kept pure so the device's `AudioDeviceInfo` and
 * `AudioDeviceCallback` stay in `:app` and the numbers stay under test.
 */
object OutputRoute {

    /** `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`. */
    const val TYPE_BLUETOOTH_A2DP = 8

    /** `AudioDeviceInfo.TYPE_BLE_HEADSET`, API 31 and later. */
    const val TYPE_BLE_HEADSET = 26

    /** `AudioDeviceInfo.TYPE_WIRED_HEADSET`. */
    const val TYPE_WIRED_HEADSET = 3

    /** `AudioDeviceInfo.TYPE_WIRED_HEADPHONES`. */
    const val TYPE_WIRED_HEADPHONES = 4

    /** `AudioDeviceInfo.TYPE_USB_DEVICE`. */
    const val TYPE_USB_DEVICE = 11

    /** `AudioDeviceInfo.TYPE_USB_HEADSET`. */
    const val TYPE_USB_HEADSET = 22

    /** `Build.VERSION_CODES.S`: where BLE audio devices first exist. */
    private const val SDK_S = 31

    /** What a Bluetooth route is assumed to cost when nothing better is known, in ms. */
    const val BLUETOOTH_DEFAULT_MS = 200

    /** The most a take is ever moved, in ms: a larger figure is a wrong reading, not a slow route. */
    const val MAX_COMPENSATION_MS = 400

    /** The first of Bluetooth, wired, USB that is plugged in, else the speaker. [types] are `AudioDeviceInfo.getType()` of the outputs. */
    fun classify(types: Collection<Int>, sdk: Int): MidiReport.Route = when {
        isBluetooth(types, sdk) -> MidiReport.Route.BLUETOOTH
        TYPE_WIRED_HEADPHONES in types || TYPE_WIRED_HEADSET in types -> MidiReport.Route.WIRED
        TYPE_USB_HEADSET in types || TYPE_USB_DEVICE in types -> MidiReport.Route.USB
        else -> MidiReport.Route.SPEAKER
    }

    /** True when an A2DP sink, or on API 31+ a BLE headset, is among the outputs. */
    fun isBluetooth(types: Collection<Int>, sdk: Int): Boolean =
        TYPE_BLUETOOTH_A2DP in types || (sdk >= SDK_S && TYPE_BLE_HEADSET in types)

    /**
     * The output latency, in ms, the stream has actually shown: the frames the
     * callback has rendered, less the frame the hardware last presented
     * ([presentedFrame] at [presentedNanos]), as time at [rate], less how long
     * ago that presentation was ([nowNanos]). Null when any input is unusable or
     * the answer is not above 0 and at most [MAX_COMPENSATION_MS]: a zero or
     * negative figure is an unfilled timestamp, not a miracle.
     */
    fun measuredMs(framesRendered: Long, presentedFrame: Long, presentedNanos: Long, nowNanos: Long, rate: Int): Double? {
        if (rate <= 0 || presentedNanos <= 0L || presentedFrame < 0L) return null
        val queuedMs = (framesRendered - presentedFrame) * 1000.0 / rate
        val sinceMs = (nowNanos - presentedNanos) / 1_000_000.0
        val ms = queuedMs - sinceMs
        return ms.takeIf { it.isFinite() && it > 0.0 && it <= MAX_COMPENSATION_MS }
    }

    /**
     * The latency a take is corrected by, in whole ms, 0..[MAX_COMPENSATION_MS].
     *
     * The player's own offset wins when they set one ([manualMs] non-null).
     * Otherwise the measured figure ([measured], null when the stream gave no
     * usable timestamp). On Bluetooth the measured figure is only a floor: an
     * A2DP timestamp does not include the codec's delay, so the assumed
     * [BLUETOOTH_DEFAULT_MS] is the smallest it is allowed to be. On any other
     * route with nothing measured, no correction.
     */
    fun compensationMs(manualMs: Int?, measured: Double?, bluetooth: Boolean): Int {
        val ms = when {
            manualMs != null -> manualMs.toDouble()
            bluetooth -> max(measured ?: 0.0, BLUETOOTH_DEFAULT_MS.toDouble())
            else -> measured ?: 0.0
        }
        return ms.coerceIn(0.0, MAX_COMPENSATION_MS.toDouble()).let { Math.round(it).toInt() }
    }

    /**
     * When a touch stamped at [uptimeMillis] was HEARD to land, against a clock
     * that stamps when the beat was ISSUED: the touch's own time less the
     * [latencyMs] the sound took to reach the ear. The count-in's early-open
     * gate reads this same value, so a tap on the click is not early or late
     * by the route.
     */
    fun hitNanos(uptimeMillis: Long, latencyMs: Int): Long =
        uptimeMillis * 1_000_000L - latencyMs.coerceIn(0, MAX_COMPENSATION_MS) * 1_000_000L
}

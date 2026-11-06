package com.snipsnap.shell

/**
 * Where the dock's LISTEN takes its sound from: the room through the mic, or another app's audio
 * through Android's playback capture (UAT E2-CHOOSER, ruled 3 Nov 2026).
 *
 * The first LISTEN asks, in the capture sheet; the answer is kept and the sheet is skipped after
 * it. SETUP changes it. What is kept is only this question: APP AUDIO still raises Android's own
 * screen-capture consent on every arm (Android 14+ honours each consent for one projection), so
 * remembering the source never remembers the consent.
 */
enum class CaptureSource(
    /** The word the dock, the sheet and SETUP say for it. */
    val label: String,
    /** What is stored. Never the enum name, so a rename cannot orphan a stored choice. */
    val stored: String,
) {
    MIC("MIC", "mic"),
    APP_AUDIO("APP AUDIO", "app_audio");

    companion object {
        /** A stored value read back, or null for none or anything this build does not know (an older or newer file). */
        fun parse(raw: String?): CaptureSource? = entries.firstOrNull { it.stored == raw }
    }
}

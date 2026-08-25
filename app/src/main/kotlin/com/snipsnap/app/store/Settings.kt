package com.snipsnap.app.store

import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import java.io.File

/** The two choices Tape Properties owns. */
interface SettingsStore {
    var schemeId: SchemeId
    var personality: Personality
}

/**
 * Settings as two lines of `key=value` in the app's files directory.
 *
 * A plain [File] rather than `SharedPreferences`: it keeps the whole
 * settings layer testable off-device, and two enum values do not justify
 * an Android dependency. A value the current build cannot parse — an
 * older file, a hand-edited one — falls back to the default rather than
 * refusing to start.
 */
class FileSettings(private val file: File) : SettingsStore {

    private val values: MutableMap<String, String> = read()

    override var schemeId: SchemeId
        get() = values[KEY_SCHEME]?.let { raw ->
            SchemeId.entries.firstOrNull { it.name == raw }
        } ?: SchemeId.CHROME
        set(value) {
            values[KEY_SCHEME] = value.name
            write()
        }

    override var personality: Personality
        get() = values[KEY_PERSONALITY]?.let { raw ->
            Personality.entries.firstOrNull { it.name == raw }
        } ?: Personality.FULL
        set(value) {
            values[KEY_PERSONALITY] = value.name
            write()
        }

    private fun read(): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        return try {
            file.readLines()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
                .toMap(mutableMapOf())
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun write() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        } catch (e: Exception) {
            // A settings write that fails must never take the app down; the
            // choice simply does not survive the session.
        }
    }

    private companion object {
        const val KEY_SCHEME = "scheme"
        const val KEY_PERSONALITY = "personality"
    }
}

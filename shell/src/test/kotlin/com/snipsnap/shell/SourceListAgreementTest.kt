package com.snipsnap.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Two quantities this repo keeps restating, and that have already gone
 * stale once each: the native engine's source list (production CMake and
 * the host harness name it separately) and the menu row's length (the
 * row grew to thirteen while comments still said nine, ten, and twelve).
 *
 * Both laws read the files. Neither compiles anything.
 */
class SourceListAgreementTest {

    private val numberWord = mapOf(
        9 to "nine",
        10 to "ten",
        11 to "eleven",
        12 to "twelve",
        13 to "thirteen",
        14 to "fourteen",
        15 to "fifteen",
        16 to "sixteen",
    )

    @Test
    fun `the host harness compiles the same engine sources the APK links`() {
        val prod = File("../app/src/main/cpp/CMakeLists.txt")
        val harness = File("../app/src/main/cpp/test/CMakeLists.txt")
        assertTrue(prod.isFile, "missing ${prod.path}")
        assertTrue(harness.isFile, "missing ${harness.path}")
        val production = Regex("""(?m)^\s+(\w+\.cpp)\s*$""")
            .findAll(prod.readText())
            .map { it.groupValues[1] }
            .toSet()
        val host = Regex("""ENGINE_DIR\}/(\w+\.cpp)""")
            .findAll(harness.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(production.isNotEmpty(), "parsed no .cpp names from ${prod.path} — the pattern no longer matches add_library")
        assertEquals(
            production,
            host,
            "The production library and the host harness name different engine sources. " +
                "A .cpp added to one and not the other is either uncovered by native-tests " +
                "or missing from the APK. Production: $production. Harness: $host.",
        )
    }

    @Test
    fun `comments that count the menu tabs match the MenuItems Chrome declares`() {
        val chrome = read("../app/src/main/kotlin/com/snipsnap/app/ui/Chrome.kt")
        // A quoted label, not the data-class constructor and not the comment
        // that mentions the pattern. Every current tab label is uppercase.
        val tabs = Regex("""MenuItem\("[A-Z]""").findAll(chrome).count()
        val word = numberWord[tabs]
            ?: fail("menu has $tabs tabs, and this law has no number-word for that count — add one, then update the comments")
        assertTrue(
            chrome.contains("The $word tabs,"),
            "MenuRow's KDoc must open on the count MENU_GROUPS declares ($tabs, \"$word\"). " +
                "A second spelling of that count is how the row's comments went stale.",
        )
        assertTrue(
            chrome.contains("A menu of $word fits no"),
            "SPLIT's KDoc cites the menu length ($word) as the reason it is not a tab.",
        )
        val workshop = read("../shell/src/main/kotlin/com/snipsnap/shell/Workshop.kt")
        assertTrue(
            workshop.contains("the menu row holds $word"),
            "Workshop.kt restates the menu length and must say \"$word\" ($tabs tabs).",
        )
        val workshopDoc = read("../docs/WORKSHOP.md")
        assertTrue(
            workshopDoc.contains("a menu of $word fits no phone"),
            "docs/WORKSHOP.md quotes the menu-length comment and must say \"$word\".",
        )
    }

    @Test
    fun `SynthScreen's engine kdoc counts the enum it declares`() {
        val src = read("../app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt")
        val body = Regex("""private enum class Engine \{([^}]+)\}""")
            .find(src)
            ?.groupValues
            ?.get(1)
            ?: fail("SynthScreen no longer declares `private enum class Engine` in the shape this law reads")
        val count = body.split(',')
            .map { it.trim().trimEnd(';').trim() }
            .count { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("/*") }
        val word = numberWord[count]
            ?: fail("Engine has $count entries and this law has no number-word for that — add one, then update the KDoc")
        assertTrue(
            src.contains("All $word engines in this enum"),
            "The adapter KDoc must count the enum ($count, \"$word\"). The picker and the comment drifted apart once already.",
        )
    }

    private fun read(path: String): String {
        val file = File(path)
        assertTrue(file.isFile, "expected to find ${file.path}")
        return file.readText()
    }
}

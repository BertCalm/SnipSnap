package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * [MidiBinding]s to text and back, for the app to keep in its preferences.
 *
 * JSON through the house's own `:json`, versioned, read forgivingly: a
 * binding whose kind, mode or numbers this build does not know is dropped on
 * its own rather than losing every mapping, so a saved file from a newer or
 * older build never costs the player more than the entries it cannot read.
 * Text that is not this format at all reads as no bindings.
 */
object MidiLearnStore {

    const val VERSION = 1

    /** Where the app keeps [write]'s text: one key in its preferences for every screen's bindings. */
    const val PREFS_KEY = "midi.learn"

    /** The bindings that belong to [screen]: its targets are named `screen.something`. */
    fun of(screen: String, bindings: List<MidiBinding>): List<MidiBinding> =
        bindings.filter { it.target.startsWith("$screen.") }

    /**
     * [all] with [screen]'s bindings replaced by [mine], so one screen saving
     * what it learned never drops another screen's. A control may drive one
     * thing on each screen; within a screen, [MidiLearn] keeps it one to one.
     */
    fun replace(all: List<MidiBinding>, screen: String, mine: List<MidiBinding>): List<MidiBinding> {
        require(mine.all { it.target.startsWith("$screen.") }) { "a binding saved for $screen names another screen's target" }
        return all.filterNot { it.target.startsWith("$screen.") } + mine
    }

    /** [screen]'s bindings out of the text kept under [PREFS_KEY] (null when nothing is kept yet). */
    fun load(text: String?, screen: String): List<MidiBinding> = of(screen, read(text))

    /** The text to keep under [PREFS_KEY] once [screen] has learned [mine], every other screen's bindings kept. */
    fun save(text: String?, screen: String, mine: List<MidiBinding>): String = write(replace(read(text), screen, mine))

    fun write(bindings: List<MidiBinding>): String = Json.write(
        JsonValue.Obj(
            linkedMapOf(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "bindings" to JsonValue.Arr(bindings.map(::entry)),
            ),
        ),
    )

    fun read(text: String?): List<MidiBinding> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parse(text).obj()
        } catch (e: JsonException) {
            return emptyList()
        }
        if (root["version"]?.let { (it as? JsonValue.Num)?.value } != VERSION.toDouble()) return emptyList()
        val items = (root["bindings"] as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull(::binding)
    }

    private fun entry(b: MidiBinding): JsonValue {
        val fields = linkedMapOf<String, JsonValue>()
        b.source.device?.let { fields["device"] = JsonValue.Str(it) }
        fields["kind"] = JsonValue.Str(b.source.kind.name)
        fields["channel"] = JsonValue.Num(b.source.channel.toDouble())
        fields["number"] = JsonValue.Num(b.source.number.toDouble())
        fields["target"] = JsonValue.Str(b.target)
        fields["mode"] = JsonValue.Str(b.mode.name)
        fields["takeover"] = JsonValue.Str(b.takeover.name)
        fields["min"] = JsonValue.Num(b.min.toDouble())
        fields["max"] = JsonValue.Num(b.max.toDouble())
        return JsonValue.Obj(fields)
    }

    private fun binding(v: JsonValue): MidiBinding? = try {
        val o = v.obj()
        val source = MidiBinding.Source(
            device = (o["device"] as? JsonValue.Str)?.value,
            kind = MidiBinding.Kind.valueOf(o.getValue("kind").str()),
            channel = o.getValue("channel").int().also { require(it in 0..15) },
            number = o.getValue("number").int().also { require(it in 0..127) },
        )
        MidiBinding(
            source = source,
            target = o.getValue("target").str(),
            mode = MidiBinding.Mode.valueOf(o.getValue("mode").str()),
            takeover = MidiBinding.Takeover.valueOf(o.getValue("takeover").str()),
            min = o.getValue("min").num().toFloat(),
            max = o.getValue("max").num().toFloat(),
        )
    } catch (e: JsonException) {
        null
    } catch (e: IllegalArgumentException) {
        // An unknown enum name, a number out of range, or a binding MidiBinding refuses.
        null
    } catch (e: NoSuchElementException) {
        null
    }
}

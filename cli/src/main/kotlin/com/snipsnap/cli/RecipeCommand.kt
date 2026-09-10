package com.snipsnap.cli

import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.RecipeReplay
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap recipe <kit-dir> <pad> --from <kit-dir>:<pad>` — DO IT
 * AGAIN from the terminal: the source pad's last treatment, replayed on
 * the destination through the model's own doors. A recipe that only
 * names something the replay would need (a MUTATE parent, SPLICE's
 * takes, a room) is refused by name, never skipped.
 */
object RecipeCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--from"), boolean = emptySet())
        val dirArg = opts.positional.getOrNull(0)
            ?: throw CliError("recipe wants a kit, a pad and a source: snipsnap recipe <kit-dir> A03 --from <kit-dir>:A02")
        val kitDir = File(dirArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $dirArg")
        val padArg = opts.positional.getOrNull(1)
            ?: throw CliError("which pad? (A01..A16, B01.., or a slot number)")
        val slot = parsePad(padArg)

        val from = opts["--from"] ?: throw CliError("--from wants a source: --from <kit-dir>:A02")
        val colon = from.lastIndexOf(':')
        if (colon <= 0 || colon == from.length - 1) throw CliError("--from wants <kit-dir>:<pad>, got '$from'")
        val fromDir = File(from.substring(0, colon))
        if (!File(fromDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): ${fromDir.path}")
        val fromPadArg = from.substring(colon + 1)
        val fromSlot = parsePad(fromPadArg)
        val sourceKit = KitStore.load(fromDir)
        val sourcePad = sourceKit.pad(fromSlot) ?: throw CliError("no pad on ${fromDir.name} $fromPadArg")
        val clip = RecipeReplay.clip(sourcePad.recipe, sourceKit.name, fromSlot)
            ?: throw CliError("${fromDir.name} $fromPadArg carries no recipe - nothing to copy")

        val model = KitBuilderModel.open(kitDir)
        val target = model.pad(slot) ?: throw CliError("no pad on $padArg")
        val plan = RecipeReplay.plan(clip.recipe)
        if (plan is RecipeReplay.Plan.Refused) throw CliError(plan.reason.lowercase())
        val done = RecipeReplay.apply(model, slot, clip.recipe, target.displayName.uppercase())
        model.save()
        out.println("pad $padArg <- ${clip.word.lowercase()} from ${clip.from.lowercase()}")
        out.println("  ${done.toast.lowercase()}")
        out.println("  ${done.pad.sampleFile} re-rendered in place; re-export to hear it on the card")
        return 0
    }

    /** "A03" → 3, "B01" → 17, "7" → 7. */
    private fun parsePad(arg: String): Int {
        arg.toIntOrNull()?.let { return it }
        val m = Regex("^([A-Ha-h])(\\d{1,2})$").matchEntire(arg.trim())
            ?: throw CliError("can't read pad '$arg' - try A03, B01, or a slot number")
        val bank = m.groupValues[1].uppercase()[0] - 'A'
        val n = m.groupValues[2].toInt()
        if (n !in 1..16) throw CliError("pad number in a bank is 1..16, got $n")
        return bank * 16 + n
    }
}

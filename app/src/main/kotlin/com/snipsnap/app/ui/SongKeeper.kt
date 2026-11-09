package com.snipsnap.app.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snipsnap.kit.AnswerStore
import com.snipsnap.kit.ClipRef
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.Kit
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.SongPlan
import com.snipsnap.kit.SongPlanStore
import com.snipsnap.shell.Arranger
import com.snipsnap.shell.Copy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The song SONG shows, and every edit to it: what ARRANGE holds between
 * the plan on disk and the rows on screen. The plan a person keeps is
 * [kept] (names, repeats, locks, seeds, see [SongPlan]); [plan] is that
 * laid out against the kit as it is now ([Arranger.resolve]), which is
 * what the rows, PLAY and EXPORT read.
 *
 * Every edit is applied to [kept] at once, written to `song.json` in the
 * background, and then re-laid-out; the last edit's layout wins. The write
 * is not tied to the screen, so leaving straight after a tap still keeps
 * the edit. With no [projectDir] (no project is open) the song is
 * edited on screen only, and [keeps] says so.
 *
 * A plain class holding snapshot state rather than a pile of `remember`s in
 * the screen: ARRANGE's composable sits close to the dex method-size cliff
 * (`docs/`, the SynthScreen incident), and state it did not have to declare
 * is registers it did not spend (SynthScreen hit the 256-register dex
 * limit on 2026-11-01, which is also why this screen's pieces are separate
 * composables).
 */
internal class SongKeeper(
    private val kit: Kit,
    private val kitDir: File,
    private val projectDir: File?,
    private val scope: CoroutineScope,
    private val swingPercent: Int?,
    private val feel: Float,
    private val feelTemplate: GrooveFeel.Template?,
    private val toast: (String) -> Unit,
) {
    /** One step back: what was undone, the plan before it, and the row that was selected. */
    class Undo(val verb: String, val plan: SongPlan, val selected: Int)

    /** The song laid out against the kit now; null while loading, or when it cannot be (see [refusal]). */
    var plan by mutableStateOf<Arranger.Arrangement?>(null)
        private set

    /** The song as kept. */
    var kept by mutableStateOf<SongPlan?>(null)
        private set

    var refusal by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    /** Every clip a section can be swapped to, by name. */
    var picks by mutableStateOf<List<Arranger.Pick>>(emptyList())
        private set

    /** What the person called their patterns, by program number: a section that plays one shows the name. */
    var names by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    var undoStack by mutableStateOf<List<Undo>>(emptyList())
        private set

    /** Whether the kit has an Answer bassline for a section's BASS to play. */
    var hasBass by mutableStateOf(false)
        private set

    /** The row the editor is on. */
    var selected by mutableIntStateOf(0)

    /** Whether an edit is kept for next time: a project is open to keep it in. */
    val keeps: Boolean get() = projectDir != null

    private var layout: Job? = null

    private class Opened(val kept: SongPlan, val plan: Arranger.Arrangement, val picks: List<Arranger.Pick>, val refused: List<String>, val save: Boolean, val names: Map<Int, String>, val hasBass: Boolean)

    /**
     * Reads the kept song, or drafts one: the first open of a kit with
     * nothing kept is `Arranger.arrange(seed = 0)`, as it was before songs
     * were kept, and is kept from then on. A kept name that no longer
     * resolves is planned again and said by name, once, and the planned
     * song is what is kept.
     */
    suspend fun open() {
        loading = true
        val opened = withContext(Dispatchers.IO) { runCatching { read() } }
        opened.onSuccess { o ->
            kept = o.kept
            plan = o.plan
            picks = o.picks
            names = o.names
            hasBass = o.hasBass
            refusal = null
            if (o.refused.isNotEmpty()) toast(Copy.songRefused(o.refused))
            if (o.save) persist(o.kept)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            plan = null
            kept = null
            refusal = e.message ?: Copy.ARRANGE_NEEDS_GROOVE
        }
        loading = false
    }

    private fun read(): Opened {
        val stored = projectDir?.let { SongPlanStore.load(it, kitDir.name) }
        val picks = Arranger.available(kit, kitDir, swingPercent, feel, feelTemplate)
        val bass = AnswerStore.load(kitDir) != null
        if (stored == null) {
            val draft = Arranger.arrange(kit, kitDir, 0, swingPercent, feel, feelTemplate)
            return Opened(Arranger.toPlan(draft), draft, picks, emptyList(), save = projectDir != null, names = GrooveEdit.namesById(kitDir), hasBass = bass)
        }
        val resolved = Arranger.resolve(kit, kitDir, stored, swingPercent, feel, feelTemplate)
        return Opened(Arranger.toPlan(resolved.arrangement), resolved.arrangement, picks, resolved.refused, save = resolved.refused.isNotEmpty(), names = GrooveEdit.namesById(kitDir), hasBass = bass)
    }

    // ---- edits ----

    /** The selected section moves [delta] places; the selection goes with it. */
    fun move(delta: Int) {
        val k = kept ?: return
        edit(Copy.SONG_VERB_REORDER, k.moved(selected, delta) ?: return, selected + delta)
    }

    /** The selected section plays [delta] more times (fewer if negative), held to what one sequence holds. */
    fun repeat(delta: Int) {
        val k = kept ?: return
        val shown = plan?.sections?.getOrNull(selected) ?: return
        val now = k.sections.getOrNull(selected)?.repeats ?: return
        val to = (now + delta).coerceIn(1, Arranger.maxRepeats(shown.clip))
        if (to == now) return
        edit(Copy.SONG_VERB_REPEATS, k.withRepeats(selected, to) ?: return)
    }

    /** The selected section plays [ref] instead; its repeats are cut to what that clip's length allows. */
    fun swap(ref: String) {
        val k = kept ?: return
        val section = k.sections.getOrNull(selected) ?: return
        if (section.clip == ref) return
        val bars = picks.firstOrNull { it.ref == ref }?.bars ?: return
        val cut = minOf(section.repeats, Arranger.maxRepeats(bars))
        edit(Copy.SONG_VERB_SWAP, (k.withClip(selected, ref) ?: return).withRepeats(selected, cut) ?: return)
    }

    fun duplicate() {
        val k = kept ?: return
        edit(Copy.SONG_DUPLICATE, k.duplicated(selected) ?: return, selected + 1)
    }

    fun delete() {
        val k = kept ?: return
        edit(Copy.SONG_DELETE, k.without(selected) ?: return, minOf(selected, k.sections.size - 2))
    }

    /** A new section at the end playing the captured groove once. */
    fun add() {
        val k = kept ?: return
        edit(Copy.SONG_ADD, k.added(ClipRef.CAPTURED) ?: return, k.sections.size)
    }

    /** The selected section's bass under it, on or off; the change is named by the state it lands in. */
    fun toggleBass() {
        val k = kept ?: return
        val now = !(k.sections.getOrNull(selected)?.bass ?: return)
        edit(if (now) Copy.SONG_BASS_ON else Copy.SONG_BASS_OFF, k.withBass(selected, now) ?: return)
        if (now && !hasBass) toast(Copy.SONG_BASS_NONE_YET)
    }

    /** The pull-up into the selected section, on or off. The first section has nothing before it to pull up. */
    fun togglePullUp() {
        val k = kept ?: return
        if (selected == 0) return
        val now = !(k.sections.getOrNull(selected)?.pullUp ?: return)
        edit(if (now) Copy.SONG_PULL_UP_ON else Copy.SONG_PULL_UP_OFF, k.withPullUp(selected, now) ?: return)
    }

    fun toggleLock() {
        val k = kept ?: return
        val now = !(k.sections.getOrNull(selected)?.locked ?: return)
        edit(if (now) Copy.SONG_LOCK else Copy.SONG_UNLOCK, k.withLock(selected, now) ?: return)
    }

    /** True while a reroll is being drawn; a second tap meanwhile is dropped. */
    var rerolling by mutableStateOf(false)
        private set

    /**
     * Redraws the unlocked sections with a new seed ([Arranger.rerolled]:
     * each derived clip drawn again from its family, a fill re-taken, your
     * own programs and the order kept) and says what it did. When nothing
     * unlocked can change (every one plays your own program), it says that
     * instead of pretending. The draw reads the kit's grooves, so it runs
     * off the main thread; an edit made meanwhile wins and the draw is dropped.
     */
    fun reroll() {
        val k = kept ?: return
        if (rerolling) return
        val unlocked = k.sections.count { !it.locked }
        if (unlocked == 0) {
            toast(Copy.SONG_ALL_LOCKED)
            return
        }
        rerolling = true
        scope.launch {
            try {
                val drawn = withContext(Dispatchers.IO) {
                    runCatching { Arranger.rerolled(kit, kitDir, k, k.seed + 1, swingPercent, feel, feelTemplate) }
                }
                if (kept !== k) return@launch
                drawn.onSuccess { r ->
                    if (r.redrawn == 0) {
                        toast(Copy.SONG_REROLL_NOTHING)
                    } else {
                        edit(Copy.SONG_VERB_REROLL, r.plan)
                        toast(Copy.songRerolled(r.redrawn, k.sections.size - unlocked))
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "reroll: failed", e)
                    toast(e.message ?: Copy.ARRANGE_NEEDS_GROOVE)
                }
            } finally {
                rerolling = false
            }
        }
    }

    /** Puts back the song before the last edit. */
    fun undo() {
        val last = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        apply(last.plan, last.selected)
        toast(Copy.songUndone(last.verb))
    }

    private fun edit(verb: String, next: SongPlan, select: Int = selected) {
        val before = kept ?: return
        undoStack = (undoStack + Undo(verb, before, selected)).takeLast(MAX_UNDO)
        apply(next, select)
    }

    private fun apply(next: SongPlan, select: Int) {
        kept = next
        selected = select.coerceIn(0, next.sections.lastIndex)
        persist(next)
        layout?.cancel()
        layout = scope.launch {
            val laid = withContext(Dispatchers.IO) {
                runCatching { Arranger.resolve(kit, kitDir, next, swingPercent, feel, feelTemplate) }
            }
            laid.onSuccess { plan = it.arrangement; refusal = null }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "layout: failed", e)
                    plan = null
                    refusal = e.message ?: Copy.ARRANGE_NEEDS_GROOVE
                }
        }
    }

    private fun persist(next: SongPlan) {
        val dir = projectDir ?: return
        val folder = kitDir.name
        writes.launch {
            try {
                SongPlanStore.save(dir, folder, next)
            } catch (e: Exception) {
                Log.e(TAG, "persist: the song did not save", e)
                withContext(NonCancellable + Dispatchers.Main) { toast(Copy.SONG_NOT_KEPT) }
            }
        }
    }

    private companion object {
        const val TAG = "SongKeeper"
        const val MAX_UNDO = 30

        /**
         * Writes of the kept song, one at a time and in the order they were
         * asked for, on a scope of their own: an edit made just before BACK
         * is still kept, which a write on the screen's scope would lose.
         */
        val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    }
}

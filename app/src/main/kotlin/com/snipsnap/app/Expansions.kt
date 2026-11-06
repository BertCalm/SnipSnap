package com.snipsnap.app

import com.snipsnap.shell.ExpansionMembers
import com.snipsnap.shell.ExpansionStore
import java.io.File

/** The phone's expansions: `<files>/expansions`, beside the shelf's own `<files>/Kits`. */
fun KitShelf.expansionStore(): ExpansionStore = ExpansionStore(File(root.absoluteFile.parentFile, "expansions"))

/**
 * Drops from every expansion the kits that are gone for good: not on the
 * shelf and not in its bin. Run where the shelf forgets kits for ever (the
 * bin's sweep and EMPTY THE BIN NOW); a kit in the bin stays a member and
 * rejoins on restore, and a shelf with a kit that would not read prunes
 * nothing. Callers hold [KitWrites.mutex].
 */
fun KitShelf.pruneExpansions(): Int? = ExpansionMembers.pruneSafely(expansionStore(), root, binDir)

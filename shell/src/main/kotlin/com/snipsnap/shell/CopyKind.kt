package com.snipsnap.shell

/**
 * What kind of words a piece of shipped copy is, so a law can tell them apart
 * (clarity spec 7.5, owner ruling D3). Copy is split three ways:
 *
 * - **Message**: a sentence. It is the default, so a constant with no mark is
 *   one. Sentence case, ends in a full stop, `…` (work in progress) or `?`.
 * - **[CopyLabel]**: a name the player taps or reads as a heading: place and stage
 *   tabs, buttons, section titles, chip text, pad ids. CAPS.
 * - **[CopyCaption]**: a sentence fragment read beside a control, such as a hint,
 *   a legend, a row description or a door's one-line description. Sentence case,
 *   and no full stop needed.
 *
 * `PersonalityTest` reads the marks by reflection on the static fields of `Copy`
 * and `Places`, so a new constant is held to the law for its kind the moment
 * it is added. Put the mark on the field: `@field:CopyLabel const val X = "…"`.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class CopyLabel

/** See [CopyLabel]. A caption is sentence case without the full-stop requirement. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class CopyCaption

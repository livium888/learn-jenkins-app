package com.flashcardreader.app.data.repository

/**
 * Hands out safe card ids for an imported review history.
 *
 * The ids in a backup belong to another device's database. Reused as-is they would collide with
 * this one's, and since the fit works by grouping a card's reviews over time, a collision does not
 * fail loudly - it quietly merges two unrelated cards' histories and skews the schedule. Room
 * counts ids upwards from one, so negatives are permanently free: each imported card gets one, the
 * same card always gets the same one, and no local card can ever be handed it.
 *
 * Pure and separate so this is covered by tests rather than by noticing a wrong schedule months on.
 */
internal class ImportedCardIds {
    private val assigned = mutableMapOf<Pair<Long, String>, Long>()
    private var next = -1L

    fun idFor(originalId: Long, cardKind: String): Long =
        assigned.getOrPut(originalId to cardKind) { next-- }
}

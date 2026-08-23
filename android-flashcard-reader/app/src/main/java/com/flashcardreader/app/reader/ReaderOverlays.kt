package com.flashcardreader.app.reader

/**
 * Which prompt, if any, is on top of the text - and therefore whether reading should be accruing.
 *
 * This exists because those two questions were answered in two places that quietly disagreed, and
 * the disagreement deadlocked the reader.
 *
 * A comprehension question is *written* at 75% of the way to the interval, so it is ready the
 * moment it is due. Accrual, meanwhile, was suppressed whenever a question merely existed. So from
 * 75% onwards the tracker was told nothing was being read, the word count stopped, it never
 * reached 100%, the question never became due, and it was therefore never shown or cleared. The
 * reader sat there permanently: no credit, no questions, and a status report frozen at the same
 * number no matter how much was read. A question that came round again rather than being written
 * fresh hit it every time, having no network call to lose the race to.
 *
 * So the rule is: text is covered by what is actually *drawn*, never by what is merely prepared.
 * Both the screen and the credit loop now read that from here, and cannot drift apart again.
 */
internal data class ReaderOverlays(
    val showFlashcard: Boolean,
    val showReadingCheck: Boolean,
    val showComprehension: Boolean,
    val inMultiWindow: Boolean,
) {
    /** True when nothing should be earning reading credit right now. */
    val coversText: Boolean
        get() = showFlashcard || showReadingCheck || showComprehension || inMultiWindow
}

/**
 * Works out what should be on screen, in the order the reader prioritises them: a due flashcard
 * first, then a question about the passage just read, and the generic recall prompt only when
 * there is no real question to ask instead.
 */
internal fun readerOverlays(
    pendingFlashcards: Int,
    pendingChecks: Int,
    checkDue: Boolean,
    pendingComprehension: Boolean,
    inMultiWindow: Boolean,
): ReaderOverlays {
    val showFlashcard = pendingFlashcards > 0
    // Prepared but not yet due is not shown - and so must not stop the reading that makes it due.
    val showReadingCheck = !showFlashcard && pendingChecks > 0 && checkDue
    val showComprehension = !showFlashcard && !showReadingCheck && pendingComprehension
    return ReaderOverlays(
        showFlashcard = showFlashcard,
        showReadingCheck = showReadingCheck,
        showComprehension = showComprehension,
        inMultiWindow = inMultiWindow,
    )
}

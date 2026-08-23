package com.flashcardreader.app.data.repository

/** What a restore actually put back, so the result can say so instead of just "done". */
data class RestoreResult(
    val words: Int = 0,
    val questions: Int = 0,
    val reviews: Int = 0,
    /** Set when history was skipped because this device already has its own. */
    val historySkipped: Boolean = false,
) {
    val isEmpty: Boolean get() = words == 0 && questions == 0 && reviews == 0
}

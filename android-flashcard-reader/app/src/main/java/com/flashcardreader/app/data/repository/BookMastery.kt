package com.flashcardreader.app.data.repository

/** One book's questions, and how far they have actually got. */
data class BookMastery(
    val title: String,
    val total: Int,
    val correct: Int,
    val retained: Int,
) {
    /**
     * Where this book stands, on the ladder Khan Academy uses: getting something right in the
     * moment is not the same as having it, and only the delayed answer earns the top rung.
     */
    val level: MasteryLevel
        get() = when {
            retained > 0 && retained == total -> MasteryLevel.RETAINED
            retained > 0 -> MasteryLevel.RETAINING
            correct > 0 -> MasteryLevel.UNDERSTOOD
            else -> MasteryLevel.UNTESTED
        }
}

enum class MasteryLevel(val label: String) {
    /** Questions exist but none has been answered right yet. */
    UNTESTED("Not tested yet"),

    /** Answered right, but only ever with the passage fresh. */
    UNDERSTOOD("Understood"),

    /** Some have survived a delay. */
    RETAINING("Sticking"),

    /** All of them have been answered right out of context. */
    RETAINED("Retained"),
}

package com.flashcardreader.app.stats

import com.flashcardreader.app.data.repository.RestoreResult

/**
 * Says what a restore actually put back.
 *
 * It used to report only a word count, which was accurate right up until the backup started
 * carrying questions and review history too - at which point "Restored 0 words" was the message
 * for a file that had just restored a year of reading.
 */
internal fun describeRestore(result: RestoreResult): String {
    if (result.isEmpty) {
        return if (result.historySkipped) {
            "Nothing new to restore. The review history was left alone - this phone has its own."
        } else {
            "Nothing new to restore"
        }
    }
    val parts = buildList {
        if (result.words > 0) add("${result.words} word${plural(result.words)}")
        if (result.questions > 0) add("${result.questions} question${plural(result.questions)}")
        if (result.reviews > 0) add("${result.reviews} past review${plural(result.reviews)}")
    }
    val restored = "Restored " + when (parts.size) {
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> "${parts.dropLast(1).joinToString(", ")} and ${parts.last()}"
    }
    return if (result.historySkipped) {
        "$restored. The review history was left alone - this phone has its own."
    } else {
        restored
    }
}

internal fun plural(n: Int) = if (n == 1) "" else "s"

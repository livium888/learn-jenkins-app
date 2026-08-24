package com.flashcardreader.app.data.parser

/**
 * Cleans the invisible junk out of a book's extracted text, and says where it took it from.
 *
 * The symptom is pages that are mostly empty. The cause is that "blank" is not the same as
 * "whitespace": `<p>&nbsp;</p>` is how a great many EPUBs draw a gap, and a non-breaking space is
 * NOT whitespace as far as Kotlin's `trim()` is concerned - nor are zero-width spaces, byte-order
 * marks or soft hyphens. So a paragraph containing one invisible character reads as a real
 * paragraph, gets kept, and takes a blank line with it. A book with a few hundred of those loses
 * whole pages to nothing at all.
 *
 * This only became visible once pages were real: a run of empty lines simply scrolled past before,
 * and now it eats a page you have to swipe through.
 *
 * Every removal is recorded, because char offsets are how this app remembers everything - reading
 * position, bookmarks, which passage a question came from, which text has earned Focus Gate time.
 * Cleaning the text without moving those with it would scatter all of them.
 */
object TextTidy {

    /** Invisible characters that are not whitespace, and so survive an ordinary trim. */
    private val INVISIBLE = setOf(
        ' ', // no-break space - the usual culprit, from &nbsp;
        ' ', // figure space
        ' ', // narrow no-break space
        '​', // zero-width space
        '‌', // zero-width non-joiner
        '‍', // zero-width joiner
        '﻿', // byte-order mark, often left at the head of a chapter file
        '­', // soft hyphen
    )

    /** Ones that stand in for a space, rather than for nothing at all. */
    private val INVISIBLE_SPACES = setOf(' ', ' ', ' ')

    /** True when this holds nothing a reader could see - whitespace or invisible alike. */
    fun isBlank(text: String): Boolean = text.all { it.isWhitespace() || it in INVISIBLE }

    /** How much of the text is empty lines, 0..1. The number that decides a book needs tidying. */
    fun blankRatio(text: String): Float {
        if (text.isEmpty()) return 0f
        var blank = 0
        for (line in text.lineSequence()) if (isBlank(line)) blank++
        val lines = text.count { it == '\n' } + 1
        return blank.toFloat() / lines
    }

    /**
     * Tidies [text] and returns it with a map from old offsets to new ones.
     *
     * Deliberately conservative: invisible characters become a space or nothing, trailing space on
     * a line goes, and a run of blank lines becomes one. Spacing *within* a line is left alone,
     * because indentation can be verse rather than damage.
     */
    fun tidy(text: String): TidyResult {
        val out = StringBuilder(text.length)
        val cutStarts = ArrayList<Int>()
        val cutEnds = ArrayList<Int>()

        fun cut(from: Int, to: Int) {
            if (to <= from) return
            if (cutEnds.isNotEmpty() && cutEnds.last() == from) {
                cutEnds[cutEnds.lastIndex] = to
            } else {
                cutStarts.add(from)
                cutEnds.add(to)
            }
        }

        /** A character that takes up space without being content. */
        fun isFiller(c: Char) = c.isWhitespace() || c in INVISIBLE

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!isFiller(c)) {
                if (c in INVISIBLE) cut(i, i + 1) else out.append(c)
                i++
                continue
            }

            // Take the whole run of filler at once: only once its far end is known can it be said
            // whether this was a paragraph break, a space between words, or a trailing tail.
            val runStart = i
            var newlines = 0
            while (i < text.length && isFiller(text[i])) {
                if (text[i] == '\n') newlines++
                i++
            }
            val atEnd = i >= text.length

            val keep = when {
                // Whitespace running off the end of the book is never content.
                atEnd -> ""
                newlines >= 2 -> "\n\n"
                newlines == 1 -> "\n"
                // No newline: a gap between words on the same line. Left as it was, because
                // indentation inside a line can be verse rather than damage - except that any
                // invisible stand-in for a space is replaced by a real one.
                else -> text.substring(runStart, i).map { if (it in INVISIBLE_SPACES) ' ' else it }
                    .filter { it !in INVISIBLE }
                    .joinToString("")
            }
            out.append(keep)
            cut(runStart + keep.length, i)
        }

        return TidyResult(out.toString(), OffsetMap(cutStarts.toIntArray(), cutEnds.toIntArray()))
    }
}

/** Tidied text, plus the means to move any remembered position onto it. */
data class TidyResult(val text: String, val offsets: OffsetMap) {
    val removedChars: Int get() = offsets.totalRemoved
}

/**
 * Maps an offset in the original text to the same place in the tidied text.
 *
 * An offset that lands inside something removed maps to where that removal began, which is the
 * nearest surviving character - so a bookmark on a blank line lands on the next real text rather
 * than drifting into the middle of a paragraph.
 */
class OffsetMap(private val cutStarts: IntArray, private val cutEnds: IntArray) {

    /** Total characters removed before each cut, so a map is one binary search. */
    private val removedBefore = IntArray(cutStarts.size).also { acc ->
        var running = 0
        for (i in cutStarts.indices) {
            acc[i] = running
            running += cutEnds[i] - cutStarts[i]
        }
    }

    val totalRemoved: Int
        get() = if (cutStarts.isEmpty()) 0 else removedBefore.last() + (cutEnds.last() - cutStarts.last())

    fun map(oldOffset: Int): Int {
        if (oldOffset <= 0 || cutStarts.isEmpty()) return oldOffset.coerceAtLeast(0)
        // The last cut that begins at or before this offset.
        var low = 0
        var high = cutStarts.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (cutStarts[mid] <= oldOffset) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return oldOffset
        return if (oldOffset < cutEnds[found]) {
            cutStarts[found] - removedBefore[found]
        } else {
            oldOffset - (removedBefore[found] + (cutEnds[found] - cutStarts[found]))
        }
    }
}

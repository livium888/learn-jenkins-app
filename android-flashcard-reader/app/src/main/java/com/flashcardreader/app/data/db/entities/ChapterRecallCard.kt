package com.flashcardreader.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chapter's pass: what it claimed, and the order it claimed it in - scheduled like any other card.
 *
 * Where a [ReadingCheckCard] asks about one passage minutes after it was read, this asks about a
 * whole chapter at the end of the session. The delay is the point: metacomprehension accuracy is
 * far better when the recall step comes after the reading rather than immediately (Thiede et al.),
 * and a chapter you can still reassemble an hour later is one you actually followed.
 *
 * Two payloads, both packed into single columns the way [ReadingCheckCard.distractors] already is:
 *
 *  - [propositions] - claims about the chapter, some of which it never made. Tapping the ones it
 *    did is the recognition stand-in for Read-Recite-Review's recite step. (3R's own recite is
 *    free recall, spoken or written; this is weaker, and [hints] is the compensation.)
 *  - [hints] - one short line per step in the argument, stored in the order the chapter made them.
 *    Shuffled at showing time and tapped back into order: Franklin's exercise from his
 *    Autobiography, and the generative half of the pass.
 *
 * The correct hint order is not the model's opinion. Each hint was anchored to a sentence quoted
 * from the chapter, and the anchors' positions in the text are what fixed the order - checked on
 * device before the card was ever saved. See ai/ChapterRecall.kt.
 */
@Entity(
    tableName = "chapter_recalls",
    indices = [Index(name = "index_chapter_recalls_source", value = ["sourceId", "chapterIndex"])],
)
data class ChapterRecallCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** Which chapter, by position in the table of contents. */
    val chapterIndex: Int,
    val chapterTitle: String,
    val startChar: Int,
    val endChar: Int,
    /** Claims, packed as text + [FIELD_SEPARATOR] + said + [FIELD_SEPARATOR] + evidence. */
    val propositions: String,
    /** Hints in true order, packed as hint + [FIELD_SEPARATOR] + anchor. */
    val hints: String,
    val createdAt: Long,

    // --- FSRS scheduling state (same fields as Term and ReadingCheckCard) ---
    val difficulty: Double = 0.0,
    val stability: Double = 0.0,
    /** Epoch millis of the next scheduled review. Null = never answered yet (due immediately). */
    val due: Long? = null,
    val lastReviewedAt: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: CardState = CardState.NEW,
    @ColumnInfo(defaultValue = "0")
    val hyperMiss: Boolean = false,
) {
    /** The claims, unpacked. */
    val claims: List<Claim>
        get() = propositions.split(SEPARATOR).mapNotNull { row ->
            val parts = row.split(FIELD_SEPARATOR)
            if (parts.size < 3 || parts[0].isBlank()) null
            else Claim(parts[0], parts[1] == "1", parts[2])
        }

    /** The hints, in the order the chapter made them. */
    val steps: List<Step>
        get() = hints.split(SEPARATOR).mapNotNull { row ->
            val parts = row.split(FIELD_SEPARATOR)
            if (parts.size < 2 || parts[0].isBlank()) null else Step(parts[0], parts[1])
        }

    data class Claim(val text: String, val said: Boolean, val evidence: String)
    data class Step(val text: String, val anchor: String)

    companion object {
        /** Unit separator between rows; cannot occur in prose, so no claim can split itself. */
        const val SEPARATOR = "\u001F"

        /** Record separator between a row's fields, for the same reason. */
        const val FIELD_SEPARATOR = "\u001E"

        fun packClaims(claims: List<Claim>): String = claims.joinToString(SEPARATOR) {
            listOf(it.text, if (it.said) "1" else "0", it.evidence).joinToString(FIELD_SEPARATOR)
        }

        fun packSteps(steps: List<Step>): String = steps.joinToString(SEPARATOR) {
            listOf(it.text, it.anchor).joinToString(FIELD_SEPARATOR)
        }
    }
}

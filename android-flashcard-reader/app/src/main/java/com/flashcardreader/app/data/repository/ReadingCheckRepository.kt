package com.flashcardreader.app.data.repository

import com.flashcardreader.app.ai.ReadingCheck
import com.flashcardreader.app.data.db.dao.ReadingCheckDao
import com.flashcardreader.app.data.db.entities.ReadingCheckCard
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.FsrsState
import com.flashcardreader.app.data.fsrs.Rating
import kotlinx.coroutines.flow.Flow

/**
 * Stores comprehension questions and puts them on the same spacing schedule as vocabulary cards.
 *
 * The rating is derived from the answer, never self-reported: right is GOOD, wrong is AGAIN. That
 * is the whole reason these are multiple choice - a tap on the correct option is evidence, whereas
 * tapping "I knew that" is just a claim.
 */
class ReadingCheckRepository(
    private val dao: ReadingCheckDao,
    private val fsrs: Fsrs = Fsrs(),
) {

    /** Saves a freshly generated question against the passage it came from. */
    suspend fun save(check: ReadingCheck, sourceId: Long, charOffset: Int): Long =
        dao.insert(
            ReadingCheckCard(
                question = check.question,
                correctAnswer = check.correctAnswer,
                distractors = ReadingCheckCard.joinDistractors(check.distractors),
                evidence = check.evidence,
                sourceId = sourceId,
                charOffset = charOffset,
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun due(now: Long = System.currentTimeMillis()): List<ReadingCheckCard> = dao.due(now)

    suspend fun byId(id: Long): ReadingCheckCard? = dao.byId(id)

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun forSource(sourceId: Long): List<ReadingCheckCard> = dao.forSource(sourceId)

    suspend fun deleteForSource(sourceId: Long) = dao.deleteForSource(sourceId)

    /**
     * Records an answer and reschedules the card.
     *
     * [wasConfident] only matters when the answer was wrong: being sure and being wrong is the
     * hypercorrection case, which is unusually well remembered once corrected and so worth marking.
     */
    suspend fun answer(
        card: ReadingCheckCard,
        correct: Boolean,
        wasConfident: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        val rating = if (correct) Rating.GOOD else Rating.AGAIN
        val next = fsrs.review(
            FsrsState(
                difficulty = card.difficulty,
                stability = card.stability,
                due = card.due,
                lastReviewedAt = card.lastReviewedAt,
                reps = card.reps,
                lapses = card.lapses,
                state = card.state,
            ),
            rating,
            now,
        )
        dao.update(
            card.copy(
                difficulty = next.difficulty,
                stability = next.stability,
                due = next.due,
                lastReviewedAt = next.lastReviewedAt,
                reps = next.reps,
                lapses = next.lapses,
                state = next.state,
                hyperMiss = card.hyperMiss || (!correct && wasConfident),
            ),
        )
    }
}

package com.flashcardreader.app.data.repository

import com.flashcardreader.app.ai.ChapterRecall
import com.flashcardreader.app.data.db.dao.ChapterRecallDao
import com.flashcardreader.app.data.db.entities.CardKind
import com.flashcardreader.app.data.db.entities.ChapterRecallCard
import com.flashcardreader.app.data.db.entities.ReviewContext
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.FsrsState
import com.flashcardreader.app.data.fsrs.Rating

/**
 * Stores chapter passes and schedules them like every other card.
 *
 * The pass is what makes Franklin's exercise more than a puzzle. He jumbled his hints and came back
 * to them "weeks later"; here that delay is FSRS's, which is the same idea with the interval fitted
 * to how this particular reader forgets.
 */
class ChapterRecallRepository(
    private val dao: ChapterRecallDao,
    private val fsrs: Fsrs = Fsrs(),
    private val history: ReviewHistory? = null,
) {

    /** The pass already written for this chapter, if any. */
    suspend fun forChapter(sourceId: Long, chapterIndex: Int): ChapterRecallCard? =
        dao.forChapter(sourceId, chapterIndex)

    suspend fun chaptersWithPass(sourceId: Long): Set<Int> = dao.chaptersWithPass(sourceId).toSet()

    suspend fun due(now: Long = System.currentTimeMillis()): List<ChapterRecallCard> = dao.due(now)

    suspend fun dueCount(now: Long = System.currentTimeMillis()): Int = dao.dueCount(now)

    suspend fun deleteForSource(sourceId: Long) = dao.deleteForSource(sourceId)

    /** Saves a freshly written pass against the chapter it covers. */
    suspend fun save(
        recall: ChapterRecall,
        sourceId: Long,
        chapterIndex: Int,
        chapterTitle: String,
        startChar: Int,
        endChar: Int,
    ): Long = dao.insert(
        ChapterRecallCard(
            sourceId = sourceId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            startChar = startChar,
            endChar = endChar,
            propositions = ChapterRecallCard.packClaims(
                recall.propositions.map { ChapterRecallCard.Claim(it.text, it.said, it.evidence) },
            ),
            hints = ChapterRecallCard.packSteps(
                recall.hints.map { ChapterRecallCard.Step(it.text, it.anchor) },
            ),
            createdAt = System.currentTimeMillis(),
        ),
    )

    /**
     * Records an answered pass.
     *
     * [score] is the combined result of both tasks - see [com.flashcardreader.app.reader.PassScore].
     * The rating comes from it rather than from the reader, for the same reason the comprehension
     * questions derive theirs: a tap on the right answer is evidence, "I knew that" is a claim.
     */
    suspend fun answer(
        card: ChapterRecallCard,
        score: Double,
        rating: Rating,
        context: ReviewContext = ReviewContext.UNKNOWN,
        now: Long = System.currentTimeMillis(),
    ) {
        history?.record(
            cardId = card.id,
            kind = CardKind.CHAPTER_RECALL,
            rating = rating,
            stabilityBefore = card.stability,
            difficultyBefore = card.difficulty,
            lastReviewedAt = card.lastReviewedAt,
            context = context,
            now = now,
        )
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
                // Being sure of a chapter and then failing to reassemble it is the hypercorrection
                // case, and the one worth marking: a confident miss is unusually well remembered
                // once corrected. A near-miss on the ordering is not that.
                hyperMiss = card.hyperMiss || score < PASS_MISS_BELOW,
            ),
        )
        history?.refitIfDue()
    }

    private companion object {
        /** Below this the chapter was not followed at all, rather than half-remembered. */
        const val PASS_MISS_BELOW = 0.4
    }
}

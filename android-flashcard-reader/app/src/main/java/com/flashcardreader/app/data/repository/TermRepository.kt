package com.flashcardreader.app.data.repository

import com.flashcardreader.app.data.db.dao.OccurrenceDao
import com.flashcardreader.app.data.db.dao.TermDao
import com.flashcardreader.app.data.db.entities.Occurrence
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.Rating
import kotlinx.coroutines.flow.Flow

/** The global, source-agnostic vocabulary + flashcard store. */
class TermRepository(
    private val termDao: TermDao,
    private val occurrenceDao: OccurrenceDao,
    private val fsrs: Fsrs = Fsrs(),
) {
    fun observeAll(): Flow<List<Term>> = termDao.observeAll()

    suspend fun allTerms(): List<Term> = termDao.getAll()

    fun isDue(term: Term, now: Long): Boolean = fsrs.isDue(term, now)

    /** Tags a newly-selected word/phrase as a flashcard. Reuses an existing term if already tracked. */
    suspend fun createOrGetTerm(rawText: String, definition: String): Term {
        val normalized = rawText.trim().lowercase()
        termDao.findByNormalizedText(normalized)?.let { return it }
        val term = Term(
            normalizedText = normalized,
            displayText = rawText.trim(),
            definition = definition,
            createdAt = System.currentTimeMillis(),
        )
        val id = termDao.insert(term)
        return term.copy(id = id)
    }

    suspend fun updateDefinition(term: Term, definition: String) {
        termDao.update(term.copy(definition = definition))
    }

    /** Update the card's answer plus its encoding-booster metadata (self-note, curiosity). */
    suspend fun updateMeta(term: Term, definition: String, selfNote: String, curious: Boolean) {
        termDao.update(term.copy(definition = definition, selfNote = selfNote, curious = curious))
    }

    suspend fun delete(term: Term) = termDao.delete(term.id)

    /** Records that a term was seen while reading, without necessarily quizzing on it. */
    suspend fun logOccurrence(termId: Long, sourceId: Long, charOffset: Int, triggeredReview: Boolean) {
        occurrenceDao.insert(
            Occurrence(
                termId = termId,
                sourceId = sourceId,
                charOffset = charOffset,
                seenAt = System.currentTimeMillis(),
                triggeredReview = triggeredReview,
            ),
        )
    }

    /** Applies the user's flashcard answer, advancing the FSRS schedule for next time. */
    suspend fun submitReview(term: Term, rating: Rating): Term {
        val updated = fsrs.review(term, rating, System.currentTimeMillis())
        termDao.update(updated)
        return updated
    }

    /** Most recent sighting of a term, used to pull up its context sentence outside of active reading. */
    suspend fun latestOccurrence(termId: Long): Occurrence? = occurrenceDao.forTerm(termId).firstOrNull()
}

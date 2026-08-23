package com.flashcardreader.app.data.repository

import com.flashcardreader.app.data.db.dao.OccurrenceDao
import com.flashcardreader.app.data.db.dao.TermDao
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Occurrence
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Confidence
import com.flashcardreader.app.data.fsrs.Fsrs
import com.flashcardreader.app.data.fsrs.Rating
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/** The global, source-agnostic vocabulary + flashcard store. */
class TermRepository(
    private val termDao: TermDao,
    private val occurrenceDao: OccurrenceDao,
    private val calibration: CalibrationStore,
    private val fsrs: Fsrs = Fsrs(),
    /** Optional so tests and tools can build a repository without a database of past reviews. */
    private val history: ReviewHistory? = null,
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

    /** Records all of a page's occurrences in one transaction (cheaper than one insert each). */
    suspend fun logOccurrences(entries: List<OccurrenceLog>) {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        occurrenceDao.insertAll(
            entries.map {
                Occurrence(
                    termId = it.termId,
                    sourceId = it.sourceId,
                    charOffset = it.charOffset,
                    seenAt = now,
                    triggeredReview = it.triggeredReview,
                )
            },
        )
    }

    /**
     * Applies the user's flashcard answer, advancing the FSRS schedule, and records the
     * hypercorrection flag: being confident but wrong sets it; getting it right (Good/Easy)
     * clears it; anything else leaves it unchanged.
     */
    suspend fun submitReview(term: Term, rating: Rating, confidence: Confidence): Term {
        val now = System.currentTimeMillis()
        // Logged before rescheduling, because the fit needs the state the answer was given from.
        history?.record(
            cardId = term.id,
            kind = com.flashcardreader.app.data.db.entities.CardKind.TERM,
            rating = rating,
            stabilityBefore = term.stability,
            difficultyBefore = term.difficulty,
            lastReviewedAt = term.lastReviewedAt,
            now = now,
        )
        val scheduled = fsrs.review(term, rating, now)
        val hyperMiss = when {
            confidence == Confidence.CONFIDENT && rating == Rating.AGAIN -> true
            rating == Rating.GOOD || rating == Rating.EASY -> false
            else -> term.hyperMiss
        }
        val updated = scheduled.copy(hyperMiss = hyperMiss)
        termDao.update(updated)
        calibration.record(confidence, knew = rating == Rating.GOOD || rating == Rating.EASY)
        history?.refitIfDue()
        return updated
    }

    /**
     * Serialises every flashcard (answer + full FSRS schedule + metadata) to a JSON backup that
     * can be saved off-device and restored on a new phone. Book files aren't included - they can
     * be re-added or re-downloaded; the words and their review schedule are the irreplaceable part.
     */
    suspend fun exportJson(): String {
        val arr = JSONArray()
        for (t in termDao.getAll()) {
            arr.put(
                JSONObject()
                    .put("normalizedText", t.normalizedText)
                    .put("displayText", t.displayText)
                    .put("definition", t.definition)
                    .put("createdAt", t.createdAt)
                    .put("difficulty", t.difficulty)
                    .put("stability", t.stability)
                    .put("due", t.due ?: JSONObject.NULL)
                    .put("lastReviewedAt", t.lastReviewedAt ?: JSONObject.NULL)
                    .put("reps", t.reps)
                    .put("lapses", t.lapses)
                    .put("state", t.state.name)
                    .put("curious", t.curious)
                    .put("selfNote", t.selfNote)
                    .put("hyperMiss", t.hyperMiss),
            )
        }
        return JSONObject().put("version", 1).put("terms", arr).toString()
    }

    /** Restores flashcards from a backup, skipping any word already present. Returns how many were added. */
    suspend fun importJson(json: String): Int {
        val arr = JSONObject(json).optJSONArray("terms") ?: return 0
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val normalized = o.optString("normalizedText", "")
            if (normalized.isBlank() || termDao.findByNormalizedText(normalized) != null) continue
            termDao.insert(
                Term(
                    normalizedText = normalized,
                    displayText = o.optString("displayText", normalized),
                    definition = o.optString("definition", ""),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    difficulty = o.optDouble("difficulty", 0.0),
                    stability = o.optDouble("stability", 0.0),
                    due = if (o.isNull("due")) null else o.optLong("due"),
                    lastReviewedAt = if (o.isNull("lastReviewedAt")) null else o.optLong("lastReviewedAt"),
                    reps = o.optInt("reps", 0),
                    lapses = o.optInt("lapses", 0),
                    state = runCatching { CardState.valueOf(o.optString("state", "NEW")) }.getOrDefault(CardState.NEW),
                    curious = o.optBoolean("curious", false),
                    selfNote = o.optString("selfNote", ""),
                    hyperMiss = o.optBoolean("hyperMiss", false),
                ),
            )
            added++
        }
        return added
    }

    /** Most recent sighting of a term, used to pull up its context sentence outside of active reading. */
    suspend fun latestOccurrence(termId: Long): Occurrence? = occurrenceDao.forTerm(termId).firstOrNull()

    /**
     * A randomly chosen sighting of a term. Reviewing with a *different* real sentence from your
     * own reading each time is a deliberate "encoding variability" boost - it trains you to
     * recognise the word across contexts, not to parrot one memorised example.
     */
    suspend fun randomOccurrence(termId: Long): Occurrence? = occurrenceDao.forTerm(termId).randomOrNull()
}

/** One pending occurrence-log row, batched by [TermRepository.logOccurrences]. */
data class OccurrenceLog(
    val termId: Long,
    val sourceId: Long,
    val charOffset: Int,
    val triggeredReview: Boolean,
)

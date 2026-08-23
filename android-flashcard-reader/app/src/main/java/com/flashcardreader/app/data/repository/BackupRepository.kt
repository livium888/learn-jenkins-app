package com.flashcardreader.app.data.repository

import com.flashcardreader.app.data.db.dao.ReadingCheckDao
import com.flashcardreader.app.data.db.dao.ReviewLogDao
import com.flashcardreader.app.data.db.dao.SourceDao
import com.flashcardreader.app.data.db.entities.CardKind
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.ReadingCheckCard
import com.flashcardreader.app.data.db.entities.ReviewLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole backup: words, comprehension questions, and the review history behind the schedule.
 *
 * Version 1 held only the flashcards, and stayed that way while two more kinds of irreplaceable
 * thing were added around it - every question written from your reading, and the log the scheduler
 * fits itself to. Moving phones silently lost both. Version 2 carries all three; version 1 files
 * still restore, they simply have nothing to say about the other two.
 *
 * Book *files* are still left out. They can be re-downloaded or re-added; what cannot is the work
 * of having read them.
 */
class BackupRepository(
    private val terms: TermRepository,
    private val readingChecks: ReadingCheckDao,
    private val reviewLogs: ReviewLogDao,
    private val sources: SourceDao,
) {

    suspend fun exportJson(): String {
        val root = JSONObject(terms.exportJson())
        root.put("version", VERSION)
        root.put("readingChecks", exportChecks())
        root.put("reviewLogs", exportLogs())
        return root.toString()
    }

    private suspend fun exportChecks(): JSONArray {
        val arr = JSONArray()
        // Titles are cached because a book with many questions would otherwise be looked up once
        // per question.
        val titles = mutableMapOf<Long, String>()
        for (c in readingChecks.getAll()) {
            val title = titles.getOrPut(c.sourceId) { sources.getById(c.sourceId)?.title.orEmpty() }
            arr.put(
                JSONObject()
                    .put("question", c.question)
                    .put("correctAnswer", c.correctAnswer)
                    .put("distractors", c.distractors)
                    .put("evidence", c.evidence)
                    // The row id means nothing on another device, so the book is named instead and
                    // matched back by title on restore.
                    .put("book", title)
                    .put("charOffset", c.charOffset)
                    .put("createdAt", c.createdAt)
                    .put("difficulty", c.difficulty)
                    .put("stability", c.stability)
                    .put("due", c.due ?: JSONObject.NULL)
                    .put("lastReviewedAt", c.lastReviewedAt ?: JSONObject.NULL)
                    .put("reps", c.reps)
                    .put("lapses", c.lapses)
                    .put("state", c.state.name)
                    .put("hyperMiss", c.hyperMiss),
            )
        }
        return arr
    }

    private suspend fun exportLogs(): JSONArray {
        val arr = JSONArray()
        for (log in reviewLogs.getAll()) {
            arr.put(
                JSONObject()
                    .put("cardId", log.cardId)
                    .put("cardKind", log.cardKind.name)
                    .put("rating", log.rating)
                    .put("elapsedDays", log.elapsedDays)
                    .put("stabilityBefore", log.stabilityBefore)
                    .put("difficultyBefore", log.difficultyBefore)
                    .put("wasFirstReview", log.wasFirstReview)
                    .put("reviewedAt", log.reviewedAt),
            )
        }
        return arr
    }

    suspend fun importJson(json: String): RestoreResult {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return RestoreResult()
        val words = terms.importJson(json)
        val questions = importChecks(root.optJSONArray("readingChecks"))
        val existingHistory = reviewLogs.count()
        val logs = root.optJSONArray("reviewLogs")
        val reviews = if (existingHistory == 0) importLogs(logs) else 0
        return RestoreResult(
            words = words,
            questions = questions,
            reviews = reviews,
            historySkipped = existingHistory > 0 && (logs?.length() ?: 0) > 0,
        )
    }

    private suspend fun importChecks(arr: JSONArray?): Int {
        if (arr == null) return 0
        // Built once: matching every question's book by title would otherwise re-read the whole
        // library per question.
        val byTitle = sources.snapshot().associate { it.title to it.id }
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val question = o.optString("question", "")
            if (question.isBlank()) continue
            // Restoring the same file twice is a normal thing to do by accident; it should not
            // double every question.
            if (readingChecks.countByQuestion(question) > 0) continue
            readingChecks.insert(
                ReadingCheckCard(
                    question = question,
                    correctAnswer = o.optString("correctAnswer", ""),
                    distractors = o.optString("distractors", ""),
                    evidence = o.optString("evidence", ""),
                    // Zero when the book isn't on this device. The question still reviews; it just
                    // can't be jumped back to in the text.
                    sourceId = byTitle[o.optString("book", "")] ?: 0L,
                    charOffset = o.optInt("charOffset", 0),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    difficulty = o.optDouble("difficulty", 0.0),
                    stability = o.optDouble("stability", 0.0),
                    due = if (o.isNull("due")) null else o.optLong("due"),
                    lastReviewedAt = if (o.isNull("lastReviewedAt")) null else o.optLong("lastReviewedAt"),
                    reps = o.optInt("reps", 0),
                    lapses = o.optInt("lapses", 0),
                    state = runCatching { CardState.valueOf(o.optString("state", "NEW")) }
                        .getOrDefault(CardState.NEW),
                    hyperMiss = o.optBoolean("hyperMiss", false),
                ),
            )
            added++
        }
        return added
    }

    /**
     * Restores the review history.
     *
     * Card ids are remapped on the way in - see [ImportedCardIds] for why that matters.
     */
    private suspend fun importLogs(arr: JSONArray?): Int {
        if (arr == null || arr.length() == 0) return 0
        val ids = ImportedCardIds()
        val logs = mutableListOf<ReviewLog>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kindName = o.optString("cardKind", CardKind.TERM.name)
            val kind = runCatching { CardKind.valueOf(kindName) }.getOrDefault(CardKind.TERM)
            val cardId = ids.idFor(o.optLong("cardId", 0L), kindName)
            logs.add(
                ReviewLog(
                    cardId = cardId,
                    cardKind = kind,
                    rating = o.optInt("rating", 3),
                    elapsedDays = o.optDouble("elapsedDays", 0.0),
                    stabilityBefore = o.optDouble("stabilityBefore", 0.0),
                    difficultyBefore = o.optDouble("difficultyBefore", 0.0),
                    wasFirstReview = o.optBoolean("wasFirstReview", false),
                    reviewedAt = o.optLong("reviewedAt", 0L),
                ),
            )
        }
        reviewLogs.insertAll(logs)
        return logs.size
    }

    private companion object {
        const val VERSION = 2
    }
}

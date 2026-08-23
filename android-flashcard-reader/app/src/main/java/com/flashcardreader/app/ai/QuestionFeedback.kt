package com.flashcardreader.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the questions you marked as bad, so the prompt that writes them can be tuned from evidence.
 *
 * The comprehension questions are written by a model against a prompt nobody has ever evaluated -
 * it was written blind, and a bad one is currently indistinguishable from a good one from here. A
 * flag on a specific question, kept with the passage it claimed as evidence, is the only thing that
 * turns "the questions feel off" into something actionable.
 *
 * A plain capped file, not a table: this is diagnostic data rather than anything precious, and the
 * app just spent a release crashing on a schema migration. It never leaves the phone on its own -
 * the report is text, and only goes anywhere if it is deliberately copied out.
 */
class QuestionFeedback(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    val count: Int get() = entries().length()

    /** Records one rejected question, keeping the newest [MAX_ENTRIES]. */
    fun record(check: ReadingCheck, book: String) {
        runCatching {
            val existing = entries()
            val entry = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("book", book)
                .put("question", check.question)
                .put("correctAnswer", check.correctAnswer)
                .put("distractors", JSONArray(check.distractors))
                .put("evidence", check.evidence)
            // Oldest first, so trimming from the front keeps the most recent complaints.
            val kept = JSONArray()
            val start = (existing.length() + 1 - MAX_ENTRIES).coerceAtLeast(0)
            for (i in start until existing.length()) kept.put(existing.get(i))
            kept.put(entry)
            file.writeText(kept.toString())
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    /** A plain-text dump, meant to be pasted somewhere it can be read and acted on. */
    fun report(): String {
        val arr = entries()
        if (arr.length() == 0) return "No questions have been flagged."
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return buildString {
            appendLine("Flagged reading questions (${arr.length()})")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                appendLine()
                appendLine("--- ${stamp.format(Date(o.optLong("at")))} · ${o.optString("book").ifBlank { "unknown book" }}")
                appendLine("Q: ${o.optString("question")}")
                appendLine("Answer given as correct: ${o.optString("correctAnswer")}")
                val d = o.optJSONArray("distractors") ?: JSONArray()
                for (j in 0 until d.length()) appendLine("Other option: ${d.optString(j)}")
                appendLine("Quoted from the book: ${o.optString("evidence")}")
            }
        }
    }

    private fun entries(): JSONArray = runCatching {
        if (!file.exists()) JSONArray() else JSONArray(file.readText())
    }.getOrDefault(JSONArray())

    private companion object {
        const val FILE_NAME = "flagged-questions.json"
        const val MAX_ENTRIES = 50
    }
}

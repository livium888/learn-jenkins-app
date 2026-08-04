package com.flashcardreader.app.data.repository

import android.content.Context
import com.flashcardreader.app.data.fsrs.Confidence

/** Accuracy at one confidence level: how often "I felt this sure" turned out right. */
data class CalibrationLevel(val label: String, val total: Int, val correct: Int) {
    val pct: Int? get() = if (total > 0) (correct * 100) / total else null
}

/** A snapshot of calibration across confidence levels, plus an optional plain-language nudge. */
data class Calibration(val levels: List<CalibrationLevel>, val note: String?)

/**
 * Tracks metacognitive calibration: for each confidence level you pick before revealing, how
 * often you actually recalled the answer. Comparing "how sure I felt" to "how right I was"
 * trains self-monitoring - the skill of knowing what you do and don't know, which is what
 * decides whether you study the right things.
 *
 * Kept as a tiny global tally in SharedPreferences (no per-card history, no schema change):
 * calibration is a general habit, not a per-word fact.
 */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("calibration", Context.MODE_PRIVATE)

    /** Records one graded review. [knew] is true when the answer was actually recalled (Good/Easy). */
    fun record(confidence: Confidence, knew: Boolean) {
        val totalKey = "${confidence.name}_total"
        val correctKey = "${confidence.name}_correct"
        prefs.edit().apply {
            putInt(totalKey, prefs.getInt(totalKey, 0) + 1)
            if (knew) putInt(correctKey, prefs.getInt(correctKey, 0) + 1)
        }.apply()
    }

    fun snapshot(): Calibration {
        val levels = Confidence.values().map { c ->
            CalibrationLevel(
                label = c.name.lowercase().replaceFirstChar(Char::uppercase),
                total = prefs.getInt("${c.name}_total", 0),
                correct = prefs.getInt("${c.name}_correct", 0),
            )
        }
        val confident = levels.firstOrNull { it.label.equals("Confident", ignoreCase = true) }
        val note = if (confident != null && confident.total >= 5 && (confident.pct ?: 100) < 70) {
            "You felt sure on ${confident.total} reviews but recalled ${confident.pct}% of them — " +
                "the answers that feel obvious are the ones worth a second look."
        } else {
            null
        }
        return Calibration(levels, note)
    }
}

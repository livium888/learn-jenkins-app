package com.flashcardreader.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls the user's Gemini API to evaluate their guess at a word's meaning and explain it.
 * Uses HttpURLConnection + org.json (both built into Android) so it adds no dependency.
 * Always returns a Result - the caller shows the message on failure and the write-your-own
 * flow keeps working regardless.
 */
object GeminiTutor {

    suspend fun evaluateGuess(
        context: Context,
        word: String,
        sentence: String,
        guess: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val prefs = AiPrefs(context)
        val key = prefs.apiKey
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Add your Gemini API key in AI tutor settings first."))
        }

        val prompt = prefs.promptTemplate
            .replace("{word}", word)
            .replace("{sentence}", sentence.ifBlank { "(no sentence available)" })
            .replace("{my_guess}", guess.ifBlank { "(no guess given)" })
            .replace("{my_language}", prefs.myLanguage)

        request(prefs, key, prompt)
    }

    /**
     * Writes one comprehension question about a passage the reader genuinely read.
     *
     * Asks for the answer key in the same reply, so grading happens instantly on the device and a
     * second round trip never interrupts reading. The passage is sent verbatim - that is why this
     * feature is off by default and has its own switch: unlike the word tutor, which sends a single
     * word and sentence on an explicit tap, this uploads pages of a book automatically.
     */
    suspend fun generateReadingCheck(
        context: Context,
        passage: String,
    ): Result<ReadingCheck> = withContext(Dispatchers.IO) {
        val prefs = AiPrefs(context)
        val key = prefs.apiKey
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Add your Gemini API key in AI tutor settings first."))
        }
        val trimmed = passage.trim()
        if (trimmed.length < MIN_PASSAGE_CHARS) {
            return@withContext Result.failure(IllegalStateException("Not enough read text to ask about yet."))
        }

        val prompt = buildString {
            appendLine("You are helping someone check they understood what they just read.")
            appendLine()
            appendLine("Write ONE multiple-choice question about the passage below.")
            appendLine("Rules:")
            appendLine("- Ask about meaning, cause, motive or consequence - never trivia like a name, a date or a colour.")
            appendLine("- Someone who read and understood the passage should answer it easily; someone who skimmed should not.")
            appendLine("- The question must be answerable from the passage alone.")
            appendLine("- Give exactly 3 wrong options. Each must be plausible and clearly wrong to a careful reader.")
            appendLine("- Quote the sentence(s) the answer comes from, copied EXACTLY from the passage, character for character.")
            appendLine("- Write in ${prefs.myLanguage}.")
            appendLine()
            appendLine("Reply with JSON only, no other text, in exactly this shape:")
            appendLine("""{"question":"...","answer":"...","distractors":["...","...","..."],"evidence":"..."}""")
            appendLine()
            appendLine("PASSAGE:")
            appendLine(trimmed)
        }

        request(prefs, key, prompt).mapCatching { reply ->
            ReadingCheck.parse(reply, trimmed)
                ?: error("The AI's question didn't check out, so it was skipped.")
        }
    }

    /** One call to the model, returning its text. Shared by every prompt this app sends. */
    private fun request(prefs: AiPrefs, key: String, prompt: String): Result<String> = runCatching {
        val requestBody = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))),
            ),
        )

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent?key=$key")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        connection.disconnect()

        if (code !in 200..299) {
            error("AI request failed (HTTP $code). ${extractError(payload)}")
        }
        parseAnswer(payload)
    }

    /** Below this there isn't enough read text for a question worth asking. */
    private const val MIN_PASSAGE_CHARS = 400

    private fun parseAnswer(payload: String): String {
        val candidates = JSONObject(payload).optJSONArray("candidates")
            ?: error("The AI returned no answer.")
        if (candidates.length() == 0) error("The AI returned no answer.")
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: error("The AI returned no answer.")
        val text = buildString {
            for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }.trim()
        return text.ifBlank { error("The AI returned an empty answer.") }
    }

    private fun extractError(payload: String): String =
        runCatching { JSONObject(payload).getJSONObject("error").getString("message") }
            .getOrDefault(payload.take(180))
}

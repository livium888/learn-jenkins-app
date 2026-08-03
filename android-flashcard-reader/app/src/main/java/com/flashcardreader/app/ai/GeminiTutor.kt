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

        runCatching {
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
    }

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

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
    suspend fun generateReadingChecks(
        context: Context,
        passage: String,
    ): Result<List<ReadingCheck>> = withContext(Dispatchers.IO) {
        val prefs = AiPrefs(context)
        val key = prefs.apiKey
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Add your Gemini API key in AI tutor settings first."))
        }
        val trimmed = passage.trim()
        if (trimmed.length < MIN_PASSAGE_CHARS) {
            return@withContext Result.failure(IllegalStateException("Not enough read text to ask about yet."))
        }

        // Scaled to the passage rather than fixed: a short stretch rarely holds three separate
        // ideas, and asking three about it produces two questions padded out of one. The model is
        // told this is a ceiling, not a quota - fewer real ideas should mean fewer questions.
        val ceiling = questionCeiling(trimmed)

        val prompt = buildString {
            appendLine("You are helping someone check they understood what they just read.")
            appendLine()
            appendLine("Find the distinct key ideas in the passage below - the things someone would")
            appendLine("have had to follow to have understood it - and write ONE multiple-choice")
            appendLine("question about each.")
            appendLine("Rules:")
            appendLine("- At most $ceiling questions. This is a ceiling, not a target: if the passage")
            appendLine("  carries only one real idea, return exactly one question. Never pad.")
            appendLine("- Each question must be about a DIFFERENT idea, and cite different lines.")
            appendLine("- Ask about meaning, cause, motive or consequence - never trivia like a name, a date or a colour.")
            appendLine("- Someone who read and understood the passage should answer it easily; someone who skimmed should not.")
            appendLine("- The question must be answerable from the passage alone.")
            appendLine("- Give exactly 3 wrong options. Each must be plausible and clearly wrong to a careful reader.")
            appendLine("- Quote the sentence(s) the answer comes from, copied EXACTLY from the passage, character for character.")
            appendLine("- Write in ${prefs.myLanguage}.")
            appendLine()
            appendLine("Reply with JSON only, no other text, in exactly this shape:")
            appendLine("""{"questions":[{"question":"...","answer":"...","distractors":["...","...","..."],"evidence":"..."}]}""")
            appendLine()
            appendLine("PASSAGE:")
            appendLine(trimmed)
        }

        askWithWorkingModel(prefs, key, prompt, READING_CHECK_SCHEMA).mapCatching { reply ->
            ReadingCheck.parseAll(reply, trimmed, ceiling).ifEmpty {
                error("The AI answered, but none of its questions checked out (bad JSON, wrong number of options, or evidence that isn't in the passage).")
            }
        }
    }

    /**
     * How many questions to allow for a passage of this length.
     *
     * One per [WORDS_PER_QUESTION] words, capped. The cap is the important half: this is an
     * interruption to someone's reading, and the risk the whole feature runs is turning a book
     * into a quiz. Three is already a lot to answer before carrying on.
     */
    internal fun questionCeiling(passage: String): Int {
        val words = passage.split(Regex("\\s+")).count { it.isNotBlank() }
        return (words / WORDS_PER_QUESTION + 1).coerceIn(1, ReadingCheck.MAX_QUESTIONS)
    }

    /**
     * Sends the prompt, and if the configured model is not one this key can use, finds one that is.
     *
     * Model ids get retired, and a retired one fails with a 404 that says nothing useful to anyone
     * reading it on a phone. Rather than hard-coding a guess - which is how this broke in the first
     * place - the API is asked what the key can actually call, a sensible one is picked and saved,
     * and the request is retried. Same lesson as the book catalogues: ask the source, don't guess.
     */
    private fun askWithWorkingModel(
        prefs: AiPrefs,
        key: String,
        prompt: String,
        schema: JSONObject?,
    ): Result<String> {
        val first = request(prefs, key, prompt, prefs.model, schema)
        if (first.isSuccess || !looksLikeUnknownModel(first.exceptionOrNull())) return first

        val available = fetchModels(key).getOrElse { return first }
        val replacement = pickModel(available) ?: return Result.failure(
            IllegalStateException(
                "\"${prefs.model}\" isn't available to this key. Models it can use: " +
                    available.joinToString(", ").ifBlank { "none reported" },
            ),
        )
        prefs.model = replacement
        return request(prefs, key, prompt, replacement, schema)
    }

    /** A 404 or an explicit "not found" is the API saying it has never heard of this model. */
    private fun looksLikeUnknownModel(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        return message.contains("HTTP 404") ||
            message.contains("not found", ignoreCase = true) ||
            message.contains("is not supported", ignoreCase = true)
    }

    /** Every model this key may call for generateContent, newest-looking first. */
    fun fetchModels(key: String): Result<List<String>> = runCatching {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("x-goog-api-key", key)
        }
        val code = connection.responseCode
        val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) error("HTTP $code. ${extractError(payload)}")

        val models = JSONObject(payload).optJSONArray("models") ?: return@runCatching emptyList()
        (0 until models.length()).mapNotNull { i ->
            val model = models.getJSONObject(i)
            val methods = model.optJSONArray("supportedGenerationMethods")
            val supported = (0 until (methods?.length() ?: 0))
                .any { methods!!.optString(it) == "generateContent" }
            // Names come back as "models/gemini-2.5-flash"; the request path wants the bare id.
            if (supported) model.optString("name").removePrefix("models/").takeIf { it.isNotBlank() } else null
        }
    }

    /**
     * Picks the best model for this job from what the key can use.
     *
     * Flash-class models are wanted specifically: the question is short, it is generated while
     * someone is reading, and it is paid for by the user - so speed and cost matter more than the
     * extra depth a pro model would bring to a four-option comprehension question.
     */
    fun pickModel(available: List<String>): String? {
        if (available.isEmpty()) return null
        fun score(name: String): Int {
            var points = 0
            if ("flash" in name) points += 100
            if ("lite" in name) points -= 10
            // Avoid previews, experiments and anything specialised (vision, tts, embedding).
            if ("preview" in name || "exp" in name) points -= 40
            if ("thinking" in name || "tts" in name || "embedding" in name || "vision" in name) points -= 200
            // Prefer the higher version number, so this keeps working as new ones appear.
            Regex("""(\d+)\.(\d+)""").find(name)?.let { m ->
                points += m.groupValues[1].toInt() * 10 + m.groupValues[2].toInt()
            }
            return points
        }
        return available.filterNot { "embedding" in it || "aqa" in it }.maxByOrNull { score(it) }
    }

    /** One call to the model, returning its text. Shared by every prompt this app sends. */
    private fun request(
        prefs: AiPrefs,
        key: String,
        prompt: String,
        model: String = prefs.model,
        jsonSchema: JSONObject? = null,
    ): Result<String> = runCatching {
        val requestBody = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))),
            ),
        )
        // Asking for JSON in the prompt is a request; a response schema is a constraint. With one
        // set, the model cannot reply with prose, a code fence, or a missing field - which removes
        // most of the ways a perfectly good question used to be thrown away by validation.
        if (jsonSchema != null) {
            requestBody.put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", jsonSchema),
            )
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            // The key goes in a header rather than the URL. As a query parameter, one stray
            // character from a paste corrupts the request and the API reports no credential at
            // all - a 401 that blames authentication for what is really a malformed URL.
            setRequestProperty("x-goog-api-key", key)
        }
        connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        connection.disconnect()

        if (code !in 200..299) {
            error(explain(code, payload))
        }
        parseAnswer(payload)
    }

    /** Below this there isn't enough read text for a question worth asking. */
    private const val MIN_PASSAGE_CHARS = 400

    /**
     * Reading per question allowed.
     *
     * Set so the default four-minute stretch (about 960 words at the tracker's assumed pace) comes
     * out at two rather than three. Three should be what a deliberately long stretch earns, not
     * what every ordinary one does.
     */
    private const val WORDS_PER_QUESTION = 500

    /** The exact shape a reading check must come back in - enforced by the API, not just asked for. */
    private val READING_CHECK_SCHEMA: JSONObject
        get() {
            val question = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("question", JSONObject().put("type", "STRING"))
                        .put("answer", JSONObject().put("type", "STRING"))
                        .put(
                            "distractors",
                            JSONObject()
                                .put("type", "ARRAY")
                                .put("items", JSONObject().put("type", "STRING")),
                        )
                        .put("evidence", JSONObject().put("type", "STRING")),
                )
                .put("required", JSONArray().put("question").put("answer").put("distractors").put("evidence"))
            return JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject().put(
                        "questions",
                        JSONObject().put("type", "ARRAY").put("items", question),
                    ),
                )
                .put("required", JSONArray().put("questions"))
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

    /**
     * Turns Google's wording into something that points at the actual fix.
     *
     * Its 401 says "Expected OAuth 2 access token", which sounds like the key is the wrong sort of
     * thing. It really means no key was recognised - so the useful advice is about the key itself,
     * not about OAuth, which this app does not and should not use.
     */
    private fun explain(code: Int, payload: String): String = when (code) {
        401 -> "The key wasn't accepted (HTTP 401). Check it was pasted whole and is still active " +
            "at aistudio.google.com/apikey. ${extractError(payload)}"
        403 -> "The key was refused (HTTP 403) - it may be restricted to certain apps or APIs, or " +
            "the Generative Language API may not be enabled for its project. ${extractError(payload)}"
        429 -> "Rate limit or quota reached (HTTP 429). ${extractError(payload)}"
        else -> "AI request failed (HTTP $code). ${extractError(payload)}"
    }

    private fun extractError(payload: String): String =
        runCatching { JSONObject(payload).getJSONObject("error").getString("message") }
            .getOrDefault(payload.take(180))
}

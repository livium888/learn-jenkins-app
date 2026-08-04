package com.flashcardreader.app.data.reference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free, no-API-key definition lookup for a word or a name:
 *  - Wiktionary REST for ordinary words (dictionary senses),
 *  - Wikipedia REST summary for names / places / multi-word proper nouns.
 * Picks the likelier source first and falls back to the other. Same HttpURLConnection approach
 * as the Gemini tutor and no new dependency (Jsoup is already used for EPUB parsing). This is the
 * free default that complements the optional, bring-your-own-key AI tutor.
 */
object DictionaryClient {

    /**
     * Looks up [query] and returns an explanation written in [explanationLang]. [readingLang] is
     * the language of the word itself: the Wiktionary response is queried on the explanation-
     * language edition (so definitions are in that language) but the reading-language section is
     * preferred, giving "Spanish word → English explanation" and disambiguating homographs.
     */
    suspend fun lookup(
        query: String,
        readingLang: String = "en",
        explanationLang: String = "en",
    ): String? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        // A capitalised or multi-word term is probably a name/place -> Wikipedia first.
        val looksLikeName = q.contains(' ') || (q.firstOrNull()?.isUpperCase() == true)
        if (looksLikeName) {
            wikipedia(q, explanationLang) ?: wiktionary(q, readingLang, explanationLang)
        } else {
            wiktionary(q, readingLang, explanationLang) ?: wikipedia(q, explanationLang)
        }
    }

    private fun wiktionary(word: String, readingLang: String, explanationLang: String): String? {
        val json = httpGet("https://$explanationLang.wiktionary.org/api/rest_v1/page/definition/${enc(word)}") ?: return null
        return try {
            val root = JSONObject(json)
            // Prefer the section for the language being read (disambiguates cognates/homographs)…
            firstDefinition(root.optJSONArray(readingLang))?.let { return it }
            // …otherwise fall back to the first non-blank definition from any language section.
            val keys = root.keys()
            while (keys.hasNext()) {
                firstDefinition(root.optJSONArray(keys.next()))?.let { return it }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** First non-blank, HTML-stripped definition in a Wiktionary language section, or null. */
    private fun firstDefinition(section: org.json.JSONArray?): String? {
        if (section == null) return null
        for (i in 0 until section.length()) {
            val defs = section.getJSONObject(i).optJSONArray("definitions") ?: continue
            for (j in 0 until defs.length()) {
                val text = Jsoup.parse(defs.getJSONObject(j).optString("definition", "")).text().trim()
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    private fun wikipedia(title: String, lang: String): String? {
        val json = httpGet("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title)}") ?: return null
        return try {
            JSONObject(json).optString("extract", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = "GET"
            instanceFollowRedirects = true
            // Wikimedia REST APIs require a descriptive User-Agent.
            setRequestProperty("User-Agent", "FlashcardReader/1.0 (personal reading app)")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

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

    /** [lang] is a Wikimedia edition code ("en", "fr", "de", …); definitions come back in it. */
    suspend fun lookup(query: String, lang: String = "en"): String? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        // A capitalised or multi-word term is probably a name/place -> Wikipedia first.
        val looksLikeName = q.contains(' ') || (q.firstOrNull()?.isUpperCase() == true)
        if (looksLikeName) wikipedia(q, lang) ?: wiktionary(q, lang) else wiktionary(q, lang) ?: wikipedia(q, lang)
    }

    private fun wiktionary(word: String, lang: String): String? {
        val json = httpGet("https://$lang.wiktionary.org/api/rest_v1/page/definition/${enc(word)}") ?: return null
        return try {
            val root = JSONObject(json)
            // The response is keyed by the word's language(s); the definition text itself is in the
            // edition's language. Take the first non-blank definition from any section (not just "en").
            val keys = root.keys()
            var result: String? = null
            outer@ while (keys.hasNext()) {
                val section = root.optJSONArray(keys.next()) ?: continue
                for (i in 0 until section.length()) {
                    val defs = section.getJSONObject(i).optJSONArray("definitions") ?: continue
                    for (j in 0 until defs.length()) {
                        val text = Jsoup.parse(defs.getJSONObject(j).optString("definition", "")).text().trim()
                        if (text.isNotBlank()) {
                            result = text
                            break@outer
                        }
                    }
                }
            }
            result
        } catch (e: Exception) {
            null
        }
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

package com.flashcardreader.app.reader

import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Fsrs

/** [contextSentence] is only populated for due matches - the occurrence log doesn't need it. */
data class TermMatch(val term: Term, val range: IntRange, val contextSentence: String = "")

/** The outcome of one scan of a page: terms present ([all]) and the subset FSRS says are due. */
data class ScanResult(val due: List<TermMatch>, val all: List<TermMatch>)

/**
 * Finds occurrences of tracked terms in a page of text. This is what makes the interstitial
 * global and spacing-aware rather than "quiz on every occurrence": a term you just reviewed
 * won't fire again just because it reappears on the same page.
 *
 * Matching is single-pass: instead of running one regex per tracked term (cost grows with the
 * vocabulary), an Aho-Corasick automaton finds every term in ONE walk of the text, so scan cost
 * is independent of how many words you've saved. The automaton is rebuilt only when the term set
 * changes. [scan] is synchronized because the reader runs it off the main thread and rapid
 * scrolling can overlap calls.
 */
class TermScanner(private val fsrs: Fsrs) {

    private var automaton: Aho? = null
    private var builtForTerms: List<Term>? = null

    @Synchronized
    fun scan(pageText: String, allTerms: List<Term>, now: Long): ScanResult {
        if (allTerms.isEmpty() || pageText.isEmpty()) return ScanResult(emptyList(), emptyList())
        val aho = ensureAutomaton(allTerms)

        // Earliest match start per term index (mirrors the old "first occurrence per term").
        val earliest = HashMap<Int, Int>()
        var state = 0
        for (i in pageText.indices) {
            val c = pageText[i].lowercaseChar()
            state = aho.step(state, c)
            val outs = aho.output[state]
            if (outs.isEmpty()) continue
            for (o in outs) {
                val termIdx = o[0]
                val len = o[1]
                val start = i - len + 1
                val end = i + 1 // exclusive, matches the old matcher.end()
                if (isWholeWord(pageText, start, end)) {
                    val prev = earliest[termIdx]
                    if (prev == null || start < prev) earliest[termIdx] = start
                }
            }
        }
        if (earliest.isEmpty()) return ScanResult(emptyList(), emptyList())

        val all = ArrayList<TermMatch>(earliest.size)
        val due = ArrayList<TermMatch>()
        for ((termIdx, start) in earliest) {
            val term = allTerms[termIdx]
            val end = start + term.normalizedText.length
            all.add(TermMatch(term, start..end))
            if (fsrs.isDue(term, now)) {
                val context = ContextExtractor.sentenceAround(pageText, start, end)
                due.add(TermMatch(term, start..end, context))
            }
        }
        return ScanResult(due = due.sortedBy { it.range.first }, all = all)
    }

    private fun ensureAutomaton(allTerms: List<Term>): Aho {
        val current = automaton
        if (current != null && builtForTerms === allTerms) return current
        val built = Aho(allTerms.map { it.normalizedText })
        automaton = built
        builtForTerms = allTerms
        return built
    }

    /** A `\b`-style boundary: the term must not sit inside a larger word on either side. */
    private fun isWholeWord(text: String, start: Int, end: Int): Boolean {
        val leftOk = start == 0 || !isWordChar(text[start - 1])
        val rightOk = end >= text.length || !isWordChar(text[end])
        return leftOk && rightOk
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * A compact Aho-Corasick automaton over the (case-folded) term strings. Nodes are stored in
     * parallel lists; [output] gives, per node, the `[termIndex, length]` of every term that ends
     * there (including via fail links, merged at build time).
     */
    private class Aho(terms: List<String>) {
        private val goto = ArrayList<HashMap<Char, Int>>()
        private val fail = ArrayList<Int>()
        val output = ArrayList<ArrayList<IntArray>>()

        init {
            newNode() // root = 0
            for ((termIdx, raw) in terms.withIndex()) {
                if (raw.isEmpty()) continue
                var node = 0
                for (ch in raw) {
                    val lc = ch.lowercaseChar()
                    node = goto[node][lc] ?: newNode().also { goto[node][lc] = it }
                }
                output[node].add(intArrayOf(termIdx, raw.length))
            }
            // Breadth-first fail links; merge each node's output with its fail target's.
            val queue = ArrayDeque<Int>()
            for (next in goto[0].values) {
                fail[next] = 0
                queue.add(next)
            }
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                for ((ch, v) in goto[u]) {
                    queue.add(v)
                    var f = fail[u]
                    while (f != 0 && goto[f][ch] == null) f = fail[f]
                    fail[v] = goto[f][ch]?.takeIf { it != v } ?: 0
                    output[v].addAll(output[fail[v]])
                }
            }
        }

        /** Transition from [state] on char [c], following fail links then the goto edge. */
        fun step(state: Int, c: Char): Int {
            var s = state
            while (s != 0 && goto[s][c] == null) s = fail[s]
            return goto[s][c] ?: 0
        }

        private fun newNode(): Int {
            goto.add(HashMap())
            fail.add(0)
            output.add(ArrayList())
            return goto.size - 1
        }
    }
}

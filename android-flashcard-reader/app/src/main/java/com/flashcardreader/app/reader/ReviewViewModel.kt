package com.flashcardreader.app.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flashcardreader.app.ai.QuestionFeedback
import com.flashcardreader.app.ai.ReadingCheck
import com.flashcardreader.app.data.db.entities.ReadingCheckCard
import com.flashcardreader.app.data.db.entities.ReviewContext
import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Confidence
import com.flashcardreader.app.data.fsrs.Rating
import com.flashcardreader.app.data.repository.LibraryRepository
import com.flashcardreader.app.data.repository.ReadingCheckRepository
import com.flashcardreader.app.data.repository.TermRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val queue: List<Term> = emptyList(),
    /** Due comprehension questions, mixed in among the words rather than queued behind them. */
    val checkQueue: List<ReadingCheckCard> = emptyList(),
    /** True when the next thing to answer is a comprehension question. */
    val showCheckNext: Boolean = false,
    val contextSentence: String = "",
    /** Title of the book the shown sentence was read in, for the "you read this in …" cue. */
    val contextSource: String = "",
    val loading: Boolean = true,
)

/**
 * Standalone review queue for due flashcards that haven't naturally reappeared
 * in anything you're currently reading. This is what keeps the spacing
 * schedule honest even if a word never resurfaces in your current book.
 */
class ReviewViewModel(
    private val termRepository: TermRepository,
    private val libraryRepository: LibraryRepository,
    private val readingCheckRepository: ReadingCheckRepository,
    private val feedback: QuestionFeedback? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val due = termRepository.allTerms().filter { termRepository.isDue(it, now) }
            val checks = runCatching { readingCheckRepository.due(now) }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    queue = due,
                    checkQueue = checks,
                    // With no words due, a question is the only thing left to ask.
                    showCheckNext = due.isEmpty() && checks.isNotEmpty(),
                    loading = false,
                )
            }
            loadContextForCurrent()
        }
    }

    /** Looks up where the current queue item was last seen and pulls its sentence for context. */
    private fun loadContextForCurrent() {
        val term = _uiState.value.queue.firstOrNull()
        if (term == null) {
            _uiState.update { it.copy(contextSentence = "", contextSource = "") }
            return
        }
        viewModelScope.launch {
            // A *different* real sentence from your own reading each time (encoding variability).
            val occurrence = termRepository.randomOccurrence(term.id)
            val source = occurrence?.let { libraryRepository.getSource(it.sourceId) }
            val text = source?.let { libraryRepository.readText(it) }
            val sentence = if (occurrence != null && text != null) {
                ContextExtractor.sentenceAround(text, occurrence.charOffset, occurrence.charOffset + term.displayText.length)
            } else {
                ""
            }
            _uiState.update { it.copy(contextSentence = sentence, contextSource = source?.title.orEmpty()) }
        }
    }

    /**
     * Records an answer to a due comprehension question. The rating comes from the answer itself,
     * never from a self-assessment - a tap on the right option out of four is evidence.
     */
    fun answerCurrentCheck(correct: Boolean) {
        val card = _uiState.value.checkQueue.firstOrNull() ?: return
        viewModelScope.launch {
            readingCheckRepository.answer(card, correct, context = ReviewContext.REVIEW)
            _uiState.update { it.copy(checkQueue = it.checkQueue.drop(1)) }
            advanceAfterCheck()
        }
    }

    /**
     * Throws the current question away as a bad one, and keeps a copy of it for tuning.
     *
     * Deleted rather than merely buried: a question that is wrong or unfair should not come back
     * on a schedule, and leaving it in the deck to be skipped forever is the worse of the two.
     */
    fun rejectCurrentCheck() {
        val card = _uiState.value.checkQueue.firstOrNull() ?: return
        viewModelScope.launch {
            feedback?.record(
                ReadingCheck(
                    question = card.question,
                    correctAnswer = card.correctAnswer,
                    distractors = card.wrongOptions,
                    evidence = card.evidence,
                ),
                book = libraryRepository.getSource(card.sourceId)?.title.orEmpty(),
            )
            readingCheckRepository.discard(card)
            _uiState.update { it.copy(checkQueue = it.checkQueue.drop(1)) }
            advanceAfterCheck()
        }
    }

    fun skipCurrentCheck() {
        _uiState.update { it.copy(checkQueue = it.checkQueue.drop(1)) }
        advanceAfterCheck()
    }

    /** Back to words after a question, unless the words have run out. */
    private fun advanceAfterCheck() {
        answeredSinceCheck = 0
        _uiState.update {
            it.copy(showCheckNext = it.queue.isEmpty() && it.checkQueue.isNotEmpty())
        }
    }

    /**
     * How many words to answer between comprehension questions.
     *
     * They used to sit strictly behind the whole vocabulary queue, which meant that with a real
     * backlog of due words you would never reach one - indistinguishable from the questions not
     * being saved at all. Mixing them in means both kinds actually get reviewed.
     */
    private var answeredSinceCheck = 0
    private val wordsBetweenChecks = 3

    fun answerCurrent(rating: Rating, confidence: Confidence) {
        val term = _uiState.value.queue.firstOrNull() ?: return
        viewModelScope.launch {
            termRepository.submitReview(term, rating, confidence, ReviewContext.REVIEW)
            answeredSinceCheck++
            _uiState.update {
                val remaining = it.queue.drop(1)
                it.copy(
                    queue = remaining,
                    showCheckNext = it.checkQueue.isNotEmpty() &&
                        (remaining.isEmpty() || answeredSinceCheck >= wordsBetweenChecks),
                )
            }
            loadContextForCurrent()
        }
    }
}

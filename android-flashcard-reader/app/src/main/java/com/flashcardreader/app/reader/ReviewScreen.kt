package com.flashcardreader.app.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.ai.ReadingCheck
import com.flashcardreader.app.ui.AppTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(viewModel: ReviewViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppTopBar(title = "Due for review", onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val current = state.queue.firstOrNull()
            val currentCheck = state.checkQueue.firstOrNull()
            when {
                state.loading -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Mixed in among the words rather than queued behind them, so a backlog of due
                // vocabulary can never bury the reading questions completely.
                currentCheck != null && (state.showCheckNext || current == null) -> ReadingCheckDialog(
                    check = ReadingCheck(
                        question = currentCheck.question,
                        correctAnswer = currentCheck.correctAnswer,
                        distractors = currentCheck.wrongOptions,
                        evidence = currentCheck.evidence,
                    ),
                    onAnswered = viewModel::answerCurrentCheck,
                    onSkip = viewModel::skipCurrentCheck,
                    remaining = state.queue.size + state.checkQueue.size,
                )
                current == null -> Text(
                    "Nothing due right now — nice work.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
                else -> FlashcardDialog(
                    term = current,
                    contextSentence = state.contextSentence,
                    sourceLabel = state.contextSource,
                    remaining = state.queue.size,
                    onExit = onBack,
                    earnMode = viewModel.focusEnabled,
                    onEarned = viewModel::earnFromCard,
                    onAnswered = viewModel::answerCurrent,
                )
            }
        }
    }
}

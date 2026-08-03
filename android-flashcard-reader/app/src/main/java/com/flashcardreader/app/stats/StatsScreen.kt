package com.flashcardreader.app.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                navigationIcon = { IconButton(onClick = onBack) { Text("<") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatRow("Words tracked", stats.total.toString())
            StatRow("Due now", stats.dueNow.toString())
            StatRow("Reviewed today", stats.reviewedToday.toString())
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("Maturity")
            StatRow("New", stats.newCount.toString())
            StatRow("Learning", stats.learningCount.toString())
            StatRow("In review", stats.reviewCount.toString())
            StatRow("Relearning", stats.relearningCount.toString())
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("Retrieval")
            StatRow("Total reviews", stats.totalReviews.toString())
            StatRow("Retention (approx.)", stats.retentionPct?.let { "$it%" } ?: "—")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("Flagged")
            StatRow("★ Curious", stats.curious.toString())
            StatRow("⚠ High-confidence misses", stats.hyperMiss.toString())

            Text(
                "Retention is a rough proxy (1 − lapses ÷ reviews). It sharpens as you review more.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
    }
}

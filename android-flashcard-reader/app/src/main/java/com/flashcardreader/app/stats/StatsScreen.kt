package com.flashcardreader.app.stats

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.reminders.ReminderScheduler
import com.flashcardreader.app.ui.AppTopBar
import com.flashcardreader.app.ui.SectionCard
import com.flashcardreader.app.ui.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit, onOpenAiSettings: () -> Unit) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppTopBar(title = "Progress", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            SectionCard {
                SectionTitle("Today")
                StatRow("Words tracked", stats.total.toString())
                StatRow("Due now", stats.dueNow.toString())
                StatRow("Reviewed today", stats.reviewedToday.toString())
            }

            SectionCard {
                SectionTitle("Maturity")
                StatRow("New", stats.newCount.toString())
                StatRow("Learning", stats.learningCount.toString())
                StatRow("In review", stats.reviewCount.toString())
                StatRow("Relearning", stats.relearningCount.toString())
            }

            SectionCard {
                SectionTitle("Retrieval")
                StatRow("Total reviews", stats.totalReviews.toString())
                StatRow("Retention (approx.)", stats.retentionPct?.let { "$it%" } ?: "—")
                Text(
                    "A rough proxy (1 − lapses ÷ reviews). It sharpens as you review more.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                SectionTitle("Flagged")
                StatRow("★ Curious", stats.curious.toString())
                StatRow("⚠ High-confidence misses", stats.hyperMiss.toString())
            }

            SectionCard {
                SectionTitle("Reminders")
                ReminderToggle()
            }

            AiSettingsRow(onOpenAiSettings)
        }
    }
}

@Composable
private fun AiSettingsRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("AI tutor settings", style = MaterialTheme.typography.bodyLarge)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReminderToggle() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ReminderScheduler.isEnabled(context)) }

    // Turning reminders on needs the Android 13+ notification permission first.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        enabled = granted
        ReminderScheduler.setEnabled(context, granted)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Evening + morning nudges", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = enabled,
            onCheckedChange = { wantOn ->
                if (wantOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    enabled = wantOn
                    ReminderScheduler.setEnabled(context, wantOn)
                }
            },
        )
    }
    Text(
        "A quick review before bed and after waking, when memory consolidates. " +
            "Timing is best-effort and can be delayed by battery settings.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
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

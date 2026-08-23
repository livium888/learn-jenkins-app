package com.flashcardreader.app.stats

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.reminders.ReminderScheduler
import com.flashcardreader.app.ui.AppTopBar
import com.flashcardreader.app.data.repository.ReadingLog
import com.flashcardreader.app.ui.SectionCard
import com.flashcardreader.app.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenFocusGate: () -> Unit,
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Read once when the screen opens rather than observed: it only changes from the reader or the
    // review queue, never while this screen is in front of you.
    var flagged by remember { mutableStateOf(viewModel.flaggedQuestions) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            val json = viewModel.exportJson()
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
            snackbar.showSnackbar("Backup saved")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            if (text != null) {
                val restored = viewModel.importJson(text)
                snackbar.showSnackbar(describeRestore(restored))
            } else {
                snackbar.showSnackbar("Couldn't read that file")
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Progress", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
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

            // The number the app has always known and never shown. Time with a book open is easy
            // to accrue; time actually reading is not, and the anti-fake tracker can tell them
            // apart. Seeing the gap is a cheaper corrective than any prompt.
            ReadingAttentionCard()

            // Proof the comprehension questions are being kept and scheduled, not just asked once
            // and thrown away - which is impossible to tell from the reader alone.
            if (stats.readingChecks > 0) {
                SectionCard {
                    SectionTitle("Reading questions")
                    StatRow("Written from your reading", stats.readingChecks.toString())
                    StatRow("Due now", stats.readingChecksDue.toString())
                    StatRow("Answered", stats.readingChecksAnswered.toString())
                    StatRow("Never missed", stats.readingChecksRemembered.toString())
                    Text(
                        "Each one is scheduled by the same algorithm as your words, so it comes back " +
                            "when you are about to forget it rather than on a fixed timetable.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                StatRow(
                    "Schedule fitted to you",
                    if (stats.scheduleFittedFrom > 0) "from ${stats.scheduleFittedFrom} reviews" else "not yet",
                )
                Text(
                    if (stats.scheduleFittedFrom > 0) {
                        "Your intervals are now set from your own review history rather than from " +
                            "FSRS's published averages, and are refitted as more history builds up."
                    } else {
                        "Intervals currently use FSRS's published defaults. Once you have answered " +
                            "enough cards, they are refitted to how you actually forget."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "A rough proxy (1 − lapses ÷ reviews). It sharpens as you review more.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Only appears once a question has actually been rejected. The questions are written
            // by a model against a prompt that has never been evaluated; this is the only place
            // that judgement gets recorded rather than being lost in the moment.
            if (flagged > 0) {
                SectionCard {
                    SectionTitle("Questions you rejected")
                    StatRow("Thrown away as bad", flagged.toString())
                    Text(
                        "Copy this and send it, and the questions can be made better. Each one is " +
                            "kept with the lines it claimed as evidence.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Copy the report",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val clipboard = context
                                    .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("Flagged questions", viewModel.flaggedQuestionsReport()),
                                )
                                scope.launch { snackbar.showSnackbar("Report copied") }
                            }
                            .padding(vertical = 8.dp),
                    )
                    Text(
                        "Clear them",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.clearFlaggedQuestions()
                                flagged = 0
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }

            SectionCard {
                SectionTitle("Flagged")
                StatRow("★ Curious", stats.curious.toString())
                StatRow("⚠ High-confidence misses", stats.hyperMiss.toString())
            }

            if (stats.calibration.any { it.total > 0 }) {
                SectionCard {
                    SectionTitle("Calibration")
                    stats.calibration.forEach { level ->
                        StatRow(
                            "Felt ${level.label.lowercase()}",
                            level.pct?.let { "$it% right · ${level.total}" } ?: "—",
                        )
                    }
                    Text(
                        stats.calibrationNote
                            ?: "How often your confidence matched reality. Well-calibrated readers feel sure exactly when they are.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard {
                SectionTitle("Reminders")
                ReminderToggle()
            }

            SectionCard {
                SectionTitle("Backup")
                Text(
                    "Back up everything",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { exportLauncher.launch("flashcards-backup.json") }
                        .padding(vertical = 8.dp),
                )
                Text(
                    "Restore from a backup",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                        .padding(vertical = 8.dp),
                )
                Text(
                    "Saves your words, the questions written from your reading, and the review " +
                        "history the schedule is fitted to - to one file you can move to a new " +
                        "phone. Restoring adds anything not already here. Books aren't included; " +
                        "they can be added again, the reading of them can't.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsRow("Focus Gate", onOpenFocusGate)
            AiSettingsRow(onOpenAiSettings)
        }
    }
}

/** A settings entry row, styled like the AI one. */
@Composable
private fun SettingsRow(label: String, onClick: () -> Unit) {
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
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                Icons.Filled.KeyboardArrowRight,
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

/**
 * Time open versus time genuinely read, today.
 *
 * "Actually read" means it passed the anti-fake rules - centred in view, dwelt on long enough for
 * its length, inside a global rate cap, with a real finger on the screen - so the comparison is
 * honest rather than flattering. Stated without praise or scolding on purpose: the gap speaks for
 * itself, and a number that lectures gets ignored.
 */
@Composable
private fun ReadingAttentionCard() {
    val context = LocalContext.current
    // Read once per composition rather than observed: this is a daily tally, not a live readout.
    val log = remember { ReadingLog(context) }
    val open = log.openSecondsToday
    val read = log.readSecondsToday
    val pct = log.attentionPct

    SectionCard {
        SectionTitle("Attention")
        StatRow("Book open today", formatReadingMinutes(open))
        StatRow("Actually read", formatReadingMinutes(read))
        StatRow("Of the time open", pct?.let { "$it%" } ?: "—")
        Text(
            if (pct == null) {
                "Read for a minute or two and this fills in."
            } else {
                "“Actually read” counts only pages that stayed in view long enough to have been " +
                    "read, with your hand on the screen. Scrolling past doesn't count."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatReadingMinutes(seconds: Long): String = when {
    seconds < 60 -> "under a minute"
    seconds < 3_600 -> "${seconds / 60} min"
    else -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
}

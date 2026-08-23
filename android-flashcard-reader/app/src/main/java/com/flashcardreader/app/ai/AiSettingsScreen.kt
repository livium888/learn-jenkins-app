package com.flashcardreader.app.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.ui.AppTopBar
import androidx.compose.material3.Surface
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.SectionCard
import com.flashcardreader.app.ui.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AiPrefs(context) }

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var language by remember { mutableStateOf(prefs.myLanguage) }
    var model by remember { mutableStateOf(prefs.model) }
    var template by remember { mutableStateOf(prefs.promptTemplate) }
    var readingChecks by remember { mutableStateOf(prefs.readingChecks) }
    var checkMinutes by remember { mutableStateOf(prefs.readingCheckMinutes) }
    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { AppTopBar(title = "AI tutor", onBack = onBack) },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Enable AI tutor", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.enabled = it })
                }
                Text(
                    "When on, tapping “Check my guess” sends the word, its sentence, and your guess to " +
                        "Google Gemini and shows its feedback — only on that tap, and only those three things.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A separate switch from the tutor above, and off by default, because it is a different
            // bargain: the tutor sends a word when you ask it to, this sends pages of your book by
            // itself. Handing over an API key must never be treated as consent to upload books.
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ask me about what I read", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = readingChecks,
                        onCheckedChange = { readingChecks = it; prefs.readingChecks = it },
                    )
                }
                Text(
                    "Every few minutes of genuine reading, a question about the passage you just read — " +
                        "four options, one tap. Get it wrong and it shows you the lines it came from. " +
                        "Each question then joins your review schedule like any other card.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "This one is different from the tutor above, so read it before switching it on: it " +
                        "sends the pages you have read to Google Gemini automatically, without asking each " +
                        "time. Only pages you actually read are sent, never the whole book — but if a book " +
                        "is private, leave this off or exclude that book from its page in your library. " +
                        "Roughly a thousand words per question; an hour of reading is about fifteen.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (readingChecks) {
                    Text(
                        "Ask about every $checkMinutes minutes of reading",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = checkMinutes.toFloat(),
                        onValueChange = { checkMinutes = it.toInt() },
                        onValueChangeFinished = { prefs.readingCheckMinutes = checkMinutes },
                        valueRange = AiPrefs.MIN_MINUTES.toFloat()..AiPrefs.MAX_MINUTES.toFloat(),
                        steps = AiPrefs.MAX_MINUTES - AiPrefs.MIN_MINUTES - 1,
                    )
                    Text(
                        "Measured in reading actually done, not minutes on the clock — skim a chapter and " +
                            "no question comes, because there is nothing you read to ask about.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Waiting minutes to find out your key is wrong is a miserable way to learn it.
                    // This asks the model one question about a fixed passage and shows the raw
                    // answer, so a broken key, a retired model or a refused request says so at once.
                    PrimaryButton(
                        text = if (testing) "Testing…" else "Test it now",
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testResult = ""
                            scope.launch {
                                // Check the key first and separately. A failure here is about the
                                // key; a failure afterwards is about the model or the question -
                                // and telling those apart is most of the diagnosis.
                                val models = withContext(Dispatchers.IO) {
                                    GeminiTutor.fetchModels(prefs.apiKey)
                                }
                                testResult = models.fold(
                                    onSuccess = { names ->
                                        val outcome = GeminiTutor.generateReadingChecks(context, SAMPLE_PASSAGE)
                                        outcome.fold(
                                            onSuccess = { checks ->
                                                // Every question is shown, not just the first: the
                                                // test is now partly "does it find more than one
                                                // idea in a passage", and one line would hide that.
                                                buildString {
                                                    append("Working, using ${prefs.model}.")
                                                    append("\n\nIt asked ${checks.size} question")
                                                    if (checks.size != 1) append("s")
                                                    append(":")
                                                    checks.forEach { check ->
                                                        append("\n\n${check.question}")
                                                        append("\nAnswer: ${check.correctAnswer}")
                                                    }
                                                }
                                            },
                                            onFailure = { error ->
                                                "Key is fine (${names.size} models available), but the " +
                                                    "question failed:\n${error.message}\n\nModels this key can use:\n" +
                                                    names.take(12).joinToString("\n")
                                            },
                                        )
                                    },
                                    onFailure = { "The key itself was rejected:\n${it.message}" },
                                )
                                model = prefs.model
                                testing = false
                            }
                        },
                    )
                    if (testResult.isNotBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                testResult,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }

            SectionCard {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; prefs.apiKey = it },
                    label = { Text("Gemini API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Stored only on this device. Get a free key at aistudio.google.com/apikey.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it; prefs.myLanguage = it },
                    label = { Text("Your language (for translations)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; prefs.model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard {
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it; prefs.promptTemplate = it },
                    label = { Text("Prompt template") },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Placeholders: {word}, {sentence}, {my_guess}, {my_language}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { template = AiPrefs.DEFAULT_PROMPT; prefs.promptTemplate = AiPrefs.DEFAULT_PROMPT }) {
                    Text("Reset prompt to default")
                }
            }
        }
    }
}

/**
 * A passage with enough going on to write a real comprehension question about - used only by the
 * "Test it now" button, so the setup can be proved without first reading for several minutes.
 */
private const val SAMPLE_PASSAGE = """
The lamplighter went along the street at dusk, tilting his pole to each wick in turn. He had walked
the same route for thirty years, and knew which lamps guttered in a wind from the east. On those he
lingered, sheltering the flame with his cap until it steadied. The boys who followed him for the
first few corners never understood why he was slower at the corner of Mill Street, and he had long
since stopped explaining it to them.
"""

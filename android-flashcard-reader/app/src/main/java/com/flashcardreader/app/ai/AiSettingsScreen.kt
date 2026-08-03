package com.flashcardreader.app.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI tutor") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enable AI tutor", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.enabled = it })
            }
            Text(
                "When on, tapping \"Check my guess\" sends the word, its sentence, and your guess to " +
                    "Google Gemini and shows its feedback. This is the only time anything leaves your " +
                    "device.",
                style = MaterialTheme.typography.labelSmall,
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; prefs.apiKey = it },
                label = { Text("Gemini API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Stored only on this device. Get a free key at aistudio.google.com/apikey.",
                style = MaterialTheme.typography.labelSmall,
            )

            OutlinedTextField(
                value = language,
                onValueChange = { language = it; prefs.myLanguage = it },
                label = { Text("Your language (for translations)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it; prefs.model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = template,
                onValueChange = { template = it; prefs.promptTemplate = it },
                label = { Text("Prompt template") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Placeholders: {word}, {sentence}, {my_guess}, {my_language}.",
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = { template = AiPrefs.DEFAULT_PROMPT; prefs.promptTemplate = AiPrefs.DEFAULT_PROMPT }) {
                Text("Reset prompt to default")
            }
        }
    }
}

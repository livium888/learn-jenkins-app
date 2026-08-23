package com.flashcardreader.app.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.Spacing

/**
 * Shown instead of the app when the previous run crashed.
 *
 * It replaces the whole UI rather than appearing as a dialog over it, because the crash that
 * matters most is the one that happens on launch: anything that tries to draw the real screens
 * first would crash again before this could be read. Nothing here touches the database or any
 * setting, for the same reason.
 */
@Composable
fun CrashReportScreen(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            Text("The app closed unexpectedly", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Here is exactly what went wrong. Copying this and sending it is the fastest way " +
                    "to get it fixed - it names the file and line.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Scrolls both ways: stack frames are long lines, and wrapping them makes a trace
                // much harder to read than sliding it sideways.
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(Spacing.gap),
                )
            }
            PrimaryButton(
                text = "Copy the report",
                onClick = { copyToClipboard(context, report) },
            )
            OutlineButton(text = "Continue to the app", onClick = onDismiss)
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Crash report", text))
}

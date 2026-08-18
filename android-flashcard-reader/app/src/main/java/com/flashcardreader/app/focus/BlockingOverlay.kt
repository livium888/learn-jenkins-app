package com.flashcardreader.app.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.flashcardreader.app.ui.OutlineButton
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.SectionCard
import com.flashcardreader.app.ui.Spacing

/**
 * The gate shown over a blocked app once the balance runs out.
 *
 * Full-bleed rather than a dialog: AppDialog is a Compose Dialog (its own window, needs an activity
 * window token) and is not full-screen, so it cannot be used from a service overlay.
 *
 * The tone is deliberately matter-of-fact rather than scolding - this is a bank balance, not a
 * telling-off, and the way out is to go and read something.
 */
@Composable
fun BlockingGate(
    appLabel: String,
    balanceSeconds: Long,
    onRead: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.screen),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionCard {
                Text(appLabel, style = MaterialTheme.typography.titleLarge)
                Text(
                    "No reading time banked.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "You have ${formatBalance(balanceSeconds)}. Read for a few minutes and it will " +
                        "unlock itself - skimming or scrolling past doesn't count.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
                PrimaryButton(text = "Read to earn time", onClick = onRead)
                OutlineButton(text = "Close", onClick = onClose)
            }
        }
    }
}

/** "0 minutes" / "1 minute" / "12 minutes" - seconds are noise at this granularity. */
fun formatBalance(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes == 1L) "1 minute" else "$minutes minutes"
}

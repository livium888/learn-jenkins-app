package com.flashcardreader.app.focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.ui.AppTopBar
import com.flashcardreader.app.ui.PrimaryButton
import com.flashcardreader.app.ui.SectionCard
import com.flashcardreader.app.ui.Spacing

/**
 * Focus Gate settings: turn it on, grant the two Settings permissions, choose which apps are gated,
 * and see the current balance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusGateScreen(
    viewModel: FocusGateViewModel,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Settings grants always report RESULT_CANCELED, and some OEM screens never return, so the
    // only reliable check is to re-read the real state every time we come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val ready = state.hasUsageAccess && state.canOverlay

    Scaffold(topBar = { AppTopBar(title = "Focus Gate", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            SectionCard {
                SectionTitle("Banked time")
                Text(
                    formatBalance(state.balanceSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "Reading earns time you can spend in the apps you've gated. Skimming, " +
                        "auto-scrolling and leaving the app open don't count.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Gate my chosen apps", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = state.enabled,
                        enabled = ready,
                        onCheckedChange = { viewModel.setEnabled(it) },
                    )
                }
                if (!ready) {
                    Text(
                        "Grant the two permissions below first.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard {
                SectionTitle("Permissions")
                PermissionRow(
                    title = "Usage access",
                    explanation = "Lets the app notice when you open a gated app. It only ever " +
                        "reads which app is in front - never what you do in it.",
                    granted = state.hasUsageAccess,
                    onGrant = { UsageAccess.open(context, UsageAccess.usageAccessIntent()) },
                )
                PermissionRow(
                    title = "Display over other apps",
                    explanation = "Lets the gate appear on top when there's no time banked.",
                    granted = state.canOverlay,
                    onGrant = { UsageAccess.open(context, UsageAccess.overlayIntent(context)) },
                )
                PermissionRow(
                    title = "Battery optimisation",
                    explanation = "Optional, but on many phones the gate is shut down in the " +
                        "background unless this app is exempted.",
                    granted = false,
                    grantLabel = "Open settings",
                    onGrant = { UsageAccess.open(context, UsageAccess.batteryOptimisationIntent()) },
                )
            }

            SectionCard {
                SectionTitle("Gated apps")
                Text(
                    if (state.blocked.isEmpty()) "None chosen yet" else "${state.blocked.size} chosen",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenApps() }
                        .padding(vertical = 8.dp),
                )
                Text(
                    "Whole apps only. Blocking one website inside a browser would need much deeper " +
                        "access to what you're doing, which this app deliberately doesn't ask for.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                SectionTitle("Worth knowing")
                Text(
                    "This is a speed bump, not a lock. You can always turn it off here, revoke the " +
                        "permissions, or uninstall the app - and on some phones the system will " +
                        "stop it in the background by itself. That's the honest limit of what any " +
                        "app can do without taking over your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    explanation: String,
    granted: Boolean,
    grantLabel: String = "Grant",
    onGrant: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (granted) {
                Text(
                    "Granted",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            explanation,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) PrimaryButton(text = grantLabel, onClick = onGrant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun StatValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
    }
}

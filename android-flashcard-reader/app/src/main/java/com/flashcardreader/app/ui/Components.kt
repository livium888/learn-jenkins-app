package com.flashcardreader.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The "calm paper" component kit. One vocabulary of surfaces, buttons, and bars so every
 * screen shares the same rhythm - generous padding, soft corners, comfortable touch
 * targets - instead of each screen improvising its own spacing.
 */
object Spacing {
    val screen = 20.dp
    val section = 24.dp
    val gap = 16.dp
    val tight = 8.dp
}

private val ButtonShapePad = PaddingValues(vertical = 14.dp)

/** A center-aligned top bar with a proper back arrow (no more bare "<"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = actions,
    )
}

/**
 * A roomy, rounded dialog surface used for every modal in the app, replacing Material's
 * cramped AlertDialog. Content is a single scrollable column with even 16dp rhythm.
 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 660.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
                content = content,
            )
        }
    }
}

/** A grouped surface for list/stat sections - the soft "paper card". */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            content = content,
        )
    }
}

/** Full-width filled primary action with a comfortable 54dp+ target. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        contentPadding = ButtonShapePad,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Full-width tonal secondary action (quieter than primary, still tappable-large). */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        contentPadding = ButtonShapePad,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** A simple "Are you sure?" dialog for destructive actions. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(text = confirmLabel, onClick = onConfirm)
        OutlineButton(text = "Cancel", onClick = onDismiss)
    }
}

/** Full-width outlined action for low-emphasis choices (Cancel, Skip). */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        contentPadding = ButtonShapePad,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

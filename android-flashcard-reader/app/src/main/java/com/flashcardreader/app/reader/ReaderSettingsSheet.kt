package com.flashcardreader.app.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.ai.AiPrefs
import com.flashcardreader.app.theme.ComfortLight
import com.flashcardreader.app.theme.ReaderFont
import com.flashcardreader.app.theme.ReaderPalette
import com.flashcardreader.app.theme.ReaderTypography
import com.flashcardreader.app.theme.colorsFor
import com.flashcardreader.app.ui.AppDialog
import com.flashcardreader.app.ui.PrimaryButton

/** Typography/theme controls - font, size, line height, and the page and night themes. */
@Composable
fun ReaderSettingsSheet(
    typography: ReaderTypography,
    onChange: (ReaderTypography) -> Unit,
    onDismiss: () -> Unit,
    /** This book, so its comprehension checks can be turned off without leaving the reader. */
    sourceId: Long = 0,
    /** Why a reading question has or hasn't appeared - built fresh each time it's shown. */
    readingCheckReport: () -> String = { "" },
    /** Why the chosen font isn't the one on screen, or null when it is. */
    fontError: String? = null,
    /** Throw the cached font away and fetch it again. */
    onRetryFont: () -> Unit = {},
) {
    val context = LocalContext.current
    val aiPrefs = remember { AiPrefs(context) }
    // Only worth showing when checks are actually running; otherwise it is a switch about nothing.
    val checksOn = aiPrefs.isReady && aiPrefs.readingChecks
    var excluded by remember(sourceId) { mutableStateOf(sourceId in aiPrefs.excludedSources) }
    var showCheckReport by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    AppDialog(onDismiss = onDismiss) {
        Text("Reading settings", style = MaterialTheme.typography.titleLarge)

        // The per-book opt-out lives here rather than in the library list, because this is where
        // you are when you notice that *this* book is one you would rather not send anywhere.
        if (checksOn && sourceId != 0L) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ask me about this book", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = !excluded,
                    onCheckedChange = {
                        excluded = !it
                        aiPrefs.setExcluded(sourceId, excluded)
                    },
                )
            }
            Text(
                if (excluded) {
                    "Off for this book — none of it is sent anywhere."
                } else {
                    "Pages you read in this book are sent to Gemini to write the questions."
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // "Nothing happened" has a dozen possible causes and no way to tell them apart, so the
        // reader can ask what the app thinks it is doing rather than guessing - or waiting.
        if (aiPrefs.readingChecks && sourceId != 0L) {
            TextButton(onClick = { showCheckReport = !showCheckReport }) {
                Text(if (showCheckReport) "Hide question status" else "Why no question yet?")
            }
            if (showCheckReport) {
                val report = readingCheckReport()
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                    Text("Copy status")
                }
            }
        }

        Label("Font")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            ReaderFont.values().forEach { font ->
                FilterChip(
                    selected = typography.font == font,
                    onClick = { onChange(typography.copy(font = font)) },
                    label = { Text(font.label) },
                )
            }
        }
        // OpenDyslexic and Atkinson are fetched on first use. That used to fail in silence, so
        // choosing one on a phone that could not reach the CDN looked like a setting being ignored.
        if (fontError != null) {
            Text(
                "${typography.font.label} isn't downloaded yet — $fontError. " +
                    "Showing a plain sans-serif until it arrives.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetryFont) { Text("Try downloading it again") }
        }

        LayoutSlider(
            value = typography.fontSizeSp,
            range = 12f..28f,
            label = { "Text size: ${it.toInt()}sp" },
            onCommit = { onChange(typography.copy(fontSizeSp = it)) },
        )

        LayoutSlider(
            value = typography.lineHeightMultiplier,
            range = 1.0f..2.2f,
            label = { "Line spacing: ×${"%.1f".format(it)}" },
            onCommit = { onChange(typography.copy(lineHeightMultiplier = it)) },
        )

        // Split light from dark rather than one long scroller: the choice you are making is
        // almost always "a page for this room", and the two groups are that question's two answers.
        Label("Page")
        ThemeRow(ReaderPalette.values().filterNot { it.isDark }, typography, onChange)
        Label("Night")
        ThemeRow(ReaderPalette.values().filter { it.isDark }, typography, onChange)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Justify text", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = typography.justify,
                onCheckedChange = { onChange(typography.copy(justify = it)) },
            )
        }

        LayoutSlider(
            value = typography.marginScale,
            range = 0.5f..2.5f,
            label = { "Margins: ×${"%.1f".format(it)}" },
            onCommit = { onChange(typography.copy(marginScale = it)) },
        )

        LayoutSlider(
            value = typography.letterSpacingEm,
            range = 0f..0.2f,
            label = { "Letter spacing: ${(it * 100).toInt()}" },
            onCommit = { onChange(typography.copy(letterSpacingEm = it)) },
        )

        TextButton(onClick = {
            onChange(
                typography.copy(
                    font = ReaderFont.SANS,
                    justify = false,
                    lineHeightMultiplier = 1.8f,
                    letterSpacingEm = 0.12f,
                    fontSizeSp = maxOf(typography.fontSizeSp, 20f),
                ),
            )
        }) {
            Text("Apply dyslexia-friendly preset")
        }

        val systemBrightness = typography.brightness < 0f
        Label(if (systemBrightness) "Brightness: system" else "Brightness: ${(typography.brightness * 100).toInt()}%")
        Slider(
            value = if (systemBrightness) 0.5f else typography.brightness,
            onValueChange = { onChange(typography.copy(brightness = it.coerceIn(0.05f, 1f))) },
            valueRange = 0.05f..1f,
        )
        if (!systemBrightness) {
            TextButton(onClick = { onChange(typography.copy(brightness = -1f)) }) {
                Text("Use system brightness")
            }
        }

        Label(if (typography.warmth <= 0f) "Night warmth: off" else "Night warmth: ${(typography.warmth * 100).toInt()}%")
        Slider(
            value = typography.warmth,
            onValueChange = { onChange(typography.copy(warmth = it)) },
            valueRange = 0f..1f,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Warm the page in the evening", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = typography.autoWarmth,
                onCheckedChange = { onChange(typography.copy(autoWarmth = it)) },
            )
        }
        if (typography.autoWarmth) {
            Text(
                "Fades in from ${ComfortLight.DEFAULT_START_HOUR}:00, full by " +
                    "${ComfortLight.DEFAULT_FULL_HOUR}:00, off again at " +
                    "0${ComfortLight.DEFAULT_END_HOUR}:00. The slider above is how warm it gets.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Volume keys turn pages", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = typography.volumeKeysTurnPages,
                onCheckedChange = { onChange(typography.copy(volumeKeysTurnPages = it)) },
            )
        }
        if (typography.volumeKeysTurnPages) {
            Text(
                "Volume up goes back, volume down goes forward - the way the text moves. " +
                    "They are volume keys again everywhere else in the app.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = { onChange(ReaderTypography()) }) {
            Text("Reset to defaults")
        }

        PrimaryButton(text = "Done", onClick = onDismiss)
    }
}

/** One row of theme chips, each drawn in the colours it would actually give the page. */
@Composable
private fun ThemeRow(
    palettes: List<ReaderPalette>,
    typography: ReaderTypography,
    onChange: (ReaderTypography) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        palettes.forEach { palette ->
            val colors = colorsFor(palette)
            FilterChip(
                selected = typography.palette == palette,
                onClick = { onChange(typography.copy(palette = palette)) },
                label = { Text(palette.label) },
                // Showing each theme in its own colours means the choice is made by looking rather
                // than by reading a list of names and trying them one at a time.
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = colors.background,
                    labelColor = colors.text,
                    selectedContainerColor = colors.background,
                    selectedLabelColor = colors.text,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = typography.palette == palette,
                    borderColor = colors.text.copy(alpha = 0.25f),
                    selectedBorderColor = colors.accent,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 2.dp,
                ),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/**
 * A slider for something that changes where the lines fall, committed once per gesture.
 *
 * Every one of these re-lays-out the whole book. Slider.onValueChange fires on every pixel of a
 * drag, so committing there restarted the pagination of a few hundred pages dozens of times a
 * second, cancelling each attempt before it could finish - and the page breaks never changed. From
 * a reader's side, the slider simply did nothing, while brightness and warmth (which touch no
 * layout) worked fine.
 *
 * The handle still moves with the finger, because the position is held here; only the commit
 * waits for the finger to lift.
 */
@Composable
private fun LayoutSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember(value) { mutableStateOf(value) }
    val shown = if (dragging) local else value
    Label(label(shown))
    Slider(
        value = shown,
        onValueChange = { dragging = true; local = it },
        onValueChangeFinished = { dragging = false; onCommit(local) },
        valueRange = range,
    )
}

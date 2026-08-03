package com.flashcardreader.app.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.theme.colorsFor
import kotlinx.coroutines.delay

/** What the add/edit-flashcard dialog is currently prefilled with, or null if closed. */
private data class FlashcardPrefill(val term: String, val definition: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val typography = state.typography
    val colors = colorsFor(typography.palette)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val context = LocalContext.current

    var showSettings by remember { mutableStateOf(false) }
    var flashcardPrefill by remember { mutableStateOf<FlashcardPrefill?>(null) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.source?.title ?: "", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Text("<") } },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Reading settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { flashcardPrefill = FlashcardPrefill(clipboardText(context), "") },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add flashcard")
            }
        },
    ) { padding ->
        if (state.loading || state.fullText.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).background(colors.background))
            return@Scaffold
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.background),
        ) {
            val widthPx = with(density) { maxWidth.toPx() }.toInt()
            val heightPx = with(density) { maxHeight.toPx() }.toInt()
            val style = TextStyle(
                fontFamily = typography.font.family,
                fontSize = typography.fontSize,
                lineHeight = typography.lineHeight,
                color = colors.text,
            )

            // Keyed on the font/size/line-height/viewport - deliberately NOT on
            // typography.palette, since a theme/color change never affects line
            // breaks and shouldn't trigger a (potentially slow, for a big book)
            // re-pagination. The debounce delay matters: a settings Slider fires
            // onValueChange on every pixel of a drag, and loadPages() runs on
            // viewModelScope (independent of this LaunchedEffect), so without it,
            // dragging a slider on a big book would fire off dozens of overlapping,
            // uncancelled re-pagination passes instead of just the final value.
            LaunchedEffect(state.fullText, typography.font, typography.fontSizeSp, typography.lineHeightMultiplier, widthPx, heightPx) {
                if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
                delay(300)
                viewModel.loadPages(
                    measure = { chunk, constraints -> measurer.measure(chunk, style, constraints = constraints) },
                    widthPx = widthPx,
                    heightPx = heightPx,
                    typography = typography,
                )
            }

            val page = state.pages.getOrNull(state.currentPageIndex)
            val pageText = if (page != null) state.fullText.substring(page.startChar, page.endChar) else ""

            // A word can wrap to any position on the page (including right against the
            // left/right edges), so paging by screen-position "zones" fought with tapping
            // words. Instead we separate gestures by *type*, on two independent detectors:
            //   - horizontal swipe  -> turn the page (right = back, left = forward)
            //   - long-press a word -> highlight it and open its add/edit flashcard
            //   - plain tap         -> clear any lingering highlight
            // The word highlights the instant the finger lands (pressing) and stays
            // highlighted after the dialog closes (selected) until the next tap, so the
            // gesture always has visible feedback rather than a silent wait.
            var pressingRange by remember(pageText) { mutableStateOf<IntRange?>(null) }
            var selectedRange by remember(pageText) { mutableStateOf<IntRange?>(null) }

            val annotated = remember(pageText, pressingRange, selectedRange, colors.accent) {
                buildAnnotatedString {
                    append(pageText)
                    selectedRange?.let { r ->
                        val end = (r.last + 1).coerceAtMost(pageText.length)
                        if (r.first in 0 until end) addStyle(SpanStyle(background = colors.accent.copy(alpha = 0.32f)), r.first, end)
                    }
                    pressingRange?.let { r ->
                        val end = (r.last + 1).coerceAtMost(pageText.length)
                        if (r.first in 0 until end) addStyle(SpanStyle(background = colors.accent.copy(alpha = 0.18f)), r.first, end)
                    }
                }
            }

            Text(
                text = annotated,
                style = style,
                onTextLayout = { textLayout = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    // Page-turn: a real horizontal swipe, not a screen region.
                    .pointerInput(state.currentPageIndex, state.pages.size) {
                        var totalDx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDx = 0f },
                            onDragEnd = {
                                val threshold = size.width * 0.12f
                                if (totalDx <= -threshold) viewModel.goToNextPage()
                                else if (totalDx >= threshold) viewModel.goToPreviousPage()
                            },
                            onDragCancel = { totalDx = 0f },
                        ) { change, dragAmount ->
                            totalDx += dragAmount
                            change.consume()
                        }
                    }
                    // Word selection: long-press highlights + opens the flashcard; a plain
                    // tap clears the highlight. onPress gives touch-down feedback immediately.
                    .pointerInput(state.currentPageIndex, pageText) {
                        detectTapGestures(
                            onPress = { offset ->
                                val layout = textLayout
                                pressingRange = layout?.let { wordRangeAt(pageText, it.getOffsetForPosition(offset)) }
                                tryAwaitRelease()
                                pressingRange = null
                            },
                            onTap = { selectedRange = null },
                            onLongPress = { offset ->
                                val layout = textLayout
                                val range = layout?.let { wordRangeAt(pageText, it.getOffsetForPosition(offset)) }
                                if (range != null) {
                                    selectedRange = range
                                    val word = pageText.substring(range.first, (range.last + 1).coerceAtMost(pageText.length))
                                    val existing = state.terms.find { it.normalizedText == word.lowercase() }
                                    flashcardPrefill = FlashcardPrefill(word, existing?.definition.orEmpty())
                                }
                            },
                        )
                    },
            )

            if (state.pagesLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        state.pendingFlashcards.firstOrNull()?.let { match ->
            FlashcardDialog(term = match.term, contextSentence = match.contextSentence, onAnswered = viewModel::answerFlashcard)
        }

        if (showSettings) {
            ReaderSettingsSheet(
                typography = typography,
                onChange = viewModel::updateTypography,
                onDismiss = { showSettings = false },
            )
        }

        flashcardPrefill?.let { prefill ->
            AddFlashcardDialog(
                prefilledText = prefill.term,
                prefilledDefinition = prefill.definition,
                onDismiss = { flashcardPrefill = null },
                onSave = { term, definition ->
                    viewModel.createFlashcard(term, definition)
                    flashcardPrefill = null
                },
            )
        }
    }
}

/** The char range (first..last inclusive) of the word at [index], or null if it hit whitespace/punctuation. */
private fun wordRangeAt(text: String, index: Int): IntRange? {
    if (text.isEmpty()) return null
    val i = index.coerceIn(0, text.length - 1)
    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\''
    if (!isWordChar(text[i])) return null
    var start = i
    while (start > 0 && isWordChar(text[start - 1])) start--
    var end = i
    while (end < text.length - 1 && isWordChar(text[end + 1])) end++
    return start..end
}

private fun clipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip: ClipData? = clipboard?.primaryClip
    return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
}

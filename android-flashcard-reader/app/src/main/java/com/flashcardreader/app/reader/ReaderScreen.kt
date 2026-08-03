package com.flashcardreader.app.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardreader.app.theme.colorsFor

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
    var showAddFlashcard by remember { mutableStateOf(false) }

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
            FloatingActionButton(onClick = { showAddFlashcard = true }) {
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

            LaunchedEffect(state.fullText, typography, widthPx, heightPx) {
                if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
                val pages = ReaderPaginator.paginate(
                    text = state.fullText,
                    measure = { chunk, constraints ->
                        measurer.measure(chunk, style, constraints = constraints)
                    },
                    maxWidthPx = widthPx,
                    maxHeightPx = heightPx,
                )
                viewModel.onPagesComputed(pages)
            }

            val page = state.pages.getOrNull(state.currentPageIndex)
            val pageText = if (page != null) state.fullText.substring(page.startChar, page.endChar) else ""

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(state.currentPageIndex) {
                        detectTapGestures { offset ->
                            if (offset.x < size.width / 3f) {
                                viewModel.goToPreviousPage()
                            } else if (offset.x > size.width * 2f / 3f) {
                                viewModel.goToNextPage()
                            }
                        }
                    },
            ) {
                SelectionContainer {
                    Text(text = pageText, style = style, modifier = Modifier.padding(24.dp))
                }
            }
        }

        state.pendingFlashcards.firstOrNull()?.let { match ->
            FlashcardDialog(term = match.term, onAnswered = viewModel::answerFlashcard)
        }

        if (showSettings) {
            ReaderSettingsSheet(
                typography = typography,
                onChange = viewModel::updateTypography,
                onDismiss = { showSettings = false },
            )
        }

        if (showAddFlashcard) {
            val clipboardText = remember { clipboardText(context) }
            AddFlashcardDialog(
                prefilledText = clipboardText,
                onDismiss = { showAddFlashcard = false },
                onSave = { term, definition ->
                    viewModel.createFlashcard(term, definition)
                    showAddFlashcard = false
                },
            )
        }
    }
}

private fun clipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip: ClipData? = clipboard?.primaryClip
    return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
}

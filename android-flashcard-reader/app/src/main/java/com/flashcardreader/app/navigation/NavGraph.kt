package com.flashcardreader.app.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.flashcardreader.app.FlashcardReaderApp
import com.flashcardreader.app.ai.AiSettingsScreen
import com.flashcardreader.app.focus.BlockedAppsScreen
import com.flashcardreader.app.focus.FocusGateScreen
import com.flashcardreader.app.focus.FocusGateViewModel
import com.flashcardreader.app.gutenberg.GutenbergScreen
import com.flashcardreader.app.gutenberg.GutenbergViewModel
import com.flashcardreader.app.library.LibraryScreen
import com.flashcardreader.app.library.LibraryViewModel
import com.flashcardreader.app.reader.ReaderScreen
import com.flashcardreader.app.reader.ReaderViewModel
import com.flashcardreader.app.reader.ReviewScreen
import com.flashcardreader.app.reader.ReviewViewModel
import com.flashcardreader.app.stats.StatsScreen
import com.flashcardreader.app.stats.StatsViewModel
import com.flashcardreader.app.words.WordsScreen
import com.flashcardreader.app.words.WordsViewModel

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_REVIEW = "review"
private const val ROUTE_WORDS = "words"
private const val ROUTE_STATS = "stats"
private const val ROUTE_AI = "ai-settings"
private const val ROUTE_GUTENBERG = "gutenberg"
private const val ROUTE_READER = "reader/{sourceId}"
private const val ROUTE_FOCUS = "focus-gate"
private const val ROUTE_FOCUS_APPS = "focus-gate/apps"

@Composable
fun AppNavGraph(app: FlashcardReaderApp) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_LIBRARY) {
        composable(ROUTE_LIBRARY) {
            val viewModel: LibraryViewModel = viewModel(
                factory = viewModelFactory { initializer { LibraryViewModel(app.libraryRepository) } },
            )
            LibraryScreen(
                viewModel = viewModel,
                onOpenSource = { id -> navController.navigate("reader/$id") },
                onOpenReview = { navController.navigate(ROUTE_REVIEW) },
                onOpenWords = { navController.navigate(ROUTE_WORDS) },
                onOpenStats = { navController.navigate(ROUTE_STATS) },
                onOpenGutenberg = { navController.navigate(ROUTE_GUTENBERG) },
            )
        }
        composable(ROUTE_GUTENBERG) {
            val viewModel: GutenbergViewModel = viewModel(
                factory = viewModelFactory { initializer { GutenbergViewModel(app.libraryRepository) } },
            )
            GutenbergScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(ROUTE_REVIEW) {
            val viewModel: ReviewViewModel = viewModel(
                factory = viewModelFactory { initializer { ReviewViewModel(app.termRepository, app.libraryRepository, app.focusPrefs, app.creditBank) } },
            )
            ReviewScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(ROUTE_WORDS) {
            val viewModel: WordsViewModel = viewModel(
                factory = viewModelFactory { initializer { WordsViewModel(app.termRepository) } },
            )
            WordsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(ROUTE_STATS) {
            val viewModel: StatsViewModel = viewModel(
                factory = viewModelFactory { initializer { StatsViewModel(app.termRepository, app.calibration) } },
            )
            StatsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenAiSettings = { navController.navigate(ROUTE_AI) },
                onOpenFocusGate = { navController.navigate(ROUTE_FOCUS) },
            )
        }
        composable(ROUTE_AI) {
            AiSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_FOCUS) {
            val viewModel: FocusGateViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { FocusGateViewModel(app, app.focusPrefs, app.creditBank) }
                },
            )
            FocusGateScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenApps = { navController.navigate(ROUTE_FOCUS_APPS) },
            )
        }
        composable(ROUTE_FOCUS_APPS) {
            val viewModel: FocusGateViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { FocusGateViewModel(app, app.focusPrefs, app.creditBank) }
                },
            )
            BlockedAppsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(
            ROUTE_READER,
            arguments = listOf(navArgument("sourceId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val sourceId = backStackEntry.arguments?.getLong("sourceId") ?: return@composable
            val viewModel: ReaderViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ReaderViewModel(
                            sourceId,
                            app.libraryRepository,
                            app.termRepository,
                            app.readerPrefs,
                            app.focusPrefs,
                            app.creditBank,
                        )
                    }
                },
            )
            ReaderScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}

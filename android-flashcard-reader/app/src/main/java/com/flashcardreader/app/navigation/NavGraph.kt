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
import com.flashcardreader.app.library.LibraryScreen
import com.flashcardreader.app.library.LibraryViewModel
import com.flashcardreader.app.reader.ReaderScreen
import com.flashcardreader.app.reader.ReaderViewModel
import com.flashcardreader.app.reader.ReviewScreen
import com.flashcardreader.app.reader.ReviewViewModel

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_REVIEW = "review"
private const val ROUTE_READER = "reader/{sourceId}"

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
            )
        }
        composable(ROUTE_REVIEW) {
            val viewModel: ReviewViewModel = viewModel(
                factory = viewModelFactory { initializer { ReviewViewModel(app.termRepository, app.libraryRepository) } },
            )
            ReviewScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(
            ROUTE_READER,
            arguments = listOf(navArgument("sourceId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val sourceId = backStackEntry.arguments?.getLong("sourceId") ?: return@composable
            val viewModel: ReaderViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ReaderViewModel(sourceId, app.libraryRepository, app.termRepository, app.readerPrefs)
                    }
                },
            )
            ReaderScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}

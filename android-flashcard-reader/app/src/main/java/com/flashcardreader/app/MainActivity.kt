package com.flashcardreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.flashcardreader.app.navigation.AppNavGraph
import com.flashcardreader.app.theme.FlashcardReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system (or an aggressive OEM) can kill the Focus Gate watcher at any time. Opening
        // the app is a good moment to quietly put it back, rather than leaving it silently dead.
        val app = application as FlashcardReaderApp
        if (app.focusPrefs.enabled &&
            com.flashcardreader.app.focus.UsageAccess.fullyGranted(this) &&
            !com.flashcardreader.app.focus.FocusGateService.isRunning
        ) {
            com.flashcardreader.app.focus.FocusGateService.start(this)
        }
        enableEdgeToEdge()
        setContent {
            FlashcardReaderTheme {
                AppNavGraph(app = application as FlashcardReaderApp)
            }
        }
    }
}

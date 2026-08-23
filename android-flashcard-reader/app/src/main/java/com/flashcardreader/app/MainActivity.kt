package com.flashcardreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.flashcardreader.app.diagnostics.CrashReportScreen
import com.flashcardreader.app.navigation.AppNavGraph
import com.flashcardreader.app.theme.FlashcardReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system (or an aggressive OEM) can kill the Focus Gate watcher at any time. Opening
        // the app is a good moment to quietly put it back, rather than leaving it silently dead.
        val app = application as FlashcardReaderApp
        // Read before anything else runs: if the last launch died, this is the only thing that
        // will get drawn, so it must not depend on the parts that might be broken.
        val lastCrash = app.crashLog.read()
        if (app.focusPrefs.enabled &&
            com.flashcardreader.app.focus.UsageAccess.fullyGranted(this) &&
            !com.flashcardreader.app.focus.FocusGateService.isRunning
        ) {
            com.flashcardreader.app.focus.FocusGateService.start(this)
        }
        enableEdgeToEdge()
        setContent {
            FlashcardReaderTheme {
                var showCrash by remember { mutableStateOf(lastCrash != null) }
                val report = lastCrash
                if (showCrash && report != null) {
                    CrashReportScreen(
                        report = report,
                        onDismiss = {
                            // Cleared only on dismissal, so closing the app before reading it
                            // doesn't lose the report.
                            app.crashLog.clear()
                            showCrash = false
                        },
                    )
                } else {
                    AppNavGraph(app = app)
                }
            }
        }
    }
}

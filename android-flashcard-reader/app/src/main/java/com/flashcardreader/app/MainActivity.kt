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

    /**
     * What to do with a volume key, set by the reader while it is on screen.
     *
     * Handled here rather than in Compose because volume keys are dispatched to the activity before
     * any view sees them - a focusable composable would only receive them by accident of focus,
     * which is exactly the kind of thing that works on one phone and not the next. Returning true
     * swallows the key, so the volume does not change while a page turns.
     */
    var onVolumeKey: ((up: Boolean) -> Boolean)? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handler = onVolumeKey
        if (handler != null && event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (handler(true)) return true
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (handler(false)) return true
            }
        }
        // The matching key-up must be swallowed too, or the system rings the volume panel anyway.
        if (handler != null && event.action == android.view.KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

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

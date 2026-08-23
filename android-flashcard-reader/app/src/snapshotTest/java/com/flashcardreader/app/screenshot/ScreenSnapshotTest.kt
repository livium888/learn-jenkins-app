package com.flashcardreader.app.screenshot

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flashcardreader.app.ai.ReadingCheck
import com.flashcardreader.app.reader.ReadingCheckPreviewBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Renders screens to PNG on the JVM, with no device and no emulator.
 *
 * This exists because of a concrete limitation rather than as a nicety: the Android SDK is
 * unreachable from where this app is written, so the UI has only ever been checked by reading the
 * source and by someone on a phone reporting back. These snapshots are the first time the layout
 * can actually be looked at before it ships - long option text wrapping, a quoted evidence block
 * running off the card, dark mode contrast.
 *
 * They *record* rather than verify. Golden images cannot be generated here, so there is nothing
 * honest to compare against yet; CI renders them and uploads the PNGs. Once a set is committed
 * from a run, this can be switched to verify and start failing on unintended visual change.
 */
class ScreenSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Before
    fun onlyUnderPaparazzi() {
        // Skipped during the ordinary unit-test run, so the APK build can never be held up by a
        // rendering problem in a screenshot.
        assumeTrue(
            System.getProperty("paparazzi.test.record") != null ||
                System.getProperty("paparazzi.test.verify") != null,
        )
    }

    private val shortCheck = ReadingCheck(
        question = "Why did the lamplighter linger at the corner of Mill Street?",
        correctAnswer = "The lamps there guttered in an east wind",
        distractors = listOf(
            "He was waiting for the boys to catch up",
            "The pole was too short for those lamps",
            "He had been told to by the parish",
        ),
        evidence = "He had walked the same route for thirty years, and knew which lamps guttered in a wind from the east.",
    )

    /** Long everything: the case most likely to overflow, so it is the one worth looking at. */
    private val longCheck = ReadingCheck(
        question = "Considering how the narrator describes the routine and what the boys fail to " +
            "understand about it, what does the passage suggest about the value of long familiarity?",
        correctAnswer = "That it produces knowledge which looks like slowness from the outside",
        distractors = listOf(
            "That routine work inevitably dulls the attention of the person performing it",
            "That the boys would have understood had the lamplighter chosen to explain himself",
            "That thirty years is long enough for anyone to master any similarly practical trade",
        ),
        evidence = "The boys who followed him for the first few corners never understood why he was " +
            "slower at the corner of Mill Street, and he had long since stopped explaining it to them.",
    )

    @Test fun readingCheckUnanswered() = snap(shortCheck, chosen = null)

    @Test fun readingCheckCorrect() = snap(shortCheck, chosen = shortCheck.correctAnswer)

    /** The wrong-answer state also shows the quoted evidence, which is the tallest layout. */
    @Test fun readingCheckWrong() = snap(shortCheck, chosen = shortCheck.distractors.first())

    @Test fun readingCheckLongText() = snap(longCheck, chosen = longCheck.distractors.first())

    private fun snap(check: ReadingCheck, chosen: String?) {
        paparazzi.snapshot {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(16.dp)) {
                        ReadingCheckPreviewBody(check = check, chosen = chosen)
                    }
                }
            }
        }
    }
}

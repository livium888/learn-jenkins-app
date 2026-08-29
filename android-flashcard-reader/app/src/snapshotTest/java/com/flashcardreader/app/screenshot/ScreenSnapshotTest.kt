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
import com.flashcardreader.app.reader.ReaderPagePreviewBody
import com.flashcardreader.app.reader.ReadingCheckPreviewBody
import com.flashcardreader.app.theme.ReaderPalette
import com.flashcardreader.app.theme.ReaderTypography
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

    /**
     * A page of real prose, at the settings most likely to look wrong.
     *
     * Justified text is the case worth seeing: without hyphenation it develops rivers of white
     * space, and no amount of reading the source tells you whether it did. Long words are included
     * on purpose, because they are what a justified line has to stretch around.
     */
    private val pageText = """
        The commissioners arrived in the spring and stayed for eleven weeks, and in all that time
        they wrote nothing down that anyone afterwards could find. It was said in the valley that
        they had been sent to look at the mill, and it was said in the town that they had been sent
        to look at the commissioners; both accounts were incomprehensible to the families whose
        wages were set, week by week, by the price of bread rather than by anything a commission
        might recommend. Extraordinarily, the report when it finally came concluded that the valley
        had been impoverished before the mill was built, which was true, and that it would have been
        impoverished without it, which nobody had asked.
    """.trimIndent().replace("\n", " ")

    private fun page(typography: ReaderTypography) {
        paparazzi.snapshot { ReaderPagePreviewBody(pageText, typography) }
    }

    @Test fun pageJustified() = page(ReaderTypography(justify = true))

    @Test fun pageRagged() = page(ReaderTypography(justify = false))

    /** The smallest text at the widest measure - the hardest case for justification. */
    @Test fun pageSmallTextWideMeasure() =
        page(ReaderTypography(fontSizeSp = 12f, marginScale = 0.5f, justify = true))

    /** The largest text at the narrowest measure - few words per line, so breaks show worst. */
    @Test fun pageLargeTextNarrowMeasure() =
        page(ReaderTypography(fontSizeSp = 28f, marginScale = 2.5f, justify = true))

    @Test fun pageSepia() = page(ReaderTypography(palette = ReaderPalette.SEPIA))

    @Test fun pageCandle() = page(ReaderTypography(palette = ReaderPalette.CANDLE))

    @Test fun pageCobalt() = page(ReaderTypography(palette = ReaderPalette.COBALT))

    @Test fun pageBlack() = page(ReaderTypography(palette = ReaderPalette.BLACK))

    /** Wide tracking and loose leading together, the dyslexia-friendly preset's shape. */
    @Test fun pageDyslexiaPreset() = page(
        ReaderTypography(fontSizeSp = 20f, lineHeightMultiplier = 1.9f, letterSpacingEm = 0.08f, justify = false),
    )

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

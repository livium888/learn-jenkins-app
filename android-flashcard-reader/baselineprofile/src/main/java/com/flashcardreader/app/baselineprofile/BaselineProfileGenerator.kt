package com.flashcardreader.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile by driving the app on a device/emulator and recording which
 * methods ART should AOT-compile at install time. Kept deliberately simple: launching to the
 * first screen already exercises app startup, Compose setup, and the initial library render -
 * the hot paths that most affect perceived speed. Run via `:app:generateBaselineProfile`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.flashcardreader.app") {
        pressHome()
        startActivityAndWait()
        // Let the first screen settle so its composition/layout paths are captured.
        device.waitForIdle()
    }
}

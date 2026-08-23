package com.flashcardreader.app.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash, so it can be read off the phone instead of guessed at.
 *
 * This app is written somewhere that cannot build or run it - CI is the only compiler and a real
 * phone is the only place it has ever executed. When it crashed on launch, the only evidence
 * available was the sentence "it crashes", and the cause had to be reasoned out from the diff.
 * That is a bad way to fix a crash and a worse way to confirm it is fixed.
 *
 * So the trace is written to a file before the process dies, and shown on the next launch with a
 * button that copies it. Deliberately a plain file in the app's own storage: no crash-reporting
 * service, no network, nothing leaves the phone unless it is pasted somewhere on purpose.
 */
class CrashLog(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** The last crash report, or null when the previous run ended normally. */
    fun read(): String? = runCatching {
        if (file.exists()) file.readText().ifBlank { null } else null
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * Installs the handler. Chains to whatever was there before, so the system still does its
     * usual job of ending the process - this only takes a copy on the way past.
     */
    fun install(versionName: String, versionCode: Int) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Wrapped because a failure while recording a crash must never replace the real crash
            // with a confusing one from inside the reporter.
            runCatching { file.writeText(report(thread, error, versionName, versionCode)) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun report(thread: Thread, error: Throwable, versionName: String, versionCode: Int): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Flashcard Reader $versionName ($versionCode)")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("thread: ${thread.name}")
            appendLine()
            append(stack)
        }
    }

    private companion object {
        const val FILE_NAME = "last-crash.txt"
    }
}

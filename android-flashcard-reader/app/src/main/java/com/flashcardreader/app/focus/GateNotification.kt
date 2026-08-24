package com.flashcardreader.app.focus

/**
 * What the Focus Gate notification says, as a pure function of the balance and what is in front.
 *
 * Deliberately coarse, and deliberately not a live counter. A ticking mm:ss readout was the obvious
 * design and is the wrong one twice over: a number rising while you read invites reading at the
 * counter instead of at the book, which is the exact behaviour the reading tracker exists to
 * refuse; and a smooth countdown while you spend manufactures the same urgency the gated apps
 * already sell, which tends to produce a last-minute binge rather than an early exit.
 *
 * Whole minutes change slowly enough that there is nothing to watch, and they match the gate
 * overlay, which has always rounded the same way.
 */
object GateNotification {

    const val TITLE = "Focus Gate is on"

    /**
     * [spendingIn] is the label of the gated app currently in front, or null when nothing is being
     * spent. Returns the notification's one line of body text.
     */
    fun text(balanceSeconds: Long, spendingIn: String?): String {
        if (balanceSeconds <= 0) return "Nothing banked - reading earns time."
        val minutes = balanceSeconds / 60
        return when {
            spendingIn != null && minutes < 1 -> "Under a minute left in $spendingIn."
            spendingIn != null -> "${plural(minutes)} left in $spendingIn."
            minutes < 1 -> "Under a minute banked."
            else -> "${plural(minutes)} banked."
        }
    }

    private fun plural(minutes: Long) = if (minutes == 1L) "1 minute" else "$minutes minutes"
}

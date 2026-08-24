package com.flashcardreader.app.theme

/**
 * Warms the page automatically as the evening goes on, the way an e-reader's comfort light does.
 *
 * The warm overlay has been available all along as a slider, which means it only ever got used by
 * someone who remembered to move it - and then remembered to move it back in the morning. A setting
 * you have to operate at the moment you least want to touch a screen is a setting nobody operates.
 *
 * Ramped rather than switched. A page that snaps from white to amber at 20:00 is startling, and the
 * eye adapts to a change it cannot see happening. The whole point is not to notice.
 *
 * Fixed hours rather than real sunset: sunset needs a location, a location needs a permission, and
 * a permission for this is a bad trade. The hours are adjustable instead.
 *
 * Android-free so the ramp can be tested at every hour of the day on the JVM.
 */
object ComfortLight {

    /** When warming begins, as an hour of the day. */
    const val DEFAULT_START_HOUR = 20

    /** When it reaches full strength. Before this it is part way up the ramp. */
    const val DEFAULT_FULL_HOUR = 22

    /** When it ends. Morning light needs no help. */
    const val DEFAULT_END_HOUR = 7

    /**
     * How warm the page should be right now, 0..1, where [maximum] is the reader's chosen strength.
     *
     * [minuteOfDay] is 0..1439. The window wraps past midnight, which is most of what this has to
     * get right: 23:30 and 01:00 are both night, and 08:00 is not.
     */
    fun warmthAt(
        minuteOfDay: Int,
        maximum: Float,
        startHour: Int = DEFAULT_START_HOUR,
        fullHour: Int = DEFAULT_FULL_HOUR,
        endHour: Int = DEFAULT_END_HOUR,
    ): Float {
        if (maximum <= 0f) return 0f
        val minute = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val start = startHour * 60
        val full = fullHour * 60
        val end = endHour * 60

        // Minutes since warming began, counting through midnight.
        val sinceStart = (minute - start + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val untilEnd = (end - start + MINUTES_PER_DAY) % MINUTES_PER_DAY
        if (sinceStart >= untilEnd) return 0f

        val rampLength = (full - start + MINUTES_PER_DAY) % MINUTES_PER_DAY
        if (rampLength <= 0) return maximum
        val fraction = (sinceStart.toFloat() / rampLength).coerceIn(0f, 1f)
        return maximum * fraction
    }

    private const val MINUTES_PER_DAY = 24 * 60
}

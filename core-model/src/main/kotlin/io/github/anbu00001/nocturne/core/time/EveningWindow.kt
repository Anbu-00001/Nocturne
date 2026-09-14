package io.github.anbu00001.nocturne.core.time

/**
 * A local clock window, in minutes after midnight, that may wrap past midnight.
 * Half-open: [startMinute] is inside, [endMinute] is not.
 */
data class EveningWindow(val startMinute: Int, val endMinute: Int) {

    init {
        require(startMinute in 0 until LocalClock.MINUTES_PER_DAY && endMinute in 0 until LocalClock.MINUTES_PER_DAY)
    }

    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute <= endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }

    fun contains(ts: Long, offsetMinutes: Int): Boolean = contains(LocalClock.minuteOfDay(ts, offsetMinutes))

    companion object {
        /** Brown et al., PLOS Biology 2022: the evening window starts at least 3 h before bedtime. */
        const val LEAD_MINUTES = 180

        /** Runs from 3 h before habitual sleep onset until habitual wake (spec §1.2 correction 2). */
        fun fromSleepOnset(onsetMinute: Int, wakeMinute: Int): EveningWindow =
            EveningWindow(Math.floorMod(onsetMinute - LEAD_MINUTES, LocalClock.MINUTES_PER_DAY), wakeMinute)

        /**
         * Stand-in until sleep inference (spec §6.3) has 7 nights of data: onset 00:00, wake 07:00.
         * Sessions tagged with it are re-tagged by recomputeAll() once a real estimate exists.
         */
        val PROVISIONAL: EveningWindow = fromSleepOnset(onsetMinute = 0, wakeMinute = 7 * 60)
    }
}

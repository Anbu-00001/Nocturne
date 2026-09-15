package io.github.anbu00001.nocturne.core.light

/**
 * Turns light-sensor events into one reading per fixed window (spec §4.2: a median lux value every 30 s).
 *
 * TYPE_LIGHT is an on-change sensor, so a steady room may send nothing for minutes: the value in force
 * when a window opens carries into it. The median is weighted by how long each value was in force, so a
 * burst of events inside one second cannot outvote the rest of the window. (The A18's stk33c01 reports
 * every 200 ms while auto-brightness listens, seen in `dumpsys sensorservice`, but Android only promises
 * changes.)
 */
class LuxWindow(
    private val windowMs: Long = 30_000,
    /** A window cut short by the screen going off is kept only if it lasted at least this long. */
    private val minPartialMs: Long = 5_000,
) {
    init {
        require(windowMs > 0 && minPartialMs in 0..windowMs)
    }

    /** [medianLux] is null when no value was in force at any point of the window. */
    data class Reading(val startTs: Long, val durationMs: Long, val medianLux: Double?, val events: Int)

    private var running = false
    private var windowStart = 0L
    private var accountedTo = 0L
    private var value: Double? = null
    private var events = 0
    private val spans = ArrayList<Pair<Double, Long>>()

    val isRunning: Boolean get() = running

    /** The screen came on and the sensor was registered at [ts]. */
    fun start(ts: Long) {
        running = true
        windowStart = ts
        accountedTo = ts
        value = null
        events = 0
        spans.clear()
    }

    /** A sensor event; returns the windows that closed before it. */
    fun onValue(ts: Long, lux: Double): List<Reading> {
        if (!running) return emptyList()
        val closed = advance(ts)
        value = lux
        events++
        return closed
    }

    /** No event, only time passing: returns the windows that closed. */
    fun tick(ts: Long): List<Reading> = if (running) advance(ts) else emptyList()

    /** The screen went off at [ts]: closes what is due and keeps the cut-short window if it is long enough. */
    fun stop(ts: Long): List<Reading> {
        if (!running) return emptyList()
        val closed = advance(ts).toMutableList()
        if (accountedTo - windowStart >= minPartialMs) closed += close(accountedTo)
        running = false
        value = null
        return closed
    }

    private fun advance(ts: Long): List<Reading> {
        // A wall clock stepped backwards counts as no time passing.
        val to = maxOf(ts, accountedTo)
        val closed = ArrayList<Reading>()
        while (to >= windowStart + windowMs) {
            val end = windowStart + windowMs
            account(end)
            closed += close(end)
        }
        account(to)
        return closed
    }

    private fun account(to: Long) {
        val v = value
        if (v != null && to > accountedTo) spans += v to (to - accountedTo)
        if (to > accountedTo) accountedTo = to
    }

    private fun close(end: Long): Reading {
        val reading = Reading(windowStart, end - windowStart, weightedMedian(spans), events)
        windowStart = end
        spans.clear()
        events = 0
        return reading
    }
}

/** The value in force for at least half of the time; null when nothing was in force. */
internal fun weightedMedian(spans: List<Pair<Double, Long>>): Double? {
    if (spans.isEmpty()) return null
    val sorted = spans.sortedBy { it.first }
    val half = sorted.sumOf { it.second } / 2.0
    var seen = 0L
    for ((value, ms) in sorted) {
        seen += ms
        if (seen >= half) return value
    }
    return sorted.last().first
}

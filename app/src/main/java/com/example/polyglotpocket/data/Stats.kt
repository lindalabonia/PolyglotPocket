package com.example.polyglotpocket.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Client-side aggregation of session rows for the progress/statistics screens.
 * SQLite stores started_at in UTC; days are bucketed in the device's local time
 * zone so "today" matches the user's day.
 */
object Stats {

    const val CALENDAR_WEEKS = 16

    data class Totals(val sessions: Int, val cards: Int, val durationMs: Long)

    data class Calendar16(
        val levels: IntArray,          // size CALENDAR_WEEKS*7, index = week*7 + weekday (Mon=0)
        val startMondayMillis: Long,   // local midnight of the first column's Monday
    )

    private val utcParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseInstant(startedAt: String): Date? =
        try { utcParser.parse(startedAt) } catch (e: Exception) { null }

    /** Local midnight (device time zone) for the given instant. */
    private fun localMidnight(instant: Date): Long {
        val c = Calendar.getInstance()
        c.time = instant
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun totals(sessions: List<SessionSummary>): Totals {
        var cards = 0
        var dur = 0L
        for (s in sessions) {
            cards += s.numCards
            dur += s.durationMs
        }
        return Totals(sessions.size, cards, dur)
    }

    /** Consecutive days with at least one session, ending today or yesterday
     *  (one day of grace so the streak doesn't drop before the day is over). */
    fun currentStreak(sessions: List<SessionSummary>): Int {
        val days = HashSet<Long>()
        for (s in sessions) parseInstant(s.startedAt)?.let { days.add(localMidnight(it)) }
        if (days.isEmpty()) return 0

        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (!days.contains(c.timeInMillis)) c.add(Calendar.DAY_OF_YEAR, -1) // grace for today

        var streak = 0
        while (days.contains(c.timeInMillis)) {
            streak++
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    /** A [weeks]-column grid of activity levels (0..4) by cards studied per day. */
    fun calendar(sessions: List<SessionSummary>, weeks: Int = CALENDAR_WEEKS): Calendar16 {
        val c = Calendar.getInstance()
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) c.add(Calendar.DAY_OF_YEAR, -1)
        c.add(Calendar.DAY_OF_YEAR, -7 * (weeks - 1))
        val startMonday = c.timeInMillis

        val cells = weeks * 7
        val cardsPerCell = IntArray(cells)
        for (s in sessions) {
            val inst = parseInstant(s.startedAt) ?: continue
            val day = localMidnight(inst)
            // Round to the nearest day to stay correct across DST transitions.
            val offset = Math.round((day - startMonday) / 86_400_000.0).toInt()
            if (offset in 0 until cells) cardsPerCell[offset] += s.numCards
        }

        val levels = IntArray(cells) { levelFor(cardsPerCell[it]) }
        return Calendar16(levels, startMonday)
    }

    /** Per-language figures for the Statistics screen. */
    data class LangStats(
        val avgAccuracy: Int,           // 0..100
        val accuracySeries: FloatArray, // last 20 sessions, oldest -> newest, each 0..100
        val cardsThisWeek: IntArray,    // size 7, Mon..Sun
        val correct: Int,
        val wrong: Int,
    )

    /** Languages the user has at least one session in, most-practiced first. */
    fun languagesWithSessions(sessions: List<SessionSummary>): List<String> =
        sessions.groupingBy { it.targetLang }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }

    fun forLanguage(sessions: List<SessionSummary>, lang: String): LangStats {
        // Backend returns sessions ordered by started_at ascending.
        val ls = sessions.filter { it.targetLang == lang }

        var correct = 0
        var wrong = 0
        for (s in ls) {
            correct += s.numCorrect
            wrong += s.numWrong
        }
        val avg = if (correct + wrong > 0) Math.round(correct * 100.0 / (correct + wrong)).toInt() else 0

        val last20 = ls.takeLast(20)
        val series = FloatArray(last20.size) { i ->
            val s = last20[i]
            val denom = s.numCorrect + s.numWrong
            if (denom > 0) s.numCorrect * 100f / denom else 0f
        }

        val week = IntArray(7)
        val mondayStart = startOfThisWeek()
        for (s in ls) {
            val inst = parseInstant(s.startedAt) ?: continue
            val offset = Math.round((localMidnight(inst) - mondayStart) / 86_400_000.0).toInt()
            if (offset in 0..6) week[offset] += s.numCards
        }

        return LangStats(avg, series, week, correct, wrong)
    }

    /** Local midnight of Monday of the current week. */
    private fun startOfThisWeek(): Long {
        val c = Calendar.getInstance()
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }

    private fun levelFor(cards: Int): Int = when {
        cards <= 0 -> 0
        cards <= 5 -> 1
        cards <= 15 -> 2
        cards <= 30 -> 3
        else -> 4
    }
}

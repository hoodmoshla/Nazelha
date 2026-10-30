package com.snapnet.util

import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpVersionTest {
    @Test
    fun versionsOlderThanNinetyDaysAreStale() {
        val now = utcMidnight(2026, 10, 31)

        assertTrue(YtDlpVersion.isOlderThanDays("2025.11.12", 90, now))
        assertTrue(YtDlpVersion.isOlderThanDays("2026.08.01", 90, now))
        assertTrue(YtDlpVersion.isOlderThanDays("2026.8.1", 90, now))
    }

    @Test
    fun versionExactlyNinetyDaysOldIsNotStaleButNewerOnesAreFresh() {
        val now = utcMidnight(2026, 10, 31)

        assertFalse(YtDlpVersion.isOlderThanDays("2026.08.02", 90, now))
        assertFalse(YtDlpVersion.isOlderThanDays("2026.08.19", 90, now))
    }

    @Test
    fun unknownMalformedAndFutureVersionsAreNotMarkedStale() {
        val now = utcMidnight(2026, 10, 31)

        assertFalse(YtDlpVersion.isOlderThanDays(null, 90, now))
        assertFalse(YtDlpVersion.isOlderThanDays("not-a-version", 90, now))
        assertFalse(YtDlpVersion.isOlderThanDays("2026.13.40", 90, now))
        assertFalse(YtDlpVersion.isOlderThanDays("2027.01.01", 90, now))
    }

    private fun utcMidnight(year: Int, month: Int, day: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
}

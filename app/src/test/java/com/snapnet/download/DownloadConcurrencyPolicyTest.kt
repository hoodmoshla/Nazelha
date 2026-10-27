package com.snapnet.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConcurrencyPolicyTest {
    @Test
    fun defaultIsThreeConcurrentDownloads() {
        assertEquals(3, DownloadConcurrencyPolicy.normalizedLimit(DownloadConcurrencyPolicy.DEFAULT_LIMIT))
    }

    @Test
    fun userLimitIsClampedToSupportedRange() {
        assertEquals(1, DownloadConcurrencyPolicy.normalizedLimit(Int.MIN_VALUE))
        assertEquals(1, DownloadConcurrencyPolicy.normalizedLimit(1))
        assertEquals(5, DownloadConcurrencyPolicy.normalizedLimit(5))
        assertEquals(5, DownloadConcurrencyPolicy.normalizedLimit(Int.MAX_VALUE))
    }

    @Test
    fun schedulerStartsWorkOnlyBelowConfiguredLimit() {
        assertTrue(DownloadConcurrencyPolicy.mayStartAnother(2, 3))
        assertFalse(DownloadConcurrencyPolicy.mayStartAnother(3, 3))
        assertFalse(DownloadConcurrencyPolicy.mayStartAnother(6, 5))
    }
}

package com.snapnet.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUtilRequestPolicyTest {
    @Test
    fun sharedFormatRequestUsesTheActualHyphenatedTaskKeyPrefix() {
        assertFalse(DownloadUtil.shouldApplyFormatSorter("FetchShared-12345"))
        assertTrue(DownloadUtil.shouldApplyFormatSorter("FetchFormat-12345"))
        assertTrue(DownloadUtil.shouldApplyFormatSorter(null))
    }
}

package com.snapnet.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStoragePolicyTest {
    @Test fun rejectsKnownDownloadWhenSpaceIsInsufficient() {
        assertFalse(DownloadStoragePolicy.canFit(99L, 100L))
        assertFalse(DownloadStoragePolicy.canFit(0L, 1L))
        assertTrue(DownloadStoragePolicy.canFit(100L, 100L))
    }

    @Test fun accountsForAlreadyDownloadedBytesWithoutOverflow() {
        assertTrue(DownloadStoragePolicy.canFit(10L, 100L, 90L))
        assertFalse(DownloadStoragePolicy.canFit(9L, 100L, 90L))
        assertTrue(DownloadStoragePolicy.canFit(1L, Long.MAX_VALUE, Long.MAX_VALUE - 1L))
        assertFalse(DownloadStoragePolicy.canFit(1L, Long.MAX_VALUE))
        assertTrue(DownloadStoragePolicy.canFit(0L, 100L, Long.MAX_VALUE))
    }

    @Test fun unknownSizesDeferToFilesystemButNegativePartialDoesNotReduceRequirement() {
        assertTrue(DownloadStoragePolicy.canFit(0L, null))
        assertTrue(DownloadStoragePolicy.canFit(-1L, 100L))
        assertFalse(DownloadStoragePolicy.canFit(99L, 100L, -100L))
    }
}

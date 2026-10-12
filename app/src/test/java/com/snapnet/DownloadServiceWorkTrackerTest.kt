package com.snapnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadServiceWorkTrackerTest {
    private val tracker = DownloadServiceWorkTracker()

    @Test
    fun `service stays needed until every independent work source is idle`() {
        tracker.setActive(DownloadWorkSource.TASK_QUEUE, true)
        tracker.setActive(DownloadWorkSource.LEGACY_DOWNLOADER, true)

        tracker.setActive(DownloadWorkSource.TASK_QUEUE, false)
        assertTrue(tracker.hasActiveWork())
        assertEquals(setOf(DownloadWorkSource.LEGACY_DOWNLOADER), tracker.activeSources())

        tracker.setActive(DownloadWorkSource.LEGACY_DOWNLOADER, false)
        assertFalse(tracker.hasActiveWork())
    }

    @Test
    fun `updating one source is idempotent and does not clear another source`() {
        tracker.setActive(DownloadWorkSource.QUICK_SHARE, true)
        tracker.setActive(DownloadWorkSource.QUICK_SHARE, true)
        tracker.setActive(DownloadWorkSource.TASK_QUEUE, true)
        tracker.setActive(DownloadWorkSource.LEGACY_COMMAND, true)
        tracker.setActive(DownloadWorkSource.QUICK_SHARE, false)
        tracker.setActive(DownloadWorkSource.QUICK_SHARE, false)

        assertTrue(tracker.hasActiveWork())
        assertEquals(
            setOf(DownloadWorkSource.TASK_QUEUE, DownloadWorkSource.LEGACY_COMMAND),
            tracker.activeSources(),
        )
    }
}

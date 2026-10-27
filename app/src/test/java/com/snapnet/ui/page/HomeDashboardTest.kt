package com.snapnet.ui.page

import com.snapnet.download.Task.DownloadState
import com.snapnet.download.Task.RestartableAction
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDashboardTest {
    @Test
    fun countsActiveCompletedAndStoppedTasksSeparately() {
        val states =
            listOf(
                DownloadState.Idle,
                DownloadState.ReadyWithInfo,
                DownloadState.FetchingInfo(taskId = "fetching"),
                DownloadState.Running(taskId = "running", progress = 0.5f),
                DownloadState.Completed(filePath = "/downloads/finished.mp4"),
                DownloadState.Canceled(action = RestartableAction.Download, paused = true),
                DownloadState.Canceled(action = RestartableAction.Download, paused = false),
                DownloadState.Error(action = RestartableAction.FetchInfo),
            )

        assertEquals(DashboardCounts(active = 4, completed = 1, stopped = 3), dashboardCounts(states))
    }

    @Test
    fun emptyQueueHasZeroCounts() {
        assertEquals(DashboardCounts(), dashboardCounts(emptyList()))
    }
}

package com.snapnet.ui.page.downloadv2

import com.snapnet.download.Task
import com.snapnet.download.Task.DownloadState
import com.snapnet.download.Task.RestartableAction
import com.snapnet.ui.page.downloadv2.configure.PreferencesMock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueFilterTest {
    private val task = Task(url = "https://example.com/video", preferences = PreferencesMock)
    private val viewState = Task.ViewState(title = "Example video")

    private fun entry(state: DownloadState) = task to Task.State(state, null, viewState)

    @Test
    fun activeFilterContainsOnlyQueuedOrRunningWork() {
        assertTrue(Filter.Active.predict(entry(DownloadState.Idle)))
        assertTrue(Filter.Active.predict(entry(DownloadState.FetchingInfo(taskId = "fetch"))))
        assertTrue(Filter.Active.predict(entry(DownloadState.ReadyWithInfo)))
        assertTrue(Filter.Active.predict(entry(DownloadState.Running(taskId = "run"))))
        assertFalse(Filter.Active.predict(entry(DownloadState.Completed(null))))
        assertFalse(Filter.Active.predict(entry(DownloadState.Canceled(RestartableAction.Download, paused = true))))
    }

    @Test
    fun completedAndStoppedFiltersAreDisjointAndErrorsRemainAccessible() {
        assertTrue(Filter.Completed.predict(entry(DownloadState.Completed("/file"))))
        assertFalse(Filter.Completed.predict(entry(DownloadState.Error(action = RestartableAction.Download))))
        assertTrue(Filter.Stopped.predict(entry(DownloadState.Canceled(RestartableAction.Download, paused = true))))
        assertTrue(Filter.Stopped.predict(entry(DownloadState.Canceled(RestartableAction.Download, paused = false))))
        assertTrue(Filter.Stopped.predict(entry(DownloadState.Error(action = RestartableAction.FetchInfo))))
        assertFalse(Filter.Stopped.predict(entry(DownloadState.Running(taskId = "run"))))
    }

    @Test
    fun allFilterRetainsEveryState() {
        assertTrue(Filter.All.predict(entry(DownloadState.Error(action = RestartableAction.Download))))
        assertTrue(Filter.All.predict(entry(DownloadState.Completed(null))))
    }
}

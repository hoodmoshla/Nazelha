package com.snapnet.download

import com.snapnet.database.objects.CommandTemplate
import com.snapnet.download.Task.DownloadState.Canceled
import com.snapnet.download.Task.DownloadState.Idle
import com.snapnet.download.Task.DownloadState.ReadyWithInfo
import com.snapnet.download.Task.DownloadState.Running
import com.snapnet.download.Task.RestartableAction.Download
import com.snapnet.download.http.DirectHttpFile
import com.snapnet.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRestorePolicyTest {
    private val preferences = DownloadUtil.DownloadPreferences.EMPTY

    @Test
    fun `active direct file resumes from saved metadata`() {
        val task = Task("https://example.com/file.bin", Task.TypeInfo.DirectHttpFile, preferences)
        val file = DirectHttpFile("https://example.com/file.bin", "file.bin", "application/octet-stream")
        val state = state(task, Running(taskId = task.id), directHttpFile = file)

        assertEquals(ReadyWithInfo, TaskRestorePolicy.restore(task, state).downloadState)
    }

    @Test
    fun `active direct file without metadata is resolved again before downloading`() {
        val task = Task("https://example.com/file.bin", Task.TypeInfo.DirectHttpFile, preferences)
        val state = state(task, Running(taskId = task.id), directHttpFile = null)

        assertEquals(Idle, TaskRestorePolicy.restore(task, state).downloadState)
    }

    @Test
    fun `explicit user pause and cancel remain stopped`() {
        val task = Task("https://example.com/file.bin", Task.TypeInfo.DirectHttpFile, preferences)
        val paused = state(task, Canceled(action = Download, progress = 0.4f, paused = true))
        val canceled = state(task, Canceled(action = Download, progress = 0.4f, paused = false))

        assertEquals(paused.downloadState, TaskRestorePolicy.restore(task, paused).downloadState)
        assertEquals(canceled.downloadState, TaskRestorePolicy.restore(task, canceled).downloadState)
    }

    @Test
    fun `custom command is not replayed automatically but remains explicitly restartable`() {
        val template = CommandTemplate(id = 1, name = "test command", template = "--simulate")
        val task =
            Task(
                "https://example.com/video",
                Task.TypeInfo.CustomCommand(template),
                preferences,
            )
        val running = state(task, Running(taskId = task.id, progress = 0.4f))

        val restored = TaskRestorePolicy.restore(task, running)

        assertEquals(Canceled(action = Download, progress = 0.4f), restored.downloadState)
        assertEquals(null, restored.videoInfo)
        assertTrue(task.type is Task.TypeInfo.CustomCommand)
    }

    private fun state(
        task: Task,
        downloadState: Task.DownloadState,
        directHttpFile: DirectHttpFile? = null,
    ) =
        Task.State(
            downloadState = downloadState,
            videoInfo = null,
            viewState = Task.ViewState(url = task.url, title = "example.com"),
            directHttpFile = directHttpFile,
        )
}

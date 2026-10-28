package com.snapnet.download

import com.snapnet.database.objects.CommandTemplate
import com.snapnet.download.Task.DownloadState.Canceled
import com.snapnet.download.Task.DownloadState.Error
import com.snapnet.download.Task.DownloadState.FetchingInfo
import com.snapnet.download.Task.DownloadState.Idle
import com.snapnet.download.Task.DownloadState.ReadyWithInfo
import com.snapnet.download.Task.DownloadState.Running
import com.snapnet.download.Task.RestartableAction.Download
import com.snapnet.download.Task.RestartableAction.FetchInfo
import com.snapnet.download.Task.TypeInfo
import com.snapnet.download.http.DirectHttpFile
import com.snapnet.download.http.HttpDownloadSecurityPolicy
import com.snapnet.util.DownloadUtil
import com.snapnet.util.Format
import com.snapnet.util.VideoInfo
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRestorePolicyTest {
    private val preferences = DownloadUtil.DownloadPreferences.EMPTY

    @Test
    fun `interrupted direct file is paused with progress and requires manual resume`() {
        val task = Task("https://example.com/file.bin", TypeInfo.DirectHttpFile, preferences)
        val file =
            DirectHttpFile("https://example.com/file.bin", "file.bin", "application/octet-stream")
        val state =
            state(
                task,
                Running(taskId = task.id, progress = 0.4f, progressText = "4 MB"),
                directHttpFile = file,
            )

        assertEquals(
            Canceled(action = Download, progress = 0.4f, paused = true, progressText = "4 MB"),
            TaskRestorePolicy.restore(task, state).downloadState,
        )
    }

    @Test
    fun `restored HTTP tasks stay paused and only direct files can retry with fresh consent`() {
        val directUrl = "http://example.com/file.bin"
        val directTask = Task(directUrl, TypeInfo.DirectHttpFile, preferences)
        val directFile = DirectHttpFile(directUrl, "file.bin", "application/octet-stream")
        val restoredDirect =
            TaskRestorePolicy.restore(
                directTask,
                state(directTask, Running(taskId = directTask.id), directHttpFile = directFile),
            )

        assertEquals(Canceled(action = Download, paused = true), restoredDirect.downloadState)
        assertFalse(HttpDownloadSecurityPolicy.mayStartTask(directUrl, true, false))
        assertTrue(HttpDownloadSecurityPolicy.mayStartTask(directUrl, true, true))

        val legacyUrl = "http://example.com/video"
        val legacyTask = Task(legacyUrl, TypeInfo.URL, preferences)
        val info = VideoInfo(id = "legacy", title = "Legacy video", webpageUrl = legacyUrl)
        val restoredLegacy =
            TaskRestorePolicy.restore(
                legacyTask,
                state(legacyTask, Running(taskId = legacyTask.id), videoInfo = info),
            )

        assertEquals(Canceled(action = Download, paused = true), restoredLegacy.downloadState)
        assertFalse(HttpDownloadSecurityPolicy.mayStartTask(legacyUrl, false, true))
        assertFalse(HttpDownloadSecurityPolicy.mayUseYtDlp(legacyUrl))
    }

    @Test
    fun `interrupted video with saved metadata waits for manual resume`() {
        val task = Task("https://example.com/video", TypeInfo.URL, preferences)
        val videoInfo = VideoInfo(id = "video", title = "Example video", webpageUrl = task.url)
        val state = state(task, Running(taskId = task.id, progress = 0.25f), videoInfo = videoInfo)
        val restored = TaskRestorePolicy.restore(task, state)

        assertEquals(
            Canceled(action = Download, progress = 0.25f, paused = true),
            restored.downloadState,
        )
        assertEquals(
            ReadyWithInfo,
            TaskRestorePolicy.restartTarget(task, restored, restored.downloadState as Canceled),
        )
    }

    @Test
    fun `interrupted direct file without saved metadata waits for manual link resolution`() {
        val task = Task("https://example.com/file.bin", TypeInfo.DirectHttpFile, preferences)
        val state = state(task, Running(taskId = task.id))
        val restored = TaskRestorePolicy.restore(task, state)

        assertEquals(Canceled(action = FetchInfo, paused = true), restored.downloadState)
        assertEquals(
            Idle,
            TaskRestorePolicy.restartTarget(task, restored, restored.downloadState as Canceled),
        )
    }

    @Test
    fun `idle and fetching tasks are stopped rather than automatically retried`() {
        val task = Task("https://example.com/video", TypeInfo.URL, preferences)

        assertEquals(
            Canceled(action = FetchInfo, paused = true),
            TaskRestorePolicy.restore(task, state(task, Idle)).downloadState,
        )
        assertEquals(
            Canceled(action = FetchInfo, paused = true),
            TaskRestorePolicy.restore(task, state(task, FetchingInfo(taskId = task.id)))
                .downloadState,
        )
    }

    @Test
    fun `ready direct file is stopped and can resume only after user action`() {
        val task = Task("https://example.com/file.bin", TypeInfo.DirectHttpFile, preferences)
        val file =
            DirectHttpFile("https://example.com/file.bin", "file.bin", "application/octet-stream")
        val restored =
            TaskRestorePolicy.restore(task, state(task, ReadyWithInfo, directHttpFile = file))

        assertEquals(Canceled(action = Download, paused = true), restored.downloadState)
        assertEquals(
            ReadyWithInfo,
            TaskRestorePolicy.restartTarget(task, restored, restored.downloadState as Canceled),
        )
    }

    @Test
    fun `custom commands stay stopped and resume through their execution path`() {
        val template = CommandTemplate(id = 1, name = "test command", template = "--simulate")
        val task = Task("https://example.com/video", TypeInfo.CustomCommand(template), preferences)
        val restored = TaskRestorePolicy.restore(task, state(task, Idle))

        assertEquals(Canceled(action = Download, paused = true), restored.downloadState)
        assertEquals(
            Idle,
            TaskRestorePolicy.restartTarget(task, restored, restored.downloadState as Canceled),
        )
    }

    @Test
    fun `legacy canceled download without metadata falls back to fetching info`() {
        val task = Task("https://example.com/video", TypeInfo.URL, preferences)
        val saved = state(task, Canceled(action = Download))

        assertEquals(
            Idle,
            TaskRestorePolicy.restartTarget(task, saved, saved.downloadState as Canceled),
        )
    }

    @Test
    fun `legacy download error without metadata falls back to fetching info`() {
        val task = Task("https://example.com/video", TypeInfo.URL, preferences)
        val saved = state(task, Error(action = Download))

        assertEquals(
            Idle,
            TaskRestorePolicy.restartTarget(task, saved, saved.downloadState as Error),
        )
    }

    @Test
    fun `playlist and audio only metadata remain stopped until manual resume`() {
        val playlistTask =
            Task("https://example.com/playlist", TypeInfo.Playlist(index = 0), preferences)
        val audioTask = Task("https://example.com/audio", TypeInfo.URL, preferences)
        val playlistInfo =
            VideoInfo(id = "entry", title = "Playlist entry", webpageUrl = playlistTask.url)
        val audioInfo =
            VideoInfo(
                id = "audio",
                title = "Audio only",
                webpageUrl = audioTask.url,
                formats =
                    listOf(
                        Format(formatId = "audio", ext = "opus", acodec = "opus", vcodec = "none")
                    ),
            )

        listOf(playlistTask to playlistInfo, audioTask to audioInfo).forEach { (task, info) ->
            val restored =
                TaskRestorePolicy.restore(task, state(task, ReadyWithInfo, videoInfo = info))
            assertEquals(Canceled(action = Download, paused = true), restored.downloadState)
            assertEquals(
                ReadyWithInfo,
                TaskRestorePolicy.restartTarget(task, restored, restored.downloadState as Canceled),
            )
        }
    }

    @Test
    fun `all scheduler eligible states restore to stopped tasks`() {
        val task = Task("https://example.com/video", TypeInfo.URL, preferences)
        val info = VideoInfo(id = "video", title = "Example video", webpageUrl = task.url)
        val states =
            listOf(
                state(task, Idle),
                state(task, FetchingInfo(taskId = task.id)),
                state(task, ReadyWithInfo, videoInfo = info),
                state(task, Running(taskId = task.id), videoInfo = info),
            )

        states.forEach { saved ->
            assertTrue(TaskRestorePolicy.restore(task, saved).downloadState is Canceled)
        }
    }

    @Test
    fun `late info and download callbacks no longer own stopped task state`() {
        val task = Task("https://example.com/video", TypeInfo.URL, preferences)
        val infoJob = Job()
        val downloadJob = Job()

        assertTrue(
            TaskRestorePolicy.isCurrentOperation(
                FetchingInfo(job = infoJob, taskId = task.id),
                infoJob,
            )
        )
        assertFalse(
            TaskRestorePolicy.isCurrentOperation(
                Canceled(action = FetchInfo, paused = true),
                infoJob,
            )
        )
        assertTrue(
            TaskRestorePolicy.isCurrentOperation(
                Running(job = downloadJob, taskId = task.id),
                downloadJob,
            )
        )
        assertFalse(
            TaskRestorePolicy.isCurrentOperation(
                Canceled(action = Download, paused = true),
                downloadJob,
            )
        )
    }

    @Test
    fun `explicit pause cancel and errors remain unchanged`() {
        val task = Task("https://example.com/file.bin", TypeInfo.DirectHttpFile, preferences)
        val paused = state(task, Canceled(action = Download, progress = 0.4f, paused = true))
        val canceled = state(task, Canceled(action = Download, progress = 0.4f, paused = false))
        val error = state(task, Error(action = FetchInfo))

        assertEquals(paused.downloadState, TaskRestorePolicy.restore(task, paused).downloadState)
        assertEquals(
            canceled.downloadState,
            TaskRestorePolicy.restore(task, canceled).downloadState,
        )
        assertEquals(error.downloadState, TaskRestorePolicy.restore(task, error).downloadState)
    }

    private fun state(
        task: Task,
        downloadState: Task.DownloadState,
        videoInfo: VideoInfo? = null,
        directHttpFile: DirectHttpFile? = null,
    ) =
        Task.State(
            downloadState = downloadState,
            videoInfo = videoInfo,
            viewState = Task.ViewState(url = task.url, title = "example.com"),
            directHttpFile = directHttpFile,
        )
}

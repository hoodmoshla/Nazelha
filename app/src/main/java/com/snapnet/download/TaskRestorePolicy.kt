package com.snapnet.download

import com.snapnet.download.Task.DownloadState
import com.snapnet.download.Task.DownloadState.Canceled
import com.snapnet.download.Task.DownloadState.Completed
import com.snapnet.download.Task.DownloadState.Error
import com.snapnet.download.Task.DownloadState.FetchingInfo
import com.snapnet.download.Task.DownloadState.Idle
import com.snapnet.download.Task.DownloadState.ReadyWithInfo
import com.snapnet.download.Task.DownloadState.Running
import com.snapnet.download.Task.RestartableAction.Download
import com.snapnet.download.Task.RestartableAction.FetchInfo
import com.snapnet.download.Task.TypeInfo
import kotlinx.coroutines.Job

/**
 * Restores persisted tasks as stopped, explicitly resumable work. The queue scheduler starts Idle
 * and ReadyWithInfo tasks automatically, so no state rehydrated here may use either active state.
 */
internal object TaskRestorePolicy {
    fun restore(task: Task, state: Task.State): Task.State {
        val previous = state.downloadState
        val running = previous as? Running
        val resumeAction = state.manualResumeAction(task)
        val restoredState =
            when (previous) {
                is Canceled,
                is Error,
                is Completed -> previous
                else ->
                    Canceled(
                        action = resumeAction,
                        progress =
                            running?.progress?.takeIf { resumeAction == Download && it >= 0f },
                        paused = true,
                        progressText =
                            running?.progressText?.takeIf { resumeAction == Download }.orEmpty(),
                    )
            }
        return state.copy(downloadState = restoredState)
    }

    /**
     * Converts a user-requested restart into the scheduler state appropriate for the task type and
     * the metadata currently present. Old backups can contain Download errors without their info;
     * resume must then fetch info again rather than throwing from download().
     */
    fun restartTarget(
        task: Task,
        taskState: Task.State,
        restartable: DownloadState.Restartable,
    ): DownloadState =
        when {
            restartable.action == FetchInfo -> Idle
            task.type is TypeInfo.CustomCommand -> Idle
            task.type == TypeInfo.DirectHttpFile && taskState.directHttpFile == null -> Idle
            task.type == TypeInfo.DirectHttpFile -> ReadyWithInfo
            taskState.videoInfo != null -> ReadyWithInfo
            else -> Idle
        }

    /** True only while [job] still owns the visible in-flight state for its task. */
    fun isCurrentOperation(state: DownloadState, job: Job): Boolean =
        when (state) {
            is FetchingInfo -> state.job === job
            is Running -> state.job === job
            else -> false
        }

    private fun Task.State.manualResumeAction(task: Task): Task.RestartableAction =
        when {
            task.type is TypeInfo.CustomCommand -> Download
            task.type == TypeInfo.DirectHttpFile && directHttpFile != null -> Download
            task.type == TypeInfo.DirectHttpFile -> FetchInfo
            videoInfo != null -> Download
            else -> FetchInfo
        }
}

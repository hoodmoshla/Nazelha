package com.snapnet.download

import com.snapnet.download.Task.DownloadState
import com.snapnet.download.Task.DownloadState.Canceled
import com.snapnet.download.Task.DownloadState.Idle
import com.snapnet.download.Task.DownloadState.ReadyWithInfo
import com.snapnet.download.Task.RestartableAction.Download

/** Restores in-flight downloads when the user next opens the app, without replaying explicit stops. */
internal object TaskRestorePolicy {
    fun restore(task: Task, state: Task.State): Task.State {
        val restoredState =
            when (val previous = state.downloadState) {
                is DownloadState.FetchingInfo -> Idle
                Idle -> Idle
                is DownloadState.Running ->
                    when {
                        task.type is Task.TypeInfo.CustomCommand ->
                            Canceled(action = Download, progress = previous.progress)
                        task.type == Task.TypeInfo.DirectHttpFile && state.directHttpFile == null -> Idle
                        task.type == Task.TypeInfo.DirectHttpFile || state.videoInfo != null -> ReadyWithInfo
                        else -> Idle
                    }
                ReadyWithInfo -> ReadyWithInfo
                else -> previous
            }
        return state.copy(downloadState = restoredState)
    }
}

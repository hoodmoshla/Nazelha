package com.snapnet.util

internal enum class DownloadNotificationAction {
    PAUSE,
    CANCEL,
    RESUME,
}

internal data class DownloadNotificationState(
    val ongoing: Boolean,
    val actions: List<DownloadNotificationAction>,
)

/** Pure notification-state rules shared by Android notification builders and JVM tests. */
internal object DownloadNotificationPolicy {
    fun active(hasTaskId: Boolean, canPause: Boolean = true) =
        DownloadNotificationState(
            ongoing = true,
            actions =
                if (!hasTaskId) emptyList()
                else
                    buildList {
                        if (canPause) add(DownloadNotificationAction.PAUSE)
                        add(DownloadNotificationAction.CANCEL)
                    },
        )

    fun paused() =
        DownloadNotificationState(
            ongoing = false,
            actions = listOf(DownloadNotificationAction.RESUME),
        )

    fun completed() = DownloadNotificationState(ongoing = false, actions = emptyList())
}

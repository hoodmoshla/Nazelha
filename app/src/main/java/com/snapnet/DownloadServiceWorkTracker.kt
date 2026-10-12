package com.snapnet

/** Work sources that keep the download foreground service alive. */
internal enum class DownloadWorkSource {
    LEGACY_DOWNLOADER,
    LEGACY_COMMAND,
    TASK_QUEUE,
    QUICK_SHARE,
}

/**
 * Tracks independent download work so one pipeline cannot stop the foreground service while another
 * pipeline still needs it.
 */
internal class DownloadServiceWorkTracker {
    private val activeSources = mutableSetOf<DownloadWorkSource>()

    @Synchronized
    fun setActive(source: DownloadWorkSource, active: Boolean) {
        if (active) activeSources.add(source) else activeSources.remove(source)
    }

    @Synchronized fun hasActiveWork(): Boolean = activeSources.isNotEmpty()

    @Synchronized fun activeSources(): Set<DownloadWorkSource> = activeSources.toSet()
}

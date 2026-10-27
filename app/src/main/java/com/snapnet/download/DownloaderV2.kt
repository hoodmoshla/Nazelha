package com.snapnet.download

import android.app.PendingIntent
import android.content.Context
import android.media.MediaScannerConnection
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.snapnet.App
import com.snapnet.DownloadWorkSource
import com.snapnet.R
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
import com.snapnet.download.http.DirectHttpDownloader
import com.snapnet.download.http.DirectHttpFile
import com.snapnet.download.http.DirectHttpLinkResolver
import com.snapnet.util.DownloadUtil
import com.snapnet.util.FileNaming
import com.snapnet.util.FileUtil
import com.snapnet.util.NotificationUtil
import com.snapnet.util.MAX_PARALLEL_DOWNLOADS
import com.snapnet.util.PreferenceUtil
import com.snapnet.util.PreferenceUtil.getInt
import com.snapnet.util.VideoInfo
import com.snapnet.util.resolvedFileSizeBytes
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PROGRESS_UPDATE_BYTES = 256L * 1024L

interface DownloaderV2 {
    fun getTaskStateMap(): SnapshotStateMap<Task, Task.State>

    fun cancel(task: Task): Boolean

    /**
     * Stops [task] without discarding its progress.
     *
     * yt-dlp writes to `.part` files and resumes them by default, so a paused task can continue from
     * roughly where it stopped when it is restarted.
     */
    fun pause(task: Task): Boolean

    fun cancel(taskId: String): Boolean {
        return getTaskStateMap().keys.find { it.id == taskId }?.let { cancel(it) } ?: false
    }

    fun restart(task: Task)

    /** Resumes the task with [taskId]; returns false when no such task is queued. */
    fun restart(taskId: String): Boolean =
        getTaskStateMap().keys.find { it.id == taskId }?.let {
            restart(it)
            true
        } ?: false

    /** Enqueue a [Task] with an empty [Task.State] */
    fun enqueue(task: Task)

    fun enqueue(task: Task, state: Task.State)

    fun enqueue(taskWithState: TaskFactory.TaskWithState) {
        val (task, state) = taskWithState
        enqueue(task, state)
    }

    fun remove(task: Task): Boolean
}

internal object FakeDownloaderV2 : DownloaderV2 {
    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return mutableStateMapOf()
    }

    override fun cancel(task: Task): Boolean {
        return false
    }

    override fun pause(task: Task): Boolean {
        return false
    }

    override fun restart(task: Task) {}

    override fun enqueue(task: Task) {}

    override fun enqueue(task: Task, state: Task.State) {}

    override fun remove(task: Task): Boolean {
        return true
    }
}

/**
 * TODO:
 *     - Notification
 *     - Custom commands
 *     - States for ViewModels
 */
@OptIn(FlowPreview::class)
class DownloaderV2Impl(private val appContext: Context) : DownloaderV2, KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directHttpResolver = DirectHttpLinkResolver()
    private val directHttpDownloader = DirectHttpDownloader()
    private val outputFileLock = Any()
    private val taskStateMap = mutableStateMapOf<Task, Task.State>()
    private val stoppingJobs = ConcurrentHashMap<String, StoppingTaskJob>()
    private val snapshotFlow = snapshotFlow { taskStateMap.toMap() }

    init {
        scope.launch(Dispatchers.Default) {
            snapshotFlow
                .onEach { doYourWork() }
                .map { it.countActiveWork() }
                .distinctUntilChanged()
                .collect { App.setServiceWorkActive(DownloadWorkSource.TASK_QUEUE, it > 0) }
        }

        scope.launch(Dispatchers.IO) {
            // don't write before we read
            enqueueFromBackup()

            snapshotFlow
                .map { it.filter { it.value.downloadState !is Completed } }
                .distinctUntilChanged()
                .collect {
                    PreferenceUtil.encodeTaskListBackup(it)
                }
        }
    }

    private fun enqueueFromBackup() {
        val taskList =
            PreferenceUtil.decodeTaskListBackup()
                .filter { it.value.downloadState !is Completed }
                .mapValues { (task, state) ->
                    TaskRestorePolicy.restore(task, state)
                }
        taskList.forEach(::enqueue)
    }

    private fun Map<Task, Task.State>.countActiveWork(): Int = count { (_, state) ->
        state.downloadState == ReadyWithInfo ||
            state.downloadState is Running ||
            state.downloadState is FetchingInfo
    }

    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return taskStateMap
    }

    override fun enqueue(task: Task) {
        taskStateMap +=
            task to Task.State(Idle, null, Task.ViewState(url = task.url, title = safeDownloadTitle(task.url)))
    }

    override fun enqueue(task: Task, state: Task.State) {
        taskStateMap += task to state
    }

    /**
     * Noted the caller is responsible for stopping the [task] before removing it
     *
     * @return true if the task was removed
     */
    override fun remove(task: Task): Boolean {
        val previousState = taskStateMap[task] ?: return false
        taskStateMap.remove(task)
        if (
            previousState.downloadState !is DownloadState.Cancelable &&
                !stoppingJobs.containsKey(task.id)
        ) {
            task.cleanupDirectHttpArtifacts(previousState)
        }
        return true
    }

    override fun cancel(task: Task): Boolean = task.cancelImpl()

    override fun pause(task: Task): Boolean = task.pauseImpl()

    override fun restart(task: Task) {
        task.restartImpl()
    }

    private var Task.state: Task.State
        get() = taskStateMap[this]!!
        set(value) {
            taskStateMap[this] = value
        }

    private var Task.downloadState: DownloadState
        get() = state.downloadState
        set(value) {
            val prevState = state
            taskStateMap[this] = prevState.copy(downloadState = value)
        }

    private var Task.info: VideoInfo?
        get() = state.videoInfo
        set(value) {
            val prevState = state
            taskStateMap[this] = prevState.copy(videoInfo = value)
        }

    private var Task.viewState: Task.ViewState
        get() = state.viewState
        set(value) {
            val prevState = state
            taskStateMap[this] = prevState.copy(viewState = value)
        }

    private val Task.notificationId: Int
        get() = id.hashCode()

    /** Processes pending tasks, prioritizing downloads. */
    private fun doYourWork() {
        if (
            !DownloadConcurrencyPolicy.mayStartAnother(
                runningTasks = taskStateMap.countRunning(),
                configuredLimit = MAX_PARALLEL_DOWNLOADS.getInt(DownloadConcurrencyPolicy.DEFAULT_LIMIT),
            )
        ) return

        taskStateMap.entries
            .sortedBy { (_, state) -> state.downloadState }
            .firstOrNull { (_, state) ->
                state.downloadState == ReadyWithInfo || state.downloadState == Idle
            }
            ?.let { (task, state) ->
                when (state.downloadState) {
                    Idle -> task.prepare()
                    ReadyWithInfo -> task.download()
                    else -> {
                        throw IllegalStateException()
                    }
                }
            }
    }

    private fun Task.prepare() {
        check(downloadState == Idle)
        if (type is TypeInfo.CustomCommand) {
            execute()
        } else {
            fetchInfo()
        }
    }

    private fun Task.fetchInfo() {
        check(downloadState == Idle)
        if (type == TypeInfo.DirectHttpFile) {
            fetchDirectHttpInfo()
            return
        }
        val task = this
        val taskInfo = task.type
        val playlistIndex = if (taskInfo is TypeInfo.Playlist) taskInfo.index else null
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                App.awaitYtDlpReady()
                App.ensureForegroundServiceReady()
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        playlistIndex = playlistIndex,
                        preferences = preferences,
                        taskKey = id,
                    )
                    .onSuccess {
                        info = it
                        downloadState = ReadyWithInfo
                        viewState = Task.ViewState.fromVideoInfo(it)
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        task.downloadState = Error(throwable = throwable, action = FetchInfo)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
            }
        downloadState = FetchingInfo(job = job, taskId = id)
        job.invokeOnCompletion { cause ->
            if (cause != null) handleUnexpectedFailure(job, FetchInfo, cause)
        }
        NotificationUtil.notifyProgress(
            notificationId = notificationId,
            title = viewState.title,
            taskId = id,
        )
        job.start()
    }

    private fun Task.fetchDirectHttpInfo() {
        check(downloadState == Idle)
        val task = this
        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                runCatching {
                        retryTransientDownload { directHttpResolver.resolve(url, id) }
                            ?: throw IOException("The link is not a direct HTTP file")
                    }
                    .onSuccess { file ->
                        val previous = state
                        state =
                            previous.copy(
                                videoInfo = null,
                                viewState = viewStateFor(url, file),
                                directHttpFile = file,
                            )
                        downloadState = ReadyWithInfo
                    }
                    .onFailure { throwable ->
                        if (throwable is CancellationException) return@onFailure
                        task.downloadState = Error(throwable = throwable, action = FetchInfo)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = "Direct HTTP link preview failed: ${throwable::class.java.simpleName}",
                        )
                    }
            }
        downloadState = FetchingInfo(job = job, taskId = id)
        job.invokeOnCompletion { cause ->
            if (cause != null) handleUnexpectedFailure(job, FetchInfo, cause)
        }
        NotificationUtil.notifyProgress(
            notificationId = notificationId,
            title = viewState.title,
            taskId = id,
        )
        job.start()
    }

    private fun viewStateFor(url: String, file: DirectHttpFile): Task.ViewState =
        Task.ViewState(
            url = url,
            title = file.fileName,
            uploader = file.contentType,
            extractorKey = "HTTP",
            fileSizeApprox = file.sizeBytes?.toDouble() ?: .0,
        )

    private fun Task.download() {
        check(downloadState == ReadyWithInfo)
        if (type == TypeInfo.DirectHttpFile) {
            downloadDirectHttpFile()
            return
        }
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        check(info != null)
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                App.awaitYtDlpReady()
                App.ensureForegroundServiceReady()
                DownloadUtil.downloadVideo(
                        videoInfo = info,
                        taskId = id,
                        downloadPreferences = preferences,
                        progressCallback = { progressPercentage, _, text ->
                            val progress = progressPercentage / 100f
                            val progressLabel = downloadSizeLabel(text)
                            when (val preState = downloadState) {
                                is Running -> {
                                    downloadState =
                                        preState.copy(progress = progress, progressText = progressLabel)
                                    NotificationUtil.notifyProgress(
                                        notificationId = notificationId,
                                        progress = progressPercentage.toInt(),
                                        text = progressLabel,
                                        title = viewState.title,
                                        taskId = id,
                                    )
                                }
                                else -> {}
                            }
                        },
                    )
                    .onSuccess { pathList ->
                        downloadState = Completed(pathList.firstOrNull())

                        val text =
                            appContext.getString(
                                if (pathList.isEmpty()) R.string.status_completed
                                else R.string.download_finish_notification
                            )
                        FileUtil.createIntentForOpeningFile(pathList.firstOrNull()).run {
                            NotificationUtil.finishNotification(
                                notificationId,
                                title = viewState.title,
                                text = text,
                                intent =
                                    if (this != null)
                                        PendingIntent.getActivity(
                                            appContext,
                                            0,
                                            this,
                                            PendingIntent.FLAG_IMMUTABLE,
                                        )
                                    else null,
                            )
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        downloadState = Error(throwable = throwable, action = Download)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
            }
        downloadState = Running(job = job, taskId = id)
        job.invokeOnCompletion { cause ->
            if (cause != null) handleUnexpectedFailure(job, Download, cause)
        }
        NotificationUtil.notifyProgress(
            notificationId = notificationId,
            title = viewState.title,
            taskId = id,
        )
        job.start()
    }

    /**
     * Stops the running process but marks the task as paused instead of cancelled, so the UI can
     * offer "resume" and yt-dlp can continue the partial download.
     */
    private fun Task.downloadDirectHttpFile() {
        check(downloadState == ReadyWithInfo && type == TypeInfo.DirectHttpFile)
        val task = this
        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                runCatching {
                        App.ensureForegroundServiceReady()
                        val previousFile = state.directHttpFile
                        val resolvedFile = retryTransientDownload { directHttpResolver.resolve(url, id) }
                        val file = resolvedFile ?: previousFile
                            ?: throw IOException("The link is not a direct HTTP file")
                        state =
                            state.copy(
                                directHttpFile = file,
                                videoInfo = null,
                                viewState = viewStateFor(url, file),
                            )

                        val partialDirectory = FileUtil.getExternalTempDir()
                        // Legacy hashCode-named files have no validator/owner binding. Leave them
                        // untouched rather than risking corruption or resuming another task's bytes.
                        val partialFile =
                            File(partialDirectory, DirectHttpTempFileNames.partialName(id))
                        if (!partialDirectory.exists() && !partialDirectory.mkdirs()) {
                            throw IOException("Cannot create temporary download folder")
                        }
                        ensureAvailableSpace(partialDirectory, file.sizeBytes, partialFile.length())
                        var lastProgressAt = 0L
                        var lastProgressBytes = 0L
                        val progressCallback: (Long, Long?) -> Unit = progress@{ downloaded, total ->
                            val now = System.currentTimeMillis()
                            if (
                                now - lastProgressAt >= 500L ||
                                    downloaded - lastProgressBytes >= PROGRESS_UPDATE_BYTES ||
                                    (total != null && downloaded >= total)
                            ) {
                                lastProgressAt = now
                                lastProgressBytes = downloaded
                                val current = downloadState as? Running ?: return@progress
                                val percentage =
                                    if (total != null && total > 0L) {
                                        (downloaded * 100L / total).toInt().coerceIn(0, 100)
                                    } else -1
                                val text =
                                    if (total == null) formatBytes(downloaded)
                                    else "${formatBytes(downloaded)} / ${formatBytes(total)}"
                                downloadState =
                                    current.copy(
                                        progress = if (percentage < 0) -1f else percentage / 100f,
                                        progressText = text,
                                    )
                                NotificationUtil.notifyProgress(
                                    notificationId = notificationId,
                                    progress = percentage,
                                    text = text,
                                    title = viewState.title,
                                    taskId = id,
                                )
                            }
                        }
                        retryTransientDownload {
                            directHttpDownloader.download(id, file, partialFile, progressCallback)
                        }

                        publishDirectHttpFile(task, partialFile, file, preferences)
                    }
                    .onSuccess { savedPaths ->
                        val savedPath = savedPaths.firstOrNull()
                        if (savedPath != null && !preferences.sdcard) {
                            MediaScannerConnection.scanFile(
                                appContext,
                                arrayOf(savedPath),
                                arrayOf(state.directHttpFile?.contentType),
                                null,
                            )
                        }
                        val openIntent = FileUtil.createIntentForOpeningFile(savedPath)
                        NotificationUtil.finishNotification(
                            notificationId = notificationId,
                            title = viewState.title,
                            text = appContext.getString(R.string.download_finish_notification),
                            intent =
                                openIntent?.let {
                                    PendingIntent.getActivity(
                                        appContext,
                                        0,
                                        it,
                                        PendingIntent.FLAG_IMMUTABLE,
                                    )
                                },
                        )
                    }
                    .onFailure { throwable ->
                        if (
                            throwable is CancellationException ||
                                throwable is YoutubeDL.CanceledException
                        ) return@onFailure
                        downloadState = Error(throwable = throwable, action = Download)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = "Direct HTTP download failed: ${throwable::class.java.simpleName}",
                        )
                    }
            }
        downloadState = Running(job = job, taskId = id)
        job.invokeOnCompletion { cause ->
            if (cause != null) handleUnexpectedFailure(job, Download, cause)
        }
        NotificationUtil.notifyProgress(
            notificationId = notificationId,
            title = viewState.title,
            taskId = id,
        )
        job.start()
    }

    private fun publishDirectHttpFile(
        task: Task,
        partialFile: File,
        file: DirectHttpFile,
        preferences: DownloadUtil.DownloadPreferences,
    ): List<String> {
        val safeName = FileNaming.sanitize(file.fileName)
        val nameToken = directHttpNameToken(task.id)
        if (preferences.sdcard) {
            if (preferences.sdcardUri.isBlank()) throw IOException("No SD card destination is selected")
            val stagingDirectory =
                FileUtil.run { appContext.getSdcardTempDir("direct-http-$nameToken") }
            if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
                throw IOException("Cannot create the selected SD card staging folder")
            }
            val stagedFile = File(stagingDirectory, safeName)
            if (stagedFile.exists() && !stagedFile.delete()) throw IOException("Cannot reset staging file")
            ensureAvailableSpace(stagingDirectory, partialFile.length())
            task.copyDirectHttpPartial(partialFile, stagedFile)
            val result =
                FileUtil.moveFilesToSdcard(stagingDirectory, preferences.sdcardUri) {
                    task.checkDirectHttpTaskRunning()
                }
            val paths =
                result.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    throw IOException("Could not save to the selected SD card", failure)
                }
            if (paths.isEmpty()) throw IOException("The selected SD card did not accept the file")
            val committed =
                synchronized(task) {
                    if (taskStateMap[task]?.downloadState !is Running) {
                        false
                    } else {
                        directHttpDownloader.clearResumeMetadata(partialFile)
                        task.downloadState = Completed(paths.firstOrNull())
                        true
                    }
                }
            if (!committed) {
                deletePublishedDirectHttpFiles(paths)
                throw CancellationException("Download was stopped before commit")
            }
            partialFile.delete()
            return paths
        }

        val directory =
            File(if (preferences.privateDirectory) App.privateDownloadDir else App.videoDownloadDir)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create the configured download folder")
        }
        val extension = FileNaming.extensionOf(safeName)
        val baseName = safeName.removeSuffix(extension)
        val stagingFile = File(directory, ".${safeName}.$nameToken.partial")
        if (stagingFile.exists() && !stagingFile.delete()) throw IOException("Cannot reset staging file")
        ensureAvailableSpace(directory, partialFile.length())
        task.copyDirectHttpPartial(partialFile, stagingFile)

        val finalPath =
            synchronized(task) {
                task.checkDirectHttpTaskRunning()
                synchronized(outputFileLock) {
                    var committedPath: String? = null
                    for (suffix in 0..999) {
                        val candidateName =
                            if (suffix == 0) "$baseName$extension" else "$baseName ($suffix)$extension"
                        val destination = File(directory, candidateName)
                        if (destination.exists()) continue
                        if (stagingFile.renameTo(destination)) {
                            directHttpDownloader.clearResumeMetadata(partialFile)
                            task.downloadState = Completed(destination.absolutePath)
                            committedPath = destination.absolutePath
                            break
                        }
                        if (!destination.exists()) {
                            throw IOException("Could not finalize the downloaded file")
                        }
                    }
                    committedPath ?: throw IOException("Could not find an unused file name")
                }
            }
        partialFile.delete()
        return listOf(finalPath)
    }

    private fun Task.checkDirectHttpTaskRunning() {
        if (taskStateMap[this]?.downloadState !is Running) {
            throw CancellationException("Direct HTTP task is no longer running")
        }
    }

    private fun Task.copyDirectHttpPartial(source: File, destination: File) {
        checkDirectHttpTaskRunning()
        source.inputStream().use { input ->
            java.io.FileOutputStream(destination, false).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkDirectHttpTaskRunning()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        checkDirectHttpTaskRunning()
    }

    private fun deletePublishedDirectHttpFiles(paths: List<String>) {
        paths.forEach { path ->
            runCatching {
                if (path.startsWith("content://")) {
                    appContext.contentResolver.delete(android.net.Uri.parse(path), null, null)
                } else {
                    File(path).delete()
                }
            }
        }
    }

    @Suppress("UsableSpace") // Conservative: do not count cache bytes Android may reclaim.
    private fun ensureAvailableSpace(directory: File, totalBytes: Long?, existingBytes: Long = 0L) {
        if (!DownloadStoragePolicy.canFit(directory.usableSpace, totalBytes, existingBytes)) {
            throw IOException("No space left on device for this download")
        }
    }

    private fun directHttpNameToken(taskId: String): String =
        UUID.nameUUIDFromBytes(taskId.toByteArray(Charsets.UTF_8)).toString()

    /** Delete only task-owned temporary files; never touch a published destination file. */
    private fun Task.cleanupDirectHttpArtifacts(taskState: Task.State? = taskStateMap[this]) {
        if (type != TypeInfo.DirectHttpFile) return
        runCatching {
            val partialFile =
                File(FileUtil.getExternalTempDir(), DirectHttpTempFileNames.partialName(id))
            directHttpDownloader.clearResumeMetadata(partialFile)
            partialFile.delete()

            val sourceName = taskState?.directHttpFile?.fileName ?: return@runCatching
            val token = directHttpNameToken(id)
            val safeName = FileNaming.sanitize(sourceName)
            if (preferences.sdcard) {
                FileUtil.run { appContext.getSdcardTempDir("direct-http-$token") }.deleteRecursively()
            } else {
                val directory =
                    File(if (preferences.privateDirectory) App.privateDownloadDir else App.videoDownloadDir)
                File(directory, ".$safeName.$token.partial").delete()
            }
        }
    }

    /** Delay destructive cleanup until the canceled IO job has closed its file handles. */
    private data class StoppingTaskJob(
        val job: Job,
        val finished: CompletableDeferred<Unit>,
        val stoppedState: Task.State?,
    )

    private fun Task.trackStoppingJob(job: Job): StoppingTaskJob {
        val task = this
        val stoppingJob = StoppingTaskJob(job, CompletableDeferred(), taskStateMap[this])
        stoppingJobs[id] = stoppingJob
        job.invokeOnCompletion {
            scope.launch(Dispatchers.IO) {
                try {
                    if (task.type == TypeInfo.DirectHttpFile) {
                        val latestState = taskStateMap[task]?.downloadState
                        if (latestState == null || (latestState is Canceled && !latestState.paused)) {
                            task.cleanupDirectHttpArtifacts(taskStateMap[task] ?: stoppingJob.stoppedState)
                        }
                    }
                } finally {
                    stoppingJobs.remove(task.id, stoppingJob)
                    stoppingJob.finished.complete(Unit)
                }
            }
        }
        return stoppingJob
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        do {
            value /= 1024.0
            unitIndex++
        } while (value >= 1024.0 && unitIndex < units.lastIndex)
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }

    private fun Task.pauseImpl(): Boolean =
        synchronized(this) {
            when (val preState = downloadState) {
                is DownloadState.Cancelable -> {
                    val res =
                        if (type == TypeInfo.DirectHttpFile) {
                            directHttpResolver.cancel(preState.taskId)
                            directHttpDownloader.cancel(preState.taskId)
                            true
                        } else YoutubeDL.destroyProcessById(preState.taskId)
                    if (res) {
                        preState.job.cancel()
                        NotificationUtil.cancelNotification(notificationId)
                        val progress = (preState as? Running)?.progress
                        downloadState =
                            DownloadState.Canceled(
                                action = preState.action,
                                progress = progress,
                                paused = true,
                                progressText = (preState as? Running)?.progressText.orEmpty(),
                            )
                        trackStoppingJob(preState.job)
                        if (type == TypeInfo.DirectHttpFile) {
                            NotificationUtil.notifyPaused(
                                title = viewState.title,
                                notificationId = notificationId,
                                taskId = id,
                                progress =
                                    progress?.takeIf { it >= 0f }?.let { (it * 100).toInt().coerceIn(0, 100) }
                                        ?: 0,
                                text = (preState as? Running)?.progressText,
                            )
                        }
                    }
                    res
                }
                else -> false
            }
        }

    private fun Task.cancelImpl(): Boolean {
        var cleanupNow = false
        val canceled =
            synchronized(this) {
                when (val preState = downloadState) {
                    is DownloadState.Cancelable -> {
                        val res =
                            if (type == TypeInfo.DirectHttpFile) {
                                directHttpResolver.cancel(preState.taskId)
                                directHttpDownloader.cancel(preState.taskId)
                                true
                            } else YoutubeDL.destroyProcessById(preState.taskId)
                        if (res) {
                            preState.job.cancel()
                            val progress = if (preState is Running) preState.progress else null
                            NotificationUtil.cancelNotification(notificationId)
                            downloadState = DownloadState.Canceled(action = preState.action, progress = progress)
                            trackStoppingJob(preState.job)
                        }
                        res
                    }
                    Idle -> {
                        downloadState = DownloadState.Canceled(action = FetchInfo)
                        cleanupNow = true
                        true
                    }
                    ReadyWithInfo -> {
                        downloadState = DownloadState.Canceled(action = Download)
                        cleanupNow = true
                        true
                    }
                    else -> false
                }
            }
        if (cleanupNow) cleanupDirectHttpArtifacts()
        return canceled
    }

    private fun Task.restartImpl() {
        val task = this
        val pendingRestart =
            synchronized(this) {
                val preState = downloadState as? DownloadState.Restartable ?: throw IllegalStateException()
                val stoppingJob = stoppingJobs[id]
                if (stoppingJob == null) {
                    downloadState = restartTarget(preState)
                    null
                } else {
                    preState to stoppingJob
                }
            }

        pendingRestart?.let { (preState, stoppingJob) ->
            scope.launch(Dispatchers.IO) {
                stoppingJob.finished.await()
                synchronized(task) {
                    val latest = taskStateMap[task] ?: return@synchronized
                    if (latest.downloadState == preState) {
                        taskStateMap[task] = latest.copy(downloadState = task.restartTarget(preState))
                    }
                }
            }
        }
    }

    private fun Task.restartTarget(state: DownloadState.Restartable): DownloadState =
        when (state.action) {
            Download -> ReadyWithInfo
            FetchInfo -> Idle
        }

    /**
     * Execute a custom command task
     *
     * @see Task.TypeInfo.CustomCommand
     */
    private fun Task.execute() {
        check(downloadState == Idle)
        check(type is TypeInfo.CustomCommand)
        val template = type.template
        val job = scope.launch(start = CoroutineStart.LAZY) {
                App.awaitYtDlpReady()
                App.ensureForegroundServiceReady()
                DownloadUtil.executeCustomCommandTask(url, id, template, preferences) {
                        progressPercentage,
                        _,
                        text ->
                        val progress = progressPercentage / 100f
                        when (val preState = downloadState) {
                            is Running -> {
                                downloadState =
                                    preState.copy(progress = progress, progressText = text)
                                NotificationUtil.makeNotificationForCustomCommand(
                                    notificationId = notificationId,
                                    taskId = id,
                                    progress = progressPercentage.toInt(),
                                    templateName = template.name,
                                    taskUrl = url,
                                    text = text,
                                    canPause = true,
                                )
                            }
                            else -> {}
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        downloadState = Error(throwable = throwable, action = Download)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                    .onSuccess {
                        downloadState = Completed(null)

                        val text = appContext.getString(R.string.status_completed)

                        NotificationUtil.finishNotification(
                            notificationId = notificationId,
                            title = viewState.title,
                            text = text,
                            intent = null,
                        )
                    }
            }
        downloadState = Running(job = job, taskId = id)
        job.invokeOnCompletion { cause ->
            if (cause != null) handleUnexpectedFailure(job, Download, cause)
        }
        NotificationUtil.makeNotificationForCustomCommand(
            notificationId = notificationId,
            taskId = id,
            progress = -1,
            templateName = template.name,
            taskUrl = url,
            canPause = true,
        )
        job.start()
    }

    private fun Task.handleUnexpectedFailure(
        job: kotlinx.coroutines.Job,
        action: Task.RestartableAction,
        cause: Throwable,
    ) {
        if (cause is CancellationException || cause is YoutubeDL.CanceledException) return
        val currentState = taskStateMap[this]?.downloadState ?: return
        val jobIsStillCurrent =
            when (currentState) {
                is FetchingInfo -> currentState.job === job
                is Running -> currentState.job === job
                else -> false
            }
        if (!jobIsStillCurrent) return

        downloadState = Error(throwable = cause, action = action)
        NotificationUtil.notifyError(
            title = viewState.title,
            textId = if (action == FetchInfo) R.string.download_error_msg else R.string.fetch_info_error_msg,
            notificationId = notificationId,
            report = cause.stackTraceToString(),
        )
    }

    /** Adds the selected file size once, without replacing yt-dlp's own progress text. */
    private fun Task.downloadSizeLabel(progressText: String): String {
        val bytes = info?.fileSize ?: viewState.fileSizeApprox
        if (bytes <= 0.0) return progressText
        val size = appContext.getString(R.string.filesize_mb).format(bytes / (1024.0 * 1024.0))
        return if (progressText.contains(size)) progressText else "$progressText · $size"
    }
}

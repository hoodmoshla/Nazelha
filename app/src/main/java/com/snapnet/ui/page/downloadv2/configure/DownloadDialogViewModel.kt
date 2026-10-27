package com.snapnet.ui.page.downloadv2.configure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapnet.database.objects.CommandTemplate
import com.snapnet.download.DownloaderV2
import com.snapnet.download.Task
import com.snapnet.download.TaskFactory
import com.snapnet.download.http.DirectHttpFile
import com.snapnet.download.http.DirectHttpLinkResolver
import com.snapnet.download.http.DiscoveredDownloadLink
import com.snapnet.util.DownloadUtil
import com.snapnet.util.NotificationUtil
import com.snapnet.util.PlaylistResult
import com.snapnet.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class DownloadDialogViewModel(private val downloader: DownloaderV2) : ViewModel() {

    private val directHttpResolver = DirectHttpLinkResolver()

    sealed interface SelectionState {
        data object Idle : SelectionState

        data class PlaylistSelection(val result: PlaylistResult) : SelectionState

        data class FormatSelection(val info: VideoInfo, val isSharedIntent: Boolean = false) :
            SelectionState
    }

    sealed interface SheetState {
        data object InputUrl : SheetState

        data class Configure(val urlList: List<String>) : SheetState

        data class Loading(val taskKey: String, val job: Job) : SheetState

        data class DirectHttpPreview(val url: String, val file: DirectHttpFile) : SheetState

        data class DiscoveredPageLinks(val links: List<DiscoveredDownloadLink>) : SheetState

        data class Error(val action: Action, val throwable: Throwable) : SheetState
    }

    sealed interface SheetValue {
        data object Expanded : SheetValue

        data object Hidden : SheetValue
    }

    sealed interface Action {
        data object HideSheet : Action

        data class ShowSheet(val urlList: List<String>? = null) : Action

        data class ProceedWithURLs(val urlList: List<String>) : Action

        data object Reset : Action

        data class FetchPlaylist(
            val url: String,
            val preferences: DownloadUtil.DownloadPreferences,
            val isSharedIntent: Boolean = false,
        ) : Action

        data class FetchFormats(
            val url: String,
            val audioOnly: Boolean,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class DownloadWithPreset(
            val urlList: List<String>,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class RunCommand(
            val url: String,
            val template: CommandTemplate,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data object Cancel : Action

        data class EnqueueDirectHttpFile(
            val url: String,
            val file: DirectHttpFile,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action
    }

    private val mSelectionStateFlow: MutableStateFlow<SelectionState> =
        MutableStateFlow(SelectionState.Idle)
    private val mSheetStateFlow: MutableStateFlow<SheetState> =
        MutableStateFlow(SheetState.InputUrl)
    private val mSheetValueFlow: MutableStateFlow<SheetValue> = MutableStateFlow(SheetValue.Hidden)

    val selectionStateFlow = mSelectionStateFlow.asStateFlow()
    val sheetStateFlow = mSheetStateFlow.asStateFlow()
    val sheetValueFlow = mSheetValueFlow.asStateFlow()

    private val sheetState
        get() = sheetStateFlow.value

    fun postAction(action: Action) {
        with(action) {
            when (this) {
                is Action.ProceedWithURLs -> proceedWithUrls(this)
                is Action.FetchFormats -> fetchFormat(this)
                is Action.FetchPlaylist -> fetchPlaylist(this)
                is Action.EnqueueDirectHttpFile -> enqueueDirectHttpFile(this)
                is Action.DownloadWithPreset -> downloadWithPreset(urlList, preferences)
                is Action.RunCommand -> runCommand(url, template, preferences)
                Action.HideSheet -> hideDialog()
                is Action.ShowSheet -> showDialog(this)
                Action.Cancel -> cancel()
                Action.Reset -> resetSelectionState()
            }
        }
    }

    private fun proceedWithUrls(action: Action.ProceedWithURLs) {
        if (action.urlList.size != 1) {
            mSheetStateFlow.update { SheetState.Configure(action.urlList) }
            return
        }
        val url = action.urlList.first()
        resolveDirectFile(url) {
            mSheetStateFlow.update { SheetState.Configure(action.urlList) }
        }
    }

    private fun fetchPlaylist(action: Action.FetchPlaylist) {
        val (url, preferences) = action
        mSelectionStateFlow.update { SelectionState.Idle }
        if (action.isSharedIntent) {
            resolveDirectFile(url) { fetchSharedFormats(action) }
            return
        }
        val taskKey = opaqueTaskKey("FetchPlaylist")
        val job =
            viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                DownloadUtil.getPlaylistOrVideoInfo(
                        playlistURL = url,
                        downloadPreferences = preferences,
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            when (info) {
                                is PlaylistResult -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.PlaylistSelection(result = info)
                                    }
                                }

                                is VideoInfo -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.FormatSelection(
                                            info = info,
                                            isSharedIntent = action.isSharedIntent,
                                        )
                                    }
                                }
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        mSheetStateFlow.update { SheetState.Error(action = action, throwable = th) }
                    }
            }
        mSheetStateFlow.update { SheetState.Loading(taskKey = taskKey, job = job) }
        mSheetValueFlow.update { SheetValue.Expanded }
        job.start()
    }

    /** Shared links need the complete format list. A flat playlist request omits lower qualities. */
    private fun fetchSharedFormats(action: Action.FetchPlaylist) {
        val (url, preferences) = action
        val taskKey = opaqueTaskKey("FetchShared")
        val job =
            viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        preferences = preferences,
                        taskKey = taskKey,
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            mSelectionStateFlow.update {
                                SelectionState.FormatSelection(info = info, isSharedIntent = true)
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        mSheetStateFlow.update { SheetState.Error(action = action, throwable = th) }
                    }
            }
        mSheetStateFlow.update { SheetState.Loading(taskKey = taskKey, job = job) }
        mSheetValueFlow.update { SheetValue.Expanded }
        job.start()
    }

    private fun resolveDirectFile(url: String, onNotDirectFile: () -> Unit) {
        val taskKey = opaqueTaskKey("DirectHttpPreview")
        val job =
            viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val discovery: Pair<DirectHttpFile?, List<DiscoveredDownloadLink>> =
                    try {
                        val directFile = directHttpResolver.resolve(url, taskKey)
                        directFile to
                            if (directFile == null) {
                                directHttpResolver.discoverPageDownloads(url, taskKey)
                            } else {
                                emptyList()
                            }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                    } catch (_: Exception) {
                        null to emptyList()
                    }
                val file = discovery.first
                val discoveredLinks = discovery.second
                withContext(Dispatchers.Main) {
                    val loading = sheetState as? SheetState.Loading
                    if (loading?.taskKey != taskKey) return@withContext
                    if (file == null) {
                        if (discoveredLinks.isNotEmpty()) {
                            mSheetStateFlow.update {
                                SheetState.DiscoveredPageLinks(links = discoveredLinks)
                            }
                        } else {
                            onNotDirectFile()
                        }
                    } else {
                        mSheetStateFlow.update { SheetState.DirectHttpPreview(url = url, file = file) }
                    }
                }
            }
        mSheetStateFlow.update { SheetState.Loading(taskKey = taskKey, job = job) }
        mSheetValueFlow.update { SheetValue.Expanded }
        job.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) directHttpResolver.cancel(taskKey)
        }
        job.start()
    }

    private fun fetchFormat(action: Action.FetchFormats) {
        val (url, audioOnly, preferences) = action
        val taskKey = opaqueTaskKey("FetchFormat")

        val job =
            viewModelScope.launch(Dispatchers.IO) {
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        preferences = preferences.copy(extractAudio = audioOnly),
                        taskKey = taskKey,
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            mSelectionStateFlow.update {
                                SelectionState.FormatSelection(info = info)
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        withContext(Dispatchers.Main) {
                            mSheetStateFlow.update { SheetState.Error(action, throwable = th) }
                        }
                    }
            }

        mSheetStateFlow.update { SheetState.Loading(taskKey = taskKey, job = job) }
    }

    private fun downloadWithPreset(
        urlList: List<String>,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        urlList.forEach { downloader.enqueue(Task(url = it, preferences = preferences)) }
        hideDialog()
    }

    private fun enqueueDirectHttpFile(action: Action.EnqueueDirectHttpFile) {
        downloader.enqueue(
            TaskFactory.createDirectHttpFile(
                url = action.url,
                file = action.file,
                preferences = action.preferences,
            )
        )
        hideDialog()
    }

    private fun runCommand(
        url: String,
        template: CommandTemplate,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        val task =
            Task(
                url = url,
                type = Task.TypeInfo.CustomCommand(template = template),
                preferences = preferences,
            )
        downloader.enqueue(task)
    }

    private fun hideDialog() {
        mSheetValueFlow.update { SheetValue.Hidden }
        when (sheetState) {
            is SheetState.Loading -> {
                cancel()
            }

            else -> {}
        }
    }

    private fun showDialog(action: Action.ShowSheet) {
        val urlList = action.urlList
        if (!urlList.isNullOrEmpty()) {
            mSheetStateFlow.update { SheetState.Configure(urlList) }
        } else {
            mSheetStateFlow.update { SheetState.InputUrl }
        }
        mSheetValueFlow.update { SheetValue.Expanded }
    }

    private fun cancel(): Boolean {
        return when (val state = sheetState) {
            is SheetState.Loading -> {
                // Closing the preview must stop the fetch itself. Previously the process survived
                // whenever yt-dlp rejected the cancel, leaving the progress notification running.
                val res =
                    if (state.taskKey.startsWith("DirectHttpPreview-")) {
                        directHttpResolver.cancel(state.taskKey)
                        true
                    } else YoutubeDL.destroyProcessById(id = state.taskKey)
                state.job.cancel()
                NotificationUtil.cancelNotification(state.taskKey.hashCode())
                NotificationUtil.cancelNotification(NotificationUtil.SERVICE_NOTIFICATION_ID)
                return true
            }
            else -> false
        }
    }

    private fun resetSelectionState() {
        cancel()
        mSelectionStateFlow.update { SelectionState.Idle }
    }

    private fun opaqueTaskKey(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}

package com.snapnet.ui.page.downloadv2.configure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapnet.R
import com.snapnet.database.objects.CommandTemplate
import com.snapnet.download.DownloaderV2
import com.snapnet.download.Task
import com.snapnet.download.TaskFactory
import com.snapnet.download.http.DirectHttpFile
import com.snapnet.download.http.DirectHttpLinkResolver
import com.snapnet.download.http.DiscoveredDownloadLink
import com.snapnet.download.http.HttpDownloadSecurityPolicy
import com.snapnet.util.DownloadUtil
import com.snapnet.util.NotificationUtil
import com.snapnet.util.PlaylistResult
import com.snapnet.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun DownloadDialogViewModel.Action.withHttpConsentFlag(
    granted: Boolean
): DownloadDialogViewModel.Action =
    when (this) {
        is DownloadDialogViewModel.Action.ProceedWithURLs -> copy(httpConsentGranted = granted)
        is DownloadDialogViewModel.Action.FetchPlaylist -> copy(httpConsentGranted = granted)
        is DownloadDialogViewModel.Action.FetchFormats -> copy(httpConsentGranted = granted)
        is DownloadDialogViewModel.Action.DownloadWithPreset -> copy(httpConsentGranted = granted)
        is DownloadDialogViewModel.Action.RunCommand -> copy(httpConsentGranted = granted)
        is DownloadDialogViewModel.Action.EnqueueDirectHttpFile ->
            copy(httpConsentGranted = granted)
        else -> this
    }

class DownloadDialogViewModel(private val downloader: DownloaderV2) : ViewModel() {

    private val directHttpResolver = DirectHttpLinkResolver()

    sealed interface SelectionState {
        data object Idle : SelectionState

        data class PlaylistSelection(
            val result: PlaylistResult,
            val httpConsentGranted: Boolean = false,
        ) : SelectionState

        data class FormatSelection(
            val info: VideoInfo,
            val isSharedIntent: Boolean = false,
            val httpConsentGranted: Boolean = false,
        ) : SelectionState
    }

    sealed interface SheetState {
        data object InputUrl : SheetState

        data class Configure(val urlList: List<String>) : SheetState

        data class Loading(val taskKey: String, val job: Job) : SheetState

        data class DirectHttpPreview(
            val url: String,
            val file: DirectHttpFile,
            val httpConsentGranted: Boolean = false,
        ) : SheetState

        data class HttpWarning(val url: String) : SheetState

        data class UnsupportedUrl(val messageRes: Int) : SheetState

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

        data class ProceedWithURLs(
            val urlList: List<String>,
            val httpConsentGranted: Boolean = false,
        ) : Action

        data object Reset : Action

        data class FetchPlaylist(
            val url: String,
            val preferences: DownloadUtil.DownloadPreferences,
            val isSharedIntent: Boolean = false,
            val httpConsentGranted: Boolean = false,
        ) : Action

        data class FetchFormats(
            val url: String,
            val audioOnly: Boolean,
            val preferences: DownloadUtil.DownloadPreferences,
            val httpConsentGranted: Boolean = false,
        ) : Action

        data class DownloadWithPreset(
            val urlList: List<String>,
            val preferences: DownloadUtil.DownloadPreferences,
            val httpConsentGranted: Boolean = false,
        ) : Action

        data class RunCommand(
            val url: String,
            val template: CommandTemplate,
            val preferences: DownloadUtil.DownloadPreferences,
            val httpConsentGranted: Boolean = false,
        ) : Action

        data object Cancel : Action

        data object ConfirmHttpDownload : Action

        data object CancelHttpDownload : Action

        data class EnqueueDirectHttpFile(
            val url: String,
            val file: DirectHttpFile,
            val preferences: DownloadUtil.DownloadPreferences,
            val httpConsentGranted: Boolean = false,
        ) : Action
    }

    private val mSelectionStateFlow: MutableStateFlow<SelectionState> =
        MutableStateFlow(SelectionState.Idle)
    private val mSheetStateFlow: MutableStateFlow<SheetState> =
        MutableStateFlow(SheetState.InputUrl)
    private val mSheetValueFlow: MutableStateFlow<SheetValue> = MutableStateFlow(SheetValue.Hidden)

    private var pendingHttpAction: Action? = null
    private var pendingHttpUrls: List<String> = emptyList()
    private val confirmedHttpUrls = linkedSetOf<String>()

    val selectionStateFlow = mSelectionStateFlow.asStateFlow()
    val sheetStateFlow = mSheetStateFlow.asStateFlow()
    val sheetValueFlow = mSheetValueFlow.asStateFlow()

    private val sheetState
        get() = sheetStateFlow.value

    fun postAction(action: Action) {
        when (action) {
            Action.ConfirmHttpDownload -> {
                confirmHttpDownload()
                return
            }
            Action.CancelHttpDownload -> {
                cancelHttpDownload()
                return
            }
            else -> {}
        }

        val urls = action.downloadUrls()
        val invalidUrl = urls.firstOrNull { !HttpDownloadSecurityPolicy.isSupportedSourceUrl(it) }
        if (invalidUrl != null) {
            pendingHttpAction = null
            pendingHttpUrls = emptyList()
            confirmedHttpUrls.clear()
            val messageRes =
                if (HttpDownloadSecurityPolicy.hasCleartextCredentials(invalidUrl)) {
                    R.string.http_credentials_in_url_blocked
                } else {
                    R.string.unsupported_url_scheme
                }
            mSheetStateFlow.update { SheetState.UnsupportedUrl(messageRes) }
            mSheetValueFlow.update { SheetValue.Expanded }
            return
        }

        if (urls.isNotEmpty() && !action.hasHttpConsent()) {
            val httpUrls =
                urls.filter(HttpDownloadSecurityPolicy::requiresUserConfirmation).distinct()
            if (httpUrls.isNotEmpty()) {
                if (action is Action.RunCommand) {
                    mSheetStateFlow.update {
                        SheetState.UnsupportedUrl(R.string.http_video_extractor_unsupported)
                    }
                    mSheetValueFlow.update { SheetValue.Expanded }
                    return
                }
                pendingHttpAction = action
                pendingHttpUrls = httpUrls
                confirmedHttpUrls.clear()
                showNextHttpWarning()
                return
            }
        }

        if (
            action is Action.RunCommand &&
                action.downloadUrls().any(HttpDownloadSecurityPolicy::requiresUserConfirmation)
        ) {
            mSheetStateFlow.update {
                SheetState.UnsupportedUrl(R.string.http_video_extractor_unsupported)
            }
            mSheetValueFlow.update { SheetValue.Expanded }
            return
        }

        dispatchAction(action)
    }

    private fun dispatchAction(action: Action) {
        when (action) {
            is Action.ProceedWithURLs -> proceedWithUrls(action)
            is Action.FetchFormats -> fetchFormat(action)
            is Action.FetchPlaylist -> fetchPlaylist(action)
            is Action.EnqueueDirectHttpFile -> enqueueDirectHttpFile(action)
            is Action.DownloadWithPreset -> downloadWithPreset(action)
            is Action.RunCommand -> runCommand(action)
            Action.HideSheet -> hideDialog()
            is Action.ShowSheet -> showDialog(action)
            Action.Cancel -> cancel()
            Action.Reset -> resetSelectionState()
            Action.ConfirmHttpDownload,
            Action.CancelHttpDownload -> Unit
        }
    }

    private fun confirmHttpDownload() {
        val action = pendingHttpAction ?: return
        val currentWarning = (sheetState as? SheetState.HttpWarning)?.url ?: return
        confirmedHttpUrls += currentWarning
        val next = pendingHttpUrls.firstOrNull { it !in confirmedHttpUrls }
        if (next != null) {
            mSheetStateFlow.update { SheetState.HttpWarning(next) }
            return
        }
        pendingHttpAction = null
        pendingHttpUrls = emptyList()
        confirmedHttpUrls.clear()
        dispatchAction(action.withHttpConsent())
    }

    private fun cancelHttpDownload() {
        pendingHttpAction = null
        pendingHttpUrls = emptyList()
        confirmedHttpUrls.clear()
        hideDialog()
    }

    private fun showNextHttpWarning() {
        val next = pendingHttpUrls.firstOrNull { it !in confirmedHttpUrls } ?: return
        mSheetStateFlow.update { SheetState.HttpWarning(next) }
        mSheetValueFlow.update { SheetValue.Expanded }
    }

    private fun Action.downloadUrls(): List<String> =
        when (this) {
            is Action.ProceedWithURLs -> urlList
            is Action.FetchPlaylist -> listOf(url)
            is Action.FetchFormats -> listOf(url)
            is Action.DownloadWithPreset -> urlList
            is Action.RunCommand -> listOf(url)
            is Action.EnqueueDirectHttpFile -> listOf(url)
            else -> emptyList()
        }

    private fun Action.hasHttpConsent(): Boolean =
        when (this) {
            is Action.ProceedWithURLs -> httpConsentGranted
            is Action.FetchPlaylist -> httpConsentGranted
            is Action.FetchFormats -> httpConsentGranted
            is Action.DownloadWithPreset -> httpConsentGranted
            is Action.RunCommand -> httpConsentGranted
            is Action.EnqueueDirectHttpFile -> httpConsentGranted
            else -> false
        }

    private fun Action.withHttpConsent(): Action = withHttpConsentFlag(granted = true)

    private fun proceedWithUrls(action: Action.ProceedWithURLs) {
        if (action.urlList.size != 1) {
            mSheetStateFlow.update { SheetState.Configure(action.urlList) }
            return
        }
        val url = action.urlList.first()
        resolveDirectFile(url, action.httpConsentGranted) {
            if (HttpDownloadSecurityPolicy.requiresUserConfirmation(url)) {
                showHttpExtractorUnsupported()
            } else {
                mSheetStateFlow.update { SheetState.Configure(action.urlList) }
            }
        }
    }

    private fun fetchPlaylist(action: Action.FetchPlaylist) {
        val (url, preferences) = action
        mSelectionStateFlow.update { SelectionState.Idle }
        if (HttpDownloadSecurityPolicy.requiresUserConfirmation(url)) {
            resolveDirectFile(url, action.httpConsentGranted) { showHttpExtractorUnsupported() }
            return
        }
        if (action.isSharedIntent) {
            resolveDirectFile(url, action.httpConsentGranted) { fetchSharedFormats(action) }
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
                                        SelectionState.PlaylistSelection(
                                            result = info,
                                            httpConsentGranted = action.httpConsentGranted,
                                        )
                                    }
                                }

                                is VideoInfo -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.FormatSelection(
                                            info = info,
                                            isSharedIntent = action.isSharedIntent,
                                            httpConsentGranted = action.httpConsentGranted,
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

    /**
     * Shared links need the complete format list. A flat playlist request omits lower qualities.
     */
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
                                SelectionState.FormatSelection(
                                    info = info,
                                    isSharedIntent = true,
                                    httpConsentGranted = action.httpConsentGranted,
                                )
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

    private fun resolveDirectFile(
        url: String,
        httpConsentGranted: Boolean = false,
        onNotDirectFile: () -> Unit,
    ) {
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
                        mSheetStateFlow.update {
                            SheetState.DirectHttpPreview(
                                url = url,
                                file = file,
                                httpConsentGranted = httpConsentGranted,
                            )
                        }
                    }
                }
            }
        mSheetStateFlow.update { SheetState.Loading(taskKey = taskKey, job = job) }
        mSheetValueFlow.update { SheetValue.Expanded }
        job.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException)
                directHttpResolver.cancel(taskKey)
        }
        job.start()
    }

    private fun fetchFormat(action: Action.FetchFormats) {
        val (url, audioOnly, preferences) = action
        if (HttpDownloadSecurityPolicy.requiresUserConfirmation(url)) {
            resolveDirectFile(url, action.httpConsentGranted) { showHttpExtractorUnsupported() }
            return
        }
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
                                SelectionState.FormatSelection(
                                    info = info,
                                    httpConsentGranted = action.httpConsentGranted,
                                )
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

    private fun downloadWithPreset(action: Action.DownloadWithPreset) {
        val httpUrls = action.urlList.filter(HttpDownloadSecurityPolicy::requiresUserConfirmation)
        if (httpUrls.isNotEmpty()) {
            if (action.urlList.size == 1) {
                resolveDirectFile(action.urlList.single(), action.httpConsentGranted) {
                    showHttpExtractorUnsupported()
                }
            } else {
                showHttpExtractorUnsupported()
            }
            return
        }
        action.urlList.forEach {
            downloader.enqueue(
                Task(url = it, preferences = action.preferences),
                action.httpConsentGranted,
            )
        }
        hideDialog()
    }

    private fun enqueueDirectHttpFile(action: Action.EnqueueDirectHttpFile) {
        downloader.enqueue(
            TaskFactory.createDirectHttpFile(
                url = action.url,
                file = action.file,
                preferences = action.preferences,
            ),
            action.httpConsentGranted,
        )
        hideDialog()
    }

    private fun runCommand(action: Action.RunCommand) {
        val task =
            Task(
                url = action.url,
                type = Task.TypeInfo.CustomCommand(template = action.template),
                preferences = action.preferences,
            )
        downloader.enqueue(task, action.httpConsentGranted)
    }

    private fun showHttpExtractorUnsupported() {
        mSheetStateFlow.update {
            SheetState.UnsupportedUrl(R.string.http_video_extractor_unsupported)
        }
        mSheetValueFlow.update { SheetValue.Expanded }
    }

    private fun hideDialog() {
        pendingHttpAction = null
        pendingHttpUrls = emptyList()
        confirmedHttpUrls.clear()
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
        pendingHttpAction = null
        pendingHttpUrls = emptyList()
        confirmedHttpUrls.clear()
        cancel()
        mSelectionStateFlow.update { SelectionState.Idle }
    }

    private fun opaqueTaskKey(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}

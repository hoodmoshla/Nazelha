package com.snapnet.ui.page.downloadv2.configure

import com.snapnet.R
import com.snapnet.database.objects.CommandTemplate
import com.snapnet.download.FakeDownloaderV2
import com.snapnet.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDialogViewModelHttpConsentTest {
    @Test
    fun httpProbeDoesNotStartUntilTheUserConfirmsTheWarning() {
        val viewModel = DownloadDialogViewModel(FakeDownloaderV2)

        viewModel.postAction(
            DownloadDialogViewModel.Action.ProceedWithURLs(listOf("http://example.com/file.bin"))
        )

        assertEquals(
            DownloadDialogViewModel.SheetState.HttpWarning("http://example.com/file.bin"),
            viewModel.sheetStateFlow.value,
        )
        assertEquals(DownloadDialogViewModel.SheetValue.Expanded, viewModel.sheetValueFlow.value)
        assertFalse(viewModel.sheetStateFlow.value is DownloadDialogViewModel.SheetState.Loading)
    }

    @Test
    fun httpCredentialQueryIsRejectedBeforeWarningOrLoading() {
        val viewModel = DownloadDialogViewModel(FakeDownloaderV2)
        val url = "http://example.com/file?access_token=secret"

        viewModel.postAction(DownloadDialogViewModel.Action.ProceedWithURLs(listOf(url)))

        assertEquals(
            DownloadDialogViewModel.SheetState.UnsupportedUrl(
                R.string.http_credentials_in_url_blocked
            ),
            viewModel.sheetStateFlow.value,
        )
        assertFalse(viewModel.sheetStateFlow.value is DownloadDialogViewModel.SheetState.Loading)
    }

    @Test
    fun sharedHttpLinkWaitsForConfirmationBeforeStartingItsDirectFileProbe() {
        val viewModel = DownloadDialogViewModel(FakeDownloaderV2)
        val url = "http://example.com/shared-file.bin"

        viewModel.postAction(
            DownloadDialogViewModel.Action.FetchPlaylist(
                url = url,
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
                isSharedIntent = true,
            )
        )

        assertEquals(
            DownloadDialogViewModel.SheetState.HttpWarning(url),
            viewModel.sheetStateFlow.value,
        )
        assertEquals(DownloadDialogViewModel.SheetValue.Expanded, viewModel.sheetValueFlow.value)
        assertEquals(
            DownloadDialogViewModel.SelectionState.Idle,
            viewModel.selectionStateFlow.value,
        )
        assertFalse(viewModel.sheetStateFlow.value is DownloadDialogViewModel.SheetState.Loading)
    }

    @Test
    fun retryingPreviouslyConfirmedHttpActionRequiresFreshConsent() {
        val viewModel = DownloadDialogViewModel(FakeDownloaderV2)
        val url = "http://example.com/retry.bin"
        val retryAction =
            DownloadDialogViewModel.Action.ProceedWithURLs(listOf(url), httpConsentGranted = true)
                .withHttpConsentFlag(false)

        assertEquals(
            DownloadDialogViewModel.Action.ProceedWithURLs(listOf(url), httpConsentGranted = false),
            retryAction,
        )
        viewModel.postAction(retryAction)

        assertEquals(
            DownloadDialogViewModel.SheetState.HttpWarning(url),
            viewModel.sheetStateFlow.value,
        )
        assertFalse(viewModel.sheetStateFlow.value is DownloadDialogViewModel.SheetState.Loading)
    }

    @Test
    fun eachHttpLinkInAMultiLinkActionMustBeConfirmedBeforeItContinues() {
        val viewModel = DownloadDialogViewModel(FakeDownloaderV2)
        val urls = listOf("http://one.example/file", "http://two.example/file")

        viewModel.postAction(DownloadDialogViewModel.Action.ProceedWithURLs(urls))
        assertEquals(
            DownloadDialogViewModel.SheetState.HttpWarning(urls[0]),
            viewModel.sheetStateFlow.value,
        )

        viewModel.postAction(DownloadDialogViewModel.Action.ConfirmHttpDownload)
        assertEquals(
            DownloadDialogViewModel.SheetState.HttpWarning(urls[1]),
            viewModel.sheetStateFlow.value,
        )

        viewModel.postAction(DownloadDialogViewModel.Action.ConfirmHttpDownload)
        assertEquals(
            DownloadDialogViewModel.SheetState.Configure(urls),
            viewModel.sheetStateFlow.value,
        )
    }

    @Test
    fun nonHttpSchemesAreRejectedBeforeAnyLoadingState() {
        val viewModel = DownloadDialogViewModel(FakeDownloaderV2)

        viewModel.postAction(
            DownloadDialogViewModel.Action.ProceedWithURLs(listOf("file:///tmp/private.bin"))
        )

        assertEquals(
            DownloadDialogViewModel.SheetState.UnsupportedUrl(R.string.unsupported_url_scheme),
            viewModel.sheetStateFlow.value,
        )
        assertTrue(viewModel.sheetValueFlow.value == DownloadDialogViewModel.SheetValue.Expanded)
        assertFalse(viewModel.sheetStateFlow.value is DownloadDialogViewModel.SheetState.Loading)
    }

    @Test
    fun customCommandCannotUseHttpEvenIfAnActionClaimsConsent() {
        val viewModel = DownloadDialogViewModel(FakeDownloaderV2)
        val action =
            DownloadDialogViewModel.Action.RunCommand(
                url = "http://example.com/video",
                template = CommandTemplate(id = 1, name = "test", template = "--simulate"),
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
                httpConsentGranted = true,
            )

        viewModel.postAction(action)

        assertEquals(
            DownloadDialogViewModel.SheetState.UnsupportedUrl(
                R.string.http_video_extractor_unsupported
            ),
            viewModel.sheetStateFlow.value,
        )
        assertFalse(viewModel.sheetStateFlow.value is DownloadDialogViewModel.SheetState.Loading)
    }
}

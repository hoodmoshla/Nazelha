package com.snapnet.ui.page.downloadv2.configure

import com.snapnet.R
import com.snapnet.download.FakeDownloaderV2
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
}

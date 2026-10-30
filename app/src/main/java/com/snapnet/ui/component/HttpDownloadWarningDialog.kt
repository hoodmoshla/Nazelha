package com.snapnet.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hoodmoshla.nazelha.R
import com.snapnet.download.http.HttpDownloadSecurityPolicy

@Composable
fun HttpDownloadWarningDialog(url: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.http_download_warning_title)) },
        text = {
            Text(
                stringResource(
                    R.string.http_download_warning_message,
                    HttpDownloadSecurityPolicy.hostForDisplay(url),
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.http_download_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

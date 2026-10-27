package com.snapnet.ui.page.files

internal fun toggleFileSelection(selection: Set<String>, key: String): Set<String> =
    if (key in selection) selection - key else selection + key

internal fun selectVisibleFiles(
    selection: Set<String>,
    visibleKeys: Set<String>,
    selected: Boolean,
): Set<String> =
    if (selected) selection + visibleKeys else selection - visibleKeys

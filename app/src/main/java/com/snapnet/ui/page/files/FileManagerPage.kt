package com.snapnet.ui.page.files

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.documentfile.provider.DocumentFile
import com.snapnet.App
import com.hoodmoshla.nazelha.R
import com.snapnet.ui.component.BackButton
import com.snapnet.util.FileUtil
import com.snapnet.util.PreferenceUtil.getString
import com.snapnet.util.SDCARD_URI
import java.io.File
import java.text.Collator
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_SCANNED_FILES = 10_000
private const val MAX_SCANNED_ENTRIES = 20_000

private enum class FileListFilter(val category: FileManagerCategory?) {
    ALL(null),
    VIDEO(FileManagerCategory.VIDEO),
    AUDIO(FileManagerCategory.AUDIO),
    IMAGE(FileManagerCategory.IMAGE),
    DOCUMENT(FileManagerCategory.DOCUMENT),
    ARCHIVE(FileManagerCategory.ARCHIVE),
    OTHER(FileManagerCategory.OTHER),
}

private enum class FileListSort { NEWEST, NAME, SIZE }

private data class ManagedDownloadFile(
    val key: String,
    val name: String,
    val path: String,
    val location: String,
    val size: Long,
    val modified: Long,
    val mimeType: String?,
    val file: File? = null,
    val document: DocumentFile? = null,
) {
    val category: FileManagerCategory get() = FileManagerClassifier.classify(name, mimeType)
}

private data class FileManagerSnapshot(
    val files: List<ManagedDownloadFile>,
    val needsStorageAccess: Boolean,
    val freeSpaceBytes: Long?,
    val truncated: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerPage(
    onNavigateBack: () -> Unit,
    onOpenDirectorySettings: () -> Unit,
    showTopBar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var files by remember { mutableStateOf(emptyList<ManagedDownloadFile>()) }
    var isLoading by remember { mutableStateOf(false) }
    var needsStorageAccess by remember { mutableStateOf(false) }
    var freeSpaceBytes by remember { mutableStateOf<Long?>(null) }
    var scanTruncated by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanGeneration by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(FileListFilter.ALL) }
    var selectedSort by remember { mutableStateOf(FileListSort.NEWEST) }
    var isGridView by rememberSaveable { mutableStateOf(false) }
    var selectedFileKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var renameTarget by remember { mutableStateOf<ManagedDownloadFile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTargets by remember { mutableStateOf<List<ManagedDownloadFile>?>(null) }

    fun refreshFiles() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        scanJob = scope.launch {
            isLoading = true
            try {
                val snapshot = withContext(Dispatchers.IO) { scanDownloadFiles(context) }
                if (generation == scanGeneration) {
                    files = snapshot.files
                    selectedFileKeys = selectedFileKeys intersect snapshot.files.mapTo(mutableSetOf()) { it.key }
                    needsStorageAccess = snapshot.needsStorageAccess
                    freeSpaceBytes = snapshot.freeSpaceBytes
                    scanTruncated = snapshot.truncated
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                snackbarHostState.showSnackbar(context.getString(R.string.file_manager_error))
            } finally {
                if (generation == scanGeneration) isLoading = false
            }
        }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshFiles() }

    LaunchedEffect(Unit) { refreshFiles() }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshFiles()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var visibleFiles by remember { mutableStateOf(emptyList<ManagedDownloadFile>()) }
    LaunchedEffect(files, searchText, selectedFilter, selectedSort) {
        val source = files
        val query = searchText.trim()
        val filter = selectedFilter
        val sort = selectedSort
        visibleFiles = withContext(Dispatchers.Default) {
            val collator = Collator.getInstance(Locale.getDefault())
            source.asSequence()
                .filter { item ->
                    (filter.category == null || item.category == filter.category) &&
                        (query.isBlank() || item.name.contains(query, ignoreCase = true) ||
                            item.path.contains(query, ignoreCase = true))
                }
                .sortedWith(
                    when (sort) {
                        FileListSort.NEWEST -> compareByDescending<ManagedDownloadFile> { it.modified }
                        FileListSort.NAME -> Comparator { left, right -> collator.compare(left.name, right.name) }
                        FileListSort.SIZE -> compareByDescending { it.size }
                    }
                )
                .toList()
        }
    }

    val selectedFiles = files.filter { it.key in selectedFileKeys }

    fun toggleSelection(item: ManagedDownloadFile) {
        selectedFileKeys = toggleFileSelection(selectedFileKeys, item.key)
    }

    fun openManagedFile(item: ManagedDownloadFile) {
        runCatching {
            val intent = FileUtil.createIntentForOpeningFile(item.path)
                ?: error(context.getString(R.string.file_open_error))
            context.startActivity(intent)
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.file_open_error)) }
        }
    }

    fun shareManagedFiles(items: List<ManagedDownloadFile>) {
        runCatching {
            val uris = items.mapNotNull { item ->
                FileUtil.createIntentForSharingFile(item.path)?.data
            }
            if (uris.isEmpty()) error(context.getString(R.string.file_open_error))
            val intent =
                if (uris.size == 1) {
                    FileUtil.createIntentForSharingFile(items.first().path)
                        ?: error(context.getString(R.string.file_open_error))
                } else {
                    val mimeTypes = uris.mapNotNull { context.contentResolver.getType(it) }.distinct()
                    val clip = ClipData.newUri(context.contentResolver, context.getString(R.string.file_action_share), uris.first())
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = mimeTypes.singleOrNull() ?: "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        clipData = clip
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.file_action_share)))
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.file_open_error)) }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.file_manager)) },
                    navigationIcon = { BackButton(onNavigateBack) },
                    actions = {
                        IconButton(onClick = onOpenDirectorySettings) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.download_directory))
                        }
                        IconButton(onClick = { refreshFiles() }, enabled = !isLoading) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    },
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        val header: @Composable () -> Unit = {
            FileManagerHeader(
                freeSpaceBytes = freeSpaceBytes,
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                selectedFilter = selectedFilter,
                onFilterSelected = { selectedFilter = it },
                selectedSort = selectedSort,
                onSortSelected = { selectedSort = it },
                isGridView = isGridView,
                onToggleView = { isGridView = !isGridView },
                onOpenDirectorySettings = onOpenDirectorySettings,
                onRefresh = { refreshFiles() },
                isLoading = isLoading,
                scanTruncated = scanTruncated,
                needsStorageAccess = needsStorageAccess,
                onRequestStorageAccess = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) openAllFilesAccessSettings(context)
                    else legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                },
                empty = !isLoading && visibleFiles.isEmpty(),
                selectedCount = selectedFiles.size,
                allVisibleSelected = visibleFiles.isNotEmpty() && visibleFiles.all { it.key in selectedFileKeys },
                onSelectAllVisible = {
                    selectedFileKeys = selectVisibleFiles(selectedFileKeys, visibleFiles.mapTo(mutableSetOf()) { it.key }, selected = true)
                },
                onClearSelection = { selectedFileKeys = emptySet() },
                onOpenSelected = { selectedFiles.singleOrNull()?.let(::openManagedFile) },
                onShareSelected = { shareManagedFiles(selectedFiles) },
                onRenameSelected = {
                    selectedFiles.singleOrNull()?.let { target ->
                        renameTarget = target
                        renameText = target.name
                    }
                },
                onDeleteSelected = { if (selectedFiles.isNotEmpty()) deleteTargets = selectedFiles },
            )
        }
        if (isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 176.dp),
                modifier = contentModifier,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { header() }
                items(visibleFiles, key = { it.key }) { item ->
                    ManagedFileCard(
                        modifier = Modifier,
                        item = item,
                        selected = item.key in selectedFileKeys,
                        selectionMode = selectedFileKeys.isNotEmpty(),
                        compact = true,
                        onToggleSelection = { toggleSelection(item) },
                        onOpen = { if (selectedFileKeys.isNotEmpty()) toggleSelection(item) else openManagedFile(item) },
                        onShare = { shareManagedFiles(listOf(item)) },
                        onRename = { renameTarget = item; renameText = item.name },
                        onDelete = { deleteTargets = listOf(item) },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = contentModifier,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { header() }
                items(visibleFiles, key = { it.key }) { item ->
                    ManagedFileCard(
                        modifier = Modifier,
                        item = item,
                        selected = item.key in selectedFileKeys,
                        selectionMode = selectedFileKeys.isNotEmpty(),
                        compact = false,
                        onToggleSelection = { toggleSelection(item) },
                        onOpen = { if (selectedFileKeys.isNotEmpty()) toggleSelection(item) else openManagedFile(item) },
                        onShare = { shareManagedFiles(listOf(item)) },
                        onRename = { renameTarget = item; renameText = item.name },
                        onDelete = { deleteTargets = listOf(item) },
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.file_rename_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_manager_location, target.location))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.file_name)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameText.trim()
                        scope.launch {
                            val renamed =
                                withContext(Dispatchers.IO) {
                                    runCatching { if (
                                        newName.isBlank() || newName in setOf(".", "..") ||
                                            newName.any { it == '/' || it == '\\' || it == '\u0000' }
                                    ) false
                                    else if (target.file != null) {
                                        val destination = File(target.file.parentFile, newName)
                                        destination.absolutePath == target.file.absolutePath ||
                                            (!destination.exists() && target.file.renameTo(destination))
                                    } else {
                                        val document = target.document
                                        val existing = document?.parentFile?.findFile(newName)
                                        if (existing != null && existing.uri != document?.uri) false
                                        else document?.renameTo(newName) == true
                                    } }.getOrDefault(false)
                                }
                            renameTarget = null
                            if (renamed) refreshFiles()
                            else snackbarHostState.showSnackbar(context.getString(R.string.file_operation_error))
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    deleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text(stringResource(R.string.file_delete_title)) },
            text = {
                Column {
                    if (targets.size == 1) {
                        Text(stringResource(R.string.file_delete_confirmation, targets.single().name))
                        Text(
                            stringResource(R.string.file_manager_location, targets.single().location),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Text(stringResource(R.string.file_delete_multiple_confirmation, targets.size))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val results =
                                withContext(Dispatchers.IO) {
                                    targets.map { target ->
                                        target.key to runCatching {
                                            target.file?.delete() ?: (target.document?.delete() == true)
                                        }.getOrDefault(false)
                                    }
                                }
                            deleteTargets = null
                            selectedFileKeys = selectedFileKeys - results.filter { it.second }.map { it.first }.toSet()
                            if (results.any { it.second }) refreshFiles()
                            if (results.any { !it.second }) {
                                snackbarHostState.showSnackbar(context.getString(R.string.file_operation_error))
                            }
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun FileManagerHeader(
    freeSpaceBytes: Long?,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    selectedFilter: FileListFilter,
    onFilterSelected: (FileListFilter) -> Unit,
    selectedSort: FileListSort,
    onSortSelected: (FileListSort) -> Unit,
    isGridView: Boolean,
    onToggleView: () -> Unit,
    onOpenDirectorySettings: () -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    scanTruncated: Boolean,
    needsStorageAccess: Boolean,
    onRequestStorageAccess: () -> Unit,
    empty: Boolean,
    selectedCount: Int,
    allVisibleSelected: Boolean,
    onSelectAllVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onRenameSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.file_manager), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onOpenDirectorySettings) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.download_directory))
            }
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
            }
            IconToggleButton(checked = isGridView, onCheckedChange = { onToggleView() }) {
                Icon(
                    imageVector = if (isGridView) Icons.AutoMirrored.Outlined.List else Icons.Outlined.GridView,
                    contentDescription = stringResource(if (isGridView) R.string.file_list_view else R.string.file_grid_view),
                )
            }
        }
        if (freeSpaceBytes != null) {
            Text(
                text = stringResource(R.string.file_manager_free_space, formatFileSize(freeSpaceBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = searchText,
            onValueChange = onSearchTextChange,
            label = { Text(stringResource(R.string.search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FileListFilter.values().forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onFilterSelected(filter) },
                    label = { Text(stringResource(filter.labelResource())) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FileListSort.values().forEach { sort ->
                FilterChip(
                    selected = selectedSort == sort,
                    onClick = { onSortSelected(sort) },
                    label = { Text(stringResource(sort.labelResource())) },
                )
            }
        }
        if (selectedCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.file_selected_count, selectedCount), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = if (allVisibleSelected) onClearSelection else onSelectAllVisible) {
                    Text(stringResource(if (allVisibleSelected) R.string.file_clear_selection else R.string.file_select_all))
                }
                if (selectedCount == 1) {
                    IconButton(onClick = onOpenSelected) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.file_action_open))
                    }
                    IconButton(onClick = onRenameSelected) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.file_action_rename))
                    }
                }
                IconButton(onClick = onShareSelected) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.file_share_selected))
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.file_delete_selected))
                }
            }
            if (selectedCount > 1) {
                Text(
                    stringResource(R.string.file_selected_rename_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (scanTruncated) {
            Text(
                pluralStringResource(R.plurals.file_manager_scan_limited, MAX_SCANNED_FILES, MAX_SCANNED_FILES),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (needsStorageAccess) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.file_manager_permission_needed), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.file_manager_permission_desc),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRequestStorageAccess) {
                        Text(stringResource(R.string.file_manager_permission_button))
                    }
                }
            }
        }
        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        if (empty) {
            Text(
                stringResource(R.string.file_manager_empty),
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ManagedFileCard(
    modifier: Modifier,
    item: ManagedDownloadFile,
    selected: Boolean,
    selectionMode: Boolean,
    compact: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier =
            modifier.fillMaxWidth().combinedClickable(
                onClick = onOpen,
                onLongClick = onToggleSelection,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.semantics { contentDescription = item.name },
                )
            }
            Text(
                text = stringResource(R.string.file_status_complete),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${stringResource(item.category.labelResource())} · ${formatFileSize(item.size)} · ${formatModified(item.modified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    text = stringResource(R.string.file_manager_location, item.location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selectionMode && !compact) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onOpen) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.file_action_open))
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.file_action_share))
                    }
                    IconButton(onClick = onRename) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.file_action_rename))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.file_action_delete))
                    }
                }
            }
        }
    }
}

private fun FileListFilter.labelResource(): Int =
    when (this) {
        FileListFilter.ALL -> R.string.file_filter_all
        FileListFilter.VIDEO -> R.string.file_type_video
        FileListFilter.AUDIO -> R.string.file_type_audio
        FileListFilter.IMAGE -> R.string.file_type_image
        FileListFilter.DOCUMENT -> R.string.file_type_document
        FileListFilter.ARCHIVE -> R.string.file_type_archive
        FileListFilter.OTHER -> R.string.file_type_other
    }

private fun FileListSort.labelResource(): Int =
    when (this) {
        FileListSort.NEWEST -> R.string.file_sort_newest
        FileListSort.NAME -> R.string.file_sort_name
        FileListSort.SIZE -> R.string.file_sort_size
    }

private fun FileManagerCategory.labelResource(): Int =
    when (this) {
        FileManagerCategory.VIDEO -> R.string.file_type_video
        FileManagerCategory.AUDIO -> R.string.file_type_audio
        FileManagerCategory.IMAGE -> R.string.file_type_image
        FileManagerCategory.DOCUMENT -> R.string.file_type_document
        FileManagerCategory.ARCHIVE -> R.string.file_type_archive
        FileManagerCategory.OTHER -> R.string.file_type_other
    }

private suspend fun scanDownloadFiles(context: Context): FileManagerSnapshot {
    val files = mutableListOf<ManagedDownloadFile>()
    val seen = hashSetOf<String>()
    val visitedDirectories = hashSetOf<String>()
    val scanContext = currentCoroutineContext()
    var scannedEntries = 0
    val hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    var freeSpace: Long? = null
    var truncated = false

    fun addFile(file: File, root: File) {
        if (!file.isFile || file.name.startsWith('.') || file.name.endsWith(".partial") || file.name.endsWith(".part")) return
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (!seen.add(path)) return
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
        files +=
            ManagedDownloadFile(
                key = path,
                name = file.name,
                path = path,
                location = file.parentFile?.absolutePath ?: root.absolutePath,
                size = file.length(),
                modified = file.lastModified(),
                mimeType = mime,
                file = file,
            )
    }

    if (hasStorageAccess) {
        val roots =
            listOf(App.videoDownloadDir, App.audioDownloadDir, App.privateDownloadDir)
                .map(::File)
                .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        rootsLoop@ for (root in roots) {
            if (!root.isDirectory) continue
            if (freeSpace == null) freeSpace = runCatching { StatFs(root.absolutePath).availableBytes }.getOrNull()
            val rootPath = root.canonicalPath
            val walk = root.walkTopDown()
                .onEnter { directory ->
                    scanContext.ensureActive()
                    val canonical = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
                    (canonical == rootPath || canonical.startsWith(rootPath + File.separator)) &&
                        (directory == root || (!directory.name.startsWith('.') && directory.name != "tmp")) &&
                        visitedDirectories.add(canonical)
                }
            for (file in walk) {
                scanContext.ensureActive()
                if (files.size >= MAX_SCANNED_FILES || ++scannedEntries > MAX_SCANNED_ENTRIES) {
                    truncated = true
                    break@rootsLoop
                }
                addFile(file, root)
            }
        }
    }

    val treeUri = SDCARD_URI.getString().takeIf(String::isNotBlank)?.let(Uri::parse)
    treeUri?.let { uri ->
        DocumentFile.fromTreeUri(context, uri)?.let { root ->
            // The writer saves directly into this selected destination. Do not recursively expose
            // pre-existing nested folders as downloadable files with destructive actions.
            for (child in root.listFiles()) {
                scanContext.ensureActive()
                if (files.size >= MAX_SCANNED_FILES || ++scannedEntries > MAX_SCANNED_ENTRIES) {
                    truncated = true
                    break
                }
                if (child.isFile && !child.name.orEmpty().startsWith('.') &&
                    !child.name.orEmpty().endsWith(".part") && !child.name.orEmpty().endsWith(".partial")) {
                        val path = child.uri.toString()
                        if (seen.add(path)) {
                            files +=
                                ManagedDownloadFile(
                                    key = path,
                                    name = child.name ?: context.getString(R.string.file_name),
                                    path = path,
                                    location = child.parentFile?.uri?.toString() ?: root.uri.toString(),
                                    size = child.length(),
                                    modified = child.lastModified(),
                                    mimeType = child.type,
                                    document = child,
                                )
                        }
                }
            }
        }
    }

    return FileManagerSnapshot(
        files = files,
        needsStorageAccess = !hasStorageAccess,
        freeSpaceBytes = freeSpace,
        truncated = truncated,
    )
}

private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val uri = Uri.parse("package:${context.packageName}")
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
    runCatching { context.startActivity(intent) }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private fun formatModified(timestamp: Long): String =
    if (timestamp > 0L) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(timestamp))
    else "—"

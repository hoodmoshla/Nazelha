package com.snapnet.ui.page.files

import android.Manifest
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.documentfile.provider.DocumentFile
import com.snapnet.App
import com.snapnet.R
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
    var renameTarget by remember { mutableStateOf<ManagedDownloadFile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ManagedDownloadFile?>(null) }

    fun refreshFiles() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        scanJob = scope.launch {
            isLoading = true
            try {
                val snapshot = withContext(Dispatchers.IO) { scanDownloadFiles(context) }
                if (generation == scanGeneration) {
                    files = snapshot.files
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

    Scaffold(
        topBar = {
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (freeSpaceBytes != null) {
                        Text(
                            text = stringResource(R.string.file_manager_free_space, formatFileSize(freeSpaceBytes!!)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        value = searchText,
                        onValueChange = { searchText = it },
                        label = { Text(stringResource(R.string.search)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileListFilter.values().forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(stringResource(filter.labelResource())) },
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileListSort.values().forEach { sort ->
                        FilterChip(
                            selected = selectedSort == sort,
                            onClick = { selectedSort = sort },
                            label = { Text(stringResource(sort.labelResource())) },
                        )
                    }
                }
            }
            if (scanTruncated) {
                item {
                    Text(
                        stringResource(R.string.file_manager_scan_limited, MAX_SCANNED_FILES),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (needsStorageAccess) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.file_manager_permission_needed), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.file_manager_permission_desc),
                                modifier = Modifier.padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    openAllFilesAccessSettings(context)
                                } else {
                                    legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                            }) {
                                Text(stringResource(R.string.file_manager_permission_button))
                            }
                        }
                    }
                }
            }
            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
            if (!isLoading && visibleFiles.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.file_manager_empty),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visibleFiles, key = { it.key }) { item ->
                ManagedFileCard(
                    item = item,
                    onOpen = {
                        runCatching {
                            val intent = FileUtil.createIntentForOpeningFile(item.path)
                                ?: error(context.getString(R.string.file_open_error))
                            context.startActivity(intent)
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.file_open_error)) }
                        }
                    },
                    onShare = {
                        runCatching {
                            val intent = FileUtil.createIntentForSharingFile(item.path)
                                ?: error(context.getString(R.string.file_open_error))
                            context.startActivity(Intent.createChooser(intent, item.name))
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.file_open_error)) }
                        }
                    },
                    onRename = {
                        renameTarget = item
                        renameText = item.name
                    },
                    onDelete = { deleteTarget = item },
                )
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

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.file_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_delete_confirmation, target.name))
                    Text(stringResource(R.string.file_manager_location, target.location), modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val deleted =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        target.file?.delete() ?: (target.document?.delete() == true)
                                    }.getOrDefault(false)
                                }
                            deleteTarget = null
                            if (deleted) refreshFiles()
                            else snackbarHostState.showSnackbar(context.getString(R.string.file_operation_error))
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ManagedFileCard(
    item: ManagedDownloadFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(R.string.file_status_complete),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${stringResource(item.category.labelResource())} · ${formatFileSize(item.size)} · ${formatModified(item.modified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.file_manager_location, item.location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

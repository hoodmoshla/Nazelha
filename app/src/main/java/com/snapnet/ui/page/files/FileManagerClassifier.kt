package com.snapnet.ui.page.files

internal enum class FileManagerCategory {
    VIDEO,
    AUDIO,
    IMAGE,
    DOCUMENT,
    ARCHIVE,
    OTHER,
}

internal object FileManagerClassifier {
    private val videoExtensions = setOf("3gp", "avi", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")
    private val audioExtensions = setOf("aac", "flac", "m4a", "mp3", "ogg", "opus", "wav", "wma")
    private val imageExtensions = setOf("bmp", "gif", "heic", "jpeg", "jpg", "png", "svg", "webp")
    private val documentExtensions =
        setOf("csv", "doc", "docx", "epub", "html", "json", "md", "pdf", "ppt", "pptx", "rtf", "txt", "xls", "xlsx")
    private val archiveExtensions = setOf("7z", "bz2", "gz", "rar", "tar", "tgz", "xz", "zip")

    fun classify(name: String, mimeType: String? = null): FileManagerCategory {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension in videoExtensions || mimeType?.startsWith("video/") == true ->
                FileManagerCategory.VIDEO
            extension in audioExtensions || mimeType?.startsWith("audio/") == true ->
                FileManagerCategory.AUDIO
            extension in imageExtensions || mimeType?.startsWith("image/") == true ->
                FileManagerCategory.IMAGE
            extension in archiveExtensions -> FileManagerCategory.ARCHIVE
            extension in documentExtensions || mimeType == "application/pdf" ->
                FileManagerCategory.DOCUMENT
            else -> FileManagerCategory.OTHER
        }
    }
}

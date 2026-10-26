package com.snapnet.download.http

import kotlinx.serialization.Serializable

/** Metadata resolved from a public HTTP(S) file link. No cookies or credentials are stored here. */
@Serializable
data class DirectHttpFile(
    val downloadUrl: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long? = null,
    val supportsRanges: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
)

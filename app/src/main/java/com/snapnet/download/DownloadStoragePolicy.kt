package com.snapnet.download

/** Best-effort preflight; unknown response sizes still rely on filesystem write errors. */
internal object DownloadStoragePolicy {
    fun canFit(availableBytes: Long, totalBytes: Long?, existingBytes: Long = 0L): Boolean {
        if (totalBytes == null || totalBytes < 0L || availableBytes < 0L) return true
        val remaining = (totalBytes - existingBytes.coerceIn(0L, totalBytes)).coerceAtLeast(0L)
        return remaining <= availableBytes
    }
}

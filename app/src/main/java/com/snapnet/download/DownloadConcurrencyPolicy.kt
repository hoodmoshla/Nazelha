package com.snapnet.download

/** Keeps user-configured parallel work within predictable mobile-device bounds. */
internal object DownloadConcurrencyPolicy {
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 5
    const val DEFAULT_LIMIT = 3

    fun normalizedLimit(configuredLimit: Int): Int =
        configuredLimit.coerceIn(MIN_LIMIT, MAX_LIMIT)

    fun mayStartAnother(runningTasks: Int, configuredLimit: Int): Boolean =
        runningTasks.coerceAtLeast(0) < normalizedLimit(configuredLimit)
}

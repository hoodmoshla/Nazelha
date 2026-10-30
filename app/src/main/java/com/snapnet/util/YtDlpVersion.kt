package com.snapnet.util

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * Reads the version of the yt-dlp build that the app will actually execute and answers whether that
 * build is new enough for the features the app relies on.
 *
 * Two copies of the engine can exist on a device:
 *
 *  * the one bundled in `youtubedl-android`, inside the APK (`res/raw/ytdlp`);
 *  * the one installed by a runtime update, under `noBackupFilesDir/youtubedl-android/yt-dlp/yt-dlp`.
 *
 * The installed copy wins when present, because that is the process yt-dlp will actually run. Both
 * are Python zipapps, so `yt_dlp/version.py` can be read without executing the engine. Official
 * standalone zipapps may start with a Python shebang before the ZIP local-file header.
 */
object YtDlpVersion {

    private const val TAG = "YtDlpVersion"
    private const val VERSION_ENTRY = "yt_dlp/version.py"
    private const val MAX_ZIP_PREFIX_BYTES = 4096
    private const val MAX_VERSION_SOURCE_BYTES = 256 * 1024
    private const val MILLIS_PER_DAY = 86_400_000L

    private val VERSION_PATTERN = Regex("""__version__\s*=\s*'([^']+)'""")
    private val VERSION_DATE_PATTERN = Regex("""^(\d{4})\.(\d{1,2})\.(\d{1,2})""")

    /** The oldest engine that understands `--js-runtimes`. */
    const val MIN_VERSION_FOR_JS_RUNTIMES = "2025.01.01"

    private fun installedBinary(context: Context): File =
        File(File(File(context.noBackupFilesDir, "youtubedl-android"), "yt-dlp"), "yt-dlp")

    private fun zipStartOffset(prefix: ByteArray, length: Int): Int {
        for (offset in 0..(length - 4)) {
            if (
                prefix[offset] == 0x50.toByte() &&
                    prefix[offset + 1] == 0x4b.toByte() &&
                    prefix[offset + 2] == 0x03.toByte() &&
                    prefix[offset + 3] == 0x04.toByte()
            ) {
                return offset
            }
        }
        return -1
    }

    /** Opens a ZIP stream at its first local-file header, ignoring a short shebang/preamble. */
    private fun openZipStream(input: InputStream): ZipInputStream? {
        val prefix = ByteArray(MAX_ZIP_PREFIX_BYTES)
        var bytesRead = 0
        var startOffset = -1

        while (bytesRead < prefix.size && startOffset < 0) {
            val count = input.read(prefix, bytesRead, prefix.size - bytesRead)
            if (count <= 0) break
            bytesRead += count
            startOffset = zipStartOffset(prefix, bytesRead)
        }

        if (startOffset < 0) {
            input.close()
            return null
        }

        val pushback = PushbackInputStream(input, bytesRead - startOffset)
        pushback.unread(prefix, startOffset, bytesRead - startOffset)
        return ZipInputStream(pushback)
    }

    private fun readVersionSource(zip: ZipInputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var totalBytes = 0

        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            totalBytes += count
            if (totalBytes > MAX_VERSION_SOURCE_BYTES) return null
            output.write(buffer, 0, count)
        }

        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun readVersion(input: InputStream): String? {
        val zip = openZipStream(input) ?: return null
        return zip.use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.name == VERSION_ENTRY) {
                    val source = readVersionSource(archive) ?: return@use null
                    return@use VERSION_PATTERN.find(source)?.groupValues?.get(1)
                }
                entry = archive.nextEntry
            }
            null
        }
    }

    /** Reads a version from a runtime-installed or test zipapp file. */
    internal fun readVersionFromBinary(binary: File): String? {
        if (!binary.isFile) return null
        return binary.inputStream().use { input -> readVersion(input) }
    }

    /** Version of the runtime-installed engine, or null when no update has been installed. */
    fun resolveInstalledVersion(context: Context): String? =
        runCatching { readVersionFromBinary(installedBinary(context)) }
            .onFailure { Log.w(TAG, "could not read the installed yt-dlp version", it) }
            .getOrNull()

    /** Version of the engine bundled inside the APK. */
    fun resolveBundledVersion(context: Context): String? =
        runCatching {
                context.resources
                    .openRawResource(com.yausername.youtubedl_android.R.raw.ytdlp)
                    .use { input -> readVersion(input) }
            }
            .onFailure { Log.w(TAG, "could not read the bundled yt-dlp version", it) }
            .getOrNull()

    /** The version that will actually run: installed engine first, otherwise the bundled copy. */
    fun resolveEffectiveVersion(context: Context): String? =
        resolveInstalledVersion(context) ?: resolveBundledVersion(context)

    /** Exact file that youtubedl-android 0.18.1 passes to Python. */
    fun effectiveBinaryPath(context: Context): String = installedBinary(context).absolutePath

    /** yt-dlp versions are date based (`YYYY.MM.DD`), so a lexicographic compare is correct. */
    fun compare(a: String?, b: String?): Int {
        if (a == null) return -1
        if (b == null) return 1
        return a.trim().compareTo(b.trim())
    }

    /** @return true when [version] understands `--js-runtimes`. */
    fun supportsJsRuntimes(version: String?): Boolean =
        compare(version, MIN_VERSION_FOR_JS_RUNTIMES) >= 0

    /**
     * Returns true when a date-based engine version is more than [maxAgeDays] old. Unknown or
     * malformed versions are not considered stale; callers separately treat unknown versions as
     * incompatible with features the app requires.
     */
    fun isOlderThanDays(
        version: String?,
        maxAgeDays: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        require(maxAgeDays >= 0) { "maxAgeDays must be non-negative" }
        val match = version?.let { VERSION_DATE_PATTERN.find(it) } ?: return false
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val releaseMillis =
            runCatching {
                    GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                        isLenient = false
                        clear()
                        set(year, month - 1, day, 0, 0, 0)
                    }.timeInMillis
                }
                .getOrNull() ?: return false

        return nowMillis - releaseMillis > maxAgeDays * MILLIS_PER_DAY
    }

    /** Convenience: does the engine that will run support `--js-runtimes`? */
    fun effectiveEngineSupportsJsRuntimes(context: Context): Boolean =
        runCatching { supportsJsRuntimes(resolveEffectiveVersion(context)) }.getOrDefault(false)
}

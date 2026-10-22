package com.snapnet.util

import kotlin.math.roundToInt

/** The real, downloadable choices shown by the format picker. */
data class FormatPickerOptions(val music: List<Format>, val video: List<Format>)

/**
 * Builds user-facing choices from yt-dlp's actual formats. Video-only streams are offered only when
 * their reported resolution is known and an actual audio-only stream can be paired with them. The
 * generated format id is yt-dlp's real `videoId+audioId` selector, not a fabricated format.
 */
fun formatPickerOptions(
    formats: List<Format>,
    durationSeconds: Double? = null,
): FormatPickerOptions {
    val audioFormats =
        formats
            .asSequence()
            .filter { it.containsAudio() && !it.containsVideo() && it.formatId.isUsableFormatId() }
            .distinctBy { it.selectionIdKey() }
            .sortedWith(audioQualityComparator)
            .toList()

    // Keep the real audio choices; the UI shows the best one first and reveals the rest on demand.
    val music = audioFormats
    val videoChoices =
        formats.mapNotNull { format ->
            when {
                !format.containsVideo() || !format.formatId.isUsableFormatId() -> null
                format.containsAudio() -> format
                else -> {
                    val audio =
                        audioFormats.maxWithOrNull(
                            compareBy<Format> { audioContainerCompatibility(format, it) }
                                .thenBy { it.abr ?: it.tbr ?: 0.0 }
                                .thenBy { it.fileSize ?: it.fileSizeApprox ?: 0.0 }
                        )
                    audio?.let { format.pairedWith(it, durationSeconds) }
                }
            }
        }

    val video =
        videoChoices
            .asSequence()
            // Preserve distinct streams at a shared resolution; their codecs, containers, bitrate,
            // format IDs, and sizes can differ materially.
            .distinctBy { it.selectionIdKey() }
            .sortedWith(videoQualityComparator)
            .toList()

    return FormatPickerOptions(music = music, video = video)
}

/** One best real audio format for the compact initial view. */
fun initialAudioFormatChoices(formats: List<Format>): List<Format> = formats.take(1)

/** Common real video qualities shown directly, from lowest to highest. */
val COMPACT_VIDEO_QUALITY_LEVELS = listOf(360, 480, 720, 1080)

/** One best real video-with-audio format for each common quality in the compact initial view. */
fun initialVideoFormatChoices(formats: List<Format>): List<Format> =
    COMPACT_VIDEO_QUALITY_LEVELS.mapNotNull { quality ->
        formats.firstOrNull { it.videoQualityLevel() == quality }
    }

/** Real formats above the compact view, kept available without inventing a quality. */
fun advancedVideoFormatChoices(formats: List<Format>): List<Format> {
    val compactIds = initialVideoFormatChoices(formats).mapNotNull { it.formatId }.toSet()
    return formats.filter { it.formatId !in compactIds }
}

/** A title based only on resolution reported by yt-dlp; returns null rather than inventing one. */
fun Format.videoQualityLabel(): String? {
    val width = videoQualityWidth()
    val height = videoQualityHeight()
    val quality = videoQualityLevel() ?: return null
    return if (width != null && height != null) "$width×$height (${quality}p)" else "${quality}p"
}

/** Resolution tier uses the shorter reported side so portrait video is labeled as expected. */
fun Format.videoQualityLevel(): Int? {
    if (!containsVideo()) return null
    val width = videoQualityWidth()
    val height = videoQualityHeight()
    return if (width != null && height != null) minOf(width, height) else width ?: height
}

/** Resolve height from structured fields or explicit dimensions/`720p` text in yt-dlp output. */
fun Format.videoQualityHeight(): Int? {
    if (!containsVideo()) return null
    return height.toPositiveInt()
        ?: reportedDimensions()?.second
        ?: formatNote?.let { PIXEL_HEIGHT.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
}

private fun Format.videoQualityWidth(): Int? = width.toPositiveInt() ?: reportedDimensions()?.first

private fun Format.reportedDimensions(): Pair<Int, Int>? {
    val match =
        sequenceOf(resolution, format)
            .filterNotNull()
            .mapNotNull { DIMENSIONS.find(it) }
            .firstOrNull() ?: return null
    val width = match.groupValues[1].toIntOrNull() ?: return null
    val height = match.groupValues[2].toIntOrNull() ?: return null
    return width to height
}

private fun Double?.toPositiveInt(): Int? = this?.takeIf { it.isFinite() && it > 0.0 }?.roundToInt()

private fun String?.isUsableFormatId(): Boolean = !isNullOrBlank()

private fun Format.selectionIdKey(): String? = canonicalFormatId(formatId?.split('+').orEmpty())

private fun canonicalFormatId(ids: Iterable<String>): String? {
    val parts =
        ids.flatMap { it.split('+') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
    return parts.takeIf { it.isNotEmpty() }?.joinToString("+")
}

private fun Format.pairedWith(
    audio: Format,
    @Suppress("UNUSED_PARAMETER") durationSeconds: Double?,
): Format {
    val videoId = requireNotNull(formatId).trim()
    val audioId = requireNotNull(audio.formatId).trim()
    val videoBitrate = vbr ?: tbr
    val audioBitrate = audio.abr ?: audio.tbr
    val combinedSizes = originalCombinedFileSizes(audio)
    val resolvedWidth = width ?: videoQualityWidth()?.toDouble()
    val resolvedHeight = height ?: videoQualityHeight()?.toDouble()

    return Format(
        formatId = "$videoId+$audioId",
        // Leave raw yt-dlp descriptions out of the visible title; the UI uses the real resolution.
        ext = null,
        acodec = audio.acodec,
        vcodec = vcodec,
        width = resolvedWidth,
        height = resolvedHeight,
        fps = fps,
        audioExt = audio.audioExt ?: audio.ext,
        videoExt = videoExt ?: ext,
        resolution =
            resolution
                ?: reportedDimensions()?.let { "${it.first}x${it.second}" }
                ?: if (resolvedWidth != null && resolvedHeight != null) {
                    "${resolvedWidth.roundToInt()}x${resolvedHeight.roundToInt()}"
                } else null,
        vbr = videoBitrate,
        abr = audioBitrate,
        tbr =
            if (videoBitrate != null && audioBitrate != null) videoBitrate + audioBitrate else null,
        fileSize = combinedSizes.first,
        fileSizeApprox = combinedSizes.second,
    )
}

/**
 * Adds only sizes reported by the source. An approximate source size stays approximate, and a
 * missing size stays missing; bitrate estimates are not presented as a download size.
 */
private fun Format.originalCombinedFileSizes(audio: Format): Pair<Double?, Double?> {
    val exact = listOfNotNull(fileSize, audio.fileSize)
    if (fileSize != null && audio.fileSize != null) return (exact.sum()) to null

    val known = listOfNotNull(fileSize ?: fileSizeApprox, audio.fileSize ?: audio.fileSizeApprox)
    return if (known.size == 2) null to known.sum() else null to null
}

private fun audioContainerCompatibility(video: Format, audio: Format): Int {
    val videoExt = (video.videoExt ?: video.ext)?.lowercase()?.removePrefix(".") ?: return 0
    val audioExt = (audio.audioExt ?: audio.ext)?.lowercase()?.removePrefix(".") ?: return 0
    if (videoExt == audioExt) return 3

    val mp4Family = setOf("mp4", "m4a", "m4v", "mov")
    return if (videoExt in mp4Family && audioExt in mp4Family) 2 else 0
}

private val audioQualityComparator =
    compareByDescending<Format> { it.abr ?: it.tbr ?: 0.0 }
        .thenByDescending { it.fileSize ?: it.fileSizeApprox ?: 0.0 }

private val videoQualityComparator =
    compareByDescending<Format> { it.videoQualityLevel() ?: 0 }
        .thenByDescending { it.videoQualityWidth() ?: 0 }
        .thenByDescending { it.fps ?: 0.0 }
        .thenByDescending { it.tbr ?: it.vbr ?: 0.0 }

private val DIMENSIONS = Regex("(?<!\\d)(\\d{2,5})\\s*[x×]\\s*(\\d{2,5})(?!\\d)")
private val PIXEL_HEIGHT = Regex("(?<!\\d)(\\d{3,4})\\s*p\\b", RegexOption.IGNORE_CASE)

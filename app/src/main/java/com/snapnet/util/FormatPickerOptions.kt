package com.snapnet.util

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The real, downloadable choices shown by the format picker. */
data class FormatPickerOptions(
    val music: List<Format>,
    val video: List<Format>,
)

/**
 * Builds user-facing choices from yt-dlp's actual formats. Video-only streams are offered only
 * when their reported resolution is known and an actual audio-only stream can be paired with them.
 * The generated format id is yt-dlp's real `videoId+audioId` selector, not a fabricated format.
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

    // A single best standalone audio choice keeps the picker compact and easy to scan.
    val music = audioFormats.take(1)
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

/** A title based only on resolution reported by yt-dlp; returns null rather than inventing one. */
fun Format.videoQualityLabel(): String? {
    val height = videoQualityHeight() ?: return null
    val width = videoQualityWidth()
    return if (width != null) "$width×$height (${height}p)" else "${height}p"
}

/** Resolve height from structured fields or explicit dimensions/`720p` text in yt-dlp output. */
fun Format.videoQualityHeight(): Int? {
    if (!containsVideo()) return null
    return height.toPositiveInt()
        ?: reportedDimensions()?.second
        ?: formatNote?.let { PIXEL_HEIGHT.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
}

private fun Format.videoQualityWidth(): Int? =
    width.toPositiveInt() ?: reportedDimensions()?.first

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

private fun Double?.toPositiveInt(): Int? =
    this?.takeIf { it.isFinite() && it > 0.0 }?.roundToInt()

private fun String?.isUsableFormatId(): Boolean = !isNullOrBlank()

private fun Format.selectionIdKey(): String? =
    canonicalFormatId(formatId?.split('+').orEmpty())

private fun canonicalFormatId(ids: Iterable<String>): String? {
    val parts = ids.flatMap { it.split('+') }.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
    return parts.takeIf { it.isNotEmpty() }?.joinToString("+")
}

private fun Format.pairedWith(audio: Format, durationSeconds: Double?): Format {
    val videoId = requireNotNull(formatId).trim()
    val audioId = requireNotNull(audio.formatId).trim()
    val videoBitrate = vbr ?: tbr
    val audioBitrate = audio.abr ?: audio.tbr
    val combinedSizes = combinedFileSizes(audio, durationSeconds)
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
        tbr = if (videoBitrate != null && audioBitrate != null) videoBitrate + audioBitrate else null,
        fileSize = combinedSizes.first,
        fileSizeApprox = combinedSizes.second,
    )
}

private fun Format.combinedFileSizes(
    audio: Format,
    durationSeconds: Double?,
): Pair<Double?, Double?> {
    if (fileSize != null && audio.fileSize != null) {
        return (fileSize + audio.fileSize) to null
    }

    val videoEstimate =
        fileSize ?:
            fileSizeApprox ?:
            estimateBytesFromBitrate(vbr ?: tbr, durationSeconds)
    val audioEstimate =
        audio.fileSize ?:
            audio.fileSizeApprox ?:
            estimateBytesFromBitrate(audio.abr ?: audio.tbr, durationSeconds)
    return if (videoEstimate != null && audioEstimate != null) {
        null to (videoEstimate + audioEstimate)
    } else {
        null to null
    }
}

/** `kbps × 125 × seconds` converts a reported bitrate to an approximate byte count. */
private fun estimateBytesFromBitrate(bitrateKbps: Double?, durationSeconds: Double?): Double? {
    val bitrate = bitrateKbps?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return (bitrate * 125.0 * duration).roundToLong().toDouble()
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
    compareByDescending<Format> { it.videoQualityHeight() ?: 0 }
        .thenByDescending { it.videoQualityWidth() ?: 0 }
        .thenByDescending { it.fps ?: 0.0 }
        .thenByDescending { it.tbr ?: it.vbr ?: 0.0 }

private val DIMENSIONS = Regex("(?<!\\d)(\\d{2,5})\\s*[x×]\\s*(\\d{2,5})(?!\\d)")
private val PIXEL_HEIGHT = Regex("(?<!\\d)(\\d{3,4})\\s*p\\b", RegexOption.IGNORE_CASE)

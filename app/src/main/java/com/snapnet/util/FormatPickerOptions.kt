package com.snapnet.util

/**
 * The formats offered by the download picker. Standalone video streams are intentionally omitted:
 * only audio-only formats and formats that already include both audio and video are user choices.
 */
data class FormatPickerOptions(
    val music: List<Format>,
    val video: List<Format>,
)

fun formatPickerOptions(formats: List<Format>): FormatPickerOptions {
    val music =
        formats
            .filter { it.containsAudio() && !it.containsVideo() }
            .sortedWith(compareByDescending<Format> { it.tbr ?: it.abr ?: 0.0 })
    val video =
        formats
            .filter { it.containsAudio() && it.containsVideo() }
            .sortedWith(
                compareByDescending<Format> {
                        it.height ?: it.resolution?.substringAfterLast('x')?.toDoubleOrNull() ?: 0.0
                    }
                    .thenByDescending { it.width ?: 0.0 }
                    .thenByDescending { it.tbr ?: it.vbr ?: 0.0 }
            )
    return FormatPickerOptions(music = music, video = video)
}

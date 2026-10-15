package com.snapnet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FormatPickerOptionsTest {
    @Test
    fun pickerKeepsAudioOnlyAndCombinedAudioVideoButHidesVideoOnly() {
        val audioOnly = Format(formatId = "audio", acodec = "opus", vcodec = "none")
        val combined =
            Format(
                formatId = "combined",
                acodec = "aac",
                vcodec = "avc1",
                height = 720.0,
                fileSize = 1_024_000.0,
            )
        val videoOnly = Format(formatId = "silent-video", acodec = "none", vcodec = "avc1")

        val options = formatPickerOptions(listOf(videoOnly, audioOnly, combined))

        assertEquals(listOf("audio"), options.music.map { it.formatId })
        assertEquals(listOf("combined"), options.video.map { it.formatId })
        assertFalse(options.music.any { it.formatId == "silent-video" })
        assertFalse(options.video.any { it.formatId == "silent-video" })
        assertEquals(1_024_000.0, options.video.single().fileSize!!, 0.0)
    }

    @Test
    fun optionsAreSortedFromHighestToLowestQuality() {
        val audioLow = Format(formatId = "audio-low", acodec = "opus", abr = 96.0)
        val audioHigh = Format(formatId = "audio-high", acodec = "opus", abr = 256.0)
        val video720 =
            Format(formatId = "video-720", acodec = "aac", vcodec = "avc1", height = 720.0)
        val video1080LowRate =
            Format(
                formatId = "video-1080-low-rate",
                acodec = "aac",
                vcodec = "avc1",
                height = 1080.0,
                tbr = 1_500.0,
            )
        val video1080HighRate =
            Format(
                formatId = "video-1080-high-rate",
                acodec = "aac",
                vcodec = "avc1",
                height = 1080.0,
                tbr = 3_000.0,
            )

        val options =
            formatPickerOptions(
                listOf(audioLow, video720, audioHigh, video1080LowRate, video1080HighRate)
            )

        assertEquals(listOf("audio-high", "audio-low"), options.music.map { it.formatId })
        assertEquals(
            listOf("video-1080-high-rate", "video-1080-low-rate", "video-720"),
            options.video.map { it.formatId },
        )
    }
}

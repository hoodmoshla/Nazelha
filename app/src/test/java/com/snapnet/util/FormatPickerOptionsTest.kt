package com.snapnet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatPickerOptionsTest {
    @Test
    fun pairsRealDashStreamsWithContainerCompatibleAudioAndKeepsMuxedFormats() {
        val audioM4a =
            Format(
                formatId = "audio-m4a",
                acodec = "mp4a.40.2",
                vcodec = "none",
                ext = "m4a",
                abr = 128.0,
                fileSize = 200.0,
            )
        val audioOpus =
            Format(
                formatId = "audio-opus",
                acodec = "opus",
                vcodec = "none",
                ext = "webm",
                abr = 160.0,
                fileSize = 100.0,
            )
        val dashMp4 =
            Format(
                formatId = "video-mp4-720",
                acodec = "none",
                vcodec = "avc1",
                ext = "mp4",
                width = 1280.0,
                height = 720.0,
                vbr = 900.0,
                fileSize = 1_000.0,
            )
        val dashWebm =
            Format(
                formatId = "video-webm-1080",
                acodec = "none",
                vcodec = "vp9",
                ext = "webm",
                width = 1920.0,
                height = 1080.0,
                vbr = 1_800.0,
                fileSizeApprox = 2_000.0,
            )
        val muxed =
            Format(
                formatId = "muxed-360",
                acodec = "aac",
                vcodec = "avc1",
                ext = "mp4",
                width = 640.0,
                height = 360.0,
            )
        val unreportedResolution =
            Format(
                formatId = "video-unknown",
                format = "hd - unknown",
                acodec = "none",
                vcodec = "avc1",
            )

        val options =
            formatPickerOptions(
                formats = listOf(dashMp4, audioOpus, muxed, unreportedResolution, audioM4a, dashWebm),
                durationSeconds = 10.0,
            )

        assertEquals(
            listOf(
                "video-webm-1080+audio-opus",
                "video-mp4-720+audio-m4a",
                "muxed-360",
                "video-unknown+audio-opus",
            ),
            options.video.map { it.formatId },
        )
        assertEquals("opus", options.video[0].acodec)
        assertEquals("vp9", options.video[0].vcodec)
        assertTrue(options.video.all { it.containsAudio() && it.containsVideo() })
        assertEquals(1_200.0, options.video[1].fileSize!!, 0.0)
        assertEquals(2_100.0, options.video[0].fileSizeApprox!!, 0.0)
        assertEquals("1920×1080 (1080p)", options.video[0].videoQualityLabel())
        assertNull(options.video.last().videoQualityLabel())
    }

    @Test
    fun showsOnlyTheHighestQualityStandaloneAudioChoice() {
        val low = Format(formatId = "audio-low", acodec = "opus", vcodec = "none", abr = 96.0)
        val high = Format(formatId = "audio-high", acodec = "opus", vcodec = "none", abr = 256.0)
        val video =
            Format(
                formatId = "video-720",
                acodec = "none",
                vcodec = "avc1",
                height = 720.0,
            )

        val options = formatPickerOptions(listOf(low, video, high))

        assertEquals(listOf("audio-high"), options.music.map { it.formatId })
        assertEquals(listOf("video-720+audio-high"), options.video.map { it.formatId })
    }

    @Test
    fun keepsEveryRealVideoFormatIncludingSameQualityAndUnknownResolution() {
        val audio = Format(formatId = "audio", acodec = "aac", vcodec = "none", abr = 128.0)
        val recommended360 =
            Format(
                formatId = "muxed-360",
                acodec = "aac",
                vcodec = "avc1",
                format = "18 - 640x360 (360p)",
            )
        val dash360 =
            Format(
                formatId = "dash-360",
                acodec = "none",
                vcodec = "avc1",
                resolution = "640x360",
            )
        val dash720 =
            Format(
                formatId = "dash-720",
                acodec = "none",
                vcodec = "avc1",
                formatNote = "720p",
            )
        val unknown =
            Format(formatId = "unknown", format = "hd - unknown", acodec = "none", vcodec = "avc1")

        val options =
            formatPickerOptions(
                formats = listOf(audio, recommended360, dash360, dash720, unknown),
            )

        assertEquals(
            setOf("muxed-360", "dash-360+audio", "dash-720+audio", "unknown+audio"),
            options.video.mapNotNull { it.formatId }.toSet(),
        )
        assertEquals(4, options.video.size)
        assertEquals(2, options.video.count { it.videoQualityHeight() == 360 })
        assertEquals("720p", options.video.first { it.formatId == "dash-720+audio" }.videoQualityLabel())
        assertNull(unknown.videoQualityLabel())
    }

    @Test
    fun sortsVideoChoicesByResolutionThenFrameRateAndBitrate() {
        val audio = Format(formatId = "audio", acodec = "aac", vcodec = "none", abr = 128.0)
        val low =
            Format(
                formatId = "video-360",
                acodec = "none",
                vcodec = "avc1",
                height = 360.0,
                vbr = 1_000.0,
            )
        val standard =
            Format(
                formatId = "video-720-30",
                acodec = "none",
                vcodec = "avc1",
                height = 720.0,
                fps = 30.0,
                vbr = 1_000.0,
            )
        val highFrameRate =
            Format(
                formatId = "video-720-60",
                acodec = "none",
                vcodec = "avc1",
                height = 720.0,
                fps = 60.0,
                vbr = 1_000.0,
            )
        val fullHd =
            Format(
                formatId = "video-1080",
                acodec = "none",
                vcodec = "avc1",
                height = 1080.0,
                vbr = 1_500.0,
            )

        val options = formatPickerOptions(listOf(low, standard, audio, highFrameRate, fullHd))

        assertEquals(
            listOf(
                "video-1080+audio",
                "video-720-60+audio",
                "video-720-30+audio",
                "video-360+audio",
            ),
            options.video.map { it.formatId },
        )
    }

    @Test
    fun estimatesCombinedSizeOnlyWhenBothStreamsHaveUsableSizeData() {
        val audio = Format(formatId = "audio", acodec = "aac", vcodec = "none", abr = 128.0)
        val video =
            Format(
                formatId = "video",
                acodec = "none",
                vcodec = "avc1",
                height = 720.0,
                vbr = 800.0,
            )

        val estimated = formatPickerOptions(listOf(video, audio), durationSeconds = 60.0).video.single()

        assertNull(estimated.fileSize)
        assertEquals(6_960_000.0, estimated.fileSizeApprox!!, 0.0)
    }
}

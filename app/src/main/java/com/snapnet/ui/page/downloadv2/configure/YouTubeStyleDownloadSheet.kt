package com.snapnet.ui.page.downloadv2.configure

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapnet.R
import com.snapnet.ui.common.AsyncImageImpl
import com.snapnet.util.Format
import com.snapnet.util.VideoInfo
import com.snapnet.util.initialVideoFormatChoices
import com.snapnet.util.nearestStandardVideoQuality
import com.snapnet.util.toDurationText
import com.snapnet.util.toFileSizeText
import com.snapnet.util.toHttpsUrl
import com.snapnet.util.videoQualityLabel
import com.snapnet.util.videoQualityLevel
import java.util.Locale
import kotlin.math.roundToInt

private enum class DownloadSheetTab {
    Video,
    Audio,
}

private val SheetGreen = Color(0xFF3DDC84)
private val SheetGreenDark = Color(0xFF1B8F4A)
private val SelectedGreenFill = Color(0xFF163D28)
private val SelectedGreenBorder = Color(0xFF3DDC84)
private val SheetCard = Color(0xFF1C1C1E)
private val SheetRow = Color(0xFF2A2A2C)
private val SheetText = Color(0xFFF4F4F5)
private val SheetMuted = Color(0xFFB5B5B8)
private val LightSheetCard = Color(0xFFF4F4F5)
private val LightSheetRow = Color(0xFFFFFFFF)
private val LightSelectedFill = Color(0xFFE5F6EC)
private val LightText = Color(0xFF1C1C1E)
private val LightMuted = Color(0xFF66666A)

private data class SheetPalette(
    val card: Color,
    val row: Color,
    val selectedFill: Color,
    val selectedBorder: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
)

@Composable
private fun rememberSheetPalette(): SheetPalette {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        SheetPalette(
            card = SheetCard,
            row = SheetRow,
            selectedFill = SelectedGreenFill,
            selectedBorder = SelectedGreenBorder,
            text = SheetText,
            muted = SheetMuted,
            accent = SheetGreen,
            onAccent = Color(0xFF102116),
        )
    } else {
        SheetPalette(
            card = LightSheetCard,
            row = LightSheetRow,
            selectedFill = LightSelectedFill,
            selectedBorder = SheetGreenDark,
            text = LightText,
            muted = LightMuted,
            accent = SheetGreenDark,
            onAccent = Color.White,
        )
    }
}

private fun Color.luminance(): Float {
    fun channel(value: Float) =
        if (value <= 0.03928f) value / 12.92f
        else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

/**
 * Shared download sheet matching the requested layout: header, video/audio tabs, real ascending
 * qualities, original reported sizes only, and a fixed download button. No quality is invented.
 */
@Composable
fun YouTubeStyleDownloadSheet(
    videoInfo: VideoInfo,
    videoFormats: List<Format>,
    audioFormats: List<Format>,
    selectedVideoIndex: Int,
    selectedAudioIndex: Int,
    onSelectVideo: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberSheetPalette()
    val visibleVideo = remember(videoFormats) { initialVideoFormatChoices(videoFormats) }
    var tab by remember(videoInfo) { mutableStateOf(DownloadSheetTab.Video) }
    val selectedFormat =
        when {
            selectedVideoIndex >= 0 -> videoFormats.getOrNull(selectedVideoIndex)
            selectedAudioIndex >= 0 -> audioFormats.getOrNull(selectedAudioIndex)
            else -> null
        }
    val downloadEnabled = selectedFormat != null

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(palette.card)
                .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        SheetHeader(videoInfo = videoInfo, palette = palette, onDismiss = onDismiss)
        Spacer(Modifier.height(12.dp))
        SheetTabs(selected = tab, palette = palette, onSelect = { tab = it })
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tab == DownloadSheetTab.Video) {
                items(visibleVideo, key = { it.formatId ?: it.videoQualityLabel().orEmpty() }) {
                    format ->
                    val index = videoFormats.indexOf(format)
                    QualityRow(
                        format = format,
                        selected = index == selectedVideoIndex,
                        palette = palette,
                        onClick = { if (index >= 0) onSelectVideo(index) },
                    )
                }
            } else {
                items(audioFormats, key = { it.formatId ?: "audio-${it.abr}-${it.fileSize}" }) {
                    format ->
                    val index = audioFormats.indexOf(format)
                    AudioRow(
                        format = format,
                        selected = index == selectedAudioIndex,
                        palette = palette,
                        onClick = { if (index >= 0) onSelectAudio(index) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        DownloadNowButton(
            format = selectedFormat,
            audioSelected = selectedAudioIndex >= 0 && selectedVideoIndex < 0,
            enabled = downloadEnabled,
            palette = palette,
            onClick = onDownload,
        )
    }
}

@Composable
private fun SheetHeader(videoInfo: VideoInfo, palette: SheetPalette, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Box {
            AsyncImageImpl(
                model = videoInfo.thumbnail.toHttpsUrl(),
                contentDescription = stringResource(R.string.thumbnail),
                modifier = Modifier.width(118.dp).height(72.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            videoInfo.duration
                ?.roundToInt()
                ?.takeIf { it > 0 }
                ?.let { seconds ->
                    Text(
                        text = seconds.toDurationText(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier.align(Alignment.BottomStart)
                                .padding(5.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(
                text = videoInfo.title.ifBlank { stringResource(R.string.video) },
                color = palette.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 23.sp,
            )
            val meta = videoMeta(videoInfo)
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = palette.muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Close, stringResource(R.string.close), tint = palette.text)
        }
    }
}

@Composable
private fun videoMeta(videoInfo: VideoInfo): String {
    val views =
        videoInfo.viewCount
            ?.takeIf { it > 0 }
            ?.let { stringResource(R.string.video_views_count, formatCount(it)) }
    val author =
        videoInfo.uploader?.takeIf { it.isNotBlank() }
            ?: videoInfo.channel?.takeIf { it.isNotBlank() }
    return listOfNotNull(views, author).joinToString(" · ")
}

private fun formatCount(value: Long): String {
    val locale = Locale.getDefault()
    return when {
        value >= 1_000_000_000 ->
            if (locale.language == "ar")
                String.format(locale, "%.1f مليار", value / 1_000_000_000.0)
            else String.format(locale, "%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 ->
            if (locale.language == "ar") "${value / 1_000_000} مليون"
            else String.format(locale, "%,d", value)
        value >= 1_000 -> String.format(locale, "%,d", value)
        else -> value.toString()
    }
}

@Composable
private fun SheetTabs(
    selected: DownloadSheetTab,
    palette: SheetPalette,
    onSelect: (DownloadSheetTab) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SheetTab(
            text = stringResource(R.string.video),
            icon = Icons.Rounded.Videocam,
            selected = selected == DownloadSheetTab.Video,
            palette = palette,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(DownloadSheetTab.Video) },
        )
        SheetTab(
            text = stringResource(R.string.audio_only_format),
            icon = Icons.Filled.MusicNote,
            selected = selected == DownloadSheetTab.Audio,
            palette = palette,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(DownloadSheetTab.Audio) },
        )
    }
}

@Composable
private fun SheetTab(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    palette: SheetPalette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val border = if (selected) palette.accent else palette.muted.copy(alpha = 0.28f)
    val fill = if (selected) palette.selectedFill else palette.row
    Row(
        modifier =
            modifier
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(fill)
                .border(1.5.dp, border, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.text)
        Spacer(Modifier.width(8.dp))
        Text(text, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun QualityRow(
    format: Format,
    selected: Boolean,
    palette: SheetPalette,
    onClick: () -> Unit,
) {
    val level = format.videoQualityLevel()
    val captionLevel = nearestStandardVideoQuality(level)
    val border = if (selected) palette.selectedBorder else Color.Transparent
    val fill = if (selected) palette.selectedFill else palette.row
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(fill)
                .border(if (selected) 1.5.dp else 0.dp, border, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors =
                RadioButtonDefaults.colors(
                    selectedColor = palette.accent,
                    unselectedColor = palette.muted,
                ),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        level?.let { "${it}p" } ?: stringResource(R.string.resolution_unavailable),
                    color = palette.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                qualityBadge(level)?.let { (label, color) ->
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier.padding(start = 8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(color)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            qualityCaption(captionLevel)?.let {
                Text(
                    it,
                    color = palette.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        val size = originalSizeText(format)
        if (!size.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = palette.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    size,
                    color = palette.text,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AudioRow(
    format: Format,
    selected: Boolean,
    palette: SheetPalette,
    onClick: () -> Unit,
) {
    val border = if (selected) palette.selectedBorder else Color.Transparent
    val fill = if (selected) palette.selectedFill else palette.row
    val bitrate = (format.abr ?: format.tbr)?.takeIf { it > 0.0 }?.roundToInt()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(fill)
                .border(if (selected) 1.5.dp else 0.dp, border, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors =
                RadioButtonDefaults.colors(
                    selectedColor = palette.accent,
                    unselectedColor = palette.muted,
                ),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                stringResource(R.string.audio_only_format),
                color = palette.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            bitrate?.let {
                Text(
                    "$it kbps",
                    color = palette.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        originalSizeText(format)?.let { size ->
            Text(size, color = palette.text, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun qualityBadge(level: Int?): Pair<String, Color>? =
    when (level) {
        in 720..899 -> stringResource(R.string.quality_badge_hd) to Color(0xFF2F80ED)
        in 900..1199 -> stringResource(R.string.quality_badge_full_hd) to Color(0xFFF2A200)
        in 1200..1799 -> stringResource(R.string.quality_badge_2k) to Color(0xFFE23B4A)
        in 1800..Int.MAX_VALUE -> stringResource(R.string.quality_badge_4k) to Color(0xFF7B3FF2)
        else -> null
    }

@Composable
private fun qualityCaption(level: Int?): String? =
    when (level) {
        in 1..399 -> stringResource(R.string.quality_360_caption)
        in 400..599 -> stringResource(R.string.quality_480_caption)
        in 600..899 -> stringResource(R.string.quality_720_caption)
        in 900..1199 -> stringResource(R.string.quality_1080_caption)
        in 1200..Int.MAX_VALUE -> stringResource(R.string.quality_1440_caption)
        else -> null
    }

/**
 * Shows a size only when the source reported one. `fileSizeApprox` is yt-dlp's own reported value,
 * not a bitrate estimate. Missing sizes stay blank and are never labeled "about".
 */
@Composable
private fun originalSizeText(format: Format): String? =
    (format.fileSize ?: format.fileSizeApprox)?.takeIf { it > 0.0 }?.toFileSizeText()

@Composable
private fun DownloadNowButton(
    format: Format?,
    audioSelected: Boolean,
    enabled: Boolean,
    palette: SheetPalette,
    onClick: () -> Unit,
) {
    val quality =
        when {
            audioSelected -> stringResource(R.string.audio_only_format)
            else -> format?.videoQualityLevel()?.let { "${it}p" }.orEmpty()
        }
    val size = format?.let { originalSizeText(it) }
    val detail =
        when {
            !enabled -> ""
            size != null && quality.isNotBlank() ->
                stringResource(R.string.selected_quality_size, quality, size)
            else -> quality
        }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) palette.accent else palette.muted.copy(alpha = 0.35f),
        contentColor = palette.onAccent,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(R.string.download_now),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (detail.isNotBlank()) {
                Text(detail, fontSize = 13.sp, color = palette.onAccent.copy(alpha = 0.82f))
            }
        }
    }
}

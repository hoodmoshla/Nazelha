package com.snapnet.database.backup

import android.content.Context
import com.snapnet.App
import com.hoodmoshla.nazelha.R
import com.snapnet.database.objects.CommandTemplate
import com.snapnet.database.objects.DownloadedVideoInfo
import com.snapnet.database.objects.OptionShortcut
import com.snapnet.util.DatabaseUtil
import com.snapnet.util.Redactor
import java.util.Date
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object BackupUtil {
    private val format = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun exportTemplatesToJson() =
        exportTemplatesToJson(
            templates = DatabaseUtil.getTemplateList(),
            shortcuts = DatabaseUtil.getShortcutList(),
        )

    fun exportTemplatesToJson(
        templates: List<CommandTemplate>,
        shortcuts: List<OptionShortcut>,
    ): String {
        val safeTemplates = templates.map { it.copy(template = Redactor.redactUrls(it.template)) }
        val safeShortcuts = shortcuts.map { it.copy(option = Redactor.redactUrls(it.option)) }
        return format.encodeToString(Backup(templates = safeTemplates, shortcuts = safeShortcuts))
    }

    fun List<DownloadedVideoInfo>.toJsonString(): String {
        val safeHistory =
            map { item ->
                item.copy(
                    videoUrl = Redactor.redactUrl(item.videoUrl),
                    thumbnailUrl = Redactor.redactUrl(item.thumbnailUrl),
                )
            }
        return format.encodeToString(Backup(downloadHistory = safeHistory))
    }

    fun List<DownloadedVideoInfo>.toURLListString(): String {
        return map { Redactor.redactUrl(it.videoUrl) }.joinToString(separator = "\n")
    }

    fun String.decodeToBackup(): Result<Backup> {
        return format.runCatching { decodeFromString<Backup>(this@decodeToBackup) }
    }

    fun getDownloadHistoryExportFilename(context: Context): String {
        return listOf(
                context.getString(R.string.app_name),
                App.packageInfo.versionName.toString(),
                Date().toString(),
            )
            .joinToString(separator = "-") { it }
    }

    enum class BackupDestination {
        File,
        Clipboard,
    }

    enum class BackupType {
        DownloadHistory,
        URLList,
        CommandTemplate,
        CommandShortcut,
    }
}

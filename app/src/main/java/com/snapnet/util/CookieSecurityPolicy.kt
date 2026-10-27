package com.snapnet.util

/** Ensures yt-dlp never sends imported cookies over a cleartext HTTP connection. */
internal object CookieSecurityPolicy {
    fun httpsOnlyNetscapeContent(content: String): String {
        val hadTrailingNewline = content.endsWith('\n')
        val lines = content.split('\n').toMutableList()
        if (hadTrailingNewline && lines.isNotEmpty()) lines.removeAt(lines.lastIndex)
        val secured =
            lines.map { line ->
                if (line.isBlank() || (line.startsWith('#') && !line.startsWith("#HttpOnly_"))) {
                    line
                } else {
                    val fields = line.split('\t').toMutableList()
                    if (fields.size >= 7) fields[3] = "TRUE"
                    fields.joinToString("\t")
                }
            }
        return secured.joinToString("\n", postfix = if (hadTrailingNewline) "\n" else "")
    }
}

package com.snapnet.download

import java.security.MessageDigest

internal object DirectHttpTempFileNames {
    fun partialName(taskId: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "direct-http-$hex.part"
    }
}

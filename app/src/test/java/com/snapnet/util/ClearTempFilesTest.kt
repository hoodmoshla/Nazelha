package com.snapnet.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearTempFilesTest {
    @Test
    fun `clear preserves queued partial and its resume metadata`() {
        val root = Files.createTempDirectory("nazelha-clear-temp").toFile()
        try {
            val temp = File(root, "tmp").apply { mkdirs() }
            val activePart = File(temp, "direct-http-active.part").apply { writeText("active") }
            val activeMetadata =
                File(temp, ".${activePart.name}.resume").apply { writeText("active metadata") }
            val stalePart = File(temp, "direct-http-stale.part").apply { writeText("stale") }
            val staleMetadata =
                File(temp, ".${stalePart.name}.resume").apply { writeText("stale metadata") }
            val noMedia = File(temp, ".nomedia").apply { writeText("") }

            val deleted = FileUtil.clearTempFiles(temp, setOf(activePart.name))

            assertEquals(2, deleted)
            assertTrue(activePart.exists())
            assertTrue(activeMetadata.exists())
            assertFalse(stalePart.exists())
            assertFalse(staleMetadata.exists())
            assertTrue(noMedia.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `clear refuses config directories and preserves config files even in tmp`() {
        val root = Files.createTempDirectory("nazelha-config-preservation").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            val cookiesInCache = File(cache, "cookies.txt").apply { writeText("cookie data") }
            val configInCache = File(cache, "configprofile.txt").apply { writeText("config data") }

            assertEquals(0, FileUtil.clearTempFiles(cache))
            assertTrue(cookiesInCache.exists())
            assertTrue(configInCache.exists())

            val temp = File(root, "tmp").apply { mkdirs() }
            val cookies = File(temp, "cookies.txt").apply { writeText("cookie data") }
            val config = File(temp, "configprofile.txt").apply { writeText("config data") }
            val disposable = File(temp, "stale.tmp").apply { writeText("temp data") }

            assertEquals(1, FileUtil.clearTempFiles(temp))
            assertTrue(cookies.exists())
            assertTrue(config.exists())
            assertFalse(disposable.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

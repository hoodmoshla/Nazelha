package com.snapnet.download

import com.snapnet.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskIdentityTest {
    private val preferences = DownloadUtil.DownloadPreferences.EMPTY

    @Test
    fun `task id is deterministic but does not disclose its source url`() {
        val secretUrl = "https://cdn.example/file?token=temporary-secret"
        val first = Task(url = secretUrl, preferences = preferences)
        val second = Task(url = secretUrl, preferences = preferences)

        assertEquals(first.id, second.id)
        assertTrue(first.id.startsWith("task-v2-"))
        assertFalse(first.id.contains(secretUrl))
        assertFalse(first.id.contains("temporary-secret"))
    }

    @Test
    fun `different source urls receive different opaque ids`() {
        val first = Task(url = "https://cdn.example/one?token=a", preferences = preferences)
        val second = Task(url = "https://cdn.example/two?token=b", preferences = preferences)

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `legacy id is replaced without changing the task url`() {
        val secretUrl = "https://cdn.example/file?token=temporary-secret"
        val legacy = Task(url = secretUrl, preferences = preferences, id = secretUrl)

        val migrated = legacy.withOpaqueId()

        assertEquals(secretUrl, migrated.url)
        assertEquals(Task(url = secretUrl, preferences = preferences).id, migrated.id)
        assertFalse(migrated.id.contains("temporary-secret"))
    }

    @Test
    fun `default notification title keeps only the host`() {
        assertEquals(
            "cdn.example",
            safeDownloadTitle("https://user:pass@cdn.example/private/file?token=temporary-secret"),
        )
        assertEquals("Download", safeDownloadTitle("not a url with ?token=secret"))
    }
}

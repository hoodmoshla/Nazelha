package com.snapnet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskListBackupMigrationTest {
    @Test
    fun `moves legacy payload then clears old copy`() {
        var persisted: String? = null
        var legacyCleared = false

        val result =
            migrateTaskListPayload(
                currentPayload = null,
                legacyPayload = "queued-tasks",
                persistCurrent = { persisted = it; true },
                clearLegacy = { legacyCleared = true },
            )

        assertEquals("queued-tasks", result)
        assertEquals("queued-tasks", persisted)
        assertTrue(legacyCleared)
    }

    @Test
    fun `preserves legacy payload if new store write fails`() {
        var legacyCleared = false

        val result =
            migrateTaskListPayload(
                currentPayload = null,
                legacyPayload = "queued-tasks",
                persistCurrent = { false },
                clearLegacy = { legacyCleared = true },
            )

        assertEquals("queued-tasks", result)
        assertFalse(legacyCleared)
    }

    @Test
    fun `prefers current payload and clears stale legacy copy`() {
        var persisted = false
        var legacyCleared = false

        val result =
            migrateTaskListPayload(
                currentPayload = "new-queue",
                legacyPayload = "old-queue",
                persistCurrent = { persisted = true; true },
                clearLegacy = { legacyCleared = true },
            )

        assertEquals("new-queue", result)
        assertFalse(persisted)
        assertTrue(legacyCleared)
    }
}

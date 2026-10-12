package com.snapnet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationPolicyTest {
    @Test
    fun `active download starts ongoing with pause and cancel controls`() {
        val state = DownloadNotificationPolicy.active(hasTaskId = true)

        assertTrue(state.ongoing)
        assertEquals(
            listOf(DownloadNotificationAction.PAUSE, DownloadNotificationAction.CANCEL),
            state.actions,
        )
    }

    @Test
    fun `unpausable work still offers cancel without advertising pause`() {
        val state = DownloadNotificationPolicy.active(hasTaskId = true, canPause = false)

        assertTrue(state.ongoing)
        assertEquals(listOf(DownloadNotificationAction.CANCEL), state.actions)
    }

    @Test
    fun `paused notification is not ongoing and offers resume`() {
        val state = DownloadNotificationPolicy.paused()

        assertFalse(state.ongoing)
        assertEquals(listOf(DownloadNotificationAction.RESUME), state.actions)
    }

    @Test
    fun `completion notification is non ongoing and has no action buttons`() {
        val state = DownloadNotificationPolicy.completed()

        assertFalse(state.ongoing)
        assertTrue(state.actions.isEmpty())
    }
}

package com.snapnet.ui.page

import com.snapnet.ui.common.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopLevelNavigationTest {
    @Test
    fun destinationsFollowTheRequiredLogicalRtlOrder() {
        assertEquals(
            listOf(
                TopLevelDestination.HOME,
                TopLevelDestination.DOWNLOADS,
                TopLevelDestination.FILES,
                TopLevelDestination.BROWSER,
                TopLevelDestination.MORE,
            ),
            TopLevelDestination.entries,
        )
    }

    @Test
    fun eachTopLevelRouteMapsToItsDestination() {
        TopLevelDestination.entries.forEach { destination ->
            assertEquals(destination, topLevelDestinationForRoute(destination.route))
        }
        assertEquals(Route.HOME, TopLevelDestination.HOME.route)
        assertNull(topLevelDestinationForRoute(null))
        assertNull(topLevelDestinationForRoute(Route.DOWNLOAD_HISTORY))
    }
}

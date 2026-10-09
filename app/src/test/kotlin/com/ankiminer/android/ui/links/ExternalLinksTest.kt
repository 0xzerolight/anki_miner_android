package com.ankiminer.android.ui.links

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLinksTest {
    @Test
    fun opensTheLinkThroughTheOpener() {
        val opened = mutableListOf<String>()

        assertTrue(openExternalLink(AppLinks.REPOSITORY) { opened += it })
        assertEquals(listOf(AppLinks.REPOSITORY), opened)
    }

    @Test
    fun noAppForTheLinkReportsFailureInsteadOfThrowing() {
        listOf(
            // What Compose's AndroidUriHandler throws when no activity resolves ACTION_VIEW.
            IllegalArgumentException("Can't open https://example.com."),
            ActivityNotFoundException("No Activity found to handle Intent"),
            SecurityException("Permission Denial"),
        ).forEach { failure ->
            assertFalse(
                failure::class.java.name,
                openExternalLink(AppLinks.REPOSITORY) { throw failure },
            )
        }
    }

    @Test
    fun unrelatedFailurePropagates() {
        assertThrows(IllegalStateException::class.java) {
            openExternalLink(AppLinks.REPOSITORY) { throw IllegalStateException("bug") }
        }
    }
}

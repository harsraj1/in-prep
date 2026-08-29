package com.harsraj.inprep

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderContentTest {
    @Test
    fun `default content identifies the app and readiness state`() {
        val content = PlaceholderContent()

        assertEquals("In Prep", content.title)
        assertEquals(
            "Your AI interview practice workspace is being prepared.",
            content.message,
        )
    }
}

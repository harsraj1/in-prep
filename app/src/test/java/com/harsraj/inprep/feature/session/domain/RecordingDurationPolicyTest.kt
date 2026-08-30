package com.harsraj.inprep.feature.session.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingDurationPolicyTest {
    private val policy = RecordingDurationPolicy(minimumMillis = 3_000, maximumMillis = 30_000)

    @Test
    fun `rejects samples shorter than minimum`() {
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireValid(2_999)
        }
    }

    @Test
    fun `accepts minimum and clamps maximum`() {
        assertEquals(3_000L, policy.requireValid(3_000))
        assertEquals(30_000L, policy.requireValid(31_000))
    }
}

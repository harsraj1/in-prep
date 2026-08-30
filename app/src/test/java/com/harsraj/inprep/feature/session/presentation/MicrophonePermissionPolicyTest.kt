package com.harsraj.inprep.feature.session.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophonePermissionPolicyTest {
    @Test
    fun `granted permission starts recording without another prompt`() {
        assertEquals(
            MicrophonePermissionNextStep.START_RECORDING,
            MicrophonePermissionPolicy.beforeRequest(isGranted = true, shouldShowRationale = false),
        )
    }

    @Test
    fun `denied permission has rationale retry and permanent settings recovery`() {
        assertEquals(
            MicrophonePermissionNextStep.SHOW_RATIONALE,
            MicrophonePermissionPolicy.beforeRequest(isGranted = false, shouldShowRationale = true),
        )
        assertEquals(
            MicrophoneDenialRecovery.RETRY_REQUEST,
            MicrophonePermissionPolicy.afterDenial(shouldShowRationale = true),
        )
        assertEquals(
            MicrophoneDenialRecovery.OPEN_SETTINGS,
            MicrophonePermissionPolicy.afterDenial(shouldShowRationale = false),
        )
    }
}

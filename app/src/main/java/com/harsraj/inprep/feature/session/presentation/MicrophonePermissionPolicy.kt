package com.harsraj.inprep.feature.session.presentation

enum class MicrophonePermissionNextStep {
    START_RECORDING,
    SHOW_RATIONALE,
    REQUEST_PERMISSION,
}

enum class MicrophoneDenialRecovery {
    RETRY_REQUEST,
    OPEN_SETTINGS,
}

object MicrophonePermissionPolicy {
    fun beforeRequest(
        isGranted: Boolean,
        shouldShowRationale: Boolean,
    ): MicrophonePermissionNextStep = when {
        isGranted -> MicrophonePermissionNextStep.START_RECORDING
        shouldShowRationale -> MicrophonePermissionNextStep.SHOW_RATIONALE
        else -> MicrophonePermissionNextStep.REQUEST_PERMISSION
    }

    fun afterDenial(shouldShowRationale: Boolean): MicrophoneDenialRecovery =
        if (shouldShowRationale) {
            MicrophoneDenialRecovery.RETRY_REQUEST
        } else {
            MicrophoneDenialRecovery.OPEN_SETTINGS
        }
}

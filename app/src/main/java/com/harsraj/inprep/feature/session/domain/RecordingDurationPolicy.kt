package com.harsraj.inprep.feature.session.domain

class RecordingDurationPolicy(
    val minimumMillis: Long = 3_000L,
    val maximumMillis: Long = 30_000L,
) {
    init {
        require(minimumMillis > 0)
        require(maximumMillis >= minimumMillis)
    }

    fun requireValid(durationMillis: Long): Long {
        require(durationMillis >= minimumMillis) {
            "Record at least ${minimumMillis / 1_000} seconds of clear speech"
        }
        return durationMillis.coerceAtMost(maximumMillis)
    }
}

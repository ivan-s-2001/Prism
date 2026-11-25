package com.prism.music.connect.yandex

import kotlin.math.abs

internal object StationSeekConfirmation {
    fun matches(state: StationPlaybackState?, url: String, targetMs: Long, issuedAt: Long): Boolean {
        if (state == null || !state.matches(url) || state.at <= issuedAt) return false
        val actual = state.position ?: return false
        // Whole-second firmware reports and brief playback advancement are expected.
        return abs(actual - targetMs) <= 2000
    }
}

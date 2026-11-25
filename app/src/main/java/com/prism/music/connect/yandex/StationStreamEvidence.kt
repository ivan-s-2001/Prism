package com.prism.music.connect.yandex

/**
 * Some audio_client firmwares report an empty track ID for external playback.
 * A fresh GET and a stopped boundary before replacement are required in that case.
 * This correlates receiver state with a stream; it is not proof of acoustic output.
 */
internal class StationStreamEvidence {
    private var url: String? = null
    private var baseline = 0L
    private var issuedAt = Long.MAX_VALUE
    private var confirmed = false
    private var interrupted = false

    @Synchronized fun begin(url: String, baseline: Long, issuedAt: Long) {
        this.url = url
        this.baseline = baseline
        this.issuedAt = issuedAt
        confirmed = false
        interrupted = false
    }

    @Synchronized fun clear() {
        url = null
        confirmed = false
    }

    @Synchronized fun observe(raw: StationPlaybackState, delivery: (String) -> Long): StationPlaybackState {
        val expected = url ?: return raw
        if (raw.at < issuedAt) return raw
        if (raw.type == "bluetooth" || (!raw.id.isNullOrEmpty() && raw.id != expected)) {
            if (raw.playing == true) { confirmed = false; interrupted = true }
            return raw
        }
        if (raw.playing == true && (raw.matches(expected) || (raw.id == "" && !interrupted && delivery(expected) > baseline))) {
            confirmed = true
            interrupted = false
        }
        // Only normalize explicitly anonymous frames of an already correlated stream.
        return if (confirmed && raw.id == "") raw.copy(id = expected) else raw
    }
}

package com.prism.music.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.foryou.MyWave
import com.prism.music.data.foryou.WaveScenarios
import com.prism.music.data.prefs.SavedWavePreset
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.playback.VibeOriginKind

/** Single ordered shelf model used by phone, TV and Looks. Stable keys are separate from stored scenario IDs. */
internal data class VibeShortcut(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selected: Boolean = false,
    val style: MyWave.Style? = null,
    val scenarioId: String? = null,
)

internal fun personalVibe(style: MyWave.Style): MyWave.Style = MyWave.Style(russianPercent = style.russianPercent)

internal fun vibeStationTitle(saved: SavedWavePreset?, scenario: WaveScenarios.Scenario?): String =
    saved?.name ?: scenario?.label ?: tr(R.string.prism_stream_station_default)

/** Context radios retain their original title when the recommended track changes. */
internal fun activeVibeTitle(source: com.prism.music.playback.QueueSource,
    saved: SavedWavePreset?, scenario: WaveScenarios.Scenario?): String =
    if (source.isContextVibe) source.title.ifBlank { tr(R.string.stream) } else vibeStationTitle(saved, scenario)

internal fun activeVibeOrigin(source: com.prism.music.playback.QueueSource,
    current: com.prism.music.data.model.Song?): com.prism.music.playback.VibeOrigin? {
    if (!source.isContextVibe) return null
    val seed = current?.takeIf { it.id == source.seedId }
    source.vibeOrigin?.let { origin ->
        if (origin.kind == VibeOriginKind.TRACK && origin.artist.isNullOrBlank() && seed != null) {
            return origin.copy(artist = seed.artists.joinToString(", ") { it.name }.ifBlank { null })
        }
        return origin
    }
    // Old saved queues may lack an explicit origin. A single-track radio still has a stable
    // source title after the seed stops playing; never replace it with the next recommendation.
    if (source.vibeAnchors.isEmpty() && source.seedId != null) {
        val name = seed?.title ?: source.title.substringAfter(" · ", source.title)
        if (name.isNotBlank()) return com.prism.music.playback.VibeOrigin(
            com.prism.music.playback.VibeOriginKind.TRACK, name, seed?.thumbnail, seed?.artists?.joinToString(", ") { it.name }?.ifBlank { null })
    }
    return null
}

internal fun vibeOriginArtist(origin: com.prism.music.playback.VibeOrigin): String? =
    origin.artist?.trim()?.takeIf {
        (origin.kind == VibeOriginKind.TRACK || origin.kind == VibeOriginKind.ALBUM) &&
            it.isNotBlank() && !it.equals(origin.name.trim(), ignoreCase = true)
    }

internal fun vibeOriginLabel(kind: VibeOriginKind): Int = when (kind) {
    VibeOriginKind.TRACK -> R.string.prism_vibe_from_track
    VibeOriginKind.ALBUM -> R.string.prism_vibe_from_album
    VibeOriginKind.ARTIST -> R.string.prism_vibe_from_artist
    VibeOriginKind.PLAYLIST -> R.string.prism_vibe_from_playlist
    VibeOriginKind.COLLECTION -> R.string.prism_vibe_from_collection
    VibeOriginKind.MIX -> R.string.prism_vibe_from_mix
}

internal fun vibeShortcuts(
    style: MyWave.Style,
    saved: List<SavedWavePreset>,
    scenarios: List<WaveScenarios.Scenario>,
    overrides: Map<String, String>,
    activeSavedId: String?,
    activeScenarioId: String?,
    iconForPreset: (SavedWavePreset) -> ImageVector = { PrismIcons.Bookmark },
): List<VibeShortcut> = buildList {
    val personal = personalVibe(style)
    add(VibeShortcut("personal", tr(R.string.prism_stream_station_default), PrismIcons.Stream,
        activeSavedId == null && activeScenarioId == null && style == personal, personal))
    saved.forEach { preset ->
        add(VibeShortcut("custom:${preset.id}", preset.name, iconForPreset(preset),
            activeSavedId == preset.id, MyWave.style(preset.origin), preset.id))
    }
    scenarios.forEach { scenario ->
        add(VibeShortcut("scenario:${scenario.id}", scenario.label, PrismIcons.waveScenario(scenario.glyph),
            activeScenarioId == scenario.id, WaveScenarios.resolve(scenario, overrides, style.russianPercent), scenario.id))
    }
}

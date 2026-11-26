package com.prism.music.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.foryou.MyWave
import com.prism.music.data.foryou.WaveScenarios
import com.prism.music.data.prefs.SavedWavePreset
import com.prism.music.ui.theme.PrismIcons

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

internal fun vibeShortcuts(
    style: MyWave.Style,
    saved: List<SavedWavePreset>,
    scenarios: List<WaveScenarios.Scenario>,
    overrides: Map<String, String>,
    activeSavedId: String?,
    activeScenarioId: String?,
): List<VibeShortcut> = buildList {
    val personal = personalVibe(style)
    add(VibeShortcut("personal", tr(R.string.prism_stream_station_default), PrismIcons.Stream,
        activeSavedId == null && activeScenarioId == null && style == personal, personal))
    add(VibeShortcut("tune", tr(R.string.prism_stream_tune), PrismIcons.Customize))
    saved.forEach { preset ->
        add(VibeShortcut("custom:${preset.id}", preset.name, PrismIcons.Bookmark,
            activeSavedId == preset.id, MyWave.style(preset.origin), preset.id))
    }
    scenarios.forEach { scenario ->
        add(VibeShortcut("scenario:${scenario.id}", scenario.label, PrismIcons.waveScenario(scenario.glyph),
            activeScenarioId == scenario.id, WaveScenarios.resolve(scenario, overrides, style.russianPercent), scenario.id))
    }
}

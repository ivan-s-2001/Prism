package com.prism.music.data.foryou

import com.prism.music.data.prefs.SavedWavePreset

/** A tuning session is isolated from live playback and persistent preferences until Done. */
internal data class WaveSettingsDraft(
    val style: MyWave.Style,
    val themeColors: Boolean,
    val presets: List<SavedWavePreset>,
    val presetId: String?,
    val scenarioStyles: Map<String, String> = emptyMap(),
) {
    /** Edits are scoped to the active scenario, but remain a draft until confirmation. */
    fun tune(value: MyWave.Style): WaveSettingsDraft {
        if (value == style) return this
        val id = presetId
        return when {
            id == null -> copy(style = value)
            presets.any { it.id == id } -> copy(style = value,
                presets = presets.map { if (it.id == id) it.copy(origin = value.origin) else it })
            WaveScenarios.all.any { it.id == id } -> copy(style = value,
                scenarioStyles = scenarioStyles + (id to value.origin))
            else -> copy(style = value, presetId = null)
        }
    }
    fun discovery(percent: Int) = tune(style.copy(discoveryPercent = percent.coerceIn(0, 100),
        character = when (percent) {
            0 -> MyWave.Character.FAMILIAR
            100 -> MyWave.Character.DISCOVERY
            else -> MyWave.Character.BALANCED
        }))
    fun choose(preset: SavedWavePreset) = copy(style = MyWave.style(preset.origin), presetId = preset.id)
    fun choose(scenario: WaveScenarios.Scenario) =
        copy(style = WaveScenarios.resolve(scenario, scenarioStyles, style.russianPercent), presetId = scenario.id)
    fun save(name: String, id: String = java.util.UUID.randomUUID().toString()): WaveSettingsDraft {
        val title = name.trim().take(40)
        require(title.isNotEmpty() && presets.none { it.name.equals(title, ignoreCase = true) })
        return copy(presets = presets + SavedWavePreset(id, title, style.origin), presetId = id)
    }
    fun delete(id: String) = copy(presets = presets.filterNot { it.id == id },
        presetId = presetId.takeUnless { it == id })
    fun reset(): WaveSettingsDraft {
        val scenario = WaveScenarios.all.firstOrNull { it.id == presetId }
        return if (scenario != null) copy(style = scenario.style.copy(russianPercent = style.russianPercent),
            scenarioStyles = scenarioStyles - scenario.id)
        else copy(style = MyWave.Style(), presetId = null)
    }
}

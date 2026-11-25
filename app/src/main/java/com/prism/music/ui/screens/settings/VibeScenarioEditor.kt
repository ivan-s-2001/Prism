package com.prism.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.foryou.WaveScenarios
import com.prism.music.ui.components.DragGrip
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.ReorderList
import com.prism.music.ui.screens.PrismCheck
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons

/** Only built-in shortcuts are arranged here. Saved mixes and the tuning button are never removed. */
@Composable
internal fun VibeScenarioEditor(stored: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val initial = remember(stored) { WaveScenarios.visibleOrder(WaveScenarios.all, stored).map { it.id } }
    var auto by remember(stored) { mutableStateOf(stored.isEmpty()) }
    var shown by remember(stored) { mutableStateOf(initial.toSet()) }
    var ordered by remember(stored) { mutableStateOf((initial + WaveScenarios.all.map { it.id }).distinct()) }
    val scenarios = remember { WaveScenarios.all.associateBy { it.id } }
    PrismSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = ui.gap(16.dp)).navigationBarsPadding()) {
            Text(tr(R.string.prism_stream_appearance_scenarios), style = MaterialTheme.typography.headlineSmall)
            SwitchRow(tr(R.string.prism_stream_appearance_auto_order), auto) { value ->
                auto = value
                if (value) { shown = scenarios.keys; ordered = WaveScenarios.all.map { it.id } }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (!auto) {
                    ReorderList(ordered, { it }, { ordered = it }) { id, _, _, modifier ->
                        val item = scenarios.getValue(id)
                        Row(modifier.fillMaxWidth().padding(vertical = ui.gap(8.dp)), verticalAlignment = Alignment.CenterVertically) {
                            PrismCheck(id in shown, label = item.label) { checked ->
                                shown = if (checked) shown + id else shown - id
                            }
                            Icon(PrismIcons.waveScenario(item.glyph), null, Modifier.padding(end = 10.dp))
                            Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            DragGrip(Modifier.dragHandle(), item.label)
                        }
                    }
                }
            }
            PrismButton(onClick = { val result = if (auto) "" else WaveScenarios.encodeOrder(ordered.filter { it in shown }); close { onSave(result) } },
                modifier = Modifier.fillMaxWidth().padding(vertical = ui.gap(8.dp))) { Text(tr(R.string.prism_stream_done)) }
        }
    }
}

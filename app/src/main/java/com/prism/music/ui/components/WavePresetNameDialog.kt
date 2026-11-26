package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.VibePresetIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun WavePresetNameDialog(
    names: List<String>, onDismiss: () -> Unit,
    initialName: String = "", initialIcon: String = "bookmark", editing: Boolean = false,
    onSave: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var iconId by rememberSaveable(initialIcon) { mutableStateOf(initialIcon) }
    var query by rememberSaveable { mutableStateOf("") }
    val cleaned = name.trim()
    val duplicate = names.any { it.equals(cleaned, ignoreCase = true) }
    val all by produceState(emptyList<String>(), context) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VibePresetIcons.names(context) }
    }
    val featured = remember { listOf("music", "headphones", "ripple", "heart", "star", "flame", "bolt", "moon", "sun", "cloud-rain", "coffee", "run", "car", "mountain", "planet", "sparkles", "guitar", "vinyl", "bookmark") }
    val results = remember(all, query) {
        val terms = query.lowercase().trim().split(Regex("[ -]+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) (featured.filter { it in all } + all).distinct()
        else all.filter { id -> terms.all { id.contains(it) } }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(tr(if (editing) R.string.prism_vibe_edit_preset else R.string.prism_stream_new_preset)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(VibePresetIcons.vector(context, iconId), null, Modifier.size(27.dp), tint = scheme.primary)
                    }
                    PrismTextField(value = name, onValueChange = { name = it.take(40) },
                        label = { Text(tr(R.string.prism_stream_name)) }, singleLine = true,
                        isError = duplicate, supportingText = { if (duplicate) Text(tr(R.string.prism_stream_name_already_used)) },
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                }
                PrismTextField(value = query, onValueChange = { query = it.take(80) },
                    label = { Text(tr(R.string.prism_vibe_icon_search)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text(tr(R.string.prism_vibe_icon_catalog, all.size), style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant)
                LazyVerticalGrid(columns = GridCells.Adaptive(46.dp), modifier = Modifier.fillMaxWidth().height(204.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(results, key = { it }) { id ->
                        var focused by remember { mutableStateOf(false) }
                        val selected = iconId == id
                        val shape = MaterialTheme.shapes.medium
                        Box(Modifier.height(48.dp).clip(shape)
                            .background(if (selected || focused) scheme.primary.copy(alpha = if (focused) 0.24f else 0.15f) else scheme.surfaceVariant.copy(alpha = 0.35f))
                            .border(1.dp, if (focused || selected) scheme.primary else androidx.compose.ui.graphics.Color.Transparent, shape)
                            .onFocusChanged { focused = it.isFocused }
                            .semantics { this.selected = selected }
                            .clickable { iconId = id }, contentAlignment = Alignment.Center) {
                            Icon(remember(id, context) { VibePresetIcons.vector(context, id) }, id.replace('-', ' '),
                                Modifier.size(26.dp), tint = if (selected || focused) scheme.primary else scheme.onSurface)
                        }
                    }
                }
                Text(iconId.replace('-', ' '), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        confirmButton = { PrismTextButton(enabled = cleaned.isNotEmpty() && !duplicate, onClick = { onSave(cleaned, iconId) }) { Text(tr(R.string.prism_stream_save)) } },
        dismissButton = { PrismTextButton(onClick = onDismiss) { Text(tr(R.string.prism_stream_cancel)) } })
}

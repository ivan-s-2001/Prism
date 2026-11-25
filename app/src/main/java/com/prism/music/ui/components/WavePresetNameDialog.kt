package com.prism.music.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction

@Composable
internal fun WavePresetNameDialog(ru: Boolean, names: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    fun l(a: String, b: String) = if (ru) a else b
    var name by rememberSaveable { mutableStateOf("") }
    val cleaned = name.trim()
    val duplicate = names.any { it.equals(cleaned, ignoreCase = true) }
    val focus = remember { FocusRequester() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(l("Новый пресет", "New preset")) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) },
                label = { Text(l("Название", "Name")) }, singleLine = true,
                isError = duplicate, supportingText = { if (duplicate) Text(l("Название уже занято", "Name already used")) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (cleaned.isNotEmpty() && !duplicate) onSave(cleaned) }))
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(enabled = cleaned.isNotEmpty() && !duplicate, onClick = { onSave(cleaned) }) { Text(l("Сохранить", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(l("Отмена", "Cancel")) } })
}

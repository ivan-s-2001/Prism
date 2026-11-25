package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr

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
internal fun WavePresetNameDialog(names: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {

    var name by rememberSaveable { mutableStateOf("") }
    val cleaned = name.trim()
    val duplicate = names.any { it.equals(cleaned, ignoreCase = true) }
    val focus = remember { FocusRequester() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(tr(R.string.prism_stream_new_preset)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) },
                label = { Text(tr(R.string.prism_stream_name)) }, singleLine = true,
                isError = duplicate, supportingText = { if (duplicate) Text(tr(R.string.prism_stream_name_already_used)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (cleaned.isNotEmpty() && !duplicate) onSave(cleaned) }))
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(enabled = cleaned.isNotEmpty() && !duplicate, onClick = { onSave(cleaned) }) { Text(tr(R.string.prism_stream_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.prism_stream_cancel)) } })
}

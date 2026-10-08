package com.c0mpile.grimmreader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.c0mpile.grimmreader.core.designsystem.icon.LucideIcons
import com.c0mpile.grimmreader.core.dictionary.Definition

/**
 * Definitions of a looked-up word from every installed dictionary. The word can be edited and looked up again
 * (to try another form). Definitions are rendered from sanitized HTML; links never open anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DictionarySheet(
    lookup: WordLookup,
    onLookUp: (String) -> Unit,
    onOpenDictionaries: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var text by rememberSaveable(lookup.query) { mutableStateOf(lookup.query) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Word") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onLookUp(text) }),
                trailingIcon = {
                    IconButton(onClick = { onLookUp(text) }) { Icon(LucideIcons.Search, contentDescription = "Look up") }
                },
            )
            val result = lookup.result
            when {
                result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                result.dictionaries == 0 -> {
                    Text("No dictionary installed yet. Download one or add your own StarDict files.")
                    OutlinedButton(onClick = onOpenDictionaries) { Text("Get dictionaries") }
                }
                result.definitions.isEmpty() ->
                    Text(
                        "No definition found for “${result.query}”.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> {
                    val matched = result.matched
                    if (matched != null && !matched.equals(result.query, ignoreCase = true)) {
                        Text(
                            "Showing results for “$matched”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(result.definitions) { DefinitionItem(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DefinitionItem(definition: Definition) {
    // A listener that does nothing: links in dictionary data are shown as text but never opened.
    val body = remember(definition.html) { AnnotatedString.fromHtml(definition.html, linkInteractionListener = {}) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(definition.dictionary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(definition.word, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

package io.github.barsicc.swapgps.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.barsicc.swapgps.places.Place

/** Ряд «Моих мест» под поиском: касание — перейти, долгое — переименовать или удалить. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlacesRow(places: List<Place>, onClick: (Place) -> Unit, onLongClick: (Place) -> Unit) {
    val haptic = LocalHapticFeedback.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(places, key = { it.id }) { place ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier.combinedClickable(
                    onClick = { onClick(place) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick(place)
                    },
                ),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(place.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Сохранить новое место или переименовать / удалить существующее. */
@Composable
fun PlaceDialog(state: PlaceDialogState, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    val initial = state.place?.name ?: ""
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Star, null) },
        title = { Text(if (state.place == null) "Сохранить место" else "Моё место") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.text.length <= 24) name = it },
                label = { Text("Короткое название") },
                placeholder = { Text(state.suggestedName?.take(24) ?: "Например, Дом") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.text.ifBlank { state.suggestedName?.take(24) ?: "Место" }) },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            if (state.place != null) {
                TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

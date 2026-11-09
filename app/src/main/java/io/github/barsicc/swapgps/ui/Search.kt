package io.github.barsicc.swapgps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.barsicc.swapgps.engine.GeoPoint
import io.github.barsicc.swapgps.map.MapController
import io.github.barsicc.swapgps.map.PlaceSearch
import io.github.barsicc.swapgps.map.Suggestion
import io.github.barsicc.swapgps.places.Place
import io.github.barsicc.swapgps.util.CoordinateParser
import kotlinx.coroutines.delay

/**
 * Строка поиска. Пустая и в фокусе — показывает «Мои места».
 * По мере ввода — подсказки Яндекса (ближайшие к видимой части карты — первыми).
 */
@Composable
fun SearchBox(
    search: PlaceSearch?,
    controller: MapController?,
    around: GeoPoint,
    places: List<Place>,
    onPoint: (GeoPoint) -> Unit,
    onSettings: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onPlaceLongClick: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // Номер запроса: ответы на устаревший текст игнорируются
    val generation = remember { java.util.concurrent.atomic.AtomicInteger() }

    fun close() {
        focus.clearFocus()
        suggestions = emptyList()
        error = null
    }

    fun pick(s: Suggestion) {
        close()
        query = s.title
        val area = controller?.visibleGeometry()
        if (s.point != null || search == null || area == null) {
            s.point?.let(onPoint)
        } else {
            search.resolve(s, area) { p ->
                if (p != null) onPoint(p) else error = "Не удалось найти это место"
            }
        }
    }

    // «Назад» сначала закрывает поиск
    BackHandler(enabled = focused) { close() }

    // Подсказки по мере ввода (с паузой, чтобы не дёргать сервер на каждую букву)
    LaunchedEffect(query, focused) {
        val current = generation.incrementAndGet()
        error = null
        val text = query.trim()
        if (!focused || text.isEmpty()) {
            search?.cancelSuggest()
            suggestions = emptyList()
            return@LaunchedEffect
        }
        CoordinateParser.parse(text)?.let { p ->
            suggestions = listOf(Suggestion("Координаты", p.format(), null, p, text, null))
            return@LaunchedEffect
        }
        if (text.length < 2 || search == null || controller == null) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        search.suggest(
            text = text,
            around = around,
            bounds = controller.visibleBounds(),
            onResult = { if (current == generation.get()) suggestions = it },
            onError = { if (current == generation.get()) error = it },
        )
    }

    val showPlaces = focused && query.isBlank() && places.isNotEmpty()
    val showSuggestions = focused && query.isNotBlank() && (suggestions.isNotEmpty() || error != null)

    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            if (search != null) "Адрес, место или координаты" else "Координаты или ссылка",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { suggestions.firstOrNull()?.let { pick(it) } }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            focused = it.isFocused
                            onFocusChange(it.isFocused)
                        },
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Очистить")
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                }
            }
        }

        AnimatedVisibility(
            visible = showPlaces || showSuggestions,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (showPlaces) {
                        item {
                            Text(
                                "Мои места",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(places, key = { it.id }) { p ->
                            ResultRow(
                                Icons.Filled.LocationOn, p.name, "Удерживайте, чтобы изменить", null,
                                onLongClick = { close(); onPlaceLongClick(p) },
                            ) {
                                close()
                                onPoint(p.point)
                            }
                        }
                    } else {
                        error?.let { e ->
                            item { Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                        }
                        items(suggestions) { s ->
                            ResultRow(Icons.Filled.Place, s.title, s.subtitle, s.distance) { pick(s) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ResultRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    trailing: String?,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

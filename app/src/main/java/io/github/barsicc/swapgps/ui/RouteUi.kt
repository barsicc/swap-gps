package io.github.barsicc.swapgps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.barsicc.swapgps.engine.GeoPoint
import io.github.barsicc.swapgps.engine.RouteProgress
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Маршрут, который построен, но поездка ещё не началась. */
data class RoutePreview(
    val points: List<GeoPoint> = emptyList(),
    val meters: Double = 0.0,
    val building: Boolean = true,
    val error: String? = null,
)

private const val MIN_SPEED = 20.0
private const val MAX_SPEED = 3000.0

/** Ползунок нелинейный: левая половина — обычные скорости, правая — до 3000 км/ч. */
private fun speedToPosition(kmh: Int): Float =
    (ln(kmh.coerceIn(MIN_SPEED.toInt(), MAX_SPEED.toInt()) / MIN_SPEED) / ln(MAX_SPEED / MIN_SPEED)).toFloat()

private fun positionToSpeed(position: Float): Int {
    val raw = MIN_SPEED * exp(position * ln(MAX_SPEED / MIN_SPEED))
    val step = when {
        raw < 100 -> 5
        raw < 1000 -> 10
        else -> 50
    }
    return ((raw / step).roundToInt() * step).coerceIn(MIN_SPEED.toInt(), MAX_SPEED.toInt())
}

fun formatKm(meters: Double): String =
    if (meters < 1000) "${(meters / 10).roundToInt() * 10} м"
    else String.format(java.util.Locale("ru"), "%.1f км", meters / 1000)

/** Примерное время в пути при выбранной скорости — в секундах, пока это меньше минуты. */
fun formatEta(meters: Double, speedKmh: Int): String {
    val seconds = (meters / (speedKmh / 3.6)).roundToInt().coerceAtLeast(1)
    return when {
        seconds < 60 -> "$seconds сек"
        seconds < 3600 -> "${seconds / 60} мин ${seconds % 60} сек"
        else -> "${seconds / 3600} ч ${seconds % 3600 / 60} мин"
    }
}

/** Выбор скорости: от 20 до 3000 км/ч. */
@Composable
fun SpeedSlider(speedKmh: Int, onChange: (Int) -> Unit, onChangeFinished: () -> Unit = {}) {
    var position by remember { mutableFloatStateOf(speedToPosition(speedKmh)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Скорость", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("$speedKmh км/ч", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = position,
            onValueChange = {
                position = it
                onChange(positionToSpeed(it))
            },
            onValueChangeFinished = onChangeFinished,
        )
    }
}

/** Панель построенного маршрута: длина, время, скорость, «Поехать». */
@Composable
fun RoutePreviewContent(
    preview: RoutePreview,
    speedKmh: Int,
    onSpeed: (Int) -> Unit,
    onGo: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            preview.building -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text("Строим маршрут по дорогам…", style = MaterialTheme.typography.titleMedium)
            }
            preview.error != null -> Text(
                preview.error,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Column {
                Text(
                    "Маршрут · ${formatKm(preview.meters)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "≈ ${formatEta(preview.meters, speedKmh)} в пути",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!preview.building && preview.error == null) {
            SpeedSlider(speedKmh, onChange = onSpeed)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text("Отмена", fontSize = 16.sp) }
            Button(
                onClick = onGo,
                enabled = !preview.building && preview.error == null,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text("Поехать", fontSize = 16.sp) }
        }
    }
}

/** Панель во время поездки: сколько осталось, скорость, пауза, остановка. */
@Composable
fun DrivingContent(
    route: RouteProgress,
    speedKmh: Int,
    onSpeed: (Int) -> Unit,
    onSpeedFinished: () -> Unit,
    onFinish: () -> Unit,
    onStopHere: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(Color(0xFF2E7D32), CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "В пути · ${route.currentKmh} км/ч",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Text(
                "Осталось ${formatKm(route.remainingMeters)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "≈ ${formatEta(route.remainingMeters, speedKmh)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val progress = if (route.totalMeters > 0) (1 - route.remainingMeters / route.totalMeters).toFloat() else 0f
        LinearProgressIndicator(
            progress = { progress },
            strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        SpeedSlider(speedKmh, onChange = onSpeed, onChangeFinished = onSpeedFinished)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = onFinish,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text("Сразу в конец", fontSize = 16.sp) }
            FilledTonalButton(
                onClick = onStopHere,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text("Стоп здесь", fontSize = 16.sp) }
        }
    }
}

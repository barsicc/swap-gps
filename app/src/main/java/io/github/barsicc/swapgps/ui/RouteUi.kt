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
import kotlin.math.roundToInt

/** Маршрут, который построен, но поездка ещё не началась. */
data class RoutePreview(
    val points: List<GeoPoint> = emptyList(),
    val meters: Double = 0.0,
    val building: Boolean = true,
    val error: String? = null,
)

private const val MIN_SPEED = 10f
private const val MAX_SPEED = 150f

fun formatKm(meters: Double): String =
    if (meters < 1000) "${(meters / 10).roundToInt() * 10} м"
    else String.format(java.util.Locale("ru"), "%.1f км", meters / 1000)

/** Примерное время в пути при выбранной скорости. */
fun formatEta(meters: Double, speedKmh: Int): String {
    val minutes = (meters / (speedKmh / 3.6) / 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
}

/** Выбор скорости: ползунок с шагом 5 км/ч. */
@Composable
fun SpeedSlider(speedKmh: Int, onChange: (Int) -> Unit, onChangeFinished: () -> Unit = {}) {
    var value by remember(speedKmh) { mutableFloatStateOf(speedKmh.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Скорость", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${value.roundToInt()} км/ч", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = {
                value = it
                onChange((it / 5).roundToInt() * 5)
            },
            onValueChangeFinished = onChangeFinished,
            valueRange = MIN_SPEED..MAX_SPEED,
            steps = ((MAX_SPEED - MIN_SPEED) / 5).toInt() - 1,
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
    onPause: () -> Unit,
    onStopHere: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (route.paused) MaterialTheme.colorScheme.outline else Color(0xFF2E7D32), CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (route.paused) "Пауза" else "В пути · ${route.currentKmh} км/ч",
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
                onClick = onPause,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text(if (route.paused) "Продолжить" else "Пауза", fontSize = 16.sp) }
            FilledTonalButton(
                onClick = onStopHere,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text("Стоп здесь", fontSize = 16.sp) }
        }
    }
}

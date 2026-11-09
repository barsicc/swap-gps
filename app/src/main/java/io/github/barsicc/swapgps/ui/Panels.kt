package io.github.barsicc.swapgps.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.barsicc.swapgps.engine.Diag
import io.github.barsicc.swapgps.engine.MockEngine
import io.github.barsicc.swapgps.engine.SpoofState
import io.github.barsicc.swapgps.engine.SpoofStatus
import io.github.barsicc.swapgps.setup.DeviceSetup
import io.github.barsicc.swapgps.util.CoordinateParser
import kotlinx.coroutines.delay

/** Что из настроек телефона готово для подмены. */
internal data class Checks(
    val mockApp: Boolean,
    val location: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
) {
    val allGood get() = mockApp && location && notifications && battery
}

internal fun readChecks(context: Context) = Checks(
    mockApp = MockEngine.isSelectedMockApp(context),
    location = MockEngine.hasLocationPermission(context),
    notifications = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
    battery = DeviceSetup.isIgnoringBatteryOptimizations(context),
)

@Composable
internal fun SetupCard(checks: Checks, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Настройка телефона", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CheckRow(
                ok = checks.location && checks.notifications,
                title = "Разрешения: геолокация и уведомления",
                action = "Разрешить",
                onAction = onRequestPermissions,
            )
            CheckRow(
                ok = checks.mockApp,
                title = "«Для разработчиков» → «Приложение для фиктивных местоположений» → Swap GPS",
                action = "Открыть",
                onAction = { DeviceSetup.openDeveloperSettings(context) },
            )
            CheckRow(
                ok = checks.battery,
                title = "Батарея: без ограничений, чтобы подмена не отключалась",
                action = "Открыть",
                onAction = { DeviceSetup.openBatterySettings(context) },
            )
            // Не обязательно, но убирает редкие скачки в приложениях с собственным поиском по Wi-Fi
            CheckRow(
                ok = if (DeviceSetup.isScanningForLocationOn(context)) null else true,
                title = "Совет: выключите «Поиск сетей Wi-Fi» и «Поиск Bluetooth» в настройках местоположения — меньше скачков",
                action = "Открыть",
                onAction = { DeviceSetup.openScanningSettings(context) },
            )
            if (DeviceSetup.isXiaomi) {
                // Проверить автозапуск Xiaomi нельзя — отмечаем выполненным, когда пользователь в него зашёл
                var autostartDone by remember { mutableStateOf(DeviceSetup.isAutostartConfirmed(context)) }
                CheckRow(
                    ok = autostartDone,
                    title = "Xiaomi: «Автозапуск» для Swap GPS",
                    action = "Открыть",
                    onAction = {
                        DeviceSetup.openAutostartSettings(context)
                        DeviceSetup.setAutostartConfirmed(context)
                        autostartDone = true
                    },
                )
            }
        }
    }
}

/** ok == null — состояние проверить нельзя (например, автозапуск Xiaomi). */
@Composable
private fun CheckRow(ok: Boolean?, title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok == true) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = when (ok) {
                true -> Color(0xFF2E7D32)
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (ok != true) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
internal fun SettingsCard() {
    val context = LocalContext.current
    var google by remember { mutableStateOf(SpoofState.isGoogleMockEnabled(context)) }
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Подмена в сервисах Google", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Fused provider. Убирает скачки из-за Wi-Fi. Применяется при следующем запуске подмены",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = google, onCheckedChange = {
                google = it
                SpoofState.setGoogleMockEnabled(context, it)
            })
        }
    }
}

@Composable
internal fun DiagnosticsCard() {
    val lines by Diag.lines.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Диагностика", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (expanded && lines.isNotEmpty()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(Diag.asText())) }) { Text("Копировать") }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    if (lines.isEmpty()) {
                        Text("Пока пусто", style = MaterialTheme.typography.bodySmall)
                    }
                    lines.takeLast(40).reversed().forEach {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

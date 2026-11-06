package io.github.barsicc.swapgps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.yandex.mapkit.mapview.MapView
import io.github.barsicc.swapgps.R
import io.github.barsicc.swapgps.engine.Diag
import io.github.barsicc.swapgps.engine.GeoPoint
import io.github.barsicc.swapgps.engine.MockEngine
import io.github.barsicc.swapgps.engine.SpoofState
import io.github.barsicc.swapgps.engine.SpoofStatus
import io.github.barsicc.swapgps.map.CameraState
import io.github.barsicc.swapgps.map.MapController
import io.github.barsicc.swapgps.map.PlaceSearch
import io.github.barsicc.swapgps.map.Suggestion
import io.github.barsicc.swapgps.util.CoordinateParser
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(mapView: MapView?, controller: MapController?) {
    val context = LocalContext.current
    val status by SpoofState.status.collectAsStateWithLifecycle()

    // Перепроверяем настройки телефона при каждом возвращении в приложение
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }
    val checks = remember(resumeCount, status) { readChecks(context) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeCount++
    }
    val requestPermissions: () -> Unit = {
        val list = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(list.toTypedArray())
    }

    val search = remember(mapView) {
        if (mapView == null) null
        else runCatching { PlaceSearch() }.onFailure { Diag.log("Поиск Яндекса недоступен", it) }.getOrNull()
    }

    val fallbackCamera = remember { CameraState(SpoofState.lastPoint(context) ?: MapController.MOSCOW, 16f, false) }
    val camera by (controller?.camera ?: remember { kotlinx.coroutines.flow.MutableStateFlow(fallbackCamera) })
        .collectAsState()

    // Без карты точка берётся из поля поиска
    var manualPoint by remember { mutableStateOf<GeoPoint?>(SpoofState.lastPoint(context)) }
    val target: GeoPoint? = if (controller != null) camera.center else manualPoint

    var showSettings by remember { mutableStateOf(false) }

    // Тёмная карта в тёмной теме
    val night = isSystemInDarkTheme()
    LaunchedEffect(controller, night) { controller?.setNightMode(night) }

    // Первое положение карты: активная подмена → реальное местоположение → последняя точка
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        val active = SpoofState.status.value.point
        if (active != null) {
            c.moveTo(active, animated = false)
        } else {
            c.moveTo(SpoofState.lastPoint(context) ?: MapController.MOSCOW, MapController.DEFAULT_ZOOM, animated = false)
            moveToDeviceLocation(context, c, animated = false)
        }
    }

    // Точка подмены на карте
    val markerColor = MaterialTheme.colorScheme.primary.toArgb()
    LaunchedEffect(controller, status.active, status.point) {
        controller?.showSpoofPoint(if (status.active) status.point else null, markerColor)
    }

    // Адрес под меткой
    var address by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(camera.center, camera.moving, search) {
        if (camera.moving || search == null) return@LaunchedEffect
        delay(350)
        search.address(camera.center, camera.zoom.toInt()) { address = it }
    }

    fun startSpoof() {
        val point = target
        when {
            point == null -> SpoofState.setError("Выберите точку: передвиньте карту или введите координаты")
            !checks.location -> requestPermissions()
            !checks.mockApp -> {
                SpoofState.setError("Выберите Swap GPS для фиктивных местоположений")
                showSettings = true
            }
            else -> {
                SpoofState.setError(null)
                MockEngine.start(context, point)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (mapView != null) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            CenterPin(moving = camera.moving, modifier = Modifier.align(Alignment.Center))
        } else {
            NoMapHint(Modifier.align(Alignment.Center))
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBox(
                search = search,
                controller = controller,
                around = camera.center,
                onPoint = { point ->
                    if (controller != null) controller.moveTo(point) else manualPoint = point
                },
                onSettings = { showSettings = true },
            )
            AnimatedVisibility(visible = !checks.allGood, enter = fadeIn(), exit = fadeOut()) {
                Chip(
                    text = "Нужна настройка телефона",
                    color = MaterialTheme.colorScheme.errorContainer,
                    icon = { Icon(Icons.Filled.Warning, null, Modifier.size(18.dp)) },
                    onClick = { showSettings = true },
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (controller != null) {
                SmallFloatingActionButton(
                    onClick = { moveToDeviceLocation(context, controller, animated = true) },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 12.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_my_location), contentDescription = "Моё местоположение")
                }
            }
            BottomPanel(
                status = status,
                target = target,
                address = if (camera.moving) null else address,
                hasMap = controller != null,
                onStart = ::startSpoof,
                onReset = { MockEngine.reset(context) },
            )
        }
    }

    if (showSettings) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = sheetState) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                SetupCard(checks, onRequestPermissions = requestPermissions)
                SettingsCard()
                DiagnosticsCard()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Переместить карту туда, где телефон сейчас (во время подмены — в подменённую точку). */
@SuppressLint("MissingPermission")
private fun moveToDeviceLocation(context: android.content.Context, controller: MapController, animated: Boolean) {
    if (!MockEngine.hasLocationPermission(context)) return
    LocationServices.getFusedLocationProviderClient(context).lastLocation
        .addOnSuccessListener { loc ->
            if (loc != null) controller.moveTo(GeoPoint(loc.latitude, loc.longitude), MapController.DEFAULT_ZOOM, animated)
        }
}

/** Метка в центре карты. Приподнимается, пока карту двигают. */
@Composable
private fun CenterPin(moving: Boolean, modifier: Modifier = Modifier) {
    val lift by animateDpAsState(if (moving) 14.dp else 0.dp, label = "lift")
    val shadowAlpha by animateFloatAsState(if (moving) 0.35f else 0.2f, label = "shadow")
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 10.dp, height = 5.dp)
                .alpha(shadowAlpha)
                .background(Color.Black, CircleShape),
        )
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(48.dp)
                .offset(y = -22.dp - lift),
        )
    }
}

@Composable
private fun NoMapHint(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Place, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "Карта недоступна. Вставьте координаты или ссылку на карту в поле сверху",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchBox(
    search: PlaceSearch?,
    controller: MapController?,
    around: GeoPoint,
    onPoint: (GeoPoint) -> Unit,
    onSettings: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pick(s: Suggestion) {
        focus.clearFocus()
        query = s.title
        suggestions = emptyList()
        val area = controller?.visibleGeometry()
        if (s.point != null || search == null || area == null) {
            s.point?.let(onPoint)
        } else {
            search.resolve(s, area) { p ->
                if (p != null) onPoint(p) else error = "Не удалось найти это место"
            }
        }
    }

    // Подсказки по мере ввода (с небольшой паузой, чтобы не дёргать сервер на каждую букву)
    LaunchedEffect(query, focused) {
        error = null
        if (!focused || query.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        CoordinateParser.parse(query)?.let { p ->
            suggestions = listOf(Suggestion("Координаты", p.format(), null, p, query, null))
            return@LaunchedEffect
        }
        if (query.trim().length < 2 || search == null || controller == null) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        search.suggest(
            text = query,
            around = around,
            bounds = controller.visibleBounds(),
            onResult = { suggestions = it },
            onError = { error = it },
        )
    }

    Column {
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
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; suggestions = emptyList() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Очистить")
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                }
            }
        }

        AnimatedVisibility(
            visible = focused && (suggestions.isNotEmpty() || error != null),
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
                    error?.let { e ->
                        item {
                            Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                        }
                    }
                    items(suggestions) { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pick(s) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                s.subtitle?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            s.distance?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = color, shadowElevation = 3.dp, onClick = onClick) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BottomPanel(
    status: SpoofStatus,
    target: GeoPoint?,
    address: String?,
    hasMap: Boolean,
    onStart: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActiveLine(status)

            Column {
                Text(
                    text = when {
                        address != null -> address
                        hasMap -> "…"
                        else -> "Точка"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    target?.format() ?: "не выбрана",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            status.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (status.active) "Перенести" else "Подменить", fontSize = 16.sp)
                }
                Button(
                    onClick = onReset,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Сброс", fontSize = 16.sp)
                }
            }
        }
    }
}

/** Строка «Подмена активна · 03:12» или предупреждение о прерванной подмене. */
@Composable
private fun ActiveLine(status: SpoofStatus) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.active) {
        while (status.active) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val (dot, text) = when {
        status.active -> Color(0xFF2E7D32) to "Подмена активна · " +
            DateUtils.formatElapsedTime((now - status.startedAt).coerceAtLeast(0) / 1000)
        status.interrupted -> MaterialTheme.colorScheme.error to "Подмена прервана системой — нажмите «Сброс»"
        else -> MaterialTheme.colorScheme.outline to "Реальное местоположение"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

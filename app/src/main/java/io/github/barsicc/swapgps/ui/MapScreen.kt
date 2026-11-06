package io.github.barsicc.swapgps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import io.github.barsicc.swapgps.engine.Notice
import io.github.barsicc.swapgps.engine.SpoofState
import io.github.barsicc.swapgps.engine.SpoofStatus
import io.github.barsicc.swapgps.map.CameraState
import io.github.barsicc.swapgps.map.MapController
import io.github.barsicc.swapgps.map.PlaceSearch
import io.github.barsicc.swapgps.map.RouteBuilder
import io.github.barsicc.swapgps.engine.RouteMovement
import io.github.barsicc.swapgps.engine.SpoofService
import io.github.barsicc.swapgps.places.Place
import io.github.barsicc.swapgps.places.Places
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(mapView: MapView?, controller: MapController?) {
    val context = LocalContext.current
    val status by SpoofState.status.collectAsStateWithLifecycle()
    val notice by SpoofState.notice.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { Places.load(context) }
    val places by Places.items.collectAsStateWithLifecycle()

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
    val camera by (controller?.camera ?: remember { MutableStateFlow(fallbackCamera) }).collectAsState()

    // Без карты точка берётся из поля поиска
    var manualPoint by remember { mutableStateOf(SpoofState.lastPoint(context)) }
    val target: GeoPoint? = if (controller != null) camera.center else manualPoint

    var showSettings by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    // Диалог места: null — закрыт; place == null — новое место
    var placeDialog by remember { mutableStateOf<PlaceDialogState?>(null) }

    // Маршрут: предпросмотр до поездки и линия текущей поездки
    val routeBuilder = remember { RouteBuilder() }
    var preview by remember { mutableStateOf<RoutePreview?>(null) }
    val routeLine by SpoofState.routeLine.collectAsStateWithLifecycle()
    val driving = status.route != null
    var speedKmh by remember { mutableIntStateOf(SpoofState.routeSpeed(context)) }

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

    val markerColor = MaterialTheme.colorScheme.primary.toArgb()
    LaunchedEffect(controller, status.active, status.point) {
        controller?.showSpoofPoint(if (status.active) status.point else null, markerColor)
    }

    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    LaunchedEffect(controller, preview?.points, routeLine) {
        controller?.showRoute(preview?.points?.takeIf { it.isNotEmpty() } ?: routeLine, routeColor)
    }
    LaunchedEffect(driving) { if (driving) controller?.resumeFollow() }
    LaunchedEffect(status.point, driving) {
        if (driving) status.point?.let { controller?.follow(it) }
    }

    // Адрес под меткой
    var address by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(camera.center, camera.moving, search, driving) {
        if (camera.moving || search == null || driving) return@LaunchedEffect
        delay(350)
        search.address(camera.center, camera.zoom.toInt()) { address = it }
    }

    // Карту начали двигать — закрываем поиск, чтобы подсказки не висели поверх
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(camera.moving) {
        if (camera.moving && searchFocused) focusManager.clearFocus()
    }

    val goTo: (GeoPoint) -> Unit = { point ->
        if (controller != null) controller.moveTo(point) else manualPoint = point
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

    /** Построить маршрут от текущей подменённой точки (или от реального местоположения) до метки. */
    fun buildRoute() {
        val destination = target ?: return
        when {
            !checks.location -> { requestPermissions(); return }
            !checks.mockApp -> {
                SpoofState.setError("Выберите Swap GPS для фиктивных местоположений")
                showSettings = true
                return
            }
        }
        SpoofState.setError(null)
        preview = RoutePreview()
        val build: (GeoPoint) -> Unit = { from ->
            if (RouteMovement.distance(from, destination) < 30) {
                preview = RoutePreview(building = false, error = "Передвиньте карту к месту назначения")
            } else {
                routeBuilder.build(from, destination) { route, error ->
                    if (preview == null) return@build // уже отменили
                    preview = if (route != null) {
                        controller?.showWhole(route.points)
                        RoutePreview(route.points, route.meters, building = false)
                    } else {
                        RoutePreview(building = false, error = error ?: "Не удалось построить маршрут")
                    }
                }
            }
        }
        val active = status.point.takeIf { status.active }
        if (active != null) {
            build(active)
        } else {
            currentLocation(context) { from ->
                if (from != null) build(from)
                else preview = RoutePreview(building = false, error = "Не удалось определить, откуда ехать. Включите подмену в начальной точке")
            }
        }
    }

    val savedHere = Places.at(target).takeIf { places.isNotEmpty() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (mapView != null) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (preview == null && !driving) {
                CenterPin(moving = camera.moving, modifier = Modifier.align(Alignment.Center))
            }
        } else {
            NoMapHint(Modifier.align(Alignment.Center))
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBox(
                search = search,
                controller = controller,
                around = camera.center,
                places = places,
                onPoint = goTo,
                onSettings = { showSettings = true },
                onFocusChange = { searchFocused = it },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            AnimatedVisibility(
                visible = !searchFocused && places.isNotEmpty() && preview == null && !driving,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PlacesRow(
                    places = places,
                    onClick = { goTo(it.point) },
                    onLongClick = { placeDialog = PlaceDialogState(it, it.point) },
                )
            }
            AnimatedVisibility(visible = !searchFocused && !checks.allGood, enter = fadeIn(), exit = fadeOut()) {
                Pill(
                    text = "Нужна настройка телефона",
                    color = MaterialTheme.colorScheme.errorContainer,
                    icon = Icons.Filled.Warning,
                    onClick = { showSettings = true },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            NoticeBanner(notice, modifier = Modifier.padding(horizontal = 12.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (controller != null) {
                SmallFloatingActionButton(
                    onClick = {
                        val p = status.point
                        if (driving && p != null) {
                            controller.resumeFollow()
                            controller.follow(p)
                        } else {
                            moveToDeviceLocation(context, controller, animated = true)
                        }
                    },
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
                title = savedHere?.name ?: if (camera.moving) null else address,
                saved = savedHere != null,
                hasMap = controller != null,
                onSave = {
                    target?.let { placeDialog = PlaceDialogState(savedHere, it, suggestedName = address) }
                },
                onStart = ::startSpoof,
                onRoute = ::buildRoute,
                onReset = {
                    preview = null
                    routeBuilder.cancel()
                    MockEngine.reset(context)
                },
                routeContent = when {
                    preview != null -> {
                        {
                            RoutePreviewContent(
                                preview = preview!!,
                                speedKmh = speedKmh,
                                onSpeed = {
                                    speedKmh = it
                                    SpoofState.setRouteSpeed(context, it)
                                },
                                onGo = {
                                    preview?.points?.let { MockEngine.startRoute(context, it) }
                                    preview = null
                                },
                                onCancel = {
                                    routeBuilder.cancel()
                                    preview = null
                                },
                            )
                        }
                    }
                    driving -> {
                        {
                            DrivingContent(
                                route = status.route!!,
                                speedKmh = speedKmh,
                                onSpeed = {
                                    speedKmh = it
                                    SpoofState.setRouteSpeed(context, it)
                                },
                                onSpeedFinished = { MockEngine.routeCommand(context, SpoofService.ACTION_SPEED, speedKmh) },
                                onFinish = { MockEngine.routeCommand(context, SpoofService.ACTION_FINISH) },
                                onStopHere = { MockEngine.routeCommand(context, SpoofService.ACTION_STOP_HERE) },
                            )
                        }
                    }
                    else -> null
                },
            )
        }
    }

    placeDialog?.let { state ->
        PlaceDialog(
            state = state,
            onDismiss = { placeDialog = null },
            onSave = { name ->
                if (state.place == null) Places.add(context, name, state.point)
                else Places.rename(context, state.place.id, name)
                placeDialog = null
            },
            onDelete = {
                state.place?.let { Places.delete(context, it.id) }
                placeDialog = null
            },
        )
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
private fun moveToDeviceLocation(context: Context, controller: MapController, animated: Boolean) {
    if (!MockEngine.hasLocationPermission(context)) return
    LocationServices.getFusedLocationProviderClient(context).lastLocation
        .addOnSuccessListener { loc ->
            if (loc != null) controller.moveTo(GeoPoint(loc.latitude, loc.longitude), MapController.DEFAULT_ZOOM, animated)
        }
}

/** Где телефон сейчас (реальное местоположение, если подмена выключена). */
@SuppressLint("MissingPermission")
private fun currentLocation(context: Context, onResult: (GeoPoint?) -> Unit) {
    if (!MockEngine.hasLocationPermission(context)) return onResult(null)
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.lastLocation
        .addOnSuccessListener { loc ->
            if (loc != null) onResult(GeoPoint(loc.latitude, loc.longitude))
            else client.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { cur -> onResult(cur?.let { GeoPoint(it.latitude, it.longitude) }) }
                .addOnFailureListener { onResult(null) }
        }
        .addOnFailureListener { onResult(null) }
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
private fun Pill(
    text: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (onClick != null) {
        Surface(shape = RoundedCornerShape(20.dp), color = color, shadowElevation = 3.dp, onClick = onClick, modifier = modifier, content = content)
    } else {
        Surface(shape = RoundedCornerShape(20.dp), color = color, shadowElevation = 3.dp, modifier = modifier, content = content)
    }
}

/** Плашка-сообщение («Готово: ваше реальное местоположение вернулось»), сама исчезает. */
@Composable
private fun NoticeBanner(notice: Notice?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf<Notice?>(null) }
    // Старые сообщения (до открытия экрана) не показываем повторно
    val since = remember { System.nanoTime() }
    LaunchedEffect(notice?.id) {
        if (notice == null || notice.id < since) return@LaunchedEffect
        visible = notice
        delay(if (notice.success) 3500 else 6000)
        visible = null
    }
    AnimatedVisibility(
        visible = visible != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val n = visible ?: notice ?: return@AnimatedVisibility
        Pill(
            text = n.text,
            color = if (n.success) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
            icon = if (n.success) Icons.Filled.CheckCircle else Icons.Filled.Info,
        )
    }
}

@Composable
private fun BottomPanel(
    status: SpoofStatus,
    target: GeoPoint?,
    title: String?,
    saved: Boolean,
    hasMap: Boolean,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onRoute: () -> Unit,
    onReset: () -> Unit,
    /** Содержимое для маршрута (предпросмотр или поездка) вместо обычного. */
    routeContent: (@Composable () -> Unit)?,
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
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (routeContent != null) {
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                routeContent()
                // Во время поездки вернуть реальное местоположение можно в любой момент
                if (status.route != null) ResetButton(prominent = true, onReset = onReset)
            }
          } else {
            ActiveLine(status)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title ?: if (hasMap) "…" else "Точка",
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
                IconButton(onClick = onSave, enabled = target != null) {
                    Icon(
                        // Закрашенная — только когда метка стоит на сохранённом месте
                        if (saved) rememberVectorPainter(Icons.Filled.Star) else painterResource(R.drawable.ic_star_border),
                        contentDescription = if (saved) "Изменить место" else "Сохранить в «Мои места»",
                        tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            status.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStart,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f).height(54.dp),
                    ) {
                        Text(if (status.active) "Перенести сюда" else "Подменить сюда", fontSize = 16.sp)
                    }
                    FilledTonalButton(
                        onClick = onRoute,
                        enabled = hasMap,
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(54.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_route), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Маршрут", fontSize = 15.sp)
                    }
                }
                // Во время подмены кнопка возврата заметнее, без подмены — спокойнее
                ResetButton(prominent = status.active || status.interrupted, onReset = onReset)
            }
          }
        }
    }
}

@Composable
private fun ResetButton(prominent: Boolean, onReset: () -> Unit) {
    if (prominent) {
        Button(
            onClick = onReset,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Вернуть реальное местоположение", fontSize = 16.sp)
        }
    } else {
        FilledTonalButton(
            onClick = onReset,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("Вернуть реальное местоположение", fontSize = 15.sp)
        }
    }
}

/** «Подмена активна · 03:12» — только во время подмены (или если её прервала система). */
@Composable
private fun ActiveLine(status: SpoofStatus) {
    if (!status.active && !status.interrupted) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.active) {
        while (status.active) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val (dot, text) = if (status.active) {
        Color(0xFF2E7D32) to "Подмена активна · " + DateUtils.formatElapsedTime((now - status.startedAt).coerceAtLeast(0) / 1000)
    } else {
        MaterialTheme.colorScheme.error to "Подмену остановила система — верните реальное местоположение"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Состояние диалога места: place == null — сохраняем новое. */
data class PlaceDialogState(val place: Place?, val point: GeoPoint, val suggestedName: String? = null)

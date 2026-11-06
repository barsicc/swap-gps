package io.github.barsicc.swapgps.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.mapview.MapView
import io.github.barsicc.swapgps.BuildConfig
import io.github.barsicc.swapgps.engine.Diag
import io.github.barsicc.swapgps.map.MapController
import io.github.barsicc.swapgps.ui.theme.SwapTheme

class MainActivity : ComponentActivity() {

    /** null — карта недоступна (нет ключа или не загрузилась), остальное работает. */
    private var mapView: MapView? = null
    private var mapController: MapController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (BuildConfig.MAPKIT_KEY.isNotBlank()) {
            try {
                MapKitFactory.initialize(this)
                mapView = MapView(this).also { mapController = MapController(it) }
            } catch (e: Throwable) {
                Diag.log("Карта не загрузилась", e)
            }
        } else {
            Diag.log("Нет ключа Яндекс MapKit — карта отключена")
        }

        setContent {
            SwapTheme {
                MapScreen(mapView = mapView, controller = mapController)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (mapView != null) {
            MapKitFactory.getInstance().onStart()
            mapView?.onStart()
        }
    }

    override fun onStop() {
        if (mapView != null) {
            mapView?.onStop()
            MapKitFactory.getInstance().onStop()
        }
        super.onStop()
    }
}

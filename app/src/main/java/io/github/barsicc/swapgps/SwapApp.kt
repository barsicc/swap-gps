package io.github.barsicc.swapgps

import android.app.Application
import com.yandex.mapkit.MapKitFactory
import io.github.barsicc.swapgps.engine.Diag
import io.github.barsicc.swapgps.engine.SpoofService
import io.github.barsicc.swapgps.engine.SpoofState

class SwapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpoofService.createChannel(this)
        SpoofState.restore(this)

        if (BuildConfig.MAPKIT_KEY.isNotBlank()) {
            try {
                MapKitFactory.setApiKey(BuildConfig.MAPKIT_KEY)
                MapKitFactory.setLocale("ru_RU")
            } catch (e: Throwable) {
                Diag.log("Не удалось настроить карту", e)
            }
        }
    }
}

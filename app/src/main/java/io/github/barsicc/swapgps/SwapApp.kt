package io.github.barsicc.swapgps

import android.app.Application
import io.github.barsicc.swapgps.engine.SpoofService
import io.github.barsicc.swapgps.engine.SpoofState

class SwapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpoofService.createChannel(this)
        SpoofState.restore(this)
    }
}

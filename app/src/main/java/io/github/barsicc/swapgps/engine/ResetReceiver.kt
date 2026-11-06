package io.github.barsicc.swapgps.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Кнопка «Сбросить» в уведомлении. */
class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MockEngine.reset(context)
    }
}

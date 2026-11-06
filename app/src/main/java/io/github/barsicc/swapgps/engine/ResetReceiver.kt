package io.github.barsicc.swapgps.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Кнопка «Сбросить» в уведомлении. */
class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MockEngine.reset(context)
        android.widget.Toast.makeText(context, "Подмена выключена, возвращаем реальное местоположение", android.widget.Toast.LENGTH_SHORT).show()
    }
}

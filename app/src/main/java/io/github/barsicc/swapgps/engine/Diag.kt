package io.github.barsicc.swapgps.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал диагностики: последние события движка подмены.
 * Показывается на экране приложения, чтобы по скриншоту было видно, что пошло не так.
 */
object Diag {
    private const val TAG = "SwapGps"
    private const val MAX_LINES = 150

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(message: String, error: Throwable? = null) {
        val text = if (error != null) "$message: ${error.javaClass.simpleName} ${error.message.orEmpty()}" else message
        if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message)
        val line = "${time.format(Date())}  $text"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun asText(): String = _lines.value.joinToString("\n")
}

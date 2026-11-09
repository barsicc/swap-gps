package io.github.barsicc.swapgps.setup

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Переходы в системные настройки, которые нужны для стабильной подмены. */
object DeviceSetup {

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** «Для разработчиков», где выбирается приложение для фиктивных местоположений. */
    fun openDeveloperSettings(context: Context) {
        launch(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) ||
            launch(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }

    /** Отключить ограничения батареи для приложения. */
    @SuppressLint("BatteryLife")
    fun openBatterySettings(context: Context) {
        launch(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) ||
            openAppDetails(context)
    }

    /** Автозапуск на Xiaomi / Redmi / POCO. */
    fun openAutostartSettings(context: Context) {
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        launch(context, miui) || openAppDetails(context)
    }

    fun isAutostartConfirmed(context: Context): Boolean =
        context.getSharedPreferences("setup", Context.MODE_PRIVATE).getBoolean("autostart", false)

    fun setAutostartConfirmed(context: Context) {
        context.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("autostart", true).apply()
    }

    /**
     * Включён ли фоновый поиск Wi-Fi / Bluetooth для определения местоположения.
     * Некоторые приложения (например, Яндекс.Карты) по нему сами находят реальное место
     * в обход подмены — отсюда редкие скачки.
     */
    fun isScanningForLocationOn(context: Context): Boolean {
        val cr = context.contentResolver
        val wifi = runCatching { Settings.Global.getInt(cr, "wifi_scan_always_enabled", 0) }.getOrDefault(0)
        val ble = runCatching { Settings.Global.getInt(cr, "ble_scan_always_enabled", 0) }.getOrDefault(0)
        return wifi == 1 || ble == 1
    }

    fun openScanningSettings(context: Context) {
        launch(context, Intent("android.settings.LOCATION_SCANNING_SETTINGS")) || openLocationSettings(context).let { true }
    }

    fun openAppDetails(context: Context): Boolean =
        launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    fun openLocationSettings(context: Context) {
        launch(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }


    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

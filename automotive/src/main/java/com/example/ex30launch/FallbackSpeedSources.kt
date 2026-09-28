package com.example.ex30launch

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat

/**
 * Yedek 1: Car App Library'nin host uzerinden verdigi hiz.
 * VHAL'e dogrudan baglanamadigimiz durumlarda devreye girer. Ornek zamani
 * host'tan gelmedigi icin callback ani damgalanir — bu yuzden VHAL'den bir
 * tik daha az hassastir.
 */
class CarInfoSpeedSource(
    private val carContext: CarContext,
    private val onSample: (mps: Float, tNanos: Long, display: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "SprintSpeed"
    }

    private var listener: OnCarDataAvailableListener<Speed>? = null
    var running = false
        private set
    var lastError: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            val l = OnCarDataAvailableListener<Speed> { speed ->
                // Iki kanali da ayri ayri bildir; hangisinin olcume girecegine
                // SpeedHub karar verir (ham hiz / gosterge hizi secimi).
                val t = SystemClock.elapsedRealtimeNanos()
                speed.rawSpeedMetersPerSecond.takeIf { it.status == CarValue.STATUS_SUCCESS }
                    ?.value?.let { onSample(it, t, false) }
                speed.displaySpeedMetersPerSecond.takeIf { it.status == CarValue.STATUS_SUCCESS }
                    ?.value?.let { onSample(it, t, true) }
            }
            info.addSpeedListener(ContextCompat.getMainExecutor(carContext), l)
            listener = l
            running = true
            true
        } catch (e: Throwable) {
            Log.w(TAG, "CarInfo hiz dinleyicisi acilamadi", e)
            lastError = "Host hızı: ${e.javaClass.simpleName}"
            false
        }
    }

    fun stop() {
        val l = listener ?: return
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(l)
        }
        listener = null
        running = false
    }
}

/**
 * Yedek 2: GPS hizi. EX30'da Car App Library'nin CarSensors API'si calismadigi
 * icin konum/hareket verisi standart LocationManager'dan alinir (prompt.md §10.3).
 *
 * Tipik olarak 1 Hz gelir; 0-100 icin cozunurlugu dusuktur, ekranda bu belirtilir.
 */
class GpsSpeedSource(
    private val context: Context,
    private val onSample: (mps: Float, tNanos: Long) -> Unit,
) {
    companion object {
        private const val TAG = "SprintSpeed"
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasSpeed()) return
            val t = if (location.elapsedRealtimeNanos > 0) {
                location.elapsedRealtimeNanos
            } else {
                SystemClock.elapsedRealtimeNanos()
            }
            onSample(location.speed, sanitizeTimestamp(t))
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    var running = false
        private set
    var lastError: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        return try {
            // Olabilecek en sik ornek: araligi 0, mesafe filtresi yok.
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener)
            running = true
            true
        } catch (e: Throwable) {
            Log.w(TAG, "GPS acilamadi", e)
            lastError = "GPS: ${e.javaClass.simpleName}"
            false
        }
    }

    fun stop() {
        if (!running) return
        runCatching { locationManager.removeUpdates(listener) }
        running = false
    }
}

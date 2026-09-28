package com.example.ex30launch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * En hassas hiz kaynagi: platformun `android.car.CarPropertyManager` servisine
 * yansimayla baglanip PERF_VEHICLE_SPEED ozelligini surekli (CONTINUOUS) modda dinler.
 *
 * Neden yansima: `android.car.*` siniflari normal SDK'da yok, compileSdk ile derlenmiyor
 * (prompt.md §10.1). Property ID sabitleri de elle yazilmiyor; `VehiclePropertyIds`
 * alanlari calisma aninda okunuyor.
 *
 * Zaman damgasi olarak CarPropertyValue.getTimestamp() kullaniliyor — bu deger
 * VHAL'in olcum ani, callback'in bize ulastigi an degil; 0-100 olcumunde aradaki
 * fark dogrudan hataya donusurdu.
 */
class CarPropertySpeedSource(
    private val context: Context,
    private val onSample: (mps: Float, tNanos: Long, display: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "SprintSpeed"

        /** Denenecek ornekleme hizlari (Hz). Arac kabul etmezse sirayla dusuluyor. */
        private val RATE_CANDIDATES = floatArrayOf(100f, 50f, 20f, 10f, 5f, 1f)

        /** registerCallback hic calismazsa bu araliktaki dogrudan okumaya dusulur. */
        private const val POLL_INTERVAL_MS = 25L
    }

    private var car: Any? = null
    private var manager: Any? = null
    private var speedCallback: Any? = null
    private var displayCallback: Any? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false

    var running = false
        private set

    /** Araca kabul ettirilen ornekleme hizi. */
    var requestedRateHz: Float = 0f
        private set

    /** Iki kanalin son degerleri (karsilastirma ve teshis icin). */
    @Volatile
    var displaySpeedMps: Float? = null
        private set

    @Volatile
    var rawSpeedMps: Float? = null
        private set

    var lastError: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        try {
            val carClass = Class.forName("android.car.Car")
            val carObj = carClass.getMethod("createCar", Context::class.java)
                .invoke(null, context) ?: run {
                lastError = "Car servisi bulunamadı"
                return false
            }
            val pm = carClass.getMethod("getCarManager", String::class.java)
                .invoke(carObj, "property") ?: run {
                lastError = "CarPropertyManager yok"
                return false
            }
            car = carObj
            manager = pm

            val speedId = propertyId("PERF_VEHICLE_SPEED") ?: run {
                lastError = "PERF_VEHICLE_SPEED bu araçta tanımsız"
                return false
            }
            val displayId = propertyId("PERF_VEHICLE_SPEED_DISPLAY")

            // Iki kanal da dinlenir; hangisinin olcume girecegine SpeedHub karar verir
            // (kullanici ham hiz / gosterge hizi arasinda seciyor).
            speedCallback = registerContinuous(pm, speedId, trackRate = true) { value, tNanos ->
                rawSpeedMps = value
                onSample(value, tNanos, false)
            }
            displayId?.let {
                displayCallback = registerContinuous(pm, it, trackRate = false) { value, tNanos ->
                    displaySpeedMps = value
                    onSample(value, tNanos, true)
                }
            }
            if (speedCallback == null && displayCallback == null) {
                // Dinleyici acilmadi — dogrudan okumayla devam et.
                startPolling(pm, speedId, displayId)
            }

            running = true
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "VHAL hiz kaynagi acilamadi", e)
            lastError = "VHAL: ${e.javaClass.simpleName}"
            return false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        polling = false
        pollThread = null
        val pm = manager
        if (pm != null) {
            unregister(pm, speedCallback)
            unregister(pm, displayCallback)
        }
        speedCallback = null
        displayCallback = null
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
        manager = null
    }

    // --- Ic isler ---

    /** `VehiclePropertyIds` sabitini calisma aninda cozer (elle sayi yazmak yok). */
    private fun propertyId(fieldName: String): Int? = runCatching {
        Class.forName("android.car.VehiclePropertyIds").getField(fieldName).getInt(null)
    }.getOrNull()

    /**
     * CONTINUOUS dinleyici kurar. Aracin bildirdigi azami ornekleme hizindan
     * baslayip kabul edilene kadar asagi iner.
     */
    private fun registerContinuous(
        pm: Any,
        propId: Int,
        trackRate: Boolean,
        sink: (Float, Long) -> Unit,
    ): Any? {
        val callbackClass = runCatching {
            Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
        }.getOrNull() ?: return null

        val register = pm.javaClass.methods.firstOrNull {
            it.name == "registerCallback" && it.parameterCount == 3 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return null

        val proxy = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            SpeedCallbackHandler(propId, sink),
        )

        val rates = buildList {
            maxSampleRate(pm, propId)?.let { if (it > 0f) add(it) }
            addAll(RATE_CANDIDATES.toList())
        }
        for (rate in rates) {
            val result = runCatching { register.invoke(pm, proxy, propId, rate) }
                .onFailure { Log.d(TAG, "rate=$rate reddedildi: ${it.cause?.javaClass?.simpleName}") }
            // Metot boolean donuyorsa true bekleriz; void donen surumlerde
            // istisna atilmamis olmasi basari sayilir.
            val ok = result.isSuccess && (result.getOrNull() as? Boolean ?: true)
            if (ok) {
                // Ekranda gosterilen hiz, olcumu tasiyan kanalin hizi olmali.
                if (trackRate) requestedRateHz = rate
                Log.i(TAG, "prop=$propId dinleniyor, rate=$rate Hz")
                return proxy
            }
        }
        return null
    }

    private fun maxSampleRate(pm: Any, propId: Int): Float? = runCatching {
        val cfg = pm.javaClass.methods
            .firstOrNull { it.name == "getCarPropertyConfig" && it.parameterCount == 1 }
            ?.invoke(pm, propId) ?: return null
        (cfg.javaClass.getMethod("getMaxSampleRate").invoke(cfg) as? Float)
    }.getOrNull()

    private fun unregister(pm: Any, callback: Any?) {
        if (callback == null) return
        runCatching {
            val m = pm.javaClass.methods.firstOrNull {
                it.name == "unregisterCallback" && it.parameterCount == 1
            }
            m?.invoke(pm, callback)
        }
    }

    /** registerCallback yoksa: ayri bir thread'den periyodik getProperty. */
    private fun startPolling(pm: Any, rawId: Int, displayId: Int?) {
        val getProperty = pm.javaClass.methods.firstOrNull {
            it.name == "getProperty" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return
        requestedRateHz = 1000f / POLL_INTERVAL_MS
        polling = true
        pollThread = Thread {
            Log.i(TAG, "registerCallback yok — ${POLL_INTERVAL_MS} ms araliklarla okunuyor")
            while (polling) {
                readOnce(pm, getProperty, rawId, display = false)
                displayId?.let { readOnce(pm, getProperty, it, display = true) }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun readOnce(pm: Any, getProperty: Method, propId: Int, display: Boolean) {
        runCatching {
            val pv = getProperty.invoke(pm, propId, 0) ?: return
            val v = pv.javaClass.getMethod("getValue").invoke(pv) as? Float ?: return
            val ts = runCatching {
                pv.javaClass.getMethod("getTimestamp").invoke(pv) as Long
            }.getOrDefault(0L)
            if (display) displaySpeedMps = v else rawSpeedMps = v
            onSample(v, sanitizeTimestamp(ts), display)
        }
    }

    /** Proxy uzerinden gelen CarPropertyEventCallback cagrilarini karsilar. */
    private class SpeedCallbackHandler(
        private val propId: Int,
        private val sink: (Float, Long) -> Unit,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "onChangeEvent" -> {
                    handleValue(args?.getOrNull(0))
                    null
                }
                "onErrorEvent" -> {
                    Log.w("SprintSpeed", "prop=$propId hata olayi")
                    null
                }
                // Proxy'nin Object metotlari — null donmek NPE uretir.
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SpeedCallback(prop=$propId)"
                else -> null
            }

        private fun handleValue(pv: Any?) {
            if (pv == null) return
            runCatching {
                // Gecersiz durumdaki degerler cop olabiliyor (prompt.md §10.4).
                val status = runCatching {
                    pv.javaClass.getMethod("getStatus").invoke(pv) as? Int
                }.getOrNull()
                if (status != null && status != 0) return
                val value = pv.javaClass.getMethod("getValue").invoke(pv) as? Float ?: return
                val ts = runCatching {
                    pv.javaClass.getMethod("getTimestamp").invoke(pv) as Long
                }.getOrDefault(0L)
                sink(value, sanitizeTimestamp(ts))
            }
        }
    }
}

package com.example.ex30launch

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext

/**
 * Hiz kaynagi turleri. Kucuk oncelik degeri = daha iyi kaynak.
 *
 * Gercek EX30'da PERF_VEHICLE_SPEED platform sondasiyla okunabiliyor (prompt.md §10.2);
 * Car App Library'nin CarSensors API'si ise calismyor, o yuzden konum/hareket yedegi
 * dogrudan LocationManager uzerinden (prompt.md §10.3).
 */
enum class SpeedSourceKind(val priority: Int, val label: String, val precise: Boolean) {
    CAR_PROPERTY(0, "Araç hızı (VHAL)", true),
    CAR_INFO(1, "Araç hızı (host)", true),
    GPS(2, "GPS hızı", false),
    NONE(99, "kaynak yok", false),
}

/**
 * Olcumun dayandigi hiz: aracin ham hizi mi, kadranda yazan deger mi?
 *
 * Gosterge hizi (PERF_VEHICLE_SPEED_DISPLAY) mevzuat geregi gercek hizin altini
 * gostermez, yani genelde bir tik yuksek okur; ondan olculen 0-100 suresi ham
 * hizdan olculene gore daha kisa cikar. Varsayilan bu yuzden RAW.
 */
enum class SpeedReference(val label: String, val shortLabel: String) {
    RAW("Ham hız", "ham"),
    DISPLAY("Gösterge hızı", "gösterge"),
}

/** Tek bir hiz ornegi. Zaman damgasi elapsedRealtime nanosaniye tabanindadir. */
data class SpeedSample(
    val mps: Float,
    val tNanos: Long,
    val kind: SpeedSourceKind,
    /** Ornek gosterge kanalindan mi geldi (PERF_VEHICLE_SPEED_DISPLAY)? */
    val display: Boolean = false,
)

/**
 * Uc hiz kaynagini sirayla dener ve en iyi calisani kullanir.
 *
 * Kaynaklar arasi gecis olcumun ortasinda zaman ekseni karismasin diye
 * ornek bazinda degil, "aktif kaynak" bazinda yapilir.
 */
class SpeedHub(
    private val carContext: CarContext,
    private val onSample: (SpeedSample) -> Unit,
) {
    companion object {
        private const val TAG = "SprintSpeed"

        /** Ust kaynaktan bu sure boyunca ornek gelmezse bir alt kaynagi da baslat. */
        private const val PROMOTE_TIMEOUT_MS = 3_000L

        /** Aktif kaynak bu kadar susarsa daha dusuk oncelikli kaynaga izin ver. */
        private const val SILENCE_NANOS = 3_000_000_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private val carProperty = CarPropertySpeedSource(carContext) { mps, t, display ->
        submit(SpeedSample(mps, t, SpeedSourceKind.CAR_PROPERTY, display))
    }
    private val carInfo = CarInfoSpeedSource(carContext) { mps, t, display ->
        submit(SpeedSample(mps, t, SpeedSourceKind.CAR_INFO, display))
    }
    private val gps = GpsSpeedSource(carContext) { mps, t ->
        // GPS'te tek bir hiz var; secilen referans ne olursa olsun bu kullanilir.
        submit(SpeedSample(mps, t, SpeedSourceKind.GPS, reference == SpeedReference.DISPLAY))
    }

    /** Su an olcumde kullanilan kaynak. */
    @Volatile
    var activeKind: SpeedSourceKind = SpeedSourceKind.NONE
        private set

    /** Aktif kaynaktan gelen ortalama ornekleme araligi (ms) — olcum cozunurlugu. */
    @Volatile
    var sampleIntervalMs: Double = 0.0
        private set

    /** Kaynak kurulumunda olusan hata / durum metni (ekranda gosteriliyor). */
    @Volatile
    var statusText: String = "Hız kaynağı aranıyor…"
        private set

    /** Olcumun dayandigi hiz kanali. Varsayilan ham hiz. */
    @Volatile
    var reference: SpeedReference = SpeedReference.RAW
        private set

    /** Secilmeyen kanalin son degeri — ekranda karsilastirma icin gosteriliyor. */
    @Volatile
    var otherSpeedMps: Float? = null
        private set

    /** Istenen kanal gelmedigi icin digerine dusuldu mu? */
    @Volatile
    var referenceFallback = false
        private set

    private var lastAcceptedNanos = 0L
    private var lastWantedNanos = 0L
    private var started = false

    /**
     * Referansi degistirir. Olcum zaman ekseni ve kanal degistigi icin
     * cagiran taraf suren olcumu sifirlamali.
     */
    fun setReference(ref: SpeedReference) {
        if (ref == reference) return
        reference = ref
        otherSpeedMps = null
        referenceFallback = false
        sampleIntervalMs = 0.0
        // Yeni kanala konusmasi icin sure tani; yoksa diger kanaldan gelen
        // ilk ornek "bu kanal yok" sanilip hemen yedege dusuluyor.
        lastWantedNanos = SystemClock.elapsedRealtimeNanos()
        Log.i(TAG, "Hiz referansi: ${ref.label}")
    }

    fun start() {
        if (started) return
        started = true
        lastWantedNanos = SystemClock.elapsedRealtimeNanos()

        val ok = carProperty.start()
        statusText = if (ok) {
            "VHAL dinleniyor (${carProperty.requestedRateHz.toInt()} Hz istendi)"
        } else {
            carProperty.lastError ?: "VHAL hızı açılamadı"
        }

        // Ust kaynak veri uretmezse sirayla yedekleri de ac.
        handler.postDelayed({
            if (activeKind.priority > SpeedSourceKind.CAR_INFO.priority) {
                Log.i(TAG, "VHAL sessiz — Car App Library hiz dinleyicisi aciliyor")
                if (carInfo.start()) statusText = "VHAL sessiz, host hızına geçildi"
            }
        }, PROMOTE_TIMEOUT_MS)

        handler.postDelayed({
            if (activeKind == SpeedSourceKind.NONE || activeKind == SpeedSourceKind.GPS) {
                Log.i(TAG, "Arac hizi gelmedi — GPS yedegi aciliyor")
                if (gps.start()) statusText = "Araç hızı yok, GPS yedeği (düşük hassasiyet)"
                else statusText = gps.lastError ?: "Hız verisi bulunamadı"
            }
        }, PROMOTE_TIMEOUT_MS * 2)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        carProperty.stop()
        carInfo.stop()
        gps.stop()
        activeKind = SpeedSourceKind.NONE
    }

    private fun submit(sample: SpeedSample) {
        // --- Kanal secimi: yalnizca secili referans olcume girer ---
        val wantDisplay = reference == SpeedReference.DISPLAY
        if (sample.display != wantDisplay) {
            otherSpeedMps = sample.mps
            // Istenen kanal 2 sn'dir susuyorsa (arac o property'yi sunmuyor
            // olabilir) olcumu bosa dusurmemek icin bu kanala razi ol.
            if (sample.tNanos - lastWantedNanos < 2_000_000_000L) return
            if (!referenceFallback) {
                referenceFallback = true
                Log.w(TAG, "${reference.label} kanali yok — diger kanala dusuldu")
            }
        } else {
            lastWantedNanos = sample.tNanos
            if (referenceFallback) referenceFallback = false
        }

        // Daha iyi bir kaynak konusuyorsa dusuk oncelikliyi yok say;
        // aktif kaynak susmussa devri teslim et.
        val active = activeKind
        if (sample.kind.priority > active.priority &&
            sample.tNanos - lastAcceptedNanos < SILENCE_NANOS
        ) {
            return
        }
        if (sample.kind != active) {
            Log.i(TAG, "Aktif hiz kaynagi: ${sample.kind.label}")
            activeKind = sample.kind
            sampleIntervalMs = 0.0
            statusText = sample.kind.label
        } else if (lastAcceptedNanos != 0L) {
            val dtMs = (sample.tNanos - lastAcceptedNanos) / 1_000_000.0
            if (dtMs in 0.5..2_000.0) {
                // Yumusatilmis ortalama — ekranda "cozunurluk" olarak gosteriliyor.
                sampleIntervalMs =
                    if (sampleIntervalMs == 0.0) dtMs else sampleIntervalMs * 0.8 + dtMs * 0.2
            }
        }
        lastAcceptedNanos = sample.tNanos

        // Olcum mantigi ana thread'de calissin; zamanlama ornegin kendi
        // damgasindan geldigi icin bu gecikme dogrulugu etkilemez.
        if (Looper.myLooper() == Looper.getMainLooper()) onSample(sample)
        else handler.post { onSample(sample) }
    }

}

/** Guvenli zaman damgasi: kaynagin verdigi damga sacmaysa simdiki ani kullan. */
internal fun sanitizeTimestamp(tNanos: Long): Long {
    val now = SystemClock.elapsedRealtimeNanos()
    return if (tNanos > 0 && tNanos <= now + 1_000_000_000L && now - tNanos < 5_000_000_000L) {
        tNanos
    } else {
        now
    }
}

package com.example.ex30launch

import android.os.SystemClock

enum class SprintState {
    /** Bekleme — "Hazır"a basilmadi. */
    IDLE,

    /** "Hazır"a basildi ama arac hareket halinde; once tam durmasi gerekiyor. */
    WAIT_STOP,

    /** Arac duruyor, kalkis bekleniyor. Gaza basildigi an kronometre baslar. */
    ARMED,

    /** Olcum suruyor. */
    RUNNING,

    /** Hedef hiza ulasildi. */
    FINISHED,

    /** Olcum yarida kesildi. */
    ABORTED,
}

/** Tamamlanmis bir olcum. [splits] anahtarlari km/h, degerleri saniye. */
data class SprintRun(
    val seconds: Double,
    val splits: Map<Int, Double>,
    val sourceLabel: String,
    val resolutionMs: Double,
    /** Hangi hiz kanalindan olculdu — farkli referanslar kiyaslanamaz. */
    val reference: SpeedReference,
    /** Olcumun bittigi an (duvar saati) — rekor listesinde tarih olarak gosterilir. */
    val timestampMillis: Long = System.currentTimeMillis(),
    /** Olcum sirasinda gorulen en yuksek hiz. */
    val peakKmh: Double = 0.0,
)

/**
 * 0-100 km/h kronometresi.
 *
 * Dogruluk notlari:
 * - Zaman ekseni ornegin kendi damgasidir (VHAL olcum ani), callback gecikmesi degil.
 * - Kalkis ani iki ornek arasinda kalir; once v=0'a dogrusal geri interpolasyon
 *   yapilir, ardindan bir sonraki ornekle hesaplanan ivme kullanilarak rafine edilir.
 * - Hedef ve ara hizlar (split) da ornekler arasinda dogrusal interpolasyonla bulunur;
 *   boylece sonuc ornekleme araligina yuvarlanmaz.
 */
class SprintTimer(private val onChange: () -> Unit) {

    companion object {
        /** Uygulamanin mansetteki sonucu: 0-100 km/h. */
        const val TARGET_KMH = 100

        /**
         * Olculen hiz baremleri. Olcum sonuncusuna (140) kadar surer.
         * Ust sinir Turkiye'deki azami hiz limitinin icinde kalacak sekilde secildi.
         */
        val SPLITS = intArrayOf(50, 70, TARGET_KMH, 120, 140)

        /** Olcumun bittigi hiz — son barem. */
        val MAX_KMH = SPLITS.last()

        /** Bu hizin uzerine cikinca "kalkti" sayilir (sensor gurultusu payi). */
        private const val LAUNCH_KMH = 0.8

        /** Bu hizin altinda arac duruyor kabul edilir. */
        private const val STOP_KMH = 0.4

        /** Olcum sirasinda arac bu kadar sure durursa iptal. */
        private const val ABORT_STOP_NANOS = 1_500_000_000L

        /**
         * Tek bir olcumun ust siniri. Sure dolunca olcum kapanir; o ana kadar
         * yakalanan baremler ekranda kalir (100 km/h yakalandiysa sonuc gecerlidir).
         */
        private const val MAX_RUN_SECONDS = 30
        private const val MAX_RUN_NANOS = MAX_RUN_SECONDS * 1_000_000_000L

        private const val NANOS_PER_SEC = 1_000_000_000.0
    }

    var state: SprintState = SprintState.IDLE
        private set

    var currentKmh: Double = 0.0
        private set

    /** Olcum sirasinda gorulen en yuksek hiz. */
    var peakKmh: Double = 0.0
        private set

    /** Su anki (veya son) olcumun ara zamanlari. */
    val splitTimes = LinkedHashMap<Int, Double>()

    var lastRun: SprintRun? = null
        private set

    var abortReason: String? = null
        private set

    val history = ArrayList<SprintRun>()

    /** Son ornegin geldigi an — verinin bayatlayip bayatlamadigini gostermek icin. */
    var lastSampleNanos: Long = 0L
        private set

    /** Kalkis ani (elapsedRealtime nanosaniye). */
    private var t0Nanos: Long = 0L
    private var t0Refined = false

    /** Kalkis esigini asan ilk ornek ve ondan onceki ornek. */
    private var launchPrevNanos = 0L
    private var launchMps = 0f
    private var launchNanos = 0L

    private var prevMps = 0f
    private var prevNanos = 0L
    private var stoppedSinceNanos = 0L

    private var sourceLabel: String = ""
    private var resolutionMs: Double = 0.0

    /** Olcumun dayandigi hiz kanali — gecmisi bu kanala gore ayirmak icin. */
    var reference: SpeedReference = SpeedReference.RAW
        private set

    /** Kaynak bilgisi sonuc kaydina yazilsin diye disaridan guncellenir. */
    fun setSourceInfo(label: String, intervalMs: Double, reference: SpeedReference) {
        sourceLabel = label
        resolutionMs = intervalMs
        this.reference = reference
    }

    /** "Hazır" dugmesi. */
    fun arm() {
        splitTimes.clear()
        abortReason = null
        lastRun = null
        peakKmh = 0.0
        t0Nanos = 0L
        t0Refined = false
        stoppedSinceNanos = 0L
        state = if (currentKmh <= STOP_KMH) SprintState.ARMED else SprintState.WAIT_STOP
        onChange()
    }

    /** "Sıfırla" dugmesi — suren olcumu iptal eder, gecmisi korur. */
    fun reset() {
        state = SprintState.IDLE
        splitTimes.clear()
        abortReason = null
        lastRun = null
        peakKmh = 0.0
        t0Nanos = 0L
        t0Refined = false
        stoppedSinceNanos = 0L
        onChange()
    }

    /** Gecmisi de temizler (Sıfırla'ya olcum yokken basilinca). */
    fun clearHistory() {
        history.clear()
        onChange()
    }

    /**
     * Buyuk kronometrede gosterilecek deger.
     *
     * 100 km/h gecildikten sonra olcum son bareme kadar surse de mansetteki sure
     * 0-100 degerinde donar; ust baremler yalnizca hucrelere yazilir.
     */
    fun displaySeconds(): Double? = when (state) {
        SprintState.RUNNING -> splitTimes[TARGET_KMH]
            ?: if (t0Nanos == 0L) 0.0
            else (SystemClock.elapsedRealtimeNanos() - t0Nanos) / NANOS_PER_SEC
        SprintState.FINISHED -> lastRun?.seconds
        SprintState.ABORTED -> splitTimes[TARGET_KMH]
        else -> null
    }

    /** Olcum 100'u gecti mi (ust baremler toplanmaya devam ediyor). */
    fun targetReached(): Boolean = splitTimes.containsKey(TARGET_KMH)

    fun onSpeed(mps: Float, tNanos: Long) {
        val kmh = mps * 3.6
        val pv = prevMps
        val pt = prevNanos
        prevMps = mps
        prevNanos = tNanos
        currentKmh = kmh
        lastSampleNanos = tNanos

        when (state) {
            SprintState.WAIT_STOP -> {
                if (kmh <= STOP_KMH) {
                    state = SprintState.ARMED
                    onChange()
                }
                return
            }
            SprintState.ARMED -> {
                if (kmh <= LAUNCH_KMH) return
                startRun(pv, pt, mps, tNanos)
            }
            SprintState.RUNNING -> Unit
            else -> return
        }

        if (state == SprintState.RUNNING) {
            advanceRun(pv, pt, mps, tNanos)
        }
        onChange()
    }

    // --- Ic mantik ---

    private fun startRun(prevMps: Float, prevNanos: Long, mps: Float, tNanos: Long) {
        splitTimes.clear()
        peakKmh = 0.0
        stoppedSinceNanos = 0L
        launchPrevNanos = if (prevNanos > 0L) prevNanos else tNanos
        launchMps = mps
        launchNanos = tNanos
        t0Refined = false

        // Ilk tahmin: hizi 0'a dogru geriye dogrusal uzat.
        t0Nanos = if (prevNanos > 0L && mps > prevMps) {
            crossingTime(prevMps, prevNanos, mps, tNanos, 0f)
        } else {
            tNanos
        }
        state = SprintState.RUNNING
    }

    private fun advanceRun(prevMps: Float, prevNanos: Long, mps: Float, tNanos: Long) {
        // Kalkis anini bir sonraki ornekten hesaplanan ivmeyle rafine et:
        // esigi asan ornekte hiz zaten launchMps idi, sabit ivmeyle geri gidersek
        // gercek kalkis ani t = launchNanos - launchMps / a olur.
        if (!t0Refined && tNanos > launchNanos) {
            val dt = (tNanos - launchNanos) / NANOS_PER_SEC
            val a = (mps - launchMps) / dt
            if (a > 0.3f) {
                val back = (launchMps / a * NANOS_PER_SEC).toLong()
                val refined = (launchNanos - back).coerceIn(launchPrevNanos, launchNanos)
                // Kalkis ani kaydiysa bu ana kadar yazilmis ara zamanlar da kayar.
                val shiftSec = (refined - t0Nanos) / NANOS_PER_SEC
                if (shiftSec != 0.0) {
                    for (key in splitTimes.keys.toList()) {
                        splitTimes[key] = splitTimes.getValue(key) - shiftSec
                    }
                }
                t0Nanos = refined
            }
            t0Refined = true
        }

        if (mps * 3.6 > peakKmh) peakKmh = mps * 3.6

        // Ara hizlar ve hedef — ornekler arasina dusen geciler interpolasyonla.
        val prevKmh = prevMps * 3.6
        val kmh = mps * 3.6
        for (split in SPLITS) {
            if (splitTimes.containsKey(split)) continue
            if (prevKmh < split && kmh >= split) {
                val tCross = crossingTime(prevMps, prevNanos, mps, tNanos, split / 3.6f)
                splitTimes[split] = (tCross - t0Nanos) / NANOS_PER_SEC
            }
        }

        // Son barem yakalandiysa olcum tamamdir.
        if (splitTimes.containsKey(MAX_KMH)) {
            endRun(null)
            return
        }

        // Bitis / iptal kosullari
        if (kmh <= STOP_KMH) {
            if (stoppedSinceNanos == 0L) stoppedSinceNanos = tNanos
            else if (tNanos - stoppedSinceNanos > ABORT_STOP_NANOS) {
                endRun("Araç durdu")
            }
        } else {
            stoppedSinceNanos = 0L
        }
        if (state == SprintState.RUNNING && tNanos - t0Nanos > MAX_RUN_NANOS) {
            endRun("$MAX_RUN_SECONDS sn doldu")
        }
    }

    /**
     * Olcumu kapatir. 100 km/h yakalanmissa — ust baremlere ulasilamamis olsa
     * bile — sonuc gecerlidir; [note] yalnizca neden erken bittigini soyler.
     */
    private fun endRun(note: String?) {
        val headline = splitTimes[TARGET_KMH]
        if (headline == null) {
            // 0-100 yakalanmadi: manset sonucu yok ama yakalanan alt baremler
            // (50, 70) ekranda kaliyor — olcum bilgisi bosa gitmesin.
            abortReason = if (note != null) "$note — 100 km/h'ye ulaşılamadı"
            else "Ölçüm iptal edildi"
            state = SprintState.ABORTED
            return
        }
        val run = SprintRun(
            seconds = headline,
            splits = LinkedHashMap(splitTimes),
            sourceLabel = sourceLabel,
            resolutionMs = resolutionMs,
            reference = reference,
            timestampMillis = System.currentTimeMillis(),
            peakKmh = peakKmh,
        )
        lastRun = run
        history.add(0, run)
        // Gecmis referans bazinda tutuluyor: ham ile gosterge olcumleri
        // ayni listede kiyaslanamaz, her kanaldan son 5 kayit saklanir.
        var sameRef = 0
        history.removeAll { r ->
            r.reference == run.reference && ++sameRef > 5
        }
        abortReason = note
        state = SprintState.FINISHED
    }

    /** iki ornek arasinda [target] hizina ulasilan ani dogrusal olarak bulur. */
    private fun crossingTime(
        v0: Float,
        t0: Long,
        v1: Float,
        t1: Long,
        target: Float,
    ): Long {
        if (t0 <= 0L || v1 == v0) return t1
        val ratio = ((target - v0) / (v1 - v0)).toDouble().coerceIn(0.0, 1.0)
        return t0 + ((t1 - t0) * ratio).toLong()
    }
}

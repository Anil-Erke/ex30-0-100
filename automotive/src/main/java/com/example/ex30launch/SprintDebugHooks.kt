package com.example.ex30launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.exp

/**
 * Yalnizca debug derlemesinde kurulan gelistirme kancalari. Emulatorde host,
 * dokunuslari uygulamaya iletmedigi icin (ve VHAL hizi user-build emulatorde
 * enjekte edilemedigi icin) olcum zincirini adb'den tetiklemek gerekiyor:
 *
 *   adb shell am broadcast --user 10 -p com.example.ex30launch \
 *       -a com.example.ex30launch.DEBUG --es cmd sim --ef target 5.3
 *
 * cmd: accept | arm | reset | sim | ref | records | view | seed | clearrec
 *
 * Release derlemesinde bu sinif hic kurulmaz (bkz. SprintScreen.onCreate).
 */
class SprintDebugHooks(
    private val context: Context,
    private val timer: SprintTimer,
    private val records: RecordStore,
    private val onToggleReference: () -> Unit,
    private val onAcceptDisclaimer: () -> Unit,
    private val onShowRecord: (SprintRun) -> Unit,
    private val onOpenRecords: () -> Unit,
    private val onSimulationState: (Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "SprintDebug"
        private const val ACTION = "com.example.ex30launch.DEBUG"
        private const val STEP_MS = 50L

        /** Simulasyon asimptotu (m/s) — en ust barem de yakalanabilsin diye. */
        private const val SIM_VMAX = 55.0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var simStartNanos = 0L
    private var simTau = 0.0

    private val simStep = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            val t = (now - simStartNanos) / 1_000_000_000.0
            // v(t) = vmax * (1 - e^(-t/tau)) — gercek bir EV ivmelenme egrisine yakin.
            val v = SIM_VMAX * (1.0 - exp(-t / simTau))
            timer.onSpeed(v.toFloat(), now)
            // ARMED de surdurulur: yavas hizlanmada ilk ornekler kalkis esiginin
            // altinda kalabiliyor, oradan devam edilmezse simulasyon hic baslamiyor.
            if (timer.state == SprintState.RUNNING || timer.state == SprintState.ARMED) {
                handler.postDelayed(this, STEP_MS)
            } else {
                onSimulationState(false)
                Log.i(TAG, "Simulasyon bitti: ${timer.lastRun?.seconds}")
            }
        }
    }

    fun install() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.getStringExtra("cmd")) {
                    "arm" -> timer.arm()
                    "reset" -> timer.reset()
                    "sim" -> startSimulation(intent.getFloatExtra("target", 5.3f))
                    // Emulatorde host dugmeleri tiklanamadigi icin (§6.10)
                    // referans degisimi buradan tetiklenebiliyor.
                    "ref" -> onToggleReference()
                    "accept" -> onAcceptDisclaimer()
                    // Emulatorde liste satirlari tiklanamadigi icin kayit
                    // goruntulemeyi ve liste doldurmayi buradan tetikliyoruz.
                    "view" -> {
                        val index = intent.getIntExtra("index", 1) - 1
                        records.records.getOrNull(index)?.let(onShowRecord)
                            ?: Log.w(TAG, "kayit yok: ${index + 1}")
                    }
                    "records" -> onOpenRecords()
                    "seed" -> seedRecords(intent.getIntExtra("count", 5))
                    "clearrec" -> {
                        records.clear()
                        Log.i(TAG, "kayitlar silindi")
                    }
                    else -> Log.w(TAG, "bilinmeyen cmd")
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, IntentFilter(ACTION))
        }
        receiver = r
        Log.i(TAG, "Debug kancalari kurulu")
    }

    fun remove() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        handler.removeCallbacks(simStep)
    }

    /** Rekor listesini sahte kayitlarla doldurur (siralama/tasma testi icin). */
    private fun seedRecords(count: Int) {
        var accepted = 0
        for (k in 0 until count) {
            val base = 4.6 + k * 0.17
            val run = SprintRun(
                seconds = base,
                splits = linkedMapOf(
                    50 to base * 0.41,
                    70 to base * 0.62,
                    100 to base,
                    120 to base * 1.32,
                    140 to base * 1.74,
                ),
                sourceLabel = SpeedSourceKind.CAR_PROPERTY.label,
                resolutionMs = 100.0,
                reference = SpeedReference.RAW,
                timestampMillis = System.currentTimeMillis() - k * 3_600_000L,
                peakKmh = 141.0,
            )
            if (records.offer(run) != null) accepted++
        }
        Log.i(TAG, "seed: $count denendi, $accepted kabul, toplam ${records.records.size}")
    }

    /** [targetSeconds] saniyede 100 km/h'ye ulasan sahte bir ivmelenme uretir. */
    private fun startSimulation(targetSeconds: Float) {
        // 27.78 m/s (100 km/h) degerine tam targetSeconds'ta ulasacak tau.
        simTau = -targetSeconds / kotlin.math.ln(1.0 - 27.7778 / SIM_VMAX)
        timer.arm()
        onSimulationState(true)
        simStartNanos = SystemClock.elapsedRealtimeNanos()
        handler.postDelayed(simStep, STEP_MS)
        Log.i(TAG, "Simulasyon basladi, hedef=$targetSeconds s (tau=$simTau)")
    }
}

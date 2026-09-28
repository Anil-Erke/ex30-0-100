package com.example.ex30launch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

/**
 * Ana ekran: once arac hizi iznini ister, sonra Surface uzerine kronometreyi cizen
 * NavigationTemplate'i gosterir.
 *
 * "Hazır" ve "Sıfırla" hem Surface'e cizili dugmelerden hem de ActionStrip'ten
 * calisir; host Surface dokunuslarini iletmezse uygulama yine kullanilabilir kalir.
 */
class SprintScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    companion object {
        private val REQUIRED_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        /** Olcum sirasinda ekran akici olsun diye sik, bosta seyrek cizim. */
        private const val TICK_FAST_MS = 40L
        private const val TICK_IDLE_MS = 300L

        private const val PREFS = "ex30sprint"
        private const val KEY_REFERENCE = "speedReference"

        /** Acilis uyarisinin maddeleri — Surface'e cizilir (SprintRenderer §neden). */
        private val DISCLAIMER_LINES = listOf(
            "Bu uygulama hızlanma süresi ölçer ve tam gaz hızlanma gerektirir.",
            "Ani hızlanma tehlikelidir: aracın kontrolünü kaybedebilir, kazaya ve " +
                "yaralanmaya yol açabilirsiniz.",
            "Ölçümü mümkünse trafiğe kapalı bir yolda veya pistte yapın. Islak, buzlu " +
                "veya kalabalık yolda denemeyin.",
            "Hız limitlerine ve trafik kurallarına uymak tamamen sizin sorumluluğunuzdadır.",
            "Ölçüm hareketle kendiliğinden başlar ve biter; sürerken ekrana bakmanız gerekmez.",
            "Geliştirici; doğabilecek kaza, yaralanma, araç hasarı, trafik cezası ve diğer " +
                "zararlardan sorumlu tutulamaz. Sorumluluk tamamen sürücüye aittir.",
        )
    }

    // Tipler acikca yazili: renderer <-> hub karsilikli referansi tip cikarimini kilitliyor.
    private val timer: SprintTimer = SprintTimer { onTimerChanged() }
    private val renderer: SprintRenderer = SprintRenderer(timer) { hub }
    private val hub: SpeedHub = SpeedHub(carContext) { sample -> onSpeedSample(sample) }
    private val records = RecordStore(carContext)

    /** Tamamlanan olcumun rekor listesine kaydedilip kaydedilmedigi. */
    private var lastFinishedRun: SprintRun? = null

    /** Acilis guvenlik uyarisi kabul edildi mi (her oturumda yeniden sorulur). */
    private var disclaimerAccepted = false
    private var permissionsResolved = false

    /** Izin aktivitesi acildi mi — onResume'un tetikleyicisi (§16.1). */
    private var permissionRequested = false
    private var collecting = false

    /** Debug simulasyonu surerken gercek arac ornekleri yok sayilir. */
    private var simulating = false
    private var debugHooks: SprintDebugHooks? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            // Guvenlik agi: izinler BASKA bir yoldan verildiyse (sistem ayarlari,
            // ya da onResume host surumune gore tetiklenmediyse) sablonu yenile.
            // renderer.render() yalnizca Surface'i ciziyor, sablonu DEGIL —
            // bu satir olmadan ekran izin sablonunda takili kalabilir.
            if (!permissionsResolved && missingPermissions().isEmpty()) invalidate()
            renderer.render()
            val fast = timer.state == SprintState.RUNNING || timer.state == SprintState.ARMED
            handler.postDelayed(this, if (fast) TICK_FAST_MS else TICK_IDLE_MS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        restoreReference()
        // Izinler ve olcum, guvenlik uyarisi kabul edilene kadar baslamaz.
        if (missingPermissions().isEmpty()) permissionsResolved = true
        handler.post(ticker)

        if (BuildConfig.DEBUG) {
            debugHooks = SprintDebugHooks(
                context = carContext,
                timer = timer,
                records = records,
                onToggleReference = { toggleReference() },
                onAcceptDisclaimer = { acceptDisclaimer() },
                onShowRecord = { showRecord(it) },
                onOpenRecords = { openRecords() },
                onSimulationState = { simulating = it },
            ).also { it.install() }
        }
    }

    /**
     * Izin aktivitesinden ya da sistem ayarlarindan DONUSTE calisir.
     *
     * `CarContext.requestPermissions`'in geri cagirimi yerine gecen sey bu
     * (prompt.md §16.1). [permissionRequested] bayragi olmadan ilk acilista da
     * tetiklenir ve kullaniciya hic sormadan "cozuldu" sayardik.
     */
    override fun onResume(owner: LifecycleOwner) {
        if (!permissionRequested) return
        permissionRequested = false
        // Reddedilse bile devam ediyoruz: hangi kaynagin calistigi ekranda yaziyor.
        permissionsResolved = true
        startCollecting()
        invalidate()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(ticker)
        debugHooks?.remove()
        hub.stop()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    override fun onGetTemplate(): Template {
        // Her acilista once guvenlik uyarisi; kabul edilmeden hicbir sey baslamaz.
        if (!disclaimerAccepted) return disclaimerTemplate()

        if (!permissionsResolved && missingPermissions().isNotEmpty()) {
            return MessageTemplate.Builder(
                "0-100 km/h ölçümü için araç hızı iznine ihtiyaç var. Araç hızı hiç " +
                    "gelmezse uygulama GPS hızına düşer; bunun için konum izni gerekir."
            )
                .setTitle("İzin gerekli")
                .addAction(
                    Action.Builder()
                        .setTitle("İzin ver")
                        .setOnClickListener { requestPermissions() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("İzinsiz devam")
                        .setOnClickListener {
                            permissionsResolved = true
                            startCollecting()
                            invalidate()
                        }
                        .build()
                )
                .build()
        }

        permissionsResolved = true
        startCollecting()
        return NavigationTemplate.Builder()
            // Ust cubuk: baslikli dugmeler. ~10 sn sonra gizlenir, dokununca doner.
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(actionReady(withTitle = true))
                    .addAction(actionReset(withTitle = true))
                    .addAction(actionReference())
                    .addAction(actionRecords())
                    .build()
            )
            // Harita kontrol cubugu: host'un kalici olarak gosterdigi ikon dugmeler.
            // Surface'e cizilen dugmeler ise ise yaramiyor — host dokunus olaylarini
            // uygulamaya hic iletmiyor (emulatorde onClick/onScroll dogrulandi).
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(actionReady(withTitle = false))
                    .addAction(actionReset(withTitle = false))
                    .build()
            )
            .build()
    }

    private fun actionReady(withTitle: Boolean): Action =
        Action.Builder()
            .apply { if (withTitle) setTitle("Hazır") }
            .setIcon(icon(R.drawable.ic_ready))
            .setOnClickListener {
                showLiveView()
                timer.arm()
                renderer.render()
            }
            .build()

    private fun actionReset(withTitle: Boolean): Action =
        Action.Builder()
            .apply { if (withTitle) setTitle("Sıfırla") }
            .setIcon(icon(R.drawable.ic_reset))
            .setOnClickListener {
                val wasViewingRecord = renderer.viewedRecord != null
                showLiveView()
                // Kayit goruntulerken Sıfırla yalnizca canli goruntuye doner;
                // kazara oturum gecmisi silinmesin.
                if (!wasViewingRecord) {
                    if (timer.state == SprintState.IDLE) timer.clearHistory() else timer.reset()
                }
                renderer.render()
            }
            .build()

    /** Rekor listesi ekranini acar. */
    private fun actionRecords(): Action =
        Action.Builder()
            .setTitle("Rekorlar")
            .setIcon(icon(R.drawable.ic_records))
            .setOnClickListener { openRecords() }
            .build()

    private fun openRecords() {
        screenManager.push(RecordsScreen(carContext, records) { run -> showRecord(run) })
    }

    /** Secilen kaydi ana ekrandaki semada gosterir. */
    private fun showRecord(run: SprintRun) {
        renderer.viewedRecord = run
        renderer.viewedRecordRank = records.records.indexOfFirst { it === run } + 1
        renderer.render()
    }

    /** Kayit gorunumunden canli olcum gorunumune doner. */
    private fun showLiveView() {
        renderer.viewedRecord = null
        renderer.viewedRecordRank = 0
    }

    /**
     * Acilis uyarisi. Kabul edilmeden olcum ekranina gecilmez; reddedilirse
     * uygulama kapanir (`finishCarApp`). Kalici olarak saklanmiyor — uyari
     * bilerek her acilista gosteriliyor.
     */
    private fun disclaimerTemplate(): Template {
        renderer.disclaimerLines = DISCLAIMER_LINES
        renderer.render()

        fun accept(withTitle: Boolean) = Action.Builder()
            .apply { if (withTitle) setTitle("Kabul ediyorum") }
            .setIcon(icon(R.drawable.ic_ready))
            .setOnClickListener { acceptDisclaimer() }
            .build()

        // Baslik kisa tutuldu: "Kabul etmiyorum" ActionStrip'te kirpiliyor.
        fun decline(withTitle: Boolean) = Action.Builder()
            .apply { if (withTitle) setTitle("Çıkış") }
            .setIcon(icon(R.drawable.ic_close))
            .setOnClickListener { carContext.finishCarApp() }
            .build()

        // Ust cubuk basliklı, harita cubugu ikon: ikisi de calisiyor (§5.4).
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(accept(withTitle = true))
                    .addAction(decline(withTitle = true))
                    .build()
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(accept(withTitle = false))
                    .addAction(decline(withTitle = false))
                    .build()
            )
            .build()
    }

    /**
     * Olcumun dayandigi hiz kanalini degistirir. Baslik o an **aktif olan**
     * referansi gosterir (EX30 Telemetry'nin menzil dugmesiyle ayni kalip).
     */
    private fun actionReference(): Action =
        Action.Builder()
            // Ust cubukta dort dugme var; baslik kisa olmali yoksa kirpiliyor.
            .setTitle(hub.reference.shortLabel.replaceFirstChar { it.uppercase(Locale("tr")) })
            .setIcon(icon(R.drawable.ic_source))
            .setOnClickListener { toggleReference() }
            .build()

    private fun acceptDisclaimer() {
        if (disclaimerAccepted) return
        disclaimerAccepted = true
        renderer.disclaimerLines = null
        if (missingPermissions().isEmpty()) startCollecting()
        invalidate()
        renderer.render()
    }

    private fun toggleReference() {
        val next = if (hub.reference == SpeedReference.RAW) {
            SpeedReference.DISPLAY
        } else {
            SpeedReference.RAW
        }
        hub.setReference(next)
        saveReference(next)
        // Zaman ekseni ve kanal degisti; suren olcum artik gecerli degil.
        showLiveView()
        timer.reset()
        invalidate()
        renderer.render()
    }

    private fun restoreReference() {
        val saved = carContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_REFERENCE, null)
        val ref = SpeedReference.entries.firstOrNull { it.name == saved } ?: return
        hub.setReference(ref)
    }

    private fun saveReference(ref: SpeedReference) {
        carContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REFERENCE, ref.name)
            .apply()
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun onSpeedSample(sample: SpeedSample) {
        timer.setSourceInfo(hub.activeKind.label, hub.sampleIntervalMs, hub.reference)
        if (simulating) return
        timer.onSpeed(sample.mps, sample.tNanos)
    }

    /**
     * Olcum bitince cagrilir: sonuc yalnizca ilk [RecordStore.MAX_RECORDS]
     * dereceye giriyorsa kaydedilir, giriyorsa liste doluysa en kotu kayit duser.
     */
    private fun onTimerChanged() {
        // Olcum basladiysa kayit gorunumunde kalinmaz: ekranda canli veri olmali.
        if (timer.state == SprintState.RUNNING && renderer.viewedRecord != null) showLiveView()
        if (timer.state == SprintState.FINISHED) timer.lastRun?.let { onRunFinished(it) }
        renderer.render()
    }

    private fun onRunFinished(run: SprintRun) {
        if (run === lastFinishedRun) return
        lastFinishedRun = run
        renderer.lastRunRank = records.offer(run) ?: 0
    }

    private fun startCollecting() {
        if (collecting || !permissionsResolved || !disclaimerAccepted) return
        collecting = true
        hub.start()
    }

    private fun missingPermissions(): List<String> = REQUIRED_PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(carContext, it) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Izin akisi — `CarContext.requestPermissions` KULLANILMIYOR (prompt.md §16.1).
     *
     * O cagri Android 15'te uygulamayi cokertiyor. Yerine kendi opak
     * aktivitemiz aciliyor; acilamazsa uygulamanin sistem ayar sayfasina
     * dusuluyor (kullanici izinleri zaten elle oradan veriyordu).
     *
     * Sonucu geri tasiyan bir geri cagirim YOK: aktivite kapaninca bu ekrana
     * donuluyor ve [onResume] durumu yeniden okuyor.
     */
    private fun requestPermissions() {
        permissionRequested = true
        if (launch(PermissionActivity.intent(carContext, missingPermissions()))) return

        // Kendi aktivitemiz acilamadi: sistem ayar sayfasi garantili kacis yolu.
        if (launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", carContext.packageName, null))
            )
        ) return

        // Ikisi de olmadi. Istisnayi YUTMA: araçta adb yok (§11), hatanin
        // sinifini gormenin tek yolu ekrana basmak.
        permissionRequested = false
        CarToast.makeText(carContext, lastLaunchError ?: "Açılamadı", CarToast.LENGTH_LONG).show()
    }

    private var lastLaunchError: String? = null

    /** Aktiviteyi acmayi dener; basarisiz olursa hatayi saklar ve false doner. */
    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Iki ayri kod yolu: birinin engellenmesi digerini engellemeyebilir.
        for (ctx in listOf<android.content.Context>(carContext, carContext.applicationContext)) {
            val r = runCatching { ctx.startActivity(intent) }
            if (r.isSuccess) return true
            r.exceptionOrNull()?.let {
                lastLaunchError = "${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}"
            }
        }
        return false
    }
}

package com.example.ex30launch

import android.graphics.Canvas
import android.graphics.Color
import android.icu.text.SimpleDateFormat
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * NavigationTemplate'in verdigi Surface uzerine kronometre ekranini cizer ve
 * "Hazır" / "Sıfırla" dugmelerinin dokunusunu karsilar.
 *
 * Dugmeler bilerek Surface'e ciziliyor: ActionStrip ~10 sn sonra kendini gizliyor
 * ve kullaniciyi iki dokunusa zorluyor (prompt.md §5.2). Yine de ayni eylemler
 * ActionStrip'te de duruyor — host onClick'i iletmezse oradan kullanilabilsin.
 */
class SprintRenderer(
    private val timer: SprintTimer,
    private val hub: () -> SpeedHub?,
) : SurfaceCallback {

    private var surface: Surface? = null
    private var visibleArea: Rect? = null

    /**
     * Doluysa olcum ekrani yerine guvenlik uyarisi cizilir.
     *
     * Uyari neden Surface'e ciziliyor: `MessageTemplate` metni sürüs dikkat
     * dagitma kurallari geregi ~2 satirda kirpiyor (emulatorde dogrulandi),
     * `LongMessageTemplate` ise yalnizca park halinde gosterilebiliyor.
     * Serbest cizim yuzeyinde metnin tamami her durumda gorunur.
     */
    var disclaimerLines: List<String>? = null

    /**
     * Rekor listesinden secilen kayit. Doluysa olcum semasi canli veri yerine
     * bu kaydin degerlerini gosterir; renk canli sonuctan ayirt edilsin diye
     * yesil degil mavi.
     */
    var viewedRecord: SprintRun? = null
    var viewedRecordRank: Int = 0

    /** Son tamamlanan olcumun rekor listesindeki sirasi; 0 ise listeye giremedi. */
    var lastRunRank: Int = 0

    // --- Renkler ---
    private val colBg = Color.parseColor("#0B0E13")
    private val colPanel = Color.parseColor("#161A23")
    private val colMuted = Color.parseColor("#8A93A5")
    private val colAccent = Color.parseColor("#4FC3F7")
    private val colGreen = Color.parseColor("#3FB950")
    private val colAmber = Color.parseColor("#E3B341")
    private val colRed = Color.parseColor("#F85149")

    private val bgPaint = Paint().apply { color = colBg }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colPanel }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // --- SurfaceCallback ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        render()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        render()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (visibleArea == null) {
            visibleArea = stableArea
            render()
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    // --- Cizim ---

    fun render() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas: Canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            return
        }
        try {
            draw(canvas)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun draw(canvas: Canvas) {
        // Host'un kendi arayuzunu ortmemek icin once temizle, arka plani
        // yalnizca bize ayrilan alana ciz (prompt.md §5.1).
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val area = visibleArea ?: Rect(0, 0, canvas.width, canvas.height)
        canvas.drawRect(area, bgPaint)

        val w = area.width().toFloat()
        val h = area.height().toFloat()
        if (w < 50f || h < 50f) return
        // EX30 dikey ekranli; ama yatay ekranli araclarda (ve Play'in yatay
        // ekran goruntusunde) dikey referansla olcekleyince her sey kuculuyor.
        val scale = if (w > h * 1.15f) {
            min(w / 1100f, h / 620f)
        } else {
            min(w / 720f, h / 1180f)
        }.coerceIn(0.45f, 2.2f)

        disclaimerLines?.let {
            drawDisclaimer(canvas, area, it, scale)
            return
        }
        val pad = 22f * scale
        val left = area.left + pad
        val right = area.right - pad
        val centerX = (left + right) / 2f

        // Alt seride yonerge + kaynak durumu sabit; icerik kalan alani paylasir.
        val hintH = 44f * scale
        val statusH = 28f * scale
        val bottomTop = area.bottom - pad - hintH - statusH

        var y = area.top + pad
        y = drawHeader(canvas, left, right, y, scale)
        y = drawBanner(canvas, left, right, y, scale)

        val avail = (bottomTop - y - pad).coerceAtLeast(60f)
        val timerH = avail * 0.46f
        val speedH = avail * 0.20f
        val splitsH = avail * 0.24f
        val historyH = avail * 0.10f

        drawTimer(canvas, centerX, y, timerH, scale)
        y += timerH
        drawSpeed(canvas, left, right, y, speedH, scale)
        y += speedH
        drawSplits(canvas, left, right, y, splitsH, scale)
        y += splitsH
        drawHistory(canvas, left, right, y, historyH, scale)

        drawHint(canvas, left, right, bottomTop, hintH, scale)
        drawStatus(canvas, left, right, area.bottom - pad, scale)
    }

    /**
     * Dugmeler host'un kontrol cubuklarinda oldugu icin kullaniciya nereye
     * basacagini soyleyen serit. Surface'e dugme cizmek ise yaramiyor:
     * host dokunus olaylarini uygulamaya iletmiyor.
     */
    private fun drawHint(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        scale: Float,
    ) {
        viewedRecord?.let {
            drawHintBox(
                canvas, left, right, top, height, scale,
                "Canlı ölçüme dönmek için ▶ HAZIR  ·  ↺ SIFIRLA", colAccent,
            )
            return
        }
        val text = when (timer.state) {
            SprintState.IDLE -> "▶ HAZIR düğmesine basın  ·  ekrana dokununca düğmeler görünür"
            SprintState.WAIT_STOP -> "Aracı tam durdurun — ölçüm duruştan başlar"
            SprintState.ARMED -> "Gaza basın — kronometre kendiliğinden başlar"
            SprintState.RUNNING ->
                if (timer.targetReached()) "${SprintTimer.MAX_KMH} km/h'de kendiliğinden bitecek"
                else "Kronometre 100 km/h'de donar, ölçüm ${SprintTimer.MAX_KMH}'ye kadar sürer"
            SprintState.FINISHED -> when {
                timer.abortReason != null -> "${timer.abortReason} — üst baremler tamamlanmadı"
                lastRunRank == 1 -> "🏆 Yeni rekor! Listede 1. sıraya girdi"
                lastRunRank > 1 -> "Rekor listesine $lastRunRank. sıradan girdi"
                else -> "İlk ${RecordStore.MAX_RECORDS} dereceye giremedi — kaydedilmedi"
            }
            SprintState.ABORTED -> "Tekrar denemek için ▶ HAZIR"
        }
        drawHintBox(
            canvas, left, right, top, height, scale, text,
            if (timer.state == SprintState.ARMED) colGreen else colMuted,
        )
    }

    private fun drawHintBox(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        scale: Float,
        text: String,
        color: Int,
    ) {
        val rect = RectF(left, top, right, top + height)
        panelPaint.color = colPanel
        canvas.drawRoundRect(rect, 10f * scale, 10f * scale, panelPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = (height * 0.42f).coerceAtMost(26f * scale)
        textPaint.color = color
        canvas.drawText(text, rect.centerX(), rect.centerY() + textPaint.textSize * 0.36f, textPaint)
    }

    /**
     * Guvenlik uyarisi ekrani. Metin, ayrilan alana sigana kadar punto
     * dusurulerek sarilir; hicbir madde kirpilmaz.
     */
    private fun drawDisclaimer(
        canvas: Canvas,
        area: Rect,
        lines: List<String>,
        scale: Float,
    ) {
        val pad = 26f * scale
        val left = area.left + pad
        val right = area.right - pad
        val maxWidth = right - left
        val centerX = (left + right) / 2f

        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 38f * scale
        boldPaint.color = colAmber
        val titleBaseline = area.top + pad + 36f * scale
        canvas.drawText("⚠  GÜVENLİK UYARISI", centerX, titleBaseline, boldPaint)

        // Alt yonerge once olculur: govdeye kalan yer ona gore hesaplanir.
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 22f * scale
        val footerLines = wrapText(
            "KABUL EDİYORUM ile devam edin  ·  ÇIKIŞ ile kapatın  ·  " +
                "düğmeler için ekrana dokunun",
            textPaint, maxWidth,
        )
        val footerH = footerLines.size * 26f * scale

        val bodyTop = titleBaseline + 18f * scale
        val bodyBottom = area.bottom - pad - footerH

        // Sigana kadar punto dusur: madde sayisi sabit, sarma sonucu puntoya bagli.
        textPaint.textAlign = Paint.Align.LEFT
        var size = 30f * scale
        var wrapped: List<String>
        var needed: Float
        while (true) {
            textPaint.textSize = size
            wrapped = lines.flatMap { wrapText("•  $it", textPaint, maxWidth) }
            needed = wrapped.size * size * 1.42f + lines.size * size * 0.45f
            if (needed <= bodyBottom - bodyTop || size <= 13f * scale) break
            size -= 1.5f * scale
        }

        // Govdeyi kalan alanda dikey ortala.
        var y = bodyTop + ((bodyBottom - bodyTop - needed) / 2f).coerceAtLeast(0f) + size
        for ((index, item) in lines.withIndex()) {
            // Ilk madde (ne yaptigi) ve son madde (sorumluluk) vurgulu.
            textPaint.color = if (index == 0 || index == lines.lastIndex) Color.WHITE else colMuted
            for (part in wrapText("•  $item", textPaint, maxWidth)) {
                canvas.drawText(part, left, y, textPaint)
                y += size * 1.42f
            }
            y += size * 0.45f
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 22f * scale
        textPaint.color = colAccent
        var fy = area.bottom - pad - footerH + 22f * scale
        for (part in footerLines) {
            canvas.drawText(part, centerX, fy, textPaint)
            fy += 26f * scale
        }
    }

    /** Basit kelime sarma — Canvas'ta hazir bir sarma yok. */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                out.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun drawHeader(canvas: Canvas, left: Float, right: Float, top: Float, scale: Float): Float {
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = 32f * scale
        boldPaint.color = Color.WHITE
        canvas.drawText("EX30  0 → 100 km/h", left, top + 30f * scale, boldPaint)

        val h = hub()
        val kind = h?.activeKind ?: SpeedSourceKind.NONE
        val ref = h?.reference ?: SpeedReference.RAW
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 24f * scale
        textPaint.color = when {
            kind == SpeedSourceKind.NONE -> colRed
            h?.referenceFallback == true -> colAmber
            kind.precise -> colGreen
            else -> colAmber
        }
        // Hangi kaynak + hangi kanal olculuyor: sonucun anlami buna bagli.
        canvas.drawText("${kind.label} · ${ref.shortLabel}", right, top + 28f * scale, textPaint)
        return top + 46f * scale
    }

    private fun drawBanner(canvas: Canvas, left: Float, right: Float, top: Float, scale: Float): Float {
        viewedRecord?.let { run ->
            val date = if (run.timestampMillis > 0) {
                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
                    .format(Date(run.timestampMillis))
            } else {
                "tarihsiz"
            }
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = 34f * scale
            boldPaint.color = colAccent
            canvas.drawText(
                "Rekor #$viewedRecordRank  ·  $date",
                (left + right) / 2f, top + 38f * scale, boldPaint,
            )
            return top + 54f * scale
        }
        val (msg, color) = when (timer.state) {
            SprintState.IDLE -> "Ölçüm için HAZIR'a basın" to colMuted
            SprintState.WAIT_STOP -> "Aracı tam durdurun…" to colAmber
            SprintState.ARMED -> "HAZIR — gaza basınca başlar" to colGreen
            SprintState.RUNNING ->
                if (timer.targetReached()) "0-100 tamam — üst baremler ölçülüyor" to colGreen
                else "Ölçülüyor…" to colAmber
            SprintState.FINISHED -> "Sonuç: 0 → 100 km/h" to colGreen
            SprintState.ABORTED -> (timer.abortReason ?: "Ölçüm iptal edildi") to colRed
        }
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 34f * scale
        boldPaint.color = color
        canvas.drawText(msg, (left + right) / 2f, top + 38f * scale, boldPaint)
        return top + 54f * scale
    }

    private fun drawTimer(canvas: Canvas, centerX: Float, top: Float, height: Float, scale: Float) {
        val record = viewedRecord
        val seconds = record?.seconds ?: timer.displaySeconds()
        val text = String.format(Locale.US, "%.2f", seconds ?: 0.0)
        val color = when {
            record != null -> colAccent
            timer.state == SprintState.FINISHED -> colGreen
            timer.state == SprintState.RUNNING -> Color.WHITE
            timer.state == SprintState.ABORTED -> colRed
            else -> colMuted
        }
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = color
        boldPaint.textSize = (height * 0.68f).coerceAtMost(240f * scale)
        val baseline = top + height * 0.72f
        canvas.drawText(text, centerX, baseline, boldPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = boldPaint.textSize * 0.28f
        textPaint.color = colMuted
        val textW = boldPaint.measureText(text)
        canvas.drawText("s", centerX + textW / 2f + 8f * scale, baseline, textPaint)
    }

    private fun drawSpeed(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        scale: Float,
    ) {
        val kmh = timer.currentKmh
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.color = Color.WHITE
        boldPaint.textSize = (height * 0.46f).coerceAtMost(64f * scale)
        canvas.drawText(String.format(Locale.US, "%.0f", kmh), left, top + height * 0.45f, boldPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = boldPaint.textSize * 0.4f
        textPaint.color = colMuted
        val numW = boldPaint.measureText(String.format(Locale.US, "%.0f", kmh))
        canvas.drawText("km/h", left + numW + 8f * scale, top + height * 0.45f, textPaint)

        // Zirve hiz (olcum sirasinda) ya da karsi kanalin degeri (bilgi):
        // ham/gosterge farkini kullanicinin gormesi icin.
        val hubRef = hub()
        val otherKmh = hubRef?.otherSpeedMps?.let { it * 3.6f }
        val otherLabel = when (hubRef?.reference) {
            SpeedReference.DISPLAY -> "ham"
            else -> "gösterge"
        }
        val record = viewedRecord
        val rightInfo = when {
            // Kayit gorunumunde kaydin zirvesi gosterilir; soldaki buyuk deger
            // ise canli hiz olarak kalir (arac hareket ediyor olabilir).
            record != null && record.peakKmh > 0 ->
                String.format(Locale.US, "kayıt zirvesi %.0f km/h", record.peakKmh)
            record != null -> "kayıt görünümü"
            timer.state == SprintState.RUNNING || timer.state == SprintState.FINISHED ->
                String.format(Locale.US, "zirve %.0f km/h", timer.peakKmh)
            otherKmh != null -> String.format(Locale.US, "%s %.0f km/h", otherLabel, otherKmh)
            else -> null
        }
        if (rightInfo != null) {
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = 24f * scale
            canvas.drawText(rightInfo, right, top + height * 0.45f, textPaint)
        }

        // Ilerleme cubugu: 0'dan son bareme kadar; 100 isareti vurgulu.
        val barTop = top + height * 0.62f
        val barH = (height * 0.22f).coerceIn(8f, 26f * scale)
        val r = barH / 2f
        val barMax = SprintTimer.MAX_KMH.toFloat()
        barPaint.color = colPanel
        canvas.drawRoundRect(RectF(left, barTop, right, barTop + barH), r, r, barPaint)
        val ratio = (kmh / barMax).coerceIn(0.0, 1.0).toFloat()
        if (ratio > 0f) {
            barPaint.color = if (timer.targetReached()) colGreen else colAccent
            canvas.drawRoundRect(
                RectF(left, barTop, left + (right - left) * ratio, barTop + barH), r, r, barPaint,
            )
        }
        // Barem isaretleri
        for (split in SprintTimer.SPLITS) {
            if (split >= SprintTimer.MAX_KMH) continue
            val x = left + (right - left) * (split / barMax)
            val isTarget = split == SprintTimer.TARGET_KMH
            strokePaint.color = if (isTarget) Color.WHITE else colBg
            strokePaint.strokeWidth = if (isTarget) 3f * scale else 2f * scale
            canvas.drawLine(x, barTop - if (isTarget) 3f * scale else 0f, x, barTop + barH, strokePaint)
        }
    }

    private fun drawSplits(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        scale: Float,
    ) {
        val splits = SprintTimer.SPLITS
        val gap = 8f * scale
        val cellW = (right - left - gap * (splits.size - 1)) / splits.size
        val cellH = height - gap
        for ((i, split) in splits.withIndex()) {
            val x0 = left + i * (cellW + gap)
            val rect = RectF(x0, top, x0 + cellW, top + cellH)
            panelPaint.color = colPanel
            canvas.drawRoundRect(rect, 10f * scale, 10f * scale, panelPaint)

            val record = viewedRecord
            val value = if (record != null) record.splits[split]
            else timer.splitTimes[split] ?: timer.lastRun?.splits?.get(split)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = (cellH * 0.26f).coerceAtMost(24f * scale)
            textPaint.color = colMuted
            canvas.drawText("$split", rect.centerX(), rect.top + cellH * 0.36f, textPaint)

            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = (cellH * 0.36f).coerceAtMost(34f * scale)
            boldPaint.color = if (value == null) colMuted else {
                if (split == SprintTimer.TARGET_KMH) colGreen else Color.WHITE
            }
            val txt = value?.let { String.format(Locale.US, "%.2f", it) } ?: "-.--"
            canvas.drawText(txt, rect.centerX(), rect.top + cellH * 0.78f, boldPaint)
        }
    }

    private fun drawHistory(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        scale: Float,
    ) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = (height * 0.5f).coerceAtMost(26f * scale)
        textPaint.color = colMuted
        val baseline = top + height * 0.62f

        // Kayit gorunumunde bu satir kaydin kunyesini gosterir.
        viewedRecord?.let { run ->
            val source = run.sourceLabel.ifBlank { "kaynak bilinmiyor" }
            val res = if (run.resolutionMs > 0) {
                String.format(Locale.US, "  ·  %.0f Hz", 1000.0 / run.resolutionMs)
            } else {
                ""
            }
            canvas.drawText("Kayıt: $source  ·  ${run.reference.shortLabel}$res", left, baseline, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = colAccent
            canvas.drawText("rekor sırası #$viewedRecordRank", right, baseline, textPaint)
            return
        }
        // Gecmis yalnizca ayni referansla yapilan olcumleri gosterir;
        // ham ile gosterge suresi kiyaslanamaz.
        val runs = timer.history.filter { it.reference == timer.reference }
        if (runs.isEmpty()) {
            val note = if (timer.history.isEmpty()) "henüz ölçüm yok"
            else "bu referansla ölçüm yok"
            canvas.drawText("Geçmiş (${timer.reference.shortLabel}): $note", left, baseline, textPaint)
            return
        }
        val list = runs.joinToString("  ·  ") { String.format(Locale.US, "%.2f", it.seconds) }
        canvas.drawText("Geçmiş (${timer.reference.shortLabel}): $list", left, baseline, textPaint)

        val best = runs.minOf { it.seconds }
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = colGreen
        canvas.drawText(String.format(Locale.US, "en iyi %.2f s", best), right, baseline, textPaint)
    }

    private fun drawStatus(canvas: Canvas, left: Float, right: Float, baseline: Float, scale: Float) {
        val h = hub()
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 21f * scale

        // Kaynak baglandi ama ornek akisi kesildiyse bunu soylemek sart:
        // ekranda donmus bir hiz degeri yanlis olcume yol acar.
        val last = timer.lastSampleNanos
        val stale = last != 0L &&
            android.os.SystemClock.elapsedRealtimeNanos() - last > 2_000_000_000L
        if (stale) {
            textPaint.color = colRed
            canvas.drawText("Hız verisi gelmiyor — kontak ve izinleri kontrol edin", left, baseline, textPaint)
            return
        }
        if (h?.referenceFallback == true) {
            textPaint.color = colAmber
            canvas.drawText(
                "${h.reference.label} bu araçta yok — diğer kanal kullanılıyor",
                left, baseline, textPaint,
            )
            return
        }
        textPaint.color = colMuted
        canvas.drawText(h?.statusText ?: "…", left, baseline, textPaint)

        val interval = h?.sampleIntervalMs ?: 0.0
        if (interval > 0.0) {
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                String.format(Locale.US, "%.0f Hz · ±%.0f ms", 1000.0 / interval, interval / 2),
                right, baseline, textPaint,
            )
        }
    }

}

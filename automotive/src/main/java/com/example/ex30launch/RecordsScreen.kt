package com.example.ex30launch

import android.icu.text.SimpleDateFormat
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import java.util.Date
import java.util.Locale

/**
 * Rekor listesi: en iyi 0-100 sureleri.
 *
 * Liste `ListTemplate` ile ciziliyor cunku satirlarin tiklanabilir olmasi
 * gerekiyor; Surface'e cizilen alanlar dokunus almiyor (prompt.md §5.4).
 * Host, surus dikkat dagitma kurali geregi bir listede gosterilecek satir
 * sayisini sinirliyor — 20 kayit bu yuzden sayfalaniyor.
 */
class RecordsScreen(
    carContext: CarContext,
    private val store: RecordStore,
    private val onSelect: (SprintRun) -> Unit,
) : Screen(carContext) {

    companion object {
        private const val TAG = "SprintRecords"
    }

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
    private var page = 0

    /** Host'un izin verdigi satir sayisi; alinamazsa makul bir varsayilan. */
    private val pageSize: Int by lazy {
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrNull() ?: 6
        Log.i(TAG, "Host liste siniri: $limit satir")
        limit.coerceIn(3, RecordStore.MAX_RECORDS)
    }

    override fun onGetTemplate(): Template {
        val records = store.records
        if (records.isEmpty()) {
            return MessageTemplate.Builder(
                "Henüz kayıt yok. Tamamlanan her 0-100 ölçümü, en iyi " +
                    "${RecordStore.MAX_RECORDS} süre arasına giriyorsa buraya kaydedilir."
            )
                .setTitle("Rekorlar")
                .setHeaderAction(Action.BACK)
                .build()
        }

        val pageCount = (records.size + pageSize - 1) / pageSize
        page = page.coerceIn(0, pageCount - 1)
        val from = page * pageSize
        val to = minOf(from + pageSize, records.size)

        val list = ItemList.Builder()
        for (index in from until to) {
            val run = records[index]
            list.addItem(
                Row.Builder()
                    .setTitle("${index + 1}.  ${format(run.seconds)} s")
                    .addText(splitSummary(run))
                    .addText(meta(run))
                    .setOnClickListener {
                        onSelect(run)
                        screenManager.pop()
                    }
                    .build()
            )
        }

        val title = if (pageCount > 1) {
            "Rekorlar  ${from + 1}-$to / ${records.size}"
        } else {
            "Rekorlar  (${records.size})"
        }

        val builder = ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())

        if (pageCount > 1) {
            builder.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("◀")
                            .setOnClickListener {
                                page = (page - 1 + pageCount) % pageCount
                                invalidate()
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("▶")
                            .setOnClickListener {
                                page = (page + 1) % pageCount
                                invalidate()
                            }
                            .build()
                    )
                    .build()
            )
        }
        return builder.build()
    }

    /** Manset disindaki baremler: 50, 70, 120, 140. */
    private fun splitSummary(run: SprintRun): String = SprintTimer.SPLITS
        .filter { it != SprintTimer.TARGET_KMH }
        .joinToString("   ") { kmh ->
            val value = run.splits[kmh]
            if (value == null) "$kmh –" else "$kmh ${format(value)}"
        }

    private fun meta(run: SprintRun): String {
        val date = if (run.timestampMillis > 0) {
            dateFormat.format(Date(run.timestampMillis))
        } else {
            "tarihsiz"
        }
        return "$date  ·  ${run.reference.shortLabel}"
    }

    private fun format(seconds: Double): String = String.format(Locale.US, "%.2f", seconds)
}

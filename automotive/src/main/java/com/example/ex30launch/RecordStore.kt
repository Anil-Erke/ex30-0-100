package com.example.ex30launch

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * En iyi [MAX_RECORDS] olcumu kalici olarak saklar.
 *
 * Siralama 0-100 km/h suresine gore (kucukten buyuge). Liste doluyken yeni
 * olcum ancak listedeki en kotu sureden hizliysa girer ve en kotu kayit duser.
 * Sonucun butun baremleri saklanir; kayda tiklandiginda ana ekranda gosterilir.
 *
 * Depolama: SharedPreferences icinde JSON. Uygulama internet izni almadigi icin
 * (gizlilik politikasi) veri cihazdan disari cikmaz.
 */
class RecordStore(context: Context) {

    companion object {
        const val MAX_RECORDS = 20
        private const val TAG = "SprintRecords"
        private const val PREFS = "ex30sprint"
        private const val KEY = "records_v1"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val items = ArrayList<SprintRun>()

    init {
        load()
    }

    /** En iyiden en kotuye sirali kayitlar. */
    val records: List<SprintRun> get() = items

    /**
     * Olcumu listeye sokmayi dener.
     *
     * @return 1'den baslayan sira; olcum ilk 20'ye giremediyse null (kaydedilmez).
     */
    fun offer(run: SprintRun): Int? {
        if (items.size >= MAX_RECORDS && run.seconds >= items.last().seconds) return null
        items.add(run)
        items.sortBy { it.seconds }
        while (items.size > MAX_RECORDS) items.removeAt(items.size - 1)
        save()
        val index = items.indexOfFirst { it === run }
        return if (index >= 0) index + 1 else null
    }

    fun clear() {
        items.clear()
        save()
    }

    // --- Kalici depolama ---

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                fromJson(array.getJSONObject(i))?.let { items.add(it) }
            }
            items.sortBy { it.seconds }
            Log.i(TAG, "${items.size} kayit yuklendi")
        } catch (e: Throwable) {
            // Bozuk kayit yuzunden uygulama acilmamasi kabul edilemez: sifirla.
            Log.w(TAG, "Kayitlar okunamadi, sifirlaniyor", e)
            items.clear()
        }
    }

    private fun save() {
        val array = JSONArray()
        for (run in items) array.put(toJson(run))
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(run: SprintRun): JSONObject {
        val splits = JSONObject()
        for ((kmh, seconds) in run.splits) splits.put(kmh.toString(), seconds)
        return JSONObject().apply {
            put("seconds", run.seconds)
            put("splits", splits)
            put("source", run.sourceLabel)
            put("resolutionMs", run.resolutionMs)
            put("reference", run.reference.name)
            put("timestamp", run.timestampMillis)
            put("peakKmh", run.peakKmh)
        }
    }

    private fun fromJson(o: JSONObject): SprintRun? {
        val seconds = o.optDouble("seconds", Double.NaN)
        if (seconds.isNaN()) return null
        val splits = LinkedHashMap<Int, Double>()
        o.optJSONObject("splits")?.let { obj ->
            for (key in obj.keys()) {
                val kmh = key.toIntOrNull() ?: continue
                splits[kmh] = obj.optDouble(key, 0.0)
            }
        }
        val reference = SpeedReference.entries
            .firstOrNull { it.name == o.optString("reference") } ?: SpeedReference.RAW
        return SprintRun(
            seconds = seconds,
            // Baremler ekranda sabit sirada cizildigi icin anahtar sirasi onemli degil.
            splits = splits,
            sourceLabel = o.optString("source"),
            resolutionMs = o.optDouble("resolutionMs", 0.0),
            reference = reference,
            timestampMillis = o.optLong("timestamp", 0L),
            peakKmh = o.optDouble("peakKmh", 0.0),
        )
    }
}

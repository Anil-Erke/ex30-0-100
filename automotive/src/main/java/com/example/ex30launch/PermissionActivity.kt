package com.example.ex30launch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Izinleri isteyen kendi aktivitemiz — `CarContext.requestPermissions` YERINE.
 *
 * ## Neden var (prompt.md §16.1)
 *
 * **Araçta olculdu (2026-09-10, arac yazilimi 2.1.2 / Android 15):** izin
 * ekranindaki "İzin ver" dugmesine basildiginda uygulama COKUYOR. Bu makinede
 * gelistirilen butun sablon tabanli AAOS uygulamalarinda ayni davranis goruldu,
 * yani sorun izin listesinde degil, kutuphanenin akisinda.
 *
 * Car App Library'nin `CarContext.requestPermissions` cagrisi kendi aktivitesini
 * aciyor ve o aktivite AAR manifestinde SAYDAM tanimli:
 *
 * ```xml
 * <activity android:name="androidx.car.app.CarAppPermissionActivity"
 *           android:theme="@android:style/Theme.Translucent.NoTitleBar" />
 * ```
 *
 * Saydam aktivite + yonelim istegi Android'de bilinen bir cokme sinifi ve
 * Android 15 bu tarafi sertlestirdi. Araçta `adb` olmadigi icin yigin izi
 * alinamiyor (§11); teshis etmek yerine **supheli yol devre disi birakildi**.
 *
 * ## Tasarim
 *
 * Izin listesi INTENT ile geliyor — boylece bu dosya uygulamadan uygulamaya
 * degismeden kopyalanabiliyor; cagiran ekran kendi listesini biliyor.
 *
 * Tema BILEREK opak (`CarAppTheme`): saydam yapmak ayni cokme sinifini geri
 * getirir. `distractionOptimized` BILEREK yok: izin diyalogu surus sirasinda
 * acilmamali, sistem engellesin (§4.2).
 */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ekran donunce istek bastan gonderilmesin; sistem diyalogu zaten ayakta.
        if (savedInstanceState != null) return

        val asked = intent.getStringArrayExtra(EXTRA_PERMISSIONS).orEmpty()

        // Platformda TANIMSIZ izni istemek sessiz redle sonuclanir ve cagiran
        // taraf bunu "kullanici vermedi" sanar (§16.2). Istemeden once ayikla.
        val requestable = asked.filter { perm ->
            checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED &&
                runCatching { packageManager.getPermissionInfo(perm, 0); true }
                    .getOrDefault(false)
        }

        if (requestable.isEmpty()) {
            finish()
            return
        }

        Log.i(TAG, "izin isteniyor: ${requestable.joinToString()}")
        runCatching { requestPermissions(requestable.toTypedArray(), REQUEST_CODE) }
            .onFailure {
                Log.w(TAG, "izin isteği açılamadı", it)
                finish()
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Sonuc ne olursa olsun kapaniyoruz: cagiran ekran durumu onResume'da
        // kendi yeniden okuyor. Sonucu geri tasiyan bir geri cagirim YOK.
        finish()
    }

    companion object {
        private const val TAG = "PermActivity"
        private const val REQUEST_CODE = 1001
        private const val EXTRA_PERMISSIONS = "permissions"

        /** Cagiran ekran kendi eksik izin listesini veriyor. */
        fun intent(context: Context, permissions: List<String>): Intent =
            Intent(context, PermissionActivity::class.java)
                .putExtra(EXTRA_PERMISSIONS, permissions.toTypedArray())
    }
}

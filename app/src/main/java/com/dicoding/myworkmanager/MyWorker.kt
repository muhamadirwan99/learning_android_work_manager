package com.dicoding.myworkmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.loopj.android.http.AsyncHttpResponseHandler
import com.loopj.android.http.SyncHttpClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import cz.msebera.android.httpclient.Header
import org.json.JSONObject
import java.text.DecimalFormat

/**
 * # MyWorker
 *
 * Kelas inti yang berisi **logika pekerjaan latar belakang** sesungguhnya.
 * Kelas ini dieksekusi oleh WorkManager di thread terpisah (bukan Main Thread),
 * sehingga operasi berat seperti HTTP request aman dilakukan di sini.
 *
 * ## Mengapa Meng-extend `Worker`?
 * `Worker` adalah implementasi **sinkronus** dari `ListenableWorker`. Artinya,
 * `doWork()` akan memblokir thread pemanggil hingga selesai — ini adalah pola
 * yang tepat karena WorkManager sudah menjalankannya di background thread secara otomatis.
 * Gunakan `CoroutineWorker` jika ingin memanfaatkan Kotlin Coroutines (suspend functions).
 *
 * ## Alur Eksekusi:
 * 1. WorkManager memanggil `doWork()` di background thread.
 * 2. `doWork()` mengambil data input (nama kota) lalu memanggil [getCurrentWeatherCity].
 * 3. HTTP request dilakukan secara sinkronus menggunakan `SyncHttpClient`.
 * 4. Response di-parse menggunakan **Moshi** dan hasilnya ditampilkan sebagai notifikasi.
 * 5. Fungsi mengembalikan `Result.success()` atau `Result.failure()` ke WorkManager.
 *
 * ## Hubungan dengan File Lain:
 * - **[MainActivity]**: Yang membuat dan meng-enqueue Work Request, serta meneruskan
 *   nama kota sebagai input data menggunakan kunci [EXTRA_CITY].
 * - **[Response]**: Model data (data class) yang digunakan Moshi untuk mem-parse JSON response API.
 * - **AndroidManifest.xml**: Izin `INTERNET` wajib ada agar HTTP request tidak diblokir sistem.
 *   Izin `POST_NOTIFICATIONS` wajib ada agar [showNotification] bisa menampilkan notifikasi.
 */
class MyWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    companion object {
        /** Tag untuk filter log di Logcat, menggunakan nama kelas agar konsisten. */
        private val TAG = MyWorker::class.java.simpleName

        /**
         * API Key untuk OpenWeatherMap, dibaca dari **BuildConfig** yang di-generate
         * saat build berdasarkan nilai di `local.properties`.
         *
         * ⚠️ NOTE: Jangan hardcode API Key langsung di sini. Nilai ini dibaca dari
         * `local.properties` (yang seharusnya **tidak** di-commit ke Version Control)
         * untuk menjaga keamanan kredensial. Lihat `app/build.gradle.kts` untuk
         * konfigurasi `buildConfigField`-nya.
         */
        const val API_KEY = BuildConfig.API_KEY

        /**
         * Kunci (key) untuk mengambil data nama kota dari objek `Data` WorkManager.
         *
         * Menggunakan konstanta ini (bukan string literal) mencegah typo saat
         * menulis data di [MainActivity] dan membacanya di sini.
         */
        const val EXTRA_CITY = "city"

        /** ID unik untuk notifikasi. Menggunakan ID yang sama akan **menimpa** notifikasi sebelumnya,
         *  bukan membuat notifikasi baru — cocok agar tidak membanjiri notification tray. */
        const val NOTIFICATION_ID = 1

        /** ID channel yang menghubungkan notifikasi ke `NotificationChannel` yang dibuat di [showNotification]. */
        const val CHANNEL_ID = "channel_01"

        /** Nama channel yang **terlihat oleh pengguna** di pengaturan notifikasi sistem. */
        const val CHANNEL_NAME = "dicoding channel"
    }

    /**
     * Menyimpan status akhir eksekusi Worker (`Result.success()` atau `Result.failure()`).
     *
     * Diperlukan karena `SyncHttpClient` menggunakan callback (`AsyncHttpResponseHandler`),
     * sehingga hasil tidak bisa langsung di-return dari dalam callback.
     * Variabel ini di-set di dalam callback, lalu di-return setelah client selesai.
     *
     * ⚠️ NOTE: Meskipun menggunakan `SyncHttpClient` (sinkronus), callback tetap dipanggil
     * pada thread yang sama. Pastikan `resultStatus` tidak `null` saat di-cast di akhir
     * [getCurrentWeatherCity] — jika terjadi exception yang tidak tertangkap sebelum
     * callback dipanggil, ini bisa menyebabkan `NullPointerException`.
     */
    private var resultStatus: Result? = null

    /**
     * **Titik masuk utama** yang dipanggil WorkManager untuk menjalankan pekerjaan.
     *
     * Fungsi ini berjalan di **background thread** yang dikelola WorkManager,
     * sehingga aman untuk melakukan operasi blocking (seperti HTTP request sinkronus).
     *
     * @return [Result.success] jika pekerjaan berhasil, [Result.failure] jika gagal,
     *         atau [Result.retry] jika pekerjaan perlu dicoba ulang.
     */
    override fun doWork(): Result {
        // Mengambil nama kota yang dikirim dari MainActivity melalui Data.Builder.
        val dataCity = inputData.getString(EXTRA_CITY)
        return getCurrentWeatherCity(dataCity)
    }

    /**
     * Melakukan HTTP GET ke API OpenWeatherMap untuk mendapatkan data cuaca terkini.
     *
     * ## Mengapa `SyncHttpClient`?
     * WorkManager menjalankan `doWork()` di background thread, sehingga kita bisa
     * menggunakan HTTP client **sinkronus** dengan aman. Menggunakan `AsyncHttpClient`
     * (asinkronus) di sini akan berbahaya karena `doWork()` bisa selesai *sebelum*
     * callback HTTP dipanggil, menyebabkan WorkManager menganggap task sudah selesai
     * padahal request belum selesai.
     *
     * ## Mengapa `Looper.prepare()`?
     * `SyncHttpClient` dari library `android-async-http` secara internal mencoba
     * mengakses `Looper` untuk mengantarkan callback. Background thread WorkManager
     * tidak memiliki `Looper` secara default, sehingga `Looper.prepare()` diperlukan
     * untuk menginisialisasinya agar tidak terjadi `RuntimeException`.
     *
     * ## Parsing Response dengan Moshi:
     * Response JSON di-parse menggunakan **Moshi** (bukan `JSONObject` manual) karena
     * lebih type-safe dan mengurangi risiko typo saat mengakses field JSON.
     * Model data-nya didefinisikan di **[Response]**.
     *
     * @param city Nama kota yang akan dicari datanya. Bisa `null` jika data tidak dikirim.
     * @return [Result.success] atau [Result.failure] tergantung hasil eksekusi.
     */
    private fun getCurrentWeatherCity(city: String?): Result {
        Log.d(TAG, "getCurrentWeather: Mulai.....")
        // Menginisialisasi Looper untuk thread ini agar callback SyncHttpClient
        // bisa dikirim dan diproses dengan benar.
        Looper.prepare()

        val client = SyncHttpClient()
        // URL API OpenWeatherMap. Parameter `q` adalah nama kota, `appid` adalah API Key.
        val url = "https://api.openweathermap.org/data/2.5/weather?q=$city&appid=$API_KEY"
        Log.d(TAG, "getCurrentWeather: $url")

        client.post(url, object : AsyncHttpResponseHandler() {
            /**
             * Dipanggil ketika server memberikan response sukses (HTTP 2xx).
             * `responseBody` berisi raw JSON dalam bentuk ByteArray.
             */
            override fun onSuccess(
                statusCode: Int, headers: Array<Header?>?, responseBody: ByteArray
            ) {
                // Mengubah ByteArray menjadi String UTF-8 untuk bisa di-parse.
                val result = String(responseBody)
                Log.d(TAG, result)

                try {
//                    val responseObject = JSONObject(result)
//                    val currentWeather: String =
//                        responseObject.getJSONArray("weather").getJSONObject(0).getString("main")
//                    val description: String =
//                        responseObject.getJSONArray("weather").getJSONObject(0).getString("description")
//                    val tempInKelvin = responseObject.getJSONObject("main").getDouble("temp")

                    // Membangun instance Moshi dengan `KotlinJsonAdapterFactory` agar
                    // Moshi bisa merefleksikan properti data class Kotlin secara otomatis.
                    val moshi = Moshi.Builder()
                        .addLast(KotlinJsonAdapterFactory())
                        .build()

                    // Membuat adapter yang tahu cara mengubah JSON menjadi objek [Response].
                    val jsonAdapter = moshi.adapter<Response>(Response::class.java)
                    val response = jsonAdapter.fromJson(result)

                    // `let` hanya dijalankan jika `response` tidak null,
                    // menghindarkan NullPointerException secara aman (null-safety Kotlin).
                    response?.let {
                        val currentWeather = it.weatherList[0].main
                        val description = it.weatherList[0].description
                        val tempInKelvin = it.main.temperature

                        // API OpenWeatherMap mengembalikan suhu dalam Kelvin.
                        // Rumus konversi: Celsius = Kelvin - 273.15 (disederhanakan menjadi 273).
                        val tempInCelsius = tempInKelvin - 273
                        val temperature: String = DecimalFormat("##.##").format(tempInCelsius)
                        val title = "Current Weather in $city"
                        val message = "$currentWeather, $description with $temperature celsius"
                        showNotification(title, message)
                    }

                    Log.d(TAG, "onSuccess: Selesai.....")

                    // Menandai task sebagai berhasil agar WorkManager tidak menjadwalkan ulang.
                    resultStatus = Result.success()
                } catch (e: Exception) {
                    // Jika parsing gagal atau data tidak sesuai ekspektasi, tampilkan
                    // notifikasi error dan tandai task sebagai gagal.
                    showNotification("Get Current Weather Not Success", e.message)
                    Log.d(TAG, "onSuccess: Gagal.....")
                    resultStatus = Result.failure()
                }
            }

            /**
             * Dipanggil ketika request gagal — bisa karena timeout, tidak ada jaringan,
             * atau server mengembalikan error (HTTP 4xx/5xx).
             */
            override fun onFailure(
                statusCode: Int,
                headers: Array<Header?>?,
                responseBody: ByteArray?,
                error: Throwable
            ) {
                Log.d(TAG, "onFailure: Gagal.....")
                // ketika proses gagal, maka jobFinished diset dengan parameter true. Yang artinya job perlu di reschedule
                showNotification("Get Current Weather Failed", error.message)
                // `Result.failure()` memberi tahu WorkManager bahwa task gagal permanen.
                // Gunakan `Result.retry()` jika ingin WorkManager mencoba kembali secara otomatis.
                resultStatus = Result.failure()
            }
        })

        // `resultStatus` pasti sudah di-set oleh salah satu callback di atas
        // karena SyncHttpClient bersifat blocking — fungsi ini tidak akan berlanjut
        // ke baris ini sebelum salah satu callback selesai dieksekusi.
        return resultStatus as Result
    }

    /**
     * Menampilkan notifikasi sistem dengan judul dan isi pesan yang diberikan.
     *
     * ## Mengapa NotificationChannel?
     * Sejak Android 8.0 (API 26 / OREO), semua notifikasi **wajib** dimasukkan ke dalam
     * `NotificationChannel`. Channel ini memungkinkan pengguna mengatur preferensi notifikasi
     * per-kategori (suara, getaran, prioritas) dari pengaturan sistem.
     *
     * ## Mengapa `NotificationCompat`?
     * `NotificationCompat.Builder` dari `androidx.core` memastikan kompatibilitas
     * notifikasi lintas versi Android (backward compatible hingga API yang lebih lama)
     * tanpa perlu menulis kode berbeda untuk setiap versi.
     *
     * ## Hubungan dengan File Lain:
     * - **AndroidManifest.xml**: Izin `POST_NOTIFICATIONS` diperlukan di Android 13+ agar
     *   notifikasi bisa muncul. Izin ini diminta di **[MainActivity]** saat runtime.
     * - `CHANNEL_ID` dan `NOTIFICATION_ID` didefinisikan sebagai konstanta di `companion object`
     *   agar konsisten di seluruh kelas.
     *
     * @param title Judul notifikasi yang ditampilkan secara bold di bagian atas.
     * @param description Isi pesan notifikasi, bisa berupa info cuaca atau pesan error.
     *
     * ⚠️ NOTE: Memanggil `createNotificationChannel` berkali-kali aman — sistem Android
     * akan mengabaikannya jika channel dengan ID yang sama sudah ada, tanpa menimpa
     * pengaturan pengguna yang sudah ada.
     */
    private fun showNotification(title: String, description: String?) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Membangun notifikasi menggunakan NotificationCompat untuk kompatibilitas lintas versi.
        val notification: NotificationCompat.Builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_notifications_24)
            .setContentTitle(title)
            .setContentText(description)
            // PRIORITY_HIGH memastikan notifikasi muncul sebagai "heads-up" (popup di atas layar).
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // DEFAULT_ALL mengaktifkan suara, getaran, dan lampu LED default perangkat.
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        // NotificationChannel hanya tersedia dan wajib ada di Android 8.0 (API 26) ke atas.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            notification.setChannelId(CHANNEL_ID)
            notificationManager.createNotificationChannel(channel)
        }

        // Menampilkan notifikasi. Menggunakan NOTIFICATION_ID yang sama akan
        // memperbarui (update) notifikasi yang sudah ada, bukan membuat yang baru.
        notificationManager.notify(NOTIFICATION_ID, notification.build())
    }

}